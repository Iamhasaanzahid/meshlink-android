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
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val CLOUD_AI_URL = "https://mhzaly-ai.mhzaly.workers.dev/api/chat"

private val AI_PROMPTS = listOf(
    "Khaana suggest karo",
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
                "Salam! Main Mhzaly AI hoon — aapka private daily assistant. Aam baat karo, kuch bhi poocho: khaana, plan, mood, padhai, kaam, jokes… main yahan hoon. 💜",
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
        // Index 0 is the scrolling header card.
        val last = if (isTyping) messages.size + 1 else messages.size
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Header now scrolls with the conversation (no fixed purple bar).
            // ---------------------------------------------------- messages
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(BrandDeep, BrandViolet, BrandLight),
                                ),
                            )
                            .clip(RoundedCornerShape(20.dp))
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = Color.White,
                                )
                            }
                            Spacer(Modifier.width(2.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Mhzaly AI",
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    "Facts, maths, plans aur daily baat",
                                    color = Color.White.copy(alpha = 0.88f),
                                    fontSize = 11.sp,
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
                    Text(
                        "Daily chat",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
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
                    placeholder = { Text("Message Mhzaly AI…") },
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
                if (mine) "You" else "Mhzaly AI",
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

/** Cloud brain for real questions. Returns null when offline or unavailable. */
private suspend fun chatWithCloud(
    history: List<AiMessage>,
    prompt: String,
): String? = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        val messages = JSONArray()
        history.takeLast(12).forEach { message ->
            messages.put(
                JSONObject()
                    .put("role", message.role)
                    .put("content", message.text.take(1500)),
            )
        }
        val payload = JSONObject().put("messages", messages).toString()
        connection = URL(CLOUD_AI_URL).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 4_000
        connection.readTimeout = 25_000
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("X-Mhzaly-Client", "android")
        connection.doOutput = true
        connection.outputStream.use { out -> out.write(payload.toByteArray(Charsets.UTF_8)) }

        val code = connection.responseCode
        val body = connection.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299 || body.isBlank()) return@withContext null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
        if (!json.optBoolean("ok", false)) return@withContext null
        return@withContext cleanEngineReply(json.optString("reply", ""))
    } catch (e: Exception) {
        return@withContext null
    } finally {
        connection?.disconnect()
    }
}

/** Cloud first for real questions, private engine second, built-in always works. */
private suspend fun chatWithEngine(
    history: List<AiMessage>,
    prompt: String,
): String = withContext(Dispatchers.IO) {
    // Everyday chat is answered by the built-in layer straight away. This keeps
    // replies natural and instant when the private engine is missing or slow.
    val builtIn = builtInReply(history, prompt)
    if (builtIn.confident) return@withContext builtIn.text

    // Real questions go to the cloud brain first so even basic facts, maths
    // and general knowledge get a proper answer instead of a generic line.
    val cloud = chatWithCloud(history, prompt)
    if (cloud != null) return@withContext cloud

    return@withContext builtIn.text
}

/**
 * Built-in responder result. [confident] intents (all everyday chat) skip the
 * local engine completely, so answers stay natural even when it is slow/absent.
 */
private data class BuiltInReply(val text: String, val confident: Boolean)

private enum class ChatIntent {
    GREETING, HOW_ARE_YOU, THANKS, SHORT_ACK, IDENTITY, MATH,
    TIRED, BORED, HAPPY, SAD, ANGRY, ANXIOUS, SICK,
    FOOD, PLAN, WORK, STUDY, FAMILY, LOVE, JOKE,
    WEATHER, HEALTH, TIME,
    APP_CHAT, APP_GROUP, APP_CALL, APP_MESH, APP_VAULT, APP_LINK,
    APP_IDENTITY, APP_REPLY, AI_ENGINE, UNKNOWN,
}

private fun normalizeChat(text: String): String =
    text.trim().lowercase().replace(Regex("\\s+"), " ")

private fun has(text: String, vararg patterns: String): Boolean =
    patterns.any { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(text) }

