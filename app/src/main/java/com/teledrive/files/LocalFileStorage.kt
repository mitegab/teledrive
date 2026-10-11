package com.teledrive.files

import android.content.Context
import android.net.Uri
import com.teledrive.core.model.FilenameRules
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        if (!source.isFile) throw IOException("TDLib completed without a readable local file")
        if (expectedSizeBytes != null && expectedSizeBytes >= 0L && source.length() != expectedSizeBytes) {
            throw IOException("The downloaded file size does not match Telegram metadata")
        }
        val directory = downloadsDirectory.resolve(stableDirectory(stableId)).apply { mkdirs() }
        val safeName = FilenameRules.sanitize(displayName)
        val finalFile = chooseFinalFile(directory, safeName, source.length())
        if (finalFile.isFile && finalFile.length() == source.length()) return@withContext finalFile.absolutePath

        val temporary = directory.resolve(".${UUID.randomUUID()}.part")
        try {
            source.inputStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    output.fd.sync()
                }
            }
            if (temporary.length() != source.length()) throw IOException("The local download was truncated")
            if (!temporary.renameTo(finalFile)) throw IOException("Unable to publish the downloaded file")
            finalFile.absolutePath
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    fun isAvailable(path: String?, expectedSizeBytes: Long?): Boolean {
        val file = managedFile(path) ?: return false
        if (!file.isFile) return false
        return expectedSizeBytes == null || expectedSizeBytes < 0L || file.length() == expectedSizeBytes
    }

    suspend fun remove(path: String?) = withContext(Dispatchers.IO) {
        managedFile(path)?.takeIf(File::isFile)?.delete()
    }

    fun isStagedSource(path: String?): Boolean = stagedFile(path)?.isFile == true

    suspend fun removeStagedSource(path: String?) = withContext(Dispatchers.IO) {
        stagedFile(path)?.takeIf(File::isFile)?.delete()
    }

    suspend fun cleanupTemporaryFiles(maxAgeMillis: Long = 24 * 60 * 60 * 1000L) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        sequenceOf(downloadsDirectory, stagingDirectory).forEach { root ->
            root.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".part") && it.lastModified() < cutoff }
                .forEach(File::delete)
        }
    }

    suspend fun storageUsageBytes(): Long = withContext(Dispatchers.IO) {
        downloadsDirectory.walkTopDown().filter(File::isFile).sumOf(File::length)
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

    private fun stagedFile(path: String?): File? {
        if (path.isNullOrBlank()) return null
        val root = runCatching { stagingDirectory.canonicalFile }.getOrNull() ?: return null
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        return file.takeIf {
            it.isFile && it.name.endsWith(".part") && it.path.startsWith(root.path + File.separator)
        }
    }

    private fun stableDirectory(stableId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(stableId.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val DOWNLOAD_DIRECTORY = "downloads"
        const val STAGING_DIRECTORY = "staging"
    }
}
