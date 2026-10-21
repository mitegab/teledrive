package com.teledrive.files

import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.teledrive.core.model.AppError
import com.teledrive.core.model.AppErrorMapper
import com.teledrive.core.model.ErrorCategory
import com.teledrive.core.model.ErrorOperation
import com.teledrive.core.model.FilenameRules
import com.teledrive.core.model.LocalFileProbe
import com.teledrive.core.model.TeleDriveLog
import com.teledrive.core.model.UploadStagingRules
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reason a local copy could not be created or validated. */
enum class LocalStorageFailure {
    SOURCE_MISSING,
    SOURCE_UNREADABLE,
    TRUNCATED,
    SIZE_MISMATCH,
    OUT_OF_SPACE,
    WRITE_FAILED,
    PUBLISH_FAILED,
    NOT_MANAGED,
}

class LocalStorageException(
    val failure: LocalStorageFailure,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Maps a local storage failure onto the app error model without leaking paths. */
fun LocalStorageException.toAppError(operation: ErrorOperation): AppError = when (failure) {
    LocalStorageFailure.OUT_OF_SPACE -> AppError(
        category = ErrorCategory.STORAGE_FULL,
        operation = operation,
        userMessage = com.teledrive.core.model.AppErrorMessages.forCategory(ErrorCategory.STORAGE_FULL),
        retryable = false,
        diagnosticCode = failure.name,
    )
    LocalStorageFailure.SOURCE_MISSING, LocalStorageFailure.SOURCE_UNREADABLE -> AppErrorMapper.localFileMissing(operation)
    LocalStorageFailure.TRUNCATED, LocalStorageFailure.SIZE_MISMATCH -> AppError(
        category = ErrorCategory.CORRUPT_FILE,
        operation = operation,
        userMessage = com.teledrive.core.model.AppErrorMessages.forCategory(ErrorCategory.CORRUPT_FILE),
        retryable = true,
        diagnosticCode = failure.name,
    )
    LocalStorageFailure.NOT_MANAGED -> AppError(
        category = ErrorCategory.LOCAL_FILE_INACCESSIBLE,
        operation = operation,
        userMessage = com.teledrive.core.model.AppErrorMessages.forCategory(ErrorCategory.LOCAL_FILE_INACCESSIBLE),
        retryable = false,
        diagnosticCode = failure.name,
    )
    // Writing or publishing the verified copy is a problem with this device's storage, not
    // with Telegram. Reporting it as a download/connection failure sent users to the wrong fix.
    LocalStorageFailure.WRITE_FAILED, LocalStorageFailure.PUBLISH_FAILED -> AppError(
        category = ErrorCategory.LOCAL_FILE_INACCESSIBLE,
        operation = operation,
        userMessage = "TeleDrive couldn't save the downloaded file on this device. Check free space and try again.",
        retryable = true,
        diagnosticCode = failure.name,
        diagnosticCause = AppErrorMapper.sanitizeCause(message),
    )
}

/** App-private, durable copies separate from TDLib's own cache and from user-exported files. */
class LocalFileStorage @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val downloadsDirectory = context.filesDir.resolve(DOWNLOAD_DIRECTORY).apply { mkdirs() }
    private val stagingDirectory = context.filesDir.resolve(STAGING_DIRECTORY).apply { mkdirs() }

    suspend fun importCompleted(
        sourcePath: String,
        stableId: String,
        displayName: String,
        expectedSizeBytes: Long?,
    ): String = withContext(Dispatchers.IO) {
        val source = File(sourcePath)
        if (!source.isFile) throw LocalStorageException(LocalStorageFailure.SOURCE_MISSING, "Telegram finished without a readable local file")
        if (!source.canRead()) throw LocalStorageException(LocalStorageFailure.SOURCE_UNREADABLE, "Telegram's local copy cannot be read")
        val sourceLength = source.length()
        // TDLib reports size = 0 when the size is still unknown, so only a positive expectation
        // can disprove the bytes; a 0-byte source is rejected by the TRUNCATED check below.
        if (expectedSizeBytes != null && expectedSizeBytes > 0L && sourceLength != expectedSizeBytes) {
            throw LocalStorageException(LocalStorageFailure.SIZE_MISMATCH, "The downloaded file size does not match Telegram metadata")
        }
        if (sourceLength <= 0L) throw LocalStorageException(LocalStorageFailure.TRUNCATED, "The downloaded file is empty")
        val directory = downloadsDirectory.resolve(stableDirectory(stableId)).apply { mkdirs() }
        val safeName = FilenameRules.sanitize(displayName)
        val finalFile = chooseFinalFile(directory, safeName, sourceLength)
        if (finalFile.isFile && finalFile.length() == sourceLength) return@withContext finalFile.absolutePath

        // The copy doubles the footprint momentarily; verify there is room for it.
        if (availableBytes() < sourceLength) {
            throw LocalStorageException(LocalStorageFailure.OUT_OF_SPACE, "Not enough free space to keep this download")
        }

        val temporary = directory.resolve(".${UUID.randomUUID()}.part")
        try {
            source.inputStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    output.fd.sync()
                }
            }
            if (temporary.length() != sourceLength) throw LocalStorageException(LocalStorageFailure.TRUNCATED, "The local download was truncated")
            if (!temporary.renameTo(finalFile)) throw LocalStorageException(LocalStorageFailure.PUBLISH_FAILED, "Unable to publish the downloaded file")
            finalFile.absolutePath
        } catch (error: LocalStorageException) {
            temporary.delete()
            throw error
        } catch (error: IOException) {
            temporary.delete()
            val storageFull = error.message?.contains("ENOSPC", ignoreCase = true) == true || availableBytes() < sourceLength
            throw LocalStorageException(
                if (storageFull) LocalStorageFailure.OUT_OF_SPACE else LocalStorageFailure.WRITE_FAILED,
                if (storageFull) "Not enough free space to keep this download" else "The download could not be written",
                error,
            )
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    /**
     * Free bytes on the volume that holds app-private storage. Returns 0 when the
     * filesystem cannot be queried, which callers treat as "no space": download code
     * must fail closed rather than start a copy that is guaranteed to run out of room.
     */
    fun availableBytes(): Long = try {
        StatFs(downloadsDirectory.absolutePath).availableBytes
    } catch (error: Exception) {
        TeleDriveLog.d("storage.statfs_failed", "error" to error.message)
        0L
    }

    /** Inspects a recorded offline path without trusting the database flag. */
    fun probe(path: String?, expectedSizeBytes: Long? = null): LocalFileProbe {
        val file = managedFile(path) ?: return LocalFileProbe.Missing
        if (!file.isFile) return LocalFileProbe.Missing
        val readable = file.canRead()
        val size = try {
            file.length()
        } catch (error: Exception) {
            // Unreadable metadata is a real (if unusual) local failure, not "missing".
            TeleDriveLog.d("storage.probe_failed", "error" to error.message)
            null
        }
        return LocalFileProbe(exists = true, sizeBytes = size, readable = readable)
    }

    /** Reads the first bytes of a managed file so PDFs can be validated before rendering. */
    fun readHeader(path: String?, maxBytes: Int): ByteArray? {
        val file = managedFile(path) ?: return null
        if (!file.isFile || !file.canRead()) return null
        return try {
            file.inputStream().use { input ->
                val buffer = ByteArray(maxBytes)
                val read = input.read(buffer)
                if (read <= 0) ByteArray(0) else buffer.copyOf(read)
            }
        } catch (error: Exception) {
            // The PDF preflight reports this as an unreadable file instead of guessing.
            TeleDriveLog.d("storage.header_read_failed", "error" to error.message)
            null
        }
    }

    /** Deletes only temporary artifacts for a stable id, never published copies. */
    suspend fun discardPartialFiles(stableId: String) = withContext(Dispatchers.IO) {
        val directory = downloadsDirectory.resolve(stableDirectory(stableId))
        if (directory.isDirectory) {
            directory.listFiles()?.filter { it.isFile && it.name.endsWith(".part") }?.forEach(File::delete)
        }
    }

    fun isAvailable(path: String?, expectedSizeBytes: Long?): Boolean {
        val file = managedFile(path) ?: return false
        if (!file.isFile) return false
        return expectedSizeBytes == null || expectedSizeBytes < 0L || file.length() == expectedSizeBytes
    }

    suspend fun remove(path: String?): Boolean = withContext(Dispatchers.IO) {
        managedFile(path)?.takeIf(File::isFile)?.delete() ?: false
    }

    fun isStagedSource(path: String?): Boolean = UploadStagingRules.isStagedSource(stagingDirectory, path)

    suspend fun removeStagedSource(path: String?) = withContext(Dispatchers.IO) {
        UploadStagingRules.removeStagedSource(stagingDirectory, path)
    }

    suspend fun cleanupTemporaryFiles(maxAgeMillis: Long = 24 * 60 * 60 * 1000L) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        downloadsDirectory.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".part") && it.lastModified() < cutoff }
            .forEach(File::delete)
        UploadStagingRules.cleanup(stagingDirectory, cutoff)
    }

    suspend fun storageUsageBytes(): Long = withContext(Dispatchers.IO) {
        downloadsDirectory.walkTopDown().filter(File::isFile).sumOf(File::length)
    }

    /** Deletes every app-managed offline copy (the Room rows are cleared separately). */
    suspend fun clearManagedFiles(): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        downloadsDirectory.listFiles()?.forEach { child ->
            freed += child.walkTopDown().filter(File::isFile).sumOf(File::length)
            child.deleteRecursively()
        }
        freed
    }

    suspend fun export(path: String, destination: Uri, context: Context) = withContext(Dispatchers.IO) {
        val source = managedFile(path)
            ?: throw IOException("The local cached file is outside app-managed storage")
        if (!source.isFile) throw IOException("The local cached file is no longer available")
        context.contentResolver.openOutputStream(destination, "wt")?.use { output ->
            source.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        } ?: throw IOException("The selected destination could not be opened")
    }

    private fun chooseFinalFile(directory: File, safeName: String, size: Long): File {
        val first = directory.resolve(safeName)
        if (!first.exists() || first.length() == size) return first
        val extension = safeName.substringAfterLast('.', "").takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()
        val stem = if (extension.isEmpty()) safeName else safeName.removeSuffix(extension)
        var index = 1
        while (true) {
            val candidate = directory.resolve("$stem ($index)$extension")
            if (!candidate.exists() || candidate.length() == size) return candidate
            index++
        }
    }

    private fun managedFile(path: String?): File? {
        if (path.isNullOrBlank()) return null
        val root = runCatching { downloadsDirectory.canonicalFile }.getOrNull() ?: return null
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        return file.takeIf { it.path == root.path || it.path.startsWith(root.path + File.separator) }
    }

    private fun stableDirectory(stableId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(stableId.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val DOWNLOAD_DIRECTORY = "downloads"
        const val STAGING_DIRECTORY = "staging"
    }
}
