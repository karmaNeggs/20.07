package org.offlinemesh.app.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayPoolTest {
    private val urls = listOf("wss://a", "wss://b", "wss://c")
    private var nowMs = 1_000_000_000_000L
    private val net = FakeRelayNetwork()
    private val delivered = ArrayList<Pair<NostrEvent, String>>()
    private val settled = ArrayList<Triple<String, Int, Int>>()
    private val sk = ByteArray(32) { (it + 1).toByte() }

    private fun pool(cfg: RelayPool.Config = RelayPool.Config()) = RelayPool(
        urls, net.connector(), { nowMs }, cfg,
        onEvent = { e, url -> delivered.add(e to url) },
        onPublishSettled = { id, ok, n -> settled.add(Triple(id, ok, n)) },
    )

    private fun event(content: String = "hello", kind: Int = 22007, ts: Long = nowMs / 1000) =
        Nostr.signEvent(sk, ts, kind, listOf(listOf("t", "ab12")), content, ByteArray(32))

    private fun advance(ms: Long, p: RelayPool) { nowMs += ms; p.tick() }
    private val filter = NostrFilter(listOf(22007), mapOf("t" to listOf("ab12")))

    @Test fun `tick connects every relay and subscribes once open`() {
        val p = pool()
        p.setSubscription(listOf(filter))
        p.tick()
        assertTrue(p.status().all { it.state == RelayPool.State.CONNECTED })
        for (u in urls) assertTrue(net.server(u).clients.single().sent.single().startsWith("[\"REQ\""))
    }

    @Test fun `no subscription means no REQ or CLOSE noise on connect`() {
        val p = pool()
        p.tick()
        for (u in urls) assertTrue(net.server(u).clients.single().sent.isEmpty())
    }

    @Test fun `publish goes to every relay and settles with the accepted count`() {
        val p = pool()
        net.server("wss://c").reply = { false to "invalid: nope" }
        p.tick()
        val e = event()
        p.publish(e)
        p.tick()
        assertEquals(listOf(Triple(e.id, 2, 3)), settled)
        for (u in urls) assertEquals(listOf(e.id), net.server(u).received.map { it.id })
    }

    @Test fun `events are paced per relay`() {
        val p = pool()
        p.tick()
        p.publish(event("1")); p.publish(event("2"))
        p.tick()
        assertEquals(1, net.server("wss://a").received.size)
        advance(500, p)
        assertEquals(1, net.server("wss://a").received.size)
        advance(700, p)
        assertEquals(2, net.server("wss://a").received.size)
    }

    @Test fun `a relay that never answers settles as a failure after the ack timeout`() {
        val p = pool()
        for (u in urls) net.server(u).silent = true
        p.tick()
        p.publish(event())
        p.tick()
        assertTrue(settled.isEmpty())
        advance(11_000, p)
        assertEquals(1, settled.size)
        assertEquals(0, settled[0].second)
        assertEquals(3, settled[0].third)
    }

    @Test fun `rate-limited reply pauses that relay instead of hammering it`() {
        val p = pool()
        net.server("wss://a").reply = { false to "rate-limited: you are noting too much" }
        p.tick()
        p.publish(event("1")); p.tick()
        p.publish(event("2"))
        advance(1200, p)
        assertEquals(1, net.server("wss://a").received.size)
        advance(5000, p)
        assertEquals(2, net.server("wss://a").received.size)
    }

    @Test fun `a banning relay is dropped, its queue failed, and not reconnected until the ban ends`() {
        val p = pool(RelayPool.Config(banBackoffMs = 60_000))
        net.server("wss://b").reply = { false to "banned: too many rate-limit violations" }
        p.tick()
        p.publish(event("1")); p.publish(event("2"))
        p.tick()
        assertEquals(RelayPool.State.BANNED, p.status().first { it.url == "wss://b" }.state)
        val connects = net.server("wss://b").connectCount
        advance(30_000, p)
        assertEquals(connects, net.server("wss://b").connectCount)
        advance(31_000, p)
        assertEquals(connects + 1, net.server("wss://b").connectCount)
    }

    @Test fun `publish while every relay is banned settles immediately with zero attempted`() {
        val p = pool()
        for (u in urls) net.server(u).reply = { false to "blocked: go away" }
        p.tick()
        p.publish(event("1")); p.tick()
        val e2 = event("2")
        p.publish(e2)
        assertEquals(Triple(e2.id, 0, 0), settled.last())
        assertFalse(p.hasConnectedRelay())
    }

    @Test fun `a dropped connection reconnects with growing backoff and resubscribes`() {
        val p = pool()
        p.setSubscription(listOf(filter))
        p.tick()
        val a = net.server("wss://a")
        a.up = false
        a.dropAll()
        assertEquals(RelayPool.State.BACKOFF, p.status().first { it.url == "wss://a" }.state)
        advance(1000, p)
        assertEquals(1, a.connectCount)
        advance(1500, p)
        assertEquals(2, a.connectCount)
        a.up = true
        advance(4100, p)
        assertEquals(3, a.connectCount)
        assertTrue(a.clients.single().sent.single().startsWith("[\"REQ\""))
    }

    @Test fun `a stale callback from a replaced connection is ignored`() {
        val p = pool()
        p.tick()
        val a = net.server("wss://a")
        val old = a.clients.single()
        a.dropAll()
        advance(2100, p)
        assertTrue(p.status().first { it.url == "wss://a" }.state == RelayPool.State.CONNECTED)
        old.listener.onClosed("late echo of the old socket")
        assertTrue(p.status().first { it.url == "wss://a" }.state == RelayPool.State.CONNECTED)
    }

    @Test fun `the same event from several relays is delivered once`() {
        val p = pool()
        p.setSubscription(listOf(filter))
        p.tick()
        p.publish(event("dup"))
        p.tick()
        assertEquals(1, delivered.size)
    }

    @Test fun `an incoming event with a bad signature is dropped`() {
        val p = pool()
        p.setSubscription(listOf(filter))
        p.tick()
        val good = event("x")
        val bad = good.copy(content = "tampered")
        net.server("wss://a").clients.single().listener.onMessage("[\"EVENT\",\"2007\",${Nostr.eventJson(bad)}]")
        assertTrue(delivered.isEmpty())
    }

    @Test fun `a queued event expires when its relay never comes up`() {
        val p = pool()
        net.server("wss://a").up = false
        p.tick()
        p.publish(event())
        p.tick()
        advance(31_000, p)
        assertEquals(1, settled.size)
        assertEquals(2, settled[0].second)
    }

    @Test fun `a full queue drops the oldest and fails it`() {
        val p = pool(RelayPool.Config(maxQueuePerRelay = 2))
        for (u in urls) net.server(u).up = false
        p.tick()
        val first = event("1")
        p.publish(first); p.publish(event("2")); p.publish(event("3"))
        assertTrue(settled.any { it.first == first.id })
    }

    @Test fun `close shuts every connection and fails what was pending`() {
        val p = pool()
        for (u in urls) net.server(u).silent = true
        p.tick()
        p.publish(event())
        p.tick()
        p.close()
        assertEquals(1, settled.size)
        assertEquals(0, settled[0].second)
    }
}
