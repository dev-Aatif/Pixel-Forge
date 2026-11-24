package com.pixelforge.app.data.repository

import android.content.Context
import android.net.Uri
import com.pixelforge.app.data.local.ProcessedImageDao
import com.pixelforge.app.data.local.ProcessedImageEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ImageRepositoryImplTest {

    private lateinit var rootDir: File
    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private lateinit var externalDir: File
    private lateinit var context: Context
    private lateinit var dao: ProcessedImageDao
    private lateinit var repo: ImageRepositoryImpl

    @Before
    fun setUp() {
        rootDir = createTempDirectory("pf_repo_test").toFile()
        filesDir = File(rootDir, "files").apply { mkdirs() }
        cacheDir = File(rootDir, "cache").apply { mkdirs() }
        externalDir = File(rootDir, "external").apply { mkdirs() }

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns cacheDir
        dao = mockk(relaxed = true)
        repo = ImageRepositoryImpl(context, dao)

        mockkStatic(Uri::class)
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
        rootDir.deleteRecursively()
    }

    private fun stubFileUri(file: File): String {
        val uriString = "file://${file.absolutePath}"
        val uri = mockk<Uri>()
        every { uri.scheme } returns "file"
        every { uri.path } returns file.absolutePath
        every { Uri.parse(uriString) } returns uri
        return uriString
    }

    @Test
    fun `deleteById deletes app-owned staged file and refuses external files`() = runTest {
        val internalFile = File(filesDir, "uploads/staged.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(32))
        }
        val externalFile = File(externalDir, "user_photo.jpg").apply {
            writeBytes(ByteArray(32))
        }

        val internalUri = stubFileUri(internalFile)
        val externalUri = stubFileUri(externalFile)

        coEvery { dao.getById(1L) } returns ProcessedImageEntity(
            id = 1L,
            inputUri = internalUri,
            outputUri = "content://out/1",
            modelName = "realesr-general-x4v3",
            scaleFactor = 4,
            inputWidth = 100,
            inputHeight = 100,
            outputWidth = 400,
            outputHeight = 400,
            fileSizeBytes = 100,
            processingTimeMs = 100,
            status = "completed",
            createdAt = 1L
        )
        coEvery { dao.getById(2L) } returns ProcessedImageEntity(
            id = 2L,
            inputUri = externalUri,
            outputUri = "content://out/2",
            modelName = "realesr-general-x4v3",
            scaleFactor = 4,
            inputWidth = 100,
            inputHeight = 100,
            outputWidth = 400,
            outputHeight = 400,
            fileSizeBytes = 100,
            processingTimeMs = 100,
            status = "completed",
            createdAt = 2L
        )

        repo.deleteById(1L)
        repo.deleteById(2L)

        coVerify(exactly = 1) { dao.deleteById(1L) }
        coVerify(exactly = 1) { dao.deleteById(2L) }
        assertFalse("Internal staged file must be deleted", internalFile.exists())
        assertTrue("External file must never be deleted", externalFile.exists())
    }
}
