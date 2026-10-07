package org.offlinemesh.app.gateway

import org.offlinemesh.app.ble.MeshFrameCodec
import org.offlinemesh.app.ble.UplinkGateway
import java.util.Base64

/**
 * Joins the blind [UplinkGateway] (what to carry) to a [RelayPool] (how to reach the internet), per
 * `PLAN-v2.md` Part 13. Not yet wired into the app (that is G2); it takes frames in through
 * [offerFromBle] and hands frames bound for the BLE mesh to [inject].
 *
 * **Event shapes** (decision 67 rules): LIVE frames go out as ephemeral kind [LIVE_KIND] under their 60 s
 * tag; POSITION_LAST_KNOWN and TEXT go out as stored kind [MAILBOX_KIND] with a NIP-40 `expiration` so a
 * recipient who connects later can still collect them. Content is the base64 of one [UplinkBatch]. Both
 * kinds are unproven on relays beyond the spike's ephemeral one; the opt-in live test measures them.
 *
 * **Interest tags:** a keyless gateway can only subscribe to tags somebody tells it about. [setInterestTags]
 * takes the set (local members' current 60 s tags and hourly mailbox tags); G2 feeds it from a new
 * member-to-gateway interest frame (`PLAN-v2.md` §13.16).
 *
 * Call [tick] about once a second from one owner; everything else is thread-safe through the pool.
 */
