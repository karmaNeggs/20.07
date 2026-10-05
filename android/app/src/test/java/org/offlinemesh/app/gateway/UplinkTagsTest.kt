package org.offlinemesh.app.gateway

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.offlinemesh.app.ble.MeshFrameCodec

class UplinkTagsTest {
    private val keyA = ByteArray(32) { 1 }
    private val keyB = ByteArray(32) { 2 }
    private val t0 = 1_800_000_000L

    @Test fun `tags are deterministic and stay constant inside their window`() {
        val base = t0 - t0 % 60
        assertArrayEquals(UplinkTags.liveTag(keyA, base), UplinkTags.liveTag(keyA, base + 59))
        assertFalse(UplinkTags.liveTag(keyA, base).contentEquals(UplinkTags.liveTag(keyA, base + 60)))
        val hour = t0 - t0 % 3600
        assertArrayEquals(UplinkTags.mailboxTag(keyA, hour), UplinkTags.mailboxTag(keyA, hour + 3599))
        assertFalse(UplinkTags.mailboxTag(keyA, hour).contentEquals(UplinkTags.mailboxTag(keyA, hour + 3600)))
    }

    @Test fun `different groups and different purposes never share a tag`() {
        assertFalse(UplinkTags.liveTag(keyA, t0).contentEquals(UplinkTags.liveTag(keyB, t0)))
        assertFalse(UplinkTags.liveTag(keyA, t0).contentEquals(UplinkTags.mailboxTag(keyA, t0)))
    }

    @Test fun `live tag equals the tag the BLE beacon advertises`() {
        val beacon = org.offlinemesh.app.crypto.CryptoUtils.rotatingAdvertisementId(keyA, t0)
        assertArrayEquals(beacon, UplinkTags.liveTag(keyA, t0))
    }

    @Test fun `tag choice follows the class`() {
        assertArrayEquals(UplinkTags.liveTag(keyA, t0), UplinkTags.tagFor(keyA, MeshFrameCodec.UPLINK_CLASS_LIVE, t0))
        val text = UplinkTags.tagFor(keyA, MeshFrameCodec.UPLINK_CLASS_TEXT, t0)
        assertArrayEquals(UplinkTags.mailboxTag(keyA, t0), text)
        assertArrayEquals(
            UplinkTags.mailboxTag(keyA, t0),
            UplinkTags.tagFor(keyA, MeshFrameCodec.UPLINK_CLASS_POSITION_LAST_KNOWN, t0),
        )
    }

    @Test fun `interest covers this and the previous live window and a day of mailbox hours`() {
        val tags = UplinkTags.interestTags(keyA, t0)
        assertEquals(2 + UplinkTags.MAILBOX_LOOKBACK_HOURS + 1, tags.size)
        assertEquals(tags.size, tags.map { it.toHex() }.distinct().size)
        val set = tags.map { it.toHex() }.toSet()
        assertTrue(UplinkTags.liveTag(keyA, t0).toHex() in set)
        assertTrue(UplinkTags.liveTag(keyA, t0 - 60).toHex() in set)
        assertTrue(UplinkTags.mailboxTag(keyA, t0 - 23 * 3600).toHex() in set)
    }

    @Test fun `a frame published now is listened for by a receiver one window later`() {
        val sent = UplinkTags.liveTag(keyA, t0).toHex()
        val receiverInterest = UplinkTags.interestTags(keyA, t0 + 60).map { it.toHex() }
        assertTrue(sent in receiverInterest)
    }

    @Test fun `wrap and unwrap round-trip the sealed frame untouched`() {
        val inner = ByteArray(400) { (it * 5).toByte() }
        val wrapped = UplinkWrapper.wrap(keyA, MeshFrameCodec.UPLINK_CLASS_TEXT, inner, t0)
        assertArrayEquals(UplinkTags.mailboxTag(keyA, t0), wrapped.relayTag)
        assertEquals(t0, wrapped.createdAtSec)
        assertEquals(0, wrapped.flags)
        val encoded = MeshFrameCodec.encodeUplink(
            wrapped.relayTag, wrapped.cls, wrapped.flags, wrapped.createdAtSec, wrapped.inner,
        )
        assertArrayEquals(inner, UplinkWrapper.unwrap(encoded))
    }

    @Test fun `unwrap rejects anything that is not an uplink`() {
        assertNull(UplinkWrapper.unwrap(ByteArray(10)))
        assertNull(UplinkWrapper.unwrap(MeshFrameCodec.encodeL2capCap(5)))
        assertNotNull(UplinkWrapper.unwrap(MeshFrameCodec.encodeUplink(ByteArray(6), 0, 0, 1, ByteArray(3))))
    }
}
