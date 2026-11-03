package org.offlinemesh.app.data

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64

/**
 * A group's entire identity (random id + random key + display name + expiry) packed into one
 * shareable string — paste it, read it aloud, or embed it in a mesh2007://join?c=... link.
 * No passphrase to mistype or mismatch: whoever has the exact code has the exact same group,
 * by construction, not by two people separately typing "the same" secret and hoping it matches
 * (that ambiguity — auto-capitalized/whitespace-mangled passphrases silently deriving different
 * keys on two phones — was a real bug in the previous passphrase-derived design).
 *
 * Groups default to a timed lifetime. An explicit null lifetime creates a permanent group:
 * v4 codes carry [NO_EXPIRY], while finite v3 codes retain their absolute expiry timestamp.
 * Every member reads the same lifetime from the code; joining never extends it.
 * Permanent groups remain until manually deleted; content retention and relay limits are unchanged.
 * Older apps reject v4 codes. See [org.offlinemesh.app.data.GroupRepository.expireGroups].
 */
object JoinCode {
    // v2: added expiresAtEpochSec. Deliberately not wire-compatible with v1 — see decode()'s
    // version check, which now rejects v1 codes outright rather than trying to interpret them
    // with a missing field. Acceptable because groups are short-lived and this project has zero
    // deployed users at the time of this change (see CHANGELOG).
    // v3 (CR-25, PLAN-v2.md Part 10, 2026-08-09 review pass): expiresAtEpochSec widened from a
    // 4-byte signed int to an 8-byte long — the 4-byte field overflows in 2038 (the well-known
    // Y2038 limit), at which point EVERY code, including one just generated, would fail decode()'s
    // own validation immediately. v2's own comment called this "acceptable... nowhere near that
    // boundary" and said to revisit only as 2038 approached — fixed now instead, while it's a
    // trivial 4-byte field change and zero deployed users are affected, rather than waiting for a
    // deadline that would otherwise force an emergency fix. Same "not wire-compatible, and that's
    // fine at this project's stage" precedent v2 already set.
    private const val VERSION: Byte = 3
    // v4 is reserved for permanent groups; older apps reject it rather than misreading expiry.
    private const val PERMANENT_VERSION: Byte = 4
    const val NO_EXPIRY = 0L
    private const val GROUP_ID_LEN = 8
    private const val KEY_LEN = 32
    private const val EXPIRES_AT_LEN = 8
    private const val MILLIS_PER_SECOND = 1000L

    /** Finite lifetimes are bounded independently of the picker. Permanent groups require the
     *  explicit v4 no-expiry representation, rather than an arbitrary distant timestamp. */
    const val MAX_LIFETIME_MILLIS = 180L * 24 * 60 * 60 * 1000 // ~6 months

    /** Used when a caller doesn't specify a lifetime (matches the UI picker's own default). */
    const val DEFAULT_LIFETIME_MILLIS = 48L * 60 * 60 * 1000 // 48 hours

    data class Parsed(val groupId: String, val key: ByteArray, val name: String, val expiresAtEpochSec: Long)

    /** Null [lifetimeMillis] explicitly means no expiry. Finite values are coerced into
     *  `0..MAX_LIFETIME_MILLIS` — a caller asking for
     *  longer gets the max instead of generating a code that would then fail its own [decode]
     *  validation the moment anyone (including the creator, on a later reconstruction via
     *  [org.offlinemesh.app.data.GroupRepository.getShareCode]) tried to read it back. */
    fun generate(name: String, lifetimeMillis: Long? = DEFAULT_LIFETIME_MILLIS): Parsed {
        val idBytes = ByteArray(GROUP_ID_LEN).also { SecureRandom().nextBytes(it) }
        val key = ByteArray(KEY_LEN).also { SecureRandom().nextBytes(it) }
        val expiresAtEpochSec = lifetimeMillis?.let {
            (System.currentTimeMillis() + it.coerceIn(0, MAX_LIFETIME_MILLIS)) / MILLIS_PER_SECOND
        } ?: NO_EXPIRY
        return Parsed(idBytes.joinToString("") { "%02x".format(it) }, key, name, expiresAtEpochSec)
    }

