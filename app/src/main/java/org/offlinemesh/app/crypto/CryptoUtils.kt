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

    // decision 38 (docs/DECISIONS.md): GATT-relayed frames (SOS/position/evidence/nickname/
    // presence) persist/relay for up to RelayEngine's 48h content-retention ceiling — far longer
    // than a beacon payload's sub-minute life. Unlike a beacon id (re-derived fresh every ~60s
    // advertise cycle), a GATT handle is computed ONCE at creation/first-ingest and forwarded
    // verbatim for the frame's whole relay life (see e.g. SosEntity.handle's doc) — so for a
    // receiver's ±1-window tolerance to still catch a handle computed at time T when checked at any
    // later receive time within the 48h ceiling, this window must EXCEED 48h, not just cover it.
    // 72h gives 24h of margin, absorbing decision 33's multi-hour 120-hop transit time and ordinary
    // clock skew.
    //
    // Domain-separated from ID_WINDOW_SECONDS by construction, not by luck: for any realistic
    // calendar date this app runs at (Unix epoch ~1.6e9-4.1e9, i.e. 2020-2100), the beacon's window
    // (epoch/60) ranges over [26.6M, 68.3M] while this window (epoch/259200) ranges over
    // [6172, 15818] — disjoint integer ranges, so rotatingAdvertisementId's HMAC input
    // (window.toString()) can never collide between the two purposes sharing one groupKey.
    const val GATT_GROUP_HANDLE_WINDOW_SECONDS = 72L * 60 * 60

    // 6 bytes = 48 bits of entropy per rotating window — still astronomically collision-safe
    // for this purpose. Kept deliberately short: legacy BLE advertising has a hard 31-byte
    // total limit (Android auto-adds 3 bytes for a Flags structure you don't control), and
    // every byte here is a byte the whole beacon payload has to fit inside alongside its
    // header overhead (found live during device testing — the original 8-byte id, combined
    // with an also-advertised Service UUID list, silently overflowed the limit and meant
    // advertising was failing outright, so phones never discovered each other at all). Reused
    // as-is for the GATT handle (decision 38) — GATT frames have no comparable size pressure, but
    // there's no reason to widen it: 48 bits is already astronomically collision-safe for this
    // app's group counts, and reusing the same length keeps one construction serving both purposes.
    private const val ROTATING_ID_LEN = 6

    /** Rotating pseudonymous id for this group, changes every [windowSeconds]. Used both for the
     *  beacon's own discovery payload (default [ID_WINDOW_SECONDS]) and, since decision 38, for the
     *  GATT wire handle that replaces cleartext `groupId` on every relayed frame (callers pass
     *  [GATT_GROUP_HANDLE_WINDOW_SECONDS] via [org.offlinemesh.app.ble.MeshFrameCodec.groupHandle]) —
     *  see that constant's own doc for why GATT needs a much wider window than the beacon does. */
    fun rotatingAdvertisementId(
        groupKey: ByteArray,
        epochSeconds: Long = System.currentTimeMillis() / 1000,
        windowSeconds: Long = ID_WINDOW_SECONDS,
    ): ByteArray {
        val window = epochSeconds / windowSeconds
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(groupKey, "HmacSHA256"))
        val windowBytes = window.toString().toByteArray()
        return mac.doFinal(windowBytes).copyOfRange(0, ROTATING_ID_LEN)
    }

    /** Candidate ids for current + adjacent windows, to tolerate clock drift between phones (and,
     *  for the GATT-purpose [windowSeconds], the real time elapsed between a handle's creation and
     *  a receiver eventually seeing it relayed — see [GATT_GROUP_HANDLE_WINDOW_SECONDS]'s doc). */
    fun candidateAdvertisementIds(
        groupKey: ByteArray,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
        windowSeconds: Long = ID_WINDOW_SECONDS,
    ): List<ByteArray> {
        val window = nowSeconds / windowSeconds
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
