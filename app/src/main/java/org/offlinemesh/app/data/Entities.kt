package org.offlinemesh.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** [expiresAt] is epoch millis, matching [createdAt]'s unit — the DB entity has no wire-size
 *  constraint the way [JoinCode]'s own compact epoch-seconds field does, so full millis precision
 *  costs nothing here. Always derived from [JoinCode.Parsed.expiresAtEpochSec] (never computed
 *  independently), so every member of a group agrees on the exact same expiry regardless of when
 *  each one joined — see [JoinCode]'s class doc. */
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val expiresAt: Long
)

/** Dedup cache: any packet id (sos id, evidence id, chunk composite id) we've already processed. */
@Entity(tableName = "seen_messages")
data class SeenMessageEntity(
    @PrimaryKey val id: String,
    val seenAt: Long
)

// The four entities below all carry a ByteArray field (`mac`, or `data` for chunks). A data
// class's auto-generated equals/hashCode compares ByteArray fields by reference, not content —
// harmless for Room itself (it maps rows by column reflection, never by equals), but every one of
// these flows through Compose (GroupChatScreen/NavigateScreen's remember(...) keys, LazyColumn's
// item diffing), where equals() genuinely matters: two rows with byte-for-byte identical bytes but
// a freshly-allocated array instance (the normal case — Room reconstructs entities from the cursor
// on every query) would otherwise compare as "different," triggering unnecessary recomposition on
// every Flow re-emission even when nothing actually changed.
//
// Each override compares the non-ByteArray fields as one `List<Any?>` (Kotlin's `List.equals`/
// `hashCode` are structural, so this is one clean comparison instead of a long `&&` chain some
// static analysis flags as overly complex) and the ByteArray field separately via `contentEquals`/
// `contentHashCode`.

@Entity(tableName = "sos_events")
data class SosEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val senderId: String,
    val senderIsMe: Boolean,
    val message: String,
    val timestamp: Long,
    val ttl: Int,
    // Distance from the origin in relay hops, incremented by exactly +1 on every ingest
    // (RelayEngine.ingestSos) — deliberately independent of [ttl], which a busy relay may drop by
    // MORE than 1 in one hop (PLAN-v2.md P1's degree-based flood-control clamp,
    // ForwardingPolicy.forwardedTtl). Mirrors the cleartext-envelope hop field positions already
    // carry (see MeshFrameCodec.Frame.PositionSealed.hop, added v0.4.0/decision 8) — HopTracker
    // must never derive a hop count from ttl consumed again; see docs/DECISIONS.md decision 16.
    val hop: Int = 0,
    // Decision 35 (docs/DECISIONS.md): every message in this app is a SosEntity — there was never a
    // separate "casual chat" type — but until now every single one also triggered the loud, alarm-
    // style notification and the Tier B broadcast-tier hop-gradient/content-preview treatment,
    // which only makes sense for a genuine emergency. This is the ONE new field that splits the
    // two: false (default — the normal "Send" action) is a quiet message, relayed and catalog-
    // filter-synced exactly like today but with none of the alert-only side effects; true (a
    // dedicated SOS action) gets all of them, same as every SosEntity did before this decision.
    // Sealed inside the AES-GCM body (decision 37) so a relay can't flip it undetected in either
    // direction — silencing a real emergency or manufacturing a false alarm.
    val isAlert: Boolean = false,
    // Decision 37 (docs/DECISIONS.md): [message]/[senderId]/[timestamp]/[isAlert] used to travel in
    // the wire frame as cleartext plus a separate HMAC (`mac`) and an optional Ed25519 [signature] —
    // any nearby non-member relay could read the message text directly. Replaced with an AES-GCM
    // seal under the group key, mirroring [org.offlinemesh.app.ble.PositionTracker.Record.sealed]'s
    // exact shape and reasoning: this is the ORIGINAL sealed bytes (our own fresh seal at authorship,
    // or exactly what we received when relaying someone else's), kept so relaying forwards them
    // verbatim instead of re-encrypting — re-encrypting would mint a fresh ciphertext every hop,
    // breaking downstream blind-relay dedup the same way it would have for position (see that
    // field's own doc). The GCM tag baked into this IS the authentication (replacing the old
    // separate `mac`); an optional Ed25519 signature travels INSIDE the seal (replacing the old
    // separate `signature` column), verified once at ingest and not re-checked afterward — same as
    // `PositionBody.signature`'s handling, never persisted as its own field once verified. Null only
    // transiently during construction before a fresh seal is computed; every stored row has one.
    val sealed: ByteArray? = null,
    // Decision 38 (docs/DECISIONS.md): the opaque GATT wire handle — HMAC(group_key, epoch), see
    // MeshFrameCodec.groupHandle's doc — this SOS was framed under, replacing the old cleartext
    // `groupId` wire field. Computed ONCE (RelayEngine.createSos for our own, or copied verbatim
    // from the arriving frame when ingesting someone else's) and forwarded unchanged on every
    // relay, same discipline as [sealed]: a blind relay has no key to recompute it with, so it must
    // stay stable across the whole relay life of this row. Null only transiently during
    // construction; every stored row has one.
    val handle: ByteArray? = null,
) {
    private fun scalars() = listOf(id, groupId, senderId, senderIsMe, message, timestamp, ttl, hop, isAlert)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SosEntity) return false
        return scalars() == other.scalars() &&
            (sealed?.contentEquals(other.sealed) ?: (other.sealed == null)) &&
            (handle?.contentEquals(other.handle) ?: (other.handle == null))
    }

    override fun hashCode(): Int =
        31 * (31 * scalars().hashCode() + (sealed?.contentHashCode() ?: 0)) + (handle?.contentHashCode() ?: 0)
}

