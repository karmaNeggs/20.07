package org.offlinemesh.app.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
@Suppress("LargeClass") // mirrors MeshFrameCodec.kt's own single coherent wire-frame surface — one
// section per frame type (sos/position/presence/nickname/...), same shape as CryptoUtils' own
// TooManyFunctions suppress; splitting would fragment tests away from the frame type they cover.
class MeshFrameCodecTest {

    private val fakeSha256 = "a".repeat(64) // valid 32-byte-hex shape, content doesn't matter here
    // Valid 6-byte handle shape (decision 38) — content doesn't matter for tests that only care
    // whether it round-trips, not what group it actually resolves to.
    private val fakeHandle = ByteArray(6) { it.toByte() }
    private fun randomKey() = ByteArray(32).also { SecureRandom().nextBytes(it) }

    // ---------- SOS (decision 37, docs/DECISIONS.md): AES-GCM sealed, same shape as position ----------
    // sealSos/openSos replaced the old cleartext-plus-HMAC (sosMacInput) scheme entirely — a
    // non-member relay used to be able to read SOS message text directly; now it moves opaque bytes,
    // same as it always has for position. handle/id/ttl/hop stay in the cleartext envelope (see
    // Frame.SosSealed's own doc) so blind relaying still works without ever opening the seal.
    // handle (decision 38) replaced the old cleartext groupId — MeshFrameCodec.groupHandle(key,
    // epochSeconds) is the single source of truth for what value to expect it holds.

    // Decision 39 (docs/DECISIONS.md): sealSos now takes rootKey (for groupHandle) and contentKey
    // (for the actual seal) separately. This helper derives contentKey the same way production
    // code does (CryptoUtils.contentEpochKey(rootKey, timestamp/1000)) so tests don't have to
    // repeat that at every call site — matches this file's existing fakeHandle/randomKey helper
    // style.
    @Suppress("LongParameterList") // wire-protocol scalars — see MeshFrameCodec.sealSos's identical suppress
    private fun sealSosFixture(
        rootKey: ByteArray, id: String, senderId: String, message: String, timestamp: Long,
        isAlert: Boolean, ttl: Int, hop: Int, signingPrivateKey: ByteArray? = null,
    ): Pair<ByteArray, ByteArray> {
        val contentKey = CryptoUtils.contentEpochKey(rootKey, timestamp / 1000)
        val sealed = MeshFrameCodec.sealSos(
            rootKey, contentKey, id, senderId, message, timestamp, isAlert, ttl, hop, signingPrivateKey
        )
        return sealed to contentKey
    }

    @Test
    fun `sos frame is opaque without the group key and opens correctly with it`() {
        val key = randomKey()
        val (sealed, contentKey) = sealSosFixture(
            rootKey = key, id = "sos-1", senderId = "sender-1",
            message = "need help at the north gate", timestamp = 1_700_000_000_000L,
            isAlert = false, ttl = 8, hop = 2,
        )
        val decoded = MeshFrameCodec.decode(sealed)
        check(decoded is MeshFrameCodec.Frame.SosSealed)
        assertArrayEquals(MeshFrameCodec.groupHandle(key, 1_700_000_000_000L / 1000), decoded.handle)
        assertEquals("sos-1", decoded.id)
        assertEquals(8, decoded.ttl)
        // hop must round-trip independently of ttl (docs/DECISIONS.md decision 16 — the whole
        // point of adding it was decoupling hop-from-origin from a ttl a degree-aware relay may
        // clamp by more than 1 in a single hop).
        assertEquals(2, decoded.hop)
        // Wrong key must not open it.
        assertNull(MeshFrameCodec.openSos(decoded.sealed, randomKey()))
        val body = MeshFrameCodec.openSos(decoded.sealed, contentKey)
        checkNotNull(body)
        assertEquals("sender-1", body.senderId)
        assertEquals("need help at the north gate", body.message)
        assertEquals(1_700_000_000_000L, body.timestamp)
        assertFalse(body.isAlert)
    }

