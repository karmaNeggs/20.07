package org.offlinemesh.app.transport.wifidirect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.offlinemesh.app.ble.MeshFrameCodec
import org.offlinemesh.app.ble.RelayEngine
import org.offlinemesh.app.crypto.CryptoUtils
import org.offlinemesh.app.data.EvidenceChunkEntity
import java.security.SecureRandom

/**
 * The WiFi Direct handoff protocol's decision/verification logic — deliberately has no
 * `WifiP2pManager`/socket type anywhere in its API surface, the same role
 * [ConnectionAttemptTracker] plays for the BLE connection-attempt state machine: the real radio
 * mechanics live in [WifiDirectAccelerator] instead, so this class is fully unit-testable with a
 * fake accelerator and an injectable clock.
 *
 * Single-flight (v1): at most one handoff in progress at a time, app-wide — matches this feature's
 * "ephemeral, pairwise" framing rather than juggling multiple concurrent WiFi Direct groups on one
 * radio, which this codebase has never needed to reason about before.
 *
 * **Token derivation.** The handoff token doubles as the `WifiDirectAccept` frame's own MAC
 * (`CryptoUtils.authTag(groupKey, wifiDirectAcceptMacInput(...))`). Both sides can independently
 * compute this exact value — the sender has [ActiveTransfer.senderNonce] because it generated it,
 * the receiver has both nonces because it received `senderNonce` in the Handoff frame and generated
 * `receiverNonce` itself — so it's a value only two holders of the same group key who both
 * participated in this exact exchange can produce, with no separate key-derivation step needed.
 *
 * **Group-owner election.** Deterministic by *role*, not a compared value that needs its own
 * round-trip: the side that proposed the handoff (holds the chunks, [maybeProposeHandoff]) always
 * initiates as the higher-`groupOwnerIntent` candidate; the side that accepted
 * ([onHandoffProposalReceived]) always responds as the lower one. See [WifiDirectAccelerator]'s
 * `beginAsInitiator`/`beginAsResponder` split.
 *
 * **Only ever runs between two group-key holders.** A blind relay (no key) can compute a chunk
 * deficit but can never produce a verifiable MAC here — so WiFi Direct acceleration structurally
 * never triggers between blind relays; that pairing simply never sees a proposal accepted and stays
 * on BLE forever, exactly preserving the mesh's blind-relay property. See [RelayResponder]'s
 * `Frame.WifiDirectHandoff`/`Frame.WifiDirectAccept` cases, both gated on `repo.getGroupKey != null`
 * before this class is ever called.
 */
