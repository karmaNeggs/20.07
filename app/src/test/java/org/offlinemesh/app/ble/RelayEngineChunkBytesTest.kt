package org.offlinemesh.app.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [RelayEngine.chunkBytes] — the `copyOfRange`-based replacement for
 * `data.toList().chunked(size)`, which boxed every byte of a plaintext/ciphertext into a
 * `java.lang.Byte` before this fix. Kept as its own file (not folded into a would-be
 * `RelayEngineTest`, which needs Robolectric for a real [android.content.Context]) since this
 * function has no such dependency and deserves to stay a plain, instant JVM test.
 */
class RelayEngineChunkBytesTest {

    @Test
    fun `splits evenly-divisible data into equal chunks`() {
        val data = ByteArray(12) { it.toByte() }
        val chunks = RelayEngine.chunkBytes(data, 4)
        assertEquals(3, chunks.size)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), chunks[0])
        assertArrayEquals(byteArrayOf(4, 5, 6, 7), chunks[1])
        assertArrayEquals(byteArrayOf(8, 9, 10, 11), chunks[2])
    }

    @Test
    fun `the final chunk is shorter when data doesn't divide evenly`() {
        val data = ByteArray(10) { it.toByte() }
        val chunks = RelayEngine.chunkBytes(data, 4)
        assertEquals(3, chunks.size)
        assertEquals(4, chunks[0].size)
        assertEquals(4, chunks[1].size)
        assertArrayEquals(byteArrayOf(8, 9), chunks[2]) // the short final chunk
    }

    @Test
    fun `concatenating the chunks back together reproduces the original bytes exactly`() {
        // The actual correctness property maybeReassemble on the receiving end depends on —
        // chunking must be lossless and order-preserving, matching this class's own arraycopy-
        // based reassembly exactly.
        val data = ByteArray(1000) { (it % 251).toByte() } // 251 is prime — avoids any accidental
        // periodicity lining up with CHUNK_SIZE-like boundaries and masking an ordering bug.
        val chunks = RelayEngine.chunkBytes(data, RelayEngine.CHUNK_SIZE)
        val reassembled = ByteArray(data.size)
        var offset = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, reassembled, offset, chunk.size)
            offset += chunk.size
        }
        assertArrayEquals(data, reassembled)
    }

    @Test
    fun `an empty input produces no chunks`() {
        assertTrue(RelayEngine.chunkBytes(ByteArray(0), 400).isEmpty())
    }

    @Test
    fun `data shorter than one chunk size produces a single short chunk`() {
        val data = ByteArray(50) { it.toByte() }
        val chunks = RelayEngine.chunkBytes(data, 400)
        assertEquals(1, chunks.size)
        assertArrayEquals(data, chunks[0])
    }
}
