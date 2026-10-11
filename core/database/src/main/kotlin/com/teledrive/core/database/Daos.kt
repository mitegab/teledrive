package com.teledrive.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StoredFileDao {
    @Query("SELECT * FROM stored_files ORDER BY savedAtEpochSeconds DESC, stableId DESC")
    fun observeAll(): Flow<List<StoredFileEntity>>

    @Query("SELECT * FROM stored_files WHERE stableId = :stableId LIMIT 1")
    fun observeById(stableId: String): Flow<StoredFileEntity?>

    @Query("SELECT * FROM stored_files WHERE stableId = :stableId LIMIT 1")
    suspend fun getById(stableId: String): StoredFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(file: StoredFileEntity)

    @Query("UPDATE stored_files SET folderId = :folderId WHERE stableId = :stableId")
    suspend fun setFolder(stableId: String, folderId: String?)

    @Query("UPDATE stored_files SET favorite = :favorite WHERE stableId = :stableId")
    suspend fun setFavorite(stableId: String, favorite: Boolean)

    @Query("UPDATE stored_files SET localAvailability = 'NOT_DOWNLOADED', localPath = NULL WHERE stableId = :stableId")
    suspend fun clearLocalCopy(stableId: String)

    @Query("UPDATE stored_files SET remoteAvailability = :availability WHERE stableId = :stableId")
    suspend fun setRemoteAvailability(stableId: String, availability: String)

    @Query("UPDATE stored_files SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolderAssignments(folderId: String)
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY parentId IS NOT NULL, name COLLATE NOCASE")
    fun observeAll(): Flow<List<FolderEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(folder: FolderEntity)

    @Query("UPDATE folders SET name = :name, modifiedAtEpochSeconds = :modifiedAt WHERE id = :id")
    suspend fun rename(id: String, name: String, modifiedAt: Long)

    @Query("UPDATE folders SET parentId = :parentId, modifiedAtEpochSeconds = :modifiedAt WHERE id = :id")
    suspend fun move(id: String, parentId: String?, modifiedAt: Long)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY state, id")
    fun observeAll(): Flow<List<TransferEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(transfer: TransferEntity)

    @Query("SELECT * FROM transfers WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TransferEntity?
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = 0 LIMIT 1")
    suspend fun get(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncStateEntity)
}
