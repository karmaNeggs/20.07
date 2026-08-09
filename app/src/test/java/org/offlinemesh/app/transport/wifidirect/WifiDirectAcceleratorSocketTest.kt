package org.offlinemesh.app.transport.wifidirect

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.offlinemesh.app.ble.MeshFrameCodec
import org.robolectric.RobolectricTestRunner
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * Exercises [WifiDirectAccelerator.handshakeToken]/[WifiDirectAccelerator.receiveChunks] over a
 * REAL loopback [Socket] pair — these two methods are plain `java.io`/`java.net` stream parsing,
 * with no `WifiP2pManager` involved, so a real localhost socket is more honest here than a fake
 * would be. Robolectric-backed only because [WifiDirectAccelerator]'s constructor touches
 * `Context.getSystemService(WIFI_P2P_SERVICE)` — Robolectric shadows `WifiP2pManager`, so
 * construction succeeds without ever calling connect()/discoverPeers() (this test never does).
 *
 * Covers a fixed bug: both methods previously allocated `ByteArray(peerLen)` / `ByteArray(len)` directly
 * off an unauthenticated, attacker-controlled length prefix read from the raw socket — before
 * anything on that socket was verified. A device that won the accept()/connect() race (not
 * necessarily the intended peer) could crash the phone with a few bytes. Also covers the
 * token-handshake becoming role-asymmetric: previously both sides sent the identical raw token,
 * so a socket recipient learned the real secret before proving it held it itself.
 *
 * Deliberately uses real [Thread]s (each running its own `runBlocking`), NOT `runTest`/`async`:
 * [WifiDirectAccelerator.handshakeToken] does real, non-suspending blocking socket I/O
 * (`DataInputStream.readInt`/`readFully`), and two such calls that depend on each other's real-time
 * progress will deadlock under `runTest`'s single-threaded virtual-time scheduler — only one
 * coroutine can actually run on that scheduler's one thread at a time, so if it blocks on real I/O
 * waiting for the other side, the other side never gets scheduled. Real threads sidestep this
 * entirely, matching how these two socket ends genuinely run on separate real threads/processes in
 * production.
 */
