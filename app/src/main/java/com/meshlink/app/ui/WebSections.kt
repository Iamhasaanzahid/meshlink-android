package com.meshlink.app.ui

import android.net.wifi.p2p.WifiP2pDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshlink.app.data.Chat

/** The extra rail sections — Nearby, Status, Starred and Archived. They reuse
 * existing state, so nothing here needs a new database table. */

@Composable
fun NearbyScreen(
    status: Map<String, Any>,
    wifiPeers: List<WifiP2pDevice>,
    onRefreshPeers: () -> Unit,
    onConnectPeer: (WifiP2pDevice) -> Unit,
) {
    val tokens = MeshTokens.palette
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(tokens.canvasStart, tokens.canvasEnd))),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionTitle(
                "Nearby radios",
                "Devices within radio range — no internet needed",
                tokens.mint,
            )
            WebInfoCard(
                rows = listOf(
                    "Wi-Fi Direct" to (status["wifiLanPeers"] as? Int ?: 0).toString(),
                    "Bluetooth LE" to (status["blePeers"] as? Int ?: 0).toString(),
                    "Group owner" to if (status["wifiGroup"] == true) "Active" else "Off",
                ),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onRefreshPeers,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = tokens.violet,
                    contentColor = Color.White,
                ),
            ) {
                Text("Scan nearby devices", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
        }
        if (wifiPeers.isEmpty()) {
            item {
                WebInfoCard(listOf("No devices found" to "Turn on Wi-Fi Direct and tap Scan"))
            }
        } else {
            items(wifiPeers, key = { it.deviceAddress }) { peer ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = tokens.rowStart),
                    shape = RoundedCornerShape(20.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, tokens.hairline, RoundedCornerShape(20.dp))
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Dot(if (peer.status == WifiP2pDevice.AVAILABLE) tokens.mint else tokens.amber)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                peer.deviceName.ifBlank { "Unknown device" },
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                peer.deviceAddress,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = { onConnectPeer(peer) },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = tokens.violet,
                                contentColor = Color.White,
                            ),
                        ) { Text("Connect") }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusScreen(status: Map<String, Any>, myUin: String, displayName: String) {
    val tokens = MeshTokens.palette
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(tokens.canvasStart, tokens.canvasEnd))),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionTitle("Mesh status", "Your identity and live transports", tokens.sky)
            WebInfoCard(
                rows = listOf(
                    "Mhzaly ID" to myUin,
                    "Name" to displayName,
                    "Internet relay" to if (status["onlineConnected"] == true) "Connected" else "Offline",
                    "Wi-Fi Direct peers" to (status["wifiLanPeers"] as? Int ?: 0).toString(),
                    "Bluetooth peers" to (status["blePeers"] as? Int ?: 0).toString(),
                    "Local group" to if (status["wifiGroup"] == true) "Active" else "Off",
                ),
            )
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(listOf(tokens.violet, tokens.sky)),
                        ),
                ) {
                    Text(
                        "Messages are end-to-end encrypted on this device. Your 10-digit " +
                            "Mhzaly ID is your real identity — email is only used for security verification.",
                        modifier = Modifier.padding(18.dp),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
fun StarredScreen(
    chats: List<Chat>,
    pinned: Set<String>,
    onOpen: (Chat) -> Unit,
    onTogglePin: (Chat) -> Unit,
    onToggleMute: (Chat) -> Unit,
) {
    val starred = chats.filter { pinned.contains(it.peer.uppercase()) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(MeshTokens.palette.canvasStart, MeshTokens.palette.canvasEnd))),
    ) {
        SectionTitle("Starred", "Pinned conversations", MeshTokens.palette.amber)
        if (starred.isEmpty()) {
            WebInfoCard(listOf("Nothing starred yet" to "Long-press a chat → Pin to top"))
        } else {
            ChatListScreen(
                chats = starred,
                pinned = pinned,
                muted = emptySet(),
                onOpen = onOpen,
                onTogglePin = onTogglePin,
                onToggleMute = onToggleMute,
            )
        }
    }
}

@Composable
fun ArchivedScreen(
    chats: List<Chat>,
    muted: Set<String>,
    onOpen: (Chat) -> Unit,
    onTogglePin: (Chat) -> Unit,
    onToggleMute: (Chat) -> Unit,
) {
    val archived = chats.filter { muted.contains(it.peer.uppercase()) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(MeshTokens.palette.canvasStart, MeshTokens.palette.canvasEnd))),
    ) {
        SectionTitle("Archived", "Muted conversations stay here", MeshTokens.palette.coral)
        if (archived.isEmpty()) {
            WebInfoCard(listOf("Nothing archived yet" to "Long-press a chat → Mute notifications"))
        } else {
            ChatListScreen(
                chats = archived,
                pinned = emptySet(),
                muted = muted,
                onOpen = onOpen,
                onTogglePin = onTogglePin,
                onToggleMute = onToggleMute,
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String, accent: Color) {
    Row(
        Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 4.dp, height = 40.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.25f)))),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WebInfoCard(rows: List<Pair<String, String>>) {
    val tokens = MeshTokens.palette
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = tokens.rowStart),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, tokens.hairline, RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(
        Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.20f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.55f)))),
        )
    }
}
