package com.pixelforge.app.engine

import android.content.Context
import android.graphics.Bitmap
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

/**
 * Selective face detection & restoration pipeline.
 *
 * 1. Detects faces in <30ms using MediaPipe BlazeFace (`blaze_face_short_range.tflite`).
 * 2. If the optional GFPGAN ONNX pack is installed, restores each cropped face at 512x512
 *    and blends it back seamlessly with an elliptical radial feather mask.
 * 3. Otherwise, applies fast local micro-clarity & eye/skin detail enhancement on face regions
 *    while preserving natural identity.
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
    }

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
     * Enhance detected faces in-place on [upscaledBitmap] (must be mutable or copied).
     * Returns pair of (resultingBitmap, enhancedFaceCount).
     */
    fun enhanceFaces(upscaledBitmap: Bitmap): Pair<Bitmap, Int> {
        val boxes = detectFaceBounds(upscaledBitmap)
        if (boxes.isEmpty()) return upscaledBitmap to 0

        val mutable = if (upscaledBitmap.isMutable) {
            upscaledBitmap
        } else {
            upscaledBitmap.copy(Bitmap.Config.ARGB_8888, true)
        }

        val session = getOrCreateGfpganSession()
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
                runGfpganOnCrop(session, faceCrop)
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
        return mutable to enhancedCount
    }

    private fun detectFaceBounds(bitmap: Bitmap): List<RectF> {
        return try {
            val det = getOrCreateDetector() ?: return emptyList()
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = det.detect(mpImage)
            result.detections().mapNotNull { it.boundingBox() }
        } catch (t: Throwable) {
            Log.w(TAG, "Face detection skipped: ${t.message}")
            emptyList()
        }
    }

    @Synchronized
    private fun getOrCreateDetector(): FaceDetector? {
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

    @Synchronized
    private fun getOrCreateGfpganSession(): OrtSession? {
        if (!modelManager.isFaceRestoreAvailable()) return null
        if (gfpganSession != null) return gfpganSession
        return runCatching {
            val opts = OrtSession.SessionOptions()
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            opts.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 6))
            env.createSession(modelManager.faceRestoreFile().absolutePath, opts).also {
                gfpganSession = it
                opts.close()
            }
        }.onFailure {
            Log.w(TAG, "GFPGAN session init failed: ${it.message}")
        }.getOrNull()
    }

    private fun runGfpganOnCrop(session: OrtSession, crop: Bitmap): Bitmap? {
        val resized = Bitmap.createScaledBitmap(crop, GFPGAN_INPUT_SIZE, GFPGAN_INPUT_SIZE, true)
        val pixels = IntArray(GFPGAN_INPUT_SIZE * GFPGAN_INPUT_SIZE)
        resized.getPixels(pixels, 0, GFPGAN_INPUT_SIZE, 0, 0, GFPGAN_INPUT_SIZE, GFPGAN_INPUT_SIZE)

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
            if (resized !== crop) resized.recycle()
        }

        if (outArr == null || outArr.size < 3 * plane) return null

        for (i in 0 until plane) {
            val r = (((outArr[i] + 1f) * 127.5f) + 0.5f).toInt().coerceIn(0, 255)
            val g = (((outArr[plane + i] + 1f) * 127.5f) + 0.5f).toInt().coerceIn(0, 255)
            val b = (((outArr[2 * plane + i] + 1f) * 127.5f) + 0.5f).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val restored512 = Bitmap.createBitmap(pixels, GFPGAN_INPUT_SIZE, GFPGAN_INPUT_SIZE, Bitmap.Config.ARGB_8888)
        return if (crop.width != GFPGAN_INPUT_SIZE || crop.height != GFPGAN_INPUT_SIZE) {
            val scaledBack = Bitmap.createScaledBitmap(restored512, crop.width, crop.height, true)
            restored512.recycle()
            scaledBack
        } else {
            restored512
        }
    }

    /**
     * Fallback lightweight face clarity enhancement (local unsharp contrast on luminance)
     * when optional GFPGAN weights are not installed.
     */
    private fun enhanceFaceMicroClarity(crop: Bitmap): Bitmap {
        val w = crop.width
        val h = crop.height
        val src = IntArray(w * h)
        crop.getPixels(src, 0, w, 0, 0, w, h)
        val out = src.copyOf()
        val amount = 0.22f

        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val c = src[row + x]
                val u = src[(y - 1) * w + x]
                val d = src[(y + 1) * w + x]
                val l = src[row + x - 1]
                val r = src[row + x + 1]

                val blurR = (((u ushr 16) and 0xFF) + ((d ushr 16) and 0xFF) + ((l ushr 16) and 0xFF) + ((r ushr 16) and 0xFF)) * 0.25f
                val blurG = (((u ushr 8) and 0xFF) + ((d ushr 8) and 0xFF) + ((l ushr 8) and 0xFF) + ((r ushr 8) and 0xFF)) * 0.25f
                val blurB = ((u and 0xFF) + (d and 0xFF) + (l and 0xFF) + (r and 0xFF)) * 0.25f

                val cr = (c ushr 16) and 0xFF
                val cg = (c ushr 8) and 0xFF
                val cb = c and 0xFF
                val ca = (c ushr 24) and 0xFF

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
        runCatching { detector?.close() }
        detector = null
        runCatching { gfpganSession?.close() }
        gfpganSession = null
    }
}
