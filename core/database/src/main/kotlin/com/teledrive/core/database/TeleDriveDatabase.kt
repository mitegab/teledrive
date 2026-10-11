package com.teledrive.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [StoredFileEntity::class, FolderEntity::class, TransferEntity::class, SyncStateEntity::class],
    version = 4,
    exportSchema = true,
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

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transfers ADD COLUMN sourcePath TEXT")
            }
        }

        /**
         * Generic media support. Purely additive (new nullable/defaulted columns): existing rows, folders, favorites
         * and transfers are untouched. Rows keep mediaType UNKNOWN until the next sync refreshes them.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stored_files ADD COLUMN mediaType TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL("ALTER TABLE stored_files ADD COLUMN remoteUniqueId TEXT")
                db.execSQL("ALTER TABLE stored_files ADD COLUMN thumbnailFileId INTEGER")
                db.execSQL("ALTER TABLE stored_files ADD COLUMN durationSeconds INTEGER")
                db.execSQL("ALTER TABLE stored_files ADD COLUMN localSizeBytes INTEGER")
            }
        }

        fun create(context: Context): TeleDriveDatabase = Room.databaseBuilder(
            context,
            TeleDriveDatabase::class.java,
            "teledrive.db",
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    }
}
