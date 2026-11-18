package com.meshlink.app.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Tests for splitting files into mesh chunks and reassembling them. */
class FileTransferTest {

    @Test
    fun `chunk count rounds up`() {
        assertEquals(1, FileTransfer.chunkCount(1))
        assertEquals(1, FileTransfer.chunkCount(90))
        assertEquals(2, FileTransfer.chunkCount(91))
        assertEquals(3, FileTransfer.chunkCount(180 + 1))
    }

    @Test
    fun `online chunk size splits a photo into a few pieces`() {
        val data = Random(11).nextBytes(300_000)
        val chunkSize = 48 * 1024
        val chunks = FileTransfer.split(data, chunkSize)
        assertEquals(7, chunks.size)
        assertTrue(chunks.all { it.size <= chunkSize })

        val assembler = FileTransfer.Assembler(chunks.size)
        chunks.forEachIndexed { i, c -> assembler.put(i, c) }
        assertTrue(assembler.isComplete())
        assertArrayEquals(data, assembler.join())
    }

    @Test
    fun `online chunk count matches the split`() {
        assertEquals(1, FileTransfer.chunkCount(48 * 1024, 48 * 1024))
        assertEquals(2, FileTransfer.chunkCount(48 * 1024 + 1, 48 * 1024))
    }

    @Test
    fun `empty file is one empty chunk`() {
        val chunks = FileTransfer.split(ByteArray(0))
        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].size)
    }

    @Test
    fun `split never exceeds the frame budget`() {
        val data = Random(1).nextBytes(5_000)
        FileTransfer.split(data).forEach {
            assertTrue("chunk too big: ${it.size}", it.size <= FileTransfer.MAX_CHUNK)
        }
    }

    @Test
    fun `round trip preserves every byte`() {
        val data = Random(7).nextBytes(12_345)
        val chunks = FileTransfer.split(data)
        val assembler = FileTransfer.Assembler(chunks.size)
        chunks.forEachIndexed { index, bytes ->
            val done = assembler.put(index, bytes)
            if (index < chunks.lastIndex) assertFalse(done)
        }
        assertTrue(assembler.isComplete())
        assertArrayEquals(data, assembler.join())
    }

    @Test
    fun `out of order chunks still reassemble`() {
        val data = Random(3).nextBytes(1_000)
        val chunks = FileTransfer.split(data)
        val assembler = FileTransfer.Assembler(chunks.size)
        chunks.indices.reversed().forEach { assembler.put(it, chunks[it]) }
        assertTrue(assembler.isComplete())
        assertArrayEquals(data, assembler.join())
    }

    @Test
    fun `duplicate chunk does not corrupt the file`() {
        val data = Random(5).nextBytes(500)
        val chunks = FileTransfer.split(data)
        val assembler = FileTransfer.Assembler(chunks.size)
        assertFalse(assembler.put(0, chunks[0]))
        assertFalse(assembler.put(0, chunks[0]))
        assertEquals(1, assembler.received)
    }

    @Test
    fun `missing reports gaps`() {
        val data = Random(9).nextBytes(400)
        val chunks = FileTransfer.split(data)
        val assembler = FileTransfer.Assembler(chunks.size)
        assembler.put(0, chunks[0])
        assembler.put(2, chunks[2])
        val missing = assembler.missing()
        assertFalse(missing.contains(0))
        assertFalse(missing.contains(2))
        assertTrue(missing.contains(1))
        assertFalse(assembler.isComplete())
    }

    @Test
    fun `index outside the range is rejected`() {
        val assembler = FileTransfer.Assembler(2)
        assertFalse(assembler.put(-1, ByteArray(1)))
        assertFalse(assembler.put(2, ByteArray(1)))
        assertEquals(0, assembler.received)
    }
}
