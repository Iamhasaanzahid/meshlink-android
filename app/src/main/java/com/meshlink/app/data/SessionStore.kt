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
    private const val KEY_PHONE = "phone_number"
    private const val KEY_PRIVACY = "privacy_seen"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True once the privacy / permissions explanation has been shown. */
    fun privacySeen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PRIVACY, false)

    fun markPrivacySeen(context: Context) {
        prefs(context).edit().putBoolean(KEY_PRIVACY, true).apply()
    }

    fun uin(context: Context): String? =
        prefs(context).getString(KEY_UIN, null)?.takeIf { it.isNotBlank() }

    fun displayName(context: Context): String =
        prefs(context).getString(KEY_NAME, "") ?: ""

    /**
     * Optional phone number, used only for the SMS fallback. It is shared with
     * people you chat with so they can reach you when the mesh cannot.
     */
    fun phone(context: Context): String =
        prefs(context).getString(KEY_PHONE, "") ?: ""

    fun savePhone(context: Context, phone: String) {
        prefs(context).edit().putString(KEY_PHONE, phone.trim()).apply()
    }

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
