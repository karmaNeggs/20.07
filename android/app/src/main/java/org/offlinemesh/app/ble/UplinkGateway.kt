package org.offlinemesh.app.ble

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Length-prefixed concatenation of already-encoded frames: the `content` of one relay event, before
 * the Nostr layer base64-encodes it (G1). Frames are self-describing, so a 2-byte length per frame is
 * all the framing needed. [decode] never throws: on malformed input it returns what it parsed so far.
 */
object UplinkBatch {
    private const val LEN_BYTES = 2
    private const val MAX_U16 = 0xFFFF
    private const val BYTE_MASK = 0xFF
    private const val BYTE_BITS = 8

    /** Bytes one frame occupies inside a batch. */
    fun sizeOf(frame: ByteArray): Int = LEN_BYTES + frame.size

    fun encode(frames: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (f in frames) {
            require(f.size <= MAX_U16) { "frame too large for a batch: ${f.size}" }
            out.write(f.size ushr BYTE_BITS)
            out.write(f.size and BYTE_MASK)
            out.write(f)
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): List<ByteArray> {
        val buf = ByteBuffer.wrap(bytes)
        val frames = ArrayList<ByteArray>()
        while (buf.remaining() >= LEN_BYTES) {
            val n = buf.short.toInt() and MAX_U16
            if (n > buf.remaining()) break
            frames.add(ByteArray(n).also { buf.get(it) })
        }
        return frames
    }
}

/**
 * The keyless "mule" brain (`PLAN-v2.md` Part 13, `docs/DECISIONS.md` decisions 66-68). Pure logic: no
 * Android, no network, no disk, so the admission, batching and loop-control rules are unit-testable
 * and nothing it holds survives a restart (same RAM-only property as [PositionTracker]).
 *
 * It holds NO group key and never opens an `inner` frame. It decides only whether to carry a
 * member-wrapped [MeshFrameCodec.Frame.Uplink], when to expire it, and how to batch it for a relay
 * that throttles to about one event per second (decision 67).
 *
 * **Uplink:** [offer] each Uplink heard over BLE; [drain] returns the batches to publish; if a
 * publish fails, [requeue] puts a batch back and refunds its budget.
 * **Downlink:** [onRelayBatch] takes one relay event's content and returns the frames to inject into
 * the BLE mesh, marked `fromInternet` so no gateway ever uplinks them again.
 *
 * **Spam posture:** a gateway cannot verify a tag (it has no key), and a held frame's sender may have
 * left, so the local-presence check is only a PRIORITY ([noteBeaconSeen]), not a gate. Everything else
 * is bounded: freshness per class, a per-tag rate, a per-hour upload budget, bounded queues, dedup.
 * Junk that survives costs budget and airtime only; members drop it at AEAD/signature.
 */
// TooManyFunctions: one small, single-purpose private helper per rule (admission, expiry, budget, rate,
// priority, dedup), kept in one class because they share the queues and clock they all read.
@Suppress("TooManyFunctions")
class UplinkGateway(
    private val now: () -> Long = System::currentTimeMillis,
    private val config: Config = Config(),
) {
    data class Config(
        val liveMaxAgeSec: Long = LIVE_MAX_AGE_SEC,
        val positionMaxAgeSec: Long = POSITION_MAX_AGE_SEC,
        val textMaxAgeSec: Long = TEXT_MAX_AGE_SEC,
        val liveKeepPerTag: Int = LIVE_KEEP_PER_TAG,
        val positionKeepPerTag: Int = POSITION_KEEP_PER_TAG,
        val textKeepPerTag: Int = TEXT_KEEP_PER_TAG,
        val fileMaxAgeSec: Long = FILE_MAX_AGE_SEC,
        val fileMetaKeepPerTag: Int = FILE_META_KEEP_PER_TAG,
        val fileSymbolKeepPerTag: Int = FILE_SYMBOL_KEEP_PER_TAG,
        val bulkHourlyBudgetBytes: Int = BULK_HOURLY_BUDGET_BYTES,
        val framesPerTagPerMinute: Int = FRAMES_PER_TAG_PER_MINUTE,
        val hourlyBudgetBytes: Int = HOURLY_BUDGET_BYTES,
        val maxBatchBytes: Int = MAX_BATCH_BYTES,
        val priorityTagSeenSec: Long = PRIORITY_TAG_SEEN_SEC,
        val dedupEntries: Int = DEDUP_ENTRIES,
        val maxQueues: Int = MAX_QUEUES,
        val maxDownlinkFramesPerMinute: Int = MAX_DOWNLINK_PER_MINUTE,
        val maxDownlinkFramesPerEvent: Int = MAX_DOWNLINK_FRAMES_PER_EVENT,
    )

    enum class Reject { FROM_INTERNET, BAD_CLASS, BAD_TAG, OVERSIZE, STALE, FUTURE, DUPLICATE, TAG_RATE }

    sealed class Decision {
        /** [priority] = this tag was seen in a nearby beacon recently, so it drains first. */
        data class Accepted(val priority: Boolean) : Decision()
        data class Rejected(val reason: Reject) : Decision()
    }

    /** One relay event's worth of frames for a single tag and class. */
    class Batch(val relayTag: ByteArray, val cls: Int, val frames: List<ByteArray>, val drainedAtMs: Long = 0L) {
        val bytes: Int get() = frames.sumOf { UplinkBatch.sizeOf(it) }
        fun content(): ByteArray = UplinkBatch.encode(frames)
    }

    private class Held(val tag: ByteArray, val cls: Int, val createdAtSec: Long, val encoded: ByteArray)
    private class RateWindow(var startMs: Long, var count: Int)

    private val queues = LinkedHashMap<String, ArrayDeque<Held>>()
    private val seenTags = LinkedHashMap<String, Long>()
    private val rate = HashMap<String, RateWindow>()
    private val dedup = object : LinkedHashMap<String, Boolean>(DEDUP_INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) = size > config.dedupEntries
    }
    private var budgetWindowStartMs = now()
    private var budgetUsedBytes = 0
    private var bulkUsedBytes = 0
    private var downWindowStartMs = 0L
    private var downCount = 0

