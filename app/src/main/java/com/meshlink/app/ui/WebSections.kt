package com.meshlink.app.ui

import android.net.wifi.p2p.WifiP2pDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshlink.app.data.Chat

/** The four extra rail sections that the web app has and the old native app
 * did not: Nearby, Status, Starred and Archived. They reuse existing state, so
 * nothing here needs a new database table. */

@Composable
fun NearbyScreen(
    status: Map<String, Any>,
    wifiPeers: List<WifiP2pDevice>,
    onRefreshPeers: () -> Unit,
    onConnectPeer: (WifiP2pDevice) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionTitle("Nearby radios", "Devices within radio range — no internet needed")
            WebInfoCard(
                rows = listOf(
                    "Wi-Fi Direct" to (status["wifiLanPeers"] as? Int ?: 0).toString(),
                    "Bluetooth LE" to (status["blePeers"] as? Int ?: 0).toString(),
                    "Group owner" to if (status["wifiGroup"] == true) "Active" else "Off",
                ),
            )
            Spacer(Modifier.height(6.dp))
            Button(onClick = onRefreshPeers, modifier = Modifier.fillMaxWidth()) {
                Text("Scan nearby devices")
            }
            Spacer(Modifier.height(8.dp))
        }
        if (wifiPeers.isEmpty()) {
            item {
                WebInfoCard(listOf("No devices found" to "Turn on Wi-Fi Direct and tap Scan"))
            }
        } else {
            items(wifiPeers, key = { it.deviceAddress }) { peer ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Dot(if (peer.status == WifiP2pDevice.AVAILABLE) BrandMint else MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(peer.deviceName.ifBlank { "Unknown device" }, fontWeight = FontWeight.SemiBold)
                            Text(peer.deviceAddress, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = { onConnectPeer(peer) }) { Text("Connect") }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusScreen(status: Map<String, Any>, myUin: String, displayName: String) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionTitle("Mesh status", "Your identity and live transports")
            WebInfoCard(
                rows = listOf(
                    "MeshLink ID" to myUin,
                    "Name" to displayName,
                    "Internet relay" to if (status["onlineConnected"] == true) "Connected" else "Offline",
                    "Wi-Fi Direct peers" to (status["wifiLanPeers"] as? Int ?: 0).toString(),
                    "Bluetooth peers" to (status["blePeers"] as? Int ?: 0).toString(),
                    "Local group" to if (status["wifiGroup"] == true) "Active" else "Off",
                ),
            )
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(
                    "Messages are end-to-end encrypted on this device. Your 10-digit " +
                        "MeshLink ID is your real identity — email is only used for security verification.",
                    modifier = Modifier.padding(16.dp),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
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
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SectionTitle("Starred", "Pinned conversations")
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
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SectionTitle("Archived", "Muted conversations stay here")
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
private fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp))
        Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WebInfoCard(rows: List<Pair<String, String>>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun Dot(color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.size(9.dp).background(color, CircleShape))
}
