package com.meshlink.app.ui

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

private const val DEFAULT_BASE_URL = "http://192.168.1.50:11434"
private const val DEFAULT_MODEL = "llama3.2"

private val AI_PROMPTS = listOf(
    "Aaj kya khaya?",
    "Dinner ka idea do",
    "Mujhe thaka laga raha hai",
    "Plan banata hai",
    "Koi joke sunao",
    "Kya seekhun aaj?",
)

private data class AiMessage(val role: String, val text: String) {
    val isUser: Boolean get() = role == "user"
}

/**
 * MeshLink AI — a private everyday assistant. Conversation stays on the device
 * when possible; the built-in responder always works without internet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiScreen(onBack: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var isTyping by remember { mutableStateOf(false) }
    val messages = remember {
        mutableStateListOf(
            AiMessage(
                "assistant",
                "Salam! Main MeshLink AI hoon — aapka private daily assistant. Aam baat karo, kuch bhi poocho: khaana, plan, mood, padhai, kaam, jokes… main yahan hoon. 💜",
            ),
        )
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    fun ask(question: String) {
        val text = question.trim()
        if (text.isBlank() || isTyping) return
        input = ""
        keyboard?.hide()
        messages.add(AiMessage("user", text))
        isTyping = true
        scope.launch {
            val history = messages.takeLast(12).map { AiMessage(it.role, it.text) }
            val reply = chatWithEngine(history, text)
            messages.add(AiMessage("assistant", reply))
            isTyping = false
        }
    }

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
                            "Private everyday assistant",
                            color = Color.White.copy(alpha = 0.88f),
                            fontSize = 12.sp,
                        )
                    }
                    IconButton(
                        onClick = {
                            messages.clear()
                            messages.add(
                                AiMessage(
                                    "assistant",
                                    "Chat clear ho gaya. Ab kya baat karein? 💜",
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

            // -------------------------------------------------- chat header
            Text(
                "Daily chat",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

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

/** Internal probe — never shown in the UI. Keeps replies instant when offline. */
private suspend fun probeEngine(): Boolean = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        connection = URL(DEFAULT_BASE_URL.trimEnd('/') + "/api/tags").openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 1_500
        connection.readTimeout = 1_500
        connection.responseCode in 200..299
    } catch (e: Exception) {
        false
    } finally {
        connection?.disconnect()
    }
}

/** Private local engine when available; built-in responder is always the fallback. */
private suspend fun chatWithEngine(
    history: List<AiMessage>,
    prompt: String,
): String = withContext(Dispatchers.IO) {
    if (!probeEngine()) return@withContext localReply(prompt)
    var connection: HttpURLConnection? = null
    try {
        val messages = JSONArray()
        messages.put(
            JSONObject()
                .put("role", "system")
                .put(
                    "content",
                    "You are MeshLink AI, a friendly private everyday assistant for normal conversation. Talk naturally about daily life, food, plans, mood, study, work, health and small talk. Be warm, concise and emotionally aware. Match the user's language (Roman Urdu, English or Hinglish). Detect feelings and answer with empathy. Never mention model names, servers, setup or technical configuration.",
                ),
        )
        history.forEach { message ->
            messages.put(
                JSONObject()
                    .put("role", message.role)
                    .put("content", message.text),
            )
        }
        val payload = JSONObject()
            .put("model", DEFAULT_MODEL)
            .put("stream", false)
            .put("messages", messages)
            .toString()

        connection = URL(DEFAULT_BASE_URL.trimEnd('/') + "/api/chat").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 6_000
        connection.readTimeout = 25_000
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        connection.doOutput = true
        connection.outputStream.use { out ->
            out.write(payload.toByteArray(Charsets.UTF_8))
        }

        val code = connection.responseCode
        val body = connection.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) return@withContext localReply(prompt)
        val json = JSONObject(body)
        val content = (json.optJSONObject("message")?.optString("content", "").orEmpty())
            .ifBlank { json.optString("response", "") }
        return@withContext content.trim().ifBlank { localReply(prompt) }
    } catch (e: IOException) {
        return@withContext localReply(prompt)
    } catch (e: Exception) {
        return@withContext localReply(prompt)
    } finally {
        connection?.disconnect()
    }
}

