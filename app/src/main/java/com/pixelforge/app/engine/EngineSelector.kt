package com.pixelforge.app.engine

import android.graphics.Bitmap
import com.pixelforge.app.data.model.ModelManager
import com.pixelforge.app.domain.model.ModelType
import com.pixelforge.app.domain.model.UpscaleConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates super-resolution backend routing (NPU-first ORT vs. Vulkan NCNN),
 * selective face restoration, and color/detail finishing.
 */
@Singleton
class EngineSelector @Inject constructor(
    private val ortEngine: OrtNpuUpscaleEngine,
    private val ncnnEngine: NcnnUpscaleEngine,
    private val facePipeline: FaceRestorationPipeline,
    private val postProcessor: PostProcessor,
    private val modelManager: ModelManager
) {
    @Volatile
    private var activeEngine: UpscaleEngine? = null

    suspend fun runPipeline(
        inputBitmap: Bitmap,
        config: UpscaleConfig,
        onStageProgress: (progress: Float, message: String) -> Unit
    ): UpscaleResult? {
        var resolvedConfig = config

        // If user selected Ultra Photo without optional weights downloaded, fall back gracefully to Fast Photo
        if (resolvedConfig.modelType == ModelType.ULTRA_PHOTO &&
            !modelManager.isModelAvailable(ModelType.ULTRA_PHOTO, resolvedConfig.scaleFactor)
        ) {
            resolvedConfig = resolvedConfig.copy(modelType = ModelType.FAST_PHOTO)
        }

        // Choose primary engine: ORT (NPU-first) when model has ONNX graph; NCNN (Vulkan) otherwise
        val primary: UpscaleEngine = if (resolvedConfig.modelType.onnxAssetPath != null) {
            ortEngine
        } else {
            ncnnEngine
        }
        activeEngine = primary

        onStageProgress(0.05f, "Running AI super-resolution...")
        var upscaled = primary.upscale(inputBitmap, resolvedConfig) { p ->
            onStageProgress(0.05f + p * 0.78f, "Enhancing details... ${(p * 100).toInt()}%")
        }
        var backendUsed = primary.activeBackend

        // Fallback to NCNN Vulkan if ORT failed
        if (upscaled == null && primary === ortEngine) {
            val fallbackConfig = resolvedConfig.copy(modelType = ModelType.ANIME_ART)
            activeEngine = ncnnEngine
            upscaled = ncnnEngine.upscale(inputBitmap, fallbackConfig) { p ->
                onStageProgress(0.05f + p * 0.78f, "Enhancing via GPU... ${(p * 100).toInt()}%")
            }
            backendUsed = ncnnEngine.activeBackend
        }

        val srBitmap = upscaled ?: return null

        // Selective Face Restoration (only for photos when enabled)
        var faceCount = 0
        var afterFaces = srBitmap
        if (resolvedConfig.faceEnhance && resolvedConfig.modelType != ModelType.ANIME_ART) {
            onStageProgress(0.86f, "Refining faces...")
            val (faceOutput, count) = facePipeline.enhanceFaces(srBitmap)
            afterFaces = faceOutput
            faceCount = count
        }

        // Color & micro-detail finishing pass
        onStageProgress(0.93f, "Polishing color & sharpness...")
        val sharpen = if (resolvedConfig.modelType == ModelType.ANIME_ART) 0.05f else 0.11f
        val polished = postProcessor.polish(inputBitmap, afterFaces, sharpenAmount = sharpen)
        if (polished !== afterFaces) {
            afterFaces.recycle()
        }

        onStageProgress(0.98f, "Saving result...")
        return UpscaleResult(
            bitmap = polished,
            backendUsed = backendUsed,
            facesEnhanced = faceCount
        )
    }

    fun cancelCurrent() {
        activeEngine?.cancel()
    }

    fun releaseAll() {
        ortEngine.release()
        ncnnEngine.release()
        facePipeline.release()
    }
}
