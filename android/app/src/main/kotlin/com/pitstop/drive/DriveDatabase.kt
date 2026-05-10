package com.pitstop.drive

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * App-local SQLite database. Holds the [PendingDrive] queue; can grow
 * later with other phone-canonical state (recorded but not-yet-shipped
 * derived stats, etc).
 *
 * Migration policy: schema-version bumps require an `addMigrations()`
 * call. We accept destructive migration on debug builds (faster
 * iteration) but never in release.
 */
@Database(
    entities = [PendingDrive::class],
    version = 1,
    exportSchema = false,
)
abstract class DriveDatabase : RoomDatabase() {
    abstract fun pendingDriveDao(): PendingDriveDao

    companion object {
        const val DB_NAME = "pitstop-drive.db"

        fun build(context: Context): DriveDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                DriveDatabase::class.java,
                DB_NAME,
            )
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}
