package com.meshlink.app.data

import android.content.Context
import com.meshlink.app.service.MeshService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Single source of truth for chats, messages, calls and contacts.
 *
 * Everything is stored on the device. Outgoing messages are handed to
 * [MeshService.engine], which tries Wi-Fi Direct, then Bluetooth LE, then the
 * cellular radio (SMS). No internet connection is ever required.
 */
object ChatRepository {

    private const val COLORS_COUNT = 8

    private lateinit var db: MeshDatabase
    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var initialised = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    val chats: StateFlow<List<Chat>> = _chats.asStateFlow()

    private val _calls = MutableStateFlow<List<CallLog>>(emptyList())
    val calls: StateFlow<List<CallLog>> = _calls.asStateFlow()

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val messageFlows = HashMap<String, MutableStateFlow<List<Message>>>()
    private val messageLock = Any()

    /** Fired for every message that arrives while the app is in the background. */
    @Volatile
    var onNewMessage: ((Message, Chat) -> Unit)? = null

    // ------------------------------------------------------------------ setup

    fun init(context: Context) {
        if (initialised) return
        synchronized(this) {
            if (initialised) return
            db = MeshDatabase(context.applicationContext)
            appContext = context.applicationContext
            initialised = true
        }
        scope.launch {
            refreshChats()
            refreshCalls()
            refreshContacts()
        }
    }

    fun messagesFor(chatId: String): StateFlow<List<Message>> = synchronized(messageLock) {
        messageFlows.getOrPut(chatId) { MutableStateFlow(readMessages(chatId)) }
    }

    // ------------------------------------------------------------------ chats

    fun refreshChats() {
        val list = ArrayList<Chat>()
        val c = db.readableDatabase.rawQuery(
            "SELECT peer, name, last_message, last_ts, unread, last_transport FROM chats ORDER BY last_ts DESC",
            null,
        )
        c.use {
            while (it.moveToNext()) {
                list += Chat(
                    peer = it.getString(0),
                    name = it.getString(1),
                    lastMessage = it.getString(2),
                    lastTimestamp = it.getLong(3),
                    unread = it.getInt(4),
                    lastTransport = it.getString(5),
                )
            }
        }
        _chats.value = list
    }

    fun markRead(peer: String) {
        scope.launch {
            db.writableDatabase.execSQL("UPDATE chats SET unread = 0 WHERE peer = ?", arrayOf(peer))
            refreshChats()
        }
    }

    fun clearChat(peer: String) {
        scope.launch {
            db.writableDatabase.execSQL("DELETE FROM messages WHERE chat_id = ?", arrayOf(peer))
            db.writableDatabase.execSQL("DELETE FROM chats WHERE peer = ?", arrayOf(peer))
            refreshMessages(peer)
            refreshChats()
        }
    }

    // --------------------------------------------------------------- messages

    private fun readMessages(chatId: String): List<Message> {
        val list = ArrayList<Message>()
        val c = db.readableDatabase.rawQuery(
            "SELECT id, chat_id, from_me, body, ts, transport, status FROM messages " +
                "WHERE chat_id = ? ORDER BY ts ASC",
            arrayOf(chatId),
        )
        c.use {
            while (it.moveToNext()) {
                list += Message(
                    id = it.getString(0),
                    chatId = it.getString(1),
                    fromMe = it.getInt(2) == 1,
                    body = it.getString(3),
                    timestamp = it.getLong(4),
                    transport = it.getString(5),
                    status = runCatching { MsgStatus.valueOf(it.getString(6)) }
                        .getOrDefault(MsgStatus.SENT),
                )
            }
        }
        return list
    }

    private fun refreshMessages(chatId: String) {
        synchronized(messageLock) {
            messageFlows.getOrPut(chatId) { MutableStateFlow(emptyList()) }.value =
                readMessages(chatId)
        }
    }

    /** Send a text message. Returns the queued message immediately. */
    fun sendText(peer: String, body: String, name: String = ""): Message? {
        val target = peer.trim()
        val text = body.trim()
        if (target.isEmpty() || text.isEmpty()) return null

        val msg = Message(
            id = UUID.randomUUID().toString(),
            chatId = target,
            fromMe = true,
            body = text,
            timestamp = System.currentTimeMillis(),
            transport = "AUTO",
            status = MsgStatus.SENDING,
        )
        scope.launch {
            insertMessage(msg)
            touchChat(target, resolveName(target, name), text, msg.timestamp, "AUTO", 0)
            refreshMessages(target)
            refreshChats()
            dispatch(msg)
        }
        return msg
    }

