package com.pixelforge.app.core.extensions

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Bitmap and image utility extensions with OOM-safe sampling and EXIF orientation handling.
 */

/**
 * Decode an upright ARGB_8888 Bitmap from [uri], capped so its longest edge is at most [maxDimension].
 * Uses power-of-two subsampling during decode to avoid OOM on 50MP+ photos.
 */
fun Context.decodeBitmapFromUri(uri: Uri, maxDimension: Int = 4096): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        val rawWidth = bounds.outWidth
        val rawHeight = bounds.outHeight
        if (rawWidth <= 0 || rawHeight <= 0) return null

        val exifOrientation = readExifOrientation(uri)
        val isSwapped = isOrientationSwapped(exifOrientation)
        val orientedWidth = if (isSwapped) rawHeight else rawWidth
        val orientedHeight = if (isSwapped) rawWidth else rawHeight

        // Choose largest power-of-two sampleSize keeping the longest edge >= maxDimension (if larger)
        val longestSide = maxOf(orientedWidth, orientedHeight)
        var sampleSize = 1
        while ((longestSide / (sampleSize * 2)) >= maxDimension) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            inMutable = false
        }

        val decoded = contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: return null

        val upright = applyExifOrientation(decoded, exifOrientation)
        if (upright !== decoded) decoded.recycle()

        val maxSide = maxOf(upright.width, upright.height)
        if (maxSide <= maxDimension) {
            return ensureArgb8888(upright)
        }

        val scale = maxDimension.toFloat() / maxSide.toFloat()
        val targetW = (upright.width * scale).toInt().coerceAtLeast(1)
        val targetH = (upright.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(upright, targetW, targetH, true)
        if (scaled !== upright) upright.recycle()
        ensureArgb8888(scaled)
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: Exception) {
        null
    }
}

/**
 * Decode a display-sized preview bitmap bounded by [maxPreviewDimension].
 */
fun Context.decodePreviewBitmap(uri: Uri, maxPreviewDimension: Int = 1440): Bitmap? {
    return decodeBitmapFromUri(uri, maxDimension = maxPreviewDimension)
}

/**
 * Get upright image dimensions from a content URI without decoding full pixels.
 */
fun Context.getImageDimensions(uri: Uri): Pair<Int, Int>? {
    return try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null

        val orientation = readExifOrientation(uri)
        if (isOrientationSwapped(orientation)) {
            Pair(options.outHeight, options.outWidth)
        } else {
            Pair(options.outWidth, options.outHeight)
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Get the accurate file size (in bytes) from a content or file URI.
 */
fun Context.getFileSize(uri: Uri): Long {
    return try {
        if (uri.scheme == "file" && uri.path != null) {
            return File(uri.path!!).length()
        }
        val afdLength = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (afdLength >= 0L) return afdLength

        contentResolver.openInputStream(uri)?.use { stream ->
            var total = 0L
            val buf = ByteArray(8192)
            while (true) {
                val read = stream.read(buf)
                if (read == -1) break
                total += read
            }
            total
        } ?: 0L
    } catch (_: Exception) {
        0L
    }
}

/**
 * Copy a content URI to app-private files storage with its real extension so transient
 * Photo Picker URIs persist safely for History & Result inspection.
 */
fun Context.copyUriToInternalStorage(uri: Uri, folderName: String = "uploads"): Uri? {
    return try {
        val inputStream = contentResolver.openInputStream(uri) ?: return null
        val mime = contentResolver.getType(uri)
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
        val folder = File(filesDir, folderName).apply { mkdirs() }
        val fileName = "IMG_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$ext"
        val file = File(folder, fileName)

        inputStream.use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        Uri.fromFile(file)
    } catch (_: Exception) {
        null
    }
}

/**
 * Deletes an app-owned staged file inside [filesDir]/uploads or [cacheDir] safely.
 */
fun Context.deleteAppOwnedFile(uriString: String?): Boolean {
    if (uriString.isNullOrBlank()) return false
    return runCatching {
        val uri = Uri.parse(uriString)
        if (uri.scheme != "file") return false
        val path = uri.path ?: return false
        val file = File(path)
        val filesRoot = filesDir.canonicalPath
        val cacheRoot = cacheDir.canonicalPath
        val targetCanonical = file.canonicalPath
        if (targetCanonical.startsWith(filesRoot) || targetCanonical.startsWith(cacheRoot)) {
            file.delete()
        } else {
            false
        }
    }.getOrDefault(false)
}

/**
 * Format byte size into human-readable string.
 */
fun Long.formatFileSize(): String {
    if (this <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = this.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024
        unitIndex++
    }
    return "%.1f %s".format(size, units[unitIndex])
}

private fun Context.readExifOrientation(uri: Uri): Int {
    return runCatching {
        contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
}

private fun isOrientationSwapped(orientation: Int): Boolean {
    return orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
        orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
        orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
        orientation == ExifInterface.ORIENTATION_TRANSVERSE
}

private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.preScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.preScale(-1f, 1f)
        }
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun ensureArgb8888(bitmap: Bitmap): Bitmap {
    if (bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
    val converted = bitmap.copy(Bitmap.Config.ARGB_8888, false)
    if (converted != null) {
        bitmap.recycle()
        return converted
    }
    return bitmap
}
