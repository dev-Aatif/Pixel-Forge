package com.pixelforge.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.pixelforge.app.domain.model.ModelType
import com.pixelforge.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore-backed implementation of SettingsRepository.
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : SettingsRepository {

    companion object {
        private val KEY_MODEL_TYPE = stringPreferencesKey("default_model_type")
        private val KEY_SCALE_FACTOR = intPreferencesKey("default_scale_factor")
        private val KEY_USE_GPU = booleanPreferencesKey("use_gpu")
        private val KEY_EXPORT_FORMAT = stringPreferencesKey("export_format")
        private val KEY_JPEG_QUALITY = intPreferencesKey("jpeg_quality")
        private val KEY_MAX_RESOLUTION = intPreferencesKey("max_resolution")
    }

    override fun getDefaultModelType(): Flow<ModelType> {
        return dataStore.data.map { prefs ->
            val name = prefs[KEY_MODEL_TYPE] ?: ModelType.PHOTO.modelName
            ModelType.entries.find { it.modelName == name } ?: ModelType.PHOTO
        }
    }

    override suspend fun setDefaultModelType(modelType: ModelType) {
        dataStore.edit { it[KEY_MODEL_TYPE] = modelType.modelName }
    }

    override fun getDefaultScaleFactor(): Flow<Int> {
        return dataStore.data.map { it[KEY_SCALE_FACTOR] ?: 4 }
    }

    override suspend fun setDefaultScaleFactor(scale: Int) {
        dataStore.edit { it[KEY_SCALE_FACTOR] = scale }
    }

    override fun getUseGpu(): Flow<Boolean> {
        return dataStore.data.map { it[KEY_USE_GPU] ?: true }
    }

    override suspend fun setUseGpu(useGpu: Boolean) {
        dataStore.edit { it[KEY_USE_GPU] = useGpu }
    }

    override fun getDefaultExportFormat(): Flow<String> {
        return dataStore.data.map { it[KEY_EXPORT_FORMAT] ?: "PNG" }
    }

    override suspend fun setDefaultExportFormat(format: String) {
        dataStore.edit { it[KEY_EXPORT_FORMAT] = format }
    }

    override fun getJpegQuality(): Flow<Int> {
        return dataStore.data.map { it[KEY_JPEG_QUALITY] ?: 95 }
    }

    override suspend fun setJpegQuality(quality: Int) {
        dataStore.edit { it[KEY_JPEG_QUALITY] = quality }
    }

    override fun getMaxResolution(): Flow<Int> {
        return dataStore.data.map { it[KEY_MAX_RESOLUTION] ?: 4096 }
    }

    override suspend fun setMaxResolution(maxRes: Int) {
        dataStore.edit { it[KEY_MAX_RESOLUTION] = maxRes }
    }
}
