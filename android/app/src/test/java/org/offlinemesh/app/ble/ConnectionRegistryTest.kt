package org.offlinemesh.app.ble

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRegistryTest {

    @Test
    fun `empty registry has no open links`() {
        assertEquals(0, ConnectionRegistry().openLinkCount())
    }

    @Test
    fun `registering a connection is reflected in open link count`() {
        val registry = ConnectionRegistry()
        registry.register("peer-a") { true }
        registry.register("peer-b") { true }
        assertEquals(2, registry.openLinkCount())
    }

    @Test
    fun `unregistering removes the connection`() {
        val registry = ConnectionRegistry()
        registry.register("peer-a") { true }
        registry.unregister("peer-a")
        assertEquals(0, registry.openLinkCount())
    }

    @Test
    fun `others excludes the given peer but includes everyone else`() {
        val registry = ConnectionRegistry()
        registry.register("peer-a") { true }
        registry.register("peer-b") { true }
        registry.register("peer-c") { true }
        val others = registry.others(excludePeerKey = "peer-b")
        assertEquals(setOf("peer-a", "peer-c"), others.keys)
    }

    @Test
    fun `others with a null exclusion returns everyone`() {
        val registry = ConnectionRegistry()
        registry.register("peer-a") { true }
        registry.register("peer-b") { true }
        assertEquals(setOf("peer-a", "peer-b"), registry.others(excludePeerKey = null).keys)
    }

    @Test
    fun `registered push callback is actually invoked and its result observable`() = runTest {
        val registry = ConnectionRegistry()
        var received: ByteArray? = null
        registry.register("peer-a") { bytes -> received = bytes; true }
        val ok = registry.others(excludePeerKey = null).getValue("peer-a").send(byteArrayOf(1, 2, 3))
        assertTrue(ok)
        assertTrue(received.contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `re-registering the same peer key replaces the old callback rather than adding a second entry`() = runTest {
        val registry = ConnectionRegistry()
        registry.register("peer-a") { false }
        registry.register("peer-a") { true }
        assertEquals(1, registry.openLinkCount())
        assertTrue(registry.others(null).getValue("peer-a").send(ByteArray(0)))
    }
}
