package com.pitstop.drive

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * App-local SQLite database. Holds the [PendingDrive] queue; can grow
 * later with other phone-canonical state (recorded but not-yet-shipped
 * derived stats, etc).
 *
 * Migration policy: schema-version bumps require an `addMigrations()`
 * call. We accept destructive migration on downgrade only — never on
 * upgrade in release, so the user's queue can't be silently nuked.
 */
@Database(
    entities = [PendingDrive::class],
    version = 2,
    exportSchema = false,
)
abstract class DriveDatabase : RoomDatabase() {
    abstract fun pendingDriveDao(): PendingDriveDao

    companion object {
        const val DB_NAME = "pitstop-drive.db"

        /**
         * v1 → v2 (v0.1.111): adds [PendingDrive.payloadFilePath]. The
         * inline payload column stays NOT NULL because SQLite ALTER
         * can't relax that constraint without rebuilding the table,
         * and the uploader handles legacy rows (payloadFilePath null)
         * by dropping them.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_drive ADD COLUMN payloadFilePath TEXT")
            }
        }

        fun build(context: Context): DriveDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                DriveDatabase::class.java,
                DB_NAME,
            )
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}
