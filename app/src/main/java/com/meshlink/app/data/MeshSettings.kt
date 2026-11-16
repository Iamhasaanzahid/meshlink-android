package com.meshlink.app.data

import android.content.Context

/**
 * User-tunable mesh settings, stored on the device only.
 *
 * [maxHops] is how many phones a message may pass through before it is
 * dropped. Every extra hop adds range but also latency, because each relay
 * phone has to receive and re-broadcast the frame.
 */
object MeshSettings {

    private const val PREFS = "meshlink_settings"
    private const val KEY_MAX_HOPS = "max_hops"
    private const val KEY_SMS_FALLBACK = "sms_fallback"

    const val DEFAULT_HOPS = 8
    const val MIN_HOPS = 1
    const val MAX_HOPS = 20

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun maxHops(context: Context): Int =
        prefs(context).getInt(KEY_MAX_HOPS, DEFAULT_HOPS).coerceIn(MIN_HOPS, MAX_HOPS)

    fun setMaxHops(context: Context, hops: Int) {
        prefs(context).edit()
            .putInt(KEY_MAX_HOPS, hops.coerceIn(MIN_HOPS, MAX_HOPS))
            .apply()
    }

    /**
     * SMS fallback is off by default. It is the only transport that can cost
     * the user money, so it is opt-in and never used by automatic retries.
     */
    fun smsFallback(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SMS_FALLBACK, false)

    fun setSmsFallback(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SMS_FALLBACK, enabled).apply()
    }
}