    /** Records that a member's beacon with this 60 s tag was scanned nearby (a priority hint only). */
    @Synchronized
    fun noteBeaconSeen(tag: ByteArray) {
        val h = hex(tag)
        seenTags.remove(h)
        seenTags[h] = now()
        if (seenTags.size > SEEN_TAGS_MAX) seenTags.remove(seenTags.keys.first())
    }

    @Synchronized
    fun offer(frame: MeshFrameCodec.Frame.Uplink): Decision {
        val tagHex = hex(frame.relayTag)
        val key = dedupKey(frame)
        val reason = admissionFailure(frame, tagHex, key)
        if (reason != null) return Decision.Rejected(reason)
        dedup[key] = true
        enqueue(
            tagHex,
            Held(
                frame.relayTag, frame.cls, frame.createdAtSec,
                MeshFrameCodec.encodeUplink(frame.relayTag, frame.cls, frame.flags, frame.createdAtSec, frame.inner),
            ),
        )
        return Decision.Accepted(isPriority(tagHex))
    }

    /** Batches ready to publish now, at most [maxBatches], within this hour's remaining budget. */
    @Synchronized
    fun drain(maxBatches: Int = Int.MAX_VALUE, includeBulk: Boolean = true): List<Batch> {
        expire()
        resetBudgetIfNeeded()
        val batches = ArrayList<Batch>()
        val order = queues.entries
            .filter { it.value.isNotEmpty() && (includeBulk || !isBulk(it.value.first().cls)) }
            .sortedWith(
                compareBy<Map.Entry<String, ArrayDeque<Held>>>(
                    { classRank(it.value.first().cls) },
                    { if (isPriority(it.key.substringBefore('/'))) 0 else 1 },
                    { it.value.minOf { h -> h.createdAtSec } },
                ),
            )
        for ((key, queue) in order.take(maxBatches)) {
            takeBatch(queue)?.let { batches.add(it) }
            if (queue.isEmpty()) queues.remove(key)
        }
        return batches
    }

