package org.offlinemesh.app.ble

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.offlinemesh.app.data.AppDatabase
import org.offlinemesh.app.data.EvidenceEntity
import org.offlinemesh.app.data.GroupRepository
import org.offlinemesh.app.data.SosEntity
import org.robolectric.RobolectricTestRunner

/**
 * Regression coverage for the "is this new" signal [RelayEngine.ingestSos]/
 * [RelayEngine.ingestEvidenceMeta] report — it used to be derived purely from the shorter-lived
 * `seenDao` flood-dedup cache, so once that cache entry expired (well before the SOS/evidence row
 * itself, which lives for the full 48h `CONTENT_MAX_AGE_MILLIS`), the next relay of the exact same
 * item was reported as new again — feeding straight into an `IMPORTANCE_HIGH`/`CATEGORY_ALARM`
 * notification for content that was never actually new. Fixed by deriving newness from the DAO
 * insert's own return value (-1 when `OnConflictStrategy.IGNORE` dropped a genuine duplicate).
 *
 * Robolectric-backed for [RelayEngine]'s real Room database, matching [RelayResponderTest]'s own
 * setup and cleanup pattern. Uses `ingestSos`/`ingestEvidenceMeta` only (never `createSos`/
 * `createEvidence`, which touch `GroupRepository.getGroupKey` / Android Keystore — unavailable
 * under Robolectric, see [RelayResponderTest]'s class doc for the same constraint).
 */
@RunWith(RobolectricTestRunner::class)
class RelayEngineTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val repo = GroupRepository(context)
    private val relay = RelayEngine(context, repo)

    @Before
    fun setUp() {
        // Same reasoning as RelayResponderTest.setUp: AppDatabase.get() is a real singleton that
        // survives across @Test methods under Robolectric, so leftover fixtures must be cleared.
        runBlocking {
            val db = AppDatabase.get(context)
            db.sosDao().deleteForGroup("group-1")
            db.evidenceDao().deleteForGroup("group-1")
            db.seenMessageDao().prune(Long.MAX_VALUE)
        }
    }

    private fun sosFixture(id: String = "sos-1") = SosEntity(
        id = id, groupId = "group-1", senderId = "sender-1", senderIsMe = false,
        message = "help", timestamp = System.currentTimeMillis(), ttl = RelayEngine.DEFAULT_TTL
    )

    private fun evidenceFixture(id: String = "evid-1") = EvidenceEntity(
        id = id, groupId = "group-1", senderId = "sender-1", senderIsMe = false,
        timestamp = System.currentTimeMillis(), sha256 = "ab".repeat(32), totalChunks = 3,
        mimeType = "image/jpeg", ttl = RelayEngine.DEFAULT_TTL
    )

    @Test
    fun `ingesting the same SOS again after the seen-cache entry expires does not report it as new`() = runTest {
        val sos = sosFixture()
        assertTrue("first ingest of a fresh SOS must be new", relay.ingestSos(sos))

        // Simulates the seen-cache entry expiring (SEEN_ID_MAX_AGE_MILLIS) while the SOS row
        // itself (CONTENT_MAX_AGE_MILLIS, much longer) is still alive — exactly the gap the old
        // bug lived in.
        AppDatabase.get(context).seenMessageDao().prune(Long.MAX_VALUE)

        assertFalse(
            "re-ingesting the same SOS after seen-cache expiry must not be reported as new " +
                "(this is what used to re-fire the SOS alarm notification for old content)",
            relay.ingestSos(sos)
        )
    }

    @Test
    fun `a genuinely new SOS after another one's seen-cache entry expires is still reported as new`() = runTest {
        assertTrue(relay.ingestSos(sosFixture("sos-1")))
        AppDatabase.get(context).seenMessageDao().prune(Long.MAX_VALUE)
        assertTrue("a different, never-before-seen SOS id must still be new", relay.ingestSos(sosFixture("sos-2")))
    }

    @Test
    fun `ingesting the same evidence header again after the seen-cache entry expires does not report it as new`() =
        runTest {
            val meta = evidenceFixture()
            assertTrue(relay.ingestEvidenceMeta(meta))
            AppDatabase.get(context).seenMessageDao().prune(Long.MAX_VALUE)
            assertFalse(relay.ingestEvidenceMeta(meta))
        }

    @Test
    fun `catalogEpoch does not advance for a duplicate ingested after seen-cache expiry`() = runTest {
        relay.ingestSos(sosFixture())
        AppDatabase.get(context).seenMessageDao().prune(Long.MAX_VALUE)
        val before = relay.catalogEpoch
        relay.ingestSos(sosFixture())
        assertTrue(relay.catalogEpoch == before)
    }
}
