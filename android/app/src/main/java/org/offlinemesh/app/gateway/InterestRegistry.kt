package org.offlinemesh.app.gateway

import org.offlinemesh.app.ble.MeshFrameCodec

/**
 * Relay tags that nearby phones told us they want (`FRAME_UPLINK_INTEREST`), so a gateway can subscribe to them without
  * holding any group key (PLAN-v2.md §13.16). RAM only, expires after [ttlMs]. Re-gossip is bounded: only tags
  * heard within
  * the last minute and fewer than [MeshFrameCodec.MAX_INTEREST_HOP] hops away are passed on, and an entry is
  * refreshed only
 * by an equal-or-closer source, so a ring of phones cannot keep a tag alive after its owner has left.
 */
class InterestRegistry(
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = TTL_MS,
    private val maxTags: Int = MAX_TAGS,
) {
    private class Entry(var hop: Int, var heardAt: Long)

    private val entries = LinkedHashMap<String, Entry>()
    private val raw = HashMap<String, ByteArray>()

    @Synchronized
    fun onHeard(frame: MeshFrameCodec.Frame.UplinkInterest) {
        val t = now()
        for (tag in frame.tags) {
            val key = tag.toHex()
            val e = entries[key]
            if (e == null) {
                if (entries.size >= maxTags) continue
                entries[key] = Entry(frame.hop, t); raw[key] = tag
            } else if (frame.hop <= e.hop) {
                e.hop = frame.hop; e.heardAt = t
            }
        }
    }

    /** Tags to subscribe to now. */
    @Synchronized
    fun tags(): List<ByteArray> {
        expire()
        return entries.keys.mapNotNull { raw[it] }
    }

    /** Interest frames to pass on to a neighbour, with hop + 1. */
    @Synchronized
    fun regossip(maxFrames: Int): List<ByteArray> {
        expire()
        val t = now()
        val fresh = entries.entries.filter { t - it.value.heardAt <= REGOSSIP_WINDOW_MS && it.value.hop <
            MeshFrameCodec.MAX_INTEREST_HOP }
        return fresh.chunked(MeshFrameCodec.MAX_INTEREST_TAGS_PER_FRAME).take(maxFrames).map { chunk ->
            MeshFrameCodec.encodeUplinkInterest(chunk.minOf { it.value.hop } + 1, chunk.mapNotNull { raw[it.key] })
        }
    }

    private fun expire() {
        val t = now()
        val gone = entries.filterValues { t - it.heardAt > ttlMs }.keys
        for (k in gone) { entries.remove(k); raw.remove(k) }
    }

    companion object {
        const val TTL_MS = 10 * 60 * 1000L
        const val MAX_TAGS = 512
        private const val REGOSSIP_WINDOW_MS = 60_000L
    }
}
