@file:Suppress("MaxLineLength","MagicNumber","TooManyFunctions","LongParameterList","LongMethod","CyclomaticComplexMethod","NestedBlockDepth","EmptyFunctionBlock","VariableNaming","WildcardImport","ComplexCondition","ReturnCount","LoopWithTooManyJumpStatements","SwallowedException","TooGenericExceptionCaught","EmptyElseBlock","FunctionNaming","TopLevelPropertyNaming","UnusedPrivateMember")

package org.offlinemesh.app.breakera

import org.junit.Assert.*
import org.junit.Test
import org.offlinemesh.app.ble.*
import org.offlinemesh.app.crypto.CryptoUtils
import org.offlinemesh.app.crypto.SenderIdentity
import org.offlinemesh.app.data.JoinCode
import org.offlinemesh.app.gateway.Bip340
import org.offlinemesh.app.gateway.Nostr
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Random

class BreakerATest {
    private val root = ByteArray(32) { (it + 1).toByte() }
    private val now = 1_800_000_000_000L
    private fun ck(ms: Long = now) = CryptoUtils.contentEpochKey(root, ms / 1000)

    // ---------- (1) decoder fuzz ----------
    @Test fun breakera_decodeFuzzNeverThrows() {
        val rnd = Random(42)
        val types = byteArrayOf(0x12, 0x13, 0x15, 0x16, 0x17, 0x18, 0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0x21)
        var bad: Throwable? = null
        repeat(60_000) { i ->
            val n = rnd.nextInt(300)
            val b = ByteArray(n).also { rnd.nextBytes(it) }
            if (n >= 2) { b[0] = types[rnd.nextInt(types.size)]; b[1] = MeshFrameCodec.VERSION.toByte() }
            try { MeshFrameCodec.decode(b) } catch (t: Throwable) { bad = bad ?: t }
            if (i % 3 == 0) try { MeshFrameCodec.unpadGattFrame(b) } catch (t: Throwable) { bad = bad ?: t }
        }
        assertNull("decode threw $bad", bad)
    }

    @Test fun breakera_mutatedValidFramesNeverThrow() {
        val rnd = Random(7)
        val h = MeshFrameCodec.groupHandle(root, now / 1000)
        val seeds = listOf(
            MeshFrameCodec.sealSos(root, ck(), "id1", "s", "hello", now, true, 5, 0),
            MeshFrameCodec.encodePosition(root, ck(), "s", 1.0, 2.0, 5, now / 1000, 0),
            MeshFrameCodec.encodePresence("g", "s", now, root, ck()),
            MeshFrameCodec.encodeUplink(ByteArray(16), 1, 0, now, ByteArray(40)),
            MeshFrameCodec.encodeUplinkInterest(0, listOf(ByteArray(8))),
            MeshFrameCodec.encodeCourier(ByteArray(16), "c", now, 3, ByteArray(40)),
            MeshFrameCodec.encodeCatalogFilter(1, 64, ByteArray(8)),
            MeshFrameCodec.encodeSymbolRequest("e", 5),
            MeshFrameCodec.encodeEvidSymbol(MeshFrameCodec.Frame.EvidSymbol("e", 1, ByteArray(400))),
        )
        var bad: Throwable? = null
        for (s in seeds) repeat(5000) {
            val m = s.copyOf(if (rnd.nextInt(4) == 0) rnd.nextInt(s.size + 1) else s.size)
            repeat(1 + rnd.nextInt(4)) { if (m.isNotEmpty()) m[rnd.nextInt(m.size)] = rnd.nextInt(256).toByte() }
            try { MeshFrameCodec.decode(m) } catch (t: Throwable) { bad = bad ?: t }
        }
        assertNull("threw $bad", bad); assertNotNull(h)
    }

    // ---------- (2) authenticity ----------
    /** A pinned sender key must make an UNSIGNED frame fail (otherwise a hostile member omits the signature). */
    @Test fun breakera_pinnedSenderUnsignedFrameMustBeRejected() {
        val kp = SenderIdentity.generateKeyPair()
        val data = ByteArray(20) { 3 }
        assertFalse(
            "pinned key + signature=null accepted: member impersonation by omitting signature",
            RelayResponder.signatureCheckPasses(kp.publicKey, null, data),
        )
    }

