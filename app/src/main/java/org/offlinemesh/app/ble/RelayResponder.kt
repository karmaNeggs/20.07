package org.offlinemesh.app.ble

import android.util.Log
import kotlinx.coroutines.delay
import org.offlinemesh.app.crypto.CryptoUtils
import org.offlinemesh.app.crypto.SenderIdentity
import org.offlinemesh.app.data.CourierEnvelopeEntity
import org.offlinemesh.app.data.EvidenceEntity
import org.offlinemesh.app.data.GroupRepository
import org.offlinemesh.app.data.NicknameEntity
import org.offlinemesh.app.data.PeerKeyEntity
import org.offlinemesh.app.data.SosEntity
import org.offlinemesh.app.diagnostics.DiagnosticsLog
import org.offlinemesh.app.sensors.LocationTracker
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything both the GATT server and GATT client paths need to do with a connection once it's
 * open: what to announce on connect, and what to do with each frame that arrives. Kept
 * independent of BluetoothGatt/BluetoothGattServer entirely so both roles share one
 * implementation instead of two copies that could quietly drift apart.
 */
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass")
// LongParameterList: one collaborator per constructor param, same shape MeshService already wires
// every other class in this file with (BeaconRadio/MeshGattClient/MeshGattServer all take a
// comparable number) — not a candidate for a params-object without adding an abstraction this
// codebase doesn't otherwise use. TooManyFunctions: one small handler per wire frame type (see the
// "per-frame handlers" section) — many small, single-purpose functions instead of one large
// dispatcher.
class RelayResponder(
    private val repo: GroupRepository,
    private val relay: RelayEngine,
    private val hopTracker: HopTracker,
    private val positionTracker: PositionTracker,
    private val locationTracker: LocationTracker,
    // Shared with MeshGattClient (PLAN-v2.md §5.2 / P0b) — this side WRITES the address->senderId
    // mapping (see the per-frame handlers below, right alongside their existing hopTracker calls);
    // MeshGattClient reads it to key ConnectionAttemptTracker on the stable senderId once known,
    // instead of the BLE address, which rotates roughly every 15 minutes.
    private val peerIdentity: PeerIdentityResolver,
    // Shared with MeshGattClient/MeshGattServer (PLAN-v2.md P1 §5.3) — this side READS it to learn
    // the current open-link count (ForwardingPolicy's degree signal) and to push a flood-forward
    // to every other currently-open link. See ConnectionRegistry's class doc.
    private val connectionRegistry: ConnectionRegistry,
    // P5 item 3 (docs/DECISIONS.md's own entry for this slice) — L2capBulkTransport.openFor,
    // wired post-construction-order-agnostic (a plain method reference, not a capturing lambda, so
    // no cycle with L2capBulkTransport needing to exist before RelayResponder can reference it).
    // Optional, default-null — so nothing that constructs a RelayResponder outside MeshService
    // (e.g. a future test) needs to change. Called from handleL2capCap once a peer's advertised
    // PSM is known; the resulting channel (if any) is what handleSymbolRequest prefers over GATT's
    // own respond. Placed before onSosReceived, not after, so onSosReceived stays the last
    // constructor param — MeshService's existing call site uses trailing-lambda syntax for it.
    private val bulkChannelOpener: (suspend (peerAddress: String, psm: Int) -> BulkChannel?)? = null,
    // This device's own currently-advertised L2CAP PSM, or null if not listening (pre-API-29, no
    // adapter, or the listen call itself failed) — read fresh every connection in
    // framesToPushOnConnect, same "check live, not once" style every other capability check in
    // this file follows.
    private val localL2capPsm: () -> Int? = { null },
    // Fires once per newly-received (not duplicate/relayed-again), authenticated SOS in a group
    // we're actually a member of — MeshService uses this to post the system notification. No-op
    // default so this stays optional for anything else that constructs a RelayResponder.
    private val onSosReceived: suspend (SosEntity, groupName: String) -> Unit = { _, _ -> },
) {
    // Raised from 4 to 120 (decision 33, docs/DECISIONS.md) — the original 4 had no documented
    // justification anywhere in this codebase or its decision log; it just happened to comfortably
    // cover this app's stated 3-8 person GROUP size (PLAN-v2.md §5.5). But a position/presence
    // frame's relay depth isn't bounded by group membership — ANY phone, member or not, can carry a
    // frame one hop further (the blind-relay architecture), so a long, unbroken CHAIN of relays can
    // legitimately span far more devices than the group itself has members. 120 stays comfortably
    // below the 1-byte hop field's real ceiling (255, MeshProtocol.UNKNOWN_HOP's own sentinel value)
    // while giving real multi-kilometer reach at realistic per-hop BLE range, given an unbroken
    // chain with no gaps. Reaching hundreds of kilometers would need thousands of hops, which simply
    // doesn't fit an 8-bit field — that's a wire-format change (widening to 2 bytes), deliberately
    // NOT made this pass given v0.7.0/0.7.1-dev APKs are already out on real devices. Shared (by doc
    // only, not by reference — no ble-internal type to hang a single source of truth off, same
    // pattern PositionTracker.PER_HOP_SLACK_SECONDS/HopTracker.PER_HOP_SLACK_MS already use) with
    // BeaconRadio.MAX_POSITION_RELAY_HOPS, which must stay equal. Also reused unchanged for opaque
    // (blind-carried) position AND presence custody below (takeOpaqueCustody/
    // takeOpaquePresenceCustody) — this was already sharing one ceiling for both before this
    // change, and there's no reason to fork it into two now.
    @Suppress("MagicNumber") // see the doc above — 120 is a deliberately chosen ceiling, not a stray literal
    private val maxPositionRelayHops = 120

    // Blind-relay custody for frames belonging to groups we hold no key for — see
    // OpaqueFrameRelay's class doc for why positions and presence both needed this while content
    // never did. Four stores, not one, purely so each can be reasoned about (and counted)
    // separately.
    private val opaquePositions = OpaqueFrameRelay()
    // Presence gets a shorter carry window than positions: a receiver's skew gate is
    // PRESENCE_MAX_SKEW_MS plus per-hop slack, so holding a heartbeat longer than the base skew
    // window just spends airtime on frames the far end will reject as replays.
    private val opaquePresence = OpaqueFrameRelay(maxAgeMillis = PRESENCE_MAX_SKEW_MS)
    // SOS content is now sealed (decision 37, docs/DECISIONS.md) — a non-member can no longer read
    // it, so it needs the same blind-custody treatment position/presence already had. Default
    // maxAgeMillis (matches position's own, generous enough for an SOS's own longer useful life).
    private val opaqueSos = OpaqueFrameRelay()
    // New (decision 38, docs/DECISIONS.md) — nickname never had a blind-relay path before, since a
    // non-member could always vacuously "pass" the old cleartext-plus-HMAC auth check and store a
    // usable row. That row was already a dead end in practice (every nickname push path is scoped
    // to getActiveGroups() only, confirmed via grep — a blind-relay-held row was never re-served to
    // anyone), so this is a genuine new capability, not a like-for-like port. See
    // takeOpaqueNicknameCustody's doc.
    private val opaqueNickname = OpaqueFrameRelay()

    // P4 slice 4 (docs/DECISIONS.md decision 44) — rate-limits repeated handover attempts for the
    // same (envelope, peer) pair, see CourierHandoverTracker's own class doc.
    private val courierHandoverTracker = CourierHandoverTracker()

    // See framesToPushOnConnect's doc for what this default is for. 517 matches the MTU every
    // connection actually requests (MeshGattClient.onConnectionStateChange's requestMtu(517)) —
    // i.e. "assume negotiation succeeds," not a conservative floor.
    private val defaultMaxFrameBytes = ASSUMED_NEGOTIATED_MTU - MeshProtocol.ATT_WRITE_OVERHEAD_BYTES

    /** Combines [RelayEngine.catalogEpoch] (new SOS/evidence/nickname content) with
     *  [PositionTracker.positionEpoch] (a fresher position accepted for someone) into the one
     *  signal [ConnectionAttemptTracker]'s `currentEpoch` param actually needs: "has ANYTHING
     *  worth reconnecting early for changed since I last fully synced with this peer." Originally
     *  content-only — live testing found a relayed position had no equivalent fast path at all,
     *  sitting out the full, un-skippable cooldown no matter how quickly it was actually received,
     *  while content relayed almost immediately. Both are monotonically-increasing counters, so
     *  summing them is a safe combined "did anything change" signal for [canAttempt]'s simple
     *  inequality check — it can never coincidentally return to an earlier value. Kept as a
     *  passthrough rather than handing `relay`/`positionTracker` themselves to
     *  [MeshGattClient]/[MeshGattServer], which only ever depend on [RelayResponder]. */
    val catalogEpoch: Int get() = relay.catalogEpoch + positionTracker.positionEpoch

    // Per-connection cap on *responses* to a SymbolRequest (i.e. novel symbols actually pushed) —
    // renamed from maxChunksPerSession/sessionBudget (decision 47, docs/DECISIONS.md) when fountain
    // coding replaced chunks with symbols, but NOT deleted: PLAN-v2.md §4.3's own "deletes... the
    // session chunk budget" line describes the full Tier X target architecture (a dedicated bulk
    // pipe off the shared GATT link, §4.3 item 3), which this slice is not — until item 3 lands,
    // symbols still share this same connection with SOS/catalog-sync/presence/position traffic, and
    // this budget's real purpose ("keeps one busy item from starving the rotation through other
    // peers") doesn't evaporate just because chunks became symbols. Also now the sole backstop
    // against a hostile/inflated `Frame.SymbolRequest.stillNeed` (see that field's own doc — it is
    // deliberately NOT bound-checked at decode time, unlike the retired Manifest.totalChunks).
    private val maxSymbolsPerSession = 150
    private val symbolSessionBudget = ConcurrentHashMap<String, Int>()

    // Same fairness reasoning as maxSymbolsPerSession/symbolSessionBudget above, applied to the
    // catalog-filter response path instead — caps how many sos/evidence-header/nickname
    // items get pushed to one peer in one connection, so a connection carrying an unusually large
    // catalog deficit can't monopolize the session; anything left over is simply offered again
    // next reconnect (see CatalogFilter's own class doc on why that's safe).
    private val maxCatalogItemsPerSession = MAX_CATALOG_ITEMS_PER_SESSION
    private val catalogItemBudget = ConcurrentHashMap<String, Int>()

    // peerWfdCapable/markWfdCapable/isWfdCapable (Wi-Fi Direct capability tracking) lived here
    // through v0.7.15-dev — deleted by decision 49 (docs/DECISIONS.md), Wi-Fi Direct's removal.
    // peerBulkChannel below is this slice's own, unrelated equivalent for L2CAP CoC.

    // P5 item 3 (docs/DECISIONS.md's own entry for this slice) — the L2CAP bulk channel opened
    // (if any) for a given peer this connection, set by handleL2capCap and read by
    // handleSymbolRequest. Plain ConcurrentHashMap, cleared on every resetSessionBudget call (start
    // of the NEXT connection for this address) — the actual socket teardown lives in
    // L2capBulkTransport.closeFor, called directly from both GATT roles' own disconnect handling;
    // this map is just a reference, not the resource itself.
    private val peerBulkChannel = ConcurrentHashMap<String, BulkChannel>()

    @Synchronized
    fun resetSessionBudget(address: String) {
        // remove(), not set-to-0: this runs once at the start of EVERY connection (both GATT
        // roles), so a set-to-0 entry accumulated one per address ever seen, forever — a real,
        // confirmed unbounded-growth bug (PLAN-v2.md §1.3, independent of address rotation:
        // even a re-key onto a stable identity wouldn't have bounded this on its own, since these
        // two maps were never evicted at all). consumeSymbolBudget/consumeCatalogItemBudget already
        // treat a missing entry as 0 via getOrDefault, so this changes memory footprint only.
        symbolSessionBudget.remove(address)
        catalogItemBudget.remove(address)
        peerBulkChannel.remove(address)
    }

    /** [FIRST_SIGHT]/[UNCHANGED] are both "nothing to worry about". [CHANGED] means this sender is
     *  presenting a different key than the one already pinned for them — see [pinOrCheckSenderKey]
     *  for why that is a warning that re-pins, and NOT a reason to drop traffic. */
    internal enum class SenderKeyPinResult { FIRST_SIGHT, UNCHANGED, CHANGED }

    /** The ONLY place a sender's Ed25519 public key gets pinned — from the presence
     *  heartbeat's [MeshFrameCodec.Frame.Presence.senderPublicKey], never inferred from a signed
     *  content frame. Looks up any existing pin, defers the pure comparison to [checkSenderKeyPin]
     *  (kept `internal` so it's directly unit-testable without a DAO — same reasoning as
     *  [presenceWithinSkew]/[selectPositionsToRelay] below), and writes the pin on first sight OR
     *  when it has changed.
     *
     *  **Why a changed key re-pins instead of rejecting.** It originally hard-rejected the whole
     *  frame, on the reasoning that a changed key is indistinguishable from impersonation (SSH's
     *  trust-on-first-use model). Live testing showed why that's the wrong tradeoff here: a pin
     *  outlives the keypair that created it (pins are in Room, keypairs in EncryptedSharedPreferences
     *  — a reinstall, a Keystore reset, or an APK upgrade can leave the two out of step), and once
     *  stale, it silently dropped EVERY signed frame from that peer forever — presence, SOS, and
     *  positions all gone, while beacon-derived hop count kept working. That reads to a user as
     *  "connected, 1 hop away, but nothing arrives", with no way to recover short of clearing app
     *  data on both phones. A key change now re-pins and logs a warning; the frame still has to pass
     *  the group-key check (its own MAC or seal) to be accepted at all, so a non-member still can't
     *  inject anything. What's given up is detection of a *member* swapping their own identity mid-group —
     *  worth surfacing (and it is, loudly), but not worth silently breaking the app over. */
    private suspend fun pinOrCheckSenderKey(
        groupId: String,
        senderId: String,
        publicKey: ByteArray?,
    ): SenderKeyPinResult {
        val existing = repo.peerKeyDao.get(groupId, senderId)
        val result = checkSenderKeyPin(existing?.publicKey, publicKey)
        val isNewOrChanged =
            result == SenderKeyPinResult.FIRST_SIGHT || result == SenderKeyPinResult.CHANGED
        if (publicKey != null && isNewOrChanged) {
            repo.peerKeyDao.insert(PeerKeyEntity(groupId, senderId, publicKey, System.currentTimeMillis()))
        }
        return result
    }

    /** True = OK to proceed (either a genuine signature verified, or there was nothing to check
     *  yet); false = hard reject, same effect as any other group-key check failure. Looks up any
     *  existing pin,
     *  defers the actual pass/fail decision to [signatureCheckPasses] (pure/`internal`, directly
     *  unit-testable without a DAO). */
    private suspend fun verifySignatureIfPinned(
        groupId: String,
        senderId: String,
        signature: ByteArray?,
        signedData: ByteArray,
    ): Boolean {
        val pinned = repo.peerKeyDao.get(groupId, senderId)
        return signatureCheckPasses(pinned?.publicKey, signature, signedData)
    }

    /** Wraps [PeerIdentityResolver.learn], logging only the events actually worth a line — a
     *  brand-new peer resolved, or an address rotating onto an already-known one — so the P0b
     *  hardware gate (`PLAN-v2.md` Part 7: "ship the debug APK, user runs 3 phones, exports the
     *  log") has something concrete to check: `distinct=` should stay near the physical phone
     *  count even as `addresses=` climbs with rotation, instead of the two tracking together like
     *  the pre-P0b `19-prefixes-for-3-phones` diagnostics. */
    private fun learnPeerIdentity(address: String, stableKey: String) {
        if (!peerIdentity.learn(address, stableKey)) return
        DiagnosticsLog.event(
            "identity",
            "peer resolved: addresses=${peerIdentity.trackedAddressCount()} " +
                "distinct=${peerIdentity.distinctIdentityCount()}"
        )
    }

    // want.coerceAtLeast(0): Frame.SymbolRequest.stillNeed is NOT bound-checked at decode time (see
    // that field's own doc), unlike the retired Manifest.totalChunks which fed an O(totalChunks)
    // allocation inside decode() itself — a negative value here has nothing analogous to guard
    // against upstream, so this clamp is the actual defense.
    @Synchronized
    private fun consumeSymbolBudget(address: String, want: Int): Int {
        val used = symbolSessionBudget.getOrDefault(address, 0)
        val remaining = (maxSymbolsPerSession - used).coerceAtLeast(0)
        val take = minOf(remaining, want.coerceAtLeast(0))
        symbolSessionBudget[address] = used + take
        return take
    }

    @Synchronized
    private fun consumeCatalogItemBudget(address: String, want: Int): Int {
        val used = catalogItemBudget.getOrDefault(address, 0)
        val remaining = (maxCatalogItemsPerSession - used).coerceAtLeast(0)
        val take = minOf(remaining, want)
        catalogItemBudget[address] = used + take
        return take
    }

    /** Inverse of [consumeCatalogItemBudget] — CR-14 (`PLAN-v2.md` Part 10, 2026-08-09). A slot
     *  reserved by [consumeCatalogItemBudget] but never actually spent (see
     *  [pushCouriersWithHandover]'s own doc for the concrete gap this closes) must be given back,
     *  or a peer sending several `CatalogFilter` frames on one connection could exhaust the session
     *  budget without a matching number of items ever having been pushed. `coerceAtLeast(0)` — a
     *  no-op if [resetSessionBudget] already cleared this address's entry mid-flight (e.g. a
     *  concurrent disconnect/reconnect), same defensive floor [consumeSymbolBudget] already uses. */
    @Synchronized
    private fun refundCatalogItemBudget(address: String, amount: Int) {
        if (amount <= 0) return
        val used = catalogItemBudget.getOrDefault(address, 0)
        catalogItemBudget[address] = (used - amount).coerceAtLeast(0)
    }

    /** All of our currently-HELD SOS/evidence-header/nickname item keys, across every active group
     *  — the exact set [CatalogFilter] gets built over, and the exact key format
     *  [handleIncoming]'s `Frame.CatalogFilter` case tests a peer's incoming filter against. Kept
     *  as one shared helper so the two sides can never quietly drift out of sync on key format.
     *
     *  Deliberately "held" ([RelayEngine.heldSosIds]/`heldEvidenceIds`), not "relayable"
     *  ([RelayEngine.relayableSos]/`relayableEvidenceMeta`, ttl > 0 only) — an item at ttl 0 has
     *  stopped propagating but is still held until the 48h prune, and omitting it here previously
     *  meant every peer kept re-pushing it to us for the rest of that window since our own filter
     *  never admitted to already having it. This only changes what we ADVERTISE holding; what
     *  actually gets pushed in response to a peer's filter (`handleIncoming`'s `Frame.CatalogFilter`
     *  case) still reads `relayableSos`/`relayableEvidenceMeta`, unchanged — a ttl-0 item is never
     *  sent, only acknowledged as already held. */
    private suspend fun currentCatalogKeys(): List<String> {
        val keys = mutableListOf<String>()
        for (id in relay.heldSosIds()) keys += "sos:$id"
        for (id in relay.heldEvidenceIds()) keys += "evid:$id"
        for (g in repo.groupDao.getActiveGroups()) {
            for (n in relay.nicknamesForGroup(g.id)) keys += "nick:${n.groupId}:${n.senderId}:${n.updatedAt}"
        }
        // Own-group AND blind-carry both included (P4 slice 3, decision 43) — same "held, not
        // relayable" reasoning as sos/evid above, and it's what stops a peer from re-pushing us an
        // envelope we're already blind-carrying forever (see RelayEngine.heldCourierIds' own doc).
        for (id in relay.heldCourierIds()) keys += "cour:$id"
        return keys
    }

    /**
     * On connect we announce: a [CatalogFilter] of everything we hold (SOS/evidence-headers/
     * nicknames), presence, position, and a per-evidence-item symbol deficit (`SymbolRequest` —
     * decision 47, docs/DECISIONS.md, replacing the retired have-bitset `Manifest`). Actual
     * SOS/evidence-header/nickname *content*, and evidence symbol bytes, only move in response to
     * something the peer tells us — a received [CatalogFilter] (see `Frame.CatalogFilter` in
     * [handleIncoming]) or a received `SymbolRequest` respectively — never eagerly here. This is
     * the same "advertise state, then push only the deficit" shape the evidence-symbol request
     * exchange already uses, generalized to the whole catalog: once both sides have synced, a
     * connection exchanges two compact filters and near-nothing else, instead of re-walking every
     * SOS/header/nickname this device has ever seen on every single connection.
     *
     * This replaced an earlier design (`PeerDeliveryTracker`) that instead remembered, per specific
     * peer address, which static items that peer had already been sent — correct at small scale,
     * but the tracking itself needed a bounded, evictable cache, and an evicted peer silently
     * reverted to "resend them everything." A [CatalogFilter] needs no memory of any specific peer
     * at all: each side freshly advertises its own current holdings every connection, so there is
     * nothing to evict and nothing that goes stale by being forgotten.
     *
     * **MTU fallback.** [CatalogFilter] sizes itself to the actual catalog (see its `sizeBitsFor`
     * doc), but a filter that still doesn't fit this specific connection's negotiated MTU falls
     * back to eagerly pushing content directly — see [framesToPushOnConnect]'s `maxFrameBytes`
     * param. Without this, a low-MTU connection would decode a truncated filter as garbage (or
     * fail to decode at all) and — since delivery is otherwise exclusively reactive to receiving a
     * peer's filter — silently deliver nothing on that connection.
     *
     * [maxFrameBytes] defaults generously (assumes a 517-byte MTU negotiation succeeded) so
     * existing callers (and every pre-existing test) see no change unless they opt into passing
     * the real tracked value. Real production callers ([MeshGattClient]/[MeshGattServer]) always
     * pass their actual negotiated MTU for this connection.
     */
    suspend fun framesToPushOnConnect(
        maxFrameBytes: Int = defaultMaxFrameBytes,
        toPeer: String? = null,
    ): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        val keys = currentCatalogKeys()
        val filter = CatalogFilter.build(keys)
        val filterFrame = MeshFrameCodec.encodeCatalogFilter(filter.seed, filter.sizeBits, filter.toBits())
        // CR-3 (PLAN-v2.md Part 10, 2026-08-09) — was `filterFrame.size <= maxFrameBytes`, which
        // compared the UNPADDED frame against the budget while the transport (MeshGattClient.write/
        // MeshGattServer.notify) pads every outgoing frame afterward via MeshFrameCodec.padGattFrame.
        // [maxFrameBytes] IS that same padGattFrame's own `maxUsableBytes` budget for this connection
        // (both are `negotiated MTU - MeshProtocol.ATT_WRITE_OVERHEAD_BYTES`, see this function's own
        // doc), so this now asks the real question: does what padGattFrame will ACTUALLY produce for
        // this frame, under this connection's own budget, still fit that budget? A filter within 2
        // bytes of maxFrameBytes (padGattFrame's own length prefix) used to "fit" here and then not
        // fit the write it was handed to moments later.
        if (MeshFrameCodec.padGattFrame(filterFrame, maxFrameBytes).size <= maxFrameBytes) {
            frames += filterFrame
            // Cheap, low-volume (once per connection, not per frame) — lets a live logcat pull
            // during a "message isn't arriving" report confirm whether the item was even in this
            // device's own outgoing catalog at connect time, without needing to reproduce anything.
            Log.d("RelayResponder", "advertising catalog filter: ${keys.size} item(s) held, ${filterFrame.size}B")
        } else {
            // The filter itself doesn't fit this connection's negotiated MTU — dynamic sizing
            // (CatalogFilter.sizeBitsFor) keeps this rare for a typical short-lived group's small
            // catalog, but a hard floor (unfiltered legacy chipsets, some OEM stacks that never
            // grant more than the default 23-byte MTU) must never silently drop SOS delivery the
            // way it used to: before this fallback existed, a filter that didn't fit simply failed
            // to decode on the peer's end, and since the catalog-filter redesign made SOS/evidence-
            // header/nickname delivery EXCLUSIVELY reactive to receiving a peer's filter, that
            // failure was silent and total on this connection. Falls back to the pre-catalog-
            // filter behavior: push relayable content eagerly. Correctness (it arrives) beats
            // efficiency (a compact diff) here.
            DiagnosticsLog.event(
                "relay",
                "catalog filter (${filterFrame.size}B) exceeds ${maxFrameBytes}B budget — eager " +
                    "push of ${keys.size} item(s)"
            )
            Log.w(
                "RelayResponder",
                "catalog filter (${filterFrame.size}B) exceeds this connection's ${maxFrameBytes}B " +
                    "budget — falling back to eager push of ${keys.size} item(s)"
            )
            // Decision 37 (docs/DECISIONS.md): forwards the ORIGINAL sealed bytes verbatim, same
            // "never re-encrypt a relayed item" reasoning position/nickname relay already follow —
            // sealed is null only transiently during construction, never for a stored row.
            for (sos in relay.relayableSos()) {
                sos.sealed?.let {
                    frames += MeshFrameCodec.reframeSosForRelay(sos.handle!!, sos.id, sos.ttl, sos.hop, it)
                }
            }
            for (meta in relay.relayableEvidenceMeta()) frames += MeshFrameCodec.encodeEvidMeta(meta)
            for (g in repo.groupDao.getActiveGroups()) {
                for (n in relay.nicknamesForGroup(g.id)) frames += MeshFrameCodec.encodeNickname(n)
            }
        }
        // P5 item 3 (docs/DECISIONS.md's own entry for this slice) — announced fresh every
        // connection (never cached): localL2capPsm() re-reads L2capBulkTransport's own current
        // listening state every time, so a listen failure/adapter toggle mid-session is reflected
        // immediately rather than cached from connect time, same "check live, not once" style every
        // group-key check in this file already follows.
        localL2capPsm()?.let { psm -> frames += MeshFrameCodec.encodeL2capCap(psm) }
        frames += presenceAndPositionFrames(toPeer)
        // P5 slice 1 (docs/DECISIONS.md decision 45): fullResRelayable, NOT relayableEvidenceMeta —
        // sending our own SymbolRequest here IS what solicits symbols back (see RelayEngine.
        // fullResRelayable's own doc for the full mechanism), so this is the actual pull-gate.
        // Decision 47's own small improvement over the retired Manifest mechanism: a COMPLETE item
        // (stillNeed == 0) now sends nothing at all, rather than always re-sending a manifest every
        // connection even at 100% — RelayEngine.symbolDeficit already returns 0 for that case.
        for (meta in relay.fullResRelayable()) {
            val stillNeed = relay.symbolDeficit(meta.id)
            if (stillNeed > 0) frames += MeshFrameCodec.encodeSymbolRequest(meta.id, stillNeed)
        }
        return frames
    }

    /** Presence + position — own, relayed for other members, and blind-carried for groups we
     *  aren't in — the LIVE, time-sensitive subset of [framesToPushOnConnect]'s full set. Called
     *  once as part of that (connection start) AND periodically thereafter on an already-open
     *  link (see [refreshFramesToPush] / `MeshGattClient`'s periodic-refresh loop, PLAN-v2.md P3 /
     *  docs/DECISIONS.md decision 20): everything else `framesToPushOnConnect` sends (the catalog
     *  filter, WFD cap, evidence symbol requests) either doesn't need this cadence of refreshing or is
     *  already handled by P1's event-driven flood-forward — only presence/position go stale purely
     *  from TIME passing on a link that's no longer cycling every ~45-60s the way v1's did. */
    private suspend fun presenceAndPositionFrames(toPeer: String?): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        for (g in repo.groupDao.getActiveGroups()) {
            // A tiny authenticated "I'm a member of this group, on this connection" heartbeat, sent
            // first for every group we're in. This is what makes presence work when beacon discovery
            // is one-directional: the single GATT link carries it both ways, so a peer that can't
            // hear our beacon still learns we're here. Costs ~70 bytes and one HMAC per group.
            repo.getGroupKey(g.id)?.let { rootKey ->
                val identity = repo.getSenderKeyPair(g.id)
                val timestamp = System.currentTimeMillis()
                // Decision 39 (docs/DECISIONS.md): captured as one local, reused for both the frame
                // and the epoch derivation — two separate System.currentTimeMillis() calls could
                // straddle an epoch boundary and derive a key that doesn't match what's actually
                // authenticated.
                val contentKey = CryptoUtils.contentEpochKey(rootKey, timestamp / MILLIS_PER_SECOND)
                frames += MeshFrameCodec.encodePresence(
                    g.id, repo.senderIdFor(g.id), timestamp, rootKey, contentKey,
                    senderPublicKey = identity?.publicKey, signingPrivateKey = identity?.privateKey
                )
            }
            frames += positionFramesToPush(g.id, toPeer)
            // Decision 30 (docs/DECISIONS.md, hardware-confirmed 2026-08-06): nickname content was
            // ONLY ever pushed via framesToPushOnConnect's once-per-connection catalog-filter
            // exchange — never refreshed on an already-open link the way presence/position were in
            // decision 20. A nickname set after a P3 persistent link had already connected could
            // never reach that peer until the link happened to drop and reconnect, which — same as
            // decision 20's own finding — could now be indefinitely long. Small and bounded (this
            // app's groups are 3-8 people, PLAN-v2.md §5.5), so pushed unconditionally every
            // refresh, same as presence, rather than tracked for whether it actually changed.
            for (n in relay.nicknamesForGroup(g.id)) frames += MeshFrameCodec.encodeNickname(n)
        }
        // Positions/presence/SOS/nicknames we're carrying for groups we aren't in. Outside the
        // per-group loop above on purpose: these belong to groups absent from getActiveGroups()
        // precisely because we're not a member, so that loop would never reach them.
        // Budgeted, like every other relay path here (MAX_RELAYED_POSITIONS_PER_GROUP for member
        // positions, MAX_CATALOG_ITEMS_PER_SESSION for content). This one previously wasn't: two
        // 200-entry stores could emit up to 400 frames, unbudgeted, at the FRONT of the push — and
        // since every frame is a serialised GATT write, a phone carrying for several strangers'
        // groups could spend an entire push on their traffic. Blind carriage must not outrank the
        // mesh's own delivery.
        //
        // Decision 38 (docs/DECISIONS.md): opaqueSos.framesToRelay was never called here — a real
        // bug, confirmed by grep, present since decision 37 added opaqueSos.offer without ever
        // wiring its output back out. SOS blind custody accepted frames but never actually forwarded
        // them. opaqueNickname is new this decision — nickname's old vacuous-auth blind-relay never
        // actually re-served a held row to anyone (traced: every nickname push path is scoped to
        // getActiveGroups() only), so this is the first time it genuinely propagates.
        val carried = opaquePositions.framesToRelay(excludePeer = toPeer, limit = MAX_OPAQUE_FRAMES_PER_SESSION) +
            opaquePresence.framesToRelay(excludePeer = toPeer, limit = MAX_OPAQUE_FRAMES_PER_SESSION) +
            opaqueSos.framesToRelay(excludePeer = toPeer, limit = MAX_OPAQUE_FRAMES_PER_SESSION) +
            opaqueNickname.framesToRelay(excludePeer = toPeer, limit = MAX_OPAQUE_FRAMES_PER_SESSION)
        if (carried.isNotEmpty()) {
            frames += carried
            DiagnosticsLog.event("relay", "forwarding ${carried.size} opaque frame(s)")
        }
        return frames
    }

    /** Call periodically (~15-20s, see `MeshGattClient`'s refresh loop) on an already-open,
     *  persistent link — PLAN-v2.md P3 kept links open far past the moment
     *  [framesToPushOnConnect] used to be the only chance presence/position/nicknames ever had to
     *  cross one. Confirmed live (2026-08-05, docs/DECISIONS.md decision 20): without this, a
     *  peer's radar dot only ever refreshed when a connection happened to drop and reopen, which —
     *  now that links can stay up for 10-20 minutes — read as "the radar doesn't work" for the
     *  entire life of a healthy link. Nicknames got the same fix later (decision 30,
     *  2026-08-06) after the identical symptom showed up for them: a nickname set after a link was
     *  already open never reached that peer. */
    suspend fun refreshFramesToPush(toPeer: String?): List<ByteArray> = presenceAndPositionFrames(toPeer)

    /** My own fix, plus whatever I'm holding on behalf of other group members, one hop further out
     *  — capped to the nearest [MAX_RELAYED_POSITIONS_PER_GROUP] (see [selectPositionsToRelay]'s
     *  doc for why). Every frame is AES-GCM-sealed under the group key, so this is safe to push
     *  even to a peer that turns out not to be a member — they receive opaque bytes, not our
     *  members' GPS. */
    private fun positionFramesToPush(groupId: String, toPeer: String? = null): List<ByteArray> {
        val rootKey = repo.getGroupKey(groupId) ?: return emptyList()
        val frames = mutableListOf<ByteArray>()
        val myLoc = locationTracker.location.value
        if (myLoc != null) {
            val nowSec = System.currentTimeMillis() / 1000
            // Decision 39 (docs/DECISIONS.md): sealed under the current epoch's derived content
            // key; groupHandle (inside encodePosition) stays on the root key, unchanged.
            val contentKey = CryptoUtils.contentEpochKey(rootKey, nowSec)
            frames.add(
                MeshFrameCodec.encodePosition(
                    rootKey, contentKey, repo.senderIdFor(groupId), myLoc.latitude, myLoc.longitude,
                    myLoc.accuracy.toInt(), nowSec, 0,
                    signingPrivateKey = repo.getSenderKeyPair(groupId)?.privateKey
                )
            )
        }
        // Only OUR OWN fix above gets signed with OUR identity — a position we're relaying on
        // someone else's behalf was already signed (if at all) by ITS original sender before it
        // ever reached us; we have no private key to sign as them, and re-signing as ourselves
        // would be indistinguishable from us fabricating their position. That relayed signature
        // isn't actually re-attached here — see PositionTracker.Record, which only ever stores the
        // fields RelayResponder.handlePositionSealed extracts from an opened PositionBody, and this
        // codebase doesn't yet carry a signature through that relay hop. Left for a later pass if
        // multi-hop position provenance turns out to matter in practice; the receiver's own
        // pin-on-first-sight check still applies to a DIRECT neighbor's own position frame either
        // way, which is this feature's primary target.
        val toRelay = selectPositionsToRelay(
            positionTracker.forGroup(groupId), repo.senderIdFor(groupId), maxPositionRelayHops, toPeer
        )
        for ((senderId, record) in toRelay) {
            // Forward the ORIGINAL ciphertext rather than re-sealing it. Re-encrypting produced a
            // fresh nonce every push (MeshFrameCodec.positionNonce), so the same position looked
            // like a brand-new frame to every downstream blind relay's ciphertext dedup — one
            // position could occupy 4-18 slots of a neighbour's 200-entry store and be re-pushed to
            // everyone, evicting genuinely distinct frames. Forwarding verbatim makes the ciphertext
            // stable end-to-end, so dedup works at every hop, AND preserves the sender's Ed25519
            // signature inside the seal, which re-encryption silently dropped (relayed positions
            // were previously unsigned and unverifiable).
            val sealed = record.sealed
            val handle = record.handle
            frames.add(
                if (sealed != null && handle != null) {
                    MeshFrameCodec.reframePositionForRelay(handle, record.hop + 1, sealed)
                } else {
                    // No original bytes (a record predating this, or our own fix) — seal it
                    // ourselves. Content key derives from the RECORD's own authored time (decision
                    // 39, docs/DECISIONS.md), not "now" — this is a re-seal of someone else's
                    // content, not a fresh authoring.
                    val recordContentKey = CryptoUtils.contentEpochKey(rootKey, record.timestampSec)
                    MeshFrameCodec.encodePosition(
                        rootKey, recordContentKey, senderId, record.lat, record.lon,
                        record.accuracyM, record.timestampSec, record.hop + 1
                    )
                }
            )
        }
        return frames
    }

    // maybeAccelerateOverWifiDirect/handleWifiDirectCap/handleWifiDirectHandoff/
    // handleWifiDirectAccept and the whole Wi-Fi Direct accelerator subsystem lived here through
    // v0.7.15-dev — deleted by decision 49 (docs/DECISIONS.md), PLAN-v2.md §4.3 item 3's own
    // already-planned removal, once decision 48 shipped BLE L2CAP CoC as its replacement.

    // ---------- per-frame handlers ----------
    // One private handler per frame type, dispatched from handleIncoming below. Each handler's own
    // early `return` abandons only that frame (exactly the same effect as the old inline `when`
    // branches had, since nothing followed the `when` inside handleIncoming's try block either) —
    // splitting these out changes nothing about behavior, only where the dispatch decision lives.

    /** Decision 37 (docs/DECISIONS.md): SOS content is now AES-GCM sealed, not cleartext-plus-HMAC
     *  — a phone with no key for [frame]'s group can no longer read OR authenticate it, so it takes
     *  opaque custody instead of attempting the old vacuous-pass auth check. Same split
     *  [handlePositionSealed] already makes between the member path (this function) and
     *  [takeOpaqueSosCustody] (the blind-relay path). Decision 38: [frame] no longer names its
     *  group directly — [GroupRepository.resolveGroupKeyByHandle] resolves [frame]'s opaque
     *  `handle` to a real (groupId, key) pair first; a resolution failure is now what routes to the
     *  blind-relay path, instead of a direct `getGroupKey` miss. */
    private suspend fun handleSos(frame: MeshFrameCodec.Frame.SosSealed, peerAddress: String) {
        val resolved = repo.resolveGroupKeyByHandle(frame.handle)
        if (resolved == null) {
            takeOpaqueSosCustody(frame, peerAddress)
            return
        }
        val (groupId, rootKey) = resolved
        // A failed decrypt (wrong key, tampered ciphertext, or a GCM tag mismatch) IS the auth
        // failure now; there is no separate mac to check. Replaces the old authOk(...) call
        // entirely. Decision 39 (docs/DECISIONS.md): tries each candidate content-epoch key in
        // turn — SOS's own timestamp lives inside the seal, so the exact epoch isn't knowable
        // before something actually opens.
        val body = CryptoUtils.candidateContentEpochKeys(rootKey)
            .firstNotNullOfOrNull { MeshFrameCodec.openSos(frame.sealed, it) }
            ?: run {
                Log.w("RelayResponder", "SOS failed to open for a group we hold the key to — dropping")
                DiagnosticsLog.event("reject", "sos failed to open for a held group")
                return
            }
        ingestOpenedSos(groupId, frame, body, peerAddress)
    }

    /** The member path for an SOS we could actually open. Split from [handleSos] to keep both
     *  functions' return counts within detekt's limit — same reason [ingestOpenedPosition] is split
     *  from [handlePositionSealed]. [groupId] is the real group [handleSos] just resolved from
     *  [frame]'s opaque `handle` (decision 38) — used everywhere [frame.groupId] used to be read
     *  directly. */
    private suspend fun ingestOpenedSos(
        groupId: String,
        frame: MeshFrameCodec.Frame.SosSealed,
        body: MeshFrameCodec.SosBody,
        peerAddress: String,
    ) {
        // Additive per-sender check: decrypting under the group key only proves SOME member
        // produced this; a pinned sender key catches a different member forging this one's SOS.
        if (!verifySignatureIfPinned(groupId, body.senderId, body.signature, body.signedBytes)) {
            Log.w(
                "RelayResponder",
                "SOS signature failed verification for a pinned sender — dropping (possible impersonation)"
            )
            DiagnosticsLog.event("reject", "sos signature failed for a pinned sender")
            return
        }
        val sos = SosEntity(
            frame.id, groupId, body.senderId, senderIsMe = false, body.message, body.timestamp,
            frame.ttl, frame.hop, body.isAlert, sealed = frame.sealed, handle = frame.handle,
        )
        val isNew = relay.ingestSos(sos)
        // The receive half of relay. Without this the diagnostics log only showed what we PUSHED,
        // which made it impossible to tell "relay isn't happening" from "relay happened and we
        // simply had nothing new to offer" — the two look identical from the push side alone.
        // hop>1 here is direct evidence a frame arrived via a relay.
        // Hop-tracking runs on every receipt, not gated behind ingestSos's dedup return — a shorter
        // path found on a later sighting of the same sos.id must still improve our estimate;
        // considerDirectHop keeps it only if better.
        // frame.hop + 1, NOT ttl-derived (PLAN-v2.md P1 / docs/DECISIONS.md decision 16): a
        // degree-aware relay may drop ttl by more than 1 in a single hop for flood control, which
        // would silently corrupt a ttl-derived hop count. hop is a dedicated cleartext envelope
        // field, incremented by exactly +1 on every RelayEngine.ingestSos, immune to that.
        // frame.hop AS RECEIVED is the SENDER's own distance from origin (their stored, already-
        // incremented value) — mine is exactly one more, the same "+1" the old ttl formula baked in
        // (DEFAULT_TTL - ttl + 1) and what RelayEngine.ingestSos's `hop = sos.hop + 1` also stores,
        // so the value shown here and the value persisted for this SOS's own next relay agree.
        val hopsFromOrigin = frame.hop + 1
        if (isNew) {
            // frame.id (a per-message UUID, not a person identifier) is included, deliberately
            // unlike sender/group ids elsewhere in this file — for a device test it's what lets
            // exported logs from separate phones be joined on "the same message," to measure
            // actual origin-to-receipt delay per hop. See DiagnosticsLog's class doc: this is a
            // content id, not the kind of identifier that class doc's exclusions are about.
            DiagnosticsLog.event(
                "recv",
                "NEW sos id=${frame.id.take(SENDER_ID_LOG_CHARS)} from " +
                    "${body.senderId.take(SENDER_ID_LOG_CHARS)} hop=$hopsFromOrigin"
            )
        }
        // Sourced on senderId (stable per (device, group) — see PeerIdentityResolver's class doc),
        // not peerAddress (rotates ~every 15min and would strand HopTracker's route ownership on
        // an address that no longer exists — PLAN-v2.md §1.3 / P0b). body.senderId is already
        // authenticated by the successful decrypt-under-the-group-key above, so learning it here is
        // no less trustworthy than the routing decision this same value already drives.
        learnPeerIdentity(peerAddress, body.senderId)
        // Both gated on isAlert (decision 35, docs/DECISIONS.md) — an ordinary quiet message still
        // relays/syncs exactly like before (floodForwardSos below is unconditional), but has no
        // business feeding the SOS hop-gradient or firing the alarm-style notification, both of
        // which only make sense for a genuine flagged emergency.
        if (sos.isAlert) {
            hopTracker.considerDirectHop(groupId, frame.id, hopsFromOrigin, body.senderId)
            // Reaching this branch already means we hold the key (we're a member) — the old
            // separate isMember check is no longer needed, a blind relay can't reach this far.
            if (isNew) {
                val groupName = repo.groupDao.getGroup(groupId)?.name ?: groupId
                onSosReceived(sos, groupName)
            }
        }
        // PLAN-v2.md P1 §5.3: immediate forward across every OTHER currently-open link, instead of
        // waiting for that link's own next catalogue-sync — see floodForwardSos's doc. Gated on
        // isNew alone (relay.ingestSos's DB-backed dedup, not a separate in-memory layer — see
        // docs/DECISIONS.md decision 18 for why DedupCache stays unwired here for now): a
        // duplicate has nothing new to flood.
        // excludeKey is best-effort split horizon: resolves to whatever learnPeerIdentity just
        // taught the resolver above, which may not exactly match the key this connection
        // originally registered under if identity resolution changed mid-connection (see
        // ConnectionRegistry's registeredKey/activeTrackerKey docs). Worst case is a redundant
        // echo back to the sender, which their own ingestSos dedup silently absorbs — not a
        // correctness bug.
        if (isNew) floodForwardSos(sos, hopsFromOrigin, excludeKey = peerIdentity.resolve(peerAddress))
    }

    /** Blind-relay custody for an SOS we hold no key for — see [OpaqueFrameRelay]'s class doc and
     *  [takeOpaqueCustody]/[takeOpaquePresenceCustody]'s identical shape for position/presence.
     *  Decision 37 (docs/DECISIONS.md) is what makes this possible at all: before SOS content was
     *  sealed, a non-member could already read it in cleartext via [handleSos]'s old vacuous-pass
     *  auth check, so there was never a reason for a separate opaque path. [maxHops] reuses
     *  [RelayEngine.DEFAULT_TTL] as a reasonable blind-relay depth ceiling — matching the scale a
     *  member's own degree-aware flood-forward would naturally reach, since a blind relay has no
     *  group-key-derived signal of its own to size this from. Decision 38: [frame.handle] is
     *  forwarded verbatim, never recomputed — a blind relay has no key to recompute it with. */
    private fun takeOpaqueSosCustody(frame: MeshFrameCodec.Frame.SosSealed, peerAddress: String) {
        val accepted = opaqueSos.offer(
            dedupKey = OpaqueFrameRelay.dedupKey(frame.sealed),
            hop = frame.hop,
            maxHops = RelayEngine.DEFAULT_TTL,
            viaPeer = peerAddress,
        ) { MeshFrameCodec.reframeSosForRelay(frame.handle, frame.id, frame.ttl, frame.hop + 1, frame.sealed) }
        if (accepted) {
            DiagnosticsLog.event("relay", "carrying opaque sos hop=${frame.hop} (not a member)")
        }
    }

    // ---------- couriers (P4 slice 3, docs/DECISIONS.md decision 43) ----------
    // Shaped like handleSos/ingestOpenedSos/takeOpaqueSosCustody above (resolve-then-branch, verify
    // a pinned signature, dedup via RelayEngine's own admission gate), with three deliberate
    // differences: (1) the blind-carry path stores a real CourierEnvelopeEntity row via
    // RelayEngine.admitCourierEnvelope, not an OpaqueFrameRelay custody offer (decision 42 already
    // ruled that mechanism's 3-minute default max age wrong for a 24h-TTL envelope); (2) there is no
    // notification-equivalent to onSosReceived — payload is an opaque ByteArray with no schema yet
    // (decision 41), nothing to render even if a callback fired; (3) no immediate flood-forward on
    // receipt (no floodForwardSos equivalent) — delivery flows exclusively through the catalog-
    // filter deficit-push cycle (currentCatalogKeys/handleCatalogFilter below), the same one-shot-
    // per-connection treatment SOS/evidence-header/nickname *content* already get. A freshly
    // received courier envelope might therefore wait for the next reconnect on an already-open link
    // before reaching a third peer — the same class of gap decision 19 found and fixed for SOS,
    // deliberately left open here since couriers exist specifically to survive multi-hour
    // partitions, so "might wait for the next connection" costs little against that baseline.

    private suspend fun handleCourier(frame: MeshFrameCodec.Frame.Courier, peerAddress: String) {
        val resolved = repo.resolveGroupKeyByCourierTag(frame.tag)
        if (resolved == null) {
            takeCourierCustody(frame)
            return
        }
        val (groupId, rootKey) = resolved
        // Single exact epoch key, not a candidate search — unlike SOS/position, createdAt is
        // cleartext on this frame (see Frame.Courier's own doc), so the right epoch is already known.
        val contentKey = CryptoUtils.contentEpochKey(rootKey, frame.createdAt / MILLIS_PER_SECOND)
        val body = MeshFrameCodec.openCourierBody(frame.sealed, contentKey) ?: run {
            Log.w("RelayResponder", "courier envelope failed to open for a group we hold the key to — dropping")
            DiagnosticsLog.event("reject", "courier envelope failed to open for a held group")
            return
        }
        ingestOpenedCourier(groupId, frame, body, peerAddress)
    }

    /** The member path for a courier envelope we could actually open. Split from [handleCourier] to
     *  keep both functions' return counts within detekt's limit — same reason [ingestOpenedSos] is
     *  split from [handleSos]. */
    private suspend fun ingestOpenedCourier(
        groupId: String,
        frame: MeshFrameCodec.Frame.Courier,
        body: MeshFrameCodec.CourierBody,
        peerAddress: String,
    ) {
        if (!verifySignatureIfPinned(groupId, body.senderId, body.signature, body.signedBytes)) {
            DiagnosticsLog.event("reject", "courier envelope signature failed for a pinned sender")
            Log.w(
                "RelayResponder",
                "courier envelope signature failed verification for a pinned sender — dropping " +
                    "(possible impersonation)"
            )
            return
        }
        val envelope = CourierEnvelopeEntity(
            id = frame.id, groupId = groupId, senderId = body.senderId, tag = frame.tag,
            sealed = frame.sealed, createdAt = frame.createdAt, copiesRemaining = frame.copiesRemaining,
        )
        if (relay.admitCourierEnvelope(envelope)) {
            learnPeerIdentity(peerAddress, body.senderId)
            DiagnosticsLog.event(
                "recv",
                "NEW courier envelope from ${body.senderId.take(SENDER_ID_LOG_CHARS)}"
            )
        }
    }

    /** Blind-carry custody for a courier envelope tagged for a group we hold no key for — a
     *  persisted [CourierEnvelopeEntity] row (`groupId`/`senderId` null), NOT [OpaqueFrameRelay]
     *  custody, per decision 42's own finding that even SOS's blind custody ([takeOpaqueSosCustody]
     *  above) uses [OpaqueFrameRelay]'s default 3-minute max age — the wrong shape for something
     *  meant to survive a real multi-hour partition or an app restart. Single-hop only: this stores
     *  what arrived, but [RelayEngine.relayableCourierEnvelopes] only ever reads own-group rows, so
     *  this row is never proactively re-offered to a further peer — see that function's own doc for
     *  why (nothing bounds further propagation without [CourierEnvelopeEntity.copiesRemaining]
     *  actually meaning something yet, a later P4 slice's job). */
    private suspend fun takeCourierCustody(frame: MeshFrameCodec.Frame.Courier) {
        val envelope = CourierEnvelopeEntity(
            id = frame.id, groupId = null, senderId = null, tag = frame.tag,
            sealed = frame.sealed, createdAt = frame.createdAt, copiesRemaining = frame.copiesRemaining,
        )
        if (relay.admitCourierEnvelope(envelope)) {
            DiagnosticsLog.event("relay", "carrying courier envelope (not a member)")
        }
    }

    /** Call right after [RelayEngine.createSos] succeeds for a message THIS device originated —
     *  see `MeshService.sendSos`. Without this, a freshly-authored message only ever left the
     *  device via `framesToPushOnConnect`'s one-shot push at the START of a connection; now that
     *  P3 keeps links open far past that moment (decision 19), an already-open link at the time of
     *  sending would otherwise never carry it at all until that link eventually drops and a new
     *  one forms. This is what closes that gap: the origin's own copy gets the exact same
     *  immediate flood-forward a received frame gets in [handleSos], just with no arrival peer to
     *  exclude and hop 0 (it's already the origin's own value — see `SosEntity.hop`'s doc). */
    suspend fun floodForwardLocalSos(sos: SosEntity) {
        floodForwardSos(sos, hopsFromOrigin = sos.hop, excludeKey = null)
    }

    /** Forwards [sos] (re-stamped with [hopsFromOrigin] and a degree-clamped TTL) to a fanout
     *  subset of every currently-open link EXCEPT [excludeKey] (null = no exclusion, the local-
     *  origin case), after a degree-scaled jitter — all via the real [ForwardingPolicy], exactly
     *  as gated in the P0a/P1 simulator (`ForwardingPlaneEngine`) before this was trusted with
     *  production wiring. */
    private suspend fun floodForwardSos(sos: SosEntity, hopsFromOrigin: Int, excludeKey: String?) {
        // sealed/handle are null only transiently during construction (see SosEntity.sealed/
        // handle's own docs) — never for anything that reached here, which is always either
        // freshly created (createSos seals+handles before storing) or freshly ingested (handleSos
        // constructs with both set) — hence handle's own unguarded `!!` a few lines below.
        val sealed = sos.sealed ?: return
        val shortId = sos.id.take(SENDER_ID_LOG_CHARS)
        val openLinkCount = connectionRegistry.openLinkCount()
        val forwardedTtl = ForwardingPolicy.forwardedTtl(sos.ttl, openLinkCount)
        if (forwardedTtl <= 0) {
            DiagnosticsLog.event("send", "sos id=$shortId hop=$hopsFromOrigin BLOCKED: ttl exhausted")
            return
        }
        val candidates = connectionRegistry.others(excludeKey).keys.toList()
        if (candidates.isEmpty()) {
            DiagnosticsLog.event("send", "sos id=$shortId hop=$hopsFromOrigin BLOCKED: no open links")
            return
        }
        val targets = ForwardingPolicy.linksToForwardOn(
            candidates, messageIdSeed = sos.id.hashCode().toLong(), openLinkCount = openLinkCount,
        )
        // Decision 37 (docs/DECISIONS.md): forwards the ORIGINAL sealed bytes verbatim, only the
        // envelope's hop/ttl change — same "never re-encrypt a relayed item" reasoning position's
        // own reframePositionForRelay already follows, and for the same dedup-stability reason.
        val outgoing = MeshFrameCodec.reframeSosForRelay(sos.handle!!, sos.id, forwardedTtl, hopsFromOrigin, sealed)
        val jitterMs = ForwardingPolicy.pickJitterMs(openLinkCount)
        delay(jitterMs)
        val liveTargets = connectionRegistry.others(excludeKey)
        var sentCount = 0
        for (peerKey in targets) if (liveTargets[peerKey]?.send(outgoing) == true) sentCount++
        DiagnosticsLog.event(
            "send",
            "sos id=$shortId hop=$hopsFromOrigin sent to $sentCount/${targets.size} target(s), " +
                "jitter=${jitterMs}ms"
        )
    }

    /** Call right after [RelayEngine.requestFullResolution] succeeds (P5 slice 1, docs/DECISIONS.md
     *  decision 45) — same gap [floodForwardLocalSos] closes for a freshly-authored SOS, applied
     *  here to a freshly-issued pull request: without this, a request made while a link to the
     *  holder is ALREADY open (common under P3's long-lived links, decision 19) would sit unsent
     *  until that link happens to reconnect. Unlike [floodForwardSos]'s degree-scaled fanout
     *  subset, this goes to EVERY currently-open link, no jitter, no exclusion — a `SymbolRequest`
     *  is small and cheap, we don't know which specific link (if any) holds the content, and
     *  there's no "already seen this, don't re-flood" concern the way there is for SOS content (a
     *  request is idempotent state, not a one-shot event). No-ops silently if [evidenceId] doesn't
     *  resolve to a member row, or is already complete — mirrors [RelayEngine.requestFullResolution]
     *  's own refusal for a blind-carried item, since there would be nothing meaningful to solicit
     *  either way. */
    suspend fun pushFullResRequestNow(evidenceId: String) {
        val meta = relay.evidenceMeta(evidenceId) ?: return
        if (meta.groupId == null) return
        val stillNeed = relay.symbolDeficit(evidenceId)
        if (stillNeed <= 0) return
        val requestFrame = MeshFrameCodec.encodeSymbolRequest(meta.id, stillNeed)
        val others = connectionRegistry.others(excludePeerKey = null)
        for ((_, push) in others) {
            push.send(requestFrame)
        }
        DiagnosticsLog.event(
            "send",
            "symbol request evid=${evidenceId.take(SENDER_ID_LOG_CHARS)} stillNeed=$stillNeed " +
                "to ${others.size} link(s)"
        )
    }

    /** Decision 38 (docs/DECISIONS.md): [frame] no longer names its group directly — resolves
     *  [frame.handle] to a real (groupId, key) pair first. Unlike SOS/position/presence, a
     *  resolution failure does NOT route to a separate in-memory opaque-custody path: this still
     *  stores a real (if `groupId = null`) [EvidenceEntity] row — but as of P5 slice 1 (decision
     *  45), a `groupId = null` row only ever holds this header (id/hash/size/mimeType/thumbnail).
     *  It is never symbol-relayed: [RelayEngine.fullResRelayable] (what [framesToPushOnConnect]
     *  reads to decide which items to send OUR OWN `SymbolRequest` for) excludes every blind-carried
     *  row by construction — a `SymbolRequest` is the only thing that ever solicits symbols back
     *  (decision 47 replaced the manifest this doc originally described), and a blind carrier never
     *  sends one. This function itself needs no gating of its own for that; the header still floods
     *  to everyone, blind relay included, same as always. */
    private suspend fun handleEvidMeta(frame: MeshFrameCodec.Frame.EvidMeta) {
        val resolved = repo.resolveGroupKeyByHandle(frame.handle)
        if (resolved != null && !evidMetaIsAuthentic(frame, resolved.first, resolved.second)) return
        if (resolved == null) {
            DiagnosticsLog.event("relay", "carrying evidence header for an unresolved group (not a member)")
        }
        val meta = EvidenceEntity(
            id = frame.id, groupId = resolved?.first, senderId = frame.senderId, senderIsMe = false,
            timestamp = frame.timestamp, sha256 = frame.sha256, totalChunks = frame.totalChunks,
            mimeType = frame.mimeType, ttl = frame.ttl, mac = frame.mac, signature = frame.signature,
            handle = frame.handle, thumbnail = frame.thumbnail, contentLength = frame.contentLength,
        )
        // P5 slice 1 (docs/DECISIONS.md decision 45): no longer responds with our own manifest
        // here. A freshly-ingested row's wantsFullRes is always false (EvidenceEntity's own
        // default), so relay.fullResRelayable() would never have included it anyway — this was
        // dead code the moment the gating landed, removed rather than left as an unreachable no-op.
        relay.ingestEvidenceMeta(meta)
    }

    /** Split out purely to keep [handleEvidMeta]'s return count within detekt's limit, same shape
     *  [presenceIsAuthentic] already uses — only called once [handleEvidMeta] has actually resolved
     *  a group for [frame]. */
    private suspend fun evidMetaIsAuthentic(
        frame: MeshFrameCodec.Frame.EvidMeta,
        groupId: String,
        rootKey: ByteArray,
    ): Boolean {
        val macInput = MeshFrameCodec.evidMacInput(
            frame.id, groupId, frame.senderId, frame.timestamp, frame.sha256, frame.totalChunks,
            frame.mimeType, frame.thumbnail, frame.contentLength,
        )
        // Decision 39 (docs/DECISIONS.md): single derivation, not a candidate list — frame.timestamp
        // is already cleartext in the envelope, so the exact epoch is known directly.
        val contentKey = CryptoUtils.contentEpochKey(rootKey, frame.timestamp / MILLIS_PER_SECOND)
        if (!CryptoUtils.constantTimeEquals(CryptoUtils.authTag(contentKey, macInput), frame.mac)) {
            Log.w("RelayResponder", "evidence header failed auth for a group we hold — dropping")
            DiagnosticsLog.event("reject", "evidence header failed auth for a held group")
            return false
        }
        val signatureOk = verifySignatureIfPinned(groupId, frame.senderId, frame.signature, macInput)
        if (!signatureOk) {
            DiagnosticsLog.event("reject", "evidence header signature failed for a pinned sender")
            Log.w(
                "RelayResponder",
                "evidence header signature failed verification for a pinned sender — dropping " +
                    "(possible impersonation)"
            )
        }
        return signatureOk
    }

    private suspend fun handleEvidSymbol(frame: MeshFrameCodec.Frame.EvidSymbol) {
        relay.ingestSymbol(frame.evidenceId, frame.esi, frame.data)
    }

    /** Carries a position for a group we hold no key for — see [OpaquePositionRelay]'s class doc.
     *  Split out of [handlePositionSealed] purely to keep that function's return count within
     *  detekt's limit. */
    private fun takeOpaqueCustody(frame: MeshFrameCodec.Frame.PositionSealed, peerAddress: String) {
        // Dedup on the ciphertext itself: encodePosition gives every frame a unique nonce, so
        // identical sealed bytes mean the identical original frame coming back around a triangle.
        val accepted = opaquePositions.offer(
            dedupKey = OpaqueFrameRelay.dedupKey(frame.sealed),
            hop = frame.hop,
            maxHops = maxPositionRelayHops,
            viaPeer = peerAddress,
        ) { MeshFrameCodec.reframePositionForRelay(frame.handle, frame.hop + 1, frame.sealed) }
        if (accepted) {
            DiagnosticsLog.event("relay", "carrying opaque position hop=${frame.hop} (not a member)")
        }
    }

    /** Presence for a group we hold no key for. Same custody pattern as [takeOpaqueCustody], and the
     *  piece that makes a GPS-less member visible past a non-member relay: they push no position for
     *  the position path to piggyback on, so this is the only thing that carries them outward. */
    private fun takeOpaquePresenceCustody(frame: MeshFrameCodec.Frame.Presence, peerAddress: String) {
        // (handle, senderId, timestamp) identifies one presence heartbeat exactly — the sender
        // stamps a fresh timestamp per connection, and the mac is a pure function of these three
        // (decision 38: handle replaces the old cleartext groupId here, same dedup role).
        val accepted = opaquePresence.offer(
            dedupKey = OpaqueFrameRelay.dedupKey(
                frame.handle, frame.senderId.toByteArray(), frame.timestamp.toString().toByteArray()
            ),
            hop = frame.hop,
            maxHops = maxPositionRelayHops,
            viaPeer = peerAddress,
        ) { MeshFrameCodec.reframePresenceForRelay(frame, frame.hop + 1) }
        if (accepted) {
            DiagnosticsLog.event("relay", "carrying opaque presence hop=${frame.hop} (not a member)")
        }
    }

    /** Decision 38 (docs/DECISIONS.md): [frame] no longer names its group directly — resolves
     *  [frame.handle] to a real (groupId, key) pair first; a resolution failure is what routes to
     *  [takeOpaqueCustody] now, instead of a direct `getGroupKey` miss. */
    private suspend fun handlePositionSealed(frame: MeshFrameCodec.Frame.PositionSealed, peerAddress: String) {
        // No key for this group: we cannot read this position and never will — but we CAN carry it,
        // and until this branch existed we simply dropped it, which is what made a member behind a
        // non-member relay invisible on the radar (see OpaquePositionRelay's class doc). The
        // ciphertext is moved verbatim; only the envelope's hop byte changes.
        val resolved = repo.resolveGroupKeyByHandle(frame.handle)
        if (resolved == null) {
            takeOpaqueCustody(frame, peerAddress)
            return
        }
        val (groupId, rootKey) = resolved
        // Decision 39 (docs/DECISIONS.md): tries each candidate content-epoch key — position's own
        // timestamp lives inside the seal, same reasoning as handleSos's own candidate loop.
        val body = CryptoUtils.candidateContentEpochKeys(rootKey)
            .firstNotNullOfOrNull { MeshFrameCodec.openPosition(frame.sealed, it) } ?: return
        ingestOpenedPosition(groupId, frame, body, peerAddress)
    }

    /** The member path for a position we could actually open. Split from [handlePositionSealed] to
     *  keep both functions' return counts within detekt's limit. [groupId] is the real group
     *  [handlePositionSealed] just resolved from [frame]'s opaque `handle` (decision 38). */
    private suspend fun ingestOpenedPosition(
        groupId: String,
        frame: MeshFrameCodec.Frame.PositionSealed,
        body: MeshFrameCodec.PositionBody,
        peerAddress: String,
    ) {
        if (!verifySignatureIfPinned(groupId, body.senderId, body.signature, body.signedBytes)) {
            DiagnosticsLog.event("reject", "position signature failed for a pinned sender")
            Log.w(
                "RelayResponder",
                "position signature failed verification for a pinned sender — dropping (possible impersonation)"
            )
            return
        }
        if (body.senderId != repo.senderIdFor(groupId)) {
            // Receiving an authenticated position over GATT is itself proof this member is
            // reachable — feed presence from it too (its hop, so a relayed position also extends
            // presence outward), not just from the beacon path.
            // frame.hop (envelope), not body.hop (sealed): the envelope's is the one every relay on
            // the path actually incremented, including relays that couldn't open the body at all.
            // Sourced on body.senderId (stable), not peerAddress — see PeerIdentityResolver's
            // class doc / PLAN-v2.md §1.3 / P0b. Already passed verifySignatureIfPinned above.
            learnPeerIdentity(peerAddress, body.senderId)
            hopTracker.considerNeighborReport(groupId, "PRESENCE", frame.hop, body.senderId)
            if (frame.hop < maxPositionRelayHops) {
                positionTracker.offer(
                    groupId, body.senderId, body.lat, body.lon,
                    body.accuracyM, body.timestampSec, frame.hop,
                    viaPeer = peerAddress, sealed = frame.sealed, handle = frame.handle,
                )
                // hop>0 is a RELAYED position — the single clearest signal that multi-hop actually
                // worked, and the thing the radar's far-phone dot depends on. Never logs the
                // coordinates themselves (see DiagnosticsLog's class doc).
                DiagnosticsLog.event(
                    "recv",
                    "position from ${body.senderId.take(SENDER_ID_LOG_CHARS)} " +
                        "hop=${frame.hop}" + if (frame.hop > 0) " (RELAYED)" else ""
                )
            }
        }
    }

    /** Decision 38 (docs/DECISIONS.md): [frame] no longer names its group directly — resolves
     *  [frame.handle] to a real (groupId, key) pair first; a resolution failure is what routes to
     *  [takeOpaquePresenceCustody] now, instead of a direct `getGroupKey` miss. */
    private suspend fun handlePresence(frame: MeshFrameCodec.Frame.Presence, peerAddress: String) {
        // Replay check FIRST, before any key lookup or MAC verification: the MAC covers the
        // timestamp, so it can't be forward-dated by an attacker, but nothing previously checked
        // the timestamp was recent at all. Anyone who ever captured one valid presence frame could
        // replay it indefinitely with no key needed — it verifies as authentic forever. A group's
        // "1 hop away" reading is exactly the thing this app's core promise ("are my people near
        // me") depends on being live, not a stale capture. See presenceWithinSkew's doc for the
        // tolerance chosen.
        if (!presenceWithinSkew(frame.timestamp, hop = frame.hop)) {
            Log.w("RelayResponder", "presence frame outside skew window — dropping (replay?)")
            DiagnosticsLog.event("reject", "presence frame outside skew window (replay?)")
            return
        }
        // No key: we can't verify this and never will — but we can carry it, which is what makes a
        // GPS-less member reachable past a stranger's phone (see takeOpaquePresenceCustody).
        val resolved = repo.resolveGroupKeyByHandle(frame.handle)
        if (resolved == null) {
            takeOpaquePresenceCustody(frame, peerAddress)
            return
        }
        val (groupId, key) = resolved
        if (!presenceIsAuthentic(frame, groupId, key)) return
        if (frame.senderId != repo.senderIdFor(groupId)) {
            // frame.hop, not a hardcoded 0: a presence that crossed relays (including relays that
            // couldn't verify it) must report the distance it actually travelled, or a member two
            // hops out reads as a direct neighbour. Sourced on frame.senderId (stable), not
            // peerAddress — see PeerIdentityResolver's class doc / PLAN-v2.md §1.3 / P0b. This
            // frame already passed presenceIsAuthentic (group MAC + sender-key pin) above.
            learnPeerIdentity(peerAddress, frame.senderId)
            hopTracker.considerNeighborReport(groupId, "PRESENCE", frame.hop, frame.senderId)
        }
    }

    /** Group-key MAC plus the sender-identity pin/signature checks, in that order. Folded into one
     *  function so [handlePresence] keeps its return count within detekt's limit. [groupId] is the
     *  real group [handlePresence] just resolved from [frame]'s opaque `handle` (decision 38). */
    private suspend fun presenceIsAuthentic(
        frame: MeshFrameCodec.Frame.Presence,
        groupId: String,
        rootKey: ByteArray,
    ): Boolean {
        val macInput = MeshFrameCodec.presenceMacInput(groupId, frame.senderId, frame.timestamp)
        // Decision 39 (docs/DECISIONS.md): single derivation, not a candidate list — frame.timestamp
        // is already cleartext, and presenceWithinSkew (checked before this is ever called) already
        // bounds it to within seconds of "now" anyway.
        val contentKey = CryptoUtils.contentEpochKey(rootKey, frame.timestamp / MILLIS_PER_SECOND)
        val macOk = CryptoUtils.constantTimeEquals(CryptoUtils.authTag(contentKey, macInput), frame.mac)
        return macOk && presencePassesSenderIdentityChecks(frame, groupId, macInput)
    }

    /** The sender-identity pin/signature checks specific to presence, split out of [handlePresence]
     *  purely to keep that function's own return count within detekt's limit — both failures here
     *  have the same effect (hard reject, logged distinctly) as any other group-key check failure.
     *  See [pinOrCheckSenderKey]'s doc for why a CHANGED public key is a hard reject here
     *  specifically (this is the only frame type that carries one to pin), unlike an absent one
     *  elsewhere. [groupId] is the real group [handlePresence] resolved (decision 38). */
    private suspend fun presencePassesSenderIdentityChecks(
        frame: MeshFrameCodec.Frame.Presence,
        groupId: String,
        macInput: ByteArray,
    ): Boolean {
        val pin = pinOrCheckSenderKey(groupId, frame.senderId, frame.senderPublicKey)
        if (pin == SenderKeyPinResult.CHANGED) {
            // Re-pinned, not dropped — see pinOrCheckSenderKey's doc. Logged loudly because the
            // benign explanation (peer reinstalled / Keystore reset) and the hostile one (a member
            // swapped identity) look identical from here, and only the user can tell them apart.
            Log.w(
                "RelayResponder",
                "sender ${frame.senderId.take(SENDER_ID_LOG_CHARS)} presented a NEW public key for " +
                    "group ${groupId.take(SENDER_ID_LOG_CHARS)} — re-pinned (benign after a " +
                    "reinstall; otherwise possible impersonation)"
            )
            DiagnosticsLog.event("identity", "re-pinned key for ${frame.senderId.take(SENDER_ID_LOG_CHARS)}")
            // Deliberately falls through: the frame still had to pass the group-key MAC, and its
            // own signature is checked below against the key we just accepted.
        }
        if (!verifySignatureIfPinned(groupId, frame.senderId, frame.signature, macInput)) {
            Log.w(
                "RelayResponder",
                "presence signature failed verification under the pinned key — dropping (possible impersonation)"
            )
            // CR-19 (PLAN-v2.md Part 10, 2026-08-09) — this used to fire twice for one rejection
            // (once without, once with the sender id), double-counting this event in any log
            // analysis. Kept the version that includes the truncated sender id — more useful for a
            // live logcat/DiagnosticsLog pull than the duplicate that lacked it.
            DiagnosticsLog.event("reject", "presence signature failed for ${frame.senderId.take(SENDER_ID_LOG_CHARS)}")
            return false
        }
        return true
    }

    /** Decision 38 (docs/DECISIONS.md): [frame] no longer names its group directly — resolves
     *  [frame.handle] to a real (groupId, key) pair first. Unlike [handleEvidMeta], a resolution
     *  failure routes to a genuinely NEW opaque-custody path ([takeOpaqueNicknameCustody]) rather
     *  than storing a row with a null groupId — see that function's own doc for why this is a new
     *  capability, not a like-for-like port of the old behavior. */
    private suspend fun handleNickname(frame: MeshFrameCodec.Frame.Nickname, peerAddress: String) {
        val resolved = repo.resolveGroupKeyByHandle(frame.handle)
        if (resolved == null) {
            takeOpaqueNicknameCustody(frame, peerAddress)
            return
        }
        ingestResolvedNickname(resolved.first, resolved.second, frame)
    }

    /** The member path for a nickname whose group we could actually resolve. Split from
     *  [handleNickname] to keep both functions' return counts within detekt's limit — same reason
     *  [ingestOpenedSos] is split from [handleSos]. */
    private suspend fun ingestResolvedNickname(
        groupId: String,
        rootKey: ByteArray,
        frame: MeshFrameCodec.Frame.Nickname,
    ) {
        val macInput = MeshFrameCodec.nicknameMacInput(groupId, frame.senderId, frame.username, frame.updatedAt)
        // Decision 39 (docs/DECISIONS.md): single derivation, not a candidate list —
        // frame.updatedAt is already cleartext, so the exact epoch is known directly.
        val contentKey = CryptoUtils.contentEpochKey(rootKey, frame.updatedAt / MILLIS_PER_SECOND)
        if (!CryptoUtils.constantTimeEquals(CryptoUtils.authTag(contentKey, macInput), frame.mac)) {
            Log.w("RelayResponder", "nickname failed auth for a group we hold — dropping")
            DiagnosticsLog.event("reject", "nickname failed auth for a held group")
            return
        }
        val nickSignatureOk = verifySignatureIfPinned(groupId, frame.senderId, frame.signature, macInput)
        if (!nickSignatureOk) {
            DiagnosticsLog.event("reject", "nickname signature failed for a pinned sender")
            Log.w(
                "RelayResponder",
                "nickname signature failed verification for a pinned sender — dropping (possible impersonation)"
            )
            return
        }
        relay.ingestNickname(
            NicknameEntity(
                groupId, frame.senderId, frame.username, frame.updatedAt, frame.mac, frame.signature, frame.handle
            )
        )
    }

    /** New (decision 38, docs/DECISIONS.md) — before this, nickname had no blind-relay path at all,
     *  but tracing the existing push code showed that gap was already moot in practice: every
     *  nickname push path (`currentCatalogKeys`/`framesToPushOnConnect`/`presenceAndPositionFrames`/
     *  `handleCatalogFilter`) is scoped to `repo.groupDao.getActiveGroups()` only, so a
     *  blind-relay-held row (stored under the old vacuous-auth-pass scheme) was never re-served to
     *  anyone — a dead end, not a working feature. This IS a working feature: an in-memory
     *  `OpaqueFrameRelay` custody, same shape SOS/position/presence already use. No hop field exists
     *  on this wire frame (never did), so `hop`/`maxHops` here are fixed bookkeeping for
     *  `OpaqueFrameRelay`'s own ceiling check only, not a real propagation-depth limit — bounded
     *  instead by `OpaqueFrameRelay`'s own entry-count/age limits, acceptable given nicknames' low
     *  volume/urgency relative to SOS/position. */
    private fun takeOpaqueNicknameCustody(frame: MeshFrameCodec.Frame.Nickname, peerAddress: String) {
        val accepted = opaqueNickname.offer(
            dedupKey = OpaqueFrameRelay.dedupKey(
                frame.handle, frame.senderId.toByteArray(), frame.updatedAt.toString().toByteArray()
            ),
            hop = 0,
            maxHops = RelayEngine.DEFAULT_TTL,
            viaPeer = peerAddress,
        ) { MeshFrameCodec.reframeNicknameForRelay(frame) }
        if (accepted) {
            DiagnosticsLog.event("relay", "carrying opaque nickname (not a member)")
        }
    }

    private suspend fun handleCatalogFilter(
        frame: MeshFrameCodec.Frame.CatalogFilter,
        peerAddress: String,
        respond: suspend (ByteArray) -> Unit,
    ) {
        // The peer just told us (probabilistically) what they already hold — push only what their
        // filter says they're missing. See CatalogFilter's class doc for why a false positive here
        // (skipping something they actually don't have) is safe: it costs a skipped send this
        // connection, not a lost item — the item stays in our own relayable set and gets offered
        // again, against a freshly-salted filter, on the very next reconnect.
        val peerFilter = CatalogFilter.fromBits(frame.bits, frame.seed, frame.sizeBits)

        val nicknames = repo.groupDao.getActiveGroups().flatMap { relay.nicknamesForGroup(it.id) }
        val (sosToPush, sosSkipped) = partitionByFilter(relay.relayableSos(), peerFilter) { "sos:${it.id}" }
        val (evidToPush, evidSkipped) = partitionByFilter(relay.relayableEvidenceMeta(), peerFilter) { "evid:${it.id}" }
        val (nicknamesToPush, nickSkipped) = partitionByFilter(nicknames, peerFilter) {
            "nick:${it.groupId}:${it.senderId}:${it.updatedAt}"
        }
        // RelayEngine.relayableCourierEnvelopes: own-group rows plus blind-carry rows that still
        // have spare copy budget (P4 slice 4, decision 44) — see that function's own doc for how
        // this set grew from slice 3's own-group-only version.
        val (courierToPush, courierSkipped) =
            partitionByFilter(relay.relayableCourierEnvelopes(), peerFilter) { "cour:${it.id}" }
        val filterSkipped = sosSkipped + evidSkipped + nickSkipped + courierSkipped

        // Per-connection cap on how many of these we actually push this connection — mirrors
        // consumeSymbolBudget's role for SymbolRequest pushes (see maxSymbolsPerSession's doc),
        // applied to this different push path. A typical short-lived group's catalog (tens of items)
        // rarely approaches this; the cap exists for the dense-crowd case where it could, so one
        // connection carrying an unusually large deficit can't monopolize the whole session
        // pushing it — anything left over is simply offered again next reconnect, same as a
        // filter-skipped item (see CatalogFilter's own class doc on why that's safe).
        val wantToPush = sosToPush.size + evidToPush.size + nicknamesToPush.size + courierToPush.size
        val allowedToPush = consumeCatalogItemBudget(peerAddress, wantToPush)
        // Decision 37 (docs/DECISIONS.md): forwards each SOS's ORIGINAL sealed bytes verbatim, same
        // "never re-encrypt a relayed item" reasoning as floodForwardSos above. sealed is null only
        // transiently during construction, never for a stored row — see SosEntity.sealed's own doc.
        var pushed = pushUpTo(sosToPush, allowedToPush, ::reframeStoredSos, respond)
        pushed += pushUpTo(evidToPush, allowedToPush - pushed, MeshFrameCodec::encodeEvidMeta, respond)
        pushed += pushUpTo(nicknamesToPush, allowedToPush - pushed, MeshFrameCodec::encodeNickname, respond)
        val (courierPushed, handoverSkipped) =
            pushCouriersWithHandover(courierToPush, allowedToPush - pushed, peerAddress, respond)
        pushed += courierPushed
        // CR-14 (PLAN-v2.md Part 10, 2026-08-09 review pass) — consumeCatalogItemBudget above
        // already reserved wantToPush's full count as "used," including courier items that
        // pushCouriersWithHandover then skips via its own per-item `continue` (rate-limited by
        // courierHandoverTracker, or too few copies left to split) rather than actually pushing.
        // Those reserved-but-unspent slots must be given back, or a peer sending multiple
        // CatalogFilter frames on one connection (a real, supported case) could exhaust the
        // 200-item session budget without a corresponding number of items ever having been
        // delivered. Distinct from the natural "ran out of budget" case (pushCouriersWithHandover's
        // own `if (pushed >= remainingBudget) break`) — THAT count correctly stays consumed, it
        // really is deferred to the next reconnect; only handover-specific skips are refunded here.
        refundCatalogItemBudget(peerAddress, handoverSkipped)
        val budgetSkipped = wantToPush - pushed - handoverSkipped

        // The single most useful line for diagnosing "messaging isn't arriving" from a live
        // logcat pull: confirms the round trip actually ran on this connection and exactly how it
        // resolved, without needing to reproduce anything or add a debugger. peerAddress included
        // since a device can hold several connections in quick succession and this is the only
        // place that ties a decision to which one.
        Log.d(
            "RelayResponder",
            "catalog filter from $peerAddress: pushed $pushed, filter-skipped $filterSkipped" +
                (if (budgetSkipped > 0) ", budget-skipped $budgetSkipped (retries next connection)" else "") +
                (if (handoverSkipped > 0) ", handover-skipped $handoverSkipped (retries next connection)" else "")
        )
        DiagnosticsLog.event(
            "sync",
            "peer ${peerAddress.take(SENDER_ID_LOG_CHARS)}: pushed=$pushed skipped=$filterSkipped " +
                "budget=$budgetSkipped handover=$handoverSkipped"
        )
    }

    /** Splits [items] into what the peer's [peerFilter] says it's missing vs. what it (probably)
     *  already holds — see [handleCatalogFilter]'s doc on why a false-positive "already held" here
     *  is safe (a skipped send, not a lost item). Returns the to-push list and a skip count, since
     *  callers only need the latter for logging. */
    private inline fun <T> partitionByFilter(
        items: List<T>,
        peerFilter: CatalogFilter,
        keyOf: (T) -> String,
    ): Pair<List<T>, Int> {
        val toPush = mutableListOf<T>()
        var skipped = 0
        for (item in items) {
            if (peerFilter.mightContain(keyOf(item))) skipped++ else toPush += item
        }
        return toPush to skipped
    }

    /** Re-frames a stored, already-sealed SOS for push — decision 37 (docs/DECISIONS.md), same
     *  "forward the original ciphertext verbatim" reasoning as [floodForwardSos]. `sealed`/`handle`
     *  are null only transiently during construction, never for a stored row (see
     *  [SosEntity.sealed]/`handle`'s own docs), so `!!` here documents an invariant rather than
     *  papering over a real null case. */
    private fun reframeStoredSos(sos: SosEntity): ByteArray =
        MeshFrameCodec.reframeSosForRelay(sos.handle!!, sos.id, sos.ttl, sos.hop, sos.sealed!!)

    /** Pushes courier envelopes with a real handover, unlike [reframeStoredSos]'s "forward
     *  verbatim" role for SOS — P4 slice 4 (docs/DECISIONS.md decision 44). Not built on the
     *  generic [pushUpTo] helper: that helper's `encode` step is a pure, synchronous mapper, but a
     *  courier handover needs a suspend, side-effecting sequence per item (rate-limit check, split,
     *  persist the local `keep`, THEN encode/respond with `give`) — conflating that into `pushUpTo`
     *  would either lose the persistence step or force every OTHER item category to accept the same
     *  suspend/side-effect shape for no benefit.
     *
     *  [CourierHandover.split] returns `null` for an envelope with fewer than
     *  [CourierHandover.MIN_COPIES_TO_SPLIT] copies left — skipped entirely, not pushed unsplit
     *  (matches [RelayEngine.relayableCourierEnvelopes]' own filtering for the blind-carry case; an
     *  own-group row can still reach here with too few copies if a previous handover already
     *  depleted it, so this check applies uniformly to both tiers). [courierHandoverTracker] gates
     *  a SECOND, independent reason to skip: a rate-limited (envelope, peer) pair is left for a
     *  later reconnect rather than re-split on every connection — see that tracker's own class doc.
     *  `tag`/`sealed` are null only transiently during construction, never for a stored row (see
     *  [CourierEnvelopeEntity.tag]/`sealed`'s own docs), so `!!` here documents an invariant.
     *
     *  **Returns (pushed, handoverSkipped), not a single `Int`** — CR-14 (`PLAN-v2.md` Part 10,
     *  2026-08-09) split the return value specifically so [handleCatalogFilter] can give back the
     *  budget slots this function's own per-item `continue`s reserve-but-never-spend (rate-limited
     *  by [courierHandoverTracker], or too few copies left to split) — distinct from slots the
     *  `if (pushed >= remainingBudget) break` below genuinely consumes on purpose. */
    private suspend fun pushCouriersWithHandover(
        items: List<CourierEnvelopeEntity>,
        remainingBudget: Int,
        peerAddress: String,
        respond: suspend (ByteArray) -> Unit,
    ): Pair<Int, Int> {
        val peerKey = peerIdentity.resolve(peerAddress)
        var pushed = 0
        var handoverSkipped = 0
        for (envelope in items) {
            if (pushed >= remainingBudget) break
            if (!courierHandoverTracker.canAttempt(envelope.id, peerKey)) {
                handoverSkipped++
                continue
            }
            val split = CourierHandover.split(envelope.copiesRemaining)
            if (split == null) {
                handoverSkipped++
                continue
            }
            val (keep, give) = split
            relay.updateCourierCopiesRemaining(envelope.id, keep)
            courierHandoverTracker.recordAttempt(envelope.id, peerKey)
            respond(
                MeshFrameCodec.encodeCourier(envelope.tag!!, envelope.id, envelope.createdAt, give, envelope.sealed!!)
            )
            pushed++
        }
        return pushed to handoverSkipped
    }

    /** Pushes [items] one at a time via [encode]/[respond] until either [items] is exhausted or
     *  [remainingBudget] items have been pushed — the shared shape behind each of
     *  [handleCatalogFilter]'s item categories, each drawing down the same per-connection budget in
     *  sequence. Returns how many were actually pushed. */
    private suspend fun <T> pushUpTo(
        items: List<T>,
        remainingBudget: Int,
        encode: (T) -> ByteArray,
        respond: suspend (ByteArray) -> Unit,
    ): Int {
        var pushed = 0
        for (item in items) {
            if (pushed >= remainingBudget) break
            respond(encode(item))
            pushed++
        }
        return pushed
    }

    /** Replaces the retired `handleManifest` (docs/DECISIONS.md decision 47, PLAN-v2.md §4.3 item 2)
     *  — no positional deficit to compute anymore, [frame.stillNeed] already says exactly how many
     *  more distinct symbols the peer wants. [RelayEngine.symbolsToSend] draws fresh repair symbols
     *  from a shared, item-scoped esi cursor for a complete item, or whatever partial rows this
     *  device itself holds for an incomplete one — see that function's own doc.
     *
     *  **Prefers [peerBulkChannel] (P5 item 3) over GATT's own [respond] when one is open** — the
     *  actual point of the bulk pipe: a `BulkChannel` is a real socket with credit-based flow
     *  control, so the artificial `delay(15)` between pushes that exists purely to pace
     *  [GattOperationQueue] would defeat its whole throughput purpose; only the GATT fallback path
     *  paces itself. A [BulkChannel.send] failure mid-run does NOT fall back to GATT for the
     *  remaining symbols in this same call — the channel is presumed dead (removed here, and
     *  [L2capBulkTransport]'s own receive loop reaches the same conclusion independently on its
     *  side of the same socket), and whatever didn't get sent is simply requested again on the
     *  peer's next `SymbolRequest`, the same "worst case is wasted bandwidth, never incorrectness"
     *  framing [FountainCode]'s own class doc already gives the primitive this rides on.
     *
     *  Wi-Fi Direct's own accelerator subsystem is gone entirely (decision 49, docs/DECISIONS.md,
     *  PLAN-v2.md §4.3 item 3) — this function never had, and now could not have, any equivalent
     *  call into it. */
    private suspend fun handleSymbolRequest(
        frame: MeshFrameCodec.Frame.SymbolRequest,
        peerAddress: String,
        respond: suspend (ByteArray) -> Unit,
    ) {
        val take = consumeSymbolBudget(peerAddress, frame.stillNeed)
        if (take <= 0) return
        val bulk = peerBulkChannel[peerAddress]
        DiagnosticsLog.event(
            "bulk",
            "sending $take symbol(s) to ${peerAddress.take(SENDER_ID_LOG_CHARS)} via " +
                if (bulk != null) "l2cap" else "gatt"
        )
        for (symbol in relay.symbolsToSend(frame.evidenceId, take)) {
            val evidSymbol = MeshFrameCodec.Frame.EvidSymbol(frame.evidenceId, symbol.esi, symbol.data)
            val encoded = MeshFrameCodec.encodeEvidSymbol(evidSymbol)
            if (bulk != null) {
                if (!bulk.send(encoded)) {
                    peerBulkChannel.remove(peerAddress, bulk)
                    DiagnosticsLog.event(
                        "bulk",
                        "l2cap send failed mid-run, dropped channel: ${peerAddress.take(SENDER_ID_LOG_CHARS)}"
                    )
                    return
                }
            } else {
                respond(encoded)
                delay(15)
            }
        }
    }

    /** P5 item 3 (docs/DECISIONS.md's own entry for this slice) — a peer just told us the PSM to
     *  reach their own listening L2CAP socket on. Attempts to open a channel immediately via
     *  [bulkChannelOpener]; the result (or null, if unsupported/unavailable/the connect failed) is
     *  what [handleSymbolRequest] later reads from [peerBulkChannel]. No role restriction — see
     *  [L2capBulkTransport]'s own class doc on why a race here is harmless, unlike the retired WFD
     *  accelerator's initiator/responder split. */
    private suspend fun handleL2capCap(frame: MeshFrameCodec.Frame.L2capCap, peerAddress: String) {
        val opener = bulkChannelOpener ?: return
        val channel = opener(peerAddress, frame.psm)
        val short = peerAddress.take(SENDER_ID_LOG_CHARS)
        if (channel != null) {
            peerBulkChannel[peerAddress] = channel
            DiagnosticsLog.event("bulk", "l2cap channel ready for $short (psm=${frame.psm})")
        } else {
            DiagnosticsLog.event("bulk", "l2cap channel NOT opened for $short (psm=${frame.psm})")
        }
    }

    // handleWifiDirectCap/handleWifiDirectHandoff/handleWifiDirectAccept lived here through
    // v0.7.15-dev — deleted by decision 49 (docs/DECISIONS.md), Wi-Fi Direct's removal.

    /** May call [respond] zero, one, or many times (a `SymbolRequest` can trigger a whole run of
     *  symbol frames) — the caller supplies how a response frame actually reaches the peer. */
    suspend fun handleIncoming(bytes: ByteArray, peerAddress: String, respond: suspend (ByteArray) -> Unit) {
        val frame = MeshFrameCodec.decode(bytes) ?: return
        try {
            when (frame) {
                is MeshFrameCodec.Frame.SosSealed -> handleSos(frame, peerAddress)
                is MeshFrameCodec.Frame.EvidMeta -> handleEvidMeta(frame)
                is MeshFrameCodec.Frame.EvidSymbol -> handleEvidSymbol(frame)
                is MeshFrameCodec.Frame.PositionSealed -> handlePositionSealed(frame, peerAddress)
                is MeshFrameCodec.Frame.Presence -> handlePresence(frame, peerAddress)
                is MeshFrameCodec.Frame.Nickname -> handleNickname(frame, peerAddress)
                is MeshFrameCodec.Frame.CatalogFilter -> handleCatalogFilter(frame, peerAddress, respond)
                is MeshFrameCodec.Frame.SymbolRequest -> handleSymbolRequest(frame, peerAddress, respond)
                is MeshFrameCodec.Frame.L2capCap -> handleL2capCap(frame, peerAddress)
                is MeshFrameCodec.Frame.Courier -> handleCourier(frame, peerAddress)
            }
        } catch (e: Exception) {
            Log.w("RelayResponder", "frame handling failed: ${e.message}")
            DiagnosticsLog.event(
                "error",
                "frame handling threw (${frame::class.simpleName}): ${e::class.simpleName} ${e.message}"
            )
        }
    }

    companion object {
        // How much of a sender/group id may appear in a log line — enough to tell peers apart
        // while never writing a full identifier to disk (see DiagnosticsLog's class doc).
        internal const val SENDER_ID_LOG_CHARS = 8

        /** millis-to-epoch-seconds conversion, for [CryptoUtils.contentEpochKey]'s callers here
         *  (decision 39, `docs/DECISIONS.md`). */
        private const val MILLIS_PER_SECOND = 1000L

        private const val MAX_CATALOG_ITEMS_PER_SESSION = 200

        /** Per-connection cap on blind-carried frames, per store. Deliberately close to
         *  MAX_RELAYED_POSITIONS_PER_GROUP (12): carrying a stranger's group should cost about what
         *  serving one of our own does, not 16x more. OpaqueFrameRelay rotates its window, so a
         *  full 200-entry store still drains completely over successive connections. */
        private const val MAX_OPAQUE_FRAMES_PER_SESSION = 16

        // See defaultMaxFrameBytes' doc — matches the MTU MeshGattClient always requests on
        // connect, i.e. "assume negotiation succeeds" rather than a conservative pre-negotiation
        // floor (MeshProtocol.DEFAULT_ATT_MTU, which is what a connection actually has until/
        // unless negotiation completes).
        private const val ASSUMED_NEGOTIATED_MTU = 517

        // Consistent with the clock agreement the rotating-ID scheme already assumes
        // (CryptoUtils.ID_WINDOW_SECONDS = 60s, tolerating +/-1 adjacent window) — two minutes is
        // generous headroom above that for a heartbeat that's meant to be "you're reachable right
        // now," not "you were reachable at some point."
        private const val PRESENCE_MAX_SKEW_MS = 120_000L

        // Per-hop slack on top of the skew window, mirroring HopTracker/PositionTracker's own
        // per-hop model (same 45s reconnect-cooldown unit). Without it, the flat 120s gate applied
        // to a RELAYED heartbeat too — but each relay hop costs at least one full reconnect cycle
        // (~45s) because presence only moves in framesToPushOnConnect, never mid-connection. So a
        // 2-hop presence needed ~90-135s to arrive and was then rejected as a replay, which
        // silently defeated the whole blind-presence-relay path. Replay protection at hop 0 is
        // unchanged at 120s; a relayed heartbeat gets exactly the budget its path actually costs.
        private const val PRESENCE_PER_HOP_SLACK_MS = 45_000L

        // CR-12 (PLAN-v2.md Part 10, 2026-08-09 review pass) — same fix, same reasoning as
        // PositionTracker.MAX_SLACK_HOPS: [hop] here is the SAME cleartext, unauthenticated
        // envelope field ([Frame.Presence.hop]) a blind relay increments with no group key. Before
        // this cap, the very replay protection [presenceWithinSkew]'s own doc describes adding was
        // substantially defeated by that same field — capture one valid presence frame, replay it
        // with hop rewritten to maxPositionRelayHops-1 (119), and the skew window widens from the
        // intended ~2 minutes to ~90 minutes. Kept in sync by doc only with PositionTracker's own
        // MAX_SLACK_HOPS (identical value, identical reasoning — this file has no dependency on
        // that one to hang a shared constant off, same precedent PER_HOP_SLACK_MS/PER_HOP_SLACK_MS
        // already set for the analogous HopTracker/PositionTracker pair).
        private const val MAX_SLACK_HOPS = 6

        /** Pure — no [GroupRepository]/key access, deliberately, so this can be checked (and unit-
         *  tested) before ever touching the group key. See the `Frame.Presence` case above for why
         *  the ordering matters: the MAC already covers [timestamp], so an attacker can't forge a
         *  fresher one, but nothing previously verified the timestamp was recent at all — a replay
         *  of one captured frame verified as authentic forever. `internal` so it's directly
         *  unit-testable without a Robolectric `Context` (this class's other tests need one only
         *  because [RelayEngine]/[GroupRepository] do; this function needs neither). [hop]'s slack
         *  contribution is capped at [MAX_SLACK_HOPS] (CR-12, `PLAN-v2.md` Part 10) — see that
         *  constant's own doc for why the window no longer scales all the way to a real (and
         *  attacker-influenceable) hop value. */
        internal fun presenceWithinSkew(
            timestamp: Long,
            now: Long = System.currentTimeMillis(),
            hop: Int = 0,
        ): Boolean =
            kotlin.math.abs(now - timestamp) <=
                PRESENCE_MAX_SKEW_MS + hop.coerceIn(0, MAX_SLACK_HOPS) * PRESENCE_PER_HOP_SLACK_MS

        /** Pure decision behind [pinOrCheckSenderKey] — given whatever's already pinned
         *  for a (groupId, senderId) and the key this frame just carried, decide OK vs. MISMATCH.
         *  `internal`, no DAO/repo access, so directly unit-testable (this class's other tests need
         *  a `Context`/Robolectric only because [RelayEngine]/[GroupRepository] do; this function
         *  needs neither — see [RelayResponderTest]'s class doc for that constraint, which doesn't
         *  apply here).
         *
         *  [incomingPublicKey] null (a peer not yet carrying one, or intentionally omitted after
         *  the first heartbeat — see [MeshFrameCodec.encodePresence]'s doc) is always tolerated:
         *  nothing to check, so [SenderKeyPinResult.OK]. [existingPublicKey] null means first
         *  sight — also OK (the caller persists the new pin). Both present is the real check: equal
         *  is OK, different is [SenderKeyPinResult.MISMATCH] — a legitimate sender's identity never
         *  changes mid-group by design (see [org.offlinemesh.app.data.GroupRepository.
         *  ensureSenderIdentity]'s doc for why), so a changed key is either device-swap-without-
         *  rejoin (out of scope) or impersonation, and this app's threat model treats both the same
         *  way: don't silently trust the new key. */
        internal fun checkSenderKeyPin(
            existingPublicKey: ByteArray?,
            incomingPublicKey: ByteArray?,
        ): SenderKeyPinResult {
            if (incomingPublicKey == null) return SenderKeyPinResult.UNCHANGED // nothing offered, nothing to do
            if (existingPublicKey == null) return SenderKeyPinResult.FIRST_SIGHT
            return if (existingPublicKey.contentEquals(incomingPublicKey)) {
                SenderKeyPinResult.UNCHANGED
            } else {
                SenderKeyPinResult.CHANGED
            }
        }

        /** Pure decision behind [verifySignatureIfPinned] — `internal`, no DAO access,
         *  directly unit-testable (see [checkSenderKeyPin]'s doc for why that matters here).
         *  [signature] null is tolerated (a peer not yet signing, or on a build that predates this
         *  feature) — this, combined with [pinnedPublicKey] null also being tolerated (nothing
         *  pinned yet to check against), is what makes enforcement "mandatory once pinned, tolerant
         *  before" per-SENDER rather than a single global rollout flag: once a key IS pinned for a
         *  given sender, a present signature that fails to verify under it is never tolerated. */
        internal fun signatureCheckPasses(
            pinnedPublicKey: ByteArray?,
            signature: ByteArray?,
            signedData: ByteArray,
        ): Boolean {
            if (signature == null || pinnedPublicKey == null) return true
            return SenderIdentity.verify(pinnedPublicKey, signature, signedData)
        }

        // See selectPositionsToRelay's doc for what this bounds and why.
        private const val MAX_RELAYED_POSITIONS_PER_GROUP = 12

        /** Which of [positions] (a group's currently-known member positions) get relayed to a peer
         *  on this connection, and in what order: the nearest (fewest-hop)
         *  [MAX_RELAYED_POSITIONS_PER_GROUP], excluding [selfId] (pushed separately, always) and
         *  anything whose relay hop would reach or exceed [maxHops].
         *
         *  **Why this cap exists (deliberately scoped small).** Position frames only refresh
         *  on a GATT reconnect — there's no separate lightweight position channel — so every
         *  reconnect currently re-pushes one position frame per *other* known member in the group,
         *  uncapped. That means per-connection cost scales with total group size, not with
         *  anything bounded, which is the real reason `syncedReconnectCooldownMs` had to be
         *  neutralized back to the ordinary cooldown (see `docs/DECISIONS.md`, decision 5) instead
         *  of being tuned longer: a longer cooldown reduces reconnect *frequency*, not the
         *  per-reconnect *cost*, so it couldn't fix this on its own. Capping to the nearest members
         *  by hop is the smallest change that bounds the cost without a redesign — the larger idea
         *  (a lightweight position channel independent of reconnects, or positions carried in the
         *  beacon itself) is a real, separate design question, deliberately not attempted here.
         *
         *  `internal`, no key/repo access — pure selection logic, directly unit-testable despite
         *  [positionFramesToPush] itself needing a real group key (Keystore, unavailable under
         *  Robolectric — see [RelayResponderTest]'s class doc for the same constraint). */
        internal fun selectPositionsToRelay(
            positions: Map<String, PositionTracker.Record>,
            selfId: String,
            maxHops: Int,
            toPeer: String? = null,
            limit: Int = MAX_RELAYED_POSITIONS_PER_GROUP,
        ): List<Pair<String, PositionTracker.Record>> =
            positions.entries
                .asSequence()
                .filter { (senderId, record) ->
                    // Split horizon: never advertise a route back toward whoever taught it to us.
                    // The textbook distance-vector loop guard, and needed here for exactly the
                    // textbook reason — see PositionTracker.Record.viaPeer's doc for the measured
                    // loop it eliminates. A null viaPeer is our own fix, always relayable.
                    val learnedFromThisPeer = toPeer != null && record.viaPeer == toPeer
                    senderId != selfId && record.hop + 1 < maxHops && !learnedFromThisPeer
                }
                .map { it.key to it.value }
                .sortedBy { it.second.hop }
                .take(limit)
                .toList()
    }
}
