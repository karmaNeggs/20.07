package org.offlinemesh.app.gateway

/** One open socket to a relay. Implemented over OkHttp in production and by fakes in tests. */
interface RelayConnection {
    /** Queues [text] for sending. False means the socket is dead. */
    fun send(text: String): Boolean
    fun close()
}

/** Callbacks a transport makes into the pool. May arrive on any thread. */
interface RelayListener {
    fun onOpen()
    fun onMessage(text: String)
    fun onClosed(reason: String)
}

fun interface RelayConnector {
    fun connect(url: String, listener: RelayListener): RelayConnection
}

/**
 * Several Nostr relays treated as one lossy pipe (`PLAN-v2.md` §13.5, decision 67): publishes every
 * event to every relay, subscribes on all of them, and de-duplicates what comes back.
 *
 * Rules taken from the real-relay spike: at most one EVENT per relay per [Config.minPublishIntervalMs]
 * (damus rejects faster and bans); a relay that says it is banning, restricting or rate-limiting us is
 * backed off instead of hammered; every relay is expected to be down, slow or hostile some of the time,
 * so a publish is "settled" when each relay has answered or timed out and the caller learns how many
 * accepted it.
 *
 * No threads and no clock of its own: the owner calls [tick] about once a second. Public methods are
 * synchronized and every outward callback is invoked after the lock is released, so a callback may
 * safely call back into the pool.
 */
