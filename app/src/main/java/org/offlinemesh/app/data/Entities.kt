package org.offlinemesh.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long
)

/** Dedup cache: any packet id (sos id, evidence id, chunk composite id) we've already processed. */
@Entity(tableName = "seen_messages")
data class SeenMessageEntity(
    @PrimaryKey val id: String,
    val seenAt: Long
)

@Entity(tableName = "sos_events")
data class SosEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val senderId: String,
    val senderIsMe: Boolean,
    val message: String,
    val timestamp: Long,
    val ttl: Int,
    // HMAC(group_key) over the immutable fields (everything but ttl). A member verifies this before
    // ever showing or acting on the SOS, so a phone without the key can't inject a fake emergency.
    // Stored (not just recomputed) so a non-member blind carrier can relay it onward byte-for-byte.
    val mac: ByteArray? = null
)

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
    val mac: ByteArray? = null
)

@Entity(tableName = "evidence_chunks", primaryKeys = ["evidenceId", "chunkIndex"])
data class EvidenceChunkEntity(
    val evidenceId: String,
    val chunkIndex: Int,
    val data: ByteArray
)

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
    val mac: ByteArray? = null
)
