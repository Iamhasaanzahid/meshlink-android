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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshlink.app.data.MeshSettings
import kotlin.math.roundToInt
import com.meshlink.app.BuildConfig

@Composable
fun SettingsScreen(
    myUin: String,
    displayName: String,
    status: Map<String, Any>,
    wifiPeers: List<WifiP2pDevice>,
    maxHops: Int,
    onMaxHopsChange: (Int) -> Unit,
    myPhone: String,
    onSavePhone: (String) -> Unit,
    missingPermissions: Int,
    missingTitles: List<String>,
    onRequestPermissions: () -> Unit,
    onlineEnabled: Boolean,
    onlineConnected: Boolean,
    onlineBroker: String,
    onToggleOnline: (Boolean) -> Unit,
    onSaveBroker: (String) -> Unit,
    onOpenSystemSettings: () -> Unit,
    smsFallback: Boolean,
    onToggleSmsFallback: (Boolean) -> Unit,
    onConnectPeer: (WifiP2pDevice) -> Unit,
    onRefreshPeers: () -> Unit,
    onLogout: () -> Unit,
) {
    var phoneDraft by remember(myPhone) { mutableStateOf(myPhone) }
    var brokerDraft by remember(onlineBroker) { mutableStateOf(onlineBroker) }

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
                        "Your ID lives on this phone · no account",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle("Online mode")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Worldwide messaging",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                        )
                        Text(
                            when {
                                !onlineEnabled -> "Off · local radios only"
                                onlineConnected -> "Connected · messages anywhere in the world"
                                else -> "Connecting to the relay…"
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = onlineEnabled, onCheckedChange = onToggleOnline)
                }
                if (onlineEnabled) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = brokerDraft,
                        onValueChange = { brokerDraft = it },
                        label = { Text("Relay address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onSaveBroker(brokerDraft) }) { Text("Save relay") }
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (onlineConnected) "live" else "offline",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Default is a free public broker. Message bodies are encrypted " +
                            "(AES-GCM) with a key derived from both IDs, so other listeners " +
                            "on the broker cannot read them. This is not a verified " +
                            "end-to-end key exchange yet, so avoid secrets on the public " +
                            "relay. For privacy, run your own broker and paste its address " +
                            "(ssl://host:8883).",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle("SMS reach")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    "My phone number",
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = phoneDraft,
                    onValueChange = { phoneDraft = it },
                    placeholder = { Text("+92 3xx xxxxxxx") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Shared with people you chat with, so they can reach you by SMS " +
                            "when no MeshLink phone is in between. Leave empty to share nothing.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onSavePhone(phoneDraft.trim()) }) { Text("Save") }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "SMS fallback",
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                        )
                        Text(
                            if (smsFallback) "On · uses your SIM when no MeshLink phone is near"
                            else "Off · free radio mesh only",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = smsFallback, onCheckedChange = onToggleSmsFallback)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Android warns that SMS can cost money — that is your operator's " +
                        "normal SMS rate, not a MeshLink charge. It is off by default " +
                        "and automatic retries never send SMS. Bluetooth, Wi-Fi Direct " +
                        "and Wi-Fi LAN are always free.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                InfoRow(
                    "Wi-Fi (same network)",
                    if (status["wifiLanActive"] == true) {
                        "${status["wifiLanPeers"] ?: 0} phone(s) on this network · free, no data"
                    } else {
                        "not listening"
                    },
                    status["wifiLanActive"] == true,
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle("Range & relays")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Relay hops",
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "$maxHops phone${if (maxHops == 1) "" else "s"}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = maxHops.toFloat(),
                    onValueChange = { onMaxHopsChange(it.roundToInt()) },
                    valueRange = MeshSettings.MIN_HOPS.toFloat()..MeshSettings.MAX_HOPS.toFloat(),
                    steps = MeshSettings.MAX_HOPS - MeshSettings.MIN_HOPS - 1,
                )
                Text(
                    "How many MeshLink phones a message may pass through. Every extra " +
                        "hop adds reach, but delivery is slower because each phone has to " +
                        "receive and re-broadcast the frame. Relaying only works when other " +
                        "MeshLink phones are actually in between.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle("Coverage")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                InfoRow("Bluetooth LE", "10–50 m per hop · no SIM, no router", true)
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                InfoRow("Wi-Fi Direct", "50–200 m per hop · no router", true)
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                InfoRow("Wi-Fi (same network)", "whole house/campus · router or hotspot", true)
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                InfoRow(
                    "Cellular radio (SMS)",
                    if (status["smsFallbackEnabled"] == true) "city-wide or nationwide · opt-in"
                    else "off · switch it on in Settings",
                    status["smsFallbackEnabled"] == true,
                )
            }
        }
        Text(
            "There is no way to send a message 100 km directly from a phone: " +
                "Bluetooth and Wi-Fi Direct are short-range radios, and phone FM " +
                "hardware is a receiver, not a transmitter (and broadcasting needs a " +
                "licensed station). Long distances work by chaining phones with " +
                "multi-hop relaying, or by the SMS radio.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

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

        Spacer(Modifier.height(18.dp))
        SectionTitle("Privacy")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                InfoRow("Data collected", "0% · no account, no server, no tracking", true)
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                InfoRow("Chats, calls, contacts", "stored only on this phone", true)
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                InfoRow(
                    "Permissions",
                    if (missingPermissions == 0) "all granted"
                    else "$missingPermissions missing · messaging may not work",
                    missingPermissions == 0,
                )
            }
        }
        if (missingPermissions > 0) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "Allow these so messaging works",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(6.dp))
                    missingTitles.forEach { title ->
                        Text(
                            "•  $title",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onRequestPermissions,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text("Allow now") }
                    TextButton(
                        onClick = onOpenSystemSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("If nothing appears, open Android settings") }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("About")
        Text(
            "MeshLink ${BuildConfig.VERSION_NAME} · free software (GPL-3.0-or-later)\n" +
                "Messages and calls use the phone's own radios — Wi-Fi Direct, " +
                "Wi-Fi LAN, Bluetooth LE and SMS. No account and no tracking. " +
                "Online mode is optional, off by default, and only used when " +
                "you switch it on.",
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
