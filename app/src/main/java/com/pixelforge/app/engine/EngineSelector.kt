package com.pixelforge.app.engine

import android.graphics.Bitmap
import com.pixelforge.app.data.model.ModelManager
import com.pixelforge.app.domain.model.ModelType
import com.pixelforge.app.domain.model.UpscaleConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates super-resolution backend routing (NPU-first ORT vs. Vulkan NCNN),
 * selective face restoration, and color/detail finishing.
 *
 * Serializes pipeline runs with [pipelineMutex] so replaced WorkManager jobs cannot
 * race against shared singleton engines, and defers [releaseIfIdle] until active
 * inference completes.
 */
@Singleton
class EngineSelector @Inject constructor(
    private val ortEngine: OrtNpuUpscaleEngine,
    private val ncnnEngine: NcnnUpscaleEngine,
    private val facePipeline: FaceRestorationPipeline,
    private val postProcessor: PostProcessor,
    private val modelManager: ModelManager
) {
    private val pipelineMutex = Mutex()
    private val activeRuns = AtomicInteger(0)
    private val pendingRelease = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var activeEngine: UpscaleEngine? = null

    suspend fun runPipeline(
        inputBitmap: Bitmap,
        config: UpscaleConfig,
        onStageProgress: (progress: Float, message: String) -> Unit
    ): UpscaleResult? = pipelineMutex.withLock {
        activeRuns.incrementAndGet()
        cancelled.set(false)
        try {
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

            // Never fall back to another model if the user cancelled the current run.
            if (cancelled.get()) {
                upscaled?.recycle()
                return@withLock null
            }

            // Only fall back from ORT to NCNN if a photo-compatible NCNN model (Ultra Photo) is available.
            if (upscaled == null &&
                primary === ortEngine &&
                modelManager.isModelAvailable(ModelType.ULTRA_PHOTO, resolvedConfig.scaleFactor)
            ) {
                val fallbackConfig = resolvedConfig.copy(modelType = ModelType.ULTRA_PHOTO)
                activeEngine = ncnnEngine
                upscaled = ncnnEngine.upscale(inputBitmap, fallbackConfig) { p ->
                    onStageProgress(0.05f + p * 0.78f, "Enhancing via GPU... ${(p * 100).toInt()}%")
                }
                backendUsed = ncnnEngine.activeBackend
            }

            if (cancelled.get()) {
                upscaled?.recycle()
                return@withLock null
            }

            val srBitmap = upscaled ?: return@withLock null

            // Selective Face Restoration (detects on compact inputBitmap, blends in-place on srBitmap)
            var faceCount = 0
            var afterFaces = srBitmap
            if (resolvedConfig.faceEnhance && resolvedConfig.modelType != ModelType.ANIME_ART) {
                onStageProgress(0.86f, "Refining faces...")
                val (faceOutput, count) = facePipeline.enhanceFaces(
                    upscaledBitmap = srBitmap,
                    sourceBitmap = inputBitmap
                )
                afterFaces = faceOutput
                faceCount = count
            }

            if (cancelled.get()) {
                afterFaces.recycle()
                return@withLock null
            }

            // Color & micro-detail finishing pass (in-place via native 3-row buffer when mutable)
            onStageProgress(0.93f, "Polishing color & sharpness...")
            val sharpen = if (resolvedConfig.modelType == ModelType.ANIME_ART) 0.05f else 0.11f
            val polished = postProcessor.polish(inputBitmap, afterFaces, sharpenAmount = sharpen)
            if (polished !== afterFaces) {
                afterFaces.recycle()
            }

            onStageProgress(0.98f, "Saving result...")
            UpscaleResult(
                bitmap = polished,
                backendUsed = backendUsed,
                facesEnhanced = faceCount
            )
        } finally {
            activeEngine = null
            if (activeRuns.decrementAndGet() == 0 && pendingRelease.getAndSet(false)) {
                releaseEnginesInternal()
            }
        }
    }

    fun cancelCurrent() {
        cancelled.set(true)
        activeEngine?.cancel()
    }

    /**
     * Releases cached models if idle, or defers release until the active run completes.
     * Safe to invoke from a background dispatcher on memory trim events.
     */
    fun releaseIfIdle() {
        if (activeRuns.get() > 0) {
            pendingRelease.set(true)
            return
        }
        if (pipelineMutex.tryLock()) {
            try {
                if (activeRuns.get() == 0) {
                    pendingRelease.set(false)
                    releaseEnginesInternal()
                } else {
                    pendingRelease.set(true)
                }
            } finally {
                pipelineMutex.unlock()
            }
        } else {
            pendingRelease.set(true)
        }
    }

    fun releaseAll() {
        releaseIfIdle()
    }

    private fun releaseEnginesInternal() {
        ortEngine.release()
        ncnnEngine.release()
        facePipeline.release()
    }
}