/** Longest names first so "dal chawal" is not reduced to "dal". */
private val KNOWN_FOODS = listOf(
    "dal chawal", "biryani", "omelette", "paratha", "sandwich", "noodles",
    "samosa", "pizza", "coffee", "paneer", "halwa", "burger", "momo",
    "khichdi", "bread", "chai", "dahi", "eggs", "andaa", "rice", "dal",
)

private fun mentionedFood(text: String): String? = KNOWN_FOODS.firstOrNull { it in text }

/** Tiny backchannels such as "hmm", "ok", "thoda sa" never trigger topic rules. */
private fun isShortAck(text: String): Boolean {
    val compact = text.replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (compact.length > 16) return false
    return Regex(
        "^(hmm*|hm+|ok+|okay|k|theek|thik|achha|accha|haan|han|nahi|no|yep|ya|yaar|cool|nice|sure|fine|good|thoda|thoda sa|kuch nahi|bas|aur)$",
        RegexOption.IGNORE_CASE,
    ).matches(compact)
}

/** Ordered intent matcher: feeling first, then topic, then app help. */
private fun detectChatIntent(text: String): ChatIntent {
    val t = normalizeChat(text)
    return when {
        // Basic arithmetic works instantly, even with no internet.
        Regex("^\\s*-?\\d+(?:\\.\\d+)?\\s*(?:\\+|-|\\*|/|x|×|÷)\\s*-?\\d+(?:\\.\\d+)?\\s*=??\\s*$")
            .matches(t) -> ChatIntent.MATH
        isShortAck(t) -> ChatIntent.SHORT_ACK
        has(t, "\\b(hi+|hey|hello|hy|yo|salam|assalam|alaikum|aoa|good morning|gm|good night)\\b") -> ChatIntent.GREETING
        has(
            t,
            "\\b(how are you|kaise ho|koise ho|theek ho|kia haal|kya haal|haal kya|kya chal raha|kya kar rahe|what'?s up|what are you doing|wassup|sup|sab theek|saab theek)\\b",
        ) -> ChatIntent.HOW_ARE_YOU
        has(t, "\\b(thank\\w*|shukriya|dhanyavaad)\\b") -> ChatIntent.THANKS
        has(t, "\\b(who are you|kaun ho|tum kaun|your name)\\b") -> ChatIntent.IDENTITY
        has(t, "\\b(thak\\w*|tired|exhaust\\w*|sleepy|neend|low energy)\\b") -> ChatIntent.TIRED
        has(t, "\\b(bored|borg|boring|mann nahi laga)\\b") -> ChatIntent.BORED
        has(
            t,
            "\\b(sad|udaas|dukhi|depress\\w*|lonely|broken|rona|cry\\w*|khush nahi|mann kharab|din kharab|bad day|mann nahi|bura lag\\w*|upset)\\b",
        ) -> ChatIntent.SAD
        has(t, "\\b(angry|gussa|furious|irritat\\w*|frustrat\\w*|chir\\w*)\\b") -> ChatIntent.ANGRY
        has(
            t,
            "\\b(anxious|worried|tension|stress\\w*|pareshan|nervous|scared|panic|overthink\\w*|darr)\\b",
        ) -> ChatIntent.ANXIOUS
        has(t, "\\b(fever|bukhar|dard|sick|bimari|tabiyat|khansi|cough|medicine|dawa|health|sehat)\\b") -> ChatIntent.SICK
        has(t, "\\b(love you|pyaar|crush|girlfriend|boyfriend|breakup|shaadi|marriage|propose|relation\\w*)\\b") -> ChatIntent.LOVE
        has(t, "\\b(happy|khush|mast|excited|great|awesome|good news|celebrat\\w*|love it|love this|bahut achha)\\b") -> ChatIntent.HAPPY
        has(
            t,
            "\\b(khana|khaana|food|lunch|dinner|breakfast|naashta|bhukha|bhookh|eat|ate|swad|recipe|pizza|biryani|chai|coffee|dahi|omelette|paratha|khichdi|dal chawal|noodles|bread|sandwich|burger|momo|samosa|paneer|eggs|andaa|halwa)\\b",
        ) -> ChatIntent.FOOD
        has(t, "\\b(joke|mazaak|funny|hasana|laugh|chutkala|comedy)\\b") -> ChatIntent.JOKE
        has(
            t,
            "\\b(padhai|study|studying|exam|revision|notes|seekh\\w*|learn\\w*|course|skill|assignment|homework|teacher|class|college|school)\\b",
        ) -> ChatIntent.STUDY
        has(t, "\\b(office|meeting|boss|project|deadline|job|interview|kaam|work|business)\\b") -> ChatIntent.WORK
        has(t, "\\b(plan\\w*|schedule|routine|tomorrow|weekend|task|aaj ka plan|kal ka plan)\\b") -> ChatIntent.PLAN
        has(t, "\\b(family|ghar|mom|mummy|dad|papa|bhai|behen|friend|dost|saath|parents)\\b") -> ChatIntent.FAMILY
        has(t, "\\b(weather|mousam|barish|garmi|sardi|rain)\\b") -> ChatIntent.WEATHER
        has(t, "\\b(diet|exercise|walk|gym|weight)\\b") -> ChatIntent.HEALTH
        has(t, "\\b(time|kitne baje|clock)\\b") -> ChatIntent.TIME
        has(t, "\\b(summar\\w*|recap|overview|chat history|old chats|pak link chats)\\b") -> ChatIntent.APP_CHAT
        has(t, "\\b(group\\w*|community|broadcast)\\b") -> ChatIntent.APP_GROUP
        has(t, "\\b(call\\w*|voice|video|ptt|walkie)\\b") -> ChatIntent.APP_CALL
        has(t, "\\b(offline|mesh|hotspot|lan|relay|radio)\\b") -> ChatIntent.APP_MESH
        has(t, "\\b(vault|pin|encrypt\\w*|secur\\w*|password|privacy|hack\\w*)\\b") -> ChatIntent.APP_VAULT
        has(t, "\\b(qr|link|web|device|login)\\b") -> ChatIntent.APP_LINK
        has(t, "\\b(identity|email|pak link id|phone number)\\b") -> ChatIntent.APP_IDENTITY
        has(t, "\\b(reply|draft|write|compose)\\b") -> ChatIntent.APP_REPLY
        has(t, "\\b(ollama|model|engine|intelligence|server|api)\\b") -> ChatIntent.AI_ENGINE
        else -> ChatIntent.UNKNOWN
    }
}

