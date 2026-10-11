package com.teledrive.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "stored_files",
    indices = [Index(value = ["remoteChatId", "remoteMessageId"], unique = true), Index(value = ["originalFilename"]), Index(value = ["folderId"])],
)
data class StoredFileEntity(
    @PrimaryKey val stableId: String,
    val remoteChatId: Long,
    val remoteMessageId: Long,
    val tdlibFileId: Int?,
    val originalFilename: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val savedAtEpochSeconds: Long,
    val caption: String?,
    val folderId: String?,
    val favorite: Boolean,
    val remoteAvailability: String,
    val localAvailability: String,
    val localPath: String?,
)

@Entity(
    tableName = "folders",
    indices = [Index(value = ["parentId"]), Index(value = ["parentId", "name"], unique = true)],
)
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String?,
    val createdAtEpochSeconds: Long,
    val modifiedAtEpochSeconds: Long,
)

@Entity(
    tableName = "transfers",
    indices = [Index(value = ["state"]), Index(value = ["direction"])],
)
data class TransferEntity(
    @PrimaryKey val id: String,
    val direction: String,
    val filename: String,
    val remoteChatId: Long?,
    val remoteMessageId: Long?,
    val tdlibFileId: Int?,
    val bytesTransferred: Long,
    val totalBytes: Long?,
    val state: String,
    val errorMessage: String?,
    val attempt: Int,
    val retryAtEpochSeconds: Long?,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 0,
    val lastHistoryMessageId: Long = 0,
    val lastSuccessfulSyncEpochSeconds: Long? = null,
    val metadataSchemaVersion: Int = 1,
    val lastMetadataMessageId: Long = 0,
)
