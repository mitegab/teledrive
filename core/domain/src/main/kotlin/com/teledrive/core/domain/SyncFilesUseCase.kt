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
        var cursor = HistoryCursor()
        var pages = 0
        var filesSeen = 0
        while (true) {
            val page = gateway.getFilesPage(cursor, pageSize)
            pages++
            filesSeen += page.files.size
            repository.upsertFiles(page.files)
            val next = page.nextCursor ?: HistoryPagination.nextCursor(cursor, page)
            if (next == null) break
            cursor = next
        }
        return SyncSummary(pages = pages, filesSeen = filesSeen)
    }
}

data class SyncSummary(val pages: Int, val filesSeen: Int)

class SearchFilesUseCase(private val repository: FileIndexRepository) {
    operator fun invoke(query: FileQuery, sort: FileSort = FileSort()): Flow<List<StoredFile>> =
        repository.observeFiles(query, sort).map { it }
}