/** P4 slice 2 (docs/DECISIONS.md decision 41's own follow-up, PLAN-v2.md §4.2) — a courier envelope:
 *  group-addressed, opportunistic store-and-carry delivery for a partition flood-relay + this app's
 *  own 48h content retention can't bridge in time. Genuinely new persisted storage in this codebase,
 *  not a reuse of [org.offlinemesh.app.ble.OpaqueFrameRelay]'s shape — that class is in-memory only
 *  with a 3-minute default max age (position/presence's own blind-relay custody, and SOS's, all use
 *  it as-is), which can't survive a real multi-hour partition crossing or an app restart the way a
 *  24h-TTL envelope must (see [org.offlinemesh.app.ble.RelayEngine.COURIER_MAX_AGE_MILLIS]).
 *
 *  [groupId]/[senderId] are nullable from the start, mirroring [EvidenceEntity.groupId]'s own
 *  precedent (decision 38) — a courier carrying an envelope for a group it holds no key for can
 *  resolve neither. This slice's own [org.offlinemesh.app.ble.RelayEngine.createCourierEnvelope]
 *  only ever constructs a row with both populated (we're always a member of a group we're
 *  authoring for); the blind-carry case (both null) is wired in a later P4 slice. */
@Entity(tableName = "courier_envelopes")
data class CourierEnvelopeEntity(
    @PrimaryKey val id: String,
    val groupId: String? = null,
    val senderId: String? = null,
    // Recognition tag (MeshFrameCodec.courierTag), computed once at creation and forwarded verbatim
    // by any later carrier — same "computed once, stable for the row's whole relay life" discipline
    // SosEntity.handle/EvidenceEntity.handle already establish (decision 38). Null only transiently
    // during construction; every stored row has one.
    val tag: ByteArray? = null,
    // Raw AES-GCM sealed bytes (MeshFrameCodec.sealCourierBody) — mirrors SosEntity.sealed exactly,
    // and for the identical reason (see sealCourierBody's own doc): whoever stores this must never
    // be handed a pre-framed wire message, only ever raw ciphertext. Null only transiently during
    // construction; every stored row has one.
    val sealed: ByteArray? = null,
    val createdAt: Long,
    // PLAN-v2.md §4.2's own "Copy budget 4, cap 8" — inert until a later P4 slice's handover
    // arithmetic reads/writes this. No default here (matching SosEntity.ttl's own precedent, which
    // takes RelayEngine.DEFAULT_TTL explicitly at the call site rather than duplicating it as a
    // field default) — RelayEngine.createCourierEnvelope passes RelayEngine.COURIER_INITIAL_COPY_BUDGET
    // explicitly, the single source of truth for the starting value.
    val copiesRemaining: Int,
) {
    private fun scalars() = listOf(id, groupId, senderId, createdAt, copiesRemaining)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CourierEnvelopeEntity) return false
        return scalars() == other.scalars() &&
            (tag?.contentEquals(other.tag) ?: (other.tag == null)) &&
            (sealed?.contentEquals(other.sealed) ?: (other.sealed == null))
    }

    override fun hashCode(): Int =
        31 * (31 * scalars().hashCode() + (tag?.contentHashCode() ?: 0)) + (sealed?.contentHashCode() ?: 0)
}

