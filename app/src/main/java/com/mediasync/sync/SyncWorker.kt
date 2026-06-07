package com.mediasync.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import com.mediasync.data.SyncDatabase
import com.mediasync.data.SyncedFile
import com.mediasync.protocol.*
import java.util.concurrent.TimeUnit

private const val TAG = "SyncWorker"

const val KEY_HOST     = "server_host"
const val KEY_PORT     = "server_port"
const val KEY_USERNAME = "username"
const val KEY_PASSWORD = "password"
const val KEY_DAYS     = "scan_days"

// Keys for progress LiveData
const val PROGRESS_CURRENT  = "progress_current"
const val PROGRESS_TOTAL    = "progress_total"
const val PROGRESS_FILENAME = "progress_filename"

class SyncWorker(
    context: Context,
    params:  WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val host     = inputData.getString(KEY_HOST)     ?: return Result.failure(workDataOf("error" to "no host"))
        val port     = inputData.getInt(KEY_PORT, 9876)
        val username = inputData.getString(KEY_USERNAME) ?: "default"
        val password = inputData.getString(KEY_PASSWORD) ?: ""
        val scanDays = inputData.getInt(KEY_DAYS, 7)

        val db  = SyncDatabase.getInstance(applicationContext)
        val dao = db.syncedFileDao()

        Log.i(TAG, "sync started → $host:$port user=$username scanDays=$scanDays")

        val allFiles = scanMediaFiles(applicationContext, scanDays)
        if (allFiles.isEmpty()) {
            Log.i(TAG, "no new media files found")
            return Result.success(workDataOf("synced" to 0, "skipped" to 0, "errors" to 0))
        }

        // Filter out already-synced files by URI
        val toProcess = mutableListOf<MediaFile>()
        var localSkipped = 0

        for (media in allFiles) {
            if (dao.existsByUri(media.uri.toString())) {
                localSkipped++
            } else {
                toProcess.add(media)
            }
        }

        Log.i(TAG, "to send: ${toProcess.size}, already synced (local): $localSkipped")

        var synced  = 0
        var skipped = 0
        var errors  = 0
        val errorDetails = mutableListOf<String>()

        toProcess.forEachIndexed { index, media ->
            setProgress(workDataOf(
                PROGRESS_CURRENT  to index + 1,
                PROGRESS_TOTAL    to toProcess.size,
                PROGRESS_FILENAME to media.name,
            ))

            try {
                // Pass 1: compute SHA-256 by streaming (no full file in memory)
                val sha256 = computeFileHash(
                    applicationContext, media.uri, media.size
                ) ?: run {
                    errors++
                    errorDetails += "${media.name}: не удалось прочитать файл"
                    Log.e(TAG, "✗ ${media.name}: cannot read from ContentResolver")
                    return@forEachIndexed
                }

                if (dao.exists(sha256)) {
                    dao.insertUri(media.uri.toString(), sha256)
                    skipped++
                    Log.d(TAG, "~ ${media.name} (hash known)")
                    return@forEachIndexed
                }

                // Pass 2: stream file to server (no full file in memory)
                val result = sendFileStream(
                    host      = host,
                    port      = port,
                    username  = username,
                    password  = password,
                    fileType  = media.fileType,
                    fileSize  = media.size,
                    timestamp = media.timestamp,
                    fileName  = media.name,
                    sha256hex = sha256,
                    context   = applicationContext,
                    uri       = media.uri,
                    onProgress = { sent, total ->
                        val pct = if (total > 0) sent * 100 / total else 0
                        if (pct % 10 == 0L) Log.v(TAG, "  ${media.name}: $pct%")
                    }
                )

                when (result) {
                    is SendResult.Ok -> {
                        dao.insert(SyncedFile(
                            sha256       = sha256,
                            uri          = media.uri.toString(),
                            originalName = media.name,
                            shotAt       = media.timestamp,
                            fileType     = if (media.isVideo) "video" else "photo",
                        ))
                        synced++
                        Log.i(TAG, "✓ [${index+1}/${toProcess.size}] ${media.name} (${humanSize(media.size)})")
                    }
                    is SendResult.Skip -> {
                        dao.insert(SyncedFile(
                            sha256       = sha256,
                            uri          = media.uri.toString(),
                            originalName = media.name,
                            shotAt       = media.timestamp,
                            fileType     = if (media.isVideo) "video" else "photo",
                        ))
                        skipped++
                        Log.i(TAG, "~ ${media.name} (server skip)")
                    }
                    is SendResult.Error -> {
                        errors++
                        errorDetails += "${media.name}: ${result.message}"
                        Log.e(TAG, "✗ [${index+1}/${toProcess.size}] ${media.name}: ${result.message}")
                    }
                }

            } catch (e: AuthException) {
                errors++
                errorDetails += "auth failed: ${e.message}"
                Log.e(TAG, "✗ auth failed: ${e.message}")
                // Stop syncing on auth failure
                return Result.failure(workDataOf(
                    "error" to "auth failed: ${e.message}",
                    "synced" to synced,
                    "skipped" to skipped + localSkipped,
                    "errors" to errors,
                ))
            } catch (e: Exception) {
                errors++
                errorDetails += "${media.name}: ${e.message}"
                Log.e(TAG, "✗ exception on ${media.name}: ${e.message}")
            }
        }

        val summary = "отправлено=$synced пропущено=${skipped+localSkipped} ошибок=$errors"
        Log.i(TAG, "sync done: $summary")
        if (errorDetails.isNotEmpty()) {
            Log.w(TAG, "Errors detail:\n${errorDetails.joinToString("\n")}")
        }

        return Result.success(workDataOf(
            "synced"       to synced,
            "skipped"      to skipped + localSkipped,
            "errors"       to errors,
            "error_detail" to errorDetails.take(10).joinToString("\n"),
        ))
    }

    companion object {
        private const val WORK_NAME_PERIODIC  = "mediasync_periodic"
        private const val WORK_NAME_IMMEDIATE = "mediasync_now"

        fun schedulePeriodicSync(context: Context, host: String, port: Int, username: String, password: String) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setInputData(workDataOf(
                    KEY_HOST to host, KEY_PORT to port,
                    KEY_USERNAME to username, KEY_PASSWORD to password,
                    KEY_DAYS to 7,
                ))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request,
            )
        }

        fun syncNow(context: Context, host: String, port: Int, username: String, password: String, days: Int = 0): androidx.work.Operation {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(
                    KEY_HOST to host, KEY_PORT to port,
                    KEY_USERNAME to username, KEY_PASSWORD to password,
                    KEY_DAYS to days,
                ))
                .build()

            return WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_IMMEDIATE, ExistingWorkPolicy.REPLACE, request,
            )
        }
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576     -> "%.1f MB".format(bytes / 1_048_576.0)
    else                   -> "${bytes / 1024} KB"
}
