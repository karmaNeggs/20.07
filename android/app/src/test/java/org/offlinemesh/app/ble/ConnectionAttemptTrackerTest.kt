package org.offlinemesh.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 1: the exact state machine behind a real live-tested bug ("far away, connection breaks,
 * doesn't come back"; "Bluetooth off/on breaks it"; "breaks after 4-5 messages") — a peer whose
 * connection attempt never received a single callback stayed marked "connecting" forever. See
 * `docs/DECISIONS.md`, decision 5, for the full story. A fake clock makes the 15-second-timeout /
 * 45-second-cooldown behavior deterministic without a real wait.
 */
class ConnectionAttemptTrackerTest {
    private var clock = 0L
    private var epoch = 0
    private fun tracker(maxConcurrent: Int = 3, cooldownMs: Long = 45_000L, syncedCooldownMs: Long = cooldownMs) =
        ConnectionAttemptTracker(maxConcurrent, cooldownMs, syncedCooldownMs, now = { clock }, currentEpoch = { epoch })

    @Test
    fun `a fresh address can attempt`() {
        assertTrue(tracker().canAttempt("AA:BB"))
    }

    @Test
    fun `an address already attempting cannot be attempted again`() {
        val t = tracker()
        t.attemptStarted("AA:BB")
        assertFalse(t.canAttempt("AA:BB"))
    }

    @Test
    fun `an attempt that never receives any callback is reported stuck`() {
        val t = tracker()
        t.attemptStarted("AA:BB")
        assertTrue(t.isStuck("AA:BB"))
    }

    @Test
    fun `receiving any callback — even a failure status — means the attempt is not stuck`() {
        val t = tracker()
        t.attemptStarted("AA:BB")
        t.callbackReceived("AA:BB") // caller marks this for ANY onConnectionStateChange, not just success
        assertFalse(t.isStuck("AA:BB"))
    }

    @Test
    fun `an address never attempted is not reported stuck`() {
        assertFalse(tracker().isStuck("AA:BB"))
    }

    @Test
    fun `connectionEnded clears the connecting state and starts the reconnect cooldown`() {
        val t = tracker(cooldownMs = 45_000L)
        t.attemptStarted("AA:BB")
        t.connectionEnded("AA:BB")
        assertFalse(t.canAttempt("AA:BB")) // still cooling down
    }

    @Test
    fun `the cooldown expires and the address becomes attemptable again`() {
        val t = tracker(cooldownMs = 45_000L)
        t.attemptStarted("AA:BB")
        t.connectionEnded("AA:BB")
        clock += 45_001
        assertTrue(t.canAttempt("AA:BB"))
    }

    @Test
    fun `a stuck attempt force-cleaned by the caller can eventually be retried`() {
        // The exact regression scenario end to end (docs/DECISIONS.md, decision 5): start an
        // attempt, never get a callback (peer went out of range / Bluetooth toggled mid-attempt),
        // the caller's own timer confirms
        // isStuck() after its wait and calls connectionEnded() to force cleanup — the address must
        // NOT stay permanently unattemptable, which is exactly what the original bug did.
        val t = tracker(cooldownMs = 45_000L)
        t.attemptStarted("AA:BB")
        assertTrue(t.isStuck("AA:BB"))
        t.connectionEnded("AA:BB")
        assertFalse(t.canAttempt("AA:BB"))
        clock += 45_001
        assertTrue(t.canAttempt("AA:BB"))
    }

    @Test
    fun `respects the max concurrent connection limit`() {
        val t = tracker(maxConcurrent = 2)
        t.attemptStarted("A")
        t.attemptStarted("B")
        assertFalse(t.canAttempt("C"))
    }

    @Test
    fun `a completed connection frees a concurrency slot`() {
        val t = tracker(maxConcurrent = 1)
        t.attemptStarted("A")
        assertFalse(t.canAttempt("B"))
        t.connectionEnded("A")
        clock += 100_000 // clear A's own cooldown too, isolate the concurrency-limit assertion
        assertTrue(t.canAttempt("B"))
    }

    @Test
    fun `different addresses are tracked independently`() {
        val t = tracker()
        t.attemptStarted("A")
        assertTrue(t.canAttempt("B"))
        assertFalse(t.isStuck("B"))
    }

    // ---- peer-selection: synced peers get a longer cooldown than failed/unsynced ones ----

    @Test
    fun `an unsynced disconnect uses the short cooldown`() {
        val t = tracker(cooldownMs = 45_000L, syncedCooldownMs = 180_000L)
        t.attemptStarted("AA:BB")
        t.connectionEnded("AA:BB", synced = false)
        clock += 45_001
        assertTrue(t.canAttempt("AA:BB")) // short cooldown already expired
    }

    @Test
    fun `a synced disconnect uses the longer cooldown, biasing slots toward unvisited peers`() {
        val t = tracker(cooldownMs = 45_000L, syncedCooldownMs = 180_000L)
        t.attemptStarted("AA:BB")
        t.connectionEnded("AA:BB", synced = true)
        clock += 45_001
        assertFalse(t.canAttempt("AA:BB")) // short cooldown alone would have expired, long one hasn't
        clock += 135_000
        assertTrue(t.canAttempt("AA:BB")) // long cooldown now expired
    }

    @Test
    fun `defaults to the reconnect cooldown for synced too when no synced cooldown is configured`() {
        val t = ConnectionAttemptTracker(maxConcurrent = 3, reconnectCooldownMs = 45_000L, now = { clock })
        t.attemptStarted("AA:BB")
        t.connectionEnded("AA:BB", synced = true)
        clock += 45_001
        assertTrue(t.canAttempt("AA:BB")) // no behavior change when syncedCooldownMs isn't specified
    }

    // ---- passerby relay: skip the synced cooldown for a peer once we're carrying something new
    // for them specifically, instead of waiting out a cooldown that predates that content ----

    @Test
    fun `still respects the synced cooldown when nothing new has arrived for this peer`() {
        val t = tracker(cooldownMs = 45_000L, syncedCooldownMs = 180_000L)
        t.attemptStarted("AA:BB")
        t.connectionEnded("AA:BB", synced = true) // epoch 0 recorded for AA:BB
        clock += 1_000
        assertFalse(t.canAttempt("AA:BB")) // still cooling down, catalog hasn't changed since
    }

    @Test
    fun `skips the synced cooldown once the catalog changes after syncing with this peer`() {
        val t = tracker(cooldownMs = 45_000L, syncedCooldownMs = 180_000L)
        t.attemptStarted("AA:BB")
        t.connectionEnded("AA:BB", synced = true) // epoch 0 recorded for AA:BB
        clock += 1_000
        epoch++ // picked up something new from a different peer in between
        assertTrue(t.canAttempt("AA:BB")) // worth trying again now, even mid-cooldown
    }

    @Test
    fun `a peer never fully synced still respects its cooldown regardless of epoch`() {
        val t = tracker(cooldownMs = 45_000L, syncedCooldownMs = 180_000L)
        t.attemptStarted("AA:BB")
        t.connectionEnded("AA:BB", synced = false) // failed/aborted — no syncedEpoch recorded
        epoch++
        assertFalse(t.canAttempt("AA:BB")) // no recorded sync to compare against — normal cooldown applies
    }

    @Test
    fun `an epoch bump for one peer doesn't bypass another peer's independent cooldown`() {
        val t = tracker(cooldownMs = 45_000L, syncedCooldownMs = 180_000L)
        t.attemptStarted("AA:BB")
        t.connectionEnded("AA:BB", synced = true) // epoch 0 recorded for AA:BB
        epoch++
        t.attemptStarted("CC:DD")
        t.connectionEnded("CC:DD", synced = true) // epoch 1 recorded for CC:DD — already current
        assertTrue(t.canAttempt("AA:BB")) // stale relative to AA:BB's own last sync
        assertFalse(t.canAttempt("CC:DD")) // not stale relative to its own — still cooling down
    }

    // ---- bounded cooldown map: BLE addresses rotate (RPA, ~15min), so a crowd session must not
    // accumulate one cooldown entry per address ever seen, forever ----

    @Test
    fun `evicts the least recently touched address once over the tracked cap`() {
        val t = ConnectionAttemptTracker(
            maxConcurrent = 10, reconnectCooldownMs = 45_000L, maxTrackedAddresses = 2, now = { clock }
        )
        t.attemptStarted("A"); t.connectionEnded("A")
        t.attemptStarted("B"); t.connectionEnded("B")
        t.attemptStarted("C"); t.connectionEnded("C") // A hasn't been touched since, should be evicted
        assertEquals(2, t.trackedAddressCount())
        assertTrue(t.canAttempt("A")) // its cooldown entry is gone, so nothing blocks a fresh attempt
    }

    @Test
    fun `checking an address's cooldown protects it from eviction`() {
        val t = ConnectionAttemptTracker(
            maxConcurrent = 10, reconnectCooldownMs = 45_000L, maxTrackedAddresses = 2, now = { clock }
        )
        t.attemptStarted("A"); t.connectionEnded("A")
        t.attemptStarted("B"); t.connectionEnded("B")
        t.canAttempt("A") // access refreshes recency for A
        t.attemptStarted("C"); t.connectionEnded("C") // B is now least-recently-touched, not A
        assertFalse(t.canAttempt("A")) // still cooling down — its entry survived
        assertTrue(t.canAttempt("B")) // evicted, so cooldown is gone
    }

    // ---------- cooldown-skip rate limit (see canAttempt's comment: the epoch signal now includes
    // live positions, which change constantly, so unlimited skipping = no cooldown at all) ----------

    @Test
    fun `a moved epoch skips the cooldown once, as before`() {
        val t = tracker()
        t.attemptStarted("A")
        t.connectionEnded("A", synced = true)
        assertFalse("still cooling down with nothing new", t.canAttempt("A"))
        epoch++
        assertTrue("something new for this peer — skip the cooldown", t.canAttempt("A"))
    }

    @Test
    fun `a constantly-moving epoch cannot skip the cooldown repeatedly within the rate limit`() {
        // The reconnect-storm case: positions keep bumping the global epoch every few seconds, so
        // without a floor between skips every scan result would re-attempt every peer forever.
        val t = tracker()
        t.attemptStarted("A")
        t.connectionEnded("A", synced = true)
        epoch++
        assertTrue(t.canAttempt("A")) // first skip allowed
        clock += 5_000
        epoch++
        assertFalse("inside the rate limit — must not skip again", t.canAttempt("A"))
    }

    @Test
    fun `a moved epoch can skip the cooldown again once the rate limit has elapsed`() {
        val t = tracker()
        t.attemptStarted("A")
        t.connectionEnded("A", synced = true)
        epoch++
        assertTrue(t.canAttempt("A"))
        clock += 10_001
        epoch++
        assertTrue("past the rate limit — a genuinely new change may skip again", t.canAttempt("A"))
    }
}
