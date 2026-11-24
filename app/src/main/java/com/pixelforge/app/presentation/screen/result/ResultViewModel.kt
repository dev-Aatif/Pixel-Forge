package com.pixelforge.app.presentation.screen.result

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixelforge.app.core.extensions.decodePreviewBitmap
import com.pixelforge.app.domain.model.ProcessedImage
import com.pixelforge.app.domain.repository.ImageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ResultUiState(
    val record: ProcessedImage? = null,
    val beforeBitmap: ImageBitmap? = null,
    val afterBitmap: ImageBitmap? = null,
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class ResultViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageRepository: ImageRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ResultUiState())
    val uiState: StateFlow<ResultUiState> = _uiState.asStateFlow()

    private var loadedRecordId: Long? = null

    fun loadResult(recordId: Long) {
        if (loadedRecordId == recordId && _uiState.value.afterBitmap != null) return
        loadedRecordId = recordId

        viewModelScope.launch {
            _uiState.value = ResultUiState(isLoading = true)
            val record = imageRepository.getById(recordId)
            if (record == null) {
                _uiState.value = ResultUiState(
                    isLoading = false,
                    error = "Result not found"
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(record = record)
            loadBitmaps(record)
        }
    }

    private suspend fun loadBitmaps(record: ProcessedImage) {
        withContext(Dispatchers.IO) {
            try {
                val beforeBitmap = decodePreviewWithFallback(
                    Uri.parse(record.inputUri),
                    preferredMaxDim = 2048
                )?.asImageBitmap()

                val afterBitmap = record.outputUri?.let { uriStr ->
                    decodePreviewWithFallback(
                        Uri.parse(uriStr),
                        preferredMaxDim = 2880
                    )?.asImageBitmap()
                }

                if (beforeBitmap != null && afterBitmap != null) {
                    _uiState.value = _uiState.value.copy(
                        beforeBitmap = beforeBitmap,
                        afterBitmap = afterBitmap,
                        isLoading = false,
                        error = null
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Failed to load image previews"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load image previews: ${e.message}"
                )
            }
        }
    }

    private fun decodePreviewWithFallback(uri: Uri, preferredMaxDim: Int): android.graphics.Bitmap? {
        for (dim in listOf(preferredMaxDim, 1920, 1440).distinct()) {
            val bmp = context.decodePreviewBitmap(uri, maxPreviewDimension = dim)
            if (bmp != null) return bmp
        }
        return null
    }
}
