package com.pixelforge.app.domain.repository

import com.pixelforge.app.domain.model.ModelType
import com.pixelforge.app.core.utils.MediaStoreUtils
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for user settings/preferences.
 */
interface SettingsRepository {

    fun getDefaultModelType(): Flow<ModelType>
    suspend fun setDefaultModelType(modelType: ModelType)

    fun getDefaultScaleFactor(): Flow<Int>
    suspend fun setDefaultScaleFactor(scale: Int)

    fun getUseGpu(): Flow<Boolean>
    suspend fun setUseGpu(useGpu: Boolean)

    fun getDefaultExportFormat(): Flow<String>
    suspend fun setDefaultExportFormat(format: String)

    fun getJpegQuality(): Flow<Int>
    suspend fun setJpegQuality(quality: Int)

    fun getMaxResolution(): Flow<Int>
    suspend fun setMaxResolution(maxRes: Int)
}
