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
     *
     * @param context Application context
     * @param bitmap  The bitmap to save
     * @param format  Image format (JPEG or PNG)
     * @param quality Compression quality (1-100, only for JPEG)
     * @param subfolder Optional subfolder within Pictures
     * @return The content URI of the saved image, or null on failure
     */
    fun saveBitmap(
        context: Context,
        bitmap: Bitmap,
        format: ImageFormat = ImageFormat.PNG,
        quality: Int = 95,
        subfolder: String = "PixelForge"
    ): Uri? {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
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
            resolver.openOutputStream(uri)?.use { outputStream ->
                val compressFormat = when (format) {
                    ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
                    ImageFormat.PNG -> Bitmap.CompressFormat.PNG
                }
                bitmap.compress(compressFormat, quality, outputStream)
            }

            // Clear IS_PENDING flag to make visible
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)

            uri
        } catch (e: Exception) {
            // Clean up on failure
            resolver.delete(uri, null, null)
            null
        }
    }

    enum class ImageFormat(val extension: String, val mimeType: String) {
        JPEG("jpg", "image/jpeg"),
        PNG("png", "image/png")
    }
}
