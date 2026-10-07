package org.offlinemesh.app.gateway

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.offlinemesh.app.ble.MeshFrameCodec
import org.offlinemesh.app.ble.UplinkGateway

class InternetReachControllerTest {
    private var nowMs = 1_800_000_000_000L
    private val groupKey = ByteArray(32) { 7 }
    private val otherKey = ByteArray(32) { 9 }

    private class FakeSource(val key: ByteArray, val groupId: String = "g1") : UplinkSource {
        val live = ArrayList<ByteArray>()
        val mailbox = ArrayList<MailboxItem>()
        val lastKnown = ArrayList<ByteArray>()
        override suspend fun groups() = listOf(UplinkGroup(groupId, key))
        override suspend fun liveFrames(groupId: String) = live.toList()
        override suspend fun mailboxItems(groupId: String) = mailbox.toList()
        override suspend fun lastKnownFrames(groupId: String) = lastKnown.toList()
    }

    private class FakeLink : UplinkLink {
        val offered = ArrayList<MeshFrameCodec.Frame.Uplink>()
        var interest: List<ByteArray> = emptyList()
        var connected = true
        var closed = false
        var ticks = 0
        override fun offerLocal(frame: MeshFrameCodec.Frame.Uplink): UplinkGateway.Decision {
            offered.add(frame); return UplinkGateway.Decision.Accepted(false)
        }
        override fun setInterestTags(tags: Collection<ByteArray>) { interest = tags.toList() }
        override fun tick() { ticks++ }
        override fun close() { closed = true }
        override fun hasConnectedRelay() = connected
    }

    private fun fakeController(source: UplinkSource, link: FakeLink, inbound: MutableList<ByteArray> = ArrayList()) =
        InternetReachController(
            source, { link }, { _, inner -> inbound.add(inner) }, { nowMs },
            InternetReachController.Config(jitterFraction = 0.0),
        )

    private fun step(c: InternetReachController, ms: Long = 1000) = runBlocking { nowMs += ms; c.step() }

    @Test fun `nothing happens before start and after stop`() {
        val link = FakeLink(); val src = FakeSource(groupKey); src.live.add(ByteArray(10))
        val c = fakeController(src, link)
        step(c)
        assertEquals(0, link.ticks)
        c.start(); step(c); c.stop(); step(c)
        assertTrue(link.closed)
        assertFalse(c.running)
    }

    @Test fun `live frames are wrapped under the live tag on an interval and not before a relay is connected`() {
        val link = FakeLink(); link.connected = false
        val src = FakeSource(groupKey); src.live.add(ByteArray(40) { 1 })
        val c = fakeController(src, link); c.start()
        step(c)
        assertTrue(link.offered.isEmpty())
        link.connected = true
        step(c)
        val f = link.offered.single()
        assertEquals(MeshFrameCodec.UPLINK_CLASS_LIVE, f.cls)
        assertArrayEquals(UplinkTags.liveTag(groupKey, nowMs / 1000), f.relayTag)
        step(c, 3000)
        assertEquals(1, link.offered.size)
        step(c, 8000)
        assertEquals(2, link.offered.size)
    }

    @Test fun `a message is wrapped as text exactly once`() {
        val link = FakeLink(); val src = FakeSource(groupKey)
        src.mailbox.add(MailboxItem("sos:1", ByteArray(60) { 2 }))
        val c = fakeController(src, link); c.start()
        repeat(4) { step(c, 6000) }
        val texts = link.offered.filter { it.cls == MeshFrameCodec.UPLINK_CLASS_TEXT }
        assertEquals(1, texts.size)
        val sent = texts.single()
        assertArrayEquals(UplinkTags.mailboxTag(groupKey, sent.createdAtSec), sent.relayTag)
    }

    @Test fun `an item that arrived from the relay is never published back`() {
        val link = FakeLink(); val src = FakeSource(groupKey)
        src.mailbox.add(MailboxItem("sos:echo", ByteArray(60)))
        val c = fakeController(src, link); c.start()
        c.markUplinked("sos:echo")
        repeat(3) { step(c, 6000) }
        assertTrue(link.offered.isEmpty())
    }

    @Test fun `interest covers the group's live and mailbox tags`() {
        val link = FakeLink(); val c = fakeController(FakeSource(groupKey), link); c.start(); step(c)
        val want = UplinkTags.interestTags(groupKey, nowMs / 1000).map { it.toHex() }.toSet()
        assertEquals(want, link.interest.map { it.toHex() }.toSet())
    }

    // ---------- the two win conditions, end to end through the real link and in-memory relays ----------

    private class Phone(
        val name: String,
        val source: FakeSource,
        val controller: InternetReachController,
        val inbound: ArrayList<ByteArray>,
        val lastKnownInbound: ArrayList<ByteArray> = ArrayList(),
    )

