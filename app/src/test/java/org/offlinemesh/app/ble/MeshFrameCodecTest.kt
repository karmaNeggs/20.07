package org.offlinemesh.app.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.offlinemesh.app.crypto.CryptoUtils
import org.offlinemesh.app.crypto.SenderIdentity
import org.offlinemesh.app.data.EvidenceChunkEntity
import org.offlinemesh.app.data.EvidenceEntity
import org.offlinemesh.app.data.NicknameEntity
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
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

    // ---------- SOS (decision 37, docs/DECISIONS.md): AES-GCM sealed, same shape as position ----------
    // sealSos/openSos replaced the old cleartext-plus-HMAC (sosMacInput) scheme entirely — a
    // non-member relay used to be able to read SOS message text directly; now it moves opaque bytes,
    // same as it always has for position. groupId/id/ttl/hop stay in the cleartext envelope (see
    // Frame.SosSealed's own doc) so blind relaying still works without ever opening the seal.

    @Test
    fun `sos frame is opaque without the group key and opens correctly with it`() {
        val key = randomKey()
        val sealed = MeshFrameCodec.sealSos(
            groupId = "group-1", key = key, id = "sos-1", senderId = "sender-1",
            message = "need help at the north gate", timestamp = 1_700_000_000_000L,
            isAlert = false, ttl = 8, hop = 2,
        )
        val decoded = MeshFrameCodec.decode(sealed)
        check(decoded is MeshFrameCodec.Frame.SosSealed)
        assertEquals("group-1", decoded.groupId)
        assertEquals("sos-1", decoded.id)
        assertEquals(8, decoded.ttl)
        // hop must round-trip independently of ttl (docs/DECISIONS.md decision 16 — the whole
        // point of adding it was decoupling hop-from-origin from a ttl a degree-aware relay may
        // clamp by more than 1 in a single hop).
        assertEquals(2, decoded.hop)
        // Wrong key must not open it.
        assertNull(MeshFrameCodec.openSos(decoded.sealed, randomKey()))
        val body = MeshFrameCodec.openSos(decoded.sealed, key)
        checkNotNull(body)
        assertEquals("sender-1", body.senderId)
        assertEquals("need help at the north gate", body.message)
        assertEquals(1_700_000_000_000L, body.timestamp)
        assertFalse(body.isAlert)
    }

    @Test
    fun `sos frame with hop 0 (an origin-authored SOS) round-trips`() {
        val key = randomKey()
        val sealed = MeshFrameCodec.sealSos(
            "group-1", key, "sos-1", "sender-1", "need help", 1_700_000_000_000L,
            isAlert = false, ttl = 8, hop = 0,
        )
        val decoded = MeshFrameCodec.decode(sealed)
        check(decoded is MeshFrameCodec.Frame.SosSealed)
        assertEquals(0, decoded.hop)
    }

    @Test
    fun `sos frame round-trips isAlert true`() {
        // decision 35, docs/DECISIONS.md — splits the loud/broadcast alert treatment from ordinary
        // quiet messages sharing this same entity/frame.
        val key = randomKey()
        val sealed = MeshFrameCodec.sealSos(
            "group-1", key, "sos-1", "sender-1", "medical emergency", 1_700_000_000_000L,
            isAlert = true, ttl = 8, hop = 0,
        )
        val decoded = MeshFrameCodec.decode(sealed)
        check(decoded is MeshFrameCodec.Frame.SosSealed)
        val body = MeshFrameCodec.openSos(decoded.sealed, key)
        checkNotNull(body)
        assertTrue(body.isAlert)
    }

    @Test
    fun `sos frame round-trips isAlert false`() {
        val key = randomKey()
        val sealed = MeshFrameCodec.sealSos(
            "group-1", key, "sos-1", "sender-1", "meet at gate 3", 1_700_000_000_000L,
            isAlert = false, ttl = 8, hop = 0,
        )
        val decoded = MeshFrameCodec.decode(sealed)
        check(decoded is MeshFrameCodec.Frame.SosSealed)
        val body = MeshFrameCodec.openSos(decoded.sealed, key)
        checkNotNull(body)
        assertFalse(body.isAlert)
    }

    @Test
    fun `tampering with any byte of a sealed sos makes it fail to open, including a flipped isAlert`() {
        // Supersedes the old sosMacInput-based tests (decision 37 removed that scheme entirely):
        // AES-GCM authenticates senderId/message/timestamp/isAlert as one unit, so a relay can no
        // longer flip isAlert — or rewrite any part of the message, including past the old scheme's
        // 255-byte mac-input truncation bug — without invalidating the GCM tag outright, rather than
        // needing a dedicated per-field check the way the old scheme did.
        val key = randomKey()
        val sealed = MeshFrameCodec.sealSos(
            "group-1", key, "sos-1", "sender-1", "need help", 1_700_000_000_000L,
            isAlert = false, ttl = 8, hop = 0,
        )
        val decoded = MeshFrameCodec.decode(sealed)
        check(decoded is MeshFrameCodec.Frame.SosSealed)
        val tampered = decoded.sealed.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        assertNull(MeshFrameCodec.openSos(tampered, key))
    }

    @Test
    fun `sealing the same sos id twice produces identical ciphertext`() {
        // Regression guard for sealSos's deterministic, id-derived nonce (see sosNonce's own doc):
        // unlike position (resealed repeatedly, needs an in-process counter to disambiguate same-
        // second sends), a given SOS id is sealed exactly once ever — content is immutable — so
        // re-sealing identical content must reproduce identical bytes. This is what lets
        // reframeSosForRelay forward the ORIGINAL ciphertext verbatim across every hop instead of
        // re-encrypting, which would otherwise break blind-relay dedup the same way it would for
        // position.
        val key = randomKey()
        val a = MeshFrameCodec.sealSos(
            "group-1", key, "sos-1", "sender-1", "need help", 1_700_000_000_000L,
            isAlert = false, ttl = 8, hop = 0,
        )
        val b = MeshFrameCodec.sealSos(
            "group-1", key, "sos-1", "sender-1", "need help", 1_700_000_000_000L,
            isAlert = false, ttl = 8, hop = 0,
        )
        val decodedA = MeshFrameCodec.decode(a) as MeshFrameCodec.Frame.SosSealed
        val decodedB = MeshFrameCodec.decode(b) as MeshFrameCodec.Frame.SosSealed
        assertTrue(decodedA.sealed.contentEquals(decodedB.sealed))
    }

    @Test
    fun `sos frame carries a signature inside the seal, verifiable once opened`() {
        val key = randomKey()
        val pair = SenderIdentity.generateKeyPair()
        val sealed = MeshFrameCodec.sealSos(
            "group-1", key, "sos-1", "sender-1", "need help", 1_700_000_000_000L,
            isAlert = false, ttl = 8, hop = 0, signingPrivateKey = pair.privateKey,
        )
        val decoded = MeshFrameCodec.decode(sealed)
        check(decoded is MeshFrameCodec.Frame.SosSealed)
        val body = MeshFrameCodec.openSos(decoded.sealed, key)
        checkNotNull(body)
        checkNotNull(body.signature)
        assertTrue(SenderIdentity.verify(pair.publicKey, body.signature!!, body.signedBytes))
    }

    @Test
    fun `sos signature detects impersonation even though the group-key seal is still valid`() {
        // Same threat model as position's equivalent test: a malicious GROUP MEMBER legitimately
        // holds the group key (so the GCM seal opens fine) but forges a senderId they don't hold
        // the Ed25519 private key for.
        val key = randomKey()
        val impostor = SenderIdentity.generateKeyPair()
        val realSender = SenderIdentity.generateKeyPair()
        val sealed = MeshFrameCodec.sealSos(
            "group-1", key, "sos-1", "real-sender-id", "need help", 1_700_000_000_000L,
            isAlert = false, ttl = 8, hop = 0, signingPrivateKey = impostor.privateKey,
        )
        val decoded = MeshFrameCodec.decode(sealed)
        check(decoded is MeshFrameCodec.Frame.SosSealed)
        val body = MeshFrameCodec.openSos(decoded.sealed, key) // GCM auth passes — same group key
        checkNotNull(body)
        checkNotNull(body.signature)
        assertFalse(SenderIdentity.verify(realSender.publicKey, body.signature!!, body.signedBytes))
        assertTrue(SenderIdentity.verify(impostor.publicKey, body.signature!!, body.signedBytes))
    }

    @Test
    fun `sos frame with no signing key round-trips a null signature`() {
        val key = randomKey()
        val sealed = MeshFrameCodec.sealSos(
            "group-1", key, "sos-1", "sender-1", "need help", 1_700_000_000_000L,
            isAlert = false, ttl = 8, hop = 0,
        )
        val decoded = MeshFrameCodec.decode(sealed)
        check(decoded is MeshFrameCodec.Frame.SosSealed)
        val body = MeshFrameCodec.openSos(decoded.sealed, key)
        checkNotNull(body)
        assertNull(body.signature)
    }

    @Test
    fun `sos frame carries id, ttl, and hop in the cleartext envelope, readable without any key`() {
        // The property blind relaying depends on (decision 37's whole point): a phone with no group
        // key must still be able to dedup on id, flood-control on ttl, and advance hop — none of
        // that requires ever opening the seal. See RelayResponder.takeOpaqueSosCustody.
        val sealed = MeshFrameCodec.sealSos(
            "group-1", randomKey(), "sos-1", "sender-1", "need help", 1_700_000_000_000L,
            isAlert = false, ttl = 8, hop = 2,
        )
        val decoded = MeshFrameCodec.decode(sealed)
        check(decoded is MeshFrameCodec.Frame.SosSealed)
        assertEquals("sos-1", decoded.id)
        assertEquals(8, decoded.ttl)
        assertEquals(2, decoded.hop)
    }

    @Test
    fun `reframeSosForRelay changes only ttl and hop, never the sealed bytes`() {
        val key = randomKey()
        val original = MeshFrameCodec.decode(
            MeshFrameCodec.sealSos(
                "group-1", key, "sos-1", "sender-1", "need help", 1_700_000_000_000L,
                isAlert = false, ttl = 8, hop = 0,
            )
        ) as MeshFrameCodec.Frame.SosSealed

        val relayed = MeshFrameCodec.decode(
            MeshFrameCodec.reframeSosForRelay(
                original.groupId, original.id, original.ttl - 1, original.hop + 1, original.sealed
            )
        ) as MeshFrameCodec.Frame.SosSealed

        assertEquals(7, relayed.ttl)
        assertEquals(1, relayed.hop)
        assertTrue(original.sealed.contentEquals(relayed.sealed))
        // And it still opens correctly for an actual member, unchanged by the relay hop/ttl.
        val body = MeshFrameCodec.openSos(relayed.sealed, key)
        checkNotNull(body)
        assertEquals("sender-1", body.senderId)
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
        assertNull(decoded.meta.signature)
    }

    @Test
    fun `evidence meta frame round-trips a signature alongside the mac`() {
        val pair = SenderIdentity.generateKeyPair()
        val meta = EvidenceEntity(
            id = "evid-1", groupId = "group-1", senderId = "sender-1", senderIsMe = true,
            timestamp = 1_700_000_000_000L, sha256 = fakeSha256, totalChunks = 42,
            mimeType = "image/jpeg", ttl = 8, mac = ByteArray(16),
            signature = SenderIdentity.sign(pair.privateKey, "evid-1".toByteArray())
        )
        val decoded = MeshFrameCodec.decode(MeshFrameCodec.encodeEvidMeta(meta))
        check(decoded is MeshFrameCodec.Frame.EvidMeta)
        assertArrayEquals(meta.signature, decoded.meta.signature)
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
    fun `position frame carries a signature inside the seal, verifiable once opened`() {
        val key = randomKey()
        val pair = SenderIdentity.generateKeyPair()
        val frame = MeshFrameCodec.encodePosition(
            "group-1", key, "sender-1", 1.0, 2.0, 5, 1_700_000_000L, 0,
            signingPrivateKey = pair.privateKey
        )
        val decoded = MeshFrameCodec.decode(frame)
        check(decoded is MeshFrameCodec.Frame.PositionSealed)
        val body = MeshFrameCodec.openPosition(decoded.sealed, key)
        checkNotNull(body)
        checkNotNull(body.signature)
        assertTrue(SenderIdentity.verify(pair.publicKey, body.signature!!, body.signedBytes))
    }

    @Test
    fun `position signature detects impersonation even though the group-key seal is still valid`() {
        // The threat sender identity exists for: a malicious GROUP MEMBER (who legitimately holds the
        // group key, so GCM authentication alone passes) forges a position "from" someone else.
        // They can produce a validly-sealed frame claiming any senderId they like, but they don't
        // hold that sender's Ed25519 private key — so their own signature verifies fine under
        // THEIR OWN public key, but not under the impersonated sender's.
        val key = randomKey()
        val impostor = SenderIdentity.generateKeyPair()
        val realSender = SenderIdentity.generateKeyPair()
        val frame = MeshFrameCodec.encodePosition(
            "group-1", key, "real-sender-id", 1.0, 2.0, 5, 1_700_000_000L, 0,
            signingPrivateKey = impostor.privateKey // signed by the impostor, claiming to be real-sender-id
        )
        val decoded = MeshFrameCodec.decode(frame)
        check(decoded is MeshFrameCodec.Frame.PositionSealed)
        val body = MeshFrameCodec.openPosition(decoded.sealed, key) // GCM auth passes — same group key
        checkNotNull(body)
        checkNotNull(body.signature)
        assertFalse(SenderIdentity.verify(realSender.publicKey, body.signature!!, body.signedBytes))
        assertTrue(SenderIdentity.verify(impostor.publicKey, body.signature!!, body.signedBytes))
    }

    @Test
    fun `position frame with no signing key round-trips a null signature`() {
        val key = randomKey()
        val frame = MeshFrameCodec.encodePosition("group-1", key, "sender-1", 1.0, 2.0, 5, 1_700_000_000L, 0)
        val decoded = MeshFrameCodec.decode(frame)
        check(decoded is MeshFrameCodec.Frame.PositionSealed)
        val body = MeshFrameCodec.openPosition(decoded.sealed, key)
        checkNotNull(body)
        assertNull(body.signature)
    }

    @Test
    fun `two position frames from the same sender in the same second use different nonces`() {
        // Regression guard for the deterministic-nonce path: same senderId + same timestampSec is
        // exactly the collision-prone case (sha256(senderId)-prefix and timestampSec bytes both
        // identical) — only the in-process counter tells them apart. Comparing the raw sealed
        // bytes (nonce is the first 12 bytes) is a proxy for "the nonce actually differed"; if it
        // hadn't, GCM would also make the ciphertext identical for identical plaintext+key+nonce.
        val key = randomKey()
        val a = MeshFrameCodec.encodePosition("group-1", key, "sender-1", 1.0, 2.0, 5, 1_700_000_000L, 0)
        val b = MeshFrameCodec.encodePosition("group-1", key, "sender-1", 1.0, 2.0, 5, 1_700_000_000L, 0)
        val decodedA = MeshFrameCodec.decode(a) as MeshFrameCodec.Frame.PositionSealed
        val decodedB = MeshFrameCodec.decode(b) as MeshFrameCodec.Frame.PositionSealed
        assertTrue(!decodedA.sealed.contentEquals(decodedB.sealed))
        // Both must still open correctly despite identical plaintext/key/second.
        assertEquals(1.0, MeshFrameCodec.openPosition(decodedA.sealed, key)!!.lat, 1e-6)
        assertEquals(1.0, MeshFrameCodec.openPosition(decodedB.sealed, key)!!.lat, 1e-6)
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
        assertNull(decoded.nickname.signature)
    }

    @Test
    fun `nickname frame round-trips a signature alongside the mac`() {
        val pair = SenderIdentity.generateKeyPair()
        val nick = NicknameEntity(
            "group-1", "sender-1", "responder", 1_700_000_000_000L,
            mac = ByteArray(16), signature = SenderIdentity.sign(pair.privateKey, "responder".toByteArray())
        )
        val decoded = MeshFrameCodec.decode(MeshFrameCodec.encodeNickname(nick))
        check(decoded is MeshFrameCodec.Frame.Nickname)
        assertArrayEquals(nick.signature, decoded.nickname.signature)
    }

    @Test
    fun `presence frame round-trips and carries a verifiable tag`() {
        val key = randomKey()
        val frame = MeshFrameCodec.encodePresence("group-1", "sender-1", 1_700_000_000_000L, key)
        val decoded = MeshFrameCodec.decode(frame)
        check(decoded is MeshFrameCodec.Frame.Presence)
        assertEquals("group-1", decoded.groupId)
        assertEquals("sender-1", decoded.senderId)
        assertNull(decoded.senderPublicKey)
        assertNull(decoded.signature)
    }

    @Test
    fun `presence frame round-trips a sender public key and signature`() {
        val key = randomKey()
        val pair = SenderIdentity.generateKeyPair()
        val frame = MeshFrameCodec.encodePresence(
            "group-1", "sender-1", 1_700_000_000_000L, key,
            senderPublicKey = pair.publicKey, signingPrivateKey = pair.privateKey
        )
        val decoded = MeshFrameCodec.decode(frame)
        check(decoded is MeshFrameCodec.Frame.Presence)
        assertArrayEquals(pair.publicKey, decoded.senderPublicKey)
        checkNotNull(decoded.signature)
        val macInput = MeshFrameCodec.presenceMacInput("group-1", "sender-1", 1_700_000_000_000L)
        assertTrue(SenderIdentity.verify(pair.publicKey, decoded.signature!!, macInput))
    }

    @Test
    fun `catalog filter frame round-trips seed and bits, and preserves membership answers`() {
        val filter = CatalogFilter.build(listOf("sos:a", "evid:b"), seed = 12345L)
        val decoded = MeshFrameCodec.decode(
            MeshFrameCodec.encodeCatalogFilter(filter.seed, filter.sizeBits, filter.toBits())
        )
        check(decoded is MeshFrameCodec.Frame.CatalogFilter)
        assertEquals(12345L, decoded.seed)
        assertEquals(filter.sizeBits, decoded.sizeBits)
        val reconstructed = CatalogFilter.fromBits(decoded.bits, decoded.seed, decoded.sizeBits)
        assertTrue(reconstructed.mightContain("sos:a"))
        assertTrue(reconstructed.mightContain("evid:b"))
    }

    @Test
    fun `wifi direct capability frame round-trips its version byte`() {
        val decoded = MeshFrameCodec.decode(MeshFrameCodec.encodeWifiDirectCap(version = 3))
        check(decoded is MeshFrameCodec.Frame.WifiDirectCap)
        assertEquals(3, decoded.version)
    }

    @Test
    fun `wifi direct handoff frame round-trips and carries a verifiable tag`() {
        val key = randomKey()
        val nonce = ByteArray(16) { it.toByte() }
        val mac = CryptoUtils.authTag(key, MeshFrameCodec.wifiDirectHandoffMacInput("evid-1", "group-1", 42, nonce))
        val decoded = MeshFrameCodec.decode(MeshFrameCodec.encodeWifiDirectHandoff("evid-1", "group-1", 42, nonce, mac))
        check(decoded is MeshFrameCodec.Frame.WifiDirectHandoff)
        assertEquals("evid-1", decoded.evidenceId)
        assertEquals("group-1", decoded.groupId)
        assertEquals(42, decoded.deficitCount)
        assertArrayEquals(nonce, decoded.senderNonce)
        val recomputedInput = MeshFrameCodec.wifiDirectHandoffMacInput(
            decoded.evidenceId, decoded.groupId, decoded.deficitCount, decoded.senderNonce
        )
        val recomputed = CryptoUtils.authTag(key, recomputedInput)
        assertTrue(CryptoUtils.constantTimeEquals(recomputed, decoded.mac))
    }

    @Test
    fun `wifi direct accept frame round-trips and carries a verifiable tag`() {
        val key = randomKey()
        val senderNonce = ByteArray(16) { it.toByte() }
        val receiverNonce = ByteArray(16) { (it + 1).toByte() }
        val readyAt = 1_700_000_000_000L
        val macInput = MeshFrameCodec.wifiDirectAcceptMacInput("evid-1", "group-1", senderNonce, receiverNonce, readyAt)
        val mac = CryptoUtils.authTag(key, macInput)
        val encoded =
            MeshFrameCodec.encodeWifiDirectAccept("evid-1", "group-1", senderNonce, receiverNonce, readyAt, mac)
        val decoded = MeshFrameCodec.decode(encoded)
        check(decoded is MeshFrameCodec.Frame.WifiDirectAccept)
        assertEquals("evid-1", decoded.evidenceId)
        assertEquals(readyAt, decoded.readyAtEpochMs)
        assertArrayEquals(senderNonce, decoded.senderNonce)
        assertArrayEquals(receiverNonce, decoded.receiverNonce)
        val recomputedInput = MeshFrameCodec.wifiDirectAcceptMacInput(
            decoded.evidenceId, decoded.groupId, decoded.senderNonce, decoded.receiverNonce, decoded.readyAtEpochMs
        )
        assertTrue(CryptoUtils.constantTimeEquals(CryptoUtils.authTag(key, recomputedInput), decoded.mac))
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

    // ---------- hostile totalChunks must never reach MeshProtocol.encodeBitset's allocation ----------
    // A remote, unauthenticated, non-member relay can send an evidence header or manifest carrying
    // an arbitrary totalChunks — encodeBitset allocates (totalChunks + 7) / 8 bytes downstream
    // (RelayResponder.framesToPushOnConnect / handleIncoming's Manifest case), and the header is
    // persisted to Room, so the crash recurs on every future connection until the 48h prune. decode()
    // is the one choke point every such frame must pass through regardless of entry path.

    @Test
    fun `decode rejects an evidence meta frame with a hostile totalChunks`() {
        val meta = EvidenceEntity(
            id = "evid-1", groupId = "group-1", senderId = "sender-1", senderIsMe = true,
            timestamp = 1_700_000_000_000L, sha256 = fakeSha256, totalChunks = Int.MAX_VALUE,
            mimeType = "image/jpeg", ttl = 8, mac = ByteArray(16)
        )
        assertNull(MeshFrameCodec.decode(MeshFrameCodec.encodeEvidMeta(meta)))
    }

    @Test
    fun `decode rejects an evidence meta frame with a negative or zero totalChunks`() {
        val negative = EvidenceEntity(
            id = "evid-1", groupId = "group-1", senderId = "sender-1", senderIsMe = true,
            timestamp = 1_700_000_000_000L, sha256 = fakeSha256, totalChunks = -1,
            mimeType = "image/jpeg", ttl = 8, mac = ByteArray(16)
        )
        assertNull(MeshFrameCodec.decode(MeshFrameCodec.encodeEvidMeta(negative)))
        val zero = negative.copy(totalChunks = 0)
        assertNull(MeshFrameCodec.decode(MeshFrameCodec.encodeEvidMeta(zero)))
    }

    @Test
    fun `decode still accepts a legitimate evidence meta frame under the cap`() {
        val meta = EvidenceEntity(
            id = "evid-1", groupId = "group-1", senderId = "sender-1", senderIsMe = true,
            timestamp = 1_700_000_000_000L, sha256 = fakeSha256, totalChunks = 200,
            mimeType = "image/jpeg", ttl = 8, mac = ByteArray(16)
        )
        val decoded = MeshFrameCodec.decode(MeshFrameCodec.encodeEvidMeta(meta))
        check(decoded is MeshFrameCodec.Frame.EvidMeta)
        assertEquals(200, decoded.meta.totalChunks)
    }

    /** Hand-builds the raw manifest wire layout (rather than calling [MeshFrameCodec.encodeManifest],
     *  which itself calls the vulnerable [MeshProtocol.encodeBitset] and would allocate on the
     *  encode side too) — this is exactly what a hostile peer's raw bytes look like: a huge claimed
     *  totalChunks paired with a tiny actual bitset payload. */
    private fun rawManifestFrame(evidenceId: String, totalChunks: Int, bitsetBytes: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        val d = DataOutputStream(bos)
        d.writeByte(MeshFrameCodec.FRAME_MANIFEST.toInt())
        d.writeByte(MeshFrameCodec.VERSION)
        val idBytes = evidenceId.toByteArray(Charsets.UTF_8)
        d.writeByte(idBytes.size)
        d.write(idBytes)
        d.writeInt(totalChunks)
        d.write(bitsetBytes)
        return bos.toByteArray()
    }

    @Test
    fun `decode rejects a manifest frame with a hostile totalChunks`() {
        val hostile = rawManifestFrame("evid-1", Int.MAX_VALUE, ByteArray(4))
        assertNull(MeshFrameCodec.decode(hostile))
    }

    @Test
    fun `decode rejects a manifest frame with a negative totalChunks`() {
        val hostile = rawManifestFrame("evid-1", -1, ByteArray(4))
        assertNull(MeshFrameCodec.decode(hostile))
    }

    @Test
    fun `decode still accepts a legitimate manifest frame under the cap`() {
        val bitset = MeshProtocol.encodeBitset(setOf(0, 1, 2), 17)
        val legit = rawManifestFrame("evid-1", 17, bitset)
        val decoded = MeshFrameCodec.decode(legit)
        check(decoded is MeshFrameCodec.Frame.Manifest)
        assertEquals(17, decoded.totalChunks)
    }

    // ---------- MAX_SOS_MESSAGE_BYTES is enforced by openSos, not decode ----------
    // Unlike the old cleartext scheme, decode() never looks inside the sealed blob — it only parses
    // the envelope — so a hostile over-length message can't be rejected until a member with the
    // group key actually opens it. That's an acceptable tradeoff (openSos still refuses to hand back
    // an oversized body before anything downstream touches it) rather than a gap, since a non-member
    // relay could never have checked this cap either way — it's not the auth boundary, just a sanity
    // cap on what a real member's own UI would ever construct.

    @Test
    fun `openSos rejects a message exceeding MAX_SOS_MESSAGE_BYTES`() {
        val key = randomKey()
        val sealed = MeshFrameCodec.sealSos(
            "group-1", key, "sos-1", "sender-1", "x".repeat(MeshFrameCodec.MAX_SOS_MESSAGE_BYTES + 1),
            1000L, isAlert = false, ttl = 8, hop = 0,
        )
        val decoded = MeshFrameCodec.decode(sealed)
        check(decoded is MeshFrameCodec.Frame.SosSealed)
        assertNull(MeshFrameCodec.openSos(decoded.sealed, key))
    }

    @Test
    fun `openSos still accepts a message at exactly MAX_SOS_MESSAGE_BYTES`() {
        val key = randomKey()
        val sealed = MeshFrameCodec.sealSos(
            "group-1", key, "sos-1", "sender-1", "x".repeat(MeshFrameCodec.MAX_SOS_MESSAGE_BYTES),
            1000L, isAlert = false, ttl = 8, hop = 0,
        )
        val decoded = MeshFrameCodec.decode(sealed)
        check(decoded is MeshFrameCodec.Frame.SosSealed)
        val body = MeshFrameCodec.openSos(decoded.sealed, key)
        checkNotNull(body)
        assertEquals(MeshFrameCodec.MAX_SOS_MESSAGE_BYTES, body.message.length)
    }

    @Test
    fun `position frame carries its hop in the cleartext envelope, readable without any key`() {
        // The property blind relaying depends on: a phone with no group key must still be able to
        // read and increment the hop. If this ever moves back inside the seal, non-member relays
        // silently stop forwarding positions again (see Frame.PositionSealed's doc).
        val key = randomKey()
        val frame = MeshFrameCodec.encodePosition("group-1", key, "sender-1", 1.0, 2.0, 5, 1_700_000_000L, hop = 2)
        val decoded = MeshFrameCodec.decode(frame)
        check(decoded is MeshFrameCodec.Frame.PositionSealed)
        assertEquals(2, decoded.hop)
    }

    @Test
    fun `reframePositionForRelay changes only the hop, never the sealed bytes`() {
        val key = randomKey()
        val original = MeshFrameCodec.decode(
            MeshFrameCodec.encodePosition("group-1", key, "sender-1", 1.0, 2.0, 5, 1_700_000_000L, hop = 0)
        ) as MeshFrameCodec.Frame.PositionSealed

        val relayed = MeshFrameCodec.decode(
            MeshFrameCodec.reframePositionForRelay(original.groupId, original.hop + 1, original.sealed)
        ) as MeshFrameCodec.Frame.PositionSealed

        assertEquals(1, relayed.hop)
        assertTrue(original.sealed.contentEquals(relayed.sealed))
        // And it still opens correctly for an actual member, unchanged by the relay hop.
        val body = MeshFrameCodec.openPosition(relayed.sealed, key)
        checkNotNull(body)
        assertEquals("sender-1", body.senderId)
    }

    @Test
    fun `presence carries an envelope hop and reframes for relay without any key`() {
        // The GPS-less member case: presence is the ONLY thing that can carry them outward, so a
        // relay holding no group key must be able to advance its hop. Nothing but the hop may change
        // — the mac a real member verifies has to survive the relay byte-for-byte.
        val key = randomKey()
        val original = MeshFrameCodec.decode(
            MeshFrameCodec.encodePresence("group-1", "sender-1", 1_700_000_000_000L, key)
        ) as MeshFrameCodec.Frame.Presence
        assertEquals(0, original.hop)

        val relayed = MeshFrameCodec.decode(
            MeshFrameCodec.reframePresenceForRelay(original, original.hop + 1)
        ) as MeshFrameCodec.Frame.Presence

        assertEquals(1, relayed.hop)
        assertEquals(original.senderId, relayed.senderId)
        assertEquals(original.timestamp, relayed.timestamp)
        assertArrayEquals(original.mac, relayed.mac)
        // And the mac still verifies for an actual member, unaffected by having been relayed.
        val macInput = MeshFrameCodec.presenceMacInput("group-1", "sender-1", 1_700_000_000_000L)
        assertTrue(CryptoUtils.constantTimeEquals(CryptoUtils.authTag(key, macInput), relayed.mac))
    }
}
