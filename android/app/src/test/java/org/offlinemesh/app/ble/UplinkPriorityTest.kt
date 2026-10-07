@file:Suppress("MaxLineLength", "EmptyFunctionBlock") // compact test fixtures and no-op fake overrides

package org.offlinemesh.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UplinkPriorityTest {
    private var nowMs = 1_800_000_000_000L
    private val nowSec get() = nowMs / 1000
    private fun gw(cfg: UplinkGateway.Config = UplinkGateway.Config()) = UplinkGateway({ nowMs }, cfg)
    private fun up(cls: Int, tag: Int = 1, size: Int = 100, seed: Int = 0) =
        MeshFrameCodec.Frame.Uplink(byteArrayOf(tag.toByte(), 2, 3, 4, 5, 6), cls, 0, nowSec, ByteArray(size) { (it + seed).toByte() })

    @Test fun `drain order is alert, text, live, last-known, file header, file bulk`() {
        val g = gw()
        val order = listOf(
            MeshFrameCodec.UPLINK_CLASS_FILE_SYMBOLS, MeshFrameCodec.UPLINK_CLASS_FILE_META,
            MeshFrameCodec.UPLINK_CLASS_POSITION_LAST_KNOWN, MeshFrameCodec.UPLINK_CLASS_LIVE,
            MeshFrameCodec.UPLINK_CLASS_TEXT, MeshFrameCodec.UPLINK_CLASS_ALERT,
        )
        for (c in order) g.offer(up(c))
        val got = g.drain().map { it.cls }
        assertEquals(order.reversed(), got)
    }

    @Test fun `a file's bulk can exceed the per-tag rate cap but is limited by its own budget`() {
        val g = gw(UplinkGateway.Config(framesPerTagPerMinute = 5, bulkHourlyBudgetBytes = 3000, maxBatchBytes = 1000))
        var accepted = 0
        for (i in 0 until 40) {
            if (g.offer(up(MeshFrameCodec.UPLINK_CLASS_FILE_SYMBOLS, size = 400, seed = i)) is UplinkGateway.Decision.Accepted) accepted++
        }
        assertEquals(40, accepted)
        var total = 0
        repeat(10) { total += g.drain().sumOf { it.bytes } }
        assertTrue("sent $total", total in 1..3000)
        assertTrue(g.drain().isEmpty())
    }

    @Test fun `bulk never consumes the budget that messages need`() {
        val g = gw(UplinkGateway.Config(hourlyBudgetBytes = 1000, bulkHourlyBudgetBytes = 100_000, maxBatchBytes = 800))
        for (i in 0 until 30) g.offer(up(MeshFrameCodec.UPLINK_CLASS_FILE_SYMBOLS, size = 400, seed = i))
        g.drain()
        g.offer(up(MeshFrameCodec.UPLINK_CLASS_ALERT, tag = 2))
        assertEquals(MeshFrameCodec.UPLINK_CLASS_ALERT, g.drain().first().cls)
    }

    @Test fun `bulk is skipped entirely when not allowed and still sent later`() {
        val g = gw()
        g.offer(up(MeshFrameCodec.UPLINK_CLASS_FILE_SYMBOLS)); g.offer(up(MeshFrameCodec.UPLINK_CLASS_TEXT, tag = 2))
        assertEquals(listOf(MeshFrameCodec.UPLINK_CLASS_TEXT), g.drain(includeBulk = false).map { it.cls })
        assertEquals(listOf(MeshFrameCodec.UPLINK_CLASS_FILE_SYMBOLS), g.drain().map { it.cls })
    }

    @Test fun `file classes expire after a day and unknown classes are rejected`() {
        val g = gw()
        val old = MeshFrameCodec.Frame.Uplink(byteArrayOf(1, 2, 3, 4, 5, 6), MeshFrameCodec.UPLINK_CLASS_FILE_META, 0, nowSec - 25 * 3600, ByteArray(10))
        assertEquals(UplinkGateway.Decision.Rejected(UplinkGateway.Reject.STALE), g.offer(old))
        assertEquals(UplinkGateway.Decision.Rejected(UplinkGateway.Reject.BAD_CLASS), g.offer(up(9)))
    }

    @Test fun `a member heard over the internet is not bridged for 60 seconds`() {
        val t = PositionTracker { nowMs }
        t.offer("g", "m1", 1.0, 2.0, 5, nowSec, 0, viaPeer = RelayResponder.INTERNET_PEER)
        assertTrue(t.heardViaInternetWithin("g", "m1", 60))
        nowMs += 61_000
        assertTrue(!t.heardViaInternetWithin("g", "m1", 60))
        t.offer("g", "m2", 1.0, 2.0, 5, nowSec, 0, viaPeer = "ble-peer")
        assertTrue(!t.heardViaInternetWithin("g", "m2", 60))
    }
}
