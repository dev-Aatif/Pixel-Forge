package com.pixelforge.app.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

class AppDatabaseMigrationTest {

    @Test
    fun `MIGRATION_1_2 targets versions 1 to 2 and adds backendUsed with NCNN default`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)

        assertEquals(1, AppDatabase.MIGRATION_1_2.startVersion)
        assertEquals(2, AppDatabase.MIGRATION_1_2.endVersion)

        AppDatabase.MIGRATION_1_2.migrate(db)

        verify(exactly = 1) {
            db.execSQL("ALTER TABLE processed_images ADD COLUMN backendUsed TEXT NOT NULL DEFAULT 'NCNN'")
        }
    }
}
