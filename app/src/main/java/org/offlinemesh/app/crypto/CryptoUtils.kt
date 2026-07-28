package org.offlinemesh.app.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Group keys are random (see JoinCode.generate), not derived from a typed passphrase — nothing
 * here derives a key anymore, only uses one. No secret ever leaves the device in plaintext over
 * the mesh; only the rotating id derived from it does.
 */
object CryptoUtils {

    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_LEN_BITS = 128
    const val ID_WINDOW_SECONDS = 60L

    // 6 bytes = 48 bits of entropy per rotating window — still astronomically collision-safe
    // for this purpose. Kept deliberately short: legacy BLE advertising has a hard 31-byte
    // total limit (Android auto-adds 3 bytes for a Flags structure you don't control), and
    // every byte here is a byte the whole beacon payload has to fit inside alongside its
    // header overhead (found live during device testing — the original 8-byte id, combined
    // with an also-advertised Service UUID list, silently overflowed the limit and meant
    // advertising was failing outright, so phones never discovered each other at all).
    private const val ROTATING_ID_LEN = 6

    /** Rotating pseudonymous beacon id for this group, changes every ID_WINDOW_SECONDS. */
    fun rotatingAdvertisementId(groupKey: ByteArray, epochSeconds: Long = System.currentTimeMillis() / 1000): ByteArray {
        val window = epochSeconds / ID_WINDOW_SECONDS
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(groupKey, "HmacSHA256"))
        val windowBytes = window.toString().toByteArray()
        return mac.doFinal(windowBytes).copyOfRange(0, ROTATING_ID_LEN)
    }

    /** Candidate ids for current + adjacent windows, to tolerate clock drift between phones. */
    fun candidateAdvertisementIds(groupKey: ByteArray, nowSeconds: Long = System.currentTimeMillis() / 1000): List<ByteArray> {
        val window = nowSeconds / ID_WINDOW_SECONDS
        return listOf(window - 1, window, window + 1).map { w ->
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(groupKey, "HmacSHA256"))
            mac.doFinal(w.toString().toByteArray()).copyOfRange(0, ROTATING_ID_LEN)
        }
    }

    // Shared across the process, not per-call — SecureRandom is safe for concurrent use and
    // reconstructing it (and re-seeding from the OS entropy pool) on every single encrypt() call
    // was pure waste on a path evidence chunking can call thousands of times for one large file.
    private val secureRandom = SecureRandom()

    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_LEN).also { secureRandom.nextBytes(it) }
        return encryptWithNonce(key, plaintext, iv)
    }

    /** Same construction as [encrypt] but takes an explicit 12-byte nonce instead of drawing one
     *  from [SecureRandom] — see [org.offlinemesh.app.ble.MeshFrameCodec.encodePosition] for why
     *  position frames need this instead of the random-IV path. */
    fun encryptWithNonce(key: ByteArray, plaintext: ByteArray, nonce: ByteArray): ByteArray {
        require(nonce.size == GCM_IV_LEN) { "nonce must be $GCM_IV_LEN bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LEN_BITS, nonce))
        val ct = cipher.doFinal(plaintext)
        return nonce + ct
    }

    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray? {
        return try {
            val iv = blob.copyOfRange(0, GCM_IV_LEN)
            val ct = blob.copyOfRange(GCM_IV_LEN, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LEN_BITS, iv))
            cipher.doFinal(ct)
        } catch (e: Exception) {
            null // wrong key / not our group / corrupted packet
        }
    }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256Hex(bytes: ByteArray): String = sha256(bytes).joinToString("") { "%02x".format(it) }

    private const val MAC_TAG_LEN = 16

    /** Truncated HMAC-SHA256 authentication tag. 16 bytes is ample against forgery here and keeps
     *  the tag off the wire budget — it exists so a phone without the group key cannot fabricate a
     *  SOS or an evidence header that a member will act on. */
    fun authTag(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data).copyOf(MAC_TAG_LEN)
    }

    /** Constant-time compare so tag verification doesn't leak via timing. */
    fun constantTimeEquals(a: ByteArray?, b: ByteArray?): Boolean {
        if (a == null || b == null || a.size != b.size) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].toInt() xor b[i].toInt())
        return r == 0
    }
}
