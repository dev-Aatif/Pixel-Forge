package com.pixelforge.app.data.repository

import com.pixelforge.app.data.local.ProcessedImageDao
import com.pixelforge.app.data.mapper.ImageMapper.toDomain
import com.pixelforge.app.data.mapper.ImageMapper.toEntity
import com.pixelforge.app.domain.model.ProcessedImage
import com.pixelforge.app.domain.repository.ImageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of ImageRepository backed by Room.
 */
@Singleton
class ImageRepositoryImpl @Inject constructor(
    private val dao: ProcessedImageDao
) : ImageRepository {

    override fun getHistory(): Flow<List<ProcessedImage>> {
        return dao.getAllOrdered().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getById(id: Long): ProcessedImage? {
        return dao.getById(id)?.toDomain()
    }

    override suspend fun insert(image: ProcessedImage): Long {
        return dao.insert(image.toEntity())
    }

    override suspend fun update(image: ProcessedImage) {
        dao.update(image.toEntity())
    }

    override suspend fun deleteById(id: Long) {
        dao.deleteById(id)
    }

    override suspend fun deleteAll() {
        dao.deleteAll()
    }

    override suspend fun getCount(): Int {
        return dao.getCount()
    }
}
