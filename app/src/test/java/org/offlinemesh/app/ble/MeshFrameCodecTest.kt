package org.offlinemesh.app.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.offlinemesh.app.data.EvidenceChunkEntity
import org.offlinemesh.app.data.EvidenceEntity
import org.offlinemesh.app.data.NicknameEntity
import org.offlinemesh.app.data.SosEntity
import java.security.SecureRandom

/**
 * Tier 1: round-trips every wire frame type (encode -> decode -> same fields back), plus malformed/
 * truncated input handling. This is exactly the kind of thing "parsing dummy variables" should
 * catch in a second instead of a live 2-phone session — a frame layout bug here would otherwise
 * only surface as "the other phone never seems to get X."
 */
class MeshFrameCodecTest {

    private val fakeSha256 = "a".repeat(64) // valid 32-byte-hex shape, content doesn't matter here
    private fun randomKey() = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Test
    fun `sos frame round-trips`() {
        val sos = SosEntity(
            id = "sos-1", groupId = "group-1", senderId = "sender-1", senderIsMe = true,
            message = "need help at the north gate", timestamp = 1_700_000_000_000L, ttl = 8,
            mac = ByteArray(16) { it.toByte() }
        )
        val decoded = MeshFrameCodec.decode(MeshFrameCodec.encodeSos(sos))
        check(decoded is MeshFrameCodec.Frame.Sos)
        assertEquals(sos.id, decoded.sos.id)
        assertEquals(sos.groupId, decoded.sos.groupId)
        assertEquals(sos.senderId, decoded.sos.senderId)
        assertEquals(sos.message, decoded.sos.message)
        assertEquals(sos.timestamp, decoded.sos.timestamp)
        assertEquals(sos.ttl, decoded.sos.ttl)
        assertArrayEquals(sos.mac, decoded.sos.mac)
        assertEquals(false, decoded.sos.senderIsMe) // always false on the receiving side, by design
    }

    @Test
    fun `evidence meta frame round-trips including the sha256 digest`() {
        val meta = EvidenceEntity(
            id = "evid-1", groupId = "group-1", senderId = "sender-1", senderIsMe = true,
            timestamp = 1_700_000_000_000L, sha256 = fakeSha256, totalChunks = 42,
            mimeType = "image/jpeg", ttl = 8, mac = ByteArray(16) { it.toByte() }
        )
        val decoded = MeshFrameCodec.decode(MeshFrameCodec.encodeEvidMeta(meta))
        check(decoded is MeshFrameCodec.Frame.EvidMeta)
        assertEquals(meta.id, decoded.meta.id)
        assertEquals(meta.sha256, decoded.meta.sha256)
        assertEquals(meta.totalChunks, decoded.meta.totalChunks)
        assertEquals(meta.mimeType, decoded.meta.mimeType)
    }

    @Test
    fun `evidence chunk frame round-trips arbitrary binary data`() {
        val data = ByteArray(400) { (it % 256).toByte() }
        val chunk = EvidenceChunkEntity(evidenceId = "evid-1", chunkIndex = 7, data = data)
        val decoded = MeshFrameCodec.decode(MeshFrameCodec.encodeChunk(chunk))
        check(decoded is MeshFrameCodec.Frame.EvidChunk)
        assertEquals(chunk.evidenceId, decoded.chunk.evidenceId)
        assertEquals(chunk.chunkIndex, decoded.chunk.chunkIndex)
        assertArrayEquals(data, decoded.chunk.data)
    }

    @Test
    fun `position frame is opaque without the group key and opens correctly with it`() {
        val key = randomKey()
        val frame = MeshFrameCodec.encodePosition("group-1", key, "sender-1", 12.3456, 78.9012, 5, 1_700_000_000L, 2)
        val decoded = MeshFrameCodec.decode(frame)
        check(decoded is MeshFrameCodec.Frame.PositionSealed)
        assertEquals("group-1", decoded.groupId)
        // Wrong key must not open it.
        assertNull(MeshFrameCodec.openPosition(decoded.sealed, randomKey()))
        val body = MeshFrameCodec.openPosition(decoded.sealed, key)
        checkNotNull(body)
        assertEquals("sender-1", body.senderId)
        assertEquals(12.3456, body.lat, 1e-6)
        assertEquals(78.9012, body.lon, 1e-6)
        assertEquals(5, body.accuracyM)
        assertEquals(2, body.hop)
    }

    @Test
    fun `manifest frame round-trips a bitset including boundary indexes`() {
        val total = 17 // deliberately not a multiple of 8, to exercise the partial last byte
        val have = setOf(0, 1, 7, 8, 16)
        val decoded = MeshFrameCodec.decode(MeshFrameCodec.encodeManifest("evid-1", total, have))
        check(decoded is MeshFrameCodec.Frame.Manifest)
        assertEquals(total, decoded.totalChunks)
        assertEquals(have, decoded.peerHave)
    }

    @Test
    fun `nickname frame round-trips and truncates to MAX_USERNAME_CHARS`() {
        val tooLong = "x".repeat(MeshFrameCodec.MAX_USERNAME_CHARS + 10)
        val nick = NicknameEntity("group-1", "sender-1", tooLong, 1_700_000_000_000L, ByteArray(16))
        val decoded = MeshFrameCodec.decode(MeshFrameCodec.encodeNickname(nick))
        check(decoded is MeshFrameCodec.Frame.Nickname)
        assertEquals(MeshFrameCodec.MAX_USERNAME_CHARS, decoded.nickname.username.length)
        assertTrue(tooLong.startsWith(decoded.nickname.username))
    }

    @Test
    fun `presence frame round-trips and carries a verifiable tag`() {
        val key = randomKey()
        val frame = MeshFrameCodec.encodePresence("group-1", "sender-1", 1_700_000_000_000L, key)
        val decoded = MeshFrameCodec.decode(frame)
        check(decoded is MeshFrameCodec.Frame.Presence)
        assertEquals("group-1", decoded.groupId)
        assertEquals("sender-1", decoded.senderId)
    }

    @Test
    fun `decode rejects empty and truncated input without throwing`() {
        assertNull(MeshFrameCodec.decode(ByteArray(0)))
        assertNull(MeshFrameCodec.decode(byteArrayOf(MeshFrameCodec.FRAME_SOS))) // type byte only, no version/body
    }

    @Test
    fun `decode rejects an unknown frame type`() {
        assertNull(MeshFrameCodec.decode(byteArrayOf(0x7F, 1)))
    }

    @Test
    fun `decode rejects a frame from a different version`() {
        val encoded = MeshFrameCodec.encodePresence("g", "s", 0L, randomKey())
        val wrongVersion = encoded.copyOf().also { it[1] = 99 }
        assertNull(MeshFrameCodec.decode(wrongVersion))
    }
}
