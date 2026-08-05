package org.offlinemesh.app.ble

/**
 * Resolves a transient BLE address to the stable identity behind it, once known — the middle path
 * PLAN-v2.md §5.2 calls for: nothing new goes on the wire (the beacon stays a rotating, unlinkable
 * ID), but once a connection has authenticated a sender's `senderId` (a random-per-install id, see
 * [org.offlinemesh.app.data.GroupRepository.deviceId] — global across a device's groups, and
 * already sent in cleartext on presence heartbeats, so keying LOCAL state on it exposes nothing new
 * on the wire), every peer-keyed decision for that device can key on the stable identity instead of
 * the address that happened to be current when the connection was made.
 *
 * This is what `NEXT_STEPS.md` D1 asked for: "46 distinct addresses in 23 minutes for 2-3 phones —
 * `ConnectionAttemptTracker` cooldowns and `HopTracker` route-ownership assume ~15min address
 * stability, so every reconnect can look like a brand-new peer." [PeerIdentityResolver] doesn't
 * change *when* an address rotates — it changes what a caller does once it learns two different
 * addresses were the same device: [resolve] returns the SAME key for both, from the moment
 * [learn] first runs.
 *
 * **Cold-start caveat, stated honestly:** a brand-new address is unresolvable until a connection to
 * it actually completes far enough to authenticate a `senderId` — [resolve] falls back to the
 * address itself until then (the §5.4 "low-information case is the identity function" pattern
 * already used elsewhere in this codebase: HopTracker, TrickleTimer). So the FIRST connection to
 * any rotated address always costs exactly what v1 always cost; the benefit is every reconnect
 * *after* that within the same session, which is where the measured 46-addresses-in-23-minutes
 * churn actually lived.
 *
 * LRU-bounded for the same reason as [ConnectionAttemptTracker.cooldownUntil] — an unbounded map
 * here would leak one entry per address ever seen, forever.
 */
class PeerIdentityResolver(private val maxTrackedAddresses: Int = 500) {

    private val addressToKey = object : LinkedHashMap<String, String>(
        DEFAULT_MAP_CAPACITY, DEFAULT_MAP_LOAD_FACTOR, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > maxTrackedAddresses
    }

    /** Records that [address] belongs to [stableKey] — call once an authenticated frame received
     *  on a connection to [address] reveals who's actually on the other end (see
     *  `RelayResponder`'s per-frame handlers, right alongside their existing `HopTracker` calls).
     *  Returns true if this changed what [address] resolves to (a brand-new address, or the same
     *  address now claiming a different key) — false for a routine re-confirmation on an
     *  already-known connection. Callers use this to log only the events actually worth a line in
     *  `DiagnosticsLog` (see the P0b hardware-gate note on each call site) instead of once per
     *  presence heartbeat. */
    @Synchronized
    fun learn(address: String, stableKey: String): Boolean {
        val changed = addressToKey[address] != stableKey
        addressToKey[address] = stableKey
        return changed
    }

    /** The stable identity behind [address], if learned yet — else [address] itself. */
    @Synchronized
    fun resolve(address: String): String = addressToKey[address] ?: address

    @Synchronized
    fun trackedAddressCount(): Int = addressToKey.size

    /** How many DISTINCT stable identities the tracked addresses currently resolve to — the
     *  number the P0b hardware gate actually cares about (should stay near the physical peer
     *  count even as [trackedAddressCount] grows with address-rotation churn). */
    @Synchronized
    fun distinctIdentityCount(): Int = addressToKey.values.toSet().size

    private companion object {
        const val DEFAULT_MAP_CAPACITY = 16
        const val DEFAULT_MAP_LOAD_FACTOR = 0.75f
    }
}
