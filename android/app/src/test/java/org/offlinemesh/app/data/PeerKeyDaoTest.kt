package org.offlinemesh.app.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers [PeerKeyDao] (sender identity) directly — Room-backed, not Keystore-backed
 * (unlike [GroupKeyStore]'s signing-keypair storage), so it's fully testable under Robolectric
 * with no constraint. The actual pin-on-first-sight/hard-reject DECISIONS this table backs are
 * covered separately in [org.offlinemesh.app.ble.RelayResponderSenderIdentityTest] (pure logic, no
 * DAO); this file only confirms the storage layer itself round-trips and scopes correctly.
 */
@RunWith(RobolectricTestRunner::class)
class PeerKeyDaoTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dao = AppDatabase.get(context).peerKeyDao()

    @Before
    fun setUp() {
        runBlocking {
            dao.deleteForGroup("group-1")
            dao.deleteForGroup("group-2")
        }
    }

    private fun key(seed: Byte) = ByteArray(32) { seed }

    @Test
    fun `get returns null when nothing is pinned yet`() = runTest {
        assertNull(dao.get("group-1", "sender-1"))
    }

    @Test
    fun `insert then get round-trips the pinned public key`() = runTest {
        val entity = PeerKeyEntity("group-1", "sender-1", key(1), firstSeenAt = 1_000L)
        dao.insert(entity)
        val fetched = dao.get("group-1", "sender-1")
        checkNotNull(fetched)
        assertArrayEquals(key(1), fetched.publicKey)
    }

    @Test
    fun `insert with REPLACE overwrites an existing pin for the same (group, sender)`() = runTest {
        dao.insert(PeerKeyEntity("group-1", "sender-1", key(1), firstSeenAt = 1_000L))
        dao.insert(PeerKeyEntity("group-1", "sender-1", key(2), firstSeenAt = 2_000L))
        val fetched = dao.get("group-1", "sender-1")
        checkNotNull(fetched)
        assertArrayEquals(key(2), fetched.publicKey)
    }

    @Test
    fun `pins are scoped per group, not shared across groups for the same senderId`() = runTest {
        dao.insert(PeerKeyEntity("group-1", "sender-1", key(1), firstSeenAt = 1_000L))
        dao.insert(PeerKeyEntity("group-2", "sender-1", key(2), firstSeenAt = 1_000L))
        assertArrayEquals(key(1), dao.get("group-1", "sender-1")!!.publicKey)
        assertArrayEquals(key(2), dao.get("group-2", "sender-1")!!.publicKey)
    }

    @Test
    fun `deleteForGroup removes only that group's pins`() = runTest {
        dao.insert(PeerKeyEntity("group-1", "sender-1", key(1), firstSeenAt = 1_000L))
        dao.insert(PeerKeyEntity("group-2", "sender-1", key(2), firstSeenAt = 1_000L))
        dao.deleteForGroup("group-1")
        assertNull(dao.get("group-1", "sender-1"))
        assertArrayEquals(key(2), dao.get("group-2", "sender-1")!!.publicKey)
    }
}
