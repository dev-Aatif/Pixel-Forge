package com.pixelforge.app.core.extensions

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Bitmap and image utility extensions.
 */

/**
 * Decode a Bitmap from a content URI, ensuring ARGB_8888 format for NCNN processing.
 * Returns null if decoding fails.
 */
fun Context.decodeBitmapFromUri(uri: Uri, maxDimension: Int = 4096): Bitmap? {
    return try {
        // First pass: get dimensions only
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }

        // Calculate sample size
        val width = options.outWidth
        val height = options.outHeight
        var sampleSize = 1
        while (width / sampleSize > maxDimension || height / sampleSize > maxDimension) {
            sampleSize *= 2
        }

        // Second pass: decode with sample size
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }
        contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Get image dimensions from a content URI without decoding the full bitmap.
 */
fun Context.getImageDimensions(uri: Uri): Pair<Int, Int>? {
    return try {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        if (options.outWidth > 0 && options.outHeight > 0) {
            Pair(options.outWidth, options.outHeight)
        } else null
    } catch (e: Exception) {
        null
    }
}

/**
 * Get the file size (in bytes) from a content URI.
 */
fun Context.getFileSize(uri: Uri): Long {
    return try {
        contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
            afd.length
        } ?: 0L
    } catch (e: Exception) {
        0L
    }
}

/**
 * Copy a content URI to internal files storage to ensure persistence.
 * Useful for transient URIs like Photo Picker.
 */
fun Context.copyUriToInternalStorage(uri: Uri, folderName: String = "uploads"): Uri? {
    return try {
        val inputStream = contentResolver.openInputStream(uri) ?: return null
        val folder = File(filesDir, folderName).apply { mkdirs() }
        val fileName = "IMG_${System.currentTimeMillis()}.jpg"
        val file = File(folder, fileName)
        
        inputStream.use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        Uri.fromFile(file)
    } catch (e: Exception) {
        null
    }
}

/**
 * Format byte size into human-readable string.
 */
fun Long.formatFileSize(): String {
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = this.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024
        unitIndex++
    }
    return "%.1f %s".format(size, units[unitIndex])
}