@Entity(tableName = "evidence")
data class EvidenceEntity(
    @PrimaryKey val id: String,
    // Decision 38 (docs/DECISIONS.md): nullable since the wire no longer carries groupId in the
    // clear. Null exactly means "blind-relay-held, group unresolved"; every row we're an actual
    // member of has this populated (either by RelayEngine.createEvidence for our own, or by
    // RelayResponder.handleEvidMeta once GroupRepository.resolveGroupKeyByHandle succeeds). As of
    // P5 slice 1 (decision 45), a null-groupId row only ever holds THIS header (id/hash/size/
    // mimeType/thumbnail) — RelayEngine.fullResRelayable's own doc explains why a blind relay no
    // longer solicits or carries the full chunk set at all, the actual "6MB of ciphertext most
    // carriers can never read" fix PLAN-v2.md §9.2 item 8 names.
    val groupId: String?,
    val senderId: String,
    val senderIsMe: Boolean,
    val timestamp: Long,
    val sha256: String,
    val totalChunks: Int,
    val mimeType: String,
    val ttl: Int,
    val originalLocalPath: String? = null,
    val complete: Boolean = false,
    // HMAC(group_key) over the header fields — prevents a non-member from forging an evidence
    // header (which would otherwise seed a bogus reassembly target). The chunks themselves are
    // already AES-GCM; this authenticates the metadata that steers them.
    val mac: ByteArray? = null,
    // Same additive per-sender Ed25519 signature scheme SosEntity's own sealed body carries
    // internally (see its doc) — this entity predates decision 37's seal and still travels as a
    // separate column alongside mac, over evidMacInput's canonical bytes.
    val signature: ByteArray? = null,
    // Decision 38: the opaque GATT wire handle this evidence header was framed under — see
    // SosEntity.handle's doc for the exact same "computed once, forwarded verbatim" discipline.
    // Unlike [groupId], this is populated even when we're a blind relay (it's read straight off
    // the wire, no resolution needed) — it's what actually lets a blind relay keep forwarding this
    // header onward without ever learning [groupId].
    val handle: ByteArray? = null,
    // P5 slice 1 (docs/DECISIONS.md decision 45, PLAN-v2.md §4.3) — raw AES-GCM SEALED bytes
    // (MeshFrameCodec.sealThumbnail), not a raw preview. Shipped cleartext-plus-MAC first, matching
    // this entity's own existing mac/signature treatment; caught before landing as a real passive-
    // exposure increase (any nearby connecting device would see a genuine visual hint, not just
    // metadata) and sealed under the same content-epoch key SOS/position bodies already use — see
    // MeshFrameCodec.Frame.EvidMeta.thumbnail's own doc for the full story. A blind relay still
    // stores/forwards this opaque blob but can never render it. Capped at
    // MeshFrameCodec.MAX_THUMBNAIL_BYTES (the sealed size), also covered by evidMacInput on top of
    // the seal's own GCM tag. Empty (not null) when absent, matching the wire's own "always present,
    // zero-length when there's nothing to show" shape — MeshFrameCodec.sealThumbnail/openThumbnail
    // both no-op on empty rather than sealing/opening nothing.
    val thumbnail: ByteArray = ByteArray(0),
    // P5 slice 1 — set via RelayEngine.requestFullResolution once a MEMBER (never a blind relay,
    // see that function's own doc) explicitly asks for the full-resolution chunks this header
    // describes. Gates RelayEngine.fullResRelayable, which in turn gates whether this device ever
    // sends its own manifest for this item — see that function's own doc for the full mechanism.
    val wantsFullRes: Boolean = false,
) {
    private fun scalars() = listOf(
        id, groupId, senderId, senderIsMe, timestamp, sha256, totalChunks, mimeType, ttl,
        originalLocalPath, complete, wantsFullRes,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EvidenceEntity) return false
        return scalars() == other.scalars() &&
            (mac?.contentEquals(other.mac) ?: (other.mac == null)) &&
            (signature?.contentEquals(other.signature) ?: (other.signature == null)) &&
            (handle?.contentEquals(other.handle) ?: (other.handle == null)) &&
            thumbnail.contentEquals(other.thumbnail)
    }

    override fun hashCode(): Int {
        var result = 31 * scalars().hashCode() + (mac?.contentHashCode() ?: 0)
        result = 31 * result + (signature?.contentHashCode() ?: 0)
        result = 31 * result + (handle?.contentHashCode() ?: 0)
        return 31 * result + thumbnail.contentHashCode()
    }
}

