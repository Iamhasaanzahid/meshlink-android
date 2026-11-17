package com.meshlink.app.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.meshlink.app.data.Chat
import com.meshlink.app.data.ChatRepository
import com.meshlink.app.data.MeshSettings
import com.meshlink.app.data.SessionStore
import com.meshlink.app.mesh.CallManager
import com.meshlink.app.mesh.CallPhase
import com.meshlink.app.service.MeshService
import com.meshlink.app.util.PermissionsHelper
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    myUin: String,
    displayName: String,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val chats by ChatRepository.chats.collectAsState()
    val calls by ChatRepository.calls.collectAsState()
    val contacts by ChatRepository.contacts.collectAsState()
    val callState by CallManager.state.collectAsState()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openPeer by rememberSaveable { mutableStateOf<String?>(null) }
    var openName by rememberSaveable { mutableStateOf("") }
    var newChatOpen by remember { mutableStateOf(false) }
    var addContactOpen by remember { mutableStateOf(false) }
    var contactName by remember { mutableStateOf("") }
    var contactAddress by remember { mutableStateOf("") }
    var simFallback by remember { mutableStateOf<Pair<String, String>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var onlineCallOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    var maxHops by remember { mutableStateOf(MeshSettings.maxHops(context)) }
    var myPhone by remember { mutableStateOf(SessionStore.phone(context)) }
    var smsFallback by remember { mutableStateOf(MeshSettings.smsFallback(context)) }
    var missingPerms by remember { mutableStateOf(PermissionsHelper.missing(context)) }
    var onlineEnabled by remember { mutableStateOf(MeshSettings.onlineEnabled(context)) }
    var onlineBroker by remember { mutableStateOf(MeshSettings.onlineBroker(context)) }

    // The permission list must refresh after the user answers an Android
    // dialog or comes back from system settings, otherwise the Settings card
    // keeps saying "missing" on some phones even after everything is granted.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                missingPerms = PermissionsHelper.missing(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val corePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        missingPerms = PermissionsHelper.missing(context)
        if (missingPerms.isNotEmpty()) {
            Toast.makeText(
                context,
                "Still missing ${missingPerms.size} permission(s). Tap Allow again, or open Android settings.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun requestCorePermissions() {
        val missing = PermissionsHelper.missing(context)
        missingPerms = missing
        if (missing.isEmpty()) {
            Toast.makeText(context, "All permissions are already allowed.", Toast.LENGTH_SHORT).show()
        } else {
            corePermissionLauncher.launch(missing.toTypedArray())
        }
    }

    // SMS permission is requested only when the user switches SMS fallback on,
    // so first launch never shows Android's "SMS can cost money" warning.
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (PermissionsHelper.smsGranted(context)) {
            MeshSettings.setSmsFallback(context, true)
            smsFallback = true
        } else {
            MeshSettings.setSmsFallback(context, false)
            smsFallback = false
            Toast.makeText(
                context,
                "SMS fallback needs the SMS permission. Everything else keeps working without it.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun setSmsFallback(enabled: Boolean) {
        if (!enabled) {
            MeshSettings.setSmsFallback(context, false)
            smsFallback = false
        } else if (PermissionsHelper.smsGranted(context)) {
            MeshSettings.setSmsFallback(context, true)
            smsFallback = true
        } else {
            smsPermissionLauncher.launch(PermissionsHelper.smsPermissions())
        }
    }

    var engine by remember { mutableStateOf(MeshService.engine) }
    var status by remember { mutableStateOf<Map<String, Any>>(emptyMap()) }
    var wifiPeers by remember { mutableStateOf<List<WifiP2pDevice>>(emptyList()) }

    LaunchedEffect(Unit) {
        while (engine == null) {
            delay(200)
            engine = MeshService.engine
        }
    }
    LaunchedEffect(engine) {
        while (true) {
            engine?.let {
                status = it.status()
                wifiPeers = it.wifiPeers()
            }
            delay(2_500)
        }
    }

    fun startCall(peer: String, name: String) {
        val mesh = engine
        val overMesh = mesh != null && CallManager.placeCall(peer, name)
        if (!overMesh) simFallback = peer to name
    }

    val openChat: Chat? = openPeer?.let { peer ->
        chats.firstOrNull { it.peer == peer }
            ?: Chat(peer, openName.ifBlank { peer }, "", System.currentTimeMillis(), 0, "AUTO")
    }

    // Full-screen call UI takes priority.
    if (callState.phase != CallPhase.IDLE) {
        CallScreen(callState)
        return
    }

    if (openChat != null) {
        ConversationScreen(
            chat = openChat,
            onBack = { openPeer = null },
            onCall = { peer, name -> startCall(peer, name) },
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("MeshLink", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            val presence = when {
                                status["onlineConnected"] == true -> "online"
                                (status["wifiLanPeers"] as? Int ?: 0) > 0 -> "nearby"
                                status["wifiGroup"] == true -> "nearby"
                                (status["blePeers"] as? Int ?: 0) > 0 -> "nearby"
                                else -> "offline"
                            }
                            Text("ID: $myUin · $presence", fontSize = 11.sp)
                        }
                    },
                    actions = {
                        if (tab == 0) {
                            IconButton(onClick = { searching = !searching; query = "" }) {
                                Icon(Icons.Filled.Search, contentDescription = "Search", tint = Color.White)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = Color.White,
                    ),
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = {
                        when (tab) {
                            2 -> { contactName = ""; contactAddress = ""; addContactOpen = true }
                            else -> newChatOpen = true
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "New")
                }
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = {
                            BadgedBox(
                                badge = {
                                    val unread = chats.sumOf { it.unread }
                                    if (unread > 0) {
                                        Badge { Text(if (unread > 99) "99+" else unread.toString()) }
                                    }
                                },
                            ) { Icon(Icons.Filled.Chat, contentDescription = "Chats") }
                        },
                        label = { Text("Chats") },
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Filled.Call, contentDescription = "Calls") },
                        label = { Text("Calls") },
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Filled.Person, contentDescription = "Contacts") },
                        label = { Text("Contacts") },
                    )
                    NavigationBarItem(
                        selected = tab == 3,
                        onClick = { tab = 3 },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                    )
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    0 -> Column {
                        if (searching) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = { Text("Search chats") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                        ChatListScreen(
                            chats = chats.filter {
                                query.isBlank() ||
                                    it.name.contains(query, ignoreCase = true) ||
                                    it.peer.contains(query, ignoreCase = true)
                            },
                            onOpen = { chat ->
                                openPeer = chat.peer
                                openName = chat.name
                                ChatRepository.markRead(chat.peer)
                            },
                        )
                    }
                    1 -> CallsScreen(
                        calls = calls,
                        onCall = { peer, name -> startCall(peer, name) },
                        onOpenChat = { peer, name ->
                            openPeer = peer
                            openName = name
                        },
                        onOnlineCall = { onlineCallOpen = true },
                    )
                    2 -> ContactsScreen(
                        contacts = contacts,
                        onOpen = { contact ->
                            openPeer = contact.address
                            openName = contact.name
                        },
                        onDelete = { ChatRepository.deleteContact(it.address) },
                    )
                    else -> SettingsScreen(
                        myUin = myUin,
                        displayName = displayName,
                        status = status,
                        wifiPeers = wifiPeers,
                        maxHops = maxHops,
                        onMaxHopsChange = {
                            maxHops = it
                            MeshSettings.setMaxHops(context, it)
                        },
                        myPhone = myPhone,
                        onSavePhone = {
                            myPhone = it
                            SessionStore.savePhone(context, it)
                        },
                        missingPermissions = missingPerms.size,
                        missingTitles = PermissionsHelper.core()
                            .filter { !PermissionsHelper.granted(context, it.permission) }
                            .map { it.title }
                            .distinct(),
                        onRequestPermissions = { requestCorePermissions() },
                        onlineEnabled = onlineEnabled,
                        onlineConnected = status["onlineConnected"] == true,
                        onlineBroker = onlineBroker,
                        onToggleOnline = { enabled ->
                            onlineEnabled = enabled
                            engine?.setOnlineMode(enabled)
                        },
                        onSaveBroker = { url ->
                            onlineBroker = url
                            MeshSettings.setOnlineBroker(context, url)
                            engine?.restartOnline()
                        },
                        smsFallback = smsFallback,
                        onToggleSmsFallback = { setSmsFallback(it) },
                        onOpenSystemSettings = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:" + context.packageName),
                                    )
                                )
                            }
                        },
                        onConnectPeer = { engine?.connectWifiPeer(it) },
                        onRefreshPeers = {
                            engine?.refreshWifiPeers()
                            wifiPeers = engine?.wifiPeers() ?: emptyList()
                        },
                        onLogout = onLogout,
                    )
                }
            }
        }
    }

    if (newChatOpen) {
        NewChatDialog(
            contacts = contacts,
            onDismiss = { newChatOpen = false },
            onStart = { peer, name ->
                newChatOpen = false
                if (name.isNotBlank()) ChatRepository.addContact(name, peer)
                openPeer = peer
                openName = name.ifBlank { peer }
            },
        )
    }

    if (addContactOpen) {
        AlertDialog(
            onDismissRequest = { addContactOpen = false },
            title = { Text("New contact") },
            text = {
                Column {
                    OutlinedTextField(
                        value = contactName,
                        onValueChange = { contactName = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = contactAddress,
                        onValueChange = { contactAddress = it },
                        label = { Text("UIN or phone number") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        ChatRepository.addContact(contactName, contactAddress)
                        addContactOpen = false
                    },
                    enabled = contactAddress.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { addContactOpen = false }) { Text("Cancel") } },
        )
    }

    simFallback?.let { (peer, name) ->
        AlertDialog(
            onDismissRequest = { simFallback = null },
            title = { Text("Wi-Fi Direct not connected") },
            text = {
                Text(
                    "In-app calls travel phone-to-phone over Wi-Fi Direct — free and " +
                        "without internet. Both phones must be connected in a Wi-Fi " +
                        "Direct group first (Settings → Nearby phones).\n\n" +
                        "You can call $name over the normal SIM network instead.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    simFallback = null
                    dialSim(context, peer)
                }) { Text("Call over SIM") }
            },
            dismissButton = { TextButton(onClick = { simFallback = null }) { Text("Cancel") } },
        )
    }

    if (onlineCallOpen) {
        OnlineCallScreen(onClose = { onlineCallOpen = false })
    }
}

private fun dialSim(context: android.content.Context, target: String) {
    if (!target.matches(Regex("^\\+?[0-9]{3,15}$"))) {
        Toast.makeText(context, "Save a phone number to call over the SIM.", Toast.LENGTH_LONG).show()
        return
    }
    val granted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.CALL_PHONE,
    ) == PackageManager.PERMISSION_GRANTED
    val intent = Intent(if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:$target"))
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(context, "Cannot open the dialer.", Toast.LENGTH_SHORT).show()
        }
}