// LongParameterList: every collaborator (gateway, relays, transport, key, clock, injection sink, config) is a
// distinct seam the tests replace, so none can be folded away.
// TooManyFunctions: the UplinkLink surface plus a few pure helpers around one small state holder.
@Suppress("LongParameterList", "TooManyFunctions")
class NostrGatewayLink(
    private val gateway: UplinkGateway,
    urls: List<String>,
    connector: RelayConnector,
    private val keys: GatewayKeyHolder = GatewayKeyHolder(),
    private val now: () -> Long = System::currentTimeMillis,
    private val inject: (ByteArray) -> Unit,
    private val config: Config = Config(),
) : UplinkLink {
    data class Config(
        val maxBatchesPerTick: Int = MAX_BATCHES_PER_TICK,
        val mailboxLookbackSec: Long = MAILBOX_LOOKBACK_SEC,
        val liveLookbackSec: Long = LIVE_LOOKBACK_SEC,
        val mailboxLimit: Int = MAILBOX_LIMIT,
        val maxInterestTags: Int = MAX_INTEREST_TAGS,
        val poolConfig: RelayPool.Config = RelayPool.Config(),
    )

    @Volatile private var bulkAllowed = false
    private val backoff = PublishBackoff()
    private val inFlight = HashMap<String, UplinkGateway.Batch>()
    private val lock = Any()

    private val pool = RelayPool(
        urls, connector, now, config.poolConfig,
        onEvent = { event, _ -> onRelayEvent(event) },
        onPublishSettled = { id, accepted, _ -> onSettled(id, accepted) },
    )

    fun offerFromBle(frame: MeshFrameCodec.Frame.Uplink): UplinkGateway.Decision = gateway.offer(frame)

    /** This phone's own (or its group's) frame, wrapped by a member. Same admission rules as one heard over BLE. */
    override fun offerLocal(frame: MeshFrameCodec.Frame.Uplink): UplinkGateway.Decision = gateway.offer(frame)

    fun noteBeaconSeen(tag: ByteArray) = gateway.noteBeaconSeen(tag)

    /** Replaces the set of relay tags this gateway listens for. */
    override fun setInterestTags(tags: Collection<ByteArray>) {
        val hex = tags.map { it.toHex() }.distinct().take(config.maxInterestTags)
        if (hex.isEmpty()) {
            pool.setSubscription(emptyList())
            return
        }
        val nowSec = now() / MS_PER_SEC
        pool.setSubscription(
            listOf(
                NostrFilter(listOf(LIVE_KIND), mapOf("t" to hex), since = nowSec - config.liveLookbackSec),
                NostrFilter(
                    listOf(MAILBOX_KIND), mapOf("t" to hex),
                    since = nowSec - config.mailboxLookbackSec, limit = config.mailboxLimit,
                ),
            ),
        )
    }

    /** Pushes pool state forward and publishes whatever the gateway has ready. */
    override fun tick() {
        pool.tick()
        if (!pool.hasConnectedRelay() || backoff.blocked(now())) return
        val batches = gateway.drain(config.maxBatchesPerTick, includeBulk = bulkAllowed)
        for (batch in batches) publish(batch)
        // Flush right away so a freshly queued event does not wait a whole tick (the pool still paces sends).
        if (batches.isNotEmpty()) pool.tick()
    }

    fun relayStatus(): List<RelayPool.Status> = pool.status()

    override fun close() = pool.close()

    override fun hasConnectedRelay(): Boolean = pool.hasConnectedRelay()

    override fun congested(): Boolean = pool.congested()

    override fun setBulkAllowed(allowed: Boolean) { bulkAllowed = allowed }

    override fun summary(): String = pool.status().joinToString(" ") {
        it.url.removePrefix("wss://").removePrefix("relay.") + "=" + it.state.name.lowercase()
    }

    /** A last-known position is the most sensitive thing stored on a relay, so it expires sooner than a text. */
    private fun expirySec(cls: Int): Long =
        when (cls) {
            MeshFrameCodec.UPLINK_CLASS_POSITION_LAST_KNOWN -> UplinkGateway.POSITION_MAX_AGE_SEC
            MeshFrameCodec.UPLINK_CLASS_FILE_META,
                MeshFrameCodec.UPLINK_CLASS_FILE_SYMBOLS -> UplinkGateway.FILE_MAX_AGE_SEC
            else -> MAILBOX_EXPIRY_SEC
        }

    private fun publish(batch: UplinkGateway.Batch) {
        val nowSec = now() / MS_PER_SEC
        val mailbox = batch.cls != MeshFrameCodec.UPLINK_CLASS_LIVE
        val tags = ArrayList<List<String>>()
        tags.add(listOf("t", batch.relayTag.toHex()))
        if (mailbox) tags.add(listOf("expiration", (nowSec + expirySec(batch.cls)).toString()))
        val content = Base64.getEncoder().encodeToString(batch.content())
        val event = Nostr.signEvent(keys.current(), nowSec, if (mailbox) MAILBOX_KIND else LIVE_KIND, tags, content)
        synchronized(lock) { inFlight[event.id] = batch }
        pool.publish(event)
    }

    private fun onSettled(eventId: String, accepted: Int) {
        val batch = synchronized(lock) { inFlight.remove(eventId) } ?: return
        if (accepted == 0) {
            gateway.requeue(batch)
            backoff.failed(now())
        } else {
            backoff.succeeded()
        }
    }

    // SwallowedException: undecodable content from an untrusted relay is expected input and is simply dropped.
    @Suppress("SwallowedException")
    private fun onRelayEvent(event: NostrEvent) {
        if (event.kind != LIVE_KIND && event.kind != MAILBOX_KIND) return
        val bytes = try {
            Base64.getDecoder().decode(event.content)
        } catch (e: IllegalArgumentException) {
            return
        }
        for (frame in gateway.onRelayBatch(bytes)) inject(frame)
    }

    companion object {
        /** Ephemeral range (20000-29999): relays should not store it, but 3 of 5 tested did. */
        const val LIVE_KIND = 22007
        /** Regular, stored kind used with an `expiration` tag. Unverified on relays until the live test. */
        const val MAILBOX_KIND = 7007
        const val MAX_BATCHES_PER_TICK = 4
        const val MAILBOX_LOOKBACK_SEC = 24L * 60 * 60
        const val LIVE_LOOKBACK_SEC = 30L
        const val MAILBOX_LIMIT = 200
        const val MAX_INTEREST_TAGS = 100
        const val MAILBOX_EXPIRY_SEC = 7L * 24 * 60 * 60
        private const val MS_PER_SEC = 1000L
    }
}
