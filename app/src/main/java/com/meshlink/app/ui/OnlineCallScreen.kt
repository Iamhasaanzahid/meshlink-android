package com.meshlink.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.meshlink.app.mesh.OnlineCallManager
import com.meshlink.app.mesh.OnlineCallManager.Phase
import com.meshlink.app.mesh.OnlineCallManager.Role

/**
 * Full-screen "online call" flow. No server: two codes are exchanged by the
 * users, then WebRTC connects the phones directly with encrypted audio.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineCallScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val ui by OnlineCallManager.state.collectAsState()

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        micGranted = granted
        if (granted) {
            pendingAction?.invoke()
        } else {
            Toast.makeText(context, "Microphone permission is needed for a call.", Toast.LENGTH_LONG).show()
        }
        pendingAction = null
    }

    fun withMic(action: () -> Unit) {
        if (micGranted) action() else {
            pendingAction = action
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun close() {
        OnlineCallManager.reset()
        onClose()
    }

    var joinCode by remember { mutableStateOf("") }
    var answerCode by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Online call", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(
                            "Direct phone-to-phone over the internet · encrypted",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { close() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }

                Spacer(Modifier.height(18.dp))

                when (ui.phase) {
                    Phase.IDLE, Phase.ENDED -> {
                        Text(
                            if (ui.phase == Phase.ENDED) "Call ended" else "Worldwide call, no server",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Works anywhere both phones have internet (Wi-Fi or mobile data). " +
                                "MeshLink has no call server: the two phones exchange one code each " +
                                "and then talk directly. Audio is encrypted end to end with " +
                                "DTLS-SRTP, so nobody in between can listen.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(Modifier.height(16.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    "I want to call someone",
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "You get a call code, send it to the other person, they send " +
                                        "one back, and the call starts.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = { withMic { OnlineCallManager.startAsCaller() } },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Filled.Call, contentDescription = null)
                                    Spacer(Modifier.size(8.dp))
                                    Text("Start a call")
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            ),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Someone sent me a call code", fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = joinCode,
                                    onValueChange = { joinCode = it },
                                    placeholder = { Text("Paste MLCALL1:… here") },
                                    minLines = 3,
                                    maxLines = 6,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        withMic { OnlineCallManager.joinWithOfferCode(joinCode) }
                                    },
                                    enabled = joinCode.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Join the call") }
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Honest note: a few very strict mobile networks block direct " +
                                "peer-to-peer audio. If a call cannot connect, switch one phone to " +
                                "Wi-Fi or a hotspot and try again. No free relay server is included.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Phase.PREPARING -> {
                        Spacer(Modifier.height(30.dp))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Preparing a secure connection…",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Phase.SHARE_CODE -> {
                        Text(
                            if (ui.role == Role.CALLER) "1. Send this call code"
                            else "Send this reply code back",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Share it with the other person over any messenger or SMS. It " +
                                "contains connection details, so send it only to the person you " +
                                "are calling.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        CodeBox(ui.code)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { copyCode(context, ui.code) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text("Copy")
                            }
                            OutlinedButton(
                                onClick = { shareCode(context, ui.code) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.Share, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text("Share")
                            }
                        }

                        if (ui.role == Role.CALLER) {
                            Spacer(Modifier.height(20.dp))
                            Text("2. Paste the reply code", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = answerCode,
                                onValueChange = { answerCode = it },
                                placeholder = { Text("Paste the MLCALL1:… answer here") },
                                minLines = 3,
                                maxLines = 6,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { OnlineCallManager.acceptAnswerCode(answerCode) },
                                enabled = answerCode.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Connect the call") }
                        } else {
                            Spacer(Modifier.height(18.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(10.dp))
                                Text(
                                    "Waiting for the caller to connect…",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Phase.CONNECTING -> {
                        Spacer(Modifier.height(40.dp))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Connecting directly to ${ui.remoteName.ifBlank { "the other phone" }}…",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            fontSize = 13.sp,
                        )
                    }

                    Phase.CONNECTED -> {
                        Spacer(Modifier.height(20.dp))
                        Text(
                            ui.remoteName.ifBlank { "MeshLink call" },
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            formatCallDuration(ui.seconds),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "encrypted · direct peer-to-peer",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(Modifier.height(36.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            CallControl(
                                icon = if (ui.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                                label = if (ui.muted) "Unmute" else "Mute",
                                active = ui.muted,
                                onClick = { OnlineCallManager.toggleMute() },
                            )
                            CallControl(
                                icon = if (ui.speakerOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                                label = "Speaker",
                                active = ui.speakerOn,
                                onClick = { OnlineCallManager.toggleSpeaker() },
                            )
                            CallControl(
                                icon = Icons.Filled.CallEnd,
                                label = "End",
                                active = true,
                                danger = true,
                                onClick = {
                                    OnlineCallManager.hangup()
                                    onClose()
                                },
                            )
                        }
                    }

                    Phase.FAILED -> {
                        Text("Call could not connect", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            ui.message ?: "Something went wrong.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { OnlineCallManager.reset() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Try again") }
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = { close() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Close")
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun CodeBox(code: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Text(
            code,
            modifier = Modifier
                .height(150.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun CallControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = when {
        danger -> MaterialTheme.colorScheme.error
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        danger || active -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(64.dp)
                .background(bg, RoundedCornerShape(50)),
        ) {
            Icon(icon, contentDescription = label, tint = fg)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun copyCode(context: Context, code: String) {
    runCatching {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("MeshLink call code", code))
        Toast.makeText(context, "Call code copied", Toast.LENGTH_SHORT).show()
    }
}

private fun shareCode(context: Context, code: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, code)
        }
        context.startActivity(Intent.createChooser(intent, "Share call code").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}

private fun formatCallDuration(total: Int): String {
    val m = total / 60
    val s = total % 60
    return "%02d:%02d".format(m, s)
}