/** Last meaningful user topic, used for light continuity on short replies. */
private fun previousTopic(history: List<AiMessage>): ChatIntent? =
    history.dropLast(1).asReversed()
        .firstOrNull { it.isUser && detectChatIntent(it.text) != ChatIntent.SHORT_ACK }
        ?.let { detectChatIntent(it.text) }
        ?.takeIf { it != ChatIntent.UNKNOWN }

private fun continuityLead(topic: ChatIntent?): String = when (topic) {
    ChatIntent.FOOD -> "Khaane wali baat yaad aa gayi 😊 "
    ChatIntent.PLAN -> "Pehle wala plan bhi hai na 💜 "
    ChatIntent.STUDY -> "Padhai wali baat chal rahi thi 😊 "
    ChatIntent.WORK -> "Kaam wali baat yaad aa gayi 💜 "
    ChatIntent.FAMILY -> "Ghar/dost wali baat yaad aa gayi 😊 "
    ChatIntent.LOVE -> "Pyaar wali baat abhi chal rahi thi 💜 "
    ChatIntent.TIRED -> "Thakan wali baat yaad aa gayi 💜 "
    ChatIntent.BORED -> "Boring wala mood tha na 😄 "
    ChatIntent.SAD -> "Pehle wali baat abhi saath hai 💜 "
    ChatIntent.ANXIOUS -> "Wahi tension wala feel? 🌿 "
    ChatIntent.JOKE -> "Joke wala mood tha na 😄 "
    else -> ""
}

