package com.meshlink.app.mesh

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import android.util.Log
import com.meshlink.app.util.isMeshLinkId
import org.json.JSONObject
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.security.MessageDigest
import kotlin.concurrent.thread
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Optional online mode: messages travel over the internet through an MQTT
 * relay, so two MeshLink phones anywhere in the world can talk.
 *
 * Design notes
 *  - One topic per receiver: `meshlink/v1/<receiver>/<sender>`.
 *  - Frames are encrypted with AES-GCM. The key is derived from both UINs, so
 *    the relay (and anyone else subscribed to a public broker) sees only
 *    ciphertext. This is not a substitute for a verified key exchange, but it
 *    does stop casual reading of the broker traffic.
 *  - QoS 0 keeps latency low (a text usually lands in well under a second);
 *    the mesh layer already deduplicates and retries.
 */
class OnlineTransport(private val context: Context) {

    companion object {
        private const val TAG = "MeshLinkOnline"
        private const val TOPIC_ROOT = "meshlink/v1"
        /** Shared topic with the web app (plain UTF-8 JSON, no app crypto). */
        private const val WEB_TOPIC_ROOT = "meshlink/web"
        const val DEFAULT_BROKER = "ssl://broker.hivemq.com:8883"
        // Some mobile carriers block outbound 8883/MQTT. Paho supports these
        // URI schemes natively, so we rotate instead of getting stuck forever.
        private val FALLBACK_BROKERS = listOf(
            "wss://broker.hivemq.com:8884/mqtt",
            "ws://broker.hivemq.com:8000/mqtt",
            "tcp://broker.hivemq.com:1883",
        )
    }

    /** A decrypted frame arrived from [fromUin]. */
    var onFrame: ((fromUin: String, bytes: ByteArray) -> Unit)? = null

    /** A message sent from a linked web session arrived; mirror it as outgoing. */
    var onCompanionForMe:
        ((peer: String, messageId: String, body: String, senderName: String) -> Unit)? = null

    /**
     * A frame for a phone near us arrived from a far sender. We have
     * internet, that phone does not, so hand it over on the local radios.
     */
    var onRelayFor: ((receiverUin: String, senderUin: String, bytes: ByteArray) -> Unit)? = null

    /** Nearby UINs whose relay topics we watch (so replies can reach them). */
    private val relaySubs = LinkedHashSet<String>()

    /**
     * Subscribes to [nearbyUin]'s topic. Called for phones we actually heard
     * on a local radio, so we never watch the whole world.
     */
    fun watchUin(nearbyUin: String) {
        val u = nearbyUin.trim().uppercase()
        if (!u.isMeshLinkId() || u == uin) return
        val added = synchronized(relaySubs) {
            if (relaySubs.size >= 24) false else relaySubs.add(u)
        }
        if (!added) return
        if (!isConnected) return
        runCatching { client?.subscribe("$TOPIC_ROOT/$u/#", 0) }
    }

    private fun resubscribe(c: MqttClient) {
        runCatching { c.subscribe("$TOPIC_ROOT/$uin/#", 0) }
        runCatching { c.subscribe("$WEB_TOPIC_ROOT/$uin", 0) }
        val watched = synchronized(relaySubs) { relaySubs.toList() }
        for (u in watched) runCatching { c.subscribe("$TOPIC_ROOT/$u/#", 0) }
    }

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    private var uin: String = ""

    @Volatile
    private var brokerUrl: String = ""

    /** Endpoint currently attempted (may differ from the configured one). */
    @Volatile
    private var currentBroker: String = ""

    /** Last connection error, surfaced in Settings. */
    @Volatile
    var lastError: String = ""
        private set

    @Volatile
    private var stopped: Boolean = true

    private var client: MqttClient? = null
    private var retryThread: Thread? = null

    private val random = SecureRandom()

    /** Starts (or restarts) the connection in the background. */
    fun start(uin: String, broker: String = DEFAULT_BROKER) {
        val me = uin.trim().uppercase()
        val url = broker.trim().ifBlank { DEFAULT_BROKER }
        if (me.isBlank()) return
        stop()
        this.uin = me
        brokerUrl = url
        currentBroker = initialBroker(url)
        lastError = ""
        stopped = false
        retryThread = thread(name = "meshlink-online", isDaemon = true) {
            connectLoop()
        }
    }

    fun stop() {
        stopped = true
        isConnected = false
        val c = client
        client = null
        runCatching { c?.disconnectForcibly(0, 0, false) }
        runCatching { c?.close() }
    }

