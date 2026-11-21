package com.meshlink.app.util

import android.util.Base64
import com.meshlink.app.mesh.OnlineCrypto

/**
 * Defensive text cleanup for chat bubbles and previews.
 *
 * Transport-layer decryption happens before a frame reaches the repository,
 * but older builds and the web bridge can occasionally leave an encrypted
 * Base64 payload in the stored body. Never dump that blob into a bubble: try
 * one local decrypt with the pair key, otherwise show a short, honest fallback.
 */
object MessageText {

    private const val FALLBACK = "🔒 Encrypted message — cannot be shown on this device."

    /** Returns text that is always safe and readable to place in the UI. */
    fun display(body: String, peer: String, myUin: String): String {
        if (body.isBlank()) return ""

        var prefix = ""
        var raw = body.trim()
        if (raw.startsWith("MLG1|")) {
            val parts = raw.split("|", limit = 5)
            if (parts.size == 5) {
                prefix = "${parts[3].take(40)}: "
                raw = parts[4]
            }
        }

        if (!looksLikeCiphertext(raw)) return prefix + raw

        val plain = tryDecrypt(raw, peer, myUin)
        return prefix + if (plain != null && printable(plain)) plain else FALLBACK
    }

    /** Single-line version for the chat list preview. */
    fun preview(body: String, peer: String, myUin: String): String =
        display(body, peer, myUin).replace('\n', ' ').take(140)

    private fun looksLikeCiphertext(text: String): Boolean {
        if (text.length < 16 || text.length > 4000) return false
        if (text.any { it.isWhitespace() }) return false
        if (!BASE64.matches(text)) return false
        // Long random blobs are obviously ciphertext. Shorter blobs need a
        // little more proof so normal words / 10-digit UINs are never hidden.
        if (text.length >= 24) return true
        val mixedCase = text.any { it.isUpperCase() } && text.any { it.isLowerCase() }
        val cryptoMarkers = text.any { it.isDigit() } || text.contains('=') ||
            text.contains('+') || text.contains('/')
        return mixedCase && cryptoMarkers
    }

    private val BASE64 = Regex("^[A-Za-z0-9+/=_-]+$")

    private fun tryDecrypt(text: String, peer: String, myUin: String): String? {
        if (!peer.isMeshLinkId() || !myUin.isMeshLinkId()) return null
        val blob = runCatching { Base64.decode(text, Base64.NO_WRAP) }.getOrNull()
            ?: runCatching { Base64.decode(text, Base64.URL_SAFE or Base64.NO_WRAP) }.getOrNull()
            ?: return null
        val plain = runCatching {
            OnlineCrypto.decrypt(OnlineCrypto.pairKey(myUin, peer), blob)
        }.getOrNull() ?: return null
        return runCatching { String(plain, Charsets.UTF_8) }.getOrNull()
    }

    private fun printable(text: String): Boolean =
        text.isNotBlank() && text.any { !it.isWhitespace() } &&
            text.none { it.isISOControl() || it == '\uFFFD' }
}
