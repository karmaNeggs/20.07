package org.offlinemesh.app.sim

import org.offlinemesh.app.ble.CatalogFilter
import org.offlinemesh.app.ble.OpaqueFrameRelay

/** [CatalogSyncEngine]'s connection-lifecycle timing + the blind-relay budget, bundled so the
 *  engine's own constructor stays short. Defaults match PLAN-v2.md §1.2/§1.4's documented v1
 *  estimates (setup ≈3s, session 15-20s, `reconnectCooldownMs`-equivalent owned by
 *  [org.offlinemesh.app.ble.ConnectionAttemptTracker] itself, not here). */
data class CatalogSyncTiming(
    val connectionSetupMs: Long = 3_000L,
    val connectionSessionMs: Long = 15_000L,
    val connectTimeoutMs: Long = 15_000L,
    val connectionMaxMs: Long = 20_000L,
    /** Per-connection cap on opaque (blind-relay) frames exchanged, mirroring
     *  `RelayResponder`'s per-connection carry cap. Null = unbounded, matching v1's CURRENT
     *  actual behaviour (§5.5: "today blind relay is unbounded") — pass a value to model the
     *  backlog fix and compare. */
    val blindRelayBudgetPerConnection: Int? = null,
)

/**
 * The v1-baseline connection mechanism, modelled directly from the documented behaviour of
 * `RelayResponder`/`MeshGattClient`/[org.offlinemesh.app.ble.ConnectionAttemptTracker] rather than
 * re-derived from scratch: pick an eligible neighbour, connect, exchange catalogue deficits (and
 * optionally blind-relay opaque carry), disconnect, cooldown. This is deliberately the CURRENT,
 * expensive mechanism PLAN-v2.md Part 1 diagnoses — connect -> sync -> disconnect as the *primary*
 * delivery path — not P1's forwarding plane. A P1 engine reusing this same [SimNode]/[SimNetwork]/
 * [SimMetrics] plumbing is how that later phase's sim gate compares directly against this baseline.
 */
