package com.meshlink.app.ui

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "MeshLinkQrLink"

/**
 * Handles a `meshlink://pair` QR scanned from the web app and approves the
 * short-lived session on the MeshLink server. The browser polls the same
 * session and signs in as this phone.
 */
object QrLink {

    data class Request(val host: String, val session: String, val token: String) {
        /** Only allow the app's own demo server or an HTTPS MeshLink host. */
        val approveUrl: String
            get() {
                val uri = Uri.parse(host)
                val scheme = uri.scheme ?: "https"
                val safe = when {
                    scheme == "https" -> true
                    scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "10.0.2.2") -> true
                    else -> false
                }
                require(safe) { "Unsafe QR host" }
                return "$scheme://${uri.authority}/api/pair/approve"
            }
    }

    fun parse(content: String): Request? {
        val uri = runCatching { Uri.parse(content) }.getOrNull() ?: return null
        if (uri.scheme != "meshlink" || uri.host != "pair") return null
        val session = uri.getQueryParameter("s") ?: return null
        val token = uri.getQueryParameter("t") ?: return null
        val host = uri.getQueryParameter("h") ?: return null
        if (session.isBlank() || token.isBlank() || host.isBlank()) return null
        return Request(host, session, token)
    }

    suspend fun approve(request: Request, meshId: String, displayName: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = request.approveUrl
                val body = JSONObject()
                    .put("session", request.session)
                    .put("token", request.token)
                    .put("meshId", meshId)
                    .put("name", displayName)
                    .toString()
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { out: OutputStream -> out.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val text = conn.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                conn.disconnect()
                if (code !in 200..299) {
                    Log.w(TAG, "approve failed: HTTP $code $text")
                    throw IllegalStateException("Link expired or already used")
                }
                text
            }.onFailure { Log.w(TAG, "approve error", it) }
        }
}
