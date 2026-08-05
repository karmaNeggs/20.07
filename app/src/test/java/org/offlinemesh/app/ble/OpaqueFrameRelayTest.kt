package org.offlinemesh.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 1: [OpaqueFrameRelay], the blind-relay custody that lets a phone carry positions and presence
 * it cannot read. This exists because of a live-confirmed outage: across three 3-phone sessions all
 * 627 positions received arrived at hop 0, since a relay without the group key dropped them outright,
 * while SOS reached hop 2 through the very same relay.
 */
class OpaqueFrameRelayTest {
    private var clock = 0L
    private fun relay(maxEntries: Int = 200) = OpaqueFrameRelay(maxEntries = maxEntries, now = { clock })

    private fun bytes(tag: Int) = ByteArray(16) { (it + tag).toByte() }

    @Test
    fun `a frame for a group we cannot decrypt is accepted for carrying`() {
        val r = relay()
        assertTrue(r.offer("k1", hop = 0, maxHops = 4) { bytes(1) })
        assertEquals(1, r.size())
    }

    @Test
    fun `the same frame arriving again is not carried twice`() {
        // Loop prevention: in a 3-phone triangle the identical frame comes back around, and
        // forwarding it again would circulate one position or heartbeat forever.
        val r = relay()
        assertTrue(r.offer("k1", hop = 0, maxHops = 4) { bytes(1) })
        assertFalse(r.offer("k1", hop = 1, maxHops = 4) { bytes(1) })
        assertEquals(1, r.size())
    }

    @Test
    fun `a frame already at the hop ceiling is not carried at all`() {
        val r = relay()
        // hop 3 would forward as 4, which is maxHops — no recipient would accept it.
        assertFalse(r.offer("k1", hop = 3, maxHops = 4) { bytes(1) })
        assertEquals(0, r.size())
    }

    @Test
    fun `the encoder runs once at custody time, not per forward`() {
        // Forwarding must not re-derive anything: the bytes are fixed the moment we take custody.
        val r = relay()
        var encodes = 0
        r.offer("k1", hop = 0, maxHops = 4) { encodes++; bytes(1) }
        r.framesToRelay(); r.framesToRelay(); r.framesToRelay()
        assertEquals(1, encodes)
    }

    @Test
    fun `a carried frame expires rather than being forwarded forever`() {
        val r = relay()
        r.offer("k1", hop = 0, maxHops = 4) { bytes(1) }
        clock += 180_001 // past the window its eventual recipient would apply anyway
        assertTrue(r.framesToRelay().isEmpty())
        assertEquals(0, r.size())
    }

    @Test
    fun `custody is bounded so a dense crowd cannot grow it without limit`() {
        val r = relay(maxEntries = 3)
        for (i in 1..10) r.offer("k$i", hop = 0, maxHops = 4) { bytes(i) }
        assertEquals(3, r.size())
    }

    @Test
    fun `dedupKey is stable for the same parts and differs for different ones`() {
        val a = OpaqueFrameRelay.dedupKey("g".toByteArray(), "s".toByteArray())
        val b = OpaqueFrameRelay.dedupKey("g".toByteArray(), "s".toByteArray())
        val c = OpaqueFrameRelay.dedupKey("g".toByteArray(), "t".toByteArray())
        assertEquals(a, b)
        assertFalse(a == c)
    }

    @Test
    fun `a carried frame is never handed back to the peer that supplied it`() {
        val r = relay()
        r.offer("k1", hop = 0, maxHops = 4, viaPeer = "AA:BB:CC") { bytes(1) }
        assertTrue(r.framesToRelay(excludePeer = "AA:BB:CC").isEmpty())
        assertEquals(1, r.framesToRelay(excludePeer = "DD:EE:FF").size)
        assertEquals(1, r.framesToRelay().size)
    }

    @Test
    fun `the per-connection limit is honoured`() {
        val r = relay()
        for (i in 1..50) r.offer("k$i", hop = 0, maxHops = 4) { bytes(i) }
        assertEquals(16, r.framesToRelay(limit = 16).size)
    }

    @Test
    fun `the window rotates so a store larger than the budget still drains completely`() {
        // Without rotation the same head-of-list frames go out every session and the tail NEVER
        // does — a silent, permanent delivery hole for whatever landed late.
        val r = relay()
        for (i in 1..10) r.offer("k$i", hop = 0, maxHops = 4) { byteArrayOf(i.toByte()) }
        val seen = mutableSetOf<Byte>()
        repeat(5) { r.framesToRelay(limit = 4).forEach { seen += it[0] } }
        assertEquals("every carried frame must eventually be forwarded", 10, seen.size)
    }
}
