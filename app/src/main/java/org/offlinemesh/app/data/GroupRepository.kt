package org.offlinemesh.app.data

import android.content.Context
import org.offlinemesh.app.crypto.CryptoUtils
import org.offlinemesh.app.crypto.SenderIdentity
import java.util.UUID

@Suppress("TooManyFunctions") // one resolver per relayed frame type sharing a group-scoped key store
class GroupRepository(context: Context) {
    private val db = AppDatabase.get(context)
    // Lazy, not eager: building this touches the Android Keystore (via GroupKeyStore's
    // EncryptedSharedPreferences/MasterKey), real work that's wasted if a GroupRepository is ever
    // constructed without actually reading/writing a key this session — and, found while testing
    // WifiDirectHandoffCoordinator, the Keystore provider isn't available under Robolectric at
    // all (NoSuchAlgorithmException), so eager construction made GroupRepository impossible to
    // construct in that test environment even though none of its exercised code paths ever touch
    // key storage.
    private val keyStore by lazy { GroupKeyStore(context) }
    val groupDao = db.groupDao()
    // Public, not private, matching peerKeyDao below — BeaconRadio's Tier B SOS content broadcast
    // (decision 29, docs/DECISIONS.md) needs a direct held-SOS-by-id lookup the same way it already
    // uses peerKeyDao directly for position ingestion (decision 27), rather than growing this
    // repository with a one-off wrapper per cross-class DAO need.
    val sosDao = db.sosDao()
    private val evidenceDao = db.evidenceDao()
    private val evidenceSymbolDao = db.evidenceSymbolDao()
    private val nicknameDao = db.nicknameDao()
    val peerKeyDao = db.peerKeyDao()
    // CR-2 (PLAN-v2.md Part 10, 2026-08-09 review pass) — dismantleGroup needs this to actually
    // delete a group's own courier envelopes; see that function's own doc for the gap this closes
    // (found: a dismantled group's courier rows kept a non-null groupId and kept being relayed for
    // up to COURIER_MAX_AGE_MILLIS after the user believed the group was destroyed).
    private val courierEnvelopeDao = db.courierEnvelopeDao()

