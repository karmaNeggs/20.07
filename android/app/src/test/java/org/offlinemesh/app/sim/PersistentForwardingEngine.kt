package org.offlinemesh.app.sim

import org.offlinemesh.app.ble.CatalogFilter
import org.offlinemesh.app.ble.DedupCache
import org.offlinemesh.app.ble.ForwardingPolicy
import org.offlinemesh.app.ble.LinkSelector
import org.offlinemesh.app.ble.RelayEngine

/** [PersistentForwardingEngine]'s knobs, bundled so the engine's own constructor stays short
 *  (matches [CatalogSyncTiming]'s precedent — deliberately NOT reusing that class here: this
 *  engine has no fixed session and no blind-relay budget, so most of its fields wouldn't apply). */
data class PersistentLinkTuning(
    val quirks: PlatformQuirks = PlatformQuirks(),
    val connectTimeoutMs: Long = 15_000L,
    val connectionSetupMs: Long = 3_000L,
    val connectionMaxMs: Long = 20_000L,
    val reevaluateIntervalMs: Long = 15_000L,
    val backfillIntervalMs: Long = 15_000L,
    val minDiversitySeparation: Double = 0.1,
    // Must match whatever maxConcurrentConnections the caller built its SimNodes with (default 3,
    // see SimNodeConfig) — the engine has no other way to learn a node's own configured cap.
    val maxConcurrentConnections: Int = 3,
    // False = v1's actual current behaviour, "first-heard" — whichever link connects first keeps
    // the slot forever (reevaluate never evicts). Exists so P3's own sim gate ("diversity beats
    // first-heard on reachability") can run the identical engine both ways and compare, rather
    // than comparing against a differently-shaped baseline engine.
    val diversityEnabled: Boolean = true,
)

/**
 * PLAN-v2.md P1 (§5.3, forwarding) + P3 (link management) combined, per the user's explicit
 * sequencing decision (2026-08-05, after `docs/DECISIONS.md` decision 16 found P1 alone doesn't
 * deliver its own hardware-gate latency claim): build P3 next, then measure the two TOGETHER
 * before committing to any production wire-format change.
 *
 * Replaces [ForwardingPlaneEngine]'s fixed `connectionSessionMs` session with a PERSISTENT link:
 * once established, a link stays open until [LinkSelector] decides a newly-heard candidate is
 * diverse enough to be worth evicting it for (§5.4/P3: "select for diversity, evict redundant
 * links", not the oldest). While a link is open, [ForwardingPolicy]'s flood-forward (same as
 * [ForwardingPlaneEngine], reusing the real [DedupCache]) can use it at any moment, and a periodic
 * catalogue-sync backfill (§5.3's "~15s exchange on already-open links") runs on it directly,
 * rather than once at session end.
 *
 * **Diversity is a synthetic 1D "position" per node, not real RSSI** — the sim has no physical
 * radio model, and this is an honestly-documented stand-in: real BLE RSSI roughly correlates with
 * physical distance, and physically distinct neighbours in a crowd tend to sit in distinct
 * signal-strength bands, which [positions] is meant to approximate for testing "does the SELECTION
 * RULE prefer spread over redundancy" — not to predict real RSSI values. Positions are supplied by
 * the caller (constant per scenario), not modelled as SimNode state (see [CatalogSyncEngine]'s own
 * doc on why engine-specific concerns stay out of the shared [SimNode] class).
 */
