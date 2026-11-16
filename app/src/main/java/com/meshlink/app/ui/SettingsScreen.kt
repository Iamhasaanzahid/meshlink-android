package com.meshlink.app.ui

import android.net.wifi.p2p.WifiP2pDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshlink.app.BuildConfig

@Composable
fun SettingsScreen(
    myUin: String,
    displayName: String,
    status: Map<String, Any>,
    wifiPeers: List<WifiP2pDevice>,
    onConnectPeer: (WifiP2pDevice) -> Unit,
    onRefreshPeers: () -> Unit,
    onLogout: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // Profile card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(displayName.ifBlank { myUin }, 64.dp)
                Spacer(Modifier.padding(14.dp, 0.dp, 0.dp, 0.dp))
                Column {
                    Text(
                        displayName.ifBlank { "MeshLink user" },
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "ID: $myUin",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Offline identity · no account, no internet",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle("Radios")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                InfoRow(
                    "Cellular radio (SMS)",
                    if (status["radioSmsAvailable"] == true) "SIM ready · works anywhere with signal"
                    else "No SIM detected",
                    status["radioSmsAvailable"] == true,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                InfoRow(
                    "Bluetooth LE",
                    "${status["blePeers"] ?: 0} peer(s) · 10–50 m per hop",
                    status["bluetoothLeActive"] == true,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                InfoRow(
                    "Wi-Fi Direct",
                    if (status["wifiGroup"] == true) {
                        "Group formed · ${if (status["wifiGroupOwner"] == true) "owner" else "client"}"
                    } else {
                        "${status["wifiPeers"] ?: 0} peer(s) nearby · 50–200 m per hop"
                    },
                    status["wifiDirectActive"] == true,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Nearby phones", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefreshPeers) { Text("Search") }
        }

        if (wifiPeers.isEmpty()) {
            Text(
                "Keep this screen open on both phones. They find each other over " +
                    "Wi-Fi Direct automatically — no router involved.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            wifiPeers.forEach { device ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(13.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                device.deviceName?.takeIf { it.isNotBlank() } ?: "Android phone",
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                            )
                            Text(
                                "${device.deviceAddress} · ${peerStatus(device.status)}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = { onConnectPeer(device) },
                            shape = RoundedCornerShape(10.dp),
                        ) { Text("Connect", fontSize = 12.sp) }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("About")
        Text(
            "MeshLink ${BuildConfig.VERSION_NAME} · free software (GPL-3.0-or-later)\n" +
                "Messages and calls use only the phone's own radios: " +
                "Wi-Fi Direct, Bluetooth LE and the cellular radio (SMS). " +
                "No servers, no tracking, no internet needed.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(18.dp))
        OutlinedButton(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) { Text("Log out / reset identity") }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

private fun peerStatus(status: Int): String = when (status) {
    WifiP2pDevice.CONNECTED -> "connected"
    WifiP2pDevice.INVITED -> "invited"
    WifiP2pDevice.FAILED -> "failed"
    WifiP2pDevice.AVAILABLE -> "available"
    else -> "unavailable"
}
