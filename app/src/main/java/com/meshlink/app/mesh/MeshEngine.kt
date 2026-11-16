package com.meshlink.app.mesh

import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.util.Log
import org.json.JSONObject
import java.security.MessageDigest

enum class TransportType {
    /** Cellular radio via SMS — works with only a SIM, no internet. */
    RADIO_SMS,

    /** Bluetooth LE — local 2.4 GHz radio, no SIM and no internet. */
    BLUETOOTH_LE,

    /** Wi-Fi Direct — phone-to-phone Wi-Fi, no router and no internet. */
    WIFI_DIRECT,

    /** Try every available radio. */
    AUTO,
}

/**
 * MeshEngine — orchestrates all three radios and performs multi-hop routing.
 *
 * Everything here is offline: Wi-Fi Direct, Bluetooth LE and the cellular
 * radio (SMS). There is no server, no account, no internet requirement.
 *
 * Incoming frames are deduplicated, and when they are not addressed to this
 * node they are re-broadcast with a decremented TTL (mesh relay). This is what
 * lets a message travel phone → phone → phone when no tower or hotspot exists.
 */
class MeshEngine(private val context: Context) {

    companion object {
        private const val TAG = "MeshLinkMesh"
        private const val DEDUPE_CACHE = 512

        /** [4-byte message hash][seq][total][data] */
        private const val SEG_HEADER = 6
        private const val SEG_DATA = MeshFrame.MAX_PAYLOAD - SEG_HEADER
        private const val SEG_MAX_PARTS = 255
        private const val SEG_TIMEOUT_MS = 2 * 60 * 1000L
    }

    /** Our mesh node id, derived from our UIN. */
    @Volatile
    var localNodeId: Int = 0
        private set

    /** Every node id that addresses this device (UIN, and 0 = broadcast). */
    @Volatile
    private var localNodeIds: Set<Int> = emptySet()

    /** A complete text message arrived and is addressed to us. */
    var onMessageForMe:
        ((from: String, messageId: String, body: String, senderName: String, transport: TransportType) -> Unit)? =
        null

    /** The receiving phone confirmed delivery of [messageId]. */
    var onAckFor: ((messageId: String) -> Unit)? = null

    /** Call signalling: action = invite / accept / reject / end. */
    var onControl: ((from: String, action: String, callId: String, name: String) -> Unit)? = null

    private val ble = BleTransport(context)
    private val wifi = WifiDirectTransport(context)

    private val seen = LinkedHashMap<String, Long>()
    private val seenLock = Any()

    private val segments = HashMap<String, SegBuffer>()
    private val segmentLock = Any()

    private class SegBuffer(val total: Int) {
        val parts = arrayOfNulls<ByteArray>(total)
        var received = 0
        var updatedAt = System.currentTimeMillis()

        fun put(index: Int, data: ByteArray): Boolean {
            if (index < 0 || index >= total) return false
            if (parts[index] == null) {
                parts[index] = data
                received++
            }
            updatedAt = System.currentTimeMillis()
            return received == total
        }

        fun join(): ByteArray {
            var size = 0
            parts.forEach { size += it?.size ?: 0 }
            val out = ByteArray(size)
            var pos = 0
            parts.forEach { part ->
                if (part != null) {
                    part.copyInto(out, pos)
                    pos += part.size
                }
            }
            return out
        }
    }

    var bleActive: Boolean = false
        private set
    var wifiActive: Boolean = false
        private set

    // ------------------------------------------------------------------ setup

    fun configure(localUin: String) {
        localNodeId = MeshFrame.nodeId16(localUin)
        localNodeIds = setOf(localNodeId)
    }

