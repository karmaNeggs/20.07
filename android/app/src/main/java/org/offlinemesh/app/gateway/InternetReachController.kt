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

    /** A frame a Bluetooth neighbour handed us to carry (we may hold it, and upload it once we have internet). */
    fun offerFromBle(frame: MeshFrameCodec.Frame.Uplink): UplinkGateway.Decision =
        UplinkGateway.Decision.Rejected(UplinkGateway.Reject.BAD_CLASS)

    /** Frames held for upload, as (key, encoded), to hand to Bluetooth neighbours. */
    fun heldFrames(maxFrames: Int, maxFrameBytes: Int): List<Pair<String, ByteArray>> = emptyList()

    /** True when most relays are failing or banning us: slow down and pause bulk. */
    fun congested(): Boolean = false

    /** Whether file (bulk) frames may be sent now (unmetered network only). */
    fun setBulkAllowed(allowed: Boolean) {}

    /** One line describing each relay's state, for diagnostics only. */
    fun summary(): String = ""
}

/** One group this phone belongs to, with its root key. */
class UplinkGroup(val id: String, val rootKey: ByteArray)

/** A text-like item (message or nickname) with a stable [id] so it is wrapped only once. */
class MailboxItem(val id: String, val frame: ByteArray, val alert: Boolean = false)

/** A complete file this phone can send: its header frame and a source of fountain symbol frames
 *  (PLAN-v2.md Part 14). */
class FileUplink(
    val id: String,
    val metaFrame: ByteArray,
    val symbolsWanted: Int,
    val nextSymbols: suspend (count: Int) -> List<ByteArray>,
)

/** Where the controller gets frames to send. The app implements this over `RelayResponder`. */
interface UplinkSource {
    suspend fun groups(): List<UplinkGroup>

    /** Presence and positions (own, and those held for other members), sealed, ready to wrap as LIVE. */
    suspend fun liveFrames(groupId: String): List<ByteArray>

    /** Messages and nicknames, sealed, ready to wrap as TEXT. */
    suspend fun mailboxItems(groupId: String): List<MailboxItem>

    /** The newest position known for each member (own included), sealed, ready to wrap as POSITION_LAST_KNOWN. */
    suspend fun lastKnownFrames(groupId: String): List<ByteArray>

