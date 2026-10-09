@file:Suppress("MaxLineLength","MagicNumber","TooManyFunctions","LongParameterList","LongMethod","CyclomaticComplexMethod","NestedBlockDepth","EmptyFunctionBlock","VariableNaming","WildcardImport","ComplexCondition","ReturnCount","LoopWithTooManyJumpStatements","SwallowedException","TooGenericExceptionCaught","EmptyElseBlock","FunctionNaming","TopLevelPropertyNaming","UnusedPrivateMember")

package org.offlinemesh.app.breakerc

import org.junit.Assert.*
import org.junit.Test
import org.offlinemesh.app.ble.MeshFrameCodec
import org.offlinemesh.app.ble.UplinkBatch
import org.offlinemesh.app.ble.UplinkGateway
import java.util.Random
import java.util.concurrent.atomic.AtomicLong

class BreakerCGatewayTest {
    @Test(timeout = 90_000)
    fun threads_hammer_all_public_apis() {
        val clock = AtomicLong(T0)
        val gw = UplinkGateway(now = { clock.get() })
        val netText = AtomicLong(0); val netBulk = AtomicLong(0)
        val errs = runThreads(12, 3.0) { i, rnd ->
            val nowSec = clock.get() / 1000
            when (rnd.nextInt(12)) {
                0, 1, 2, 3 -> gw.offer(Fx.frame(rnd.nextInt(300), rnd.nextInt(6), nowSec, Fx.bytes(rnd, 1 + rnd.nextInt(300))))
                4, 5 -> for (b in gw.drain(1 + rnd.nextInt(5), rnd.nextBoolean())) {
                    val bulk = b.cls >= 4
                    (if (bulk) netBulk else netText).addAndGet(b.bytes.toLong())
                    if (rnd.nextInt(3) == 0) { gw.requeue(b); (if (bulk) netBulk else netText).addAndGet(-b.bytes.toLong()) }
                    assertTrue(b.content().size <= 32 * 1024)
                }
                6 -> gw.heldFrames(1 + rnd.nextInt(20), 4000)
                7 -> gw.noteBeaconSeen(Fx.tag(rnd.nextInt(300)))
                8 -> {
                    val fs = (0 until 1 + rnd.nextInt(5)).map { Fx.enc(Fx.frame(rnd.nextInt(300), rnd.nextInt(6), nowSec, Fx.bytes(rnd, 50))) }
                    gw.onRelayBatch(UplinkBatch.encode(fs))
                }
                9 -> { gw.pendingCount(); gw.hasPending(rnd.nextInt(6)) }
                10 -> if (i == 10) clock.addAndGet(1) else gw.offer(Fx.frame(rnd.nextInt(5), 3, nowSec, Fx.bytes(rnd, 20)))
                else -> clock.addAndGet(0)
            }
        }
        assertTrue("errors: $errs", errs.isEmpty())
        assertTrue("text budget exceeded: ${netText.get()}", netText.get() <= UplinkGateway.HOURLY_BUDGET_BYTES)
        assertTrue("bulk budget exceeded: ${netBulk.get()}", netBulk.get() <= UplinkGateway.BULK_HOURLY_BUDGET_BYTES)
        assertTrue(gw.pendingCount() <= 512 * 160)
    }

    @Test
    fun clock_back_tag_rate_window_never_resets() {
        val clock = AtomicLong(T0)
        val gw = UplinkGateway(now = { clock.get() })
        val r = Random(1)
        repeat(40) { gw.offer(Fx.frame(1, 2, clock.get() / 1000, Fx.bytes(r, 10))) }
        clock.set(T0 - DAY) // clock corrected backwards by a day
        clock.addAndGet(10 * 60_000) // 10 real minutes later
        val d = gw.offer(Fx.frame(1, 2, clock.get() / 1000, Fx.bytes(r, 10)))
        assertTrue("tag rate-limited forever after clock step back: $d", d is UplinkGateway.Decision.Accepted)
    }

