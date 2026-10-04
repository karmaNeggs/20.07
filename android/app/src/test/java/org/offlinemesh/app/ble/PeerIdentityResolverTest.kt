package org.offlinemesh.app.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class PeerIdentityResolverTest {

    @Test
    fun `unknown address resolves to itself`() {
        val resolver = PeerIdentityResolver()
        assertEquals("addr-1", resolver.resolve("addr-1"))
    }

    @Test
    fun `learned address resolves to its stable key`() {
        val resolver = PeerIdentityResolver()
        resolver.learn("addr-1", "device-x")
        assertEquals("device-x", resolver.resolve("addr-1"))
    }

    @Test
    fun `a rotated address for the same device resolves to the same key once learned`() {
        val resolver = PeerIdentityResolver()
        resolver.learn("addr-1", "device-x")
        resolver.learn("addr-2", "device-x") // same physical device, new BLE address after rotation
        assertEquals("device-x", resolver.resolve("addr-1"))
        assertEquals("device-x", resolver.resolve("addr-2"))
    }

    @Test
    fun `relearning a different key for the same address overwrites it`() {
        val resolver = PeerIdentityResolver()
        resolver.learn("addr-1", "device-x")
        resolver.learn("addr-1", "device-y")
        assertEquals("device-y", resolver.resolve("addr-1"))
    }

    @Test
    fun `bounded via LRU eviction, same shape as ConnectionAttemptTracker's cooldownUntil`() {
        val resolver = PeerIdentityResolver(maxTrackedAddresses = 3)
        resolver.learn("addr-1", "device-1")
        resolver.learn("addr-2", "device-2")
        resolver.learn("addr-3", "device-3")
        resolver.learn("addr-4", "device-4") // evicts addr-1, the least recently touched
        assertEquals(3, resolver.trackedAddressCount())
        assertEquals("addr-1", resolver.resolve("addr-1")) // forgotten -> falls back to the address itself
        assertEquals("device-4", resolver.resolve("addr-4"))
    }

    @Test
    fun `resolving an address protects it from eviction, same as ConnectionAttemptTracker's cooldownUntil`() {
        val resolver = PeerIdentityResolver(maxTrackedAddresses = 2)
        resolver.learn("addr-1", "device-1")
        resolver.learn("addr-2", "device-2")
        resolver.resolve("addr-1") // touches addr-1, making addr-2 the least recently used
        resolver.learn("addr-3", "device-3") // should evict addr-2, not addr-1
        assertEquals("device-1", resolver.resolve("addr-1"))
        assertEquals("addr-2", resolver.resolve("addr-2"))
    }
}
