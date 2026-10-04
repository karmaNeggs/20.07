package org.offlinemesh.app.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Serializes every outbound GATT operation (characteristic write, descriptor write, or notify)
 * against a single remote device address. Android allows exactly one outstanding GATT operation
 * per connection — of ANY kind, not just "one write" — so issuing a second before the first's
 * completion callback fires silently corrupts or drops it. This was the root cause behind two
 * separate live bugs: SOS/evidence writes racing an unsynchronized position write, and (later)
 * the very first post-connect write racing a CCCD subscription write that had no completion
 * handler at all. One queue per role (client, server), keyed by peer address, closes both.
 */
class GattOperationQueue {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private fun lockFor(address: String) = locks.getOrPut(address) { Mutex() }

    /** [queue] performs the actual platform call (e.g. gatt.writeCharacteristic) and returns
     *  whether it was accepted for queuing; the real result comes from [complete] being called
     *  by the matching completion callback once the radio operation actually finishes. */
    suspend fun run(address: String, timeoutMs: Long = 2000, queue: () -> Boolean): Boolean =
        lockFor(address).withLock {
            val deferred = CompletableDeferred<Boolean>()
            pending[address] = deferred
            val accepted = try { queue() } catch (e: Exception) { false }
            if (!accepted) {
                pending.remove(address)
                return@withLock false
            }
            val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (result == null) pending.remove(address)
            result ?: false
        }

    fun complete(address: String, success: Boolean) {
        pending.remove(address)?.complete(success)
    }

    /** Call on disconnect — releases anything still waiting rather than leaving it to time out. */
    fun clear(address: String) {
        locks.remove(address)
        pending.remove(address)?.complete(false)
    }
}
