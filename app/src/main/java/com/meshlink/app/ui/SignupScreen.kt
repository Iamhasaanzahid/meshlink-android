package com.meshlink.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshlink.app.BuildConfig
import com.meshlink.app.data.VerificationClient
import com.meshlink.app.data.VerifyResult
import com.meshlink.app.util.newMeshLinkId
import kotlinx.coroutines.launch

private val Purple = Color(0xFF6750A4)
private val PurpleLight = Color(0xFFEADDFF)
private val PurpleSoft = Color(0xFFF3EDF7)
private val Muted = Color(0xFF625B71)
private val Ink = Color(0xFF1D1B20)

private val EmailPattern = Regex("^[A-Za-z0-9+._%\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$")

private enum class SignupStep { DETAILS, CODE, DONE }

@Composable
fun SignupScreen(
    onSignupSuccess: (uin: String, displayName: String, email: String) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(SignupStep.DETAILS) }
    var generatedUin by remember { mutableStateOf("") }
    var debugBypass by remember { mutableStateOf(false) }
    var showDemoBypass by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun requestCode() {
        val cleanEmail = email.trim()
        when {
            displayName.isBlank() -> error = "Enter your display name."
            !EmailPattern.matches(cleanEmail) -> error = "Enter a valid email address."
            else -> {
                error = ""
                info = ""
                showDemoBypass = false
                busy = true
                // The MeshLink ID is always generated on the device. It stays
                // the same across resends: email is only a security layer.
                val meshId = generatedUin.ifBlank { newMeshLinkId() }
                generatedUin = meshId
                scope.launch {
                    when (val result = VerificationClient.sendCode(cleanEmail, meshId)) {
                        is VerifyResult.CodeSent -> {
                            step = SignupStep.CODE
                            info = if (result.demo) {
                                "Demo mode: the code is 123456 (confirmed by the server)."
                            } else {
                                "We sent a 6-digit code to $cleanEmail."
                            }
                        }

                        is VerifyResult.Error -> {
                            error = result.message
                            showDemoBypass = BuildConfig.DEBUG && result.network
                        }

                        else -> error = "Unexpected verification response. Please try again."
                    }
                    busy = false
                }
            }
        }
    }

    fun verifyAndCreate() {
        val cleanCode = code.trim()
        when {
            cleanCode.length != 6 || !cleanCode.all { it.isDigit() } ->
                error = "Enter the 6-digit code we sent you."

            else -> {
                error = ""
                info = ""
                busy = true
                scope.launch {
                    val accepted = when {
                        // DEBUG ONLY: offline demo bypass. Release builds always
                        // require a real server confirmation.
                        debugBypass && BuildConfig.DEBUG -> {
                            if (cleanCode == "123456") {
                                true
                            } else {
                                error = "The demo code is 123456."
                                false
                            }
                        }

                        else -> when (val result = VerificationClient.confirm(
                            email.trim(), generatedUin, cleanCode,
                        )) {
                            is VerifyResult.Verified -> true
                            is VerifyResult.Error -> {
                                error = result.message
                                showDemoBypass = BuildConfig.DEBUG && result.network
                                false
                            }

                            else -> {
                                error = "Unexpected verification response. Please try again."
                                false
                            }
                        }
                    }
                    if (accepted) {
                        showDemoBypass = false
                        step = SignupStep.DONE
                    }
                    busy = false
                }
            }
        }
    }

    // Light background so the purple title and body text stay readable.
    val gradient = Brush.verticalGradient(
        listOf(Color(0xFFF3EDF7), Color(0xFFFEF7FF), Color.White)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(gradient)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("MeshLink", fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Purple)
            Spacer(Modifier.height(4.dp))
            Text("Free Secure Messaging", fontSize = 16.sp, color = Muted)
            Spacer(Modifier.height(24.dp))

            when (step) {
                SignupStep.DETAILS -> {
                    Text(
                        "Your email is used only to verify this device — your MeshLink ID stays your identity.",
                        fontSize = 13.sp,
                        color = Muted,
                    )
                    Spacer(Modifier.height(18.dp))

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it; error = "" },
                        label = { Text("Email (for verification)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = fieldColors(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                    )

                    Spacer(Modifier.height(14.dp))

                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it; error = "" },
                        label = { Text("Display Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = fieldColors(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done,
                        ),
                    )
                }

                SignupStep.CODE -> {
                    Text(
                        "Verify your email",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Purple,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("Code sent to ${email.trim()}", fontSize = 13.sp, color = Muted)
                    Spacer(Modifier.height(4.dp))
                    Text("MeshLink ID: $generatedUin", fontSize = 12.sp, color = Purple)
                    Spacer(Modifier.height(18.dp))

                    OutlinedTextField(
                        value = code,
                        onValueChange = { value ->
                            if (value.length <= 6 && value.all { it.isDigit() }) {
                                code = value
                                error = ""
                            }
                        },
                        label = { Text("6-digit code") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = fieldColors(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done,
                        ),
                    )
                }

                SignupStep.DONE -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = PurpleLight),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("Your Unique ID", fontSize = 13.sp, color = Muted)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                generatedUin,
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold,
                                color = Purple,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Email verified for ${email.trim()}. Your ID stays the same — email is security only.",
                                fontSize = 12.sp,
                                color = Muted,
                            )
                        }
                    }
                }
            }

            if (info.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(info, color = Purple, fontSize = 12.sp)
            }
            if (error.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            if (busy) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Purple,
                )
            }

            Spacer(Modifier.height(24.dp))

            when (step) {
                SignupStep.DETAILS -> {
                    Button(
                        onClick = { requestCode() },
                        enabled = !busy && displayName.isNotBlank() && email.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Purple,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text("Send code", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }

                SignupStep.CODE -> {
                    Button(
                        onClick = { verifyAndCreate() },
                        enabled = !busy && code.length == 6,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Purple,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text("Verify & create ID", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(onClick = {
                            email = ""
                            code = ""
                            step = SignupStep.DETAILS
                            error = ""
                            info = ""
                            debugBypass = false
                        }) {
                            Text("Different email", fontSize = 12.sp)
                        }
                        TextButton(onClick = { requestCode() }, enabled = !busy) {
                            Text("Resend code", fontSize = 12.sp)
                        }
                    }
                    if (showDemoBypass) {
                        TextButton(onClick = {
                            debugBypass = true
                            showDemoBypass = false
                            error = ""
                            info = "Demo bypass enabled — enter 123456."
                        }) {
                            Text(
                                "DEBUG: use demo code 123456",
                                fontSize = 12.sp,
                                color = Color(0xFF00695C),
                            )
                        }
                    }
                }

                SignupStep.DONE -> {
                    Button(
                        onClick = { onSignupSuccess(generatedUin, displayName.trim(), email.trim()) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Purple,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text("Open MeshLink", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            FeatureItem("✓", "No phone number needed")
            FeatureItem("✓", "Email verification only — not your identity")
            FeatureItem("✓", "Works offline via radio mesh")

            Spacer(Modifier.height(18.dp))
            RadioStatusCard()

            Spacer(Modifier.height(14.dp))
            Text("v${BuildConfig.VERSION_NAME} · Free Forever", fontSize = 12.sp, color = Muted)
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = PurpleLight,
    unfocusedContainerColor = PurpleSoft,
    focusedBorderColor = Purple,
    unfocusedBorderColor = Color(0xFFCAC4D0),
    focusedLabelColor = Purple,
    unfocusedLabelColor = Muted,
)

@Composable
private fun RadioStatusCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0x99FFFFFF)),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(2.dp, Color(0x266750A4)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "RADIO MESH — NO INTERNET REQUIRED",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Purple,
            )
            Spacer(Modifier.height(10.dp))
            RadioRow("Radio (SMS / cellular)", "city-wide")
            RadioRow("Bluetooth LE", "10–50 m")
            RadioRow("Wi-Fi Direct", "50–200 m")
        }
    }
}

@Composable
private fun RadioRow(name: String, range: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .background(Color(0xFF2E7D32), RoundedCornerShape(50)),
        )
        Spacer(Modifier.width(10.dp))
        Text(name, fontSize = 13.sp, color = Ink, modifier = Modifier.weight(1f))
        Text(
            range,
            fontSize = 11.sp,
            color = Muted,
            modifier = Modifier
                .background(PurpleLight, RoundedCornerShape(50))
                .padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun FeatureItem(icon: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(PurpleLight, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Text(icon, color = Purple, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 14.sp, color = Ink)
    }
}
