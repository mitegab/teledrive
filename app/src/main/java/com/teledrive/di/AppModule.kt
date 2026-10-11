package com.teledrive.di

import android.content.Context
import com.teledrive.BuildConfig
import com.teledrive.core.database.RoomFileIndexRepository
import com.teledrive.core.database.TeleDriveDatabase
import com.teledrive.core.domain.FileIndexRepository
import com.teledrive.core.domain.ObserveFilesUseCase
import com.teledrive.core.domain.SynchronizeFilesUseCase
import com.teledrive.core.domain.TelegramStorageGateway
import com.teledrive.core.telegram.TdLibJsonGateway
import com.teledrive.core.telegram.TelegramCredentials
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideTelegramCredentials(): TelegramCredentials = TelegramCredentials(
        apiId = BuildConfig.TELEGRAM_API_ID,
        apiHash = BuildConfig.TELEGRAM_API_HASH,
    )

    @Provides
    @Singleton
    fun provideTelegramGateway(
        @ApplicationContext context: Context,
        credentials: TelegramCredentials,
    ): TelegramStorageGateway = TdLibJsonGateway(
        context = context,
        credentials = credentials,
        applicationVersion = BuildConfig.VERSION_NAME,
    )

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TeleDriveDatabase = TeleDriveDatabase.create(context)

    @Provides
    @Singleton
    fun provideFileIndexRepository(database: TeleDriveDatabase): FileIndexRepository = RoomFileIndexRepository(database)

    @Provides
    @Singleton
    fun provideObserveFilesUseCase(repository: FileIndexRepository): ObserveFilesUseCase = ObserveFilesUseCase(repository)

    @Provides
    @Singleton
    fun provideSynchronizeFilesUseCase(
        gateway: TelegramStorageGateway,
        repository: FileIndexRepository,
    ): SynchronizeFilesUseCase = SynchronizeFilesUseCase(gateway, repository)
}
