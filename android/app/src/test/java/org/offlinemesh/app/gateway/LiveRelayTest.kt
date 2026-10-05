package org.offlinemesh.app.gateway

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.offlinemesh.app.ble.MeshFrameCodec
import org.offlinemesh.app.ble.UplinkGateway
import java.io.File

/**
 * OPT-IN: talks to real public relays, so it is skipped unless `RELAY_LIVE=1`. Sends a handful of
 * random-byte events (no real data) and writes a per-relay report to `RELAY_LIVE_OUT`. It exists to
 * answer what no fake can: does a relay accept OUR Kotlin-signed events, and which kinds do relays take
 * (ephemeral 22007 vs stored 7007 with an expiration tag)?
 */
class LiveRelayTest {
    private val urls = listOf(
        "wss://relay.snort.social", "wss://nos.lol", "wss://nostr.mom",
        "wss://relay.primal.net", "wss://relay.damus.io",
    )

    private class Recording(private val inner: RelayConnector, val log: MutableList<String>) : RelayConnector {
        override fun connect(url: String, listener: RelayListener): RelayConnection {
            val c = inner.connect(
                url,
                object : RelayListener {
                    override fun onOpen() { log.add("$url OPEN"); listener.onOpen() }
                    override fun onMessage(text: String) {
                        val interesting = listOf("[\"OK\"", "[\"NOTICE\"", "[\"CLOSED\"")
                        if (interesting.any { text.startsWith(it) }) {
                            log.add("$url ${text.take(160)}")
                        }
                        listener.onMessage(text)
                    }
                    override fun onClosed(reason: String) { log.add("$url CLOSED $reason"); listener.onClosed(reason) }
                },
            )
            return c
        }
    }

    @Test fun `kotlin-signed live and mailbox events are accepted by real relays and cross between two links`() {
        assumeTrue(System.getenv("RELAY_LIVE") == "1")
        val log = ArrayList<String>()
        val connector = Recording(OkHttpRelayConnector(), log)
        val tag = ByteArray(6) { (System.nanoTime() shr (it * 5)).toByte() }
        val injected = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        val gwA = UplinkGateway(); val gwB = UplinkGateway()
        val a = NostrGatewayLink(gwA, urls, connector, inject = {})
        val b = NostrGatewayLink(gwB, urls, connector, inject = { injected.add(it) })
        b.setInterestTags(listOf(tag))
        val now = System.currentTimeMillis() / 1000
        val live = ByteArray(300) { 1 }
        val text = ByteArray(300) { 2 }
        a.offerFromBle(MeshFrameCodec.Frame.Uplink(tag, MeshFrameCodec.UPLINK_CLASS_LIVE, 0, now, live))
        a.offerFromBle(MeshFrameCodec.Frame.Uplink(tag, MeshFrameCodec.UPLINK_CLASS_TEXT, 0, now, text))
        val deadline = System.currentTimeMillis() + LIVE_WAIT_MS
        while (System.currentTimeMillis() < deadline && injected.size < 2) {
            a.tick(); b.tick(); Thread.sleep(TICK_MS)
        }
        // Phase 2: a mule that connects AFTER the text was published. Only a relay that stored the
        // mailbox event can serve it, so this measures real stored-retrieval, not live push.
        val lateInjected = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        val late = NostrGatewayLink(UplinkGateway(), urls, connector, inject = { lateInjected.add(it) })
        late.setInterestTags(listOf(tag))
        val lateDeadline = System.currentTimeMillis() + LATE_WAIT_MS
        while (System.currentTimeMillis() < lateDeadline && lateInjected.isEmpty()) {
            late.tick(); Thread.sleep(TICK_MS)
        }
        val report = buildString {
            appendLine("injected at B: ${injected.size} of 2 (live + text)")
            appendLine("late collector (joined after publish) got: ${lateInjected.size} (1 = the stored text)")
            appendLine("A status: ${a.relayStatus()}")
            appendLine("B status: ${b.relayStatus()}")
            log.forEach { appendLine(it) }
        }
        System.getenv("RELAY_LIVE_OUT")?.let { File(it).writeText(report) }
        a.close(); b.close(); late.close()
        assertTrue(report, injected.isNotEmpty())
    }

    private companion object {
        const val LIVE_WAIT_MS = 25_000L
        const val LATE_WAIT_MS = 12_000L
        const val TICK_MS = 500L
    }
}
