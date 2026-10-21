package com.teledrive.core.model

fun Iterable<StoredFile>.queryFiles(query: FileQuery): List<StoredFile> {
    val normalizedText = query.text.trim().lowercase()
    return filter { file ->
        val textMatches = normalizedText.isBlank() || listOfNotNull(
            file.originalFilename,
            file.mimeType,
            file.caption,
            file.kind.name,
        ).any { it.lowercase().contains(normalizedText) }
        val kindMatches = query.kinds.isEmpty() || file.kind in query.kinds
        val folderMatches = query.folderId == null || file.folderId == query.folderId
        val favoriteMatches = !query.favoritesOnly || file.favorite
        val localMatches = !query.localOnly || file.localAvailability == LocalAvailability.AVAILABLE
        val sizeMatches = (query.minSizeBytes == null || (file.sizeBytes ?: Long.MIN_VALUE) >= query.minSizeBytes) &&
            (query.maxSizeBytes == null || (file.sizeBytes ?: Long.MAX_VALUE) <= query.maxSizeBytes)
        val dateMatches = (query.minSavedAtEpochSeconds == null || file.savedAtEpochSeconds >= query.minSavedAtEpochSeconds) &&
            (query.maxSavedAtEpochSeconds == null || file.savedAtEpochSeconds <= query.maxSavedAtEpochSeconds)
        textMatches && kindMatches && folderMatches && favoriteMatches && localMatches && sizeMatches && dateMatches
    }
}

fun Iterable<StoredFile>.sortedFiles(sort: FileSort): List<StoredFile> {
    val comparator = when (sort.field) {
        SortField.NAME -> compareBy<StoredFile> { it.originalFilename.lowercase() }.thenBy { it.stableId }
        SortField.SIZE -> compareBy<StoredFile> { it.sizeBytes ?: Long.MIN_VALUE }.thenBy { it.originalFilename.lowercase() }
        SortField.DATE -> compareBy<StoredFile> { it.savedAtEpochSeconds }.thenBy { it.stableId }
        SortField.TYPE -> compareBy<StoredFile> { it.kind.name }.thenBy { it.originalFilename.lowercase() }
    }
    return when (sort.direction) {
        SortDirection.ASCENDING -> sortedWith(comparator)
        SortDirection.DESCENDING -> sortedWith(comparator.reversed())
    }
}