    /** deviceId identifies this phone within groups; random per-install, never tied to real identity. */
    val deviceId: String by lazy {
        val prefs = context.getSharedPreferences("mesh_device", Context.MODE_PRIVATE)
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    /** Creates a brand-new group with a random id+key, expiring at [lifetimeMillis] from now
     *  (coerced to [JoinCode.MAX_LIFETIME_MILLIS] — see that constant's doc). Returns the
     *  shareable code for it. */
    suspend fun createGroup(
        name: String,
        lifetimeMillis: Long = JoinCode.DEFAULT_LIFETIME_MILLIS,
    ): Pair<GroupEntity, String> {
        val parsed = JoinCode.generate(name, lifetimeMillis)
        keyStore.putKey(parsed.groupId, parsed.key)
        ensureSenderIdentity(parsed.groupId)
        val group = GroupEntity(
            id = parsed.groupId, name = name, createdAt = System.currentTimeMillis(),
            expiresAt = parsed.expiresAtEpochSec * 1000
        )
        groupDao.insert(group)
        return group to JoinCode.encode(parsed)
    }

    /** Joins a group from someone else's shared code (or a mesh2007://join?c=... link). Null if
     *  malformed OR already expired ([JoinCode.decode] rejects both). The joiner's local
     *  `expiresAt` is taken directly from the code — see [JoinCode]'s class doc for why this must
     *  be the same absolute moment for every member, not independently computed per-join. */
    suspend fun joinGroup(rawCode: String): GroupEntity? {
        val parsed = JoinCode.decode(JoinCode.extractCode(rawCode)) ?: return null
        keyStore.putKey(parsed.groupId, parsed.key)
        ensureSenderIdentity(parsed.groupId)
        val group = GroupEntity(
            id = parsed.groupId, name = parsed.name, createdAt = System.currentTimeMillis(),
            expiresAt = parsed.expiresAtEpochSec * 1000
        )
        groupDao.insert(group)
        return group
    }

    fun getGroupKey(groupId: String): ByteArray? = keyStore.getKey(groupId)

    /** Decision 38 (docs/DECISIONS.md): the receive-side counterpart to
     *  [org.offlinemesh.app.ble.MeshFrameCodec.groupHandle] — resolves an opaque GATT wire handle
     *  back to the (groupId, key) pair it was computed from, since frames no longer carry `groupId`
     *  in the clear. Modeled directly on [org.offlinemesh.app.ble.BeaconRadio.refreshCaches]'s
     *  exact shape (iterate every active group's key, compute candidate handles, match) — that
     *  function solves the identical problem for the beacon's own 60s-window rotating id.
     *
     *  Scoped to [GroupDao.getActiveGroups] (not `allGroupIds`), matching [getGroupKey]'s own
     *  scope: an expired group's key is already gone via [dismantleGroup], so there'd be nothing to
     *  resolve to anyway. Deliberately NOT cached (unlike [org.offlinemesh.app.ble.BeaconRadio]'s
     *  `matchTable`) — that cache exists because scan-result callbacks are a genuinely hot path in
     *  a dense crowd (PLAN-v2.md §5.4/§9.2); GATT frame receipt is bounded by open-connection count
     *  × per-connection frame cadence, several orders of magnitude cooler, and this app's own group
     *  counts are small (a few groups, 3-8 members each — PLAN-v2.md §5.5). A cache here would add
     *  join/leave/expiry invalidation complexity for no measurable win at this app's scale. */
    suspend fun resolveGroupKeyByHandle(
        handle: ByteArray,
        epochSeconds: Long = System.currentTimeMillis() / MILLIS_PER_SECOND,
    ): Pair<String, ByteArray>? {
        val groups = groupDao.getActiveGroups().mapNotNull { g -> getGroupKey(g.id)?.let { g.id to it } }
        return matchHandle(handle, groups, epochSeconds)
    }

    /** P4 slice 3 (`docs/DECISIONS.md` decision 43, `PLAN-v2.md` §4.2) — resolves a courier
     *  envelope's opaque [tag] to a real group, mirroring [resolveGroupKeyByHandle] exactly. Calls
     *  [CryptoUtils] directly, not `MeshFrameCodec.candidateCourierTags`'s convenience wrapper —
     *  matching [matchHandle]'s own precedent of not introducing a `data` -> `ble` dependency this
     *  class has never had. */
    suspend fun resolveGroupKeyByCourierTag(
        tag: ByteArray,
        epochSeconds: Long = System.currentTimeMillis() / MILLIS_PER_SECOND,
    ): Pair<String, ByteArray>? {
        val groups = groupDao.getActiveGroups().mapNotNull { g -> getGroupKey(g.id)?.let { g.id to it } }
        return matchCourierTag(tag, groups, epochSeconds)
    }

    companion object {
        /** millis-to-epoch-seconds conversion, for [resolveGroupKeyByHandle]'s default param. */
        private const val MILLIS_PER_SECOND = 1000L

        /** [senderIdFor]'s truncation length — 16 hex chars = 64 bits, comfortably collision-
         *  resistant at this app's realistic group sizes (hundreds, not millions of members). */
        private const val SENDER_ID_HEX_CHARS = 16

        /** Pure matching core of [resolveGroupKeyByHandle] — no DAO/Keystore access, so directly
         *  unit-testable despite this class's real-Keystore construction constraint under
         *  Robolectric (see [keyStore]'s own doc). `.contentEquals()`, not
         *  [CryptoUtils.constantTimeEquals] — a handle isn't secret once it's on the wire (every
         *  relay, member or not, already sees it), so there's no timing-attack surface to defend
         *  here, unlike comparing an actual auth tag. */
        internal fun matchHandle(
            handle: ByteArray,
            groups: List<Pair<String, ByteArray>>,
            epochSeconds: Long,
        ): Pair<String, ByteArray>? {
            for ((groupId, key) in groups) {
                val candidates = CryptoUtils.candidateAdvertisementIds(
                    key, epochSeconds, CryptoUtils.GATT_GROUP_HANDLE_WINDOW_SECONDS
                )
                if (candidates.any { it.contentEquals(handle) }) return groupId to key
            }
            return null
        }

        /** Pure matching core of [resolveGroupKeyByCourierTag], mirroring [matchHandle] exactly —
         *  same no-DAO/no-Keystore, directly-unit-testable shape. Passes
         *  [CryptoUtils.COURIER_TAG_LEN] explicitly (16) — the one real gotcha copying [matchHandle]
         *  verbatim would introduce: [CryptoUtils.candidateAdvertisementIds]' own `truncateLen`
         *  defaults to [CryptoUtils.ROTATING_ID_LEN] (6, the beacon/GATT-handle length), which would
         *  never match a real 16-byte courier tag if silently dropped here. */
        internal fun matchCourierTag(
            tag: ByteArray,
            groups: List<Pair<String, ByteArray>>,
            epochSeconds: Long,
        ): Pair<String, ByteArray>? {
            for ((groupId, key) in groups) {
                val candidates = CryptoUtils.candidateAdvertisementIds(
                    key, epochSeconds, CryptoUtils.COURIER_TAG_WINDOW_SECONDS, CryptoUtils.COURIER_TAG_LEN,
                )
                if (candidates.any { it.contentEquals(tag) }) return groupId to key
            }
            return null
        }
    }

    /** Generates this device's Ed25519 sender-identity keypair for [groupId] — once. A join code
     *  can be re-scanned for a group already joined (`groupDao.insert` uses `REPLACE`), and
     *  regenerating the keypair on every call would silently change what every peer who already
     *  pinned our old public key (see [PeerKeyEntity]) considers "our" identity — indistinguishable
     *  from impersonation on their end, since [org.offlinemesh.app.ble.RelayResponder]'s
     *  pin-on-first-sight verification hard-rejects a changed key by design. Only ever generates
     *  fresh when nothing is stored yet for this exact groupId. */
    private fun ensureSenderIdentity(groupId: String) {
        if (keyStore.getSigningKeyPair(groupId) == null) {
            keyStore.putSigningKeyPair(groupId, SenderIdentity.generateKeyPair())
        }
    }

    /** This device's own per-group Ed25519 keypair (see [SenderIdentity]'s class doc for why
     *  per-group, not per-device) — used to sign authored content ([org.offlinemesh.app.ble.
     *  RelayEngine]'s `createSos`/`createEvidence`/`setNickname`) and to attach our public key to
     *  the outgoing presence heartbeat ([org.offlinemesh.app.ble.RelayResponder.
     *  framesToPushOnConnect]). Null only if [groupId] was never actually joined/created through
     *  this repository (every real join/create path establishes one via [ensureSenderIdentity]). */
    fun getSenderKeyPair(groupId: String): SenderIdentity.Ed25519KeyPair? = keyStore.getSigningKeyPair(groupId)

    /** The per-group identity string carried on the wire as `senderId` — hop-tracking, presence/
     *  position envelopes, nickname keying, display. Derived from [getSenderKeyPair]'s own public
     *  key (`sha256Hex(publicKey).take(SENDER_ID_HEX_CHARS)`), NOT [deviceId] — [deviceId] is a
     *  single random id shared across every group this device is in, and `senderId` used to be
     *  exactly that (found this session: any member of two overlapping groups could correlate a
     *  device across them, and since `senderId` travels in cleartext on presence/position
     *  broadcasts, so could any passive non-member listener — see `PLAN-v2.md`'s P0b-correction
     *  write-up and `docs/DECISIONS.md` decision 53/54). No new key material: purely a computed
     *  value over what [ensureSenderIdentity] already generates and persists per (device, group).
     *  `require`s a keypair already exists — every real join/create path calls
     *  [ensureSenderIdentity] first, so a missing keypair here means [groupId] was never actually
     *  joined through this repository, a caller bug worth surfacing loudly rather than silently
     *  falling back to something wrong. */
    fun senderIdFor(groupId: String): String {
        val publicKey = requireNotNull(getSenderKeyPair(groupId)) {
            "no sender identity for group $groupId — was it ever joined/created through this repository?"
        }.publicKey
        return CryptoUtils.sha256Hex(publicKey).take(SENDER_ID_HEX_CHARS)
    }

    /**
     * Reconstructs the exact same invite code any member could show — there's no "owner" role
     * in this design. Whoever joined has the full (id, key, name) stored locally already, so
     * every member can invite new people just as well as whoever originally created it. This is
     * what lets a group outlive its creator deleting their own copy or going offline for good.
     *
     * Passes the group's ALREADY-STORED `expiresAt` through unchanged — never recomputes a fresh
     * one — so a code reconstructed partway through a group's life still carries the exact same
     * expiry every existing member already agreed on, not a new one measured from this moment.
     */
    suspend fun getShareCode(groupId: String): String? {
        val group = groupDao.getGroup(groupId) ?: return null
        val key = keyStore.getKey(groupId) ?: return null
        return JoinCode.encode(JoinCode.Parsed(groupId, key, group.name, expiresAtEpochSec = group.expiresAt / 1000))
    }

    /** Actually deletes the group and everything relayed for it — not just hides it. */
    suspend fun dismantleGroup(groupId: String) {
        for (evidenceId in evidenceDao.idsForGroup(groupId)) {
            evidenceSymbolDao.deleteForEvidence(evidenceId)
        }
        evidenceDao.deleteForGroup(groupId)
        sosDao.deleteForGroup(groupId)
        nicknameDao.deleteForGroup(groupId)
        peerKeyDao.deleteForGroup(groupId)
        // CR-2 (PLAN-v2.md Part 10) — was missing entirely; a dismantled group's own courier
        // envelopes (groupId still set) survived here and kept being offered to peers via
        // RelayEngine.relayableCourierEnvelopes for up to the full 24h COURIER_MAX_AGE_MILLIS.
        courierEnvelopeDao.deleteForGroup(groupId)
        groupDao.delete(groupId)
        keyStore.removeKey(groupId)
        keyStore.removeSigningKeyPair(groupId)
    }

    /** Dismantles every group whose baked-in expiry has passed — the actual enforcement behind
     *  the "groups are ephemeral" promise (see [JoinCode]'s class doc). Called periodically from
     *  [org.offlinemesh.app.ble.MeshService.startPruning] and once on service startup, so a phone
     *  that was off past a group's expiry cleans it up on next launch rather than waiting for the
     *  next scheduled sweep. Reuses [dismantleGroup] for the actual deletion — evidence/chunks/
     *  SOS/nicknames/key, all of it, not just the group row. */
    suspend fun expireGroups(now: Long = System.currentTimeMillis()) {
        for (groupId in groupDao.expiredGroupIds(now)) {
            dismantleGroup(groupId)
        }
    }

    /** Deletes any stored group key with no matching row left in the `groups` table — a real, if
     *  narrow, leak: `EncryptedSharedPreferences` (where keys live, see [GroupKeyStore]) is a
     *  separate store from Room, so a destructive schema migration
     *  (`AppDatabase`'s `fallbackToDestructiveMigration`, used for every schema bump so far) wipes
     *  every group row but leaves old keys behind with nothing left to use them. Called once on
     *  [org.offlinemesh.app.ble.MeshService] startup — cheap (one Room query, one prefs read) and
     *  not worth a periodic re-check since new orphans can only appear via a migration, which only
     *  happens across an app update, i.e. already a fresh process start. */
    suspend fun sweepOrphanKeys() {
        val liveIds = groupDao.allGroupIds().toSet()
        for (groupId in keyStore.allGroupIds()) {
            if (groupId !in liveIds) {
                keyStore.removeKey(groupId)
                // CR-18 (PLAN-v2.md Part 10, 2026-08-09) — removeKey alone left this group's
                // Ed25519 signing keypair (private key material) behind forever; dismantleGroup
                // already removes both together, this sweep was the one place that didn't.
                keyStore.removeSigningKeyPair(groupId)
            }
        }
        // Separate pass, not folded into the loop above: covers a signing keypair with no matching
        // symmetric-key entry at all (e.g. one this same sweep itself left behind before the fix
        // above existed) — GroupKeyStore.allGroupIds() only ever enumerates the symmetric-key
        // namespace, so that entry would never be visited otherwise.
        for (groupId in keyStore.signingKeyGroupIds()) {
            if (groupId !in liveIds) keyStore.removeSigningKeyPair(groupId)
        }
    }
}
