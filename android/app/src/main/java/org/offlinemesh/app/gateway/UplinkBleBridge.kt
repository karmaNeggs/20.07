package org.offlinemesh.app.gateway

import org.offlinemesh.app.ble.MeshFrameCodec
import org.offlinemesh.app.ble.RelayResponder

/**
  * Stranger carrying (`PLAN-v2.md` §13.16, decision 78): the Bluetooth side of Internet reach. A phone with the
  * switch on
  * hands the wrapped frames it holds (its own, and ones it was given) to each Bluetooth neighbour once, so any
  * phone that has
  * internet can upload them, group member or not; it also passes on which relay tags are wanted nearby, so a
  * gateway knows
 * what to listen for. It never opens anything: held frames are sealed, tags are opaque.
 *
  * Bounded everywhere: a handful of frames per push, each frame once per neighbour, a short list of remembered
  * neighbours.
  * Frames that came from the internet are not carried over Bluetooth by this path (they enter the normal mesh as
  * ordinary
 * sealed frames through the existing blind relay).
 */
class UplinkBleBridge(
    private val controller: InternetReachController,
    private val registry: InterestRegistry,
    private val enabled: () -> Boolean,
) : RelayResponder.UplinkHook {
    private val sentTo = object : LinkedHashMap<String, LinkedHashSet<String>>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,
            LinkedHashSet<String>>) = size > MAX_PEERS
    }

    override suspend fun onUplink(frame: MeshFrameCodec.Frame.Uplink) {
        if (!enabled() || frame.flags and MeshFrameCodec.UPLINK_FLAG_FROM_INTERNET != 0) return
        controller.offerFromBle(frame)
    }

    override fun onInterest(frame: MeshFrameCodec.Frame.UplinkInterest) {
        if (enabled()) registry.onHeard(frame)
    }

    override suspend fun framesToPush(peer: String, maxFrameBytes: Int): List<ByteArray> {
        if (!enabled() || !controller.running) return emptyList()
        val out = ArrayList<ByteArray>()
        val sent = sentTo.getOrPut(peer) { LinkedHashSet() }
        for ((key, encoded) in controller.heldFrames(HELD_PER_PUSH * SCAN_FACTOR, maxFrameBytes)) {
            if (out.size >= HELD_PER_PUSH) break
            if (sent.add(key)) out.add(encoded)
        }
        while (sent.size > MAX_SENT_PER_PEER) sent.remove(sent.first())
        controller.ownInterestTags().chunked(MeshFrameCodec.MAX_INTEREST_TAGS_PER_FRAME).take(OWN_INTEREST_FRAMES)
            .mapTo(out) { MeshFrameCodec.encodeUplinkInterest(0, it) }
        out.addAll(registry.regossip(REGOSSIP_FRAMES))
        return out.filter { it.size <= maxFrameBytes }
    }

    private companion object {
        const val HELD_PER_PUSH = 6
        const val SCAN_FACTOR = 4
        const val MAX_PEERS = 16
        const val MAX_SENT_PER_PEER = 512
        const val OWN_INTEREST_FRAMES = 2
        const val REGOSSIP_FRAMES = 1
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
    }
}
