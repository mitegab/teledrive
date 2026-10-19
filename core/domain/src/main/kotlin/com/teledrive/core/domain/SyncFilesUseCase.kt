package com.teledrive.core.domain

import com.teledrive.core.model.FileQuery
import com.teledrive.core.model.FileSort
import com.teledrive.core.model.FilesPage
import com.teledrive.core.model.HistoryCursor
import com.teledrive.core.model.HistoryPagination
import com.teledrive.core.model.StoredFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ObserveFilesUseCase(private val repository: FileIndexRepository) {
    operator fun invoke(query: FileQuery = FileQuery(), sort: FileSort = FileSort()): Flow<List<StoredFile>> =
        repository.observeFiles(query, sort)
}

class SynchronizeFilesUseCase(
    private val gateway: TelegramStorageGateway,
    private val repository: FileIndexRepository,
) {
    suspend operator fun invoke(pageSize: Int = 100): SyncSummary {
        require(pageSize in 1..100) { "TDLib getChatHistory accepts at most 100 messages per page" }
        var cursor = repository.getHistoryCursor()
        val fullHistoryScan = cursor.fromMessageId == 0L
        val seenRemoteIds = mutableSetOf<String>()
        val visitedCursors = mutableSetOf(cursor.fromMessageId)
        var pages = 0
        var filesSeen = 0
        var messagesSeen = 0
        var complete = false
        while (true) {
            val page = stablePage(cursor, pageSize)
            pages++
            filesSeen += page.files.size
            messagesSeen += page.messageCount
            if (fullHistoryScan) page.files.forEach { seenRemoteIds += it.stableId }
            // Persist the page before advancing the checkpoint. A failed Room write leaves the
            // previous cursor available for a safe retry after process recreation.
            repository.upsertFiles(page.files)
            val next = page.nextCursor ?: HistoryPagination.nextCursor(cursor, page)
            if (next == null) {
                complete = true
                break
            }
            if (pages >= MAX_PAGES || next.fromMessageId == cursor.fromMessageId || !visitedCursors.add(next.fromMessageId)) break
            repository.saveHistoryCursor(next)
            cursor = next
        }
        // Absence is only provable after a completed full scan that actually saw messages.
        // An empty or error-interrupted scan must never mark remote files deleted.
        if (complete && fullHistoryScan && messagesSeen > 0) repository.reconcileRemoteFiles(seenRemoteIds)
        repository.saveHistoryCursor(if (complete) HistoryCursor() else cursor)
        return SyncSummary(pages = pages, filesSeen = filesSeen, complete = complete, messagesSeen = messagesSeen)
    }

    /**
     * TDLib's `getChatHistory` documents that the returned message count can be
     * smaller than `limit`, even before history is exhausted (for example while
     * the local message database is still warming up). A short page — especially
     * at the start of history — or an empty answer is re-asked a bounded number
     * of times and the fullest answer is kept, so a partial reply cannot end the
     * scan early.
     */
    private suspend fun stablePage(cursor: HistoryCursor, pageSize: Int): FilesPage {
        var best = gateway.getFilesPage(cursor, pageSize)
        if (best.messageCount >= pageSize) return best
        if (best.messageCount > 0 && best.nextCursor != null && cursor.fromMessageId != 0L) return best
        repeat(SHORT_ANSWER_RETRIES) {
            kotlinx.coroutines.delay(SHORT_ANSWER_RETRY_DELAY_MILLIS)
            val again = gateway.getFilesPage(cursor, pageSize)
            if (again.messageCount > best.messageCount) best = again
            if (best.messageCount >= pageSize) return best
        }
        return best
    }

    private companion object {
        const val MAX_PAGES = 10_000
        const val SHORT_ANSWER_RETRIES = 2
        const val SHORT_ANSWER_RETRY_DELAY_MILLIS = 200L
    }
}

data class SyncSummary(
    val pages: Int,
    val filesSeen: Int,
    val complete: Boolean = true,
    val messagesSeen: Int = 0,
)

class SearchFilesUseCase(private val repository: FileIndexRepository) {
    operator fun invoke(query: FileQuery, sort: FileSort = FileSort()): Flow<List<StoredFile>> =
        repository.observeFiles(query, sort).map { it }
}
