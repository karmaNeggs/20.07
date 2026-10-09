@file:Suppress("MaxLineLength","MagicNumber","TooManyFunctions","LongParameterList","LongMethod","CyclomaticComplexMethod","NestedBlockDepth","EmptyFunctionBlock","VariableNaming","WildcardImport","ComplexCondition","ReturnCount","LoopWithTooManyJumpStatements","SwallowedException","TooGenericExceptionCaught","EmptyElseBlock","FunctionNaming","TopLevelPropertyNaming","UnusedPrivateMember")

package org.offlinemesh.app.breakerc

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.offlinemesh.app.ble.*
import org.offlinemesh.app.gateway.*
import java.util.Random
import java.util.concurrent.atomic.AtomicLong

class BreakerCSmallTest {
    private fun pos(p: PositionTracker, g: String, s: String, ts: Long, hop: Int = 0) = p.offer(g, s, 1.0, 2.0, 5, ts, hop)

    @Test(timeout = 90_000)
    fun position_tracker_threads_lost_update() {
        val clock = AtomicLong(T0)
        var bad = 0
        repeat(6) { round ->
            val p = PositionTracker { clock.get() }
            val max = AtomicLong(0)
            val errs = runThreads(8, 0.5) { i, rnd ->
                val ts = T0 / 1000 - rnd.nextInt(100)
                pos(p, "g", "s", ts, rnd.nextInt(3)); max.accumulateAndGet(ts) { a, b -> maxOf(a, b) }
                p.forGroup("g"); p.lastSeenForGroup("g")
                if (i == 0 && rnd.nextInt(1000) == 0) p.clearForGroup("zz")
            }
            assertTrue("errors $errs", errs.isEmpty())
            val rec = p.forGroup("g")["s"]!!
            if (rec.timestampSec != max.get()) bad++
        }
        assertEquals("tracker kept an older fix than the newest offered (check-then-put race) in $bad of 6 rounds", 0, bad)
    }

    @Test
    fun position_future_timestamp_never_expires_and_blocks_updates() {
        val clock = AtomicLong(T0); val p = PositionTracker { clock.get() }
        val nowSec = T0 / 1000
        pos(p, "g", "s", nowSec + 365L * 86400) // a member whose clock was a year ahead
        clock.addAndGet(10 * 3_600_000L)
        pos(p, "g", "s", clock.get() / 1000) // genuine fix 10 h later
        val rec = p.forGroup("g")["s"]
        assertTrue("future-stamped fix is immortal and rejects every genuine later fix (rec=${rec?.timestampSec}, now=${clock.get() / 1000})", rec != null && rec.timestampSec <= clock.get() / 1000 + 120)
    }

    @Test
    fun position_future_timestamps_unbounded_growth() {
        val clock = AtomicLong(T0); val p = PositionTracker { clock.get() }
        for (i in 0 until 20_000) pos(p, "g", "s$i", T0 / 1000 + 10_000_000L + i)
        clock.addAndGet(30 * DAY)
        pos(p, "g", "x", clock.get() / 1000)
        val n = Fx.size(Fx.field(p, "table"))
        assertTrue("table holds $n entries 30 days later (future-stamped entries never pruned)", n < 100)
    }

    @Test
    fun position_old_senders_leak_without_lastSeen_calls() {
        val clock = AtomicLong(T0); val p = PositionTracker { clock.get() }
        for (day in 0 until 3) { for (i in 0 until 5000) pos(p, "g", "d${day}s$i", clock.get() / 1000); clock.addAndGet(DAY) }
        pos(p, "g", "x", clock.get() / 1000)
        val n = Fx.size(Fx.field(p, "lastSeen"))
        assertTrue("lastSeen holds $n entries 2-3 days old (pruned only when lastSeenForGroup is called)", n < 6000)
    }

