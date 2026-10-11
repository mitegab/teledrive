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
                0L to FilesPage(emptyList(), HistoryCursor(10)),
                10L to FilesPage(listOf(file(11)), HistoryCursor(20)),
                20L to FilesPage(listOf(file(21)), null),
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
                0L to FilesPage(listOf(file(10)), HistoryCursor(5)),
                5L to FilesPage(listOf(file(5)), HistoryCursor(5)),
            ),
        )

        val summary = SynchronizeFilesUseCase(gateway, repository).invoke()

        assertFalse(summary.complete)
        assertEquals(listOf(5L, 5L), repository.savedCursors)
        assertEquals(5L, repository.cursor.fromMessageId)
        assertEquals(listOf("42:10", "42:5"), repository.files.map(StoredFile::stableId))
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
        reconciledRemoteIds += seenRemoteIds
    }
    override suspend fun deleteLocalFile(remoteId: String) = Unit
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

private class FakeGateway(
    private val pages: Map<Long, FilesPage>,
) : TelegramStorageGateway {
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
    override suspend fun getFilesPage(cursor: HistoryCursor, limit: Int): FilesPage = pages[cursor.fromMessageId] ?: FilesPage(emptyList(), null)
    override suspend fun getMetadataMessagesPage(cursor: HistoryCursor, limit: Int) = MetadataMessagesPage(emptyList(), null)
    override suspend fun uploadDocument(request: UploadRequest) = error("unused")
    override suspend fun downloadFile(request: DownloadRequest) = error("unused")
    override suspend fun cancelTransfer(transferId: TransferId, direction: TransferDirection, tdlibFileId: Int?) = Unit
    override suspend fun deleteRemoteMessage(remote: RemoteMessageId, revoke: Boolean) = Unit
    override suspend fun sendMetadataRecord(text: String) = RemoteMessageId(42, 1)
    override suspend fun close() = Unit
}