/** Small offline calculator so basic maths never depends on the network. */
private fun solveMath(text: String): String {
    val match = Regex(
        "(-?\\d+(?:\\.\\d+)?)\\s*(\\+|-|\\*|/|x|×|÷)\\s*(-?\\d+(?:\\.\\d+)?)",
    ).find(text) ?: return "Numbers thoda clear likho — jaise 2 + 2 ya 10 / 2."
    val a = match.groupValues[1].toDoubleOrNull()
        ?: return "Numbers thoda clear likho — jaise 2 + 2 ya 10 / 2."
    val b = match.groupValues[3].toDoubleOrNull()
        ?: return "Numbers thoda clear likho — jaise 2 + 2 ya 10 / 2."
    val op = match.groupValues[2]
    if ((op == "/" || op == "÷") && b == 0.0) return "Zero se divide nahi hota 🙂"
    val result = when (op) {
        "+" -> a + b
        "-" -> a - b
        "*", "x", "×" -> a * b
        else -> a / b
    }
    val pretty = if (result % 1.0 == 0.0) {
        result.toLong().toString()
    } else {
        String.format(Locale.US, "%.4f", result).trimEnd('0').trimEnd('.')
    }
    return "$a $op $b = $pretty"
}

/** Built-in answer for a detected intent, with deterministic variation. */
private fun replyFor(intent: ChatIntent, text: String, history: List<AiMessage>): String {
    val topic = previousTopic(history)
    val occurrence = history.count { it.isUser && detectChatIntent(it.text) == intent }
        .coerceAtLeast(1)
    fun variant(options: List<String>): String = options[(occurrence - 1) % options.size]

    return when (intent) {
        ChatIntent.MATH -> solveMath(text)
        ChatIntent.SHORT_ACK -> shortAckReply(topic, text, history)
        ChatIntent.GREETING -> variant(
            listOf(
                "Salam! 😊 Main yahan hoon. Aaj kaise ho — sab theek?",
                "Hey! 👋 Kya haal hai? Waqt mil gaya to batao, kya kar rahe ho?",
                "Assalam-o-Alaikum! 😊 Aaj din kaisa chal raha hai?",
            ),
        )
        ChatIntent.HOW_ARE_YOU -> variant(
            listOf(
                "Main bilkul theek hoon, shukriya 😊 Aap kaise ho? Aaj ka din kaisa gaya?",
                "Sab achha hai yahan 😊 Aapka haal kya hai? Thoda batao na, kya kar rahe ho?",
                "Main theek-thak hoon 💜 Aap kaise ho? Koi achhi ya buri baat ho to share kar do.",
            ),
        )
        ChatIntent.THANKS -> variant(
            listOf(
                "Koi baat nahi! 😊 Khushi hui ki madad mili. Aur kya chahiye?",
                "Bilkul 💜 Batao, aur koi baat?",
                "Shukriya mat karo, main yahin hoon 😊 Aur kya kar sakte hain?",
            ),
        )
        ChatIntent.IDENTITY, ChatIntent.AI_ENGINE ->
            "Main Mhzaly ka private daily assistant hoon — aapki aam baat ka saathi. Khaana, mood, plan, jokes… kuch bhi poocho 💜"
        ChatIntent.TIRED -> variant(
            listOf(
                "Arre, thaka hua lag rahe ho 😔 Paani pi lo aur 10 minute aankhein band kar lo. Aaj ka kaam thoda kam bhi chalega.",
                "Samajh gaya, energy low hai 💜 Ek chhoti break, thoda walk, phir wapas. Aaj kya baaki hai?",
                "Thakan bilkul normal hai. 20 minute ki neend ya bas thoda stretch — fresh feel hoga. Main yahin hoon.",
            ),
        )
        ChatIntent.BORED -> variant(
            listOf(
                "Bore ho rahe ho 😄 Chalo kuch light — ek joke, 5 minute walk ya koi nayi cheez. Aapka kya mann hai?",
                "Samajh gaya, mann nahi lag raha 💜 Kabhi-kabhi bas thoda badlaav kaafi hota hai. Ek chhota task pakadte hain?",
                "Boring din me main joke suna sakta hoon 😄 Ya phir khaana/plan ki baat karte hain. Kya pasand hai?",
            ),
        )
        ChatIntent.HAPPY -> variant(
            listOf(
                "Wow, yeh energy dekh ke main bhi khush ho gaya 😄 Kya achhi baat hui? Batao na!",
                "Love it! 😊 Aapki khushi mujhe bhi lag gayi. Kya celebrate kar rahe ho?",
                "Mast! 💜 Aaj din achha chal raha hai — yeh feeling thodi der aur rahe. Kis baat se itne khush ho?",
            ),
        )
        ChatIntent.SAD -> variant(
            listOf(
                "Poora kya hua nahi pata, par dil bhara hai — main yahin hoon 💜 Thoda batao na, kya baat hai?",
                "Udas hona bilkul insaani hai. Abhi koi turant fix dhoondhne ki zarurat nahi, bas main sun raha hoon 🌿",
                "Yeh feel bhari hai 💜 Ek deep saans lo. Jo bhi hai, step-by-step dekhte hain. Abhi kya sabse zyada para hai?",
            ),
        )
        ChatIntent.ANGRY -> variant(
            listOf(
                "Gussa aana bilkul natural hai jab kuch galat ho 😤 Pehle ek deep saans, phir baat karte hain. Kya hua?",
                "Samajh gaya, aap irritate ho rahe ho 💜 Chalo thoda pause, phir ek-ek karke dekhte hain.",
                "Frustration samajh aa raha hai 💜 Kya sabse zyada chirkiya aa rahi hai? Usi se shuru karein.",
            ),
        )
        ChatIntent.ANXIOUS -> variant(
            listOf(
                "Tension ho rahi hai to pehle 4 second andar, 4 bahar 🌿 Sab kuch ek saath solve nahi karna. Sabse chhoti baat kya hai?",
                "Pareshani samajh gaya 💜 Dimag bahut soch raha hoga. Ek list banate hain: kya control me hai, kya nahi.",
                "Take it easy, main yahin hoon. Aaj bas ek chhota step enough hai. Kya sabse zyada soch aa raha hai?",
            ),
        )
        ChatIntent.SICK -> variant(
            listOf(
                "Tabiyat kharab hai to paani, aaram aur light khaana 💜 Fever/dard zyada ho ya 2 din chal jaye to doctor se milna zaroori. Kya feel ho raha hai?",
                "Bimari me dimag bhi thaka hota hai. Dawa time par lo, kaam kal ke liye chhod do. Kya symptoms hain?",
            ),
        )
        ChatIntent.FOOD -> {
            val reporting = has(
                text,
                "\\b(kya khaya|kya khaaya|khaya|kha liya|kha gaya|ate|khaana kya)\\b",
            )
            if (reporting) {
                val food = mentionedFood(text)
                if (food != null) {
                    "Accha, $food sun ke bhukh aa gayi 😄 Thoda dahi ya salad saath kar lo. Agle meal me kya khana chahte ho?"
                } else {
                    variant(
                        listOf(
                            "Mere paas khaana nahi hota 😄 — aapne kya khaya? Us hisaab se agla meal suggest kar dunga.",
                            "Accha! 😊 Aaj kya khaya? Bata dijiye to doosre meal ka idea de sakta hoon.",
                            "Achcha, sun ke khushi hui 😊 Aur kuch tasty banane ka mann hai?",
                        ),
                    )
                }
            } else {
                variant(
                    listOf(
                        "Dinner ke liye teen easy options: dal-chawal + dahi, omelette ya paneer paratha, ya khichdi thode achar ke saath. Aapko kya pasand hai? 😊",
                        "Bhookh lag gayi to 10 minute me bread-omelette ya noodles bana lo. Thoda salad bhi add kar lo — aaj kya mann hai?",
                        "Kuch light chahiye to khichdi/dal-chawal, kuch masaledar chahiye to paratha ya biryani. Aap kaunsa mood hai? 🍽️",
                    ),
                )
            }
        }
        ChatIntent.PLAN -> variant(
            listOf(
                "Chalo simple plan: 1) sabse zaroori kaam pehle, 2) 25 minute focus + 5 minute break, 3) thoda paani aur walk, 4) shaam me 5 minute review. Aapka first task kya hai?",
                "Ek chhota schedule: pehle ek important kaam, phir chhoti break, phir baaki. Raat ko phone thoda door rakhna. Aapka din kaisa hai aaj?",
                "Plan ban gaya 💜 Bas ek kaam chuno, timer lagao, karte jao. Kya shuru karein?",
            ),
        )
        ChatIntent.WORK -> variant(
            listOf(
                "Kaam me heavy din ho to pehle 'must-do' task pakdo, baaki kal. 25-minute focus blocks zyada asardaar hote hain. Aaj sabse bada kaam kya hai?",
                "Office wali baat samajh gaya 💜 Pehle 3 points likh lo, phir baat aasan ho jayegi. Kya deadline hai?",
                "Boss/meeting ki tension ho to deep breath, notes, aur ek clear question. Aapko kya clarify karna hai?",
            ),
        )
        ChatIntent.STUDY -> variant(
            listOf(
                "Padhai ka simple formula: 20 minute theory, 20 minute practice, phir apne words me summarize. Roz thoda, par roz. Aaj kya topic hai?",
                "Exam close ho to ek topic, ek timer, ek break — bas. Notes chhote rakho, revision baad me. Kya subject padh rahe ho?",
                "Seekhn ka best tareeka: samjho, likho, phir kisi ko samjhao. Aaj kya seekhna chahte ho?",
            ),
        )
        ChatIntent.FAMILY -> variant(
            listOf(
                "Family aur dost sabse bada support hote hain 💜 Ek chhoti 'kaise ho' message bhi din bana deti hai. Aap kis ke baare me soch rahe ho?",
                "Ghar wale ya dost yaad aa rahe hai? Unhe ek quick message kar do — baat phir apne aap chal jayegi. Kya baat hai?",
                "Saath wale log zaroori hote hain 😊 Aap unke saath kya kar rahe ho ya kya kehna chahte ho?",
            ),
        )
        ChatIntent.LOVE -> variant(
            listOf(
                "Pyaar me sab natural hai — khushi bhi, confusion bhi 💜 Honest baat karo aur boundaries ka khayal rakho. Aap kya feel kar rahe ho?",
                "Crush ya relationship ki baat hai? Pehle apne dil ki suno, phir saamne wale ki. Jaldi faisla mat lo. Batao, kya hua?",
                "Yeh dil ki baat hai, main yahin hoon 💜 Chahe confuse ho ya khush — dono theek hain. Kya sabse zyada mann me hai?",
            ),
        )
        ChatIntent.JOKE -> variant(
            listOf(
                "Ek chhota sa: fridge khola to bill mila — 'Payment pending' 😄 Aur ek? dost bola 'diet par hoon', phir pizza order kar diya 🍕. Aur sunaoon?",
                "Teacher: '2+2 kya?' Baccha: 'Sir, weekend aa gaya, sab bhool gaye 😅' Aur ek joke chahiye?",
                "Aaj ka joke: alarm 6:00 par lagaya, khud 6:05 par soya 😴. Relate kar rahe ho? 😄",
            ),
        )
        ChatIntent.WEATHER ->
            "Mere paas live weather nahi hai 🌦️ Phone ke weather app se check kar lo. Barish/garmi ho to chhatri/paani saath rakhna. Aaj aapke yahan kaisa hai?"
        ChatIntent.HEALTH ->
            "Sehat pehle 💜 Paani, neend aur thoda movement sabse zyada farak dete hain. Fever ya dard ho to doctor se consult karna better. Main general tips de sakta hoon, diagnosis nahi."
        ChatIntent.TIME -> {
            val now = SimpleDateFormat("h:mm a, d MMMM", Locale.getDefault()).format(Date())
            "Aapke phone ke hisaab se abhi $now hai ⏰ Chahein to time management ka chhota plan bana deta hoon."
        }
        ChatIntent.APP_CHAT ->
            "Mhzaly chats phone par encrypted vault me rehti hain. Chats tab se conversations dekh sakte ho — cloud par upload nahi hota. Main reply draft karne me bhi madad kar sakta hoon."
        ChatIntent.APP_GROUP ->
            "Groups device par hote hain aur mesh ke through tab sync hote hain jab peers paas ho. Mhzaly ID se log add karo; internet na ho to bhi messages aage badhte hain."
        ChatIntent.APP_CALL ->
            "Calls ke 3 layer hain: online encrypted WebRTC audio/video, local Wi-Fi Direct/mesh P2P with push-to-talk, aur offline voice-note jo baad me sync hota hai."
        ChatIntent.APP_MESH ->
            "Mhzaly ke 3 layer hain: internet par online relay, internet na ho to local hotspot/LAN mesh (Bluetooth LE, Wi-Fi Direct, SMS), aur store-and-forward."
        ChatIntent.APP_VAULT ->
            "Vault AES-256-GCM aur PBKDF2-SHA256 use karta hai. 6-digit app PIN doosra layer hai aur 5 minute baad auto-lock hota hai."
        ChatIntent.APP_LINK ->
            "QR linking: Settings → Link device, website ka QR scan karo, phir yahan approve karo. Session short-lived hota hai aur messages share nahi hote."
        ChatIntent.APP_IDENTITY ->
            "Mhzaly ID isi device par banti hai aur kabhi nahi badalti. Verified email security check hai, pehchaan nahi — chat me kabhi nahi dikhti."
        ChatIntent.APP_REPLY ->
            "Try karo: \"Bhai, message mil gaya — main 10 minute me reply karta hoon.\" Short, clear aur warm. Formal ya casual chahiye?"
        ChatIntent.UNKNOWN -> fallbackReply(topic, history)
    }
}

