package com.pixelforge.app.bridge

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log
import java.nio.ByteBuffer

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

    private external fun nativePolishInPlace(
        bitmap: Bitmap,
        shiftR: Float,
        shiftG: Float,
        shiftB: Float,
        sharpenAmount: Float
    ): Boolean

    private external fun nativeHasTransparency(bitmap: Bitmap): Boolean

    private external fun nativePackTileNchw(
        inBitmap: Bitmap,
        px0: Int,
        py0: Int,
        padLeft: Int,
        padTop: Int,
        tileW: Int,
        tileH: Int,
        unpremultiply: Boolean,
        nchwDirectByteBuffer: ByteBuffer
    ): Boolean

    private external fun nativeWriteNchwTileToBitmap(
        nchwDirectByteBuffer: ByteBuffer,
        tileOutW: Int,
        tileOutH: Int,
        cropOffset: Int,
        coreW: Int,
        coreH: Int,
        outX0: Int,
        outY0: Int,
        downsample2x: Boolean,
        outBitmap: Bitmap
    ): Boolean

    private external fun nativeUpscaleAlphaToBitmap(
        inBitmap: Bitmap,
        outBitmap: Bitmap,
        scale: Int
    ): Boolean

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

    fun polishInPlace(
        bitmap: Bitmap,
        shiftR: Float,
        shiftG: Float,
        shiftB: Float,
        sharpenAmount: Float
    ): Boolean {
        if (!NativeLoader.isLoaded) return false
        if (!bitmap.isMutable || bitmap.config != Bitmap.Config.ARGB_8888) return false
        return nativePolishInPlace(bitmap, shiftR, shiftG, shiftB, sharpenAmount)
    }

    fun hasTransparency(bitmap: Bitmap): Boolean {
        if (!NativeLoader.isLoaded || bitmap.config != Bitmap.Config.ARGB_8888) return false
        return nativeHasTransparency(bitmap)
    }

    fun packTileNchw(
        inBitmap: Bitmap,
        px0: Int,
        py0: Int,
        padLeft: Int,
        padTop: Int,
        tileW: Int,
        tileH: Int,
        unpremultiply: Boolean,
        nchwDirectByteBuffer: ByteBuffer
    ): Boolean {
        if (!NativeLoader.isLoaded || inBitmap.config != Bitmap.Config.ARGB_8888) return false
        if (!nchwDirectByteBuffer.isDirect) return false
        return nativePackTileNchw(
            inBitmap, px0, py0, padLeft, padTop, tileW, tileH, unpremultiply, nchwDirectByteBuffer
        )
    }

    fun writeNchwTileToBitmap(
        nchwDirectByteBuffer: ByteBuffer,
        tileOutW: Int,
        tileOutH: Int,
        cropOffset: Int,
        coreW: Int,
        coreH: Int,
        outX0: Int,
        outY0: Int,
        downsample2x: Boolean,
        outBitmap: Bitmap
    ): Boolean {
        if (!NativeLoader.isLoaded || !outBitmap.isMutable || outBitmap.config != Bitmap.Config.ARGB_8888) {
            return false
        }
        if (!nchwDirectByteBuffer.isDirect) return false
        return nativeWriteNchwTileToBitmap(
            nchwDirectByteBuffer, tileOutW, tileOutH, cropOffset, coreW, coreH,
            outX0, outY0, downsample2x, outBitmap
        )
    }

    fun upscaleAlphaToBitmap(
        inBitmap: Bitmap,
        outBitmap: Bitmap,
        scale: Int
    ): Boolean {
        if (!NativeLoader.isLoaded || !outBitmap.isMutable) return false
        if (inBitmap.config != Bitmap.Config.ARGB_8888 || outBitmap.config != Bitmap.Config.ARGB_8888) {
            return false
        }
        return nativeUpscaleAlphaToBitmap(inBitmap, outBitmap, scale)
    }
}
