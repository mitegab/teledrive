package com.teledrive.core.domain

import com.teledrive.core.model.AuthorizationState
import com.teledrive.core.model.FileQuery
import com.teledrive.core.model.FileSort
import com.teledrive.core.model.FilesPage
import com.teledrive.core.model.Folder
import com.teledrive.core.model.HistoryCursor
import com.teledrive.core.model.MetadataMessagesPage
import com.teledrive.core.model.RemoteMessageId
import com.teledrive.core.model.SavedMessagesChat
import com.teledrive.core.model.StoredFile
import com.teledrive.core.model.TelegramUser
import com.teledrive.core.model.Transfer
import com.teledrive.core.model.TransferDirection
import com.teledrive.core.model.TransferId
import com.teledrive.core.model.TransferProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncFilesUseCaseTest {
    @Test
    fun followsCursorEvenWhenAnIntermediatePageHasNoSupportedFiles() = runBlocking {
        val repository = FakeRepository()
        val gateway = FakeGateway(
            mapOf(
                0L to listOf(FilesPage(emptyList(), HistoryCursor(10))),
                10L to listOf(FilesPage(listOf(file(11)), HistoryCursor(20))),
                20L to listOf(FilesPage(listOf(file(21)), null)),
            ),
        )

        val summary = SynchronizeFilesUseCase(gateway, repository).invoke()

        assertTrue(summary.complete)
        assertEquals(listOf(10L, 20L, 0L), repository.savedCursors)
        assertEquals(listOf("42:11", "42:21"), repository.files.map(StoredFile::stableId))
        assertEquals(setOf("42:11", "42:21"), repository.reconciledRemoteIds)
    }

    @Test
    fun repeatedCursorStopsWithoutResettingTheCheckpoint() = runBlocking {
        val repository = FakeRepository()
        val gateway = FakeGateway(
            mapOf(
                0L to listOf(FilesPage(listOf(file(10)), HistoryCursor(5))),
                5L to listOf(FilesPage(listOf(file(5)), HistoryCursor(5))),
            ),
        )

        val summary = SynchronizeFilesUseCase(gateway, repository).invoke()

        assertFalse(summary.complete)
        assertEquals(listOf(5L, 5L), repository.savedCursors)
        assertEquals(5L, repository.cursor.fromMessageId)
        assertEquals(listOf("42:10", "42:5"), repository.files.map(StoredFile::stableId))
    }

    @Test
    fun emptyHistoryScanNeverReconcilesDeletions() = runBlocking {
        val repository = FakeRepository()
        val gateway = FakeGateway(mapOf(0L to listOf(FilesPage(emptyList(), null))))

        val summary = SynchronizeFilesUseCase(gateway, repository).invoke()

        assertTrue(summary.complete)
        assertEquals(0, summary.messagesSeen)
        assertEquals(0, repository.reconcileCalls)
        assertTrue(repository.reconciledRemoteIds.isEmpty())
        // A full scan is attempted again next time; the checkpoint still resets.
        assertEquals(0L, repository.cursor.fromMessageId)
    }

    @Test
    fun shortFirstPageIsRetriedAndTheFullestAnswerIsKept() = runBlocking {
        val repository = FakeRepository()
        val gateway = FakeGateway(
            mapOf(
                0L to listOf(
                    FilesPage(listOf(file(10)), HistoryCursor(5), messageCount = 1),
                    FilesPage(listOf(file(10), file(9), file(8)), HistoryCursor(5), messageCount = 3),
                ),
                5L to listOf(FilesPage(listOf(file(5)), null, messageCount = 1)),
            ),
        )

        val summary = SynchronizeFilesUseCase(gateway, repository).invoke(pageSize = 3)

        assertTrue(summary.complete)
        assertTrue("expected a retry of the short first page, got ${gateway.calls}", gateway.calls.count { it == 0L } >= 2)
        assertEquals(listOf("42:10", "42:9", "42:8", "42:5"), repository.files.map(StoredFile::stableId))
        assertEquals(setOf("42:10", "42:9", "42:8", "42:5"), repository.reconciledRemoteIds)
    }

    @Test
    fun emptyPageIsRetriedBeforeTheScanIsDeclaredComplete() = runBlocking {
        val repository = FakeRepository()
        val gateway = FakeGateway(
            mapOf(
                0L to listOf(FilesPage(listOf(file(50)), HistoryCursor(30), messageCount = 1)),
                // The second page answers empty at first (cold message database),
                // then delivers the older message on retry.
                30L to listOf(
                    FilesPage(emptyList(), null, messageCount = 0),
                    FilesPage(emptyList(), null, messageCount = 0),
                    FilesPage(listOf(file(12)), HistoryCursor(5), messageCount = 1),
                ),
                5L to listOf(FilesPage(emptyList(), null, messageCount = 0)),
            ),
        )

        val summary = SynchronizeFilesUseCase(gateway, repository).invoke()

        assertTrue(summary.complete)
        assertTrue("expected retries at cursor 30, got ${gateway.calls}", gateway.calls.count { it == 30L } >= 3)
        assertEquals(listOf("42:50", "42:12"), repository.files.map(StoredFile::stableId))
        assertEquals(setOf("42:50", "42:12"), repository.reconciledRemoteIds)
    }

    @Test
    fun interruptedScanDoesNotReconcileDeletions() = runBlocking {
        val repository = FakeRepository()
        val gateway = FakeGateway(
            mapOf(
                0L to listOf(FilesPage(listOf(file(30)), HistoryCursor(20), messageCount = 1)),
                20L to listOf(FilesPage(listOf(file(20)), HistoryCursor(10), messageCount = 1)),
                10L to listOf(FilesPage(listOf(file(10)), null, messageCount = 1)),
            ),
        ).also { it.failingCursors += 10L }

        var failed = false
        try {
            SynchronizeFilesUseCase(gateway, repository).invoke()
        } catch (error: Throwable) {
            failed = true
        }

        assertTrue(failed)
        assertEquals(0, repository.reconcileCalls)
    }

    @Test
    fun scanEmitsPageAndCompletionDiagnostics() = runBlocking {
        val repository = FakeRepository()
        val gateway = FakeGateway(
            mapOf(
                0L to listOf(FilesPage(listOf(file(20)), HistoryCursor(10), messageCount = 2)),
                10L to listOf(FilesPage(listOf(file(9)), HistoryCursor(3), messageCount = 1)),
                3L to listOf(FilesPage(emptyList(), null, messageCount = 0)),
            ),
        )
        val lines = mutableListOf<String>()
        com.teledrive.core.model.TeleDriveLog.sink = { lines += it }
        try {
            SynchronizeFilesUseCase(gateway, repository).invoke()
        } finally {
            com.teledrive.core.model.TeleDriveLog.sink = null
        }

        val pageLogs = lines.filter { it.startsWith("sync.page") }
        assertEquals(3, pageLogs.size)
        assertTrue("page log should carry counts: ${pageLogs.first()}", pageLogs.first().contains("messages=2"))
        assertTrue(pageLogs.first().contains("files=1"))
        assertTrue(pageLogs.first().contains("next_cursor=10"))
        val done = lines.single { it.startsWith("sync.done") }
        assertTrue("done log: $done", done.contains("complete=true"))
        assertTrue(done.contains("reconciled=true"))
    }

    private fun file(messageId: Long) = StoredFile(
        remote = RemoteMessageId(42, messageId),
        tdlibFileId = messageId.toInt(),
        originalFilename = "$messageId.pdf",
        mimeType = "application/pdf",
        sizeBytes = 1,
        savedAtEpochSeconds = messageId,
    )
}

