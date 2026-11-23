package com.pixelforge.app.engine

import android.graphics.Bitmap
import com.pixelforge.app.domain.model.UpscaleConfig

/**
 * Hardware compute backend used for inference.
 */
enum class ComputeBackend(val label: String) {
    NPU_QNN("NPU (QNN)"),
    NPU_NNAPI("NPU / DSP (NNAPI)"),
    GPU_VULKAN("GPU (Vulkan)"),
    CPU("CPU (OpenMP/XNNPACK)")
}

/**
 * Result of an upscaling pipeline execution.
 */
data class UpscaleResult(
    val bitmap: Bitmap,
    val backendUsed: ComputeBackend,
    val facesEnhanced: Int = 0
)

/**
 * Common abstraction for super-resolution inference backends.
 */
interface UpscaleEngine {
    val activeBackend: ComputeBackend

    suspend fun upscale(
        bitmap: Bitmap,
        config: UpscaleConfig,
        onProgress: (Float) -> Unit
    ): Bitmap?

    fun cancel()

    fun release()
}
