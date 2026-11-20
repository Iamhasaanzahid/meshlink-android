package com.meshlink.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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

val ChatBackground = WebLightBgSoft
val IncomingBubble = WebLightPanel
val OutgoingBubble = BrandViolet
val TickBlue = Color(0xFF9FE8FF)
val OnlineGreen = Color(0xFF34D399)

private val AvatarColors = listOf(
    Color(0xFF7C3AED), Color(0xFF2563EB), Color(0xFF0F766E),
    Color(0xFFB45309), Color(0xFFBE185D), Color(0xFF6D28D9),
    Color(0xFF0369A1), Color(0xFF9333EA),
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

/** The MeshLink monogram: three peers joined in a violet mesh. Drawn entirely
 * with Compose primitives so no assets or fonts are needed. */
@Composable
fun MeshMark(size: Dp = 40.dp) {
    val radius = size * 0.30f
    Box(
        modifier = Modifier
            .size(size)
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(radius),
                ambientColor = BrandViolet.copy(alpha = 0.5f),
                spotColor = BrandDeep.copy(alpha = 0.45f),
            )
            .clip(RoundedCornerShape(radius))
            .background(Brush.linearGradient(listOf(Color(0xFFA78BFA), BrandDeep)))
            .border(
                1.dp, Color.White.copy(alpha = 0.30f), RoundedCornerShape(radius),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size * 0.58f)) {
            val s = size.toPx() * 0.58f
            val r = s * 0.095f
            val stroke = s * 0.055f
            val nodes = listOf(
                Offset(s * 0.24f, s * 0.26f),
                Offset(s * 0.76f, s * 0.26f),
                Offset(s * 0.50f, s * 0.76f),
            )
            for (i in nodes.indices) {
                for (j in i + 1 until nodes.size) {
                    drawLine(
                        color = Color.White.copy(alpha = 0.9f),
                        start = nodes[i],
                        end = nodes[j],
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
            nodes.forEach {
                drawCircle(color = Color.White, radius = r, center = it)
            }
        }
    }
}

// ------------------------------------------------------------------- avatar

@Composable
fun Avatar(
    name: String,
    size: Dp = 48.dp,
    showOnline: Boolean = false,
    photoPath: String? = null,
) {
    val base = avatarColor(name)
    val deep = Color(
        red = base.red * 0.65f,
        green = base.green * 0.62f,
        blue = base.blue * 0.80f,
        alpha = 1f,
    )
    Box(modifier = Modifier.size(size)) {
        Box(
            modifier = Modifier
                .size(size)
                .shadow(
                    elevation = if (size >= 44.dp) 8.dp else 4.dp,
                    shape = CircleShape,
                    ambientColor = base.copy(alpha = 0.45f),
                    spotColor = base.copy(alpha = 0.45f),
                )
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(base, deep)))
                .border(
                    width = 1.5.dp,
                    color = Color.White.copy(alpha = 0.28f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = remember(photoPath) {
                photoPath?.let { path ->
                    runCatching {
                        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                        android.graphics.BitmapFactory.decodeFile(path, opts)
                    }.getOrNull()
                }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(size)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = initials(name),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.36f).sp,
                )
            }
        }
        if (showOnline) {
            val pill = size * 0.30f
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(pill + 6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF6EE7B7).copy(alpha = 0.28f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(pill)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Color(0xFF6EE7B7), Color(0xFF2DD4BF))))
                        .border(
                            width = 2.dp,
                            color = MeshTokens.palette.drawer,
                            shape = CircleShape,
                        ),
                )
            }
        }
    }
}

/** Compact tinted label used for "Online", presence and card states. */
@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.32f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
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
    val tokens = MeshTokens.palette

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(26.dp),
        title = {
            Text(
                "New chat",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
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
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (contacts.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Saved contacts",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = tokens.lilac,
                    )
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
    val color = if (active) MeshTokens.palette.mint else MeshTokens.palette.coral
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.55f)))),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun chatTitle(chat: Chat): String = chat.name.ifBlank { chat.peer }
