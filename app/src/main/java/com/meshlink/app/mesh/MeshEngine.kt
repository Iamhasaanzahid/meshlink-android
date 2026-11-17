package com.meshlink.app.mesh

import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.util.Log
import com.meshlink.app.data.MeshSettings
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

    /** The receiving phone confirmed delivery of [messageId]. */
    var onAckFor: ((messageId: String) -> Unit)? = null

    /** A complete file (photo / document) arrived and is addressed to us. */
    var onFileForMe:
        ((from: String, messageId: String, name: String, mime: String, path: String,
          size: Long, transport: TransportType, senderName: String) -> Unit)? = null

    /** Call signalling: action = invite / accept / reject / end. */
    var onControl:
        ((from: String, action: String, callId: String, name: String, peerIp: String) -> Unit)? = null

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
    private val fileLock = Any()

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
            online = it
        }
        t.start(me, MeshSettings.onlineBroker(context))
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
        val frames = runCatching { buildFrames(target, messageId, body, senderName) }
            .getOrElse {
                Log.w(TAG, "cannot build frames", it)
                return false
            }
        return sendFrames(target, frames, allowSms, smsNumber)
    }

    /** True when [destination] can be dialled/SMSed over the cellular radio. */
    fun isRadioNumber(destination: String): Boolean =
        destination.trim().matches(Regex("^\\+?[0-9]{7,15}$"))

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
            !smsNumber.isNullOrBlank() && isRadioNumber(smsNumber) -> smsNumber.trim()
            isRadioNumber(destination) -> destination.trim()
            else -> null
        }

        // Online mode is the only path that crosses the world, so publish
        // first when it is switched on. Local radios still get the frame too,
        // which keeps nearby chats instant and lets both paths race.
        val onlineOk = if (destination.trim().uppercase().startsWith("OFF-")) {
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

    private fun buildFrames(
        destination: String,
        messageId: String,
        body: String,
        senderName: String,
    ): List<ByteArray> {
        val myPhone = com.meshlink.app.data.SessionStore.phone(context)
        val json = JSONObject()
            .put("i", messageId)
            .put("b", body)
            .put("n", senderName)
            .put("u", localUin)
            .put("t", System.currentTimeMillis())
            .apply { if (myPhone.isNotBlank()) put("p", myPhone) }
            .toString()
        val payload = json.toByteArray(Charsets.UTF_8)
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
        val ip = localLanIp() ?: ""
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

    fun isFileCapable(): Boolean = lanActive || bleActive || wifiActive

    private fun safeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "file" }

    private fun handleFileMeta(from: String, payload: ByteArray, transport: TransportType) {
        val json = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull() ?: return
        val id = json.optString("i", "")
        val total = json.optInt("c", 0)
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

    private fun purgeFiles() {
        val now = System.currentTimeMillis()
        val it = incomingFiles.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.updatedAt > 3 * 60 * 1000L) it.remove()
        }
    }

    // --------------------------------------------------------------- receiving

    private fun handleIncoming(fromAddress: String, transport: TransportType, bytes: ByteArray) {
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

        onMessageForMe?.invoke(sender, messageId, body, name, transport, phone)
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
    )

    // ------------------------------------------------------------------- utils

    private fun newId(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .copyOfRange(0, 16)
            .joinToString("") { "%02x".format(it) }
}
