package com.pixelforge.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database definition.
 */
@Database(
    entities = [ProcessedImageEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun processedImageDao(): ProcessedImageDao
}
