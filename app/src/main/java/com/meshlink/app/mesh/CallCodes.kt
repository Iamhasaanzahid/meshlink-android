package com.meshlink.app.mesh

/**
 * The text codes exchanged for an online call.
 *
 * Wire format: `MLCALL1:` followed by URL-safe Base64 of
 * `type '\0' senderName '\0' sdp`.
 *
 * Pure Kotlin (no Android classes) so the encoding can be unit tested:
 * a broken code means two strangers cannot connect, which is worth testing.
 */
object CallCodes {

    const val PREFIX = "MLCALL1:"

    private const val SEPARATOR = '\u0000'
    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    data class Decoded(val type: String, val sdp: String, val name: String)

    fun encode(type: String, sdp: String, name: String): String =
        PREFIX + base64UrlEncode("$type$SEPARATOR$name$SEPARATOR$sdp".toByteArray(Charsets.UTF_8))

    /**
     * Accepts the code with or without the prefix and ignores spaces and line
     * breaks that chat apps insert when wrapping long text.
     */
    fun decode(raw: String): Decoded? {
        var text = raw.trim()
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")
        if (text.startsWith(PREFIX)) text = text.removePrefix(PREFIX)
        if (text.isEmpty()) return null

        val bytes = base64UrlDecode(text) ?: return null
        val parts = String(bytes, Charsets.UTF_8).split(SEPARATOR)
        if (parts.size != 3) return null
        val type = parts[0]
        val name = parts[1]
        val sdp = parts[2]
        if (type != "offer" && type != "answer") return null
        if (sdp.isBlank()) return null
        return Decoded(type, sdp, name)
    }

    // ---------------------------------------------------------------- base64

    private fun base64UrlEncode(data: ByteArray): String {
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else -1

            out.append(ALPHABET[b0 shr 2])
            out.append(ALPHABET[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            if (b1 >= 0) {
                out.append(ALPHABET[((b1 and 0x0F) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)])
            }
            if (b2 >= 0) {
                out.append(ALPHABET[b2 and 0x3F])
            }
            i += 3
        }
        return out.toString()
    }

    private fun base64UrlDecode(text: String): ByteArray? {
        if (text.isEmpty()) return null
        val out = ByteArray(text.length * 3 / 4 + 3)
        var outLen = 0
        var buffer = 0
        var bits = 0
        for (ch in text) {
            val value = ALPHABET.indexOf(ch)
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[outLen++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return out.copyOf(outLen)
    }
}