/** Built-in everyday answers — works instantly, even with no internet. */
private fun localReply(question: String): String {
    val low = question.trim().lowercase()
    val base = when {
        Regex("^(hi|hello|salam|assalam|yo)\\b").containsMatchIn(low) ->
            "Salam! 😊 Aaj kaise ho? Khaana, plan, mood ya koi bhi baat — main yahan hoon."

        Regex("how are you|kaise ho|kya haal|what.?s up|kya chal raha").containsMatchIn(low) ->
            "Main theek hoon, shukriya! 😊 Aap kaise ho? Aaj kya kar rahe ho?"

        Regex("thank|shukriya|dhanyavaad").containsMatchIn(low) ->
            "Koi baat nahi! 😊 Khush raho. Aur kya madad chahiye?"

        Regex("who are you|kaun ho|tum kaun|your name").containsMatchIn(low) ->
            "Main MeshLink AI hoon — aapka private daily assistant. Kisi bhi aam baat ke liye yahan hoon. 💜"

        Regex("aaj.*khaya|today.*ate|maine.*khaya|kya khaya|kha liya|kha gaya").containsMatchIn(low) ->
            "Accha! Aapne aaj kya khaya? 😊 Bata dijiye to main mood aur energy ke hisaab se kuch suggestion bhi de sakta hoon."

        Regex("khana|khaana|food|lunch|dinner|breakfast|naashta|recipe|bhukha|bhookh|eat|swad").containsMatchIn(low) ->
            "Khaana sabse important 😊. Agar kuch light chahiye to dal-chawal, khichdi ya omelette try karo. Thoda dahi/salad bhi add kar lo. Aapko aaj kya khane ka mann hai?"

        Regex("thaka|thak gaya|tired|exhaust|neend|sleep|rest|borg").containsMatchIn(low) ->
            "Thakan lagna bilkul normal hai. Thoda paani piyo, aankhon ko 10 minute aaram do, aur agar ho jaye to 20-minute ki chhoti neend. Aaj ka kaam thoda kam kar ke bhi chalega. 💜"

        Regex("plan|schedule|today|kal|tomorrow|routine|kaam|work|office|meeting|padhai|study|exam").containsMatchIn(low) ->
            "Chalo ek simple plan banate hain: 1) sabse zaroori kaam pehle, 2) 25-minute focus aur 5-minute break, 3) thoda walk aur paani, 4) shaam me 10-minute review. Aapka aaj ka sabse bada kaam kya hai?"

        Regex("joke|mazaak|funny|hasana|laugh|chutkala").containsMatchIn(low) ->
            "Ek chhota sa: maine fridge khola to andar se bill mila — 'Electricity department: payment pending.' 😄 Aur kya sunaoon?"

        Regex("weather|mousam|barish|garmi|sardi|rain").containsMatchIn(low) ->
            "Mere paas live weather data nahi hai, lekin aap phone ke weather app se check kar sakte ho. Agar barish ya garmi ho to paani aur suitable kapde saath rakhna. 🌦️"

        Regex("health|sehat|diet|exercise|walk|gym|weight|dawa|medicine|fever|bukhar|dard").containsMatchIn(low) ->
            "Sehat pehle. Paani, neend aur thoda movement sabse zyada farak dete hain. Agar fever ya dard ho to doctor se consult karna better. Main general tips de sakta hoon, medical diagnosis nahi. 💜"

        Regex("family|ghar|mom|dad|friend|dost|saath|relationship").containsMatchIn(low) ->
            "Aapke log sabse important hote hain. Unke saath thoda time, ek chhoti message ya shukriya bhi din bana deta hai. Aap kis ke baare me soch rahe ho?"

        Regex("love|pyaar|crush|breakup|girlfriend|boyfriend").containsMatchIn(low) ->
            "Pyaar me sab kuch natural hai — kabhi khushi, kabhi confusion. Honest baat karo, boundaries respect karo, aur apne feelings ko ignore mat karo. Aap kya feel kar rahe ho? 💜"

        Regex("seekh|learn|skill|course|knowledge|samjho|seekhna").containsMatchIn(low) ->
            "Aaj kuch chhota seekhne ka best tareeka: 20 minute theory, 20 minute practice, phir apne words me summarize. Consistency badi cheez se zyada important hai. Kya seekhna chahte ho?"

        Regex("kya karun|what should i|suggest|help|decision|confuse|soch").containsMatchIn(low) ->
            "Pehle deep saans lo. Do options likho, har ek ka ek plus aur ek minus, aur jo aapke aage ke liye sahi lage wo chuno. Aap mujhe thoda context de dijiye to main aur clear madad kar sakta hoon."

        Regex("summar|recap|overview|chats?|messages?").containsMatchIn(low) ->
            "MeshLink chats aapke phone par encrypted vault me rehti hain. Chats tab se conversations dekh sakte ho — kuch bhi cloud par upload nahi hota. Main reply draft karne me bhi madad kar sakta hoon."

        Regex("group|community|broadcast").containsMatchIn(low) ->
            "Groups aapke device par hote hain aur mesh ke through tab sync hote hain jab peers paas ho. MeshLink ID se log add karo; internet na ho to bhi messages hop-by-hop aage badhte hain."

        Regex("call|voice|video|ptt|walkie").containsMatchIn(low) ->
            "Calls ke 3 layer hain: online par encrypted WebRTC audio/video, local Wi-Fi Direct / mesh P2P with push-to-talk, aur offline voice-note jo baad me sync ho jata hai."

        Regex("offline|mesh|hotspot|lan|relay|radio").containsMatchIn(low) ->
            "MeshLink ke 3 layer hain: 1) internet par online relay, 2) internet na ho to local hotspot/LAN mesh (Bluetooth LE, Wi-Fi Direct, SMS), 3) store-and-forward jo connectivity wapas aate hi messages bhej deta hai."

        Regex("(^| )ai( |$)|model|engine|intelligence|ollama").containsMatchIn(low) ->
            "Main MeshLink AI hoon — aapka private assistant. Aam baat karo ya koi app feature samjho; koi setup ya server dikhane ki zarurat nahi. 💜"

        Regex("vault|pin|encrypt|secur|password|privacy|hack").containsMatchIn(low) ->
            "Vault AES-256-GCM aur PBKDF2-SHA256 use karta hai. 6-digit app PIN doosra layer hai aur 5 minute baad auto-lock ho jata hai. Email sirf verification hai; pehchaan MeshLink ID hi hai."

        Regex("qr|link|web|device|login").containsMatchIn(low) ->
            "QR linking phone ko MeshLink Web se pairs karta hai: Settings → Link device, website ka QR scan karo, phir yahan approve karo. Session short-lived hota hai aur messages share nahi hote."

        Regex("identity|email|(^| )id( |$)|number").containsMatchIn(low) ->
            "MeshLink ID isi device par banti hai aur kabhi nahi badalti. Verified email sirf security check hai — ye aapki pehchaan nahi hai aur chat me kabhi nahi dikhti."

        Regex("reply|draft|write|compose").containsMatchIn(low) ->
            "Try karo: \"Bhai, message mil gaya — main 10 minute me reply karta hoon.\" Short, clear aur warm. Aur formal ya casual chahiye?"

        Regex("time|kitne baje|clock").containsMatchIn(low) ->
            "Mere paas aapki local clock access nahi hai, lekin phone ki clock dekh lijiye. Chahein to main time management me madad kar sakta hoon. ⏰"

        else ->
            "Main yahan hoon 💜. Aam baat karo — khaana, plan, mood, padhai, kaam, jokes ya koi bhi confusion. English, Roman Urdu ya Hinglish, jo aapko aasan lage."
    }
    val emotion = detectEmotion(question)
    return when (emotion) {
        "tired" -> "Thoda aaram kar lo — aapki thakan samajh sakti hoon. 💜 " + base
        "sad" -> "I'm sorry you're feeling low right now. 💜 " + base
        "angry" -> "I understand the frustration. Let's handle it step by step. 💜 " + base
        "anxious" -> "Take a breath — we'll sort this out together. 💜 " + base
        "happy" -> "Love that energy! 😊 " + base
        else -> base
    }
}

/** Lightweight emotion check so MeshLink AI can answer with empathy. */
private fun detectEmotion(text: String): String {
    val low = text.trim().lowercase()
    return when {
        Regex("thaka|thak gaya|tired|exhaust|neend a").containsMatchIn(low) -> "tired"
        Regex("sad|cry|dukhi|depress|lonely|broken|rona|khush nahi|upset").containsMatchIn(low) -> "sad"
        Regex("angry|gussa|furious|hate|pagal|bhadwa|irritat").containsMatchIn(low) -> "angry"
        Regex("anxious|worried|tension|stress|pareshan|nervous|scared|panic").containsMatchIn(low) -> "anxious"
        Regex("happy|khush|great|awesome|love|shukriya|thanks|excited|mast").containsMatchIn(low) -> "happy"
        else -> "neutral"
    }
}
