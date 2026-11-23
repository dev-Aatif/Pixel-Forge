package com.pixelforge.app.engine

import android.content.Context
import android.graphics.Bitmap
import com.pixelforge.app.bridge.RealesrganBridge
import com.pixelforge.app.data.model.ModelManager
import com.pixelforge.app.domain.model.UpscaleConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NCNN + Vulkan/CPU super-resolution engine backed by [RealesrganBridge].
 * Keeps the loaded model warm in native memory between runs for sub-second startup.
 */
@Singleton
class NcnnUpscaleEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bridge: RealesrganBridge,
    private val modelManager: ModelManager
) : UpscaleEngine {

    @Volatile
    override var activeBackend: ComputeBackend = ComputeBackend.CPU
        private set

    override suspend fun upscale(
        bitmap: Bitmap,
        config: UpscaleConfig,
        onProgress: (Float) -> Unit
    ): Bitmap? {
        val modelType = config.modelType
        val requestedScale = config.scaleFactor
        val runScale = if (requestedScale == 2 && modelType.supportsNative2x) 2 else modelType.nativeScale
        val ncnnModelName = modelType.ncnnModelBaseName(runScale)

        val extParam = modelManager.ncnnExternalParamPath(ncnnModelName)
        val extBin = modelManager.ncnnExternalBinPath(ncnnModelName)

        val initOk = bridge.init(
            assetManager = context.assets,
            modelName = ncnnModelName,
            scale = runScale,
            tileSize = config.tileSize,
            useGpu = config.useGpu,
            allowFp16Arithmetic = modelType.allowFp16Arithmetic,
            externalParamPath = extParam,
            externalBinPath = extBin
        )
        if (!initOk) return null

        activeBackend = if (bridge.isUsingGpu()) ComputeBackend.GPU_VULKAN else ComputeBackend.CPU

        var lastProgress = 0f
        val rawOutput = coroutineScope {
            val progressJob = launch {
                while (isActive) {
                    val p = bridge.getProgress()
                    if (p > lastProgress) {
                        lastProgress = p
                        onProgress(p.coerceIn(0f, 0.99f))
                    }
                    if (p >= 1.0f) break
                    delay(120)
                }
            }

            try {
                bridge.process(bitmap)
            } finally {
                progressJob.cancel()
            }
        } ?: return null

        // If user asked for 2x on a 4x-only model, supersample 4x output down to 2x (preserving full source detail)
        return if (requestedScale == 2 && runScale == 4) {
            val targetW = (bitmap.width * 2).coerceAtLeast(1)
            val targetH = (bitmap.height * 2).coerceAtLeast(1)
            val downsampled = Bitmap.createScaledBitmap(rawOutput, targetW, targetH, true)
            if (downsampled !== rawOutput) rawOutput.recycle()
            downsampled
        } else {
            rawOutput
        }
    }

    override fun cancel() {
        bridge.cancel()
    }

    override fun release() {
        bridge.release()
    }
}
