package org.offlinemesh.app.ble

/**
 * One open, bidirectional bulk-transfer channel to a specific peer — currently only ever backed by
 * a BLE L2CAP CoC socket (see [L2capBulkTransport]), kept as an interface so [RelayResponder]'s own
 * logic ([RelayResponder.handleSymbolRequest] preferring this over GATT's own `respond`) doesn't
 * need to know which concrete transport backs it. Same decoupling role the retired
 * `WifiDirectTransport` played for the (now-deleted) WFD accelerator.
 */
interface BulkChannel {
    /** Sends one already-encoded [MeshFrameCodec] frame. Returns false on any I/O failure — the
     *  caller's own fallback (GATT's `respond`) is what actually delivers the frame when this
     *  happens, not a retry here. */
    suspend fun send(frame: ByteArray): Boolean

    /** Idempotent, never throws — same contract the retired `WifiDirectAccelerator.abortCurrent`
     *  established for tearing down a radio resource from a disconnect path that must not fail. */
    fun close()
}

/**
 * Length-prefixed frame I/O over a plain byte stream — the actual framing logic
 * [L2capBulkTransport] needs, kept free of any `BluetoothSocket`/Android dependency so it's
 * directly unit-testable against ordinary `java.io` streams (a `PipedInputStream`/
 * `PipedOutputStream` pair, or a `ByteArrayOutputStream`), the same "decouple the mechanism from
 * the real radio" split the retired `WifiDirectAccelerator.handshakeToken`/`receiveChunks` already
 * used for their own now-deleted socket parsing. (CR-26, `PLAN-v2.md` Part 10, 2026-08-09: this was
 * previously a KDoc `[...]` link to a deleted class — a dangling reference, since it never resolves
 * — fixed to plain backticks, matching every other reference to a retired class in this same file.)
 *
 * **Deliberately NOT [MeshFrameCodec.padGattFrame]/`unpadGattFrame`** — that wrapper's bucket-
 * rounded padding was designed for, and only ever used by, MESSAGE-ORIENTED GATT writes, where the
 * platform callback itself already delivers exactly one bounded blob per call (`unpadGattFrame`
 * takes a single already-complete buffer, sized to its own bucket — there is no "read more until
 * the frame boundary" step because the transport already gives you the boundary). A raw byte stream
 * has no such boundary at all. The actual existing precedent in this codebase for a byte-stream
 * transport is the retired `WifiDirectAccelerator.sendChunks`/`receiveChunks`: a plain 4-byte
 * length prefix around the raw encoded frame, no bucket padding — reused verbatim here rather than
 * introducing `padGattFrame` into a stream context it was never built for. See `PLAN-v2.md` §4.3
 * item 3 / `docs/DECISIONS.md`'s own entry for this slice on why padding-over-the-bulk-pipe stays
 * an open, deliberately-not-silently-decided question rather than being resolved here.
 */
internal object BulkFraming {
    /** Generous ceiling on one bulk frame — a `FRAME_EVID_SYMBOL` is `RelayEngine.CHUNK_SIZE`
     *  (400) plus a small header, comfortably under this. Guards a hostile/corrupt length prefix
     *  from forcing an unbounded allocation, same reasoning the retired `WifiDirectTuning.
     *  MAX_CHUNK_FRAME_BYTES` gave its own now-deleted read loop. */
    const val MAX_FRAME_BYTES = 4096

    fun writeFrame(out: java.io.OutputStream, frame: ByteArray) {
        val d = java.io.DataOutputStream(out)
        d.writeInt(frame.size)
        d.write(frame)
        d.flush()
    }

    /** Null on clean EOF (peer closed the stream) or a length prefix outside
     *  `1..[MAX_FRAME_BYTES]` — both are "stop reading," not something worth distinguishing at this
     *  layer. */
    fun readFrame(input: java.io.InputStream): ByteArray? {
        val d = java.io.DataInputStream(input)
        // -1 on clean EOF -- deliberately folded into the same bounds check below rather than a
        // separate early return, since an EOF and an out-of-bounds length both mean the same thing
        // here: nothing valid left to read.
        val len = try { d.readInt() } catch (_: java.io.EOFException) { -1 }
        if (len !in 1..MAX_FRAME_BYTES) return null
        val bytes = ByteArray(len)
        d.readFully(bytes)
        return bytes
    }
}
