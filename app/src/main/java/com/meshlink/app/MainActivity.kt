package com.meshlink.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.meshlink.app.data.ChatRepository
import com.meshlink.app.data.SessionStore
import com.meshlink.app.service.startMeshService
import com.meshlink.app.service.stopMeshService
import com.meshlink.app.ui.HomeScreen
import com.meshlink.app.ui.MeshLinkTheme
import com.meshlink.app.ui.SignupScreen

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestMeshPermissions()

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

                val uin = sessionUin
                if (uin == null) {
                    SignupScreen(
                        onSignupSuccess = { newUin, name ->
                            SessionStore.save(activity, newUin, name)
                            startMeshService(activity, newUin)
                            sessionUin = newUin
                            displayName = name
                        }
                    )
                } else {
                    HomeScreen(
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
        val wanted = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            Manifest.permission.VIBRATE,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wanted += Manifest.permission.BLUETOOTH_SCAN
            wanted += Manifest.permission.BLUETOOTH_CONNECT
            wanted += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
            wanted += Manifest.permission.NEARBY_WIFI_DEVICES
        }

        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
}
