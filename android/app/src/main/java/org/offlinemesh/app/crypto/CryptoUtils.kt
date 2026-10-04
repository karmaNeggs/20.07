package org.offlinemesh.app.crypto

import com.google.crypto.tink.subtle.Hkdf
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Group keys are random (see JoinCode.generate), not derived from a typed passphrase. No secret
 * ever leaves the device in plaintext over the mesh; only values derived from it do — the rotating
 * discovery/wire-handle id, and (decision 39, docs/DECISIONS.md) the per-epoch content-sealing key
 * [contentEpochKey] derives via HKDF. See that function's own doc for what it does and does NOT
 * buy — it is deliberately NOT a forward-secrecy mechanism against phone seizure, despite
 * `PLAN-v2.md` §4.4's original wording; the group's root key must stay permanently retained
 * regardless (the rotating handle and `GroupRepository.getShareCode`'s re-shareable invite code
 * both depend on it never changing), which makes any *non-interactive* per-epoch derivation from
 * it — no matter how many hash-chain hops — trivially recomputable by anyone already holding that
 * root key. Real forward secrecy needs an interactive key-agreement step, which this app
 * deliberately doesn't do (PLAN-v2.md §4.4 rejected MLS/interactive schemes specifically because
 * this mesh partitions constantly and can't rely on two members successfully coordinating).
 */
