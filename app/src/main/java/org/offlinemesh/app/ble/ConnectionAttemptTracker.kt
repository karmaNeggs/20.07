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
 * Not internally thread-safe; callers are expected to guard access themselves, the same way
 * `MeshGattClient.maybeConnect` is already `@Synchronized`.
 *
 * [syncedCooldownMs] is the peer-selection lever: this app is a blind carrier that connects to
 * every mesh device it hears, with no topology awareness — in a dense crowd that's redundant
 * connection churn re-syncing the same nearby peers while others sit unvisited. Rather than build
 * real peer sampling, a peer we just *fully* synced with (see [connectionEnded]'s `synced` param)
 * is put on a longer cooldown than one whose connection failed or ended before any real exchange —
 * that alone biases the limited concurrent-connection slots toward peers not yet caught up, using
 * the same cooldown mechanism already proven by the Pass 16 bug fix rather than a new data
 * structure. Defaults to [reconnectCooldownMs] (i.e. no behavior change) when not specified.
 */
class ConnectionAttemptTracker(
    private val maxConcurrent: Int,
    private val reconnectCooldownMs: Long,
    private val syncedCooldownMs: Long = reconnectCooldownMs,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val connecting = mutableSetOf<String>()
    private val callbackReceived = mutableSetOf<String>()
    private val cooldownUntil = mutableMapOf<String, Long>()

    /** True if a fresh attempt to [address] should proceed right now — not already attempting/
     *  connected, not in post-disconnect cooldown, and under the concurrency limit. */
    fun canAttempt(address: String): Boolean {
        if (address in connecting) return false
        val cooldown = cooldownUntil[address]
        if (cooldown != null && now() < cooldown) return false
        return connecting.size < maxConcurrent
    }

    /** Call once the caller has actually initiated `connectGatt()`. */
    fun attemptStarted(address: String) {
        connecting.add(address)
        callbackReceived.remove(address)
    }

    /** Call from `onConnectionStateChange` for *any* state, not just success — this is what lets
     *  [isStuck] tell "slow but the OS is talking to us" apart from "never heard back at all." */
    fun callbackReceived(address: String) {
        callbackReceived.add(address)
    }

    /** True if [address] is still marked "connecting" and has never received a single callback —
     *  i.e. genuinely stuck, not just slow. The caller is expected to have already waited its own
     *  timeout window before asking (this class has no concept of elapsed time by itself). */
    fun isStuck(address: String): Boolean = address in connecting && address !in callbackReceived

    /** Call on a real disconnect, or to force-clean a [isStuck] attempt. Starts the reconnect
     *  cooldown either way — a peer that just failed shouldn't be retried immediately.
     *  [synced] should be true only when the connection got far enough to actually push our
     *  content (see [syncedCooldownMs]) — a failed/aborted attempt always gets the short cooldown
     *  so a peer we haven't reached yet is retried soon, not penalized for our own failure. */
    fun connectionEnded(address: String, synced: Boolean = false) {
        connecting.remove(address)
        callbackReceived.remove(address)
        cooldownUntil[address] = now() + if (synced) syncedCooldownMs else reconnectCooldownMs
    }
}
