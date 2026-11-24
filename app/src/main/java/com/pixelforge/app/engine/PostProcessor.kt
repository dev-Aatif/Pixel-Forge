package com.pixelforge.app.engine

import android.graphics.Bitmap
import com.pixelforge.app.bridge.RealesrganBridge
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Fast finishing pass after AI upscaling:
 * - Prevents subtle GAN color/brightness drift by aligning global channel means slightly toward source
 * - Applies gentle adaptive unsharp crispness so textures look photographic rather than waxy
 *
 * Uses JNI native 3-row rolling-buffer processing in-place on mutable bitmaps so 4K/8K outputs
 * require ~100 KB scratch instead of full-frame IntArray heap allocations.
 */
@Singleton
class PostProcessor @Inject constructor(
    private val bridge: RealesrganBridge
) {
    constructor() : this(RealesrganBridge())

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

        val target = if (upscaled.isMutable && upscaled.config == Bitmap.Config.ARGB_8888) {
            upscaled
        } else {
            upscaled.copy(Bitmap.Config.ARGB_8888, true) ?: return upscaled
        }

        if (bridge.polishInPlace(target, shiftR, shiftG, shiftB, sharpenAmount)) {
            return target
        }

        // Row-rolling Kotlin fallback (e.g. JVM unit tests where native lib isn't loaded)
        polishRowRollingKotlin(target, w, h, shiftR, shiftG, shiftB, sharpenAmount)
        return target
    }

    private fun polishRowRollingKotlin(
        target: Bitmap,
        w: Int,
        h: Int,
        shiftR: Float,
        shiftG: Float,
        shiftB: Float,
        sharpenAmount: Float
    ) {
        var prevRow = IntArray(w)
        var currRow = IntArray(w)
        var nextRow = IntArray(w)
        val outRow = IntArray(w)

        target.getPixels(currRow, 0, w, 0, 0, w, 1)
        target.getPixels(nextRow, 0, w, 0, 1, w, 1)

        for (y in 0 until h) {
            if (y == 0 || y == h - 1) {
                for (x in 0 until w) {
                    val c = currRow[x]
                    val ca = (c ushr 24) and 0xFF
                    if (ca == 0) {
                        outRow[x] = 0
                        continue
                    }
                    val cr = ((c ushr 16) and 0xFF) + shiftR
                    val cg = ((c ushr 8) and 0xFF) + shiftG
                    val cb = (c and 0xFF) + shiftB
                    outRow[x] = (ca shl 24) or
                        (cr.roundToInt().coerceIn(0, ca) shl 16) or
                        (cg.roundToInt().coerceIn(0, ca) shl 8) or
                        cb.roundToInt().coerceIn(0, ca)
                }
            } else {
                for (x in 0 until w) {
                    val c = currRow[x]
                    val ca = (c ushr 24) and 0xFF
                    if (ca == 0) {
                        outRow[x] = 0
                        continue
                    }
                    if (x == 0 || x == w - 1) {
                        val cr = ((c ushr 16) and 0xFF) + shiftR
                        val cg = ((c ushr 8) and 0xFF) + shiftG
                        val cb = (c and 0xFF) + shiftB
                        outRow[x] = (ca shl 24) or
                            (cr.roundToInt().coerceIn(0, ca) shl 16) or
                            (cg.roundToInt().coerceIn(0, ca) shl 8) or
                            cb.roundToInt().coerceIn(0, ca)
                        continue
                    }
                    val u = prevRow[x]
                    val d = nextRow[x]
                    val l = currRow[x - 1]
                    val r = currRow[x + 1]

                    val cr = (c ushr 16) and 0xFF
                    val cg = (c ushr 8) and 0xFF
                    val cb = c and 0xFF

                    val br = (((u ushr 16) and 0xFF) + ((d ushr 16) and 0xFF) + ((l ushr 16) and 0xFF) + ((r ushr 16) and 0xFF)) * 0.25f
                    val bg = (((u ushr 8) and 0xFF) + ((d ushr 8) and 0xFF) + ((l ushr 8) and 0xFF) + ((r ushr 8) and 0xFF)) * 0.25f
                    val bb = ((u and 0xFF) + (d and 0xFF) + (l and 0xFF) + (r and 0xFF)) * 0.25f

                    val nr = (cr + (cr - br) * sharpenAmount + shiftR + 0.5f).toInt().coerceIn(0, ca)
                    val ng = (cg + (cg - bg) * sharpenAmount + shiftG + 0.5f).toInt().coerceIn(0, ca)
                    val nb = (cb + (cb - bb) * sharpenAmount + shiftB + 0.5f).toInt().coerceIn(0, ca)
                    outRow[x] = (ca shl 24) or (nr shl 16) or (ng shl 8) or nb
                }
            }
            target.setPixels(outRow, 0, w, 0, y, w, 1)

            if (y + 1 < h) {
                val tmp = prevRow
                prevRow = currRow
                currRow = nextRow
                nextRow = tmp
                if (y + 2 < h) {
                    target.getPixels(nextRow, 0, w, 0, y + 2, w, 1)
                }
            }
        }
    }

    private fun meanRgb(bmp: Bitmap): FloatArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var sr = 0L
        var sg = 0L
        var sb = 0L
        var count = 0
        for (p in px) {
            val a = (p ushr 24) and 0xFF
            if (a < 16) continue
            sr += (p ushr 16) and 0xFF
            sg += (p ushr 8) and 0xFF
            sb += p and 0xFF
            count++
        }
        val n = count.coerceAtLeast(1).toFloat()
        return floatArrayOf(sr / n, sg / n, sb / n)
    }
}