    /**
     * Publishes one frame to [destination]'s topic.
     * Returns false when not connected or the address is not a UIN.
     */
    fun publish(destination: String, frame: ByteArray, qos: Int = 0): Boolean {
        val dest = destination.trim().uppercase()
        val me = uin
        if (me.isBlank() || dest.isBlank()) return false
        if (!dest.isMeshLinkId()) return false
        val c = client ?: return false
        if (!isConnected) return false
        val blob = runCatching { encrypt(pairKey(me, dest), frame) }.getOrNull() ?: return false
        return runCatching {
            c.publish(
                "$TOPIC_ROOT/$dest/$me",
                MqttMessage(blob).apply {
                    this.qos = qos
                    isRetained = false
                },
            )
            true
        }.getOrElse {
            Log.w(TAG, "publish failed: ${it.message}")
            false
        }
    }

    /**
     * Sends a small JSON envelope to the web bridge, encrypted with AES-GCM.
     * [keyPeer] is the account whose web session should be able to read it:
     * the recipient for a normal message, or this phone's own ID for the
     * linked-device copy.
     */
    fun publishWebBridge(destination: String, payload: String, keyPeer: String = destination): Boolean {
        val dest = destination.trim().uppercase()
        val peer = keyPeer.trim().uppercase()
        if (!dest.isMeshLinkId() || !peer.isMeshLinkId() || !isConnected) {
            Log.w(TAG, "web bridge skipped: online=${isConnected} dest=$dest")
            return false
        }
        return runCatching {
            val encrypted = OnlineCrypto.encrypt(
                OnlineCrypto.pairKey(uin, peer),
                payload.toByteArray(Charsets.UTF_8),
                random,
            )
            val wire = JSONObject()
                .put("u", uin)
                .put("e", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .toString()
            client?.publish(
                "$WEB_TOPIC_ROOT/$dest",
                MqttMessage(wire.toByteArray(Charsets.UTF_8)).apply { qos = 0; isRetained = false },
            )
            Log.i(TAG, "web bridge published to $dest")
            true
        }.getOrElse {
            Log.w(TAG, "web bridge publish failed: ${it.message}")
            false
        }
    }

    // ------------------------------------------------------------- connection

    private fun onCellular(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = cm.activeNetwork ?: return false
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        } catch (t: Throwable) {
            false
        }
    }

    private fun initialBroker(configured: String): String {
        if (configured != DEFAULT_BROKER) return configured
        // Start on the WebSocket/TLS candidate when the active link is cellular.
        return if (onCellular()) FALLBACK_BROKERS.first() else configured
    }

    private fun rotateBroker() {
        if (brokerUrl != DEFAULT_BROKER) return
        val candidates = listOf(DEFAULT_BROKER) + FALLBACK_BROKERS
        val next = candidates.indexOf(currentBroker) + 1
        currentBroker = candidates[if (next >= candidates.size) 0 else next]
    }

