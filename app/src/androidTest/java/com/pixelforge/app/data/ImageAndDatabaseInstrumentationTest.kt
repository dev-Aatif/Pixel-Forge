package com.pixelforge.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pixelforge.app.core.extensions.copyUriToInternalStorage
import com.pixelforge.app.core.extensions.decodeBitmapFromUri
import com.pixelforge.app.core.extensions.deleteAppOwnedFile
import com.pixelforge.app.core.utils.ImageAnalyzer
import com.pixelforge.app.data.local.AppDatabase
import com.pixelforge.app.data.local.ProcessedImageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * On-device instrumentation tests for Room v1->v2 SQLite migration, EXIF orientation,
 * panorama inSampleSize bounds, staged upload cleanup, and ImageAnalyzer heuristics.
 */
@RunWith(AndroidJUnit4::class)
class ImageAndDatabaseInstrumentationTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun roomMigration1To2_preservesDataAndDeletesStaleProcessing() = runBlocking {
        val dbName = "test_migration_${System.currentTimeMillis()}.db"
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `processed_images` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `inputUri` TEXT NOT NULL,
                            `outputUri` TEXT,
                            `modelName` TEXT NOT NULL,
                            `scaleFactor` INTEGER NOT NULL,
                            `inputWidth` INTEGER NOT NULL,
                            `inputHeight` INTEGER NOT NULL,
                            `outputWidth` INTEGER NOT NULL,
                            `outputHeight` INTEGER NOT NULL,
                            `fileSizeBytes` INTEGER NOT NULL,
                            `processingTimeMs` INTEGER NOT NULL,
                            `status` TEXT NOT NULL,
                            `createdAt` INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        INSERT INTO processed_images
                        (id, inputUri, outputUri, modelName, scaleFactor, inputWidth, inputHeight,
                         outputWidth, outputHeight, fileSizeBytes, processingTimeMs, status, createdAt)
                        VALUES
                        (1, 'file:///in.jpg', 'content://out.png', 'realesr-general-x4v3', 4,
                         400, 300, 1600, 1200, 102400, 2100, 'completed', 1700000000000)
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            helper.writableDatabase.close()
        }

        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
        try {
            val dao = roomDb.processedImageDao()
            val migrated = dao.getById(1L)
            assertNotNull(migrated)
            assertEquals("NCNN", migrated?.backendUsed)
            assertEquals("completed", migrated?.status)

            val staleId = dao.insert(
                ProcessedImageEntity(
                    inputUri = "file:///stale.jpg",
                    outputUri = null,
                    modelName = "realesr-general-x4v3",
                    scaleFactor = 4,
                    inputWidth = 400,
                    inputHeight = 400,
                    outputWidth = 1600,
                    outputHeight = 1600,
                    fileSizeBytes = 0,
                    processingTimeMs = 0,
                    status = "processing",
                    backendUsed = "Initializing",
                    createdAt = 100L
                )
            )
            dao.deleteStaleProcessing()
            assertNull(dao.getById(staleId))
            assertNotNull(dao.getById(1L))
        } finally {
            roomDb.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun decodeBitmapFromUri_appliesExifOrientationAndWidePanoramaCapping() {
        val dir = File(context.cacheDir, "exif_test_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val bmp = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint()
            paint.color = Color.RED
            canvas.drawRect(0f, 0f, 30f, 40f, paint)
            paint.color = Color.BLUE
            canvas.drawRect(30f, 0f, 60f, 40f, paint)

            val rot90File = File(dir, "rot90.jpg")
            FileOutputStream(rot90File).use { bmp.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            bmp.recycle()

            ExifInterface(rot90File.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }

            val decoded90 = context.decodeBitmapFromUri(Uri.fromFile(rot90File), maxDimension = 1000)
            assertNotNull(decoded90)
            assertEquals(40, decoded90!!.width)
            assertEquals(60, decoded90.height)
            val top = decoded90.getPixel(20, 10)
            val bot = decoded90.getPixel(20, 50)
            assertTrue("Top should be red after 90 CW", Color.red(top) > 180 && Color.blue(top) < 70)
            assertTrue("Bottom should be blue after 90 CW", Color.blue(bot) > 180 && Color.red(bot) < 70)
            decoded90.recycle()

            val panoFile = File(dir, "pano.png")
            Bitmap.createBitmap(1600, 200, Bitmap.Config.ARGB_8888).let { pano ->
                pano.eraseColor(Color.GREEN)
                FileOutputStream(panoFile).use { pano.compress(Bitmap.CompressFormat.PNG, 100, it) }
                pano.recycle()
            }
            val decodedPano = context.decodeBitmapFromUri(Uri.fromFile(panoFile), maxDimension = 400)
            assertNotNull(decodedPano)
            assertEquals(400, decodedPano!!.width)
            assertEquals(50, decodedPano.height)
            decodedPano.recycle()

            val staged = context.copyUriToInternalStorage(Uri.fromFile(panoFile))
            assertNotNull(staged)
            assertFalse(context.deleteAppOwnedFile(Uri.fromFile(panoFile).toString()))
            assertTrue(context.deleteAppOwnedFile(staged.toString()))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun imageAnalyzer_distinguishesFlatArtWithAlphaFromNoisyPhoto() {
        val flat = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.argb(128, 50, 120, 210))
        }
        val flatProfile = ImageAnalyzer.analyze(flat)
        assertTrue(flatProfile.hasAlpha)
        assertTrue(flatProfile.isLikelyFlatIllustration)
        flat.recycle()

        val noisy = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                noisy.setPixel(x, y, Color.rgb((x * 53 + y * 97) % 256, (x * 29 + y * 71) % 256, (x * 89 + y * 17) % 256))
            }
        }
        val noisyProfile = ImageAnalyzer.analyze(noisy)
        assertFalse(noisyProfile.hasAlpha)
        assertFalse(noisyProfile.isLikelyFlatIllustration)
        noisy.recycle()
    }
}
