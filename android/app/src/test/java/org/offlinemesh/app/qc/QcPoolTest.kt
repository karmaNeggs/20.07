@file:Suppress("MaxLineLength","MagicNumber","TooManyFunctions","LongParameterList","LongMethod","CyclomaticComplexMethod","NestedBlockDepth","EmptyFunctionBlock","VariableNaming","WildcardImport","ComplexCondition","ReturnCount","LoopWithTooManyJumpStatements","SwallowedException","TooGenericExceptionCaught","EmptyElseBlock")

package org.offlinemesh.app.qc

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.offlinemesh.app.ble.MeshFrameCodec
import org.offlinemesh.app.ble.PositionTracker
import org.offlinemesh.app.ble.RelayResponder
import org.offlinemesh.app.ble.UplinkGateway
import org.offlinemesh.app.gateway.*
import org.json.JSONArray
import java.util.Random

class FakeConn(val url: String, val listener: RelayListener, val owner: FakeNet) : RelayConnection {
    val sent = ArrayList<Pair<Long, String>>()
    var alive = true
    var closed = false
    override fun send(text: String): Boolean {
        if (!alive) return false
        sent.add(owner.clock() to text)
        owner.onSend?.invoke(this, text)
        return alive
    }
    override fun close() { closed = true }
    val events get() = sent.filter { it.second.startsWith("[\"EVENT\"") }
    val reqs get() = sent.filter { it.second.startsWith("[\"REQ\"") }
}

class FakeNet(val clock: () -> Long) : RelayConnector {
    val conns = ArrayList<FakeConn>()
    var syncOpen = false
    var syncFail = false
    var onSend: ((FakeConn, String) -> Unit)? = null
    override fun connect(url: String, listener: RelayListener): RelayConnection {
        val c = FakeConn(url, listener, this)
        conns.add(c)
        if (syncFail) { listener.onClosed("x"); return c }
        if (syncOpen) listener.onOpen()
        return c
    }
    fun last(url: String) = conns.last { it.url == url }
}

class QcPoolTest {
    private var t = 1_800_000_000_000L
    private val urls = listOf("wss://a", "wss://b", "wss://c", "wss://d")
    private fun ev(i: Int, tag: String = "aa$i") =
        Nostr.signEvent(ByteArray(32) { 3 }, t / 1000, 22007, listOf(listOf("t", tag)), "c$i", ByteArray(32))

    private fun okMsg(e: NostrEvent, ok: Boolean = true, msg: String = "") = "[\"OK\",\"${e.id}\",$ok,\"$msg\"]"

    private class Rig(val pool: RelayPool, val net: FakeNet, val settled: MutableList<Triple<String, Int, Int>>, val inbound: MutableList<NostrEvent>)

    private fun rig(urlList: List<String> = urls, cfg: RelayPool.Config = RelayPool.Config()): Rig {
        val net = FakeNet { t }
        val settled = ArrayList<Triple<String, Int, Int>>()
        val inbound = ArrayList<NostrEvent>()
        val pool = RelayPool(urlList, net, { t }, cfg, { e, _ -> inbound.add(e) }, { id, a, n -> settled.add(Triple(id, a, n)) })
        return Rig(pool, net, settled, inbound)
    }

    private fun openAll(rg: Rig) {
        rg.pool.tick()
        rg.net.conns.filter { !it.closed }.forEach { it.listener.onOpen() }
        rg.pool.tick()
    }

    @Test fun publishesToAtMostTwoConnectedRelaysChosenByHash() {
        val rg = rig(); openAll(rg)
        val e = ev(1, "tagX")
        rg.pool.publish(e); rg.pool.tick()
        val sentTo = rg.net.conns.filter { it.events.isNotEmpty() }.map { it.url }.toSet()
        assertEquals(2, sentTo.size)
        // same pick regardless of url order
        val rg2 = rig(urls.reversed()); openAll(rg2)
        rg2.pool.publish(e); rg2.pool.tick()
        assertEquals(sentTo, rg2.net.conns.filter { it.events.isNotEmpty() }.map { it.url }.toSet())
        // only connected relays chosen
        val rg3 = rig(); rg3.pool.tick()
        rg3.net.last("wss://a").listener.onOpen(); rg3.net.last("wss://b").listener.onOpen()
        for (i in 0 until 20) rg3.pool.publish(ev(i, "t$i"))
        repeat(40) { t += 1200; rg3.pool.tick() }
        assertTrue(rg3.net.conns.filter { it.url in listOf("wss://c", "wss://d") }.all { it.events.isEmpty() })
    }

