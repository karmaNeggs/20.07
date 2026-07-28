package org.offlinemesh.app.ble

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.offlinemesh.app.data.AppDatabase
import org.offlinemesh.app.data.EvidenceEntity
import org.offlinemesh.app.data.GroupRepository
import org.offlinemesh.app.data.SosEntity
import org.offlinemesh.app.sensors.LocationTracker
import org.robolectric.RobolectricTestRunner

/**
 * Covers [RelayResponder]'s `Frame.CatalogFilter` handling — the round-trip set-reconciliation
 * mechanism ([CatalogFilter]'s class doc) that replaced the old direct-push-on-connect design.
 * This had zero coverage before ([CatalogFilterTest] only tests the Bloom filter math in
 * isolation, never [RelayResponder]'s actual decision to push or skip an item), despite README's
 * own Known Limitations already flagging it as needing a live 2-phone pass before being trusted
 * like the rest of the mesh core. Written after "messaging breaking" was reported live and traced
 * to this mechanism as the most likely cause — these tests exercise the real production encode/
 * decode/filter/push code path, just without a real BLE link underneath it.
 *
 * Items are seeded via [RelayEngine.ingestSos]/[RelayEngine.ingestEvidenceMeta] (the "I learned
 * this from a peer" path) rather than [RelayEngine.createSos]/[RelayEngine.createEvidence] (the
 * "I authored this locally" path) deliberately — the latter calls [GroupRepository.getGroupKey],
 * which touches [org.offlinemesh.app.data.GroupKeyStore]'s Android Keystore-backed
 * `EncryptedSharedPreferences`, unavailable under Robolectric (same constraint already documented
 * on [GroupRepository]'s own lazy `keyStore` property). `Frame.CatalogFilter` handling itself
 * never calls `authOk`/`getGroupKey` — unlike `Frame.Sos`/`Frame.EvidMeta` — so this constraint
 * doesn't limit what this file can actually cover.
 *
 * Robolectric-backed for the same reason [WifiDirectHandoffCoordinatorTest] is: [RelayEngine]
 * needs a real `Context` for its Room database.
 */
