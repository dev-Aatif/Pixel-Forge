package com.pixelforge.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import com.pixelforge.app.bridge.NativeLoader
import com.pixelforge.app.bridge.RealesrganBridge
import com.pixelforge.app.domain.model.UpscaleConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
 * Prioritizes Qualcomm QNN HTP NPU (when QNN EP libs are packaged), then Android NNAPI (FP16),
 * falling back to XNNPACK-accelerated CPU.
 *
 * Uses constant-shape padded tiles (eliminating NNAPI/QNN dynamic-shape recompilation),
 * reusable direct input/output buffers, and JNI zero-copy bitmap packing/unpacking to avoid
 * full-frame Java IntArray/FloatArray heap OOMs.
 */
@Singleton
class OrtNpuUpscaleEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bridge: RealesrganBridge
) : UpscaleEngine {

    constructor(context: Context) : this(context, RealesrganBridge())

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
    private var activeSessionUsers = 0
    private var pendingRelease = false

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

        val result = runWithSession(assetPath, config.useNpu, bitmap, config, onProgress)
        if (result == null && !cancelled.get() && config.useNpu && activeBackend != ComputeBackend.CPU) {
            Log.w(TAG, "Retrying ORT inference on XNNPACK CPU after $activeBackend failure")
            invalidateSession()
            return runWithSession(assetPath, useNpu = false, bitmap, config, onProgress)
        }
        return result
    }

    private fun runWithSession(
        assetPath: String,
        useNpu: Boolean,
        bitmap: Bitmap,
        config: UpscaleConfig,
        onProgress: (Float) -> Unit
    ): Bitmap? {
        val session = acquireSession(assetPath, useNpu) ?: return null
        return try {
            executeTiledInference(session, bitmap, config, onProgress)
        } finally {
            releaseSessionRef()
        }
    }

    private fun executeTiledInference(
        session: OrtSession,
        bitmap: Bitmap,
        config: UpscaleConfig,
        onProgress: (Float) -> Unit
    ): Bitmap? {
        val inputName = session.inputNames.firstOrNull() ?: "input"
        val outputName = session.outputNames.firstOrNull() ?: "output"

        val inW = bitmap.width
        val inH = bitmap.height
        if (inW <= 0 || inH <= 0) return null

        val modelScale = config.modelType.nativeScale
        val targetScale = if (config.scaleFactor == 2 && modelScale == 4) 2 else modelScale
        val downsample2x = (config.scaleFactor == 2 && modelScale == 4)

        val outW = (inW * targetScale).coerceAtLeast(1)
        val outH = (inH * targetScale).coerceAtLeast(1)

        val outBitmap = try {
            Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            Log.e(TAG, "OOM allocating output bitmap (${outW}x${outH})")
            return null
        }

        val tileSize = if (config.tileSize > 0) config.tileSize else DEFAULT_TILE_SIZE
        val fixedTileInW = tileSize + 2 * TILE_OVERLAP
        val fixedTileInH = tileSize + 2 * TILE_OVERLAP
        val fixedTileOutW = fixedTileInW * modelScale
        val fixedTileOutH = fixedTileInH * modelScale

        val tilesX = ceil(inW.toDouble() / tileSize).toInt().coerceAtLeast(1)
        val tilesY = ceil(inH.toDouble() / tileSize).toInt().coerceAtLeast(1)
        val totalTiles = tilesX * tilesY

        val useNativeOps = NativeLoader.isLoaded && bitmap.config == Bitmap.Config.ARGB_8888
        val hasAlpha = if (useNativeOps) {
            bridge.hasTransparency(bitmap)
        } else {
            hasAlphaKotlin(bitmap)
        }

        val inPlane = fixedTileInW * fixedTileInH
        val outPlane = fixedTileOutW * fixedTileOutH
        val inputByteBuf = ByteBuffer.allocateDirect(3 * inPlane * 4).order(ByteOrder.nativeOrder())
        val inputFloatBuf = inputByteBuf.asFloatBuffer()
        val outputByteBuf = ByteBuffer.allocateDirect(3 * outPlane * 4).order(ByteOrder.nativeOrder())
        val outputFloatBuf = outputByteBuf.asFloatBuffer()

        val inShape = longArrayOf(1L, 3L, fixedTileInH.toLong(), fixedTileInW.toLong())
        val outShape = longArrayOf(1L, 3L, fixedTileOutH.toLong(), fixedTileOutW.toLong())

        val inputTensor = OnnxTensor.createTensor(env, inputFloatBuf, inShape)
        var pinnedOutputTensor: OnnxTensor? = OnnxTensor.createTensor(env, outputFloatBuf, outShape)
        var usePinnedOutputs = true

        // Reusable Kotlin fallback scratch buffers only allocated if native bridge isn't loaded (e.g. JVM tests)
        var fallbackInFloat: FloatArray? = null
        var fallbackInInt: IntArray? = null
        var fallbackRowInt: IntArray? = null

        var completed = false
        try {
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

                    if (useNativeOps) {
                        val packed = bridge.packTileNchw(
                            inBitmap = bitmap,
                            px0 = px0,
                            py0 = py0,
                            padLeft = padLeft,
                            padTop = padTop,
                            tileW = fixedTileInW,
                            tileH = fixedTileInH,
                            unpremultiply = hasAlpha,
                            nchwDirectByteBuffer = inputByteBuf
                        )
                        if (!packed) return null
                    } else {
                        if (fallbackInFloat == null) fallbackInFloat = FloatArray(3 * inPlane)
                        if (fallbackInInt == null) fallbackInInt = IntArray(inW)
                        packTileNchwKotlin(
                            bitmap = bitmap,
                            inW = inW,
                            inH = inH,
                            px0 = px0,
                            py0 = py0,
                            padLeft = padLeft,
                            padTop = padTop,
                            tileW = fixedTileInW,
                            tileH = fixedTileInH,
                            unpremultiply = hasAlpha,
                            rowScratch = fallbackInInt,
                            arrScratch = fallbackInFloat,
                            dstFloatBuf = inputFloatBuf
                        )
                    }
                    inputFloatBuf.rewind()

                    val inferenceOk = try {
                        if (usePinnedOutputs && pinnedOutputTensor != null) {
                            session.run(
                                mapOf(inputName to inputTensor),
                                mapOf(outputName to pinnedOutputTensor)
                            ).use { /* output written directly into outputFloatBuf */ }
                            true
                        } else {
                            runUnpinnedIntoBuffer(session, inputName, inputTensor, outputFloatBuf)
                        }
                    } catch (e: OrtException) {
                        if (usePinnedOutputs) {
                            Log.w(TAG, "Pinned output run failed on $activeBackend, falling back to standard run: ${e.message}")
                            usePinnedOutputs = false
                            pinnedOutputTensor?.close()
                            pinnedOutputTensor = null
                            runUnpinnedIntoBuffer(session, inputName, inputTensor, outputFloatBuf)
                        } else {
                            Log.e(TAG, "ORT tile inference failed on $activeBackend: ${e.message}")
                            false
                        }
                    }

                    if (!inferenceOk) return null

                    val cropOffset = TILE_OVERLAP * modelScale
                    val coreW = (x1 - x0) * modelScale
                    val coreH = (y1 - y0) * modelScale
                    val outX0 = x0 * modelScale
                    val outY0 = y0 * modelScale

                    if (useNativeOps) {
                        val written = bridge.writeNchwTileToBitmap(
                            nchwDirectByteBuffer = outputByteBuf,
                            tileOutW = fixedTileOutW,
                            tileOutH = fixedTileOutH,
                            cropOffset = cropOffset,
                            coreW = coreW,
                            coreH = coreH,
                            outX0 = outX0,
                            outY0 = outY0,
                            downsample2x = downsample2x,
                            outBitmap = outBitmap
                        )
                        if (!written) return null
                    } else {
                        val targetCoreW = if (downsample2x) coreW / 2 else coreW
                        if (fallbackRowInt == null || fallbackRowInt.size < targetCoreW) {
                            fallbackRowInt = IntArray(targetCoreW)
                        }
                        writeNchwTileToBitmapKotlin(
                            srcFloatBuf = outputFloatBuf,
                            tileOutW = fixedTileOutW,
                            tileOutH = fixedTileOutH,
                            cropOffset = cropOffset,
                            coreW = coreW,
                            coreH = coreH,
                            outX0 = outX0,
                            outY0 = outY0,
                            downsample2x = downsample2x,
                            rowScratch = fallbackRowInt,
                            outBitmap = outBitmap
                        )
                    }

                    processed++
                    onProgress((processed.toFloat() / totalTiles).coerceIn(0f, 0.99f))
                }
            }

            if (hasAlpha) {
                if (useNativeOps) {
                    bridge.upscaleAlphaToBitmap(bitmap, outBitmap, targetScale)
                } else {
                    upscaleAlphaToBitmapKotlin(bitmap, outBitmap, targetScale)
                }
            }

            completed = true
            return outBitmap
        } finally {
            inputTensor.close()
            pinnedOutputTensor?.close()
            if (!completed) {
                outBitmap.recycle()
            }
        }
    }

    private fun runUnpinnedIntoBuffer(
        session: OrtSession,
        inputName: String,
        inputTensor: OnnxTensor,
        dstFloatBuf: FloatBuffer
    ): Boolean {
        return try {
            session.run(mapOf(inputName to inputTensor)).use { result ->
                val outTensor = result[0] as? OnnxTensor ?: return false
                val fb = outTensor.floatBuffer
                dstFloatBuf.clear()
                if (fb.remaining() > dstFloatBuf.capacity()) return false
                dstFloatBuf.put(fb)
                dstFloatBuf.rewind()
                true
            }
        } catch (e: OrtException) {
            Log.e(TAG, "ORT unpinned run failed on $activeBackend: ${e.message}")
            false
        }
    }

    override fun cancel() {
        cancelled.set(true)
    }

    override fun release() {
        synchronized(sessionLock) {
            if (activeSessionUsers > 0) {
                pendingRelease = true
                return
            }
            closeCachedSessionLocked()
        }
    }

    private fun invalidateSession() {
        synchronized(sessionLock) {
            if (activeSessionUsers == 0) {
                closeCachedSessionLocked()
            } else {
                pendingRelease = true
            }
        }
    }

    private fun acquireSession(assetPath: String, useNpu: Boolean): OrtSession? {
        val key = "$assetPath|$useNpu"
        synchronized(sessionLock) {
            if (cachedSession != null && cachedKey == key) {
                activeSessionUsers++
                return cachedSession
            }

            if (activeSessionUsers > 0) {
                // Should not occur because EngineSelector serializes runs, but guard safely
                return cachedSession
            }

            closeCachedSessionLocked()

            val modelBytes = runCatching {
                context.assets.open(assetPath).use { it.readBytes() }
            }.getOrNull() ?: return null

            // Attempt 1: Qualcomm QNN HTP NPU (only if QNN EP shared lib is actually packaged)
            if (useNpu && isQualcommSoc() && hasQnnProviderLibrary()) {
                tryCreateSession(modelBytes, ComputeBackend.NPU_QNN) { opts ->
                    opts.addQnn(
                        mapOf(
                            "backend_type" to "htp",
                            "htp_performance_mode" to "burst",
                            "enable_htp_fp16_precision" to "1"
                        )
                    )
                }?.let {
                    cachedSession = it
                    cachedKey = key
                    activeBackend = ComputeBackend.NPU_QNN
                    activeSessionUsers++
                    return it
                }
            }

            // Attempt 2: Android NNAPI (FP16 NPU/DSP) with static-shape padded tiles
            if (useNpu) {
                tryCreateSession(modelBytes, ComputeBackend.NPU_NNAPI) { opts ->
                    opts.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
                }?.let {
                    cachedSession = it
                    cachedKey = key
                    activeBackend = ComputeBackend.NPU_NNAPI
                    activeSessionUsers++
                    return it
                }
            }

            // Attempt 3: XNNPACK ARM NEON CPU (with recommended spinning disabled to avoid thread contention)
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
            return tryCreateSession(modelBytes, ComputeBackend.CPU) { opts ->
                opts.addConfigEntry("session.intra_op.allow_spinning", "0")
                opts.setIntraOpNumThreads(1)
                opts.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
            }?.also {
                cachedSession = it
                cachedKey = key
                activeBackend = ComputeBackend.CPU
                activeSessionUsers++
            }
        }
    }

    private fun releaseSessionRef() {
        synchronized(sessionLock) {
            activeSessionUsers = (activeSessionUsers - 1).coerceAtLeast(0)
            if (activeSessionUsers == 0 && pendingRelease) {
                closeCachedSessionLocked()
            }
        }
    }

    private fun closeCachedSessionLocked() {
        runCatching { cachedSession?.close() }
        cachedSession = null
        cachedKey = ""
        pendingRelease = false
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
            runCatching { opts.close() }
        }
    }

    private fun hasQnnProviderLibrary(): Boolean {
        val nativeDir = context.applicationInfo.nativeLibraryDir ?: return false
        return File(nativeDir, "libonnxruntime_providers_qnn.so").exists()
    }

    private fun isQualcommSoc(): Boolean {
        val manufacturer = Build.SOC_MANUFACTURER.lowercase()
        return manufacturer.contains("qti") ||
            manufacturer.contains("qualcomm") ||
            Build.HARDWARE.lowercase().contains("qcom")
    }

    private fun hasAlphaKotlin(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val row = IntArray(w)
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if ((row[x] ushr 24) < 252) return true
            }
        }
        return false
    }

    private fun packTileNchwKotlin(
        bitmap: Bitmap,
        inW: Int,
        inH: Int,
        px0: Int,
        py0: Int,
        padLeft: Int,
        padTop: Int,
        tileW: Int,
        tileH: Int,
        unpremultiply: Boolean,
        rowScratch: IntArray,
        arrScratch: FloatArray,
        dstFloatBuf: FloatBuffer
    ) {
        val planeSize = tileW * tileH
        val inv255 = 1f / 255f
        for (ty in 0 until tileH) {
            val srcY = (py0 + ty - padTop).coerceIn(0, inH - 1)
            bitmap.getPixels(rowScratch, 0, inW, 0, srcY, inW, 1)
            val dstRow = ty * tileW
            for (tx in 0 until tileW) {
                val srcX = (px0 + tx - padLeft).coerceIn(0, inW - 1)
                val p = rowScratch[srcX]
                var r = (p ushr 16) and 0xFF
                var g = (p ushr 8) and 0xFF
                var b = p and 0xFF
                if (unpremultiply) {
                    val a = (p ushr 24) and 0xFF
                    if (a in 1..254) {
                        r = min(255, (r * 255 + a / 2) / a)
                        g = min(255, (g * 255 + a / 2) / a)
                        b = min(255, (b * 255 + a / 2) / a)
                    }
                }
                val idx = dstRow + tx
                arrScratch[idx] = r * inv255
                arrScratch[planeSize + idx] = g * inv255
                arrScratch[2 * planeSize + idx] = b * inv255
            }
        }
        dstFloatBuf.clear()
        dstFloatBuf.put(arrScratch, 0, 3 * planeSize)
        dstFloatBuf.rewind()
    }

    private fun writeNchwTileToBitmapKotlin(
        srcFloatBuf: FloatBuffer,
        tileOutW: Int,
        tileOutH: Int,
        cropOffset: Int,
        coreW: Int,
        coreH: Int,
        outX0: Int,
        outY0: Int,
        downsample2x: Boolean,
        rowScratch: IntArray,
        outBitmap: Bitmap
    ) {
        val planeSize = tileOutW * tileOutH
        if (!downsample2x) {
            for (cy in 0 until coreH) {
                val srcRowOffset = (cy + cropOffset) * tileOutW + cropOffset
                for (cx in 0 until coreW) {
                    val idx = srcRowOffset + cx
                    val r = (srcFloatBuf.get(idx) * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val g = (srcFloatBuf.get(planeSize + idx) * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val b = (srcFloatBuf.get(2 * planeSize + idx) * 255f + 0.5f).toInt().coerceIn(0, 255)
                    rowScratch[cx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                outBitmap.setPixels(rowScratch, 0, coreW, outX0, outY0 + cy, coreW, 1)
            }
        } else {
            val halfW = coreW / 2
            val halfH = coreH / 2
            val dstX0 = outX0 / 2
            val dstY0 = outY0 / 2
            for (dy in 0 until halfH) {
                val sy0 = (cropOffset + dy * 2) * tileOutW + cropOffset
                val sy1 = sy0 + tileOutW
                for (dx in 0 until halfW) {
                    val sx = dx * 2
                    val i00 = sy0 + sx
                    val i10 = i00 + 1
                    val i01 = sy1 + sx
                    val i11 = i01 + 1
                    val r = ((srcFloatBuf.get(i00) + srcFloatBuf.get(i10) + srcFloatBuf.get(i01) + srcFloatBuf.get(i11)) * 0.25f * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val g = ((srcFloatBuf.get(planeSize + i00) + srcFloatBuf.get(planeSize + i10) + srcFloatBuf.get(planeSize + i01) + srcFloatBuf.get(planeSize + i11)) * 0.25f * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val b = ((srcFloatBuf.get(2 * planeSize + i00) + srcFloatBuf.get(2 * planeSize + i10) + srcFloatBuf.get(2 * planeSize + i01) + srcFloatBuf.get(2 * planeSize + i11)) * 0.25f * 255f + 0.5f).toInt().coerceIn(0, 255)
                    rowScratch[dx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                outBitmap.setPixels(rowScratch, 0, halfW, dstX0, dstY0 + dy, halfW, 1)
            }
        }
    }

    private fun upscaleAlphaToBitmapKotlin(inBitmap: Bitmap, outBitmap: Bitmap, scale: Int) {
        val inW = inBitmap.width
        val inH = inBitmap.height
        val outW = outBitmap.width
        val outH = outBitmap.height
        val invScale = 1f / scale.toFloat()
        val row0 = IntArray(inW)
        val row1 = IntArray(inW)
        val outRow = IntArray(outW)
        var cachedY0 = -1
        var cachedY1 = -1

        for (y in 0 until outH) {
            val sy = ((y + 0.5f) * invScale - 0.5f).coerceIn(0f, (inH - 1).toFloat())
            val y0 = sy.toInt()
            val y1 = min(y0 + 1, inH - 1)
            val fy = sy - y0
            if (y0 != cachedY0) {
                inBitmap.getPixels(row0, 0, inW, 0, y0, inW, 1)
                cachedY0 = y0
            }
            if (y1 != cachedY1) {
                if (y1 == y0) {
                    System.arraycopy(row0, 0, row1, 0, inW)
                } else {
                    inBitmap.getPixels(row1, 0, inW, 0, y1, inW, 1)
                }
                cachedY1 = y1
            }
            outBitmap.getPixels(outRow, 0, outW, 0, y, outW, 1)
            for (x in 0 until outW) {
                val sx = ((x + 0.5f) * invScale - 0.5f).coerceIn(0f, (inW - 1).toFloat())
                val x0 = sx.toInt()
                val x1 = min(x0 + 1, inW - 1)
                val fx = sx - x0
                val a00 = Color.alpha(row0[x0])
                val a10 = Color.alpha(row0[x1])
                val a01 = Color.alpha(row1[x0])
                val a11 = Color.alpha(row1[x1])
                val top = a00 + (a10 - a00) * fx
                val bot = a01 + (a11 - a01) * fx
                val a = (top + (bot - top) * fy + 0.5f).toInt().coerceIn(0, 255)
                val px = outRow[x]
                outRow[x] = if (a == 0) {
                    0
                } else if (a < 255) {
                    val r = (((px ushr 16) and 0xFF) * a + 127) / 255
                    val g = (((px ushr 8) and 0xFF) * a + 127) / 255
                    val b = ((px and 0xFF) * a + 127) / 255
                    (a shl 24) or (r shl 16) or (g shl 8) or b
                } else {
                    (px and 0x00FFFFFF) or (0xFF shl 24)
                }
            }
            outBitmap.setPixels(outRow, 0, outW, 0, y, outW, 1)
        }
    }
}