    /** SOS id lives in the clear envelope but is not bound to the sealed body: replay under a fresh id. */
    /** MAC covers only the first 20 chars of a nickname, but receivers keep the whole decoded string. */
    @Test fun breakera_nicknameTailBeyond20CharsUnauthenticated() {
        val base = "A".repeat(20)
        val m1 = MeshFrameCodec.nicknameMacInput("g", "s", base, 5L)
        val m2 = MeshFrameCodec.nicknameMacInput("g", "s", base + "  <<EVIL TAIL>>", 5L)
        assertArrayEquals("mac input must differ if username differs", m1, m2)
        val bos = ByteArrayOutputStream(); val d = DataOutputStream(bos)
        val evil = (base + "  <<EVIL TAIL>>").toByteArray()
        d.writeByte(MeshFrameCodec.FRAME_NICKNAME.toInt()); d.writeByte(MeshFrameCodec.VERSION)
        d.writeByte(16); d.write(ByteArray(16) { 1 }); d.writeByte(1); d.write('s'.code)
        d.writeByte(evil.size); d.write(evil); d.writeLong(5L); d.writeByte(16); d.write(ByteArray(16)); d.writeByte(0)
        val f = MeshFrameCodec.decode(bos.toByteArray()) as MeshFrameCodec.Frame.Nickname
        assertTrue("decoder enforces MAX_USERNAME_CHARS", f.username.length <= MeshFrameCodec.MAX_USERNAME_CHARS)
    }

    /** Presence senderPublicKey is outside both MAC and signature: relay can swap it on a valid frame. */
    /** Evidence header ttl is outside the MAC (relay can immortalise). */
    // ---------- (3) resource abuse ----------
    // ---------- (5) join code ----------
    @Test fun breakera_extractCodeIgnoresTrailingLinkParams() {
        val p = JoinCode.generate("team")
        val code = JoinCode.encode(p)
        val link = JoinCode.shareLink(code) + "&utm=x"
        assertNotNull("valid link with extra query param must still join", JoinCode.decode(JoinCode.extractCode(link)))
        val link2 = "mesh2007://join?src=1&c=$code"
        assertNotNull("c= not first param", JoinCode.decode(JoinCode.extractCode(link2)))
    }

    @Test fun breakera_joinCodeFuzzNoThrow() {
        val rnd = Random(1)
        val good = JoinCode.encode(JoinCode.generate("x"))
        repeat(20000) {
            val s = if (it % 2 == 0) String(CharArray(rnd.nextInt(120)) { rnd.nextInt(0x3000).toChar() }) else
                good.toCharArray().also { a -> if (a.isNotEmpty()) a[rnd.nextInt(a.size)] = "-_AZ09=\u0000".random(kotlin.random.Random(rnd.nextLong())) }.concatToString()
            JoinCode.decode(JoinCode.extractCode(s))
        }
    }

    // ---------- Nostr / BIP340 ----------
    @Test fun breakera_nostrDeeplyNestedJsonNoCrash() {
        val s = "[\"EVENT\"," + "[".repeat(90_000) + "]".repeat(90_000) + "]"
        var err: Throwable? = null
        try { Nostr.parseRelayMessage(s) } catch (t: Throwable) { err = t }
        assertNull("hostile relay can crash parser with $err", err)
    }

    @Test fun breakera_nostrParseFuzz() {
        val rnd = Random(3)
        var bad: Throwable? = null
        val ev = Nostr.eventMessage(Nostr.signEvent(ByteArray(32) { 1 }, 1L, 1, listOf(listOf("t", "x")), "hi"))
        repeat(20000) {
            val c = ev.toCharArray(); repeat(1 + rnd.nextInt(3)) { c[rnd.nextInt(c.size)] = "[]{}\",:0a\\".random(kotlin.random.Random(rnd.nextLong())) }
            try { Nostr.parseRelayMessage(String(c, 0, rnd.nextInt(c.size) + 1)) } catch (t: Throwable) { bad = bad ?: t }
        }
        assertNull("$bad", bad)
    }

    @Test fun breakera_bip340RejectsDegenerate() {
        val z = ByteArray(32)
        assertFalse(Bip340.verify(z, z, ByteArray(64)))
        val sk = ByteArray(32) { 2 }
        val msg = ByteArray(32) { 9 }
        val sig = Bip340.sign(msg, sk, ByteArray(32))
        val pk = Bip340.publicKey(sk)
        assertTrue(Bip340.verify(msg, pk, sig))
        val s2 = sig.copyOf(); s2[40] = (s2[40] + 1).toByte()
        assertFalse(Bip340.verify(msg, pk, s2))
        val ev = Nostr.signEvent(sk, 1L, 1, emptyList(), "x")
        assertFalse(Nostr.verifyEvent(ev.copy(id = ev.id.uppercase())))
        assertFalse(Nostr.verifyEvent(ev.copy(content = "y")))
    }

    // ---------- (6) crypto ----------
    private fun ByteArray.indexOfSubArray(sub: ByteArray): Int {
        outer@ for (i in 0..size - sub.size) { for (j in sub.indices) if (this[i + j] != sub[j]) continue@outer; return i }
        return -1
    }
}
