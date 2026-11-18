package com.meshlink.app.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Bridge envelope: split a message, then reassemble it like a relay does. */
class BridgeFramesTest {

    private fun reassemble(parts: List<ByteArray>): ByteArray {
        val total = BridgeFrames.parse(parts[0])!!.total
        val data = arrayOfNulls<ByteArray>(total)
        parts.forEach { p ->
            val h = BridgeFrames.parse(p)!!
            data[h.sequence] = p.copyOfRange(BridgeFrames.HEADER, p.size)
        }
        return data.fold(ByteArray(0)) { acc, b -> acc + b!! }
    }

    @Test
    fun `short message is one part`() {
        val json = """{"i":"abc","b":"salam"}""".toByteArray()
        val parts = BridgeFrames.payloads("OFF-RKEX7H", "abc", json)
        assertEquals(1, parts.size)
        assertArrayEquals(json, reassemble(parts))
    }

    @Test
    fun `long message splits and rejoins`() {
        val json = Random(3).nextBytes(500)
        val parts = BridgeFrames.payloads("OFF-RKEX7H", "id-1", json)
        assertTrue(parts.size > 1)
        parts.forEach { assertTrue(it.size <= MeshFrame.MAX_PAYLOAD) }
        assertArrayEquals(json, reassemble(parts))
    }

    @Test
    fun `header names the destination`() {
        val parts = BridgeFrames.payloads("off-rkex7h", "id", "{}".toByteArray())
        assertEquals("OFF-RKEX7H", BridgeFrames.parse(parts[0])!!.destination)
    }

    @Test
    fun `phone numbers are not bridgeable`() {
        assertTrue(BridgeFrames.payloads("+923001234567", "id", "{}".toByteArray()).isEmpty())
    }

    @Test
    fun `garbage is rejected`() {
        assertNull(BridgeFrames.parse(ByteArray(4)))
        assertNull(BridgeFrames.parse(ByteArray(BridgeFrames.HEADER)))
    }
}
