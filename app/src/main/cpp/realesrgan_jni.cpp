#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

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

    // Release previous engine before loading new weights to avoid transient 2x model memory spike.
    {
        std::lock_guard<std::mutex> stateLock(g_state_mutex);
        if (g_engine) {
            g_engine->release();
            g_engine.reset();
            g_current_key.clear();
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
        static_cast<int>(inInfo.stride),
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

    env->DeleteLocalRef(argb8888);
    env->DeleteLocalRef(configClass);
    env->DeleteLocalRef(bitmapClass);

    if (env->ExceptionCheck()) {
        LOGE("Exception creating output bitmap (%dx%d)", outWidth, outHeight);
        env->ExceptionClear();
        delete[] outData;
        return nullptr;
    }

    if (!outBitmap) {
        LOGE("Failed to create output bitmap");
        delete[] outData;
        return nullptr;
    }

    AndroidBitmapInfo outInfo;
    if (AndroidBitmap_getInfo(env, outBitmap, &outInfo) != 0) {
        LOGE("Failed to get output bitmap info");
        delete[] outData;
        env->DeleteLocalRef(outBitmap);
        return nullptr;
    }

    void* outPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, outBitmap, &outPixels) != 0) {
        LOGE("Failed to lock output bitmap pixels");
        delete[] outData;
        env->DeleteLocalRef(outBitmap);
        return nullptr;
    }

    const size_t rowBytes = static_cast<size_t>(outWidth) * 4;
    if (outInfo.stride == rowBytes) {
        std::memcpy(outPixels, outData, rowBytes * static_cast<size_t>(outHeight));
    } else {
        auto* dstBase = static_cast<unsigned char*>(outPixels);
        for (int y = 0; y < outHeight; ++y) {
            std::memcpy(
                dstBase + static_cast<size_t>(y) * outInfo.stride,
                outData + static_cast<size_t>(y) * rowBytes,
                rowBytes
            );
        }
    }
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

