package com.pixelforge.app.bridge

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log

/**
 * Kotlin wrapper for the native Real-ESRGAN JNI bridge.
 * All JNI calls are centralized here — never scatter them in other classes.
 */
class RealesrganBridge {

    companion object {
        private const val TAG = "RealesrganBridge"
    }

    // =========================================================
    // JNI native method declarations
    // =========================================================

    private external fun nativeInit(
        assetManager: AssetManager,
        modelName: String,
        scale: Int,
        tileSize: Int,
        useGpu: Boolean
    ): Int

    private external fun nativeProcess(inputBitmap: Bitmap): Bitmap?

    private external fun nativeGetProgress(): Float

    private external fun nativeCancel()

    private external fun nativeRelease()

    // =========================================================
    // Public API
    // =========================================================

    /**
     * Initialize the engine with a specific model.
     *
     * @return true on success, false on failure
     */
    fun init(
        assetManager: AssetManager,
        modelName: String,
        scale: Int,
        tileSize: Int = 0,
        useGpu: Boolean = true
    ): Boolean {
        val result = nativeInit(assetManager, modelName, scale, tileSize, useGpu)
        if (result != 0) {
            Log.e(TAG, "Native init failed with code: $result")
        }
        return result == 0
    }

    /**
     * Process (upscale) a bitmap.
     *
     * @param input ARGB_8888 bitmap
     * @return Upscaled bitmap, or null on failure/cancellation
     */
    fun process(input: Bitmap): Bitmap? {
        if (input.config != Bitmap.Config.ARGB_8888) {
            Log.e(TAG, "Input must be ARGB_8888, got ${input.config}")
            return null
        }
        return nativeProcess(input)
    }

    /**
     * Get current processing progress [0.0, 1.0].
     */
    fun getProgress(): Float = nativeGetProgress()

    /**
     * Request cancellation of current processing.
     */
    fun cancel() = nativeCancel()

    /**
     * Release all native resources.
     */
    fun release() = nativeRelease()
}
