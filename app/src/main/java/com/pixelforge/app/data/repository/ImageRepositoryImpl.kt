package com.pixelforge.app.data.repository

import android.content.Context
import android.net.Uri
import com.pixelforge.app.data.local.ProcessedImageDao
import com.pixelforge.app.data.mapper.ImageMapper.toDomain
import com.pixelforge.app.data.mapper.ImageMapper.toEntity
import com.pixelforge.app.domain.model.ProcessedImage
import com.pixelforge.app.domain.repository.ImageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of ImageRepository backed by Room.
 * Also cleans up app-private staged input files when records are removed.
 */
@Singleton
class ImageRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
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
        val existing = dao.getById(id)
        dao.deleteById(id)
        existing?.let { deleteAppOwnedStagedFile(it.inputUri) }
    }

    override suspend fun deleteAll() {
        val snapshot = dao.getAllSnapshot()
        dao.deleteAll()
        snapshot.forEach { deleteAppOwnedStagedFile(it.inputUri) }
    }

    override suspend fun getCount(): Int {
        return dao.getCount()
    }

    private fun deleteAppOwnedStagedFile(uriString: String) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        if (uri.scheme != "file") return
        val path = uri.path ?: return
        val file = File(path)
        val filesRoot = context.filesDir.canonicalPath
        val cacheRoot = context.cacheDir.canonicalPath
        val targetCanonical = runCatching { file.canonicalPath }.getOrNull() ?: return
        if (targetCanonical.startsWith(filesRoot) || targetCanonical.startsWith(cacheRoot)) {
            file.delete()
        }
    }
}
