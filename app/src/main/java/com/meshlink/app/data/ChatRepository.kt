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
    private const val MAX_MEDIA_BYTES = 3L * 1024 * 1024

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

    /** Upload progress for photos / documents, keyed by message id (0..100). */
    private val _transferProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val transferProgress: StateFlow<Map<String, Int>> = _transferProgress.asStateFlow()

    /** Peers currently typing, keyed by UIN with the time we last heard it. */
    private val _typing = MutableStateFlow<Map<String, Long>>(emptyMap())
    val typing: StateFlow<Map<String, Long>> = _typing.asStateFlow()

    fun setTyping(peer: String, typing: Boolean) {
        val key = peer.trim().uppercase()
        if (key.isEmpty()) return
        _typing.value = if (typing) _typing.value + (key to System.currentTimeMillis())
        else _typing.value - key
    }

    /** Fired for every message that arrives while the app is in the background. */
    @Volatile
    var onNewMessage: ((Message, Chat) -> Unit)? = null

    // ------------------------------------------------------------------ setup

    fun init(context: Context) {
        if (initialised) return
        synchronized(this) {
            if (initialised) return
            db = MeshDatabase(context.applicationContext)
            GroupStore.init(context)
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
            if (!GroupStore.isGroup(peer)) sendReadReceipts(peer)
        }
    }

    /** Tell the sender that their messages were actually opened. */
    private fun sendReadReceipts(peer: String) {
        if (peer.isBlank()) return
        val ids = ArrayList<String>()
        db.readableDatabase.rawQuery(
            "SELECT id FROM messages WHERE chat_id = ? AND from_me = 0 AND status = ?",
            arrayOf(peer, MsgStatus.RECEIVED.name),
        ).use { while (it.moveToNext()) ids.add(it.getString(0)) }
        if (ids.isEmpty()) return
        val sender = appContext?.let { SessionStore.displayName(it) }.orEmpty()
        ids.forEach { id ->
            MeshService.engine?.sendMessage(
                destination = peer,
                messageId = UUID.randomUUID().toString(),
                body = "MLRD|$id",
                senderName = sender,
                allowSms = false,
            )
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
            "SELECT id, chat_id, from_me, body, ts, transport, status, kind, media_path, media_mime, media_name, media_size, reaction FROM messages " +
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
                    kind = runCatching { MsgKind.valueOf(it.getString(7)) }
                        .getOrDefault(MsgKind.TEXT),
                    mediaPath = it.getString(8),
                    mediaMime = it.getString(9),
                    mediaName = it.getString(10),
                    mediaSize = it.getLong(11),
                    reaction = it.getString(12) ?: "",
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
        if (GroupStore.isGroup(target)) return sendGroupText(target, text, name)

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

    /**
     * Queue a photo, document or voice note.
     *
     * [file] must already live in app storage (the picker copies it there), so
     * the message keeps working after the original content URI is gone.
     * Files larger than 3 MB are refused: the mesh radios cannot carry them in
     * a reasonable time and the receiver caps reassembly at 3.6 MB.
     */
    fun sendMedia(
        peer: String,
        file: java.io.File,
        kind: MsgKind,
        mime: String,
        displayName: String,
        contactName: String = "",
    ): Message? {
        val target = peer.trim()
        if (target.isEmpty() || !file.exists() || file.length() > MAX_MEDIA_BYTES) return null

        val preview = previewFor(kind, displayName)
        val msg = Message(
            id = UUID.randomUUID().toString(),
            chatId = target,
            fromMe = true,
            body = preview,
            timestamp = System.currentTimeMillis(),
            transport = "AUTO",
            status = MsgStatus.SENDING,
            kind = kind,
            mediaPath = file.absolutePath,
            mediaMime = mime,
            mediaName = displayName,
            mediaSize = file.length(),
        )
        scope.launch {
            insertMessage(msg)
            touchChat(target, resolveName(target, contactName), preview, msg.timestamp, "AUTO", 0)
            refreshMessages(target)
            refreshChats()
            if (GroupStore.isGroup(target)) dispatchGroupMedia(msg) else dispatchMedia(msg)
        }
        return msg
    }

    /** Send an emoji reaction over the same path as the original chat. */
    fun sendReaction(message: Message, emoji: String) {
        val targetChat = message.chatId
        val targets = if (GroupStore.isGroup(targetChat)) {
            GroupStore.get(targetChat)?.members?.toList().orEmpty()
        } else listOf(targetChat)
        if (targets.isEmpty()) return
        val wire = "MLR1|$targetChat|${message.id}|${emoji.take(8)}"
        val sender = appContext?.let { SessionStore.displayName(it) }.orEmpty()
        targets.forEach { peer ->
            scope.launch {
                MeshService.engine?.sendMessage(
                    destination = peer,
                    messageId = UUID.randomUUID().toString(),
                    body = wire,
                    senderName = sender,
                    allowSms = false,
                )
            }
        }
        scope.launch {
            applyReaction(message.id, emoji.take(8))
        }
    }

    private fun applyReaction(messageId: String, emoji: String) {
        val c = db.readableDatabase.rawQuery(
            "SELECT chat_id FROM messages WHERE id = ? LIMIT 1", arrayOf(messageId),
        )
        val chatId = c.use { if (it.moveToFirst()) it.getString(0) else null } ?: return
        db.writableDatabase.execSQL(
            "UPDATE messages SET reaction = ? WHERE id = ?", arrayOf(emoji, messageId),
        )
        refreshMessages(chatId)
    }

    /** Create a group and invite each member over the online relay / mesh. */
    fun createGroup(name: String, membersCsv: String): String {
        val title = name.trim().take(40).ifBlank { "New group" }
        val members = membersCsv.split(',', ';', ' ', '\n')
            .map { it.trim().uppercase() }.filter { it.startsWith("OFF-") }.distinct().toSet()
        val id = "GRP-" + UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
        GroupStore.save(id, title, members)
        val ts = System.currentTimeMillis()
        scope.launch {
            touchChat(id, title, "Group created · ${members.size} member(s)", ts, "AUTO", 0)
            refreshChats()
        }
        val invite = "MLGINV|$id|$title|${members.joinToString(",")}"
        val displayName = appContext?.let { SessionStore.displayName(it) }.orEmpty()
        val engine = MeshService.engine
        members.forEach { member ->
            engine?.sendMessage(member, UUID.randomUUID().toString(), invite, displayName, false)
        }
        return id
    }

    private fun sendGroupText(groupId: String, text: String, name: String): Message? {
        val group = GroupStore.get(groupId) ?: return null
        val msg = Message(UUID.randomUUID().toString(), groupId, true, text,
            System.currentTimeMillis(), "AUTO", MsgStatus.SENDING)
        scope.launch {
            insertMessage(msg)
            touchChat(groupId, group.name, text, msg.timestamp, "AUTO", 0)
            refreshMessages(groupId)
            refreshChats()
            val sender = appContext?.let { SessionStore.displayName(it) }
                ?.takeIf { it.isNotBlank() } ?: groupId
            val wire = "MLG1|$groupId|${group.name}|$sender|$text"
            var delivered = false
            group.members.forEach { member ->
                val ok = MeshService.engine?.sendMessage(
                    member, UUID.randomUUID().toString(), wire, sender, false,
                ) ?: false
                if (ok) delivered = true
            }
            updateStatus(msg.id, if (delivered) MsgStatus.SENT else MsgStatus.FAILED)
            refreshMessages(groupId)
            refreshChats()
        }
        return msg
    }

    private fun previewFor(kind: MsgKind, name: String): String = when (kind) {
        MsgKind.IMAGE -> "📷 Photo"
        MsgKind.AUDIO -> "🎤 Voice message"
        else -> "📄 $name"
    }

    /** Re-queue a failed message. */
    /** Delete one message from this device only (there is no central server). */
    fun deleteMessage(id: String) {
        scope.launch {
            val chatId = db.readableDatabase.rawQuery(
                "SELECT chat_id FROM messages WHERE id = ?", arrayOf(id),
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
            db.writableDatabase.delete("messages", "id = ?", arrayOf(id))
            if (chatId != null) {
                refreshMessages(chatId)
                refreshChats()
            }
        }
    }

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
            // A database problem here must never take the whole app down; the
            // loop runs every 45 seconds in the background.
            runCatching { retryPendingNow() }
                .onFailure { android.util.Log.w("MeshLinkRepo", "retryPending failed", it) }
        }
    }

    private fun retryPendingNow() {
            val pending = ArrayList<Message>()
            val since = System.currentTimeMillis() - 1000L * 60 * 60 * 24
            db.readableDatabase.rawQuery(
                "SELECT id, chat_id, body, ts, kind, media_path, media_mime, media_name, media_size " +
                    "FROM messages " +
                    "WHERE from_me = 1 AND ts >= ? AND " +
                    "(status = ? OR (status = ? AND kind = 'TEXT')) " +
                    "ORDER BY ts LIMIT 30",
                arrayOf(since.toString(), MsgStatus.FAILED.name, MsgStatus.SENT.name),
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
                        kind = runCatching { MsgKind.valueOf(c.getString(4)) }
                            .getOrDefault(MsgKind.TEXT),
                        mediaPath = c.getString(5),
                        mediaMime = c.getString(6) ?: "",
                        mediaName = c.getString(7) ?: "",
                        mediaSize = c.getLong(8),
                    )
                }
            }
            if (pending.isEmpty()) return
            updateStatusBulk(pending.map { it.id }, MsgStatus.SENDING)
            for (m in pending) {
                dispatch(m, allowSms = false)
            }
            pending.map { it.chatId }.distinct().forEach {
                refreshMessages(it)
                refreshChats()
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
        if (message.kind != MsgKind.TEXT) {
            dispatchMedia(message)
            return
        }
        val engine = MeshService.engine
        val displayName = appContext?.let { SessionStore.displayName(it) } ?: ""
        val ok = engine?.sendMessage(
            destination = message.chatId,
            messageId = message.id,
            body = message.body,
            senderName = displayName,
            allowSms = allowSms,
            smsNumber = phoneFor(message.chatId),
        ) ?: false

        val status = if (ok) MsgStatus.SENT else MsgStatus.FAILED
        updateStatus(message.id, status)
        refreshMessages(message.chatId)
        refreshChats()
    }

    /** Send one local media bubble to every group member as a normal file transfer. */
    private fun dispatchGroupMedia(message: Message) {
        val engine = MeshService.engine
        val path = message.mediaPath
        val group = GroupStore.get(message.chatId)
        if (engine == null || path == null || group == null) {
            updateStatus(message.id, MsgStatus.FAILED)
            refreshMessages(message.chatId)
            return
        }
        scope.launch {
            val bytes = runCatching { java.io.File(path).readBytes() }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                updateStatus(message.id, MsgStatus.FAILED)
                refreshMessages(message.chatId)
                return@launch
            }
            var delivered = false
            group.members.forEach { member ->
                val perMemberId = UUID.randomUUID().toString()
                val ok = runCatching {
                    engine.sendFile(
                        destination = member,
                        messageId = perMemberId,
                        fileName = "${group.id} ${message.mediaName}",
                        mime = message.mediaMime,
                        data = bytes,
                    ) { percent ->
                        _transferProgress.value = _transferProgress.value +
                            (message.id to (percent / group.members.size.coerceAtLeast(1)))
                    }
                }.getOrDefault(false)
                if (ok) delivered = true
            }
            _transferProgress.value = _transferProgress.value - message.id
            updateStatus(message.id, if (delivered) MsgStatus.SENT else MsgStatus.FAILED)
            refreshMessages(message.chatId)
            refreshChats()
        }
    }

    /**
     * Streams a photo / document over the mesh. Files never use SMS (the cost
     * would be absurd) and progress is published per message so the bubble can
     * show "Sending 42%".
     */
    private fun dispatchMedia(message: Message) {
        val engine = MeshService.engine
        val path = message.mediaPath
        if (engine == null || path == null) {
            updateStatus(message.id, MsgStatus.FAILED)
            refreshMessages(message.chatId)
            return
        }

        scope.launch {
            val bytes = runCatching { java.io.File(path).readBytes() }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                updateStatus(message.id, MsgStatus.FAILED)
                refreshMessages(message.chatId)
                refreshChats()
                return@launch
            }

            val ok = runCatching {
                engine.sendFile(
                    destination = message.chatId,
                    messageId = message.id,
                    fileName = message.mediaName,
                    mime = message.mediaMime,
                    data = bytes,
                ) { percent ->
                    _transferProgress.value = _transferProgress.value + (message.id to percent)
                }
            }.getOrDefault(false)

            _transferProgress.value = _transferProgress.value - message.id
            updateStatus(message.id, if (ok) MsgStatus.SENT else MsgStatus.FAILED)
            refreshMessages(message.chatId)
            refreshChats()
        }
    }

    /** Called by the mesh when a message arrives. */
    /** Message ids already handed to the UI, to collapse mesh + bridge copies. */
    private val incomingIds = HashSet<String>()

    private fun claimIncoming(messageId: String): Boolean {
        if (messageId.isBlank()) return false
        synchronized(incomingIds) {
            if (!incomingIds.add(messageId)) return false
            if (incomingIds.size > 800) {
                val old = incomingIds.iterator()
                repeat(200) { if (old.hasNext()) { old.next(); old.remove() } }
            }
            return true
        }
    }

    fun onIncoming(
        from: String,
        messageId: String,
        body: String,
        transport: String,
        senderName: String,
        senderPhone: String = "",
    ) {
        val peer = from.trim()
        if (peer.isEmpty() || body.isEmpty()) return

        if (body.startsWith("MLRD|")) {
            applyReadReceipt(body.removePrefix("MLRD|").trim())
            return
        }

        if (body.startsWith("MLR1|")) {
            val parts = body.split("|", limit = 4)
            if (parts.size == 4) applyReaction(parts[2], parts[3].take(8))
            return
        }

        if (body.startsWith("MLGINV|")) {
            val parts = body.split("|", limit = 4)
            if (parts.size >= 3 && GroupStore.isGroup(parts[1])) {
                val gid = parts[1].uppercase()
                val gname = parts[2].take(40)
                val invited = HashSet(parts.getOrNull(3).orEmpty()
                    .split(',').map { it.trim().uppercase() }.filter { it.isNotBlank() })
                invited.add(peer)
                GroupStore.ensure(gid, gname, invited)
                val now = System.currentTimeMillis()
                scope.launch {
                    touchChat(gid, gname, "Group invite · ${invited.size} members", now, transport, 0)
                    refreshChats()
                }
            }
            return
        }

        var chatId = peer
        var visibleBody = body
        if (body.startsWith("MLG1|")) {
            val parts = body.split("|", limit = 5)
            if (parts.size < 5 || !GroupStore.isGroup(parts[1])) return
            chatId = parts[1].uppercase()
            val gname = parts[2].take(40)
            GroupStore.ensure(chatId, gname, setOf(peer))
            val author = parts[3].take(40).ifBlank { peer }
            visibleBody = "$author: ${parts[4]}"
        }

        // Ignore duplicates: the same message can arrive over the mesh and
        // over an internet bridge at the same time.
        if (!claimIncoming(messageId)) return
        if (exists(messageId)) return

        val msg = Message(
            id = messageId,
            chatId = chatId,
            fromMe = false,
            body = visibleBody,
            timestamp = System.currentTimeMillis(),
            transport = transport,
            status = MsgStatus.RECEIVED,
        )
        scope.launch {
            insertMessage(msg)
            touchChat(chatId, GroupStore.get(chatId)?.name
                ?: resolveName(peer, senderName), visibleBody, msg.timestamp, transport, 1)
            if (senderPhone.isNotBlank() && senderPhone != peer) {
                savePhoneAlias(peer, senderPhone)
            }
            refreshMessages(chatId)
            refreshChats()
            val chat = _chats.value.firstOrNull { it.peer == chatId }
            if (chat != null) onNewMessage?.invoke(msg, chat)
        }
    }

    /**
     * Called by the mesh when a complete file (photo or document) has been
     * reassembled and saved. [path] points at app-private storage.
     */
    fun onIncomingFile(
        from: String,
        messageId: String,
        name: String,
        mime: String,
        path: String,
        size: Long,
        transport: String,
        senderName: String,
    ) {
        val peer = from.trim()
        if (peer.isEmpty() || messageId.isEmpty()) return
        if (!claimIncoming(messageId)) return
        if (exists(messageId)) return

        var chatId = peer
        var visibleName = name
        if (name.startsWith("GRP-") && name.length > 14 && name[13] == ' ') {
            val gid = name.take(13).uppercase()
            if (GroupStore.isGroup(gid)) {
                chatId = gid
                visibleName = name.substring(14)
                GroupStore.ensure(gid, GroupStore.get(gid)?.name ?: gid, setOf(peer))
            }
        }

        val kind = when {
            mime.startsWith("image/") -> MsgKind.IMAGE
            mime.startsWith("audio/") -> MsgKind.AUDIO
            else -> MsgKind.DOCUMENT
        }
        val preview = previewFor(kind, visibleName)
        val msg = Message(
            id = messageId,
            chatId = chatId,
            fromMe = false,
            body = preview,
            timestamp = System.currentTimeMillis(),
            transport = transport,
            status = MsgStatus.RECEIVED,
            kind = kind,
            mediaPath = path,
            mediaMime = mime,
            mediaName = visibleName,
            mediaSize = size,
        )
        scope.launch {
            insertMessage(msg)
            touchChat(chatId, GroupStore.get(chatId)?.name
                ?: resolveName(peer, senderName), preview, msg.timestamp, transport, 1)
            refreshMessages(chatId)
            refreshChats()
            val chat = _chats.value.firstOrNull { it.peer == chatId }
            if (chat != null) onNewMessage?.invoke(msg, chat)
        }
    }

    private fun applyReadReceipt(messageId: String) {
        if (messageId.isBlank()) return
        val c = db.readableDatabase.rawQuery(
            "SELECT chat_id FROM messages WHERE id = ? LIMIT 1", arrayOf(messageId),
        )
        val chatId = c.use { if (it.moveToFirst()) it.getString(0) else null } ?: return
        db.writableDatabase.execSQL(
            "UPDATE messages SET status = ? WHERE id = ? AND from_me = 1",
            arrayOf(MsgStatus.READ.name, messageId),
        )
        refreshMessages(chatId)
        refreshChats()
    }

    /** The other phone confirmed it received [messageId] (double tick). */
    fun onDelivered(messageId: String) {
        scope.launch {
            db.writableDatabase.execSQL(
                "UPDATE messages SET status = ? WHERE id = ? AND status NOT IN (?, ?)",
                arrayOf(
                    MsgStatus.DELIVERED.name, messageId,
                    MsgStatus.RECEIVED.name, MsgStatus.READ.name,
                ),
            )
            val chatId = db.readableDatabase.rawQuery(
                "SELECT chat_id FROM messages WHERE id = ?", arrayOf(messageId),
            ).use { if (it.moveToFirst()) it.getString(0) else null }
            if (chatId != null) refreshMessages(chatId)
        }
    }

    /** Remembers a phone number shared by a mesh peer, for the SMS fallback. */
    private fun savePhoneAlias(peer: String, phone: String) {
        if (!phone.trim().matches(Regex("^\\+?[0-9]{7,15}$"))) return
        runCatching {
            db.writableDatabase.execSQL(
                "UPDATE chats SET phone = ? WHERE peer = ?",
                arrayOf(phone.trim(), peer),
            )
        }
    }

    /** The SMS address of a chat, if the other side has shared one. */
    fun phoneFor(peer: String): String {
        if (!initialised) return ""
        return runCatching {
            db.readableDatabase.rawQuery(
                "SELECT phone FROM chats WHERE peer = ? LIMIT 1",
                arrayOf(peer),
            ).use { c -> if (c.moveToFirst()) c.getString(0) ?: "" else "" }
        }.getOrDefault("")
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
                "kind" to msg.kind.name,
                "media_path" to msg.mediaPath,
                "media_mime" to msg.mediaMime,
                "media_name" to msg.mediaName,
                "media_size" to msg.mediaSize,
                "reaction" to msg.reaction,
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
        // Never replace a real saved name with the peer's UIN just because a
        // later frame (e.g. a photo) did not carry a display name.
        val displayName = name.trim().takeIf { it.isNotEmpty() && it != peer } ?: ""
        w.execSQL(
            "INSERT OR IGNORE INTO chats(peer, name, last_message, last_ts, unread, last_transport) " +
                "VALUES(?,?,?,?,0,?)",
            arrayOf(peer, displayName.ifEmpty { peer }, body, ts, transport),
        )
        w.execSQL(
            "UPDATE chats SET last_message = ?, last_ts = ?, last_transport = ?, " +
                "unread = MAX(0, unread + ?), name = CASE WHEN ? = '' THEN name ELSE ? END " +
                "WHERE peer = ?",
            arrayOf(body, ts, transport, unreadDelta, displayName, displayName, peer),
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
