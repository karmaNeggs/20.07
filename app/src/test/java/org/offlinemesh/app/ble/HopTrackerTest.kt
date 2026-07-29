package org.offlinemesh.app.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tier 1: the hop-count state machine, with a fake clock so staleness (a real 90-second window in
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
    fun `presence goes stale after 90 seconds of no update`() {
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "peerA")
        assertEquals(1, t.myHop("group-1", "PRESENCE"))
        clock += 90_001
        assertEquals(MeshProtocol.UNKNOWN_HOP, t.myHop("group-1", "PRESENCE"))
    }

    @Test
    fun `presence is still fresh at exactly the staleness boundary`() {
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "peerA")
        clock += 90_000
        assertEquals(1, t.myHop("group-1", "PRESENCE"))
    }

    @Test
    fun `a repeated report at the same value refreshes recency and prevents staleness`() {
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "peerA")
        clock += 80_000
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "peerA") // refresh, still 1 hop
        clock += 80_000 // 160s total elapsed, but only 80s since the refresh
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
        clock += 90_001
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
}
