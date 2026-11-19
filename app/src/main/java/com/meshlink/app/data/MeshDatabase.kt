package com.meshlink.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local-only SQLite store. Nothing ever leaves the phone except the mesh
 * frames themselves, which travel over SMS / Bluetooth LE / Wi-Fi Direct.
 */
class MeshDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "meshlink.db", null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE messages (
                id TEXT PRIMARY KEY,
                chat_id TEXT NOT NULL,
                from_me INTEGER NOT NULL,
                body TEXT NOT NULL,
                ts INTEGER NOT NULL,
                transport TEXT NOT NULL,
                status TEXT NOT NULL,
                kind TEXT NOT NULL DEFAULT 'TEXT',
                media_path TEXT,
                media_mime TEXT NOT NULL DEFAULT '',
                media_name TEXT NOT NULL DEFAULT '',
                media_size INTEGER NOT NULL DEFAULT 0,
                reaction TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_messages_chat ON messages(chat_id, ts)")

        db.execSQL(
            """
            CREATE TABLE chats (
                peer TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                last_message TEXT NOT NULL,
                last_ts INTEGER NOT NULL,
                unread INTEGER NOT NULL DEFAULT 0,
                last_transport TEXT NOT NULL DEFAULT 'AUTO',
                phone TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE calls (
                id TEXT PRIMARY KEY,
                peer TEXT NOT NULL,
                name TEXT NOT NULL,
                outgoing INTEGER NOT NULL,
                missed INTEGER NOT NULL,
                transport TEXT NOT NULL,
                ts INTEGER NOT NULL,
                duration INTEGER NOT NULL,
                status TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_calls_ts ON calls(ts)")

        db.execSQL(
            """
            CREATE TABLE contacts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                address TEXT NOT NULL UNIQUE
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE messages ADD COLUMN reaction TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 2) {
            // Phone number shared by the other side, used for the SMS fallback.
            db.execSQL("ALTER TABLE chats ADD COLUMN phone TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 3) {
            // Photos, documents and voice notes.
            db.execSQL("ALTER TABLE messages ADD COLUMN kind TEXT NOT NULL DEFAULT 'TEXT'")
            db.execSQL("ALTER TABLE messages ADD COLUMN media_path TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN media_mime TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN media_name TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN media_size INTEGER NOT NULL DEFAULT 0")
        }
    }

    companion object {
        fun values(vararg pairs: Pair<String, Any?>): ContentValues =
            ContentValues().apply {
                pairs.forEach { (key, value) ->
                    when (value) {
                        null -> putNull(key)
                        is String -> put(key, value)
                        is Int -> put(key, value)
                        is Long -> put(key, value)
                        is Boolean -> put(key, if (value) 1 else 0)
                        else -> put(key, value.toString())
                    }
                }
            }
    }
}
