package com.meshlink.app.mesh

import com.meshlink.app.util.isMeshLinkId
import java.security.MessageDigest

/**
 * "Bridge" envelope: a compact, relay-friendly wrapper that lets a phone with
 * internet carry a mesh message across the country, and lets a phone with
 * internet push an online message back onto the local radios.
 *
 * Payload layout (inside a [MeshFrame.TYPE_BRIDGE] frame):
 *
 *   [0..9]   destination UIN (10 chars, e.g. OFF-RKEX7H)
 *   [10..13] first 4 bytes of SHA-256(message id)
 *   [14..15] part number (big endian)
 *   [16..17] part count (big endian)
 *   [18..]   part of the normal message JSON
 *
 * A relay never needs to decrypt: it copies the frame to the relay topic of
 * the destination UIN. The destination reassembles the JSON and shows the
 * message with the original sender from the JSON "u" field.
 */
object BridgeFrames {

    const val DEST_SIZE = 10
    const val ID_SIZE = 4
    const val HEADER = DEST_SIZE + ID_SIZE + 4
    const val MAX_DATA = MeshFrame.MAX_PAYLOAD - HEADER
    const val MAX_PARTS = 64

    data class Header(
        val destination: String,
        val idHash: String,
        val sequence: Int,
        val total: Int,
    )

    fun parse(payload: ByteArray): Header? {
        if (payload.size < HEADER) return null
        val dest = String(payload, 0, DEST_SIZE, Charsets.UTF_8).trim().uppercase()
        if (dest.length != DEST_SIZE || !dest.isMeshLinkId()) return null
        val idHash = (0 until ID_SIZE).joinToString("") { "%02x".format(payload[10 + it]) }
        val seq = ((payload[14].toInt() and 0xFF) shl 8) or (payload[15].toInt() and 0xFF)
        val total = ((payload[16].toInt() and 0xFF) shl 8) or (payload[17].toInt() and 0xFF)
        if (total <= 0 || total > MAX_PARTS || seq >= total) return null
        return Header(dest, idHash, seq, total)
    }

    /** Splits [json] into bridge payloads, or returns an empty list. */
    fun payloads(destination: String, messageId: String, json: ByteArray): List<ByteArray> {
        val dest = destination.trim().uppercase()
        if (dest.length != DEST_SIZE || !dest.isMeshLinkId()) return emptyList()
        val idHash = MessageDigest.getInstance("SHA-256")
            .digest(messageId.toByteArray(Charsets.UTF_8))
            .copyOfRange(0, ID_SIZE)
        val total = maxOf(1, (json.size + MAX_DATA - 1) / MAX_DATA)
        if (total > MAX_PARTS) return emptyList()
        val destBytes = dest.toByteArray(Charsets.UTF_8)
        return (0 until total).map { seq ->
            val start = seq * MAX_DATA
            val end = minOf(start + MAX_DATA, json.size)
            val out = ByteArray(HEADER + (end - start))
            destBytes.copyInto(out, 0)
            idHash.copyInto(out, DEST_SIZE)
            out[14] = ((seq shr 8) and 0xFF).toByte()
            out[15] = (seq and 0xFF).toByte()
            out[16] = ((total shr 8) and 0xFF).toByte()
            out[17] = (total and 0xFF).toByte()
            json.copyInto(out, HEADER, start, end)
            out
        }
    }
}
