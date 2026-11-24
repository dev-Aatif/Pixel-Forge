package com.pixelforge.app.core

import com.pixelforge.app.core.extensions.formatFileSize
import com.pixelforge.app.core.utils.MediaStoreUtils
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageFormatAndMediaStoreTest {

    @Test
    fun `formatFileSize formats bytes, KB, MB, and GB cleanly`() {
        assertEquals("0 B", 0L.formatFileSize())
        assertEquals("0 B", (-5L).formatFileSize())
        assertEquals("512.0 B", 512L.formatFileSize())
        assertEquals("2.0 KB", 2048L.formatFileSize())
        assertEquals("1.5 MB", (1536L * 1024L).formatFileSize())
        assertEquals("1.0 GB", (1024L * 1024L * 1024L).formatFileSize())
    }

    @Test
    fun `MediaStoreUtils ImageFormat resolves case-insensitively with PNG fallback`() {
        assertEquals(MediaStoreUtils.ImageFormat.JPEG, MediaStoreUtils.ImageFormat.fromString("jpeg"))
        assertEquals(MediaStoreUtils.ImageFormat.WEBP, MediaStoreUtils.ImageFormat.fromString("WebP"))
        assertEquals(MediaStoreUtils.ImageFormat.PNG, MediaStoreUtils.ImageFormat.fromString("png"))
        assertEquals(MediaStoreUtils.ImageFormat.PNG, MediaStoreUtils.ImageFormat.fromString("tiff"))
        assertEquals(MediaStoreUtils.ImageFormat.PNG, MediaStoreUtils.ImageFormat.fromString(null))
    }
}
