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
 * **Groups are ephemeral by design** — 20.07 groups exist for the duration of a task (typically
 * days) and are meant to be deleted when it's done, not to persist indefinitely. [expiresAtEpochSec]
 * is an ABSOLUTE timestamp baked into the code at creation time, not a duration, specifically so
 * that everyone who joins agrees on the same end moment without any coordination — whoever joins,
 * whenever they join, reads the same fixed expiry off the same code. See
 * [org.offlinemesh.app.data.GroupRepository.expireGroups] for enforcement.
 */
object JoinCode {
    // v2: added expiresAtEpochSec. Deliberately not wire-compatible with v1 — see decode()'s
    // version check, which now rejects v1 codes outright rather than trying to interpret them
    // with a missing field. Acceptable because groups are short-lived and this project has zero
    // deployed users at the time of this change (see CHANGELOG).
    private const val VERSION: Byte = 2
    private const val GROUP_ID_LEN = 8
    private const val KEY_LEN = 32
    private const val EXPIRES_AT_LEN = 4
    private const val MILLIS_PER_SECOND = 1000L

    /** Absolute ceiling on how far in the future a code's expiry may sit — enforced in [decode],
     *  not just suggested by the UI's lifetime picker. Without this, a malformed or deliberately
     *  crafted code could set an expiry decades out, defeating the entire point of a group being
     *  bounded. 20.07 groups are meant to run days, not months; six months is a generous outer
     *  bound for an unusual longer-running case, not a typical choice. */
    const val MAX_LIFETIME_MILLIS = 180L * 24 * 60 * 60 * 1000 // ~6 months

    /** Used when a caller doesn't specify a lifetime (matches the UI picker's own default). */
    const val DEFAULT_LIFETIME_MILLIS = 48L * 60 * 60 * 1000 // 48 hours

    data class Parsed(val groupId: String, val key: ByteArray, val name: String, val expiresAtEpochSec: Long)

    /** [lifetimeMillis] is silently coerced into `0..MAX_LIFETIME_MILLIS` — a caller asking for
     *  longer gets the max instead of generating a code that would then fail its own [decode]
     *  validation the moment anyone (including the creator, on a later reconstruction via
     *  [org.offlinemesh.app.data.GroupRepository.getShareCode]) tried to read it back. */
    fun generate(name: String, lifetimeMillis: Long = DEFAULT_LIFETIME_MILLIS): Parsed {
        val idBytes = ByteArray(GROUP_ID_LEN).also { SecureRandom().nextBytes(it) }
        val key = ByteArray(KEY_LEN).also { SecureRandom().nextBytes(it) }
        val boundedLifetime = lifetimeMillis.coerceIn(0, MAX_LIFETIME_MILLIS)
        val expiresAtEpochSec = (System.currentTimeMillis() + boundedLifetime) / MILLIS_PER_SECOND
        return Parsed(idBytes.joinToString("") { "%02x".format(it) }, key, name, expiresAtEpochSec)
    }

    fun encode(parsed: Parsed): String {
        val idBytes = parsed.groupId.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val nameBytes = parsed.name.toByteArray(Charsets.UTF_8).copyOf(minOf(parsed.name.toByteArray().size, 255))
        val buf = ByteBuffer.allocate(1 + GROUP_ID_LEN + KEY_LEN + EXPIRES_AT_LEN + 1 + nameBytes.size)
        buf.put(VERSION)
        buf.put(idBytes)
        buf.put(parsed.key)
        // A signed 32-bit epoch-seconds field overflows in 2038 (the well-known Y2038 limit) —
        // acceptable here given this app's actual horizon (groups measured in days, this constant
        // itself capping any single group at ~6 months) is nowhere near that boundary, and a
        // 4-byte field keeps the code itself short. Revisit only if this project is still running
        // as-is as 2038 approaches.
        buf.putInt(parsed.expiresAtEpochSec.toInt())
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
        if (version != VERSION) {
            null
        } else {
            val idBytes = ByteArray(GROUP_ID_LEN).also { buf.get(it) }
            val key = ByteArray(KEY_LEN).also { buf.get(it) }
            val expiresAtEpochSec = buf.int.toLong()
            val nameLen = buf.get().toInt() and 0xFF
            val nameBytes = ByteArray(nameLen).also { buf.get(it) }
            val nowSec = System.currentTimeMillis() / MILLIS_PER_SECOND
            val maxLifetimeSec = MAX_LIFETIME_MILLIS / MILLIS_PER_SECOND
            if (expiresAtEpochSec <= nowSec || expiresAtEpochSec > nowSec + maxLifetimeSec) {
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
        val marker = "c="
        val idx = trimmed.indexOf(marker)
        return if (idx >= 0) trimmed.substring(idx + marker.length) else trimmed
    }
}
