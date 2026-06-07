package com.mediasync.data

import android.content.Context
import androidx.room.*

@Dao
interface SyncedFileDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(file: SyncedFile): Long

    @Query("SELECT EXISTS(SELECT 1 FROM synced_files WHERE sha256 = :sha256)")
    suspend fun exists(sha256: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM synced_files WHERE uri = :uri)")
    suspend fun existsByUri(uri: String): Boolean

    // Добавляет только URI к уже известному хешу (быстрый путь)
    @Query("UPDATE synced_files SET uri = :uri WHERE sha256 = :sha256")
    suspend fun insertUri(uri: String, sha256: String)

    @Query("SELECT COUNT(*) FROM synced_files")
    suspend fun count(): Long

    @Query("SELECT COUNT(*) FROM synced_files WHERE fileType = 'video'")
    suspend fun countVideos(): Long

    @Query("SELECT COUNT(*) FROM synced_files WHERE fileType = 'photo'")
    suspend fun countPhotos(): Long
}

@Database(entities = [SyncedFile::class], version = 2, exportSchema = false)
abstract class SyncDatabase : RoomDatabase() {

    abstract fun syncedFileDao(): SyncedFileDao

    companion object {
        @Volatile private var INSTANCE: SyncDatabase? = null

        fun getInstance(context: Context): SyncDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    SyncDatabase::class.java,
                    "mediasync.db"
                )
                .fallbackToDestructiveMigration() // при смене версии пересоздаём
                .build().also { INSTANCE = it }
            }
        }
    }
}
