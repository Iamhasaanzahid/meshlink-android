package com.meshlink.app.ui

import android.content.Context
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val AI_PREFS = "meshlink_ai"
private const val KEY_BASE_URL = "ollama_base_url"
private const val DEFAULT_BASE_URL = "http://192.168.1.50:11434"

private val AI_MODELS = listOf("llama3.2", "phi3", "qwen2.5")
private val AI_PROMPTS = listOf(
    "Summarize my chats",
    "How does offline mesh work?",
    "Tell me about calls",
    "Explain my vault PIN",
    "How does QR login work?",
)

private data class AiMessage(val role: String, val text: String) {
    val isUser: Boolean get() = role == "user"
}

private data class EngineReply(val text: String, val online: Boolean)

/**
 * MeshLink AI — on-device intelligence with a built-in offline fallback.
 * Prompts are sent to a local Ollama engine; nothing is uploaded to a cloud.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE)
    }
    var baseUrl by remember {
        mutableStateOf(prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL)
    }
    var model by remember { mutableStateOf(AI_MODELS.first()) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var isTyping by remember { mutableStateOf(false) }
    var engineOnline by remember { mutableStateOf(false) }
    var checkingEngine by remember { mutableStateOf(true) }
    val messages = remember {
        mutableStateListOf(
            AiMessage(
                "assistant",
                "Salam! Main MeshLink AI — your on-device assistant. Ask me about your chats, groups, calls, offline mesh, vault PIN or QR linking. 💜",
            ),
        )
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    fun saveBaseUrl(value: String) {
        baseUrl = value
        prefs.edit().putString(KEY_BASE_URL, value).apply()
    }

    suspend fun refreshEngineStatus() {
        checkingEngine = true
        engineOnline = probeEngine(baseUrl)
        checkingEngine = false
    }

    fun ask(question: String) {
        val text = question.trim()
        if (text.isBlank() || isTyping) return
        input = ""
        keyboard?.hide()
        messages.add(AiMessage("user", text))
        isTyping = true
        scope.launch {
            val history = messages.takeLast(12).map { AiMessage(it.role, it.text) }
            val reply = chatWithEngine(baseUrl, model, history, text)
            engineOnline = reply.online
            messages.add(AiMessage("assistant", reply.text))
            isTyping = false
        }
    }

    LaunchedEffect(Unit) { refreshEngineStatus() }

    LaunchedEffect(messages.size, isTyping) {
        val last = if (isTyping) messages.size else messages.size - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ------------------------------------------------ purple header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(BrandDeep, BrandViolet, BrandLight),
                        ),
                    )
                    .padding(horizontal = 12.dp, vertical = 16.dp),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White,
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "MeshLink AI",
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "On-device intelligence",
                                color = Color.White.copy(alpha = 0.88f),
                                fontSize = 12.sp,
                            )
                        }
                        AssistChip(
                            onClick = { scope.launch { refreshEngineStatus() } },
                            label = {
                                Text(
                                    when {
                                        checkingEngine -> "Checking…"
                                        engineOnline -> "Engine online"
                                        else -> "Engine offline"
                                    },
                                    fontSize = 10.sp,
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (engineOnline) {
                                    BrandMint.copy(alpha = 0.22f)
                                } else {
                                    Color.White.copy(alpha = 0.16f)
                                },
                                labelColor = Color.White,
                            ),
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ExposedDropdownMenuBox(
                            expanded = modelMenuOpen,
                            onExpandedChange = { modelMenuOpen = !modelMenuOpen },
                            modifier = Modifier.weight(1f),
                        ) {
                            OutlinedTextField(
                                value = model,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Model") },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(modelMenuOpen)
                                },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = headerFieldColors(),
                            )
                            ExposedDropdownMenu(
                                expanded = modelMenuOpen,
                                onDismissRequest = { modelMenuOpen = false },
                            ) {
                                AI_MODELS.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option) },
                                        onClick = {
                                            model = option
                                            modelMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                messages.clear()
                                messages.add(
                                    AiMessage(
                                        "assistant",
                                        "Chat cleared. What would you like to know about MeshLink? 💜",
                                    ),
                                )
                            },
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Clear chat",
                                tint = Color.White,
                            )
                        }
                    }
                }
            }

            // ------------------------------------------- engine URL settings
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Ollama base URL",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { scope.launch { refreshEngineStatus() } }) {
                            Text("Check", fontSize = 11.sp)
                        }
                    }
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { saveBaseUrl(it) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = chatFieldColors(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            scope.launch { refreshEngineStatus() }
                            keyboard?.hide()
                        }),
                    )
                }
            }

            // -------------------------------------------------- chat header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Conversation",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                if (!engineOnline && !checkingEngine) {
                    AssistChip(
                        onClick = {},
                        label = { Text("Built-in engine", fontSize = 10.sp) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = BrandMint.copy(alpha = 0.16f),
                            labelColor = BrandDeep,
                        ),
                    )
                }
            }

            // ---------------------------------------------------- messages
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages) { message -> ChatBubble(message) }
                if (isTyping) {
                    item { TypingBubble() }
                }
            }

            // ------------------------------------------------ quick prompts
            if (messages.size == 1 && !isTyping) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    AI_PROMPTS.forEach { prompt ->
                        AssistChip(
                            onClick = { ask(prompt) },
                            label = { Text(prompt, fontSize = 11.sp) },
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                }
            }

            // ----------------------------------------------------- composer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Message MeshLink AI…") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    shape = RoundedCornerShape(18.dp),
                    colors = chatFieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { ask(input) }),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { ask(input) },
                    enabled = input.isNotBlank() && !isTyping,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (input.isNotBlank() && !isTyping) {
                                BrandViolet
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
                ) {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = "Send message",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun headerFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.White.copy(alpha = 0.14f),
    unfocusedContainerColor = Color.White.copy(alpha = 0.10f),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = Color.White.copy(alpha = 0.65f),
    unfocusedBorderColor = Color.White.copy(alpha = 0.35f),
    focusedLabelColor = Color.White.copy(alpha = 0.9f),
    unfocusedLabelColor = Color.White.copy(alpha = 0.75f),
)

@Composable
private fun chatFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    focusedBorderColor = BrandViolet,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    focusedLabelColor = BrandViolet,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    cursorColor = BrandViolet,
)

@Composable
private fun ChatBubble(message: AiMessage) {
    val mine = message.isUser
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            Text(
                if (mine) "You" else "MeshLink AI",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(
                        if (mine) {
                            RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp)
                        } else {
                            RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
                        },
                    )
                    .background(if (mine) BrandViolet else MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    message.text,
                    fontSize = 14.sp,
                    color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun TypingBubble() {
    val transition = rememberInfiniteTransition(label = "ai-typing")
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(0, 160, 320).forEach { delay ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = delay),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot-$delay",
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)),
            )
            Spacer(Modifier.width(5.dp))
        }
    }
}

/** Quick status probe for the chip. Never blocks a Compose frame. */
private suspend fun probeEngine(base: String): Boolean = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        connection = URL(base.trimEnd('/') + "/api/tags").openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.responseCode in 200..299
    } catch (e: Exception) {
        false
    } finally {
        connection?.disconnect()
    }
}

