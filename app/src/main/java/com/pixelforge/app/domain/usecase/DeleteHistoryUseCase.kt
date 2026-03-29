package com.pixelforge.app.domain.usecase

import com.pixelforge.app.domain.repository.ImageRepository
import javax.inject.Inject

/**
 * Use case: delete all processed image records from history.
 */
class DeleteHistoryUseCase @Inject constructor(
    private val repository: ImageRepository
) {
    suspend operator fun invoke() {
        repository.deleteAll()
    }
}
