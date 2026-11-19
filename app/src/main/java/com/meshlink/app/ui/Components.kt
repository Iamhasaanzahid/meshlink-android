package com.meshlink.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshlink.app.data.Chat
import com.meshlink.app.data.Contact
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------------ palette

val ChatBackground = Color(0xFFF1ECFA)
val IncomingBubble = Color(0xFFFFFFFF)
val OutgoingBubble = BrandViolet
val TickBlue = Color(0xFF9FE8FF)
val OnlineGreen = Color(0xFF00C88A)

private val AvatarColors = listOf(
    Color(0xFF6750A4), Color(0xFF7D5260), Color(0xFF1B6C7A),
    Color(0xFF7A5B18), Color(0xFF4C662B), Color(0xFF8C4A6B),
    Color(0xFF3F5F8A), Color(0xFF6B4E8F),
)

fun avatarColor(key: String): Color =
    AvatarColors[(key.hashCode() and 0x7FFFFFFF) % AvatarColors.size]

fun initials(name: String): String {
    val parts = name.trim().split(" ").filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
    }
}

// ------------------------------------------------------------------- avatar

@Composable
fun Avatar(name: String, size: Dp = 48.dp, showOnline: Boolean = false) {
    val base = avatarColor(name)
    val deep = Color(
        red = base.red * 0.72f,
        green = base.green * 0.72f,
        blue = base.blue * 0.82f,
        alpha = 1f,
    )
    Box(modifier = Modifier.size(size)) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(base, deep),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initials(name),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.36f).sp,
            )
        }
        if (showOnline) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.28f)
                    .clip(CircleShape)
                    .background(OnlineGreen)
                    .border(
                        width = 2.5.dp,
                        color = MaterialTheme.colorScheme.surface,
                        shape = CircleShape,
                    ),
            )
        }
    }
}

// -------------------------------------------------------------------- time

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

fun formatTime(ts: Long): String = timeFormat.format(Date(ts))

fun sameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
        ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

fun formatChatStamp(ts: Long): String {
    val now = System.currentTimeMillis()
    if (sameDay(ts, now)) return formatTime(ts)
    val yesterday = now - 24L * 60 * 60 * 1000
    if (sameDay(ts, yesterday)) return "Yesterday"
    val days = (now - ts) / (24L * 60 * 60 * 1000)
    return if (days < 6) {
        SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ts))
    } else {
        SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(Date(ts))
    }
}

fun formatDateSeparator(ts: Long): String {
    val now = System.currentTimeMillis()
    if (sameDay(ts, now)) return "Today"
    val yesterday = now - 24 * 60 * 60 * 1000L
    if (sameDay(ts, yesterday)) return "Yesterday"
    return SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ts))
}

fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

// ------------------------------------------------------------------ dialogs

/**
 * "New chat" sheet: pick a saved contact or type a UIN / phone number.
 * Everything works offline — the address is just the other phone's ID.
 */
@Composable
fun NewChatDialog(
    contacts: List<Contact>,
    onDismiss: () -> Unit,
    onStart: (peer: String, name: String) -> Unit,
) {
    var address by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New chat") },
        text = {
            Column {
                Text(
                    "Enter your friend's MeshLink ID (UIN) or phone number. " +
                        "No internet needed.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("UIN or phone number") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (contacts.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text("Saved contacts", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    contacts.take(6).forEach { contact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Avatar(contact.name, 32.dp)
                            Column(Modifier.weight(1f)) {
                                Text(contact.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(contact.address, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onStart(contact.address, contact.name) }) {
                                Text("Chat", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (address.isNotBlank()) onStart(address.trim(), name.trim()) },
                enabled = address.isNotBlank(),
            ) { Text("Start chat") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Quick info row used by the settings / radios screens. */
@Composable
fun InfoRow(title: String, subtitle: String, active: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (active) OnlineGreen else Color(0xFFB3261E)),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun chatTitle(chat: Chat): String = chat.name.ifBlank { chat.peer }
