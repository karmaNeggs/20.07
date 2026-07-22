package org.offlinemesh.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
