@file:Suppress("MaxLineLength", "EmptyFunctionBlock") // compact test fixtures and no-op fake overrides

package org.offlinemesh.app.gateway

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.offlinemesh.app.ble.MeshFrameCodec
import org.offlinemesh.app.ble.UplinkGateway

class FileUplinkTest {
    private var nowMs = 1_800_000_000_000L
    private val key = ByteArray(32) { 3 }

    private class Src(val key: ByteArray, val file: FileUplink?) : UplinkSource {
        val alerts = ArrayList<MailboxItem>()
        override suspend fun groups() = listOf(UplinkGroup("g", key))
        override suspend fun liveFrames(groupId: String) = emptyList<ByteArray>()
        override suspend fun mailboxItems(groupId: String) = alerts.toList()
        override suspend fun lastKnownFrames(groupId: String) = emptyList<ByteArray>()
        override suspend fun fileUplinks(groupId: String) = listOfNotNull(file)
    }

    private class Link : UplinkLink {
        val offered = ArrayList<MeshFrameCodec.Frame.Uplink>()
        var connected = true
        var congestedNow = false
        override fun offerLocal(frame: MeshFrameCodec.Frame.Uplink): UplinkGateway.Decision {
            offered.add(frame); return UplinkGateway.Decision.Accepted(false)
        }
        override fun setInterestTags(tags: Collection<ByteArray>) {}
        override fun tick() {}
        override fun close() {}
        override fun hasConnectedRelay() = connected
        override fun congested() = congestedNow
    }

    private var symbolCounter = 0

    // Distinct bytes per symbol, like real fountain symbols (they differ by index); identical ones would be de-duplicated.
    private fun file(wanted: Int, served: MutableList<Int>) =
        FileUplink("f1", ByteArray(50) { 1 }, wanted) { n ->
            served.add(n)
            List(n) { val i = symbolCounter++; ByteArray(30) { (i + it).toByte() }.also { b -> b[0] = i.toByte(); b[1] = (i shr 8).toByte() } }
        }

    private fun run(c: InternetReachController, seconds: Int) = repeat(seconds) { nowMs += 1000; runBlocking { c.step() } }

    @Test fun `header goes first, then paced symbol batches, then nothing more`() {
        val served = ArrayList<Int>()
        val link = Link()
        val c = InternetReachController(Src(key, file(150, served)), { link }, { _, _ -> }, { nowMs }, bulkAllowed = { true })
        c.start(); run(c, 40)
        val classes = link.offered.map { it.cls }
        assertEquals(MeshFrameCodec.UPLINK_CLASS_FILE_META, classes.first())
        assertEquals(150, classes.count { it == MeshFrameCodec.UPLINK_CLASS_FILE_SYMBOLS })
        assertEquals(listOf(60, 60, 30), served)
    }

    @Test fun `no file traffic on a metered network or while congested`() {
        val served = ArrayList<Int>()
        val link = Link()
        var allowed = false
        val c = InternetReachController(Src(key, file(100, served)), { link }, { _, _ -> }, { nowMs }, bulkAllowed = { allowed })
        c.start(); run(c, 20)
        assertTrue(link.offered.isEmpty())
        allowed = true; link.congestedNow = true; run(c, 20)
        assertTrue(link.offered.isEmpty())
        link.congestedNow = false; run(c, 20)
        assertTrue(link.offered.isNotEmpty())
    }

    @Test fun `an SOS alert is wrapped as the alert class and a normal message as text`() {
        val link = Link(); val src = Src(key, null)
        src.alerts.add(MailboxItem("sos:a", ByteArray(40) { 1 }, alert = true))
        src.alerts.add(MailboxItem("sos:b", ByteArray(40) { 2 }, alert = false))
        val c = InternetReachController(src, { link }, { _, _ -> }, { nowMs })
        c.start(); run(c, 10)
        assertEquals(setOf(MeshFrameCodec.UPLINK_CLASS_ALERT, MeshFrameCodec.UPLINK_CLASS_TEXT), link.offered.map { it.cls }.toSet())
    }

    @Test fun `live frames slow down threefold while congested`() {
        val link = Link(); link.congestedNow = true
        val src = object : UplinkSource by Src(key, null) {
            override suspend fun liveFrames(groupId: String) = listOf(ByteArray(20))
        }
        val c = InternetReachController(src, { link }, { _, _ -> }, { nowMs })
        c.start(); run(c, 60)
        val live = link.offered.count { it.cls == MeshFrameCodec.UPLINK_CLASS_LIVE }
        assertTrue("live=$live", live in 2..3)
    }

    @Test fun `a file crosses two phones through relays and every symbol arrives`() {
        val net = FakeRelayNetwork()
        val inbound = ArrayList<Pair<Int, ByteArray>>()
        val sender = InternetReachController(
            Src(key, file(70, ArrayList())),
            { inj -> NostrGatewayLink(UplinkGateway({ nowMs }), listOf("wss://a", "wss://b"), net.connector(), GatewayKeyHolder({ nowMs }), { nowMs }, inj) },
            { _, _ -> }, { nowMs }, bulkAllowed = { true },
        )
        val receiver = InternetReachController(
            Src(key, null),
            { inj -> NostrGatewayLink(UplinkGateway({ nowMs }), listOf("wss://a", "wss://b"), net.connector(), GatewayKeyHolder({ nowMs }), { nowMs }, inj) },
            { cls, inner -> inbound.add(cls to inner) }, { nowMs }, bulkAllowed = { true },
        )
        sender.start(); receiver.start()
        repeat(60) { nowMs += 1000; runBlocking { sender.step(); receiver.step() } }
        assertEquals(1, inbound.count { it.first == MeshFrameCodec.UPLINK_CLASS_FILE_META })
        val got = inbound.count { it.first == MeshFrameCodec.UPLINK_CLASS_FILE_SYMBOLS }
        assertEquals(70, got)
    }
}
