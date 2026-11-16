package com.meshlink.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The runtime permissions MeshLink needs, in one place.
 *
 * [why] is the plain-language reason shown to the user before the Android
 * dialogs appear, so the sensitive-permission warnings are not a surprise.
 */
object PermissionsHelper {

    data class Item(val permission: String, val title: String, val why: String)

    fun required(): List<Item> {
        val list = mutableListOf(
            Item(
                Manifest.permission.SEND_SMS,
                "Send SMS",
                "Sends a message over your SIM when no MeshLink phone is in radio range.",
            ),
            Item(
                Manifest.permission.RECEIVE_SMS,
                "Receive SMS",
                "Receives messages that arrive over SMS from other MeshLink users.",
            ),
            Item(
                Manifest.permission.RECORD_AUDIO,
                "Microphone",
                "Only while you are in a MeshLink call. Nothing is recorded or uploaded.",
            ),
            Item(
                Manifest.permission.CALL_PHONE,
                "Phone calls",
                "Places a normal SIM call when a MeshLink call cannot connect.",
            ),
            Item(
                Manifest.permission.MODIFY_AUDIO_SETTINGS,
                "Audio routing",
                "Switches between the earpiece, speakerphone and normal call audio.",
            ),
        )

        // Android 12 and older need location to scan Wi-Fi Direct. On Android
        // 13+ the Nearby devices permission replaces it, so do not ask for it.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            list += Item(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                "Location",
                "Android requires this to scan for nearby Wi-Fi Direct phones. MeshLink never reads or stores your location.",
            )
            list += Item(
                Manifest.permission.ACCESS_FINE_LOCATION,
                "Location",
                "Same Wi-Fi Direct scan. Android asks for both together; nothing is read or stored.",
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Item(
                Manifest.permission.BLUETOOTH_SCAN,
                "Bluetooth scan",
                "Finds nearby MeshLink phones over Bluetooth LE.",
            )
            list += Item(
                Manifest.permission.BLUETOOTH_CONNECT,
                "Bluetooth connect",
                "Connects to nearby MeshLink phones over Bluetooth LE.",
            )
            list += Item(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                "Bluetooth advertise",
                "Lets other MeshLink phones find this phone.",
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Item(
                Manifest.permission.POST_NOTIFICATIONS,
                "Notifications",
                "Shows incoming messages and calls while the app is in the background.",
            )
            list += Item(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                "Nearby devices",
                "Android's replacement for location when scanning Wi-Fi Direct.",
            )
        }

        list += Item(
            Manifest.permission.VIBRATE,
            "Vibration",
            "Rings and vibrates for incoming calls.",
        )
        return list
    }

    fun allPermissions(): Array<String> = required().map { it.permission }.distinct().toTypedArray()

    fun missing(context: Context): List<String> = allPermissions().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    fun allGranted(context: Context): Boolean = missing(context).isEmpty()
}
