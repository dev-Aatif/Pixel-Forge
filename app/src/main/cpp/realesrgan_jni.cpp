#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <cstring>
#include <memory>
#include <mutex>
#include <string>

#include "realesrgan.h"

#define TAG "PixelForge-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

std::mutex g_process_mutex;
std::mutex g_state_mutex;
std::shared_ptr<pixelforge::RealESRGAN> g_engine;
std::string g_current_key;

std::shared_ptr<pixelforge::RealESRGAN> getEngineSnapshot() {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    return g_engine;
}

std::string jstringToStd(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    if (!chars) return "";
    std::string out(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_pixelforge_app_bridge_RealesrganBridge_nativeInit(
        JNIEnv* env,
        jobject /* this */,
        jobject assetManager,
        jstring modelName,
        jstring externalParamPath,
        jstring externalBinPath,
        jint scale,
        jint tileSize,
        jboolean useGpu,
        jboolean allowFp16Arithmetic) {

    std::lock_guard<std::mutex> processLock(g_process_mutex);

    const std::string model = jstringToStd(env, modelName);
    const std::string extParam = jstringToStd(env, externalParamPath);
    const std::string extBin = jstringToStd(env, externalBinPath);

    if (model.empty()) {
        LOGE("Empty model name");
        return -2;
    }

    const std::string cacheKey = model + "|" + extParam + "|" +
        std::to_string(scale) + "|" + std::to_string(tileSize) + "|" +
        (useGpu ? "1" : "0") + "|" + (allowFp16Arithmetic ? "1" : "0");

    {
        std::lock_guard<std::mutex> stateLock(g_state_mutex);
        if (g_engine && g_engine->isLoaded() && g_current_key == cacheKey) {
            LOGI("Reusing cached NCNN engine: %s", cacheKey.c_str());
            return 0;
        }
    }

    auto newEngine = std::make_shared<pixelforge::RealESRGAN>();
    int ret = -1;
    if (!extParam.empty() && !extBin.empty()) {
        LOGI("Loading NCNN model from files: %s", extParam.c_str());
        ret = newEngine->loadFromFiles(
            extParam, extBin, scale, tileSize, useGpu, allowFp16Arithmetic
        );
    } else {
        AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
        if (!mgr) {
            LOGE("Failed to get AAssetManager");
            return -1;
        }
        LOGI("Loading NCNN model from assets: %s", model.c_str());
        ret = newEngine->load(
            mgr, model, scale, tileSize, useGpu, allowFp16Arithmetic
        );
    }

    if (ret != 0) {
        LOGE("Engine load failed: %d", ret);
        return ret;
    }

    {
        std::lock_guard<std::mutex> stateLock(g_state_mutex);
        g_engine = std::move(newEngine);
        g_current_key = cacheKey;
    }

    return 0;
}

JNIEXPORT jobject JNICALL
Java_com_pixelforge_app_bridge_RealesrganBridge_nativeProcess(
        JNIEnv* env,
        jobject /* this */,
        jobject inputBitmap) {

    std::lock_guard<std::mutex> processLock(g_process_mutex);
    auto engine = getEngineSnapshot();

    if (!engine || !engine->isLoaded()) {
        LOGE("Engine not initialized");
        return nullptr;
    }

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

    unsigned char* outData = nullptr;
    int outWidth = 0;
    int outHeight = 0;

    const int ret = engine->process(
        static_cast<const unsigned char*>(inPixels),
        static_cast<int>(inInfo.width),
        static_cast<int>(inInfo.height),
        &outData, &outWidth, &outHeight
    );

    AndroidBitmap_unlockPixels(env, inputBitmap);

    if (ret != 0 || !outData) {
        LOGE("Processing failed or cancelled: %d", ret);
        return nullptr;
    }

    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jmethodID createBitmap = env->GetStaticMethodID(
        bitmapClass, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;"
    );

    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb8888Field = env->GetStaticFieldID(
        configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;"
    );
    jobject argb8888 = env->GetStaticObjectField(configClass, argb8888Field);

    jobject outBitmap = env->CallStaticObjectMethod(
        bitmapClass, createBitmap, outWidth, outHeight, argb8888
    );

    if (!outBitmap) {
        LOGE("Failed to create output bitmap");
        delete[] outData;
        return nullptr;
    }

    void* outPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, outBitmap, &outPixels) != 0) {
        LOGE("Failed to lock output bitmap pixels");
        delete[] outData;
        return nullptr;
    }

    std::memcpy(outPixels, outData, static_cast<size_t>(outWidth) * outHeight * 4);
    AndroidBitmap_unlockPixels(env, outBitmap);
    delete[] outData;

    return outBitmap;
}

JNIEXPORT jboolean JNICALL
Java_com_pixelforge_app_bridge_RealesrganBridge_nativeIsUsingGpu(
        JNIEnv* /* env */,
        jobject /* this */) {
    auto engine = getEngineSnapshot();
    return (engine && engine->isUsingGpu()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
Java_com_pixelforge_app_bridge_RealesrganBridge_nativeGetProgress(
        JNIEnv* /* env */,
        jobject /* this */) {
    auto engine = getEngineSnapshot();
    if (!engine) return 0.0f;
    return engine->getProgress();
}

JNIEXPORT void JNICALL
Java_com_pixelforge_app_bridge_RealesrganBridge_nativeCancel(
        JNIEnv* /* env */,
        jobject /* this */) {
    auto engine = getEngineSnapshot();
    if (engine) {
        engine->requestCancel();
        LOGI("Cancel requested");
    }
}

JNIEXPORT void JNICALL
Java_com_pixelforge_app_bridge_RealesrganBridge_nativeRelease(
        JNIEnv* /* env */,
        jobject /* this */) {
    std::lock_guard<std::mutex> processLock(g_process_mutex);
    std::lock_guard<std::mutex> stateLock(g_state_mutex);
    if (g_engine) {
        g_engine->release();
        g_engine.reset();
        g_current_key.clear();
        LOGI("Engine released");
    }
}

} // extern "C"