/** "hmm / ok / thoda sa" replies lean on the previous topic instead of guessing. */
private fun shortAckReply(
    topic: ChatIntent?,
    text: String,
    history: List<AiMessage>,
): String {
    val t = normalizeChat(text)
    val index = history.size
    fun pick(options: List<String>): String = options[index % options.size]

    return when {
        has(t, "\\b(nahi|no)\\b") -> when (topic) {
            ChatIntent.FOOD -> "Theek, koi baat nahi 😊 Kuch aur pasand hai to batao."
            ChatIntent.TIRED -> "Koi baat nahi, aaram ko priority do 💜."
            else -> "Theek, samajh gaya 😊 Aur kya soch rahe ho?"
        }
        has(t, "\\bthoda\\b") -> when (topic) {
            ChatIntent.FOOD -> "Thoda sa matlab light? 😊 Dal-chawal ya omelette try karo — aapko kya pasand hai?"
            ChatIntent.TIRED -> "Thoda sa aaram bhi kaam ka hota hai 💜 10 minute le lo."
            ChatIntent.HAPPY -> "Thoda sa khushi bhi bahut hoti hai 😄 Kya milestone hai?"
            else -> "Thoda sa bhi enough hai 😊 Batao na, kis baare me baat kar rahe ho?"
        }
        has(t, "\\b(haan|han|yep|yes|ya|achha|accha|ok|okay)\\b") -> when (topic) {
            ChatIntent.FOOD -> pick(
                listOf(
                    "Haan, khaane me kuch simple pakad lo 😊 Aapko kya mann hai?",
                    "Bilkul, light option dal-chawal ya omelette. Aur kya chahiye?",
                ),
            )
            ChatIntent.TIRED -> "Haan, bilkul 💜 Thoda aaram kar lo — aapki thakan samajh sakti hoon."
            ChatIntent.BORED -> "Haan, bas mann lagane ka ek chhota idea chahiye 😄 Joke sunaoon ya kuch naya try karein?"
            ChatIntent.SAD -> "Haan, main yahin hoon 💜 Koi pressure nahi, jab batao tab sununga."
            ChatIntent.JOKE -> "Haan, aur ek joke chahiye? 😄 Suna raha hoon."
            ChatIntent.FAMILY -> "Haan, unhe ek quick message kar do — baat apne aap chal jayegi 😊"
            ChatIntent.LOVE -> "Haan, dil ki baat ko thoda time do 💜 Main yahin hoon."
            ChatIntent.STUDY, ChatIntent.PLAN, ChatIntent.WORK ->
                "Haan, bas ek chhota step pehle. Baaki baad me dekhte hain — kya shuru karein?"
            else -> "Haan, samajh gaya 😊 Aur kya baat karein?"
        }
        else -> when (topic) {
            ChatIntent.FOOD -> "Hmmm, khaane wali baat yaad aa gayi 😊 Thoda batao to exact suggestion du."
            ChatIntent.TIRED -> "Hmmm, thakan samajh aa rahi hai 💜 Abhi ek chhoti break enough hai."
            ChatIntent.SAD -> "Hmmm, main yahin hoon 💜 Kya mann me chal raha hai?"
            ChatIntent.BORED -> "Hmmm, bore ho rahe ho na 😄 Ek joke ya chhota walk — kya bataon?"
            ChatIntent.JOKE -> "Hmmm, aur ek joke chahiye? 😄 Sunaoon?"
            ChatIntent.FAMILY -> "Hmmm, ghar/dost wali baat yaad aa gayi 😊 Kya kehna hai unhe?"
            ChatIntent.LOVE -> "Hmmm, dil wali baat chal rahi thi 💜 Kya feel ho raha hai?"
            else -> pick(
                listOf(
                    "Hmmm, samajh gaya 😊 Thoda aur batao to main aur accha reply kar paun — aaj kaise ho?",
                    "Haan, main sun raha hoon 💜 Kya baat kar rahe ho?",
                ),
            )
        }
    }
}

