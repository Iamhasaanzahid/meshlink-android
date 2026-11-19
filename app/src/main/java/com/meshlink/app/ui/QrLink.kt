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

    private const val API_BASE = "https://meshlink-app.pages.dev"

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

    /** Phone-side manual pairing: the phone shows this 8-digit code. */
    suspend fun createManual(meshId: String, displayName: String): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("meshId", meshId)
                    .put("name", displayName)
                    .toString()
                JSONObject(postJson("$API_BASE/api/pair/manual", body))
            }
        }

    /** Web-side claim progress; the phone approves once `claimed` is true. */
    suspend fun manualStatus(session: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("$API_BASE/api/pair/status?session=$session")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            val code = conn.responseCode
            val text = conn.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) throw IllegalStateException("Pairing expired")
            JSONObject(text)
        }
    }

    suspend fun approveManual(
        session: String,
        token: String,
        meshId: String,
        displayName: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("session", session)
                .put("token", token)
                .put("meshId", meshId)
                .put("name", displayName)
                .toString()
            postJson("$API_BASE/api/pair/approve", body)
        }
    }

    private fun postJson(url: String, body: String): String {
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
        if (code !in 200..299) throw IllegalStateException("Pairing expired or already used")
        return text
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
