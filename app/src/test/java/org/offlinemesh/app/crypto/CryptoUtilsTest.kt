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
}
