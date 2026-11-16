package com.meshlink.app.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the online-call pairing codes. */
class CallCodesTest {

    private val sdp = "v=0\r\no=- 131041468 2 IN IP4 127.0.0.1\r\ns=-\r\n" +
        "t=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=ice-ufrag:abcd\r\n"

    @Test
    fun `offer round trips`() {
        val code = CallCodes.encode("offer", sdp, "Hassaan")
        assertTrue(code.startsWith("MLCALL1:"))

        val decoded = CallCodes.decode(code)
        assertNotNull(decoded)
        assertEquals("offer", decoded!!.type)
        assertEquals("Hassaan", decoded.name)
        assertEquals(sdp, decoded.sdp)
    }

    @Test
    fun `answer round trips`() {
        val code = CallCodes.encode("answer", sdp, "Ali")
        val decoded = CallCodes.decode(code)
        assertEquals("answer", decoded?.type)
        assertEquals("Ali", decoded?.name)
    }

    @Test
    fun `decodes without the prefix`() {
        val code = CallCodes.encode("offer", sdp, "").removePrefix("MLCALL1:")
        assertEquals("offer", CallCodes.decode(code)?.type)
    }

    @Test
    fun `ignores spaces and line breaks from chat apps`() {
        val code = CallCodes.encode("offer", sdp, "Hassaan")
        val wrapped = code.chunked(40).joinToString("\n")
        assertEquals(sdp, CallCodes.decode(wrapped)?.sdp)
    }

    @Test
    fun `empty name is allowed`() {
        val decoded = CallCodes.decode(CallCodes.encode("offer", sdp, ""))
        assertEquals("", decoded?.name)
        assertEquals(sdp, decoded?.sdp)
    }

    @Test
    fun `rejects garbage`() {
        assertNull(CallCodes.decode("hello"))
        assertNull(CallCodes.decode(""))
        assertNull(CallCodes.decode("MLCALL1:"))
        assertNull(CallCodes.decode("MLCALL1:!!!!"))
    }

    @Test
    fun `rejects unknown type`() {
        val code = CallCodes.encode("video", sdp, "x")
        assertNull(CallCodes.decode(code))
    }

    @Test
    fun `rejects empty sdp`() {
        assertNull(CallCodes.decode(CallCodes.encode("offer", "", "x")))
    }

    @Test
    fun `large code survives`() {
        val big = sdp.repeat(120) // ~8 KB, realistic for a full SDP with ICE
        val decoded = CallCodes.decode(CallCodes.encode("answer", big, "A long name"))
        assertEquals(big, decoded?.sdp)
        assertEquals("A long name", decoded?.name)
    }
}
