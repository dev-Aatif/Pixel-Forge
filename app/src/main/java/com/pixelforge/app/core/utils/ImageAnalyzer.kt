package com.pixelforge.app.core.utils

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/**
 * Lightweight pre-flight image profiler (<30ms) used to choose smart upscaling defaults.
 */
data class ImageProfile(
    val width: Int,
    val height: Int,
    val hasAlpha: Boolean,
    val isLikelyFlatIllustration: Boolean
)

object ImageAnalyzer {

    /**
     * Inspect a thumbnail/bitmap quickly to detect alpha transparency and flat/anime/UI line-art.
     */
    fun analyze(bitmap: Bitmap): ImageProfile {
        val sampleW = 64
        val sampleH = 64
        val scaled = if (bitmap.width > sampleW || bitmap.height > sampleH) {
            Bitmap.createScaledBitmap(bitmap, sampleW, sampleH, false)
        } else {
            bitmap
        }

        val w = scaled.width
        val h = scaled.height
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()

        var hasTransparentPixel = false
        var flatNeighborPairs = 0
        var totalPairs = 0

        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                if (Color.alpha(p) < 250) {
                    hasTransparentPixel = true
                }
                if (x + 1 < w) {
                    val right = pixels[y * w + (x + 1)]
                    val diff = abs(Color.red(p) - Color.red(right)) +
                        abs(Color.green(p) - Color.green(right)) +
                        abs(Color.blue(p) - Color.blue(right))
                    if (diff <= 6) flatNeighborPairs++
                    totalPairs++
                }
            }
        }

        val flatRatio = if (totalPairs > 0) flatNeighborPairs.toFloat() / totalPairs else 0f
        return ImageProfile(
            width = bitmap.width,
            height = bitmap.height,
            hasAlpha = hasTransparentPixel,
            isLikelyFlatIllustration = flatRatio > 0.58f
        )
    }
}
