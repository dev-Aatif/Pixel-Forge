package com.pixelforge.app

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.pixelforge.app.bridge.NativeLoader
import com.pixelforge.app.data.cache.ImageCacheManager
import com.pixelforge.app.data.local.ProcessedImageDao
import com.pixelforge.app.engine.EngineSelector
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class PixelForgeApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var engineSelector: EngineSelector

    @Inject
    lateinit var processedImageDao: ProcessedImageDao

    @Inject
    lateinit var imageCacheManager: ImageCacheManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        NativeLoader.load()
        appScope.launch {
            processedImageDao.deleteStaleProcessing()
            imageCacheManager.cleanupStaleFiles()
            imageCacheManager.enforceSizeLimit()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            engineSelector.releaseAll()
        }
    }
}
