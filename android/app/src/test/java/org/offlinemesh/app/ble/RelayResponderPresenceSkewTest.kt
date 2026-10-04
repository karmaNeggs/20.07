package org.offlinemesh.app.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [RelayResponder.presenceWithinSkew] in isolation — deliberately NOT part of
 * [RelayResponderTest] (Robolectric-backed): [RelayResponder.handleIncoming]'s `Frame.Presence`
 * case calls [org.offlinemesh.app.data.GroupRepository.getGroupKey] once the skew check passes,
 * which touches Android Keystore-backed `EncryptedSharedPreferences` — unavailable under
 * Robolectric (see [RelayResponderTest]'s own class doc on this exact constraint). Testing the
 * replay-rejection property through the full class would therefore either need a real key (not
 * constructible here) or would have any Keystore failure silently swallowed by
 * [RelayResponder.handleIncoming]'s own broad catch, masking whether the skew check actually ran
 * first. This class is pure — no [android.content.Context], no key access — so it is both
 * fully testable here and, by construction, incapable of reaching key/MAC logic at all.
 */
class RelayResponderPresenceSkewTest {

    @Test
    fun `a fresh timestamp is within skew`() {
        val now = 1_700_000_000_000L
        assertTrue(RelayResponder.presenceWithinSkew(now, now))
    }

    @Test
    fun `a timestamp just inside the two-minute window is within skew`() {
        val now = 1_700_000_000_000L
        assertTrue(RelayResponder.presenceWithinSkew(now - 119_000L, now))
    }

    @Test
    fun `a timestamp just outside the two-minute window is rejected`() {
        val now = 1_700_000_000_000L
        assertFalse(RelayResponder.presenceWithinSkew(now - 121_000L, now))
    }

    @Test
    fun `a ten-minute-old replayed timestamp is rejected`() {
        val now = 1_700_000_000_000L
        assertFalse(RelayResponder.presenceWithinSkew(now - 10 * 60_000L, now))
    }

    @Test
    fun `a timestamp implausibly far in the future is also rejected`() {
        // The MAC covers the timestamp so an attacker can't forge one — but this guards against a
        // clock-skewed or malfunctioning legitimate sender producing nonsense just as well as it
        // guards against replay, since abs() treats both directions the same.
        val now = 1_700_000_000_000L
        assertFalse(RelayResponder.presenceWithinSkew(now + 10 * 60_000L, now))
    }

    @Test
    fun `a relayed heartbeat gets per-hop slack the flat window denied it`() {
        // Each relay hop costs at least one ~45s reconnect cycle, because presence only moves in
        // framesToPushOnConnect. A 2-hop heartbeat therefore needs ~90-135s to arrive — and the flat
        // 120s gate rejected it as a replay, silently defeating the whole blind-presence-relay path.
        val now = 1_700_000_000_000L
        val aged = now - 150_000L // 150s old: past the flat window, fine for a 2-hop path
        assertFalse(
            "hop 0 must still be held to the strict replay window",
            RelayResponder.presenceWithinSkew(aged, now)
        )
        assertTrue("a 2-hop heartbeat must be accepted", RelayResponder.presenceWithinSkew(aged, now, hop = 2))
    }

    @Test
    fun `slack is bounded, so a relayed heartbeat cannot be replayed indefinitely`() {
        val now = 1_700_000_000_000L
        val ancient = now - 400_000L
        assertFalse(RelayResponder.presenceWithinSkew(ancient, now, hop = 3))
    }

    @Test
    fun `slack stops growing past MAX_SLACK_HOPS, so a hop rewritten upward cannot widen the window further`() {
        // CR-12 (PLAN-v2.md Part 10, 2026-08-09) — hop lives in the cleartext envelope by design (a
        // blind relay must be able to increment it with no group key), so it carries no MAC. Before
        // this cap, capturing one valid presence frame and replaying it with hop rewritten toward
        // maxPositionRelayHops-1 (119) widened the acceptance window from ~2 minutes toward ~90 —
        // substantially defeating the very replay protection this function exists to provide. The
        // window at hop 6 and at hop 119 must now be identical.
        val now = 1_700_000_000_000L
        val atSixHopSlack = now - (120_000L + 6 * 45_000L) // base skew (120s) + 6 hops' worth of slack (45s each)
        assertFalse(
            "just past the hop-6 window must still be rejected regardless of a further-inflated hop",
            RelayResponder.presenceWithinSkew(atSixHopSlack - 1_000L, now, hop = 119)
        )
        assertTrue(
            "exactly at the hop-6 window must be accepted the same whether hop claims 6 or 119",
            RelayResponder.presenceWithinSkew(atSixHopSlack, now, hop = 119)
        )
    }
}
