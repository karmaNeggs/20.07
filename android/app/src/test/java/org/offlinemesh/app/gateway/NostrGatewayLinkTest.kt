package org.offlinemesh.app.gateway

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.offlinemesh.app.ble.MeshFrameCodec
import org.offlinemesh.app.ble.UplinkGateway
import java.util.Base64

class NostrGatewayLinkTest {
    private val urls = listOf("wss://a", "wss://b", "wss://c")
    private var nowMs = 1_000_000_000_000L
    private val nowSec get() = nowMs / 1000
    private val net = FakeRelayNetwork()
    private val tag = byteArrayOf(1, 2, 3, 4, 5, 6)

    private class Side(val gateway: UplinkGateway, val link: NostrGatewayLink, val injected: ArrayList<ByteArray>)

    private fun side(cfg: NostrGatewayLink.Config = NostrGatewayLink.Config(), keys: GatewayKeyHolder? = null): Side {
        val injected = ArrayList<ByteArray>()
        val gateway = UplinkGateway({ nowMs })
        val link = NostrGatewayLink(
            gateway, urls, net.connector(), keys ?: GatewayKeyHolder({ nowMs }), { nowMs },
            { injected.add(it) }, cfg,
        )
        return Side(gateway, link, injected)
    }

    private fun uplink(cls: Int, inner: ByteArray = ByteArray(200) { 5 }, createdAtSec: Long = nowSec) =
        MeshFrameCodec.Frame.Uplink(tag, cls, 0, createdAtSec, inner)

    private fun step(s: Side, ms: Long = 1200) { nowMs += ms; s.link.tick() }

    @Test fun `live frames go out as ephemeral kind, mailbox frames as stored kind with an expiration`() {
        val s = side()
        s.link.tick()
        s.link.offerFromBle(uplink(MeshFrameCodec.UPLINK_CLASS_LIVE))
        s.link.offerFromBle(uplink(MeshFrameCodec.UPLINK_CLASS_TEXT, ByteArray(100) { 9 }))
        s.link.offerFromBle(uplink(MeshFrameCodec.UPLINK_CLASS_POSITION_LAST_KNOWN, ByteArray(100) { 8 }))
        repeat(6) { step(s) }
        val got = net.server("wss://a").received
        val live = got.filter { it.kind == NostrGatewayLink.LIVE_KIND }
        val mailbox = got.filter { it.kind == NostrGatewayLink.MAILBOX_KIND }
        assertEquals(1, live.size)
        assertEquals(2, mailbox.size)
        assertEquals("010203040506", live.single().tagValue("t"))
        assertTrue(live.single().tagValue("expiration") == null)
        assertTrue(mailbox.all { it.tagValue("expiration")!!.toLong() > nowSec })
        assertTrue(got.all { Nostr.verifyEvent(it) })
    }

    @Test fun `a last-known position expires sooner than a text on the relay`() {
        val s = side()
        s.link.tick()
        s.link.offerFromBle(uplink(MeshFrameCodec.UPLINK_CLASS_POSITION_LAST_KNOWN, ByteArray(100) { 8 }))
        s.link.offerFromBle(uplink(MeshFrameCodec.UPLINK_CLASS_TEXT, ByteArray(100) { 9 }))
        repeat(4) { step(s) }
        val byExpiry = net.server("wss://a").received.map { it.tagValue("expiration")!!.toLong() - nowSec }.sorted()
        assertEquals(2, byExpiry.size)
        assertTrue(byExpiry[0] in (UplinkGateway.POSITION_MAX_AGE_SEC - 20)..UplinkGateway.POSITION_MAX_AGE_SEC)
        assertTrue(byExpiry[1] > UplinkGateway.POSITION_MAX_AGE_SEC)
    }

    @Test fun `content is a base64 batch that decodes back to the uplink frame`() {
        val s = side()
        s.link.tick()
        s.link.offerFromBle(uplink(MeshFrameCodec.UPLINK_CLASS_TEXT, ByteArray(64) { 3 }))
        step(s)
        val e = net.server("wss://a").received.single()
        val frames = org.offlinemesh.app.ble.UplinkBatch.decode(Base64.getDecoder().decode(e.content))
        val f = MeshFrameCodec.decode(frames.single()) as MeshFrameCodec.Frame.Uplink
        assertArrayEquals(ByteArray(64) { 3 }, f.inner)
    }

    @Test fun `a position crosses from one gateway to another through the relays and is marked fromInternet`() {
        val a = side(); val b = side()
        b.link.setInterestTags(listOf(tag))
        a.link.tick(); b.link.tick()
        val inner = ByteArray(300) { (it * 3).toByte() }
        a.link.offerFromBle(uplink(MeshFrameCodec.UPLINK_CLASS_LIVE, inner))
        step(a); step(b, 0)
        assertEquals(1, b.injected.size)
        val f = MeshFrameCodec.decode(b.injected.single()) as MeshFrameCodec.Frame.Uplink
        assertArrayEquals(inner, f.inner)
        assertTrue(f.flags and MeshFrameCodec.UPLINK_FLAG_FROM_INTERNET != 0)
        assertEquals(0, b.gateway.drain().size)
    }

