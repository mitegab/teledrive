package com.teledrive.core.model

sealed interface FolderMoveResult {
    data object Allowed : FolderMoveResult
    data class Rejected(val reason: String) : FolderMoveResult
}

object FolderRules {
    fun normalizeName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ").take(120)

    fun validateName(raw: String, existingNames: Collection<String> = emptyList()): String? {
        val normalized = normalizeName(raw)
        if (normalized.isBlank()) return "Folder name cannot be empty"
        if (existingNames.any { it.equals(normalized, ignoreCase = true) }) return "A folder with this name already exists"
        return null
    }

    fun validateMove(folderId: String, targetParentId: String?, folders: Map<String, Folder>): FolderMoveResult {
        if (targetParentId == folderId) return FolderMoveResult.Rejected("A folder cannot contain itself")
        if (targetParentId == null) return FolderMoveResult.Allowed
        if (targetParentId !in folders) return FolderMoveResult.Rejected("The destination folder no longer exists")

        var cursor: String? = targetParentId
        val seen = mutableSetOf<String>()
        while (cursor != null) {
            if (!seen.add(cursor)) return FolderMoveResult.Rejected("Folder hierarchy contains a cycle")
            if (cursor == folderId) return FolderMoveResult.Rejected("A folder cannot be moved into its own descendant")
            cursor = folders[cursor]?.parentId
        }
        return FolderMoveResult.Allowed
    }
}
