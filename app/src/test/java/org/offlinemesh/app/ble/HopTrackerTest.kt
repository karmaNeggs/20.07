package org.offlinemesh.app.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tier 1: the hop-count state machine, with a fake clock so staleness (a real 90-second window in
 * production) is testable in milliseconds instead of minutes. This is exactly the kind of test that
 * would have caught the Pass 16 SOS "2 hops with only 2 phones" bug — that bug came from a second,
 * unstaled tracking channel leaking into the display; a synthetic sequence test like the ones below
 * pins down the exact expected numbers instead of relying on live 2-phone hand-tracing.
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
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0)
        assertEquals(1, t.myHop("group-1", "PRESENCE"))
    }

    @Test
    fun `a worse neighbor report never overwrites a better known hop`() {
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0) // -> 1
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 3) // -> would be 4, worse
        assertEquals(1, t.myHop("group-1", "PRESENCE"))
    }

    @Test
    fun `a better neighbor report does overwrite a worse known hop`() {
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 5) // -> 6
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0) // -> 1, better
        assertEquals(1, t.myHop("group-1", "PRESENCE"))
    }

    @Test
    fun `presence goes stale after 90 seconds of no update`() {
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0)
        assertEquals(1, t.myHop("group-1", "PRESENCE"))
        clock += 90_001
        assertEquals(MeshProtocol.UNKNOWN_HOP, t.myHop("group-1", "PRESENCE"))
    }

    @Test
    fun `presence is still fresh at exactly the staleness boundary`() {
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0)
        clock += 90_000
        assertEquals(1, t.myHop("group-1", "PRESENCE"))
    }

    @Test
    fun `a repeated report at the same value refreshes recency and prevents staleness`() {
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0)
        clock += 80_000
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0) // refresh, still 1 hop
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
        // Regression for a scenario traced by hand during the Pass 16 SOS-hop investigation: the
        // origin device must stay at 0 for its own SOS even if it later hears a relayed copy with a
        // larger computed hop value.
        val t = tracker()
        t.markSosOrigin("group-1", "sos-1")
        t.considerDirectHop("group-1", "sos-1", 2)
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
        t.considerDirectHop("group-1", "sos-1", hopsFromOrigin)
        assertEquals(1, t.myHop("group-1", "sos-1"))
    }

    @Test
    fun `bestActiveSosHop ignores presence entries and stale sos entries`() {
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0) // should never count as an SOS
        t.markSosOrigin("group-1", "sos-1")
        assertEquals(0, t.bestActiveSosHop("group-1"))
        clock += 90_001
        assertEquals(MeshProtocol.UNKNOWN_HOP, t.bestActiveSosHop("group-1")) // expired, not shown as current
    }

    @Test
    fun `bestActiveSosHop returns the minimum across multiple active sos items in a group`() {
        val t = tracker()
        t.markSosOrigin("group-1", "sos-1")
        t.considerDirectHop("group-1", "sos-2", 3)
        assertEquals(0, t.bestActiveSosHop("group-1"))
    }

    @Test
    fun `hop tracking for one group never leaks into another group's key`() {
        val t = tracker()
        t.considerNeighborReport("group-A", "PRESENCE", neighborHop = 0)
        assertEquals(MeshProtocol.UNKNOWN_HOP, t.myHop("group-B", "PRESENCE"))
    }

    @Test
    fun `considerDirectHop rejects out-of-range values without corrupting existing state`() {
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0) // -> 1
        t.considerDirectHop("group-1", "PRESENCE", -1)
        t.considerDirectHop("group-1", "PRESENCE", MeshProtocol.UNKNOWN_HOP)
        assertEquals(1, t.myHop("group-1", "PRESENCE"))
    }
}
