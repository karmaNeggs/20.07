@file:Suppress("MaxLineLength","MagicNumber","TooManyFunctions","LongParameterList","LongMethod","CyclomaticComplexMethod","NestedBlockDepth","EmptyFunctionBlock","VariableNaming","WildcardImport","ComplexCondition","ReturnCount","LoopWithTooManyJumpStatements","SwallowedException","TooGenericExceptionCaught","EmptyElseBlock","FunctionNaming","TopLevelPropertyNaming","UnusedPrivateMember")

package org.offlinemesh.app.breakerc

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.offlinemesh.app.ble.MeshFrameCodec
import org.offlinemesh.app.ble.UplinkGateway
import org.offlinemesh.app.gateway.*
import java.util.Random
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class CountLink : UplinkLink {
    val offers = AtomicInteger(); val ticks = AtomicInteger(); val interest = AtomicInteger()
    @Volatile var closed = false
    @Volatile var ticksAfterClose = 0
    var held: List<Pair<String, ByteArray>> = emptyList()
    override fun offerLocal(frame: MeshFrameCodec.Frame.Uplink): UplinkGateway.Decision { offers.incrementAndGet(); return UplinkGateway.Decision.Accepted(false) }
    override fun setInterestTags(tags: Collection<ByteArray>) { interest.incrementAndGet() }
    override fun tick() { ticks.incrementAndGet(); if (closed) ticksAfterClose++ }
    override fun close() { closed = true }
    override fun hasConnectedRelay() = true
    override fun heldFrames(maxFrames: Int, maxFrameBytes: Int) = held
    override fun offerFromBle(frame: MeshFrameCodec.Frame.Uplink): UplinkGateway.Decision = UplinkGateway.Decision.Accepted(false)
}

internal class Src(val clock: AtomicLong, val groupsN: Int = 2) : UplinkSource {
    val groupCalls = AtomicInteger()
    @Volatile var gate: CompletableDeferred<Unit>? = null
    @Volatile var mailboxCount = 0
    @Volatile var mailboxFixed: Int = -1
    @Volatile var files: (Long) -> List<FileUplink> = { emptyList() }
    val gs = (0 until groupsN).map { UplinkGroup("g$it", ByteArray(32) { b -> (b + it).toByte() }) }
    override suspend fun groups(): List<UplinkGroup> { groupCalls.incrementAndGet(); gate?.let { gate = null; it.await() }; return gs }
    override suspend fun liveFrames(groupId: String) = listOf(ByteArray(40) { 1 }, ByteArray(40) { 2 })
    override suspend fun mailboxItems(groupId: String): List<MailboxItem> {
        val n = if (mailboxFixed >= 0) mailboxFixed else mailboxCount
        val from = if (mailboxFixed >= 0) 0 else maxOf(0, n - 50)
        return (from until n).map { MailboxItem("$groupId-m$it", ByteArray(30) { 3 }) }
    }
    override suspend fun lastKnownFrames(groupId: String) = listOf(ByteArray(40) { 4 })
    override suspend fun fileUplinks(groupId: String) = if (groupId == "g0") files(clock.get()) else emptyList()
}

class BreakerCControllerTest {
    private fun ctrl(src: Src, clock: AtomicLong, link: () -> UplinkLink, bulk: Boolean = true, cfg: InternetReachController.Config = InternetReachController.Config(random = kotlin.random.Random(5))) =
        InternetReachController(src, { link() }, { _, _ -> }, { clock.get() }, cfg, { bulk })

