package com.meshlink.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.meshlink.app.MainActivity
import com.meshlink.app.R
import com.meshlink.app.data.Chat
import com.meshlink.app.data.ChatRepository
import com.meshlink.app.data.MeshSettings
import com.meshlink.app.data.Message
import com.meshlink.app.data.SessionStore
import com.meshlink.app.mesh.CallManager
import com.meshlink.app.mesh.CallUiState
import com.meshlink.app.mesh.MeshEngine
import com.meshlink.app.mesh.OnlineCallManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Tracks whether an activity is visible, so we only notify in the background. */
object AppForeground {
    @Volatile
    var active: Boolean = false
}

/**
 * Foreground service that keeps every radio alive while the app is in the
 * background: cellular radio (SMS), Bluetooth LE, and Wi-Fi Direct.
 */
class MeshService : Service() {

    companion object {
        private const val CHANNEL_MESH = "meshlink_mesh"
        private const val CHANNEL_MESSAGES = "meshlink_messages"
        private const val CHANNEL_CALLS = "meshlink_calls"

        private const val NOTIFICATION_ID = 1001
        private const val MESSAGE_NOTIFICATION_ID = 1002
        private const val CALL_NOTIFICATION_ID = 1003

        /** Store-and-forward: retry pending messages while the service runs. */
        private const val RETRY_INTERVAL_MS = 45_000L

        const val ACTION_START = "com.meshlink.app.START_MESH"
        const val ACTION_STOP = "com.meshlink.app.STOP_MESH"
        const val ACTION_REPLY = "com.meshlink.app.REPLY"
        const val EXTRA_UIN = "uin"
        const val EXTRA_CHAT_ID = "chat_id"
        private const val KEY_TEXT_REPLY = "meshlink_reply"

        @Volatile
        var engine: MeshEngine? = null
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var retryJob: Job? = null
    private val flushGate = AtomicBoolean(false)
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        ChatRepository.init(this)

        val mesh = MeshEngine(applicationContext)
        engine = mesh
        SessionStore.uin(this)?.let { mesh.configure(it) }

        mesh.onMessageForMe = { from, id, body, senderName, transport, senderPhone ->
            ChatRepository.onIncoming(from, id, body, transport.name, senderName, senderPhone)
        }
        mesh.onCompanionForMe = { peer, id, body, senderName ->
            ChatRepository.onCompanionSent(peer, id, body, senderName)
        }
        mesh.onAckFor = { id -> ChatRepository.onDelivered(id) }
        mesh.onTyping = { from, typing -> ChatRepository.setTyping(from, typing) }
        mesh.onFileForMe = { from, id, name, mime, path, size, transport, senderName ->
            ChatRepository.onIncomingFile(
                from, id, name, mime, path, size, transport.name, senderName,
            )
        }

        CallManager.attach(this, mesh)
        OnlineCallManager.attach(this)
        OnlineCallManager.sendSignal = { peer, message ->
            mesh.sendOnlineCallSignal(peer, message)
        }
        mesh.onCallSignal = { from, message ->
            OnlineCallManager.onSignal(from, message)
        }
        CallManager.onIncomingCall = { state -> notifyIncomingCall(state) }
        CallManager.onCallEnded = { cancelCallNotification() }

        ChatRepository.onNewMessage = { message, chat -> notifyMessage(message, chat) }

        mesh.start()

        retryJob = serviceScope.launch {
            var ticks = 0
            while (true) {
                delay(10_000L)
                ticks++
                runCatching { updateQueueNotification() }
                if (ticks * 10_000L >= RETRY_INTERVAL_MS) {
                    ticks = 0
                    runCatching { ChatRepository.retryPending() }
                }
            }
        }
        registerConnectivityCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REPLY -> {
                val peer = intent.getStringExtra(EXTRA_CHAT_ID).orEmpty()
                val reply = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_TEXT_REPLY)
                    ?.toString()
                    .orEmpty()
                if (peer.isNotBlank() && reply.isNotBlank()) {
                    val name = SessionStore.displayName(this)
                    ChatRepository.sendText(peer, reply, name)
                    ChatRepository.retryPending()
                }
                getSystemService(NotificationManager::class.java)
                    ?.cancel(MESSAGE_NOTIFICATION_ID)
                startForeground(NOTIFICATION_ID, buildNotification())
                return START_NOT_STICKY
            }