    @Test fun pacingOneEventPer1100ms() {
        val rg = rig(listOf("wss://a")); openAll(rg)
        for (i in 0 until 5) rg.pool.publish(ev(i))
        repeat(80) { t += 100; rg.pool.tick() }
        val times = rg.net.conns.single().events.map { it.first }
        assertTrue(times.size >= 4)
        for (i in 1 until times.size) assertTrue("gap ${times[i] - times[i - 1]}", times[i] - times[i - 1] >= 1100)
    }

    @Test fun incomingDedupAndBadSignature() {
        val rg = rig(); openAll(rg)
        val e = ev(1)
        val l = rg.net.last("wss://a").listener
        val m = "[\"EVENT\",\"2007\",${Nostr.eventJson(e)}]"
        l.onMessage(m); l.onMessage(m); rg.net.last("wss://b").listener.onMessage(m)
        assertEquals(1, rg.inbound.size)
        l.onMessage("[\"EVENT\",\"2007\",${Nostr.eventJson(ev(2).copy(content = "evil"))}]")
        assertEquals(1, rg.inbound.size)
    }

    @Test fun staleSocketCallbacksIgnored() {
        val rg = rig(listOf("wss://a")); openAll(rg)
        val old = rg.net.conns.single()
        rg.pool.publish(ev(1)); rg.pool.tick()
        old.listener.onMessage(okMsg(ev(1), false, "blocked: x")) // bans, generation++
        assertEquals(RelayPool.State.BANNED, rg.pool.status().single().state)
        val settledBefore = rg.settled.size
        old.listener.onClosed("late"); old.listener.onOpen()
        old.listener.onMessage("[\"EVENT\",\"2007\",${Nostr.eventJson(ev(5))}]")
        assertEquals(0, rg.inbound.size)
        assertEquals(settledBefore, rg.settled.size)
        assertEquals(RelayPool.State.BANNED, rg.pool.status().single().state)
    }

    @Test fun publishingSameEventTwiceSettlesBoth() {
        val rg = rig(listOf("wss://a")); openAll(rg)
        val e = ev(1)
        rg.pool.publish(e); rg.pool.publish(e)
        repeat(30) { t += 1200; rg.pool.tick(); rg.net.last("wss://a").let { c -> if (c.events.size > 0) c.listener.onMessage(okMsg(e)) } }
        assertEquals("each publish() must settle exactly once", 2, rg.settled.count { it.first == e.id })
    }

    @Test fun settlesExactlyOnceFuzz() {
        for (seed in 0 until 300) {
            val rnd = Random(seed.toLong())
            t = 1_800_000_000_000L
            val rg = rig()
            rg.net.syncOpen = rnd.nextInt(3) == 0
            rg.net.syncFail = false
            val published = ArrayList<NostrEvent>()
            rg.net.onSend = { c, text ->
                if (text.startsWith("[\"EVENT\"")) {
                    val id = Regex("\"id\":\"([0-9a-f]{64})\"").find(text)!!.groupValues[1]
                    when (rnd.nextInt(12)) {
                        0 -> { c.alive = false }
                        1 -> if (rnd.nextBoolean()) c.listener.onMessage("[\"OK\",\"$id\",false,\"blocked: no\"]")
                        2 -> c.listener.onMessage("[\"OK\",\"$id\",false,\"rate-limited: slow\"]")
                        3 -> c.listener.onClosed("boom")
                        4 -> c.listener.onMessage("[\"OK\",\"$id\",true,\"\"]")
                        5 -> { c.listener.onMessage("[\"OK\",\"$id\",true,\"\"]"); c.listener.onMessage("[\"OK\",\"$id\",true,\"\"]") }
                        else -> Unit
                    }
                }
            }
            var n = 0
            repeat(200) {
                t += rnd.nextInt(2500).toLong()
                when (rnd.nextInt(8)) {
                    0, 1 -> { val e = ev(seed * 1000 + n++, "t${rnd.nextInt(6)}"); published.add(e); rg.pool.publish(e) }
                    2 -> rg.net.conns.randomOrNull(kotlin.random.Random(rnd.nextLong()))?.let { c -> if (rnd.nextBoolean()) c.listener.onOpen() else c.listener.onClosed("x") }
                    3 -> rg.net.conns.randomOrNull(kotlin.random.Random(rnd.nextLong()))?.let { c ->
                        published.randomOrNull(kotlin.random.Random(rnd.nextLong()))?.let { e -> c.listener.onMessage("[\"OK\",\"${e.id}\",${rnd.nextBoolean()},\"${listOf("", "duplicate: x", "restricted: y", "rate-limited: z")[rnd.nextInt(4)]}\"]") } }
                    4 -> rg.pool.setSubscription(if (rnd.nextBoolean()) emptyList() else listOf(NostrFilter(listOf(1))))
                    else -> rg.pool.tick()
                }
            }
            rg.pool.close()
            val counts = rg.settled.groupingBy { it.first }.eachCount()
            for (e in published) assertEquals("seed $seed event ${e.id.take(6)}", 1, counts[e.id] ?: 0)
            for (s in rg.settled) assertTrue("seed $seed accepted>attempted ${s}", s.second <= s.third)
        }
    }