    fun start() {
        RadioTransport.onFrame = { from, bytes -> handleIncoming(from, TransportType.RADIO_SMS, bytes) }
        RadioTransport.onText = { from, text ->
            onMessageForMe?.invoke(from, newId(text.toByteArray()), text, "", TransportType.RADIO_SMS)
        }

        if (ble.isSupported) {
            runCatching { ble.start() }
                .onSuccess { bleActive = true }
                .onFailure { Log.w(TAG, "BLE start failed", it) }
        }
        ble.onFrame = { addr, bytes -> handleIncoming(addr, TransportType.BLUETOOTH_LE, bytes) }

        if (wifi.isSupported) {
            runCatching { wifi.start() }
                .onSuccess { wifiActive = true }
                .onFailure { Log.w(TAG, "Wi-Fi Direct start failed", it) }
        }
        wifi.onFrame = { addr, bytes -> handleIncoming(addr, TransportType.WIFI_DIRECT, bytes) }

        Log.i(TAG, "Mesh started (BLE=$bleActive, Wi-FiDirect=$wifiActive, SMS=true)")
    }

    fun stop() {
        ble.stop()
        wifi.stop()
        bleActive = false
        wifiActive = false
    }

    // ---------------------------------------------------------------- sending

    /**
     * Sends a text message over the radios, in this order:
     *   1. Wi-Fi Direct  (free, no router, no internet)
     *   2. Bluetooth LE  (free, no internet)
     *   3. SMS           (only when the destination is a real phone number)
     *
     * Long messages are split into frames that fit the radio MTU and are put
     * back together on the other phone.
     */
    fun sendMessage(
        destination: String,
        messageId: String,
        body: String,
        senderName: String = "",
    ): Boolean {
        val target = destination.trim()
        if (target.isEmpty() || body.isEmpty()) return false
        val frames = runCatching { buildFrames(target, messageId, body, senderName) }
            .getOrElse {
                Log.w(TAG, "cannot build frames", it)
                return false
            }
        return sendFrames(target, frames)
    }

    /** True when [destination] can be dialled/SMSed over the cellular radio. */
    fun isRadioNumber(destination: String): Boolean =
        destination.trim().matches(Regex("^\\+?[0-9]{7,15}$"))

    fun sendFrame(destination: String, frame: ByteArray, transport: TransportType): Boolean =
        when (transport) {
            TransportType.RADIO_SMS -> RadioTransport.sendFrame(context, destination, frame)
            TransportType.BLUETOOTH_LE -> ble.send(null, frame) > 0
            TransportType.WIFI_DIRECT -> wifi.send(frame)
            TransportType.AUTO -> sendFrames(destination, listOf(frame))
        }

    private fun sendFrames(destination: String, frames: List<ByteArray>): Boolean {
        val numeric = isRadioNumber(destination)

        if (wifiActive) {
            var ok = true
            for (f in frames) ok = wifi.send(f) && ok
            if (ok) return true
        }
        if (bleActive) {
            var ok = true
            for (f in frames) ok = ble.send(null, f) > 0 && ok
            if (ok) return true
        }
        if (numeric && RadioTransport.isAvailable(context)) {
            var ok = true
            for (f in frames) ok = RadioTransport.sendFrame(context, destination, f) && ok
            if (ok) return true
        }
        return false
    }

    private fun buildFrames(
        destination: String,
        messageId: String,
        body: String,
        senderName: String,
    ): List<ByteArray> {
        val json = JSONObject()
            .put("i", messageId)
            .put("b", body)
            .put("n", senderName)
            .put("t", System.currentTimeMillis())
            .toString()
        val payload = json.toByteArray(Charsets.UTF_8)
        val destNode = MeshFrame.nodeId16(destination)

        if (payload.size <= MeshFrame.MAX_PAYLOAD) {
            return listOf(
                MeshFrame.Frame(
                    type = MeshFrame.TYPE_MESSAGE,
                    sourceNodeId = localNodeId,
                    destNodeId = destNode,
                    ttl = MeshFrame.DEFAULT_TTL,
                    payload = payload,
                ).serialize()
            )
        }

        val total = (payload.size + SEG_DATA - 1) / SEG_DATA
        require(total <= SEG_MAX_PARTS) { "message too large" }
        val idHash = MessageDigest.getInstance("SHA-256")
            .digest(messageId.toByteArray(Charsets.UTF_8))
            .copyOfRange(0, 4)

        val frames = ArrayList<ByteArray>(total)
        for (seq in 0 until total) {
            val start = seq * SEG_DATA
            val end = minOf(start + SEG_DATA, payload.size)
            val data = payload.copyOfRange(start, end)
            val buf = ByteArray(SEG_HEADER + data.size)
            idHash.copyInto(buf, 0)
            buf[4] = seq.toByte()
            buf[5] = total.toByte()
            data.copyInto(buf, 6)
            frames += MeshFrame.Frame(
                type = MeshFrame.TYPE_SEGMENT,
                sourceNodeId = localNodeId,
                destNodeId = destNode,
                ttl = MeshFrame.DEFAULT_TTL,
                payload = buf,
            ).serialize()
        }
        return frames
    }