    fun encode(parsed: Parsed): String {
        val idBytes = parsed.groupId.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val nameBytes = utf8Prefix(parsed.name, 255)
        val buf = ByteBuffer.allocate(1 + GROUP_ID_LEN + KEY_LEN + EXPIRES_AT_LEN + 1 + nameBytes.size)
        buf.put(if (parsed.expiresAtEpochSec == NO_EXPIRY) PERMANENT_VERSION else VERSION)
        buf.put(idBytes)
        buf.put(parsed.key)
        // 8-byte epoch-seconds (v3, CR-25) — see VERSION's own doc for why this replaced the
        // old 4-byte (Y2038-limited) field. Costs 4 extra bytes in every code; negligible against
        // the fixed 8+32-byte id+key that already dominates the code's length.
        buf.putLong(parsed.expiresAtEpochSec)
        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)
        // java.util.Base64, not android.util.Base64 — functionally identical (URL-safe alphabet, no
        // padding) but plain JDK, so JoinCode is testable as a pure JVM unit test with no Android
        // framework/Robolectric dependency needed just to exercise Base64 encoding.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf.array())
    }

    /** Rejects (returns null for) a code that's already expired, or implausibly far in the future
     *  (beyond [MAX_LIFETIME_MILLIS] from the moment of decoding) — see [MAX_LIFETIME_MILLIS]'s doc
     *  for why the ceiling is enforced here rather than left as a UI-only suggestion. Also rejects
     *  a v1 (pre-expiry) code outright, same as any other version mismatch. */
    fun decode(code: String): Parsed? = try {
        val bytes = Base64.getUrlDecoder().decode(code.trim())
        val buf = ByteBuffer.wrap(bytes)
        val version = buf.get()
        if (version != VERSION && version != PERMANENT_VERSION) {
            null
        } else {
            val idBytes = ByteArray(GROUP_ID_LEN).also { buf.get(it) }
            val key = ByteArray(KEY_LEN).also { buf.get(it) }
            val expiresAtEpochSec = buf.long
            val nameLen = buf.get().toInt() and 0xFF
            val nameBytes = ByteArray(nameLen).also { buf.get(it) }
            val nowSec = System.currentTimeMillis() / MILLIS_PER_SECOND
            val maxLifetimeSec = MAX_LIFETIME_MILLIS / MILLIS_PER_SECOND
            val validExpiry = if (version == PERMANENT_VERSION) {
                expiresAtEpochSec == NO_EXPIRY
            } else {
                expiresAtEpochSec > nowSec && expiresAtEpochSec <= nowSec + maxLifetimeSec
            }
            if (!validExpiry || buf.hasRemaining()) {
                null
            } else {
                Parsed(
                    idBytes.joinToString("") { "%02x".format(it) }, key,
                    String(nameBytes, Charsets.UTF_8), expiresAtEpochSec
                )
            }
        }
    } catch (e: Exception) {
        null
    }

    fun shareLink(code: String): String = "mesh2007://join?c=$code"

    /** Pulls the code back out whether the user pasted a raw code or a full mesh2007://join?c=... link. */
    fun extractCode(input: String): String {
        val trimmed = input.trim()
        // The code is the value of a real `c=` parameter, ending at the next `&` or `#`: a tracking suffix
        // or an earlier parameter that merely contains "c=" must not break joining.
        val m = Regex("(?:^|[?&#])c=([^&#\\s]+)").find(trimmed)
        return m?.groupValues?.get(1) ?: trimmed.substringAfter("c=", trimmed)
    }

    /** The longest prefix of [name] that fits in [maxBytes] UTF-8 bytes without cutting a character in half. */
    private fun utf8Prefix(name: String, maxBytes: Int): ByteArray {
        var bytes = 0
        val out = StringBuilder()
        for (cp in name.codePoints().toArray()) {
            val piece = String(Character.toChars(cp))
            val n = piece.toByteArray(Charsets.UTF_8).size
            if (bytes + n > maxBytes) break
            out.append(piece); bytes += n
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }
}
