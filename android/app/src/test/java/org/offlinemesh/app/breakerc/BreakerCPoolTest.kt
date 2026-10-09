@file:Suppress("MaxLineLength","MagicNumber","TooManyFunctions","LongParameterList","LongMethod","CyclomaticComplexMethod","NestedBlockDepth","EmptyFunctionBlock","VariableNaming","WildcardImport","ComplexCondition","ReturnCount","LoopWithTooManyJumpStatements","SwallowedException","TooGenericExceptionCaught","EmptyElseBlock","FunctionNaming","TopLevelPropertyNaming","UnusedPrivateMember")

package org.offlinemesh.app.breakerc

import org.junit.Assert.*
import org.junit.Test
import org.offlinemesh.app.gateway.*
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class PoolRig(urls: List<String>, val clock: AtomicLong, val conn: FakeConnector, cfg: RelayPool.Config = RelayPool.Config()) {
    val settled = ConcurrentHashMap<String, AtomicInteger>()
    val events = AtomicInteger()
    @Volatile var hook: ((String) -> Unit)? = null
    val pool = RelayPool(urls, conn, { clock.get() }, cfg,
        onEvent = { _, _ -> events.incrementAndGet() },
        onPublishSettled = { id, _, _ -> settled.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet(); hook?.invoke(id) })
}

class BreakerCPoolTest {
    @Test
    fun clock_back_ban_never_expires() {
        val clock = AtomicLong(T0); val c = FakeConnector()
        val rig = PoolRig(listOf("wss://a"), clock, c)
        rig.pool.tick()
        c.conns[0].listener.onMessage("[\"CLOSED\",\"2007\",\"blocked: nope\"]")
        assertEquals(RelayPool.State.BANNED, rig.pool.status()[0].state)
        clock.set(T0 - DAY + 700_000)
        rig.pool.tick()
        assertTrue("relay still banned a day after clock step back (ban is 10 min)", c.conns.size >= 2)
    }

    @Test
    fun clock_back_reconnect_backoff_stuck() {
        val clock = AtomicLong(T0); val c = FakeConnector()
        val rig = PoolRig(listOf("wss://a"), clock, c)
        repeat(9) { rig.pool.tick(); c.remoteClose(c.conns.last()); clock.addAndGet(301_000) }
        rig.pool.tick(); c.remoteClose(c.conns.last()) // nextConnectAt = now+300s
        val n = c.conns.size
        clock.set(clock.get() - 3_600_000 + 600_000)
        rig.pool.tick()
        assertTrue("no reconnect 10 min after clock step back (backoff cap is 5 min)", c.conns.size > n)
    }

    @Test
    fun clock_back_ack_timeout_never_fires() {
        val clock = AtomicLong(T0); val c = FakeConnector(); c.ack = FakeConnector.Ack.NONE
        val rig = PoolRig(listOf("wss://a"), clock, c)
        rig.pool.tick()
        rig.pool.publish(Fx.event(Fx.hex64(1)))
        rig.pool.tick()
        clock.set(T0 - 3_600_000)
        repeat(40) { clock.addAndGet(30_000); rig.pool.tick() }
        assertEquals("publish never settles after clock step back (ack timeout negative)", 1, rig.settled[Fx.hex64(1)]?.get() ?: 0)
    }

    @Test(timeout = 120_000)
    fun pool_threads_hammer_exactly_once_settle() {
        val clock = AtomicLong(T0); val c = FakeConnector()
        val urls = (0 until 6).map { "wss://r$it" }
        val rig = PoolRig(urls, clock, c)
        val ids = AtomicInteger(); val pub = ConcurrentHashMap<String, Boolean>()
        val acks = FakeConnector.Ack.values()
        val errs = runThreads(8, 3.0) { i, rnd ->
            when (i % 4) {
                0 -> { val id = Fx.hex64(ids.incrementAndGet().toLong()); pub[id] = true; rig.pool.publish(Fx.event(id, "%02x".format(rnd.nextInt(40)))) }
                1 -> { clock.addAndGet(200); rig.pool.tick() }
                2 -> {
                    val cs = c.conns; if (cs.isNotEmpty()) {
                        val cn = cs[rnd.nextInt(cs.size)]
                        when (rnd.nextInt(5)) {
                            0 -> cn.listener.onOpen()
                            1 -> cn.listener.onMessage("[\"OK\",\"${Fx.hex64(1L + rnd.nextInt(ids.get() + 1))}\",${rnd.nextBoolean()},\"duplicate: x\"]")
                            2 -> cn.listener.onMessage("[\"CLOSED\",\"2007\",\"${if (rnd.nextInt(8) == 0) "blocked: x" else "meh"}\"]")
                            3 -> cn.listener.onMessage("garbage")
                            else -> if (rnd.nextInt(4) == 0) c.remoteClose(cn)
                        }
                    }
                }
                else -> {
                    rig.pool.setSubscription(if (rnd.nextBoolean()) emptyList() else listOf(NostrFilter(listOf(7007), mapOf("t" to listOf("ab")))))
                    rig.pool.status(); rig.pool.congested(); rig.pool.hasConnectedRelay()
                    if (rnd.nextInt(50) == 0) c.ack = acks[rnd.nextInt(acks.size)]
                }
            }
        }
        assertTrue("errors: $errs", errs.isEmpty())
        c.ack = FakeConnector.Ack.NONE
        rig.pool.close()
        val doubles = rig.settled.filterValues { it.get() != 1 }
        val missing = pub.keys.filter { rig.settled[it] == null }
        assertTrue("double/zero settles: ${doubles.entries.take(5)}", doubles.isEmpty())
        assertTrue("never settled (${missing.size}): ${missing.take(3)}", missing.isEmpty())
        assertEquals(0, Fx.size(Fx.field(rig.pool, "settles")))
    }