@Suppress("TooManyFunctions")
// One small, coherent set of crypto primitives (encrypt/decrypt/mac/hash/rotating-id/epoch-key) —
// this app's entire symmetric-crypto surface deliberately lives in one object so there's a single
// place to audit it (see this file's own class doc), not a candidate for splitting into several
// objects just to dodge a function-count threshold.
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

    // decision 40 (P4 slice 1, docs/DECISIONS.md): a courier envelope's own recognition tag
    // (PLAN-v2.md §4.2) needs a THIRD partition of this same construction, under the same group
    // key — HMAC(groupKey, UTC-day), 16 bytes per the spec's own wording, not this file's usual
    // 6-byte beacon/GATT-handle length. epochSeconds is already Unix time (UTC), so
    // epochSeconds / COURIER_TAG_WINDOW_SECONDS IS the UTC day number with no separate timezone
    // handling needed.
    //
    // Domain-separated from BOTH existing partitions by construction, extending decision 38's own
    // proof one more partition: for realistic dates (2020-01-01 to 2100-01-01 UTC), the beacon
    // window (epoch/60) ranges [26297280, 68374080], the GATT-handle window (epoch/259200) ranges
    // [6087, 15827], and this window (epoch/86400) ranges [18262, 47482] — three pairwise disjoint
    // integer ranges, so rotatingAdvertisementId's HMAC input (window.toString()) can never collide
    // across any two of the three purposes sharing one groupKey, independent of truncation length.
    const val COURIER_TAG_WINDOW_SECONDS = 86_400L

    /** 16 bytes per `PLAN-v2.md` §4.2's own wording — wider than [ROTATING_ID_LEN] because a
     *  courier tag isn't squeezed into a beacon's 31-byte advertising payload the way the rotating
     *  beacon id is; see [ROTATING_ID_LEN]'s own doc for why THAT one is short. */
    const val COURIER_TAG_LEN = 16

    /** Rotating pseudonymous id for this group, changes every [windowSeconds]. Used for the
     *  beacon's own discovery payload (default [ID_WINDOW_SECONDS]), since decision 38 for the
     *  GATT wire handle that replaces cleartext `groupId` on every relayed frame (callers pass
     *  [GATT_GROUP_HANDLE_WINDOW_SECONDS] via [org.offlinemesh.app.ble.MeshFrameCodec.groupHandle]),
     *  and since decision 40 for a courier envelope's recognition tag (callers pass
     *  [COURIER_TAG_WINDOW_SECONDS]/[COURIER_TAG_LEN] via
     *  [org.offlinemesh.app.ble.MeshFrameCodec.courierTag]) — see each constant's own doc for why
     *  its purpose needs a different window/length than the others. [truncateLen] defaults to
     *  [ROTATING_ID_LEN] so both existing call sites (which pass none) stay byte-for-byte
     *  unchanged, same precedent [windowSeconds] itself set in decision 38. */
    fun rotatingAdvertisementId(
        groupKey: ByteArray,
        epochSeconds: Long = System.currentTimeMillis() / 1000,
        windowSeconds: Long = ID_WINDOW_SECONDS,
        truncateLen: Int = ROTATING_ID_LEN,
    ): ByteArray {
        val window = epochSeconds / windowSeconds
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(groupKey, "HmacSHA256"))
        val windowBytes = window.toString().toByteArray()
        return mac.doFinal(windowBytes).copyOfRange(0, truncateLen)
    }

    /** Candidate ids for current + adjacent windows, to tolerate clock drift between phones (and,
     *  for the GATT-purpose [windowSeconds], the real time elapsed between a handle's creation and
     *  a receiver eventually seeing it relayed — see [GATT_GROUP_HANDLE_WINDOW_SECONDS]'s doc). */
    fun candidateAdvertisementIds(
        groupKey: ByteArray,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
        windowSeconds: Long = ID_WINDOW_SECONDS,
        truncateLen: Int = ROTATING_ID_LEN,
    ): List<ByteArray> {
        val window = nowSeconds / windowSeconds
        return listOf(window - 1, window, window + 1).map { w ->
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(groupKey, "HmacSHA256"))
            mac.doFinal(w.toString().toByteArray()).copyOfRange(0, truncateLen)
        }
    }

    // decision 39 (docs/DECISIONS.md): 24h, not GATT_GROUP_HANDLE_WINDOW_SECONDS's 72h — this
    // epoch serves a genuinely different purpose with a different tolerance shape. The wire handle
    // needs a window WIDE enough that one value, computed once, stays matchable for a relayed
    // frame's whole ~48h life. The content key instead needs to actually ROTATE within a group's
    // real lifetime (4-5 days typical, up to JoinCode.MAX_LIFETIME_MILLIS's 180-day ceiling) to be
    // worth anything at all — a 72h epoch would mean a DEFAULT-length (48h) group might never cross
    // even one boundary, collapsing this to "no rotation, ever" in the common case. 24h matches
    // this codebase's existing day-scale constant family (RelayEngine.CONTENT_MAX_AGE_MILLIS 48h,
    // JoinCode.DEFAULT_LIFETIME_MILLIS 48h) and gives real rotation granularity instead.
    const val CONTENT_EPOCH_SECONDS = 24L * 60 * 60

    private const val CONTENT_EPOCH_KEY_LEN = 32

    // 96h backward from "now" — comfortably exceeds GATT_GROUP_HANDLE_WINDOW_SECONDS's own 72h
    // worst-case content-lifetime figure (SOS/position specifically, the only two frame types that
    // need this candidate list at all — see candidateContentEpochKeys' own doc), with more margin
    // than that figure itself used. +1 forward (in candidateContentEpochKeys below) mirrors
    // candidateAdvertisementIds' own +/-1 clock-skew tolerance.
    private const val CONTENT_EPOCH_BACKWARD_CANDIDATES = 3L

    // Domain-separates this derivation from any other HKDF use that might ever share this app's
    // "info" namespace, and versions it so a future format change can bump the tag rather than
    // silently colliding with this one.
    private const val CONTENT_EPOCH_INFO_PREFIX = "20.07-content-epoch-v1:"

    /** `K_e = HKDF(rootKey, e)`, `e = floor(epochSeconds / epochLenSeconds)` — the non-interactive
     *  per-epoch content-sealing key (`PLAN-v2.md` §4.4, decision 39, `docs/DECISIONS.md`). Every
     *  member derives this independently from the same root key and their own wall clock; nothing
     *  is transmitted or coordinated. Replaces the root key directly at every AES-GCM seal (SOS/
     *  position/evidence bodies) and HMAC auth tag (evidence-meta/nickname/presence macs, Tier B's
     *  SOS broadcast-preview mac) — see [org.offlinemesh.app.ble.MeshFrameCodec.groupHandle]'s own
     *  doc for the SEPARATE, unrelated wire-obfuscation epoch this must never be confused with
     *  (that one stays on the root key, unchanged, forever).
     *
     *  **This is NOT a forward-secrecy mechanism against phone seizure** — see this file's own
     *  class doc for the full reasoning (the root key must stay permanently retained regardless,
     *  which makes any non-interactive per-epoch value trivially recomputable by anyone who already
     *  has it). What it actually buys, honestly: domain separation from the wire-handle derivation
     *  (a bug or leak in one derivation doesn't hand over the other), and bounding a single leaked
     *  `K_e` (e.g. a crash dump, a memory scrape while briefly unlocked) to about
     *  [CONTENT_EPOCH_SECONDS] of exposure instead of the group's entire life — real, just
     *  narrower than "forward secrecy" implies. Uses Tink's `subtle.Hkdf` directly (already a
     *  pinned dependency, `tink-android`), matching [org.offlinemesh.app.crypto.SenderIdentity]'s
     *  own established precedent of using Tink's `subtle.*` classes directly rather than the
     *  `KeysetHandle`/registry API. */
    fun contentEpochKey(
        rootKey: ByteArray,
        epochSeconds: Long = System.currentTimeMillis() / 1000,
        epochLenSeconds: Long = CONTENT_EPOCH_SECONDS,
    ): ByteArray {
        val epoch = epochSeconds / epochLenSeconds
        val info = "$CONTENT_EPOCH_INFO_PREFIX$epoch".toByteArray()
        return Hkdf.computeHkdf("HmacSHA256", rootKey, null, info, CONTENT_EPOCH_KEY_LEN)
    }

    /** Candidate [contentEpochKey] values for opening content whose OWN authoring epoch isn't
     *  knowable before the seal is actually opened — concretely, SOS and position, whose timestamp
     *  lives inside the AES-GCM body, not the cleartext envelope. (Evidence-meta/nickname/presence/
     *  the Tier B SOS preview all carry an already-cleartext timestamp field, so those derive a
     *  single exact [contentEpochKey] directly from it instead — no candidate list needed, and
     *  none of the ambiguity this function exists to resolve.) `e+1` down through `e-3`: 96h
     *  backward coverage plus one window forward for ordinary clock skew — see
     *  [CONTENT_EPOCH_BACKWARD_CANDIDATES]'s own doc for why that backward figure is enough. */
    fun candidateContentEpochKeys(
        rootKey: ByteArray,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
        epochLenSeconds: Long = CONTENT_EPOCH_SECONDS,
    ): List<ByteArray> {
        val epoch = nowSeconds / epochLenSeconds
        return (epoch + 1 downTo epoch - CONTENT_EPOCH_BACKWARD_CANDIDATES).map { e ->
            val info = "$CONTENT_EPOCH_INFO_PREFIX$e".toByteArray()
            Hkdf.computeHkdf("HmacSHA256", rootKey, null, info, CONTENT_EPOCH_KEY_LEN)
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

    /** Draws from the same process-shared [SecureRandom] as [encrypt] — see
     *  [org.offlinemesh.app.ble.MeshFrameCodec.padGattFrame], which calls this per GATT write to
     *  fill padding bytes so a padded frame isn't visually distinguishable (all-zero tail) from the
     *  AEAD-sealed/HMAC-tagged content it follows. */
    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { secureRandom.nextBytes(it) }

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

    /** Truncated HMAC-SHA256 authentication tag length, in bytes — not private: this is the single
     *  source of truth for anything that needs to validate a tag's length before/without calling
     *  [authTag] itself (e.g. [org.offlinemesh.app.ble.MeshProtocol]'s Tier B `SosAlert.Content.mac`
     *  size check). Found live (2026-08-09 review pass) that `MeshProtocol.MAC_LEN` had silently
     *  drifted to 32 while this stayed 16 — every `content.mac.size == MAC_LEN` check in that file
     *  was therefore always false against a real [authTag] output, which meant the Tier B SOS
     *  content preview (decisions 29/30/31) had never actually been transmitted. Referencing this
     *  constant directly is what makes that drift impossible to reintroduce. */
    const val MAC_TAG_LEN = 16

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
