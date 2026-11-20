package com.meshlink.app.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
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
import com.meshlink.app.mesh.OnlineCallManager
import com.meshlink.app.mesh.CallPhase
import com.meshlink.app.service.MeshService
import com.meshlink.app.util.PermissionsHelper
import com.meshlink.app.util.isMeshLinkId
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
    val onlineCall by OnlineCallManager.state.collectAsState()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var aiOpen by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var openPeer by rememberSaveable { mutableStateOf<String?>(null) }
    var openName by rememberSaveable { mutableStateOf("") }
    var newChatOpen by remember { mutableStateOf(false) }
    var addContactOpen by remember { mutableStateOf(false) }
    var contactName by remember { mutableStateOf("") }
    var contactAddress by remember { mutableStateOf("") }
    var onlineCallOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var callPrompt by remember { mutableStateOf<Pair<String, String>?>(null) }
    var groupDialog by remember { mutableStateOf(false) }
    var groupName by remember { mutableStateOf("") }
    var groupMembers by remember { mutableStateOf("") }
    var avatarPath by remember { mutableStateOf(SessionStore.avatarPath(context)) }
    val scope = rememberCoroutineScope()
    var pendingOnlineCall by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingVideoCall by remember { mutableStateOf<Pair<String, String>?>(null) }
    var videoCallWanted by remember { mutableStateOf(false) }
    var pinnedPeers by remember { mutableStateOf(MeshSettings.pinned(context)) }
    var mutedPeers by remember { mutableStateOf(MeshSettings.muted(context)) }

    var engine by remember { mutableStateOf(MeshService.engine) }
    var status by remember { mutableStateOf<Map<String, Any>>(emptyMap()) }
    var wifiPeers by remember { mutableStateOf<List<WifiP2pDevice>>(emptyList()) }

    val tokens = MeshTokens.palette
    val isOnline = status["onlineConnected"] == true
    val presence = when {
        isOnline -> "Online"
        (status["wifiLanPeers"] as? Int ?: 0) > 0 -> "Nearby"
        status["wifiGroup"] == true -> "Nearby"
        (status["blePeers"] as? Int ?: 0) > 0 -> "Nearby"
        else -> "Offline"
    }

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

    // Calls always stay inside MeshLink: internet first (when online mode is
    // up and the other side is a UIN), then the local Wi-Fi path. Nothing
    // opens the phone's SIM dialer.
    fun beginVideoCall(peer: String, name: String) {
        if (engine?.onlineIsConnected == true) {
            OnlineCallManager.startRelayedCall(peer, name, video = true)
            onlineCallOpen = true
        } else if (!onlineEnabled) {
            videoCallWanted = true
            callPrompt = peer to name
        } else {
            Toast.makeText(
                context,
                "The relay is still connecting. Try again in a few seconds.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    val avatarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val file = withContext(Dispatchers.IO) { com.meshlink.app.util.MediaFiles.importImage(context, uri) }
                if (file != null) {
                    SessionStore.saveAvatarPath(context, file.absolutePath)
                    avatarPath = file.absolutePath
                    Toast.makeText(context, "Profile photo saved on this phone", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Could not read that photo", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val json = withContext(Dispatchers.IO) { ChatRepository.exportJson() }
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(json.toByteArray(Charsets.UTF_8))
                        } != null
                    }.getOrDefault(false)
                }
                Toast.makeText(
                    context,
                    if (ok) "Backup exported" else "Could not write the backup file",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingVideoCall
        pendingVideoCall = null
        if (granted && pending != null) {
            beginVideoCall(pending.first, pending.second)
        } else if (!granted) {
            Toast.makeText(
                context,
                "Camera permission is needed for a video call.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun startVideoCall(peer: String, name: String) {
        val isUin = peer.isMeshLinkId()
        if (!isUin) {
            Toast.makeText(context, "Video calls need a 10-digit Mhzaly ID.", Toast.LENGTH_LONG).show()
            return
        }
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingVideoCall = peer to name
            cameraLauncher.launch(android.Manifest.permission.CAMERA)
            return
        }
        beginVideoCall(peer, name)
    }

    fun startCall(peer: String, name: String) {
        val mesh = engine
        val isUin = peer.isMeshLinkId()

        // A MeshLink ID is an internet identity: always wait for the relay
        // instead of falling straight into a local radio call that immediately
        // dies when the peer is not nearby. This is the WhatsApp-style path.
        if (isUin) {
            if (!onlineEnabled) {
                // Offer to switch the relay on instead of failing with a dead end.
                callPrompt = peer to name
                return
            }
            if (mesh?.onlineIsConnected == true) {
                OnlineCallManager.startRelayedCall(peer, name)
                onlineCallOpen = true
            } else {
                pendingOnlineCall = peer to name
                videoCallWanted = false
                Toast.makeText(
                    context,
                    "Connecting to the internet relay…",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            return
        }

        // Phone-number calls only make sense over the local radio/SIM path.
        if (mesh != null && CallManager.placeCall(peer, name)) return
        Toast.makeText(
            context,
            "No local call path right now. Move closer for Wi-Fi, or save the " +
                "friend's Mhzaly ID to call over the internet.",
            Toast.LENGTH_LONG,
        ).show()
    }

    // After the user switches online mode on from the call prompt, wait for the
    // MQTT session and place the call automatically.
    LaunchedEffect(pendingOnlineCall) {
        val pending = pendingOnlineCall ?: return@LaunchedEffect
        repeat(30) {
            delay(500)
            if (engine?.onlineIsConnected == true) {
                OnlineCallManager.startRelayedCall(
                    pending.first,
                    pending.second,
                    video = videoCallWanted,
                )
                onlineCallOpen = true
                pendingOnlineCall = null
                videoCallWanted = false
                return@LaunchedEffect
            }
        }
        pendingOnlineCall = null
        Toast.makeText(
            context,
            "Could not reach the relay. Check mobile data / Wi-Fi and try again.",
            Toast.LENGTH_LONG,
        ).show()
    }

    val openChat: Chat? = openPeer?.let { peer ->
        chats.firstOrNull { it.peer == peer }
            ?: Chat(peer, openName.ifBlank { peer }, "", System.currentTimeMillis(), 0, "AUTO")
    }

    // A relayed call that ends drops straight back to the chat.
    LaunchedEffect(onlineCall.phase) {
        if (onlineCall.phase == OnlineCallManager.Phase.ENDED) onlineCallOpen = false
    }

    // Full-screen call UI takes priority.
    if (callState.phase != CallPhase.IDLE) {
        CallScreen(callState)
        return
    }

    if (groupDialog) {
        AlertDialog(
            onDismissRequest = { groupDialog = false },
            title = { Text("New group") },
            text = {
                Column {
                    OutlinedTextField(value = groupName, onValueChange = { groupName = it },
                        label = { Text("Group name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = groupMembers, onValueChange = { groupMembers = it },
                        label = { Text("Members (Mhzaly IDs)") },
                        placeholder = { Text("1234567890, 9876543210") },
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text("Everyone needs Online mode for a group to cross countries.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val title = groupName.trim()
                    if (title.isEmpty() || !groupMembers.split(Regex("[,\\s]+")).any { it.isMeshLinkId() }) {
                        Toast.makeText(context,
                            "Add a name and at least one 10-digit Mhzaly ID.",
                            Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    val gid = ChatRepository.createGroup(title, groupMembers)
                    openPeer = gid
                    openName = title
                    groupDialog = false
                    groupName = ""
                    groupMembers = ""
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { groupDialog = false }) { Text("Cancel") } },
        )
    }

    if (openChat != null) {
        ConversationScreen(
            chat = openChat,
            onBack = { openPeer = null },
            onCall = { peer, name -> startCall(peer, name) },
            onVideoCall = { peer, name -> startVideoCall(peer, name) },
        )
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.width(312.dp),
                    drawerContainerColor = tokens.drawer,
                    drawerContentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        // Identity header: avatar, name, 10-digit ID and presence.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            tokens.headerStart,
                                            tokens.headerMid,
                                            tokens.headerEnd,
                                        ),
                                    ),
                                ),
                        ) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 36.dp, y = (-46).dp)
                                    .size(180.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.radialGradient(
                                            listOf(
                                                Color.White.copy(alpha = 0.24f),
                                                Color.Transparent,
                                            ),
                                        ),
                                    ),
                            )
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 18.dp, end = 18.dp),
                            ) {
                                MeshMark(34.dp)
                            }
                            Column(
                                modifier = Modifier.padding(
                                    start = 20.dp, end = 20.dp, top = 36.dp, bottom = 22.dp,
                                ),
                            ) {
                                Avatar(
                                    displayName.ifBlank { myUin },
                                    64.dp,
                                    showOnline = isOnline,
                                    photoPath = avatarPath,
                                )
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    "Mhzaly",
                                    color = Color.White,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                                Text(
                                    "Private by design",
                                    color = BrandLilac,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.4.sp,
                                )
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    StatusPill(myUin, tokens.lilac)
                                    StatusPill(
                                        presence,
                                        if (isOnline) tokens.mint else tokens.amber,
                                    )
                                }
                            }
                        }

                        Column(Modifier.padding(horizontal = 14.dp, vertical = 18.dp)) {
                            val drawerItems = listOf(0, 1, 4, 5, 6, 2, 7)
                            drawerItems.forEach { index ->
                                val label = when (index) {
                                    0 -> "Chats"
                                    1 -> "Calls"
                                    2 -> "Nearby"
                                    4 -> "Status"
                                    5 -> "Starred"
                                    6 -> "Archived"
                                    else -> "Settings"
                                }
                                val icon = when (index) {
                                    0 -> Icons.Filled.Chat
                                    1 -> Icons.Filled.Call
                                    2 -> Icons.Filled.Wifi
                                    4 -> Icons.Filled.Info
                                    5 -> Icons.Filled.Star
                                    6 -> Icons.Filled.Archive
                                    else -> Icons.Filled.Settings
                                }
                                val accent = when (index) {
                                    0 -> tokens.violet
                                    1 -> tokens.mint
                                    2 -> tokens.sky
                                    4 -> tokens.sky
                                    5 -> tokens.amber
                                    6 -> tokens.coral
                                    else -> tokens.lilac
                                }
                                val selected = tab == index
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(54.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(
                                            Brush.horizontalGradient(
                                                if (selected) {
                                                    listOf(
                                                        accent.copy(alpha = 0.28f),
                                                        accent.copy(alpha = 0.05f),
                                                    )
                                                } else {
                                                    listOf(Color.Transparent, Color.Transparent)
                                                },
                                            ),
                                        )
                                        .clickable {
                                            tab = index
                                            scope.launch { drawerState.close() }
                                        }
                                        .padding(horizontal = 12.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (selected) {
                                            Box(
                                                Modifier
                                                    .size(width = 3.dp, height = 26.dp)
                                                    .clip(RoundedCornerShape(2.dp))
                                                    .background(accent),
                                            )
                                            Spacer(Modifier.width(12.dp))
                                        } else {
                                            Spacer(Modifier.width(15.dp))
                                        }
                                        Icon(
                                            icon,
                                            contentDescription = null,
                                            tint = if (selected) accent
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.width(16.dp))
                                        Text(
                                            label,
                                            color = if (selected) MaterialTheme.colorScheme.onSurface
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 15.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (index == 0 && chats.sumOf { it.unread } > 0) {
                                            Badge { Text(chats.sumOf { it.unread }.toString()) }
                                        }
                                    }
                                }
                            }

                            HorizontalDivider(
                                color = tokens.hairline,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp),
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(
                                                tokens.violet.copy(alpha = 0.18f),
                                                tokens.lilac.copy(alpha = 0.04f),
                                            ),
                                        ),
                                    )
                                    .clickable {
                                        aiOpen = true
                                        scope.launch { drawerState.close() }
                                    }
                                    .padding(horizontal = 16.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.AutoAwesome,
                                        contentDescription = null,
                                        tint = tokens.lilac,
                                    )
                                    Spacer(Modifier.width(16.dp))
                                    Text(
                                        "Mhzaly AI",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }
                }
            },
        ) {
        Scaffold(
            topBar = {
                if (tab != 3 && tab != 7) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(tokens.headerStart, tokens.headerMid, tokens.headerEnd),
                            ),
                        ),
                ) {
                    // Soft light blooming from the corners gives the header depth.
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 40.dp, y = (-70).dp)
                            .size(240.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                                ),
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .offset(x = (-80).dp, y = 40.dp)
                            .size(200.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(Color.White.copy(alpha = 0.12f), Color.Transparent),
                                ),
                            ),
                    )
                    Column(
                        modifier = Modifier
                            .statusBarsPadding()
                            .padding(start = 20.dp, end = 14.dp, top = 10.dp, bottom = 16.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = "Open menu", tint = Color.White)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Mhzaly",
                                    color = Color.White,
                                    fontSize = 25.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.2.sp,
                                    maxLines = 1,
                                )
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .background(
                                                if (isOnline) BrandMint else Color(0xFFFFC46B),
                                                CircleShape,
                                            ),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "$myUin · $presence",
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { tab = 4 }) {
                                    Icon(Icons.Filled.Info, contentDescription = "Status", tint = Color.White)
                                }
                                IconButton(onClick = { aiOpen = true }) {
                                    Icon(Icons.Filled.AutoAwesome, contentDescription = "Mhzaly AI", tint = Color.White)
                                }
                                IconButton(onClick = { groupDialog = true }, enabled = tab == 0) {
                                    Icon(Icons.Filled.Add, contentDescription = "New group",
                                        tint = if (tab == 0) Color.White else Color.White.copy(alpha = 0.4f))
                                }
                                Avatar(displayName.ifBlank { myUin }, 44.dp, showOnline = isOnline, photoPath = avatarPath)
                            }
                        }
                        if (tab == 0) {
                            Spacer(Modifier.height(16.dp))
                            Surface(
                                color = Color.White.copy(alpha = 0.14f),
                                contentColor = Color.White,
                                shape = RoundedCornerShape(20.dp),
                                shadowElevation = 10.dp,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp, Color.White.copy(alpha = 0.24f),
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.Search,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.9f),
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    BasicTextField(
                                        value = query,
                                        onValueChange = { query = it },
                                        singleLine = true,
                                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                                        cursorBrush = SolidColor(Color.White),
                                        modifier = Modifier.weight(1f),
                                        decorationBox = { inner ->
                                            if (query.isEmpty()) {
                                                Text(
                                                    "Search chats",
                                                    color = Color.White.copy(alpha = 0.7f),
                                                    fontSize = 14.sp,
                                                )
                                            }
                                            inner()
                                        },
                                    )
                                    if (query.isNotEmpty()) {
                                        IconButton(
                                            onClick = { query = "" },
                                            modifier = Modifier.size(48.dp),
                                        ) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = "Clear",
                                                tint = Color.White,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                }
            },
            floatingActionButton = {
                if (tab == 0) {
                    Surface(
                        onClick = { newChatOpen = true },
                        shape = RoundedCornerShape(20.dp),
                        color = Color.Transparent,
                        shadowElevation = 14.dp,
                        modifier = Modifier.height(56.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(tokens.violet, tokens.lilac),
                                    ),
                                )
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White)
                            Text(
                                "New chat",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                            )
                        }
                    }
                }
            },
            bottomBar = {
                // Elevated glassy rail: the two primary destinations stay here,
                // everything else lives in the side drawer.
                Surface(
                    color = tokens.navBar.copy(alpha = 0.97f),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    shadowElevation = 20.dp,
                    modifier = Modifier.clip(
                        RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    ),
                ) {
                Box {
                    // Slim active rail above the destinations — instantly
                    // readable even when the badge sits on the icon.
                    Row(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                    ) {
                        listOf(0, 1).forEach { index ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        if (tab == index) {
                                            Brush.horizontalGradient(
                                                listOf(tokens.violet, tokens.lilac),
                                            )
                                        } else {
                                            Brush.horizontalGradient(
                                                listOf(Color.Transparent, Color.Transparent),
                                            )
                                        },
                                    ),
                            )
                        }
                    }
                    NavigationBar(
                        containerColor = Color.Transparent,
                        tonalElevation = 0.dp,
                    ) {
                    listOf(0 to "Chats", 1 to "Calls").forEach { (index, label) ->
                        NavigationBarItem(
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.White,
                                selectedTextColor = tokens.lilac,
                                indicatorColor = tokens.violet,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = {
                                if (index == 0) {
                                    BadgedBox(badge = {
                                        val unread = chats.sumOf { it.unread }
                                        if (unread > 0) {
                                            Badge { Text(if (unread > 99) "99+" else unread.toString()) }
                                        }
                                    }) { Icon(Icons.Filled.Chat, contentDescription = label) }
                                } else {
                                    Icon(Icons.Filled.Call, contentDescription = label)
                                }
                            },
                            label = { Text(label) },
                        )
                    }
                }
                }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    0 -> Column {
                        ChatListScreen(
                            chats = chats
                                .filter {
                                    query.isBlank() ||
                                        it.name.contains(query, ignoreCase = true) ||
                                        it.peer.contains(query, ignoreCase = true)
                                }
                                .sortedWith(
                                    compareByDescending<Chat> {
                                        pinnedPeers.contains(it.peer.uppercase())
                                    }.thenByDescending { it.lastTimestamp },
                                ),
                            pinned = pinnedPeers,
                            muted = mutedPeers,
                            onTogglePin = { chat ->
                                MeshSettings.togglePinned(context, chat.peer)
                                pinnedPeers = MeshSettings.pinned(context)
                            },
                            onToggleMute = { chat ->
                                MeshSettings.toggleMuted(context, chat.peer)
                                mutedPeers = MeshSettings.muted(context)
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
                    2 -> NearbyScreen(
                        status = status,
                        wifiPeers = wifiPeers,
                        onRefreshPeers = {
                            engine?.refreshWifiPeers()
                            wifiPeers = engine?.wifiPeers() ?: emptyList()
                        },
                        onConnectPeer = { engine?.connectWifiPeer(it) },
                    )
                    3 -> AiScreen(onBack = { tab = 0 })
                    4 -> StatusScreen(
                        status = status,
                        myUin = myUin,
                        displayName = displayName,
                    )
                    5 -> StarredScreen(
                        chats = chats,
                        pinned = pinnedPeers,
                        onOpen = { chat ->
                            openPeer = chat.peer
                            openName = chat.name
                            ChatRepository.markRead(chat.peer)
                        },
                        onTogglePin = { chat ->
                            MeshSettings.togglePinned(context, chat.peer)
                            pinnedPeers = MeshSettings.pinned(context)
                        },
                        onToggleMute = { chat ->
                            MeshSettings.toggleMuted(context, chat.peer)
                            mutedPeers = MeshSettings.muted(context)
                        },
                    )
                    6 -> ArchivedScreen(
                        chats = chats,
                        muted = mutedPeers,
                        onOpen = { chat ->
                            openPeer = chat.peer
                            openName = chat.name
                            ChatRepository.markRead(chat.peer)
                        },
                        onTogglePin = { chat ->
                            MeshSettings.togglePinned(context, chat.peer)
                            pinnedPeers = MeshSettings.pinned(context)
                        },
                        onToggleMute = { chat ->
                            MeshSettings.toggleMuted(context, chat.peer)
                            mutedPeers = MeshSettings.muted(context)
                        },
                    )
                    else -> SettingsScreen(
                        myUin = myUin,
                        displayName = displayName,
                        avatarPath = avatarPath,
                        onPickAvatar = { avatarLauncher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onRemoveAvatar = {
                            SessionStore.saveAvatarPath(context, null)
                            avatarPath = null
                        },
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
                        onExportChats = { exportLauncher.launch("mhzaly-backup.json") },
                        onOpenMenu = { scope.launch { drawerState.open() } },
                        onLogout = onLogout,
                    )
                }
            }
        }
        }
    }

    // MeshLink AI slides in from the right like a chat-side copilot, instead
    // of replacing the conversation list.
    if (aiOpen) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.58f))
                    .clickable { aiOpen = false },
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(390.dp),
                color = tokens.drawer,
                shadowElevation = 24.dp,
            ) {
                AiScreen(onBack = { aiOpen = false })
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

    if (callPrompt != null) {
        AlertDialog(
            onDismissRequest = { callPrompt = null },
            title = { Text("Call over the internet?") },
            text = {
                Text(
                    "This contact is a Mhzaly ID, not a nearby radio. The call goes " +
                        "through the online relay over mobile data or any Wi-Fi — " +
                        "anywhere in the world.\n\nTurn on Online mode and connect?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val pending = callPrompt
                    callPrompt = null
                    onlineEnabled = true
                    MeshSettings.setOnlineEnabled(context, true)
                    engine?.setOnlineMode(true)
                    pendingOnlineCall = pending
                }) { Text("Turn on & call") }
            },
            dismissButton = {
                TextButton(onClick = { callPrompt = null }) { Text("Cancel") }
            },
        )
    }

    if (onlineCallOpen ||
        (onlineCall.phase != OnlineCallManager.Phase.IDLE &&
            onlineCall.phase != OnlineCallManager.Phase.ENDED)
    ) {
        OnlineCallScreen(onClose = { onlineCallOpen = false })
    }
}

