package com.pixelforge.app.data.cache

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages temporary image files used during processing.
 * Prevents storage leaks by cleaning up orphaned temp files.
 */
@Singleton
class ImageCacheManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ImageCacheManager"
        private const val CACHE_DIR_NAME = "processing_cache"
        private const val MAX_CACHE_AGE_MS = 24 * 60 * 60 * 1000L // 24 hours
        private const val MAX_CACHE_SIZE_BYTES = 500 * 1024 * 1024L // 500 MB
    }

    private val cacheDir: File
        get() = File(context.cacheDir, CACHE_DIR_NAME).also { it.mkdirs() }

    /**
     * Create a new temp file for processing input/output.
     */
    fun createTempFile(prefix: String, extension: String = "tmp"): File {
        return File.createTempFile(prefix, ".$extension", cacheDir)
    }

    /**
     * Delete a specific temp file.
     */
    fun deleteTempFile(file: File): Boolean {
        return try {
            file.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete temp file: ${file.path}", e)
            false
        }
    }

    /**
     * Clean up old temp files (older than MAX_CACHE_AGE_MS).
     */
    fun cleanupStaleFiles() {
        val now = System.currentTimeMillis()
        var deletedCount = 0

        cacheDir.listFiles()?.forEach { file ->
            if (now - file.lastModified() > MAX_CACHE_AGE_MS) {
                if (file.delete()) deletedCount++
            }
        }

        if (deletedCount > 0) {
            Log.i(TAG, "Cleaned up $deletedCount stale cache files")
        }
    }

    /**
     * Enforce cache size limit by deleting oldest files first.
     */
    fun enforceSizeLimit() {
        val files = cacheDir.listFiles()?.toMutableList() ?: return
        files.sortBy { it.lastModified() }

        var totalSize = files.sumOf { it.length() }
        var deletedCount = 0

        while (totalSize > MAX_CACHE_SIZE_BYTES && files.isNotEmpty()) {
            val oldest = files.removeFirst()
            totalSize -= oldest.length()
            if (oldest.delete()) deletedCount++
        }

        if (deletedCount > 0) {
            Log.i(TAG, "Evicted $deletedCount files to enforce size limit")
        }
    }

    /**
     * Get total cache size in bytes.
     */
    fun getCacheSize(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * Clear all cache files.
     */
    fun clearAll() {
        val count = cacheDir.listFiles()?.count { it.delete() } ?: 0
        Log.i(TAG, "Cleared $count cache files")
    }
}
