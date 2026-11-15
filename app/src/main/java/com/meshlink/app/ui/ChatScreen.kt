package com.meshlink.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.meshlink.app.api.BackendApi
import com.meshlink.app.data.SessionStore
import com.meshlink.app.mesh.TransportType
import com.meshlink.app.mesh.VoiceTransport
import com.meshlink.app.service.MeshService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Purple = Color(0xFF6750A4)
private val PurpleLight = Color(0xFFEADDFF)
private val PurpleSoft = Color(0xFFF3EDF7)
private val Muted = Color(0xFF625B71)
private val Ink = Color(0xFF1D1B20)
private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFB3261E)

data class ChatMessage(
    val sender: String,
    val text: String,
    val transport: TransportType,
    val isIncoming: Boolean,
    val delivered: Boolean = true,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(myUin: String, onLogout: () -> Unit) {
    val context = LocalContext.current
    var engine by remember { mutableStateOf(MeshService.engine) }
    val scope = rememberCoroutineScope()
    val handler = remember { Handler(Looper.getMainLooper()) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val listState = rememberLazyListState()

    var tab by remember { mutableStateOf(0) }
    var destination by remember { mutableStateOf("") }
    var messageText by remember { mutableStateOf("") }
    var selectedTransport by remember { mutableStateOf(TransportType.AUTO) }
    var status by remember { mutableStateOf<Map<String, Any>>(emptyMap()) }
    var wifiPeers by remember { mutableStateOf<List<WifiP2pDevice>>(emptyList()) }
    var voiceState by remember { mutableStateOf(VoiceTransport.State.IDLE) }
    var voiceMessage by remember { mutableStateOf("Voice idle") }
    var callNote by remember { mutableStateOf("") }

    // ---- wait for the foreground mesh service to come up ------------------
    LaunchedEffect(Unit) {
        while (engine == null) {
            delay(250)
            engine = MeshService.engine
        }
    }

    // ---- incoming messages (radio SMS text + BLE/Wi-Fi frames) ------------
    DisposableEffect(engine) {
        val mesh = engine
        mesh?.onTextForMe = { from, text ->
            handler.post {
                messages.add(
                    ChatMessage(from, text, TransportType.RADIO_SMS, isIncoming = true)
                )
            }
        }
        mesh?.onFrameForMe = { from, transport, bytes ->
            val text = decodeText(bytes)
            handler.post {
                messages.add(
                    ChatMessage(
                        from,
                        text ?: "Encrypted frame (${bytes.size} bytes)",
                        transport,
                        isIncoming = true,
                    )
                )
            }
        }
        mesh?.voice?.onState = { state, msg ->
            handler.post { voiceState = state; voiceMessage = msg }
        }
        onDispose {
            mesh?.onTextForMe = null
            mesh?.onFrameForMe = null
            mesh?.voice?.onState = null
        }
    }

    // ---- live radio status ------------------------------------------------
    LaunchedEffect(engine) {
        while (true) {
            engine?.let {
                status = it.status()
                wifiPeers = it.wifiPeers()
            }
            delay(2000)
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MeshLink", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("ID: $myUin", fontSize = 11.sp)
                    }
                },
                actions = {
                    TextButton(onClick = {
                        SessionStore.clear(context)
                        onLogout()
                    }) { Text("Logout", color = Color.White) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Purple,
                    titleContentColor = Color.White,
                ),
            )
        },
        bottomBar = {
            TabRow(selectedTabIndex = tab, containerColor = Color.White) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Chat") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Call") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Radios") })
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            when (tab) {
                0 -> ChatTab(
                    myUin = myUin,
                    messages = messages,
                    listState = listState,
                    destination = destination,
                    onDestinationChange = { destination = it },
                    messageText = messageText,
                    onMessageChange = { messageText = it },
                    selectedTransport = selectedTransport,
                    onTransportChange = { selectedTransport = it },
                    onSend = {
                        val dest = destination.trim()
                        val text = messageText.trim()
                        val mesh = engine
                        if (dest.isNotEmpty() && text.isNotEmpty() && mesh != null) {
                            val transport = selectedTransport
                            messageText = ""
                            scope.launch {
                                var sent = withContext(Dispatchers.IO) {
                                    runCatching {
                                        mesh.sendText(dest, text, transport)
                                    }.getOrDefault(false)
                                }
                                // Last resort: relay through the optional
                                // internet backend when no radio can deliver.
                                if (!sent && mesh.isRadioNumber(dest).not()) {
                                    sent = withContext(Dispatchers.IO) {
                                        runCatching {
                                            BackendApi().sendEncryptedMessage(dest, text)
                                        }.getOrDefault(false)
                                    }
                                }
                                messages.add(
                                    ChatMessage(
                                        sender = "Me → $dest",
                                        text = text,
                                        transport = transport,
                                        isIncoming = false,
                                        delivered = sent,
                                    )
                                )
                            }
                        }
                    },
                )
                1 -> CallTab(
                    destination = destination,
                    onDestinationChange = { destination = it },
                    callNote = callNote,
                    voiceMessage = voiceMessage,
                    voiceState = voiceState,
                    groupReady = engine?.wifiHasGroup == true,
                    onSimCall = {
                        val number = destination.trim()
                        if (engine?.isRadioNumber(number) != true) {
                            callNote = "Enter a phone number (7–15 digits) to call over the SIM."
                        } else if (ContextCompat.checkSelfPermission(
                                context, Manifest.permission.CALL_PHONE
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            // No CALL_PHONE permission: still open the dialer.
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                                )
                                callNote = "Phone permission missing — opened the dialer instead."
                            } catch (t: Throwable) {
                                callNote = "Grant the Phone permission to place calls."
                            }
                        } else {
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
                                )
                                callNote = "Calling $number over the cellular radio…"
                            } catch (t: Throwable) {
                                callNote = "Could not start the call: ${t.message}"
                            }
                        }
                    },
                    onStartVoice = {
                        callNote = if (engine?.startVoice() == true) {
                            "Voice group open — hold the button to talk."
                        } else {
                            "Connect a Wi-Fi Direct peer first (Radios tab)."
                        }
                    },
                    onStopVoice = { engine?.stopVoice() },
                    onTalkStart = { engine?.voice?.startTalking() },
                    onTalkStop = { engine?.voice?.stopTalking() },
                )
                else -> RadiosTab(
                    status = status,
                    wifiPeers = wifiPeers,
                    onConnectPeer = { device -> engine?.connectWifiPeer(device) },
                    onRefreshWifi = { wifiPeers = engine?.wifiPeers() ?: emptyList() },
                )
            }
        }
    }
}

