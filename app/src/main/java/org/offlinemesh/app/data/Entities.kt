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
    // HMAC(group_key) over the immutable fields (everything but ttl/hop). A member verifies this
    // before ever showing or acting on the SOS, so a phone without the key can't inject a fake
    // emergency. Stored (not just recomputed) so a non-member blind carrier can relay it onward
    // byte-for-byte.
    val mac: ByteArray? = null,
    // Ed25519 sender-identity signature over the exact same bytes [mac] covers, under
    // this sender's per-group keypair — additive, not a replacement: [mac] alone still proves
    // "someone holding the group key," this additionally proves "specifically this sender," once
    // RelayResponder has pinned their key. Null is a tolerated, not a failure, state — see
    // RelayResponder's pin-on-first-sight doc for when a null vs. a present-but-wrong signature are
    // treated differently.
    val signature: ByteArray? = null
) {
    private fun scalars() = listOf(id, groupId, senderId, senderIsMe, message, timestamp, ttl, hop)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SosEntity) return false
        return scalars() == other.scalars() &&
            (mac?.contentEquals(other.mac) ?: (other.mac == null)) &&
            (signature?.contentEquals(other.signature) ?: (other.signature == null))
    }

    override fun hashCode(): Int =
        31 * (31 * scalars().hashCode() + (mac?.contentHashCode() ?: 0)) + (signature?.contentHashCode() ?: 0)
}

@Entity(tableName = "evidence")
data class EvidenceEntity(
    @PrimaryKey val id: String,
    val groupId: String,
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
    // See SosEntity.signature's doc — same additive per-sender Ed25519 signature, same tolerance
    // rules, over evidMacInput's canonical bytes instead of sosMacInput's.
    val signature: ByteArray? = null
) {
    private fun scalars() = listOf(
        id, groupId, senderId, senderIsMe, timestamp, sha256, totalChunks, mimeType, ttl, originalLocalPath, complete
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EvidenceEntity) return false
        return scalars() == other.scalars() &&
            (mac?.contentEquals(other.mac) ?: (other.mac == null)) &&
            (signature?.contentEquals(other.signature) ?: (other.signature == null))
    }

    override fun hashCode(): Int =
        31 * (31 * scalars().hashCode() + (mac?.contentHashCode() ?: 0)) + (signature?.contentHashCode() ?: 0)
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
    // HMAC(group_key) over the fields above — same authenticated-cleartext pattern as SosEntity/
    // EvidenceEntity, so a non-member can't inject a fake display name for a real member.
    val mac: ByteArray? = null,
    // See SosEntity.signature's doc — same additive per-sender Ed25519 signature, same tolerance
    // rules, over nicknameMacInput's canonical bytes instead of sosMacInput's.
    val signature: ByteArray? = null
) {
    private fun scalars() = listOf(groupId, senderId, username, updatedAt)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NicknameEntity) return false
        return scalars() == other.scalars() &&
            (mac?.contentEquals(other.mac) ?: (other.mac == null)) &&
            (signature?.contentEquals(other.signature) ?: (other.signature == null))
    }

    override fun hashCode(): Int =
        31 * (31 * scalars().hashCode() + (mac?.contentHashCode() ?: 0)) + (signature?.contentHashCode() ?: 0)
}
