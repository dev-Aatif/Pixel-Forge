package com.pixelforge.app.data.model

import android.content.Context
import com.pixelforge.app.core.constants.AppConstants
import com.pixelforge.app.domain.model.ModelType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves model files from bundled APK assets first, falling back to on-demand
 * downloaded models under `filesDir/models/`.
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val modelsDir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    fun isNcnnModelAvailable(baseName: String): Boolean {
        if (assetExists("models/v1/$baseName.param") && assetExists("models/v1/$baseName.bin")) {
            return true
        }
        val paramFile = File(modelsDir, "$baseName.param")
        val binFile = File(modelsDir, "$baseName.bin")
        return paramFile.exists() && paramFile.length() > 0 && binFile.exists() && binFile.length() > 0
    }

    fun isModelAvailable(modelType: ModelType, scale: Int): Boolean {
        if (modelType.onnxAssetPath != null && assetExists(modelType.onnxAssetPath)) {
            return true
        }
        return isNcnnModelAvailable(modelType.ncnnModelBaseName(scale))
    }

    fun isFaceRestoreAvailable(): Boolean {
        val f = faceRestoreFile()
        return f.exists() && f.length() > 10_000_000L
    }

    fun faceRestoreFile(): File = File(modelsDir, "${AppConstants.MODEL_FACE_RESTORE}.onnx")

    fun ncnnExternalParamPath(baseName: String): String? {
        val f = File(modelsDir, "$baseName.param")
        return if (f.exists() && f.length() > 0) f.absolutePath else null
    }

    fun ncnnExternalBinPath(baseName: String): String? {
        val f = File(modelsDir, "$baseName.bin")
        return if (f.exists() && f.length() > 0) f.absolutePath else null
    }

    suspend fun downloadUltraPhotoModel(
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val paramFile = File(modelsDir, "${AppConstants.MODEL_ULTRA_PHOTO}.param")
            val binFile = File(modelsDir, "${AppConstants.MODEL_ULTRA_PHOTO}.bin")
            downloadToFile(AppConstants.URL_ULTRA_PHOTO_PARAM, paramFile) { p ->
                onProgress(p * 0.05f)
            }
            downloadToFile(AppConstants.URL_ULTRA_PHOTO_BIN, binFile) { p ->
                onProgress(0.05f + p * 0.95f)
            }
        }
    }

    suspend fun downloadFaceRestoreModel(
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            downloadToFile(AppConstants.URL_FACE_RESTORE_ONNX, faceRestoreFile(), onProgress)
        }
    }

    fun getDownloadedModelsSizeBytes(): Long {
        return modelsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    fun clearDownloadedModels() {
        modelsDir.listFiles()?.forEach { it.delete() }
    }

    private fun assetExists(path: String): Boolean {
        return runCatching {
            context.assets.open(path).close()
            true
        }.getOrDefault(false)
    }

    private fun downloadToFile(
        urlStr: String,
        target: File,
        onProgress: (Float) -> Unit
    ) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code downloading ${target.name}")
            }
            val total = conn.contentLengthLong
            var downloaded = 0L
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) {
                            onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            onProgress(1f)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }
}
