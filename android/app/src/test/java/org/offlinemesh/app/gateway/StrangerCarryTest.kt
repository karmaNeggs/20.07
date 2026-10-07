@file:Suppress("MaxLineLength", "EmptyFunctionBlock", "LongParameterList", "MagicNumber", "TooManyFunctions")

package org.offlinemesh.app.gateway

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.offlinemesh.app.ble.MeshFrameCodec
import org.offlinemesh.app.ble.UplinkGateway

class StrangerCarryTest {
    private var nowMs = 1_800_000_000_000L
    private val key = ByteArray(32) { 5 }

    // ---------- codec ----------
    @Test fun `interest frame round-trips and rejects bad shapes`() {
        val tags = List(5) { ByteArray(6) { b -> (it + b).toByte() } }
        val f = MeshFrameCodec.decode(MeshFrameCodec.encodeUplinkInterest(1, tags)) as MeshFrameCodec.Frame.UplinkInterest
        assertEquals(1, f.hop); assertEquals(5, f.tags.size); assertTrue(f.tags[3].contentEquals(tags[3]))
        assertTrue(runCatching { MeshFrameCodec.encodeUplinkInterest(0, emptyList()) }.isFailure)
        assertTrue(runCatching { MeshFrameCodec.encodeUplinkInterest(0, List(17) { ByteArray(6) }) }.isFailure)
        assertTrue(runCatching { MeshFrameCodec.encodeUplinkInterest(0, listOf(ByteArray(40))) }.isFailure)
        val good = MeshFrameCodec.encodeUplinkInterest(0, tags)
        val badHop = good.copyOf().also { it[2] = 9 }
        assertNull(MeshFrameCodec.decode(badHop))
        assertNull(MeshFrameCodec.decode(good.copyOf(good.size - 3)))
        val rnd = java.util.Random(1)
        repeat(2000) { MeshFrameCodec.decode(ByteArray(rnd.nextInt(40)) { rnd.nextInt().toByte() }.also { if (it.isNotEmpty()) it[0] = 0x21 }) }
    }

    // ---------- registry ----------
    @Test fun `registry keeps tags for ten minutes, refreshes only from equal or closer, regossips with hop plus one`() {
        val r = InterestRegistry({ nowMs })
        val t = ByteArray(6) { 1 }
        r.onHeard(MeshFrameCodec.Frame.UplinkInterest(0, listOf(t)))
        assertEquals(1, r.tags().size)
        assertEquals(1, (MeshFrameCodec.decode(r.regossip(1).single()) as MeshFrameCodec.Frame.UplinkInterest).hop)
        nowMs += 61_000
        assertTrue(r.regossip(1).isEmpty())            // not heard recently: no longer passed on
        r.onHeard(MeshFrameCodec.Frame.UplinkInterest(2, listOf(t)))   // farther source must not refresh
        nowMs += 9 * 60_000
        assertTrue(r.tags().isEmpty())                  // original 10 min ttl ran out
    }

    @Test fun `registry never passes on a tag two hops away and is capped`() {
        val r = InterestRegistry({ nowMs }, maxTags = 3)
        r.onHeard(MeshFrameCodec.Frame.UplinkInterest(2, listOf(ByteArray(6) { 9 })))
        assertTrue(r.regossip(5).isEmpty())
        r.onHeard(MeshFrameCodec.Frame.UplinkInterest(0, List(8) { ByteArray(6) { b -> (it * 10 + b).toByte() } }))
        assertTrue(r.tags().size <= 3)
    }

    // ---------- held frames ----------
    @Test fun `heldFrames lists in priority order, filters by size and does not drain`() {
        val g = UplinkGateway({ nowMs })
        val sec = nowMs / 1000
        fun up(cls: Int, n: Int, tag: Int) = MeshFrameCodec.Frame.Uplink(byteArrayOf(tag.toByte(), 2, 3, 4, 5, 6), cls, 0, sec, ByteArray(n) { (it + tag).toByte() })
        g.offer(up(MeshFrameCodec.UPLINK_CLASS_LIVE, 50, 1)); g.offer(up(MeshFrameCodec.UPLINK_CLASS_ALERT, 50, 2)); g.offer(up(MeshFrameCodec.UPLINK_CLASS_TEXT, 900, 3))
        val held = g.heldFrames(10, 200)
        assertEquals(2, held.size)                                   // the 900 B one is too big
        assertEquals(MeshFrameCodec.UPLINK_CLASS_ALERT, (MeshFrameCodec.decode(held[0].second) as MeshFrameCodec.Frame.Uplink).cls)
        assertEquals(3, g.pendingCount())                            // snapshot only
        assertEquals(held.map { it.first }, g.heldFrames(10, 200).map { it.first })   // stable keys
    }

