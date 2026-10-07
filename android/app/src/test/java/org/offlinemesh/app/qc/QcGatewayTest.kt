@file:Suppress("MaxLineLength","MagicNumber","TooManyFunctions","LongParameterList","LongMethod","CyclomaticComplexMethod","NestedBlockDepth","EmptyFunctionBlock","VariableNaming","WildcardImport","ComplexCondition","ReturnCount","LoopWithTooManyJumpStatements","SwallowedException","TooGenericExceptionCaught","EmptyElseBlock")

package org.offlinemesh.app.qc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.offlinemesh.app.ble.MeshFrameCodec
import org.offlinemesh.app.ble.UplinkBatch
import org.offlinemesh.app.ble.UplinkGateway
import java.util.Random

class QcGatewayTest {
    private var nowMs = 1_800_000_000_000L
    private val nowSec get() = nowMs / 1000

    private fun gw(cfg: UplinkGateway.Config = UplinkGateway.Config()) = UplinkGateway({ nowMs }, cfg)
    private fun tag(i: Int) = ByteArray(6) { (i shr (8 * (it % 4))).toByte() }.also { it[5] = 0x55 }
    private fun inner(seed: Int, n: Int = 100) = ByteArray(n).also { Random(seed.toLong()).nextBytes(it) }
    private fun up(tag: ByteArray, cls: Int, seed: Int, created: Long = nowSec, flags: Int = 0, n: Int = 100) =
        MeshFrameCodec.Frame.Uplink(tag, cls, flags, created, inner(seed, n))

    // ---- codec ----
    @Test fun codecFuzzNeverThrowsAndHonoursBounds() {
        val r = Random(1)
        repeat(20000) {
            val b = ByteArray(r.nextInt(80))
            r.nextBytes(b)
            if (b.size > 2 && r.nextBoolean()) { b[0] = MeshFrameCodec.FRAME_UPLINK; b[1] = MeshFrameCodec.VERSION.toByte() }
            val f = MeshFrameCodec.decode(b)
            if (f is MeshFrameCodec.Frame.Uplink) {
                assertTrue("tag ${f.relayTag.size}", f.relayTag.size in 1..MeshFrameCodec.MAX_UPLINK_TAG_BYTES)
                assertTrue(f.inner.size <= MeshFrameCodec.MAX_UPLINK_INNER_BYTES)
            }
            UplinkBatch.decode(b)
        }
    }

    @Test fun codecRoundTripExact() {
        val r = Random(2)
        repeat(500) {
            val t = ByteArray(1 + r.nextInt(32)).also(r::nextBytes)
            val i = ByteArray(r.nextInt(4097)).also(r::nextBytes)
            val c = r.nextInt(6); val fl = r.nextInt(256); val ts = r.nextLong()
            val d = MeshFrameCodec.decode(MeshFrameCodec.encodeUplink(t, c, fl, ts, i)) as MeshFrameCodec.Frame.Uplink
            assertTrue(d.relayTag.contentEquals(t)); assertEquals(c, d.cls); assertEquals(fl, d.flags)
            assertEquals(ts, d.createdAtSec); assertTrue(d.inner.contentEquals(i))
        }
    }

    @Test fun decodeRejectsOversizeTag() {
        val ok = MeshFrameCodec.encodeUplink(ByteArray(32) { 1 }, 2, 0, 5L, ByteArray(10))
        val big = ok.copyOfRange(0, 2) + byteArrayOf(40) + ByteArray(40) { 1 } + ok.copyOfRange(2 + 1 + 32, ok.size)
        assertNull("decode accepted a 40-byte tag", MeshFrameCodec.decode(big))
    }

    @Test fun decodeRejectsEmptyTag() {
        val ok = MeshFrameCodec.encodeUplink(ByteArray(1) { 1 }, 2, 0, 5L, ByteArray(10))
        val empty = ok.copyOfRange(0, 2) + byteArrayOf(0) + ok.copyOfRange(3, ok.size)
        assertNull("decode accepted an empty tag", MeshFrameCodec.decode(empty))
    }

    // ---- gateway admission ----
    @Test fun staleAndFutureBoundaries() {
        val g = gw()
        assertTrue(g.offer(up(tag(1), 0, 1, nowSec - 120)) is UplinkGateway.Decision.Accepted)
        assertEquals(UplinkGateway.Decision.Rejected(UplinkGateway.Reject.STALE), g.offer(up(tag(1), 0, 2, nowSec - 121)))
        assertTrue(g.offer(up(tag(2), 0, 3, nowSec + 120)) is UplinkGateway.Decision.Accepted)
        assertEquals(UplinkGateway.Decision.Rejected(UplinkGateway.Reject.FUTURE), g.offer(up(tag(2), 0, 4, nowSec + 121)))
        assertTrue(g.offer(up(tag(3), 2, 5, nowSec - 7 * 86400)) is UplinkGateway.Decision.Accepted)
        assertTrue(g.offer(up(tag(3), 2, 6, nowSec - 7 * 86400 - 1)) is UplinkGateway.Decision.Rejected)
        for (ts in listOf(Long.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE + 1, -1L, 0L)) {
            for (c in 0..5) assertTrue("ts=$ts c=$c", g.offer(up(tag(9), c, 7, ts)) is UplinkGateway.Decision.Rejected)
        }
    }