    @Test fun reentrantSynchronousTransport() {
        val rg = rig(); rg.net.syncOpen = true
        rg.net.onSend = { c, text -> if (text.startsWith("[\"EVENT\"")) { val id = Regex("\"id\":\"([0-9a-f]{64})\"").find(text)!!.groupValues[1]; c.listener.onMessage("[\"OK\",\"$id\",true,\"\"]") } }
        rg.pool.tick(); rg.pool.tick()
        val es = (0 until 6).map { ev(it, "q$it") }
        es.forEach { rg.pool.publish(it) }
        repeat(30) { t += 1200; rg.pool.tick() }
        for (e in es) assertEquals(1, rg.settled.count { it.first == e.id })
        assertTrue(rg.settled.all { it.second == it.third && it.third > 0 })
    }

    @Test fun pingPongSubscriptionAfterClosedReply() {
        val rg = rig(listOf("wss://a")); openAll(rg)
        rg.pool.setSubscription(listOf(NostrFilter(listOf(1))))
        rg.net.onSend = { c, text -> if (text.startsWith("[\"REQ\"")) c.listener.onMessage("[\"CLOSED\",\"2007\",\"error: nope\"]") }
        repeat(50) { t += 1000; rg.pool.tick() }
        assertTrue("REQ resent ${rg.net.conns.single().reqs.size} times to a relay that answers CLOSED", rg.net.conns.single().reqs.size < 10)
    }

    @Test fun benignRejectionTextDoesNotBanRelay() {
        val rg = rig(listOf("wss://a")); openAll(rg)
        val e = ev(1); rg.pool.publish(e); rg.pool.tick()
        rg.net.conns.single().listener.onMessage(okMsg(e, false, "invalid: content too long, restricted to 64KB"))
        assertFalse("relay banned for 10 min on an unrelated rejection", rg.pool.status().single().state == RelayPool.State.BANNED)
    }

    @Test fun backoffAfterRateLimit() {
        val rg = rig(listOf("wss://a")); openAll(rg)
        val a = ev(1); val b = ev(2)
        rg.pool.publish(a); rg.pool.publish(b); rg.pool.tick()
        rg.net.conns.single().listener.onMessage(okMsg(a, false, "rate-limited: slow down"))
        t += 1200; rg.pool.tick()
        assertEquals(1, rg.net.conns.single().events.size)
        t += 5000; rg.pool.tick()
        assertEquals(2, rg.net.conns.single().events.size)
    }

    // ---- link ----
    @Test fun interestTagsForFourGroupsAllSubscribed() {
        val net = FakeNet { t }
        val link = NostrGatewayLink(UplinkGateway({ t }), listOf("wss://a"), net, GatewayKeyHolder({ t }), { t }, {})
        val groups = (0 until 4).map { g -> ByteArray(32) { (g + 1).toByte() } }
        val all = groups.flatMap { UplinkTags.interestTags(it, t / 1000) }
        link.setInterestTags(all)
        link.tick(); net.conns.single().listener.onOpen(); link.tick()
        val req = net.conns.single().reqs.single().second
        val arr = JSONArray(req)
        val got = HashSet<String>()
        for (i in 2 until arr.length()) { val ts = arr.getJSONObject(i).getJSONArray("#t"); for (j in 0 until ts.length()) got.add(ts.getString(j)) }
        val want = all.map { it.joinToString("") { b -> "%02x".format(b) } }.toSet()
        assertEquals("4 groups need ${want.size} tags; subscribed ${got.size}", want, got)
    }