/** Talks to Ollama's /api/chat; falls back to the built-in responder. */
private suspend fun chatWithEngine(
    base: String,
    model: String,
    history: List<AiMessage>,
    prompt: String,
): EngineReply = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        val messages = JSONArray()
        history.forEach { message ->
            messages.put(
                JSONObject()
                    .put("role", message.role)
                    .put("content", message.text),
            )
        }
        val payload = JSONObject()
            .put("model", model)
            .put("stream", false)
            .put("messages", messages)
            .toString()

        connection = URL(base.trimEnd('/') + "/api/chat").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 120_000
        connection.readTimeout = 120_000
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        connection.doOutput = true
        connection.outputStream.use { out ->
            out.write(payload.toByteArray(Charsets.UTF_8))
        }

        val code = connection.responseCode
        val body = connection.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            EngineReply(localReply(prompt), false)
        } else {
            val json = JSONObject(body)
            val content = (json.optJSONObject("message")?.optString("content", "").orEmpty())
                .ifBlank { json.optString("response", "") }
            if (content.isBlank()) {
                EngineReply(localReply(prompt), false)
            } else {
                EngineReply(content.trim(), true)
            }
        }
    } catch (e: IOException) {
        EngineReply(localReply(prompt), false)
    } catch (e: Exception) {
        EngineReply(localReply(prompt), false)
    } finally {
        connection?.disconnect()
    }
}

