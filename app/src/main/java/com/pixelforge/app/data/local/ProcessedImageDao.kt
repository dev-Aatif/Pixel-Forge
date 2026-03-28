package com.pixelforge.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for processed image CRUD operations.
 */
@Dao
interface ProcessedImageDao {

    @Query("SELECT * FROM processed_images ORDER BY createdAt DESC")
    fun getAllOrdered(): Flow<List<ProcessedImageEntity>>

    @Query("SELECT * FROM processed_images WHERE id = :id")
    suspend fun getById(id: Long): ProcessedImageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ProcessedImageEntity): Long

    @Update
    suspend fun update(entity: ProcessedImageEntity)

    @Query("DELETE FROM processed_images WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM processed_images")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM processed_images")
    suspend fun getCount(): Int
}
