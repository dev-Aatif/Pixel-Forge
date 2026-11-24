package com.pixelforge.app.data.cache

import android.content.Context
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ImageCacheManagerTest {

    private lateinit var tempCacheDir: File
    private lateinit var cacheManager: ImageCacheManager

    @Before
    fun setUp() {
        tempCacheDir = createTempDirectory("pf_cache_test").toFile()
        val context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns tempCacheDir
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        cacheManager = ImageCacheManager(context)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        tempCacheDir.deleteRecursively()
    }

    @Test
    fun `createTempFile, getCacheSize, and clearAll manage processing_cache cleanly`() {
        val f1 = cacheManager.createTempFile("input", "png").apply { writeBytes(ByteArray(1200)) }
        val f2 = cacheManager.createTempFile("output", "png").apply { writeBytes(ByteArray(800)) }

        assertTrue(f1.exists())
        assertTrue(f2.exists())
        assertEquals(2000L, cacheManager.getCacheSize())

        cacheManager.clearAll()

        assertFalse(f1.exists())
        assertFalse(f2.exists())
        assertEquals(0L, cacheManager.getCacheSize())
    }

    @Test
    fun `cleanupStaleFiles removes files older than 24h while preserving recent files`() {
        val stale = cacheManager.createTempFile("old", "tmp").apply {
            writeBytes(ByteArray(100))
            setLastModified(System.currentTimeMillis() - (25L * 60L * 60L * 1000L))
        }
        val fresh = cacheManager.createTempFile("new", "tmp").apply {
            writeBytes(ByteArray(100))
        }

        cacheManager.cleanupStaleFiles()

        assertFalse("Files >24h old must be cleaned up", stale.exists())
        assertTrue("Fresh files must remain", fresh.exists())
    }
}
