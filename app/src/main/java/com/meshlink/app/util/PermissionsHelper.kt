package com.meshlink.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The runtime permissions MeshLink needs, in one place.
 *
 * Permissions are split in two groups on purpose:
 *
 *  - [core] keeps the free radio mesh alive (Bluetooth LE, Wi-Fi Direct,
 *    Wi-Fi LAN, in-app calls). It never touches SMS.
 *  - [sms] is only for the optional SMS fallback. Android warns that SMS can
 *    cost money, so MeshLink asks for it only when the user switches that
 *    feature on in Settings — never on first launch.
 */
object PermissionsHelper {

    data class Item(val permission: String, val title: String, val why: String)

    /** Everything the offline mesh needs, without SMS. */
    fun core(): List<Item> {
        val list = mutableListOf(
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
                "Finds MeshLink phones on the same Wi-Fi network or hotspot.",
            )
        }

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

        list += Item(
            Manifest.permission.VIBRATE,
            "Vibration",
            "Rings and vibrates for incoming calls.",
        )
        return list
    }

    /** Optional group: only needed for the SIM-based SMS fallback. */
    fun sms(): List<Item> = listOf(
        Item(
            Manifest.permission.SEND_SMS,
            "Send SMS",
            "Only when you switch on SMS fallback in Settings. Your operator's normal SMS rate may apply.",
        ),
        Item(
            Manifest.permission.RECEIVE_SMS,
            "Receive SMS",
            "Reads MeshLink messages that arrive as SMS. Personal SMS are never opened or stored.",
        ),
        Item(
            Manifest.permission.READ_PHONE_STATE,
            "Active SIM",
            "Picks the right SIM for MeshLink SMS on dual-SIM phones. No call history or contacts are read.",
        ),
    )

    fun corePermissions(): Array<String> = core().map { it.permission }.distinct().toTypedArray()

    fun smsPermissions(): Array<String> = sms().map { it.permission }.distinct().toTypedArray()

    fun missing(context: Context): List<String> = corePermissions().filter { !granted(context, it) }

    fun allGranted(context: Context): Boolean = missing(context).isEmpty()

    fun smsGranted(context: Context): Boolean = smsPermissions().all { granted(context, it) }

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