/** Natural, non-robotic answer for free-form statements or engine failure. */
private fun fallbackReply(topic: ChatIntent?, history: List<AiMessage>): String {
    val lead = continuityLead(topic)
    val options = listOf(
        "${lead}Aapki baat samajh aa gayi 😊 Isme aapka agla step kya hai? Main yahin hoon.",
        "${lead}Haan, yeh bhi ek baat hai 💜 Thoda aur batao to main aur clear answer de paun.",
        "${lead}Interesting — aap kis taraf soch rahe ho? Chalo ek chhoti baat se shuru karte hain.",
    )
    return options[history.size % options.size]
}

/** Every chat gets a built-in answer; unknown topics may use the private engine. */
private fun builtInReply(history: List<AiMessage>, prompt: String): BuiltInReply {
    val intent = detectChatIntent(prompt)
    return BuiltInReply(
        text = replyFor(intent, normalizeChat(prompt), history),
        confident = intent != ChatIntent.UNKNOWN,
    )
}

private val MECHANICAL_REPLY_PATTERNS = listOf(
    Regex(
        "\\bi(?:'m| am)\\s+(?:an?\\s+)?(?:ai|language model|virtual assistant|program)\\b",
        RegexOption.IGNORE_CASE,
    ),
    Regex("\\bas an? (?:ai|language model)\\b", RegexOption.IGNORE_CASE),
    Regex(
        "\\b(?:ollama|api key|endpoint|server|model name|configuration|setup)\\b",
        RegexOption.IGNORE_CASE,
    ),
    Regex(
        "\\bmodel (?:is )?not available\\b|\\bno model\\b|\\bplease ask something else\\b|\\bi (?:can't|cannot) (?:answer|help)\\b",
        RegexOption.IGNORE_CASE,
    ),
    Regex("\\b(?:json|training data|as a responsible)\\b|[{}<>]|```", RegexOption.IGNORE_CASE),
)

/** Reject mechanical, technical or broken model output before showing it. */
private fun cleanEngineReply(raw: String): String? {
    val cleaned = raw.trim().trim('"', '\'')
        .replace(Regex("\\s+"), " ")
        .trim()
    if (cleaned.length < 8 || cleaned.length > 700) return null
    if (MECHANICAL_REPLY_PATTERNS.any { it.containsMatchIn(cleaned) }) return null
    return cleaned
}
