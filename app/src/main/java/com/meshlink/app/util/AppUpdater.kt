package com.meshlink.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdate(
    val version: String,
    val notes: String,
    val apkUrl: String,
)

object AppUpdater {
    private const val LATEST_URL =
        "https://api.github.com/repos/Iamhasaanzahid/meshlink-android/releases/latest"

    /** Returns the latest GitHub release, or null if it could not be fetched. */
    suspend fun check(): AppUpdate? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(LATEST_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "MeshLink-App-Updater")
            if (connection.responseCode !in 200..299) return@withContext null
            val body = connection.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (body.isBlank()) return@withContext null
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "").trim().removePrefix("v")
            if (tag.isBlank()) return@withContext null
            val notes = json.optString("body", "").trim()
            val assets = json.optJSONArray("assets") ?: return@withContext null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url", "")
                    break
                }
            }
            if (apkUrl.isNullOrBlank()) return@withContext null
            AppUpdate(tag, notes, apkUrl)
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** True when [latest] is strictly newer than [current]. */
    fun isNewer(current: String, latest: String): Boolean {
        val a = current.split(".").mapNotNull { it.toIntOrNull() }
        val b = latest.split(".").mapNotNull { it.toIntOrNull() }
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (y != x) return y > x
        }
        return false
    }

    /** Downloads the APK to the app cache dir, reporting 0..1 progress. */
    suspend fun download(
        context: Context,
        url: String,
        onProgress: (Float) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "MeshLink-App-Updater")
            if (connection.responseCode !in 200..299) return@withContext null
            val total = connection.contentLengthLong
            val file = File(context.cacheDir, "MeshLink-update.apk")
            connection.inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) {
                            val progress = (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            withContext(Dispatchers.Main) { onProgress(progress) }
                        }
                    }
                    output.flush()
                }
            }
            file
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    fun canInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun install(context: Context, file: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri: Uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fall back to opening settings if the install intent cannot start.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val settings = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settings)
            }
        }
    }
}