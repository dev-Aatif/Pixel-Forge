<p align="center">
  <img src="docs/assets/pixelforge-logo.png" width="128" alt="PixelForge Logo" />
</p>

<h1 align="center">PixelForge</h1>

<p align="center">
  Offline on-device AI image upscaling &amp; restoration for Android (<code>arm64-v8a</code>).<br />
  No cloud uploads. No queues. Built with Jetpack Compose, ONNX Runtime (QNN / NNAPI), NCNN Vulkan, and MediaPipe.
</p>

---

## Features

- **Dual-Backend Super-Resolution**
  - **Fast Photo (`realesr-general-x4v3`)**: Runs via ONNX Runtime targeting Qualcomm QNN HTP / Android NNAPI (with multi-threaded CPU fallback) for fast everyday photo upscaling.
  - **Anime & Text (`realesr-animevideov3`)**: Native `2×` and `4×` NCNN Vulkan models tuned for crisp line art, screenshots, and text.
  - **Ultra Photo (`realesrgan-x4plus`)**: Optional 23-RRDB heavy model (downloadable on demand inside the app) for maximum texture reconstruction.
- **Selective Face Restoration**
  - On-device face detection with MediaPipe BlazeFace (`blaze_face_short_range.tflite`).
  - Supports optional neural portrait restoration (`GFPGAN v1.4` ONNX pack) or lightweight local clarity enhancement blended smoothly with radial feathering.
- **Darkroom Inspection & Export**
  - Pinch-to-zoom, double-tap loupe, and aspect-preserving before/after split comparison.
  - EXIF-orientation-aware OOM-safe decoding, alpha channel preservation, WorkManager foreground execution, and Scoped Storage export (`PNG`, `JPEG`, `WEBP`) to `Pictures/PixelForge`.

## Architecture

```text
app/src/main/
├── cpp/                     # C++17 NCNN + Vulkan inference engine & mutex-guarded JNI bridge
├── java/com/pixelforge/app/
│   ├── bridge/              # NativeLoader & RealesrganBridge
│   ├── core/                # EXIF-safe Bitmap decoding, ImageAnalyzer, MediaStoreUtils
│   ├── data/                # Room database (v2), DataStore settings, ModelManager, cache
│   ├── di/                  # Hilt modules
│   ├── domain/              # Models, repository contracts, use cases
│   ├── engine/              # EngineSelector, OrtNpuUpscaleEngine, NcnnUpscaleEngine, FaceRestorationPipeline, PostProcessor
│   ├── presentation/        # Darkroom Material 3 Compose UI (Home, Editor, Processing, Result, History, Settings)
│   └── worker/              # WorkManager foreground UpscaleWorker
└── assets/models/           # Bundled compact models (ONNX general-x4v3, NCNN animevideov3, BlazeFace)
```

## Building from Source

### Prerequisites
- Android Studio / JDK 17
- Android SDK `compileSdk = 35`, `minSdk = 31`, and Android NDK + CMake `3.22.1`
- `arm64-v8a` prebuilt NCNN + glslang static libraries placed in `app/src/main/jniLibs/arm64-v8a/` (excluded from git via `.gitignore`):
  1. Download `ncnn-20260113-android-vulkan.zip` from [Tencent/ncnn releases](https://github.com/Tencent/ncnn/releases/tag/20260113).
  2. Copy the `arm64-v8a/include` and `arm64-v8a/lib` directories into `app/src/main/jniLibs/arm64-v8a/`.
  3. `libomp.so` is copied automatically from your local NDK by the `copyLibOmp` Gradle task.

### Commands
```bash
# Run unit tests and Android Lint
./gradlew testDebugUnitTest lintDebug

# Build and install debug APK on a connected arm64-v8a device
./gradlew installDebug

# Run on-device instrumentation & engine smoke tests
./gradlew connectedDebugAndroidTest

# Build release APK (set RELEASE_KEYSTORE_PATH env vars to sign)
./gradlew assembleRelease
```

### Release Signing (Optional / CI)
When building signed releases locally or in GitHub Actions, set:
- `RELEASE_KEYSTORE_PATH`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

In GitHub Actions, configure repository secrets `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD`, then push a tag like `v1.0.0`.

## Credits & Upstream Projects
- [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN) & [Real-ESRGAN-ncnn-vulkan](https://github.com/xinntao/Real-ESRGAN-ncnn-vulkan)
- [Tencent NCNN](https://github.com/Tencent/ncnn)
- [ONNX Runtime](https://github.com/microsoft/onnxruntime)
- [Google MediaPipe](https://github.com/google-ai-edge/mediapipe)
- [GFPGAN](https://github.com/TencentARC/GFPGAN)

## License
Licensed under the [GNU General Public License v3.0](LICENSE).
