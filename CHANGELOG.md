# Changelog

All notable changes to **PixelForge** are documented in this file.

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