            ACTION_STOP -> {
                engine?.stop()
                CallManager.detach()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                intent?.getStringExtra(EXTRA_UIN)?.let { engine?.configure(it) }
                // App relaunched or service restarted: clear the queue right away.
                ChatRepository.retryPending()
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        retryJob?.cancel()
        retryJob = null
        runCatching {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        }
        networkCallback = null
        connectivityManager = null
        engine?.stop()
        engine = null
        super.onDestroy()
    }

    /**
     * WhatsApp-style store-and-forward: the moment the phone gets a usable
     * internet connection, kick the relay and flush every queued message.
     * ChatRepository has its own gate, so the 45 s timer cannot double-send.
     */
    private fun registerConnectivityCallback() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        connectivityManager = manager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                flushQueue("network available")
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                val usable = networkCapabilities
                    .hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    networkCapabilities
                        .hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (usable) flushQueue("internet validated")
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onFailure { android.util.Log.w("MeshService", "network callback failed", it) }
        networkCallback = callback
    }

    private fun flushQueue(reason: String) {
        if (!flushGate.compareAndSet(false, true)) return
        serviceScope.launch {
            try {
                android.util.Log.d("MeshService", "Flushing queue: $reason")
                delay(1_200)
                // Give the relay a head start; a queued message must not wait
                // for the next 45 s polling round.
                engine?.restartOnline()
                delay(1_000)
                ChatRepository.retryPending()
            } finally {
                delay(2_000)
                flushGate.set(false)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------- channels

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESH,
                getString(R.string.mesh_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.mesh_channel_description)
                setShowBadge(false)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "New messages arriving over the radio mesh" }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALLS,
                "Calls",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Incoming Mhzaly calls" }
        )
    }

    private fun buildNotification(
        text: String = getString(R.string.mesh_notification_text),
    ): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_MESH)
            .setContentTitle(getString(R.string.mesh_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_meshlink_notification)
            .setOngoing(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** Shows how many messages are still waiting, even when the app is closed. */
    private fun updateQueueNotification() {
        val pending = ChatRepository.pendingCount()
        val text = if (pending > 0) {
            getString(R.string.mesh_notification_pending, pending)
        } else {
            getString(R.string.mesh_notification_text)
        }
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun notifyMessage(message: Message, chat: Chat) {
        if (AppForeground.active) return
        if (MeshSettings.isMuted(this, chat.peer)) return
        val open = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val replyLabel = getString(R.string.notification_reply)
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel(replyLabel)
            .build()
        val replyIntent = Intent(this, MeshService::class.java)
            .setAction(ACTION_REPLY)
            .putExtra(EXTRA_CHAT_ID, chat.peer)
        val replyPending = PendingIntent.getService(
            this,
            chat.peer.hashCode(),
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_meshlink_notification,
            replyLabel,
            replyPending,
        ).addRemoteInput(remoteInput).build()
        val notification = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setContentTitle(chat.name.ifBlank { chat.peer })
            .setContentText(message.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
            .setSmallIcon(R.drawable.ic_meshlink_notification)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(replyAction)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java)
            ?.notify(MESSAGE_NOTIFICATION_ID, notification)
    }

    private fun notifyIncomingCall(state: CallUiState) {
        val open = PendingIntent.getActivity(
            this,
            11,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_CALLS)
            .setContentTitle("Incoming Mhzaly call")
            .setContentText("${state.name.ifBlank { state.peer }} is calling over Wi-Fi Direct")
            .setSmallIcon(R.drawable.ic_meshlink_notification)
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
        getSystemService(NotificationManager::class.java)
            ?.notify(CALL_NOTIFICATION_ID, notification)
    }

    private fun cancelCallNotification() {
        getSystemService(NotificationManager::class.java)?.cancel(CALL_NOTIFICATION_ID)
    }
}

/** Convenience starter used by the UI. */
fun startMeshService(context: Context, uin: String?) {
    val intent = Intent(context, MeshService::class.java).apply {
        action = MeshService.ACTION_START
        putExtra(MeshService.EXTRA_UIN, uin)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

/** Stops the mesh (used when the user logs out). */
fun stopMeshService(context: Context) {
    val intent = Intent(context, MeshService::class.java).apply {
        action = MeshService.ACTION_STOP
    }
    runCatching { context.startService(intent) }
}
