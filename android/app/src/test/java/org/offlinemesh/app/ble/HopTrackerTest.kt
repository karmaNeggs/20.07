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
 * Also covers route invalidation (CR-33, `PLAN-v2.md` Part 10, closed 2026-08-10): [HopTracker] now
 * keeps one independent report per reporting source, each aging out on its own clock (see
 * [HopTracker]'s class doc for the two earlier designs and why both froze). `myHop` is the minimum
 * among whichever sources are still fresh, so a route that vanishes simply stops contributing once
 * its own source's report goes stale — no ownership handoff, no "confirms" special case.
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
    fun `a worse report from the only source does overwrite the value`() {
        // Route invalidation: peerA's own route genuinely got worse — it should be reflected, not frozen
        // at its previous best-ever report forever. peerA's report is simply overwritten each time.
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
    fun `a worse report from a peer whose earlier better report is still fresh does not win`() {
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 5, sourceId = "peerA") // -> 6
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "peerB") // -> 1
        // peerA reports worse again — overwrites peerA's OWN entry (6), but peerB's fresher, better
        // entry (1) is untouched and still the minimum.
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 5, sourceId = "peerA")
        assertEquals(1, t.myHop("group-1", "PRESENCE"))
    }

    @Test
    fun `two considerNeighborReport calls from the same source in one event let a worse one win`() {
        // Documents the actual live-confirmed bug (decision 30, docs/DECISIONS.md): BeaconRadio's
        // Tier B presence handling used to call considerNeighborReport TWICE per received beacon —
        // once for direct hearing (candidate 1), once for the broadcaster's own propagated distance
        // (candidate 3, say) — with the SAME sourceId both times, because both numbers describe the
        // same physical neighbor. A single source's report is always overwritten by its latest call,
        // so calling twice in one event still lets a worse reading silently win — this is why
        // BeaconRadio merges both candidates into ONE considerDirectHop call instead (see the next
        // test), not something HopTracker itself is meant to guard against.
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
    fun `effectiveStaleMs stops adding slack past MAX_SLACK_HOPS`() {
        // CR-12 (PLAN-v2.md Part 10, 2026-08-09) — hop is sourced from the same unauthenticated
        // cleartext envelope field every relay increments; without this cap a stale or replayed
        // reading at a large hop could sit displayed as "N hops away" for up to ~90 minutes.
        val atSixHops = HopTracker.effectiveStaleMs(90_000L, hop = 6)
        assertEquals(atSixHops, HopTracker.effectiveStaleMs(90_000L, hop = 7))
        assertEquals(atSixHops, HopTracker.effectiveStaleMs(90_000L, hop = 120))
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

    // ---------- each source ages out independently, on its own clock ----------

    @Test
    fun `a worse report from another source does not keep a vanished route's reading alive`() {
        // The live-confirmed freeze this design closes: peerA's "1" must age out on ITS OWN silence,
        // not get artificially kept fresh by unrelated traffic from someone else.
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "peerA") // -> 1
        // peerA is gone. Only worse reports keep arriving, from someone else.
        repeat(5) {
            clock += 40_000
            t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 3, sourceId = "peerB") // -> 4
        }
        // peerA's own entry is now 200s stale (> 180s window) and excluded; peerB's is fresh at 4.
        assertEquals(4, t.myHop("group-1", "PRESENCE"))
    }

    @Test
    fun `a source that keeps reporting the same value stays fresh`() {
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "peerA") // -> 1
        repeat(5) {
            clock += 40_000
            t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "peerA") // -> 1, refreshes
        }
        assertEquals(1, t.myHop("group-1", "PRESENCE"))
    }

    @Test
    fun `a rotated BLE address is a new, independent source, so a stale old one only lingers up to its own window`() {
        // BLE addresses rotate every ~10-15 min; considerNeighborReport/considerDirectHop's sourceId
        // is often such an address. There's no cross-address identity link here (that's
        // PeerIdentityResolver's job elsewhere, not this class's), so "oldAddr"'s reading persists
        // until IT ages out on its own — self-healing within one staleness window, not permanent.
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "oldAddr") // -> 1
        // Same physical peer, rotated to a new address, genuinely now farther away.
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 2, sourceId = "newAddr") // -> 3
        // oldAddr's stale "1" is still within its own window — still the minimum, by design.
        assertEquals(1, t.myHop("group-1", "PRESENCE"))
        clock += 180_001 // oldAddr's entry (hop=1) ages out; newAddr's (hop=3) does not yet
        assertEquals(3, t.myHop("group-1", "PRESENCE"))
    }

    // ---------- CR-6 (PLAN-v2.md Part 10, 2026-08-09): pruning — table/lastUpdated/lastSource/
    // snapshot used to grow forever (myHop/bestActiveSos only ever FILTERED by staleness, never
    // removed an entry) ----------

    @Test
    fun `pruneStale removes an entry past its staleness window from the snapshot`() {
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "peerA") // -> 1
        assertEquals(1, t.snapshot.value.size)
        clock += 180_001
        t.pruneStale()
        assertEquals(0, t.snapshot.value.size)
        assertEquals(MeshProtocol.UNKNOWN_HOP, t.myHop("group-1", "PRESENCE"))
    }

    @Test
    fun `pruneStale leaves a still-fresh entry alone`() {
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "peerA") // -> 1
        t.pruneStale()
        assertEquals(1, t.snapshot.value.size)
        assertEquals(1, t.myHop("group-1", "PRESENCE"))
    }

    @Test
    fun `pruneStale respects hop-aware staleness, not a flat window`() {
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 1, sourceId = "peerA") // -> 2
        clock += 180_001 // would prune a hop-1 reading, must not prune this hop-2 one
        t.pruneStale()
        assertEquals(2, t.myHop("group-1", "PRESENCE"))
    }

    @Test
    fun `clearForGroup removes every key for that group, including sos entries, and leaves others alone`() {
        val t = tracker()
        t.considerNeighborReport("group-1", "PRESENCE", neighborHop = 0, sourceId = "peerA")
        t.markSosOrigin("group-1", "sos-1")
        t.considerNeighborReport("group-2", "PRESENCE", neighborHop = 0, sourceId = "peerB")

        t.clearForGroup("group-1")

        assertEquals(MeshProtocol.UNKNOWN_HOP, t.myHop("group-1", "PRESENCE"))
        assertEquals(MeshProtocol.UNKNOWN_HOP, t.myHop("group-1", "sos-1"))
        assertEquals(1, t.myHop("group-2", "PRESENCE")) // untouched
        assertEquals(1, t.snapshot.value.size)
    }

    @Test
    fun `pruneOrphaned removes every group not in the active set`() {
        val t = tracker()
        t.considerNeighborReport("group-live", "PRESENCE", neighborHop = 0, sourceId = "peerA")
        t.considerNeighborReport("group-gone", "PRESENCE", neighborHop = 0, sourceId = "peerB")

        t.pruneOrphaned(setOf("group-live"))

        assertEquals(1, t.myHop("group-live", "PRESENCE"))
        assertEquals(MeshProtocol.UNKNOWN_HOP, t.myHop("group-gone", "PRESENCE"))
    }
}
