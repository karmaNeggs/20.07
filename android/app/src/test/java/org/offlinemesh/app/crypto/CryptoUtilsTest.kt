package org.offlinemesh.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Tier 1 of the test rig (see TESTING.md): pure JVM, no Android framework, no device. Covers
 * exactly the properties a safety-relevant crypto layer needs to hold — round-trips, tamper
 * detection, and the rotating-id window math that both the beacon and the group-matching cache
 * depend on.
 */
class CryptoUtilsTest {

    private fun randomKey(len: Int = 32) = ByteArray(len).also { SecureRandom().nextBytes(it) }

    @Test
    fun `encrypt then decrypt returns the original plaintext`() {
        val key = randomKey()
        val plaintext = "the quick brown fox".toByteArray()
        val ciphertext = CryptoUtils.encrypt(key, plaintext)
        assertArrayEquals(plaintext, CryptoUtils.decrypt(key, ciphertext))
    }

    @Test
    fun `decrypt with the wrong key fails rather than returning garbage`() {
        val ciphertext = CryptoUtils.encrypt(randomKey(), "secret".toByteArray())
        assertNull(CryptoUtils.decrypt(randomKey(), ciphertext))
    }

    @Test
    fun `decrypt detects a single flipped ciphertext byte`() {
        val key = randomKey()
        val ciphertext = CryptoUtils.encrypt(key, "message body".toByteArray())
        val tampered = ciphertext.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        assertNull(CryptoUtils.decrypt(key, tampered))
    }

