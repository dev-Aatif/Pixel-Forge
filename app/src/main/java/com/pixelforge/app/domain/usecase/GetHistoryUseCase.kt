package com.pixelforge.app.domain.usecase

import com.pixelforge.app.domain.model.ProcessedImage
import com.pixelforge.app.domain.repository.ImageRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case: retrieve image processing history.
 */
class GetHistoryUseCase @Inject constructor(
    private val repository: ImageRepository
) {
    operator fun invoke(): Flow<List<ProcessedImage>> {
        return repository.getHistory()
    }
}