    // ---- controller ----
    private class FakeLink : UplinkLink {
        val offers = ArrayList<MeshFrameCodec.Frame.Uplink>()
        var connected = true
        var cong = false
        var bulk = false
        var accept = true
        var injectFn: ((ByteArray) -> Unit)? = null
        override fun offerLocal(frame: MeshFrameCodec.Frame.Uplink): UplinkGateway.Decision {
            offers.add(frame)
            return if (accept) UplinkGateway.Decision.Accepted(false) else UplinkGateway.Decision.Rejected(UplinkGateway.Reject.TAG_RATE)
        }
        override fun setInterestTags(tags: Collection<ByteArray>) {}
        override fun tick() {}
        override fun close() {}
        override fun hasConnectedRelay() = connected
        override fun congested() = cong
        override fun setBulkAllowed(allowed: Boolean) { bulk = allowed }
    }

    private class Src(val files: MutableList<FileUplink> = ArrayList(), val items: MutableList<MailboxItem> = ArrayList()) : UplinkSource {
        val g = UplinkGroup("g", ByteArray(32) { 5 })
        override suspend fun groups() = listOf(g)
        override suspend fun liveFrames(groupId: String) = listOf(ByteArray(10) { 1 })
        override suspend fun mailboxItems(groupId: String) = items
        override suspend fun lastKnownFrames(groupId: String) = listOf(ByteArray(10) { 2 })
        override suspend fun fileUplinks(groupId: String) = files
    }

    private fun ctl(src: Src, link: FakeLink, bulk: () -> Boolean = { true }, cfg: InternetReachController.Config = InternetReachController.Config(jitterFraction = 0.0)) =
        InternetReachController(src, { inj -> link.injectFn = inj; link }, { _, _ -> }, { t }, cfg, bulk)

    @Test fun mailboxItemsWrappedOnceAndClassed() = runBlocking {
        val src = Src(items = mutableListOf(MailboxItem("m1", ByteArray(5) { 1 }), MailboxItem("s1", ByteArray(5) { 2 }, alert = true)))
        val link = FakeLink(); val c = ctl(src, link); c.start()
        repeat(10) { t += 6000; c.step() }
        assertEquals(1, link.offers.count { it.cls == 2 }); assertEquals(1, link.offers.count { it.cls == 3 })
        c.markUplinked("m2"); src.items.add(MailboxItem("m2", ByteArray(5)))
        repeat(5) { t += 6000; c.step() }
        assertEquals(1, link.offers.count { it.cls == 2 })
    }

    @Test fun rejectedMailboxItemRetried() = runBlocking {
        val src = Src(items = mutableListOf(MailboxItem("m1", ByteArray(5))))
        val link = FakeLink(); link.accept = false; val c = ctl(src, link); c.start()
        t += 6000; c.step(); link.accept = true; t += 6000; c.step(); t += 6000; c.step()
        assertEquals(2, link.offers.count { it.cls == 2 })
    }

    @Test fun stopStartDoesNotReuplinkMailboxHistory() = runBlocking {
        val src = Src(items = mutableListOf(MailboxItem("m1", ByteArray(5))))
        val link = FakeLink(); val c = ctl(src, link); c.start()
        t += 6000; c.step(); c.stop(); c.start(); t += 6000; c.step()
        assertEquals("a network flap re-wraps and re-publishes already-sent history", 1, link.offers.count { it.cls == 2 })
    }

    @Test fun filesHeaderThenPacedSymbolsUpToWanted() = runBlocking {
        var produced = 0
        val f = FileUplink("f", ByteArray(8), 150) { n -> List(n) { produced++; ByteArray(4) } }
        val src = Src(files = mutableListOf(f)); val link = FakeLink(); val c = ctl(src, link); c.start()
        repeat(40) { t += 5000; c.step() }
        assertEquals(1, link.offers.count { it.cls == 4 })
        assertEquals(150, link.offers.count { it.cls == 5 }); assertEquals(150, produced)
        assertEquals(4, link.offers.first { it.cls == 4 || it.cls == 5 }.cls)
        // never when bulk disallowed / congested
        val link2 = FakeLink(); link2.cong = true; val c2 = ctl(Src(files = mutableListOf(FileUplink("g", ByteArray(2), 10) { n -> List(n) { ByteArray(1) } })), link2); c2.start()
        repeat(20) { t += 5000; c2.step() }
        assertEquals(0, link2.offers.count { it.cls >= 4 })
        val link3 = FakeLink(); val c3 = ctl(Src(files = mutableListOf(FileUplink("h", ByteArray(2), 10) { n -> List(n) { ByteArray(1) } })), link3, bulk = { false }); c3.start()
        repeat(20) { t += 5000; c3.step() }
        assertEquals(0, link3.offers.count { it.cls >= 4 })
    }

