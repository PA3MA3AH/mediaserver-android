package com.mediasync.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Запись о файле, который уже был успешно отправлен на сервер.
 * Храним SHA-256 чтобы не отправлять дубликаты даже если файл переименован.
 */
@Entity(
    tableName = "synced_files",
    indices = [Index(value = ["sha256"], unique = true)]
)
data class SyncedFile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** HEX SHA-256 файла */
    val sha256: String,

    /** URI файла в MediaStore (для отладки) */
    val uri: String,

    /** Оригинальное имя файла */
    val originalName: String,

    /** UNIX timestamp съёмки (секунды) */
    val shotAt: Long,

    /** UNIX timestamp отправки */
    val syncedAt: Long = System.currentTimeMillis() / 1000,

    /** Тип: "video" или "photo" */
    val fileType: String,
)
