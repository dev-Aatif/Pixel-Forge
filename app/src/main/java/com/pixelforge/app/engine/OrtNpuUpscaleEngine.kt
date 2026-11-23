package com.pixelforge.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.util.Log
import com.pixelforge.app.domain.model.UpscaleConfig
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * ONNX Runtime Mobile super-resolution engine.
 * Prioritizes Qualcomm QNN HTP NPU on Snapdragon SoCs, then Android NNAPI (FP16),
 * falling back to multi-threaded XNNPACK CPU.
 */
@Singleton
class OrtNpuUpscaleEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : UpscaleEngine {

    companion object {
        private const val TAG = "OrtNpuUpscaleEngine"
        private const val DEFAULT_TILE_SIZE = 256
        private const val TILE_OVERLAP = 16
    }

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val cancelled = AtomicBoolean(false)
    private val sessionLock = Any()

    private var cachedSession: OrtSession? = null
    private var cachedKey: String = ""

    @Volatile
    override var activeBackend: ComputeBackend = ComputeBackend.CPU
        private set

    override suspend fun upscale(
        bitmap: Bitmap,
        config: UpscaleConfig,
        onProgress: (Float) -> Unit
    ): Bitmap? {
        val assetPath = config.modelType.onnxAssetPath ?: return null
        cancelled.set(false)

        val session = getOrCreateSession(assetPath, config.useNpu) ?: return null
        val inputName = session.inputNames.firstOrNull() ?: "input"

        val inW = bitmap.width
        val inH = bitmap.height
        val modelScale = config.modelType.nativeScale
        val outW = inW * modelScale
        val outH = inH * modelScale

        val tileSize = if (config.tileSize > 0) config.tileSize else DEFAULT_TILE_SIZE
        val tilesX = ceil(inW.toDouble() / tileSize).toInt().coerceAtLeast(1)
        val tilesY = ceil(inH.toDouble() / tileSize).toInt().coerceAtLeast(1)
        val totalTiles = tilesX * tilesY

        val inPixels = IntArray(inW * inH)
        bitmap.getPixels(inPixels, 0, inW, 0, 0, inW, inH)

        val outPixels = IntArray(outW * outH)
        var hasAlpha = false
        for (p in inPixels) {
            if ((p ushr 24) < 252) {
                hasAlpha = true
                break
            }
        }

        var processed = 0
        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                if (cancelled.get()) return null

                val x0 = tx * tileSize
                val y0 = ty * tileSize
                val x1 = min(x0 + tileSize, inW)
                val y1 = min(y0 + tileSize, inH)

                val px0 = max(x0 - TILE_OVERLAP, 0)
                val py0 = max(y0 - TILE_OVERLAP, 0)

                val padTop = TILE_OVERLAP - (y0 - py0)
                val padLeft = TILE_OVERLAP - (x0 - px0)

                val tileInW = (x1 - x0) + 2 * TILE_OVERLAP
                val tileInH = (y1 - y0) + 2 * TILE_OVERLAP

                val inputBuffer = buildTileNchwFloatBuffer(
                    inPixels = inPixels,
                    inW = inW,
                    inH = inH,
                    px0 = px0,
                    py0 = py0,
                    padLeft = padLeft,
                    padTop = padTop,
                    tileW = tileInW,
                    tileH = tileInH
                )

                val shape = longArrayOf(1L, 3L, tileInH.toLong(), tileInW.toLong())
                val tensor = OnnxTensor.createTensor(env, inputBuffer, shape)
                val cropOutPixels: FloatArray? = try {
                    session.run(mapOf(inputName to tensor)).use { result ->
                        val outTensor = result[0] as? OnnxTensor ?: return null
                        val fb = outTensor.floatBuffer
                        val arr = FloatArray(fb.remaining())
                        fb.get(arr)
                        arr
                    }
                } catch (e: OrtException) {
                    Log.e(TAG, "ORT tile inference failed on $activeBackend: ${e.message}")
                    return null
                } finally {
                    tensor.close()
                }

                if (cropOutPixels == null) return null

                val tileOutW = tileInW * modelScale
                val tileOutH = tileInH * modelScale
                val cropOffset = TILE_OVERLAP * modelScale
                val coreW = (x1 - x0) * modelScale
                val coreH = (y1 - y0) * modelScale
                val outX0 = x0 * modelScale
                val outY0 = y0 * modelScale
                val planeSize = tileOutW * tileOutH

                for (cy in 0 until coreH) {
                    val srcY = cy + cropOffset
                    val dstRowOffset = (outY0 + cy) * outW + outX0
                    val srcRowOffset = srcY * tileOutW + cropOffset
                    for (cx in 0 until coreW) {
                        val idx = srcRowOffset + cx
                        val r = (cropOutPixels[idx] * 255f + 0.5f).toInt().coerceIn(0, 255)
                        val g = (cropOutPixels[planeSize + idx] * 255f + 0.5f).toInt().coerceIn(0, 255)
                        val b = (cropOutPixels[2 * planeSize + idx] * 255f + 0.5f).toInt().coerceIn(0, 255)
                        outPixels[dstRowOffset + cx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }

                processed++
                onProgress((processed.toFloat() / totalTiles).coerceIn(0f, 0.99f))
            }
        }

        if (hasAlpha) {
            upscaleAlphaInto(inPixels, inW, inH, outPixels, outW, outH, modelScale)
        }

        val full4x = Bitmap.createBitmap(outPixels, outW, outH, Bitmap.Config.ARGB_8888)
        return if (config.scaleFactor == 2) {
            val targetW = (inW * 2).coerceAtLeast(1)
            val targetH = (inH * 2).coerceAtLeast(1)
            val downsampled = Bitmap.createScaledBitmap(full4x, targetW, targetH, true)
            if (downsampled !== full4x) full4x.recycle()
            downsampled
        } else {
            full4x
        }
    }

