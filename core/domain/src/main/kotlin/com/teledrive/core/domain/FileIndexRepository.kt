package com.teledrive.core.domain

import com.teledrive.core.model.FileQuery
import com.teledrive.core.model.FileSort
import com.teledrive.core.model.Folder
import com.teledrive.core.model.StoredFile
import com.teledrive.core.model.Transfer
import kotlinx.coroutines.flow.Flow

interface FileIndexRepository {
    fun observeFiles(query: FileQuery = FileQuery(), sort: FileSort = FileSort()): Flow<List<StoredFile>>
    fun observeFile(remoteId: String): Flow<StoredFile?>
    fun observeFolders(): Flow<List<Folder>>
    fun observeTransfers(): Flow<List<Transfer>>

    suspend fun upsertFiles(files: List<StoredFile>)
    suspend fun updateFile(file: StoredFile)
    suspend fun deleteLocalFile(remoteId: String)
    suspend fun setFolder(remoteId: String, folderId: String?)
    suspend fun setFavorite(remoteId: String, favorite: Boolean)

    suspend fun createFolder(folder: Folder)
    suspend fun upsertFolder(folder: Folder)
    suspend fun renameFolder(folderId: String, name: String, modifiedAtEpochSeconds: Long)
    suspend fun deleteFolder(folderId: String)
    suspend fun moveFolder(folderId: String, parentId: String?, modifiedAtEpochSeconds: Long)

    suspend fun upsertTransfer(transfer: Transfer)
    suspend fun updateTransfer(transfer: Transfer)
    suspend fun getTransfer(id: String): Transfer?
}
