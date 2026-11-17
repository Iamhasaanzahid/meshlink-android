package com.meshlink.app.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Send
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.core.content.FileProvider
import com.meshlink.app.data.Chat
import com.meshlink.app.data.ChatRepository
import com.meshlink.app.data.Message
import com.meshlink.app.data.MsgKind
import com.meshlink.app.data.MsgStatus
import com.meshlink.app.util.MediaFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    chat: Chat,
    onBack: () -> Unit,
    onCall: (peer: String, name: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages by ChatRepository.messagesFor(chat.peer).collectAsState()
    val progress by ChatRepository.transferProgress.collectAsState()
    val listState = rememberLazyListState()
    var text by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var addContactOpen by remember { mutableStateOf(false) }
    var attachOpen by remember { mutableStateOf(false) }
    var contactName by remember { mutableStateOf("") }
    var viewerPath by remember { mutableStateOf<String?>(null) }
    var cameraFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(chat.peer) { ChatRepository.markRead(chat.peer) }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
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
                                "offline · ${chat.peer}",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.75f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { onCall(chat.peer, chatTitle(chat)) }) {
                        Icon(Icons.Filled.Call, contentDescription = "Call", tint = Color.White)
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Color.White)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary),
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
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
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message") },
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4,
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            val body = text.trim()
                            if (body.isNotEmpty()) {
                                ChatRepository.sendText(chat.peer, body, chat.name)
                                text = ""
                            }
                        },
                        enabled = text.isNotBlank(),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(ChatBackground),
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
                        progress = progress[message.id],
                        onRetry = { ChatRepository.retry(message) },
                        onImage = { viewerPath = it },
                    )
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

    viewerPath?.let { path ->
        ImageViewer(path) { viewerPath = null }
    }
}

@Composable
private fun MessageBubble(
    message: Message,
    progress: Int?,
    onRetry: () -> Unit,
    onImage: (String) -> Unit,
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
                .background(if (message.fromMe) OutgoingBubble else IncomingBubble)
                .then(
                    if (message.status == MsgStatus.FAILED) Modifier.clickable(onClick = onRetry)
                    else Modifier
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
                            MsgStatus.DELIVERED -> TickBlue
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
    MsgStatus.DELIVERED -> "✓✓"
    MsgStatus.FAILED -> "Retry"
    MsgStatus.RECEIVED -> ""
}

private fun transportShort(transport: String): String = when (transport) {
    "WIFI_DIRECT" -> "Wi-Fi Direct"
    "WIFI_LAN" -> "Wi-Fi LAN"
    "BLUETOOTH_LE" -> "BLE"
    "RADIO_SMS" -> "SMS"
    "AUTO" -> "Auto"
    else -> "Radio"
}
