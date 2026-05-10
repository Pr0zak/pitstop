package com.pitstop.di

import android.content.Context
import com.pitstop.drive.DriveDatabase
import com.pitstop.drive.PendingDriveDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DriveModule {

    @Provides
    @Singleton
    fun provideDriveDatabase(
        @ApplicationContext context: Context,
    ): DriveDatabase = DriveDatabase.build(context)

    @Provides
    fun providePendingDriveDao(db: DriveDatabase): PendingDriveDao =
        db.pendingDriveDao()
}
