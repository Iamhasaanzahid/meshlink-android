package com.meshlink.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Public
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(vertical = 4.dp),
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
                    Text("No calls yet", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Call another MeshLink phone over Wi-Fi Direct — free, " +
                            "phone to phone, without internet. Or use a normal SIM call.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
        items(calls, key = { it.id }) { call ->
            CallRow(call, onCall, onOpenChat)
            Divider(
                modifier = Modifier.padding(start = 78.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun OnlineCallCard(onOnlineCall: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        onClick = onOnlineCall,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Public,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Online call (worldwide)",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "Direct, encrypted call over the internet — works across countries",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                )
            }
            Icon(
                Icons.Filled.Call,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun CallRow(
    call: CallLog,
    onCall: (String, String) -> Unit,
    onOpenChat: (String, String) -> Unit,
) {
    val color = when {
        call.missed -> Color(0xFFB3261E)
        call.outgoing -> MaterialTheme.colorScheme.primary
        else -> OnlineGreen
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenChat(call.peer, call.name) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(call.name.ifBlank { call.peer }, 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = call.name.ifBlank { call.peer },
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Call,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.height(14.dp),
                )
                Spacer(Modifier.width(6.dp))
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
            Spacer(Modifier.height(4.dp))
            IconButton(onClick = { onCall(call.peer, call.name.ifBlank { call.peer }) }) {
                Icon(
                    Icons.Filled.Call,
                    contentDescription = "Call back",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
