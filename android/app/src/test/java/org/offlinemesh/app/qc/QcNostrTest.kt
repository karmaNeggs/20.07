@file:Suppress("MaxLineLength","MagicNumber","TooManyFunctions","LongParameterList","LongMethod","CyclomaticComplexMethod","NestedBlockDepth","EmptyFunctionBlock","VariableNaming","WildcardImport","ComplexCondition","ReturnCount","LoopWithTooManyJumpStatements","SwallowedException","TooGenericExceptionCaught","EmptyElseBlock")

package org.offlinemesh.app.qc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.offlinemesh.app.ble.MeshFrameCodec
import org.offlinemesh.app.crypto.CryptoUtils
import org.offlinemesh.app.gateway.GatewayKeyHolder
import org.offlinemesh.app.gateway.Nostr
import org.offlinemesh.app.gateway.NostrEvent
import org.offlinemesh.app.gateway.RelayMessage
import org.offlinemesh.app.gateway.UplinkTags
import org.offlinemesh.app.gateway.Bip340
import java.math.BigInteger
import java.util.Random

class QcNostrTest {
    private val r = Random(11)
    private fun key(): ByteArray = ByteArray(32).also(r::nextBytes)
    private fun randStr(): String {
        val sb = StringBuilder()
        repeat(r.nextInt(40)) {
            sb.append(when (r.nextInt(8)) {
                0 -> '"'; 1 -> '\\'; 2 -> '/'; 3 -> (r.nextInt(32)).toChar(); 4 -> '\u007f'
                5 -> String(Character.toChars(0x1F600 + r.nextInt(50)))
                6 -> ' '; else -> ('a' + r.nextInt(26))
            })
        }
        return sb.toString()
    }

    @Test fun signVerifyRoundTripThroughWireJson() {
        repeat(40) {
            val tags = List(r.nextInt(3)) { listOf("t", randStr()) }
            val e = Nostr.signEvent(key(), r.nextLong().coerceAtLeast(0), r.nextInt(30000), tags, randStr())
            assertTrue(Nostr.verifyEvent(e))
            val m = Nostr.parseRelayMessage("[\"EVENT\",\"s\",${Nostr.eventJson(e)}]") as RelayMessage.Event
            assertEquals(e, m.event)
            assertTrue(Nostr.verifyEvent(m.event))
        }
    }

    @Test fun tamperingAlwaysRejected() {
        val e = Nostr.signEvent(key(), 1234, 22007, listOf(listOf("t", "ab")), "hello")
        assertTrue(Nostr.verifyEvent(e))
        val hex = "0123456789abcdef"
        repeat(300) {
            val which = r.nextInt(6)
            val c = when (which) {
                0 -> e.copy(content = e.content + "x")
                1 -> e.copy(createdAt = e.createdAt + 1)
                2 -> e.copy(kind = e.kind + 1)
                3 -> e.copy(tags = listOf(listOf("t", "ac")))
                4 -> { val i = r.nextInt(e.sig.length); e.copy(sig = e.sig.replaceRange(i, i + 1, hex.filter { ch -> ch != e.sig[i] }[r.nextInt(15)].toString())) }
                else -> { val i = r.nextInt(e.pubkey.length); e.copy(pubkey = e.pubkey.replaceRange(i, i + 1, hex.filter { ch -> ch != e.pubkey[i] }[r.nextInt(15)].toString())) }
            }
            assertFalse("variant $which", Nostr.verifyEvent(c))
        }
        for (bad in listOf("", "zz", "0", e.sig.dropLast(2), e.sig + "00", e.sig.dropLast(1))) assertFalse(Nostr.verifyEvent(e.copy(sig = bad)))
        for (bad in listOf("", "zz", e.id.dropLast(2), e.id.uppercase())) assertFalse(Nostr.verifyEvent(e.copy(id = bad)))
    }

    @Test fun signatureHexMustBeCanonical() {
        // find a valid event whose sig contains a byte < 0x10 so "0x" can be rewritten as "+x" (same bytes via toInt(16))
        repeat(50) {
            val e = Nostr.signEvent(key(), 1, 1, emptyList(), "c$it")
            for (i in 0 until e.sig.length step 2) {
                if (e.sig[i] == '0') {
                    val alt = e.sig.replaceRange(i, i + 1, "+")
                    assertFalse("non-canonical sig string '+' accepted", Nostr.verifyEvent(e.copy(sig = alt)))
                    return
                }
            }
        }
    }

    @Test fun uppercaseSigRejected() {
        val e = Nostr.signEvent(key(), 1, 1, emptyList(), "x")
        assertFalse("uppercase sig accepted (same event id, different string)", Nostr.verifyEvent(e.copy(sig = e.sig.uppercase())))
    }