    @Test fun fromInternetFrameNeverUplinked() {
        val g = gw()
        assertEquals(UplinkGateway.Decision.Rejected(UplinkGateway.Reject.FROM_INTERNET), g.offer(up(tag(1), 2, 1, flags = 1)))
        assertEquals(UplinkGateway.Decision.Rejected(UplinkGateway.Reject.FROM_INTERNET), g.offer(up(tag(1), 2, 1, flags = 3)))
        // downlink output is re-marked and cannot come back
        val raw = MeshFrameCodec.encodeUplink(tag(1), 2, 0, nowSec, inner(1))
        val out = g.onRelayBatch(UplinkBatch.encode(listOf(raw)))
        assertEquals(1, out.size)
        val f = MeshFrameCodec.decode(out[0]) as MeshFrameCodec.Frame.Uplink
        assertTrue(f.flags and 1 == 1)
        assertTrue(g.offer(f) is UplinkGateway.Decision.Rejected)
        assertEquals(0, g.pendingCount())
    }

    @Test fun ownPublishedEchoDroppedAndMultiRelayInjectsOnce() {
        val g = gw()
        g.offer(up(tag(1), 2, 1))
        val b = g.drain().single()
        assertEquals(0, g.onRelayBatch(b.content()).size)
        val raw = MeshFrameCodec.encodeUplink(tag(7), 2, 0, nowSec, inner(77))
        val c = UplinkBatch.encode(listOf(raw))
        assertEquals(1, g.onRelayBatch(c).size)
        assertEquals(0, g.onRelayBatch(c).size)
        assertEquals(0, g.onRelayBatch(c).size)
    }

    @Test fun strictClassOrderWithDistinctTags() {
        val g = gw()
        for (c in 0..5) g.offer(up(tag(c), c, c))
        assertEquals(listOf(3, 2, 0, 1, 4, 5), g.drain().map { it.cls })
    }

    @Test fun strictOrderAlertBeatsPriorityTaggedLive() {
        val g = gw()
        g.noteBeaconSeen(tag(1))
        g.offer(up(tag(1), 0, 1)) // LIVE under a nearby-beacon tag
        g.offer(up(tag(2), 3, 2)) // ALERT under another tag
        val order = g.drain().map { it.cls }
        assertEquals("SOS must drain before LIVE (strict priority)", 3, order.first())
    }

    @Test fun budgetNeverExceededAndBulkSeparate() {
        val cfg = UplinkGateway.Config(hourlyBudgetBytes = 10_000, bulkHourlyBudgetBytes = 6_000, maxBatchBytes = 3_000)
        val g = gw(cfg)
        for (i in 0 until 60) g.offer(up(tag(i), 2, i, n = 900))
        for (i in 100 until 160) g.offer(up(tag(i), 5, i, n = 900))
        var normal = 0; var bulk = 0
        repeat(10) { for (b in g.drain()) if (b.cls == 5) bulk += b.bytes else normal += b.bytes }
        assertTrue("normal $normal", normal <= 10_000)
        assertTrue("bulk $bulk", bulk <= 6_000)
        assertTrue(normal > 8_000 && bulk > 4_000)
        nowMs += 3_600_000
        assertTrue(g.drain().isNotEmpty())
    }

    @Test fun requeueRefundsCorrectBudget() {
        val cfg = UplinkGateway.Config(hourlyBudgetBytes = 3_000, bulkHourlyBudgetBytes = 3_000, maxBatchBytes = 3_000)
        val g = gw(cfg)
        g.offer(up(tag(1), 2, 1, n = 1000)); g.offer(up(tag(2), 5, 2, n = 1000))
        val ds = g.drain()
        assertEquals(2, ds.size)
        val bulkB = ds.first { it.cls == 5 }
        g.requeue(bulkB)
        // bulk refunded: can send again; normal budget untouched
        g.offer(up(tag(3), 2, 3, n = 1000)); g.offer(up(tag(4), 2, 4, n = 1000))
        val again = g.drain()
        assertTrue(again.any { it.cls == 5 })
        assertEquals("normal budget must not be refunded by a bulk requeue", 1, again.count { it.cls == 2 })
    }

