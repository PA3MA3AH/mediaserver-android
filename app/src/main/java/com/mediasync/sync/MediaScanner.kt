package com.mediasync.sync

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.mediasync.protocol.*

private const val TAG = "MediaScanner"

// MIME → File type mapping for extended format support
private val MIME_TO_FILE_TYPE: Map<String, Byte> = mapOf(
    // Video
    "video/mp4"             to FILE_TYPE_VIDEO,
    "video/quicktime"       to FILE_TYPE_MOV,
    "video/x-matroska"      to FILE_TYPE_MKV,
    "video/3gpp"            to FILE_TYPE_3GP,
    "video/webm"            to FILE_TYPE_WEBM,
    "video/mpeg"            to FILE_TYPE_MPEG,
    // Photo
    "image/jpeg"            to FILE_TYPE_PHOTO,
    "image/png"             to FILE_TYPE_PNG,
    "image/webp"            to FILE_TYPE_WEBP,
    "image/heic"            to FILE_TYPE_HEIC,
    "image/heif"            to FILE_TYPE_HEIC,
    "image/gif"             to FILE_TYPE_GIF,
    "image/bmp"             to FILE_TYPE_BMP,
    "image/x-raw"           to FILE_TYPE_RAW,
)

private val VIDEO_MIMES = setOf(
    "video/mp4", "video/quicktime", "video/x-matroska",
    "video/3gpp", "video/webm", "video/mpeg"
)

private val PHOTO_MIMES = setOf(
    "image/jpeg", "image/png", "image/webp", "image/heic",
    "image/heif", "image/gif", "image/bmp", "image/x-raw"
)

data class MediaFile(
    val uri:       Uri,
    val name:      String,
    val size:      Long,
    val timestamp: Long,
    val isVideo:   Boolean,
    val mimeType:  String,
    val fileType:  Byte,
)

/**
 * Сканирует медиа из MediaStore.
 * @param lastDays 0 = всё, N = только файлы за последние N дней
 */
fun scanMediaFiles(context: Context, lastDays: Int = 0): List<MediaFile> {
    val result = mutableListOf<MediaFile>()

    val cutoffSecs: Long = if (lastDays > 0) {
        System.currentTimeMillis() / 1000 - lastDays * 86400L
    } else 0L

    result += queryCollection(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, cutoffSecs)
    result += queryCollection(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, cutoffSecs)

    result.sortBy { it.timestamp }

    val videos = result.count { it.isVideo }
    val photos = result.count { !it.isVideo }
    Log.d(TAG, "found ${result.size} media files ($videos videos, $photos photos)" +
        if (lastDays > 0) " [last $lastDays days]" else "")

    return result
}

private fun queryCollection(
    context:    Context,
    collection: Uri,
    isVideo:    Boolean,
    cutoffSecs: Long,
): List<MediaFile> {
    val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.MIME_TYPE,
    )

    val selection = if (cutoffSecs > 0) "${MediaStore.MediaColumns.DATE_ADDED} >= ?" else null
    val selectionArgs = if (cutoffSecs > 0) arrayOf(cutoffSecs.toString()) else null

    val files = mutableListOf<MediaFile>()
    val allowedMimes = if (isVideo) VIDEO_MIMES else PHOTO_MIMES

    context.contentResolver.query(
        collection, projection, selection, selectionArgs,
        "${MediaStore.MediaColumns.DATE_ADDED} ASC"
    )?.use { cursor ->
        val idCol    = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val nameCol  = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val sizeCol  = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val takenCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
        val addedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val mimeCol  = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)

        while (cursor.moveToNext()) {
            val size = cursor.getLong(sizeCol)
            if (size <= 0) continue

            val mime = if (mimeCol >= 0) cursor.getString(mimeCol)?.lowercase() ?: "" else ""

            if (mime.isNotEmpty() && mime !in allowedMimes) continue

            val id   = cursor.getLong(idCol)
            val name = cursor.getString(nameCol) ?: "file_$id"

            val takenMs   = if (takenCol >= 0) cursor.getLong(takenCol) else 0L
            val timestamp = if (takenMs > 0) takenMs / 1000 else cursor.getLong(addedCol)

            val uri = ContentUris.withAppendedId(collection, id)

            // Map MIME to file type
            val fileType = MIME_TO_FILE_TYPE[mime] ?: if (isVideo) FILE_TYPE_VIDEO else FILE_TYPE_PHOTO

            files += MediaFile(uri, name, size, timestamp, isVideo, mime, fileType)
        }
    }

    return files
}
