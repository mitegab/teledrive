package com.teledrive.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.teledrive.core.model.LocalAvailability
import com.teledrive.core.model.RemoteAvailability
import com.teledrive.core.model.RemoteMessageId
import com.teledrive.core.model.StoredFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class RoomFileIndexRepositoryTest {
    private lateinit var database: TeleDriveDatabase
    private lateinit var repository: RoomFileIndexRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, TeleDriveDatabase::class.java).build()
        repository = RoomFileIndexRepository(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun remoteRefreshPreservesLocalOrganizationAndLocalCache() = runBlocking {
        val original = StoredFile(
            remote = RemoteMessageId(10, 20),
            tdlibFileId = 4,
            originalFilename = "report.pdf",
            mimeType = "application/pdf",
            sizeBytes = 10,
            savedAtEpochSeconds = 1,
            folderId = "work",
            favorite = true,
            remoteAvailability = RemoteAvailability.AVAILABLE,
            localAvailability = LocalAvailability.AVAILABLE,
            localPath = "/private/report.pdf",
        )
        repository.upsertFiles(listOf(original))
        val refreshed = original.copy(sizeBytes = 12, folderId = null, favorite = false, localAvailability = LocalAvailability.NOT_DOWNLOADED, localPath = null)
        repository.upsertFiles(listOf(refreshed))

        val actual = repository.observeFile(original.stableId).first()
        assertNotNull(actual)
        assertEquals("work", actual?.folderId)
        assertEquals(true, actual?.favorite)
        assertEquals(LocalAvailability.AVAILABLE, actual?.localAvailability)
        assertEquals("/private/report.pdf", actual?.localPath)
        assertEquals(12L, actual?.sizeBytes)
    }

    @Test
    fun historyCheckpointPersistsUntilACompleteReset() = runBlocking {
        repository.saveHistoryCursor(com.teledrive.core.model.HistoryCursor(99))
        assertEquals(99L, repository.getHistoryCursor().fromMessageId)
        repository.saveHistoryCursor(com.teledrive.core.model.HistoryCursor())
        assertEquals(0L, repository.getHistoryCursor().fromMessageId)
    }

    @Test
    fun remoteDeletionMarksOnlyRemoteAvailabilityAndKeepsLocalCopy() = runBlocking {
        val file = StoredFile(
            RemoteMessageId(1, 4),
            5,
            "offline.pdf",
            "application/pdf",
            8,
            1,
            remoteAvailability = RemoteAvailability.AVAILABLE,
            localAvailability = LocalAvailability.AVAILABLE,
            localPath = "/managed/offline.pdf",
        )
        repository.upsertFiles(listOf(file))
        repository.markRemoteDeleted(file.remote)

        val actual = repository.observeFile(file.stableId).first()
        assertEquals(RemoteAvailability.DELETED, actual?.remoteAvailability)
        assertEquals(LocalAvailability.AVAILABLE, actual?.localAvailability)
        assertEquals("/managed/offline.pdf", actual?.localPath)
    }

    @Test
    fun deletingLocalCopyDoesNotRemoveRemoteIndex() = runBlocking {
        val file = StoredFile(RemoteMessageId(1, 2), 3, "a.txt", "text/plain", 1, 1, remoteAvailability = RemoteAvailability.AVAILABLE, localAvailability = LocalAvailability.AVAILABLE, localPath = "/tmp/a.txt")
        repository.upsertFiles(listOf(file))
        repository.deleteLocalFile(file.stableId)
        val actual = repository.observeFile(file.stableId).first()
        assertEquals(RemoteAvailability.AVAILABLE, actual?.remoteAvailability)
        assertEquals(LocalAvailability.NOT_DOWNLOADED, actual?.localAvailability)
        assertEquals(null, actual?.localPath)
    }

    @Test
    fun deletingFolderClearsAssignmentsWithoutTouchingRemoteFiles() = runBlocking {
        repository.createFolder(com.teledrive.core.model.Folder("f", "Folder", null, 1, 1))
        val file = StoredFile(RemoteMessageId(2, 3), 4, "file", null, 1, 1, folderId = "f", remoteAvailability = RemoteAvailability.AVAILABLE)
        repository.upsertFiles(listOf(file))
        repository.deleteFolder("f")
        assertEquals(null, repository.observeFile(file.stableId).first()?.folderId)
        assertEquals(RemoteAvailability.AVAILABLE, repository.observeFile(file.stableId).first()?.remoteAvailability)
    }
}
