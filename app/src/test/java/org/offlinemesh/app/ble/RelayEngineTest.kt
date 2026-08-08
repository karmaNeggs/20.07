package org.offlinemesh.app.ble

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.offlinemesh.app.data.AppDatabase
import org.offlinemesh.app.data.CourierEnvelopeEntity
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
            // pruneOlderThan(MAX_VALUE), not deleteForGroup — P4 slice 3's own pool/eviction tests
            // insert blind-carry rows too (groupId == null), which deleteForGroup can't reach.
            db.courierEnvelopeDao().pruneOlderThan(Long.MAX_VALUE)
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
    fun `ingestSos preserves isAlert unchanged through ingestion`() = runTest {
        // decision 35, docs/DECISIONS.md — a relayed emergency must still read as an emergency, and
        // a relayed ordinary message must not suddenly start reading as one.
        relay.ingestSos(sosFixture("sos-alert").copy(isAlert = true))
        relay.ingestSos(sosFixture("sos-quiet").copy(isAlert = false))
        val stored = relay.relayableSos().associateBy { it.id }
        assertTrue(stored.getValue("sos-alert").isAlert)
        assertFalse(stored.getValue("sos-quiet").isAlert)
    }

    @Test
    fun `ingestSos increments hop by exactly 1, independent of ttl`() = runTest {
        // docs/DECISIONS.md decision 16 / PLAN-v2.md P1: hop must be a dedicated, always-plus-1
        // counter, never derived from (or coupled to) however much ttl a degree-aware relay clamp
        // decides to drop in a single hop.
        val received = sosFixture().copy(ttl = 5, hop = 2) // "arrived here having already gone 2 hops"
        relay.ingestSos(received)
        val stored = relay.relayableSos().single { it.id == received.id }
        assertEquals(3, stored.hop)
        assertEquals(4, stored.ttl)
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

    // ---------- catalogKeysForGroup (decision 34 — BeaconRadio's Tier B catalogue filter) ----------

    @Test
    fun `catalogKeysForGroup returns the expected sos and evid key format`() = runTest {
        relay.ingestSos(sosFixture("sos-1"))
        relay.ingestEvidenceMeta(evidenceFixture("evid-1"))
        val keys = relay.catalogKeysForGroup("group-1")
        assertTrue(keys.contains("sos:sos-1"))
        assertTrue(keys.contains("evid:evid-1"))
    }

    @Test
    fun `catalogKeysForGroup is scoped to one group, unlike currentCatalogKeys' combined list`() = runTest {
        // The whole point of decision 34's per-group variant: a Tier B beacon is broadcast per
        // group, and folding another group's activity in would both misrepresent what the filter
        // covers and widen the passive-observable signal decision 34 already accepted a narrower
        // version of.
        relay.ingestSos(sosFixture("sos-1").copy(groupId = "group-1"))
        relay.ingestSos(sosFixture("sos-2").copy(groupId = "group-2"))
        val group1Keys = relay.catalogKeysForGroup("group-1")
        assertTrue(group1Keys.contains("sos:sos-1"))
        assertFalse("group-1's filter must not include group-2's item", group1Keys.contains("sos:sos-2"))
        AppDatabase.get(context).sosDao().deleteForGroup("group-2")
    }

    @Test
    fun `catalogKeysForGroup returns an empty list for a group with nothing held`() = runTest {
        assertTrue(relay.catalogKeysForGroup("group-1").isEmpty())
    }

    // ---------- couriers (P4 slice 2, docs/DECISIONS.md decision 41's own follow-up) ----------
    // RelayEngine.createCourierEnvelope itself is NOT tested here — same reason createSos/
    // createEvidence never are (see this class's own doc): it calls GroupRepository.getGroupKey,
    // Android Keystore-backed, unavailable under Robolectric. These tests instead exercise the DAO/
    // entity/prune layer directly with hand-built fixtures, the same way ingestSos's tests exercise
    // RelayEngine's logic without ever calling the Keystore-dependent create path — the crypto this
    // slice adds (sealCourierBody/openCourierBody/courierTag) is already covered in isolation by
    // MeshFrameCodecTest/CryptoUtilsTest (decision 41), so what's actually new and worth proving
    // here is the persistence layer: Room round-trips a ByteArray-bearing entity correctly, and the
    // courier-specific 24h cutoff is genuinely independent of SOS/evidence's 48h one.

    private fun courierFixture(id: String = "env-1", createdAt: Long = System.currentTimeMillis()) =
        CourierEnvelopeEntity(
            id = id, groupId = "group-1", senderId = "sender-1",
            tag = ByteArray(16) { it.toByte() }, sealed = byteArrayOf(1, 2, 3, 4),
            createdAt = createdAt, copiesRemaining = RelayEngine.COURIER_INITIAL_COPY_BUDGET,
        )

    @Test
    fun `courier envelope round-trips through Room including its ByteArray fields`() = runTest {
        val envelope = courierFixture()
        AppDatabase.get(context).courierEnvelopeDao().insert(envelope)
        val stored = AppDatabase.get(context).courierEnvelopeDao().getById("env-1")
        assertEquals(envelope, stored)
    }

    @Test
    fun `getById returns null for an envelope that was never inserted`() = runTest {
        assertEquals(null, AppDatabase.get(context).courierEnvelopeDao().getById("nope"))
    }

    @Test
    fun `pruneExpired removes a courier envelope past its own 24h cutoff, independent of the 48h SOS one`() = runTest {
        val now = System.currentTimeMillis()
        // Well past the courier-specific 24h TTL but comfortably inside SOS/evidence's 48h one —
        // proves RelayEngine.pruneExpired applies COURIER_MAX_AGE_MILLIS, not the 48h `cutoff` it
        // already computes for evidence/SOS, to this table.
        val old = courierFixture("env-old", now - RelayEngine.COURIER_MAX_AGE_MILLIS - 60_000)
        val fresh = courierFixture("env-fresh", now)
        AppDatabase.get(context).courierEnvelopeDao().insert(old)
        AppDatabase.get(context).courierEnvelopeDao().insert(fresh)

        relay.pruneExpired()

        assertEquals(null, AppDatabase.get(context).courierEnvelopeDao().getById("env-old"))
        assertEquals(fresh, AppDatabase.get(context).courierEnvelopeDao().getById("env-fresh"))
    }

    @Test
    fun `deleteForGroup removes only that group's courier envelopes`() = runTest {
        val mine = courierFixture("env-mine")
        val other = mine.copy(id = "env-other", groupId = "group-2")
        AppDatabase.get(context).courierEnvelopeDao().insert(mine)
        AppDatabase.get(context).courierEnvelopeDao().insert(other)

        AppDatabase.get(context).courierEnvelopeDao().deleteForGroup("group-1")

        assertEquals(null, AppDatabase.get(context).courierEnvelopeDao().getById("env-mine"))
        assertEquals(other, AppDatabase.get(context).courierEnvelopeDao().getById("env-other"))
        AppDatabase.get(context).courierEnvelopeDao().deleteForGroup("group-2")
    }

    // ---------- admitCourierEnvelope / heldCourierIds / relayableCourierEnvelopes (P4 slice 3) ----------
    // CourierPool.decide's own admission-policy truth table is covered in isolation by
    // CourierPoolTest (plain JVM, no Room) — these tests instead prove the DAO wiring around it:
    // eviction actually deletes the right row, epoch bumps on a genuine insert, and the held-vs-
    // relayable split (own-group only for push) is correctly enforced end to end.

    private fun blindCourierFixture(id: String, createdAt: Long = System.currentTimeMillis()) =
        CourierEnvelopeEntity(
            id = id, groupId = null, senderId = null,
            tag = ByteArray(16) { it.toByte() }, sealed = byteArrayOf(1, 2, 3, 4),
            createdAt = createdAt, copiesRemaining = RelayEngine.COURIER_INITIAL_COPY_BUDGET,
        )

    @Test
    fun `admitCourierEnvelope bumps the catalog epoch on a genuinely new insert`() = runTest {
        // The un-defer decision 42 flagged: createCourierEnvelope previously did NOT bump epoch
        // because nothing read courier envelopes for pushing yet — slice 3 is exactly the point
        // that stops being true, so admitCourierEnvelope must bump it now.
        val before = relay.catalogEpoch
        relay.admitCourierEnvelope(courierFixture("env-new"))
        assertTrue(relay.catalogEpoch > before)
    }

    @Test
    fun `admitCourierEnvelope does not bump the catalog epoch on a duplicate id`() = runTest {
        relay.admitCourierEnvelope(courierFixture("env-dup"))
        val afterFirst = relay.catalogEpoch
        val isNew = relay.admitCourierEnvelope(courierFixture("env-dup"))
        assertFalse(isNew)
        assertEquals(afterFirst, relay.catalogEpoch)
    }

    @Test
    fun `admitCourierEnvelope evicts the oldest blind-carry row once the pool is full`() = runTest {
        // Fill blind-carry to its reserved sub-capacity (CAPACITY - OWN_GROUP_RESERVED = 20) with
        // rows of increasing age, oldest first, then admit one more — the oldest must be gone.
        val blindCapacity = CourierPool.CAPACITY - CourierPool.OWN_GROUP_RESERVED
        val base = System.currentTimeMillis() - 1_000_000L
        for (i in 0 until blindCapacity) {
            relay.admitCourierEnvelope(blindCourierFixture("blind-$i", base + i))
        }
        assertNotNull(AppDatabase.get(context).courierEnvelopeDao().getById("blind-0"))

        relay.admitCourierEnvelope(blindCourierFixture("blind-new", base + blindCapacity))

        assertEquals(null, AppDatabase.get(context).courierEnvelopeDao().getById("blind-0"))
        assertNotNull(AppDatabase.get(context).courierEnvelopeDao().getById("blind-new"))
        // Cleanup — these rows are all blind (groupId == null), outside setUp's deleteForGroup reach.
        AppDatabase.get(context).courierEnvelopeDao().pruneOlderThan(Long.MAX_VALUE)
    }

    @Test
    fun `admitCourierEnvelope never rejects an own-group insert even when blind-carry is full`() = runTest {
        val blindCapacity = CourierPool.CAPACITY - CourierPool.OWN_GROUP_RESERVED
        for (i in 0 until blindCapacity) {
            relay.admitCourierEnvelope(blindCourierFixture("blind-$i"))
        }
        val accepted = relay.admitCourierEnvelope(courierFixture("env-mine-2"))
        assertTrue("an own-group insert must never be hard-rejected", accepted)
        assertNotNull(AppDatabase.get(context).courierEnvelopeDao().getById("env-mine-2"))
        AppDatabase.get(context).courierEnvelopeDao().pruneOlderThan(Long.MAX_VALUE)
    }

    @Test
    fun `heldCourierIds includes both own-group and blind-carry rows`() = runTest {
        relay.admitCourierEnvelope(courierFixture("env-own"))
        relay.admitCourierEnvelope(blindCourierFixture("env-blind"))
        val held = relay.heldCourierIds()
        assertTrue(held.contains("env-own"))
        assertTrue(held.contains("env-blind"))
        AppDatabase.get(context).courierEnvelopeDao().pruneOlderThan(Long.MAX_VALUE)
    }

    @Test
    fun `relayableCourierEnvelopes excludes blind-carry rows`() = runTest {
        relay.admitCourierEnvelope(courierFixture("env-own-2"))
        relay.admitCourierEnvelope(blindCourierFixture("env-blind-2"))
        val relayable = relay.relayableCourierEnvelopes().map { it.id }
        assertTrue(relayable.contains("env-own-2"))
        assertFalse(
            "a blind-carried envelope must never be proactively pushed to a third peer",
            relayable.contains("env-blind-2"),
        )
        AppDatabase.get(context).courierEnvelopeDao().pruneOlderThan(Long.MAX_VALUE)
    }
}
