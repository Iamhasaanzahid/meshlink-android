package com.meshlink.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshlink.app.data.CallLog

@Composable
fun CallsScreen(
    calls: List<CallLog>,
    onCall: (peer: String, name: String) -> Unit,
    onOpenChat: (peer: String, name: String) -> Unit,
    onOnlineCall: () -> Unit,
) {
    val tokens = MeshTokens.palette
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(tokens.canvasStart, tokens.canvasEnd))),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        item(key = "online-call") {
            OnlineCallCard(onOnlineCall)
        }
        if (calls.isEmpty()) {
            item(key = "empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .shadow(18.dp, CircleShape, ambientColor = tokens.mint.copy(alpha = 0.35f))
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(tokens.mint.copy(alpha = 0.4f), tokens.sky.copy(alpha = 0.2f)),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Call,
                            contentDescription = null,
                            tint = tokens.mint,
                            modifier = Modifier.size(42.dp),
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "No calls yet",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Call another MeshLink phone over Wi-Fi Direct — free, " +
                            "phone to phone, without internet. Or use a normal SIM call.",
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
        items(calls, key = { it.id }) { call ->
            CallRow(call, onCall, onOpenChat)
            HorizontalDivider(
                modifier = Modifier.padding(start = 84.dp, end = 16.dp),
                color = tokens.hairline,
            )
        }
    }
}

@Composable
private fun OnlineCallCard(onOnlineCall: () -> Unit) {
    val tokens = MeshTokens.palette
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        onClick = onOnlineCall,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(tokens.violet, tokens.lilac))),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 24.dp, y = (-30).dp)
                    .size(140.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                        ),
                    ),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f))
                        .border(1.dp, Color.White.copy(alpha = 0.34f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Public,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Online call (worldwide)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "Direct, encrypted call over the internet — works across countries",
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Filled.Call,
                    contentDescription = null,
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun CallRow(
    call: CallLog,
    onCall: (String, String) -> Unit,
    onOpenChat: (String, String) -> Unit,
) {
    val tokens = MeshTokens.palette
    val color = when {
        call.missed -> tokens.coral
        call.outgoing -> tokens.sky
        else -> tokens.mint
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenChat(call.peer, call.name) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(call.name.ifBlank { call.peer }, 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = call.name.ifBlank { call.peer },
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Call,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(13.dp),
                    )
                }
                Text(
                    text = buildString {
                        append(
                            when {
                                call.missed -> "Missed"
                                call.outgoing -> "Outgoing"
                                else -> "Incoming"
                            }
                        )
                        if (call.durationSec > 0) append(" · ${formatDuration(call.durationSec)}")
                        if (call.status == "DECLINED") append(" · declined")
                    },
                    fontSize = 12.sp,
                    color = color,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatChatStamp(call.timestamp),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            IconButton(onClick = { onCall(call.peer, call.name.ifBlank { call.peer }) }) {
                Icon(
                    Icons.Filled.Call,
                    contentDescription = "Call back",
                    tint = tokens.violet,
                )
            }
        }
    }
}
