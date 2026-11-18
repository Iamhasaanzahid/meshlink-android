package com.meshlink.app.mesh

/**
 * Splitting a file into mesh-sized pieces and putting it back together.
 *
 * Pure Kotlin so the arithmetic can be unit tested: an off-by-one here would
 * corrupt every photo sent over the mesh.
 */
object FileTransfer {

    /** A chunk frame is 96 bytes: 4 transfer-id + 2 index + data. */
    const val MAX_CHUNK = 90

    /** Metadata has to fit in one mesh frame as well. */
    const val MAX_META = 96

    fun chunkCount(size: Int, chunkSize: Int = MAX_CHUNK): Int =
        if (size <= 0) 1 else (size + chunkSize - 1) / chunkSize

    fun split(data: ByteArray, chunkSize: Int = MAX_CHUNK): List<ByteArray> {
        require(chunkSize in 1..65_535) { "bad chunk size: $chunkSize" }
        if (data.isEmpty()) return listOf(ByteArray(0))
        val out = ArrayList<ByteArray>(chunkCount(data.size, chunkSize))
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunkSize, data.size)
            out += data.copyOfRange(offset, end)
            offset = end
        }
        return out
    }

    /** Rebuilds a file from chunks that may arrive out of order. */
    class Assembler(val total: Int) {
        private val parts = arrayOfNulls<ByteArray>(total)
        var received: Int = 0
            private set
        var updatedAt: Long = System.currentTimeMillis()
            private set

        fun put(index: Int, data: ByteArray): Boolean {
            if (index < 0 || index >= total) return false
            updatedAt = System.currentTimeMillis()
            if (parts[index] != null) return false
            parts[index] = data
            received++
            return received == total
        }

        fun isComplete(): Boolean = received == total

        fun join(): ByteArray {
            var size = 0
            parts.forEach { size += it?.size ?: 0 }
            val out = ByteArray(size)
            var pos = 0
            parts.forEach { part ->
                if (part != null) {
                    part.copyInto(out, pos)
                    pos += part.size
                }
            }
            return out
        }

        fun missing(): List<Int> =
            parts.indices.filter { parts[it] == null }
    }
}
