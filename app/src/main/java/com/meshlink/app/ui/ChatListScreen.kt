package com.meshlink.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshlink.app.data.Chat
import com.meshlink.app.data.SessionStore
import com.meshlink.app.util.MessageText

@Composable
fun ChatListScreen(
    chats: List<Chat>,
    onOpen: (Chat) -> Unit,
    pinned: Set<String> = emptySet(),
    muted: Set<String> = emptySet(),
    onTogglePin: (Chat) -> Unit = {},
    onToggleMute: (Chat) -> Unit = {},
) {
    if (chats.isEmpty()) {
        EmptyState(
            title = "No chats yet",
            body = "Tap New chat to start. Messages travel over Wi-Fi, " +
                "Bluetooth or the internet relay — whichever is available.",
        )
        return
    }
    val tokens = MeshTokens.palette
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(tokens.canvasStart, tokens.canvasEnd))),
    ) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 70.dp, y = (-90).dp)
                .size(300.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(tokens.glow.copy(alpha = 0.14f), Color.Transparent),
                    ),
                ),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "list-eyebrow") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "CONVERSATIONS",
                        color = tokens.lilac,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${chats.size} chats",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
            }
            items(chats, key = { it.peer }) { chat ->
                ChatRow(
                    chat = chat,
                    pinned = pinned.contains(chat.peer.uppercase()),
                    muted = muted.contains(chat.peer.uppercase()),
                    onClick = { onOpen(chat) },
                    onTogglePin = { onTogglePin(chat) },
                    onToggleMute = { onToggleMute(chat) },
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    chat: Chat,
    pinned: Boolean,
    muted: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleMute: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val myUin = SessionStore.uin(LocalContext.current).orEmpty()
    val tokens = MeshTokens.palette
    val unread = chat.unread > 0
    val rowColors = if (pinned) {
        listOf(tokens.pinnedStart, tokens.pinnedEnd)
    } else {
        listOf(tokens.rowStart, tokens.rowEnd)
    }
    val shape = RoundedCornerShape(20.dp)

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = if (unread || pinned) 8.dp else 4.dp,
                    shape = shape,
                    ambientColor = if (pinned) tokens.amber.copy(alpha = 0.22f)
                    else tokens.glow.copy(alpha = if (unread) 0.20f else 0.08f),
                    spotColor = tokens.violet.copy(alpha = 0.18f),
                )
                .clip(shape)
                .background(Brush.horizontalGradient(rowColors))
                .border(
                    width = 1.dp,
                    color = when {
                        unread -> tokens.glow.copy(alpha = 0.32f)
                        pinned -> tokens.amber.copy(alpha = 0.28f)
                        else -> tokens.hairline
                    },
                    shape = shape,
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (unread) {
                    Box(
                        Modifier
                            .size(66.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(tokens.glow.copy(alpha = 0.24f), Color.Transparent),
                                ),
                            ),
                    )
                }
                Avatar(chatTitle(chat), 56.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = chatTitle(chat),
                        fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (pinned) {
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = "Pinned",
                            tint = tokens.amber,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    if (unread) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(tokens.glow.copy(alpha = 0.14f))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = formatChatStamp(chat.lastTimestamp),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = tokens.lilac,
                            )
                        }
                    } else {
                        Text(
                            text = formatChatStamp(chat.lastTimestamp),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (muted) {
                        Icon(
                            Icons.Filled.NotificationsOff,
                            contentDescription = "Muted",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = MessageText.preview(chat.lastMessage, chat.peer, myUin)
                            .ifBlank { "Message" },
                        fontSize = 13.sp,
                        color = if (unread) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (chat.unread > 0) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .height(22.dp)
                                .widthIn(min = 22.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    if (muted) {
                                        Brush.linearGradient(
                                            listOf(
                                                MaterialTheme.colorScheme.outline,
                                                MaterialTheme.colorScheme.outline,
                                            ),
                                        )
                                    } else {
                                        Brush.horizontalGradient(
                                            listOf(tokens.violet, tokens.lilac),
                                        )
                                    },
                                )
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (chat.unread > 99) "99+" else chat.unread.toString(),
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(if (pinned) "Unpin" else "Pin to top") },
                leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onTogglePin()
                },
            )
            DropdownMenuItem(
                text = { Text(if (muted) "Unmute" else "Mute notifications") },
                leadingIcon = {
                    Icon(
                        if (muted) Icons.Filled.Notifications else Icons.Filled.NotificationsOff,
                        contentDescription = null,
                    )
                },
                onClick = {
                    menuOpen = false
                    onToggleMute()
                },
            )
        }
    }
}

@Composable
fun EmptyState(title: String, body: String) {
    val tokens = MeshTokens.palette
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(tokens.canvasStart, tokens.canvasEnd)))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(168.dp).clip(CircleShape).background(tokens.glow.copy(alpha = 0.08f)))
            Box(Modifier.size(126.dp).clip(CircleShape).background(tokens.glow.copy(alpha = 0.12f)))
            Box {
                Box(
                    modifier = Modifier
                        .size(92.dp)
                        .shadow(20.dp, CircleShape, ambientColor = tokens.glow.copy(alpha = 0.4f))
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(tokens.violet, tokens.lilac)),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Chat,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 6.dp, y = 6.dp),
                ) {
                    MeshMark(34.dp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            title,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            body,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