    @Test fun requeueAfterWindowResetDoesNotInflateNewWindowBudget() {
        val cfg = UplinkGateway.Config(hourlyBudgetBytes = 10_000, maxBatchBytes = 10_000)
        val g = gw(cfg)
        for (i in 0 until 4) g.offer(up(tag(i), 2, i, n = 1000))
        val first = g.drain()
        val firstBytes = first.sumOf { it.bytes }
        assertTrue(firstBytes in 4000..4100)
        nowMs += 3_600_001
        for (i in 10 until 30) g.offer(up(tag(i), 2, i, n = 1000))
        var w2 = g.drain().sumOf { it.bytes }
        first.forEach { g.requeue(it) } // late failure from previous window
        w2 += g.drain().sumOf { it.bytes }
        assertTrue("window-2 bytes $w2 exceed 10000 budget", w2 <= 10_000)
    }

    @Test fun includeBulkFalseExcludesBulk() {
        val g = gw()
        g.offer(up(tag(1), 4, 1)); g.offer(up(tag(2), 5, 2)); g.offer(up(tag(3), 2, 3))
        assertEquals(listOf(2), g.drain(includeBulk = false).map { it.cls })
        assertEquals(2, g.drain(includeBulk = true).size)
    }

    @Test fun queueKeepsNewestPerTagClass() {
        val g = gw(UplinkGateway.Config(framesPerTagPerMinute = 100000))
        for (i in 0 until 300) { nowMs += 1000; g.offer(up(tag(1), 2, i, created = nowSec)) }
        assertEquals(64, g.pendingCount())
        val b = g.drain(includeBulk = true)
        val times = b.flatMap { it.frames }.map { (MeshFrameCodec.decode(it) as MeshFrameCodec.Frame.Uplink).createdAtSec }
        assertEquals(64, times.size)
        assertEquals((nowSec - 63..nowSec).toList(), times.sorted())
    }

    @Test fun tagRateCapAndBulkExempt() {
        val g = gw()
        var rej = 0
        for (i in 0 until 50) if (g.offer(up(tag(1), 2, i)) is UplinkGateway.Decision.Rejected) rej++
        assertEquals(20, rej)
        for (i in 0 until 50) assertTrue(g.offer(up(tag(2), 5, i)) is UplinkGateway.Decision.Accepted)
    }

    @Test fun hostileDistinctTagsAreGloballyBounded() {
        val g = gw()
        for (i in 0 until 3000) g.offer(up(tag(i + 1000), 2, i, n = 4096))
        assertTrue("pending=${g.pendingCount()} frames x 4 KB held with no global cap", g.pendingCount() < 3000)
    }

    @Test fun dedupSurvivesFloodBeforeStaleness() {
        val g = gw()
        val raw = MeshFrameCodec.encodeUplink(tag(1), 2, 0, nowSec, inner(1))
        assertEquals(1, g.onRelayBatch(UplinkBatch.encode(listOf(raw))).size)
        for (i in 0 until 3000) { g.onRelayBatch(UplinkBatch.encode(listOf(MeshFrameCodec.encodeUplink(tag(2), 5, 0, nowSec, inner(i + 10))))) }
        assertEquals("replay of a valid, non-stale event after dedup eviction", 0, g.onRelayBatch(UplinkBatch.encode(listOf(raw))).size)
    }

    @Test fun randomizedGatewayInvariants() {
        val r = Random(5)
        val g = gw(UplinkGateway.Config(hourlyBudgetBytes = 20_000, bulkHourlyBudgetBytes = 20_000))
        var used = 0; var bulkUsed = 0
        var windowStart = nowMs
        repeat(3000) {
            nowMs += r.nextInt(5000)
            if (nowMs - windowStart >= 3_600_000) { windowStart = nowMs; used = 0; bulkUsed = 0 }
            when (r.nextInt(3)) {
                0, 1 -> g.offer(up(tag(r.nextInt(20)), r.nextInt(6), r.nextInt(100000), nowSec - r.nextInt(300), n = r.nextInt(1500)))
                else -> for (b in g.drain(r.nextInt(5) + 1, r.nextBoolean())) {
                    if (b.cls >= 4) bulkUsed += b.bytes else used += b.bytes
                    assertTrue(b.bytes <= 32 * 1024)
                    assertTrue(b.frames.all { MeshFrameCodec.decode(it) is MeshFrameCodec.Frame.Uplink })
                    if (r.nextInt(4) == 0) { g.requeue(b); if (b.cls >= 4) bulkUsed -= b.bytes else used -= b.bytes }
                }
            }
            assertTrue("used $used", used <= 20_000)
            assertTrue("bulk $bulkUsed", bulkUsed <= 20_000)
        }
    }
}
