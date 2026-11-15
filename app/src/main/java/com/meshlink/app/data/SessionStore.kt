package com.meshlink.app.data

import android.content.Context

/**
 * Remembers the signed-up identity on this device so the app opens straight
 * into the chat screen after a restart (and after a reboot, so the mesh
 * service can be reconfigured with the right node id).
 */
object SessionStore {

    private const val PREFS = "meshlink_session"
    private const val KEY_UIN = "uin"
    private const val KEY_NAME = "display_name"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun uin(context: Context): String? =
        prefs(context).getString(KEY_UIN, null)?.takeIf { it.isNotBlank() }

    fun displayName(context: Context): String =
        prefs(context).getString(KEY_NAME, "") ?: ""

    fun save(context: Context, uin: String, displayName: String = "") {
        prefs(context).edit()
            .putString(KEY_UIN, uin)
            .putString(KEY_NAME, displayName)
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