    /** Puts a batch whose publish failed back, and refunds its budget. Never re-checks dedup or rate. */
    @Synchronized
    fun requeue(batch: Batch) {
        // Refund only if the batch was charged in the CURRENT budget window; an older one already reset with it.
        if (batch.drainedAtMs >= budgetWindowStartMs) {
            if (isBulk(batch.cls)) bulkUsedBytes = (bulkUsedBytes - batch.bytes).coerceAtLeast(0)
            else budgetUsedBytes = (budgetUsedBytes - batch.bytes).coerceAtLeast(0)
        }
        for (encoded in batch.frames) {
            val f = MeshFrameCodec.decode(encoded) as? MeshFrameCodec.Frame.Uplink ?: continue
            enqueue(hex(f.relayTag), Held(f.relayTag, f.cls, f.createdAtSec, encoded))
        }
    }

    /**
     * One relay event's content in, frames to inject into BLE out. Drops anything stale, malformed,
     * already seen (including this gateway's own published frames echoing back, and the same event
     * arriving from several relays), or oversize. Returned frames carry `UPLINK_FLAG_FROM_INTERNET`.
     */
    @Synchronized
    fun onRelayBatch(content: ByteArray): List<ByteArray> {
        val t = now()
        if (t - downWindowStartMs >= MINUTE_MS) { downWindowStartMs = t; downCount = 0 }
        val room = (config.maxDownlinkFramesPerMinute - downCount).coerceAtLeast(0)
        val frames = UplinkBatch.decode(content).take(minOf(config.maxDownlinkFramesPerEvent, room))
        downCount += frames.size
        return frames.mapNotNull { acceptDownlink(it) }
    }

    private fun acceptDownlink(raw: ByteArray): ByteArray? {
        val f = MeshFrameCodec.decode(raw) as? MeshFrameCodec.Frame.Uplink
        val key = f?.let { dedupKey(it) }
        val ok = f != null && key != null && f.cls in CLASS_RANGE && f.relayTag.isNotEmpty() &&
            f.inner.size <= MeshFrameCodec.MAX_UPLINK_INNER_BYTES &&
            !isStale(f) && !isFuture(f) && !dedup.containsKey(key)
        if (f == null || key == null || !ok) return null
        dedup[key] = true
        return MeshFrameCodec.encodeUplink(
            f.relayTag, f.cls, f.flags or MeshFrameCodec.UPLINK_FLAG_FROM_INTERNET, f.createdAtSec, f.inner,
        )
    }

    /**
     * A non-draining look at what is held, in drain priority order, as (key, encoded frame) pairs no larger than
     * [maxFrameBytes]: what a carrier hands to a Bluetooth neighbour so any phone with internet can upload it.
     */
    @Synchronized
    fun heldFrames(maxFrames: Int, maxFrameBytes: Int): List<Pair<String, ByteArray>> {
        expire()
        val md = MessageDigest.getInstance("SHA-256")
        return queues.values.flatMap { it.toList() }
            .filter { it.encoded.size <= maxFrameBytes }
            .sortedWith(compareBy({ classRank(it.cls) }, { -it.createdAtSec }))
            .take(maxFrames)
            .map { hex(md.digest(it.encoded).copyOf(DEDUP_KEY_BYTES)) to it.encoded }
    }

    /** True if any frame of class [cls] is waiting (used to let SOS alerts bypass the general publish backoff). */
    @Synchronized
    fun hasPending(cls: Int): Boolean = queues.values.any { q -> q.any { it.cls == cls } }

    /** Frames currently held for upload (for tests and the UI notice). */
    @Synchronized
    fun pendingCount(): Int = queues.values.sumOf { it.size }

    private fun admissionFailure(f: MeshFrameCodec.Frame.Uplink, tagHex: String, key: String): Reject? = when {
        f.flags and MeshFrameCodec.UPLINK_FLAG_FROM_INTERNET != 0 -> Reject.FROM_INTERNET
        f.cls !in CLASS_RANGE -> Reject.BAD_CLASS
        f.relayTag.isEmpty() || f.relayTag.size > MeshFrameCodec.MAX_UPLINK_TAG_BYTES -> Reject.BAD_TAG
        f.inner.size > MeshFrameCodec.MAX_UPLINK_INNER_BYTES -> Reject.OVERSIZE
        isStale(f) -> Reject.STALE
        isFuture(f) -> Reject.FUTURE
        dedup.containsKey(key) -> Reject.DUPLICATE
        !isBulk(f.cls) && tagRateExceeded(tagHex) -> Reject.TAG_RATE
        else -> null
    }

