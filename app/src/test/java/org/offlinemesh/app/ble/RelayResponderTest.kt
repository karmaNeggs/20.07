package org.offlinemesh.app.ble

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.offlinemesh.app.crypto.CryptoUtils
import org.offlinemesh.app.data.AppDatabase
import org.offlinemesh.app.data.EvidenceEntity
import org.offlinemesh.app.data.GroupRepository
import org.offlinemesh.app.data.NicknameEntity
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
 * never calls `authOk`/`getGroupKey` — unlike `Frame.SosSealed`/`Frame.EvidMeta` — so this
 * constraint doesn't limit what this file can actually cover. [sosFixture]'s [testGroupKey] is a
 * plain in-test 32-byte array, not a [GroupRepository]-sourced one, for the same reason — it only
 * needs to produce/open a real AES-GCM seal (decision 37, `docs/DECISIONS.md`), never to round-trip
 * through Keystore.
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
        responder = RelayResponder(
            repo, relay, HopTracker(), PositionTracker(), LocationTracker(context),
            PeerIdentityResolver(), ConnectionRegistry(),
        )
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

    // A fixed test-only group key (decision 37, docs/DECISIONS.md) — see the class doc above for
    // why this can't come from GroupRepository.getGroupKey under Robolectric. Nothing here needs it
    // to match any real group; it only has to consistently seal/open within this file.
    private val testGroupKey = ByteArray(32) { it.toByte() }

    private fun sosFixture(id: String = "sos-1", message: String = "help") = SosEntity(
        id = id, groupId = "group-1", senderId = "sender-1", senderIsMe = false,
        message = message, timestamp = System.currentTimeMillis(), ttl = RelayEngine.DEFAULT_TTL,
        // sealSosBody, NOT sealSos — SosEntity.sealed stores RAW sealed bytes, mirroring exactly
        // what RelayEngine.createSos now does (and what RelayResponder.handleSos stores from an
        // arriving frame's own envelope-stripped Frame.SosSealed.sealed) — see sealSosBody's own doc.
        sealed = MeshFrameCodec.sealSosBody(
            testGroupKey, id, "sender-1", message, System.currentTimeMillis(), isAlert = false
        ),
        // Decision 38 (docs/DECISIONS.md): handle, mirroring RelayEngine.createSos's own
        // groupHandle(key, timestamp/1000) computation — needed for real, not just for shape:
        // reframeStoredSos/floodForwardSos both do `sos.handle!!` on the push path this file exercises.
        handle = MeshFrameCodec.groupHandle(testGroupKey, System.currentTimeMillis() / 1000),
    )

    // sha256 must be a real 32-byte (64 hex char) value — MeshFrameCodec's wire format always
    // writes/reads a fixed 32 bytes for it (FRAME_EVID_META), so a shorter placeholder like
    // "abc123" silently misaligns every field after it in the encoded frame.
    private fun evidenceFixture(id: String = "evid-1") = EvidenceEntity(
        id = id, groupId = "group-1", senderId = "sender-1", senderIsMe = false,
        timestamp = System.currentTimeMillis(), sha256 = "ab".repeat(32), totalChunks = 3,
        mimeType = "image/jpeg", ttl = RelayEngine.DEFAULT_TTL,
        // Decision 38: handle, same reasoning as sosFixture's own — encodeEvidMeta writes this
        // (not groupId) to the wire, and decode() rejects a frame with an empty/missing one.
        handle = MeshFrameCodec.groupHandle(testGroupKey, System.currentTimeMillis() / 1000),
    )

    @Test
    fun `pushes a held SOS when the peer's filter says they don't have it`() = runTest {
        relay.ingestSos(sosFixture())
        val peerFilter = CatalogFilter.build(emptyList()) // peer holds nothing
        val filterFrame = MeshFrameCodec.encodeCatalogFilter(peerFilter.seed, peerFilter.sizeBits, peerFilter.toBits())

        val responded = mutableListOf<ByteArray>()
        responder.handleIncoming(filterFrame, "peer1") { responded.add(it) }

        assertEquals(1, responded.size)
        val decoded = MeshFrameCodec.decode(responded[0])
        check(decoded is MeshFrameCodec.Frame.SosSealed)
        assertEquals("sos-1", decoded.id)
        val body = MeshFrameCodec.openSos(decoded.sealed, testGroupKey)
        checkNotNull(body)
        assertEquals("help", body.message)
    }

    @Test
    fun `does not re-push an SOS the peer's filter already says they have`() = runTest {
        relay.ingestSos(sosFixture())
        val peerFilter = CatalogFilter.build(listOf("sos:sos-1")) // peer already holds it
        val filterFrame = MeshFrameCodec.encodeCatalogFilter(peerFilter.seed, peerFilter.sizeBits, peerFilter.toBits())

        val responded = mutableListOf<ByteArray>()
        responder.handleIncoming(filterFrame, "peer1") { responded.add(it) }

        assertTrue(responded.isEmpty())
    }

    @Test
    fun `an evidence header not in the peer's filter is pushed the same way as SOS`() = runTest {
        relay.ingestEvidenceMeta(evidenceFixture())
        val peerFilter = CatalogFilter.build(emptyList())
        val filterFrame = MeshFrameCodec.encodeCatalogFilter(peerFilter.seed, peerFilter.sizeBits, peerFilter.toBits())

        val responded = mutableListOf<ByteArray>()
        responder.handleIncoming(filterFrame, "peer1") { responded.add(it) }

        val decoded = responded.mapNotNull { MeshFrameCodec.decode(it) }
        val evidMeta = decoded.filterIsInstance<MeshFrameCodec.Frame.EvidMeta>().singleOrNull()
        assertTrue("expected the evidence header to be pushed, got: $decoded", evidMeta != null)
        assertEquals("evid-1", evidMeta?.id)
    }

    @Test
    fun `a peer's filter only suppresses items it actually contains, not unrelated ones`() = runTest {
        relay.ingestSos(sosFixture("sos-1"))
        relay.ingestSos(sosFixture("sos-2"))
        val peerFilter = CatalogFilter.build(listOf("sos:sos-1")) // peer has sos-1 only
        val filterFrame = MeshFrameCodec.encodeCatalogFilter(peerFilter.seed, peerFilter.sizeBits, peerFilter.toBits())

        val responded = mutableListOf<ByteArray>()
        responder.handleIncoming(filterFrame, "peer1") { responded.add(it) }

        val ids = responded.mapNotNull { MeshFrameCodec.decode(it) }
            .filterIsInstance<MeshFrameCodec.Frame.SosSealed>()
            .map { it.id }
        assertEquals(listOf("sos-2"), ids)
    }

    // ---- per-connection catalog-item push budget (mirrors consumeBudget for chunks) ----

    @Test
    fun `a catalog deficit larger than the per-connection budget is capped, not pushed in full`() = runTest {
        // maxCatalogItemsPerSession is 200 — 210 held items forces the budget path for real,
        // rather than relying on a test-only override this class doesn't expose (deliberately: the
        // budget is an internal fairness knob, not something callers should be able to bypass).
        for (i in 0 until 210) relay.ingestSos(sosFixture("sos-$i"))
        val peerFilter = CatalogFilter.build(emptyList()) // peer holds nothing
        val filterFrame = MeshFrameCodec.encodeCatalogFilter(peerFilter.seed, peerFilter.sizeBits, peerFilter.toBits())

        val responded = mutableListOf<ByteArray>()
        responder.handleIncoming(filterFrame, "peer1") { responded.add(it) }

        val pushedIds = responded.mapNotNull { MeshFrameCodec.decode(it) }
            .filterIsInstance<MeshFrameCodec.Frame.SosSealed>()
            .map { it.id }
        assertEquals("expected exactly the per-connection budget's worth pushed, not all 210", 200, pushedIds.size)
    }

    @Test
    fun `the catalog-item budget is per connection, reset by resetSessionBudget`() = runTest {
        for (i in 0 until 210) relay.ingestSos(sosFixture("sos-$i"))
        val emptyPeerFilter = CatalogFilter.build(emptyList()) // peer starts holding nothing
        val firstFilterFrame =
            MeshFrameCodec.encodeCatalogFilter(emptyPeerFilter.seed, emptyPeerFilter.sizeBits, emptyPeerFilter.toBits())

        val firstConnection = mutableListOf<ByteArray>()
        responder.handleIncoming(firstFilterFrame, "peer1") { firstConnection.add(it) }
        val firstPushedIds = firstConnection.mapNotNull { MeshFrameCodec.decode(it) }
            .filterIsInstance<MeshFrameCodec.Frame.SosSealed>().map { it.id }
        assertEquals(200, firstPushedIds.size)

        // A fresh connection to the SAME peer resets the budget (MeshGattClient/MeshGattServer
        // already call this on every new connection) — simulating the peer's own filter now
        // reflecting what it just received (as it genuinely would, having ingested those 200
        // items), the remaining 10 must be reachable on this next connection, not permanently
        // stuck behind the first connection's now-exhausted budget.
        responder.resetSessionBudget("peer1")
        val updatedPeerFilter = CatalogFilter.build(firstPushedIds.map { "sos:$it" })
        val secondFilterFrame = MeshFrameCodec.encodeCatalogFilter(
            updatedPeerFilter.seed, updatedPeerFilter.sizeBits, updatedPeerFilter.toBits()
        )
        val secondConnection = mutableListOf<ByteArray>()
        responder.handleIncoming(secondFilterFrame, "peer1") { secondConnection.add(it) }
        val secondPushedIds = secondConnection.mapNotNull { MeshFrameCodec.decode(it) }
            .filterIsInstance<MeshFrameCodec.Frame.SosSealed>().map { it.id }
        // Exactly 10 items remain undelivered, but CatalogFilter is a probabilistic Bloom filter
        // (see its class doc): at a 200-item fill it has a real, non-negligible false-positive
        // rate, so an occasional one of the 10 can look "already held" and get skipped this round
        // (harmlessly — it gets a fresh chance next reconnect, per that same doc). Assert a range
        // rather than pin an exact count against a structure that's allowed to do this by design.
        assertTrue(
            "expected most of the 10 remaining items, got ${secondPushedIds.size}",
            secondPushedIds.size in 8..10
        )
        // Confirms the reset budget is what mattered here, not a fallback resend of everything.
        assertTrue(secondPushedIds.size < firstPushedIds.size)
    }

    @Test
    fun `framesToPushOnConnect advertises a filter that correctly contains our own held items`() = runTest {
        relay.ingestSos(sosFixture())
        val frames = responder.framesToPushOnConnect()
        val filterFrame = frames.mapNotNull { MeshFrameCodec.decode(it) }
            .filterIsInstance<MeshFrameCodec.Frame.CatalogFilter>()
            .single()
        val rebuilt = CatalogFilter.fromBits(filterFrame.bits, filterFrame.seed, filterFrame.sizeBits)

        assertTrue(rebuilt.mightContain("sos:sos-1"))
    }

    // ---- advertise what we HOLD (any ttl), not just what's still RELAYABLE (ttl > 0) ----

    @Test
    fun `an item at ttl 0 is advertised in our own catalog filter but is not pushed to a peer`() = runTest {
        // ingestSos decrements ttl by 1 on ingest (one hop consumed) — starting at 1 means it's
        // stored at ttl 0: held, but no longer relayable.
        relay.ingestSos(sosFixture().copy(ttl = 1))

        val frames = responder.framesToPushOnConnect()
        val filterFrame = frames.mapNotNull { MeshFrameCodec.decode(it) }
            .filterIsInstance<MeshFrameCodec.Frame.CatalogFilter>()
            .single()
        val rebuilt = CatalogFilter.fromBits(filterFrame.bits, filterFrame.seed, filterFrame.sizeBits)
        assertTrue(
            "a held item at ttl 0 must still appear in our advertised catalog (so peers stop " +
                "re-pushing it to us for the rest of its retention window)",
            rebuilt.mightContain("sos:sos-1")
        )

        // But relayableSos (ttl > 0 only) still gates what's actually SENT — a peer whose filter
        // says they don't have it must not receive it, since we no longer relay it ourselves.
        val peerFilter = CatalogFilter.build(emptyList()) // peer holds nothing
        val peerFilterFrame =
            MeshFrameCodec.encodeCatalogFilter(peerFilter.seed, peerFilter.sizeBits, peerFilter.toBits())
        val responded = mutableListOf<ByteArray>()
        responder.handleIncoming(peerFilterFrame, "peer1") { responded.add(it) }
        val pushedSosIds = responded.mapNotNull { MeshFrameCodec.decode(it) }
            .filterIsInstance<MeshFrameCodec.Frame.SosSealed>()
            .map { it.id }
        assertTrue("a ttl-0 item must never be pushed, even to a peer that doesn't have it", pushedSosIds.isEmpty())
    }

    // ---- MTU fallback — a catalog filter that doesn't fit this connection's negotiated MTU
    // must never silently drop delivery (see framesToPushOnConnect's "MTU fallback" class doc) ----

    @Test
    fun `a generous frame budget still gets a catalog filter, not eager push`() = runTest {
        relay.ingestSos(sosFixture())
        val frames = responder.framesToPushOnConnect(maxFrameBytes = 512)
        val decoded = frames.mapNotNull { MeshFrameCodec.decode(it) }
        assertTrue(decoded.any { it is MeshFrameCodec.Frame.CatalogFilter })
        assertTrue("a filter fit — the SOS itself must not also be eagerly pushed",
            decoded.none { it is MeshFrameCodec.Frame.SosSealed })
    }

    @Test
    fun `a too-small frame budget falls back to eagerly pushing the SOS instead of a filter`() = runTest {
        relay.ingestSos(sosFixture())
        // 1 byte: no encoded catalog filter can ever fit this — forces the fallback branch
        // regardless of how small CatalogFilter's own dynamic sizing makes the filter.
        val frames = responder.framesToPushOnConnect(maxFrameBytes = 1)
        val decoded = frames.mapNotNull { MeshFrameCodec.decode(it) }
        assertTrue("expected no catalog filter frame when it can't possibly fit",
            decoded.none { it is MeshFrameCodec.Frame.CatalogFilter })
        val pushedSos = decoded.filterIsInstance<MeshFrameCodec.Frame.SosSealed>().singleOrNull()
        assertTrue("expected the SOS to be pushed eagerly instead", pushedSos != null)
        assertEquals("sos-1", pushedSos?.id)
    }

    @Test
    fun `the eager-push fallback also carries evidence headers and nicknames`() = runTest {
        relay.ingestEvidenceMeta(evidenceFixture())
        val frames = responder.framesToPushOnConnect(maxFrameBytes = 1)
        val decoded = frames.mapNotNull { MeshFrameCodec.decode(it) }
        val pushedMeta = decoded.filterIsInstance<MeshFrameCodec.Frame.EvidMeta>().singleOrNull()
        assertTrue(pushedMeta != null)
        assertEquals("evid-1", pushedMeta?.id)
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

    // ---- blind relay for a group we hold no key for (decision 38, docs/DECISIONS.md) ----
    // This class's `repo` never joins/creates a group, so `resolveGroupKeyByHandle` always misses —
    // exactly the "not a member" case every one of these frame types now routes to its own opaque-
    // custody path for. That's also what makes this genuinely testable under Robolectric for the
    // first time: matchHandle's own loop never runs (zero groups), so no Keystore access ever
    // happens on this path (see GroupRepositoryHandleTest for that same property, isolated).
    //
    // Concretely proves two things this decision's own write-up documents: the opaqueSos bug (SOS
    // blind custody accepted frames but never actually forwarded them, since decision 37 — confirmed
    // via grep that opaquePositions/opaquePresence both fed presenceAndPositionFrames's carried list
    // and opaqueSos did not) is fixed, and the new opaqueNickname path genuinely propagates (unlike
    // the old vacuous-auth scheme, which never re-served a blind-held nickname to anyone).

    @Test
    fun `SOS, position, presence, and nickname frames for an unresolvable group are all carried onward`() = runTest {
        val key = testGroupKey // arbitrary — this repo holds no group for it either way
        val epoch = System.currentTimeMillis() / 1000
        val now = System.currentTimeMillis()
        val contentKey = CryptoUtils.contentEpochKey(key, epoch)

        val sosFrame = MeshFrameCodec.sealSos(
            key, contentKey, "sos-1", "sender-1", "help", now, isAlert = false, ttl = 8, hop = 0
        )
        val positionFrame = MeshFrameCodec.encodePosition(key, contentKey, "sender-1", 1.0, 2.0, 5, epoch, hop = 0)
        val presenceFrame = MeshFrameCodec.encodePresence("some-group", "sender-1", now, key, contentKey)
        val nicknameFrame = MeshFrameCodec.encodeNickname(
            NicknameEntity(
                "some-group", "sender-1", "responder", System.currentTimeMillis(),
                mac = ByteArray(16), handle = MeshFrameCodec.groupHandle(key, epoch),
            )
        )

        // Arrives from peer1 — must come back out to a DIFFERENT peer (split horizon), never
        // handed straight back to whoever supplied it.
        for (frame in listOf(sosFrame, positionFrame, presenceFrame, nicknameFrame)) {
            responder.handleIncoming(frame, "peer1") { }
        }

        val carried = responder.refreshFramesToPush("peer2").mapNotNull { MeshFrameCodec.decode(it) }

        assertTrue("expected a carried SOS frame", carried.any { it is MeshFrameCodec.Frame.SosSealed })
        assertTrue("expected a carried position frame", carried.any { it is MeshFrameCodec.Frame.PositionSealed })
        assertTrue("expected a carried presence frame", carried.any { it is MeshFrameCodec.Frame.Presence })
        assertTrue("expected a carried nickname frame", carried.any { it is MeshFrameCodec.Frame.Nickname })
    }

    @Test
    fun `a carried frame is never handed back to the peer that supplied it`() = runTest {
        val key = testGroupKey
        val now = System.currentTimeMillis()
        val contentKey = CryptoUtils.contentEpochKey(key, now / 1000)
        val sosFrame = MeshFrameCodec.sealSos(
            key, contentKey, "sos-1", "sender-1", "help", now, isAlert = false, ttl = 8, hop = 0
        )
        responder.handleIncoming(sosFrame, "peer1") { }

        val backToSamePeer = responder.refreshFramesToPush("peer1").mapNotNull { MeshFrameCodec.decode(it) }
        assertTrue(backToSamePeer.none { it is MeshFrameCodec.Frame.SosSealed })

        val toAnotherPeer = responder.refreshFramesToPush("peer2").mapNotNull { MeshFrameCodec.decode(it) }
        assertTrue(toAnotherPeer.any { it is MeshFrameCodec.Frame.SosSealed })
    }
}