class CatalogSyncEngine(
    private val clock: SimClock,
    private val events: SimEventQueue,
    private val network: SimNetwork,
    private val quirks: PlatformQuirks = PlatformQuirks(),
    private val metrics: SimMetrics = SimMetrics(),
    private val timing: CatalogSyncTiming = CatalogSyncTiming(),
) {
    fun start() {
        for (node in network.nodes) scheduleAttempt(node)
    }

    private fun scheduleAttempt(node: SimNode, delayMs: Long = 0L) {
        events.scheduleIn(delayMs) { tryConnect(node) }
    }

    private fun tryConnect(node: SimNode) {
        if (node.radioIsDown()) {
            scheduleAttempt(node, node.radioOutageRemainingMs().coerceAtLeast(1_000L))
            return
        }
        val neighbors = network.neighborsOf(node, clock.now())
        // Only the lexicographically-lower id initiates toward a given peer — a real link is one
        // GATT connection per pair, not two independent ones; without this, every pair gets
        // attempted from BOTH sides at once and the measured pair-sync cadence comes out at roughly
        // half the real reconnectCooldownMs-governed rate (caught by the P0a D=3 calibration gate:
        // the ungated version measured ~31s where v1 measured ~50s, almost exactly 2x too fast).
        val candidate = neighbors.filter { peer ->
            node.id < peer.id &&
                peer.canAdvertise &&
                node.connectionAttemptTracker.canAttempt(peer.currentPeerKey(quirks))
        }.randomOrNull(network.random)

        if (candidate == null) {
            // Nobody eligible right now — wake again shortly rather than a long fixed poll, so the
            // measured cadence isn't biased by an arbitrary tick size.
            scheduleAttempt(node, RECHECK_IDLE_MS)
            return
        }

        val peerKey = candidate.currentPeerKey(quirks)
        node.connectionAttemptTracker.attemptStarted(peerKey)
        node.touchRadio(quirks)
        metrics.recordRadioTouch(node.id, clock.now())

        if (network.random.nextDouble() < quirks.callbackNeverArrivesProbability) {
            // Pass 16 / decision 5, mechanised: no callbackReceived ever fires. The caller's own
            // stuck-attempt watchdog — not ConnectionAttemptTracker itself — is what has to
            // recover this, exactly like MeshGattClient's real connectTimeoutMs.
            events.scheduleIn(timing.connectTimeoutMs) {
                if (node.connectionAttemptTracker.isStuck(peerKey)) {
                    node.connectionAttemptTracker.connectionEnded(peerKey, synced = false)
                    metrics.recordStuckRecovered(node.id, clock.now())
                }
                scheduleAttempt(node)
            }
            return
        }

        node.connectionAttemptTracker.callbackReceived(peerKey)
        events.scheduleIn(timing.connectionSetupMs) {
            if (network.random.nextDouble() < quirks.halfOpenProbability) {
                // decision A2, mechanised: CONNECTED then silence, no DISCONNECTED ever. isStuck()
                // is already false here (a callback DID arrive), so only a separate hard
                // connectionMaxMs deadline — owned by the caller, not the tracker — recovers the
                // slot, matching the real fix's two-watchdog shape.
                metrics.recordHalfOpen(node.id, clock.now())
                events.scheduleIn(timing.connectionMaxMs) {
                    node.connectionAttemptTracker.connectionEnded(peerKey, synced = false)
                    metrics.recordHalfOpenRecovered(node.id, clock.now())
                    scheduleAttempt(node)
                }
                return@scheduleIn
            }
            events.scheduleIn(timing.connectionSessionMs) {
                val pushed = exchangeContent(node, candidate)
                node.connectionAttemptTracker.connectionEnded(peerKey, synced = true)
                metrics.recordSync(node.id, candidate.id, pushed, clock.now())
                scheduleAttempt(node)
            }
        }
    }

    private fun exchangeContent(a: SimNode, b: SimNode): Int =
        exchangeOwnCatalog(a, b) + exchangeBlindRelay(a, b)

    /** The actual catalogue-deficit exchange via the REAL [CatalogFilter] — each side advertises a
     *  filter over its holdings, the other computes what it's missing and "pushes" it. A malicious
     *  peer (§6.1/S9) contributes nothing and receives nothing — a wasted connection slot, not
     *  corruption of the honest side's own catalogue. */
    private fun exchangeOwnCatalog(a: SimNode, b: SimNode): Int {
        if (a.malicious || b.malicious) return 0
        val filterA = CatalogFilter.build(a.catalogItems)
        val filterB = CatalogFilter.build(b.catalogItems)
        val aMissing = b.catalogItems.filterNot { filterA.mightContain(it) }
        val bMissing = a.catalogItems.filterNot { filterB.mightContain(it) }
        val now = clock.now()
        a.catalogItems += aMissing
        aMissing.forEach { metrics.recordDelivery(it, a.id, now) }
        b.catalogItems += bMissing
        bMissing.forEach { metrics.recordDelivery(it, b.id, now) }
        return aMissing.size + bMissing.size
    }

    /** Blind relay of opaque (undecryptable) frames each side is carrying for groups it holds no
     *  key for, via the REAL [OpaqueFrameRelay] — decision 10's mechanism. Split horizon
     *  ([OpaqueFrameRelay.framesToRelay]'s `excludePeer`) is applied exactly as production does. */
    private fun exchangeBlindRelay(a: SimNode, b: SimNode): Int {
        val limit = timing.blindRelayBudgetPerConnection ?: Int.MAX_VALUE
        var moved = 0
        for ((from, to) in listOf(a to b, b to a)) {
            for (frame in from.opaqueRelay.framesToRelay(excludePeer = to.id, limit = limit)) {
                val accepted = to.opaqueRelay.offer(
                    dedupKey = OpaqueFrameRelay.dedupKey(frame),
                    hop = 0,
                    maxHops = MAX_OPAQUE_HOPS,
                    viaPeer = from.id,
                ) { frame }
                if (accepted) moved++
            }
        }
        return moved
    }

    private companion object {
        const val RECHECK_IDLE_MS = 2_000L
        const val MAX_OPAQUE_HOPS = 8
    }
}
