package org.offlinemesh.app.gateway

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayKeyHolderTest {
    private var nowMs = 1_000_000_000_000L
    private val week = GatewayKeyHolder.LIFETIME_MS

    @Test fun `key is stable within its lifetime and replaced after it`() {
        val h = GatewayKeyHolder({ nowMs })
        val first = h.current().copyOf()
        nowMs += week - 1
        assertArrayEquals(first, h.current())
        nowMs += 2
        assertFalse(first.contentEquals(h.current()))
    }

    @Test fun `generated keys are valid signing keys`() {
        val h = GatewayKeyHolder({ nowMs })
        assertTrue(runCatching { Bip340.publicKey(h.current()) }.isSuccess)
    }

    @Test fun `a persisted key is restored while valid`() {
        val h = GatewayKeyHolder({ nowMs })
        val saved = ByteArray(32) { 7 }
        h.restore(saved, nowMs - 1000)
        assertArrayEquals(saved, h.current())
    }

    @Test fun `an expired, malformed or invalid persisted key is ignored`() {
        val h = GatewayKeyHolder({ nowMs })
        val original = h.current().copyOf()
        h.restore(ByteArray(32) { 7 }, nowMs - week - 1)
        h.restore(ByteArray(31) { 7 }, nowMs)
        h.restore(ByteArray(32), nowMs)
        assertArrayEquals(original, h.current())
    }
}
