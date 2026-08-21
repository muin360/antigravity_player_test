package com.tensorix.antigravityplayer.data.remote

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lightweight native HTTP API client for YouTube Backend Service
 * Supports dynamic host discovery, search, stream, and scoped-storage download-to-device operations across all Android versions.
 */
class YtApiService(private val context: Context? = null) {

    private fun getBaseUrl(): String {
        context?.let { ctx ->
            val prefs = ctx.getSharedPreferences("yt_config", Context.MODE_PRIVATE)
            val customUrl = prefs.getString("server_url", null)
            if (!customUrl.isNullOrBlank()) {
                return customUrl.trimEnd('/')
            }
        }
        return "http://10.0.2.2:3000"
    }

    suspend fun searchTracks(query: String): List<YtSearchResultItem> = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val baseUrl = getBaseUrl()
        val urlString = "$baseUrl/api/search?q=$encodedQuery"
        val jsonText = httpGetWithFallback(urlString, "/api/search?q=$encodedQuery") ?: return@withContext emptyList()

        try {
            val root = JSONObject(jsonText)
            val resultsArray = root.optJSONArray("results") ?: return@withContext emptyList()
            val list = mutableListOf<YtSearchResultItem>()
            for (i in 0 until resultsArray.length()) {
                val item = resultsArray.getJSONObject(i)
                list.add(
                    YtSearchResultItem(
                        id = item.optString("id"),
                        title = item.optString("title", "Unknown Track"),
                        artist = item.optString("artist", "YouTube"),
                        durationSeconds = item.optLong("duration", 0L),
                        thumbnailUrl = item.optString("thumbnail", "")
                    )
                )
            }
            list
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun getStreamUrl(id: String): YtStreamResponse? = withContext(Dispatchers.IO) {
        val baseUrl = getBaseUrl()
        val urlString = "$baseUrl/api/stream?id=$id"
        val jsonText = httpGetWithFallback(urlString, "/api/stream?id=$id") ?: return@withContext null

        try {
            val root = JSONObject(jsonText)
            YtStreamResponse(
                id = root.optString("id", id),
                streamUrl = root.optString("streamUrl", ""),
                title = root.optString("title", "Online Track"),
                artist = root.optString("artist", "YouTube"),
                durationSeconds = root.optLong("duration", 0L),
                thumbnailUrl = root.optString("thumbnail", "")
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Downloads a YouTube track audio file to device storage.
     * Uses dynamic multi-directory fallback (Public Music -> External Files -> Internal Files)
     * for 100% compatibility across all Android OS versions (API 26-34+).
     * Performs automatic cleanup of partial downloads on failure.
     */
    suspend fun downloadTrackToDevice(
        context: Context,
        streamResponse: YtStreamResponse,
        onProgress: (Int) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        var outputFile: File? = null
        var connection: HttpURLConnection? = null
        try {
            val streamUrl = streamResponse.streamUrl
            if (streamUrl.isBlank()) return@withContext null

            val targetDir = runCatching {
                val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                val dir = File(musicDir, "AntigravityPlayer")
                if (!dir.exists()) {
                    val created = dir.mkdirs()
                    if (!created && !dir.exists()) null else dir
                } else dir
            }.getOrNull()
                ?: context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: File(context.filesDir, "Music").also { if (!it.exists()) it.mkdirs() }

            val safeTitle = streamResponse.title
                .replace(Regex("[^\\p{L}\\p{Nd}\\s\\-_]"), "")
                .trim()
                .replace(Regex("\\s+"), "_")
                .take(60)
                .ifBlank { "Track_${streamResponse.id}" }
            val fileName = "${safeTitle}_${streamResponse.id}.m4a"
            outputFile = File(targetDir, fileName)

            if (outputFile.exists() && outputFile.length() > 0) {
                onProgress(100)
                return@withContext outputFile.absolutePath
            }

            val url = URL(streamUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.requestMethod = "GET"

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                outputFile.delete()
                return@withContext null
            }

            val totalBytes = connection.contentLength.toLong()
            var downloadedBytes = 0L

            connection.inputStream.use { inputStream ->
                FileOutputStream(outputFile).use { fos ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        fos.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            onProgress(((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100))
                        }
                    }
                    fos.flush()
                }
            }

            onProgress(100)
            return@withContext outputFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            outputFile?.let { if (it.exists()) it.delete() }
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun httpGetWithFallback(primaryUrl: String, path: String): String? {
        val urlsToTry = mutableListOf(primaryUrl)
        if (!primaryUrl.contains("localhost") && !primaryUrl.contains("127.0.0.1")) {
            urlsToTry.add("http://localhost:3000$path")
            urlsToTry.add("http://127.0.0.1:3000$path")
        }

        for (u in urlsToTry) {
            val response = httpGet(u)
            if (response != null) return response
        }
        return null
    }

    private fun httpGet(urlString: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