JNIEXPORT jboolean JNICALL
Java_com_pixelforge_app_bridge_RealesrganBridge_nativePolishInPlace(
        JNIEnv* env,
        jobject /* this */,
        jobject bitmap,
        jfloat shiftR,
        jfloat shiftG,
        jfloat shiftB,
        jfloat sharpenAmount) {

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != 0 ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        info.width < 4 || info.height < 4) {
        return JNI_FALSE;
    }

    void* pixelsVoid = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixelsVoid) != 0 || !pixelsVoid) {
        return JNI_FALSE;
    }

    auto* base = static_cast<unsigned char*>(pixelsVoid);
    const int w = static_cast<int>(info.width);
    const int h = static_cast<int>(info.height);
    const size_t stride = info.stride;
    const size_t rowBytes = static_cast<size_t>(w) * 4;

    // 3-row rolling source buffer keeps RAM O(width) (~100 KB for 8K) instead of O(width * height).
    std::vector<unsigned char> prevRow(rowBytes);
    std::vector<unsigned char> currRow(rowBytes);
    std::vector<unsigned char> nextRow(rowBytes);

    std::memcpy(currRow.data(), base, rowBytes);
    std::memcpy(nextRow.data(), base + stride, rowBytes);

    for (int y = 0; y < h; ++y) {
        unsigned char* dst = base + static_cast<size_t>(y) * stride;

        if (y == 0 || y == h - 1) {
            for (int x = 0; x < w; ++x) {
                const unsigned char* s = currRow.data() + static_cast<size_t>(x) * 4;
                const int a = s[3];
                if (a == 0) continue;
                const int maxC = a;
                dst[x * 4 + 0] = static_cast<unsigned char>(std::clamp(static_cast<int>(std::lround(s[0] + shiftR)), 0, maxC));
                dst[x * 4 + 1] = static_cast<unsigned char>(std::clamp(static_cast<int>(std::lround(s[1] + shiftG)), 0, maxC));
                dst[x * 4 + 2] = static_cast<unsigned char>(std::clamp(static_cast<int>(std::lround(s[2] + shiftB)), 0, maxC));
            }
        } else {
            for (int x = 0; x < w; ++x) {
                const unsigned char* s = currRow.data() + static_cast<size_t>(x) * 4;
                const int a = s[3];
                if (a == 0) continue;
                const int maxC = a;

                if (x == 0 || x == w - 1) {
                    dst[x * 4 + 0] = static_cast<unsigned char>(std::clamp(static_cast<int>(std::lround(s[0] + shiftR)), 0, maxC));
                    dst[x * 4 + 1] = static_cast<unsigned char>(std::clamp(static_cast<int>(std::lround(s[1] + shiftG)), 0, maxC));
                    dst[x * 4 + 2] = static_cast<unsigned char>(std::clamp(static_cast<int>(std::lround(s[2] + shiftB)), 0, maxC));
                    continue;
                }

                const unsigned char* u = prevRow.data() + static_cast<size_t>(x) * 4;
                const unsigned char* d = nextRow.data() + static_cast<size_t>(x) * 4;
                const unsigned char* l = s - 4;
                const unsigned char* r = s + 4;

                const float br = (u[0] + d[0] + l[0] + r[0]) * 0.25f;
                const float bg = (u[1] + d[1] + l[1] + r[1]) * 0.25f;
                const float bb = (u[2] + d[2] + l[2] + r[2]) * 0.25f;

                const float nr = s[0] + (s[0] - br) * sharpenAmount + shiftR + 0.5f;
                const float ng = s[1] + (s[1] - bg) * sharpenAmount + shiftG + 0.5f;
                const float nb = s[2] + (s[2] - bb) * sharpenAmount + shiftB + 0.5f;

                dst[x * 4 + 0] = static_cast<unsigned char>(std::clamp(static_cast<int>(nr), 0, maxC));
                dst[x * 4 + 1] = static_cast<unsigned char>(std::clamp(static_cast<int>(ng), 0, maxC));
                dst[x * 4 + 2] = static_cast<unsigned char>(std::clamp(static_cast<int>(nb), 0, maxC));
            }
        }

        if (y + 1 < h) {
            prevRow.swap(currRow);
            currRow.swap(nextRow);
            if (y + 2 < h) {
                std::memcpy(nextRow.data(), base + static_cast<size_t>(y + 2) * stride, rowBytes);
            }
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_pixelforge_app_bridge_RealesrganBridge_nativeHasTransparency(
        JNIEnv* env,
        jobject /* this */,
        jobject bitmap) {

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != 0 ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return JNI_FALSE;
    }

    void* pixelsVoid = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixelsVoid) != 0 || !pixelsVoid) {
        return JNI_FALSE;
    }

    const auto* base = static_cast<const unsigned char*>(pixelsVoid);
    bool transparent = false;
    for (uint32_t y = 0; y < info.height && !transparent; ++y) {
        const unsigned char* row = base + static_cast<size_t>(y) * info.stride;
        for (uint32_t x = 0; x < info.width; ++x) {
            if (row[x * 4 + 3] < 252) {
                transparent = true;
                break;
            }
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return transparent ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_pixelforge_app_bridge_RealesrganBridge_nativePackTileNchw(
        JNIEnv* env,
        jobject /* this */,
        jobject inBitmap,
        jint px0,
        jint py0,
        jint padLeft,
        jint padTop,
        jint tileW,
        jint tileH,
        jboolean unpremultiply,
        jobject nchwDirectByteBuffer) {

    auto* dst = static_cast<float*>(env->GetDirectBufferAddress(nchwDirectByteBuffer));
    const jlong capacityBytes = env->GetDirectBufferCapacity(nchwDirectByteBuffer);
    const size_t planeSize = static_cast<size_t>(tileW) * static_cast<size_t>(tileH);
    if (!dst || capacityBytes < static_cast<jlong>(planeSize * 3 * sizeof(float))) {
        return JNI_FALSE;
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, inBitmap, &info) != 0 ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return JNI_FALSE;
    }

    void* pixelsVoid = nullptr;
    if (AndroidBitmap_lockPixels(env, inBitmap, &pixelsVoid) != 0 || !pixelsVoid) {
        return JNI_FALSE;
    }

    const auto* base = static_cast<const unsigned char*>(pixelsVoid);
    const int inW = static_cast<int>(info.width);
    const int inH = static_cast<int>(info.height);
    const float inv255 = 1.0f / 255.0f;

    float* rPlane = dst;
    float* gPlane = dst + planeSize;
    float* bPlane = dst + 2 * planeSize;

    for (int ty = 0; ty < tileH; ++ty) {
        const int srcY = std::clamp(py0 + ty - padTop, 0, inH - 1);
        const unsigned char* row = base + static_cast<size_t>(srcY) * info.stride;
        const size_t dstRow = static_cast<size_t>(ty) * tileW;
        for (int tx = 0; tx < tileW; ++tx) {
            const int srcX = std::clamp(px0 + tx - padLeft, 0, inW - 1);
            const unsigned char* px = row + static_cast<size_t>(srcX) * 4;
            unsigned char r = px[0];
            unsigned char g = px[1];
            unsigned char b = px[2];
            if (unpremultiply) {
                const unsigned char a = px[3];
                if (a > 0 && a < 255) {
                    r = static_cast<unsigned char>(std::min(255, (r * 255 + (a / 2)) / a));
                    g = static_cast<unsigned char>(std::min(255, (g * 255 + (a / 2)) / a));
                    b = static_cast<unsigned char>(std::min(255, (b * 255 + (a / 2)) / a));
                }
            }
            const size_t idx = dstRow + tx;
            rPlane[idx] = r * inv255;
            gPlane[idx] = g * inv255;
            bPlane[idx] = b * inv255;
        }
    }

    AndroidBitmap_unlockPixels(env, inBitmap);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_pixelforge_app_bridge_RealesrganBridge_nativeWriteNchwTileToBitmap(
        JNIEnv* env,
        jobject /* this */,
        jobject nchwDirectByteBuffer,
        jint tileOutW,
        jint tileOutH,
        jint cropOffset,
        jint coreW,
        jint coreH,
        jint outX0,
        jint outY0,
        jboolean downsample2x,
        jobject outBitmap) {

    const auto* src = static_cast<const float*>(env->GetDirectBufferAddress(nchwDirectByteBuffer));
    const jlong capacityBytes = env->GetDirectBufferCapacity(nchwDirectByteBuffer);
    const size_t planeSize = static_cast<size_t>(tileOutW) * static_cast<size_t>(tileOutH);
    if (!src || capacityBytes < static_cast<jlong>(planeSize * 3 * sizeof(float))) {
        return JNI_FALSE;
    }

    AndroidBitmapInfo outInfo;
    if (AndroidBitmap_getInfo(env, outBitmap, &outInfo) != 0 ||
        outInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return JNI_FALSE;
    }

    void* outPixelsVoid = nullptr;
    if (AndroidBitmap_lockPixels(env, outBitmap, &outPixelsVoid) != 0 || !outPixelsVoid) {
        return JNI_FALSE;
    }

    auto* outBase = static_cast<unsigned char*>(outPixelsVoid);
    const float* rPlane = src;
    const float* gPlane = src + planeSize;
    const float* bPlane = src + 2 * planeSize;
    constexpr float kPi = 3.14159265f;

    if (!downsample2x) {
        const int blendLeft = (outX0 > 0) ? std::min({8, cropOffset, outX0}) : 0;
        const int blendTop = (outY0 > 0) ? std::min({8, cropOffset, outY0}) : 0;
        const int totalW = coreW + blendLeft;
        const int totalH = coreH + blendTop;
        const int dstX0 = outX0 - blendLeft;
        const int dstY0 = outY0 - blendTop;
        const int srcStartX = cropOffset - blendLeft;
        const int srcStartY = cropOffset - blendTop;

        for (int cy = 0; cy < totalH; ++cy) {
            const int dstY = dstY0 + cy;
            if (dstY < 0 || dstY >= static_cast<int>(outInfo.height)) continue;
            unsigned char* dstRow = outBase + static_cast<size_t>(dstY) * outInfo.stride + static_cast<size_t>(dstX0) * 4;
            const size_t srcRow = static_cast<size_t>(srcStartY + cy) * tileOutW + srcStartX;
            const float wy = (cy < blendTop)
                ? 0.5f * (1.0f - std::cos(kPi * (static_cast<float>(cy) + 0.5f) / static_cast<float>(blendTop)))
                : 1.0f;
            for (int cx = 0; cx < totalW; ++cx) {
                const size_t idx = srcRow + cx;
                const float rf = std::clamp(rPlane[idx] * 255.f + 0.5f, 0.f, 255.f);
                const float gf = std::clamp(gPlane[idx] * 255.f + 0.5f, 0.f, 255.f);
                const float bf = std::clamp(bPlane[idx] * 255.f + 0.5f, 0.f, 255.f);
                const float wx = (cx < blendLeft)
                    ? 0.5f * (1.0f - std::cos(kPi * (static_cast<float>(cx) + 0.5f) / static_cast<float>(blendLeft)))
                    : 1.0f;
                const float w = wx * wy;
                if (w >= 0.999f) {
                    dstRow[cx * 4 + 0] = static_cast<unsigned char>(rf);
                    dstRow[cx * 4 + 1] = static_cast<unsigned char>(gf);
                    dstRow[cx * 4 + 2] = static_cast<unsigned char>(bf);
                } else {
                    const float invW = 1.0f - w;
                    dstRow[cx * 4 + 0] = static_cast<unsigned char>(std::clamp(dstRow[cx * 4 + 0] * invW + rf * w, 0.f, 255.f));
                    dstRow[cx * 4 + 1] = static_cast<unsigned char>(std::clamp(dstRow[cx * 4 + 1] * invW + gf * w, 0.f, 255.f));
                    dstRow[cx * 4 + 2] = static_cast<unsigned char>(std::clamp(dstRow[cx * 4 + 2] * invW + bf * w, 0.f, 255.f));
                }
                dstRow[cx * 4 + 3] = 255;
            }
        }
    } else {
        const int halfW = coreW / 2;
        const int halfH = coreH / 2;
        const int baseDstX0 = outX0 / 2;
        const int baseDstY0 = outY0 / 2;
        const int blendLeft = (baseDstX0 > 0) ? std::min({4, cropOffset / 2, baseDstX0}) : 0;
        const int blendTop = (baseDstY0 > 0) ? std::min({4, cropOffset / 2, baseDstY0}) : 0;
        const int totalW = halfW + blendLeft;
        const int totalH = halfH + blendTop;
        const int dstX0 = baseDstX0 - blendLeft;
        const int dstY0 = baseDstY0 - blendTop;
        const int srcStartX = cropOffset - blendLeft * 2;
        const int srcStartY = cropOffset - blendTop * 2;

        for (int dy = 0; dy < totalH; ++dy) {
            const int dstY = dstY0 + dy;
            if (dstY < 0 || dstY >= static_cast<int>(outInfo.height)) continue;
            unsigned char* dstRow = outBase + static_cast<size_t>(dstY) * outInfo.stride + static_cast<size_t>(dstX0) * 4;
            const size_t sy0 = static_cast<size_t>(srcStartY + dy * 2) * tileOutW + srcStartX;
            const size_t sy1 = sy0 + tileOutW;
            const float wy = (dy < blendTop)
                ? 0.5f * (1.0f - std::cos(kPi * (static_cast<float>(dy) + 0.5f) / static_cast<float>(blendTop)))
                : 1.0f;
            for (int dx = 0; dx < totalW; ++dx) {
                const size_t sx = static_cast<size_t>(dx) * 2;
                const size_t i00 = sy0 + sx;
                const size_t i10 = i00 + 1;
                const size_t i01 = sy1 + sx;
                const size_t i11 = i01 + 1;
                const float rf = std::clamp((rPlane[i00] + rPlane[i10] + rPlane[i01] + rPlane[i11]) * 0.25f * 255.f + 0.5f, 0.f, 255.f);
                const float gf = std::clamp((gPlane[i00] + gPlane[i10] + gPlane[i01] + gPlane[i11]) * 0.25f * 255.f + 0.5f, 0.f, 255.f);
                const float bf = std::clamp((bPlane[i00] + bPlane[i10] + bPlane[i01] + bPlane[i11]) * 0.25f * 255.f + 0.5f, 0.f, 255.f);
                const float wx = (dx < blendLeft)
                    ? 0.5f * (1.0f - std::cos(kPi * (static_cast<float>(dx) + 0.5f) / static_cast<float>(blendLeft)))
                    : 1.0f;
                const float w = wx * wy;
                if (w >= 0.999f) {
                    dstRow[dx * 4 + 0] = static_cast<unsigned char>(rf);
                    dstRow[dx * 4 + 1] = static_cast<unsigned char>(gf);
                    dstRow[dx * 4 + 2] = static_cast<unsigned char>(bf);
                } else {
                    const float invW = 1.0f - w;
                    dstRow[dx * 4 + 0] = static_cast<unsigned char>(std::clamp(dstRow[dx * 4 + 0] * invW + rf * w, 0.f, 255.f));
                    dstRow[dx * 4 + 1] = static_cast<unsigned char>(std::clamp(dstRow[dx * 4 + 1] * invW + gf * w, 0.f, 255.f));
                    dstRow[dx * 4 + 2] = static_cast<unsigned char>(std::clamp(dstRow[dx * 4 + 2] * invW + bf * w, 0.f, 255.f));
                }
                dstRow[dx * 4 + 3] = 255;
            }
        }
    }

    AndroidBitmap_unlockPixels(env, outBitmap);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_pixelforge_app_bridge_RealesrganBridge_nativeUpscaleAlphaToBitmap(
        JNIEnv* env,
        jobject /* this */,
        jobject inBitmap,
        jobject outBitmap,
        jint scale) {

    AndroidBitmapInfo inInfo, outInfo;
    if (AndroidBitmap_getInfo(env, inBitmap, &inInfo) != 0 ||
        AndroidBitmap_getInfo(env, outBitmap, &outInfo) != 0 ||
        inInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        outInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        scale <= 0) {
        return JNI_FALSE;
    }

    void* inVoid = nullptr;
    void* outVoid = nullptr;
    if (AndroidBitmap_lockPixels(env, inBitmap, &inVoid) != 0 || !inVoid) {
        return JNI_FALSE;
    }
    if (AndroidBitmap_lockPixels(env, outBitmap, &outVoid) != 0 || !outVoid) {
        AndroidBitmap_unlockPixels(env, inBitmap);
        return JNI_FALSE;
    }

    const auto* inBase = static_cast<const unsigned char*>(inVoid);
    auto* outBase = static_cast<unsigned char*>(outVoid);
    const int inW = static_cast<int>(inInfo.width);
    const int inH = static_cast<int>(inInfo.height);
    const int outW = static_cast<int>(outInfo.width);
    const int outH = static_cast<int>(outInfo.height);
    const float invScale = 1.0f / static_cast<float>(scale);

    for (int y = 0; y < outH; ++y) {
        float sy = (static_cast<float>(y) + 0.5f) * invScale - 0.5f;
        sy = std::clamp(sy, 0.0f, static_cast<float>(inH - 1));
        const int y0 = static_cast<int>(std::floor(sy));
        const int y1 = std::min(y0 + 1, inH - 1);
        const float fy = sy - y0;

        const unsigned char* row0 = inBase + static_cast<size_t>(y0) * inInfo.stride;
        const unsigned char* row1 = inBase + static_cast<size_t>(y1) * inInfo.stride;
        unsigned char* dstRow = outBase + static_cast<size_t>(y) * outInfo.stride;

        for (int x = 0; x < outW; ++x) {
            float sx = (static_cast<float>(x) + 0.5f) * invScale - 0.5f;
            sx = std::clamp(sx, 0.0f, static_cast<float>(inW - 1));
            const int x0 = static_cast<int>(std::floor(sx));
            const int x1 = std::min(x0 + 1, inW - 1);
            const float fx = sx - x0;

            const float a00 = row0[x0 * 4 + 3];
            const float a10 = row0[x1 * 4 + 3];
            const float a01 = row1[x0 * 4 + 3];
            const float a11 = row1[x1 * 4 + 3];

            const float top = a00 + (a10 - a00) * fx;
            const float bot = a01 + (a11 - a01) * fx;
            const float val = top + (bot - top) * fy;
            const unsigned char alpha = static_cast<unsigned char>(std::clamp(std::lround(val), 0L, 255L));

            unsigned char* px = dstRow + static_cast<size_t>(x) * 4;
            if (alpha == 0) {
                px[0] = 0;
                px[1] = 0;
                px[2] = 0;
                px[3] = 0;
            } else if (alpha < 255) {
                px[0] = static_cast<unsigned char>((px[0] * alpha + 127) / 255);
                px[1] = static_cast<unsigned char>((px[1] * alpha + 127) / 255);
                px[2] = static_cast<unsigned char>((px[2] * alpha + 127) / 255);
                px[3] = alpha;
            } else {
                px[3] = 255;
            }
        }
    }

    AndroidBitmap_unlockPixels(env, outBitmap);
    AndroidBitmap_unlockPixels(env, inBitmap);
    return JNI_TRUE;
}

} // extern "C"
