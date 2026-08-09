package org.offlinemesh.app.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * Tier 1: [BulkFraming]'s length-prefixed stream framing — plain `java.io` streams, no
 * `BluetoothSocket`/Android dependency (see [BulkFraming]'s own doc for why: a Robolectric spike
 * this session found `listenUsingInsecureL2capChannel()` returns a non-functional stub under test,
 * so this is the actual real-I/O coverage this slice gets, mirroring the retired
 * `WifiDirectAcceleratorSocketTest`'s real-`java.net.Socket`-loopback approach for its own
 * plain-TCP framing).
 */
class BulkFramingTest {
    @Test
    fun `writeFrame then readFrame round-trips an arbitrary frame`() {
        val out = ByteArrayOutputStream()
        val frame = MeshFrameCodec.encodeEvidSymbol(
            MeshFrameCodec.Frame.EvidSymbol("evid-1", esi = 3, data = ByteArray(400) { it.toByte() })
        )
        BulkFraming.writeFrame(out, frame)
        val recovered = BulkFraming.readFrame(ByteArrayInputStream(out.toByteArray()))
        assertArrayEquals(frame, recovered)
    }

    @Test
    fun `multiple frames back-to-back on one stream are each read separately, in order`() {
        val out = ByteArrayOutputStream()
        val frames = listOf(
            byteArrayOf(1, 2, 3),
            byteArrayOf(4, 5, 6, 7, 8),
            byteArrayOf(9),
        )
        for (f in frames) BulkFraming.writeFrame(out, f)
        val input = ByteArrayInputStream(out.toByteArray())
        for (expected in frames) {
            assertArrayEquals(expected, BulkFraming.readFrame(input))
        }
        // Nothing left on the stream.
        assertNull(BulkFraming.readFrame(input))
    }

    @Test
    fun `readFrame returns null on clean EOF (peer closed the stream)`() {
        assertNull(BulkFraming.readFrame(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `readFrame rejects a hostile oversized length prefix without allocating`() {
        val out = ByteArrayOutputStream()
        java.io.DataOutputStream(out).writeInt(BulkFraming.MAX_FRAME_BYTES + 10_000_000)
        // Deliberately nothing else written — a real attack would never actually have that many
        // bytes to send; the point is readFrame must reject on the length alone, not hang trying
        // to readFully() bytes that are never coming.
        assertNull(BulkFraming.readFrame(ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun `readFrame rejects a zero or negative length prefix`() {
        for (len in intArrayOf(0, -1, -1000)) {
            val out = ByteArrayOutputStream()
            java.io.DataOutputStream(out).writeInt(len)
            assertNull("length=$len should be rejected", BulkFraming.readFrame(ByteArrayInputStream(out.toByteArray())))
        }
    }

    @Test
    fun `round-trips over a real piped stream, not just an in-memory buffer`() {
        // A PipedInputStream/PipedOutputStream pair is a genuine blocking I/O stream (backed by a
        // real pipe, unlike ByteArrayInputStream/OutputStream which never block) — closer to what a
        // real BluetoothSocket's own streams behave like, without needing Bluetooth itself.
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, PIPE_BUFFER_SIZE)
        val frame = byteArrayOf(10, 20, 30, 40, 50)
        val writer = Thread { BulkFraming.writeFrame(pipedOut, frame); pipedOut.close() }
        writer.start()
        val recovered = BulkFraming.readFrame(pipedIn)
        writer.join(THREAD_JOIN_TIMEOUT_MS)
        assertArrayEquals(frame, recovered)
    }

    private companion object {
        const val PIPE_BUFFER_SIZE = 4096
        const val THREAD_JOIN_TIMEOUT_MS = 5_000L
    }
}
