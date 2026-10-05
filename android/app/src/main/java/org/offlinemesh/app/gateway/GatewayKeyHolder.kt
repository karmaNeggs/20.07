package org.offlinemesh.app.gateway

import java.security.SecureRandom

/**
 * The throwaway Nostr signing key a gateway publishes under (`PLAN-v2.md` §13.5, decision 68).
 * Random, not derived from any group key (a gateway holds none), and **replaced after [lifetimeMs]**,
 * 7 days by default to match 3-7 day group lifetimes. Holds no persistence itself: G2 stores
 * ([secret], [createdAtMs]) and hands them back through [restore] after a restart.
 */
class GatewayKeyHolder(
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val lifetimeMs: Long = LIFETIME_MS,
) {
    var secret: ByteArray = newSecret()
        private set
    var createdAtMs: Long = now()
        private set

    /** The key to sign with now, replacing it first if it has expired. */
    fun current(): ByteArray {
        if (now() - createdAtMs >= lifetimeMs) {
            secret = newSecret()
            createdAtMs = now()
        }
        return secret
    }

    /** Re-adopts a persisted key. An invalid or already-expired one is ignored and a fresh key kept. */
    fun restore(persistedSecret: ByteArray, persistedCreatedAtMs: Long) {
        val usable = persistedSecret.size == KEY_BYTES &&
            now() - persistedCreatedAtMs < lifetimeMs &&
            runCatching { Bip340.publicKey(persistedSecret) }.isSuccess
        if (usable) {
            secret = persistedSecret
            createdAtMs = persistedCreatedAtMs
        }
    }

    private fun newSecret(): ByteArray {
        while (true) {
            val candidate = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
            if (runCatching { Bip340.publicKey(candidate) }.isSuccess) return candidate
        }
    }

    companion object {
        const val LIFETIME_MS = 7L * 24 * 60 * 60 * 1000
        private const val KEY_BYTES = 32
    }
}