    @Test
    fun position_extreme_timestamps_no_throw() {
        val clock = AtomicLong(T0); val p = PositionTracker { clock.get() }
        for (t in longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE, -1, 0)) { pos(p, "g", "s$t", t); p.forGroup("g"); p.lastSeenForGroup("g") }
        clock.set(Long.MAX_VALUE); p.forGroup("g"); clock.set(Long.MIN_VALUE); p.forGroup("g")
        val m = p.forGroup("g")
        assertFalse("Long.MIN/MIN-ish timestamps shown as fresh: ${m.keys}", m.containsKey("s${Long.MIN_VALUE}") && m.containsKey("s${Long.MAX_VALUE}"))
    }

    @Test(timeout = 60_000)
    fun interest_registry_poisoned_and_clock_back() {
        val clock = AtomicLong(T0); val reg = InterestRegistry({ clock.get() })
        val r = Random(1)
        // hostile neighbour refreshes 512 junk tags (hop 0) every 5 minutes for a day
        repeat(288) {
            for (c in 0 until 32) reg.onHeard(MeshFrameCodec.Frame.UplinkInterest(0, (0 until 16).map { k -> Fx.tag(c * 16 + k) }))
            clock.addAndGet(300_000)
        }
        val legit = Fx.tag(99_999)
        reg.onHeard(MeshFrameCodec.Frame.UplinkInterest(1, listOf(legit)))
        assertTrue("legit tag can't enter a registry held full by refreshed junk (512 cap, no eviction)", reg.tags().any { it.contentEquals(legit) })
    }

    @Test
    fun interest_registry_threads_and_clock_back() {
        val clock = AtomicLong(T0); val reg = InterestRegistry({ clock.get() })
        val errs = runThreads(8, 1.5) { i, rnd ->
            when (i % 3) {
                0 -> reg.onHeard(MeshFrameCodec.Frame.UplinkInterest(rnd.nextInt(3), (0 until 1 + rnd.nextInt(16)).map { Fx.tag(rnd.nextInt(2000)) }))
                1 -> { reg.tags(); reg.regossip(3) }
                else -> clock.addAndGet(rnd.nextInt(200_000).toLong() - 100_000)
            }
        }
        assertTrue("errors $errs", errs.isEmpty())
        val c2 = AtomicLong(T0); val r2 = InterestRegistry({ c2.get() })
        r2.onHeard(MeshFrameCodec.Frame.UplinkInterest(0, listOf(Fx.tag(1))))
        c2.set(T0 - DAY); c2.addAndGet(3_600_000)
        assertTrue("interest entry survives 1 h of real time (ttl 10 min) after clock step back", r2.tags().isEmpty())
    }

    @Test(timeout = 60_000)
    fun key_holder_clock_back_and_week_boundary() {
        val c = AtomicLong(T0); val k = GatewayKeyHolder({ c.get() })
        val first = k.current().copyOf()
        c.addAndGet(7 * DAY - 1); assertArrayEquals(first, k.current())
        c.addAndGet(1); assertFalse(first.contentEquals(k.current()))
        val c2 = AtomicLong(T0); val k2 = GatewayKeyHolder({ c2.get() }); val f2 = k2.current().copyOf()
        c2.set(T0 - 30 * DAY); c2.addAndGet(8 * DAY) // 8 real days after the step back
        assertFalse("same signing key used for 8 real days (lifetime 7) after clock step back", f2.contentEquals(k2.current()))
        val threads = runThreads(8, 0.5) { _, _ -> c.addAndGet(DAY); k.current() }
        assertTrue("errors $threads", threads.isEmpty())
    }

    @Test(timeout = 60_000)
    fun ble_bridge_threads_bounded() {
        val clock = AtomicLong(T0); val src = Src(clock); val link = CountLink()
        val r0 = Random(2)
        link.held = (0 until 100).map { "k$it" to Fx.bytes(r0, 60) }
        val c = InternetReachController(src, { link }, { _, _ -> }, { clock.get() })
        c.start()
        val reg = InterestRegistry({ clock.get() })
        val br = UplinkBleBridge(c, reg) { true }
        val errs = runThreads(10, 2.0) { i, rnd ->
            when (i % 4) {
                0 -> runBlocking { br.framesToPush("peer${rnd.nextInt(60)}", 200) }
                1 -> br.onInterest(MeshFrameCodec.Frame.UplinkInterest(rnd.nextInt(3), (0 until 1 + rnd.nextInt(16)).map { Fx.tag(rnd.nextInt(5000)) }))
                2 -> runBlocking { br.onUplink(Fx.frame(rnd.nextInt(9), 2, clock.get() / 1000, Fx.bytes(rnd, 20))) }
                else -> { clock.addAndGet(100); if (i == 3) runBlocking { c.step() } }
            }
        }
        assertTrue("errors $errs", errs.isEmpty())
        val sent = Fx.field(br, "sentTo") as Map<*, *>
        assertTrue("sentTo ${sent.size}", sent.size <= 16)
        assertTrue(sent.values.all { (it as Collection<*>).size <= 512 })
    }

    @Test
    fun sos_preview_threads() {
        val b = BroadcastSosPreview()
        val errs = runThreads(8, 1.0) { i, rnd -> val g = "g${rnd.nextInt(50)}"; when (i % 3) { 0 -> b.offer(g, "s", "m", 1); 1 -> b.forGroupIfBest(g, "s"); else -> { b.clearForGroup(g); b.pruneOrphaned(setOf("g1", "g2")) } } }
        assertTrue("errors $errs", errs.isEmpty())
    }
}
