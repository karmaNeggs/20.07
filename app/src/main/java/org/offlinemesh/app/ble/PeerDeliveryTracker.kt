package org.offlinemesh.app.ble

/**
 * Tracks, per peer address, which one-time/static item keys have already been successfully
 * delivered — so a peer that's already received a given SOS/evidence-header/nickname doesn't get
 * sent it again on every future connection (Pass 17). Extracted out of [RelayResponder] into its
 * own class purely so this bookkeeping is independently unit-testable, without needing to construct
 * a full `RelayResponder` (which pulls in `GroupRepository`/`RelayEngine`/Room/Context).
 *
 * Bounded to the [maxPeers] most recently touched addresses (access-order eviction — a peer not
 * seen in a while is dropped first), so a long-running relay session carrying traffic for many
 * strangers' phones has a fixed memory ceiling. Purely in-memory, never persisted, reset on every
 * process restart — consistent with this app's "no permanent record" storage discipline.
 */
class PeerDeliveryTracker(private val maxPeers: Int = 200) {
    private val delivered = object : LinkedHashMap<String, MutableSet<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableSet<String>>) = size > maxPeers
    }

    @Synchronized
    fun alreadyDelivered(peerAddress: String, itemKey: String): Boolean =
        delivered[peerAddress]?.contains(itemKey) == true

    /** Only call once a push is confirmed successful — marking an item delivered before that would
     *  mean a write that actually failed is never retried to that peer, silently losing it rather
     *  than just delaying it. */
    @Synchronized
    fun markDelivered(peerAddress: String, itemKey: String) {
        delivered.getOrPut(peerAddress) { mutableSetOf() }.add(itemKey)
    }

    @Synchronized
    fun trackedPeerCount(): Int = delivered.size
}
