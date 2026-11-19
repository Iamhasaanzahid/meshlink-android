package com.meshlink.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val VERIFY_BASE_URL = "https://meshlink-app.pages.dev"
private const val TIMEOUT_MS = 15_000

/** Results returned by the email verification endpoints. */
sealed class VerifyResult {
    /** The server accepted the request and (when demo mode is on) sent code 123456. */
    data class CodeSent(val demo: Boolean) : VerifyResult()

    /** The code was confirmed by the server. */
    data class Verified(val email: String) : VerifyResult()

    /** Current verification state from the server. */
    data class Status(val verified: Boolean, val email: String?) : VerifyResult()

    /** [network] is true when the server could not be reached at all. */
    data class Error(val message: String, val network: Boolean = false) : VerifyResult()
}

/**
 * Thin HTTP client for the MeshLink email verification API. All calls happen on
 * [Dispatchers.IO] so they are safe to invoke from a Compose coroutine scope.
 */
object VerificationClient {

    suspend fun sendCode(email: String, meshId: String): VerifyResult = post(
        path = "/api/verify/send",
        body = JSONObject()
            .put("email", email.trim())
            .put("meshId", meshId),
    ) { json ->
        VerifyResult.CodeSent(json.optBoolean("demo", false))
    }

    suspend fun confirm(email: String, meshId: String, code: String): VerifyResult = post(
        path = "/api/verify/confirm",
        body = JSONObject()
            .put("email", email.trim())
            .put("meshId", meshId)
            .put("code", code.trim()),
    ) { json ->
        if (json.optBoolean("verified", false)) {
            val confirmedEmail = json.optString("email", "").ifBlank { email.trim() }
            VerifyResult.Verified(confirmedEmail)
        } else {
            VerifyResult.Error("That code was not accepted. Check it and try again.")
        }
    }

    suspend fun status(meshId: String): VerifyResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val query = URLEncoder.encode(meshId, "UTF-8")
            connection = URL("$VERIFY_BASE_URL/api/verify/status?meshId=$query").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")

            val code = connection.responseCode
            val text = bodyOf(connection, code)
            if (code !in 200..299) {
                VerifyResult.Error(errorMessage(text, code))
            } else {
                val json = JSONObject(text)
                VerifyResult.Status(
                    verified = json.optBoolean("verified", false),
                    email = json.optString("email", "").ifBlank { null },
                )
            }
        } catch (e: IOException) {
            VerifyResult.Error(
                "Could not reach the verification server. Check your connection and try again.",
                network = true,
            )
        } catch (e: Exception) {
            VerifyResult.Error("The verification server returned an unexpected response.")
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun post(
        path: String,
        body: JSONObject,
        parse: (JSONObject) -> VerifyResult,
    ): VerifyResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(VERIFY_BASE_URL + path).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            connection.outputStream.use { out ->
                out.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val text = bodyOf(connection, code)
            if (code !in 200..299) {
                VerifyResult.Error(errorMessage(text, code))
            } else {
                parse(JSONObject(text))
            }
        } catch (e: IOException) {
            VerifyResult.Error(
                "Could not reach the verification server. Check your connection and try again.",
                network = true,
            )
        } catch (e: Exception) {
            VerifyResult.Error("The verification server returned an unexpected response.")
        } finally {
            connection?.disconnect()
        }
    }

    private fun bodyOf(connection: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    private fun errorMessage(body: String, code: Int): String {
        val fromJson = runCatching {
            val json = JSONObject(body)
            json.optString("error", "").ifBlank { json.optString("message", "") }
        }.getOrDefault("")
        return fromJson.ifBlank { "Verification server error (HTTP $code)." }
    }
}