    @Test fun `a mule that connects later still collects a stored text`() {
        val a = side()
        a.link.tick()
        val inner = ByteArray(80) { 4 }
        a.link.offerFromBle(uplink(MeshFrameCodec.UPLINK_CLASS_TEXT, inner))
        step(a)
        val late = side()
        late.link.setInterestTags(listOf(tag))
        late.link.tick()
        assertEquals(1, late.injected.size)
        assertArrayEquals(inner, (MeshFrameCodec.decode(late.injected.single()) as MeshFrameCodec.Frame.Uplink).inner)
    }

    @Test fun `our own published event echoing back is not injected into our own mesh`() {
        val a = side()
        a.link.setInterestTags(listOf(tag))
        a.link.tick()
        a.link.offerFromBle(uplink(MeshFrameCodec.UPLINK_CLASS_LIVE))
        step(a)
        assertTrue(a.injected.isEmpty())
    }

    @Test fun `a batch no relay accepted goes back to the gateway instead of being lost`() {
        val s = side()
        for (u in urls) net.server(u).reply = { false to "invalid: no thanks" }
        s.link.tick()
        s.link.offerFromBle(uplink(MeshFrameCodec.UPLINK_CLASS_TEXT))
        step(s)
        assertEquals(1, s.gateway.pendingCount())
    }

    @Test fun `a batch accepted by at least one relay is not requeued`() {
        val s = side()
        net.server("wss://b").reply = { false to "invalid: no" }
        net.server("wss://c").reply = { false to "invalid: no" }
        s.link.tick()
        s.link.offerFromBle(uplink(MeshFrameCodec.UPLINK_CLASS_TEXT))
        step(s)
        assertEquals(0, s.gateway.pendingCount())
    }

    @Test fun `nothing is drained while no relay is connected`() {
        val s = side()
        for (u in urls) net.server(u).up = false
        s.link.offerFromBle(uplink(MeshFrameCodec.UPLINK_CLASS_TEXT))
        repeat(3) { step(s) }
        assertEquals(1, s.gateway.pendingCount())
    }

    @Test fun `interest tags become a live filter and a mailbox filter, capped`() {
        val s = side(NostrGatewayLink.Config(maxInterestTags = 3))
        s.link.setInterestTags((1..10).map { byteArrayOf(it.toByte(), 0, 0, 0, 0, 0) })
        s.link.tick()
        val req = net.server("wss://a").clients.single().filters
        assertEquals(2, req.size)
        assertEquals(listOf(NostrGatewayLink.LIVE_KIND), req[0].kinds)
        assertEquals(listOf(NostrGatewayLink.MAILBOX_KIND), req[1].kinds)
        assertEquals(3, req[0].tagValues.getValue("t").size)
        assertEquals(nowSec - NostrGatewayLink.LIVE_LOOKBACK_SEC, req[0].since)
    }

    @Test fun `clearing interest tags closes the subscription`() {
        val s = side()
        s.link.setInterestTags(listOf(tag))
        s.link.tick()
        s.link.setInterestTags(emptyList())
        step(s)
        assertTrue(net.server("wss://a").clients.single().filters.isEmpty())
    }

    @Test fun `events of other kinds and undecodable content are ignored`() {
        val s = side()
        s.link.setInterestTags(listOf(tag))
        s.link.tick()
        val sk = ByteArray(32) { 9 }
        val stranger = GatewayKeyHolder({ nowMs })
        val tags = listOf(listOf("t", "010203040506"))
        val client = net.server("wss://a").clients.single()
        for ((kind, content) in listOf(1 to "aGk=", NostrGatewayLink.LIVE_KIND to "***not base64***")) {
            val e = Nostr.signEvent(sk, nowSec, kind, tags, content)
            client.listener.onMessage("[\"EVENT\",\"2007\",${Nostr.eventJson(e)}]")
        }
        assertTrue(s.injected.isEmpty())
        assertNotEquals(0, stranger.current().size)
    }

    @Test fun `events are signed with the gateway key and it changes after a week`() {
        val keys = GatewayKeyHolder({ nowMs })
        val s = side(keys = keys)
        s.link.tick()
        s.link.offerFromBle(uplink(MeshFrameCodec.UPLINK_CLASS_LIVE, ByteArray(20) { 1 }))
        step(s)
        nowMs += GatewayKeyHolder.LIFETIME_MS
        s.link.offerFromBle(uplink(MeshFrameCodec.UPLINK_CLASS_LIVE, ByteArray(20) { 2 }, createdAtSec = nowSec))
        step(s)
        val pubs = net.server("wss://a").received.map { it.pubkey }
        assertEquals(2, pubs.size)
        assertFalse(pubs[0] == pubs[1])
    }
}
