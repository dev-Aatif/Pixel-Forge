package com.pixelforge.app.engine

import android.graphics.Bitmap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fast finishing pass after AI upscaling:
 * - Prevents subtle GAN color/brightness drift by aligning global channel means slightly toward source
 * - Applies gentle adaptive unsharp crispness so textures look photographic rather than waxy
 */
@Singleton
class PostProcessor @Inject constructor() {

    fun polish(source: Bitmap, upscaled: Bitmap, sharpenAmount: Float = 0.10f): Bitmap {
        val w = upscaled.width
        val h = upscaled.height
        if (w < 4 || h < 4) return upscaled

        val srcSample = Bitmap.createScaledBitmap(source, 32, 32, true)
        val upSample = Bitmap.createScaledBitmap(upscaled, 32, 32, true)

        val srcMean = meanRgb(srcSample)
        val upMean = meanRgb(upSample)
        srcSample.recycle()
        upSample.recycle()

        // Correct at most ±8 levels of global GAN color drift
        val shiftR = ((srcMean[0] - upMean[0]) * 0.5f).coerceIn(-8f, 8f)
        val shiftG = ((srcMean[1] - upMean[1]) * 0.5f).coerceIn(-8f, 8f)
        val shiftB = ((srcMean[2] - upMean[2]) * 0.5f).coerceIn(-8f, 8f)

        val pixels = IntArray(w * h)
        upscaled.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = pixels.copyOf()

        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val c = pixels[row + x]
                val u = pixels[(y - 1) * w + x]
                val d = pixels[(y + 1) * w + x]
                val l = pixels[row + x - 1]
                val r = pixels[row + x + 1]

                val cr = (c ushr 16) and 0xFF
                val cg = (c ushr 8) and 0xFF
                val cb = c and 0xFF
                val ca = (c ushr 24) and 0xFF

                val br = (((u ushr 16) and 0xFF) + ((d ushr 16) and 0xFF) + ((l ushr 16) and 0xFF) + ((r ushr 16) and 0xFF)) * 0.25f
                val bg = (((u ushr 8) and 0xFF) + ((d ushr 8) and 0xFF) + ((l ushr 8) and 0xFF) + ((r ushr 8) and 0xFF)) * 0.25f
                val bb = ((u and 0xFF) + (d and 0xFF) + (l and 0xFF) + (r and 0xFF)) * 0.25f

                val nr = (cr + (cr - br) * sharpenAmount + shiftR + 0.5f).toInt().coerceIn(0, 255)
                val ng = (cg + (cg - bg) * sharpenAmount + shiftG + 0.5f).toInt().coerceIn(0, 255)
                val nb = (cb + (cb - bb) * sharpenAmount + shiftB + 0.5f).toInt().coerceIn(0, 255)

                out[row + x] = (ca shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }

        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun meanRgb(bmp: Bitmap): FloatArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var sr = 0L
        var sg = 0L
        var sb = 0L
        for (p in px) {
            sr += (p ushr 16) and 0xFF
            sg += (p ushr 8) and 0xFF
            sb += p and 0xFF
        }
        val n = px.size.coerceAtLeast(1).toFloat()
        return floatArrayOf(sr / n, sg / n, sb / n)
    }
}