// ------------------------------------------------------------------ chat tab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTab(
    myUin: String,
    messages: List<ChatMessage>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    destination: String,
    onDestinationChange: (String) -> Unit,
    messageText: String,
    onMessageChange: (String) -> Unit,
    selectedTransport: TransportType,
    onTransportChange: (TransportType) -> Unit,
    onSend: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text("Send via", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Muted)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TransportType.values().forEach { type ->
                FilterChip(
                    selected = selectedTransport == type,
                    onClick = { onTransportChange(type) },
                    label = {
                        Text(
                            when (type) {
                                TransportType.AUTO -> "Auto"
                                TransportType.RADIO_SMS -> "SIM"
                                TransportType.BLUETOOTH_LE -> "Bluetooth"
                                TransportType.WIFI_DIRECT -> "Wi-Fi"
                            },
                            fontSize = 11.sp,
                        )
                    },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(PurpleSoft, RoundedCornerShape(14.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Text(
                        "No messages yet.\n\nEnter a friend's UIN or phone number below. " +
                            "Messages go over Bluetooth/Wi-Fi when nearby, or over the SIM " +
                            "(SMS radio) when far away — no internet needed.",
                        color = Muted,
                        fontSize = 13.sp,
                    )
                }
            }
            items(messages) { msg ->
                MessageBubble(msg)
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = destination,
            onValueChange = onDestinationChange,
            label = { Text("Friend's UIN or phone number") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = messageText,
                onValueChange = onMessageChange,
                label = { Text("Message") },
                modifier = Modifier.weight(1f),
                maxLines = 3,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Send,
                ),
            )
            Button(
                onClick = onSend,
                enabled = destination.isNotBlank() && messageText.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Purple),
            ) { Text("Send") }
        }

        Spacer(Modifier.height(4.dp))
        Text("Your ID: $myUin", fontSize = 10.sp, color = Muted)
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (msg.isIncoming) Alignment.Start else Alignment.End,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (msg.isIncoming) PurpleLight else Purple
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(0.86f),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    "${msg.sender} · ${transportLabel(msg.transport)}" +
                        if (!msg.delivered) " · not sent" else "",
                    fontSize = 10.sp,
                    color = if (msg.isIncoming) Muted else PurpleLight,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    msg.text,
                    fontSize = 14.sp,
                    color = if (msg.isIncoming) Ink else Color.White,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ call tab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallTab(
    destination: String,
    onDestinationChange: (String) -> Unit,
    callNote: String,
    voiceMessage: String,
    voiceState: VoiceTransport.State,
    groupReady: Boolean,
    onSimCall: () -> Unit,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onTalkStart: () -> Unit,
    onTalkStop: () -> Unit,
) {
    var talking by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Call", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(4.dp))
        Text(
            "Use the SIM when you have signal, or Wi-Fi Direct for free local calls.",
            fontSize = 12.sp,
            color = Muted,
        )
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = destination,
            onValueChange = onDestinationChange,
            label = { Text("Phone number or peer UIN") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onSimCall,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Green),
        ) { Text("Call over SIM (cellular radio)") }

        Spacer(Modifier.height(10.dp))

        OutlinedButton(
            onClick = onStartVoice,
            enabled = groupReady,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(
                if (groupReady) "Join Wi-Fi Direct voice" else "Wi-Fi Direct peer needed",
                fontSize = 13.sp,
            )
        }

        Spacer(Modifier.height(18.dp))

        Box(
            modifier = Modifier
                .size(140.dp)
                .background(
                    if (talking) Red else Purple,
                    RoundedCornerShape(70.dp),
                )
                .pointerInput(groupReady) {
                    detectTapGestures(
                        onPress = {
                            if (groupReady) {
                                talking = true
                                onTalkStart()
                                tryAwaitRelease()
                                talking = false
                                onTalkStop()
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (talking) "RELEASE\nTO STOP" else "HOLD\nTO TALK",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(voiceMessage, fontSize = 12.sp, color = Muted)

        if (voiceState != VoiceTransport.State.IDLE) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onStopVoice, shape = RoundedCornerShape(12.dp)) {
                Text("Leave voice")
            }
        }

        if (callNote.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(callNote, fontSize = 12.sp, color = Purple, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Local calls use an 8 kHz voice link over the Wi-Fi Direct radio. " +
                "The SIM call uses your operator's normal cellular voice channel.",
            fontSize = 10.sp,
            color = Muted,
        )
    }
}

// ---------------------------------------------------------------- radios tab

@Composable
private fun RadiosTab(
    status: Map<String, Any>,
    wifiPeers: List<WifiP2pDevice>,
    onConnectPeer: (WifiP2pDevice) -> Unit,
    onRefreshWifi: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Radios", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(
                "All three radios run at the same time. The app picks the best one " +
                    "for every message.",
                fontSize = 12.sp,
                color = Muted,
            )
        }

        item {
            StatusCard(
                "Cellular radio (SIM / SMS)",
                "City-wide · needs a SIM signal",
                status["radioSmsAvailable"] == true,
            )
        }
        item {
            StatusCard(
                "Bluetooth LE",
                "${status["blePeers"] ?: 0} peer(s) · 10–50 m per hop",
                status["bluetoothLeActive"] == true,
            )
        }
        item {
            val group = status["wifiGroup"] == true
            val owner = status["wifiGroupOwner"] == true
            StatusCard(
                "Wi-Fi Direct",
                if (group) {
                    "Group formed · ${if (owner) "owner" else "client"} · " +
                        "${status["wifiGroupOwnerIp"]}"
                } else {
                    "${status["wifiPeers"] ?: 0} peer(s) found · 50–200 m per hop"
                },
                status["wifiDirectActive"] == true,
            )
        }

        item {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Nearby Wi-Fi Direct peers", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                TextButton(onClick = onRefreshWifi) { Text("Refresh") }
            }
        }

        if (wifiPeers.isEmpty()) {
            item {
                Text(
                    "No Wi-Fi Direct peers yet. Keep this screen open on both phones — " +
                        "they connect automatically when they see each other.",
                    fontSize = 12.sp,
                    color = Muted,
                )
            }
        } else {
            items(wifiPeers) { device ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
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
                                device.deviceName?.takeIf { it.isNotBlank() }
                                    ?: "Android device",
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                            )
                            Text(
                                "${device.deviceAddress} · ${peerStatus(device.status)}",
                                fontSize = 11.sp,
                                color = Muted,
                            )
                        }
                        Button(
                            onClick = { onConnectPeer(device) },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Purple),
                        ) { Text("Connect", fontSize = 12.sp) }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(10.dp))
            Text(
                "Node ID: ${status["localNodeId"] ?: "—"} · Mesh TTL 5 hops",
                fontSize = 11.sp,
                color = Muted,
            )
        }
    }
}

