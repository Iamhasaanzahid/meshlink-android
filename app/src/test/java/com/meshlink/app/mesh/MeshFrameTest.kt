package com.meshlink.app.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Wire-format tests. These run on the JVM, no Android device needed, and
 * guard the bytes that go over SMS, Bluetooth LE and Wi-Fi Direct.
 */
class MeshFrameTest {

    private fun frame(
        type: Byte = MeshFrame.TYPE_MESSAGE,
        src: Int = 0x1234,
        dst: Int = 0xBEEF,
        ttl: Int = 7,
        payload: ByteArray = "hello".toByteArray(),
    ) = MeshFrame.Frame(type, src, dst, ttl, payload)

    @Test
    fun `serialize then deserialize round trips every field`() {
        val original = frame()
        val decoded = MeshFrame.deserialize(original.serialize())

        requireNotNull(decoded)
        assertEquals(original.type, decoded.type)
        assertEquals(original.sourceNodeId, decoded.sourceNodeId)
        assertEquals(original.destNodeId, decoded.destNodeId)
        assertEquals(original.ttl, decoded.ttl)
        assertArrayEquals(original.payload, decoded.payload)
    }

    @Test
    fun `header layout matches the documented offsets`() {
        val bytes = frame().serialize()

        assertEquals(MeshFrame.HEADER_SIZE + 5, bytes.size)
        assertEquals('M'.code.toByte(), bytes[0])
        assertEquals('E'.code.toByte(), bytes[1])
        assertEquals('S'.code.toByte(), bytes[2])
        assertEquals('H'.code.toByte(), bytes[3])
        assertEquals(MeshFrame.VERSION, bytes[4])
        assertEquals(MeshFrame.TYPE_MESSAGE, bytes[5])
        assertEquals(0x12, bytes[6].toInt() and 0xFF)
        assertEquals(0x34, bytes[7].toInt() and 0xFF)
        assertEquals(0xBE, bytes[8].toInt() and 0xFF)
        assertEquals(0xEF, bytes[9].toInt() and 0xFF)
        assertEquals(7, bytes[10].toInt() and 0xFF)
        assertEquals(5, bytes[11].toInt() and 0xFF)
    }

    @Test
    fun `empty payload is valid`() {
        val decoded = MeshFrame.deserialize(frame(payload = ByteArray(0)).serialize())
        requireNotNull(decoded)
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun `maximum payload is valid and one byte more is rejected`() {
        val max = ByteArray(MeshFrame.MAX_PAYLOAD) { it.toByte() }
        val decoded = MeshFrame.deserialize(frame(payload = max).serialize())
        requireNotNull(decoded)
        assertArrayEquals(max, decoded.payload)

        val tooLarge = runCatching { frame(payload = ByteArray(MeshFrame.MAX_PAYLOAD + 1)).serialize() }
        assertEquals(true, tooLarge.isFailure)
    }

    @Test
    fun `frames shorter than the header are rejected`() {
        assertNull(MeshFrame.deserialize(ByteArray(0)))
        assertNull(MeshFrame.deserialize(ByteArray(MeshFrame.HEADER_SIZE - 1)))
    }

    @Test
    fun `wrong magic is rejected`() {
        val bytes = frame().serialize()
        bytes[0] = 'X'.code.toByte()
        assertNull(MeshFrame.deserialize(bytes))
    }

    @Test
    fun `unknown version is rejected`() {
        val bytes = frame().serialize()
        bytes[4] = 99
        assertNull(MeshFrame.deserialize(bytes))
    }

    @Test
    fun `truncated payload is rejected`() {
        val bytes = frame().serialize()
        assertNull(MeshFrame.deserialize(bytes.copyOf(bytes.size - 1)))
    }

    @Test
    fun `segment and call types keep their own type byte`() {
        for (type in listOf(MeshFrame.TYPE_SEGMENT, MeshFrame.TYPE_CALL, MeshFrame.TYPE_ACK)) {
            val decoded = MeshFrame.deserialize(frame(type = type).serialize())
            requireNotNull(decoded)
            assertEquals(type, decoded.type)
        }
    }

    @Test
    fun `node id is stable and 16 bits wide for the same input`() {
        val a = MeshFrame.nodeId16("OFF-ABC123")
        val b = MeshFrame.nodeId16("OFF-ABC123")

        assertEquals(a, b)
        assertEquals(a, a and 0xFFFF)
    }

    @Test
    fun `different identities get different node ids`() {
        val ids = (0 until 200).map { MeshFrame.nodeId16("OFF-USER-$it") }.toSet()
        assertEquals(200, ids.size)
        assertNotEquals(MeshFrame.nodeId16("OFF-AAA111"), MeshFrame.nodeId16("OFF-BBB222"))
    }

    @Test
    fun `ttl survives the full 0 to 255 range`() {
        for (ttl in listOf(0, 1, 8, 20, 255)) {
            val decoded = MeshFrame.deserialize(frame(ttl = ttl).serialize())
            requireNotNull(decoded)
            assertEquals(ttl, decoded.ttl)
        }
    }
}