// TooManyFunctions: a connection state machine, one small function per transition (open, close, ban, ack,
// expiry, resolve), sharing the per-relay state they all mutate.
@Suppress("TooManyFunctions")
class RelayPool(
    private val urls: List<String>,
    private val connector: RelayConnector,
    private val now: () -> Long = System::currentTimeMillis,
    private val config: Config = Config(),
    private val onEvent: (event: NostrEvent, relayUrl: String) -> Unit,
    private val onPublishSettled: (eventId: String, accepted: Int, attempted: Int) -> Unit,
) {
    data class Config(
        val minPublishIntervalMs: Long = MIN_PUBLISH_INTERVAL_MS,
        val ackTimeoutMs: Long = ACK_TIMEOUT_MS,
        val reconnectBaseMs: Long = RECONNECT_BASE_MS,
        val reconnectMaxMs: Long = RECONNECT_MAX_MS,
        val banBackoffMs: Long = BAN_BACKOFF_MS,
        val rateLimitPauseMs: Long = RATE_LIMIT_PAUSE_MS,
        val maxQueuePerRelay: Int = MAX_QUEUE_PER_RELAY,
        val relaysPerEvent: Int = RELAYS_PER_EVENT,
        val queueTtlMs: Long = QUEUE_TTL_MS,
        val seenEventIds: Int = SEEN_EVENT_IDS,
        val subscriptionId: String = SUBSCRIPTION_ID,
    )

    enum class State { CONNECTED, CONNECTING, BACKOFF, BANNED }
    data class Status(val url: String, val state: State, val queued: Int, val failures: Int)

    private class Relay(val url: String) {
        var connection: RelayConnection? = null
        var open = false
        var failures = 0
        var nextConnectAt = 0L
        var nextPublishAt = 0L
        var bannedUntil = 0L
        var generation = 0
        var closedGeneration = -1
        var subscribedTo: List<NostrFilter>? = null
        val queue = ArrayDeque<Queued>()
        val awaiting = LinkedHashMap<String, Long>()
    }

    private class Queued(val event: NostrEvent, val at: Long)

    private class Settle(var pending: Int, var accepted: Int, val attempted: Int)

    private val relays = urls.associateWith { Relay(it) }
    private val settles = HashMap<String, Settle>()
    private val seen = object : LinkedHashMap<String, Boolean>(SEEN_INITIAL_CAPACITY, SEEN_LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) = size > config.seenEventIds
    }
    private var desired: List<NostrFilter> = emptyList()
    private val lock = Any()

    /** Replaces the subscription on every relay. An empty list closes it. */
    fun setSubscription(filters: List<NostrFilter>) = locked { _ ->
        desired = filters
    }

    /** Sends [event] to every usable relay. [onPublishSettled] reports how many accepted it. */
    fun publish(event: NostrEvent) = locked { out ->
        val t = now()
        val notBanned = relays.values.filter { it.bannedUntil <= t }
        // Prefer relays that are connected, then send to only the same two per tag (spreads load, see RelayChoice).
        val candidates = notBanned.filter { it.open }.ifEmpty { notBanned }
        val chosen = RelayChoice.pick(event.tagValue("t") ?: event.id, candidates.map { it.url },
            config.relaysPerEvent).toSet()
        val usable = candidates.filter { it.url in chosen }
        val settle = Settle(0, 0, usable.size)
        settles[event.id] = settle
        for (r in usable) {
            if (r.queue.size >= config.maxQueuePerRelay) {
                val dropped = r.queue.removeFirst()
                resolve(dropped.event.id, accepted = false, out)
            }
            r.queue.addLast(Queued(event, t))
            settle.pending++
        }
        if (settle.pending == 0) {
            settles.remove(event.id)
            out.add { onPublishSettled(event.id, 0, 0) }
        }
    }

    /** Drives connecting, subscribing, paced publishing and ack timeouts. Call about once a second. */
    fun tick() = locked { out ->
        val t = now()
        for (r in relays.values) {
            expireAcks(r, t, out)
            expireQueue(r, t, out)
            if (r.connection == null && t >= r.nextConnectAt && t >= r.bannedUntil) connect(r)
            service(r, t, out)
        }
    }

    /** True when half or more of the relays are banned or failing: the owner should slow down and pause bulk. */
    fun congested(): Boolean = synchronized(lock) {
        val t = now()
        relays.values.count { it.bannedUntil > t || it.failures >= CONGESTED_FAILURES } * 2 >= relays.size
    }

    fun hasConnectedRelay(): Boolean = synchronized(lock) { relays.values.any { it.open && it.bannedUntil <= now() } }

    fun status(): List<Status> = synchronized(lock) {
        val t = now()
        relays.values.map { r ->
            val state = when {
                r.bannedUntil > t -> State.BANNED
                r.open -> State.CONNECTED
                r.connection != null -> State.CONNECTING
                else -> State.BACKOFF
            }
            Status(r.url, state, r.queue.size, r.failures)
        }
    }

    fun close() = locked { out ->
        for (r in relays.values) {
            r.generation++
            r.connection?.close()
            r.connection = null
            r.open = false
            failEverything(r, out)
        }
    }

    private fun connect(r: Relay) {
        val generation = ++r.generation
        val connection = connector.connect(
            r.url,
            object : RelayListener {
                override fun onOpen() = handleOpen(r.url, generation)
                override fun onMessage(text: String) = handleMessage(r.url, generation, text)
                override fun onClosed(reason: String) = handleClosed(r.url, generation)
            },
        )
        // A transport can fail (call onClosed) before connect() even returns; keep that dead socket out.
        if (r.closedGeneration != generation) r.connection = connection else connection.close()
    }

    private fun handleOpen(url: String, generation: Int) = locked { out ->
        val r = relays.getValue(url)
        if (r.generation != generation) return@locked
        r.open = true
        r.failures = 0
        r.subscribedTo = null
        service(r, now(), out)
    }

    private fun handleClosed(url: String, generation: Int) = locked { out ->
        val r = relays.getValue(url)
        if (r.generation != generation || r.closedGeneration == generation) return@locked
        r.closedGeneration = generation
        r.connection = null
        r.open = false
        r.failures++
        r.nextConnectAt = now() + backoff(r.failures)
        failAwaiting(r, out)
    }

    private fun handleMessage(url: String, generation: Int, text: String) = locked { out ->
        val r = relays.getValue(url)
        if (r.generation != generation) return@locked
        when (val m = Nostr.parseRelayMessage(text)) {
            is RelayMessage.Event -> acceptIncoming(m.event, url, out)
            is RelayMessage.Ok -> handleOk(r, m, out)
            is RelayMessage.Closed -> {
                r.subscribedTo = null
                if (looksLikeBlock(m.message)) ban(r, out)
            }
            else -> Unit
        }
    }

    private fun acceptIncoming(event: NostrEvent, url: String, out: MutableList<() -> Unit>) {
        if (seen.containsKey(event.id)) return
        if (!Nostr.verifyEvent(event)) return
        seen[event.id] = true
        out.add { onEvent(event, url) }
    }

    private fun handleOk(r: Relay, m: RelayMessage.Ok, out: MutableList<() -> Unit>) {
        if (r.awaiting.remove(m.eventId) == null) return
        val accepted = m.accepted || m.message.startsWith("duplicate")
        resolve(m.eventId, accepted, out)
        if (accepted) return
        when {
            looksLikeBlock(m.message) -> ban(r, out)
            m.message.startsWith("rate-limited") -> r.nextPublishAt = now() + config.rateLimitPauseMs
        }
    }

    private fun service(r: Relay, t: Long, out: MutableList<() -> Unit>) {
        val conn = r.connection
        if (conn == null || !r.open) return
        val wantsSubscriptionChange = r.subscribedTo != desired && !(desired.isEmpty() && r.subscribedTo == null)
        if (wantsSubscriptionChange) {
            val ok = if (desired.isEmpty()) {
                conn.send(Nostr.closeMessage(config.subscriptionId))
            } else {
                conn.send(Nostr.reqMessage(config.subscriptionId, desired))
            }
            if (ok) r.subscribedTo = desired else dropConnection(r, out)
        }
        if (r.connection != null && r.queue.isNotEmpty() && t >= r.nextPublishAt) {
            val event = r.queue.removeFirst().event
            // Recorded BEFORE sending: a transport may answer synchronously, and that OK must find it.
            r.awaiting[event.id] = t
            r.nextPublishAt = t + config.minPublishIntervalMs
            if (!conn.send(Nostr.eventMessage(event))) {
                r.awaiting.remove(event.id)
                resolve(event.id, accepted = false, out)
                dropConnection(r, out)
            }
        }
    }

    private fun expireAcks(r: Relay, t: Long, out: MutableList<() -> Unit>) {
        val expired = r.awaiting.filterValues { t - it > config.ackTimeoutMs }.keys.toList()
        for (id in expired) {
            r.awaiting.remove(id)
            r.failures++
            resolve(id, accepted = false, out)
        }
    }

    private fun expireQueue(r: Relay, t: Long, out: MutableList<() -> Unit>) {
        while (r.queue.isNotEmpty() && t - r.queue.first().at > config.queueTtlMs) {
            resolve(r.queue.removeFirst().event.id, accepted = false, out)
        }
    }

    private fun dropConnection(r: Relay, out: MutableList<() -> Unit>) {
        r.generation++ // callbacks from the socket we are discarding must not be counted again
        r.connection?.close()
        r.connection = null
        r.open = false
        r.failures++
        r.nextConnectAt = now() + backoff(r.failures)
        failAwaiting(r, out)
    }

    private fun ban(r: Relay, out: MutableList<() -> Unit>) {
        r.generation++
        r.bannedUntil = now() + config.banBackoffMs
        r.connection?.close()
        r.connection = null
        r.open = false
        failEverything(r, out)
    }

    private fun failAwaiting(r: Relay, out: MutableList<() -> Unit>) {
        val ids = r.awaiting.keys.toList()
        r.awaiting.clear()
        for (id in ids) resolve(id, accepted = false, out)
    }

    private fun failEverything(r: Relay, out: MutableList<() -> Unit>) {
        failAwaiting(r, out)
        val queued = r.queue.toList()
        r.queue.clear()
        for (q in queued) resolve(q.event.id, accepted = false, out)
    }

    private fun resolve(eventId: String, accepted: Boolean, out: MutableList<() -> Unit>) {
        val s = settles[eventId] ?: return
        s.pending--
        if (accepted) s.accepted++
        if (s.pending <= 0) {
            settles.remove(eventId)
            out.add { onPublishSettled(eventId, s.accepted, s.attempted) }
        }
    }

    private fun backoff(failures: Int): Long {
        val shift = (failures - 1).coerceIn(0, MAX_BACKOFF_SHIFT)
        return (config.reconnectBaseMs shl shift).coerceAtMost(config.reconnectMaxMs)
    }

    private fun looksLikeBlock(message: String): Boolean {
        val m = message.lowercase()
        return BLOCK_MARKERS.any { m.contains(it) }
    }

    private fun locked(block: (MutableList<() -> Unit>) -> Unit) {
        val out = ArrayList<() -> Unit>()
        synchronized(lock) { block(out) }
        out.forEach { it() }
    }

    companion object {
        const val MIN_PUBLISH_INTERVAL_MS = 1100L
        const val ACK_TIMEOUT_MS = 10_000L
        const val RECONNECT_BASE_MS = 2_000L
        const val RECONNECT_MAX_MS = 300_000L
        const val BAN_BACKOFF_MS = 600_000L
        const val RATE_LIMIT_PAUSE_MS = 5_000L
        const val MAX_QUEUE_PER_RELAY = 32
        const val RELAYS_PER_EVENT = 2
        const val QUEUE_TTL_MS = 30_000L
        private const val CONGESTED_FAILURES = 3
        const val SEEN_EVENT_IDS = 4096
        const val SUBSCRIPTION_ID = "2007"
        private const val SEEN_INITIAL_CAPACITY = 256
        private const val SEEN_LOAD_FACTOR = 0.75f
        private const val MAX_BACKOFF_SHIFT = 10
        private val BLOCK_MARKERS = listOf(
            "banned", "blocked", "restricted", "web of trust", "auth-required", "paid", "not authorized",
        )
    }
}
