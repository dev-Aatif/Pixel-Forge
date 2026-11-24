package com.pixelforge.app.presentation.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixelforge.app.domain.model.ProcessedImage
import com.pixelforge.app.domain.usecase.GetHistoryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val recentImages: List<ProcessedImage> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val getHistoryUseCase: GetHistoryUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadRecentImages()
    }

    private fun loadRecentImages() {
        viewModelScope.launch {
            getHistoryUseCase().collect { images ->
                _uiState.update {
                    it.copy(
                        recentImages = images.filter { img -> img.status == "completed" }.take(10),
                        isLoading = false
                    )
                }
            }
        }
    }
}
