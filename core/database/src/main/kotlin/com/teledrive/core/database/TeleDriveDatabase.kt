package com.teledrive.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [StoredFileEntity::class, FolderEntity::class, TransferEntity::class, SyncStateEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class TeleDriveDatabase : RoomDatabase() {
    abstract fun storedFileDao(): StoredFileDao
    abstract fun folderDao(): FolderDao
    abstract fun transferDao(): TransferDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stored_files ADD COLUMN caption TEXT")
            }
        }

        fun create(context: Context): TeleDriveDatabase = Room.databaseBuilder(
            context,
            TeleDriveDatabase::class.java,
            "teledrive.db",
        )
            .addMigrations(MIGRATION_1_2)
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    }
}
