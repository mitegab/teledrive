package com.teledrive.core.testing

import com.teledrive.core.model.RemoteMessageId
import com.teledrive.core.model.StoredFile
import com.teledrive.core.model.TransferDirection
import com.teledrive.core.model.TransferId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeTelegramStorageGatewayTest {
    @Test
    fun fakeSupportsUploadAndExplicitRemoteDeletion() = runBlocking {
        val gateway = FakeTelegramStorageGateway()
        val result = gateway.uploadDocument(
            com.teledrive.core.domain.UploadRequest(TransferId("u"), "/tmp/a", "a.txt", "text/plain", 4),
        )
        assertEquals("a.txt", result.file.originalFilename)
        gateway.deleteRemoteMessage(result.file.remote)
        assertEquals(listOf(result.file.remote), gateway.deletedRemoteMessages)
    }

    @Test
    fun cancellationIsObservableAndDoesNotDeleteRemoteData() = runBlocking {
        val gateway = FakeTelegramStorageGateway()
        val transfer = TransferId("d")
        gateway.cancelTransfer(transfer, TransferDirection.DOWNLOAD, 5)
        assertEquals(listOf(transfer), gateway.cancelledTransfers)
        assertEquals(emptyList<RemoteMessageId>(), gateway.deletedRemoteMessages)
    }
}
