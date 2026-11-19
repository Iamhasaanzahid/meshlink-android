package com.meshlink.app.util

import java.security.SecureRandom

/** MeshLink IDs are 10-digit numbers generated on the device.
 * Older `OFF-XXXXXX` identities stay valid for backwards compatibility. */
fun String.isMeshLinkId(): Boolean {
    val value = trim().uppercase()
    return value.startsWith("OFF-") ||
        (value.length == 10 && value.all { it.isDigit() })
}

/** A fresh 10-digit number; the first digit is never zero. */
fun newMeshLinkId(): String {
    val random = SecureRandom()
    return buildString {
        append(('1'.code + random.nextInt(9)).toChar())
        repeat(9) { append(('0'.code + random.nextInt(10)).toChar()) }
    }
}
