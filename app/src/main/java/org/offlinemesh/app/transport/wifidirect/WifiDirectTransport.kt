package org.offlinemesh.app.transport.wifidirect

import org.offlinemesh.app.ble.MeshFrameCodec

/** What [WifiDirectHandoffCoordinator] needs from the real radio layer — [WifiDirectAccelerator]
 *  is the only production implementation. Exists purely so the coordinator's decision/verification
 *  logic can be unit-tested against a fake, the same way [ConnectionAttemptTracker] is tested
 *  without any real `BluetoothGatt` — a real [WifiDirectAccelerator] needs a live `Context` to even
 *  construct (it acquires `WifiP2pManager` in its own property initializers), which a plain JVM
 *  unit test can't provide without pulling in a mocking framework this project doesn't otherwise
 *  depend on.
 *
 *  `chunks`/`onChunk` carry [MeshFrameCodec.Frame.EvidSymbol] since decision 47 (docs/DECISIONS.md)
 *  — this whole subsystem is currently unreachable (nothing calls [WifiDirectHandoffCoordinator.
 *  maybeProposeHandoff] anymore; see that class's own doc), kept compiling as dead code pending
 *  PLAN-v2.md §4.3 item 3's already-planned removal of Wi-Fi Direct outright. */
interface WifiDirectTransport {
    suspend fun beginAsInitiator(
        peerAddress: String,
        token: ByteArray,
        readyAtEpochMs: Long,
        chunks: List<MeshFrameCodec.Frame.EvidSymbol>,
    )

    suspend fun beginAsResponder(
        peerAddress: String,
        token: ByteArray,
        readyAtEpochMs: Long,
        onChunk: suspend (MeshFrameCodec.Frame.EvidSymbol) -> Unit,
    )

    fun abortCurrent()
}
