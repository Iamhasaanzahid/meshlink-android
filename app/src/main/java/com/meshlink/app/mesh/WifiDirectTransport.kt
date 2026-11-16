package com.meshlink.app.mesh

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * WI-FI DIRECT TRANSPORT — 2.4/5 GHz radio, no internet, no router.
 *
 * Forms an ad-hoc Wi-Fi Direct group between phones and exchanges mesh
 * frames over persistent TCP sockets. Works completely offline and has
 * longer range + higher throughput than BLE (good for images and voice).
 */
@SuppressLint("MissingPermission")
class WifiDirectTransport(private val context: Context) {

    companion object {
        private const val TAG = "MeshLinkWifiDirect"
        const val PORT = 8988
        private const val RECONNECT_COOLDOWN_MS = 15_000L
    }

    /** A live socket plus its framed writer. */
    private class Connection(val socket: Socket) {
        val out: DataOutputStream = DataOutputStream(socket.getOutputStream())
        val lock = Any()

        fun write(frame: ByteArray): Boolean = try {
            synchronized(lock) {
                out.writeShort(frame.size)
                out.write(frame)
                out.flush()
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "frame write failed", t)
            false
        }

        fun close() = runCatching { socket.close() }
    }

    var onFrame: ((peerAddress: String, bytes: ByteArray) -> Unit)? = null
    var onPeersChanged: ((List<WifiP2pDevice>) -> Unit)? = null
    var onGroupChanged: ((isGroupOwner: Boolean, ownerIp: String?) -> Unit)? = null

    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Connection? = null

    private val peers = LinkedHashMap<String, WifiP2pDevice>()
    private val connections = CopyOnWriteArrayList<Connection>()

    @Volatile
    private var lastKnownInfo: WifiP2pInfo? = null

    @Volatile
    private var lastConnectAttempt = 0L

    /** Connect automatically to the first peer found when no group exists yet. */
    var autoConnect: Boolean = true

    val isSupported: Boolean get() = manager != null

    /** IP of the Wi-Fi Direct group owner, or null when no group is formed. */
    val groupOwnerIp: String?
        get() = lastKnownInfo?.takeIf { it.groupFormed }?.groupOwnerAddress?.hostAddress

    val isGroupOwner: Boolean
        get() = lastKnownInfo?.groupFormed == true && lastKnownInfo?.isGroupOwner == true

    val hasGroup: Boolean get() = lastKnownInfo?.groupFormed == true

    fun peerDevices(): List<WifiP2pDevice> = peers.values.toList()

    val connectedPeerCount: Int get() = connections.size

    // ------------------------------------------------------------- lifecycle

    fun start() {
        val m = manager ?: return
        channel = m.initialize(context, Looper.getMainLooper()) { }
        registerReceiver()
        startServer()
        discover()
    }

    fun stop() {
        runCatching { receiver?.let { context.unregisterReceiver(it) } }
        receiver = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        clientSocket?.close()
        clientSocket = null
        connections.forEach { it.close() }
        connections.clear()
        runCatching { channel?.let { manager?.stopPeerDiscovery(it, null) } }
        runCatching { channel?.let { manager?.removeGroup(it, null) } }
        lastKnownInfo = null
    }

    // -------------------------------------------------------------- discovery

