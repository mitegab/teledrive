package com.teledrive.core.domain

import com.teledrive.core.model.AuthorizationState
import com.teledrive.core.model.MetadataMessagesPage
import com.teledrive.core.model.MetadataOperation
import com.teledrive.core.model.RemoteMessageId
import com.teledrive.core.model.TransferProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals

class MetadataSyncTest {
    @Test
    fun factorySequencesOperationsAndSyncCountsMalformedRecords() {
        val factory = MetadataRecordFactory("device", nowEpochSeconds = { 100 }, operationId = { "fixed-${System.nanoTime()}" })
        val first = factory.next(MetadataOperation.CreateFolder("f", "Folder"))
        val second = factory.next(MetadataOperation.FavoriteFile("1:2", true))
        assertEquals(1, first.sequence)
        assertEquals(2, second.sequence)
        assertEquals(100, first.timestampEpochSeconds)
    }

    @Test
    fun mapperCanBeUsedWithGatewayWithoutAndroid() = kotlinx.coroutines.test.runTest {
        val gateway = FakeGateway()
        val sync = MetadataSyncUseCase(gateway)
        val result = sync.mergeMessages(listOf("ordinary", "\u2063TELEDRIVE_METADATA_V1:bad"))
        assertEquals(0, result.validRecords)
        assertEquals(1, result.ignoredMessages)
        assertEquals(1, result.malformedRecords)
    }

    private class FakeGateway : TelegramStorageGateway {
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
        override suspend fun getCurrentUser() = error("unused")
        override suspend fun getSavedMessagesChat() = error("unused")
        override suspend fun getFilesPage(cursor: com.teledrive.core.model.HistoryCursor, limit: Int) = error("unused")
        override suspend fun getMetadataMessagesPage(cursor: com.teledrive.core.model.HistoryCursor, limit: Int) = MetadataMessagesPage(emptyList(), null)
        override suspend fun uploadDocument(request: UploadRequest) = error("unused")
        override suspend fun downloadFile(request: DownloadRequest) = error("unused")
        override suspend fun cancelTransfer(transferId: com.teledrive.core.model.TransferId, direction: com.teledrive.core.model.TransferDirection, tdlibFileId: Int?) = Unit
        override suspend fun deleteRemoteMessage(remote: RemoteMessageId, revoke: Boolean) = Unit
        override suspend fun sendMetadataRecord(text: String) = RemoteMessageId(1, 1)
        override suspend fun close() = Unit
    }
}
