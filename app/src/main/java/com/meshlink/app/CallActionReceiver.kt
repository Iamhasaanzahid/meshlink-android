package com.meshlink.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meshlink.app.mesh.OnlineCallManager

/**
 * Notification actions for an incoming Mhzaly internet call. Lets the other
 * person pick up or reject straight from the lock screen — no need to open
 * the app first.
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ACCEPT -> {
                OnlineCallManager.acceptRelayed()
                val open = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(OPEN_ONLINE_CALL, true)
                }
                runCatching { context.startActivity(open) }
            }

            ACTION_DECLINE -> OnlineCallManager.declineRelayed()
        }
    }

    companion object {
        const val ACTION_ACCEPT = "com.meshlink.app.ACCEPT_CALL"
        const val ACTION_DECLINE = "com.meshlink.app.DECLINE_CALL"
        const val OPEN_ONLINE_CALL = "com.meshlink.app.OPEN_ONLINE_CALL"
    }
}
