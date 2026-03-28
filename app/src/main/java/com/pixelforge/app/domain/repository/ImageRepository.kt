package com.pixelforge.app.domain.repository

import com.pixelforge.app.domain.model.ProcessedImage
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for image persistence operations.
 * Implemented in data layer — domain layer only knows this interface.
 */
interface ImageRepository {

    /**
     * Get all processed images ordered by creation date (newest first).
     */
    fun getHistory(): Flow<List<ProcessedImage>>

    /**
     * Get a single processed image by ID.
     */
    suspend fun getById(id: Long): ProcessedImage?

    /**
     * Insert a new processed image record.
     * @return The ID of the inserted record.
     */
    suspend fun insert(image: ProcessedImage): Long

    /**
     * Update an existing processed image record.
     */
    suspend fun update(image: ProcessedImage)

    /**
     * Delete a processed image record by ID.
     * Does NOT delete the actual file — caller must handle that separately.
     */
    suspend fun deleteById(id: Long)

    /**
     * Delete all processed image records.
     */
    suspend fun deleteAll()

    /**
     * Get total count of processed images.
     */
    suspend fun getCount(): Int
}