    private fun phone(name: String, net: FakeRelayNetwork, key: ByteArray): Phone {
        val src = FakeSource(key)
        val inbound = ArrayList<ByteArray>()
        val lastKnownInbound = ArrayList<ByteArray>()
        val urls = listOf("wss://a", "wss://b", "wss://c")
        val c = InternetReachController(
            src,
            { inject ->
                NostrGatewayLink(
                    UplinkGateway({ nowMs }, UplinkGateway.Config(liveKeepPerTag = 24, framesPerTagPerMinute = 180)),
                    urls, net.connector(), GatewayKeyHolder({ nowMs }), { nowMs }, inject,
                )
            },
            { cls, inner ->
                val target = if (cls == MeshFrameCodec.UPLINK_CLASS_POSITION_LAST_KNOWN) lastKnownInbound else inbound
                target.add(inner)
            },
            { nowMs },
            InternetReachController.Config(jitterFraction = 0.0),
        )
        c.start()
        return Phone(name, src, c, inbound, lastKnownInbound)
    }

    private fun run(phones: List<Phone>, seconds: Int) = repeat(seconds) {
        nowMs += 1000
        phones.forEach { runBlocking { it.controller.step() } }
    }

    @Test fun `win 1 - two internet-only phones exchange a position and a message both ways`() {
        val net = FakeRelayNetwork()
        val c = phone("C", net, groupKey); val d = phone("D", net, groupKey)
        val posC = ByteArray(300) { 3 }; val posD = ByteArray(300) { 4 }
        val textC = ByteArray(120) { 5 }; val textD = ByteArray(120) { 6 }
        c.source.live.add(posC); d.source.live.add(posD)
        c.source.mailbox.add(MailboxItem("sos:c1", textC)); d.source.mailbox.add(MailboxItem("sos:d1", textD))
        run(listOf(c, d), 30)
        assertTrue("D got C's position", d.inbound.any { it.contentEquals(posC) })
        assertTrue("C got D's position", c.inbound.any { it.contentEquals(posD) })
        assertEquals("D got C's text once", 1, d.inbound.count { it.contentEquals(textC) })
        assertEquals("C got D's text once", 1, c.inbound.count { it.contentEquals(textD) })
    }

    @Test fun `win 2 - a bridge carries a BLE-only phone's frames to an internet-only phone and back`() {
        val net = FakeRelayNetwork()
        val bridge = phone("B", net, groupKey); val c = phone("C", net, groupKey)
        val posA = ByteArray(300) { 8 }   // learned by B over BLE from phone A, which has no internet
        val textA = ByteArray(100) { 9 }
        bridge.source.live.add(posA); bridge.source.mailbox.add(MailboxItem("sos:a1", textA))
        val textC = ByteArray(100) { 10 }
        c.source.mailbox.add(MailboxItem("sos:c1", textC))
        run(listOf(bridge, c), 30)
        assertTrue("C sees A's position through B", c.inbound.any { it.contentEquals(posA) })
        assertEquals("C gets A's text once", 1, c.inbound.count { it.contentEquals(textA) })
        val atBridge = bridge.inbound.count { it.contentEquals(textC) }
        assertEquals("B receives C's text, ready to flood to A over BLE", 1, atBridge)
    }

    @Test fun `a last-known position reaches a phone that joins after it was published`() {
        val net = FakeRelayNetwork()
        val a = phone("A", net, groupKey)
        val pos = ByteArray(280) { 11 }
        a.source.lastKnown.add(pos)
        run(listOf(a), 15)
        val late = phone("late", net, groupKey)
        run(listOf(a, late), 20)
        assertEquals("delivered once as last-known", 1, late.lastKnownInbound.count { it.contentEquals(pos) })
        assertTrue("never delivered as live", late.inbound.none { it.contentEquals(pos) })
    }

    @Test fun `last-known frames are produced about once a minute under the mailbox tag`() {
        val link = FakeLink(); val src = FakeSource(groupKey)
        src.lastKnown.add(ByteArray(50) { 1 })
        val c = fakeController(src, link); c.start()
        step(c)
        step(c, 30_000)
        assertEquals(1, link.offered.size)
        step(c, 40_000)
        assertEquals(2, link.offered.size)
        val f = link.offered.first()
        assertEquals(MeshFrameCodec.UPLINK_CLASS_POSITION_LAST_KNOWN, f.cls)
        assertArrayEquals(UplinkTags.mailboxTag(groupKey, f.createdAtSec), f.relayTag)
    }

    @Test fun `a group that is not ours never receives our frames`() {
        val net = FakeRelayNetwork()
        val c = phone("C", net, groupKey); val stranger = phone("X", net, otherKey)
        c.source.live.add(ByteArray(200) { 1 }); c.source.mailbox.add(MailboxItem("sos:c1", ByteArray(50) { 2 }))
        run(listOf(c, stranger), 30)
        assertTrue(stranger.inbound.isEmpty())
    }

    @Test fun `turning the feature off stops all publishing`() {
        val net = FakeRelayNetwork()
        val c = phone("C", net, groupKey)
        c.source.live.add(ByteArray(200) { 1 })
        run(listOf(c), 12)
        val sent = net.server("wss://a").received.size
        assertTrue(sent > 0)
        c.controller.stop()
        run(listOf(c), 30)
        assertEquals(sent, net.server("wss://a").received.size)
    }
}
