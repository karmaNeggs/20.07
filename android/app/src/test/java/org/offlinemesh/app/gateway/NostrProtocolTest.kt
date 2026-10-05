@file:Suppress("MaxLineLength") // hex fixtures

package org.offlinemesh.app.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fixture event was built and signed by an independent implementation (Python json + libsecp256k1),
 *  with content chosen to break naive JSON: `/`, `"`, `\`, newline, tab and non-ASCII. */
class NostrProtocolTest {

    private val fixtureSk = "c4a5fe0c6134fc045d87c4f0fec30c4d56961169ad642f39253794cccd4a8fe7".hexToBytes()!!
    private val fixturePub = "e1d3a1da57888e7475e9b1d883eef9c268e59cdce43cb0c8bbd6ddcb907cf66b"
    private val fixtureContent = "ab/cd+ef==\n\"quoted\" \\ back é中 tab\there"
    private val fixtureTags = listOf(listOf("t", "0a1b2c3d4e5f"), listOf("expiration", "1790000000"))
    private val fixtureId = "b361b01ea84bf11524542978b779826d3046c5ddcbc0f20034a93493cc9030a9"
    private val fixtureSig = "e5fed79265004c27ebf0b532b00f7a8074146c574a2b0deefefabc4d8df486ddcdcd81c679d5bd6e429d694ab8f79ac7589abfe7472e76bb8338e8e267b3215c"
    private val fixture = NostrEvent(fixtureId, fixturePub, 1789999900, 22007, fixtureTags, fixtureContent, fixtureSig)

    @Test fun `event id matches the independent implementation byte for byte`() {
        assertEquals(fixtureId, Nostr.computeId(fixturePub, 1789999900, 22007, fixtureTags, fixtureContent))
    }

    @Test fun `independently signed event verifies`() {
        assertTrue(Nostr.verifyEvent(fixture))
    }

    @Test fun `our signature on the same event is the same as libsecp256k1 gave`() {
        val e = Nostr.signEvent(fixtureSk, 1789999900, 22007, fixtureTags, fixtureContent, ByteArray(32))
        assertEquals(fixtureId, e.id)
        assertEquals(fixtureSig, e.sig)
    }

    @Test fun `any change to a signed event fails verification`() {
        assertFalse(Nostr.verifyEvent(fixture.copy(content = fixtureContent + "x")))
        assertFalse(Nostr.verifyEvent(fixture.copy(createdAt = 1789999901)))
        assertFalse(Nostr.verifyEvent(fixture.copy(kind = 22008)))
        assertFalse(Nostr.verifyEvent(fixture.copy(tags = emptyList())))
        assertFalse(Nostr.verifyEvent(fixture.copy(sig = fixtureSig.replaceRange(0, 2, "00"))))
        assertFalse(Nostr.verifyEvent(fixture.copy(pubkey = "00".repeat(32))))
        assertFalse(Nostr.verifyEvent(fixture.copy(id = "zz")))
    }

    @Test fun `EVENT message round-trips through our own parser unchanged`() {
        val msg = Nostr.eventMessage(fixture)
        val parsed = Nostr.parseRelayMessage("[\"EVENT\",\"sub\"," + msg.removePrefix("[\"EVENT\",").removeSuffix("]") + "]")
        val e = (parsed as RelayMessage.Event).event
        assertEquals(fixture, e)
        assertTrue(Nostr.verifyEvent(e))
    }

    @Test fun `a relay that escapes slashes still gives an event that verifies`() {
        val relaySide = Nostr.eventMessage(fixture).replace("/", "\\/")
        val parsed = Nostr.parseRelayMessage("[\"EVENT\",\"s\"," + relaySide.removePrefix("[\"EVENT\",").removeSuffix("]") + "]")
        assertTrue(Nostr.verifyEvent((parsed as RelayMessage.Event).event))
    }

    @Test fun `REQ and CLOSE messages are well formed`() {
        val req = Nostr.reqMessage("s1", listOf(NostrFilter(listOf(22007), mapOf("t" to listOf("ab", "cd")), since = 5, limit = 9)))
        assertEquals("[\"REQ\",\"s1\",{\"kinds\":[22007],\"#t\":[\"ab\",\"cd\"],\"since\":5,\"limit\":9}]", req)
        assertEquals("[\"CLOSE\",\"s1\"]", Nostr.closeMessage("s1"))
    }

    @Test fun `relay control messages parse`() {
        assertEquals(RelayMessage.Ok("abc", true, ""), Nostr.parseRelayMessage("[\"OK\",\"abc\",true,\"\"]"))
        assertEquals(
            RelayMessage.Ok("abc", false, "rate-limited: slow down"),
            Nostr.parseRelayMessage("[\"OK\",\"abc\",false,\"rate-limited: slow down\"]"),
        )
        assertEquals(RelayMessage.Eose("s1"), Nostr.parseRelayMessage("[\"EOSE\",\"s1\"]"))
        assertEquals(RelayMessage.Notice("hi"), Nostr.parseRelayMessage("[\"NOTICE\",\"hi\"]"))
        assertEquals(RelayMessage.Closed("s1", "auth-required"), Nostr.parseRelayMessage("[\"CLOSED\",\"s1\",\"auth-required\"]"))
    }

    @Test fun `garbage and unknown messages parse to null instead of throwing`() {
        for (bad in listOf("", "not json", "{}", "[]", "[\"AUTH\",\"x\"]", "[\"OK\"]", "[\"EVENT\",\"s\",{}]", "[\"EVENT\",\"s\",5]")) {
            assertNull(bad, Nostr.parseRelayMessage(bad))
        }
        assertNull(Nostr.parseRelayMessage("[\"NOTICE\",\"" + "a".repeat(Nostr.MAX_MESSAGE_CHARS) + "\"]"))
    }

    @Test fun `tag lookup returns the first matching tag value`() {
        assertEquals("0a1b2c3d4e5f", fixture.tagValue("t"))
        assertEquals("1790000000", fixture.tagValue("expiration"))
        assertNull(fixture.tagValue("e"))
        assertNotNull(fixture.tags)
    }

    @Test fun `hex helpers reject odd and non-hex input`() {
        assertNull("abc".hexToBytes())
        assertNull("zz".hexToBytes())
        assertEquals("00ff10", byteArrayOf(0, -1, 16).toHex())
    }
}
