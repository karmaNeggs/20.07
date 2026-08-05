package org.offlinemesh.app.sim

import org.offlinemesh.app.ble.ConnectionAttemptTracker
import org.offlinemesh.app.ble.HopTracker
import org.offlinemesh.app.ble.OpaqueFrameRelay

/** How a node's peer state is keyed — the entire content of P0b (PLAN-v2.md §5.2) expressed as one
 *  branch in [SimNode.currentPeerKey]. Running the identical scenario under both values is what
 *  makes P0b's sim gate ("peer-state entries track node count, not address-rotation rate")
 *  falsifiable rather than asserted. */
enum class PeerKeyMode { ROTATING_ADDRESS, STABLE_PUBKEY }

/** Per-node connection/capability knobs, bundled so [SimNode]'s constructor stays short — see each
 *  field's use at its call site ([org.offlinemesh.app.ble.ConnectionAttemptTracker] for the first
 *  two, §6.1 platform-quirk injection for the last two). */
data class SimNodeConfig(
    val maxConcurrentConnections: Int = 3,
    val reconnectCooldownMs: Long = 45_000L,
    val canAdvertise: Boolean = true,
    val malicious: Boolean = false,
)

/**
 * One simulated phone. Wraps the SAME extracted, Android-free decision classes production code
 * uses (see each class's own doc) so the simulator's "connection admitted?" / "is this route
 * stale?" answers are the real logic under test, not a re-implementation of it.
 */
class SimNode(
    val id: String,
    private val clock: SimClock,
    private val peerKeyMode: PeerKeyMode,
    config: SimNodeConfig = SimNodeConfig(),
) {
    val canAdvertise: Boolean = config.canAdvertise
    val malicious: Boolean = config.malicious

    val connectionAttemptTracker = ConnectionAttemptTracker(
        maxConcurrent = config.maxConcurrentConnections,
        reconnectCooldownMs = config.reconnectCooldownMs,
        now = clock::now,
    )
    val hopTracker = HopTracker(now = clock::now)
    val opaqueRelay = OpaqueFrameRelay(now = clock::now)

    /** Relayable catalogue keys this node currently holds ("sos:..." / "evid:..." / "nick:..." —
     *  the same key shapes [org.offlinemesh.app.ble.CatalogFilter] and `RelayResponder` already
     *  use). What P0a's "pushed=0" yield metric counts deficits against. */
    val catalogItems: MutableSet<String> = mutableSetOf()

    private var currentAddress: String = id
    private var lastRotationAt: Long = 0L

    /** The identity by which OTHER nodes currently key their [connectionAttemptTracker] entries
     *  for this node. Under [PeerKeyMode.ROTATING_ADDRESS] this changes every
     *  [PlatformQuirks.addressRotationIntervalMs]; under [PeerKeyMode.STABLE_PUBKEY] it is [id]
     *  forever, matching PLAN-v2.md §5.2's "key on the per-group Ed25519 pubkey instead". */
    fun currentPeerKey(quirks: PlatformQuirks): String {
        if (peerKeyMode == PeerKeyMode.STABLE_PUBKEY) return id
        if (clock.now() - lastRotationAt >= quirks.addressRotationIntervalMs) {
            currentAddress = "$id#${clock.now()}"
            lastRotationAt = clock.now()
        }
        return currentAddress
    }

    // Radio-churn tracking for I1 (Pass 13->14's "total, not graceful" instability class).
    private val radioTouchLog: MutableList<Long> = mutableListOf()
    private var radioOutageUntil: Long = 0L

    fun touchRadio(quirks: PlatformQuirks) {
        val now = clock.now()
        radioTouchLog += now
        radioTouchLog.removeAll { now - it > quirks.radioChurnWindowMs }
        if (radioTouchLog.size > quirks.radioChurnInstabilityThreshold) {
            radioOutageUntil = now + quirks.radioChurnOutageMs
        }
    }

    fun radioIsDown(): Boolean = clock.now() < radioOutageUntil

    fun radioOutageRemainingMs(): Long = (radioOutageUntil - clock.now()).coerceAtLeast(0L)
}
