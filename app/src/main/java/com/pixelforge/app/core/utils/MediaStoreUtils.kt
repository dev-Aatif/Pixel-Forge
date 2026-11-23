package com.pixelforge.app.core.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility for saving images via MediaStore (Scoped Storage on API 31+).
 */
object MediaStoreUtils {

    /**
     * Save a Bitmap to the device's Pictures directory via MediaStore.
     */
    fun saveBitmap(
        context: Context,
        bitmap: Bitmap,
        format: ImageFormat = ImageFormat.PNG,
        quality: Int = 95,
        subfolder: String = "PixelForge"
    ): Uri? {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val filename = "PF_${timestamp}.${format.extension}"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$subfolder"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return null

        return try {
            val wroteOk = resolver.openOutputStream(uri)?.use { outputStream ->
                val compressFormat = when (format) {
                    ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
                    ImageFormat.PNG -> Bitmap.CompressFormat.PNG
                    ImageFormat.WEBP -> Bitmap.CompressFormat.WEBP_LOSSY
                }
                bitmap.compress(compressFormat, quality.coerceIn(1, 100), outputStream)
            } ?: false

            if (!wroteOk) {
                resolver.delete(uri, null, null)
                return null
            }

            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)

            uri
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    enum class ImageFormat(val extension: String, val mimeType: String) {
        PNG("png", "image/png"),
        JPEG("jpg", "image/jpeg"),
        WEBP("webp", "image/webp");

        companion object {
            fun fromString(value: String?): ImageFormat {
                return entries.find { it.name.equals(value, ignoreCase = true) } ?: PNG
            }
        }
    }
}
