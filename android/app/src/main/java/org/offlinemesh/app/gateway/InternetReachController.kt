package org.offlinemesh.app.gateway

import kotlinx.coroutines.channels.Channel
import org.offlinemesh.app.ble.MeshFrameCodec
import org.offlinemesh.app.ble.UplinkGateway

/** What the controller needs from the link. [NostrGatewayLink] is the production implementation. */
interface UplinkLink {
    fun offerLocal(frame: MeshFrameCodec.Frame.Uplink): UplinkGateway.Decision
    fun setInterestTags(tags: Collection<ByteArray>)
    fun tick()
    fun close()
    fun hasConnectedRelay(): Boolean

    /** One line describing each relay's state, for diagnostics only. */
    fun summary(): String = ""
}

/** One group this phone belongs to, with its root key. */
class UplinkGroup(val id: String, val rootKey: ByteArray)

/** A text-like item (message or nickname) with a stable [id] so it is wrapped only once. */
class MailboxItem(val id: String, val frame: ByteArray)

/** Where the controller gets frames to send. The app implements this over `RelayResponder`. */
interface UplinkSource {
    suspend fun groups(): List<UplinkGroup>

    /** Presence and positions (own, and those held for other members), sealed, ready to wrap as LIVE. */
    suspend fun liveFrames(groupId: String): List<ByteArray>

    /** Messages and nicknames, sealed, ready to wrap as TEXT. */
    suspend fun mailboxItems(groupId: String): List<MailboxItem>

    /** The newest position known for each member (own included), sealed, ready to wrap as POSITION_LAST_KNOWN. */
    suspend fun lastKnownFrames(groupId: String): List<ByteArray>
}

/**
 * Makes an online phone a full endpoint (`PLAN-v2.md` §13.17): it wraps and publishes its own groups'
 * frames, listens on its own groups' tags, and hands whatever arrives from the relays to [onInbound] (the
 * existing frame handler), so a message lands in chat and a position on the radar exactly as over BLE.
 * Because members hold the keys, a phone with BLE neighbours is also the bridge: whatever its normal
 * relaying has learned from them is published too.
 *
 * Works with Bluetooth off: nothing here touches the radios.
 *
 * Drive it with [start]/[stop] (the switch and connectivity) and [step] about once a second. All work
 * happens inside [step] on the caller's coroutine; the link's own callbacks only enqueue.
 */
class InternetReachController(
    private val source: UplinkSource,
    private val newLink: (inject: (ByteArray) -> Unit) -> UplinkLink,
    private val onInbound: suspend (cls: Int, inner: ByteArray) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val config: Config = Config(),
) {
    data class Config(
        val liveIntervalMs: Long = LIVE_INTERVAL_MS,
        val mailboxIntervalMs: Long = MAILBOX_INTERVAL_MS,
        val lastKnownIntervalMs: Long = LAST_KNOWN_INTERVAL_MS,
        val interestIntervalMs: Long = INTEREST_INTERVAL_MS,
        val maxUplinkedIds: Int = MAX_UPLINKED_IDS,
    )

    private var link: UplinkLink? = null
    private class Inbound(val cls: Int, val inner: ByteArray)

    private var inbound = Channel<Inbound>(Channel.UNLIMITED)
    private var lastLiveAt = 0L
    private var lastMailboxAt = 0L
    private var lastKnownAt = 0L
    private var lastInterestAt = 0L
    private val uplinkedIds = object : LinkedHashMap<String, Boolean>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) = size > config.maxUplinkedIds
    }

    val running: Boolean get() = link != null

    fun relayConnected(): Boolean = link?.hasConnectedRelay() == true

    fun summary(): String = link?.summary() ?: "stopped"

    fun start() {
        if (link != null) return
        inbound = Channel(Channel.UNLIMITED)
        val channel = inbound
        link = newLink { encodedUplink ->
            (MeshFrameCodec.decode(encodedUplink) as? MeshFrameCodec.Frame.Uplink)?.let {
                channel.trySend(Inbound(it.cls, it.inner))
            }
        }
        lastLiveAt = 0; lastMailboxAt = 0; lastKnownAt = 0; lastInterestAt = 0
    }

    fun stop() {
        link?.close()
        link = null
        inbound.close()
        uplinkedIds.clear()
    }

    /** Remember an item that arrived from the relay so it is never re-published back (echo control). */
    fun markUplinked(id: String) { uplinkedIds[id] = true }

    suspend fun step() {
        val l = link ?: return
        val t = now()
        if (t - lastInterestAt >= config.interestIntervalMs) {
            lastInterestAt = t
            l.setInterestTags(source.groups().flatMap { UplinkTags.interestTags(it.rootKey, t / MS_PER_SEC) })
        }
        if (l.hasConnectedRelay()) {
            if (t - lastLiveAt >= config.liveIntervalMs) { lastLiveAt = t; produceLive(l, t / MS_PER_SEC) }
            if (t - lastMailboxAt >= config.mailboxIntervalMs) { lastMailboxAt = t; produceMailbox(l, t / MS_PER_SEC) }
            if (t - lastKnownAt >= config.lastKnownIntervalMs) { lastKnownAt = t; produceLastKnown(l, t / MS_PER_SEC) }
        }
        l.tick()
        drainInbound()
    }

    private suspend fun produceLive(l: UplinkLink, nowSec: Long) {
        for (g in source.groups()) {
            for (frame in source.liveFrames(g.id)) {
                l.offerLocal(UplinkWrapper.wrap(g.rootKey, MeshFrameCodec.UPLINK_CLASS_LIVE, frame, nowSec))
            }
        }
    }

    private suspend fun produceLastKnown(l: UplinkLink, nowSec: Long) {
        for (g in source.groups()) {
            for (frame in source.lastKnownFrames(g.id)) {
                val cls = MeshFrameCodec.UPLINK_CLASS_POSITION_LAST_KNOWN
                l.offerLocal(UplinkWrapper.wrap(g.rootKey, cls, frame, nowSec))
            }
        }
    }

    private suspend fun produceMailbox(l: UplinkLink, nowSec: Long) {
        for (g in source.groups()) {
            for (item in source.mailboxItems(g.id)) {
                if (uplinkedIds.containsKey(item.id)) continue
                val wrapped = UplinkWrapper.wrap(g.rootKey, MeshFrameCodec.UPLINK_CLASS_TEXT, item.frame, nowSec)
                val decision = l.offerLocal(wrapped)
                if (decision is UplinkGateway.Decision.Accepted) uplinkedIds[item.id] = true
            }
        }
    }

    private suspend fun drainInbound() {
        while (true) {
            val item = inbound.tryReceive().getOrNull() ?: return
            onInbound(item.cls, item.inner)
        }
    }

    companion object {
        const val LIVE_INTERVAL_MS = 10_000L
        const val MAILBOX_INTERVAL_MS = 5_000L
        const val LAST_KNOWN_INTERVAL_MS = 60_000L
        const val INTEREST_INTERVAL_MS = 30_000L
        const val MAX_UPLINKED_IDS = 2048
        private const val MS_PER_SEC = 1000L
        private const val INITIAL_CAPACITY = 256
        private const val LOAD_FACTOR = 0.75f
    }
}