    // ---------- the scenario ----------
    private class Phone(val name: String, val src: Src, val controller: InternetReachController, val registry: InterestRegistry, val bridge: UplinkBleBridge, val inbound: ArrayList<ByteArray>)
    private class Src(val key: ByteArray?, val groupId: String = "g") : UplinkSource {
        val msgs = ArrayList<MailboxItem>()
        override suspend fun groups() = if (key == null) emptyList() else listOf(UplinkGroup(groupId, key))
        override suspend fun liveFrames(groupId: String) = emptyList<ByteArray>()
        override suspend fun mailboxItems(groupId: String) = msgs.toList()
        override suspend fun lastKnownFrames(groupId: String) = emptyList<ByteArray>()
    }

    private fun phone(name: String, net: FakeRelayNetwork, groupKey: ByteArray?): Phone {
        val src = Src(groupKey)
        val inbound = ArrayList<ByteArray>()
        val registry = InterestRegistry({ nowMs })
        lateinit var c: InternetReachController
        c = InternetReachController(
            src,
            { inj -> NostrGatewayLink(UplinkGateway({ nowMs }), listOf("wss://a", "wss://b", "wss://c"), net.connector(), GatewayKeyHolder({ nowMs }), { nowMs }, inj) },
            { _, inner -> inbound.add(inner) }, { nowMs }, InternetReachController.Config(jitterFraction = 0.0), bulkAllowed = { true },
            extraInterest = { registry.tags() },
        )
        val bridge = UplinkBleBridge(c, registry) { true }
        c.start()
        return Phone(name, src, c, registry, bridge, inbound)
    }

    private fun ble(from: Phone, to: Phone, maxBytes: Int = 480) = runBlocking {
        for (enc in from.bridge.framesToPush(to.name, maxBytes)) {
            when (val f = MeshFrameCodec.decode(enc)) {
                is MeshFrameCodec.Frame.Uplink -> to.bridge.onUplink(f)
                is MeshFrameCodec.Frame.UplinkInterest -> to.bridge.onInterest(f)
                else -> {}
            }
        }
    }

    @Test fun `a member with no internet reaches a distant member through a stranger who has internet, and the reply returns`() {
        val offlineNet = FakeRelayNetwork().also { n -> listOf("wss://a", "wss://b", "wss://c").forEach { n.server(it).up = false } }
        val net = FakeRelayNetwork()
        val a = phone("A", offlineNet, key)      // member, switch on, no internet
        val s = phone("S", net, null)             // stranger: no group key, has internet
        val c = phone("C", net, key)              // member, far away, internet
        val textA = ByteArray(90) { 7 }.also { it[0] = 1 }
        val textC = ByteArray(90) { 8 }.also { it[0] = 2 }
        a.src.msgs.add(MailboxItem("m-a", textA))
        c.src.msgs.add(MailboxItem("m-c", textC))
        repeat(90) {
            nowMs += 1000
            runBlocking { a.controller.step(); s.controller.step(); c.controller.step() }
            ble(a, s); ble(s, a)
        }
        assertEquals("C received A's message once, via the stranger", 1, c.inbound.count { it.contentEquals(textA) })
        assertEquals("the stranger received C's reply because A told it which tag to listen for", 1, s.inbound.count { it.contentEquals(textC) })
        assertTrue("A is offline and never published itself", offlineNet.server("wss://a").received.isEmpty())
    }

    @Test fun `each held frame is handed to a given neighbour only once and a disabled phone carries nothing`() {
        val offlineNet = FakeRelayNetwork().also { n -> listOf("wss://a", "wss://b", "wss://c").forEach { n.server(it).up = false } }
        val a = phone("A", offlineNet, key)
        a.src.msgs.add(MailboxItem("m1", ByteArray(60) { 3 }))
        repeat(12) { nowMs += 1000; runBlocking { a.controller.step() } }
        val first = runBlocking { a.bridge.framesToPush("S", 480) }.filter { MeshFrameCodec.decode(it) is MeshFrameCodec.Frame.Uplink }
        val second = runBlocking { a.bridge.framesToPush("S", 480) }.filter { MeshFrameCodec.decode(it) is MeshFrameCodec.Frame.Uplink }
        assertTrue(first.isNotEmpty()); assertTrue(second.isEmpty())
        val off = UplinkBleBridge(a.controller, a.registry) { false }
        assertTrue(runBlocking { off.framesToPush("S", 480) }.isEmpty())
    }

    @Test fun `a frame already marked as from the internet is not carried over Bluetooth`() {
        val offlineNet = FakeRelayNetwork().also { n -> listOf("wss://a", "wss://b", "wss://c").forEach { n.server(it).up = false } }
        val s = phone("S", offlineNet, null)
        val f = MeshFrameCodec.Frame.Uplink(ByteArray(6) { 1 }, MeshFrameCodec.UPLINK_CLASS_TEXT, MeshFrameCodec.UPLINK_FLAG_FROM_INTERNET, nowMs / 1000, ByteArray(30))
        runBlocking { s.bridge.onUplink(f) }
        assertTrue(runBlocking { s.bridge.framesToPush("X", 480) }.none { MeshFrameCodec.decode(it) is MeshFrameCodec.Frame.Uplink })
    }
}