private class FakeRepository : FileIndexRepository {
    val files = mutableListOf<StoredFile>()
    val savedCursors = mutableListOf<Long>()
    val reconciledRemoteIds = mutableSetOf<String>()
    var reconcileCalls = 0
    var cursor = HistoryCursor()
    private val fileFlow = MutableStateFlow<List<StoredFile>>(emptyList())

    override fun observeFiles(query: FileQuery, sort: FileSort): Flow<List<StoredFile>> = fileFlow
    override fun observeFile(remoteId: String): Flow<StoredFile?> = emptyFlow()
    override fun observeFolders(): Flow<List<Folder>> = emptyFlow()
    override fun observeTransfers(): Flow<List<Transfer>> = emptyFlow()

    override suspend fun upsertFiles(files: List<StoredFile>) {
        files.forEach { file ->
            filesForTest()[file.stableId] = file
        }
        this.files.clear()
        this.files.addAll(filesForTest().values)
        fileFlow.value = this.files.toList()
    }

    private fun filesForTest(): MutableMap<String, StoredFile> = storedFiles
    private val storedFiles = linkedMapOf<String, StoredFile>()

    override suspend fun getHistoryCursor(): HistoryCursor = cursor
    override suspend fun saveHistoryCursor(cursor: HistoryCursor) {
        this.cursor = cursor
        savedCursors += cursor.fromMessageId
    }

