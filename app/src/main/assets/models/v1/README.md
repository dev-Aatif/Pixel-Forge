# Real-ESRGAN NCNN Model Files

## Required Files

Place the following files in this directory:

### Photo Model (realesrgan-x4plus)
- `realesrgan-x4plus.param`
- `realesrgan-x4plus.bin`

## Download Instructions

1. Go to: https://github.com/xinntao/Real-ESRGAN-ncnn-vulkan/releases
2. Download the latest release ZIP for your platform
3. Extract the ZIP and locate the `models/` folder inside
4. Copy the `.param` and `.bin` files listed above into this directory

## Important Notes

- **Do NOT use `.pth` files** — those are PyTorch format, not NCNN format
- Total size is approximately ~65MB
- These files are excluded from git via `.gitignore`
