package org.offlinemesh.app.sim

import org.offlinemesh.app.ble.CatalogFilter
import org.offlinemesh.app.ble.DedupCache
import org.offlinemesh.app.ble.ForwardingPolicy
import org.offlinemesh.app.ble.RelayEngine

/**
 * PLAN-v2.md P1's forwarding plane (§5.3), modelled in the simulator BEFORE any production wiring
 * — per this project's own established discipline (P0a's whole purpose, see `docs/DECISIONS.md`
 * decision 14), nothing here is trusted on real hardware until its sim gate passes first.
 *
 * Reuses [CatalogSyncEngine]'s exact connection-establishment mechanism conceptually (same
 * `ConnectionAttemptTracker`-driven connect/session/disconnect lifecycle, same single-lower-id-
 * initiates-per-pair rule that fixed the P0a calibration bug, same platform-quirk injection) — P1
 * does NOT replace that; P3 ("persistent links") does. What P1 ADDS: while a connection is open, a
 * newly-injected or newly-received packet is immediately flood-forwarded (after jitter, TTL-1,
 * fanout-subset — all via the REAL [ForwardingPolicy]) across every OTHER currently-open link a
 * node holds, using the REAL [DedupCache] to suppress repeats. Catalogue-sync (via the REAL
 * [CatalogFilter], same as [CatalogSyncEngine]) still runs once per connection, but now purely as
 * BACKFILL — "for what the flood missed, not the delivery mechanism" (§5.3's own words): it does
 * NOT itself trigger onward flooding, deliberately, so flood and backfill stay two simple,
 * independently-reasoned-about mechanisms rather than one entangled one.
 *
 * Deliberately a SEPARATE engine from [CatalogSyncEngine], not a modification of it — exactly as
 * that class's own doc anticipated, so P0a's already-gated v1-baseline numbers stay a fixed,
 * trusted comparison point while this engine is developed and gated against them.
 */
class ForwardingPlaneEngine(
    private val clock: SimClock,
    private val events: SimEventQueue,
    private val network: SimNetwork,
    private val quirks: PlatformQuirks = PlatformQuirks(),
    private val metrics: SimMetrics = SimMetrics(),
    private val timing: CatalogSyncTiming = CatalogSyncTiming(),
) {
    // Which peer ids each node currently holds an open (post-setup, mid-session) connection to —
    // the resource ForwardingPolicy subsets across. Deliberately local to this engine, not a
    // SimNode field: CatalogSyncEngine never needs it, so it shouldn't pollute the shared class.
    private val openLinks = mutableMapOf<String, MutableSet<String>>()
    private val dedup = mutableMapOf<String, DedupCache>()
    private fun dedupFor(nodeId: String) = dedup.getOrPut(nodeId) { DedupCache(now = clock::now) }
    private fun linksOf(nodeId: String) = openLinks.getOrPut(nodeId) { mutableSetOf() }

    fun start() {
        for (node in network.nodes) scheduleAttempt(node)
    }

    /** Injects a new packet at [origin] — the sim equivalent of a user tapping SOS, or new
     *  evidence/nickname content being created. Delivers locally and immediately attempts to
     *  flood it across whatever links [origin] currently has open. */
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

    /** [excludePeerId] is split horizon (same reasoning as [org.offlinemesh.app.ble.OpaqueFrameRelay]
     *  — never hand a packet straight back to whoever just gave it to us). */
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
                // Still open? A jittered forward can land after the link already closed.
                if (peerId !in linksOf(from.id)) return@scheduleIn
                val peer = network.nodes.firstOrNull { it.id == peerId } ?: return@scheduleIn
                metrics.recordRadioTouch(from.id, clock.now())
                if (!dedupFor(peer.id).offerNew(item)) return@scheduleIn // already seen, suppress
                deliverLocally(peer, item)
                floodForward(peer, item, forwardedTtl, excludePeerId = from.id)
            }
        }
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
        // Same single-initiator-per-pair rule P0a's own calibration gate forced (see
        // CatalogSyncEngine.tryConnect's identical comment) — a real link is one connection per
        // pair, not two.
        val candidate = neighbors.filter { peer ->
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
            events.scheduleIn(timing.connectTimeoutMs) {
                if (node.connectionAttemptTracker.isStuck(peerKey)) {
                    node.connectionAttemptTracker.connectionEnded(peerKey, synced = false)
                }
                scheduleAttempt(node)
            }
            return
        }

        node.connectionAttemptTracker.callbackReceived(peerKey)
        events.scheduleIn(timing.connectionSetupMs) {
            if (network.random.nextDouble() < quirks.halfOpenProbability) {
                events.scheduleIn(timing.connectionMaxMs) {
                    node.connectionAttemptTracker.connectionEnded(peerKey, synced = false)
                    scheduleAttempt(node)
                }
                return@scheduleIn
            }
            // Link is now UP — this is what P1 adds over CatalogSyncEngine: everything from here
            // to the matching closeLink() below is time a flood-forward can actually use this link,
            // not just the instant right before disconnect.
            openLink(node, candidate)
            events.scheduleIn(timing.connectionSessionMs) {
                val pushed = backfillSync(node, candidate)
                closeLink(node, candidate)
                node.connectionAttemptTracker.connectionEnded(peerKey, synced = true)
                metrics.recordSync(node.id, candidate.id, pushed, clock.now())
                scheduleAttempt(node)
            }
        }
    }

    private fun openLink(a: SimNode, b: SimNode) {
        linksOf(a.id) += b.id
        linksOf(b.id) += a.id
    }

    private fun closeLink(a: SimNode, b: SimNode) {
        linksOf(a.id) -= b.id
        linksOf(b.id) -= a.id
    }

    /** Backfill only — see class doc. Does NOT itself trigger [floodForward]; an item that
     *  arrives here was, by definition, something the flood already missed for this specific
     *  peer, and P1 doesn't need backfilled items to re-enter the flood for this sim gate. */
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

    private companion object {
        const val RECHECK_IDLE_MS = 2_000L
    }
}