    override suspend fun updateFile(file: StoredFile) = Unit
    override suspend fun reconcileRemoteFiles(seenRemoteIds: Set<String>) {
        reconcileCalls++
        reconciledRemoteIds += seenRemoteIds
    }
    override suspend fun deleteLocalFile(remoteId: String) = Unit
    override suspend fun saveLocalCopy(remoteId: String, localPath: String, sizeBytes: Long?, offlineSinceEpochSeconds: Long) = Unit
    override suspend fun setFolder(remoteId: String, folderId: String?) = Unit
    override suspend fun setFavorite(remoteId: String, favorite: Boolean) = Unit
    override suspend fun createFolder(folder: Folder) = Unit
    override suspend fun upsertFolder(folder: Folder) = Unit
    override suspend fun renameFolder(folderId: String, name: String, modifiedAtEpochSeconds: Long) = Unit
    override suspend fun deleteFolder(folderId: String) = Unit
    override suspend fun moveFolder(folderId: String, parentId: String?, modifiedAtEpochSeconds: Long) = Unit
    override suspend fun upsertTransfer(transfer: Transfer) = Unit
    override suspend fun updateTransfer(transfer: Transfer) = Unit
    override suspend fun getTransfer(id: String): Transfer? = null
}

/**
 * Serves scripted answers per cursor: each cursor has a list of pages consumed in
 * order (the last one repeats), so retries can observe a "warming up" TDLib.
 * Cursors listed in [failingCursors] throw, like a failed TDLib request.
 */
private class FakeGateway(
    pages: Map<Long, List<FilesPage>>,
) : TelegramStorageGateway {
    val calls = mutableListOf<Long>()
    val failingCursors = mutableSetOf<Long>()
    private val queues = pages.mapValues { (_, answers) -> ArrayDeque(answers) }
    override val authorizationState = MutableStateFlow<AuthorizationState>(AuthorizationState.Ready)
    override val transferUpdates: Flow<TransferProgress> = emptyFlow()

    override suspend fun start() = Unit
    override suspend fun submitPhoneNumber(phoneNumber: String) = Unit
    override suspend fun submitCode(code: String) = Unit
    override suspend fun submitPassword(password: String) = Unit
    override suspend fun submitEmailAddress(emailAddress: String) = Unit
    override suspend fun submitEmailCode(code: String) = Unit
    override suspend fun cancelAuthorization() = Unit
    override suspend fun logOut() = Unit
    override suspend fun getCurrentUser() = TelegramUser(1, "Test", "User")
    override suspend fun getSavedMessagesChat() = SavedMessagesChat(42, "Saved Messages")
    override suspend fun getFilesPage(cursor: HistoryCursor, limit: Int): FilesPage {
        calls += cursor.fromMessageId
        if (cursor.fromMessageId in failingCursors) error("TDLib request failed mid-scan")
        val queue = queues[cursor.fromMessageId] ?: return FilesPage(emptyList(), null, messageCount = 0)
        return if (queue.size <= 1) queue.first() else queue.removeFirst()
    }
    override suspend fun getMetadataMessagesPage(cursor: HistoryCursor, limit: Int) = MetadataMessagesPage(emptyList(), null)
    override suspend fun uploadDocument(request: UploadRequest) = error("unused")
    override suspend fun downloadFile(request: DownloadRequest) = error("unused")
    override suspend fun cancelTransfer(transferId: TransferId, direction: TransferDirection, tdlibFileId: Int?) = Unit
    override suspend fun deleteRemoteMessage(remote: RemoteMessageId, revoke: Boolean) = Unit
    override suspend fun sendMetadataRecord(text: String) = RemoteMessageId(42, 1)
    override suspend fun close() = Unit
}
