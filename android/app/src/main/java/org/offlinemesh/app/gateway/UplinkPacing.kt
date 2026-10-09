package org.offlinemesh.app.gateway

import kotlin.random.Random

/**
 * Which relays get an event: the same two for everyone who shares a tag (so receivers know where to listen), spread
 * across relays by hash so no relay carries everything. PLAN-v2.md Part 14.3, found necessary by the scale simulation.
 */
object RelayChoice {
    fun pick(key: String, urls: List<String>, n: Int): List<String> =
        urls.sortedBy { score(key, it) }.take(n)

    // A real hash, not String.hashCode: URLs that differ in one character must still spread evenly across tags.
    private fun score(key: String, url: String): Long {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest((key + "|" + url).toByteArray())
        return java.nio.ByteBuffer.wrap(d).long
    }
}

/** Exponential backoff with jitter after a publish that no relay accepted, so a congested relay is not hit by a retry
 *  storm (the simulation showed every phone retrying each second collapses a saturated relay). */
class PublishBackoff(
    private val random: Random = Random.Default,
    private val baseMs: Long = BASE_MS,
    private val maxMs: Long = MAX_MS,
) {
    private var fails = 0
    private var until = 0L

    fun failed(now: Long) {
        fails++
        val shift = (fails - 1).coerceAtMost(MAX_SHIFT)
        // Full jitter: a random wait up to the exponential cap, so phones that failed together do not retry together.
        until = now + baseMs / 2 + random.nextLong(0, minOf(maxMs, baseMs shl shift) + 1)
    }

    fun succeeded() { fails = 0; until = 0 }

    // A clock stepped backwards must not turn a seconds-long wait into one as long as the step.
    fun blocked(now: Long): Boolean = now < until && until - now <= maxMs * 2

    private companion object {
        const val BASE_MS = 1000L
        const val MAX_MS = 30_000L
        const val MAX_SHIFT = 5
    }
}
