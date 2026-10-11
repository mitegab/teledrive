package com.teledrive.core.database

import androidx.room.withTransaction
import com.teledrive.core.domain.FileIndexRepository
import com.teledrive.core.model.FileQuery
import com.teledrive.core.model.FileSort
import com.teledrive.core.model.Folder
import com.teledrive.core.model.StoredFile
import com.teledrive.core.model.Transfer
import com.teledrive.core.model.queryFiles
import com.teledrive.core.model.sortedFiles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomFileIndexRepository(
    private val database: TeleDriveDatabase,
) : FileIndexRepository {
    private val files = database.storedFileDao()
    private val folders = database.folderDao()
    private val transfers = database.transferDao()

    override fun observeFiles(query: FileQuery, sort: FileSort): Flow<List<StoredFile>> =
        files.observeAll().map { entities -> entities.map(StoredFileEntity::toDomain).queryFiles(query).sortedFiles(sort) }

    override fun observeFile(remoteId: String): Flow<StoredFile?> = files.observeById(remoteId).map { it?.toDomain() }

    override fun observeFolders(): Flow<List<Folder>> = folders.observeAll().map { entities -> entities.map(FolderEntity::toDomain) }

    override fun observeTransfers(): Flow<List<Transfer>> = transfers.observeAll().map { entities -> entities.map(TransferEntity::toDomain) }

    override suspend fun upsertFiles(files: List<StoredFile>) {
        database.withTransaction {
            files.forEach { remoteFile ->
                val existing = this@RoomFileIndexRepository.files.getById(remoteFile.stableId)
                this@RoomFileIndexRepository.files.upsert(remoteFile.toEntity(existing))
            }
        }
    }

    override suspend fun updateFile(file: StoredFile) {
        val existing = files.getById(file.stableId)
        files.upsert(file.toEntity(existing))
    }

    override suspend fun deleteLocalFile(remoteId: String) = files.clearLocalCopy(remoteId)

    override suspend fun setFolder(remoteId: String, folderId: String?) = files.setFolder(remoteId, folderId)

    override suspend fun setFavorite(remoteId: String, favorite: Boolean) = files.setFavorite(remoteId, favorite)

    override suspend fun createFolder(folder: Folder) = folders.insert(folder.toEntity())

    override suspend fun renameFolder(folderId: String, name: String, modifiedAtEpochSeconds: Long) =
        folders.rename(folderId, name, modifiedAtEpochSeconds)

    override suspend fun deleteFolder(folderId: String) = database.withTransaction {
        folders.delete(folderId)
        files.clearFolderAssignments(folderId)
    }

    override suspend fun moveFolder(folderId: String, parentId: String?, modifiedAtEpochSeconds: Long) =
        folders.move(folderId, parentId, modifiedAtEpochSeconds)

    override suspend fun upsertTransfer(transfer: Transfer) = transfers.upsert(transfer.toEntity())

    override suspend fun updateTransfer(transfer: Transfer) = transfers.upsert(transfer.toEntity())

    override suspend fun getTransfer(id: String): Transfer? = transfers.getById(id)?.toDomain()
}
