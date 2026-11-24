package com.pixelforge.app.data.update

/**
 * Metadata describing an available PixelForge release from GitHub Releases.
 */
data class AppUpdateInfo(
    val latestVersion: String,
    val tagName: String,
    val title: String,
    val releaseNotes: String,
    val releasePageUrl: String,
    val apkDownloadUrl: String?,
    val apkSizeBytes: Long?,
    val publishedAt: String?
)

/**
 * Outcome of an update check operation.
 */
sealed class UpdateCheckResult {
    data class UpdateAvailable(val info: AppUpdateInfo) : UpdateCheckResult()
    data class UpToDate(val currentVersion: String) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}
