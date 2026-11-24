# Changelog

All notable changes to **PixelForge** are documented in this file.

## [1.1.0] - 2026-11-25

### Added
- Cosine tile-overlap feathering along adjacent tile seams in both C++ NCNN (`realesrgan.cpp`) and ORT JNI (`realesrgan_jni.cpp`) backends.
- Zero-copy JNI pixel packing/unpacking (`nativePackTileNchw`, `nativeWriteNchwTileToBitmap`, `nativeUpscaleAlphaToBitmap`) and 3-row rolling in-place `nativePolishInPlace` to eliminate full-frame Java `IntArray` allocations on 4K/8K outputs.
- Expanded test suite (29 JVM unit tests across engine, worker, cache, repository, migration, and ViewModels; plus `ImageAndDatabaseInstrumentationTest` for on-device SQLite `MIGRATION_1_2` and EXIF/panorama decoding).
- TalkBack accessibility actions on `BeforeAfterSlider`, `LiveRegionMode.Polite` on `ProcessingScreen`, `BackHandler` cancellation prompt, and `SnackbarHost` download error feedback.

### Fixed
- Fixed NCNN Vulkan GPU never initializing due to missing `ncnn::create_gpu_instance()` call in `realesrgan.cpp`.
- Fixed `onTrimMemory` main-thread ANR and native UAF crashes by deferring engine release off the main thread (`EngineSelector.releaseIfIdle()`) and guarding active ORT/MediaPipe/GFPGAN sessions.
- Fixed ORT dynamic-shape NNAPI recompilation and missing `XNNPACK` registration by padding all tiles to static `288×288`, enabling `addXnnpack`, gating `addQnn` on packaged QNN libs, and retrying NPU failures on CPU.
- Fixed cancellation silently falling back to `ANIME_ART` in `EngineSelector`, `OutOfMemoryError` crashing `UpscaleWorker`, and premultiplied alpha edge halos on transparent cutouts.
- Fixed `FaceRestorationPipeline` aspect distortion (1:1 square letterboxing before 512×512 GFPGAN), compact-image BlazeFace detection, and scale-adaptive micro-clarity fallback.
- Fixed privacy & storage hygiene (`allowBackup="false"`, `data_extraction_rules.xml`, `filesDir/uploads/` orphan pruning, `ModelManager` HTTPS/size/header/space/cancellation validation), `ResultViewModel` 2880 px loupe preview, `TextTertiary` WCAG AA contrast (`#968E83`), `48dp+` touch targets, and release CI signing verification.

## [1.0.0] - 2026-11-24

### Added
- Dual-backend offline super-resolution via ONNX Runtime (`QNN HTP` / `NNAPI FP16` / `CPU`) and NCNN (`Vulkan` / `OpenMP`).
- Bundled compact models (`realesr-general-x4v3.onnx`, `realesr-animevideov3-x2/x4`, `blaze_face_short_range.tflite`) plus on-demand model pack downloads (`realesrgan-x4plus`, `GFPGANv1.4.onnx`).
- Selective MediaPipe face detection and restoration with radial smoothstep blending and color/unsharp finishing.
- Alpha channel preservation in NCNN and ONNX upscaling pipelines.
- Darkroom Material 3 Compose UI with pinch-to-zoom before/after loupe comparison, specimen ledger, archive deletion, and full export/hardware settings.
- Unit tests and on-device instrumentation smoke/latency tests (`NativeUpscaleBenchmarkTest`).

### Fixed
- Fixed `[0, 1]` input/output normalization and disabled mobile Vulkan FP16 arithmetic overflow in `realesrgan.cpp` for `realesrgan-x4plus` and `realesr-animevideov3`.
- Fixed OOM-safe `inSampleSize` decoding, EXIF orientation handling, cooperative WorkManager cancellation, `SystemForegroundService` `dataSync` manifest declaration, and `List.removeAt(0)` API compatibility.
- Fixed archive card text wrapping with `FlowRow` and tabular numerals.
