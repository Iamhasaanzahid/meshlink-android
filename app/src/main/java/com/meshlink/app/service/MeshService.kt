package com.meshlink.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
        const val EXTRA_UIN = "uin"

        @Volatile
        var engine: MeshEngine? = null
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var retryJob: Job? = null

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
            while (true) {
                delay(RETRY_INTERVAL_MS)
                runCatching { ChatRepository.retryPending() }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                engine?.stop()
                CallManager.detach()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                intent?.getStringExtra(EXTRA_UIN)?.let { engine?.configure(it) }
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        retryJob?.cancel()
        retryJob = null
        engine?.stop()
        engine = null
        super.onDestroy()
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
            ).apply { description = "Incoming MeshLink calls" }
        )
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_MESH)
            .setContentTitle(getString(R.string.mesh_notification_title))
            .setContentText(getString(R.string.mesh_notification_text))
            .setSmallIcon(R.drawable.ic_meshlink_notification)
            .setOngoing(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
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
        val notification = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setContentTitle(chat.name.ifBlank { chat.peer })
            .setContentText(message.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
            .setSmallIcon(R.drawable.ic_meshlink_notification)
            .setAutoCancel(true)
            .setContentIntent(open)
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
            .setContentTitle("Incoming MeshLink call")
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