    @Test
    fun `two encryptions of the same plaintext produce different ciphertext`() {
        // Confirms a fresh random IV each call — reusing an IV under GCM is a real, silent
        // confidentiality break, not just a style nit.
        val key = randomKey()
        val a = CryptoUtils.encrypt(key, "same message".toByteArray())
        val b = CryptoUtils.encrypt(key, "same message".toByteArray())
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `rotating advertisement id is stable within the same window`() {
        val key = randomKey()
        val epoch = 1_700_000_000L
        assertArrayEquals(
            CryptoUtils.rotatingAdvertisementId(key, epoch),
            CryptoUtils.rotatingAdvertisementId(key, epoch + 5) // same 60s window
        )
    }

    @Test
    fun `rotating advertisement id changes across a window boundary`() {
        val key = randomKey()
        val id1 = CryptoUtils.rotatingAdvertisementId(key, 1_700_000_000L)
        val id2 = CryptoUtils.rotatingAdvertisementId(key, 1_700_000_000L + CryptoUtils.ID_WINDOW_SECONDS)
        assertFalse(id1.contentEquals(id2))
    }

    @Test
    fun `candidate ids tolerate up to one window of clock skew either direction`() {
        val key = randomKey()
        val now = 1_700_000_000L
        val mineNow = CryptoUtils.rotatingAdvertisementId(key, now)
        // A peer up to ~60s ahead or behind must still appear in our candidate set.
        val candidatesForSkewedPeer = CryptoUtils.candidateAdvertisementIds(key, now + CryptoUtils.ID_WINDOW_SECONDS)
        assertTrue(candidatesForSkewedPeer.any { it.contentEquals(mineNow) })
    }

    @Test
    fun `auth tag verifies for unmodified data and fails for tampered data`() {
        val key = randomKey()
        val data = "sos payload fields concatenated".toByteArray()
        val tag = CryptoUtils.authTag(key, data)
        assertTrue(CryptoUtils.constantTimeEquals(tag, CryptoUtils.authTag(key, data)))
        val tamperedData = "sos payload fields CONCATENATED".toByteArray()
        assertFalse(CryptoUtils.constantTimeEquals(tag, CryptoUtils.authTag(key, tamperedData)))
    }

    @Test
    fun `auth tag differs under a different key`() {
        val data = "same content".toByteArray()
        val tagA = CryptoUtils.authTag(randomKey(), data)
        val tagB = CryptoUtils.authTag(randomKey(), data)
        assertFalse(tagA.contentEquals(tagB))
    }

    @Test
    fun `constant time equals rejects null and mismatched-length input`() {
        val tag = CryptoUtils.authTag(randomKey(), "x".toByteArray())
        assertFalse(CryptoUtils.constantTimeEquals(tag, null))
        assertFalse(CryptoUtils.constantTimeEquals(tag, ByteArray(1)))
    }

    @Test
    fun `sha256Hex is deterministic and sensitive to a single bit`() {
        val a = CryptoUtils.sha256Hex("hello".toByteArray())
        val b = CryptoUtils.sha256Hex("hello".toByteArray())
        val c = CryptoUtils.sha256Hex("Hello".toByteArray())
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertEquals(64, a.length) // 32 bytes, hex-encoded
    }

    @Test
    fun `sha256 raw bytes hex-encode to the same value as sha256Hex`() {
        val bytes = CryptoUtils.sha256("hello".toByteArray())
        assertEquals(32, bytes.size)
        assertEquals(CryptoUtils.sha256Hex("hello".toByteArray()), bytes.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `encryptWithNonce then decrypt returns the original plaintext`() {
        val key = randomKey()
        val nonce = ByteArray(12) { it.toByte() }
        val plaintext = "position body".toByteArray()
        val ciphertext = CryptoUtils.encryptWithNonce(key, plaintext, nonce)
        assertArrayEquals(plaintext, CryptoUtils.decrypt(key, ciphertext))
        assertArrayEquals(nonce, ciphertext.copyOfRange(0, 12)) // the exact nonce we gave it, not a random one
    }

    @Test
    fun `encryptWithNonce rejects a nonce of the wrong length`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            CryptoUtils.encryptWithNonce(randomKey(), "x".toByteArray(), ByteArray(11))
        }
    }

    @Test
    fun `reusing the same nonce under the same key produces identical ciphertext`() {
        // This is the exact property that makes GCM nonce reuse dangerous — confirming it here
        // documents *why* encodePosition's nonce construction (sha256(senderId) prefix + timestamp
        // + counter) must never repeat, rather than asserting that indirectly only.
        val key = randomKey()
        val nonce = ByteArray(12) { it.toByte() }
        val a = CryptoUtils.encryptWithNonce(key, "same plaintext".toByteArray(), nonce)
        val b = CryptoUtils.encryptWithNonce(key, "same plaintext".toByteArray(), nonce)
        assertArrayEquals(a, b)
    }

    // ---------- windowSeconds (decision 38, docs/DECISIONS.md): generalized in place ----------
    // rotatingAdvertisementId/candidateAdvertisementIds gained an explicit windowSeconds param so
    // MeshFrameCodec.groupHandle (GATT_GROUP_HANDLE_WINDOW_SECONDS, 72h) could reuse the exact same
    // HMAC(groupKey, epoch) construction the beacon's own ID_WINDOW_SECONDS (60s) already used,
    // rather than duplicating it. Every test above calls these with no windowSeconds argument at
    // all, which is itself proof the beacon's own default-param behavior is unaffected.

    @Test
    fun `a custom windowSeconds changes the rotation cadence`() {
        val key = randomKey()
        val epoch = 1_700_000_000L
        // Within the same custom window, still stable...
        assertArrayEquals(
            CryptoUtils.rotatingAdvertisementId(key, epoch, windowSeconds = 3600L),
            CryptoUtils.rotatingAdvertisementId(key, epoch + 1800, windowSeconds = 3600L),
        )
        // ...but the default 60s window would already have rotated past that same +1800s gap.
        assertFalse(
            CryptoUtils.rotatingAdvertisementId(key, epoch).contentEquals(
                CryptoUtils.rotatingAdvertisementId(key, epoch + 1800)
            )
        )
    }

    @Test
    fun `the GATT window and the beacon's own window never collide for the same key and epoch`() {
        // Domain separation, confirmed empirically (not just argued in the doc comment): the same
        // groupKey computing a handle for both purposes at the same instant must never produce the
        // same bytes, which would otherwise let a GATT frame be mistaken for a beacon id or vice versa.
        val key = randomKey()
        val epoch = 1_700_000_000L
        val beaconId = CryptoUtils.rotatingAdvertisementId(key, epoch)
        val gattHandle = CryptoUtils.rotatingAdvertisementId(key, epoch, CryptoUtils.GATT_GROUP_HANDLE_WINDOW_SECONDS)
        assertFalse(beaconId.contentEquals(gattHandle))
    }

    @Test
    fun `candidate handles under the GATT window tolerate real elapsed time, not just clock skew`() {
        // The property GroupRepository.resolveGroupKeyByHandle depends on: a handle computed once
        // at creation time must still be one of the receiver's 3 candidates even hours later, since
        // a GATT frame (unlike a beacon payload) can realistically sit in a relay queue that long.
        val key = randomKey()
        val createdAt = 1_700_000_000L
        val handleAtCreation = CryptoUtils.rotatingAdvertisementId(
            key, createdAt, CryptoUtils.GATT_GROUP_HANDLE_WINDOW_SECONDS
        )
        val receivedMuchLater = createdAt + 6 * 60 * 60 // 6 hours later, well past any beacon-scale tolerance
        val candidatesAtReceiveTime = CryptoUtils.candidateAdvertisementIds(
            key, receivedMuchLater, CryptoUtils.GATT_GROUP_HANDLE_WINDOW_SECONDS
        )
        assertTrue(candidatesAtReceiveTime.any { it.contentEquals(handleAtCreation) })
    }

    // ---------- contentEpochKey / candidateContentEpochKeys (decision 39, docs/DECISIONS.md) ----------
    // NOT a forward-secrecy mechanism — see CryptoUtils' own class doc and decision 39's write-up
    // for why a non-interactive derivation from a permanently-retained root key can never be one.
    // What these tests confirm: the derivation is stable within an epoch, rotates across a boundary,
    // is domain-separated from the wire-handle derivation (rotatingAdvertisementId), and the
    // candidate list actually covers the backward window it claims to.

    @Test
    fun `content epoch key is stable within the same epoch`() {
        val key = randomKey()
        val epoch = 1_700_000_000L
        assertArrayEquals(
            CryptoUtils.contentEpochKey(key, epoch),
            CryptoUtils.contentEpochKey(key, epoch + 3600), // same 24h epoch
        )
    }

    @Test
    fun `content epoch key changes across an epoch boundary`() {
        val key = randomKey()
        val keyBefore = CryptoUtils.contentEpochKey(key, 1_700_000_000L)
        val keyAfter = CryptoUtils.contentEpochKey(key, 1_700_000_000L + CryptoUtils.CONTENT_EPOCH_SECONDS)
        assertFalse(keyBefore.contentEquals(keyAfter))
    }

    @Test
    fun `content epoch key is deterministic for the same key and epoch`() {
        val key = randomKey()
        val epoch = 1_700_000_000L
        assertArrayEquals(CryptoUtils.contentEpochKey(key, epoch), CryptoUtils.contentEpochKey(key, epoch))
    }

    @Test
    fun `content epoch key differs under a different root key`() {
        val epoch = 1_700_000_000L
        val keyA = CryptoUtils.contentEpochKey(randomKey(), epoch)
        val keyB = CryptoUtils.contentEpochKey(randomKey(), epoch)
        assertFalse(keyA.contentEquals(keyB))
    }

    @Test
    fun `content epoch key never collides with the wire handle derived from the same root key`() {
        // Domain separation, confirmed empirically: the same root key producing both a wire handle
        // and a content-sealing key at the same instant must never produce the same bytes — mirrors
        // "the GATT window and the beacon's own window never collide" above for this newer pair.
        val key = randomKey()
        val epoch = 1_700_000_000L
        val wireHandle = CryptoUtils.rotatingAdvertisementId(key, epoch, CryptoUtils.GATT_GROUP_HANDLE_WINDOW_SECONDS)
        val contentKey = CryptoUtils.contentEpochKey(key, epoch)
        assertFalse(wireHandle.contentEquals(contentKey))
    }

    @Test
    fun `candidate content epoch keys include the current epoch's key`() {
        val key = randomKey()
        val now = 1_700_000_000L
        val current = CryptoUtils.contentEpochKey(key, now)
        assertTrue(CryptoUtils.candidateContentEpochKeys(key, now).any { it.contentEquals(current) })
    }

    @Test
    fun `candidate content epoch keys still resolve a creation-time key up to 72h later`() {
        // 72h is decision 38's own worst-case content-lifetime figure (GATT_GROUP_HANDLE_WINDOW_SECONDS);
        // the candidate list's 96h backward coverage (4 epochs at 24h each) must clear it with margin.
        val key = randomKey()
        val createdAt = 1_700_000_000L
        val keyAtCreation = CryptoUtils.contentEpochKey(key, createdAt)
        val receivedMuchLater = createdAt + 72L * 60 * 60
        val candidatesAtReceiveTime = CryptoUtils.candidateContentEpochKeys(key, receivedMuchLater)
        assertTrue(candidatesAtReceiveTime.any { it.contentEquals(keyAtCreation) })
    }

    // ---------- courier tag truncateLen + COURIER_TAG_WINDOW_SECONDS (P4 slice 1, decision 40) ----------
    // Crypto construction only in this slice — see MeshFrameCodec.courierTag's own doc. Tests here
    // exercise CryptoUtils' generalized rotatingAdvertisementId/candidateAdvertisementIds directly,
    // the same way "a custom windowSeconds changes the rotation cadence" above exercises windowSeconds
    // in isolation before MeshFrameCodec.groupHandle's own wrapper existed to call it.

    @Test
    fun `a custom truncateLen changes the output length while windowSeconds stays the same`() {
        val key = randomKey()
        val epoch = 1_700_000_000L
        val default = CryptoUtils.rotatingAdvertisementId(key, epoch)
        val wide = CryptoUtils.rotatingAdvertisementId(key, epoch, truncateLen = CryptoUtils.COURIER_TAG_LEN)
        assertEquals(6, default.size)
        assertEquals(16, wide.size)
        // The wide id's first 6 bytes must still match the default's — truncation is a prefix of the
        // same HMAC output, not a differently-derived value.
        assertArrayEquals(default, wide.copyOf(6))
    }

    @Test
    fun `courier tag window never collides with the beacon or GATT handle windows for the same key and epoch`() {
        // Extends "the GATT window and the beacon's own window never collide" (above) to a third
        // partition, at a FIXED truncateLen (16, courier's own length) for all three — proving
        // separation comes from the window math (window.toString() differing) rather than
        // incidentally from the three constructions producing different output lengths.
        val key = randomKey()
        val epoch = 1_700_000_000L
        val len = CryptoUtils.COURIER_TAG_LEN
        val beaconId = CryptoUtils.rotatingAdvertisementId(key, epoch, truncateLen = len)
        val gattHandle = CryptoUtils.rotatingAdvertisementId(
            key, epoch, CryptoUtils.GATT_GROUP_HANDLE_WINDOW_SECONDS, len,
        )
        val courierTag = CryptoUtils.rotatingAdvertisementId(
            key, epoch, CryptoUtils.COURIER_TAG_WINDOW_SECONDS, len,
        )
        assertFalse(beaconId.contentEquals(gattHandle))
        assertFalse(beaconId.contentEquals(courierTag))
        assertFalse(gattHandle.contentEquals(courierTag))
    }

    @Test
    fun `courier tag is stable within the same UTC day and rotates at the day boundary`() {
        val key = randomKey()
        val midday = 1_700_000_000L // an arbitrary instant well inside some UTC day
        val sameDayLater = midday + 3600 // 1h later, same day
        val nextDayWindow = CryptoUtils.COURIER_TAG_WINDOW_SECONDS
        assertArrayEquals(
            CryptoUtils.rotatingAdvertisementId(key, midday, nextDayWindow, 16),
            CryptoUtils.rotatingAdvertisementId(key, sameDayLater, nextDayWindow, 16),
        )
        val nextDay = midday + nextDayWindow
        assertFalse(
            CryptoUtils.rotatingAdvertisementId(key, midday, nextDayWindow, 16).contentEquals(
                CryptoUtils.rotatingAdvertisementId(key, nextDay, nextDayWindow, 16)
            )
        )
    }

    @Test
    fun `candidate courier tags tolerate up to one day of skew, either direction`() {
        val key = randomKey()
        val createdAt = 1_700_000_000L
        val window = CryptoUtils.COURIER_TAG_WINDOW_SECONDS
        val tagAtCreation = CryptoUtils.rotatingAdvertisementId(key, createdAt, window, 16)
        val candidatesJustBefore = CryptoUtils.candidateAdvertisementIds(key, createdAt - window, window, 16)
        val candidatesJustAfter = CryptoUtils.candidateAdvertisementIds(key, createdAt + window, window, 16)
        assertTrue(candidatesJustBefore.any { it.contentEquals(tagAtCreation) })
        assertTrue(candidatesJustAfter.any { it.contentEquals(tagAtCreation) })
    }
}
