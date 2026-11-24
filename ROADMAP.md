# PixelForge Roadmap

Planned improvements for **PixelForge**, focused on pushing offline speed even lower and closing the remaining quality & workflow gaps against commercial studio upscalers.

---

## 1. Core Upscaling & Restoration Engine (Main Feature)

### Near-Term (`v1.1`)
- [ ] **INT8 / FP16 Quantized NPU Models (`w8a8` / `fp16`):** Package a quantized `realesr-general-x4v3` ONNX/QNN variant to halve NPU memory bandwidth and cut inference time on Snapdragon Hexagon and MediaTek APU chips.
- [x] **Cosine / Linear Tile Overlap Feathering:** Add a narrow `4–8px` weighted blend along cropped tile seams in `realesrgan.cpp` and `OrtNpuUpscaleEngine` to prevent subtle boundary transitions on noisy low-light skies.
- [ ] **Live Tile Loupe Preview in Editor:** Let users tap/drag a `256×256` region in `EditorScreen` to preview one upscaled tile in ~1 second before committing to a full-image run.
- [ ] **Denoise Strength Control (`-dn` Blending):** Pair `realesr-general-x4v3` with its denoising weights (`realesr-general-wdn-x4v3`) so users can dial artifact removal vs. natural grain preservation.

### Mid-Term (`v1.2`)
- [ ] **Compact Quantized Face Restorer (~20–60 MB):** Replace or supplement the 340 MB `GFPGANv1.4.onnx` pack with an FP16/INT8 quantized face restorer (`GFPGAN-fp16` / `GPEN-BFR-256`) so high-fidelity portrait restoration is fast and lightweight to install.
- [ ] **`1×` Restoration Mode (Deblur + Denoise Without Enlarging):** Allow enhancing blurry or heavily compressed 12MP photos at native resolution without inflating them to 48MP.
- [ ] **Persistent NCNN Vulkan Pipeline Cache:** Persist compiled SPIR-V pipeline cache bytes to `cacheDir` so cold starts avoid shader recompilation overhead.
- [ ] **Pre-Enhance Crop & Aspect Tool:** Let users crop to a subject before upscaling so compute is focused strictly on pixels that matter.

---

## 2. App Experience & Android Platform

- [ ] **Android Share-Sheet Target (`ACTION_SEND`):** Open PixelForge directly from Google Photos, Gallery, or messaging apps into `EditorScreen`.
- [ ] **Batch Processing Queue:** Support selecting multiple images (`PickMultipleVisualMedia`) and processing them sequentially in `WorkManager` with per-item progress.
- [ ] **EXIF & Color Profile Preservation (with Optional GPS Strip):** Copy camera metadata and ICC profiles onto exported `JPEG` / `PNG` / `WEBP` outputs while offering a privacy toggle to strip GPS coordinates.
- [ ] **1-Tap Hardware Benchmark in Settings:** Measure actual per-tile latency across QNN NPU, NNAPI, Vulkan GPU, and CPU on the user's device and auto-select the fastest backend and tile size.
- [ ] **Organic Micro-Grain Toggle:** Optional subtle luminance-only film grain in `PostProcessor` to eliminate overly smooth "AI plastic" surfaces on portraits and skies.