    /** Complete files small enough to send over the internet. Default none. */
    suspend fun fileUplinks(groupId: String): List<FileUplink> = emptyList()
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
// TooManyFunctions: one small producer per traffic class plus start/stop/step, sharing one clock and link.
@Suppress("TooManyFunctions", "LongParameterList")
class InternetReachController(
    private val source: UplinkSource,
    private val newLink: (inject: (ByteArray) -> Unit) -> UplinkLink,
    private val onInbound: suspend (cls: Int, inner: ByteArray) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val config: Config = Config(),
    private val bulkAllowed: () -> Boolean = { false },
    private val extraInterest: () -> Collection<ByteArray> = { emptyList() },
) {
    data class Config(
        val fileIntervalMs: Long = FILE_INTERVAL_MS,
        val symbolsPerTick: Int = SYMBOLS_PER_TICK,
        val maxSlowdown: Int = MAX_SLOWDOWN,
        val jitterFraction: Double = JITTER_FRACTION,
        val random: kotlin.random.Random = kotlin.random.Random.Default,
        val liveIntervalMs: Long = LIVE_INTERVAL_MS,
        val mailboxIntervalMs: Long = MAILBOX_INTERVAL_MS,
        val lastKnownIntervalMs: Long = LAST_KNOWN_INTERVAL_MS,
        val interestIntervalMs: Long = INTEREST_INTERVAL_MS,
        val maxUplinkedIds: Int = MAX_UPLINKED_IDS,
    )

    private var link: UplinkLink? = null
    private class Inbound(val cls: Int, val inner: ByteArray)

    private var inbound = Channel<Inbound>(Channel.UNLIMITED)
    private var nextLiveAt = 0L
    private var slowFactor = 1
    private var lastSlowChangeAt = 0L
    private var lastMailboxAt = 0L
    private var nextKnownAt = 0L
    private var lastFileAt = 0L
    private val metaSent = HashSet<String>()
    private val symbolsSent = HashMap<String, Int>()
    private var lastInterestAt = 0L
    private val uplinkedIds = object : LinkedHashMap<String, Boolean>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) = size > config.maxUplinkedIds
    }

    val running: Boolean get() = link != null

    /** The relay tags this phone's own groups listen on (for the link, and for interest frames to neighbours). */
    suspend fun ownInterestTags(): List<ByteArray> =
        source.groups().flatMap { UplinkTags.interestTags(it.rootKey, now() / MS_PER_SEC) }

    fun heldFrames(maxFrames: Int, maxFrameBytes: Int): List<Pair<String, ByteArray>> =
        link?.heldFrames(maxFrames, maxFrameBytes) ?: emptyList()

    fun offerFromBle(frame: MeshFrameCodec.Frame.Uplink): UplinkGateway.Decision? = link?.offerFromBle(frame)

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
        // First periodic publish at a random moment within one interval (when jitter is on), so a crowd that
        // switches on together, or a relay coming back after an outage, does not produce a synchronised burst.
        val t = now()
        val spread = config.jitterFraction > 0
        val r = config.random
        nextLiveAt = if (spread) t + (config.liveIntervalMs * r.nextDouble()).toLong() else 0
        nextKnownAt = if (spread) t + (config.lastKnownIntervalMs * r.nextDouble()).toLong() else 0
        lastMailboxAt = 0; lastInterestAt = 0; lastFileAt = 0; slowFactor = 1
    }

    fun stop() {
        link?.close()
        link = null
        inbound.close()
        // uplinkedIds and file progress are KEPT across stop/start: what already went out is on the relays, and a
        // switch
        // toggle or network flap must not re-publish the whole mailbox history (a re-wrap gets a fresh timestamp, so
        // the gateway's dedup would not catch it).
    }

    /** Remember an item that arrived from the relay so it is never re-published back (echo control). */
    fun markUplinked(id: String) { uplinkedIds[id] = true }

    suspend fun step() {
        val l = link ?: return
        val t = now()
        if (t - lastInterestAt >= config.interestIntervalMs) {
            lastInterestAt = t
            l.setInterestTags(ownInterestTags() + extraInterest())
        }
        val bulk = bulkAllowed()
        l.setBulkAllowed(bulk)
        // Under congestion (most relays failing or banning us) live and last-known slow down and bulk pauses;
        // messages and SOS alerts are never slowed (PLAN-v2.md Part 14.2 and 14.3).
        val congested = l.congested()
        updateSlowFactor(congested, t)
        // Frames are produced and HELD even with no relay connected, so a phone with no internet can hand them to a
        // Bluetooth neighbour that has some (stranger carrying); only files wait for a real connection.
        val connected = l.hasConnectedRelay()
        run {
            if (t - lastFileAt >= config.fileIntervalMs) {
                lastFileAt = t
                if (bulk && !congested && connected) produceFiles(l, t / MS_PER_SEC)
            }
            if (t >= nextLiveAt) {
                nextLiveAt = t + jittered(config.liveIntervalMs * slowFactor)
                produceLive(l, t / MS_PER_SEC)
            }
            if (t - lastMailboxAt >= config.mailboxIntervalMs) { lastMailboxAt = t; produceMailbox(l, t / MS_PER_SEC) }
            if (t >= nextKnownAt) {
                nextKnownAt = t + jittered(config.lastKnownIntervalMs * slowFactor)
                produceLastKnown(l, t / MS_PER_SEC)
            }
        }
        l.tick()
        drainInbound()
    }

    /** Multiplicative-increase, stepwise-decrease slow-down of the periodic traffic (never messages or alerts): doubles
     *  every 10 s while relays are failing, up to [Config.maxSlowdown], and recovers one step per 20 s. */
    private fun updateSlowFactor(congested: Boolean, t: Long) {
        if (congested && t - lastSlowChangeAt >= SLOW_UP_MS) {
            slowFactor = minOf(slowFactor * 2, config.maxSlowdown)
            lastSlowChangeAt = t
        } else if (!congested && slowFactor > 1 && t - lastSlowChangeAt >= SLOW_DOWN_MS) {
            slowFactor--
            lastSlowChangeAt = t
        }
    }

    /** +/- jitter so phones that started together do not publish in lockstep (flash-crowd synchronisation). */
    private fun jittered(ms: Long): Long {
        val j = config.jitterFraction
        return (ms * (1 - j + 2 * j * config.random.nextDouble())).toLong()
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

    /** One file's next paced step per call: its header first, then a batch of symbols until enough are sent. */
    private suspend fun produceFiles(l: UplinkLink, nowSec: Long) {
        for (g in source.groups()) {
            for (f in source.fileUplinks(g.id)) {
                if (produceFileStep(l, g, f, nowSec)) return
            }
        }
    }

    /** Returns true if it queued something for [f] (so the caller stops for this tick). */
    private suspend fun produceFileStep(l: UplinkLink, g: UplinkGroup, f: FileUplink, nowSec: Long): Boolean {
        if (f.id !in metaSent) {
            val meta = UplinkWrapper.wrap(g.rootKey, MeshFrameCodec.UPLINK_CLASS_FILE_META, f.metaFrame, nowSec)
            if (l.offerLocal(meta) is UplinkGateway.Decision.Accepted) metaSent.add(f.id)
            return true
        }
        val done = symbolsSent[f.id] ?: 0
        if (done >= f.symbolsWanted) return false
        val symbols = f.nextSymbols(minOf(config.symbolsPerTick, f.symbolsWanted - done))
        var accepted = 0
        for (sym in symbols) {
            val w = UplinkWrapper.wrap(g.rootKey, MeshFrameCodec.UPLINK_CLASS_FILE_SYMBOLS, sym, nowSec)
            if (l.offerLocal(w) is UplinkGateway.Decision.Accepted) accepted++
        }
        symbolsSent[f.id] = done + accepted
        return true
    }

    private suspend fun produceMailbox(l: UplinkLink, nowSec: Long) {
        for (g in source.groups()) {
            for (item in source.mailboxItems(g.id)) {
                if (uplinkedIds.containsKey(item.id)) continue
                val cls = if (item.alert) MeshFrameCodec.UPLINK_CLASS_ALERT else MeshFrameCodec.UPLINK_CLASS_TEXT
                val wrapped = UplinkWrapper.wrap(g.rootKey, cls, item.frame, nowSec)
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
        const val FILE_INTERVAL_MS = 5_000L
        const val SYMBOLS_PER_TICK = 60
        const val MAX_SLOWDOWN = 6
        const val JITTER_FRACTION = 0.25
        private const val SLOW_UP_MS = 10_000L
        private const val SLOW_DOWN_MS = 20_000L
        const val INTEREST_INTERVAL_MS = 30_000L
        const val MAX_UPLINKED_IDS = 2048
        private const val MS_PER_SEC = 1000L
        private const val INITIAL_CAPACITY = 256
        private const val LOAD_FACTOR = 0.75f
    }
}