    private fun takeBatch(queue: ArrayDeque<Held>): Batch? {
        val bulk = queue.isNotEmpty() && isBulk(queue.first().cls)
        val remaining = if (bulk) {
            config.bulkHourlyBudgetBytes - bulkUsedBytes
        } else {
            config.hourlyBudgetBytes - budgetUsedBytes
        }
        val room = minOf(config.maxBatchBytes, remaining)
        val taken = ArrayList<Held>()
        var bytes = 0
        for (h in queue.sortedBy { it.createdAtSec }) {
            val size = UplinkBatch.sizeOf(h.encoded)
            if (bytes + size > room) break
            taken.add(h)
            bytes += size
        }
        if (taken.isEmpty()) return null
        queue.removeAll(taken.toSet())
        if (bulk) bulkUsedBytes += bytes else budgetUsedBytes += bytes
        return Batch(taken.first().tag, taken.first().cls, taken.map { it.encoded }, now())
    }

    private fun enqueue(tagHex: String, held: Held) {
        val queue = queues.getOrPut("$tagHex/${held.cls}") { ArrayDeque() }
        queue.addLast(held)
        val keep = when (held.cls) {
            MeshFrameCodec.UPLINK_CLASS_LIVE -> config.liveKeepPerTag
            MeshFrameCodec.UPLINK_CLASS_POSITION_LAST_KNOWN -> config.positionKeepPerTag
            MeshFrameCodec.UPLINK_CLASS_FILE_META -> config.fileMetaKeepPerTag
            MeshFrameCodec.UPLINK_CLASS_FILE_SYMBOLS -> config.fileSymbolKeepPerTag
            else -> config.textKeepPerTag
        }
        while (queue.size > keep) {
            val oldest = queue.minByOrNull { it.createdAtSec } ?: break
            queue.remove(oldest)
        }
        if (queues.size > config.maxQueues) evictWorstQueue()
    }

    /** Bounds memory against hostile or accidental floods of distinct tags: drop the lowest-priority, oldest queue. */
    private fun evictWorstQueue() {
        val worst = queues.entries.maxWithOrNull(
            compareBy<Map.Entry<String, ArrayDeque<Held>>>({ classRank(it.value.firstOrNull()?.cls ?: 0) })
                .thenByDescending { it.value.maxOfOrNull { h -> h.createdAtSec } ?: Long.MAX_VALUE },
        ) ?: return
        queues.remove(worst.key)
    }

    private fun expire() {
        for (queue in queues.values) queue.removeAll { isStale(it.cls, it.createdAtSec) }
        queues.entries.removeAll { it.value.isEmpty() }
    }

    private fun resetBudgetIfNeeded() {
        if (now() - budgetWindowStartMs >= HOUR_MS) {
            budgetWindowStartMs = now()
            budgetUsedBytes = 0
            bulkUsedBytes = 0
        }
    }

    private fun tagRateExceeded(tagHex: String): Boolean {
        val t = now()
        if (rate.size > RATE_ENTRIES_MAX) rate.entries.removeAll { t - it.value.startMs > MINUTE_MS }
        if (rate.size > RATE_ENTRIES_MAX * RATE_HARD_CAP_FACTOR) rate.clear()
        val w = rate.getOrPut(tagHex) { RateWindow(t, 0) }
        if (t - w.startMs >= MINUTE_MS) { w.startMs = t; w.count = 0 }
        w.count++
        return w.count > config.framesPerTagPerMinute
    }

    private fun isPriority(tagHex: String): Boolean {
        val seen = seenTags[tagHex] ?: return false
        return now() - seen <= config.priorityTagSeenSec * MS_PER_SEC
    }