    @Test(timeout = 120_000)
    fun seven_day_run_bounded_state() {
        val clock = AtomicLong(T0); val src = Src(clock); val link = CountLink()
        src.files = { t -> val k = (t - T0) / 60_000; ((k - 2).coerceAtLeast(0)..k).map { i -> FileUplink("f$i", ByteArray(20), 3, { n -> List(n) { ByteArray(30) } }) } }
        val c = ctrl(src, clock, { link })
        c.start()
        var maxGroupCalls = 0
        val t0 = System.nanoTime()
        runBlocking {
            for (s in 0 until 7 * 86_400) {
                clock.addAndGet(1000)
                if (s % 5 == 0) src.mailboxCount++
                val before = src.groupCalls.get()
                c.step()
                maxGroupCalls = maxOf(maxGroupCalls, src.groupCalls.get() - before)
            }
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val perSec = link.offers.get() / (7.0 * 86_400)
        println("BREAKERC 7d run: ${ms}ms offers/s=$perSec maxGroupCalls=$maxGroupCalls uplinked=${Fx.size(Fx.field(c, "uplinkedIds"))} meta=${Fx.size(Fx.field(c, "metaSent"))} sym=${Fx.size(Fx.field(c, "symbolsSent"))}")
        assertTrue("work per step $maxGroupCalls", maxGroupCalls <= 8)
        assertTrue("offers/s $perSec", perSec < 2.0)
        assertTrue(Fx.size(Fx.field(c, "uplinkedIds")) <= 8192)
        assertTrue("metaSent grows without bound: ${Fx.size(Fx.field(c, "metaSent"))} after 7d of one new file per minute", Fx.size(Fx.field(c, "metaSent")) <= 1000)
        assertTrue("symbolsSent grows without bound: ${Fx.size(Fx.field(c, "symbolsSent"))}", Fx.size(Fx.field(c, "symbolsSent")) <= 1000)
    }

    @Test
    fun mailbox_larger_than_dedup_cache_is_republished_forever() {
        val clock = AtomicLong(T0); val src = Src(clock); src.mailboxFixed = 3000; val link = CountLink()
        val c = ctrl(src, clock, { link })
        c.start()
        runBlocking { repeat(600) { clock.addAndGet(1000); c.step() } } // 10 minutes
        val offers = link.offers.get()
        assertTrue("3000 stable mailbox items re-offered $offers times in 10 min (LRU of 2048 thrashes on a sequential scan)", offers < 3000 * 2 * 2 + 1000)
    }

    @Test
    fun clock_step_back_stalls_all_periodic_production() {
        val clock = AtomicLong(T0); val src = Src(clock); val link = CountLink(); src.mailboxFixed = 3
        val c = ctrl(src, clock, { link })
        c.start()
        runBlocking { repeat(120) { clock.addAndGet(1000); c.step() } }
        val a = link.offers.get(); val i0 = link.interest.get()
        clock.set(clock.get() - DAY)
        runBlocking { repeat(3600) { clock.addAndGet(1000); c.step() } }
        assertTrue("no live/last-known production for an hour after clock stepped back a day (offers ${link.offers.get() - a})", link.offers.get() > a + 10)
        assertTrue("interest tags never refreshed after clock step back", link.interest.get() > i0)
    }

    @Test
    fun clock_near_long_max_does_not_spin() {
        val clock = AtomicLong(Long.MAX_VALUE - 200_000); val src = Src(clock); val link = CountLink()
        val c = ctrl(src, clock, { link })
        c.start()
        runBlocking { repeat(100) { clock.addAndGet(1000); c.step() } }
        assertTrue("live production every second near Long.MAX (overflow in t + interval): ${link.offers.get()} offers in 100 s", link.offers.get() < 100)
    }

    @Test
    fun clock_extremes_no_throw() {
        val src0 = AtomicLong(T0)
        for (t in longArrayOf(Long.MIN_VALUE + 1, Long.MIN_VALUE / 2, -1, 0, 1, Long.MAX_VALUE / 2, T0 + 40 * 365 * DAY)) {
            val clock = AtomicLong(t); val src = Src(src0); val link = CountLink()
            val c = ctrl(src, clock, { link }); c.start()
            runBlocking { repeat(50) { clock.addAndGet(1000); c.step() } }
        }
    }

    @Test(timeout = 60_000)
    fun stop_during_inflight_step_leaks_a_socket() {
        val clock = AtomicLong(T0); val src = Src(clock); val conn = FakeConnector(); conn.ack = FakeConnector.Ack.NONE
        val gate = CompletableDeferred<Unit>(); src.gate = gate
        val c = ctrl(src, clock, { NostrGatewayLink(UplinkGateway({ clock.get() }), listOf("wss://a", "wss://b"), conn, GatewayKeyHolder({ clock.get() }), { clock.get() }, {}) })
        c.start()
        runBlocking {
            val job = launch(Dispatchers.Default) { c.step() }
            while (src.groupCalls.get() == 0) delay(5)
            c.stop() // e.g. connectivity lost while a step is suspended in source.groups()
            gate.complete(Unit)
            job.join()
        }
        assertFalse(c.running)
        assertEquals("sockets opened after stop() by the in-flight step ticking the closed link", 0, conn.live())
    }

    @Test(timeout = 60_000)
    fun concurrent_start_creates_two_links() {
        val clock = AtomicLong(T0); val src = Src(clock); val made = AtomicInteger(); val links = ArrayList<CountLink>()
        val c = ctrl(src, clock, { made.incrementAndGet(); Thread.sleep(30); CountLink().also { synchronized(links) { links.add(it) } } })
        val bar = CyclicBarrier(4)
        val ts = (0 until 4).map { Thread { bar.await(); c.start() }.also { it.start() } }
        ts.forEach { it.join() }
        c.stop()
        assertEquals("start() raced: ${made.get()} links created, ${links.count { !it.closed }} never closed", 0, links.count { !it.closed })
    }

    @Test(timeout = 120_000)
    fun start_stop_1000_times_consistent() {
        val clock = AtomicLong(T0); val src = Src(clock); src.mailboxFixed = 5
        val conn = FakeConnector(); val r = Random(3)
        val c = ctrl(src, clock, { NostrGatewayLink(UplinkGateway({ clock.get() }), listOf("wss://a", "wss://b"), conn, GatewayKeyHolder({ clock.get() }), { clock.get() }, {}) })
        runBlocking {
            repeat(1000) {
                c.start(); c.start()
                repeat(r.nextInt(4)) { clock.addAndGet(1000); c.step() }
                c.stop(); c.stop()
                assertFalse(c.running)
            }
        }
        assertEquals("sockets leaked across 1000 start/stop cycles", 0, conn.live())
        assertTrue(Fx.size(Fx.field(c, "uplinkedIds")) <= 8192)
    }

    @Test(timeout = 300_000)
    fun real_stack_one_hour_bounded_ack_ok_and_blackhole() {
        for (mode in listOf(FakeConnector.Ack.OK, FakeConnector.Ack.NONE, FakeConnector.Ack.BLOCK)) {
            val clock = AtomicLong(T0); val src = Src(clock, 3); val conn = FakeConnector(); conn.ack = mode
            val gw = UplinkGateway({ clock.get() })
            var link: NostrGatewayLink? = null
            val c = ctrl(src, clock, { NostrGatewayLink(gw, listOf("wss://a", "wss://b", "wss://c"), conn, GatewayKeyHolder({ clock.get() }), { clock.get() }, {}).also { link = it } })
            c.start()
            val t0 = System.nanoTime()
            var maxPending = 0; var maxInFlight = 0
            runBlocking {
                for (s in 0 until 3600) {
                    clock.addAndGet(1000); if (s % 5 == 0) src.mailboxCount++
                    c.step()
                    maxPending = maxOf(maxPending, gw.pendingCount())
                    maxInFlight = maxOf(maxInFlight, Fx.size(Fx.field(link!!, "inFlight")))
                }
            }
            val pool = Fx.field(link!!, "pool")!!
            println("BREAKERC real-stack $mode 1h: ${(System.nanoTime() - t0) / 1_000_000}ms pending=$maxPending inFlight=$maxInFlight settles=${Fx.size(Fx.field(pool, "settles"))} sent=${conn.sent.get()} conns=${conn.conns.size}")
            assertTrue("$mode pending $maxPending", maxPending < 5000)
            assertTrue("$mode inFlight $maxInFlight", maxInFlight < 200)
            assertTrue("$mode settles", Fx.size(Fx.field(pool, "settles")) < 200)
            assertTrue("$mode: connection churn ${conn.conns.size}", conn.conns.size < 400)
            c.stop(); assertEquals(0, conn.live())
        }
    }

    @Test
    fun interest_tag_volume_and_request_size() {
        val clock = AtomicLong(T0); val conn = FakeConnector(); conn.sentTexts = java.util.Collections.synchronizedList(ArrayList())
        val link = NostrGatewayLink(UplinkGateway({ clock.get() }), listOf("wss://a"), conn, GatewayKeyHolder({ clock.get() }), { clock.get() }, {})
        val r = Random(1)
        link.setInterestTags((0 until 5000).map { Fx.bytes(r, 16) } + (0 until 100).map { Fx.tag(1) })
        link.tick()
        val req = conn.sentTexts!!.filter { it.startsWith("[\"REQ\"") }
        assertTrue(req.isNotEmpty())
        assertTrue("REQ of ${req.maxOf { it.length }} chars", req.maxOf { it.length } < 200_000)
        link.setInterestTags(emptyList()); link.tick()
        link.close()
    }
}
