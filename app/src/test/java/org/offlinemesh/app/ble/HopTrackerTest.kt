package org.offlinemesh.app.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tier 1: the hop-count state machine, with a fake clock so staleness (a real 180-second window in
 * production) is testable in milliseconds instead of minutes. This is exactly the kind of test that
 * would have caught a real live-tested bug — a stale SOS hop-count showing "2 hops" with only 2
 * phones in the mesh — that bug came from a second, unstaled tracking channel leaking into the
 * display; a synthetic sequence test like the ones below pins down the exact expected numbers
 * instead of relying on live 2-phone hand-tracing.
 *
 * Also covers route invalidation: a value only ever got BETTER, forever, even after the route that
 * produced it was long gone, as long as *anything* kept refreshing recency — every update call did
 * that regardless of whether it improved the tracked value. Fixed by tracking which source "owns"
 * the current value (see [HopTracker]'s `updateHop`): a worse report from that SAME source now
 * correctly downgrades it (the route it was tracking really did get worse), while a worse report
 * from a DIFFERENT source still can't override an existing better one.
 */
class HopTrackerTest {
    private var clock = 0L
    private fun tracker() = HopTracker(now = { clock })

    @Test
    fun `unknown target reports UNKNOWN_HOP`() {
        val t = tracker()
        assertEquals(MeshProtocol.UNKNOWN_HOP, t.myHop("group-1", "PRESENCE"))
    }

    @Test
    fun `hearing a neighbor directly reports 1 hop`() {
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "peerA")
        assertEquals(1, t.myHop("group-1", "PRESENCE"))
    }

    @Test
    fun `a worse neighbor report from a DIFFERENT source never overwrites a better known hop`() {
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "peerA") // -> 1
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 3, sourceId = "peerB") // would be 4, worse
        assertEquals(1, t.myHop("group-1", "PRESENCE"))
    }

    @Test
    fun `a worse neighbor report from the SAME owning source does overwrite the value`() {
        // Route invalidation: peerA's own route genuinely got worse — it should be reflected, not frozen
        // at its previous best-ever report forever.
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "peerA") // -> 1
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 3, sourceId = "peerA") // -> 4, same source
        assertEquals(4, t.myHop("group-1", "PRESENCE"))
    }

    @Test
    fun `a better neighbor report does overwrite a worse known hop`() {
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 5, sourceId = "peerA") // -> 6
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "peerB") // -> 1, better, any source
        assertEquals(1, t.myHop("group-1", "PRESENCE"))
    }

    @Test
    fun `after a better source takes over, the original source can no longer downgrade it`() {
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 5, sourceId = "peerA") // -> 6, peerA owns it
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "peerB") // -> 1, peerB now owns it
        // peerA no longer owns the value — rejected
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 5, sourceId = "peerA")
        assertEquals(1, t.myHop("group-1", "PRESENCE"))
    }

    @Test
    fun `two considerNeighborReport calls from the same source in one event let a worse one win`() {
        // Documents the actual live-confirmed bug (decision 30, docs/DECISIONS.md): BeaconRadio's
        // Tier B presence handling used to call considerNeighborReport TWICE per received beacon —
        // once for direct hearing (candidate 1), once for the broadcaster's own propagated distance
        // (candidate 3, say) — with the SAME sourceId both times, because both numbers describe the
        // same physical neighbor. The FIRST call claims ownership for that source; the SECOND call's
        // "the owning source can revise its own value" rule (meant for a source reporting a genuine
        // change over TIME) fires immediately instead, since it can't tell "this source, later" apart
        // from "this call's own sibling call, a moment ago" — so a worse reading silently wins. This
        // test pins the bug down at the HopTracker level; BeaconRadio no longer calls it this way.
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "peerA") // direct: candidate=1
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 2, sourceId = "peerA") // propagated: candidate=3
        assertEquals(3, t.myHop("group-1", "PRESENCE")) // the bug: worse value won, same event
    }

    @Test
    fun `merging both candidates into one considerDirectHop call (the fix) keeps the better value`() {
        // What BeaconRadio.handleResult does now: compute both candidates itself (direct hearing,
        // and the broadcaster's propagated distance + 1), take the minimum, and report it via ONE
        // considerDirectHop call — no ownership pass-through possible, since there's only one report.
        val t = tracker()
        val direct = 1
        val propagated = 2 + 1 // broadcaster's own reported PRESENCE hop (2), +1
        t.considerDirectHop("group-1", "PRESENCE", minOf(direct, propagated), sourceId = "peerA")
        assertEquals(1, t.myHop("group-1", "PRESENCE")) // the fix: better value kept
    }

    @Test
    fun `presence goes stale after the base window of no update`() {
        // 180s, widened from 90s after live measurement showed 5-8% of position/presence refresh
        // gaps exceeding 90s and blanking the radar — see PositionTracker's maxAgeSeconds note.
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "peerA")
        assertEquals(1, t.myHop("group-1", "PRESENCE"))
        clock += 180_001
        assertEquals(MeshProtocol.UNKNOWN_HOP, t.myHop("group-1", "PRESENCE"))
    }

    @Test
    fun `presence is still fresh at exactly the staleness boundary`() {
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "peerA")
        clock += 180_000
        assertEquals(1, t.myHop("group-1", "PRESENCE"))
    }

    @Test
    fun `a repeated report at the same value refreshes recency and prevents staleness`() {
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "peerA")
        clock += 170_000
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "peerA") // refresh, still 1 hop
        clock += 170_000 // 340s total elapsed, but only 170s since the refresh
        assertEquals(1, t.myHop("group-1", "PRESENCE"))
    }

    @Test
    fun `the sos origin always reports 0 hops to itself`() {
        val t = tracker()
        t.markSosOrigin("group-1", "sos-1")
        assertEquals(0, t.myHop("group-1", "sos-1"))
    }

    @Test
    fun `an echoed worse hop for my own sos never overwrites the origin 0`() {
        // Regression for a scenario traced by hand during the original SOS-hop bug investigation: the
        // origin device must stay at 0 for its own SOS even if it later hears a relayed copy with a
        // larger computed hop value — from a peer, i.e. a different source than "self" (markSosOrigin).
        val t = tracker()
        t.markSosOrigin("group-1", "sos-1")
        t.considerDirectHop("group-1", "sos-1", 2, sourceId = "peerA")
        assertEquals(0, t.myHop("group-1", "sos-1"))
    }

    @Test
    fun `two hops with exactly two phones is impossible via considerDirectHop from ttl consumption`() {
        // Direct-neighbor receipt of an unmodified announcement should always compute to 1, per the
        // TTL-consumed formula RelayResponder uses (DEFAULT_TTL - receivedTtl + 1): a sender who
        // never decrements its own copy locally always announces at DEFAULT_TTL, so the very first
        // hop's computed distance is exactly 1, not 2 — pins down the exact case behind the user's
        // "I never had more than 1 hop, I was told it was 2" report.
        val t = tracker()
        val defaultTtl = RelayEngine.DEFAULT_TTL
        val hopsFromOrigin = defaultTtl - defaultTtl + 1
        t.considerDirectHop("group-1", "sos-1", hopsFromOrigin, sourceId = "peerA")
        assertEquals(1, t.myHop("group-1", "sos-1"))
    }

    @Test
    fun `bestActiveSosHop ignores presence entries and stale sos entries`() {
        val t = tracker()
        // should never count as an SOS
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "peerA")
        t.markSosOrigin("group-1", "sos-1")
        assertEquals(0, t.bestActiveSosHop("group-1"))
        clock += 180_001
        assertEquals(MeshProtocol.UNKNOWN_HOP, t.bestActiveSosHop("group-1")) // expired, not shown as current
    }

    @Test
    fun `bestActiveSosHop returns the minimum across multiple active sos items in a group`() {
        val t = tracker()
        t.markSosOrigin("group-1", "sos-1")
        t.considerDirectHop("group-1", "sos-2", 3, sourceId = "peerA")
        assertEquals(0, t.bestActiveSosHop("group-1"))
    }

    @Test
    fun `bestActiveSos names the nearest sos id, not just its hop distance`() {
        val t = tracker()
        t.considerDirectHop("group-1", "sos-far", 3, sourceId = "peerA")
        t.considerDirectHop("group-1", "sos-near", 1, sourceId = "peerB")
        assertEquals("sos-near" to 1, t.bestActiveSos("group-1"))
    }

    @Test
    fun `bestActiveSos is null when nothing is fresh, matching bestActiveSosHop's UNKNOWN_HOP`() {
        val t = tracker()
        assertEquals(null, t.bestActiveSos("group-1"))
        t.markSosOrigin("group-1", "sos-1")
        clock += 180_001
        assertEquals(null, t.bestActiveSos("group-1"))
    }

    @Test
    fun `hop tracking for one group never leaks into another group's key`() {
        val t = tracker()
        t.considerNeighborReport("group-A", "PRESENCE", neighborHop = 0, sourceId = "peerA")
        assertEquals(MeshProtocol.UNKNOWN_HOP, t.myHop("group-B", "PRESENCE"))
    }

    @Test
    fun `considerDirectHop rejects out-of-range values without corrupting existing state`() {
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "peerA") // -> 1
        t.considerDirectHop("group-1", "PRESENCE", -1, sourceId = "peerA")
        t.considerDirectHop("group-1", "PRESENCE", MeshProtocol.UNKNOWN_HOP, sourceId = "peerA")
        assertEquals(1, t.myHop("group-1", "PRESENCE"))
    }

    // ---------- hop-aware staleness (live-tested finding: a flat window had zero margin for a
    // relayed value's worst-case propagation delay — see HopTracker.effectiveStaleMs's doc) ----------

    @Test
    fun `effectiveStaleMs gives hop 0 and hop 1 exactly the base window, no extra slack`() {
        assertEquals(90_000L, HopTracker.effectiveStaleMs(90_000L, hop = 0))
        assertEquals(90_000L, HopTracker.effectiveStaleMs(90_000L, hop = 1))
    }

    @Test
    fun `effectiveStaleMs adds one reconnect-cooldown's worth of slack per hop beyond the first`() {
        assertEquals(135_000L, HopTracker.effectiveStaleMs(90_000L, hop = 2))
        assertEquals(180_000L, HopTracker.effectiveStaleMs(90_000L, hop = 3))
    }

    @Test
    fun `a direct (1-hop) presence reading goes stale at exactly the base window`() {
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "peerA") // -> 1
        clock += 180_001
        assertEquals(MeshProtocol.UNKNOWN_HOP, t.myHop("group-1", "PRESENCE"))
    }

    @Test
    fun `a relayed (2-hop) presence reading survives past the old flat 90s window`() {
        // The exact scenario this fix targets: a 2-hop reading's worst-case propagation delay
        // (two independent ~45s reconnect cycles) can legitimately take close to 90s to arrive at
        // all — it must not then immediately read as stale the moment it does.
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 1, sourceId = "peerA") // -> 2
        clock += 180_001 // would have expired a 1-hop reading, must not expire a 2-hop one
        assertEquals(2, t.myHop("group-1", "PRESENCE"))
    }

    @Test
    fun `a relayed (2-hop) presence reading still eventually goes stale`() {
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 1, sourceId = "peerA") // -> 2
        clock += 225_001 // 180s base + 45s (one extra hop's worth of slack) + 1ms
        assertEquals(MeshProtocol.UNKNOWN_HOP, t.myHop("group-1", "PRESENCE"))
    }

    @Test
    fun `bestActiveSosHop applies the same hop-aware staleness as myHop`() {
        val t = tracker()
        t.considerDirectHop("group-1", "sos-1", hopValue = 2, sourceId = "peerA")
        clock += 180_001 // would expire a hop-1 SOS reading, must not expire this hop-2 one
        assertEquals(2, t.bestActiveSosHop("group-1"))
    }

    // ---------- recency must not be refreshed by rejected reports ----------

    @Test
    fun `a worse report from a non-owning source does not keep a stale reading alive`() {
        // The live-confirmed freeze: every report used to refresh recency, including rejected ones.
        // Once a group recorded "1 hop", any later traffic — even a 3-hop relayed frame from a
        // stranger — kept that 1 fresh forever, so the window never fired and the reading never
        // degraded. This is why the group row sat at "1 hop(s) away" through build after build.
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "peerA") // -> 1
        // peerA is gone. Only worse reports keep arriving, from someone else.
        repeat(5) {
            clock += 40_000
            t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 3, sourceId = "peerB") // -> 4
        }
        // With the bug, peerA's "1" was kept permanently fresh by peerB's rejected reports and the
        // reading stayed 1 forever. Correctly, peerA's reading ages out and reality takes over.
        assertEquals(4, t.myHop("group-1", "PRESENCE"))
    }

    @Test
    fun `a report confirming the current value does refresh recency`() {
        // The legitimate steady state: the route is still real, someone still sees it at that
        // distance, so it must not age out.
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "peerA") // -> 1
        repeat(5) {
            clock += 40_000
            t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "peerA") // -> 1, confirms
        }
        assertEquals(1, t.myHop("group-1", "PRESENCE"))
    }

    @Test
    fun `ownership follows whoever confirms the value, so a rotated address cannot strand it`() {
        // lastSource is a BLE address and those rotate every ~10-15 min. Without transfer-on-confirm
        // the ownership needed to revise a value UPWARD is stranded on an address that no longer
        // exists, and the reading can never degrade again.
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "oldAddr") // -> 1
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "newAddr") // confirms, takes over
        // Same peer, new address, now genuinely further away — must be able to revise upward.
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 2, sourceId = "newAddr")
        assertEquals(3, t.myHop("group-1", "PRESENCE"))
    }
}
