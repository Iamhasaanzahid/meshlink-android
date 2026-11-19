package com.meshlink.app.ui

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
import kotlinx.coroutines.launch

private val VerifyPurple = Color(0xFF6750A4)
private val VerifyPurpleLight = Color(0xFFEADDFF)
private val VerifyPurpleSoft = Color(0xFFF3EDF7)
private val VerifyMuted = Color(0xFF625B71)

private val VerifyEmailPattern = Regex("^[A-Za-z0-9+._%\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$")

/**
 * Shown to already-registered installs that have a MeshLink ID but no verified
 * email yet. The ID is unchanged — this only adds the security verification.
 */
@Composable
fun VerifyEmailScreen(uin: String, onVerified: (email: String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showDemoBypass by remember { mutableStateOf(false) }
    var debugBypass by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun sendCode() {
        val cleanEmail = email.trim()
        if (!VerifyEmailPattern.matches(cleanEmail)) {
            error = "Enter a valid email address."
            return
        }
        error = ""
        info = ""
        showDemoBypass = false
        busy = true
        scope.launch {
            when (val result = VerificationClient.sendCode(cleanEmail, uin)) {
                is VerifyResult.CodeSent -> {
                    codeSent = true
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

    fun confirmCode() {
        val cleanCode = code.trim()
        if (cleanCode.length != 6 || !cleanCode.all { it.isDigit() }) {
            error = "Enter the 6-digit code."
            return
        }
        error = ""
        info = ""
        busy = true
        scope.launch {
            val accepted = when {
                debugBypass && BuildConfig.DEBUG -> {
                    if (cleanCode == "123456") {
                        true
                    } else {
                        error = "The demo code is 123456."
                        false
                    }
                }

                else -> when (val result = VerificationClient.confirm(email.trim(), uin, cleanCode)) {
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
            if (accepted) onVerified(email.trim())
            busy = false
        }
    }

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
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "Secure your MeshLink ID",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = VerifyPurple,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Your ID stays exactly the same. Email is only a security check — it is never used as your identity.",
                fontSize = 13.sp,
                color = VerifyMuted,
            )
            Spacer(Modifier.height(18.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = VerifyPurpleLight),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Your MeshLink ID", fontSize = 12.sp, color = VerifyMuted)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        uin,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = VerifyPurple,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it; error = "" },
                label = { Text("Email for verification") },
                singleLine = true,
                enabled = !codeSent,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = VerifyPurpleLight,
                    unfocusedContainerColor = VerifyPurpleSoft,
                    focusedBorderColor = VerifyPurple,
                    unfocusedBorderColor = Color(0xFFCAC4D0),
                    focusedLabelColor = VerifyPurple,
                    unfocusedLabelColor = VerifyMuted,
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
            )

            if (codeSent) {
                Spacer(Modifier.height(14.dp))
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
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = VerifyPurpleLight,
                        unfocusedContainerColor = VerifyPurpleSoft,
                        focusedBorderColor = VerifyPurple,
                        unfocusedBorderColor = Color(0xFFCAC4D0),
                        focusedLabelColor = VerifyPurple,
                        unfocusedLabelColor = VerifyMuted,
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                )
            }

            if (info.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(info, color = VerifyPurple, fontSize = 12.sp)
            }
            if (error.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            if (busy) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = VerifyPurple)
            }

            Spacer(Modifier.height(22.dp))

            Button(
                onClick = {
                    if (codeSent) confirmCode() else sendCode()
                },
                enabled = !busy && email.isNotBlank() && (!codeSent || code.length == 6),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = VerifyPurple,
                    contentColor = Color.White,
                ),
            ) {
                Text(
                    if (codeSent) "Verify & continue" else "Send code",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (codeSent) {
                TextButton(onClick = {
                    codeSent = false
                    code = ""
                    error = ""
                    info = ""
                    debugBypass = false
                }) {
                    Text("Use a different email", fontSize = 12.sp)
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
    }
}