    fun discover() {
        val m = manager ?: return
        val c = channel ?: return
        m.discoverPeers(c, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.i(TAG, "peer discovery started") }
            override fun onFailure(reason: Int) { Log.w(TAG, "peer discovery failed: $reason") }
        })
    }

    fun connect(device: WifiP2pDevice) {
        connect(device.deviceAddress)
    }

    fun connect(deviceAddress: String) {
        val m = manager ?: return
        val c = channel ?: return
        lastConnectAttempt = System.currentTimeMillis()
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
        }
        m.connect(c, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.i(TAG, "connecting to $deviceAddress") }
            override fun onFailure(reason: Int) { Log.w(TAG, "connect failed: $reason") }
        })
    }

    /** Leave the current group so another phone can become owner if needed. */
    fun disconnectGroup() {
        val m = manager ?: return
        val c = channel ?: return
        lastKnownInfo = null
        connections.forEach { it.close() }
        connections.clear()
        runCatching { m.removeGroup(c, null) }
    }

    // -------------------------------------------------------------- broadcast

    private fun registerReceiver() {
        if (receiver != null) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val info = intent.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                        updateGroupInfo(info)
                    }
                }
            }
        }
        context.registerReceiver(receiver, filter)
    }

    private fun requestPeers() {
        val m = manager ?: return
        val c = channel ?: return
        m.requestPeers(c) { list ->
            peers.clear()
            list?.deviceList?.forEach { peers[it.deviceAddress] = it }
            onPeersChanged?.invoke(peers.values.toList())
            maybeAutoConnect()
        }
    }

    private fun maybeAutoConnect() {
        if (!autoConnect) return
        if (hasGroup) return
        val target = peers.values.firstOrNull { it.status == WifiP2pDevice.AVAILABLE } ?: return
        val now = System.currentTimeMillis()
        if (now - lastConnectAttempt < RECONNECT_COOLDOWN_MS) return
        connect(target)
    }

    // ------------------------------------------------------------------ socket

    private fun startServer() {
        if (serverSocket != null) return
        thread(name = "meshlink-wifidirect-server", isDaemon = true) {
            try {
                val ss = ServerSocket(PORT)
                serverSocket = ss
                while (!ss.isClosed) {
                    val socket = ss.accept()
                    handleSocket(socket)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "server socket stopped", t)
            }
        }
    }

    private fun connectToOwner(ownerIp: String) {
        val existing = clientSocket
        if (existing != null && !existing.socket.isClosed) return
        thread(name = "meshlink-wifidirect-client", isDaemon = true) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ownerIp, PORT), 5000)
                val conn = Connection(socket)
                clientSocket = conn
                readLoop(conn)
            } catch (t: Throwable) {
                Log.w(TAG, "client socket failed", t)
            }
        }
    }

    private fun handleSocket(socket: Socket) {
        val conn = try { Connection(socket) } catch (t: Throwable) {
            runCatching { socket.close() }
            return
        }
        connections.add(conn)
        thread(name = "meshlink-wifidirect-conn-${socket.port}", isDaemon = true) {
            readLoop(conn)
        }
    }

    private fun readLoop(conn: Connection) {
        try {
            val input = DataInputStream(conn.socket.getInputStream())
            val address = conn.socket.inetAddress?.hostAddress ?: "unknown"
            while (!conn.socket.isClosed) {
                val len = input.readUnsignedShort()
                if (len <= 0) break
                val buffer = ByteArray(len)
                input.readFully(buffer, 0, len)
                onFrame?.invoke(address, buffer)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "socket read ended", t)
        } finally {
            connections.remove(conn)
            if (clientSocket === conn) clientSocket = null
            conn.close()
        }
    }

    // ------------------------------------------------------------------- group

    fun updateGroupInfo(info: WifiP2pInfo?) {
        lastKnownInfo = info
        if (info?.groupFormed == true) {
            val ownerIp = info.groupOwnerAddress?.hostAddress
            onGroupChanged?.invoke(info.isGroupOwner, ownerIp)
            if (!info.isGroupOwner && ownerIp != null) {
                connectToOwner(ownerIp)
            }
        }
    }

    // -------------------------------------------------------------------- send

    /**
     * Sends a frame to every connected peer (group owner) or to the group
     * owner when this phone is a client. Returns true if at least one write
     * succeeded.
     */
    fun send(frame: ByteArray): Boolean {
        if (isGroupOwner) {
            var sent = connections.count { it.write(frame) } > 0
            // A lone group owner with no client yet still succeeds for SMS fallback.
            return sent
        }
        val owner = groupOwnerIp
        if (owner != null) {
            val active = clientSocket
            if (active != null && !active.socket.isClosed && active.write(frame)) return true
            connectToOwner(owner)
            // Give the socket a short moment to come up.
            val deadline = System.currentTimeMillis() + 800
            while (System.currentTimeMillis() < deadline) {
                val conn = clientSocket
                if (conn != null && !conn.socket.isClosed && conn.write(frame)) return true
                Thread.sleep(25)
            }
        }
        // Fall back to a one-shot socket if no persistent link exists yet.
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(owner ?: return false, PORT), 2000)
                val out = DataOutputStream(socket.getOutputStream())
                out.writeShort(frame.size)
                out.write(frame)
                out.flush()
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "send failed", t)
            false
        }
    }
}