@Entity(tableName = "evidence_chunks", primaryKeys = ["evidenceId", "chunkIndex"])
data class EvidenceChunkEntity(
    val evidenceId: String,
    val chunkIndex: Int,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EvidenceChunkEntity) return false
        return evidenceId == other.evidenceId && chunkIndex == other.chunkIndex && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * (31 * evidenceId.hashCode() + chunkIndex) + data.contentHashCode()
}

/** Pins one sender's Ed25519 public key within one group, on first sight of their presence
 *  heartbeat (see [org.offlinemesh.app.ble.RelayResponder]'s pin-on-first-sight verification).
 *  Composite-keyed on (groupId, senderId), not a global identity — a device's key here
 *  is scoped to this one group only, matching [org.offlinemesh.app.crypto.SenderIdentity]'s
 *  per-group (not per-device) keypair design, so a device is unlinkable across the groups it's in
 *  even by an observer who has compromised one group's traffic. */
@Entity(tableName = "peer_keys", primaryKeys = ["groupId", "senderId"])
data class PeerKeyEntity(
    val groupId: String,
    val senderId: String,
    val publicKey: ByteArray,
    val firstSeenAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerKeyEntity) return false
        return groupId == other.groupId && senderId == other.senderId &&
            publicKey.contentEquals(other.publicKey) && firstSeenAt == other.firstSeenAt
    }

    override fun hashCode(): Int {
        var result = 31 * groupId.hashCode() + senderId.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        return 31 * result + firstSeenAt.hashCode()
    }
}

/** A device's chosen display name within one specific group — not global: the same device can
 *  show a different name in each group it's a member of. Latest [updatedAt] wins on conflict, so
 *  relaying an older copy after a newer one has already arrived is a no-op (see NicknameDao). */
@Entity(tableName = "nicknames", primaryKeys = ["groupId", "senderId"])
data class NicknameEntity(
    val groupId: String,
    val senderId: String,
    val username: String,
    val updatedAt: Long,
    // HMAC(group_key) over the fields above — same authenticated-cleartext pattern EvidenceEntity
    // still uses (SosEntity moved to a full AES-GCM seal in decision 37; nicknames didn't need that
    // since a display name has no confidentiality requirement), so a non-member can't inject a fake
    // display name for a real member.
    val mac: ByteArray? = null,
    // Same additive per-sender Ed25519 signature scheme EvidenceEntity.signature carries, over
    // nicknameMacInput's canonical bytes instead of evidMacInput's.
    val signature: ByteArray? = null,
    // Decision 38 (docs/DECISIONS.md): the opaque GATT wire handle this nickname was framed under
    // — see SosEntity.handle's doc for the "computed once, forwarded verbatim" discipline. Unlike
    // EvidenceEntity, a blind-relay-held nickname never becomes a Room row at all (see
    // RelayResponder.takeOpaqueNicknameCustody — a genuinely new in-memory OpaqueFrameRelay path,
    // decision 38), so [groupId] here always names a real group we're a member of; [handle] is
    // still stored so this row's own relay (once we ARE a member) can re-frame it without needing
    // to recompute anything.
    val handle: ByteArray? = null,
) {
    private fun scalars() = listOf(groupId, senderId, username, updatedAt)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NicknameEntity) return false
        return scalars() == other.scalars() &&
            (mac?.contentEquals(other.mac) ?: (other.mac == null)) &&
            (signature?.contentEquals(other.signature) ?: (other.signature == null)) &&
            (handle?.contentEquals(other.handle) ?: (other.handle == null))
    }

    override fun hashCode(): Int {
        var result = 31 * scalars().hashCode() + (mac?.contentHashCode() ?: 0)
        result = 31 * result + (signature?.contentHashCode() ?: 0)
        return 31 * result + (handle?.contentHashCode() ?: 0)
    }
}
