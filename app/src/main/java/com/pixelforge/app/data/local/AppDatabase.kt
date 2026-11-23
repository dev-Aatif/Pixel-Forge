package com.pixelforge.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database definition.
 */
@Database(
    entities = [ProcessedImageEntity::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun processedImageDao(): ProcessedImageDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE processed_images ADD COLUMN backendUsed TEXT NOT NULL DEFAULT 'NCNN'"
                )
            }
        }
    }
}