    @Test
    fun `sos frame with hop 0 (an origin-authored SOS) round-trips`() {
        val key = randomKey()
        val (sealed, _) = sealSosFixture(
            key, "sos-1", "sender-1", "need help", 1_700_000_000_000L,
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
        val (sealed, contentKey) = sealSosFixture(
            key, "sos-1", "sender-1", "medical emergency", 1_700_000_000_000L,
            isAlert = true, ttl = 8, hop = 0,
        )
        val decoded = MeshFrameCodec.decode(sealed)
        check(decoded is MeshFrameCodec.Frame.SosSealed)
        val body = MeshFrameCodec.openSos(decoded.sealed, contentKey)
        checkNotNull(body)
        assertTrue(body.isAlert)
    }

    @Test
    fun `sos frame round-trips isAlert false`() {
        val key = randomKey()
        val (sealed, contentKey) = sealSosFixture(
            key, "sos-1", "sender-1", "meet at gate 3", 1_700_000_000_000L,
            isAlert = false, ttl = 8, hop = 0,
        )
        val decoded = MeshFrameCodec.decode(sealed)
        check(decoded is MeshFrameCodec.Frame.SosSealed)
        val body = MeshFrameCodec.openSos(decoded.sealed, contentKey)
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
        val (sealed, contentKey) = sealSosFixture(
            key, "sos-1", "sender-1", "need help", 1_700_000_000_000L,
            isAlert = false, ttl = 8, hop = 0,
        )
        val decoded = MeshFrameCodec.decode(sealed)
        check(decoded is MeshFrameCodec.Frame.SosSealed)
        val tampered = decoded.sealed.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        assertNull(MeshFrameCodec.openSos(tampered, contentKey))
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
        val (a, _) = sealSosFixture(
            key, "sos-1", "sender-1", "need help", 1_700_000_000_000L,
            isAlert = false, ttl = 8, hop = 0,
        )
        val (b, _) = sealSosFixture(
            key, "sos-1", "sender-1", "need help", 1_700_000_000_000L,
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
        val (sealed, contentKey) = sealSosFixture(
            key, "sos-1", "sender-1", "need help", 1_700_000_000_000L,
            isAlert = false, ttl = 8, hop = 0, signingPrivateKey = pair.privateKey,
        )
        val decoded = MeshFrameCodec.decode(sealed)
        check(decoded is MeshFrameCodec.Frame.SosSealed)
        val body = MeshFrameCodec.openSos(decoded.sealed, contentKey)
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
        val (sealed, contentKey) = sealSosFixture(
            key, "sos-1", "real-sender-id", "need help", 1_700_000_000_000L,
            isAlert = false, ttl = 8, hop = 0, signingPrivateKey = impostor.privateKey,
        )
        val decoded = MeshFrameCodec.decode(sealed)
        check(decoded is MeshFrameCodec.Frame.SosSealed)
        val body = MeshFrameCodec.openSos(decoded.sealed, contentKey) // GCM auth passes — same content key
        checkNotNull(body)
        checkNotNull(body.signature)
        assertFalse(SenderIdentity.verify(realSender.publicKey, body.signature!!, body.signedBytes))
        assertTrue(SenderIdentity.verify(impostor.publicKey, body.signature!!, body.signedBytes))
    }

    @Test
    fun `sos frame with no signing key round-trips a null signature`() {
        val key = randomKey()
        val (sealed, contentKey) = sealSosFixture(
            key, "sos-1", "sender-1", "need help", 1_700_000_000_000L,
            isAlert = false, ttl = 8, hop = 0,
        )
        val decoded = MeshFrameCodec.decode(sealed)
        check(decoded is MeshFrameCodec.Frame.SosSealed)
        val body = MeshFrameCodec.openSos(decoded.sealed, contentKey)
        checkNotNull(body)
        assertNull(body.signature)
    }

    @Test
    fun `sos frame carries handle, id, ttl, and hop in the cleartext envelope, readable without any key`() {
        // The property blind relaying depends on (decision 37/38's whole point): a phone with no
        // group key must still be able to read the handle, dedup on id, flood-control on ttl, and
        // advance hop — none of that requires ever opening the seal. See
        // RelayResponder.takeOpaqueSosCustody.
        val key = randomKey()
        val (sealed, _) = sealSosFixture(
            key, "sos-1", "sender-1", "need help", 1_700_000_000_000L,
            isAlert = false, ttl = 8, hop = 2,
        )
        val decoded = MeshFrameCodec.decode(sealed)
        check(decoded is MeshFrameCodec.Frame.SosSealed)
        assertArrayEquals(MeshFrameCodec.groupHandle(key, 1_700_000_000_000L / 1000), decoded.handle)
        assertEquals("sos-1", decoded.id)
        assertEquals(8, decoded.ttl)
        assertEquals(2, decoded.hop)
    }

    @Test
    fun `reframeSosForRelay changes only ttl and hop, never the sealed bytes`() {
        val key = randomKey()
        val (sealedBytes, contentKey) = sealSosFixture(
            key, "sos-1", "sender-1", "need help", 1_700_000_000_000L,
            isAlert = false, ttl = 8, hop = 0,
        )
        val original = MeshFrameCodec.decode(sealedBytes) as MeshFrameCodec.Frame.SosSealed

        val relayed = MeshFrameCodec.decode(
            MeshFrameCodec.reframeSosForRelay(
                original.handle, original.id, original.ttl - 1, original.hop + 1, original.sealed
            )
        ) as MeshFrameCodec.Frame.SosSealed

        assertEquals(7, relayed.ttl)
        assertEquals(1, relayed.hop)
        assertTrue(original.sealed.contentEquals(relayed.sealed))
        // And it still opens correctly for an actual member, unchanged by the relay hop/ttl.
        val body = MeshFrameCodec.openSos(relayed.sealed, contentKey)
        checkNotNull(body)
        assertEquals("sender-1", body.senderId)
    }

    // ---------- decision 39 (docs/DECISIONS.md): sos body is sealed under the content epoch key, ----------
    // not the root key directly — proven empirically here, not just by the two-param signature.
    @Test
    fun `sos body opens under its content epoch key but not under the raw root key`() {
        val rootKey = randomKey()
        val (sealed, contentKey) = sealSosFixture(
            rootKey, "sos-1", "sender-1", "need help", 1_700_000_000_000L,
            isAlert = false, ttl = 8, hop = 0,
        )
        val decoded = MeshFrameCodec.decode(sealed) as MeshFrameCodec.Frame.SosSealed
        assertNotNull(MeshFrameCodec.openSos(decoded.sealed, contentKey))
        assertNull(MeshFrameCodec.openSos(decoded.sealed, rootKey))
    }

    // ---------- couriers (P4 slice 1, decision 40): crypto construction only ----------
    // No Frame.Courier type and no FRAME_COURIER wire byte exist yet — see MeshFrameCodec's own
    // "couriers" section doc for why this slice is deliberately isolated to sealCourierBody/
    // openCourierBody/courierTag alone. Tests below mirror the SOS section above one-for-one
    // (opaque-without-key, tamper detection, deterministic nonce, signature/impersonation,
    // no-signing-key, oversized payload) since sealCourierBody is a direct structural analogue of
    // sealSosBody, split from a would-be "sealCourier" the same way and for the same reason.

    @Suppress("LongParameterList") // wire-protocol scalars — see sealSosFixture's identical suppress
    private fun sealCourierFixture(
        rootKey: ByteArray, id: String, senderId: String, payload: ByteArray, createdAt: Long,
        signingPrivateKey: ByteArray? = null,
    ): Pair<ByteArray, ByteArray> {
        val contentKey = CryptoUtils.contentEpochKey(rootKey, createdAt / 1000)
        val sealed = MeshFrameCodec.sealCourierBody(contentKey, id, senderId, payload, createdAt, signingPrivateKey)
        return sealed to contentKey
    }

    @Test
    fun `courier body is opaque without the group key and opens correctly with it`() {
        val key = randomKey()
        val payload = "rendezvous at the old bridge, 6pm".toByteArray()
        val (sealed, contentKey) = sealCourierFixture(key, "env-1", "sender-1", payload, 1_700_000_000_000L)
        assertNull(MeshFrameCodec.openCourierBody(sealed, randomKey()))
        val body = MeshFrameCodec.openCourierBody(sealed, contentKey)
        checkNotNull(body)
        assertEquals("sender-1", body.senderId)
        assertArrayEquals(payload, body.payload)
        assertEquals(1_700_000_000_000L, body.createdAt)
    }

    @Test
    fun `tampering with any byte of a sealed courier envelope makes it fail to open`() {
        val key = randomKey()
        val (sealed, contentKey) = sealCourierFixture(
            key, "env-1", "sender-1", byteArrayOf(1, 2, 3), 1_700_000_000_000L,
        )
        val tampered = sealed.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        assertNull(MeshFrameCodec.openCourierBody(tampered, contentKey))
    }

    @Test
    fun `sealing the same courier envelope id twice produces identical ciphertext`() {
        // Same deterministic-nonce reasoning as SOS's equivalent test — an envelope id is sealed
        // once, ever; a courier that ends up holding two independently-relayed copies of the same
        // envelope must be able to dedup by comparing sealed bytes directly (later slice's concern),
        // which only holds if re-sealing identical content is byte-for-byte reproducible.
        val key = randomKey()
        val (a, _) = sealCourierFixture(key, "env-1", "sender-1", byteArrayOf(9, 9, 9), 1_700_000_000_000L)
        val (b, _) = sealCourierFixture(key, "env-1", "sender-1", byteArrayOf(9, 9, 9), 1_700_000_000_000L)
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun `courier body carries a signature, verifiable once opened`() {
        val key = randomKey()
        val pair = SenderIdentity.generateKeyPair()
        val (sealed, contentKey) = sealCourierFixture(
            key, "env-1", "sender-1", byteArrayOf(1), 1_700_000_000_000L, signingPrivateKey = pair.privateKey,
        )
        val body = MeshFrameCodec.openCourierBody(sealed, contentKey)
        checkNotNull(body)
        checkNotNull(body.signature)
        assertTrue(SenderIdentity.verify(pair.publicKey, body.signature!!, body.signedBytes))
    }

    @Test
    fun `courier signature detects impersonation even though the group-key seal is still valid`() {
        val key = randomKey()
        val impostor = SenderIdentity.generateKeyPair()
        val realSender = SenderIdentity.generateKeyPair()
        val (sealed, contentKey) = sealCourierFixture(
            key, "env-1", "real-sender-id", byteArrayOf(1), 1_700_000_000_000L,
            signingPrivateKey = impostor.privateKey,
        )
        val body = MeshFrameCodec.openCourierBody(sealed, contentKey)
        checkNotNull(body)
        checkNotNull(body.signature)
        assertFalse(SenderIdentity.verify(realSender.publicKey, body.signature!!, body.signedBytes))
        assertTrue(SenderIdentity.verify(impostor.publicKey, body.signature!!, body.signedBytes))
    }

    @Test
    fun `courier body with no signing key round-trips a null signature`() {
        val key = randomKey()
        val (sealed, contentKey) = sealCourierFixture(key, "env-1", "sender-1", byteArrayOf(1), 1_700_000_000_000L)
        val body = MeshFrameCodec.openCourierBody(sealed, contentKey)
        checkNotNull(body)
        assertNull(body.signature)
    }

    @Test
    fun `sealCourierBody rejects a payload exceeding MAX_COURIER_PAYLOAD_BYTES`() {
        val oversized = ByteArray(MeshFrameCodec.MAX_COURIER_PAYLOAD_BYTES + 1)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            MeshFrameCodec.sealCourierBody(randomKey(), "env-1", "sender-1", oversized, 1_700_000_000_000L)
        }
    }

    @Test
    fun `sealCourierBody still accepts a payload at exactly MAX_COURIER_PAYLOAD_BYTES`() {
        val key = randomKey()
        val exact = ByteArray(MeshFrameCodec.MAX_COURIER_PAYLOAD_BYTES) { it.toByte() }
        val (sealed, contentKey) = sealCourierFixture(key, "env-1", "sender-1", exact, 1_700_000_000_000L)
        val body = MeshFrameCodec.openCourierBody(sealed, contentKey)
        checkNotNull(body)
        assertArrayEquals(exact, body.payload)
    }

    @Test
    fun `courier body opens under its content epoch key but not under the raw root key`() {
        // Mirrors "sos body opens under its content epoch key but not under the raw root key" below
        // — proves this slice followed decision 39's key discipline from day one, not bolted on later.
        val rootKey = randomKey()
        val (sealed, contentKey) = sealCourierFixture(rootKey, "env-1", "sender-1", byteArrayOf(1), 1_700_000_000_000L)
        assertNotNull(MeshFrameCodec.openCourierBody(sealed, contentKey))
        assertNull(MeshFrameCodec.openCourierBody(sealed, rootKey))
    }

    @Test
    fun `courier tag resolves against the right group among several candidates and no other`() {
        // The crypto-only equivalent of GroupRepositoryHandleTest's coverage (decision 38) — proving
        // the primitive is resolvable by candidate-tag matching before any DAO-backed
        // resolveGroupKeyByCourierTag exists (deferred to a later P4 slice).
        val groups = (1..5).map { it.toString() to randomKey() }
        val (targetGroupId, targetKey) = groups[2]
        val createdAt = 1_700_000_000L
        val tag = MeshFrameCodec.courierTag(targetKey, createdAt)

        val resolved = groups.firstOrNull { (_, key) ->
            MeshFrameCodec.candidateCourierTags(key, createdAt).any { it.contentEquals(tag) }
        }
        assertEquals(targetGroupId, resolved?.first)

        val resolvedCount = groups.count { (_, key) ->
            MeshFrameCodec.candidateCourierTags(key, createdAt).any { it.contentEquals(tag) }
        }
        assertEquals(1, resolvedCount)
    }

    // ---------- courier wire frame (P4 slice 3, decision 43): FRAME_COURIER encode/decode ----------
    // Envelope only, mirroring FRAME_SOS's own split — decode() never opens sealed, it only parses
    // the cleartext envelope (tag/id/createdAt/copiesRemaining) that a blind carrier needs to dedup,
    // prune, and store without ever holding the group key.

    @Test
    fun `courier frame round-trips its cleartext envelope, readable without any key`() {
        val tag = ByteArray(16) { it.toByte() }
        val sealed = byteArrayOf(9, 9, 9, 9)
        val encoded = MeshFrameCodec.encodeCourier(tag, "env-1", 1_700_000_000_000L, 4, sealed)
        val decoded = MeshFrameCodec.decode(encoded)
        check(decoded is MeshFrameCodec.Frame.Courier)
        assertArrayEquals(tag, decoded.tag)
        assertEquals("env-1", decoded.id)
        assertEquals(1_700_000_000_000L, decoded.createdAt)
        assertEquals(4, decoded.copiesRemaining)
        assertArrayEquals(sealed, decoded.sealed)
    }

    @Test
    fun `courier frame round-trip then open recovers the original body`() {
        // End-to-end: encode the envelope, decode it back, open the sealed bytes it carried — proves
        // the wire layer and the crypto layer (decision 41) compose correctly, not just each in
        // isolation.
        val rootKey = randomKey()
        val (sealed, contentKey) = sealCourierFixture(rootKey, "env-1", "sender-1", byteArrayOf(7), 1_700_000_000_000L)
        val tag = MeshFrameCodec.courierTag(rootKey, 1_700_000_000L)
        val encoded = MeshFrameCodec.encodeCourier(tag, "env-1", 1_700_000_000_000L, 4, sealed)
        val decoded = MeshFrameCodec.decode(encoded)
        check(decoded is MeshFrameCodec.Frame.Courier)
        val body = MeshFrameCodec.openCourierBody(decoded.sealed, contentKey)
        checkNotNull(body)
        assertEquals("sender-1", body.senderId)
        assertArrayEquals(byteArrayOf(7), body.payload)
    }

    @Test
    fun `courier frame copiesRemaining coerces into an unsigned byte`() {
        val encoded = MeshFrameCodec.encodeCourier(ByteArray(16), "env-1", 0L, 999, byteArrayOf(1))
        val decoded = MeshFrameCodec.decode(encoded)
        check(decoded is MeshFrameCodec.Frame.Courier)
        assertEquals(255, decoded.copiesRemaining)
    }

    @Test
    fun `evidence meta frame round-trips including the sha256 digest`() {
        val meta = EvidenceEntity(
            id = "evid-1", groupId = "group-1", senderId = "sender-1", senderIsMe = true,
            timestamp = 1_700_000_000_000L, sha256 = fakeSha256, totalChunks = 42,
            mimeType = "image/jpeg", ttl = 8, mac = ByteArray(16) { it.toByte() }, handle = fakeHandle,
        )
        val decoded = MeshFrameCodec.decode(MeshFrameCodec.encodeEvidMeta(meta))
        check(decoded is MeshFrameCodec.Frame.EvidMeta)
        assertEquals(meta.id, decoded.id)
        assertArrayEquals(fakeHandle, decoded.handle)
        assertEquals(meta.sha256, decoded.sha256)
        assertEquals(meta.totalChunks, decoded.totalChunks)
        assertEquals(meta.mimeType, decoded.mimeType)
        assertNull(decoded.signature)
    }

    @Test
    fun `evidence meta frame round-trips a signature alongside the mac`() {
        val pair = SenderIdentity.generateKeyPair()
        val meta = EvidenceEntity(
            id = "evid-1", groupId = "group-1", senderId = "sender-1", senderIsMe = true,
            timestamp = 1_700_000_000_000L, sha256 = fakeSha256, totalChunks = 42,
            mimeType = "image/jpeg", ttl = 8, mac = ByteArray(16),
            signature = SenderIdentity.sign(pair.privateKey, "evid-1".toByteArray()), handle = fakeHandle,
        )
        val decoded = MeshFrameCodec.decode(MeshFrameCodec.encodeEvidMeta(meta))
        check(decoded is MeshFrameCodec.Frame.EvidMeta)
        assertArrayEquals(meta.signature, decoded.signature)
    }

    // ---------- thumbnail (P5 slice 1, decision 45): FRAME_EVID_META gains `thumbnail` ----------

    @Test
    fun `evidence meta frame round-trips a non-empty thumbnail`() {
        val thumb = ByteArray(200) { it.toByte() }
        val meta = EvidenceEntity(
            id = "evid-1", groupId = "group-1", senderId = "sender-1", senderIsMe = true,
            timestamp = 1_700_000_000_000L, sha256 = fakeSha256, totalChunks = 42,
            mimeType = "image/jpeg", ttl = 8, handle = fakeHandle, thumbnail = thumb,
        )
        val decoded = MeshFrameCodec.decode(MeshFrameCodec.encodeEvidMeta(meta))
        check(decoded is MeshFrameCodec.Frame.EvidMeta)
        assertArrayEquals(thumb, decoded.thumbnail)
    }

    @Test
    fun `evidence meta frame round-trips an absent thumbnail as an empty byte array`() {
        val meta = EvidenceEntity(
            id = "evid-1", groupId = "group-1", senderId = "sender-1", senderIsMe = true,
            timestamp = 1_700_000_000_000L, sha256 = fakeSha256, totalChunks = 42,
            mimeType = "image/jpeg", ttl = 8, handle = fakeHandle,
        )
        val decoded = MeshFrameCodec.decode(MeshFrameCodec.encodeEvidMeta(meta))
        check(decoded is MeshFrameCodec.Frame.EvidMeta)
        assertEquals(0, decoded.thumbnail.size)
    }

    @Test
    fun `evidence meta frame round-trips a thumbnail at exactly MAX_THUMBNAIL_BYTES`() {
        val thumb = ByteArray(MeshFrameCodec.MAX_THUMBNAIL_BYTES) { it.toByte() }
        val meta = EvidenceEntity(
            id = "evid-1", groupId = "group-1", senderId = "sender-1", senderIsMe = true,
            timestamp = 1_700_000_000_000L, sha256 = fakeSha256, totalChunks = 42,
            mimeType = "image/jpeg", ttl = 8, handle = fakeHandle, thumbnail = thumb,
        )
        val decoded = MeshFrameCodec.decode(MeshFrameCodec.encodeEvidMeta(meta))
        check(decoded is MeshFrameCodec.Frame.EvidMeta)
        assertArrayEquals(thumb, decoded.thumbnail)
    }

    @Test
    fun `decode rejects an evidence meta frame with a thumbnail over MAX_THUMBNAIL_BYTES`() {
        // Hand-crafted: encodeEvidMeta itself has no size guard (mirrors decode's own "encode()
        // trusts its caller, decode() defends against a hostile wire byte" split elsewhere in this
        // file), so this exercises decode()'s own defense directly, not via the entity path.
        val oversized = ByteArray(MeshFrameCodec.MAX_THUMBNAIL_BYTES + 1)
        val meta = EvidenceEntity(
            id = "evid-1", groupId = "group-1", senderId = "sender-1", senderIsMe = true,
            timestamp = 1_700_000_000_000L, sha256 = fakeSha256, totalChunks = 42,
            mimeType = "image/jpeg", ttl = 8, handle = fakeHandle, thumbnail = oversized,
        )
        assertNull(MeshFrameCodec.decode(MeshFrameCodec.encodeEvidMeta(meta)))
    }

    @Test
    fun `evidMacInput produces a different tag when only the thumbnail differs`() {
        // Regression guard for exactly the class of bug decision 37 already found once (SOS's own
        // writeStr16-vs-writeStr mac-input mismatch): proves thumbnail is actually covered by the
        // mac, not just present on the wire — a relay could otherwise swap it for different content
        // while sha256/mac (over the untouched full-res ciphertext) stayed valid.
        val base = MeshFrameCodec.evidMacInput(
            "evid-1", "group-1", "sender-1", 1_700_000_000_000L, fakeSha256, 42, "image/jpeg",
            byteArrayOf(1, 2, 3),
        )
        val differentThumbnail = MeshFrameCodec.evidMacInput(
            "evid-1", "group-1", "sender-1", 1_700_000_000_000L, fakeSha256, 42, "image/jpeg",
            byteArrayOf(9, 9, 9),
        )
        assertFalse(base.contentEquals(differentThumbnail))
    }

    // ---------- sealThumbnail / openThumbnail (P5 slice 1's own follow-up, decision 45) ----------
    // The cleartext-plus-MAC design shipped first, caught before landing as a real passive-exposure
    // increase, and corrected to seal the thumbnail the same way SOS/position bodies already are.

    @Test
    fun `thumbnail is opaque without the content key and opens correctly with it`() {
        val key = randomKey()
        val thumb = ByteArray(100) { it.toByte() }
        val sealed = MeshFrameCodec.sealThumbnail(key, "evid-1", thumb)
        assertFalse("a sealed thumbnail must not equal the plaintext it carries", sealed.contentEquals(thumb))
        assertNull(MeshFrameCodec.openThumbnail(sealed, randomKey()))
        assertArrayEquals(thumb, MeshFrameCodec.openThumbnail(sealed, key))
    }

    @Test
    fun `tampering with a sealed thumbnail makes it fail to open`() {
        val key = randomKey()
        val sealed = MeshFrameCodec.sealThumbnail(key, "evid-1", ByteArray(50) { it.toByte() })
        val tampered = sealed.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        assertNull(MeshFrameCodec.openThumbnail(tampered, key))
    }

    @Test
    fun `sealing the same evidence id's thumbnail twice produces identical ciphertext`() {
        // Same deterministic-nonce reasoning sealSosBody's own test gives — a thumbnail is sealed
        // exactly once, ever, so re-sealing identical content must reproduce identical bytes; this
        // is what lets encodeEvidMeta forward the stored sealed bytes verbatim across every hop.
        val key = randomKey()
        val a = MeshFrameCodec.sealThumbnail(key, "evid-1", byteArrayOf(1, 2, 3))
        val b = MeshFrameCodec.sealThumbnail(key, "evid-1", byteArrayOf(1, 2, 3))
        assertArrayEquals(a, b)
    }

    @Test
    fun `sealing under a different evidence id changes the ciphertext even for identical plaintext`() {
        val key = randomKey()
        val a = MeshFrameCodec.sealThumbnail(key, "evid-1", byteArrayOf(1, 2, 3))
        val b = MeshFrameCodec.sealThumbnail(key, "evid-2", byteArrayOf(1, 2, 3))
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `sealThumbnail and openThumbnail both no-op on an empty thumbnail`() {
        val key = randomKey()
        val sealed = MeshFrameCodec.sealThumbnail(key, "evid-1", ByteArray(0))
        assertEquals(0, sealed.size)
        assertEquals(0, MeshFrameCodec.openThumbnail(sealed, key)?.size)
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

    // Decision 39 (docs/DECISIONS.md): encodePosition now takes rootKey (for groupHandle) and
    // contentKey (for the seal) separately — mirrors sealSosFixture above.
    @Suppress("LongParameterList") // wire-protocol scalars — see MeshFrameCodec.encodePosition's identical suppress
    private fun encodePositionFixture(
        rootKey: ByteArray, senderId: String, lat: Double, lon: Double, accuracyM: Int,
        timestampSec: Long, hop: Int, signingPrivateKey: ByteArray? = null,
    ): Pair<ByteArray, ByteArray> {
        val contentKey = CryptoUtils.contentEpochKey(rootKey, timestampSec)
        val frame = MeshFrameCodec.encodePosition(
            rootKey, contentKey, senderId, lat, lon, accuracyM, timestampSec, hop, signingPrivateKey
        )
        return frame to contentKey
    }

    @Test
    fun `position frame is opaque without the group key and opens correctly with it`() {
        val key = randomKey()
        val (frame, contentKey) = encodePositionFixture(key, "sender-1", 12.3456, 78.9012, 5, 1_700_000_000L, 2)
        val decoded = MeshFrameCodec.decode(frame)
        check(decoded is MeshFrameCodec.Frame.PositionSealed)
        assertArrayEquals(MeshFrameCodec.groupHandle(key, 1_700_000_000L), decoded.handle)
        // Wrong key must not open it.
        assertNull(MeshFrameCodec.openPosition(decoded.sealed, randomKey()))
        val body = MeshFrameCodec.openPosition(decoded.sealed, contentKey)
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
        val (frame, contentKey) = encodePositionFixture(
            key, "sender-1", 1.0, 2.0, 5, 1_700_000_000L, 0,
            signingPrivateKey = pair.privateKey
        )
        val decoded = MeshFrameCodec.decode(frame)
        check(decoded is MeshFrameCodec.Frame.PositionSealed)
        val body = MeshFrameCodec.openPosition(decoded.sealed, contentKey)
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
        val (frame, contentKey) = encodePositionFixture(
            key, "real-sender-id", 1.0, 2.0, 5, 1_700_000_000L, 0,
            signingPrivateKey = impostor.privateKey // signed by the impostor, claiming to be real-sender-id
        )
        val decoded = MeshFrameCodec.decode(frame)
        check(decoded is MeshFrameCodec.Frame.PositionSealed)
        val body = MeshFrameCodec.openPosition(decoded.sealed, contentKey) // GCM auth passes — same content key
        checkNotNull(body)
        checkNotNull(body.signature)
        assertFalse(SenderIdentity.verify(realSender.publicKey, body.signature!!, body.signedBytes))
        assertTrue(SenderIdentity.verify(impostor.publicKey, body.signature!!, body.signedBytes))
    }

    @Test
    fun `position frame with no signing key round-trips a null signature`() {
        val key = randomKey()
        val (frame, contentKey) = encodePositionFixture(key, "sender-1", 1.0, 2.0, 5, 1_700_000_000L, 0)
        val decoded = MeshFrameCodec.decode(frame)
        check(decoded is MeshFrameCodec.Frame.PositionSealed)
        val body = MeshFrameCodec.openPosition(decoded.sealed, contentKey)
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
        val (a, contentKey) = encodePositionFixture(key, "sender-1", 1.0, 2.0, 5, 1_700_000_000L, 0)
        val (b, _) = encodePositionFixture(key, "sender-1", 1.0, 2.0, 5, 1_700_000_000L, 0)
        val decodedA = MeshFrameCodec.decode(a) as MeshFrameCodec.Frame.PositionSealed
        val decodedB = MeshFrameCodec.decode(b) as MeshFrameCodec.Frame.PositionSealed
        assertTrue(!decodedA.sealed.contentEquals(decodedB.sealed))
        // Both must still open correctly despite identical plaintext/key/second.
        assertEquals(1.0, MeshFrameCodec.openPosition(decodedA.sealed, contentKey)!!.lat, 1e-6)
        assertEquals(1.0, MeshFrameCodec.openPosition(decodedB.sealed, contentKey)!!.lat, 1e-6)
    }

    // ---------- decision 39 (docs/DECISIONS.md): position body is sealed under the content epoch ----------
    // key, not the root key directly — proven empirically here, not just by the two-param signature.
    @Test
    fun `position body opens under its content epoch key but not under the raw root key`() {
        val rootKey = randomKey()
        val (frame, contentKey) = encodePositionFixture(rootKey, "sender-1", 1.0, 2.0, 5, 1_700_000_000L, 0)
        val decoded = MeshFrameCodec.decode(frame) as MeshFrameCodec.Frame.PositionSealed
        assertNotNull(MeshFrameCodec.openPosition(decoded.sealed, contentKey))
        assertNull(MeshFrameCodec.openPosition(decoded.sealed, rootKey))
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
        val nick =
            NicknameEntity("group-1", "sender-1", tooLong, 1_700_000_000_000L, ByteArray(16), handle = fakeHandle)
        val decoded = MeshFrameCodec.decode(MeshFrameCodec.encodeNickname(nick))
        check(decoded is MeshFrameCodec.Frame.Nickname)
        assertArrayEquals(fakeHandle, decoded.handle)
        assertEquals(MeshFrameCodec.MAX_USERNAME_CHARS, decoded.username.length)
        assertTrue(tooLong.startsWith(decoded.username))
        assertNull(decoded.signature)
    }

    @Test
    fun `nickname frame round-trips a signature alongside the mac`() {
        val pair = SenderIdentity.generateKeyPair()
        val nick = NicknameEntity(
            "group-1", "sender-1", "responder", 1_700_000_000_000L,
            mac = ByteArray(16), signature = SenderIdentity.sign(pair.privateKey, "responder".toByteArray()),
            handle = fakeHandle,
        )
        val decoded = MeshFrameCodec.decode(MeshFrameCodec.encodeNickname(nick))
        check(decoded is MeshFrameCodec.Frame.Nickname)
        assertArrayEquals(nick.signature, decoded.signature)
    }

    @Test
    fun `reframeNicknameForRelay re-encodes every field unchanged`() {
        // New in decision 38 (docs/DECISIONS.md) — nickname never had a reframe function before,
        // since its old vacuous-auth blind-relay path never needed to move opaque bytes forward
        // (see RelayResponder.takeOpaqueNicknameCustody's doc for why that changes here). No hop/
        // ttl field exists on this frame type, so "reframe" is a structural no-op re-encode; this
        // test is what proves it really is byte-for-byte identical, not just in shape.
        val pair = SenderIdentity.generateKeyPair()
        val nick = NicknameEntity(
            "group-1", "sender-1", "responder", 1_700_000_000_000L,
            mac = ByteArray(16) { it.toByte() },
            signature = SenderIdentity.sign(pair.privateKey, "responder".toByteArray()),
            handle = fakeHandle,
        )
        val original = MeshFrameCodec.decode(MeshFrameCodec.encodeNickname(nick)) as MeshFrameCodec.Frame.Nickname

        val relayed =
            MeshFrameCodec.decode(MeshFrameCodec.reframeNicknameForRelay(original)) as MeshFrameCodec.Frame.Nickname

        assertArrayEquals(original.handle, relayed.handle)
        assertEquals(original.senderId, relayed.senderId)
        assertEquals(original.username, relayed.username)
        assertEquals(original.updatedAt, relayed.updatedAt)
        assertArrayEquals(original.mac, relayed.mac)
        assertArrayEquals(original.signature, relayed.signature)
    }

    @Test
    fun `presence frame round-trips and carries a verifiable tag`() {
        val key = randomKey()
        val contentKey = CryptoUtils.contentEpochKey(key, 1_700_000_000_000L / 1000)
        val frame = MeshFrameCodec.encodePresence("group-1", "sender-1", 1_700_000_000_000L, key, contentKey)
        val decoded = MeshFrameCodec.decode(frame)
        check(decoded is MeshFrameCodec.Frame.Presence)
        assertArrayEquals(MeshFrameCodec.groupHandle(key, 1_700_000_000_000L / 1000), decoded.handle)
        assertEquals("sender-1", decoded.senderId)
        assertNull(decoded.senderPublicKey)
        assertNull(decoded.signature)
    }

    @Test
    fun `presence frame round-trips a sender public key and signature`() {
        val key = randomKey()
        val contentKey = CryptoUtils.contentEpochKey(key, 1_700_000_000_000L / 1000)
        val pair = SenderIdentity.generateKeyPair()
        val frame = MeshFrameCodec.encodePresence(
            "group-1", "sender-1", 1_700_000_000_000L, key, contentKey,
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
        val presenceKey = randomKey()
        val encoded = MeshFrameCodec.encodePresence(
            "g", "s", 0L, presenceKey, CryptoUtils.contentEpochKey(presenceKey, 0L)
        )
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
            mimeType = "image/jpeg", ttl = 8, mac = ByteArray(16), handle = fakeHandle,
        )
        assertNull(MeshFrameCodec.decode(MeshFrameCodec.encodeEvidMeta(meta)))
    }

    @Test
    fun `decode rejects an evidence meta frame with a negative or zero totalChunks`() {
        val negative = EvidenceEntity(
            id = "evid-1", groupId = "group-1", senderId = "sender-1", senderIsMe = true,
            timestamp = 1_700_000_000_000L, sha256 = fakeSha256, totalChunks = -1,
            mimeType = "image/jpeg", ttl = 8, mac = ByteArray(16), handle = fakeHandle,
        )
        assertNull(MeshFrameCodec.decode(MeshFrameCodec.encodeEvidMeta(negative)))
        val zero = negative.copy(totalChunks = 0)
        assertNull(MeshFrameCodec.decode(MeshFrameCodec.encodeEvidMeta(zero)))
    }

    @Test
    fun `decode still accepts a legitimate evidence meta frame under the cap`() {
        val meta = EvidenceEntity(
            id = "evid-1", groupId = "group-1", senderId = "sender-1", senderIsMe = true,
            timestamp = 1_700_000_000_000L, sha256 = fakeSha256, totalChunks = 200, handle = fakeHandle,
            mimeType = "image/jpeg", ttl = 8, mac = ByteArray(16)
        )
        val decoded = MeshFrameCodec.decode(MeshFrameCodec.encodeEvidMeta(meta))
        check(decoded is MeshFrameCodec.Frame.EvidMeta)
        assertEquals(200, decoded.totalChunks)
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

    // ---------- padGattFrame / unpadGattFrame (P6 item 4, PLAN-v2.md §4.4) ----------
    // These wrap the GATT transport, not decode()/encode() themselves — see padGattFrame's own doc
    // for why (Tier B reuses encodePosition/encodeCatalogFilter directly for a far tighter budget
    // and must never see a bucket-padded frame). Tests here exercise the wrapper in isolation,
    // independent of any particular frame type's own field layout.

    @Test
    fun `padGattFrame rounds up to the smallest bucket that fits`() {
        // 2-byte length prefix + payload must fit under the bucket, not just the payload alone.
        assertEquals(256, MeshFrameCodec.padGattFrame(ByteArray(1)).size)
        assertEquals(256, MeshFrameCodec.padGattFrame(ByteArray(254)).size) // 2 + 254 == 256, exact fit
        assertEquals(512, MeshFrameCodec.padGattFrame(ByteArray(255)).size) // 2 + 255 == 257, rolls over
        assertEquals(512, MeshFrameCodec.padGattFrame(ByteArray(510)).size)
        assertEquals(1024, MeshFrameCodec.padGattFrame(ByteArray(511)).size)
        assertEquals(1024, MeshFrameCodec.padGattFrame(ByteArray(1022)).size) // 2 + 1022 == 1024, exact fit
        assertEquals(2048, MeshFrameCodec.padGattFrame(ByteArray(1023)).size) // 2 + 1023 == 1025, rolls over
    }

    @Test
    fun `padGattFrame sends a frame past the largest bucket unpadded, prefix only`() {
        val big = ByteArray(3000) { it.toByte() }
        val padded = MeshFrameCodec.padGattFrame(big)
        assertEquals(2 + big.size, padded.size)
        assertArrayEquals(big, MeshFrameCodec.unpadGattFrame(padded))
    }

    @Test
    fun `unpadGattFrame recovers the exact original bytes across every bucket`() {
        for (size in intArrayOf(0, 1, 254, 255, 400, 510, 511, 1022, 1023, 2046, 2047)) {
            val original = ByteArray(size) { (it % 256).toByte() }
            val padded = MeshFrameCodec.padGattFrame(original)
            assertArrayEquals("size=$size", original, MeshFrameCodec.unpadGattFrame(padded))
        }
    }

    @Test
    fun `padGattFrame draws padding from randomBytes, not zero-fill`() {
        // A frame short enough to leave real padding bytes: 1-byte payload -> 256-byte bucket ->
        // 253 bytes of padding. All-zero padding would make a bucket-padded EVID_CHUNK/SOS trivially
        // distinguishable from real ciphertext by content, defeating the point of bucketing sizes.
        val padded = MeshFrameCodec.padGattFrame(ByteArray(1))
        val tail = padded.copyOfRange(3, padded.size)
        assertTrue(tail.any { it != 0.toByte() })
    }

    @Test
    fun `unpadGattFrame rejects truncated input`() {
        assertNull(MeshFrameCodec.unpadGattFrame(ByteArray(0)))
        assertNull(MeshFrameCodec.unpadGattFrame(ByteArray(1)))
        // Claims a 10-byte frame but only 5 bytes actually follow the length prefix.
        val malformed = byteArrayOf(0, 10, 1, 2, 3, 4, 5)
        assertNull(MeshFrameCodec.unpadGattFrame(malformed))
    }

    @Test
    fun `unpadGattFrame accepts exactly the claimed length with zero padding remaining`() {
        val frame = byteArrayOf(0, 3, 9, 9, 9)
        assertArrayEquals(byteArrayOf(9, 9, 9), MeshFrameCodec.unpadGattFrame(frame))
    }

    @Test
    fun `padGattFrame then unpadGattFrame then decode round-trips a real frame end to end`() {
        val bitset = MeshProtocol.encodeBitset(setOf(0, 2), 5)
        val real = rawManifestFrame("evid-1", 5, bitset)
        val onWire = MeshFrameCodec.padGattFrame(real)
        assertTrue("padding should change the wire size for a small frame", onWire.size > real.size)
        val recovered = MeshFrameCodec.unpadGattFrame(onWire)
        assertArrayEquals(real, recovered)
        val decoded = MeshFrameCodec.decode(recovered!!)
        check(decoded is MeshFrameCodec.Frame.Manifest)
        assertEquals(5, decoded.totalChunks)
    }

    @Test
    fun `padGattFrame padding does not leak into decode for FRAME_EVID_CHUNK, which reads via remaining()`() {
        // The one frame type with no internal length field of its own — decode() takes
        // buf.remaining() as the chunk payload verbatim. Padding MUST be stripped before decode()
        // ever sees these bytes, or this frame type would silently absorb trailing pad bytes as
        // chunk data. This exercises that specifically, not just round-tripping arbitrary bytes.
        val chunk = MeshFrameCodec.encodeChunk(EvidenceChunkEntity("evid-1", 2, byteArrayOf(1, 2, 3, 4, 5)))
        val onWire = MeshFrameCodec.padGattFrame(chunk)
        assertTrue(onWire.size > chunk.size)
        val recovered = MeshFrameCodec.unpadGattFrame(onWire)
        val decoded = MeshFrameCodec.decode(recovered!!)
        check(decoded is MeshFrameCodec.Frame.EvidChunk)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), decoded.chunk.data)
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
        val (sealed, contentKey) = sealSosFixture(
            key, "sos-1", "sender-1", "x".repeat(MeshFrameCodec.MAX_SOS_MESSAGE_BYTES + 1),
            1000L, isAlert = false, ttl = 8, hop = 0,
        )
        val decoded = MeshFrameCodec.decode(sealed)
        check(decoded is MeshFrameCodec.Frame.SosSealed)
        assertNull(MeshFrameCodec.openSos(decoded.sealed, contentKey))
    }

    @Test
    fun `openSos still accepts a message at exactly MAX_SOS_MESSAGE_BYTES`() {
        val key = randomKey()
        val (sealed, contentKey) = sealSosFixture(
            key, "sos-1", "sender-1", "x".repeat(MeshFrameCodec.MAX_SOS_MESSAGE_BYTES),
            1000L, isAlert = false, ttl = 8, hop = 0,
        )
        val decoded = MeshFrameCodec.decode(sealed)
        check(decoded is MeshFrameCodec.Frame.SosSealed)
        val body = MeshFrameCodec.openSos(decoded.sealed, contentKey)
        checkNotNull(body)
        assertEquals(MeshFrameCodec.MAX_SOS_MESSAGE_BYTES, body.message.length)
    }

    @Test
    fun `position frame carries its hop in the cleartext envelope, readable without any key`() {
        // The property blind relaying depends on: a phone with no group key must still be able to
        // read and increment the hop. If this ever moves back inside the seal, non-member relays
        // silently stop forwarding positions again (see Frame.PositionSealed's doc).
        val key = randomKey()
        val (frame, _) = encodePositionFixture(key, "sender-1", 1.0, 2.0, 5, 1_700_000_000L, hop = 2)
        val decoded = MeshFrameCodec.decode(frame)
        check(decoded is MeshFrameCodec.Frame.PositionSealed)
        assertEquals(2, decoded.hop)
    }

    @Test
    fun `reframePositionForRelay changes only the hop, never the sealed bytes`() {
        val key = randomKey()
        val (frameBytes, contentKey) = encodePositionFixture(key, "sender-1", 1.0, 2.0, 5, 1_700_000_000L, hop = 0)
        val original = MeshFrameCodec.decode(frameBytes) as MeshFrameCodec.Frame.PositionSealed

        val relayed = MeshFrameCodec.decode(
            MeshFrameCodec.reframePositionForRelay(original.handle, original.hop + 1, original.sealed)
        ) as MeshFrameCodec.Frame.PositionSealed

        assertEquals(1, relayed.hop)
        assertTrue(original.sealed.contentEquals(relayed.sealed))
        // And it still opens correctly for an actual member, unchanged by the relay hop.
        val body = MeshFrameCodec.openPosition(relayed.sealed, contentKey)
        checkNotNull(body)
        assertEquals("sender-1", body.senderId)
    }

    @Test
    fun `presence carries an envelope hop and reframes for relay without any key`() {
        // The GPS-less member case: presence is the ONLY thing that can carry them outward, so a
        // relay holding no group key must be able to advance its hop. Nothing but the hop may change
        // — the mac a real member verifies has to survive the relay byte-for-byte.
        val key = randomKey()
        val contentKey = CryptoUtils.contentEpochKey(key, 1_700_000_000_000L / 1000)
        val original = MeshFrameCodec.decode(
            MeshFrameCodec.encodePresence("group-1", "sender-1", 1_700_000_000_000L, key, contentKey)
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
        assertTrue(CryptoUtils.constantTimeEquals(CryptoUtils.authTag(contentKey, macInput), relayed.mac))
    }
}
