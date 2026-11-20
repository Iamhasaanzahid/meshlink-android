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
    private const val KEY_ONLINE = "online_enabled"
    private const val KEY_ONLINE_MIGRATED = "online_global_v2"
    private const val KEY_BROKER = "online_broker"

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

    /**
     * Online mode carries messages over the internet through an MQTT relay, so
     * MeshLink works anywhere in the world. It is on by default; local radios
     * stay free and keep working when there is no internet.
     */
    fun onlineEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONLINE, true)

    /** One-time upgrade: enable worldwide mode on existing installations. */
    fun ensureGlobalOnline(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean(KEY_ONLINE_MIGRATED, false)) {
            p.edit()
                .putBoolean(KEY_ONLINE_MIGRATED, true)
                .putBoolean(KEY_ONLINE, true)
                .apply()
        }
    }

    fun setOnlineEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONLINE, enabled).apply()
    }

    /** Broker URL, e.g. ssl://broker.hivemq.com:8883. */
    fun onlineBroker(context: Context): String =
        prefs(context).getString(KEY_BROKER, DEFAULT_BROKER)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_BROKER

    fun setOnlineBroker(context: Context, url: String) {
        prefs(context).edit().putString(KEY_BROKER, url.trim()).apply()
    }

    private const val KEY_PINNED = "pinned_peers"
    private const val KEY_MUTED = "muted_peers"

    /** Chats the user pinned to the top of the list (UINs, uppercase). */
    fun pinned(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PINNED, emptySet())?.toSet() ?: emptySet()

    fun togglePinned(context: Context, peer: String): Boolean {
        val key = peer.trim().uppercase()
        if (key.isEmpty()) return false
        val next = pinned(context).toMutableSet()
        val added = if (next.contains(key)) next.remove(key).let { false } else next.add(key)
        prefs(context).edit().putStringSet(KEY_PINNED, next).apply()
        return added
    }

    /** Muted chats never raise a notification (they still arrive normally). */
    fun muted(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_MUTED, emptySet())?.toSet() ?: emptySet()

    fun toggleMuted(context: Context, peer: String): Boolean {
        val key = peer.trim().uppercase()
        if (key.isEmpty()) return false
        val next = muted(context).toMutableSet()
        val added = if (next.contains(key)) next.remove(key).let { false } else next.add(key)
        prefs(context).edit().putStringSet(KEY_MUTED, next).apply()
        return added
    }

    fun isMuted(context: Context, peer: String): Boolean =
        muted(context).contains(peer.trim().uppercase())

    const val DEFAULT_BROKER = "ssl://broker.hivemq.com:8883"
}
