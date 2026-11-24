package com.pixelforge.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.pixelforge.app.data.model.ModelManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Selective face detection & restoration pipeline.
 *
 * 1. Detects faces in <30ms using MediaPipe BlazeFace (`blaze_face_short_range.tflite`)
 *    on the compact source/proxy image rather than full 4x upscaled memory.
 * 2. If the optional GFPGAN ONNX pack is installed, square-pads each face without aspect
 *    distortion, restores at 512x512, and blends seamlessly with an elliptical feather mask.
 * 3. Otherwise, applies scale-adaptive local micro-clarity on face regions.
 */
@Singleton
class FaceRestorationPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager
) {
    companion object {
        private const val TAG = "FaceRestorationPipeline"
        private const val FACE_MODEL_ASSET = "models/face/blaze_face_short_range.tflite"
        private const val GFPGAN_INPUT_SIZE = 512
        private const val DETECTOR_MAX_DIM = 640
    }

    private val pipelineLock = Any()
    private var detector: FaceDetector? = null
    private var gfpganSession: OrtSession? = null
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    /**
     * Detect how many faces exist in [bitmap] (fast pre-flight check for Editor UI).
     */
    fun detectFaceCount(bitmap: Bitmap): Int {
        return detectFaceBounds(bitmap).size
    }

    /**
     * Enhance detected faces in-place on [upscaledBitmap] (or a mutable copy if immutable).
     * When [sourceBitmap] is provided, face detection runs on the smaller source image and
     * scales bounding boxes into [upscaledBitmap] space.
     * Returns pair of (resultingBitmap, enhancedFaceCount).
     */
    fun enhanceFaces(upscaledBitmap: Bitmap, sourceBitmap: Bitmap? = null): Pair<Bitmap, Int> {
        val boxes: List<RectF> = if (sourceBitmap != null && sourceBitmap.width > 0 && sourceBitmap.height > 0) {
            val sx = upscaledBitmap.width.toFloat() / sourceBitmap.width.toFloat()
            val sy = upscaledBitmap.height.toFloat() / sourceBitmap.height.toFloat()
            detectFaceBounds(sourceBitmap).map { b ->
                RectF(b.left * sx, b.top * sy, b.right * sx, b.bottom * sy)
            }
        } else {
            detectFaceBounds(upscaledBitmap)
        }
        if (boxes.isEmpty()) return upscaledBitmap to 0

        val mutable = if (upscaledBitmap.isMutable) {
            upscaledBitmap
        } else {
            upscaledBitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return upscaledBitmap to 0
        }

        return synchronized(pipelineLock) {
            val session = getOrCreateGfpganSessionLocked()
            var enhancedCount = 0

            for (box in boxes.take(6)) {
                val cropRect = expandRect(box, mutable.width, mutable.height, expandRatio = 0.35f)
                if (cropRect.width() < 24 || cropRect.height() < 24) continue

                val cropW = cropRect.width().toInt()
                val cropH = cropRect.height().toInt()
                val cropX = cropRect.left.toInt()
                val cropY = cropRect.top.toInt()

                val faceCrop = Bitmap.createBitmap(mutable, cropX, cropY, cropW, cropH)
                val restoredCrop = if (session != null) {
                    runGfpganOnCropSquarePreservingAspect(session, faceCrop)
                } else {
                    enhanceFaceMicroClarity(faceCrop)
                }

                if (restoredCrop != null) {
                    featherBlendCrop(mutable, restoredCrop, cropX, cropY, strength = 0.78f)
                    if (restoredCrop !== faceCrop) restoredCrop.recycle()
                    enhancedCount++
                }
                faceCrop.recycle()
            }

            if (mutable !== upscaledBitmap) {
                upscaledBitmap.recycle()
            }
            mutable to enhancedCount
        }
    }

    private fun detectFaceBounds(bitmap: Bitmap): List<RectF> {
        return synchronized(pipelineLock) {
            try {
                val det = getOrCreateDetectorLocked() ?: return emptyList()
                val maxSide = max(bitmap.width, bitmap.height)
                val needsScale = maxSide > DETECTOR_MAX_DIM
                val scale = if (needsScale) DETECTOR_MAX_DIM.toFloat() / maxSide.toFloat() else 1f
                val detBitmap = if (needsScale) {
                    val tw = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
                    val th = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(bitmap, tw, th, true)
                } else {
                    bitmap
                }
                try {
                    val mpImage = BitmapImageBuilder(detBitmap).build()
                    val result = det.detect(mpImage)
                    val invScale = if (needsScale) 1f / scale else 1f
                    result.detections().mapNotNull { detection ->
                        detection.boundingBox()?.let { box ->
                            RectF(
                                box.left * invScale,
                                box.top * invScale,
                                box.right * invScale,
                                box.bottom * invScale
                            )
                        }
                    }
                } finally {
                    if (detBitmap !== bitmap) detBitmap.recycle()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Face detection skipped: ${t.message}")
                emptyList()
            }
        }
    }

    private fun getOrCreateDetectorLocked(): FaceDetector? {
        if (detector != null) return detector
        return runCatching {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(FACE_MODEL_ASSET)
                .build()
            val options = FaceDetector.FaceDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setMinDetectionConfidence(0.55f)
                .build()
            FaceDetector.createFromOptions(context, options).also { detector = it }
        }.onFailure {
            Log.w(TAG, "Failed to init FaceDetector: ${it.message}")
        }.getOrNull()
    }

    private fun getOrCreateGfpganSessionLocked(): OrtSession? {
        if (!modelManager.isFaceRestoreAvailable()) return null
        if (gfpganSession != null) return gfpganSession
        val opts = OrtSession.SessionOptions()
        return try {
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            opts.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 6))
            env.createSession(modelManager.faceRestoreFile().absolutePath, opts).also {
                gfpganSession = it
            }
        } catch (t: Throwable) {
            Log.w(TAG, "GFPGAN session init failed: ${t.message}")
            null
        } finally {
            runCatching { opts.close() }
        }
    }

    /**
     * Letterboxes non-square/edge crops into a centered 1:1 square before 512x512 GFPGAN inference
     * so facial geometry is never squashed, then un-pads back to original crop dimensions.
     */
    private fun runGfpganOnCropSquarePreservingAspect(session: OrtSession, crop: Bitmap): Bitmap? {
        val cw = crop.width
        val ch = crop.height
        val sqSize = max(cw, ch)
        val padX = (sqSize - cw) / 2
        val padY = (sqSize - ch) / 2

        val squareCrop = if (cw == ch) {
            crop
        } else {
            Bitmap.createBitmap(sqSize, sqSize, Bitmap.Config.ARGB_8888).also { sq ->
                val canvas = Canvas(sq)
                // Mirror/fill border background cleanly with edge color by stretching crop underneath, then crisp draw
                canvas.drawBitmap(crop, null, Rect(0, 0, sqSize, sqSize), null)
                canvas.drawBitmap(crop, padX.toFloat(), padY.toFloat(), null)
            }
        }

        val restoredSquare = try {
            runGfpganOnSquareCrop(session, squareCrop)
        } finally {
            if (squareCrop !== crop) squareCrop.recycle()
        } ?: return null

        return if (cw == ch && restoredSquare.width == cw && restoredSquare.height == ch) {
            restoredSquare
        } else {
            val scaledSq = if (restoredSquare.width != sqSize || restoredSquare.height != sqSize) {
                Bitmap.createScaledBitmap(restoredSquare, sqSize, sqSize, true).also {
                    if (it !== restoredSquare) restoredSquare.recycle()
                }
            } else {
                restoredSquare
            }
            if (cw == ch) {
                scaledSq
            } else {
                Bitmap.createBitmap(scaledSq, padX, padY, cw, ch).also {
                    scaledSq.recycle()
                }
            }
        }
    }

    private fun runGfpganOnSquareCrop(session: OrtSession, squareCrop: Bitmap): Bitmap? {
        val resized = if (squareCrop.width == GFPGAN_INPUT_SIZE && squareCrop.height == GFPGAN_INPUT_SIZE) {
            squareCrop
        } else {
            Bitmap.createScaledBitmap(squareCrop, GFPGAN_INPUT_SIZE, GFPGAN_INPUT_SIZE, true)
        }
        val pixels = IntArray(GFPGAN_INPUT_SIZE * GFPGAN_INPUT_SIZE)
        resized.getPixels(pixels, 0, GFPGAN_INPUT_SIZE, 0, 0, GFPGAN_INPUT_SIZE, GFPGAN_INPUT_SIZE)
        if (resized !== squareCrop) resized.recycle()

        val plane = GFPGAN_INPUT_SIZE * GFPGAN_INPUT_SIZE
        val bb = ByteBuffer.allocateDirect(3 * plane * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        val arr = FloatArray(3 * plane)

        // GFPGAN expects [-1, 1] RGB normalization
        for (i in 0 until plane) {
            val p = pixels[i]
            arr[i] = (((p ushr 16) and 0xFF) / 127.5f) - 1f
            arr[plane + i] = (((p ushr 8) and 0xFF) / 127.5f) - 1f
            arr[2 * plane + i] = ((p and 0xFF) / 127.5f) - 1f
        }
        fb.put(arr)
        fb.rewind()

        val inputName = session.inputNames.firstOrNull() ?: "input"
        val tensor = OnnxTensor.createTensor(
            env,
            fb,
            longArrayOf(1L, 3L, GFPGAN_INPUT_SIZE.toLong(), GFPGAN_INPUT_SIZE.toLong())
        )

        val outArr: FloatArray? = try {
            session.run(mapOf(inputName to tensor)).use { res ->
                val outTensor = res[0] as? OnnxTensor ?: return null
                val outFb = outTensor.floatBuffer
                FloatArray(outFb.remaining()).also { outFb.get(it) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "GFPGAN inference failed: ${t.message}")
            null
        } finally {
            tensor.close()
        }

        if (outArr == null || outArr.size < 3 * plane) return null

        for (i in 0 until plane) {
            val r = (((outArr[i] + 1f) * 127.5f) + 0.5f).toInt().coerceIn(0, 255)
            val g = (((outArr[plane + i] + 1f) * 127.5f) + 0.5f).toInt().coerceIn(0, 255)
            val b = (((outArr[2 * plane + i] + 1f) * 127.5f) + 0.5f).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, GFPGAN_INPUT_SIZE, GFPGAN_INPUT_SIZE, Bitmap.Config.ARGB_8888)
    }

    /**
     * Fallback lightweight face clarity enhancement (scale-adaptive separable box unsharp)
     * when optional GFPGAN weights are not installed.
     */
    private fun enhanceFaceMicroClarity(crop: Bitmap): Bitmap {
        val w = crop.width
        val h = crop.height
        val src = IntArray(w * h)
        crop.getPixels(src, 0, w, 0, 0, w, h)
        val radius = (min(w, h) / 160).coerceIn(1, 4)
        val amount = 0.25f

        val horiz = IntArray(w * h)
        val winSize = 2 * radius + 1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var sr = 0
                var sg = 0
                var sb = 0
                for (dx in -radius..radius) {
                    val sx = (x + dx).coerceIn(0, w - 1)
                    val p = src[row + sx]
                    sr += (p ushr 16) and 0xFF
                    sg += (p ushr 8) and 0xFF
                    sb += p and 0xFF
                }
                horiz[row + x] = ((sr / winSize) shl 16) or ((sg / winSize) shl 8) or (sb / winSize)
            }
        }

        val out = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var sr = 0
                var sg = 0
                var sb = 0
                for (dy in -radius..radius) {
                    val sy = (y + dy).coerceIn(0, h - 1)
                    val p = horiz[sy * w + x]
                    sr += (p ushr 16) and 0xFF
                    sg += (p ushr 8) and 0xFF
                    sb += p and 0xFF
                }
                val blurR = sr / winSize.toFloat()
                val blurG = sg / winSize.toFloat()
                val blurB = sb / winSize.toFloat()

                val c = src[row + x]
                val ca = (c ushr 24) and 0xFF
                val cr = (c ushr 16) and 0xFF
                val cg = (c ushr 8) and 0xFF
                val cb = c and 0xFF

                val nr = (cr + (cr - blurR) * amount + 0.5f).toInt().coerceIn(0, 255)
                val ng = (cg + (cg - blurG) * amount + 0.5f).toInt().coerceIn(0, 255)
                val nb = (cb + (cb - blurB) * amount + 0.5f).toInt().coerceIn(0, 255)
                out[row + x] = (ca shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun featherBlendCrop(
        target: Bitmap,
        crop: Bitmap,
        offsetX: Int,
        offsetY: Int,
        strength: Float
    ) {
        val cw = crop.width
        val ch = crop.height
        val dstPixels = IntArray(cw * ch)
        val srcPixels = IntArray(cw * ch)
        target.getPixels(dstPixels, 0, cw, offsetX, offsetY, cw, ch)
        crop.getPixels(srcPixels, 0, cw, 0, 0, cw, ch)

        val halfW = cw * 0.5f
        val halfH = ch * 0.5f

        for (y in 0 until ch) {
            val ny = (y - halfH) / halfH
            for (x in 0 until cw) {
                val nx = (x - halfW) / halfW
                val dist = hypot(nx.toDouble(), ny.toDouble()).toFloat()
                if (dist >= 1.0f) continue

                val mask = if (dist <= 0.65f) {
                    strength
                } else {
                    val t = 1f - ((dist - 0.65f) / 0.35f)
                    strength * t * t * (3f - 2f * t) // smoothstep falloff
                }

                val idx = y * cw + x
                val d = dstPixels[idx]
                val s = srcPixels[idx]
                val da = (d ushr 24) and 0xFF
                val r = (((d ushr 16) and 0xFF) * (1f - mask) + ((s ushr 16) and 0xFF) * mask + 0.5f).toInt().coerceIn(0, 255)
                val g = (((d ushr 8) and 0xFF) * (1f - mask) + ((s ushr 8) and 0xFF) * mask + 0.5f).toInt().coerceIn(0, 255)
                val b = ((d and 0xFF) * (1f - mask) + (s and 0xFF) * mask + 0.5f).toInt().coerceIn(0, 255)
                dstPixels[idx] = (da shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        target.setPixels(dstPixels, 0, cw, offsetX, offsetY, cw, ch)
    }

    private fun expandRect(box: RectF, maxW: Int, maxH: Int, expandRatio: Float): RectF {
        val dx = box.width() * expandRatio
        val dy = box.height() * expandRatio
        return RectF(
            max(0f, box.left - dx),
            max(0f, box.top - dy),
            min(maxW.toFloat(), box.right + dx),
            min(maxH.toFloat(), box.bottom + dy)
        )
    }

    fun release() {
        synchronized(pipelineLock) {
            runCatching { detector?.close() }
            detector = null
            runCatching { gfpganSession?.close() }
            gfpganSession = null
        }
    }
}
