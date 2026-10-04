package org.offlinemesh.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tier 1: the crowd-scaling suppression timer behind BeaconRadio's supplementary Coded PHY
 *  channel — a fake clock makes the interval-doubling/backoff behavior deterministic. */
class TrickleTimerTest {
    private var clock = 0L
    private fun timer(min: Long = 1000L, max: Long = 8000L, k: Int = 2) =
        TrickleTimer(min, max, k, now = { clock })

    @Test
    fun `does not transmit before the first window has elapsed`() {
        val t = timer(min = 1000L)
        clock += 500
        assertFalse(t.shouldTransmit())
    }

    @Test
    fun `transmits at the first window boundary when isolated (no sightings)`() {
        val t = timer(min = 1000L)
        clock += 1000
        assertTrue(t.shouldTransmit())
    }

    @Test
    fun `suppresses transmission once redundancy constant sightings arrive within the window`() {
        val t = timer(min = 1000L, k = 2)
        t.onSighting("n1"); t.onSighting("n2") // 2 neighbors already covering this
        clock += 1000
        assertFalse(t.shouldTransmit())
    }

    @Test
    fun `still transmits with fewer sightings than the redundancy constant`() {
        val t = timer(min = 1000L, k = 2)
        t.onSighting("n1") // only 1, below k=2
        clock += 1000
        assertTrue(t.shouldTransmit())
    }

    @Test
    fun `sightings are deduped by source - repeated packets from ONE neighbor never count as two`() {
        // Decision 25 (docs/DECISIONS.md): a real continuously-broadcasting neighbor generates many
        // received packets from the SAME device during one window, not one. Before this was fixed,
        // onSighting() took no source id and counted every call, so a single present neighbor could
        // trip redundancyConstant=2 within moments and pin suppression indefinitely - the opposite
        // of what "2 distinct neighbors already cover this" is supposed to mean.
        val t = timer(min = 1000L, k = 2)
        repeat(50) { t.onSighting("n1") } // one neighbor's advertising set, heard 50 times
        clock += 1000
        assertTrue(
            "expected 50 receptions from a SINGLE source to still read as only 1 distinct sighting",
            t.shouldTransmit(),
        )
    }

    @Test
    fun `interval doubles up to the max after each window`() {
        val t = timer(min = 1000L, max = 4000L, k = 2)
        t.onSighting("n1"); t.onSighting("n2")
        clock += 1000
        assertFalse(t.shouldTransmit()) // window 1 (interval was 1000) -> now interval is 2000
        clock += 1999
        assertFalse(t.shouldTransmit()) // not yet 2000ms into window 2
        clock += 1
        t.onSighting("n1"); t.onSighting("n2")
        assertFalse(t.shouldTransmit()) // window 2 closes (interval was 2000) -> now interval is 4000
        clock += 3999
        assertFalse(t.shouldTransmit()) // not yet 4000ms into window 3 — proves the interval actually grew to 4000
    }

    @Test
    fun `interval stays capped at the max instead of continuing to double`() {
        val t = timer(min = 1000L, max = 2000L, k = 2)
        t.onSighting("n1"); t.onSighting("n2")
        clock += 1000
        t.shouldTransmit() // interval was 1000, doubles to 2000 (== max, so this step doesn't test capping yet)
        t.onSighting("n1"); t.onSighting("n2")
        clock += 2000
        t.shouldTransmit() // interval was 2000, would double to 4000 if uncapped — capped at max(2000) instead
        clock += 1999
        // If the cap didn't hold, interval would be 4000 and this would also be false — the next
        // assertion (boundary hit at exactly 2000ms, not 4000ms) is what actually proves the cap.
        assertFalse(t.shouldTransmit())
        clock += 1
        assertTrue(t.shouldTransmit())
    }

    @Test
    fun `sightings from a closed window do not carry over into the next one`() {
        val t = timer(min = 1000L, k = 2)
        t.onSighting("n1"); t.onSighting("n2")
        clock += 1000
        t.shouldTransmit() // consumes those 2 sightings, resets the set
        clock += 2000 // next window (interval doubled to 2000) elapses with zero new sightings
        assertTrue(t.shouldTransmit())
    }

    @Test
    fun `reset drops back to the minimum interval`() {
        val t = timer(min = 1000L, max = 8000L, k = 2)
        t.onSighting("n1"); t.onSighting("n2")
        clock += 1000
        t.shouldTransmit() // interval now 2000
        t.reset()
        clock += 1000 // only 1000ms elapsed since reset, which restored the 1000ms minimum
        assertTrue(t.shouldTransmit())
    }

    @Test
    fun `repeated calls within the same window do not roll it over early`() {
        val t = timer(min = 1000L, k = 2)
        clock += 500
        assertFalse(t.shouldTransmit())
        assertFalse(t.shouldTransmit())
        clock += 500
        assertTrue(t.shouldTransmit()) // exactly at the 1000ms boundary now
    }

    // CR-7 (PLAN-v2.md Part 10, 2026-08-09): onSighting is called from the BLE scan-callback binder
    // thread, shouldTransmit/reset from the advertise coroutine — a plain mutableSetOf backing
    // onSighting was unsynchronized against that. Not a deterministic reproduction (races never are)
    // but hammering both sides concurrently across many iterations reliably caught the unsynchronized
    // version's ConcurrentModificationException before the @Synchronized fix; this stays green as a
    // regression guard against reintroducing the race, using the real wall clock since the fake one
    // isn't safe to mutate across threads either.
    @Test
    fun `onSighting and shouldTransmit under concurrent access do not throw`() {
        // Thread's default uncaught-exception behavior does NOT propagate to join() — captured
        // explicitly, or an unsynchronized CME here would print to stderr and pass anyway.
        val caught = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val handler = Thread.UncaughtExceptionHandler { _, e -> caught.compareAndSet(null, e) }
        val t = TrickleTimer(minIntervalMs = 1L, maxIntervalMs = 5L)
        val sightingThread = Thread { repeat(20_000) { t.onSighting("peer-$it") } }
        val transmitThread = Thread { repeat(20_000) { t.shouldTransmit() } }
        sightingThread.uncaughtExceptionHandler = handler
        transmitThread.uncaughtExceptionHandler = handler
        sightingThread.start()
        transmitThread.start()
        sightingThread.join()
        transmitThread.join()
        assertEquals(null, caught.get())
    }
}
