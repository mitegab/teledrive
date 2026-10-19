package com.teledrive.core.model

import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Layout rules for app-private upload staging.
 *
 * New entries are staged as `staging/<uuid>/<safe display name>` so the real
 * file name and extension reach TDLib: it derives the document name from the
 * `inputFileLocal` path. Older releases staged flat `upload-*.part` files whose
 * names leaked into Telegram; those paths remain valid sources until their
 * transfers settle, so existing installs need no data migration.
 */
object UploadStagingRules {
    private const val LEGACY_SUFFIX = ".part"
    private const val MAX_FILENAME_BYTES = 255

    /**
     * Creates `root/<entryId>/<safeName>` and returns the target file (not yet
     * written). Rejects any name or entry id that would escape the staging root.
     */
    fun createStagedTarget(root: File, safeName: String, entryId: String = UUID.randomUUID().toString()): File {
        if (!ENTRY_ID.matches(entryId)) {
            throw IOException("Invalid upload staging entry id")
        }
        val name = fitFileName(FilenameRules.displayName(safeName, mimeType = null))
        val canonicalRoot = root.canonicalFile
        val target = canonicalRoot.resolve(entryId).resolve(name).canonicalFile
        val insideEntryDirectory = target.parentFile?.parentFile == canonicalRoot
        if (!insideEntryDirectory || target.parentFile?.name != entryId) {
            throw IOException("Upload staging path escapes the staging directory")
        }
        target.parentFile?.mkdirs()
        return target
    }

    /** Returns the canonical staged file when [path] is a known staging source under [root]. */
    fun stagedSource(root: File, path: String?): File? {
        if (path.isNullOrBlank()) return null
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        val inside = file.path.startsWith(canonicalRoot.path + File.separator)
        if (!inside) return null
        val isLegacyFlatPart = file.parentFile == canonicalRoot && file.name.endsWith(LEGACY_SUFFIX)
        val isNamedEntry = file.parentFile?.parentFile == canonicalRoot
        return file.takeIf { isLegacyFlatPart || isNamedEntry }
    }

    fun isStagedSource(root: File, path: String?): Boolean = stagedSource(root, path)?.isFile == true

    /** Deletes the staged file; also removes its entry directory when it becomes empty. */
    fun removeStagedSource(root: File, path: String?) {
        val file = stagedSource(root, path) ?: return
        val canonicalRoot = root.canonicalFile
        val entryDirectory = file.parentFile?.takeIf { it != canonicalRoot && it.parentFile == canonicalRoot }
        if (file.isFile) file.delete()
        if (entryDirectory?.list()?.isEmpty() == true) entryDirectory.delete()
    }

    /** Removes staged leftovers older than [cutoffMillis]; fresh sources stay for retries. */
    fun cleanup(root: File, cutoffMillis: Long) {
        root.walkTopDown()
            .filter { it.isFile && it.lastModified() < cutoffMillis }
            .forEach(File::delete)
        root.walkBottomUp()
            .filter { it.isDirectory && it != root && it.list().isNullOrEmpty() }
            .forEach(File::delete)
    }

    /** Caps the UTF-8 byte length of a file name component while keeping its extension. */
    private fun fitFileName(name: String): String {
        if (name.toByteArray(Charsets.UTF_8).size <= MAX_FILENAME_BYTES) return name
        val extension = name.substringAfterLast('.', "")
        val suffix = if (extension.isEmpty() || extension == name) "" else ".$extension"
        var stem = name.removeSuffix(suffix)
        while (stem.isNotEmpty() && (stem + suffix).toByteArray(Charsets.UTF_8).size > MAX_FILENAME_BYTES) {
            stem = stem.dropLast(1)
        }
        return stem + suffix
    }

    private val ENTRY_ID = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,63}")
}
