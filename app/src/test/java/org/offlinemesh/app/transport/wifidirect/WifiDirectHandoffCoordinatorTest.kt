package org.offlinemesh.app.transport.wifidirect

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.offlinemesh.app.ble.MeshFrameCodec
import org.offlinemesh.app.ble.RelayEngine
import org.offlinemesh.app.crypto.CryptoUtils
import org.offlinemesh.app.data.EvidenceChunkEntity
import org.offlinemesh.app.data.GroupRepository
import org.robolectric.RobolectricTestRunner
import java.security.SecureRandom

/**
 * Tier 1 (Robolectric-backed — [RelayEngine] needs a real Context for its Room database, same
 * reason [org.offlinemesh.app.ui.RadarMathTest] needs it for real `android.location.Location`):
 * the WiFi Direct handoff protocol's decision/verification logic, against a [FakeTransport]
 * instead of any real `WifiP2pManager`/socket — mirrors [ConnectionAttemptTrackerTest]'s
 * structure. [WifiDirectHandoffCoordinator.capabilityAdvertisable] is driven by an injected
 * lambda rather than a real [WifiDirectCapabilities] check, the same reason `now` is injectable —
 * see that constructor param's doc. Uses `runTest`/`advanceTimeBy` (not a fake clock) to exercise
 * [WifiDirectHandoffCoordinator]'s real `delay()`-based timeout deterministically, without an
 * actual 20-second wait — a fake `now` only covers this class's own timestamp math (e.g.
 * `readyAtEpochMs`), not the separate real-time `delay()` call inside `armTimeout`.
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceTimeBy — see class doc on why it's needed here
@RunWith(RobolectricTestRunner::class)
class WifiDirectHandoffCoordinatorTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val relay = RelayEngine(context, GroupRepository(context))
    private lateinit var fake: FakeTransport

    private class FakeTransport : WifiDirectTransport {
        var initiatorCalls = 0
        var responderCalls = 0
        var abortCalls = 0

        override suspend fun beginAsInitiator(
            peerAddress: String,
            token: ByteArray,
            readyAtEpochMs: Long,
            chunks: List<EvidenceChunkEntity>,
        ) {
            initiatorCalls++
        }

        override suspend fun beginAsResponder(
            peerAddress: String,
            token: ByteArray,
            readyAtEpochMs: Long,
            onChunk: suspend (EvidenceChunkEntity) -> Unit,
        ) {
            responderCalls++
        }

        override fun abortCurrent() {
            abortCalls++
        }
    }

    @Before
    fun setUp() {
        fake = FakeTransport()
    }

    private fun TestScope.coordinator(capable: Boolean = true) =
        WifiDirectHandoffCoordinator(relay, fake, this, capabilityCheck = { capable })

    private fun randomKey() = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Test
    fun `does not propose a handoff when capability is off`() = runTest {
        val responded = mutableListOf<ByteArray>()
        coordinator(capable = false).maybeProposeHandoff("peer1", "evid-1", "group-1", listOf(1, 2, 3), randomKey()) {
            responded.add(it)
        }
        assertTrue(responded.isEmpty())
    }

    @Test
    fun `proposes a handoff with a frame whose tag verifies under the group key`() = runTest {
        val key = randomKey()
        val responded = mutableListOf<ByteArray>()
        coordinator().maybeProposeHandoff("peer1", "evid-1", "group-1", listOf(1, 2, 3), key) { responded.add(it) }
        assertEquals(1, responded.size)
        val decoded = MeshFrameCodec.decode(responded[0])
        check(decoded is MeshFrameCodec.Frame.WifiDirectHandoff)
        assertEquals("evid-1", decoded.evidenceId)
        assertEquals(3, decoded.deficitCount)
        val macInput = MeshFrameCodec.wifiDirectHandoffMacInput(
            decoded.evidenceId, decoded.groupId, decoded.deficitCount, decoded.senderNonce
        )
        assertTrue(CryptoUtils.constantTimeEquals(CryptoUtils.authTag(key, macInput), decoded.mac))
    }

    @Test
    fun `a second proposal is ignored while one is already in flight`() = runTest {
        val key = randomKey()
        val c = coordinator()
        val responded = mutableListOf<ByteArray>()
        c.maybeProposeHandoff("peer1", "evid-1", "group-1", listOf(1), key) { responded.add(it) }
        c.maybeProposeHandoff("peer2", "evid-2", "group-1", listOf(1), key) { responded.add(it) }
        assertEquals(1, responded.size) // only the first one produced a frame — single-flight
    }

    @Test
    fun `a proposal with a forged tag is rejected without accepting`() = runTest {
        val key = randomKey()
        val wrongKey = randomKey()
        val nonce = ByteArray(16) { it.toByte() }
        val forgedMacInput = MeshFrameCodec.wifiDirectHandoffMacInput("evid-1", "group-1", 5, nonce)
        val forgedMac = CryptoUtils.authTag(wrongKey, forgedMacInput)
        val frame = MeshFrameCodec.Frame.WifiDirectHandoff("evid-1", "group-1", 5, nonce, forgedMac)
        val responded = mutableListOf<ByteArray>()
        coordinator().onHandoffProposalReceived(frame, "peer1", key) { responded.add(it) }
        assertTrue(responded.isEmpty())
        assertEquals(0, fake.responderCalls)
    }

    @Test
    fun `a genuine proposal is accepted and begins the responder role`() = runTest {
        val key = randomKey()
        val nonce = ByteArray(16) { it.toByte() }
        val mac = CryptoUtils.authTag(key, MeshFrameCodec.wifiDirectHandoffMacInput("evid-1", "group-1", 5, nonce))
        val frame = MeshFrameCodec.Frame.WifiDirectHandoff("evid-1", "group-1", 5, nonce, mac)
        val responded = mutableListOf<ByteArray>()
        coordinator().onHandoffProposalReceived(frame, "peer1", key) { responded.add(it) }
        assertEquals(1, responded.size)
        val decoded = MeshFrameCodec.decode(responded[0])
        assertTrue(decoded is MeshFrameCodec.Frame.WifiDirectAccept)
        advanceTimeBy(1) // let the background serviceScope.launch{} that calls beginAsResponder run
        assertEquals(1, fake.responderCalls)
    }

    @Test
    fun `accept with a wrong tag aborts instead of beginning a transfer`() = runTest {
        val key = randomKey()
        val c = coordinator()
        val responded = mutableListOf<ByteArray>()
        c.maybeProposeHandoff("peer1", "evid-1", "group-1", listOf(1), key) { responded.add(it) }
        val badAccept = MeshFrameCodec.Frame.WifiDirectAccept(
            "evid-1", "group-1", ByteArray(16), ByteArray(16), 0L, ByteArray(16)
        )
        c.onHandoffAccepted(badAccept, "peer1", key)
        assertEquals(1, fake.abortCalls)
        assertEquals(0, fake.initiatorCalls)
    }

    @Test
    fun `timeout aborts an unresolved proposal`() = runTest {
        val key = randomKey()
        coordinator().maybeProposeHandoff("peer1", "evid-1", "group-1", listOf(1), key) { }
        advanceTimeBy(WifiDirectTuning.OVERALL_HANDOFF_TIMEOUT_MS + 1)
        assertEquals(1, fake.abortCalls)
    }

    @Test
    fun `resolving before the timeout does not also abort`() = runTest {
        val key = randomKey()
        val nonce = ByteArray(16) { it.toByte() }
        val mac = CryptoUtils.authTag(key, MeshFrameCodec.wifiDirectHandoffMacInput("evid-1", "group-1", 5, nonce))
        val frame = MeshFrameCodec.Frame.WifiDirectHandoff("evid-1", "group-1", 5, nonce, mac)
        coordinator().onHandoffProposalReceived(frame, "peer1", key) { }
        advanceTimeBy(WifiDirectTuning.OVERALL_HANDOFF_TIMEOUT_MS + 1)
        // The transfer already resolved (active was cleared once beginAsResponder's launch
        // completed) before the timeout fired, so armTimeout's reference-equality check must not
        // fire a second, spurious abort on top of a transfer that already finished normally.
        assertEquals(0, fake.abortCalls)
    }
}
