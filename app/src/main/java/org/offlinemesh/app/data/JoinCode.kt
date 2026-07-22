package org.offlinemesh.app.data

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64

/**
 * A group's entire identity (random id + random key + display name) packed into one
 * shareable string — paste it, read it aloud, or embed it in a mesh2007://join?c=... link.
 * No passphrase to mistype or mismatch: whoever has the exact code has the exact same group,
 * by construction, not by two people separately typing "the same" secret and hoping it matches
 * (that ambiguity — auto-capitalized/whitespace-mangled passphrases silently deriving different
 * keys on two phones — was a real bug in the previous passphrase-derived design).
 */
object JoinCode {
    private const val VERSION: Byte = 1
    private const val GROUP_ID_LEN = 8
    private const val KEY_LEN = 32

    data class Parsed(val groupId: String, val key: ByteArray, val name: String)

    fun generate(name: String): Parsed {
        val idBytes = ByteArray(GROUP_ID_LEN).also { SecureRandom().nextBytes(it) }
        val key = ByteArray(KEY_LEN).also { SecureRandom().nextBytes(it) }
        return Parsed(idBytes.joinToString("") { "%02x".format(it) }, key, name)
    }

    fun encode(parsed: Parsed): String {
        val idBytes = parsed.groupId.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val nameBytes = parsed.name.toByteArray(Charsets.UTF_8).copyOf(minOf(parsed.name.toByteArray().size, 255))
        val buf = ByteBuffer.allocate(1 + GROUP_ID_LEN + KEY_LEN + 1 + nameBytes.size)
        buf.put(VERSION)
        buf.put(idBytes)
        buf.put(parsed.key)
        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)
        // java.util.Base64, not android.util.Base64 — functionally identical (URL-safe alphabet, no
        // padding) but plain JDK, so JoinCode is testable as a pure JVM unit test with no Android
        // framework/Robolectric dependency needed just to exercise Base64 encoding.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf.array())
    }

    fun decode(code: String): Parsed? = try {
        val bytes = Base64.getUrlDecoder().decode(code.trim())
        val buf = ByteBuffer.wrap(bytes)
        val version = buf.get()
        if (version != VERSION) null else {
            val idBytes = ByteArray(GROUP_ID_LEN).also { buf.get(it) }
            val key = ByteArray(KEY_LEN).also { buf.get(it) }
            val nameLen = buf.get().toInt() and 0xFF
            val nameBytes = ByteArray(nameLen).also { buf.get(it) }
            Parsed(idBytes.joinToString("") { "%02x".format(it) }, key, String(nameBytes, Charsets.UTF_8))
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
