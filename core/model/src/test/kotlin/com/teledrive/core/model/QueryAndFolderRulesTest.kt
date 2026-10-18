package com.teledrive.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class QueryAndFolderRulesTest {
    private val files = listOf(
        StoredFile(RemoteMessageId(1, 1), 11, "Zeta.pdf", "application/pdf", 200, 30, favorite = true),
        StoredFile(RemoteMessageId(1, 2), 12, "alpha.jpg", "image/jpeg", 100, 40),
        StoredFile(RemoteMessageId(1, 3), 13, "Amharic ሰነድ.txt", "text/plain", 50, 50),
    )

    @Test
    fun searchesCaptionNameAndKind() {
        
        assertEquals(3, files.queryFiles(FileQuery(text = "ሰነድ")).single().remote.messageId)
    }

    @Test
    fun filtersFavoritesAndType() {
        
        val result = files.queryFiles(FileQuery(kinds = setOf(FileKind.TEXT), favoritesOnly = false))
        assertEquals(listOf(3L), result.map { it.remote.messageId })
        assertEquals(listOf(1L), files.queryFiles(FileQuery(favoritesOnly = true)).map { it.remote.messageId })
    }

    @Test
    fun sortsByNameAndSize() {
        assertEquals(listOf("alpha.jpg", "Amharic ሰነድ.txt", "Zeta.pdf"), files.sortedFiles(FileSort(SortField.NAME, SortDirection.ASCENDING)).map { it.originalFilename })
        assertEquals(listOf(200L, 100L, 50L), files.sortedFiles(FileSort(SortField.SIZE, SortDirection.DESCENDING)).map { it.sizeBytes })
    }

    @Test
    fun rejectsFolderCyclesAndMissingDestinations() {
        val folders = mapOf(
            "root" to Folder("root", "Root", null, 1, 1),
            "child" to Folder("child", "Child", "root", 1, 1),
        )
        assertIs<FolderMoveResult.Rejected>(FolderRules.validateMove("root", "child", folders))
        assertIs<FolderMoveResult.Rejected>(FolderRules.validateMove("child", "missing", folders))
        assertEquals(FolderMoveResult.Allowed, FolderRules.validateMove("child", null, folders))
    }
}
