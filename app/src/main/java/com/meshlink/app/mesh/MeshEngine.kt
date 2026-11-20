package com.meshlink.app.mesh

import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.util.Log
import com.meshlink.app.data.MeshSettings
import com.meshlink.app.util.PermissionsHelper
import com.meshlink.app.util.isMeshLinkId
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.Executors

enum class TransportType {
    /** Cellular radio via SMS — works with only a SIM, no internet. */
    RADIO_SMS,

    /** Bluetooth LE — local 2.4 GHz radio, no SIM and no internet. */
    BLUETOOTH_LE,

    /** Wi-Fi Direct — phone-to-phone Wi-Fi, no router and no internet. */
    WIFI_DIRECT,

    /** Wi-Fi LAN — both phones on the same router or hotspot, no internet. */
    WIFI_LAN,

    /** Internet relay (MQTT) — worldwide messaging over Wi-Fi or mobile data. */
    ONLINE,

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

        /** Online-only envelopes (plaintext inside the AES-GCM blob). */
        private const val TAG_ONLINE_META = "MLM1"
        private const val TAG_ONLINE_CHUNK = "MLF1"
        private const val TAG_ONLINE_CALL = "MLC1"

        /** Big chunks for the internet relay; a 3 MB photo is ~64 publishes. */
        private const val ONLINE_FILE_CHUNK = 48 * 1024
        private const val MAX_ONLINE_CHUNKS = 512
        private const val MAX_ONLINE_FILE = 5 * 1024 * 1024L

