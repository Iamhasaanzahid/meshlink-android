package com.meshlink.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.meshlink.app.data.Chat
import com.meshlink.app.data.ChatRepository
import com.meshlink.app.data.GroupStore
import com.meshlink.app.data.Message
import com.meshlink.app.data.MsgKind
import com.meshlink.app.data.MsgStatus
import com.meshlink.app.service.MeshService
import com.meshlink.app.util.MediaFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    chat: Chat,
    onBack: () -> Unit,
    onCall: (peer: String, name: String) -> Unit,
    onVideoCall: (peer: String, name: String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages by ChatRepository.messagesFor(chat.peer).collectAsState()
    val allChats by ChatRepository.chats.collectAsState()
    val typingMap by ChatRepository.typing.collectAsState()
    var peerTyping by remember { mutableStateOf(false) }
    var lastTypingSent by remember { mutableStateOf(false) }
    var lastTypingAt by remember { mutableStateOf(0L) }
    val progress by ChatRepository.transferProgress.collectAsState()
    val listState = rememberLazyListState()
    var text by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var addContactOpen by remember { mutableStateOf(false) }
    var attachOpen by remember { mutableStateOf(false) }
    var contactName by remember { mutableStateOf("") }
    var viewerPath by remember { mutableStateOf<String?>(null) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    var recording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableIntStateOf(0) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var voiceFile by remember { mutableStateOf<File?>(null) }
    var selectedMessage by remember { mutableStateOf<Message?>(null) }
    var forwardMessage by remember { mutableStateOf<Message?>(null) }
    var replyTo by remember { mutableStateOf<Message?>(null) }
    var infoMessage by remember { mutableStateOf<Message?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchHitIndex by remember { mutableIntStateOf(0) }
    var highlightId by remember { mutableStateOf<String?>(null) }
    var groupInfoOpen by remember { mutableStateOf(false) }
    var newMember by remember { mutableStateOf("") }

    LaunchedEffect(chat.peer) { ChatRepository.markRead(chat.peer) }
    LaunchedEffect(messages.size, chat.peer) { ChatRepository.markRead(chat.peer) }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(typingMap, chat.peer) {
        while (true) {
            val ts = ChatRepository.typing.value[chat.peer.uppercase()]
            peerTyping = ts != null && System.currentTimeMillis() - ts < 6_000
            delay(1_000)
        }
    }

    fun pushTyping(active: Boolean) {
        if (active == lastTypingSent) return
        val now = System.currentTimeMillis()
        if (active && now - lastTypingAt < 1_500) return
        lastTypingSent = active
        lastTypingAt = now
        MeshService.engine?.sendTyping(chat.peer, active)
    }

    fun sendComposed() {
        val body = text.trim()
        if (body.isEmpty()) return
        val quoted = replyTo?.body?.take(80)?.replace('\n', ' ')
        val outgoing = if (quoted.isNullOrBlank()) body else "↪ $quoted\n$body"
        ChatRepository.sendText(chat.peer, outgoing, chat.name)
        text = ""
        replyTo = null
        pushTyping(false)
    }

    fun sendPickedImage(uri: Uri) {
        scope.launch {
            toast("Preparing photo…")
            val file = withContext(Dispatchers.IO) { MediaFiles.importImage(context, uri) }
            if (file == null) {
                toast("Could not read that photo")
                return@launch
            }
            val sent = ChatRepository.sendMedia(
                chat.peer, file, MsgKind.IMAGE, "image/jpeg", "photo.jpg", chat.name,
            )
            if (sent == null) toast("Photo too large (max 3 MB)")
        }
    }

    fun sendPickedDocument(uri: Uri) {
        scope.launch {
            val mime = MediaFiles.mimeType(context, uri)
            val imported = withContext(Dispatchers.IO) { MediaFiles.importDocument(context, uri) }
            if (imported == null) {
                toast("Could not read that file")
                return@launch
            }
            val (file, name) = imported
            val sent = ChatRepository.sendMedia(
                chat.peer, file, MsgKind.DOCUMENT, mime, name, chat.name,
            )
            if (sent == null) toast("File too large (max 3 MB)")
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) sendPickedImage(uri) }

    // ------------------------------------------------------------ voice notes

    fun beginVoiceCapture() {
        if (recording) return
        runCatching {
            val file = File(context.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(32_000)
            rec.setAudioSamplingRate(16_000)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder?.release()
            recorder = rec
            voiceFile = file
            recordSeconds = 0
            recording = true
        }.onFailure {
            recorder?.release()
            recorder = null
            recording = false
            toast("Could not start recording")
        }
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginVoiceCapture() else toast("Microphone permission is needed")
    }

    fun startVoice() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        beginVoiceCapture()
    }

    fun stopVoice(send: Boolean) {
        val rec = recorder
        recorder = null
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        recording = false
        val file = voiceFile
        voiceFile = null
        if (send && file != null && file.length() > 0 && recordSeconds >= 1) {
            val sent = ChatRepository.sendMedia(
                chat.peer, file, MsgKind.AUDIO, "audio/mp4", "voice.m4a", chat.name,
            )
            if (sent == null) toast("Voice message could not be sent")
        } else {
            file?.delete()
        }
        recordSeconds = 0
    }

    LaunchedEffect(recording) {
        while (recording) {
            delay(1_000)
            if (!recording) break
            recordSeconds++
            if (recordSeconds >= 60) {
                stopVoice(send = true)
                break
            }
        }
    }

    val documentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) sendPickedDocument(uri) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok ->
        val file = cameraFile
        if (ok && file != null && file.exists()) {
            scope.launch {
                val prepared = withContext(Dispatchers.IO) {
                    MediaFiles.compressImageFile(context, file)
                }
                if (prepared == null) {
                    toast("Could not save that photo")
                    return@launch
                }
                val sent = ChatRepository.sendMedia(
                    chat.peer, prepared, MsgKind.IMAGE, "image/jpeg", "photo.jpg", chat.name,
                )
                if (sent == null) toast("Photo too large (max 3 MB)")
            }
        } else {
            file?.delete()
        }
    }

    fun startCamera() {
        val file = MediaFiles.newCameraFile(context)
        cameraFile = file
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
        cameraLauncher.launch(uri)
    }

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier.background(
                    Brush.verticalGradient(listOf(BrandViolet, BrandDeep)),
                ),
            ) {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(chatTitle(chat), 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                chatTitle(chat),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (peerTyping) "typing…" else "MeshLink ID · ${chat.peer}",
                                fontSize = 11.sp,
                                color = if (peerTyping) BrandMint else Color.White.copy(alpha = 0.75f),
                                fontWeight = if (peerTyping) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { searchOpen = !searchOpen }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search messages", tint = Color.White)
                    }
                    IconButton(
                        onClick = { onCall(chat.peer, chatTitle(chat)) },
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(40.dp)
                            .background(Color.White.copy(alpha = 0.16f), CircleShape),
                    ) {
                        Icon(
                            Icons.Filled.Call,
                            contentDescription = "Call",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Color.White)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Video call") },
                                leadingIcon = { Icon(Icons.Filled.Videocam, null) },
                                onClick = {
                                    menuOpen = false
                                    onVideoCall(chat.peer, chatTitle(chat))
                                },
                            )
                            if (GroupStore.isGroup(chat.peer)) {
                                DropdownMenuItem(
                                    text = { Text("Group info") },
                                    onClick = { menuOpen = false; groupInfoOpen = true },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Save contact") },
                                onClick = { menuOpen = false; contactName = chatTitle(chat); addContactOpen = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Clear chat") },
                                onClick = { menuOpen = false; ChatRepository.clearChat(chat.peer); onBack() },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column {
                    if (replyTo != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Reply,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                replyTo!!.body.take(80).replace('\n', ' '),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { replyTo = null }) {
                                Icon(Icons.Filled.Close, contentDescription = "Cancel reply")
                            }
                        }
                    }
                    Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Box {
                        IconButton(onClick = { attachOpen = true }) {
                            Icon(
                                Icons.Filled.AttachFile,
                                contentDescription = "Attach",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        DropdownMenu(
                            expanded = attachOpen,
                            onDismissRequest = { attachOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Photo from gallery") },
                                leadingIcon = { Icon(Icons.Filled.Photo, null) },
                                onClick = {
                                    attachOpen = false
                                    galleryLauncher.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                                        ),
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Take photo") },
                                leadingIcon = { Icon(Icons.Filled.PhotoCamera, null) },
                                onClick = { attachOpen = false; startCamera() },
                            )
                            DropdownMenuItem(
                                text = { Text("Document") },
                                leadingIcon = { Icon(Icons.Filled.Description, null) },
                                onClick = {
                                    attachOpen = false
                                    documentLauncher.launch("*/*")
                                },
                            )
                        }
                    }
                    if (recording) {
                        Text(
                            "● ${"%d:%02d".format(recordSeconds / 60, recordSeconds % 60)}",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                        )
                        IconButton(onClick = { stopVoice(send = false) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel recording")
                        }
                        FilledIconButton(
                            onClick = { stopVoice(send = true) },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = "Send voice message")
                        }
                    } else {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it; pushTyping(it.isNotBlank()) },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Message") },
                            shape = RoundedCornerShape(24.dp),
                            maxLines = 4,
                        )
                        Spacer(Modifier.width(8.dp))
                        if (text.isBlank()) {
                            FilledIconButton(
                                onClick = { startVoice() },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Filled.Mic, contentDescription = "Record voice message")
                            }
                        } else {
                            FilledIconButton(
                                onClick = {
                                    sendComposed()
                                },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Filled.Send, contentDescription = "Send")
                            }
                        }
                    }
                    }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        if (searchOpen) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { q -> searchQuery = q; searchHitIndex = 0; highlightId = null },
                            placeholder = { Text("Search this chat") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            searchOpen = false
                            searchQuery = ""
                            highlightId = null
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close search")
                        }
                    }
                    if (searchQuery.isNotBlank()) {
                        val hits = messages.filter { it.body.contains(searchQuery, ignoreCase = true) }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (hits.isEmpty()) "No results" else "${searchHitIndex + 1}/${hits.size}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                if (hits.isEmpty()) return@TextButton
                                searchHitIndex = (searchHitIndex - 1 + hits.size) % hits.size
                                val hit = hits[searchHitIndex]
                                highlightId = hit.id
                                val idx = messages.indexOfFirst { it.id == hit.id }
                                if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
                            }) { Text("Previous") }
                            TextButton(onClick = {
                                if (hits.isEmpty()) return@TextButton
                                searchHitIndex = (searchHitIndex + 1) % hits.size
                                val hit = hits[searchHitIndex]
                                highlightId = hit.id
                                val idx = messages.indexOfFirst { it.id == hit.id }
                                if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
                            }) { Text("Next") }
                        }
                    }
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .background(
                    Brush.verticalGradient(
                        listOf(ChatBackground, Color(0xFFE7DFF7)),
                    ),
                ),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 60.dp, start = 24.dp, end = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "Messages travel phone to phone over radio:\n" +
                                "Wi-Fi Direct → Bluetooth LE → SMS.\nNo internet needed.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            itemsIndexed(messages) { index, message ->
                Column {
                    val showDate = index == 0 ||
                        !sameDay(messages[index - 1].timestamp, message.timestamp)
                    if (showDate) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFFE1D5EC),
                            ) {
                                Text(
                                    formatDateSeparator(message.timestamp),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                    MessageBubble(
                        message = message,
                        highlighted = message.id == highlightId,
                        progress = progress[message.id],
                        onRetry = { ChatRepository.retry(message) },
                        onImage = { viewerPath = it },
                        onLongPress = { selectedMessage = message },
                    )
                }
            }
        }
        }
    }

    if (addContactOpen) {
        AlertDialog(
            onDismissRequest = { addContactOpen = false },
            title = { Text("Save contact") },
            text = {
                OutlinedTextField(
                    value = contactName,
                    onValueChange = { contactName = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ChatRepository.addContact(contactName, chat.peer)
                    addContactOpen = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { addContactOpen = false }) { Text("Cancel") } },
        )
    }

    if (groupInfoOpen) {
        val group = GroupStore.get(chat.peer)
        AlertDialog(
            onDismissRequest = { groupInfoOpen = false },
            title = { Text(group?.name ?: "Group") },
            text = {
                Column {
                    Text(
                        "${group?.members?.size ?: 0} member${if ((group?.members?.size ?: 0) == 1) "" else "s"} · ${chat.peer}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    group?.members?.sorted()?.forEach { member ->
                        Text(member, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newMember,
                        onValueChange = { newMember = it },
                        label = { Text("Add member (10-digit ID)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = {
                        ChatRepository.addGroupMember(chat.peer, newMember)
                        newMember = ""
                    }) { Text("Add member") }
                }
            },
            confirmButton = {
                TextButton(onClick = { groupInfoOpen = false }) { Text("Done") }
            },
            dismissButton = {
                TextButton(onClick = {
                    ChatRepository.leaveGroup(chat.peer)
                    groupInfoOpen = false
                    onBack()
                }) { Text("Leave group", color = MaterialTheme.colorScheme.error) }
            },
        )
    }

    viewerPath?.let { path ->
        ImageViewer(path) { viewerPath = null }
    }


    if (selectedMessage != null) {
        val m = selectedMessage!!
        AlertDialog(
            onDismissRequest = { selectedMessage = null },
            title = { Text("Message") },
            text = {
                Column {
                    Text(
                        "React",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        listOf("❤️", "👍", "😂", "😮", "😢", "🙏").forEach { emoji ->
                            TextButton(onClick = {
                                ChatRepository.sendReaction(m, emoji)
                                selectedMessage = null
                            }) { Text(emoji, fontSize = 22.sp) }
                        }
                    }
                    TextButton(onClick = {
                        replyTo = m
                        selectedMessage = null
                    }) {
                        Icon(Icons.Filled.Reply, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Reply")
                    }
                    TextButton(onClick = {
                        forwardMessage = m
                        selectedMessage = null
                    }) {
                        Icon(Icons.Filled.Forward, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Forward")
                    }
                    TextButton(onClick = {
                        infoMessage = m
                        selectedMessage = null
                    }) {
                        Icon(Icons.Filled.Info, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Message info")
                    }
                    if (m.fromMe && !GroupStore.isGroup(chat.peer)) {
                        TextButton(onClick = {
                            ChatRepository.deleteForEveryone(m)
                            selectedMessage = null
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Delete for everyone")
                        }
                    }
                    TextButton(onClick = {
                        ChatRepository.deleteMessage(m.id)
                        selectedMessage = null
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Delete for me")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedMessage = null }) { Text("Close") }
            },
        )
    }

    if (infoMessage != null) {
        val m = infoMessage!!
        AlertDialog(
            onDismissRequest = { infoMessage = null },
            title = { Text("Message info") },
            text = {
                Column {
                    Text(
                        "Direction: " + if (m.fromMe) "Sent" else "Received",
                        fontSize = 13.sp,
                    )
                    Text(
                        "Time: ${formatDateSeparator(m.timestamp)}, ${formatTime(m.timestamp)}",
                        fontSize = 13.sp,
                    )
                    Text("Route: ${transportShort(m.transport)}", fontSize = 13.sp)
                    Text(
                        "Status: " + when (m.status) {
                            MsgStatus.SENDING -> "Sending"
                            MsgStatus.SENT -> "Sent"
                            MsgStatus.DELIVERED -> "Delivered"
                            MsgStatus.READ -> "Read"
                            MsgStatus.FAILED -> "Failed — tap the bubble to retry"
                            MsgStatus.RECEIVED -> "Received"
                        },
                        fontSize = 13.sp,
                    )
                    if (m.mediaName.isNotBlank()) {
                        Text(
                            "File: ${m.mediaName} · ${formatBytes(m.mediaSize)}",
                            fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Messages are stored on this phone only. There is no server copy.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { infoMessage = null }) { Text("Close") }
            },
        )
    }

    if (forwardMessage != null) {
        val m = forwardMessage!!
        AlertDialog(
            onDismissRequest = { forwardMessage = null },
            title = { Text("Forward to") },
            text = {
                val targets = allChats.filter { it.peer != chat.peer }
                if (targets.isEmpty()) {
                    Text("No other chats yet.")
                } else {
                    LazyColumn {
                        items(targets, key = { it.peer }) { target ->
                            TextButton(onClick = {
                                val path = m.mediaPath
                                if (path != null && File(path).exists()) {
                                    ChatRepository.sendMedia(
                                        target.peer, File(path), m.kind,
                                        m.mediaMime, m.mediaName, target.name,
                                    )
                                } else {
                                    ChatRepository.sendText(target.peer, m.body, target.name)
                                }
                                forwardMessage = null
                                toast("Forwarded to ${target.name}")
                            }) { Text(target.name) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { forwardMessage = null }) { Text("Cancel") }
            },
        )
    }

}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    highlighted: Boolean = false,
    progress: Int?,
    onRetry: () -> Unit,
    onImage: (String) -> Unit,
    onLongPress: () -> Unit,
) {
    val context = LocalContext.current
    val bubbleShape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (message.fromMe) 16.dp else 4.dp,
        bottomEnd = if (message.fromMe) 4.dp else 16.dp,
    )
    val isImage = message.kind == MsgKind.IMAGE && message.mediaPath != null
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromMe) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(bubbleShape)
                .background(
                    if (highlighted) Color(0xFFFFF0A6)
                    else if (message.fromMe) OutgoingBubble else IncomingBubble,
                )
                .combinedClickable(
                    onClick = { if (message.status == MsgStatus.FAILED) onRetry() },
                    onLongClick = onLongPress,
                )
                .padding(
                    horizontal = if (isImage) 4.dp else 12.dp,
                    vertical = if (isImage) 4.dp else 7.dp,
                ),
        ) {
            when {
                isImage -> {
                    val path = message.mediaPath!!
                    val bitmap = remember(path) { MediaFiles.thumbnail(path) }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .widthIn(max = 260.dp)
                                .height(190.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onImage(path) },
                        )
                    } else {
                        Text("[photo]", fontSize = 15.sp)
                    }
                }

                message.kind == MsgKind.AUDIO && message.mediaPath != null -> {
                    VoiceBubble(path = message.mediaPath!!, fromMe = message.fromMe)
                }

                message.kind == MsgKind.DOCUMENT && message.mediaPath != null -> {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { openFile(context, message) }
                            .padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Description,
                            contentDescription = null,
                            tint = if (message.fromMe) Color.White
                            else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                message.mediaName.ifBlank { "Document" },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (message.fromMe) Color.White else Color(0xFF1D1B20),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                formatBytes(message.mediaSize),
                                fontSize = 11.sp,
                                color = if (message.fromMe) Color.White.copy(alpha = 0.75f)
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                else -> Text(
                    text = message.body,
                    fontSize = 15.sp,
                    color = if (message.fromMe) Color.White else Color(0xFF1D1B20),
                )
            }

            if (message.reaction.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.White.copy(alpha = 0.92f),
                ) {
                    Text(
                        message.reaction,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }

            if (message.fromMe && message.status == MsgStatus.SENDING &&
                message.kind != MsgKind.TEXT && progress != null
            ) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Sending $progress%",
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }

            Spacer(Modifier.height(3.dp))
            Row(
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(horizontal = if (isImage) 6.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = transportShort(message.transport),
                    fontSize = 9.sp,
                    color = if (message.fromMe) Color.White.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = formatTime(message.timestamp),
                    fontSize = 10.sp,
                    color = if (message.fromMe) Color.White.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (message.fromMe) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = ticks(message.status),
                        fontSize = 10.sp,
                        color = when (message.status) {
                            MsgStatus.FAILED -> Color(0xFFFFB4AB)
                            MsgStatus.DELIVERED, MsgStatus.READ -> TickBlue
                            else -> Color.White.copy(alpha = 0.85f)
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/** Full-screen photo viewer; tap anywhere or the X to close. */
@Composable
private fun ImageViewer(path: String, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val bitmap = remember(path) { MediaFiles.thumbnail(path, maxPx = 1600) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

private fun openFile(context: android.content.Context, message: Message) {
    val path = message.mediaPath ?: return
    runCatching {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", File(path),
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, message.mediaMime.ifBlank { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
}

private fun ticks(status: MsgStatus): String = when (status) {
    MsgStatus.SENDING -> "◷"
    MsgStatus.SENT -> "✓"
    MsgStatus.DELIVERED, MsgStatus.READ -> "✓✓"
    MsgStatus.FAILED -> "Retry"
    MsgStatus.RECEIVED -> ""
}

private fun transportShort(transport: String): String = when (transport) {
    "WIFI_DIRECT" -> "Wi-Fi Direct"
    "WIFI_LAN" -> "Wi-Fi LAN"
    "ONLINE" -> "Online"
    "BLUETOOTH_LE" -> "BLE"
    "RADIO_SMS" -> "SMS"
    "AUTO" -> "Auto"
    else -> "Radio"
}

/** Small inline player for voice notes; no external app needed. */
@Composable
private fun VoiceBubble(path: String, fromMe: Boolean) {
    val tint = if (fromMe) Color.White else MaterialTheme.colorScheme.primary
    var playing by remember(path) { mutableStateOf(false) }
    var player by remember(path) { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(path) {
        onDispose {
            runCatching { player?.stop() }
            runCatching { player?.release() }
            player = null
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp),
    ) {
        IconButton(
            onClick = {
                if (playing) {
                    runCatching { player?.stop() }
                    runCatching { player?.release() }
                    player = null
                    playing = false
                } else {
                    runCatching {
                        val p = MediaPlayer()
                        p.setDataSource(path)
                        p.setOnCompletionListener {
                            playing = false
                            it.release()
                            if (player === it) player = null
                        }
                        p.prepare()
                        p.start()
                        player = p
                        playing = true
                    }.onFailure { playing = false }
                }
            },
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause voice message" else "Play voice message",
                tint = tint,
            )
        }
        Column {
            Text(
                if (playing) "Playing…" else "Voice message",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (fromMe) Color.White else Color(0xFF1D1B20),
            )
            Text(
                "Tap to listen",
                fontSize = 11.sp,
                color = if (fromMe) Color.White.copy(alpha = 0.75f)
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
