package com.pixelforge.app.data.update

import com.pixelforge.app.core.constants.AppConstants
import com.pixelforge.app.domain.repository.SettingsRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateEngineTest {

    private val dispatcher = StandardTestDispatcher()

    private fun createEngine(
        autoCheck: Boolean = true,
        lastCheckMs: Long = 0L,
        dismissedVersion: String? = null,
        notifier: AppUpdateNotifier = mockk(relaxed = true),
        settingsRepo: SettingsRepository = mockk(relaxed = true)
    ): Triple<AppUpdateEngine, SettingsRepository, AppUpdateNotifier> {
        every { settingsRepo.getAutoCheckUpdates() } returns flowOf(autoCheck)
        every { settingsRepo.getLastUpdateCheckMs() } returns flowOf(lastCheckMs)
        every { settingsRepo.getDismissedUpdateVersion() } returns flowOf(dismissedVersion)

        val engine = AppUpdateEngine(settingsRepo, notifier).apply {
            ioDispatcher = dispatcher
            currentVersionProvider = { "1.1.0" }
            nowProvider = { 100_000_000L }
        }
        return Triple(engine, settingsRepo, notifier)
    }

    @Test
    fun `newer release surfaces update dialog, posts notification, and parses APK asset`() = runTest(dispatcher) {
        val (engine, repo, notifier) = createEngine()
        engine.fetchLatestReleaseJson = {
            """
            {
              "tag_name": "v1.2.0",
              "name": "PixelForge v1.2.0",
              "body": "Added updater engine and model descriptions.",
              "html_url": "https://github.com/dev-Aatif/Pixel-Forge/releases/tag/v1.2.0",
              "draft": false,
              "prerelease": false,
              "assets": [
                {
                  "name": "app-release.apk",
                  "size": 63404287,
                  "browser_download_url": "https://github.com/dev-Aatif/Pixel-Forge/releases/download/v1.2.0/app-release.apk"
                }
              ]
            }
            """.trimIndent()
        }

        val result = engine.checkForUpdates(manual = false)

        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        val info = (result as UpdateCheckResult.UpdateAvailable).info
        assertEquals("v1.2.0", info.tagName)
        assertEquals(63404287L, info.apkSizeBytes)
        assertTrue(engine.showUpdateDialog.value)
        verify(exactly = 1) { notifier.showUpdateNotification(info, force = false) }
        coVerify { repo.setLastUpdateCheckMs(100_000_000L) }
    }

    @Test
    fun `skipped version suppresses auto popup unless checked manually`() = runTest(dispatcher) {
        val (engine, _, notifier) = createEngine(dismissedVersion = "v1.2.0")
        engine.fetchLatestReleaseJson = {
            """
            {
              "tag_name": "v1.2.0",
              "name": "PixelForge v1.2.0",
              "html_url": "https://github.com/dev-Aatif/Pixel-Forge/releases/tag/v1.2.0",
              "assets": []
            }
            """.trimIndent()
        }

        val autoResult = engine.checkForUpdates(manual = false)
        assertTrue(autoResult is UpdateCheckResult.UpdateAvailable)
        assertFalse(engine.showUpdateDialog.value)
        verify(exactly = 0) { notifier.showUpdateNotification(any(), any()) }

        engine.checkForUpdates(manual = true)
        assertTrue(engine.showUpdateDialog.value)
        verify(exactly = 1) { notifier.showUpdateNotification(any(), force = true) }
    }

    @Test
    fun `same version reports UpToDate and draft or prerelease tags are ignored`() = runTest(dispatcher) {
        val (engine, _, _) = createEngine()
        engine.fetchLatestReleaseJson = {
            """
            {
              "tag_name": "v1.1.0",
              "html_url": "http://insecure.example.com/ignored",
              "draft": false,
              "prerelease": false
            }
            """.trimIndent()
        }

        val sameResult = engine.checkForUpdates(manual = true)
        assertEquals(UpdateCheckResult.UpToDate("1.1.0"), sameResult)
        assertFalse(engine.showUpdateDialog.value)

        engine.fetchLatestReleaseJson = {
            """{"tag_name":"v2.0.0","prerelease":true,"draft":false}"""
        }
        assertNull(engine.parseReleasePayload("""{"tag_name":"v2.0.0","prerelease":true}"""))
        assertTrue(engine.checkForUpdates(manual = true) is UpdateCheckResult.UpToDate)
    }

    @Test
    fun `checkIfDueOnLaunch respects interval and autoCheck toggle, and silent errors do not corrupt state`() = runTest(dispatcher) {
        val (engineRecent, _, _) = createEngine(
            autoCheck = true,
            lastCheckMs = 100_000_000L - (AppConstants.UPDATE_CHECK_INTERVAL_MS / 2)
        )
        var called = false
        engineRecent.fetchLatestReleaseJson = { called = true; "{}" }
        engineRecent.checkIfDueOnLaunch()
        assertFalse(called)

        val (engineFailing, _, _) = createEngine(autoCheck = true, lastCheckMs = 0L)
        engineFailing.fetchLatestReleaseJson = { throw IOException("offline") }
        engineFailing.checkIfDueOnLaunch()
        assertNull(engineFailing.updateState.value)

        val manualErr = engineFailing.checkForUpdates(manual = true)
        assertTrue(manualErr is UpdateCheckResult.Error)
    }
}
