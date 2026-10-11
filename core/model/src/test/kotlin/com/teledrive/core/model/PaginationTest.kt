package com.teledrive.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PaginationTest {
    @Test
    fun advancesToTheOldestMessageAndStopsAtEmptyPage() {
        val page = FilesPage(
            files = listOf(
                StoredFile(RemoteMessageId(1, 90), 1, "a", null, 1, 1),
                StoredFile(RemoteMessageId(1, 80), 2, "b", null, 1, 1),
            ),
            nextCursor = null,
        )
        assertEquals(HistoryCursor(80), HistoryPagination.nextCursor(HistoryCursor(), page))
        assertNull(HistoryPagination.nextCursor(HistoryCursor(80), page))
        assertNull(HistoryPagination.nextCursor(HistoryCursor(), FilesPage(emptyList(), null)))
    }
}