    private fun maxAgeSec(cls: Int): Long = when (cls) {
        MeshFrameCodec.UPLINK_CLASS_LIVE -> config.liveMaxAgeSec
        MeshFrameCodec.UPLINK_CLASS_POSITION_LAST_KNOWN -> config.positionMaxAgeSec
        MeshFrameCodec.UPLINK_CLASS_FILE_META, MeshFrameCodec.UPLINK_CLASS_FILE_SYMBOLS -> config.fileMaxAgeSec
        else -> config.textMaxAgeSec
    }

    private fun isStale(f: MeshFrameCodec.Frame.Uplink) = isStale(f.cls, f.createdAtSec)
    private fun isStale(cls: Int, createdAtSec: Long) = now() / MS_PER_SEC - createdAtSec > maxAgeSec(cls)
    private fun isFuture(f: MeshFrameCodec.Frame.Uplink) = f.createdAtSec - now() / MS_PER_SEC > FUTURE_SKEW_SEC

    /** Drain priority, lowest number first: SOS alert, text, live position, last-known, file header, file bulk. */
    private fun classRank(cls: Int) = when (cls) {
        MeshFrameCodec.UPLINK_CLASS_ALERT -> RANK_ALERT
        MeshFrameCodec.UPLINK_CLASS_TEXT -> RANK_TEXT
        MeshFrameCodec.UPLINK_CLASS_LIVE -> RANK_LIVE
        MeshFrameCodec.UPLINK_CLASS_POSITION_LAST_KNOWN -> RANK_LAST_KNOWN
        MeshFrameCodec.UPLINK_CLASS_FILE_META -> RANK_FILE_META
        else -> RANK_FILE_BULK
    }

    private fun isBulk(cls: Int) =
        cls == MeshFrameCodec.UPLINK_CLASS_FILE_META || cls == MeshFrameCodec.UPLINK_CLASS_FILE_SYMBOLS

    private fun dedupKey(f: MeshFrameCodec.Frame.Uplink): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(f.relayTag); md.update(f.cls.toByte())
        md.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(f.createdAtSec).array())
        md.update(f.inner)
        return hex(md.digest().copyOf(DEDUP_KEY_BYTES))
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    companion object {
        const val LIVE_MAX_AGE_SEC = 120L
        const val POSITION_MAX_AGE_SEC = 6L * 3600
        const val TEXT_MAX_AGE_SEC = 7L * 24 * 3600
        const val LIVE_KEEP_PER_TAG = 8
        const val POSITION_KEEP_PER_TAG = 16
        const val TEXT_KEEP_PER_TAG = 64
        const val FRAMES_PER_TAG_PER_MINUTE = 30
        const val HOURLY_BUDGET_BYTES = 256 * 1024
        const val MAX_BATCH_BYTES = 32 * 1024
        const val PRIORITY_TAG_SEEN_SEC = 180L
        const val DEDUP_ENTRIES = 8192
        const val MAX_QUEUES = 512
        const val MAX_DOWNLINK_PER_MINUTE = 3000
        const val MAX_DOWNLINK_FRAMES_PER_EVENT = 256
        const val FILE_MAX_AGE_SEC = 24L * 3600
        const val FILE_META_KEEP_PER_TAG = 64
        const val FILE_SYMBOL_KEEP_PER_TAG = 160

        /** Files get their own pool so they can never starve messages and positions (1 MB per hour). */
        const val BULK_HOURLY_BUDGET_BYTES = 1024 * 1024

        private val CLASS_RANGE = MeshFrameCodec.UPLINK_CLASS_LIVE..MeshFrameCodec.UPLINK_CLASS_FILE_SYMBOLS
        private const val FUTURE_SKEW_SEC = 120L
        private const val RANK_ALERT = 0
        private const val RANK_TEXT = 1
        private const val RANK_LIVE = 2
        private const val RANK_LAST_KNOWN = 3
        private const val RANK_FILE_META = 4
        private const val RANK_FILE_BULK = 5
        private const val SEEN_TAGS_MAX = 4096
        private const val RATE_ENTRIES_MAX = 1024
        private const val RATE_HARD_CAP_FACTOR = 4
        private const val DEDUP_INITIAL_CAPACITY = 256
        private const val LOAD_FACTOR = 0.75f
        private const val DEDUP_KEY_BYTES = 16
        private const val MS_PER_SEC = 1000L
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 3_600_000L
    }
}
