package com.pixelforge.app.presentation.screen.result

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixelforge.app.domain.model.ProcessedImage
import com.pixelforge.app.domain.usecase.GetHistoryUseCase
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
    private val getHistoryUseCase: GetHistoryUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ResultUiState())
    val uiState: StateFlow<ResultUiState> = _uiState.asStateFlow()

    fun loadResult(recordId: Long) {
        viewModelScope.launch {
            // Find the record
            getHistoryUseCase().collect { history ->
                val record = history.find { it.id == recordId }
                if (record == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Result not found"
                    )
                    return@collect
                }

                _uiState.value = _uiState.value.copy(record = record)
                loadBitmaps(record)
            }
        }
    }

    private suspend fun loadBitmaps(record: ProcessedImage) {
        withContext(Dispatchers.IO) {
            try {
                // Downsample large images for preview UI to avoid OOM
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 2 // Simple downsample for preview
                }

                val beforeUri = Uri.parse(record.inputUri)
                val beforeBitmap = context.contentResolver.openInputStream(beforeUri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }?.asImageBitmap()

                val afterUri = record.outputUri?.let { Uri.parse(it) }
                val afterBitmap = afterUri?.let {
                    context.contentResolver.openInputStream(it)?.use { stream ->
                        BitmapFactory.decodeStream(stream, null, options)
                    }?.asImageBitmap()
                }

                if (beforeBitmap != null && afterBitmap != null) {
                    _uiState.value = _uiState.value.copy(
                        beforeBitmap = beforeBitmap,
                        afterBitmap = afterBitmap,
                        isLoading = false
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
}
