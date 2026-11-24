package com.pixelforge.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.net.Uri
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
import java.io.File
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
            pruneOrphanedUploads()
            imageCacheManager.cleanupStaleFiles()
            imageCacheManager.enforceSizeLimit()
        }
    }

    private suspend fun pruneOrphanedUploads() {
        val uploadsDir = File(filesDir, "uploads")
        val files = uploadsDir.listFiles() ?: return
        val referencedUris = processedImageDao.getAllSnapshot().map { it.inputUri }.toHashSet()
        val cutoffMs = System.currentTimeMillis() - 60L * 60L * 1000L
        files.forEach { file ->
            if (file.lastModified() < cutoffMs && Uri.fromFile(file).toString() !in referencedUris) {
                file.delete()
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            // Release off the main thread and defer if inference is actively running to avoid ANRs/UAFs.
            appScope.launch(Dispatchers.Default) {
                engineSelector.releaseIfIdle()
            }
        }
    }
}
