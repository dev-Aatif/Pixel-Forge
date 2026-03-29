package com.pixelforge.app.worker

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pixelforge.app.core.constants.AppConstants
import com.pixelforge.app.core.extensions.decodeBitmapFromUri
import com.pixelforge.app.core.utils.MediaStoreUtils
import com.pixelforge.app.data.local.ProcessedImageEntity
import com.pixelforge.app.data.local.ProcessedImageDao
import com.pixelforge.app.bridge.NativeLoader
import com.pixelforge.app.bridge.RealesrganBridge
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WorkManager worker that performs image upscaling in the background.
 *
 * Input data:
 *   - KEY_INPUT_URI: Content URI of the input image
 *   - KEY_MODEL_NAME: Model name (e.g., "realesrgan-x4plus")
 *   - KEY_SCALE_FACTOR: Scale factor (2 or 4)
 *   - KEY_TILE_SIZE: Tile size (0 = auto)
 *   - KEY_USE_GPU: Whether to use GPU
 *
 * Output data:
 *   - KEY_OUTPUT_URI: Content URI of the saved output image
 *
 * Progress data:
 *   - KEY_PROGRESS: Float [0.0, 1.0]
 */
@HiltWorker
class UpscaleWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val bridge: RealesrganBridge,
    private val dao: ProcessedImageDao
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        // Extract input parameters
        val inputUriStr = inputData.getString(AppConstants.KEY_INPUT_URI)
            ?: return@withContext Result.failure(errorData("Missing input URI"))

        val modelName = inputData.getString(AppConstants.KEY_MODEL_NAME)
            ?: AppConstants.MODEL_PHOTO

        val userScaleFactor = inputData.getInt(AppConstants.KEY_SCALE_FACTOR, -1)
        val tileSize = inputData.getInt(AppConstants.KEY_TILE_SIZE, AppConstants.DEFAULT_TILE_SIZE)
        val useGpu = inputData.getBoolean(AppConstants.KEY_USE_GPU, AppConstants.DEFAULT_USE_GPU)

        // Ensure native library is loaded
        if (!NativeLoader.isLoaded) {
            if (!NativeLoader.load()) {
                return@withContext Result.failure(errorData("Failed to load native library"))
            }
        }

        // Decode input bitmap with resolution capping for speed
        // Guarantees <10s processing by limiting input to ~1.2MP
        val inputUri = Uri.parse(inputUriStr)
        val inputBitmap = applicationContext.decodeBitmapFromUri(inputUri, maxDimension = 1280)
            ?: return@withContext Result.failure(errorData("Failed to decode input image"))

        // Smart upscale factor selection
        val scaleFactor = if (userScaleFactor != -1) {
            userScaleFactor
        } else if (inputBitmap.width > 1000) {
            2
        } else {
            4
        }

        try {
            // Initialize engine (JNI layer will handle caching)
            val initOk = bridge.init(
                assetManager = applicationContext.assets,
                modelName = modelName,
                scale = scaleFactor,
                tileSize = tileSize,
                useGpu = useGpu
            )
            if (!initOk) {
                return@withContext Result.failure(errorData("Failed to initialize AI engine"))
            }

            // Insert a "processing" record
            val recordId = dao.insert(
                ProcessedImageEntity(
                    inputUri = inputUriStr,
                    outputUri = null,
                    modelName = modelName,
                    scaleFactor = scaleFactor,
                    inputWidth = inputBitmap.width,
                    inputHeight = inputBitmap.height,
                    outputWidth = inputBitmap.width * scaleFactor,
                    outputHeight = inputBitmap.height * scaleFactor,
                    fileSizeBytes = 0,
                    processingTimeMs = 0,
                    status = "processing",
                    createdAt = System.currentTimeMillis()
                )
            )

            // Process the image with progress reporting
            var lastProgress = 0f
            val outputBitmap: Bitmap? = coroutineScope {
                val progressJob = launch {
                    while (isActive) {
                        val p = bridge.getProgress()
                        if (p > lastProgress) {
                            lastProgress = p
                            this@UpscaleWorker.setProgress(workDataOf(AppConstants.KEY_PROGRESS to p))
                        }
                        if (p >= 1.0f) break
                        delay(250)
                    }
                }

                try {
                    // This blocks the IO thread in the coroutineScope
                    bridge.process(inputBitmap)
                } finally {
                    progressJob.cancel()
                }
            }

            if (outputBitmap == null) {
                dao.deleteById(recordId)
                bridge.release()
                return@withContext Result.failure(errorData("Processing failed or was cancelled"))
            }

            // Report 100%
            setProgress(workDataOf(AppConstants.KEY_PROGRESS to 1.0f))

            // Save to MediaStore
            val outputUri = MediaStoreUtils.saveBitmap(
                context = applicationContext,
                bitmap = outputBitmap,
                format = MediaStoreUtils.ImageFormat.PNG,
                quality = AppConstants.DEFAULT_JPEG_QUALITY
            )

            if (outputUri == null) {
                dao.deleteById(recordId)
                bridge.release()
                outputBitmap.recycle()
                return@withContext Result.failure(errorData("Failed to save output image"))
            }

            val processingTime = System.currentTimeMillis() - startTime

            // Update the database record
            dao.update(
                ProcessedImageEntity(
                    id = recordId,
                    inputUri = inputUriStr,
                    outputUri = outputUri.toString(),
                    modelName = modelName,
                    scaleFactor = scaleFactor,
                    inputWidth = inputBitmap.width,
                    inputHeight = inputBitmap.height,
                    outputWidth = outputBitmap.width,
                    outputHeight = outputBitmap.height,
                    fileSizeBytes = applicationContext.contentResolver
                        .openInputStream(outputUri)?.use { it.available().toLong() } ?: 0L,
                    processingTimeMs = processingTime,
                    status = "completed",
                    createdAt = System.currentTimeMillis()
                )
            )

            // Cleanup
            bridge.release()
            outputBitmap.recycle()
            inputBitmap.recycle()

            Result.success(
                workDataOf(AppConstants.KEY_OUTPUT_URI to outputUri.toString())
            )
        } catch (e: Exception) {
            bridge.release()
            inputBitmap.recycle()
            Result.failure(errorData("Unexpected error: ${e.message}"))
        }
    }

    private fun errorData(message: String): Data {
        return workDataOf(AppConstants.KEY_ERROR_MESSAGE to message)
    }
}