    @Test
    fun clock_back_hourly_budget_never_resets() {
        val clock = AtomicLong(T0)
        val gw = UplinkGateway(now = { clock.get() }, config = UplinkGateway.Config(hourlyBudgetBytes = 2000))
        val r = Random(2)
        gw.offer(Fx.frame(1, 2, clock.get() / 1000, Fx.bytes(r, 1500)))
        assertEquals(1, gw.drain().size)
        clock.set(T0 - DAY)
        clock.addAndGet(2 * 3_600_000L) // two real hours later: budget must have refreshed
        gw.offer(Fx.frame(2, 2, clock.get() / 1000, Fx.bytes(r, 1500)))
        assertEquals("upload budget stuck after clock step back", 1, gw.drain().size)
    }

    @Test
    fun clock_back_downlink_window_never_resets() {
        val clock = AtomicLong(T0)
        val gw = UplinkGateway(now = { clock.get() }, config = UplinkGateway.Config(maxDownlinkFramesPerMinute = 3))
        val r = Random(3)
        fun batch() = UplinkBatch.encode((0 until 3).map { Fx.enc(Fx.frame(9, 2, clock.get() / 1000, Fx.bytes(r, 20))) })
        assertEquals(3, gw.onRelayBatch(batch()).size)
        clock.set(T0 - DAY); clock.addAndGet(5 * 60_000)
        assertEquals("downlink dead after clock step back", 3, gw.onRelayBatch(batch()).size)
    }

