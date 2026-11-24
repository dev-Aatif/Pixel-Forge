package com.pixelforge.app.presentation.screen.editor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.pixelforge.app.core.constants.AppConstants
import com.pixelforge.app.core.extensions.copyUriToInternalStorage
import com.pixelforge.app.core.extensions.decodePreviewBitmap
import com.pixelforge.app.core.extensions.deleteAppOwnedFile
import com.pixelforge.app.core.extensions.formatFileSize
import com.pixelforge.app.core.extensions.getFileSize
import com.pixelforge.app.core.extensions.getImageDimensions
import com.pixelforge.app.core.utils.ImageAnalyzer
import com.pixelforge.app.data.model.ModelManager
import com.pixelforge.app.domain.model.ModelType
import com.pixelforge.app.domain.model.UpscaleConfig
import com.pixelforge.app.domain.repository.SettingsRepository
import com.pixelforge.app.engine.FaceRestorationPipeline
import com.pixelforge.app.worker.UpscaleWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.roundToInt

data class EditorUiState(
    val sourceUri: String = "",
    val imageUri: String = "",
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val fileSize: String = "",
    val config: UpscaleConfig = UpscaleConfig(),
    val outputWidth: Int = 0,
    val outputHeight: Int = 0,
    val detectedFaces: Int = 0,
    val isIllustrationRecommended: Boolean = false,
    val isDownscaledInput: Boolean = false,
    val isUltraAvailable: Boolean = false,
    val ultraDownloadProgress: Float? = null,
    val loadError: String? = null,
    val downloadError: String? = null,
    val isReady: Boolean = false
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    application: Application,
    private val settingsRepository: SettingsRepository,
    private val facePipeline: FaceRestorationPipeline,
    private val modelManager: ModelManager
) : AndroidViewModel(application) {

    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var stagedFileUri: String? = null
    private var workEnqueued = false

    fun loadImage(uriString: String) {
        if (_uiState.value.sourceUri == uriString && _uiState.value.isReady) return

        viewModelScope.launch {
            val originalUri = Uri.parse(uriString)
            val context = getApplication<Application>()

            val defaultModel = settingsRepository.getDefaultModelType().first()
            val defaultScale = settingsRepository.getDefaultScaleFactor().first()
            val useGpu = settingsRepository.getUseGpu().first()
            val useNpu = settingsRepository.getUseNpu().first()
            val faceEnhance = settingsRepository.getFaceEnhanceDefault().first()
            val exportFormat = settingsRepository.getDefaultExportFormat().first()
            val jpegQuality = settingsRepository.getJpegQuality().first()
            val maxResolution = settingsRepository.getMaxResolution().first()

            val analysis = withContext(ioDispatcher) {
                val persistentUri = context.copyUriToInternalStorage(originalUri) ?: originalUri
                if (persistentUri != originalUri) {
                    stagedFileUri?.takeIf { it != persistentUri.toString() }?.let { old ->
                        context.deleteAppOwnedFile(old)
                    }
                    stagedFileUri = persistentUri.toString()
                }
                val dims = context.getImageDimensions(persistentUri)
                val size = context.getFileSize(persistentUri)
                val preview = context.decodePreviewBitmap(persistentUri, maxPreviewDimension = 512)
                val profile = preview?.let { ImageAnalyzer.analyze(it) }
                val faces = preview?.let { facePipeline.detectFaceCount(it) } ?: 0
                preview?.recycle()
                AnalysisBundle(
                    persistentUri = persistentUri.toString(),
                    width = dims?.first ?: 0,
                    height = dims?.second ?: 0,
                    fileSize = size,
                    isFlat = profile?.isLikelyFlatIllustration ?: false,
                    faceCount = faces,
                    ultraAvailable = modelManager.isModelAvailable(ModelType.ULTRA_PHOTO, 4)
                )
            }

            val recommendedModel = when {
                analysis.isFlat && analysis.faceCount == 0 -> ModelType.ANIME_ART
                defaultModel == ModelType.ULTRA_PHOTO && !analysis.ultraAvailable -> ModelType.FAST_PHOTO
                else -> defaultModel
            }

            val config = UpscaleConfig(
                modelType = recommendedModel,
                scaleFactor = defaultScale,
                useGpu = useGpu,
                useNpu = useNpu,
                faceEnhance = faceEnhance && recommendedModel != ModelType.ANIME_ART,
                exportFormat = exportFormat,
                jpegQuality = jpegQuality,
                maxResolution = maxResolution
            )

            val (outW, outH) = projectOutputSize(analysis.width, analysis.height, config)
            val ready = analysis.width > 0 && analysis.height > 0

            _uiState.value = EditorUiState(
                sourceUri = uriString,
                imageUri = analysis.persistentUri,
                imageWidth = analysis.width,
                imageHeight = analysis.height,
                fileSize = analysis.fileSize.formatFileSize(),
                config = config,
                outputWidth = outW,
                outputHeight = outH,
                detectedFaces = analysis.faceCount,
                isIllustrationRecommended = analysis.isFlat && analysis.faceCount == 0,
                isDownscaledInput = ready && maxOf(analysis.width, analysis.height) > config.maxResolution,
                isUltraAvailable = analysis.ultraAvailable,
                loadError = if (ready) null else "Unable to read this image. Please choose another photo.",
                isReady = ready
            )
        }
    }

    fun setModelType(modelType: ModelType) {
        _uiState.update { state ->
            val updatedConfig = state.config.copy(
                modelType = modelType,
                faceEnhance = if (modelType == ModelType.ANIME_ART) false else state.config.faceEnhance
            )
            val (outW, outH) = projectOutputSize(state.imageWidth, state.imageHeight, updatedConfig)
            state.copy(
                config = updatedConfig,
                outputWidth = outW,
                outputHeight = outH
            )
        }
    }

    fun setScaleFactor(scale: Int) {
        _uiState.update { state ->
            val updatedConfig = state.config.copy(scaleFactor = scale)
            val (outW, outH) = projectOutputSize(state.imageWidth, state.imageHeight, updatedConfig)
            state.copy(
                config = updatedConfig,
                outputWidth = outW,
                outputHeight = outH
            )
        }
    }

    fun setFaceEnhance(enabled: Boolean) {
        _uiState.update { state ->
            state.copy(config = state.config.copy(faceEnhance = enabled))
        }
    }

    fun clearDownloadError() {
        _uiState.update { it.copy(downloadError = null) }
    }

    fun downloadUltraModel() {
        if (_uiState.value.ultraDownloadProgress != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(ultraDownloadProgress = 0f, downloadError = null) }
            val result = modelManager.downloadUltraPhotoModel { p ->
                _uiState.update { it.copy(ultraDownloadProgress = p) }
            }
            val available = result.isSuccess && modelManager.isModelAvailable(ModelType.ULTRA_PHOTO, 4)
            _uiState.update {
                it.copy(
                    isUltraAvailable = available,
                    ultraDownloadProgress = null,
                    downloadError = result.exceptionOrNull()?.message
                )
            }
            if (available) {
                setModelType(ModelType.ULTRA_PHOTO)
            }
        }
    }

    fun startProcessing(): String {
        workEnqueued = true
        val state = _uiState.value
        val cfg = state.config
        val inputData = Data.Builder()
            .putString(AppConstants.KEY_INPUT_URI, state.imageUri)
            .putString(AppConstants.KEY_MODEL_NAME, cfg.modelType.modelName)
            .putInt(AppConstants.KEY_SCALE_FACTOR, cfg.scaleFactor)
            .putInt(AppConstants.KEY_TILE_SIZE, cfg.tileSize)
            .putBoolean(AppConstants.KEY_USE_GPU, cfg.useGpu)
            .putBoolean(AppConstants.KEY_USE_NPU, cfg.useNpu)
            .putBoolean(AppConstants.KEY_FACE_ENHANCE, cfg.faceEnhance)
            .putString(AppConstants.KEY_EXPORT_FORMAT, cfg.exportFormat)
            .putInt(AppConstants.KEY_JPEG_QUALITY, cfg.jpegQuality)
            .putInt(AppConstants.KEY_MAX_RESOLUTION, cfg.maxResolution)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<UpscaleWorker>()
            .setInputData(inputData)
            .addTag(AppConstants.UPSCALE_WORK_TAG)
            .build()

        WorkManager.getInstance(getApplication()).enqueueUniqueWork(
            AppConstants.UPSCALE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        return workRequest.id.toString()
    }

    private fun projectOutputSize(inW: Int, inH: Int, config: UpscaleConfig): Pair<Int, Int> {
        if (inW <= 0 || inH <= 0) return 0 to 0
        val maxSide = maxOf(inW, inH)
        val capScale = if (maxSide > config.maxResolution) {
            config.maxResolution.toFloat() / maxSide.toFloat()
        } else {
            1f
        }
        val cappedW = (inW * capScale).roundToInt().coerceAtLeast(1)
        val cappedH = (inH * capScale).roundToInt().coerceAtLeast(1)
        return (cappedW * config.scaleFactor) to (cappedH * config.scaleFactor)
    }

    override fun onCleared() {
        if (!workEnqueued) {
            stagedFileUri?.let { uri ->
                getApplication<Application>().deleteAppOwnedFile(uri)
            }
        }
        super.onCleared()
    }

    private data class AnalysisBundle(
        val persistentUri: String,
        val width: Int,
        val height: Int,
        val fileSize: Long,
        val isFlat: Boolean,
        val faceCount: Int,
        val ultraAvailable: Boolean
    )
}
