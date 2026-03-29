package com.pixelforge.app.di

import com.pixelforge.app.bridge.RealesrganBridge
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing WorkManager-related dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object WorkerModule {

    @Provides
    @Singleton
    fun provideRealesrganBridge(): RealesrganBridge {
        return RealesrganBridge()
    }
}
