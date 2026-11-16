package com.meshlink.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.meshlink.app.data.ChatRepository
import com.meshlink.app.data.SessionStore
import com.meshlink.app.service.startMeshService
import com.meshlink.app.service.stopMeshService
import com.meshlink.app.ui.HomeScreen
import com.meshlink.app.ui.MeshLinkTheme
import com.meshlink.app.ui.PrivacyScreen
import com.meshlink.app.ui.SignupScreen
import com.meshlink.app.util.PermissionsHelper

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Local database (chats, messages, calls, contacts).
        ChatRepository.init(this)

        // Hardware-backed identity key.
        runCatching { com.meshlink.app.crypto.KeyManager().ensureE2EEKeyPair() }

        // Reconnect the mesh on every launch using the saved identity.
        SessionStore.uin(this)?.let { startMeshService(this, it) }

        val activity = this
        setContent {
            MeshLinkTheme {
                var sessionUin by remember { mutableStateOf(SessionStore.uin(activity)) }
                var displayName by remember {
                    mutableStateOf(SessionStore.displayName(activity))
                }
                var privacySeen by remember {
                    mutableStateOf(SessionStore.privacySeen(activity))
                }

                val uin = sessionUin
                when {
                    !privacySeen -> PrivacyScreen(
                        onAllow = {
                            SessionStore.markPrivacySeen(activity)
                            privacySeen = true
                            requestMeshPermissions()
                        },
                        onSkip = {
                            SessionStore.markPrivacySeen(activity)
                            privacySeen = true
                        },
                    )

                    uin == null -> SignupScreen(
                        onSignupSuccess = { newUin, name ->
                            SessionStore.save(activity, newUin, name)
                            startMeshService(activity, newUin)
                            sessionUin = newUin
                            displayName = name
                            // Permissions are usually granted on the privacy
                            // screen; ask again here for people who skipped it.
                            if (!PermissionsHelper.allGranted(activity)) requestMeshPermissions()
                        }
                    )

                    else -> HomeScreen(
                        myUin = uin,
                        displayName = displayName,
                        onLogout = {
                            stopMeshService(activity)
                            SessionStore.clear(activity)
                            sessionUin = null
                            displayName = ""
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        com.meshlink.app.service.AppForeground.active = true
    }

    override fun onPause() {
        com.meshlink.app.service.AppForeground.active = false
        super.onPause()
    }

    /**
     * Radio permissions must be granted at runtime on modern Android:
     *  - SMS  → cellular radio mesh
     *  - BLE  → Bluetooth radio mesh
     *  - Wi-Fi Direct → local radio mesh
     *  - Phone / Microphone → calls (SIM dial + Wi-Fi Direct voice)
     */
    private fun requestMeshPermissions() {
        val missing = PermissionsHelper.missing(this)
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
}
