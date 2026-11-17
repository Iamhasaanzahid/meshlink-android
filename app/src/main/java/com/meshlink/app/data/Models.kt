package com.meshlink.app.data

/** Delivery state of an outgoing message. */
enum class MsgStatus {
    /** Queued; the radio is trying to deliver it. */
    SENDING,
    /** Handed to a radio successfully (single tick). */
    SENT,
    /** The other phone confirmed receipt (double tick). */
    DELIVERED,
    /** Incoming message. */
    RECEIVED,
    /** No radio could carry it. Tap to retry. */
    FAILED,
}

/** What a message carries: plain text, a photo, any file, or a voice note. */
enum class MsgKind { TEXT, IMAGE, DOCUMENT, AUDIO }

/** One message inside a conversation. */
data class Message(
    val id: String,
    val chatId: String,
    val fromMe: Boolean,
    val body: String,
    val timestamp: Long,
    val transport: String,
    val status: MsgStatus,
    val kind: MsgKind = MsgKind.TEXT,
    val mediaPath: String? = null,
    val mediaMime: String = "",
    val mediaName: String = "",
    val mediaSize: Long = 0,
)

/** A conversation row in the chat list. */
data class Chat(
    val peer: String,
    val name: String,
    val lastMessage: String,
    val lastTimestamp: Long,
    val unread: Int,
    val lastTransport: String,
)

/** A call log entry. */
data class CallLog(
    val id: String,
    val peer: String,
    val name: String,
    val outgoing: Boolean,
    val missed: Boolean,
    val transport: String,
    val timestamp: Long,
    val durationSec: Int,
    val status: String,
)

/** A saved contact. */
data class Contact(
    val id: Long,
    val name: String,
    val address: String,
)