@Composable
private fun StatusCard(title: String, subtitle: String, active: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(if (active) Green else Red, RoundedCornerShape(50)),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Ink)
                Text(subtitle, fontSize = 11.sp, color = Muted)
            }
        }
    }
}

// ------------------------------------------------------------------- helpers

private fun transportLabel(type: TransportType): String = when (type) {
    TransportType.RADIO_SMS -> "SIM radio"
    TransportType.BLUETOOTH_LE -> "Bluetooth"
    TransportType.WIFI_DIRECT -> "Wi-Fi Direct"
    TransportType.AUTO -> "Auto"
}

private fun peerStatus(status: Int): String = when (status) {
    WifiP2pDevice.CONNECTED -> "connected"
    WifiP2pDevice.INVITED -> "invited"
    WifiP2pDevice.FAILED -> "failed"
    WifiP2pDevice.AVAILABLE -> "available"
    else -> "unavailable"
}

/** Returns a printable UTF-8 string, or null when the payload is binary. */
private fun decodeText(bytes: ByteArray): String? {
    val text = try {
        bytes.toString(Charsets.UTF_8)
    } catch (_: Throwable) {
        return null
    }
    if (text.isEmpty()) return null
    val printable = text.count { it == '\n' || it == '\r' || it == '\t' || it.code in 32..126 }
    return if (printable >= (text.length * 9) / 10) text else null
}
