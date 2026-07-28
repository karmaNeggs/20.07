package org.offlinemesh.app.ble

/**
 * The GATT client connection-attempt state machine, extracted out of [MeshGattClient] so it's
 * testable without any real Bluetooth API — this is exactly the class of logic behind the Pass 16
 * bug ("a peer that never gets a `connectGatt` callback stays marked 'connecting' forever, and
 * `maybeConnect` silently refuses to ever retry it"). A fake/injectable clock lets a test assert the
 * timeout behavior deterministically, without waiting real seconds for a real Android callback that
 * — in the bug this exists to prevent — was never going to arrive anyway.
 *
 * Pure bookkeeping only: this class never touches `BluetoothGatt`. The caller (`MeshGattClient`)
 * still owns the actual `connectGatt()` call, the real timeout `delay()`, and closing the `gatt`
 * object — this just tracks *whether* an attempt should proceed, and *whether* one has gone stuck.
 *
 * Every method is [Synchronized]: `MeshGattClient` reaches into this from its own `@Synchronized
 * maybeConnect` (`canAttempt`/`attemptStarted`) AND from the raw BLE callback thread
 * (`callbackReceived`/`connectionEnded` fire from `onConnectionStateChange`, which shares no
 * monitor with `maybeConnect`) AND from a plain coroutine (the connect-timeout cleanup). Two
 * unsynchronized mutable collections touched from three different threads is exactly the kind of
 * bug a 2-phone test never triggers but a dense crowd — far more connect attempts in flight at
 * once — reliably will (`ConcurrentModificationException` or a silently lost update).
 *
 * [cooldownUntil] is bounded via LRU eviction at [maxTrackedAddresses] entries — because a BLE
 * address isn't a stable identity: a phone's Bluetooth stack rotates its resolvable private address
 * roughly every ~15 minutes, so a long crowd session otherwise accumulates one cooldown entry per
 * address ever seen, forever, most of which will never be seen again. [connecting]/[callbackReceived]
 * don't need the same treatment — every entry [attemptStarted] adds is guaranteed to be removed by
 * a later [connectionEnded] (a real disconnect callback, or the caller's own stuck-attempt timeout),
 * so their size is already bounded by [maxConcurrent] at any moment.
 *
 * [syncedCooldownMs] is the peer-selection lever: this app is a blind carrier that connects to
 * every mesh device it hears, with no topology awareness — in a dense crowd that's redundant
 * connection churn re-syncing the same nearby peers while others sit unvisited. Rather than build
 * real peer sampling, a peer we just *fully* synced with (see [connectionEnded]'s `synced` param)
 * is put on a longer cooldown than one whose connection failed or ended before any real exchange —
 * that alone biases the limited concurrent-connection slots toward peers not yet caught up, using
 * the same cooldown mechanism already proven by the Pass 16 bug fix rather than a new data
 * structure. Defaults to [reconnectCooldownMs] (i.e. no behavior change) when not specified.
 *
 * [currentEpoch] closes a gap found live-testing a 3-phone "passerby relay" scenario: two phones
 * out of range of each other, a third carrying content between them. The synced cooldown above is
 * peer-agnostic — it has no memory of *what* we synced, only *that* we did — so a phone that syncs
 * with peer B (nothing new to offer yet), then meets peer A and picks up something new, stays
 * locked out of retrying B for the full cooldown even though it's now carrying content B needs. Each
 * [connectionEnded] with `synced=true` records [RelayEngine.catalogEpoch] (via [currentEpoch]) at
 * that moment in [syncedEpoch]; [canAttempt] then skips the cooldown, but only for that one address,
 * if the epoch has moved since — i.e. only when there's actually something new to offer that specific
 * peer, not as a general excuse to reconnect more often. Defaults to a constant so existing callers
 * (and every pre-existing test) see no behavior change unless they opt in.
 */