    /** Re-queue a failed message. */
    fun retry(message: Message) {
        scope.launch {
            updateStatus(message.id, MsgStatus.SENDING)
            refreshMessages(message.chatId)
            dispatch(message.copy(status = MsgStatus.SENDING))
        }
    }

    /**
     * Store-and-forward: tries again for messages that were handed to no
     * radio, or that were sent but never acknowledged. Called periodically by
     * the foreground service, so a message keeps trying while the phone moves
     * between people instead of dying after one attempt.
     *
     * SMS is never used here: automatic retries must not cost the user money.
     * A message that can only travel by SMS stays failed until the user taps
     * retry.
     */
    fun retryPending() {
        scope.launch {
            val pending = ArrayList<Message>()
            val since = System.currentTimeMillis() - 1000L * 60 * 60 * 24
            db.readableDatabase.rawQuery(
                "SELECT id, chat_id, body, timestamp FROM messages " +
                    "WHERE from_me = 1 AND status IN (?, ?) AND timestamp >= ? " +
                    "ORDER BY timestamp LIMIT 30",
                arrayOf(MsgStatus.FAILED.name, MsgStatus.SENT.name, since.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    pending += Message(
                        id = c.getString(0),
                        chatId = c.getString(1),
                        fromMe = true,
                        body = c.getString(2),
                        timestamp = c.getLong(3),
                        transport = "AUTO",
                        status = MsgStatus.SENT,
                    )
                }
            }
            if (pending.isEmpty()) return@launch
            updateStatusBulk(pending.map { it.id }, MsgStatus.SENDING)
            for (m in pending) {
                dispatch(m, allowSms = false)
            }
            pending.map { it.chatId }.distinct().forEach {
                refreshMessages(it)
                refreshChats()
            }
        }
    }

    private fun updateStatusBulk(ids: List<String>, status: MsgStatus) {
        if (ids.isEmpty()) return
        db.writableDatabase.beginTransaction()
        try {
            ids.forEach { updateStatus(it, status) }
            db.writableDatabase.setTransactionSuccessful()
        } finally {
            db.writableDatabase.endTransaction()
        }
    }

    private fun dispatch(message: Message, allowSms: Boolean = true) {
        val engine = MeshService.engine
        val displayName = appContext?.let { SessionStore.displayName(it) } ?: ""
        val ok = engine?.sendMessage(
            destination = message.chatId,
            messageId = message.id,
            body = message.body,
            senderName = displayName,
            allowSms = allowSms,
        ) ?: false

        val status = if (ok) MsgStatus.SENT else MsgStatus.FAILED
        updateStatus(message.id, status)
        refreshMessages(message.chatId)
        refreshChats()
    }

    /** Called by the mesh when a message arrives. */
    fun onIncoming(from: String, messageId: String, body: String, transport: String, senderName: String) {
        val peer = from.trim()
        if (peer.isEmpty() || body.isEmpty()) return

        // Ignore exact duplicates (same radio frame seen twice).
        if (exists(messageId)) return

        val msg = Message(
            id = messageId,
            chatId = peer,
            fromMe = false,
            body = body,
            timestamp = System.currentTimeMillis(),
            transport = transport,
            status = MsgStatus.RECEIVED,
        )
        scope.launch {
            insertMessage(msg)
            touchChat(peer, resolveName(peer, senderName), body, msg.timestamp, transport, 1)
            refreshMessages(peer)
            refreshChats()
            val chat = _chats.value.firstOrNull { it.peer == peer }
            if (chat != null) onNewMessage?.invoke(msg, chat)
        }
    }

    /** The other phone confirmed it received [messageId] (double tick). */
    fun onDelivered(messageId: String) {
        scope.launch {
            db.writableDatabase.execSQL(
                "UPDATE messages SET status = ? WHERE id = ? AND status != ?",
                arrayOf(MsgStatus.DELIVERED.name, messageId, MsgStatus.RECEIVED.name),
            )
            val chatId = db.readableDatabase.rawQuery(
                "SELECT chat_id FROM messages WHERE id = ?", arrayOf(messageId),
            ).use { if (it.moveToFirst()) it.getString(0) else null }
            if (chatId != null) refreshMessages(chatId)
        }
    }