        /** Call signalling is QoS 1, so the broker may deliver it twice. */
        private const val SIGNAL_DEDUPE_MS = 60 * 1000L
    }

    /** How many phones a frame may pass through before it is dropped. */
    private fun hops(): Int = MeshSettings.maxHops(context)

    /** Our mesh node id, derived from our UIN. */
    @Volatile
    var localNodeId: Int = 0
        private set

    /** This phone's UIN, carried inside frames so replies reach the right node. */
    @Volatile
    private var localUin: String = ""

    /** Every node id that addresses this device (UIN, and 0 = broadcast). */
    @Volatile
    private var localNodeIds: Set<Int> = emptySet()

    /** A complete text message arrived and is addressed to us. */
    var onMessageForMe:
        ((from: String, messageId: String, body: String, senderName: String,
          transport: TransportType, senderPhone: String) -> Unit)? = null

    /** A message was sent from the paired web app; mirror it as outgoing. */
    var onCompanionForMe:
        ((peer: String, messageId: String, body: String, senderName: String) -> Unit)? = null

    /** The receiving phone confirmed delivery of [messageId]. */
    var onAckFor: ((messageId: String) -> Unit)? = null

    /** A complete file (photo / document) arrived and is addressed to us. */
    var onFileForMe:
        ((from: String, messageId: String, name: String, mime: String, path: String,
          size: Long, transport: TransportType, senderName: String) -> Unit)? = null

    /** Call signalling: action = invite / accept / reject / end. */
    var onControl:
        ((from: String, action: String, callId: String, name: String, peerIp: String) -> Unit)? = null

    /** WebRTC signalling for internet calls, carried inside online mode. */
    var onCallSignal: ((from: String, json: String) -> Unit)? = null

    /** The other side started or stopped typing (online mode only). */
    var onTyping: ((from: String, typing: Boolean) -> Unit)? = null

    private val ble = BleTransport(context)
    private val wifi = WifiDirectTransport(context)
    private val lan = LanTransport(context)
    private var online: OnlineTransport? = null

    /**
     * Online publishes happen on their own thread: a broker write must never
     * block the UI (or a call's ring/accept path).
     */
    private val onlineSender = Executors.newSingleThreadExecutor { r ->
        Thread(r, "meshlink-online-tx").apply { isDaemon = true }
    }

    private val seen = LinkedHashMap<String, Long>()
    private val seenLock = Any()

    /** Detects duplicate WebRTC offer/answer/ICE envelopes from QoS 1 MQTT. */
    private val seenCallSignals = LinkedHashMap<String, Long>()
    private val seenCallSignalsLock = Any()

    /** UIN -> time we last heard that phone on a local radio. */
    private val lastHeard = HashMap<String, Long>()
    private val heardLock = Any()

    private val segments = HashMap<String, SegBuffer>()
    private val segmentLock = Any()

    private class IncomingFile(
        val messageId: String,
        val name: String,
        val mime: String,
        val size: Long,
        val senderUin: String,
        val assembler: FileTransfer.Assembler,
    ) {
        val updatedAt = System.currentTimeMillis()
    }

    private val incomingFiles = HashMap<String, IncomingFile>()
    private val onlineFiles = HashMap<String, OnlineFile>()
    private val fileLock = Any()

    private class OnlineFile(
        val messageId: String,
        val name: String,
        val mime: String,
        val size: Long,
        val senderUin: String,
        val total: Int,
    ) {
        val parts = arrayOfNulls<ByteArray>(total)
        var received = 0
        var updatedAt = System.currentTimeMillis()

        fun join(): ByteArray? {
            var size = 0
            parts.forEach { size += it?.size ?: 0 }
            val out = ByteArray(size)
            var pos = 0
            parts.forEach { part ->
                if (part == null) return null
                part.copyInto(out, pos)
                pos += part.size
            }
            return out
        }
    }

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
    var lanActive: Boolean = false
        private set

    // ------------------------------------------------------------------ setup

    fun configure(localUin: String) {
        this.localUin = localUin
        localNodeId = MeshFrame.nodeId16(localUin)
        localNodeIds = setOf(localNodeId)
        // The service can configure the UIN after (or before) start().
        if (MeshSettings.onlineEnabled(context) && localUin.isNotBlank()) startOnline()
    }

    fun start() {
        RadioTransport.onFrame = { from, bytes -> handleIncoming(from, TransportType.RADIO_SMS, bytes) }
        RadioTransport.onText = { from, text ->
            onMessageForMe?.invoke(from, newId(text.toByteArray()), text, "", TransportType.RADIO_SMS, "")
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

        if (lan.isSupported) {
            runCatching { lan.start() }
                .onSuccess { lanActive = true }
                .onFailure { Log.w(TAG, "Wi-Fi LAN start failed", it) }
        }
        lan.onFrame = { addr, bytes -> handleIncoming(addr, TransportType.WIFI_LAN, bytes) }

        // Online mode is opt-in; it needs internet and a relay broker.
        if (MeshSettings.onlineEnabled(context)) startOnline()

        Log.i(
            TAG,
            "Mesh started (BLE=$bleActive, Wi-FiDirect=$wifiActive, Wi-FiLAN=$lanActive, " +
                "SMS=${MeshSettings.smsFallback(context)})",
        )
    }

    fun stop() {
        ble.stop()
        wifi.stop()
        lan.stop()
        bleActive = false
        wifiActive = false
        lanActive = false
        online?.stop()
        online = null
    }

    // ------------------------------------------------------------- online mode

    /** Turns the worldwide internet relay on or off and remembers the choice. */
    fun setOnlineMode(enabled: Boolean) {
        MeshSettings.setOnlineEnabled(context, enabled)
        if (enabled) {
            startOnline()
        } else {
            online?.stop()
            online = null
        }
    }

    /** Reconnects after the broker address changed in Settings. */
    fun restartOnline() {
        online?.stop()
        online = null
        if (MeshSettings.onlineEnabled(context)) startOnline()
    }

    private fun startOnline() {
        val me = localUin
        if (me.isBlank()) return
        val t = online ?: OnlineTransport(context).also {
            it.onFrame = { from, bytes -> handleIncoming(from, TransportType.ONLINE, bytes) }
            it.onRelayFor = { receiver, sender, bytes ->
                relayToMesh(receiver, sender, bytes)
            }
            it.onCompanionForMe = { peer, id, body, name ->
                onCompanionForMe?.invoke(peer, id, body, name)
            }
            online = it
        }
        t.start(me, MeshSettings.onlineBroker(context))
    }

    /**
     * A message for a phone that is near us came down from the relay. Put it
     * back on the local radios so that phone (which has no internet) gets it.
     */
    private fun relayToMesh(receiver: String, sender: String, bytes: ByteArray) {
        if (receiver.equals(localUin, ignoreCase = true)) return
        if (!heardRecently(receiver)) return
        val frame = MeshFrame.deserialize(bytes) ?: return
        if (frame.type == MeshFrame.TYPE_FILE_META || frame.type == MeshFrame.TYPE_FILE_CHUNK) return
        if (frame.type == MeshFrame.TYPE_TYPING) return
        if (frame.type == MeshFrame.TYPE_BRIDGE) {
            val dest = BridgeFrames.parse(frame.payload)?.destination
            if (dest == null || !dest.equals(receiver, ignoreCase = true)) return
        }
        runCatching {
            if (lanActive) lan.send(bytes)
            if (wifiActive) wifi.send(bytes)
            if (bleActive) ble.send(null, bytes)
        }
        Log.i(TAG, "bridged online message for $receiver from $sender onto local radios")
    }

    private fun publishOnline(destination: String, frames: List<ByteArray>): Boolean {
        val t = online ?: return false
        if (!t.isConnected) return false
        // Fire and forget, in order. The mesh layer retries anything that is
        // not acknowledged, so a slow broker never blocks the caller.
        onlineSender.execute {
            for (f in frames) runCatching { t.publish(destination, f) }
        }
        return true
    }

    private fun isControlBody(body: String): Boolean =
        body.startsWith("MLRD|") || body.startsWith("MLDEL|") ||
            body.startsWith("MLR1|") || body.startsWith("MLGINV|") ||
            body.startsWith("MLG1|")

    /** Mirrors a text message to the web app over the shared MQTT bridge. */
    private fun publishWebText(destination: String, messageId: String, body: String, senderName: String) {
        if (!destination.isMeshLinkId() || isControlBody(body)) return
        val t = online ?: return
        if (!t.isConnected) return
        val payload = JSONObject()
            .put("i", messageId)
            .put("b", body)
            .put("n", senderName)
            .put("u", localUin)
            .toString()
        onlineSender.execute { runCatching { t.publishWebBridge(destination, payload) } }
    }

    /** Mirrors a phone-sent message to the user's own web sessions. */
    private fun publishWebCompanion(peer: String, messageId: String, body: String, senderName: String) {
        if (!peer.isMeshLinkId() || isControlBody(body)) return
        val t = online ?: return
        if (!t.isConnected || localUin.isBlank()) return
        val payload = JSONObject()
            .put("i", messageId)
            .put("b", body)
            .put("n", senderName)
            .put("u", localUin)
            .put("d", peer)
            .put("s", 1)
            .put("o", "android")
            .toString()
        onlineSender.execute { runCatching { t.publishWebBridge(localUin, payload, localUin) } }
    }

    /** Publishes one already-built online envelope (file / call signalling). */
    private fun sendOnlineRaw(destination: String, bytes: ByteArray): Boolean {
        val t = online ?: return false
        if (!t.isConnected) return false
        if (!destination.isMeshLinkId()) return false
        onlineSender.execute {
            // QoS 1 for files and call signalling: one lost chunk would
            // otherwise stall a photo forever.
            runCatching { t.publish(destination, bytes, 1) }
        }
        return true
    }

    /**
     * Typing indicator. Deliberately online-only: sending a frame per
     * keystroke over Bluetooth/Wi-Fi would waste radio airtime for a status
     * that expires in seconds.
     */
    fun sendTyping(destination: String, typing: Boolean): Boolean {
        val dest = destination.trim().uppercase()
        if (!dest.isMeshLinkId()) return false
        val t = online ?: return false
        if (!t.isConnected) return false
        val frame = MeshFrame.Frame(
            type = MeshFrame.TYPE_TYPING,
            sourceNodeId = localNodeId,
            destNodeId = MeshFrame.nodeId16(dest),
            ttl = hops(),
            payload = byteArrayOf(if (typing) 1 else 0),
        ).serialize()
        return publishOnline(dest, listOf(frame))
    }

    /** WebRTC offer / answer / ICE, delivered through online mode. */
    fun sendOnlineCallSignal(destination: String, json: String): Boolean =
        sendOnlineRaw(destination, (TAG_ONLINE_CALL + json).toByteArray(Charsets.UTF_8))

    /**
     * QoS 1 can replay a signalling message. A replayed "offer" would make the
     * callee reject its own ringing call, so drop duplicates for a short window.
     */
    private fun isDuplicateCallSignal(from: String, bytes: ByteArray): Boolean {
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val key = "$from:$fingerprint"
        val now = System.currentTimeMillis()
        synchronized(seenCallSignalsLock) {
            val it = seenCallSignals.entries.iterator()
            while (it.hasNext()) {
                if (now - it.next().value > SIGNAL_DEDUPE_MS) it.remove()
            }
            if (seenCallSignals.containsKey(key)) return true
            seenCallSignals[key] = now
            return false
        }
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
        allowSms: Boolean = true,
        smsNumber: String? = null,
    ): Boolean {
        val target = destination.trim()
        if (target.isEmpty() || body.isEmpty()) return false
        val frames = runCatching {
            buildFrames(target, messageId, body, senderName) +
                buildBridgeFrames(target, messageId, body, senderName)
        }.getOrElse {
            Log.w(TAG, "cannot build frames", it)
            return false
        }
        // The web bridge carries plain JSON, independent of radio frame size.
        publishWebText(target, messageId, body, senderName)
        publishWebCompanion(target, messageId, body, senderName)
        return sendFrames(target, frames, allowSms, smsNumber)
    }

    /** True when [destination] can be dialled/SMSed over the cellular radio. */
    fun isRadioNumber(destination: String): Boolean =
        normalizeNumber(destination).matches(Regex("^\\+?[0-9]{7,15}$"))

    /** Accepts formatted numbers such as "+92 300 1234567" or "0300-1234567". */
    fun normalizeNumber(value: String): String =
        value.trim()
            .replace(Regex("[\\s\\-().]"), "")
            .replace(Regex("(?!^)\\+"), "")

    fun sendFrame(destination: String, frame: ByteArray, transport: TransportType): Boolean =
        when (transport) {
            TransportType.RADIO_SMS -> RadioTransport.sendFrame(context, destination, frame)
            TransportType.BLUETOOTH_LE -> ble.send(null, frame) > 0
            TransportType.WIFI_DIRECT -> wifi.send(frame)
            TransportType.WIFI_LAN -> lan.send(frame)
            TransportType.ONLINE -> sendFrames(destination, listOf(frame))
            TransportType.AUTO -> sendFrames(destination, listOf(frame))
        }

    private fun sendFrames(
        destination: String,
        frames: List<ByteArray>,
        allowSms: Boolean = true,
        smsNumber: String? = null,
    ): Boolean {
        // A mesh id (OFF-XXXXXX) is not dialable. If the other side shared a
        // phone number, use it for the SMS hop so a message can cross the
        // country when no mesh peer is in range.
        val smsTo = when {
            !smsNumber.isNullOrBlank() && isRadioNumber(smsNumber) -> normalizeNumber(smsNumber)
            isRadioNumber(destination) -> normalizeNumber(destination)
            else -> null
        }

        // Online mode is the only path that crosses the world, so publish
        // first when it is switched on. Local radios still get the frame too,
        // which keeps nearby chats instant and lets both paths race.
        val onlineOk = if (destination.isMeshLinkId()) {
            publishOnline(destination, frames)
        } else false

        val lanReady = lanActive && lan.peerCount() > 0
        val wifiReady = wifiActive && wifi.hasGroup
        val bleReady = bleActive && ble.connectedPeerCount > 0

        // Known-good radios first so a nearby message never waits on a radio
        // that has no peers.
        if (lanReady && sendAll(frames) { lan.send(it) }) return true
        if (wifiReady && sendAll(frames) { wifi.send(it) }) return true
        if (bleReady && sendAll(frames) { ble.send(null, it) > 0 }) return true

        // Then the remaining radios (a broadcast can still find a peer).
        if (lanActive && sendAll(frames) { lan.send(it) }) return true
        if (wifiActive && sendAll(frames) { wifi.send(it) }) return true
        if (bleActive && sendAll(frames) { ble.send(null, it) > 0 }) return true

        if (onlineOk) return true

        // SMS is strictly opt-in: it uses the SIM and may cost money, so the
        // user has to switch it on in Settings and grant the SMS permission.
        if (allowSms && smsTo != null &&
            MeshSettings.smsFallback(context) &&
            PermissionsHelper.smsGranted(context) &&
            RadioTransport.isAvailable(context)
        ) {
            if (sendAll(frames) { RadioTransport.sendFrame(context, smsTo, it) }) return true
        }
        return false
    }

    private inline fun sendAll(frames: List<ByteArray>, send: (ByteArray) -> Boolean): Boolean {
        var ok = true
        for (f in frames) ok = send(f) && ok
        return ok
    }

    private fun messageJson(messageId: String, body: String, senderName: String): ByteArray {
        val myPhone = com.meshlink.app.data.SessionStore.phone(context)
        return JSONObject()
            .put("i", messageId)
            .put("b", body)
            .put("n", senderName)
            .put("u", localUin)
            .put("t", System.currentTimeMillis())
            .apply { if (myPhone.isNotBlank()) put("p", myPhone) }
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    /**
     * Extra copies of a text message that carry the destination UIN, so any
     * MeshLink phone with internet that sees them can forward them to the
     * worldwide relay (and from there to the destination's own phone).
     */
    private fun buildBridgeFrames(
        destination: String,
        messageId: String,
        body: String,
        senderName: String,
    ): List<ByteArray> {
        val payloads = runCatching {
            BridgeFrames.payloads(destination, messageId, messageJson(messageId, body, senderName))
        }.getOrDefault(emptyList())
        if (payloads.isEmpty()) return emptyList()
        val destNode = MeshFrame.nodeId16(destination)
        return payloads.map { payload ->
            MeshFrame.Frame(
                type = MeshFrame.TYPE_BRIDGE,
                sourceNodeId = localNodeId,
                destNodeId = destNode,
                ttl = hops(),
                payload = payload,
            ).serialize()
        }
    }

    private fun buildFrames(
        destination: String,
        messageId: String,
        body: String,
        senderName: String,
    ): List<ByteArray> {
        val payload = messageJson(messageId, body, senderName)
        val destNode = MeshFrame.nodeId16(destination)

        if (payload.size <= MeshFrame.MAX_PAYLOAD) {
            return listOf(
                MeshFrame.Frame(
                    type = MeshFrame.TYPE_MESSAGE,
                    sourceNodeId = localNodeId,
                    destNodeId = destNode,
                    ttl = hops(),
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
                ttl = hops(),
                payload = buf,
            ).serialize()
        }
        return frames
    }

    /** Call signalling frame (invite / accept / reject / end). */
    fun sendControl(destination: String, action: String, callId: String, name: String): Boolean {
        // Call signalling must fit in one 96-byte frame, so the id is short
        // and the display name is trimmed. The LAN IP lets the other phone
        // send voice straight back over the same Wi-Fi network.
        // Only advertise a voice address when the Wi-Fi LAN transport is really
        // running. A stale Wi-Fi IP would make the callee send audio to a dead
        // address even though the call is travelling over Wi-Fi Direct.
        val ip = if (lanActive) localLanIp().orEmpty() else ""
        var payload = JSONObject()
            .put("a", action)
            .put("id", callId.take(8))
            .put("n", name.take(12))
            .put("u", localUin)
            .put("ip", ip)
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (payload.size > MeshFrame.MAX_PAYLOAD) {
            // Drop the name before giving up: the UIN is what routes the call.
            payload = JSONObject()
                .put("a", action)
                .put("id", callId.take(8))
                .put("u", localUin)
                .put("ip", ip)
                .toString()
                .toByteArray(Charsets.UTF_8)
        }
        if (payload.size > MeshFrame.MAX_PAYLOAD) return false
        val frame = MeshFrame.Frame(
            type = MeshFrame.TYPE_CALL,
            sourceNodeId = localNodeId,
            destNodeId = MeshFrame.nodeId16(destination),
            ttl = hops(),
            payload = payload,
        ).serialize()
        return sendFrames(destination, listOf(frame))
    }

    // ------------------------------------------------------------- file send

    /**
     * Sends a photo, document or voice note. Files are never sent by SMS
     * (they are far too large) and the whole transfer sticks to one radio; the
     * receiver reassembles the chunks even if they arrive out of order.
     *
     * Returns true when every chunk was handed to a radio.
     */
    fun sendFile(
        destination: String,
        messageId: String,
        fileName: String,
        mime: String,
        data: ByteArray,
        onProgress: ((Int) -> Unit)? = null,
    ): Boolean {
        val target = destination.trim()
        if (target.isEmpty()) return false

        // Online mode only takes over when no local radio has actually heard
        // the other phone. Local Wi-Fi is faster and stays on the LAN.
        val useOnline = target.isMeshLinkId() &&
            online?.isConnected == true &&
            !heardRecently(target)
        if (useOnline) {
            return sendFileOnline(target, messageId, fileName, mime, data, onProgress)
        }

        val chunks = FileTransfer.split(data)
        val transferId = MessageDigest.getInstance("SHA-256")
            .digest(messageId.toByteArray(Charsets.UTF_8))
            .copyOfRange(0, 4)
        val idHex = transferId.joinToString("") { "%02x".format(it) }

        val meta = runCatching {
            JSONObject()
                .put("i", idHex)
                .put("n", safeFileName(fileName).take(12))
                .put("m", mime.take(16))
                .put("s", data.size.toLong())
                .put("c", chunks.size)
                .put("u", localUin)
                .toString()
                .toByteArray(Charsets.UTF_8)
        }.getOrNull() ?: return false
        if (meta.size > MeshFrame.MAX_PAYLOAD) return false

        val destNode = MeshFrame.nodeId16(target)
        val metaFrame = MeshFrame.Frame(
            type = MeshFrame.TYPE_FILE_META,
            sourceNodeId = localNodeId,
            destNodeId = destNode,
            ttl = hops(),
            payload = meta,
        ).serialize()

        val send: (ByteArray) -> Boolean = pickFileRadio()
        if (!send(metaFrame)) return false

        val totalFrames = chunks.size + 1
        var sent = 1
        var lastReported = 0
        for ((index, chunk) in chunks.withIndex()) {
            val payload = ByteArray(6 + chunk.size)
            transferId.copyInto(payload, 0)
            payload[4] = ((index shr 8) and 0xFF).toByte()
            payload[5] = (index and 0xFF).toByte()
            chunk.copyInto(payload, 6)

            val frame = MeshFrame.Frame(
                type = MeshFrame.TYPE_FILE_CHUNK,
                sourceNodeId = localNodeId,
                destNodeId = destNode,
                ttl = hops(),
                payload = payload,
            ).serialize()
            if (!send(frame)) return false
            sent++
            // Pace the burst a little: a 300 KB photo is ~3,400 frames and
            // flooding the receiver's UDP buffer would lose chunks.
            if (sent % 8 == 0) Thread.sleep(2)
            val percent = sent * 100 / totalFrames
            if (percent > lastReported) {
                lastReported = percent
                onProgress?.invoke(percent)
            }
        }
        onProgress?.invoke(100)
        return true
    }

    /**
     * Files over the internet relay. MQTT messages may be much bigger than a
     * mesh frame, so a photo becomes ~7 publishes instead of 3,400.
     */
    private fun sendFileOnline(
        destination: String,
        messageId: String,
        fileName: String,
        mime: String,
        data: ByteArray,
        onProgress: ((Int) -> Unit)?,
    ): Boolean {
        val chunks = FileTransfer.split(data, ONLINE_FILE_CHUNK)
        if (chunks.size > MAX_ONLINE_CHUNKS) return false
        val transferId = MessageDigest.getInstance("SHA-256")
            .digest(messageId.toByteArray(Charsets.UTF_8))
            .copyOfRange(0, 4)
        val idHex = transferId.joinToString("") { "%02x".format(it) }

        val meta = runCatching {
            TAG_ONLINE_META + JSONObject()
                .put("i", idHex)
                .put("n", safeFileName(fileName).take(64))
                .put("m", mime.take(64))
                .put("s", data.size.toLong())
                .put("c", chunks.size)
                .put("u", localUin)
                .toString()
        }.getOrNull() ?: return false
        if (!sendOnlineRaw(destination, meta.toByteArray(Charsets.UTF_8))) return false

        val totalFrames = chunks.size + 1
        var sent = 1
        var lastReported = 0
        for ((index, chunk) in chunks.withIndex()) {
            val payload = ByteArray(8 + chunk.size)
            transferId.copyInto(payload, 0)
            payload[4] = ((index shr 24) and 0xFF).toByte()
            payload[5] = ((index shr 16) and 0xFF).toByte()
            payload[6] = ((index shr 8) and 0xFF).toByte()
            payload[7] = (index and 0xFF).toByte()
            chunk.copyInto(payload, 8)
            val message = TAG_ONLINE_CHUNK.toByteArray(Charsets.UTF_8) + payload
            if (!sendOnlineRaw(destination, message)) return false
            sent++
            if (sent % 16 == 0) Thread.sleep(1)
            val percent = sent * 100 / totalFrames
            if (percent > lastReported) {
                lastReported = percent
                onProgress?.invoke(percent)
            }
        }
        onProgress?.invoke(100)
        return true
    }

    /**
     * Picks one radio for a whole file. Wi-Fi LAN first (fast, reliable),
     * then Wi-Fi Direct (only if a group exists), then Bluetooth LE with a
     * short pause per frame so the GATT queue can keep up.
     */
    private fun pickFileRadio(): (ByteArray) -> Boolean {
        val sendLan: (ByteArray) -> Boolean =
            { frame -> runCatching { lan.send(frame) }.getOrDefault(false) }
        val sendWifi: (ByteArray) -> Boolean =
            { frame -> runCatching { wifi.send(frame) }.getOrDefault(false) }
        val sendBle: (ByteArray) -> Boolean = { frame ->
            var ok = false
            repeat(3) {
                if (!ok) {
                    ok = runCatching { ble.send(null, frame) > 0 }.getOrDefault(false)
                    if (!ok) Thread.sleep(15)
                }
            }
            if (ok) Thread.sleep(10)
            ok
        }

        // Prefer a radio that has actually heard the other side. A Wi-Fi LAN
        // broadcast "succeeds" even when nobody is listening, so require at
        // least one recently heard peer.
        if (lanActive && lan.peerCount() > 0) return sendLan
        if (wifiActive && wifi.hasGroup) return sendWifi
        if (bleActive && ble.connectedPeerCount > 0) return sendBle

        if (lanActive) return sendLan
        if (bleActive) return sendBle
        if (wifiActive) return sendWifi
        return { false }
    }

    fun isFileCapable(): Boolean = lanActive || bleActive || wifiActive || online?.isConnected == true

    private fun safeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "file" }

    private fun handleFileMeta(from: String, payload: ByteArray, transport: TransportType) {
        val json = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull() ?: return
        val id = json.optString("i", "")
        val total = json.optInt("c", 0)
        noteHeard(json.optString("u", ""))
        if (id.length != 8 || total <= 0 || total > 40_000) return

        synchronized(fileLock) {
            purgeFiles()
            val key = "$from|$id"
            // A relayed duplicate of the start frame must not reset progress.
            if (incomingFiles.containsKey(key)) return
            incomingFiles[key] = IncomingFile(
                messageId = id,
                name = safeFileName(json.optString("n", "file")),
                mime = json.optString("m", "application/octet-stream"),
                size = json.optLong("s", 0),
                senderUin = json.optString("u", ""),
                assembler = FileTransfer.Assembler(total),
            )
        }
    }

    private fun handleFileChunk(from: String, payload: ByteArray, transport: TransportType) {
        if (payload.size < 6) return
        val id = payload.copyOfRange(0, 4).joinToString("") { "%02x".format(it) }
        val index = ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF)
        val data = payload.copyOfRange(6, payload.size)

        var complete: IncomingFile? = null
        synchronized(fileLock) {
            purgeFiles()
            val file = incomingFiles["$from|$id"] ?: return
            if (file.assembler.put(index, data)) {
                incomingFiles.remove("$from|$id")
                complete = file
            }
        }
        complete?.let { finishFile(from, it, transport) }
    }

    private fun finishFile(from: String, file: IncomingFile, transport: TransportType) {
        runCatching {
            val dir = java.io.File(context.filesDir, "media").apply { mkdirs() }
            val extension = file.name.substringAfterLast('.', "").take(8)
            val out = java.io.File(
                dir,
                file.messageId + if (extension.isNotBlank()) ".$extension" else "",
            )
            out.writeBytes(file.assembler.join())
            // Prefer the sender's UIN so the reply lands on that phone even
            // when the last hop was a relay.
            val sender = file.senderUin.ifBlank { from }
            onFileForMe?.invoke(
                sender, file.messageId, file.name, file.mime, out.absolutePath,
                out.length(), transport, file.senderUin,
            )
            sendAck(sender, file.messageId)
        }.onFailure { Log.w(TAG, "could not save incoming file", it) }
    }

    // ------------------------------------------------------- online file rx

    private fun handleOnlineFileMeta(from: String, raw: ByteArray) {
        if (raw.size > 4_096) return
        val json = runCatching { JSONObject(String(raw, Charsets.UTF_8)) }.getOrNull() ?: return
        val id = json.optString("i", "")
        val total = json.optInt("c", 0)
        val size = json.optLong("s", 0)
        noteHeard(json.optString("u", ""))
        if (id.length != 8 || total <= 0 || total > MAX_ONLINE_CHUNKS) return
        if (size < 0 || size > MAX_ONLINE_FILE) return

        synchronized(fileLock) {
            purgeFiles()
            val key = "$from|$id"
            if (onlineFiles.containsKey(key)) return
            onlineFiles[key] = OnlineFile(
                messageId = id,
                name = safeFileName(json.optString("n", "file")),
                mime = json.optString("m", "application/octet-stream"),
                size = size,
                senderUin = json.optString("u", ""),
                total = total,
            )
        }
    }

    private fun handleOnlineFileChunk(from: String, payload: ByteArray) {
        if (payload.size < 9) return
        val id = payload.copyOfRange(0, 4).joinToString("") { "%02x".format(it) }
        val index = ((payload[4].toInt() and 0xFF) shl 24) or
            ((payload[5].toInt() and 0xFF) shl 16) or
            ((payload[6].toInt() and 0xFF) shl 8) or
            (payload[7].toInt() and 0xFF)
        val data = payload.copyOfRange(8, payload.size)

        var complete: OnlineFile? = null
        synchronized(fileLock) {
            purgeFiles()
            val file = onlineFiles["$from|$id"] ?: return
            if (index in 0 until file.total && file.parts[index] == null) {
                file.parts[index] = data
                file.received++
                file.updatedAt = System.currentTimeMillis()
                if (file.received == file.total) {
                    onlineFiles.remove("$from|$id")
                    complete = file
                }
            }
        }
        complete?.let { finishOnlineFile(from, it) }
    }

    private fun finishOnlineFile(from: String, file: OnlineFile) {
        runCatching {
            val bytes = file.join() ?: return
            if (bytes.size > MAX_ONLINE_FILE) return
            val dir = java.io.File(context.filesDir, "media").apply { mkdirs() }
            val extension = file.name.substringAfterLast('.', "").take(8)
            val out = java.io.File(
                dir,
                file.messageId + if (extension.isNotBlank()) ".$extension" else "",
            )
            out.writeBytes(bytes)
            val sender = file.senderUin.ifBlank { from }
            onFileForMe?.invoke(
                sender, file.messageId, file.name, file.mime, out.absolutePath,
                out.length(), TransportType.ONLINE, file.senderUin,
            )
            sendAck(sender, file.messageId)
        }.onFailure { Log.w(TAG, "could not save online file", it) }
    }

    private fun purgeFiles() {
        val now = System.currentTimeMillis()
        val it = incomingFiles.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.updatedAt > 3 * 60 * 1000L) it.remove()
        }
        val online = onlineFiles.entries.iterator()
        while (online.hasNext()) {
            if (now - online.next().value.updatedAt > 3 * 60 * 1000L) online.remove()
        }
    }

    // --------------------------------------------------------------- receiving

    private fun handleIncoming(fromAddress: String, transport: TransportType, bytes: ByteArray) {
        // Online mode carries three extra envelope types beside mesh frames.
        if (transport == TransportType.ONLINE && bytes.size >= 8) {
            when (String(bytes, 0, 4, Charsets.UTF_8)) {
                TAG_ONLINE_META -> {
                    handleOnlineFileMeta(fromAddress, bytes.copyOfRange(4, bytes.size))
                    return
                }
                TAG_ONLINE_CHUNK -> {
                    handleOnlineFileChunk(fromAddress, bytes.copyOfRange(4, bytes.size))
                    return
                }
                TAG_ONLINE_CALL -> {
                    if (!isDuplicateCallSignal(fromAddress, bytes)) {
                        onCallSignal?.invoke(
                            fromAddress,
                            String(bytes, 4, bytes.size - 4, Charsets.UTF_8),
                        )
                    }
                    return
                }
            }
        }

        val frame = MeshFrame.deserialize(bytes)

        if (frame == null) {
            // Raw payload (large text from an older build).
            val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return
            if (text.isEmpty()) return
            onMessageForMe?.invoke(fromAddress, newId(bytes), text, "", transport, "")
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
                MeshFrame.TYPE_FILE_META -> handleFileMeta(fromAddress, frame.payload, transport)
                MeshFrame.TYPE_FILE_CHUNK -> handleFileChunk(fromAddress, frame.payload, transport)
                MeshFrame.TYPE_BRIDGE -> handleBridge(fromAddress, frame.payload, transport)
                MeshFrame.TYPE_TYPING ->
                    onTyping?.invoke(fromAddress, frame.payload.firstOrNull()?.toInt() == 1)
                else -> Unit
            }
            return
        }

        // Not ours. If it is a bridge envelope and this phone has internet,
        // carry it to the destination's relay topic so it can cross the
        // country even though the sender has no data connection.
        if (frame.type == MeshFrame.TYPE_BRIDGE && transport != TransportType.ONLINE) {
            val dest = BridgeFrames.parse(frame.payload)?.destination
            if (dest != null && !dest.equals(localUin, ignoreCase = true)) {
                sendOnlineRaw(dest, bytes)
            }
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
                TransportType.WIFI_LAN -> lan.send(relayed)
                else -> Unit // SMS is point-to-point
            }
        }
    }

    private fun isForMe(frame: MeshFrame.Frame, transport: TransportType): Boolean =
        transport == TransportType.RADIO_SMS ||
            transport == TransportType.ONLINE ||
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

    private fun handleBridge(from: String, payload: ByteArray, transport: TransportType) {
        val header = BridgeFrames.parse(payload) ?: return
        val data = payload.copyOfRange(BridgeFrames.HEADER, payload.size)
        val key = "$from|b${header.idHash}"
        val buffer: SegBuffer
        val complete: Boolean
        synchronized(segmentLock) {
            purgeSegments()
            val b = segments.getOrPut(key) { SegBuffer(header.total) }
            buffer = b
            complete = synchronized(b) { b.put(header.sequence, data) }
            if (complete) segments.remove(key)
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
        var phone = ""
        var sender = from
        runCatching {
            val json = JSONObject(raw)
            id = json.optString("i", "")
            body = json.optString("b", raw)
            name = json.optString("n", "")
            phone = json.optString("p", "")
            // Prefer the sender's UIN over the raw MAC/IP so the reply goes
            // back through the mesh to that specific phone.
            json.optString("u", "").takeIf { it.isNotBlank() }?.let { sender = it }
        }

        val messageId = id?.takeIf { it.isNotEmpty() } ?: newId(payload)
        if (body.isEmpty()) return
        if (transport != TransportType.ONLINE) noteHeard(sender)

        onMessageForMe?.invoke(sender, messageId, body, name, transport, phone)
        // Confirm receipt so the sender sees double ticks.
        sendAck(sender, messageId)
    }

    private fun sendAck(destination: String, messageId: String) {
        val payload = messageId.toByteArray(Charsets.UTF_8)
            .let { if (it.size > MeshFrame.MAX_PAYLOAD) it.copyOf(MeshFrame.MAX_PAYLOAD) else it }
        val frame = MeshFrame.Frame(
            type = MeshFrame.TYPE_ACK,
            sourceNodeId = localNodeId,
            destNodeId = MeshFrame.nodeId16(destination),
            ttl = hops(),
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
            val sender = json.optString("u", "").takeIf { it.isNotBlank() } ?: from
            noteHeard(sender)
            val peerIp = json.optString("ip", "")
            if (action.isNotEmpty() && callId.isNotEmpty()) {
                onControl?.invoke(sender, action, callId, name, peerIp)
            }
        }
    }

    // -------------------------------------------------------------- wi-fi mesh

    fun wifiPeers(): List<WifiP2pDevice> = wifi.peerDevices()

    fun connectWifiPeer(device: WifiP2pDevice) = wifi.connect(device)

    fun refreshWifiPeers() = wifi.discover()

    /** True when the UDP LAN transport was started (same Wi-Fi / hotspot). */
    val lanIsActive: Boolean get() = lanActive

    /** This phone's IPv4 address on the local Wi-Fi network, if any. */
    fun localLanIp(): String? = lan.localIp()

    val onlineIsConnected: Boolean get() = online?.isConnected == true

    val wifiGroupOwnerIp: String? get() = wifi.groupOwnerIp

    val wifiIsGroupOwner: Boolean get() = wifi.isGroupOwner

    val wifiHasGroup: Boolean get() = wifi.hasGroup

    val wifiConnectedPeers: Int get() = wifi.connectedPeerCount

    // ------------------------------------------------------------------ status

    fun status(): Map<String, Any> = mapOf(
        "radioSmsAvailable" to RadioTransport.isAvailable(context),
        "bluetoothLeActive" to bleActive,
        "wifiDirectActive" to wifiActive,
        "wifiLanActive" to lanActive,
        "wifiLanPeers" to lan.peerCount(),
        "smsFallbackEnabled" to MeshSettings.smsFallback(context),
        "blePeers" to ble.connectedPeerCount,
        "wifiGroup" to wifi.hasGroup,
        "wifiGroupOwner" to wifi.isGroupOwner,
        "wifiGroupOwnerIp" to (wifi.groupOwnerIp ?: ""),
        "wifiPeers" to wifi.peerDevices().size,
        "localNodeId" to localNodeId,
        "onlineEnabled" to MeshSettings.onlineEnabled(context),
        "onlineConnected" to (online?.isConnected == true),
        "onlineBroker" to MeshSettings.onlineBroker(context),
        "onlineError" to (online?.lastError ?: ""),
    )

    // ------------------------------------------------------------------- utils

    private fun noteHeard(uin: String) {
        if (uin.isBlank() || uin == localUin) return
        synchronized(heardLock) { lastHeard[uin] = System.currentTimeMillis() }
        // Ask the relay for this phone's messages too: if it is offline but
        // we have internet, we hand them over the local radios.
        online?.watchUin(uin)
    }

    /** True when this UIN was heard on Bluetooth or Wi-Fi in the last 3 min. */
    private fun heardRecently(uin: String): Boolean =
        synchronized(heardLock) {
            val at = lastHeard[uin] ?: 0L
            at > 0 && System.currentTimeMillis() - at < 3 * 60 * 1000L
        }

    private fun newId(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .copyOfRange(0, 16)
            .joinToString("") { "%02x".format(it) }
}