class ConnectionAttemptTracker(
    private val maxConcurrent: Int,
    private val reconnectCooldownMs: Long,
    private val syncedCooldownMs: Long = reconnectCooldownMs,
    private val maxTrackedAddresses: Int = 500,
    private val now: () -> Long = System::currentTimeMillis,
    private val currentEpoch: () -> Int = { 0 },
) {
    private val connecting = mutableSetOf<String>()
    private val callbackReceived = mutableSetOf<String>()

    // Java's LinkedHashMap 3-arg constructor requires initialCapacity/loadFactor even though this
    // class only actually cares about the third arg (accessOrder=true, for LRU eviction below) —
    // these are the JDK's own documented defaults, not a value chosen for this app's logic.
    private val cooldownUntil = object : LinkedHashMap<String, Long>(
        DEFAULT_MAP_CAPACITY, DEFAULT_MAP_LOAD_FACTOR, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) = size > maxTrackedAddresses
    }

    // Same LRU bounding and reasoning as cooldownUntil — one entry per address we've ever fully
    // synced with, evicted the same way once over the cap.
    private val syncedEpoch = object : LinkedHashMap<String, Int>(
        DEFAULT_MAP_CAPACITY, DEFAULT_MAP_LOAD_FACTOR, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>) = size > maxTrackedAddresses
    }

    /** True if a fresh attempt to [address] should proceed right now — not already attempting/
     *  connected, under the concurrency limit, and either not in post-disconnect cooldown or
     *  carrying something new for this specific peer since the last time we fully synced with it
     *  (see [currentEpoch]'s doc above). */
    @Suppress("ReturnCount") // three independent guard clauses read more clearly early-return than
    // folded into one boolean expression — same style choice as other early-return guards in this file.
    @Synchronized
    fun canAttempt(address: String): Boolean {
        if (address in connecting) return false
        val cooldown = cooldownUntil[address]
        if (cooldown != null && now() < cooldown) {
            val lastSyncedEpoch = syncedEpoch[address]
            val hasSomethingNewForThisPeer = lastSyncedEpoch != null && lastSyncedEpoch != currentEpoch()
            if (!hasSomethingNewForThisPeer) return false
        }
        return connecting.size < maxConcurrent
    }

    /** Call once the caller has actually initiated `connectGatt()`. */
    @Synchronized
    fun attemptStarted(address: String) {
        connecting.add(address)
        callbackReceived.remove(address)
    }

    /** Call from `onConnectionStateChange` for *any* state, not just success — this is what lets
     *  [isStuck] tell "slow but the OS is talking to us" apart from "never heard back at all." */
    @Synchronized
    fun callbackReceived(address: String) {
        callbackReceived.add(address)
    }

    /** True if [address] is still marked "connecting" and has never received a single callback —
     *  i.e. genuinely stuck, not just slow. The caller is expected to have already waited its own
     *  timeout window before asking (this class has no concept of elapsed time by itself). */
    @Synchronized
    fun isStuck(address: String): Boolean = address in connecting && address !in callbackReceived

    /** Call on a real disconnect, or to force-clean a [isStuck] attempt. Starts the reconnect
     *  cooldown either way — a peer that just failed shouldn't be retried immediately.
     *  [synced] should be true only when the connection got far enough to actually push our
     *  content (see [syncedCooldownMs]) — a failed/aborted attempt always gets the short cooldown
     *  so a peer we haven't reached yet is retried soon, not penalized for our own failure. */
    @Synchronized
    fun connectionEnded(address: String, synced: Boolean = false) {
        connecting.remove(address)
        callbackReceived.remove(address)
        cooldownUntil[address] = now() + if (synced) syncedCooldownMs else reconnectCooldownMs
        if (synced) syncedEpoch[address] = currentEpoch()
    }

    @Synchronized
    fun trackedAddressCount(): Int = cooldownUntil.size

    private companion object {
        const val DEFAULT_MAP_CAPACITY = 16
        const val DEFAULT_MAP_LOAD_FACTOR = 0.75f
    }
}
