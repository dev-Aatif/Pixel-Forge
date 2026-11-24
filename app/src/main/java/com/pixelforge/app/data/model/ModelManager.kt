package com.pixelforge.app.data.model

import android.content.Context
import com.pixelforge.app.core.constants.AppConstants
import com.pixelforge.app.domain.model.ModelType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Resolves model files from bundled APK assets first, falling back to on-demand
 * downloaded models under `filesDir/models/`.
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val MIN_ULTRA_PARAM_BYTES = 10_000L
        private const val MIN_ULTRA_BIN_BYTES = 20_000_000L
        private const val MIN_FACE_ONNX_BYTES = 200_000_000L
        private const val MIN_FREE_SPACE_MARGIN_BYTES = 32L * 1024L * 1024L
        private const val NCNN_PARAM_MAGIC = "7767517"
    }

    private val modelsDir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    fun isNcnnModelAvailable(baseName: String): Boolean {
        if (assetExists("models/v1/$baseName.param") && assetExists("models/v1/$baseName.bin")) {
            return true
        }
        val paramFile = File(modelsDir, "$baseName.param")
        val binFile = File(modelsDir, "$baseName.bin")
        val minBin = if (baseName == AppConstants.MODEL_ULTRA_PHOTO) MIN_ULTRA_BIN_BYTES else 100_000L
        val minParam = if (baseName == AppConstants.MODEL_ULTRA_PHOTO) MIN_ULTRA_PARAM_BYTES else 256L
        return paramFile.exists() && paramFile.length() >= minParam &&
            binFile.exists() && binFile.length() >= minBin &&
            hasNcnnParamMagic(paramFile)
    }

    fun isModelAvailable(modelType: ModelType, scale: Int): Boolean {
        if (modelType.onnxAssetPath != null && assetExists(modelType.onnxAssetPath)) {
            return true
        }
        return isNcnnModelAvailable(modelType.ncnnModelBaseName(scale))
    }

    fun isFaceRestoreAvailable(): Boolean {
        val f = faceRestoreFile()
        return f.exists() && f.length() >= MIN_FACE_ONNX_BYTES
    }

    fun faceRestoreFile(): File = File(modelsDir, "${AppConstants.MODEL_FACE_RESTORE}.onnx")

    fun ncnnExternalParamPath(baseName: String): String? {
        if (!isNcnnModelAvailable(baseName)) return null
        val f = File(modelsDir, "$baseName.param")
        return if (f.exists() && f.length() > 0) f.absolutePath else null
    }

    fun ncnnExternalBinPath(baseName: String): String? {
        if (!isNcnnModelAvailable(baseName)) return null
        val f = File(modelsDir, "$baseName.bin")
        return if (f.exists() && f.length() > 0) f.absolutePath else null
    }

    suspend fun downloadUltraPhotoModel(
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val paramFile = File(modelsDir, "${AppConstants.MODEL_ULTRA_PHOTO}.param")
        val binFile = File(modelsDir, "${AppConstants.MODEL_ULTRA_PHOTO}.bin")
        val paramTmp = File(modelsDir, "${paramFile.name}.tmp")
        val binTmp = File(modelsDir, "${binFile.name}.tmp")
        runCatching {
            ensureEnoughDiskSpace(MIN_ULTRA_PARAM_BYTES + MIN_ULTRA_BIN_BYTES + MIN_FREE_SPACE_MARGIN_BYTES)
            downloadToTempFile(
                urlStr = AppConstants.URL_ULTRA_PHOTO_PARAM,
                tmpFile = paramTmp,
                minBytes = MIN_ULTRA_PARAM_BYTES,
                expectedSha256 = null
            ) { p -> onProgress(p * 0.05f) }
            if (!hasNcnnParamMagic(paramTmp)) {
                throw IllegalStateException("Invalid NCNN param header in downloaded file")
            }

            downloadToTempFile(
                urlStr = AppConstants.URL_ULTRA_PHOTO_BIN,
                tmpFile = binTmp,
                minBytes = MIN_ULTRA_BIN_BYTES,
                expectedSha256 = null
            ) { p -> onProgress(0.05f + p * 0.95f) }

            promoteTempFile(paramTmp, paramFile)
            promoteTempFile(binTmp, binFile)
        }.onFailure {
            paramTmp.delete()
            binTmp.delete()
        }
    }

    suspend fun downloadFaceRestoreModel(
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val target = faceRestoreFile()
        val tmp = File(modelsDir, "${target.name}.tmp")
        runCatching {
            ensureEnoughDiskSpace(350L * 1024L * 1024L + MIN_FREE_SPACE_MARGIN_BYTES)
            downloadToTempFile(
                urlStr = AppConstants.URL_FACE_RESTORE_ONNX,
                tmpFile = tmp,
                minBytes = MIN_FACE_ONNX_BYTES,
                expectedSha256 = null,
                onProgress = onProgress
            )
            promoteTempFile(tmp, target)
        }.onFailure {
            tmp.delete()
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

    private fun hasNcnnParamMagic(file: File): Boolean {
        return runCatching {
            file.bufferedReader().use { reader ->
                reader.readLine()?.trim() == NCNN_PARAM_MAGIC
            }
        }.getOrDefault(false)
    }

    @Suppress("UsableSpace")
    private fun ensureEnoughDiskSpace(requiredBytes: Long) {
        val available = modelsDir.usableSpace
        if (available in 1 until requiredBytes) {
            throw IllegalStateException("Insufficient storage space (${available / (1024 * 1024)} MB free)")
        }
    }

    private suspend fun downloadToTempFile(
        urlStr: String,
        tmpFile: File,
        minBytes: Long,
        expectedSha256: String?,
        onProgress: (Float) -> Unit
    ) {
        val url = URL(urlStr)
        require(url.protocol.equals("https", ignoreCase = true)) {
            "Insecure model download URL scheme: ${url.protocol}"
        }

        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code downloading ${tmpFile.name}")
            }
            val contentType = conn.contentType?.lowercase().orEmpty()
            if (contentType.contains("text/html")) {
                throw IllegalStateException("Unexpected HTML response downloading ${tmpFile.name}")
            }

            val total = conn.contentLengthLong
            if (total > 0) {
                ensureEnoughDiskSpace(total + MIN_FREE_SPACE_MARGIN_BYTES)
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            conn.inputStream.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        digest.update(buf, 0, read)
                        downloaded += read
                        if (total > 0) {
                            onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                    output.fd.sync()
                }
            }

            if (total > 0 && downloaded != total) {
                throw IllegalStateException("Incomplete download for ${tmpFile.name}: $downloaded / $total bytes")
            }
            if (downloaded < minBytes) {
                throw IllegalStateException("Downloaded file ${tmpFile.name} too small ($downloaded bytes)")
            }
            if (!expectedSha256.isNullOrBlank()) {
                val actualHex = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualHex.equals(expectedSha256, ignoreCase = true)) {
                    throw SecurityException("Checksum mismatch for ${tmpFile.name}")
                }
            }
            onProgress(1f)
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun promoteTempFile(tmp: File, target: File) {
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