class PersistentForwardingEngine(
    private val clock: SimClock,
    private val events: SimEventQueue,
    private val network: SimNetwork,
    private val positions: Map<String, Double>,
    private val metrics: SimMetrics = SimMetrics(),
    private val tuning: PersistentLinkTuning = PersistentLinkTuning(),
) {
    private val quirks get() = tuning.quirks

    private val openLinks = mutableMapOf<String, MutableSet<String>>()
    private fun linksOf(nodeId: String) = openLinks.getOrPut(nodeId) { mutableSetOf() }

    // Which (lower-id, higher-id) pair currently owns which ConnectionAttemptTracker entry — see
    // class doc: only the lower-id side ever initiates (P0a's own calibration-gate rule), so only
    // it ever needs to release the slot on eviction. Centralised here rather than duplicated per
    // node, same reasoning as MeshGattClient's real activeTrackerKey fix (P0b, decision 15): the
    // key used at connect time must be reused at close time, never re-resolved.
    private val trackerKeyForPair = mutableMapOf<Pair<String, String>, String>()
    private fun pairKey(a: String, b: String) = if (a < b) a to b else b to a

    private val dedup = mutableMapOf<String, DedupCache>()
    private fun dedupFor(nodeId: String) = dedup.getOrPut(nodeId) { DedupCache(now = clock::now) }

    fun start() {
        for (node in network.nodes) {
            scheduleAttempt(node)
            scheduleReevaluate(node)
        }
    }

    fun injectPacket(origin: SimNode, item: String, ttl: Int = RelayEngine.DEFAULT_TTL) {
        deliverLocally(origin, item)
        dedupFor(origin.id).offerNew(item)
        floodForward(origin, item, ttl, excludePeerId = null)
    }

    private fun deliverLocally(node: SimNode, item: String) {
        val isNew = item !in node.catalogItems
        node.catalogItems += item
        if (isNew) {
            if (item !in metrics.injections) metrics.recordInjection(item, clock.now())
            metrics.recordDelivery(item, node.id, clock.now())
        }
    }

    private fun floodForward(from: SimNode, item: String, ttl: Int, excludePeerId: String?) {
        val candidateLinks = linksOf(from.id).filter { it != excludePeerId }
        if (candidateLinks.isEmpty()) return
        val openLinkCount = linksOf(from.id).size
        val forwardedTtl = ForwardingPolicy.forwardedTtl(ttl, openLinkCount)
        if (forwardedTtl <= 0) return
        val targets = ForwardingPolicy.linksToForwardOn(
            candidateLinks, messageIdSeed = item.hashCode().toLong(), openLinkCount = openLinkCount,
        )
        val jitterMs = ForwardingPolicy.pickJitterMs(openLinkCount, network.random)
        for (peerId in targets) {
            events.scheduleIn(jitterMs) {
                if (peerId !in linksOf(from.id)) return@scheduleIn
                val peer = network.nodes.firstOrNull { it.id == peerId } ?: return@scheduleIn
                metrics.recordRadioTouch(from.id, clock.now())
                if (!dedupFor(peer.id).offerNew(item)) return@scheduleIn
                deliverLocally(peer, item)
                floodForward(peer, item, forwardedTtl, excludePeerId = from.id)
            }
        }
    }

    // ---------- connection establishment (persistent) ----------

    private fun scheduleAttempt(node: SimNode, delayMs: Long = 0L) {
        events.scheduleIn(delayMs) { tryConnect(node) }
    }

    private fun tryConnect(node: SimNode) {
        if (node.radioIsDown()) {
            scheduleAttempt(node, node.radioOutageRemainingMs().coerceAtLeast(1_000L))
            return
        }
        val neighbors = network.neighborsOf(node, clock.now())
        val held = linksOf(node.id)
        val candidate = neighbors.filter { peer ->
            peer.id !in held &&
                node.id < peer.id &&
                peer.canAdvertise &&
                node.connectionAttemptTracker.canAttempt(peer.currentPeerKey(quirks))
        }.randomOrNull(network.random)

        if (candidate == null) {
            scheduleAttempt(node, RECHECK_IDLE_MS)
            return
        }

        val peerKey = candidate.currentPeerKey(quirks)
        node.connectionAttemptTracker.attemptStarted(peerKey)
        node.touchRadio(quirks)
        metrics.recordRadioTouch(node.id, clock.now())

        if (network.random.nextDouble() < quirks.callbackNeverArrivesProbability) {
            events.scheduleIn(tuning.connectTimeoutMs) {
                if (node.connectionAttemptTracker.isStuck(peerKey)) {
                    node.connectionAttemptTracker.connectionEnded(peerKey, synced = false)
                }
                scheduleAttempt(node)
            }
            return
        }

        node.connectionAttemptTracker.callbackReceived(peerKey)
        events.scheduleIn(tuning.connectionSetupMs) {
            if (network.random.nextDouble() < quirks.halfOpenProbability) {
                events.scheduleIn(tuning.connectionMaxMs) {
                    node.connectionAttemptTracker.connectionEnded(peerKey, synced = false)
                    scheduleAttempt(node)
                }
                return@scheduleIn
            }
            // Persistent: the link stays open from here until reevaluate() evicts it — no fixed
            // session-end disconnect, which is P3's whole point.
            trackerKeyForPair[pairKey(node.id, candidate.id)] = peerKey
            linksOf(node.id) += candidate.id
            linksOf(candidate.id) += node.id
            scheduleBackfill(node, candidate)
            scheduleAttempt(node) // keep looking — another slot may still be free
        }
    }

    private fun closePersistentLink(a: SimNode, b: SimNode) {
        linksOf(a.id) -= b.id
        linksOf(b.id) -= a.id
        val key = pairKey(a.id, b.id)
        val trackerKey = trackerKeyForPair.remove(key) ?: return
        val initiator = if (a.id < b.id) a else b
        initiator.connectionAttemptTracker.connectionEnded(trackerKey, synced = true)
    }

    // ---------- periodic backfill (§5.3: catalogue sync demoted to backfill on open links) ----------

    private fun scheduleBackfill(a: SimNode, b: SimNode) {
        events.scheduleIn(tuning.backfillIntervalMs) {
            if (b.id !in linksOf(a.id)) return@scheduleIn // link closed since this was scheduled
            val pushed = backfillSync(a, b)
            metrics.recordSync(a.id, b.id, pushed, clock.now())
            scheduleBackfill(a, b)
        }
    }

    private fun backfillSync(a: SimNode, b: SimNode): Int {
        if (a.malicious || b.malicious) return 0
        val filterA = CatalogFilter.build(a.catalogItems)
        val filterB = CatalogFilter.build(b.catalogItems)
        val aMissing = b.catalogItems.filterNot { filterA.mightContain(it) }
        val bMissing = a.catalogItems.filterNot { filterB.mightContain(it) }
        aMissing.forEach { deliverLocally(a, it) }
        bMissing.forEach { deliverLocally(b, it) }
        return aMissing.size + bMissing.size
    }

    // ---------- periodic diversity re-evaluation (§5.4/P3: LinkSelector) ----------

    private fun scheduleReevaluate(node: SimNode, delayMs: Long = tuning.reevaluateIntervalMs) {
        events.scheduleIn(delayMs) { reevaluate(node) }
    }

    private fun reevaluate(node: SimNode) {
        val held = linksOf(node.id)
        if (tuning.diversityEnabled && held.size >= tuning.maxConcurrentConnections) {
            val neighbors = network.neighborsOf(node, clock.now())
            val heldNodes = held.mapNotNull { id -> network.nodes.firstOrNull { it.id == id } }
            val heldPositions = heldNodes.map { positions.getValue(it.id) }
            val unheldCandidates = neighbors.filter { it.id !in held && it.canAdvertise }
            for (candidate in unheldCandidates) {
                val candidatePosition = positions.getValue(candidate.id)
                val evictIdx = LinkSelector.evictionCandidate(
                    heldPositions, candidatePosition, tuning.minDiversitySeparation,
                )
                if (evictIdx != null) {
                    closePersistentLink(node, heldNodes[evictIdx])
                    break // one swap per tick — avoids thrashing the same slot repeatedly
                }
            }
        }
        scheduleReevaluate(node)
    }

    private companion object {
        const val RECHECK_IDLE_MS = 2_000L
    }
}
