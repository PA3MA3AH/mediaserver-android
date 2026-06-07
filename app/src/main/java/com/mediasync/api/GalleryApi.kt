package com.mediasync.api

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "GalleryApi"

class GalleryApi(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val username: String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val baseUrl: String
        get() = "http://$host:$port"

    private fun authHeader() = "Bearer $token"

    /**
     * GET /api/gallery/{username}?page=N&limit=N
     */
    fun fetchGallery(page: Int = 0, limit: Int = 50): GalleryResponse? {
        return try {
            val url = "$baseUrl/api/gallery/$username?page=$page&limit=$limit"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", authHeader())
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    android.util.Log.w(TAG, "fetchGallery failed: ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                android.util.Log.d(TAG, "fetched ${body.length} bytes")
                com.google.gson.Gson().fromJson(body, GalleryResponse::class.java)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "fetchGallery error: ${e.message}")
            null
        }
    }

    /**
     * GET /api/gallery/{username}/storage
     */
    fun fetchStorage(): StorageResponse? {
        return try {
            val url = "$baseUrl/api/gallery/$username/storage"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", authHeader())
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                com.google.gson.Gson().fromJson(body, StorageResponse::class.java)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "fetchStorage error: ${e.message}")
            null
        }
    }

    /**
     * GET /api/gallery/{username}/download/{sha256}
     * Downloads file to the given destination.
     */
    fun downloadFile(sha256: String, destFile: File): DownloadResult {
        return try {
            val url = "$baseUrl/api/gallery/$username/download/$sha256"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", authHeader())
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return DownloadResult.Error("HTTP ${response.code}")
                }
                response.body?.use { body ->
                    destFile.outputStream().use { out ->
                        body.byteStream().copyTo(out)
                    }
                    DownloadResult.Success(destFile)
                } ?: DownloadResult.Error("Empty response body")
            }
        } catch (e: Exception) {
            DownloadResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Get thumbnail URL for a file (uses the same download endpoint).
     * The client can load the image directly from the URL.
     */
    fun getThumbnailUrl(sha256: String): String {
        return "$baseUrl/api/gallery/$username/download/$sha256"
    }

    fun getAuthHeaderForCoil(): String = authHeader()
}

// ── Data classes ──────────────────────────────────────────────────────────

data class GalleryResponse(
    val total: Int,
    val page: Int,
    val limit: Int,
    val files: List<GalleryItem>,
)

data class GalleryItem(
    val sha256: String,
    val original_name: String,
    val file_type: String,
    val shot_at: Long,
    val received_at: Long,
    val download_url: String,
)

data class StorageResponse(
    val used_bytes: Long,
    val used_human: String,
    val file_count: Long,
)

sealed class DownloadResult {
    data class Success(val file: File) : DownloadResult()
    data class Error(val message: String) : DownloadResult()
}
