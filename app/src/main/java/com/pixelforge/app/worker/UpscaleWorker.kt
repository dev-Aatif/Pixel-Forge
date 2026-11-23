package com.pixelforge.app.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pixelforge.app.core.constants.AppConstants
import com.pixelforge.app.core.extensions.decodeBitmapFromUri
import com.pixelforge.app.core.extensions.getFileSize
import com.pixelforge.app.core.utils.MediaStoreUtils
import com.pixelforge.app.data.local.ProcessedImageDao
import com.pixelforge.app.data.local.ProcessedImageEntity
import com.pixelforge.app.domain.model.ModelType
import com.pixelforge.app.domain.model.UpscaleConfig
import com.pixelforge.app.engine.EngineSelector
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager worker that performs offline AI image upscaling in the background.
 */
@HiltWorker
class UpscaleWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val engineSelector: EngineSelector,
    private val dao: ProcessedImageDao
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureNotificationChannel()
        val notification = buildNotification("Enhancing image...")
        return ForegroundInfo(
            AppConstants.UPSCALE_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        val inputUriStr = inputData.getString(AppConstants.KEY_INPUT_URI)
            ?: return@withContext Result.failure(errorData("Missing input URI"))

        val modelName = inputData.getString(AppConstants.KEY_MODEL_NAME)
            ?: AppConstants.MODEL_FAST_PHOTO
        val modelType = ModelType.fromModelName(modelName)
        val userScaleFactor = inputData.getInt(AppConstants.KEY_SCALE_FACTOR, -1)
        val tileSize = inputData.getInt(AppConstants.KEY_TILE_SIZE, AppConstants.DEFAULT_TILE_SIZE)
        val useGpu = inputData.getBoolean(AppConstants.KEY_USE_GPU, AppConstants.DEFAULT_USE_GPU)
        val useNpu = inputData.getBoolean(AppConstants.KEY_USE_NPU, AppConstants.DEFAULT_USE_NPU)
        val faceEnhance = inputData.getBoolean(AppConstants.KEY_FACE_ENHANCE, AppConstants.DEFAULT_FACE_ENHANCE)
        val exportFormatStr = inputData.getString(AppConstants.KEY_EXPORT_FORMAT)
            ?: AppConstants.DEFAULT_EXPORT_FORMAT
        val jpegQuality = inputData.getInt(AppConstants.KEY_JPEG_QUALITY, AppConstants.DEFAULT_JPEG_QUALITY)
        val maxResolution = inputData.getInt(AppConstants.KEY_MAX_RESOLUTION, AppConstants.DEFAULT_MAX_RESOLUTION)

        // Attempt foreground promotion so OS does not throttle long upscales (safe if notifications denied)
        runCatching { setForeground(getForegroundInfo()) }

        val inputUri = Uri.parse(inputUriStr)
        val inputBitmap = applicationContext.decodeBitmapFromUri(inputUri, maxDimension = maxResolution)
            ?: return@withContext Result.failure(errorData("Failed to decode input image"))

        val scaleFactor = if (userScaleFactor in listOf(2, 4)) {
            userScaleFactor
        } else if (maxOf(inputBitmap.width, inputBitmap.height) > 1000) {
            2
        } else {
            4
        }

        val config = UpscaleConfig(
            modelType = modelType,
            scaleFactor = scaleFactor,
            tileSize = tileSize,
            useGpu = useGpu,
            useNpu = useNpu,
            faceEnhance = faceEnhance,
            exportFormat = exportFormatStr,
            jpegQuality = jpegQuality,
            maxResolution = maxResolution
        )

        val recordId = dao.insert(
            ProcessedImageEntity(
                inputUri = inputUriStr,
                outputUri = null,
                modelName = modelType.modelName,
                scaleFactor = scaleFactor,
                inputWidth = inputBitmap.width,
                inputHeight = inputBitmap.height,
                outputWidth = inputBitmap.width * scaleFactor,
                outputHeight = inputBitmap.height * scaleFactor,
                fileSizeBytes = 0,
                processingTimeMs = 0,
                status = "processing",
                backendUsed = "Initializing",
                createdAt = System.currentTimeMillis()
            )
        )

        var outputBitmap: Bitmap? = null
        try {
            val result = engineSelector.runPipeline(
                inputBitmap = inputBitmap,
                config = config
            ) { progress, message ->
                if (isStopped) {
                    engineSelector.cancelCurrent()
                }
                setProgressAsync(
                    workDataOf(
                        AppConstants.KEY_PROGRESS to progress,
                        AppConstants.KEY_STATUS_MESSAGE to message
                    )
                )
            }

            if (result == null || isStopped) {
                dao.deleteById(recordId)
                return@withContext Result.failure(errorData("Processing failed or was cancelled"))
            }

            outputBitmap = result.bitmap

            val format = MediaStoreUtils.ImageFormat.fromString(exportFormatStr)
            val outputUri = MediaStoreUtils.saveBitmap(
                context = applicationContext,
                bitmap = outputBitmap,
                format = format,
                quality = jpegQuality
            )

            if (outputUri == null) {
                dao.deleteById(recordId)
                return@withContext Result.failure(errorData("Failed to save output image"))
            }

            val processingTime = System.currentTimeMillis() - startTime
            val fileSize = applicationContext.getFileSize(outputUri)

            dao.update(
                ProcessedImageEntity(
                    id = recordId,
                    inputUri = inputUriStr,
                    outputUri = outputUri.toString(),
                    modelName = modelType.modelName,
                    scaleFactor = scaleFactor,
                    inputWidth = inputBitmap.width,
                    inputHeight = inputBitmap.height,
                    outputWidth = outputBitmap.width,
                    outputHeight = outputBitmap.height,
                    fileSizeBytes = fileSize,
                    processingTimeMs = processingTime,
                    status = "completed",
                    backendUsed = result.backendUsed.label,
                    createdAt = System.currentTimeMillis()
                )
            )

            setProgress(
                workDataOf(
                    AppConstants.KEY_PROGRESS to 1.0f,
                    AppConstants.KEY_STATUS_MESSAGE to "Done!"
                )
            )

            Result.success(
                workDataOf(
                    AppConstants.KEY_OUTPUT_URI to outputUri.toString(),
                    AppConstants.KEY_RECORD_ID to recordId,
                    AppConstants.KEY_BACKEND_USED to result.backendUsed.label
                )
            )
        } catch (ce: CancellationException) {
            engineSelector.cancelCurrent()
            dao.deleteById(recordId)
            throw ce
        } catch (e: Exception) {
            dao.deleteById(recordId)
            Result.failure(errorData("Unexpected error: ${e.message ?: "Unknown"}"))
        } finally {
            outputBitmap?.recycle()
            inputBitmap.recycle()
        }
    }

    private fun ensureNotificationChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(AppConstants.UPSCALE_NOTIFICATION_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                AppConstants.UPSCALE_NOTIFICATION_CHANNEL_ID,
                "Image Enhancement",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while enhancing images offline"
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(
            applicationContext,
            AppConstants.UPSCALE_NOTIFICATION_CHANNEL_ID
        )
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(AppConstants.APP_NAME)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun errorData(message: String): Data {
        return workDataOf(AppConstants.KEY_ERROR_MESSAGE to message)
    }
}
