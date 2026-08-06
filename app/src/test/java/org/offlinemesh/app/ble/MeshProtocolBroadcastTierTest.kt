package org.offlinemesh.app.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tier 1: [MeshProtocol.encodeBroadcastTierBeacon]/[MeshProtocol.decodeBroadcastTierBeacon] — the
 * Tier B (PLAN-v2.md §5.1) wire format, decision 26 (`docs/DECISIONS.md`). Round-trip and malformed-
 * input coverage, same discipline as every other frame codec in this app even though this one is
 * new and small — a beacon payload is attacker-reachable (any nearby radio, no auth gate at the
 * decode layer) so decode() must never throw on truncated/garbage bytes.
 */
class MeshProtocolBroadcastTierTest {

    private val rid = ByteArray(MeshProtocol.ROTATING_ID_LEN) { (it + 1).toByte() }

    @Test
    fun `round-trips type, rotating id, sos hop, and presence hop`() {
        val encoded =
            MeshProtocol.encodeBroadcastTierBeacon(MeshProtocol.ADV_TYPE_GROUP, rid, sosHop = 3, presenceHop = 2)
        val decoded = MeshProtocol.decodeBroadcastTierBeacon(encoded)

        assertEquals(MeshProtocol.ADV_TYPE_GROUP, decoded?.type)
        assertArrayEquals(rid, decoded?.rotatingGroupId)
        assertEquals(3, decoded?.sosHop)
        assertEquals(2, decoded?.presenceHop)
    }

    @Test
    fun `is one byte larger than the legacy beacon - exactly the new presence hop field`() {
        val legacy = MeshProtocol.encodeBeacon(MeshProtocol.ADV_TYPE_GROUP, rid, sosHop = 1)
        val tierB =
            MeshProtocol.encodeBroadcastTierBeacon(MeshProtocol.ADV_TYPE_GROUP, rid, sosHop = 1, presenceHop = 0)
        assertEquals(legacy.size + 1, tierB.size)
    }

    @Test
    fun `UNKNOWN_HOP round-trips for presence hop, same as sos hop already does`() {
        val encoded = MeshProtocol.encodeBroadcastTierBeacon(
            MeshProtocol.ADV_TYPE_GROUP, rid, sosHop = MeshProtocol.UNKNOWN_HOP, presenceHop = MeshProtocol.UNKNOWN_HOP,
        )
        val decoded = MeshProtocol.decodeBroadcastTierBeacon(encoded)
        assertEquals(MeshProtocol.UNKNOWN_HOP, decoded?.sosHop)
        assertEquals(MeshProtocol.UNKNOWN_HOP, decoded?.presenceHop)
    }

    @Test
    fun `hop values coerce into an unsigned byte rather than wrapping negative`() {
        val encoded =
            MeshProtocol.encodeBroadcastTierBeacon(MeshProtocol.ADV_TYPE_GROUP, rid, sosHop = -5, presenceHop = 999)
        val decoded = MeshProtocol.decodeBroadcastTierBeacon(encoded)
        assertEquals(0, decoded?.sosHop)
        assertEquals(255, decoded?.presenceHop)
    }

    @Test
    fun `decode rejects truncated bytes rather than throwing`() {
        val encoded =
            MeshProtocol.encodeBroadcastTierBeacon(MeshProtocol.ADV_TYPE_GROUP, rid, sosHop = 1, presenceHop = 1)
        assertNull(MeshProtocol.decodeBroadcastTierBeacon(encoded.copyOf(encoded.size - 1)))
        assertNull(MeshProtocol.decodeBroadcastTierBeacon(ByteArray(0)))
    }

    @Test
    fun `decode does not silently truncate the legacy beacon's shorter payload into a valid result`() {
        // A legacy 8-byte beacon must NOT decode as a valid (if garbage) Tier B beacon just because
        // the first 8 bytes happen to line up — the two formats are a byte apart on purpose (see
        // encodeBroadcastTierBeacon's own doc) and must not be cross-parseable.
        val legacy = MeshProtocol.encodeBeacon(MeshProtocol.ADV_TYPE_GROUP, rid, sosHop = 1)
        assertNull(MeshProtocol.decodeBroadcastTierBeacon(legacy))
    }

    @Test
    fun `no positionFrame given - decodes with a null positionFrame, not an empty array`() {
        val encoded =
            MeshProtocol.encodeBroadcastTierBeacon(MeshProtocol.ADV_TYPE_GROUP, rid, sosHop = 0, presenceHop = 0)
        assertNull(MeshProtocol.decodeBroadcastTierBeacon(encoded)?.positionFrame)
    }

    @Test
    fun `positionFrame round-trips verbatim alongside the header fields`() {
        val fakeFrame = ByteArray(140) { (it * 7).toByte() } // stand-in for a real encodePosition() output
        val encoded = MeshProtocol.encodeBroadcastTierBeacon(
            MeshProtocol.ADV_TYPE_GROUP, rid, sosHop = 2, presenceHop = 1, positionFrame = fakeFrame,
        )
        val decoded = MeshProtocol.decodeBroadcastTierBeacon(encoded)

        assertEquals(2, decoded?.sosHop)
        assertEquals(1, decoded?.presenceHop)
        assertArrayEquals(fakeFrame, decoded?.positionFrame)
    }

    @Test
    fun `positionFrame past the size ceiling is silently omitted, not truncated into corrupt ciphertext`() {
        val oversized = ByteArray(MeshProtocol.MAX_BROADCAST_TIER_POSITION_FRAME_BYTES + 1)
        val encoded = MeshProtocol.encodeBroadcastTierBeacon(
            MeshProtocol.ADV_TYPE_GROUP, rid, sosHop = 0, presenceHop = 0, positionFrame = oversized,
        )
        // Omitted entirely (not truncated) - a truncated AES-GCM ciphertext would still "decode" as
        // bytes but could never open, silently wasting airtime on undecryptable garbage.
        assertNull(MeshProtocol.decodeBroadcastTierBeacon(encoded)?.positionFrame)
        val legacyOnlyBeacon =
            MeshProtocol.encodeBroadcastTierBeacon(MeshProtocol.ADV_TYPE_GROUP, rid, sosHop = 0, presenceHop = 0)
        assertEquals(legacyOnlyBeacon.size, encoded.size)
    }

    @Test
    fun `decode rejects a positionFrame length prefix that overruns the actual bytes`() {
        val fakeFrame = ByteArray(20)
        val encoded = MeshProtocol.encodeBroadcastTierBeacon(
            MeshProtocol.ADV_TYPE_GROUP, rid, sosHop = 0, presenceHop = 0, positionFrame = fakeFrame,
        )
        // Truncate after the length prefix claims 20 bytes are coming, but only leave 5 - a hostile
        // or corrupted beacon must be rejected outright, not read out-of-bounds or read short.
        assertNull(MeshProtocol.decodeBroadcastTierBeacon(encoded.copyOf(encoded.size - 15)))
    }
}
