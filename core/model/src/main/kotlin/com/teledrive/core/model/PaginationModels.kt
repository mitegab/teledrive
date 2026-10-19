package com.teledrive.core.model

data class HistoryCursor(val fromMessageId: Long = 0L)

data class FilesPage(
    val files: List<StoredFile>,
    val nextCursor: HistoryCursor?,
    val approximateTotalCount: Int? = null,
    /**
     * Raw messages this page covered, including metadata/text messages that are
     * not user files. 0 means TDLib answered with no messages at all, which is
     * the only reliable end-of-history signal; short pages can occur earlier.
     */
    val messageCount: Int = files.size,
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