    private fun insertMessage(msg: Message) {
        db.writableDatabase.insertWithOnConflict(
            "messages", null,
            MeshDatabase.values(
                "id" to msg.id,
                "chat_id" to msg.chatId,
                "from_me" to msg.fromMe,
                "body" to msg.body,
                "ts" to msg.timestamp,
                "transport" to msg.transport,
                "status" to msg.status.name,
            ),
            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    private fun updateStatus(id: String, status: MsgStatus) {
        db.writableDatabase.execSQL(
            "UPDATE messages SET status = ? WHERE id = ?",
            arrayOf(status.name, id),
        )
    }

    private fun exists(id: String): Boolean =
        db.readableDatabase.rawQuery("SELECT 1 FROM messages WHERE id = ? LIMIT 1", arrayOf(id))
            .use { it.moveToFirst() }

    private fun touchChat(
        peer: String,
        name: String,
        body: String,
        ts: Long,
        transport: String,
        unreadDelta: Int,
    ) {
        val w = db.writableDatabase
        w.execSQL(
            "INSERT OR IGNORE INTO chats(peer, name, last_message, last_ts, unread, last_transport) " +
                "VALUES(?,?,?,?,0,?)",
            arrayOf(peer, name, body, ts, transport),
        )
        w.execSQL(
            "UPDATE chats SET last_message = ?, last_ts = ?, last_transport = ?, " +
                "unread = MAX(0, unread + ?), name = CASE WHEN ? = '' THEN name ELSE ? END " +
                "WHERE peer = ?",
            arrayOf(body, ts, transport, unreadDelta, name, name, peer),
        )
    }

    // ----------------------------------------------------------------- calls

    fun logCall(
        peer: String,
        name: String,
        outgoing: Boolean,
        missed: Boolean,
        transport: String,
        timestamp: Long,
        durationSec: Int,
        status: String,
    ) {
        scope.launch {
            db.writableDatabase.insertWithOnConflict(
                "calls", null,
                MeshDatabase.values(
                    "id" to UUID.randomUUID().toString(),
                    "peer" to peer,
                    "name" to resolveName(peer, name),
                    "outgoing" to outgoing,
                    "missed" to missed,
                    "transport" to transport,
                    "ts" to timestamp,
                    "duration" to durationSec,
                    "status" to status,
                ),
                android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
            )
            refreshCalls()
        }
    }

    fun refreshCalls() {
        val list = ArrayList<CallLog>()
        val c = db.readableDatabase.rawQuery(
            "SELECT id, peer, name, outgoing, missed, transport, ts, duration, status " +
                "FROM calls ORDER BY ts DESC LIMIT 300",
            null,
        )
        c.use {
            while (it.moveToNext()) {
                list += CallLog(
                    id = it.getString(0),
                    peer = it.getString(1),
                    name = it.getString(2),
                    outgoing = it.getInt(3) == 1,
                    missed = it.getInt(4) == 1,
                    transport = it.getString(5),
                    timestamp = it.getLong(6),
                    durationSec = it.getInt(7),
                    status = it.getString(8),
                )
            }
        }
        _calls.value = list
    }

    fun clearCalls() {
        scope.launch {
            db.writableDatabase.execSQL("DELETE FROM calls")
            refreshCalls()
        }
    }

    // -------------------------------------------------------------- contacts

    fun addContact(name: String, address: String) {
        val n = name.trim()
        val a = address.trim()
        if (a.isEmpty()) return
        scope.launch {
            db.writableDatabase.insertWithOnConflict(
                "contacts", null,
                MeshDatabase.values("name" to n.ifEmpty { a }, "address" to a),
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
            )
            refreshContacts()
            refreshChats()
        }
    }

    fun deleteContact(address: String) {
        scope.launch {
            db.writableDatabase.delete("contacts", "address = ?", arrayOf(address))
            refreshContacts()
        }
    }

    fun refreshContacts() {
        val list = ArrayList<Contact>()
        val c = db.readableDatabase.rawQuery(
            "SELECT id, name, address FROM contacts ORDER BY name COLLATE NOCASE ASC", null,
        )
        c.use {
            while (it.moveToNext()) {
                list += Contact(it.getLong(0), it.getString(1), it.getString(2))
            }
        }
        _contacts.value = list
    }

    fun contactName(address: String): String? =
        db.readableDatabase.rawQuery(
            "SELECT name FROM contacts WHERE address = ? LIMIT 1", arrayOf(address),
        ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun resolveName(peer: String, fallback: String): String =
        contactName(peer) ?: fallback.trim().takeIf { it.isNotEmpty() } ?: peer

    // ----------------------------------------------------------------- utils

    fun totalUnread(): Int = _chats.value.sumOf { it.unread }
}
