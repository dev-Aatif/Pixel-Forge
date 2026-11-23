package com.pixelforge.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.pixelforge.app.core.constants.AppConstants
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
        private val KEY_USE_NPU = booleanPreferencesKey("use_npu")
        private val KEY_FACE_ENHANCE = booleanPreferencesKey("face_enhance")
        private val KEY_EXPORT_FORMAT = stringPreferencesKey("export_format")
        private val KEY_JPEG_QUALITY = intPreferencesKey("jpeg_quality")
        private val KEY_MAX_RESOLUTION = intPreferencesKey("max_resolution")
    }

    override fun getDefaultModelType(): Flow<ModelType> {
        return dataStore.data.map { prefs ->
            ModelType.fromModelName(prefs[KEY_MODEL_TYPE])
        }
    }

    override suspend fun setDefaultModelType(modelType: ModelType) {
        dataStore.edit { it[KEY_MODEL_TYPE] = modelType.modelName }
    }

    override fun getDefaultScaleFactor(): Flow<Int> {
        return dataStore.data.map { it[KEY_SCALE_FACTOR] ?: AppConstants.DEFAULT_SCALE_FACTOR }
    }

    override suspend fun setDefaultScaleFactor(scale: Int) {
        dataStore.edit { it[KEY_SCALE_FACTOR] = scale }
    }

    override fun getUseGpu(): Flow<Boolean> {
        return dataStore.data.map { it[KEY_USE_GPU] ?: AppConstants.DEFAULT_USE_GPU }
    }

    override suspend fun setUseGpu(useGpu: Boolean) {
        dataStore.edit { it[KEY_USE_GPU] = useGpu }
    }

    override fun getUseNpu(): Flow<Boolean> {
        return dataStore.data.map { it[KEY_USE_NPU] ?: AppConstants.DEFAULT_USE_NPU }
    }

    override suspend fun setUseNpu(useNpu: Boolean) {
        dataStore.edit { it[KEY_USE_NPU] = useNpu }
    }

    override fun getFaceEnhanceDefault(): Flow<Boolean> {
        return dataStore.data.map { it[KEY_FACE_ENHANCE] ?: AppConstants.DEFAULT_FACE_ENHANCE }
    }

    override suspend fun setFaceEnhanceDefault(enabled: Boolean) {
        dataStore.edit { it[KEY_FACE_ENHANCE] = enabled }
    }

    override fun getDefaultExportFormat(): Flow<String> {
        return dataStore.data.map { it[KEY_EXPORT_FORMAT] ?: AppConstants.DEFAULT_EXPORT_FORMAT }
    }

    override suspend fun setDefaultExportFormat(format: String) {
        dataStore.edit { it[KEY_EXPORT_FORMAT] = format }
    }

    override fun getJpegQuality(): Flow<Int> {
        return dataStore.data.map { it[KEY_JPEG_QUALITY] ?: AppConstants.DEFAULT_JPEG_QUALITY }
    }

    override suspend fun setJpegQuality(quality: Int) {
        dataStore.edit { it[KEY_JPEG_QUALITY] = quality.coerceIn(50, 100) }
    }

    override fun getMaxResolution(): Flow<Int> {
        return dataStore.data.map { it[KEY_MAX_RESOLUTION] ?: AppConstants.DEFAULT_MAX_RESOLUTION }
    }

    override suspend fun setMaxResolution(maxRes: Int) {
        dataStore.edit { it[KEY_MAX_RESOLUTION] = maxRes }
    }
}
