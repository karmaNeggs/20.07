package org.offlinemesh.app.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers [EvidenceSymbolDao] directly — Room-backed, not Keystore-backed, so it's fully testable
 * under Robolectric with no constraint. New coverage: the retired `EvidenceChunkDao` (P5 item 2
 * slice 2, docs/DECISIONS.md decision 47, renamed/reshaped from it) never had a dedicated DAO-level
 * test file. [org.offlinemesh.app.ble.RelayEngineTest] covers the layer above this (dedup-as-new
 * signal, decoder feeding); this file only confirms the storage layer itself round-trips, dedups at
 * the `(evidenceId, esi)` primary key, and scopes correctly.
 */
@RunWith(RobolectricTestRunner::class)
class EvidenceSymbolDaoTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dao = AppDatabase.get(context).evidenceSymbolDao()

    @Before
    fun setUp() {
        runBlocking {
            dao.deleteForEvidence("evid-1")
            dao.deleteForEvidence("evid-2")
        }
    }

    private fun data(fill: Int) = ByteArray(16) { fill.toByte() }

    @Test
    fun `getSymbol returns null when nothing is stored yet`() = runTest {
        assertNull(dao.getSymbol("evid-1", 0))
    }

    @Test
    fun `insert then getSymbol round-trips the stored bytes`() = runTest {
        dao.insert(EvidenceSymbolEntity("evid-1", 0, data(1)))
        val fetched = dao.getSymbol("evid-1", 0)
        checkNotNull(fetched)
        assertArrayEquals(data(1), fetched.data)
    }

    @Test
    fun `insert returns -1 (IGNORE) for a duplicate (evidenceId, esi), does not overwrite`() = runTest {
        val firstRowId = dao.insert(EvidenceSymbolEntity("evid-1", 0, data(1)))
        assertTrue("first insert of a genuinely new (evidenceId, esi) must return a real rowid", firstRowId != -1L)
        val secondRowId = dao.insert(EvidenceSymbolEntity("evid-1", 0, data(2)))
        assertEquals(-1L, secondRowId)
        assertArrayEquals(
            "OnConflictStrategy.IGNORE must not overwrite the original row",
            data(1),
            dao.getSymbol("evid-1", 0)?.data,
        )
    }

    @Test
    fun `esi is unbounded, unlike the retired chunkIndex, and accepts a repair-range value`() = runTest {
        // decision 47's own point: esi can be systematic (< k) OR repair (>= k) — the DAO itself
        // has no notion of k at all, it's just an opaque Int key.
        dao.insert(EvidenceSymbolEntity("evid-1", 99999, data(3)))
        assertArrayEquals(data(3), dao.getSymbol("evid-1", 99999)?.data)
    }

    @Test
    fun `allSymbols returns every stored row for one evidenceId, ordered by esi`() = runTest {
        dao.insert(EvidenceSymbolEntity("evid-1", 2, data(1)))
        dao.insert(EvidenceSymbolEntity("evid-1", 0, data(2)))
        dao.insert(EvidenceSymbolEntity("evid-1", 1, data(3)))
        val all = dao.allSymbols("evid-1")
        assertEquals(listOf(0, 1, 2), all.map { it.esi })
    }

    @Test
    fun `rows are scoped per evidenceId, not shared across ids`() = runTest {
        dao.insert(EvidenceSymbolEntity("evid-1", 0, data(1)))
        dao.insert(EvidenceSymbolEntity("evid-2", 0, data(2)))
        assertArrayEquals(data(1), dao.getSymbol("evid-1", 0)?.data)
        assertArrayEquals(data(2), dao.getSymbol("evid-2", 0)?.data)
        assertEquals(1, dao.allSymbols("evid-1").size)
    }

    @Test
    fun `deleteForEvidence removes only that evidenceId's rows`() = runTest {
        dao.insert(EvidenceSymbolEntity("evid-1", 0, data(1)))
        dao.insert(EvidenceSymbolEntity("evid-2", 0, data(2)))
        dao.deleteForEvidence("evid-1")
        assertTrue(dao.allSymbols("evid-1").isEmpty())
        assertArrayEquals(data(2), dao.getSymbol("evid-2", 0)?.data)
    }
}