    private fun connectLoop() {
        var failedAttempts = 0
        while (!stopped) {
            val me = uin
            var c: MqttClient? = null
            try {
                // A timestamp suffix keeps a fast reconnect from being seen as
                // a duplicate client id by the broker.
                c = MqttClient(
                    currentBroker,
                    "meshlink-$me-${System.currentTimeMillis() % 1_000_000}",
                    MemoryPersistence(),
                )
                c.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        if (stopped) return
                        isConnected = true
                        lastError = ""
                        // cleanSession=true drops subscriptions on reconnect.
                        c?.let { resubscribe(it) }
                        Log.i(TAG, "connected to $serverURI (reconnect=$reconnect)")
                    }

                    override fun connectionLost(cause: Throwable?) {
                        isConnected = false
                        Log.w(TAG, "connection lost: ${cause?.message}")
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        handleMessage(topic, message)
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
                })
                val options = MqttConnectOptions().apply {
                    isAutomaticReconnect = false
                    isCleanSession = true
                    connectionTimeout = 15
                    keepAliveInterval = 30
                }
                c.connect(options)
                if (stopped) break
                client = c
                isConnected = true
                failedAttempts = 0
                resubscribe(c)
                Log.i(TAG, "online mode ready on $currentBroker as $me")
                // Stay here until the socket drops or the user switches off.
                while (!stopped && c.isConnected) Thread.sleep(500)
            } catch (t: Throwable) {
                isConnected = false
                lastError = "${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
                Log.w(TAG, "connect failed on $currentBroker: $lastError")
            } finally {
                isConnected = false
                if (client === c) client = null
                runCatching { c?.disconnectForcibly(0, 0, false) }
                runCatching { c?.close() }
            }
            if (!stopped) {
                failedAttempts++
                if (failedAttempts % 2 == 0) rotateBroker()
                // 2 s, 4 s, 8 s, 15 s (max) plus jitter. Hammering a public
                // broker with a reconnect every 3 s gets the client throttled.
                val step = (failedAttempts - 1).coerceIn(0, 3)
                val wait = (2_000L shl step).coerceAtMost(15_000L)
                Thread.sleep(wait + (Math.random() * 600).toLong())
            }
        }
    }

    private fun handleMessage(topic: String?, message: MqttMessage?) {
        val t = topic ?: return
        if (t.startsWith("$WEB_TOPIC_ROOT/")) {
            handleWebMessage(t, message)
            return
        }
        val parts = t.split('/')
        // meshlink / v1 / <receiver> / <sender>
        if (parts.size < 4) return
        val receiver = parts[2].uppercase()
        val sender = parts[3].uppercase()
        if (receiver != uin || sender.isBlank()) return
        val blob = message?.payload ?: return
        val plain = runCatching { decrypt(pairKey(uin, sender), blob) }.getOrNull()
        if (plain == null) {
            Log.w(TAG, "dropped unreadable frame from $sender")
            return
        }
        onFrame?.invoke(sender, plain)
    }

    /** Converts a web-app JSON message into a normal MeshLink text frame. */
    private fun handleWebMessage(topic: String, message: MqttMessage?) {
        val raw = runCatching { String(message?.payload ?: return, Charsets.UTF_8) }.getOrNull() ?: return
        val wire = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val from = wire.optString("u").replace(Regex("\\D"), "").take(10)
        if (!from.isMeshLinkId()) return
        val data = if (wire.has("e")) {
            val blob = runCatching { Base64.decode(wire.optString("e"), Base64.NO_WRAP) }.getOrNull()
                ?: return
            val plain = OnlineCrypto.decrypt(OnlineCrypto.pairKey(uin, from), blob) ?: return
            runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull() ?: return
        } else wire
        val selfSync = data.optBoolean("s", false) || data.optString("s") == "1"
        if (selfSync) {
            // A message typed in the paired web app. Show it in the phone's
            // chat list as an outgoing message; do not deliver it again.
            val peer = data.optString("d").replace(Regex("\\D"), "").take(10)
            val body = data.optString("b")
            if (data.optString("o") == "web" && peer.isMeshLinkId() && body.isNotBlank()) {
                onCompanionForMe?.invoke(
                    peer,
                    data.optString("i").take(80),
                    body,
                    data.optString("n").take(48),
                )
            }
            return
        }
        if (from == uin) return
        val body = data.optString("b")
        if (body.isBlank()) return
        val payload = JSONObject()
            .put("i", data.optString("i").take(80))
            .put("b", body)
            .put("n", data.optString("n").take(48))
            .put("u", from)
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (payload.size > MeshFrame.MAX_PAYLOAD) return
        val frame = MeshFrame.Frame(
            type = MeshFrame.TYPE_MESSAGE,
            sourceNodeId = MeshFrame.nodeId16(from),
            destNodeId = MeshFrame.nodeId16(uin),
            ttl = 1,
            payload = payload,
        ).serialize()
        onFrame?.invoke(from, frame)
    }

    // -------------------------------------------------------------- crypto

    private fun pairKey(a: String, b: String): SecretKeySpec = OnlineCrypto.pairKey(a, b)

    private fun encrypt(key: SecretKeySpec, plain: ByteArray): ByteArray =
        OnlineCrypto.encrypt(key, plain, random)

    private fun decrypt(key: SecretKeySpec, blob: ByteArray): ByteArray? =
        OnlineCrypto.decrypt(key, blob)
}

/**
 * AES-GCM payload protection for online mode.
 *
 * The key comes from both UINs, so the relay only ever sees ciphertext.
 * Kept in its own object so the maths can be unit tested on the JVM.
 */
object OnlineCrypto {
    private const val BLOB_VERSION: Byte = 1
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_SALT = "meshlink-online-v1"

    fun pairKey(a: String, b: String): SecretKeySpec {
        val pair = if (a <= b) "$a|$b" else "$b|$a"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$KEY_SALT|$pair".toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest, "AES")
    }

    /** [1 byte version][12 byte IV][AES-GCM ciphertext+tag] */
    fun encrypt(key: SecretKeySpec, plain: ByteArray, random: SecureRandom): ByteArray {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val body = cipher.doFinal(plain)
        return ByteArray(1 + IV_BYTES + body.size).apply {
            this[0] = BLOB_VERSION
            iv.copyInto(this, 1)
            body.copyInto(this, 1 + IV_BYTES)
        }
    }

    fun decrypt(key: SecretKeySpec, blob: ByteArray): ByteArray? {
        if (blob.size <= 1 + IV_BYTES) return null
        if (blob[0] != BLOB_VERSION) return null
        return runCatching {
            val iv = blob.copyOfRange(1, 1 + IV_BYTES)
            val body = blob.copyOfRange(1 + IV_BYTES, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(body)
        }.getOrNull()
    }
}
