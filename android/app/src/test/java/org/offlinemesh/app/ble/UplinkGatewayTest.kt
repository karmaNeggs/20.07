package org.offlinemesh.app.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UplinkGatewayTest {

    private var nowMs = 1_000_000_000_000L
    private val nowSec get() = nowMs / 1000
    private fun gateway(cfg: UplinkGateway.Config = UplinkGateway.Config()) = UplinkGateway({ nowMs }, cfg)

    private fun tag(n: Int) = byteArrayOf(n.toByte(), 2, 3, 4, 5, 6)
    private fun uplink(
        tag: ByteArray = tag(1),
        cls: Int = MeshFrameCodec.UPLINK_CLASS_TEXT,
        flags: Int = 0,
        createdAtSec: Long = nowSec,
        inner: ByteArray = ByteArray(300) { (it % 251).toByte() },
    ) = MeshFrameCodec.Frame.Uplink(tag, cls, flags, createdAtSec, inner)

    private fun decodeUplink(bytes: ByteArray) = MeshFrameCodec.decode(bytes) as MeshFrameCodec.Frame.Uplink

    // ---------- codec ----------

    @Test fun `uplink frame round-trips every field`() {
        val inner = ByteArray(1000) { it.toByte() }
        val cls = MeshFrameCodec.UPLINK_CLASS_POSITION_LAST_KNOWN
        val bytes = MeshFrameCodec.encodeUplink(tag(7), cls, 1, 123456789L, inner)
        val f = decodeUplink(bytes)
        assertArrayEquals(tag(7), f.relayTag)
        assertEquals(MeshFrameCodec.UPLINK_CLASS_POSITION_LAST_KNOWN, f.cls)
        assertEquals(1, f.flags)
        assertEquals(123456789L, f.createdAtSec)
        assertArrayEquals(inner, f.inner)
    }

    @Test fun `uplink uses the new byte and does not collide with a retired one`() {
        val bytes = MeshFrameCodec.encodeUplink(tag(1), 0, 0, 1L, ByteArray(4))
        assertEquals(0x20.toByte(), bytes[0])
        assertTrue(bytes[0] !in byteArrayOf(0x10, 0x14, 0x19, 0x1A, 0x1B))
    }

    @Test fun `truncated uplink decodes to null instead of throwing`() {
        val bytes = MeshFrameCodec.encodeUplink(tag(1), 0, 0, 1L, ByteArray(100))
        assertNull(MeshFrameCodec.decode(bytes.copyOf(bytes.size - 10)))
    }

    @Test fun `oversize inner or empty tag is refused at encode time`() {
        val tooBig = ByteArray(MeshFrameCodec.MAX_UPLINK_INNER_BYTES + 1)
        val big = runCatching { MeshFrameCodec.encodeUplink(tag(1), 0, 0, 1L, tooBig) }
        val empty = runCatching { MeshFrameCodec.encodeUplink(ByteArray(0), 0, 0, 1L, ByteArray(4)) }
        assertTrue(big.isFailure); assertTrue(empty.isFailure)
    }

    @Test fun `batch round-trips and survives garbage`() {
        val frames = listOf(ByteArray(10) { 1 }, ByteArray(0), ByteArray(3000) { 2 })
        val decoded = UplinkBatch.decode(UplinkBatch.encode(frames))
        assertEquals(3, decoded.size)
        assertArrayEquals(frames[2], decoded[2])
        assertTrue(UplinkBatch.decode(byteArrayOf(0x7F, 0x7F, 1, 2)).isEmpty())
    }

    // ---------- admission ----------

    @Test fun `fresh frame is accepted`() {
        assertEquals(UplinkGateway.Decision.Accepted(false), gateway().offer(uplink()))
    }

    @Test fun `frame already marked fromInternet is rejected so gateways never loop`() {
        val d = gateway().offer(uplink(flags = MeshFrameCodec.UPLINK_FLAG_FROM_INTERNET))
        assertEquals(UplinkGateway.Decision.Rejected(UplinkGateway.Reject.FROM_INTERNET), d)
    }

    @Test fun `bad class and bad tag are rejected`() {
        val g = gateway()
        assertEquals(UplinkGateway.Decision.Rejected(UplinkGateway.Reject.BAD_CLASS), g.offer(uplink(cls = 9)))
        assertEquals(UplinkGateway.Decision.Rejected(UplinkGateway.Reject.BAD_TAG), g.offer(uplink(tag = ByteArray(0))))
        val tooLong = g.offer(uplink(tag = ByteArray(40)))
        assertEquals(UplinkGateway.Decision.Rejected(UplinkGateway.Reject.BAD_TAG), tooLong)
    }

    @Test fun `each class expires on its own clock`() {
        val g = gateway()
        val live = uplink(cls = MeshFrameCodec.UPLINK_CLASS_LIVE, createdAtSec = nowSec - 300)
        val lastKnown = MeshFrameCodec.UPLINK_CLASS_POSITION_LAST_KNOWN
        val pos = uplink(cls = lastKnown, createdAtSec = nowSec - 3600)
        val posOld = uplink(cls = lastKnown, createdAtSec = nowSec - 7 * 3600)
        val text = uplink(cls = MeshFrameCodec.UPLINK_CLASS_TEXT, createdAtSec = nowSec - 3 * 24 * 3600)
        assertEquals(UplinkGateway.Decision.Rejected(UplinkGateway.Reject.STALE), g.offer(live))
        assertTrue(g.offer(pos) is UplinkGateway.Decision.Accepted)
        assertEquals(UplinkGateway.Decision.Rejected(UplinkGateway.Reject.STALE), g.offer(posOld))
        assertTrue(g.offer(text) is UplinkGateway.Decision.Accepted)
    }

    @Test fun `a timestamp far in the future is rejected`() {
        val d = gateway().offer(uplink(createdAtSec = nowSec + 3600))
        assertEquals(UplinkGateway.Decision.Rejected(UplinkGateway.Reject.FUTURE), d)
    }

    @Test fun `exact duplicate is rejected`() {
        val g = gateway()
        assertTrue(g.offer(uplink()) is UplinkGateway.Decision.Accepted)
        assertEquals(UplinkGateway.Decision.Rejected(UplinkGateway.Reject.DUPLICATE), g.offer(uplink()))
    }

    @Test fun `per-tag rate cap stops a flood on one tag but not other tags`() {
        val g = gateway(UplinkGateway.Config(framesPerTagPerMinute = 5))
        var rejected = 0
        for (i in 0 until 12) {
            val d = g.offer(uplink(inner = ByteArray(20) { i.toByte() }, createdAtSec = nowSec - i))
            if (d is UplinkGateway.Decision.Rejected && d.reason == UplinkGateway.Reject.TAG_RATE) rejected++
        }
        assertEquals(7, rejected)
        assertTrue(g.offer(uplink(tag = tag(2))) is UplinkGateway.Decision.Accepted)
    }

    @Test fun `unseen tag is still carried but a seen tag is marked priority`() {
        val g = gateway()
        g.noteBeaconSeen(tag(1))
        assertEquals(UplinkGateway.Decision.Accepted(true), g.offer(uplink(tag = tag(1))))
        assertEquals(UplinkGateway.Decision.Accepted(false), g.offer(uplink(tag = tag(2))))
    }

    // ---------- batching, budget, queues ----------

    @Test fun `drain returns one batch per tag and class, within the batch byte cap`() {
        val g = gateway(UplinkGateway.Config(maxBatchBytes = 1000))
        for (i in 0 until 10) g.offer(uplink(inner = ByteArray(200) { i.toByte() }, createdAtSec = nowSec - i))
        g.offer(uplink(tag = tag(2)))
        val batches = g.drain()
        assertTrue(batches.all { it.bytes <= 1000 })
        assertEquals(2, batches.size)
        assertEquals(setOf(1, 2), batches.map { it.relayTag[0].toInt() }.toSet())
        assertEquals(4, batches.first { it.relayTag[0].toInt() == 1 }.frames.size)
    }

    @Test fun `priority tags drain before others`() {
        val g = gateway()
        g.noteBeaconSeen(tag(2))
        g.offer(uplink(tag = tag(1)))
        g.offer(uplink(tag = tag(2), inner = ByteArray(50)))
        assertEquals(2, g.drain(maxBatches = 1).single().relayTag[0].toInt())
    }

    @Test fun `hourly budget caps what leaves and resets the next hour`() {
        val g = gateway(UplinkGateway.Config(hourlyBudgetBytes = 700, maxBatchBytes = 400))
        for (i in 0 until 6) g.offer(uplink(tag = tag(i + 1), inner = ByteArray(300) { i.toByte() }))
        val first = g.drain()
        assertTrue(first.sumOf { it.bytes } <= 700)
        assertTrue(g.pendingCount() > 0)
        assertTrue(g.drain().isEmpty())
        nowMs += 3_600_001
        assertTrue(g.drain().isNotEmpty())
    }

    @Test fun `last-known positions keep only the newest K per tag`() {
        val g = gateway(UplinkGateway.Config(positionKeepPerTag = 3))
        for (i in 0 until 8) {
            g.offer(
                uplink(
                    cls = MeshFrameCodec.UPLINK_CLASS_POSITION_LAST_KNOWN,
                    createdAtSec = nowSec - 100 + i * 10,
                    inner = ByteArray(40) { i.toByte() },
                ),
            )
        }
        val batch = g.drain().single()
        assertEquals(3, batch.frames.size)
        val newest = batch.frames.map { decodeUplink(it).inner[0].toInt() }.toSet()
        assertEquals(setOf(5, 6, 7), newest)
    }

    @Test fun `held frames expire while waiting and are not uploaded stale`() {
        val g = gateway()
        g.offer(uplink(cls = MeshFrameCodec.UPLINK_CLASS_POSITION_LAST_KNOWN))
        nowMs += 7L * 3600 * 1000
        assertTrue(g.drain().isEmpty())
        assertEquals(0, g.pendingCount())
    }

    @Test fun `failed publish is requeued and its budget refunded`() {
        val g = gateway(UplinkGateway.Config(hourlyBudgetBytes = 400))
        g.offer(uplink(inner = ByteArray(300)))
        val batch = g.drain().single()
        assertTrue(g.drain().isEmpty())
        g.requeue(batch)
        assertEquals(1, g.pendingCount())
        assertEquals(1, g.drain().single().frames.size)
    }

    // ---------- downlink ----------

    @Test fun `downlink frames are marked fromInternet and cannot be uplinked again`() {
        val remote = gateway()
        remote.offer(uplink())
        val content = remote.drain().single().content()
        val local = gateway()
        val injected = local.onRelayBatch(content).single()
        val f = decodeUplink(injected)
        assertTrue(f.flags and MeshFrameCodec.UPLINK_FLAG_FROM_INTERNET != 0)
        assertEquals(UplinkGateway.Decision.Rejected(UplinkGateway.Reject.FROM_INTERNET), local.offer(f))
    }

    @Test fun `own published frame echoing back from the relay is dropped`() {
        val g = gateway()
        g.offer(uplink())
        val content = g.drain().single().content()
        assertTrue(g.onRelayBatch(content).isEmpty())
    }

    @Test fun `same event arriving from several relays is injected once`() {
        val remote = gateway()
        remote.offer(uplink())
        val content = remote.drain().single().content()
        val local = gateway()
        assertEquals(1, local.onRelayBatch(content).size)
        assertEquals(0, local.onRelayBatch(content).size)
        assertEquals(0, local.onRelayBatch(content).size)
    }

    @Test fun `stale or malformed downlink content is dropped`() {
        val local = gateway()
        val live = MeshFrameCodec.UPLINK_CLASS_LIVE
        val stale = MeshFrameCodec.encodeUplink(tag(1), live, 0, nowSec - 1000, ByteArray(10))
        assertTrue(local.onRelayBatch(UplinkBatch.encode(listOf(stale))).isEmpty())
        assertTrue(local.onRelayBatch(byteArrayOf(9, 9, 9)).isEmpty())
        assertTrue(local.onRelayBatch(UplinkBatch.encode(listOf(ByteArray(30) { 0x55 }))).isEmpty())
    }

    // ---------- the scenarios the feature exists for ----------

    @Test fun `two clusters, one gateway each, a position crosses through a fake relay untouched`() {
        val sealedPosition = ByteArray(512) { (it * 7).toByte() } // stands in for a sealed PositionSealed frame
        val gatewayA = gateway()
        val gatewayB = gateway()
        val relay = ArrayList<ByteArray>() // a dumb relay: stores whatever it is given

        gatewayA.offer(uplink(tag = tag(9), cls = MeshFrameCodec.UPLINK_CLASS_LIVE, inner = sealedPosition))
        gatewayA.drain().forEach { relay.add(it.content()) }

        val injectedAtB = relay.flatMap { gatewayB.onRelayBatch(it) }.map { decodeUplink(it) }
        assertEquals(1, injectedAtB.size)
        assertArrayEquals(sealedPosition, injectedAtB.single().inner) // moved verbatim, never opened
        assertArrayEquals(tag(9), injectedAtB.single().relayTag)
        assertEquals(0, gatewayB.drain().size) // B did not re-upload what came from the internet
    }

    @Test fun `mule passes later - text and last-known position survive, a live position does not`() {
        val g = gateway()
        val t0 = nowSec
        // The sender wrapped three frames and walked away; a carrier phone held them and now meets the mule.
        val frames = listOf(
            uplink(cls = MeshFrameCodec.UPLINK_CLASS_TEXT, createdAtSec = t0, inner = ByteArray(100) { 1 }),
            uplink(
                cls = MeshFrameCodec.UPLINK_CLASS_POSITION_LAST_KNOWN,
                createdAtSec = t0,
                inner = ByteArray(100) { 2 },
            ),
            uplink(cls = MeshFrameCodec.UPLINK_CLASS_LIVE, createdAtSec = t0, inner = ByteArray(100) { 3 }),
        )
        nowMs += 30L * 60 * 1000
        val decisions = frames.map { g.offer(it) }
        assertTrue(decisions[0] is UplinkGateway.Decision.Accepted)
        assertTrue(decisions[1] is UplinkGateway.Decision.Accepted)
        assertEquals(UplinkGateway.Decision.Rejected(UplinkGateway.Reject.STALE), decisions[2])
        assertEquals(2, g.drain().size)
    }

    @Test fun `junk on random tags cannot exceed the hourly budget`() {
        val g = gateway(UplinkGateway.Config(hourlyBudgetBytes = 50_000))
        for (i in 0 until 2000) {
            val randomTag = byteArrayOf((i shr 8).toByte(), i.toByte(), 1, 2, 3, 4)
            g.offer(uplink(tag = randomTag, inner = ByteArray(1000) { (i % 200).toByte() }))
        }
        assertTrue(g.drain().sumOf { it.bytes } <= 50_000)
        assertNotNull(g)
        assertFalse(g.pendingCount() == 0)
    }
}
