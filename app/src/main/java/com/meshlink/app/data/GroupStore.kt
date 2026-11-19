package com.meshlink.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Local group address book. Invites and messages use the normal encrypted chat path. */
object GroupStore {
    data class Group(val id: String, val name: String, val members: Set<String>)

    private const val PREFS = "meshlink_groups"
    private const val KEY = "groups"
    private val lock = Any()
    private var prefs: SharedPreferences? = null
    private var cache: MutableMap<String, Group>? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun isGroup(peer: String): Boolean = peer.trim().uppercase().startsWith("GRP-")

    fun all(): Map<String, Group> = synchronized(lock) {
        val p = prefs ?: return emptyMap()
        cache?.let { return it.toMap() }
        runCatching {
            val root = JSONObject(p.getString(KEY, "{}") ?: "{}")
            val map = HashMap<String, Group>()
            root.keys().forEach { id ->
                val o = root.getJSONObject(id)
                val arr = o.optJSONArray("members")
                val members = HashSet<String>()
                if (arr != null) for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let { members.add(it) }
                }
                map[id] = Group(id, o.optString("name", id), members)
            }
            map
        }.getOrDefault(emptyMap()).also { cache = HashMap(it) }
    }

    fun get(id: String): Group? = all()[id.trim().uppercase()]

    fun save(id: String, name: String, members: Set<String>) {
        val p = prefs ?: return
        synchronized(lock) {
            val map = HashMap(all())
            map[id] = Group(id, name.trim(), members.map { it.trim().uppercase() }.toSet())
            val root = JSONObject()
            map.values.forEach { g ->
                val o = JSONObject().put("name", g.name)
                val arr = JSONArray()
                g.members.sorted().forEach { arr.put(it) }
                o.put("members", arr)
                root.put(g.id, o)
            }
            p.edit().putString(KEY, root.toString()).apply()
            cache = map
        }
    }

    fun remove(id: String) {
        val p = prefs ?: return
        synchronized(lock) {
            val map = HashMap(all())
            map.remove(id)
            val root = JSONObject()
            map.values.forEach { g ->
                val o = JSONObject().put("name", g.name)
                val arr = JSONArray()
                g.members.sorted().forEach { arr.put(it) }
                o.put("members", arr)
                root.put(g.id, o)
            }
            p.edit().putString(KEY, root.toString()).apply()
            cache = map
        }
    }

    fun ensure(id: String, name: String, newMembers: Set<String>) {
        val existing = get(id)
        val merged = HashSet(existing?.members ?: emptySet()).apply { addAll(newMembers) }
        save(id, existing?.name?.takeIf { it.isNotBlank() } ?: name, merged)
    }
}
