package org.offlinemesh.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tier 1: the Pass 17 per-peer dedup tracker — what gets skipped on repeat connections, what
 *  stays isolated per peer/item, and the eviction bound that caps memory over a long relay session. */
class PeerDeliveryTrackerTest {

    @Test
    fun `a new item to a new peer has not been delivered`() {
        assertFalse(PeerDeliveryTracker().alreadyDelivered("peer1", "sos:abc"))
    }

    @Test
    fun `a marked item is remembered for that peer`() {
        val t = PeerDeliveryTracker()
        t.markDelivered("peer1", "sos:abc")
        assertTrue(t.alreadyDelivered("peer1", "sos:abc"))
    }

    @Test
    fun `delivery to one peer does not leak to another`() {
        val t = PeerDeliveryTracker()
        t.markDelivered("peer1", "sos:abc")
        assertFalse(t.alreadyDelivered("peer2", "sos:abc"))
    }

    @Test
    fun `different items for the same peer are tracked independently`() {
        val t = PeerDeliveryTracker()
        t.markDelivered("peer1", "sos:abc")
        assertFalse(t.alreadyDelivered("peer1", "sos:xyz"))
    }

    @Test
    fun `a nickname update produces a distinct key from the previous version`() {
        // The real call sites key nicknames as "nick:group:sender:updatedAt" specifically so a
        // genuine re-set of the nickname is treated as new content, not a repeat.
        val t = PeerDeliveryTracker()
        t.markDelivered("peer1", "nick:g1:s1:1000")
        assertFalse(t.alreadyDelivered("peer1", "nick:g1:s1:2000"))
    }

    @Test
    fun `evicts the least recently touched peer once over the cap`() {
        val t = PeerDeliveryTracker(maxPeers = 2)
        t.markDelivered("peer1", "a")
        t.markDelivered("peer2", "a")
        t.markDelivered("peer3", "a") // peer1 hasn't been touched since, should be evicted
        assertEquals(2, t.trackedPeerCount())
        assertFalse(t.alreadyDelivered("peer1", "a"))
        assertTrue(t.alreadyDelivered("peer2", "a"))
        assertTrue(t.alreadyDelivered("peer3", "a"))
    }

    @Test
    fun `touching a peer again protects it from eviction`() {
        val t = PeerDeliveryTracker(maxPeers = 2)
        t.markDelivered("peer1", "a")
        t.markDelivered("peer2", "a")
        t.alreadyDelivered("peer1", "a") // access refreshes recency for peer1
        t.markDelivered("peer3", "a") // peer2 is now the least-recently-touched, not peer1
        assertTrue(t.alreadyDelivered("peer1", "a"))
        assertFalse(t.alreadyDelivered("peer2", "a"))
    }
}
