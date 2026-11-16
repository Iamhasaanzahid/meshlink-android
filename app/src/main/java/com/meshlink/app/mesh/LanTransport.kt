package com.meshlink.app.mesh

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * WI-FI LAN TRANSPORT — any two phones on the same Wi-Fi network or the same
 * phone hotspot, with no internet.
 *
 * This is the "Wi-Fi option": connect both phones to one router, or one phone
 * shares its hotspot and the other joins it, and messages flow over the local
 * network. No mobile data is used and nothing leaves the subnet.
 *
 * Frames are sent as UDP broadcasts on [PORT] and every phone on the network
 * receives them. A random per-run instance id is prefixed to each datagram so
 * a phone ignores its own broadcasts. The mesh router deduplicates and relays
 * exactly like it does for Bluetooth and Wi-Fi Direct.
 *
 * Range: the Wi-Fi coverage area (roughly 30–100 m indoors, more outdoors),
 * and a hotspot chain works without any tower or internet.
 */
class LanTransport(private val context: Context) {

    companion object {
        private const val TAG = "MeshLinkLan"

        /** UDP port used for LAN mesh traffic. */
        const val PORT = 8991

        private const val PEER_TTL_MS = 120_000L
        private const val MAX_DATAGRAM = 1024
        private const val INSTANCE_ID_SIZE = 4
    }

    /** A frame arrived from [from] (the sender's IP address). */
    var onFrame: ((from: String, bytes: ByteArray) -> Unit)? = null

    private val instanceId = ByteArray(INSTANCE_ID_SIZE).also { SecureRandom().nextBytes(it) }
    private val peers = HashMap<String, Long>()

    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    private var running = false

    private var receiveThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    val isSupported: Boolean
        get() = context.getSystemService(Context.WIFI_SERVICE) is WifiManager

    @Synchronized
    fun start() {
        if (running) return
        val s = DatagramSocket(null)
        s.reuseAddress = true
        s.broadcast = true
        s.bind(InetSocketAddress(PORT))
        socket = s
        running = true
        acquireMulticastLock()
        receiveThread = thread(name = "meshlink-lan-rx", isDaemon = true) { receiveLoop(s) }
        Log.i(TAG, "LAN transport listening on UDP $PORT")
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        receiveThread = null
        runCatching { multicastLock?.release() }
        multicastLock = null
        synchronized(peers) { peers.clear() }
    }

    /** Broadcasts one mesh frame to every phone on the local network. */
    fun send(frame: ByteArray): Boolean {
        val s = socket ?: return false
        val packetBytes = ByteArray(INSTANCE_ID_SIZE + frame.size)
        instanceId.copyInto(packetBytes, 0)
        frame.copyInto(packetBytes, INSTANCE_ID_SIZE)

        var ok = false
        broadcastTargets().forEach { address ->
            runCatching {
                s.send(DatagramPacket(packetBytes, packetBytes.size, address, PORT))
                ok = true
            }.onFailure { Log.d(TAG, "broadcast to $address failed: ${it.message}") }
        }
        return ok
    }

    /** Phones heard on this network in the last two minutes. */
    fun peerCount(): Int {
        val now = System.currentTimeMillis()
        synchronized(peers) {
            val it = peers.entries.iterator()
            while (it.hasNext()) {
                if (now - it.next().value > PEER_TTL_MS) it.remove()
            }
            return peers.size
        }
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buffer = ByteArray(MAX_DATAGRAM)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                s.receive(packet)
            } catch (t: Throwable) {
                if (running) Log.d(TAG, "receive stopped: ${t.message}")
                break
            }

            val length = packet.length
            if (length <= INSTANCE_ID_SIZE) continue
            var mine = true
            for (i in 0 until INSTANCE_ID_SIZE) {
                if (buffer[i] != instanceId[i]) {
                    mine = false
                    break
                }
            }
            if (mine) continue

            val from = packet.address?.hostAddress ?: continue
            synchronized(peers) { peers[from] = System.currentTimeMillis() }
            val frame = buffer.copyOfRange(INSTANCE_ID_SIZE, length)
            runCatching { onFrame?.invoke(from, frame) }
        }
    }

    /** Limited broadcast, plus the subnet broadcast when the IP is known. */
    @SuppressLint("MissingPermission")
    private fun broadcastTargets(): List<InetAddress> {
        val targets = ArrayList<InetAddress>(2)
        runCatching { targets += InetAddress.getByName("255.255.255.255") }
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ip = wifi?.connectionInfo?.ipAddress ?: return@runCatching
            if (ip == 0) return@runCatching
            // Android stores the IPv4 address little-endian.
            val bytes = byteArrayOf(
                (ip and 0xFF).toByte(),
                ((ip shr 8) and 0xFF).toByte(),
                ((ip shr 16) and 0xFF).toByte(),
                0xFF.toByte(),
            )
            targets += InetAddress.getByAddress(bytes)
        }
        return targets
    }

    private fun acquireMulticastLock() {
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("meshlink-lan")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.d(TAG, "multicast lock unavailable: ${it.message}") }
    }
}
