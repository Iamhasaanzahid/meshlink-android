package com.meshlink.app.ui

import android.net.wifi.p2p.WifiP2pDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.rememberCoroutineScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.meshlink.app.data.MeshSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt
import com.meshlink.app.BuildConfig
import com.meshlink.app.util.AppUpdater
import com.meshlink.app.util.AppUpdate

@Composable
fun SettingsScreen(
    myUin: String,
    displayName: String,
    avatarPath: String? = null,
    onPickAvatar: () -> Unit = {},
    onRemoveAvatar: () -> Unit = {},
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
    onExportChats: () -> Unit = {},
    onLogout: () -> Unit,
) {
    var phoneDraft by remember(myPhone) { mutableStateOf(myPhone) }
    var brokerDraft by remember(onlineBroker) { mutableStateOf(onlineBroker) }
    val context = LocalContext.current
    val tokens = MeshTokens.palette
    val themeMode = ThemeController.mode
    val scope = rememberCoroutineScope()
    var updateInfo by remember { mutableStateOf<AppUpdate?>(null) }
    var updateChecking by remember { mutableStateOf(false) }
    var updateDownloading by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf(0f) }
    var updateError by remember { mutableStateOf<String?>(null) }
    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val request = QrLink.parse(result.contents.orEmpty())
        if (request == null) {
            Toast.makeText(context, "Not a MeshLink pairing QR", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val outcome = QrLink.approve(
                request,
                myUin,
                displayName,
                MeshSettings.ensureBridgeSecret(context),
            )
            val message = outcome.getOrNull()?.let { "Web linked · return to your browser" }
                ?: "Link failed: ${outcome.exceptionOrNull()?.message ?: "unknown error"}"
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // Manual pairing (WhatsApp-Web style alternative): the phone shows an
    // 8-digit code; after the browser claims it the user approves here.
    var manualSession by remember { mutableStateOf("") }
    var manualToken by remember { mutableStateOf("") }
    var manualCode by remember { mutableStateOf("") }
    var manualState by remember { mutableStateOf("waiting") }
    LaunchedEffect(manualSession) {
        if (manualSession.isBlank()) return@LaunchedEffect
        while (manualSession.isNotBlank()) {
            val result = QrLink.manualStatus(manualSession)
            result.onSuccess { info ->
                val status = info.optString("status", "pending")
                manualState = when {
                    status == "approved" -> "approved"
                    status == "claimed" || info.optBoolean("claimed") -> "claimed"
                    status == "expired" -> "expired"
                    else -> "waiting"
                }
            }.onFailure { manualState = "expired" }
            if (manualState == "approved" || manualState == "expired") break
            delay(1500)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // Profile card — the same gradient identity as the chat header.
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(tokens.headerStart, tokens.headerMid, tokens.headerEnd),
                        ),
                    ),
            ) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 30.dp, y = (-40).dp)
                        .size(150.dp)
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
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .clickable { onPickAvatar() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Avatar(displayName.ifBlank { myUin }, 64.dp, photoPath = avatarPath)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            displayName.ifBlank { "MeshLink user" },
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 19.sp,
                            color = Color.White,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "ID: $myUin",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.92f),
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Your ID lives on this phone · no account",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.78f),
                        )
                        Row {
                            TextButton(onClick = onPickAvatar) {
                                Text(
                                    if (avatarPath == null) "Add photo" else "Change photo",
                                    color = Color.White,
                                )
                            }
                            if (avatarPath != null) {
                                TextButton(onClick = onRemoveAvatar) {
                                    Text("Remove", color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle("Appearance")
        ThemeModeSelector(
            mode = themeMode,
            onSelect = { ThemeController.set(context, it) },
        )

        Spacer(Modifier.height(18.dp))
        SectionTitle("Online mode")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = tokens.rowStart),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        val relayError = status["onlineError"] as? String ?: ""
                        Text(
                            "Worldwide messaging",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                        )
                        Text(
                            when {
                                !onlineEnabled -> "Off · local radios only"
                                onlineConnected -> "Connected · messages anywhere in the world"
                                relayError.isNotBlank() -> "Retrying another route · $relayError"
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
                        TextButton(onClick = {
                            brokerDraft = com.meshlink.app.mesh.OnlineTransport.DEFAULT_BROKER
                            onSaveBroker(brokerDraft)
                        }) { Text("Default") }
                        TextButton(onClick = {
                            brokerDraft = "wss://broker.hivemq.com:8884/mqtt"
                            onSaveBroker(brokerDraft)
                        }) { Text("WebSocket") }
                        TextButton(onClick = {
                            brokerDraft = "tcp://broker.hivemq.com:1883"
                            onSaveBroker(brokerDraft)
                        }) { Text("TCP") }
                    }
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
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = tokens.rowStart),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
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
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = tokens.rowStart),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
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
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = tokens.rowStart),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                InfoRow(
                    "Cellular radio (SMS)",
                    if (status["radioSmsAvailable"] == true) "SIM ready · works anywhere with signal"
                    else if (status["smsFallbackEnabled"] == true) "SMS permission or SIM not ready"
                    else "Off · enable SMS fallback below",
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
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = tokens.rowStart),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
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
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = tokens.rowStart),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
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
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = tokens.rowStart),
                    elevation = CardDefaults.cardElevation(defaultElevation = 5.dp),
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
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = tokens.rowStart),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
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
        SectionTitle("Devices")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = tokens.rowStart),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Link web", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Scan the QR shown on meshlink web to sign in with this MeshLink ID. The code expires every 30 seconds.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        val options = ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Scan the MeshLink web QR code")
                            .setBeepEnabled(false)
                        qrScanner.launch(options)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Scan QR to link web") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            QrLink.createManual(myUin, displayName)
                                .onSuccess { info ->
                                    manualSession = info.optString("session")
                                    manualToken = info.optString("token")
                                    manualCode = info.optString("code")
                                    manualState = "waiting"
                                }
                                .onFailure {
                                    Toast.makeText(context, "Could not create pairing code", Toast.LENGTH_LONG).show()
                                }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Show pairing code instead") }
                Text(
                    "Type this 8-digit code in the web app, then approve it here.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("Backup")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = tokens.rowStart),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Export chats", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Save chats, messages and contacts as one JSON file you choose. Nothing is uploaded.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onExportChats) { Text("Export backup file") }
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle("App updates")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = tokens.rowStart),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Current version: ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Check GitHub releases for a newer MeshLink APK and install it in place.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            scope.launch {
                                updateChecking = true
                                updateError = null
                                updateInfo = null
                                val info = AppUpdater.check()
                                updateChecking = false
                                if (info == null) {
                                    updateError = "Could not reach the update server."
                                } else if (AppUpdater.isNewer(BuildConfig.VERSION_NAME, info.version)) {
                                    updateInfo = info
                                } else {
                                    updateError = "You are up to date (${BuildConfig.VERSION_NAME})."
                                }
                            }
                        },
                        enabled = !updateChecking && !updateDownloading,
                        shape = RoundedCornerShape(10.dp),
                    ) { Text(if (updateChecking) "Checking…" else "Check for updates") }
                    if (updateChecking) {
                        Spacer(Modifier.width(10.dp))
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
                updateInfo?.let { info ->
                    Spacer(Modifier.height(10.dp))
                    Text("New version: ${info.version}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    if (info.notes.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            info.notes.take(400),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                updateDownloading = true
                                updateProgress = 0f
                                val file = AppUpdater.download(context, info.apkUrl) { p ->
                                    updateProgress = p
                                }
                                updateDownloading = false
                                if (file == null) {
                                    updateError = "Download failed."
                                } else if (AppUpdater.canInstall(context)) {
                                    AppUpdater.install(context, file)
                                } else {
                                    updateError = "Allow MeshLink to install apps in Android settings."
                                }
                            }
                        },
                        enabled = !updateDownloading,
                        shape = RoundedCornerShape(10.dp),
                    ) { Text(if (updateDownloading) "Downloading…" else "Download & install") }
                    if (updateDownloading) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { updateProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                updateError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(18.dp))
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

    if (manualCode.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { manualCode = ""; manualSession = "" },
            title = { Text("Pairing code") },
            text = {
                Column {
                    Text(
                        manualCode,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when (manualState) {
                            "claimed" -> "Browser found. Tap Approve to finish linking."
                            "approved" -> "Web linked successfully. You can close this."
                            "expired" -> "Code expired. Show a new code and try again."
                            else -> "Open meshlink web → Use pairing code, and type this code."
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                when (manualState) {
                    "claimed" -> TextButton(onClick = {
                        scope.launch {
                            QrLink.approveManual(
                                manualSession,
                                manualToken,
                                myUin,
                                displayName,
                                MeshSettings.ensureBridgeSecret(context),
                            )
                                .onSuccess { manualState = "approved" }
                                .onFailure {
                                    manualState = "expired"
                                    Toast.makeText(context, "Pairing failed or expired", Toast.LENGTH_LONG).show()
                                }
                        }
                    }) { Text("Approve web login") }
                    "approved" -> TextButton(onClick = {
                        manualCode = ""; manualSession = ""; manualState = "waiting"
                    }) { Text("Done") }
                    else -> TextButton(onClick = {
                        manualCode = ""; manualSession = ""; manualState = "waiting"
                    }) { Text("Close") }
                }
            },
        )
    }
}

@Composable
private fun ThemeModeSelector(
    mode: AppThemeMode,
    onSelect: (AppThemeMode) -> Unit,
) {
    val tokens = MeshTokens.palette
    val options = listOf(
        AppThemeMode.DARK to "Dark",
        AppThemeMode.LIGHT to "Light",
        AppThemeMode.SYSTEM to "System",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(tokens.rowStart)
            .border(1.dp, tokens.hairline, RoundedCornerShape(20.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val selected = mode == value
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(
                        Brush.horizontalGradient(
                            if (selected) {
                                listOf(tokens.violet, tokens.lilac)
                            } else {
                                listOf(tokens.rowEnd, tokens.rowEnd)
                            },
                        ),
                    )
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (selected) Color.White
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Row(
        modifier = Modifier.padding(bottom = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 15.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(MeshTokens.palette.violet, MeshTokens.palette.lilac),
                    ),
                ),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun peerStatus(status: Int): String = when (status) {
    WifiP2pDevice.CONNECTED -> "connected"
    WifiP2pDevice.INVITED -> "invited"
    WifiP2pDevice.FAILED -> "failed"
    WifiP2pDevice.AVAILABLE -> "available"
    else -> "unavailable"
}