/** Built-in MeshLink answers when no local engine is reachable. */
private fun localReply(question: String): String {
    val low = question.trim().lowercase()
    return when {
        Regex("^(hi|hello|salam|assalam|yo)\\b").containsMatchIn(low) ->
            "Salam! Main MeshLink AI — your offline assistant. Ask me about chats, groups, calls or the mesh. 💜"

        Regex("summar|recap|overview|chats?|messages?").containsMatchIn(low) ->
            "MeshLink keeps your chats in an encrypted local vault on this phone. Open the Chats tab to review conversations and unread messages — nothing is uploaded to a cloud. I can help draft a reply or explain any feature."

        Regex("group|community|broadcast").containsMatchIn(low) ->
            "Groups live on your device and sync through the mesh when peers are nearby. Create a group, add contacts by MeshLink ID, and messages relay hop-by-hop even with no internet."

        Regex("call|voice|video|ptt|walkie").containsMatchIn(low) ->
            "Calls have 3 tiers: encrypted WebRTC audio/video when online, local Wi-Fi Direct / mesh P2P with push-to-talk, and an offline voice-note fallback that syncs later through the outbox."

        Regex("offline|mesh|hotspot|lan|relay|radio").containsMatchIn(low) ->
            "MeshLink has 3 layers: 1) online relay when internet is available, 2) local hotspot/LAN radio mesh (Bluetooth LE, Wi-Fi Direct, SMS) when it is not, 3) store-and-forward with dedupe so messages move automatically when connectivity returns."

        Regex("ai|model|ollama|engine|intelligence").containsMatchIn(low) ->
            "I am MeshLink AI, a free on-device assistant. In the code the integration is called the Ollama adapter (llama3.2, phi3, qwen2.5), but the product name is always MeshLink AI. Change the engine URL above; if it is unreachable, this built-in responder answers instead."

        Regex("vault|pin|encrypt|secur|password|privacy|hack").containsMatchIn(low) ->
            "Your vault uses AES-256-GCM with a PBKDF2-SHA256 key. A 6-digit app PIN adds a second layer and auto-locks after 5 minutes. Your email is only a security verification — your MeshLink ID stays your identity."

        Regex("qr|link|web|device|login").containsMatchIn(low) ->
            "QR linking pairs this phone with MeshLink Web: open Settings → Link device, scan the QR on the website, then approve it here. The session is short-lived and approval never shares your messages."

        Regex("id|identity|number|email").containsMatchIn(low) ->
            "Your MeshLink ID is generated on this device and never changes. Your verified email is only a security check — it is not your identity and is never shown to the people you chat with."

        Regex("reply|draft|write|compose").containsMatchIn(low) ->
            "Try: \"Bhai, message mil gaya — main 10 minute me reply karta hoon.\" Short, clear and warm. Want it more formal or more casual?"

        else ->
            "Main yahan hoon 💜. Ask me about chats, groups, calls, the offline mesh, vault/PIN security, or QR device linking — in English or Roman Urdu."
    }
}
