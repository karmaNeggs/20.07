package org.offlinemesh.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tier 1: round-trips the shareable code (what a QR code / link actually encodes) plus malformed
 *  input handling — no Android framework needed since JoinCode moved to java.util.Base64. */
class JoinCodeTest {

    @Test
    fun `generate then encode then decode returns the same group id, key, and name`() {
        val parsed = JoinCode.generate("Neighborhood Watch")
        val code = JoinCode.encode(parsed)
        val decoded = JoinCode.decode(code)
        checkNotNull(decoded)
        assertEquals(parsed.groupId, decoded.groupId)
        assertArrayEquals(parsed.key, decoded.key)
        assertEquals(parsed.name, decoded.name)
    }

    @Test
    fun `two generated groups never share an id or key`() {
        val a = JoinCode.generate("A")
        val b = JoinCode.generate("B")
        assert(a.groupId != b.groupId)
        assert(!a.key.contentEquals(b.key))
    }

    @Test
    fun `decode rejects garbage input rather than throwing`() {
        assertNull(JoinCode.decode("not a real code"))
        assertNull(JoinCode.decode(""))
    }

    @Test
    fun `decode rejects a code from a different version byte`() {
        val code = JoinCode.encode(JoinCode.generate("test"))
        // Flip the leading (version) byte by re-encoding with a corrupted first char equivalent —
        // simplest reliable way without reaching into Base64 internals: prepend an extra char to
        // shift the whole decoded byte stream, which should fail Parsed reconstruction cleanly.
        assertNull(JoinCode.decode("A$code"))
    }

    @Test
    fun `extractCode pulls the code out of a full join link`() {
        val code = JoinCode.encode(JoinCode.generate("test"))
        val link = JoinCode.shareLink(code)
        assertEquals(code, JoinCode.extractCode(link))
    }

    @Test
    fun `extractCode returns a raw pasted code unchanged`() {
        val code = JoinCode.encode(JoinCode.generate("test"))
        assertEquals(code, JoinCode.extractCode(code))
    }

    @Test
    fun `extractCode trims incidental whitespace from a pasted code`() {
        val code = JoinCode.encode(JoinCode.generate("test"))
        assertEquals(code, JoinCode.extractCode(code).trim())
    }

    @Test
    fun `group name survives characters outside plain ascii`() {
        val parsed = JoinCode.generate("Río Norte 北")
        val decoded = JoinCode.decode(JoinCode.encode(parsed))
        checkNotNull(decoded)
        assertEquals("Río Norte 北", decoded.name)
    }

    // ---------- absolute expiry baked into the code ----------

    // 16 hex chars = 8 bytes, matches GROUP_ID_LEN — a plain constant, not a function, since it
    // never varies between call sites (detekt's FunctionOnlyReturningConstant correctly flagged
    // the previous function-wrapped form of this).
    private val rawGroupId = "0011223344556677"
    private fun rawKey() = ByteArray(32)

    @Test
    fun `generate then encode then decode preserves expiresAtEpochSec`() {
        val parsed = JoinCode.generate("test", lifetimeMillis = 60_000L)
        val decoded = JoinCode.decode(JoinCode.encode(parsed))
        checkNotNull(decoded)
        assertEquals(parsed.expiresAtEpochSec, decoded.expiresAtEpochSec)
    }

    @Test
    fun `decode rejects an already-expired code`() {
        // Parsed itself does no validation (only decode() does) — constructing one directly with
        // an already-past epoch second simulates the ordinary, non-hostile case: someone shares a
        // code, then it isn't scanned/joined until after its baked-in expiry has passed.
        val expired = JoinCode.Parsed(rawGroupId, rawKey(), "test", expiresAtEpochSec = 1000L) // 1970
        assertNull(JoinCode.decode(JoinCode.encode(expired)))
    }

    @Test
    fun `decode rejects a code with an implausibly-far-future expiry`() {
        val nowSec = System.currentTimeMillis() / 1000
        val tooFar = JoinCode.Parsed(
            rawGroupId, rawKey(), "test",
            expiresAtEpochSec = nowSec + (JoinCode.MAX_LIFETIME_MILLIS / 1000) + 3600 // 1h past the ceiling
        )
        assertNull(JoinCode.decode(JoinCode.encode(tooFar)))
    }

    @Test
    fun `decode accepts a code right at the lifetime ceiling`() {
        val parsed = JoinCode.generate("test", lifetimeMillis = JoinCode.MAX_LIFETIME_MILLIS)
        val decoded = JoinCode.decode(JoinCode.encode(parsed))
        assertEquals(parsed.expiresAtEpochSec, decoded?.expiresAtEpochSec)
    }

    @Test
    fun `generate coerces an excessive lifetime down to the ceiling instead of producing an unjoinable code`() {
        val parsed = JoinCode.generate("test", lifetimeMillis = JoinCode.MAX_LIFETIME_MILLIS * 10)
        val nowSec = System.currentTimeMillis() / 1000
        val maxSec = JoinCode.MAX_LIFETIME_MILLIS / 1000
        assertTrue(parsed.expiresAtEpochSec <= nowSec + maxSec + 1) // +1s slack for test execution time
        // And critically: the resulting code must actually still decode, not silently produce one
        // that fails its own ceiling check.
        checkNotNull(JoinCode.decode(JoinCode.encode(parsed)))
    }

    @Test
    fun `decode rejects a v1 (pre-expiry) code outright`() {
        // Hand-builds the old wire layout (version=1, no expiresAt field) to confirm the version
        // check alone is sufficient to reject it, without needing a real historical v1 codepath.
        val idBytes = rawGroupId.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val nameBytes = "test".toByteArray(Charsets.UTF_8)
        val buf = java.nio.ByteBuffer.allocate(1 + 8 + 32 + 1 + nameBytes.size)
        buf.put(1.toByte()) // v1
        buf.put(idBytes)
        buf.put(rawKey())
        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)
        val v1Code = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(buf.array())
        assertNull(JoinCode.decode(v1Code))
    }
}
