package com.pixelforge.app.bridge

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log

/**
 * Kotlin wrapper for the native Real-ESRGAN NCNN JNI bridge.
 * All JNI calls are centralized here — never scatter them in other classes.
 */
class RealesrganBridge {

    companion object {
        private const val TAG = "RealesrganBridge"
    }

    private external fun nativeInit(
        assetManager: AssetManager,
        modelName: String,
        externalParamPath: String?,
        externalBinPath: String?,
        scale: Int,
        tileSize: Int,
        useGpu: Boolean,
        allowFp16Arithmetic: Boolean
    ): Int

    private external fun nativeProcess(inputBitmap: Bitmap): Bitmap?

    private external fun nativeIsUsingGpu(): Boolean

    private external fun nativeGetProgress(): Float

    private external fun nativeCancel()

    private external fun nativeRelease()

    fun init(
        assetManager: AssetManager,
        modelName: String,
        scale: Int,
        tileSize: Int = 0,
        useGpu: Boolean = true,
        allowFp16Arithmetic: Boolean = false,
        externalParamPath: String? = null,
        externalBinPath: String? = null
    ): Boolean {
        if (!NativeLoader.isLoaded && !NativeLoader.load()) {
            return false
        }
        val result = nativeInit(
            assetManager,
            modelName,
            externalParamPath,
            externalBinPath,
            scale,
            tileSize,
            useGpu,
            allowFp16Arithmetic
        )
        if (result != 0) {
            Log.e(TAG, "Native init failed with code: $result")
        }
        return result == 0
    }

    fun process(input: Bitmap): Bitmap? {
        if (!NativeLoader.isLoaded) return null
        if (input.config != Bitmap.Config.ARGB_8888) {
            Log.e(TAG, "Input must be ARGB_8888, got ${input.config}")
            return null
        }
        return nativeProcess(input)
    }

    fun isUsingGpu(): Boolean = if (NativeLoader.isLoaded) nativeIsUsingGpu() else false

    fun getProgress(): Float = if (NativeLoader.isLoaded) nativeGetProgress() else 0f

    fun cancel() {
        if (NativeLoader.isLoaded) nativeCancel()
    }

    fun release() {
        if (NativeLoader.isLoaded) nativeRelease()
    }
}
