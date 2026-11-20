package com.meshlink.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.meshlink.app.mesh.CallManager
import com.meshlink.app.mesh.CallPhase
import com.meshlink.app.mesh.CallUiState
import kotlinx.coroutines.delay

private val CallBackground = Color(0xFF1A1423)
private val CallAccent = Color(0xFF6750A4)

@Composable
fun CallScreen(state: CallUiState) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> micGranted = granted }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val elapsed = if (state.connectedAt > 0) ((now - state.connectedAt) / 1000L).toInt() else 0
    val status = when (state.phase) {
        CallPhase.DIALING -> state.note.ifBlank { "Ringing…" }
        CallPhase.RINGING -> state.note.ifBlank { "Incoming MeshLink call" }
        CallPhase.CONNECTING -> state.note.ifBlank { "Connecting…" }
        CallPhase.ACTIVE -> formatDuration(elapsed)
        CallPhase.ENDED -> state.note.ifBlank { "Call ended" }
        CallPhase.IDLE -> ""
    }

    Surface(color = CallBackground, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Avatar(state.name.ifBlank { state.peer }, 112.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                state.name.ifBlank { state.peer },
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                status,
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(8.dp))
            if (!micGranted && state.phase != CallPhase.IDLE) {
                Text(
                    "Microphone access is off. Calls need it for voice.",
                    color = Color(0xFFFFB4AB),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Button(onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Text("Grant microphone")
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (state.phase == CallPhase.ACTIVE) OnlineGreen else CallAccent),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${state.transport.ifBlank { "Wi-Fi" }} · direct, no server",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                )
            }

            Spacer(Modifier.weight(1f))

            if (state.phase == CallPhase.ENDED) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    CallAction(
                        icon = Icons.Filled.Close,
                        label = "Close",
                        background = Color(0xFF322B3D),
                        onClick = { CallManager.clearEnded() },
                    )
                }
            } else if (state.phase == CallPhase.RINGING) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    CallAction(
                        icon = Icons.Filled.Close,
                        label = "Decline",
                        background = Color(0xFFB3261E),
                        onClick = { CallManager.decline() },
                    )
                    CallAction(
                        icon = Icons.Filled.Call,
                        label = "Accept",
                        background = Color(0xFF2E7D32),
                        onClick = { CallManager.accept() },
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    CallAction(
                        icon = if (state.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        label = if (state.muted) "Unmute" else "Mute",
                        background = if (state.muted) CallAccent else Color(0xFF322B3D),
                        onClick = { CallManager.toggleMute() },
                    )
                    CallAction(
                        icon = Icons.Filled.VolumeUp,
                        label = if (state.speakerOn) "Speaker" else "Earpiece",
                        background = if (state.speakerOn) CallAccent else Color(0xFF322B3D),
                        onClick = { CallManager.toggleSpeaker() },
                    )
                    CallAction(
                        icon = Icons.Filled.CallEnd,
                        label = "End",
                        background = Color(0xFFB3261E),
                        onClick = { CallManager.hangUp() },
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun CallAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    background: Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(background),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
                Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
    }
}