@Suppress(
    // wire-protocol fields passed as plain scalars, matching MeshFrameCodec's own established
    // encode-function shape rather than introducing a request/response DTO type just for this.
    "LongParameterList",
)
class WifiDirectHandoffCoordinator(
    private val relay: RelayEngine,
    private val accelerator: WifiDirectTransport,
    private val serviceScope: CoroutineScope,
    // Defaults to the real capability/opt-in gate; injectable — like [now] below — so a test can
    // exercise this class's decision/verification logic (single-flight gating, MAC handling, the
    // respond() frame content) without needing a real Context, SharedPreferences, or PackageManager
    // at all, the same reason [now] exists rather than calling System.currentTimeMillis() directly.
    private val capabilityCheck: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class ActiveTransfer(
        val evidenceId: String,
        val peerAddress: String,
        val senderNonce: ByteArray,
        val deficitIndexes: List<Int>,
    )

    @Volatile private var active: ActiveTransfer? = null

    fun capabilityAdvertisable(): Boolean = capabilityCheck()

    /** Called from [RelayResponder]'s `Frame.Manifest` handling once a BLE deficit has been
     *  identified and crosses [WifiDirectTuning.MIN_DEFICIT_BYTES_FOR_HANDOFF] — proposes
     *  accelerating that specific deficit over WiFi Direct. Fire-and-forget: never blocks or
     *  gates the BLE chunk push that triggered it (see class-level "races BLE" design). */
    suspend fun maybeProposeHandoff(
        peerAddress: String,
        evidenceId: String,
        groupId: String,
        deficit: List<Int>,
        groupKey: ByteArray,
        respond: suspend (ByteArray) -> Unit,
    ) {
        if (!capabilityAdvertisable() || active != null) return
        val senderNonce = randomNonce()
        val macInput = MeshFrameCodec.wifiDirectHandoffMacInput(evidenceId, groupId, deficit.size, senderNonce)
        val mac = CryptoUtils.authTag(groupKey, macInput)
        active = ActiveTransfer(evidenceId, peerAddress, senderNonce, deficit)
        respond(MeshFrameCodec.encodeWifiDirectHandoff(evidenceId, groupId, deficit.size, senderNonce, mac))
        armTimeout()
    }

    /** Called from [RelayResponder] on receiving a peer's [MeshFrameCodec.Frame.WifiDirectHandoff].
     *  Verifies the proposal's MAC, and — if this side isn't already mid-transfer — accepts and
     *  begins the responder role in the background. */
    suspend fun onHandoffProposalReceived(
        frame: MeshFrameCodec.Frame.WifiDirectHandoff,
        peerAddress: String,
        groupKey: ByteArray,
        respond: suspend (ByteArray) -> Unit,
    ) {
        if (!capabilityAdvertisable() || active != null) return
        val handoffMacInput = MeshFrameCodec.wifiDirectHandoffMacInput(
            frame.evidenceId, frame.groupId, frame.deficitCount, frame.senderNonce
        )
        val expectedMac = CryptoUtils.authTag(groupKey, handoffMacInput)
        // forged/wrong-key proposal — drop, no NACK
        if (!CryptoUtils.constantTimeEquals(expectedMac, frame.mac)) return
        val receiverNonce = randomNonce()
        val readyAt = now() + WifiDirectTuning.NEGOTIATION_LEAD_MS
        val acceptMacInput = MeshFrameCodec.wifiDirectAcceptMacInput(
            frame.evidenceId, frame.groupId, frame.senderNonce, receiverNonce, readyAt
        )
        val acceptMac = CryptoUtils.authTag(groupKey, acceptMacInput)
        active = ActiveTransfer(frame.evidenceId, peerAddress, frame.senderNonce, emptyList())
        val acceptFrame = MeshFrameCodec.encodeWifiDirectAccept(
            frame.evidenceId, frame.groupId, frame.senderNonce, receiverNonce, readyAt, acceptMac
        )
        respond(acceptFrame)
        armTimeout()
        serviceScope.launch {
            accelerator.beginAsResponder(peerAddress, token = acceptMac, readyAtEpochMs = readyAt) { chunk ->
                relay.ingestChunk(chunk)
            }
            active = null
        }
    }

    /** Called from [RelayResponder] on receiving a peer's [MeshFrameCodec.Frame.WifiDirectAccept] —
     *  the initiator side's confirmation that the responder wants the transfer. Verifies the same
     *  MAC the responder computed, then begins the initiator role in the background. */
    suspend fun onHandoffAccepted(
        frame: MeshFrameCodec.Frame.WifiDirectAccept,
        peerAddress: String,
        groupKey: ByteArray,
    ) {
        val transfer = active ?: return
        if (transfer.evidenceId != frame.evidenceId || transfer.peerAddress != peerAddress) return
        val acceptMacInput = MeshFrameCodec.wifiDirectAcceptMacInput(
            frame.evidenceId, frame.groupId, transfer.senderNonce, frame.receiverNonce, frame.readyAtEpochMs
        )
        val expected = CryptoUtils.authTag(groupKey, acceptMacInput)
        if (!CryptoUtils.constantTimeEquals(expected, frame.mac)) {
            abort()
            return
        }
        serviceScope.launch {
            val chunks = relay.chunksByIndexes(transfer.evidenceId, transfer.deficitIndexes)
            accelerator.beginAsInitiator(
                peerAddress, token = expected, readyAtEpochMs = frame.readyAtEpochMs, chunks = chunks
            )
            active = null
        }
    }

    /** One timeout covering the whole handoff, armed by whichever side sends its own half of the
     *  handshake first — matches [ConnectionAttemptTracker]'s "one timeout, not a per-stage state
     *  machine" simplicity. `active === startedTransfer` (reference equality) is what lets this
     *  distinguish "still the same attempt, never resolved" from "resolved and a new one started"
     *  without needing its own id field. */
    private fun armTimeout() {
        val startedTransfer = active
        serviceScope.launch {
            delay(WifiDirectTuning.OVERALL_HANDOFF_TIMEOUT_MS)
            if (active === startedTransfer) abort()
        }
    }

    /** Every WFD failure mode — permission, capability, discovery, connect, socket, token
     *  mismatch, timeout — converges on this one call. Only ever touches WFD state; never
     *  [RelayResponder]/[RelayEngine]/BLE state, because BLE was never paused for this item in
     *  the first place (see class doc on the "races BLE" design) — so aborting here has no
     *  correctness impact on the item's delivery, only on how fast it arrived. */
    private fun abort() {
        accelerator.abortCurrent()
        active = null
    }

    private fun randomNonce(): ByteArray = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }

    companion object {
        private const val NONCE_LEN = 16
    }
}