    @Test fun parseNeverThrowsAndRejectsOversize() {
        assertNull(Nostr.parseRelayMessage("[\"NOTICE\",\"" + "a".repeat(Nostr.MAX_MESSAGE_CHARS) + "\"]"))
        val seeds = listOf("[\"EVENT\"]", "[\"EVENT\",\"s\",5]", "[\"OK\"]", "[\"OK\",\"x\"]", "[\"OK\",\"x\",\"maybe\"]", "[]", "{}", "null", "", "[1,2,3]",
            "[\"EVENT\",\"s\",{\"id\":1,\"pubkey\":null,\"created_at\":\"x\",\"kind\":1e30,\"tags\":[[1,null,{}]],\"content\":[],\"sig\":0}]",
            "[\"EOSE\"]", "[\"CLOSED\"]", "[\"CLOSED\",5,6]", "[".repeat(100_000), "{".repeat(100_000), "[\"EVENT\",\"s\",{\"tags\":" + "[".repeat(50_000))
        for (s in seeds) {
            try { Nostr.parseRelayMessage(s) } catch (t: Throwable) { throw AssertionError("threw ${t.javaClass} for ${s.take(40)}", t) }
        }
        val alphabet = "[]{},:\"\\EVNTOKSCLD0123456789 truefalsn\u0000é"
        repeat(30000) {
            val s = String(CharArray(r.nextInt(60)) { alphabet[r.nextInt(alphabet.length)] })
            try { Nostr.parseRelayMessage(s) } catch (t: Throwable) { throw AssertionError("threw ${t.javaClass} for $s", t) }
        }
    }

    // ---- key holder ----
    @Test fun keyHolderRotationAndRestore() {
        var t = 1_000_000_000L
        val h = GatewayKeyHolder({ t }, java.security.SecureRandom(), 1000)
        repeat(200) { Bip340.publicKey(GatewayKeyHolder({ t }, java.security.SecureRandom(), 1000).current()) }
        val k0 = h.current().copyOf()
        t += 999; assertTrue(k0.contentEquals(h.current()))
        t += 1; assertFalse(k0.contentEquals(h.current()))
        val h2 = GatewayKeyHolder({ t }, java.security.SecureRandom(), 1000)
        val before = h2.secret.copyOf()
        h2.restore(ByteArray(32), t); assertTrue(before.contentEquals(h2.secret))
        h2.restore(BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16).toByteArray().takeLast(32).toByteArray(), t)
        assertTrue(before.contentEquals(h2.secret))
        h2.restore(ByteArray(31) { 1 }, t); assertTrue(before.contentEquals(h2.secret))
        h2.restore(ByteArray(32) { 1 }, t - 1000); assertTrue(before.contentEquals(h2.secret))
        h2.restore(ByteArray(32) { 1 }, t - 999); assertFalse(before.contentEquals(h2.secret))
    }

    @Test fun restoreRejectsFutureDatedKey() {
        var t = 1_000_000_000L
        val h = GatewayKeyHolder({ t }, java.security.SecureRandom(), 1000)
        val before = h.secret.copyOf()
        h.restore(ByteArray(32) { 1 }, t + 10_000_000)
        assertTrue("a key dated far in the future would never expire", before.contentEquals(h.secret))
    }

    // ---- tags ----
    @Test fun tagsNoCollisionsAndCoverage() {
        val r2 = Random(3)
        val seen = HashSet<String>()
        repeat(50) { val k = ByteArray(32).also(r2::nextBytes)
            val base = 1_700_000_000L + r2.nextInt(100_000_000)
            for (i in 0 until 20) {
                assertTrue(seen.add(UplinkTags.liveTag(k, base + i * 60L).joinToString { it.toString() }))
                assertTrue(seen.add(UplinkTags.mailboxTag(k, base + i * 3600L).joinToString { it.toString() }))
            }
            // coverage at T for frames published at T and one window earlier
            repeat(200) {
                val T = base + r2.nextInt(500_000)
                val tags = UplinkTags.interestTags(k, T).map { it.toList() }
                assertTrue(tags.contains(UplinkTags.tagFor(k, 0, T).toList()))
                assertTrue(tags.contains(UplinkTags.tagFor(k, 0, T - 60).toList()))
                assertTrue(tags.contains(UplinkTags.tagFor(k, 2, T).toList()))
                assertTrue(tags.contains(UplinkTags.tagFor(k, 2, T - 3600).toList()))
                assertTrue(tags.contains(UplinkTags.tagFor(k, 2, T - 24 * 3600).toList()))
            }
        }
    }

    @Test fun liveSubscriptionCoversNextRefreshInterval() {
        // The controller refreshes interest tags every 30 s (INTEREST_INTERVAL_MS). A live (ephemeral, unstored) frame
        // published under a window that begins during that 30 s gap is never listened for.
        val k = ByteArray(32) { 7 }
        val T = 1_700_000_000L - (1_700_000_000L % 60) + 45 // 45 s into a window
        val tags = UplinkTags.interestTags(k, T).map { it.toList() }
        for (s in T..T + 30) assertTrue("live tag at +${s - T}s not subscribed", tags.contains(UplinkTags.tagFor(k, 0, s).toList()))
    }

    @Test fun liveTagEqualsPublicBleBeaconId() {
        val k = ByteArray(32) { 9 }
        // not a defect by itself (documented), but it means anyone who sniffs a BLE beacon learns the relay topic.
        assertTrue(CryptoUtils.rotatingAdvertisementId(k, 1_800_000_000L).contentEquals(UplinkTags.liveTag(k, 1_800_000_000L)))
        assertEquals(MeshFrameCodec.UPLINK_CLASS_ALERT, 3)
    }
}
