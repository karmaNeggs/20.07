package org.offlinemesh.app.ble

import org.offlinemesh.app.data.EvidenceChunkEntity

/** What [WifiDirectHandoffCoordinator] needs from the real radio layer — [WifiDirectAccelerator]
 *  is the only production implementation. Exists purely so the coordinator's decision/verification
 *  logic can be unit-tested against a fake, the same way [ConnectionAttemptTracker] is tested
 *  without any real `BluetoothGatt` — a real [WifiDirectAccelerator] needs a live `Context` to even
 *  construct (it acquires `WifiP2pManager` in its own property initializers), which a plain JVM
 *  unit test can't provide without pulling in a mocking framework this project doesn't otherwise
 *  depend on. */
interface WifiDirectTransport {
    suspend fun beginAsInitiator(
        peerAddress: String,
        token: ByteArray,
        readyAtEpochMs: Long,
        chunks: List<EvidenceChunkEntity>,
    )

    suspend fun beginAsResponder(
        peerAddress: String,
        token: ByteArray,
        readyAtEpochMs: Long,
        onChunk: suspend (EvidenceChunkEntity) -> Unit,
    )

    fun abortCurrent()
}
