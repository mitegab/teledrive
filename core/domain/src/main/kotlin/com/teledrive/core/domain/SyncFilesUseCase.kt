package com.teledrive.core.domain

import com.teledrive.core.model.FileQuery
import com.teledrive.core.model.FileSort
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
        var complete = false
        while (true) {
            val page = gateway.getFilesPage(cursor, pageSize)
            pages++
            filesSeen += page.files.size
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
        if (complete && fullHistoryScan) repository.reconcileRemoteFiles(seenRemoteIds)
        repository.saveHistoryCursor(if (complete) HistoryCursor() else cursor)
        return SyncSummary(pages = pages, filesSeen = filesSeen, complete = complete)
    }

    private companion object {
        const val MAX_PAGES = 10_000
    }
}

data class SyncSummary(val pages: Int, val filesSeen: Int, val complete: Boolean = true)

class SearchFilesUseCase(private val repository: FileIndexRepository) {
    operator fun invoke(query: FileQuery, sort: FileSort = FileSort()): Flow<List<StoredFile>> =
        repository.observeFiles(query, sort).map { it }
}
