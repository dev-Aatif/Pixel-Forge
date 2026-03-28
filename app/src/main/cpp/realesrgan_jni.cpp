#include <jni.h>
#include <android/bitmap.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>

#include <memory>
#include <string>

#include "realesrgan.h"

#define TAG "PixelForge-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Global engine instance (singleton per process)
static std::unique_ptr<pixelforge::RealESRGAN> g_engine;

extern "C" {

/**
 * Initialize the Real-ESRGAN engine with a specific model.
 *
 * @param assetManager Android AssetManager for loading model files
 * @param modelName    Model name (e.g., "realesrgan-x4plus")
 * @param scale        Upscale factor (2 or 4)
 * @param tileSize     Tile size (0 = auto)
 * @param useGpu       Whether to use Vulkan GPU
 * @return 0 on success
 */
JNIEXPORT jint JNICALL
Java_com_pixelforge_app_native_RealesrganBridge_nativeInit(
        JNIEnv* env,
        jobject /* this */,
        jobject assetManager,
        jstring modelName,
        jint scale,
        jint tileSize,
        jboolean useGpu) {

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr) {
        LOGE("Failed to get AAssetManager");
        return -1;
    }

    const char* modelStr = env->GetStringUTFChars(modelName, nullptr);
    if (!modelStr) {
        LOGE("Failed to get model name string");
        return -2;
    }

    std::string model(modelStr);
    env->ReleaseStringUTFChars(modelName, modelStr);

    // Create or reset engine
    g_engine = std::make_unique<pixelforge::RealESRGAN>();

    int ret = g_engine->load(mgr, model, scale, tileSize, useGpu);
    if (ret != 0) {
        LOGE("Engine load failed: %d", ret);
        g_engine.reset();
        return ret;
    }

    LOGI("Engine initialized: model=%s, scale=%d", model.c_str(), scale);
    return 0;
}

/**
 * Process (upscale) a Bitmap.
 *
 * @param inputBitmap  Input ARGB_8888 Bitmap
 * @return Upscaled Bitmap, or null on failure
 */
JNIEXPORT jobject JNICALL
Java_com_pixelforge_app_native_RealesrganBridge_nativeProcess(
        JNIEnv* env,
        jobject /* this */,
        jobject inputBitmap) {

    if (!g_engine || !g_engine->isLoaded()) {
        LOGE("Engine not initialized");
        return nullptr;
    }

    // Lock input bitmap
    AndroidBitmapInfo inInfo;
    if (AndroidBitmap_getInfo(env, inputBitmap, &inInfo) != 0) {
        LOGE("Failed to get input bitmap info");
        return nullptr;
    }

    if (inInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Input bitmap must be ARGB_8888, got format %d", inInfo.format);
        return nullptr;
    }

    void* inPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, inputBitmap, &inPixels) != 0) {
        LOGE("Failed to lock input bitmap pixels");
        return nullptr;
    }

    // Process
    unsigned char* outData = nullptr;
    int outWidth = 0, outHeight = 0;

    int ret = g_engine->process(
        static_cast<const unsigned char*>(inPixels),
        inInfo.width, inInfo.height,
        &outData, &outWidth, &outHeight
    );

    AndroidBitmap_unlockPixels(env, inputBitmap);

    if (ret != 0 || !outData) {
        LOGE("Processing failed: %d", ret);
        return nullptr;
    }

    // Create output Bitmap via JNI
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jmethodID createBitmap = env->GetStaticMethodID(
        bitmapClass, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;"
    );

    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb8888Field = env->GetStaticFieldID(configClass, "ARGB_8888",
                                                    "Landroid/graphics/Bitmap$Config;");
    jobject argb8888 = env->GetStaticObjectField(configClass, argb8888Field);

    jobject outBitmap = env->CallStaticObjectMethod(
        bitmapClass, createBitmap,
        outWidth, outHeight, argb8888
    );

    if (!outBitmap) {
        LOGE("Failed to create output bitmap");
        delete[] outData;
        return nullptr;
    }

    // Copy output data into bitmap
    void* outPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, outBitmap, &outPixels) != 0) {
        LOGE("Failed to lock output bitmap pixels");
        delete[] outData;
        return nullptr;
    }

    memcpy(outPixels, outData, (size_t)outWidth * outHeight * 4);
    AndroidBitmap_unlockPixels(env, outBitmap);

    delete[] outData;

    LOGI("Output bitmap: %dx%d", outWidth, outHeight);
    return outBitmap;
}

/**
 * Get current processing progress [0.0, 1.0].
 */
JNIEXPORT jfloat JNICALL
Java_com_pixelforge_app_native_RealesrganBridge_nativeGetProgress(
        JNIEnv* /* env */,
        jobject /* this */) {
    if (!g_engine) return 0.0f;
    return g_engine->getProgress();
}

/**
 * Request cancellation of current processing.
 */
JNIEXPORT void JNICALL
Java_com_pixelforge_app_native_RealesrganBridge_nativeCancel(
        JNIEnv* /* env */,
        jobject /* this */) {
    if (g_engine) {
        g_engine->requestCancel();
        LOGI("Cancel requested");
    }
}

/**
 * Release all native resources.
 */
JNIEXPORT void JNICALL
Java_com_pixelforge_app_native_RealesrganBridge_nativeRelease(
        JNIEnv* /* env */,
        jobject /* this */) {
    if (g_engine) {
        g_engine->release();
        g_engine.reset();
        LOGI("Engine released");
    }
}

} // extern "C"
