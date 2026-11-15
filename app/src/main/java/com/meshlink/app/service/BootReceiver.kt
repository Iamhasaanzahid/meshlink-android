package com.meshlink.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.meshlink.app.data.SessionStore

/** Restarts the mesh service after a reboot using the saved identity. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val serviceIntent = Intent(context, MeshService::class.java).apply {
            action = MeshService.ACTION_START
            putExtra(MeshService.EXTRA_UIN, SessionStore.uin(context))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
