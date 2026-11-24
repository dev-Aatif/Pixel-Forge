package com.pixelforge.app.presentation.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixelforge.app.core.constants.AppConstants
import com.pixelforge.app.core.extensions.formatFileSize
import com.pixelforge.app.data.cache.ImageCacheManager
import com.pixelforge.app.data.model.ModelManager
import com.pixelforge.app.domain.model.ModelType
import com.pixelforge.app.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SettingsUiState(
    val defaultModelType: ModelType = ModelType.FAST_PHOTO,
    val defaultScaleFactor: Int = AppConstants.DEFAULT_SCALE_FACTOR,
    val useGpu: Boolean = AppConstants.DEFAULT_USE_GPU,
    val useNpu: Boolean = AppConstants.DEFAULT_USE_NPU,
    val faceEnhanceDefault: Boolean = AppConstants.DEFAULT_FACE_ENHANCE,
    val exportFormat: String = AppConstants.DEFAULT_EXPORT_FORMAT,
    val jpegQuality: Int = AppConstants.DEFAULT_JPEG_QUALITY,
    val maxResolution: Int = AppConstants.DEFAULT_MAX_RESOLUTION,
    val cacheSizeStr: String = "0 B",
    val isUltraDownloaded: Boolean = false,
    val isFacePackDownloaded: Boolean = false,
    val ultraDownloadProgress: Float? = null,
    val facePackDownloadProgress: Float? = null,
    val downloadedModelsSizeStr: String = "0 B",
    val downloadError: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val cacheManager: ImageCacheManager,
    private val modelManager: ModelManager
) : ViewModel() {

    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.getDefaultModelType().collect { model ->
                _uiState.update { it.copy(defaultModelType = model) }
            }
        }
        viewModelScope.launch {
            settingsRepository.getDefaultScaleFactor().collect { scale ->
                _uiState.update { it.copy(defaultScaleFactor = scale) }
            }
        }
        viewModelScope.launch {
            settingsRepository.getUseGpu().collect { gpu ->
                _uiState.update { it.copy(useGpu = gpu) }
            }
        }
        viewModelScope.launch {
            settingsRepository.getUseNpu().collect { npu ->
                _uiState.update { it.copy(useNpu = npu) }
            }
        }
        viewModelScope.launch {
            settingsRepository.getFaceEnhanceDefault().collect { face ->
                _uiState.update { it.copy(faceEnhanceDefault = face) }
            }
        }
        viewModelScope.launch {
            settingsRepository.getDefaultExportFormat().collect { fmt ->
                _uiState.update { it.copy(exportFormat = fmt) }
            }
        }
        viewModelScope.launch {
            settingsRepository.getJpegQuality().collect { q ->
                _uiState.update { it.copy(jpegQuality = q) }
            }
        }
        viewModelScope.launch {
            settingsRepository.getMaxResolution().collect { maxRes ->
                _uiState.update { it.copy(maxResolution = maxRes) }
            }
        }
        refreshStorageStats()
    }

    fun refreshStorageStats() {
        viewModelScope.launch {
            val stats = withContext(ioDispatcher) {
                StorageStats(
                    cacheSize = cacheManager.getCacheSize().formatFileSize(),
                    ultraAvailable = modelManager.isModelAvailable(ModelType.ULTRA_PHOTO, 4),
                    facePackAvailable = modelManager.isFaceRestoreAvailable(),
                    modelsSize = modelManager.getDownloadedModelsSizeBytes().formatFileSize()
                )
            }
            _uiState.update {
                it.copy(
                    cacheSizeStr = stats.cacheSize,
                    isUltraDownloaded = stats.ultraAvailable,
                    isFacePackDownloaded = stats.facePackAvailable,
                    downloadedModelsSizeStr = stats.modelsSize
                )
            }
        }
    }

    fun setModelType(modelType: ModelType) {
        viewModelScope.launch { settingsRepository.setDefaultModelType(modelType) }
    }

    fun setScaleFactor(scale: Int) {
        viewModelScope.launch { settingsRepository.setDefaultScaleFactor(scale) }
    }

    fun setUseGpu(useGpu: Boolean) {
        viewModelScope.launch { settingsRepository.setUseGpu(useGpu) }
    }

    fun setUseNpu(useNpu: Boolean) {
        viewModelScope.launch { settingsRepository.setUseNpu(useNpu) }
    }

    fun setFaceEnhanceDefault(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setFaceEnhanceDefault(enabled) }
    }

    fun setExportFormat(format: String) {
        viewModelScope.launch { settingsRepository.setDefaultExportFormat(format) }
    }

    fun setJpegQuality(quality: Int) {
        viewModelScope.launch { settingsRepository.setJpegQuality(quality) }
    }

    fun setMaxResolution(maxRes: Int) {
        viewModelScope.launch { settingsRepository.setMaxResolution(maxRes) }
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
            _uiState.update {
                it.copy(
                    ultraDownloadProgress = null,
                    downloadError = result.exceptionOrNull()?.message
                )
            }
            refreshStorageStats()
        }
    }

    fun downloadFaceRestoreModel() {
        if (_uiState.value.facePackDownloadProgress != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(facePackDownloadProgress = 0f, downloadError = null) }
            val result = modelManager.downloadFaceRestoreModel { p ->
                _uiState.update { it.copy(facePackDownloadProgress = p) }
            }
            _uiState.update {
                it.copy(
                    facePackDownloadProgress = null,
                    downloadError = result.exceptionOrNull()?.message
                )
            }
            refreshStorageStats()
        }
    }

    fun clearDownloadedModels() {
        viewModelScope.launch {
            withContext(ioDispatcher) { modelManager.clearDownloadedModels() }
            refreshStorageStats()
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(ioDispatcher) { cacheManager.clearAll() }
            refreshStorageStats()
        }
    }

    private data class StorageStats(
        val cacheSize: String,
        val ultraAvailable: Boolean,
        val facePackAvailable: Boolean,
        val modelsSize: String
    )
}
