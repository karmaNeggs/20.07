package org.offlinemesh.app.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tier 1: [MeshProtocol.encodeBroadcastTierBeacon]/[MeshProtocol.decodeBroadcastTierBeacon] — the
 * Tier B (PLAN-v2.md §5.1) wire format, decisions 26-28 (`docs/DECISIONS.md`). Round-trip and
 * malformed-input coverage, same discipline as every other frame codec in this app even though this
 * one is new and small — a beacon payload is attacker-reachable (any nearby radio, no auth gate at
 * the decode layer) so decode() must never throw on truncated/garbage bytes.
 *
 * Rewritten for decision 28: [MeshProtocol.encodeBeacon]'s `sosHop` field was dropped from this
 * format (confirmed dead — `grep`'d, never read by any receiver, legacy or Tier B) and replaced by
 * an [MeshProtocol.SosAlert] block keyed on the real SOS id, not a rough aggregate. Both the
 * position and SOS blocks are now ALWAYS length-prefixed in the encoded output (zero length means
 * absent), which is what lets two independently-optional variable-length fields coexist.
 */
class MeshProtocolBroadcastTierTest {

    private val rid = ByteArray(MeshProtocol.ROTATING_ID_LEN) { (it + 1).toByte() }

    @Test
    fun `round-trips type, rotating id, and presence hop with no optional blocks`() {
        val encoded = MeshProtocol.encodeBroadcastTierBeacon(MeshProtocol.ADV_TYPE_GROUP, rid, presenceHop = 2)
        val decoded = MeshProtocol.decodeBroadcastTierBeacon(encoded)

        assertEquals(MeshProtocol.ADV_TYPE_GROUP, decoded?.type)
        assertArrayEquals(rid, decoded?.rotatingGroupId)
        assertEquals(2, decoded?.presenceHop)
        assertNull(decoded?.positionFrame)
        assertNull(decoded?.activeSos)
    }

    @Test
    fun `UNKNOWN_HOP round-trips for presence hop`() {
        val encoded = MeshProtocol.encodeBroadcastTierBeacon(
            MeshProtocol.ADV_TYPE_GROUP, rid, presenceHop = MeshProtocol.UNKNOWN_HOP,
        )
        assertEquals(MeshProtocol.UNKNOWN_HOP, MeshProtocol.decodeBroadcastTierBeacon(encoded)?.presenceHop)
    }

    @Test
    fun `presence hop coerces into an unsigned byte rather than wrapping negative`() {
        val negative = MeshProtocol.encodeBroadcastTierBeacon(MeshProtocol.ADV_TYPE_GROUP, rid, presenceHop = -5)
        assertEquals(0, MeshProtocol.decodeBroadcastTierBeacon(negative)?.presenceHop)
        val over = MeshProtocol.encodeBroadcastTierBeacon(MeshProtocol.ADV_TYPE_GROUP, rid, presenceHop = 999)
        assertEquals(255, MeshProtocol.decodeBroadcastTierBeacon(over)?.presenceHop)
    }

    @Test
    fun `decode rejects truncated bytes rather than throwing`() {
        val encoded = MeshProtocol.encodeBroadcastTierBeacon(MeshProtocol.ADV_TYPE_GROUP, rid, presenceHop = 1)
        assertNull(MeshProtocol.decodeBroadcastTierBeacon(encoded.copyOf(encoded.size - 1)))
        assertNull(MeshProtocol.decodeBroadcastTierBeacon(ByteArray(0)))
    }

    @Test
    fun `decode does not silently accept the legacy beacon's shorter payload as valid`() {
        // A legacy 8-byte beacon must NOT decode as a valid (if garbage) Tier B beacon just because
        // some prefix bytes happen to line up — the formats are unrelated on purpose and must not
        // be cross-parseable.
        val legacy = MeshProtocol.encodeBeacon(MeshProtocol.ADV_TYPE_GROUP, rid, sosHop = 1)
        assertNull(MeshProtocol.decodeBroadcastTierBeacon(legacy))
    }

    @Test
    fun `positionFrame round-trips verbatim alongside the header fields`() {
        val fakeFrame = ByteArray(140) { (it * 7).toByte() } // stand-in for a real encodePosition() output
        val encoded = MeshProtocol.encodeBroadcastTierBeacon(
            MeshProtocol.ADV_TYPE_GROUP, rid, presenceHop = 1, positionFrame = fakeFrame,
        )
        val decoded = MeshProtocol.decodeBroadcastTierBeacon(encoded)

        assertEquals(1, decoded?.presenceHop)
        assertArrayEquals(fakeFrame, decoded?.positionFrame)
        assertNull(decoded?.activeSos)
    }

    @Test
    fun `positionFrame past the size ceiling is silently omitted, not truncated into corrupt ciphertext`() {
        val oversized = ByteArray(MeshProtocol.MAX_BROADCAST_TIER_POSITION_FRAME_BYTES + 1)
        val encoded = MeshProtocol.encodeBroadcastTierBeacon(
            MeshProtocol.ADV_TYPE_GROUP, rid, presenceHop = 0, positionFrame = oversized,
        )
        // Omitted entirely (not truncated) - a truncated AES-GCM ciphertext would still "decode" as
        // bytes but could never open, silently wasting airtime on undecryptable garbage.
        assertNull(MeshProtocol.decodeBroadcastTierBeacon(encoded)?.positionFrame)
        val noOptionalBlocks = MeshProtocol.encodeBroadcastTierBeacon(MeshProtocol.ADV_TYPE_GROUP, rid, presenceHop = 0)
        assertEquals(noOptionalBlocks.size, encoded.size)
    }

    @Test
    fun `decode rejects a positionFrame length prefix that overruns the actual bytes`() {
        val fakeFrame = ByteArray(20)
        val encoded = MeshProtocol.encodeBroadcastTierBeacon(
            MeshProtocol.ADV_TYPE_GROUP, rid, presenceHop = 0, positionFrame = fakeFrame,
        )
        // Truncate after the length prefix claims 20 bytes are coming, but only leave 5 - a hostile
        // or corrupted beacon must be rejected outright, not read out-of-bounds or read short.
        assertNull(MeshProtocol.decodeBroadcastTierBeacon(encoded.copyOf(encoded.size - 15)))
    }

    @Test
    fun `activeSos round-trips id and hop independently of position`() {
        val encoded = MeshProtocol.encodeBroadcastTierBeacon(
            MeshProtocol.ADV_TYPE_GROUP, rid, presenceHop = 0,
            activeSos = MeshProtocol.SosAlert(id = "sos-1234", hop = 2),
        )
        val decoded = MeshProtocol.decodeBroadcastTierBeacon(encoded)

        assertNull(decoded?.positionFrame)
        assertEquals("sos-1234", decoded?.activeSos?.id)
        assertEquals(2, decoded?.activeSos?.hop)
    }

    @Test
    fun `position and activeSos coexist in the same beacon without interfering`() {
        val fakeFrame = ByteArray(150) { it.toByte() }
        val encoded = MeshProtocol.encodeBroadcastTierBeacon(
            MeshProtocol.ADV_TYPE_GROUP, rid, presenceHop = 3, positionFrame = fakeFrame,
            activeSos = MeshProtocol.SosAlert(id = "sos-real-id", hop = 0),
        )
        val decoded = MeshProtocol.decodeBroadcastTierBeacon(encoded)

        assertEquals(3, decoded?.presenceHop)
        assertArrayEquals(fakeFrame, decoded?.positionFrame)
        assertEquals("sos-real-id", decoded?.activeSos?.id)
        assertEquals(0, decoded?.activeSos?.hop)
    }

    @Test
    fun `a real UUID-length sos id round-trips`() {
        val uuidLikeId = "550e8400-e29b-41d4-a716-446655440000" // 36 chars, matches UUID.randomUUID().toString()
        val encoded = MeshProtocol.encodeBroadcastTierBeacon(
            MeshProtocol.ADV_TYPE_GROUP, rid, presenceHop = 0, activeSos = MeshProtocol.SosAlert(uuidLikeId, 1),
        )
        assertEquals(uuidLikeId, MeshProtocol.decodeBroadcastTierBeacon(encoded)?.activeSos?.id)
    }

    @Test
    fun `activeSos id past the size ceiling is silently omitted, not truncated into a wrong id`() {
        val oversizedId = "x".repeat(MeshProtocol.MAX_BROADCAST_TIER_SOS_ID_BYTES + 1)
        val encoded = MeshProtocol.encodeBroadcastTierBeacon(
            MeshProtocol.ADV_TYPE_GROUP, rid, presenceHop = 0, activeSos = MeshProtocol.SosAlert(oversizedId, 1),
        )
        // Omitted entirely, not truncated - a truncated id could collide with, or fail to match, the
        // real SosEntity.id it was supposed to name, silently corrupting hop tracking for the wrong key.
        assertNull(MeshProtocol.decodeBroadcastTierBeacon(encoded)?.activeSos)
    }

    @Test
    fun `sos hop coerces into an unsigned byte rather than wrapping negative`() {
        val encoded = MeshProtocol.encodeBroadcastTierBeacon(
            MeshProtocol.ADV_TYPE_GROUP, rid, presenceHop = 0, activeSos = MeshProtocol.SosAlert("sos-1", 999),
        )
        assertEquals(255, MeshProtocol.decodeBroadcastTierBeacon(encoded)?.activeSos?.hop)
    }

    @Test
    fun `decode rejects a sos id length prefix that overruns the actual bytes`() {
        val encoded = MeshProtocol.encodeBroadcastTierBeacon(
            MeshProtocol.ADV_TYPE_GROUP, rid, presenceHop = 0, activeSos = MeshProtocol.SosAlert("sos-1234", 1),
        )
        // Truncate away the trailing hop byte and part of the id - the claimed length prefix now
        // overruns what's actually left, which must be rejected, not read short or out-of-bounds.
        assertNull(MeshProtocol.decodeBroadcastTierBeacon(encoded.copyOf(encoded.size - 5)))
    }

    @Test
    fun `worst-case position-plus-hop-gradient payload (no content) stays within the 251B advertising budget`() {
        val maxPosition = ByteArray(MeshProtocol.MAX_BROADCAST_TIER_POSITION_FRAME_BYTES)
        val maxSosId = "x".repeat(MeshProtocol.MAX_BROADCAST_TIER_SOS_ID_BYTES)
        val encoded = MeshProtocol.encodeBroadcastTierBeacon(
            MeshProtocol.ADV_TYPE_GROUP, rid, presenceHop = 0, positionFrame = maxPosition,
            activeSos = MeshProtocol.SosAlert(maxSosId, 1),
        )
        // BLE extended advertising's in-place-update budget - see encodeBroadcastTierBeacon's own
        // doc for the worst-case arithmetic this is checking directly rather than assuming.
        val extendedAdvertisingInPlaceUpdateBudget = 251
        assertEquals(242, encoded.size)
        assert(encoded.size <= extendedAdvertisingInPlaceUpdateBudget) {
            "worst-case Tier B beacon (${encoded.size}B) must fit the ~251B in-place-update budget"
        }
    }

    @Test
    fun `activeSos content (message, timestamp, mac) round-trips`() {
        val mac = ByteArray(32) { it.toByte() } // stand-in for a real CryptoUtils.authTag output
        val content = MeshProtocol.SosAlert.Content(message = "help, medical emergency", timestamp = 123L, mac = mac)
        val encoded = MeshProtocol.encodeBroadcastTierBeacon(
            MeshProtocol.ADV_TYPE_GROUP, rid, presenceHop = 0,
            activeSos = MeshProtocol.SosAlert(id = "sos-1", hop = 0, content = content),
        )
        val decoded = MeshProtocol.decodeBroadcastTierBeacon(encoded)?.activeSos?.content

        assertEquals("help, medical emergency", decoded?.message)
        assertEquals(123L, decoded?.timestamp)
        assertArrayEquals(mac, decoded?.mac)
    }

    @Test
    fun `activeSos with no content still round-trips id and hop, content is null not a zero-length one`() {
        val encoded = MeshProtocol.encodeBroadcastTierBeacon(
            MeshProtocol.ADV_TYPE_GROUP, rid, presenceHop = 0,
            activeSos = MeshProtocol.SosAlert(id = "sos-1", hop = 2),
        )
        val decoded = MeshProtocol.decodeBroadcastTierBeacon(encoded)?.activeSos

        assertEquals("sos-1", decoded?.id)
        assertEquals(2, decoded?.hop)
        assertNull(decoded?.content)
    }

    @Test
    fun `content is only ever attached alongside a sos id - encoder cannot produce content without one`() {
        // SosAlert.Content isn't reachable without going through SosAlert itself, so this is really
        // documenting the invariant decodeBroadcastTierBeacon's own malformed-input guard enforces
        // on the wire (a content block with no sosId is rejected outright, see the length-overrun
        // test above's sibling coverage) - included here for symmetry with the encode-side shape.
        val encoded = MeshProtocol.encodeBroadcastTierBeacon(MeshProtocol.ADV_TYPE_GROUP, rid, presenceHop = 0)
        assertNull(MeshProtocol.decodeBroadcastTierBeacon(encoded)?.activeSos)
    }

    @Test
    fun `content message past the size ceiling is silently omitted, not truncated into an unverifiable mac`() {
        val oversizedMessage = "x".repeat(MeshProtocol.MAX_BROADCAST_TIER_SOS_MESSAGE_BYTES + 1)
        val content = MeshProtocol.SosAlert.Content(oversizedMessage, timestamp = 1L, mac = ByteArray(32))
        val encoded = MeshProtocol.encodeBroadcastTierBeacon(
            MeshProtocol.ADV_TYPE_GROUP, rid, presenceHop = 0,
            activeSos = MeshProtocol.SosAlert(id = "sos-1", hop = 0, content = content),
        )
        val decoded = MeshProtocol.decodeBroadcastTierBeacon(encoded)
        // Content omitted, but the hop-gradient alert itself still goes out - a message that's too
        // long to preview must never suppress the (always-valuable, always-small) hop-gradient too.
        assertNull(decoded?.activeSos?.content)
        assertEquals("sos-1", decoded?.activeSos?.id)
    }

    @Test
    fun `content with a mac that isn't exactly 32 bytes is silently omitted`() {
        val content = MeshProtocol.SosAlert.Content("help", timestamp = 1L, mac = ByteArray(16)) // wrong length
        val encoded = MeshProtocol.encodeBroadcastTierBeacon(
            MeshProtocol.ADV_TYPE_GROUP, rid, presenceHop = 0,
            activeSos = MeshProtocol.SosAlert(id = "sos-1", hop = 0, content = content),
        )
        assertNull(MeshProtocol.decodeBroadcastTierBeacon(encoded)?.activeSos?.content)
    }

    @Test
    fun `decode rejects a content block whose message length prefix overruns the actual bytes`() {
        val content =
            MeshProtocol.SosAlert.Content("a reasonably long help message", timestamp = 1L, mac = ByteArray(32))
        val encoded = MeshProtocol.encodeBroadcastTierBeacon(
            MeshProtocol.ADV_TYPE_GROUP, rid, presenceHop = 0,
            activeSos = MeshProtocol.SosAlert(id = "sos-1", hop = 0, content = content),
        )
        // Truncate away the mac and part of the message - must be rejected outright.
        assertNull(MeshProtocol.decodeBroadcastTierBeacon(encoded.copyOf(encoded.size - 20)))
    }

    @Test
    fun `worst-case payload with content but no position (BeaconRadio's own priority trade) stays within budget`() {
        val maxSosId = "x".repeat(MeshProtocol.MAX_BROADCAST_TIER_SOS_ID_BYTES)
        val maxMessage = "x".repeat(MeshProtocol.MAX_BROADCAST_TIER_SOS_MESSAGE_BYTES)
        val content = MeshProtocol.SosAlert.Content(maxMessage, timestamp = Long.MAX_VALUE, mac = ByteArray(32))
        val encoded = MeshProtocol.encodeBroadcastTierBeacon(
            MeshProtocol.ADV_TYPE_GROUP, rid, presenceHop = 0, positionFrame = null,
            activeSos = MeshProtocol.SosAlert(maxSosId, 1, content),
        )
        val extendedAdvertisingInPlaceUpdateBudget = 251
        assertEquals(222, encoded.size)
        assert(encoded.size <= extendedAdvertisingInPlaceUpdateBudget) {
            "worst-case content-bearing Tier B beacon (${encoded.size}B) must fit the ~251B budget"
        }
    }
}