@RunWith(RobolectricTestRunner::class)
class RelayResponderTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val repo = GroupRepository(context)
    private val relay = RelayEngine(context, repo)
    private lateinit var responder: RelayResponder

    @Before
    fun setUp() {
        responder = RelayResponder(repo, relay, HopTracker(), PositionTracker(), LocationTracker(context))
        // AppDatabase.get() is a real process-wide singleton (by design — one device, one DB in
        // production), which under Robolectric means it survives across @Test methods within the
        // same test run, not just within one test class instance. Without this, an earlier test's
        // fixtures leak into a later test's relayableSos()/relayableEvidenceMeta() results.
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

    // sha256 must be a real 32-byte (64 hex char) value — MeshFrameCodec's wire format always
    // writes/reads a fixed 32 bytes for it (FRAME_EVID_META), so a shorter placeholder like
    // "abc123" silently misaligns every field after it in the encoded frame.
    private fun evidenceFixture(id: String = "evid-1") = EvidenceEntity(
        id = id, groupId = "group-1", senderId = "sender-1", senderIsMe = false,
        timestamp = System.currentTimeMillis(), sha256 = "ab".repeat(32), totalChunks = 3,
        mimeType = "image/jpeg", ttl = RelayEngine.DEFAULT_TTL
    )

    @Test
    fun `pushes a held SOS when the peer's filter says they don't have it`() = runTest {
        relay.ingestSos(sosFixture())
        val peerFilter = CatalogFilter.build(emptyList()) // peer holds nothing
        val filterFrame = MeshFrameCodec.encodeCatalogFilter(peerFilter.seed, peerFilter.toBits())

        val responded = mutableListOf<ByteArray>()
        responder.handleIncoming(filterFrame, "peer1") { responded.add(it) }

        assertEquals(1, responded.size)
        val decoded = MeshFrameCodec.decode(responded[0])
        check(decoded is MeshFrameCodec.Frame.Sos)
        assertEquals("sos-1", decoded.sos.id)
        assertEquals("group-1", decoded.sos.groupId)
        assertEquals("help", decoded.sos.message)
    }

    @Test
    fun `does not re-push an SOS the peer's filter already says they have`() = runTest {
        relay.ingestSos(sosFixture())
        val peerFilter = CatalogFilter.build(listOf("sos:sos-1")) // peer already holds it
        val filterFrame = MeshFrameCodec.encodeCatalogFilter(peerFilter.seed, peerFilter.toBits())

        val responded = mutableListOf<ByteArray>()
        responder.handleIncoming(filterFrame, "peer1") { responded.add(it) }

        assertTrue(responded.isEmpty())
    }

    @Test
    fun `an evidence header not in the peer's filter is pushed the same way as SOS`() = runTest {
        relay.ingestEvidenceMeta(evidenceFixture())
        val peerFilter = CatalogFilter.build(emptyList())
        val filterFrame = MeshFrameCodec.encodeCatalogFilter(peerFilter.seed, peerFilter.toBits())

        val responded = mutableListOf<ByteArray>()
        responder.handleIncoming(filterFrame, "peer1") { responded.add(it) }

        val decoded = responded.mapNotNull { MeshFrameCodec.decode(it) }
        val evidMeta = decoded.filterIsInstance<MeshFrameCodec.Frame.EvidMeta>().singleOrNull()
        assertTrue("expected the evidence header to be pushed, got: $decoded", evidMeta != null)
        assertEquals("evid-1", evidMeta?.meta?.id)
    }

    @Test
    fun `a peer's filter only suppresses items it actually contains, not unrelated ones`() = runTest {
        relay.ingestSos(sosFixture("sos-1"))
        relay.ingestSos(sosFixture("sos-2"))
        val peerFilter = CatalogFilter.build(listOf("sos:sos-1")) // peer has sos-1 only
        val filterFrame = MeshFrameCodec.encodeCatalogFilter(peerFilter.seed, peerFilter.toBits())

        val responded = mutableListOf<ByteArray>()
        responder.handleIncoming(filterFrame, "peer1") { responded.add(it) }

        val ids = responded.mapNotNull { MeshFrameCodec.decode(it) }
            .filterIsInstance<MeshFrameCodec.Frame.Sos>()
            .map { it.sos.id }
        assertEquals(listOf("sos-2"), ids)
    }

    @Test
    fun `framesToPushOnConnect advertises a filter that correctly contains our own held items`() = runTest {
        relay.ingestSos(sosFixture())
        val frames = responder.framesToPushOnConnect()
        val filterFrame = frames.mapNotNull { MeshFrameCodec.decode(it) }
            .filterIsInstance<MeshFrameCodec.Frame.CatalogFilter>()
            .single()
        val rebuilt = CatalogFilter.fromBits(filterFrame.bits, filterFrame.seed)

        assertTrue(rebuilt.mightContain("sos:sos-1"))
    }

    // ---- catalogEpoch: the signal ConnectionAttemptTracker uses to skip a peer's synced cooldown
    // once there's something new to offer them specifically (the passerby-relay fix) ----

    @Test
    fun `catalogEpoch advances when a new SOS is ingested`() = runTest {
        val before = relay.catalogEpoch
        relay.ingestSos(sosFixture())
        assertTrue(relay.catalogEpoch > before)
    }

    @Test
    fun `catalogEpoch does not advance for a duplicate, already-seen SOS`() = runTest {
        relay.ingestSos(sosFixture())
        val afterFirst = relay.catalogEpoch
        val ingestedAgain = relay.ingestSos(sosFixture()) // same id — seenDao dedup should reject it
        assertEquals(false, ingestedAgain)
        assertEquals(afterFirst, relay.catalogEpoch)
    }

    @Test
    fun `catalogEpoch advances when a new evidence header is ingested`() = runTest {
        val before = relay.catalogEpoch
        relay.ingestEvidenceMeta(evidenceFixture())
        assertTrue(relay.catalogEpoch > before)
    }

    @Test
    fun `RelayResponder catalogEpoch mirrors the underlying RelayEngine's`() = runTest {
        relay.ingestSos(sosFixture())
        assertEquals(relay.catalogEpoch, responder.catalogEpoch)
    }
}
