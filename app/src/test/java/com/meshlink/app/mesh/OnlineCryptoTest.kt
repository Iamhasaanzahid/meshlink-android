package com.meshlink.app.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.SecureRandom

class OnlineCryptoTest {

    private val random = SecureRandom()

    @Test
    fun roundTripRestoresTheFrame() {
        val key = OnlineCrypto.pairKey("OFF-AAA111", "OFF-BBB222")
        val frame = ByteArray(96) { (it * 7).toByte() }
        val blob = OnlineCrypto.encrypt(key, frame, random)
        assertNotNull(blob)
        assertFalse(blob.contentEquals(frame))
        assertArrayEquals(frame, OnlineCrypto.decrypt(key, blob))
    }

    @Test
    fun bothSidesDeriveTheSameKey() {
        val a = OnlineCrypto.pairKey("OFF-AAA111", "OFF-BBB222")
        val b = OnlineCrypto.pairKey("OFF-BBB222", "OFF-AAA111")
        assertArrayEquals(a.encoded, b.encoded)
    }

    @Test
    fun differentPairsGetDifferentKeys() {
        val one = OnlineCrypto.pairKey("OFF-AAA111", "OFF-BBB222")
        val two = OnlineCrypto.pairKey("OFF-AAA111", "OFF-CCC333")
        assertFalse(one.encoded.contentEquals(two.encoded))
    }

    @Test
    fun wrongKeyCannotReadTheBlob() {
        val key = OnlineCrypto.pairKey("OFF-AAA111", "OFF-BBB222")
        val other = OnlineCrypto.pairKey("OFF-AAA111", "OFF-CCC333")
        val blob = OnlineCrypto.encrypt(key, "hello".toByteArray(), random)
        assertNull(OnlineCrypto.decrypt(other, blob))
    }

    @Test
    fun tamperingIsRejected() {
        val key = OnlineCrypto.pairKey("OFF-AAA111", "OFF-BBB222")
        val blob = OnlineCrypto.encrypt(key, "hello mesh".toByteArray(), random)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        assertNull(OnlineCrypto.decrypt(key, blob))
    }

    @Test
    fun everyMessageUsesAFreshIv() {
        val key = OnlineCrypto.pairKey("OFF-AAA111", "OFF-BBB222")
        val plain = "same text".toByteArray()
        val one = OnlineCrypto.encrypt(key, plain, random)
        val two = OnlineCrypto.encrypt(key, plain, random)
        assertFalse(one.contentEquals(two))
        assertArrayEquals(plain, OnlineCrypto.decrypt(key, one))
        assertArrayEquals(plain, OnlineCrypto.decrypt(key, two))
    }

    @Test
    fun emptyAndShortBlobsAreRejected() {
        val key = OnlineCrypto.pairKey("OFF-AAA111", "OFF-BBB222")
        assertNull(OnlineCrypto.decrypt(key, ByteArray(0)))
        assertNull(OnlineCrypto.decrypt(key, byteArrayOf(1)))
        assertNull(OnlineCrypto.decrypt(key, ByteArray(13)))
    }

    @Test
    fun blobCarriesVersionByte() {
        val key = OnlineCrypto.pairKey("OFF-AAA111", "OFF-BBB222")
        val blob = OnlineCrypto.encrypt(key, "x".toByteArray(), random)
        assertEquals(1, blob[0].toInt())
    }
}