    override fun cancel() {
        cancelled.set(true)
    }

    override fun release() {
        synchronized(sessionLock) {
            runCatching { cachedSession?.close() }
            cachedSession = null
            cachedKey = ""
        }
    }

    private fun getOrCreateSession(assetPath: String, useNpu: Boolean): OrtSession? {
        val key = "$assetPath|$useNpu"
        synchronized(sessionLock) {
            if (cachedSession != null && cachedKey == key) {
                return cachedSession
            }

            runCatching { cachedSession?.close() }
            cachedSession = null
            cachedKey = ""

            val modelBytes = runCatching {
                context.assets.open(assetPath).use { it.readBytes() }
            }.getOrNull() ?: return null

            // Attempt 1: Qualcomm QNN HTP NPU (on Snapdragon SoCs)
            if (useNpu && isQualcommSoc()) {
                tryCreateSession(modelBytes, ComputeBackend.NPU_QNN) { opts ->
                    opts.addQnn(
                        mapOf(
                            "backend_type" to "htp",
                            "htp_performance_mode" to "burst"
                        )
                    )
                }?.let {
                    cachedSession = it
                    cachedKey = key
                    activeBackend = ComputeBackend.NPU_QNN
                    return it
                }
            }

            // Attempt 2: Android NNAPI (FP16 NPU/DSP)
            if (useNpu) {
                tryCreateSession(modelBytes, ComputeBackend.NPU_NNAPI) { opts ->
                    opts.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
                }?.let {
                    cachedSession = it
                    cachedKey = key
                    activeBackend = ComputeBackend.NPU_NNAPI
                    return it
                }
            }

            // Attempt 3: Multi-threaded CPU (XNNPACK)
            return tryCreateSession(modelBytes, ComputeBackend.CPU) { opts ->
                opts.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 8))
            }?.also {
                cachedSession = it
                cachedKey = key
                activeBackend = ComputeBackend.CPU
            }
        }
    }

    private fun tryCreateSession(
        modelBytes: ByteArray,
        targetBackend: ComputeBackend,
        configure: (OrtSession.SessionOptions) -> Unit
    ): OrtSession? {
        val opts = OrtSession.SessionOptions()
        return try {
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            configure(opts)
            env.createSession(modelBytes, opts).also {
                Log.i(TAG, "Created ORT session with backend: $targetBackend")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Backend $targetBackend unavailable: ${t.message}")
            null
        } finally {
            opts.close()
        }
    }

    private fun isQualcommSoc(): Boolean {
        val manufacturer = Build.SOC_MANUFACTURER.lowercase()
        return manufacturer.contains("qti") ||
            manufacturer.contains("qualcomm") ||
            Build.HARDWARE.lowercase().contains("qcom")
    }

    private fun buildTileNchwFloatBuffer(
        inPixels: IntArray,
        inW: Int,
        inH: Int,
        px0: Int,
        py0: Int,
        padLeft: Int,
        padTop: Int,
        tileW: Int,
        tileH: Int
    ): FloatBuffer {
        val planeSize = tileW * tileH
        val bb = ByteBuffer.allocateDirect(3 * planeSize * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        val inv255 = 1f / 255f

        val arr = FloatArray(3 * planeSize)
        for (ty in 0 until tileH) {
            val srcY = (py0 + ty - padTop).coerceIn(0, inH - 1)
            val rowBase = srcY * inW
            val dstRow = ty * tileW
            for (tx in 0 until tileW) {
                val srcX = (px0 + tx - padLeft).coerceIn(0, inW - 1)
                val p = inPixels[rowBase + srcX]
                val idx = dstRow + tx
                arr[idx] = ((p ushr 16) and 0xFF) * inv255
                arr[planeSize + idx] = ((p ushr 8) and 0xFF) * inv255
                arr[2 * planeSize + idx] = (p and 0xFF) * inv255
            }
        }
        fb.put(arr)
        fb.rewind()
        return fb
    }

    private fun upscaleAlphaInto(
        inPixels: IntArray,
        inW: Int,
        inH: Int,
        outPixels: IntArray,
        outW: Int,
        outH: Int,
        scale: Int
    ) {
        val invScale = 1f / scale.toFloat()
        for (y in 0 until outH) {
            val sy = ((y + 0.5f) * invScale - 0.5f).coerceIn(0f, (inH - 1).toFloat())
            val y0 = sy.toInt()
            val y1 = min(y0 + 1, inH - 1)
            val fy = sy - y0
            for (x in 0 until outW) {
                val sx = ((x + 0.5f) * invScale - 0.5f).coerceIn(0f, (inW - 1).toFloat())
                val x0 = sx.toInt()
                val x1 = min(x0 + 1, inW - 1)
                val fx = sx - x0

                val a00 = Color.alpha(inPixels[y0 * inW + x0])
                val a10 = Color.alpha(inPixels[y0 * inW + x1])
                val a01 = Color.alpha(inPixels[y1 * inW + x0])
                val a11 = Color.alpha(inPixels[y1 * inW + x1])

                val top = a00 + (a10 - a00) * fx
                val bot = a01 + (a11 - a01) * fx
                val a = (top + (bot - top) * fy + 0.5f).toInt().coerceIn(0, 255)
                val idx = y * outW + x
                outPixels[idx] = (outPixels[idx] and 0x00FFFFFF) or (a shl 24)
            }
        }
    }
}
