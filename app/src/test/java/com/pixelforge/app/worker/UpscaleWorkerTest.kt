package com.pixelforge.app.worker

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.common.util.concurrent.ListenableFuture
import com.pixelforge.app.core.constants.AppConstants
import com.pixelforge.app.core.extensions.decodeBitmapFromUri
import com.pixelforge.app.core.extensions.deleteAppOwnedFile
import com.pixelforge.app.data.local.ProcessedImageDao
import com.pixelforge.app.engine.EngineSelector
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class UpscaleWorkerTest {

    private lateinit var context: Context
    private lateinit var engineSelector: EngineSelector
    private lateinit var dao: ProcessedImageDao

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        engineSelector = mockk(relaxed = true)
        dao = mockk(relaxed = true)
        coEvery { dao.insert(any()) } returns 77L
        mockkStatic(Uri::class)
        mockkStatic("com.pixelforge.app.core.extensions.ImageExtensionsKt")
    }

    @After
    fun tearDown() {
        unmockkStatic("com.pixelforge.app.core.extensions.ImageExtensionsKt")
        unmockkStatic(Uri::class)
    }

    private fun immediateVoidFuture(): ListenableFuture<Void> = object : ListenableFuture<Void> {
        override fun cancel(mayInterruptIfRunning: Boolean) = false
        override fun isCancelled() = false
        override fun isDone() = true
        override fun get(): Void? = null
        override fun get(timeout: Long, unit: TimeUnit): Void? = null
        override fun addListener(listener: Runnable, executor: Executor) {
            executor.execute(listener)
        }
    }

    private fun buildWorker(inputUri: String?): UpscaleWorker {
        val params = mockk<WorkerParameters>(relaxed = true)
        val data = if (inputUri != null) {
            workDataOf(
                AppConstants.KEY_INPUT_URI to inputUri,
                AppConstants.KEY_MODEL_NAME to AppConstants.MODEL_FAST_PHOTO,
                AppConstants.KEY_SCALE_FACTOR to 4
            )
        } else {
            workDataOf()
        }
        every { params.id } returns UUID.randomUUID()
        every { params.inputData } returns data
        every { params.progressUpdater.updateProgress(any(), any(), any()) } returns immediateVoidFuture()
        every { params.foregroundUpdater.setForegroundAsync(any(), any(), any()) } returns immediateVoidFuture()
        return UpscaleWorker(context, params, engineSelector, dao)
    }

    @Test
    fun `missing input URI fails immediately`() = runTest {
        val worker = buildWorker(inputUri = null)
        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(
            "Missing input URI",
            (result as ListenableWorker.Result.Failure).outputData.getString(AppConstants.KEY_ERROR_MESSAGE)
        )
    }

    @Test
    fun `OutOfMemoryError cleans up DB row, deletes staged upload, and fails gracefully`() = runTest {
        val stagedUriStr = "file:///data/user/0/com.pixelforge.app/files/uploads/IMG_oom.png"
        val uri = mockk<Uri>()
        val inputBmp = mockk<Bitmap>(relaxed = true) {
            every { width } returns 1600
            every { height } returns 1200
        }
        every { Uri.parse(stagedUriStr) } returns uri
        every { context.decodeBitmapFromUri(uri, any()) } returns inputBmp
        every { context.deleteAppOwnedFile(stagedUriStr) } returns true
        coEvery { engineSelector.runPipeline(inputBmp, any(), any()) } throws OutOfMemoryError("heap limit")

        val worker = buildWorker(stagedUriStr)
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        coVerify(exactly = 1) { engineSelector.cancelCurrent() }
        coVerify(exactly = 1) { dao.deleteById(77L) }
        verify(exactly = 1) { context.deleteAppOwnedFile(stagedUriStr) }
        verify(exactly = 1) { inputBmp.recycle() }
    }

    @Test
    fun `CancellationException cancels engine, removes DB row and staged upload, and rethrows`() = runTest {
        val stagedUriStr = "file:///data/user/0/com.pixelforge.app/files/uploads/IMG_cancel.png"
        val uri = mockk<Uri>()
        val inputBmp = mockk<Bitmap>(relaxed = true) {
            every { width } returns 800
            every { height } returns 600
        }
        every { Uri.parse(stagedUriStr) } returns uri
        every { context.decodeBitmapFromUri(uri, any()) } returns inputBmp
        every { context.deleteAppOwnedFile(stagedUriStr) } returns true
        coEvery { engineSelector.runPipeline(inputBmp, any(), any()) } throws CancellationException("stopped")

        val worker = buildWorker(stagedUriStr)
        var rethrown = false
        try {
            worker.doWork()
        } catch (_: CancellationException) {
            rethrown = true
        }

        assertTrue(rethrown)
        coVerify(exactly = 1) { engineSelector.cancelCurrent() }
        coVerify(exactly = 1) { dao.deleteById(77L) }
        verify(exactly = 1) { context.deleteAppOwnedFile(stagedUriStr) }
        verify(exactly = 1) { inputBmp.recycle() }
    }
}