    @Test fun congestionSlowsLiveUpToTwelveAndRecovers() = runBlocking {
        val link = FakeLink(); link.cong = true
        val c = ctl(Src(), link); c.start()
        val liveTimes = ArrayList<Long>()
        var seen = 0
        repeat(600) { t += 1000; c.step(); val n = link.offers.count { it.cls == 0 }; if (n > seen) { liveTimes.add(t); seen = n } }
        val gaps = liveTimes.zipWithNext { a, b -> (b - a) / 1000 }
        // Updated 2026-10-08 (decision 79): cap is now 12x (120 s) and recovery is one step per minute.
        assertTrue("max gap ${gaps.max()}", gaps.max() <= 120)
        assertTrue("min late gap", gaps.takeLast(3).all { it >= 119 })
        link.cong = false; liveTimes.clear(); seen = link.offers.count { it.cls == 0 }
        repeat(1500) { t += 1000; c.step(); val n = link.offers.count { it.cls == 0 }; if (n > seen) { liveTimes.add(t); seen = n } }
        val g2 = liveTimes.zipWithNext { a, b -> (b - a) / 1000 }
        assertEquals(10L, g2.last())
    }

    @Test fun startSpreadsFirstPublishWithJitter() = runBlocking {
        val firsts = HashSet<Long>()
        repeat(20) { i ->
            val link = FakeLink()
            val c = ctl(Src(), link, cfg = InternetReachController.Config(random = kotlin.random.Random(i)))
            c.start()
            var dt = 0L
            while (link.offers.none { it.cls == 0 } && dt < 20000) { t += 500; dt += 500; c.step() }
            firsts.add(dt)
        }
        assertTrue(firsts.size > 3)
    }

    // ---- tracker ----
    @Test fun trackerLastSeen() {
        var now = 1_800_000_000_000L
        val p = PositionTracker { now }
        val s = now / 1000
        p.offerLastSeen("g", "a", 1.0, 1.0, 5, s - 100, 0)
        p.offerLastSeen("g", "a", 2.0, 2.0, 5, s - 50, 0)
        p.offerLastSeen("g", "a", 9.0, 9.0, 5, s - 70, 0)
        assertEquals(2.0, p.lastSeenForGroup("g")["a"]!!.lat, 0.0)
        p.offer("g", "b", 3.0, 3.0, 5, s, 0)
        p.offerLastSeen("g", "b", 3.0, 3.0, 5, s, 0)
        assertFalse(p.lastSeenForGroup("g").containsKey("b"))
        now += 6 * 3600 * 1000L
        assertFalse(p.lastSeenForGroup("g").containsKey("a"))
        p.offerLastSeen("g", "c", 1.0, 1.0, 5, now / 1000 - 6 * 3600, 0)
        assertTrue(p.lastSeenForGroup("g").containsKey("c"))
        p.clearForGroup("g"); assertTrue(p.lastSeenForGroup("g").isEmpty())
    }

    @Test fun oldPositionDeliveredByInternetDoesNotMeanOnlineNow() {
        val now = 1_800_000_000_000L
        val p = PositionTracker { now }
        p.offerLastSeen("g", "a", 1.0, 1.0, 5, now / 1000 - 5 * 3600, 0, RelayResponder.INTERNET_PEER)
        assertFalse("5-hour-old position marks member as online via internet", p.heardViaInternetWithin("g", "a", 300))
    }

    @Test fun futureTimestampCannotPinLastSeenForever() {
        var now = 1_800_000_000_000L
        val p = PositionTracker { now }
        p.offerLastSeen("g", "a", 1.0, 1.0, 5, now / 1000 + 10L * 86400, 0)
        now += 3600_000L * 24
        p.offerLastSeen("g", "a", 7.0, 7.0, 5, now / 1000, 0)
        assertEquals("a bogus future timestamp blocks all real updates", 7.0, p.lastSeenForGroup("g")["a"]?.lat ?: -1.0, 0.0)
    }

    @Test fun clearForGroupAlsoClearsInternetHeard() {
        val now = 1_800_000_000_000L
        val p = PositionTracker { now }
        p.offerLastSeen("g", "a", 1.0, 1.0, 5, now / 1000, 0, RelayResponder.INTERNET_PEER)
        assertTrue(p.heardViaInternetWithin("g", "a", 300))
        p.clearForGroup("g")
        assertFalse(p.heardViaInternetWithin("g", "a", 300))
    }
}