@RunWith(RobolectricTestRunner::class)
class WifiDirectAcceleratorSocketTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val accelerator = WifiDirectAccelerator(context)

    private fun randomToken() = ByteArray(16).also { SecureRandom().nextBytes(it) }

    private fun loopbackPair(): Pair<Socket, Socket> {
        val server = ServerSocket(0)
        val clientSocket = Socket("127.0.0.1", server.localPort)
        val serverSocket = server.accept()
        server.close()
        return clientSocket to serverSocket
    }

    /** Runs [block] on a real, separate JVM thread and returns its result — see class doc for why
     *  this, not a coroutine, is required around blocking socket I/O in these tests. */
    private fun <T> callOnThread(block: () -> T): T {
        var result: Result<T>? = null
        val thread = Thread { result = runCatching(block) }
        thread.start()
        thread.join(10_000)
        return result!!.getOrThrow()
    }

    @Test
    fun `matching tokens with correct opposite roles handshake successfully`() {
        val (initiatorSocket, responderSocket) = loopbackPair()
        val token = randomToken()
        var initiatorOk = false
        var responderOk = false
        val initiatorThread = Thread {
            initiatorOk = runBlocking {
                accelerator.handshakeToken(initiatorSocket, token, WifiDirectAccelerator.TokenRole.INITIATOR)
            }
        }
        val responderThread = Thread {
            responderOk = runBlocking {
                accelerator.handshakeToken(responderSocket, token, WifiDirectAccelerator.TokenRole.RESPONDER)
            }
        }
        initiatorThread.start(); responderThread.start()
        initiatorThread.join(10_000); responderThread.join(10_000)
        assertTrue(initiatorOk)
        assertTrue(responderOk)
        initiatorSocket.close(); responderSocket.close()
    }

    @Test
    fun `a wrong-token peer fails the handshake instead of it silently succeeding`() {
        val (initiatorSocket, responderSocket) = loopbackPair()
        var initiatorOk = true
        var responderOk = true
        val initiatorThread = Thread {
            initiatorOk = runBlocking {
                accelerator.handshakeToken(initiatorSocket, randomToken(), WifiDirectAccelerator.TokenRole.INITIATOR)
            }
        }
        val responderThread = Thread {
            responderOk = runBlocking {
                accelerator.handshakeToken(responderSocket, randomToken(), WifiDirectAccelerator.TokenRole.RESPONDER)
            }
        }
        initiatorThread.start(); responderThread.start()
        initiatorThread.join(10_000); responderThread.join(10_000)
        assertFalse(initiatorOk)
        assertFalse(responderOk)
        initiatorSocket.close(); responderSocket.close()
    }

    @Test
    fun `neither side ever puts the raw shared token on the wire`() {
        // Directly the security property TokenRole/deriveTokenTag exists for: the derived value
        // that actually crosses the socket must never equal the shared token itself, and the two
        // roles' derived values must differ from each other.
        val token = randomToken()
        val initiatorTag = accelerator.deriveTokenTag(token, WifiDirectAccelerator.TokenRole.INITIATOR)
        val responderTag = accelerator.deriveTokenTag(token, WifiDirectAccelerator.TokenRole.RESPONDER)
        assertFalse(token.contentEquals(initiatorTag))
        assertFalse(token.contentEquals(responderTag))
        assertFalse(
            "the two roles' derived tags must differ from each other too",
            initiatorTag.contentEquals(responderTag)
        )
    }

    @Test
    fun `handshakeToken rejects a hostile oversized length prefix without allocating`() {
        val (attackerSocket, victimSocket) = loopbackPair()
        // The attacker writes a length prefix far beyond MAX_TOKEN_TAG_BYTES, then nothing else —
        // a real attack would never actually have that many bytes to send, which is the whole
        // point: the victim must reject on the length alone, not hang waiting for readFully to
        // receive gigabytes that are never coming.
        DataOutputStream(attackerSocket.getOutputStream()).apply {
            writeInt(WifiDirectTuning.MAX_TOKEN_TAG_BYTES + 1_000_000)
            flush()
        }
        val victimResult = callOnThread {
            runBlocking {
                accelerator.handshakeToken(victimSocket, randomToken(), WifiDirectAccelerator.TokenRole.RESPONDER)
            }
        }
        assertFalse(victimResult)
        attackerSocket.close(); victimSocket.close()
    }

    @Test
    fun `receiveChunks rejects a hostile oversized frame length prefix without allocating`() {
        val (attackerSocket, victimSocket) = loopbackPair()
        DataOutputStream(attackerSocket.getOutputStream()).apply {
            writeInt(WifiDirectTuning.MAX_CHUNK_FRAME_BYTES + 10_000_000)
            flush()
        }
        val received = mutableListOf<MeshFrameCodec.Frame.EvidSymbol>()
        callOnThread {
            runBlocking { accelerator.receiveChunks(victimSocket) { received.add(it) } }
        }
        assertTrue("a hostile length prefix must stop the loop, not allocate or hang", received.isEmpty())
        attackerSocket.close(); victimSocket.close()
    }

    @Test
    fun `receiveChunks still accepts a legitimate chunk frame under the cap`() {
        val (senderSocket, receiverSocket) = loopbackPair()
        val symbol =
            MeshFrameCodec.Frame.EvidSymbol(evidenceId = "evid-1", esi = 3, data = ByteArray(400) { it.toByte() })
        val encoded = MeshFrameCodec.encodeEvidSymbol(symbol)
        DataOutputStream(senderSocket.getOutputStream()).apply {
            writeInt(encoded.size)
            write(encoded)
            flush()
        }
        senderSocket.close() // EOF after one frame — receiveChunks' loop exits cleanly on EOFException
        val received = mutableListOf<MeshFrameCodec.Frame.EvidSymbol>()
        callOnThread {
            runBlocking { accelerator.receiveChunks(receiverSocket) { received.add(it) } }
        }
        assertEquals(1, received.size)
        assertEquals("evid-1", received[0].evidenceId)
        assertEquals(3, received[0].esi)
        receiverSocket.close()
    }
}