    @Test
    fun reentrant_sync_callbacks_no_overflow() {
        val clock = AtomicLong(T0); val c = FakeConnector(); c.closeFirstN = 2
        val rig = PoolRig(listOf("wss://a", "wss://b", "wss://c"), clock, c)
        val n = AtomicInteger()
        rig.hook = { if (n.incrementAndGet() < 300) { rig.pool.publish(Fx.event(Fx.hex64(10_000L + n.get()))); clock.addAndGet(1200); rig.pool.tick() } }
        rig.pool.tick()
        for (i in 0 until 50) rig.pool.publish(Fx.event(Fx.hex64(i.toLong())))
        repeat(2000) { clock.addAndGet(1200); rig.pool.tick() }
        assertEquals(0, Fx.size(Fx.field(rig.pool, "settles")))
        assertTrue(rig.settled.values.all { it.get() == 1 })
    }

    @Test
    fun flap_storm_1000_no_leak() {
        val clock = AtomicLong(T0); val c = FakeConnector(); c.ack = FakeConnector.Ack.NONE
        val rig = PoolRig(listOf("wss://a", "wss://b"), clock, c)
        val r = Random(11); var id = 0L
        repeat(1000) {
            clock.addAndGet(r.nextInt(3000).toLong() + 1)
            rig.pool.tick()
            if (r.nextInt(3) == 0) rig.pool.publish(Fx.event(Fx.hex64(++id)))
            val cs = c.conns; val cn = cs[r.nextInt(cs.size)]
            when (r.nextInt(4)) {
                0 -> c.remoteClose(cn)
                1 -> cn.listener.onMessage("[\"CLOSED\",\"2007\",\"blocked: x\"]")
                2 -> cn.listener.onOpen()
                else -> {}
            }
        }
        c.ack = FakeConnector.Ack.NONE
        rig.pool.close()
        assertEquals("open sockets leaked after close", 0, c.live())
        assertEquals(0, Fx.size(Fx.field(rig.pool, "settles")))
        assertEquals(id.toInt(), rig.settled.size)
        assertTrue(rig.settled.values.all { it.get() == 1 })
    }

    @Test(timeout = 120_000)
    fun volume_thousands_of_relays_and_big_messages() {
        val clock = AtomicLong(T0); val c = FakeConnector()
        val urls = (0 until 3000).map { "wss://relay$it.example" }
        val rig = PoolRig(urls, clock, c)
        val t0 = System.nanoTime()
        rig.pool.tick()
        for (i in 0 until 200) rig.pool.publish(Fx.event(Fx.hex64(i.toLong()), "%02x".format(i)))
        repeat(60) { clock.addAndGet(1200); rig.pool.tick() }
        val big = "[\"EVENT\",\"2007\",{\"content\":\"" + "a".repeat(1_200_000) + "\"}]"
        repeat(100) { c.conns[it].listener.onMessage(big) }
        assertEquals(0, rig.events.get())
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms < 60_000)
        assertEquals(3000, rig.pool.status().size)
    }

    @Test
    fun json_nesting_bomb_does_not_crash_reader_thread() {
        val clock = AtomicLong(T0); val c = FakeConnector()
        val rig = PoolRig(listOf("wss://a"), clock, c)
        rig.pool.tick()
        val bombs = listOf("[".repeat(190_000), "[\"EVENT\",\"s\",{\"id\":\"x\",\"tags\":" + "[".repeat(190_000), "[\"OK\"," + "[".repeat(190_000))
        for (b in bombs) {
            try { c.conns[0].listener.onMessage(b) } catch (t: Throwable) { fail("relay message of ${b.length} chars threw ${t.javaClass.simpleName} out of the socket callback") }
        }
    }

    @Test(timeout = 120_000)
    fun bad_signature_replay_cost() {
        val sk = ByteArray(32) { (it + 1).toByte() }
        val e = Nostr.signEvent(sk, 1_700_000_000L, 7007, listOf(listOf("t", "ab")), "hello")
        val badSig = e.sig.substring(0, 126) + (if (e.sig.endsWith("00")) "01" else "00")
        val bad = e.copy(sig = badSig)
        val msg = "[\"EVENT\",\"2007\"," + Nostr.eventJson(bad) + "]"
        val clock = AtomicLong(T0); val c = FakeConnector()
        val rig = PoolRig(listOf("wss://a"), clock, c)
        rig.pool.tick()
        val n = 150
        val t0 = System.nanoTime()
        repeat(n) { c.conns[0].listener.onMessage(msg) }
        val perMs = (System.nanoTime() - t0) / 1e6 / n
        println("BREAKERC bad-sig replay: $perMs ms per message (invalid events are not remembered)")
        assertEquals(0, rig.events.get())
        assertTrue("each replayed forged event costs $perMs ms of signature verification on the socket thread", perMs < 20.0)
    }

    @Test
    fun publish_backoff_clock_back_blocks_long() {
        val b = PublishBackoff(kotlin.random.Random(1))
        repeat(10) { b.failed(T0) }
        assertFalse("publish backoff (max ~30s) still blocking 1 h after a clock step back", b.blocked(T0 - 3_600_000))
    }
}
