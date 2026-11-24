package com.pixelforge.app.data.update

import com.pixelforge.app.BuildConfig
import com.pixelforge.app.core.constants.AppConstants
import com.pixelforge.app.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks GitHub Releases for newer PixelForge builds over HTTPS, drives in-app update
 * dialogs, and dispatches OS notifications when enabled.
 */
@Singleton
class AppUpdateEngine @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val updateNotifier: AppUpdateNotifier
) {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 8_000
    }

    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    internal var currentVersionProvider: () -> String = { BuildConfig.VERSION_NAME }
    internal var nowProvider: () -> Long = { System.currentTimeMillis() }
    internal var fetchLatestReleaseJson: suspend () -> String = {
        fetchReleaseJsonFromUrl(AppConstants.URL_GITHUB_RELEASES_API)
    }

    private val checkMutex = Mutex()

    private val _updateState = MutableStateFlow<UpdateCheckResult?>(null)
    val updateState: StateFlow<UpdateCheckResult?> = _updateState.asStateFlow()

    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    /**
     * Checks once every [AppConstants.UPDATE_CHECK_INTERVAL_MS] on app launch when enabled.
     * Network failures stay silent so offline usage is never disrupted.
     */
    suspend fun checkIfDueOnLaunch() {
        val autoEnabled = runCatching { settingsRepository.getAutoCheckUpdates().first() }.getOrDefault(true)
        if (!autoEnabled) return
        val lastCheck = runCatching { settingsRepository.getLastUpdateCheckMs().first() }.getOrDefault(0L)
        val elapsed = nowProvider() - lastCheck
        if (lastCheck > 0L && elapsed < AppConstants.UPDATE_CHECK_INTERVAL_MS) return

        checkForUpdates(manual = false)
    }

    /**
     * Queries the latest GitHub release and compares versions semantically against [BuildConfig.VERSION_NAME].
     */
    suspend fun checkForUpdates(manual: Boolean = true): UpdateCheckResult = checkMutex.withLock {
        _isChecking.value = true
        try {
            val currentRaw = currentVersionProvider()
            val currentVersion = AppVersion.parse(currentRaw)
                ?: AppVersion(BuildConfig.VERSION_CODE, 0, 0)

            val payload = withContext(ioDispatcher) { fetchLatestReleaseJson() }
            val parsed = parseReleasePayload(payload)
            runCatching { settingsRepository.setLastUpdateCheckMs(nowProvider()) }

            if (parsed == null) {
                val upToDate = UpdateCheckResult.UpToDate(currentRaw)
                _updateState.value = upToDate
                return@withLock upToDate
            }

            val remoteVersion = AppVersion.parse(parsed.tagName)
            val result = if (remoteVersion != null && remoteVersion > currentVersion) {
                val dismissed = runCatching {
                    settingsRepository.getDismissedUpdateVersion().first()
                }.getOrNull()
                if (manual || dismissed != parsed.tagName) {
                    _showUpdateDialog.value = true
                }
                if (manual || dismissed != parsed.tagName) {
                    updateNotifier.showUpdateNotification(parsed, force = manual)
                }
                UpdateCheckResult.UpdateAvailable(parsed)
            } else {
                _showUpdateDialog.value = false
                UpdateCheckResult.UpToDate(currentRaw)
            }

            _updateState.value = result
            result
        } catch (t: Throwable) {
            val message = t.message ?: "Unable to reach GitHub Releases"
            val error = UpdateCheckResult.Error(message)
            if (manual) {
                _updateState.value = error
            }
            error
        } finally {
            _isChecking.value = false
        }
    }

    fun openUpdateDialogIfAvailable() {
        if (_updateState.value is UpdateCheckResult.UpdateAvailable) {
            _showUpdateDialog.value = true
        }
    }

    suspend fun dismissUpdateDialog(skipThisVersion: Boolean = false) {
        _showUpdateDialog.value = false
        if (skipThisVersion) {
            val available = _updateState.value as? UpdateCheckResult.UpdateAvailable ?: return
            runCatching {
                settingsRepository.setDismissedUpdateVersion(available.info.tagName)
            }
        }
    }

    internal fun parseReleasePayload(json: String): AppUpdateInfo? {
        val obj = MinimalJson.parseObject(json)
        if (obj["draft"] == true || obj["prerelease"] == true) {
            return null
        }

        val tagName = (obj["tag_name"] as? String)?.trim().orEmpty()
        if (tagName.isEmpty()) return null

        val releaseUrl = (obj["html_url"] as? String)?.trim()
            ?.takeIf { it.startsWith("https://") }
            ?: AppConstants.URL_GITHUB_RELEASES

        val title = (obj["name"] as? String)?.trim().orEmpty().ifEmpty { "PixelForge $tagName" }
        val notes = (obj["body"] as? String)?.trim().orEmpty()
            .ifEmpty { "Bug fixes, stability updates, and performance improvements." }
        val publishedAt = (obj["published_at"] as? String)?.trim()?.ifEmpty { null }

        var apkUrl: String? = null
        var apkSize: Long? = null
        val assets = obj["assets"] as? List<*>
        if (assets != null) {
            for (item in assets) {
                val asset = item as? Map<*, *> ?: continue
                val name = asset["name"] as? String ?: continue
                val url = (asset["browser_download_url"] as? String)?.trim().orEmpty()
                if (name.endsWith(".apk", ignoreCase = true) && url.startsWith("https://")) {
                    apkUrl = url
                    val bytes = (asset["size"] as? Number)?.toLong() ?: -1L
                    apkSize = if (bytes > 0L) bytes else null
                    break
                }
            }
        }

        return AppUpdateInfo(
            latestVersion = tagName.removePrefix("v").removePrefix("V"),
            tagName = tagName,
            title = title,
            releaseNotes = notes,
            releasePageUrl = releaseUrl,
            apkDownloadUrl = apkUrl,
            apkSizeBytes = apkSize,
            publishedAt = publishedAt
        )
    }

    private fun fetchReleaseJsonFromUrl(urlStr: String): String {
        require(urlStr.startsWith("https://")) { "Update endpoint must use HTTPS" }
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PixelForge/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = conn.responseCode
            if (code == 403 || code == 429) {
                throw IOException("GitHub API rate limit reached. Try again later.")
            }
            if (code !in 200..299) {
                throw IOException("Update check failed (HTTP $code)")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