    /** Call signalling frame (invite / accept / reject / end). */
    fun sendControl(destination: String, action: String, callId: String, name: String): Boolean {
        val payload = JSONObject()
            .put("a", action)
            .put("id", callId)
            .put("n", name)
            .toString()
            .toByteArray(Charsets.UTF_8)
            .let { if (it.size > MeshFrame.MAX_PAYLOAD) it.copyOf(MeshFrame.MAX_PAYLOAD) else it }
        val frame = MeshFrame.Frame(
            type = MeshFrame.TYPE_CALL,
            sourceNodeId = localNodeId,
            destNodeId = MeshFrame.nodeId16(destination),
            ttl = MeshFrame.DEFAULT_TTL,
            payload = payload,
        ).serialize()
        return sendFrames(destination, listOf(frame))
    }

    // --------------------------------------------------------------- receiving

    private fun handleIncoming(fromAddress: String, transport: TransportType, bytes: ByteArray) {
        val frame = MeshFrame.deserialize(bytes)

        if (frame == null) {
            // Raw payload (large text from an older build).
            val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return
            if (text.isEmpty()) return
            onMessageForMe?.invoke(fromAddress, newId(bytes), text, "", transport)
            return
        }

        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        synchronized(seenLock) {
            if (seen.containsKey(hash)) return
            seen[hash] = System.currentTimeMillis()
            if (seen.size > DEDUPE_CACHE) {
                val it = seen.entries.iterator()
                if (it.hasNext()) { it.next(); it.remove() }
            }
        }

        if (isForMe(frame, transport)) {
            when (frame.type) {
                MeshFrame.TYPE_MESSAGE -> handlePayload(fromAddress, frame.payload, transport)
                MeshFrame.TYPE_SEGMENT -> handleSegment(fromAddress, frame.payload, transport)
                MeshFrame.TYPE_ACK -> {
                    val id = String(frame.payload, Charsets.UTF_8)
                    if (id.isNotEmpty()) onAckFor?.invoke(id)
                }
                MeshFrame.TYPE_CALL -> handleControl(fromAddress, frame.payload)
                else -> Unit
            }
            return
        }

        // Relay: not ours, forward with a shorter TTL.
        if (frame.ttl > 1) {
            val relayed = MeshFrame.Frame(
                type = frame.type,
                sourceNodeId = frame.sourceNodeId,
                destNodeId = frame.destNodeId,
                ttl = frame.ttl - 1,
                payload = frame.payload,
            ).serialize()
            when (transport) {
                TransportType.BLUETOOTH_LE -> ble.send(null, relayed)
                TransportType.WIFI_DIRECT -> wifi.send(relayed)
                else -> Unit // SMS is point-to-point
            }
        }
    }

    private fun isForMe(frame: MeshFrame.Frame, transport: TransportType): Boolean =
        transport == TransportType.RADIO_SMS ||
            frame.destNodeId == 0 ||
            localNodeIds.contains(frame.destNodeId)

