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
}
