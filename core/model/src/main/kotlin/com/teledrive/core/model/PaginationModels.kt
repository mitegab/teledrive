package com.teledrive.core.model

data class HistoryCursor(val fromMessageId: Long = 0L)

data class FilesPage(
    val files: List<StoredFile>,
    val nextCursor: HistoryCursor?,
    val approximateTotalCount: Int? = null,
)

data class MetadataMessagesPage(
    val records: List<String>,
    val nextCursor: HistoryCursor?,
)

object HistoryPagination {
    fun nextCursor(current: HistoryCursor, page: FilesPage): HistoryCursor? {
        if (page.files.isEmpty()) return null
        val lastMessageId = page.files.minOfOrNull { it.remote.messageId } ?: return null
        if (lastMessageId <= 0L || lastMessageId >= current.fromMessageId && current.fromMessageId != 0L) return null
        return HistoryCursor(lastMessageId)
    }
}