    private fun handleSegment(from: String, payload: ByteArray, transport: TransportType) {
        if (payload.size < SEG_HEADER) return
        val key = MessageDigest.getInstance("SHA-256").digest(payload.copyOfRange(0, 4))
            .joinToString("") { "%02x".format(it) }
        val seq = payload[4].toInt() and 0xFF
        val total = payload[5].toInt() and 0xFF
        if (total <= 0) return
        val data = payload.copyOfRange(SEG_HEADER, payload.size)

        val buffer: SegBuffer
        val complete: Boolean
        synchronized(segmentLock) {
            purgeSegments()
            val b = segments.getOrPut("$from|$key") { SegBuffer(total) }
            buffer = b
            complete = synchronized(b) { b.put(seq, data) }
            if (complete) segments.remove("$from|$key")
        }
        if (complete) handlePayload(from, buffer.join(), transport)
    }

    private fun purgeSegments() {
        val now = System.currentTimeMillis()
        val it = segments.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.updatedAt > SEG_TIMEOUT_MS) it.remove()
        }
    }

    private fun handlePayload(from: String, payload: ByteArray, transport: TransportType) {
        val raw = runCatching { String(payload, Charsets.UTF_8) }.getOrNull() ?: return
        if (raw.isEmpty()) return

        var id: String? = null
        var body = raw
        var name = ""
        runCatching {
            val json = JSONObject(raw)
            id = json.optString("i", "")
            body = json.optString("b", raw)
            name = json.optString("n", "")
        }

        val messageId = id?.takeIf { it.isNotEmpty() } ?: newId(payload)
        if (body.isEmpty()) return

        onMessageForMe?.invoke(from, messageId, body, name, transport)
        // Confirm receipt so the sender sees double ticks.
        sendAck(from, messageId)
    }

    private fun sendAck(destination: String, messageId: String) {
        val payload = messageId.toByteArray(Charsets.UTF_8)
            .let { if (it.size > MeshFrame.MAX_PAYLOAD) it.copyOf(MeshFrame.MAX_PAYLOAD) else it }
        val frame = MeshFrame.Frame(
            type = MeshFrame.TYPE_ACK,
            sourceNodeId = localNodeId,
            destNodeId = MeshFrame.nodeId16(destination),
            ttl = MeshFrame.DEFAULT_TTL,
            payload = payload,
        ).serialize()
        runCatching { sendFrames(destination, listOf(frame)) }
    }

    private fun handleControl(from: String, payload: ByteArray) {
        val raw = runCatching { String(payload, Charsets.UTF_8) }.getOrNull() ?: return
        runCatching {
            val json = JSONObject(raw)
            val action = json.optString("a", "")
            val callId = json.optString("id", "")
            val name = json.optString("n", "")
            if (action.isNotEmpty() && callId.isNotEmpty()) {
                onControl?.invoke(from, action, callId, name)
            }
        }
    }

    // -------------------------------------------------------------- wi-fi mesh

    fun wifiPeers(): List<WifiP2pDevice> = wifi.peerDevices()

    fun connectWifiPeer(device: WifiP2pDevice) = wifi.connect(device)

    fun refreshWifiPeers() = wifi.discover()

    val wifiGroupOwnerIp: String? get() = wifi.groupOwnerIp

    val wifiIsGroupOwner: Boolean get() = wifi.isGroupOwner

    val wifiHasGroup: Boolean get() = wifi.hasGroup

    val wifiConnectedPeers: Int get() = wifi.connectedPeerCount

    // ------------------------------------------------------------------ status

    fun status(): Map<String, Any> = mapOf(
        "radioSmsAvailable" to RadioTransport.isAvailable(context),
        "bluetoothLeActive" to bleActive,
        "wifiDirectActive" to wifiActive,
        "blePeers" to ble.connectedPeerCount,
        "wifiGroup" to wifi.hasGroup,
        "wifiGroupOwner" to wifi.isGroupOwner,
        "wifiGroupOwnerIp" to (wifi.groupOwnerIp ?: ""),
        "wifiPeers" to wifi.peerDevices().size,
        "localNodeId" to localNodeId,
    )

    // ------------------------------------------------------------------- utils

    private fun newId(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .copyOfRange(0, 16)
            .joinToString("") { "%02x".format(it) }
}
