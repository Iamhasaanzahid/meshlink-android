package com.meshlink.app.ui

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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshlink.app.data.Chat
import com.meshlink.app.data.ChatRepository
import com.meshlink.app.data.Message
import com.meshlink.app.data.MsgStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    chat: Chat,
    onBack: () -> Unit,
    onCall: (peer: String, name: String) -> Unit,
) {
    val messages by ChatRepository.messagesFor(chat.peer).collectAsState()
    val listState = rememberLazyListState()
    var text by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var addContactOpen by remember { mutableStateOf(false) }
    var contactName by remember { mutableStateOf("") }

    LaunchedEffect(chat.peer) { ChatRepository.markRead(chat.peer) }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
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
                    MessageBubble(message) { ChatRepository.retry(message) }
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
}

@Composable
private fun MessageBubble(message: Message, onRetry: () -> Unit) {
    val bubbleShape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (message.fromMe) 16.dp else 4.dp,
        bottomEnd = if (message.fromMe) 4.dp else 16.dp,
    )
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
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(
                text = message.body,
                fontSize = 15.sp,
                color = if (message.fromMe) Color.White else Color(0xFF1D1B20),
            )
            Spacer(Modifier.height(3.dp))
            Row(
                modifier = Modifier.align(Alignment.End),
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

private fun ticks(status: MsgStatus): String = when (status) {
    MsgStatus.SENDING -> "◷"
    MsgStatus.SENT -> "✓"
    MsgStatus.DELIVERED -> "✓✓"
    MsgStatus.FAILED -> "Retry"
    MsgStatus.RECEIVED -> ""
}

private fun transportShort(transport: String): String = when (transport) {
    "WIFI_DIRECT" -> "Wi-Fi"
    "BLUETOOTH_LE" -> "BLE"
    "RADIO_SMS" -> "SMS"
    "AUTO" -> "Auto"
    else -> "Radio"
}