    @Test
    fun clock_extremes_never_throw_and_expire() {
        val clock = AtomicLong(T0)
        val gw = UplinkGateway(now = { clock.get() })
        val r = Random(4)
        for (c in longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE / 2, -1, 0, Long.MAX_VALUE / 2, Long.MAX_VALUE, T0)) {
            clock.set(c)
            for (cs in longArrayOf(Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE, c / 1000, c / 1000 + 100_000_000L)) {
                for (cls in 0..5) {
                    gw.offer(Fx.frame(1, cls, cs, Fx.bytes(r, 10)))
                    gw.onRelayBatch(UplinkBatch.encode(listOf(Fx.enc(Fx.frame(2, cls, cs, Fx.bytes(r, 10))))))
                }
            }
            gw.drain(); gw.heldFrames(10, 4000); gw.noteBeaconSeen(Fx.tag(1))
        }
        clock.set(T0)
        gw.offer(Fx.frame(1, 2, T0 / 1000, Fx.bytes(r, 10)))
        clock.set(T0 + 5 * 365 * DAY) // years forward: everything must expire
        gw.drain()
        assertEquals(0, gw.pendingCount())
        assertEquals(0, Fx.size(Fx.field(gw, "queues")))
    }

    @Test(timeout = 120_000)
    fun volume_100k_frames_3000_tags_bounded() {
        val clock = AtomicLong(T0)
        val gw = UplinkGateway(now = { clock.get() })
        val r = Random(5)
        val start = System.nanoTime()
        for (i in 0 until 100_000) {
            if (i % 50 == 0) clock.addAndGet(1000)
            gw.offer(Fx.frame(r.nextInt(3000), r.nextInt(6), clock.get() / 1000, Fx.bytes(r, 1 + r.nextInt(64))))
            if (i % 5000 == 0) gw.drain(4)
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("queues=${Fx.size(Fx.field(gw, "queues"))}", Fx.size(Fx.field(gw, "queues")) <= 513)
        assertTrue(Fx.size(Fx.field(gw, "dedup")) <= UplinkGateway.DEDUP_ENTRIES + 1)
        assertTrue("rate map ${Fx.size(Fx.field(gw, "rate"))}", Fx.size(Fx.field(gw, "rate")) <= 1024 * 4 + 2)
        assertTrue(Fx.size(Fx.field(gw, "seenTags")) <= 4097)
        assertTrue("100k offers took $ms ms (offer is superlinear in queue count?)", ms < 40_000)
    }

    @Test
    fun max_size_frames_and_batch_limits() {
        val clock = AtomicLong(T0)
        val gw = UplinkGateway(now = { clock.get() })
        val r = Random(6)
        for (i in 0 until 200) gw.offer(Fx.frame(1, 2, clock.get() / 1000 - i % 5, Fx.bytes(r, 4096)))
        val over = gw.offer(Fx.frame(1, 2, clock.get() / 1000, Fx.bytes(r, 4097)))
        assertTrue(over is UplinkGateway.Decision.Rejected)
        var total = 0
        repeat(20) { for (b in gw.drain()) { assertTrue(b.content().size <= 32 * 1024); total += b.bytes } }
        assertTrue("budget exceeded $total", total <= UplinkGateway.HOURLY_BUDGET_BYTES)
        val huge = UplinkBatch.decode(ByteArray(1_500_000) { 0xFF.toByte() }) // 1.5MB of junk must not blow up
        assertTrue(huge.size < 100)
        assertTrue(gw.onRelayBatch(ByteArray(2_000_000) { 1 }).isEmpty())
    }

    @Test
    fun duplicates_downlink_only_once_and_echo() {
        val clock = AtomicLong(T0)
        val gw = UplinkGateway(now = { clock.get() })
        val content = UplinkBatch.encode(listOf(Fx.enc(Fx.frame(3, 2, clock.get() / 1000, ByteArray(30) { 5 }))))
        var n = 0
        repeat(1000) { n += gw.onRelayBatch(content).size }
        assertEquals(1, n)
    }

    @Test
    fun bulk_starved_when_text_budget_exhausted() {
        val clock = AtomicLong(T0)
        val gw = UplinkGateway(now = { clock.get() }, config = UplinkGateway.Config(hourlyBudgetBytes = 5000))
        val r = Random(7)
        for (t in 0 until 7) gw.offer(Fx.frame(t, 2, clock.get() / 1000, Fx.bytes(r, 1500)))
        gw.drain(4) // 3 fit; 4 left that no longer fit the text budget
        gw.offer(Fx.frame(100, 5, clock.get() / 1000, Fx.bytes(r, 100)))
        val got = gw.drain(4)
        assertTrue("bulk has its own 1MB budget but is starved by text queues that cannot fit (maxBatches slots wasted on null batches)", got.any { it.cls == 5 })
    }

    @Test
    fun requeue_after_budget_window_rolled_does_not_overrefund() {
        val clock = AtomicLong(T0)
        val gw = UplinkGateway(now = { clock.get() }, config = UplinkGateway.Config(hourlyBudgetBytes = 3000))
        val r = Random(8)
        gw.offer(Fx.frame(1, 2, clock.get() / 1000, Fx.bytes(r, 1500)))
        val b = gw.drain().single()
        clock.addAndGet(3_600_000L + 1)
        gw.offer(Fx.frame(2, 2, clock.get() / 1000, Fx.bytes(r, 1500)))
        gw.drain() // resets window, charges 1500
        gw.requeue(b) // old-window batch: must not refund the new window
        gw.offer(Fx.frame(3, 2, clock.get() / 1000, Fx.bytes(r, 1500)))
        val more = gw.drain().sumOf { it.bytes } + 0
        // new window used 1.5k of 3k; requeued (1.5k) + new (1.5k) = 3k pending -> only <=1.5k more may go
        assertTrue("over-spent window: $more", more <= 1600)
    }

    @Test
    fun flap_requeue_cycles_conserve_frames() {
        val clock = AtomicLong(T0)
        val gw = UplinkGateway(now = { clock.get() })
        val r = Random(9)
        for (i in 0 until 20) gw.offer(Fx.frame(1, 2, clock.get() / 1000 - i, Fx.bytes(r, 100)))
        val before = gw.pendingCount()
        repeat(1000) { for (b in gw.drain()) gw.requeue(b) }
        assertEquals(before, gw.pendingCount())
    }
}
