package org.offlinemesh.app.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tier 1 (pure JVM, see TESTING.md) — the same round-trip/tamper-detection properties
 *  [CryptoUtilsTest] already covers for the group HMAC/AES-GCM layer, applied to the per-sender
 *  Ed25519 signature layer instead. */
class SenderIdentityTest {

    @Test
    fun `sign then verify accepts a genuine signature`() {
        val pair = SenderIdentity.generateKeyPair()
        val data = "sos:abc:group:sender:hello:12345".toByteArray()
        val signature = SenderIdentity.sign(pair.privateKey, data)
        assertTrue(SenderIdentity.verify(pair.publicKey, signature, data))
    }

    @Test
    fun `verify rejects a signature checked against the wrong public key`() {
        val pair = SenderIdentity.generateKeyPair()
        val impostor = SenderIdentity.generateKeyPair()
        val data = "some canonical bytes".toByteArray()
        val signature = SenderIdentity.sign(pair.privateKey, data)
        assertFalse(SenderIdentity.verify(impostor.publicKey, signature, data))
    }

    @Test
    fun `verify detects a single flipped byte in the signed data`() {
        val pair = SenderIdentity.generateKeyPair()
        val signature = SenderIdentity.sign(pair.privateKey, "original message".toByteArray())
        val tampered = "originai message".toByteArray() // one byte flipped, same length
        assertFalse(SenderIdentity.verify(pair.publicKey, signature, tampered))
    }

    @Test
    fun `verify detects a single flipped byte in the signature itself`() {
        val pair = SenderIdentity.generateKeyPair()
        val data = "message body".toByteArray()
        val signature = SenderIdentity.sign(pair.privateKey, data)
        val tampered = signature.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertFalse(SenderIdentity.verify(pair.publicKey, tampered, data))
    }

    @Test
    fun `verify rejects a malformed (wrong-length) signature rather than throwing`() {
        val pair = SenderIdentity.generateKeyPair()
        val data = "message body".toByteArray()
        assertFalse(SenderIdentity.verify(pair.publicKey, ByteArray(3), data))
    }

    @Test
    fun `two generated keypairs are distinct`() {
        val a = SenderIdentity.generateKeyPair()
        val b = SenderIdentity.generateKeyPair()
        assertNotEquals(a.publicKey.toList(), b.publicKey.toList())
        assertNotEquals(a.privateKey.toList(), b.privateKey.toList())
    }
}
