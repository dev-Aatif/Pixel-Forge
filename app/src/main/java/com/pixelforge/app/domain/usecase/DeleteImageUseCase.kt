package com.pixelforge.app.domain.usecase

import com.pixelforge.app.domain.repository.ImageRepository
import javax.inject.Inject

/**
 * Use case: delete a processed image record from history.
 */
class DeleteImageUseCase @Inject constructor(
    private val repository: ImageRepository
) {
    suspend operator fun invoke(id: Long) {
        repository.deleteById(id)
    }
}
