package org.offlinemesh.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups ORDER BY createdAt DESC")
    fun observeGroups(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE id = :id")
    suspend fun getGroup(id: String): GroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(group: GroupEntity)

    // Filters out expired rows entirely at the SQL level — belt-and-braces so an expired group
    // can never be advertised, relayed for, or have its presence heartbeat sent even in the brief
    // window before GroupRepository.expireGroups' own periodic sweep actually deletes it (up to
    // 30 minutes; see MeshService.startPruning). The real deletion (and the security property that
    // matters — dropping the key, wiping content) still happens in expireGroups, not here; this is
    // only a display/relay-eligibility gate. strftime('%s','now') is UTC epoch seconds (SQLite),
    // matching System.currentTimeMillis()'s own UTC-epoch basis once scaled to millis.
    @Query("SELECT * FROM groups WHERE expiresAt > CAST(strftime('%s','now') AS INTEGER) * 1000")
    suspend fun getActiveGroups(): List<GroupEntity>

    @Query("SELECT id FROM groups WHERE expiresAt <= :nowMillis")
    suspend fun expiredGroupIds(nowMillis: Long): List<String>

    // Unfiltered — unlike getActiveGroups, this must include an already-expired-but-not-yet-swept
    // group too (see GroupRepository.sweepOrphanKeys' doc for why: that row's key is still validly
    // in use until expireGroups actually dismantles it, so treating it as "orphaned" here would
    // race the real cleanup and delete a key still needed for a few more minutes).
    @Query("SELECT id FROM groups")
    suspend fun allGroupIds(): List<String>

    @Query("DELETE FROM groups WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SeenMessageDao {
    @Query("SELECT id FROM seen_messages WHERE id = :id LIMIT 1")
    suspend fun find(id: String): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(seen: SeenMessageEntity)

    @Query("DELETE FROM seen_messages WHERE seenAt < :cutoff")
    suspend fun prune(cutoff: Long)
}

@Dao
interface SosDao {
    // Filters out anything older than *this device's* own join/create moment (GroupEntity.createdAt
    // is stamped locally on join, never synced over the wire) — a new member's key lets them decrypt
    // pre-join history that's still circulating in the mesh, but they shouldn't see it in their chat
    // feed. Still ingested/stored/relayed normally (see RelayEngine) — this filter is display-only,
    // so the mesh's flood behavior for members who *were* there isn't affected.
    @Query(
        "SELECT * FROM sos_events WHERE groupId = :groupId " +
            "AND timestamp >= (SELECT createdAt FROM groups WHERE id = :groupId) " +
            "ORDER BY timestamp DESC"
    )
    fun observeForGroup(groupId: String): Flow<List<SosEntity>>

    // Long, not Unit: Room returns the inserted rowid, or -1 when OnConflictStrategy.IGNORE
    // dropped the row because this id already exists — RelayEngine.ingestSos derives "is this
    // actually new" from that return value rather than from the separate seen-message cache (see
    // RelayEngine's doc on why: the two caches have different retention windows, and reading
    // newness off the shorter-lived one caused a stale SOS to re-fire as a fresh alarm).
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(sos: SosEntity): Long

    @Query("SELECT * FROM sos_events WHERE ttl > 0")
    suspend fun getRelayable(): List<SosEntity>

    // No ttl/ownership filter, unlike getRelayable — BeaconRadio's Tier B SOS content broadcast
    // (decision 29) needs whichever SOS HopTracker.bestActiveSos already named as nearest,
    // regardless of whether we originated it or are holding a relayed copy; ttl=0 (stopped
    // propagating over GATT) doesn't mean "don't mention it exists" for a device still in range.
    @Query("SELECT * FROM sos_events WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SosEntity?

    // No ttl filter, unlike getRelayable — "what do we hold" (used to build the outgoing catalog
    // filter, see RelayEngine.heldSosIds) is a different question from "what do we still forward"
    // (getRelayable, ttl > 0 only). An item at ttl 0 has stopped propagating but is still held
    // until the 48h prune; mirrors EvidenceDao.allIds's identical existing pattern.
    @Query("SELECT id FROM sos_events")
    suspend fun allIds(): List<String>

    // Same shape as EvidenceDao.idsForGroup — added for RelayEngine.catalogKeysForGroup (decision
    // 34, docs/DECISIONS.md), which needs a single group's held ids, not every group's combined
    // the way allIds() (RelayEngine.heldSosIds) is used for GATT's own catalog filter.
    @Query("SELECT id FROM sos_events WHERE groupId = :groupId")
    suspend fun idsForGroup(groupId: String): List<String>

    @Query("DELETE FROM sos_events WHERE groupId = :groupId")
    suspend fun deleteForGroup(groupId: String)

    @Query("DELETE FROM sos_events WHERE timestamp < :cutoffMillis")
    suspend fun pruneOlderThan(cutoffMillis: Long)
}

@Dao
interface EvidenceDao {
    // Same pre-join filter as SosDao.observeForGroup — see that doc.
    @Query(
        "SELECT * FROM evidence WHERE groupId = :groupId " +
            "AND timestamp >= (SELECT createdAt FROM groups WHERE id = :groupId) " +
            "ORDER BY timestamp DESC"
    )
    fun observeForGroup(groupId: String): Flow<List<EvidenceEntity>>

    // Long, not Unit — same reasoning as SosDao.insert above: -1 means IGNORE dropped the row
    // because this id was already stored, which RelayEngine.ingestEvidenceMeta uses as the real
    // signal for "is this new," not the separate seen-message cache.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(evidence: EvidenceEntity): Long

    @Update
    suspend fun update(evidence: EvidenceEntity)

    @Query("SELECT * FROM evidence WHERE id = :id")
    suspend fun get(id: String): EvidenceEntity?

    @Query("SELECT * FROM evidence WHERE ttl > 0")
    suspend fun getRelayable(): List<EvidenceEntity>

    @Query("SELECT id FROM evidence WHERE groupId = :groupId")
    suspend fun idsForGroup(groupId: String): List<String>

    @Query("DELETE FROM evidence WHERE groupId = :groupId")
    suspend fun deleteForGroup(groupId: String)

    @Query("SELECT id FROM evidence WHERE timestamp < :cutoffMillis")
    suspend fun idsOlderThan(cutoffMillis: Long): List<String>

    @Query("SELECT id FROM evidence")
    suspend fun allIds(): List<String>

    @Query("DELETE FROM evidence WHERE timestamp < :cutoffMillis")
    suspend fun pruneOlderThan(cutoffMillis: Long)
}

@Dao
interface EvidenceChunkDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(chunk: EvidenceChunkEntity)

    @Query("SELECT chunkIndex FROM evidence_chunks WHERE evidenceId = :evidenceId ORDER BY chunkIndex ASC")
    suspend fun receivedIndexes(evidenceId: String): List<Int>

    @Query("SELECT COUNT(*) FROM evidence_chunks WHERE evidenceId = :evidenceId")
    suspend fun receivedCount(evidenceId: String): Int

    @Query("SELECT * FROM evidence_chunks WHERE evidenceId = :evidenceId ORDER BY chunkIndex ASC")
    suspend fun allChunks(evidenceId: String): List<EvidenceChunkEntity>

    @Query("SELECT * FROM evidence_chunks WHERE evidenceId = :evidenceId AND chunkIndex = :index LIMIT 1")
    suspend fun getChunk(evidenceId: String, index: Int): EvidenceChunkEntity?

    @Query("DELETE FROM evidence_chunks WHERE evidenceId = :evidenceId")
    suspend fun deleteForEvidence(evidenceId: String)
}

@Dao
interface PeerKeyDao {
    @Query("SELECT * FROM peer_keys WHERE groupId = :groupId AND senderId = :senderId LIMIT 1")
    suspend fun get(groupId: String, senderId: String): PeerKeyEntity?

    // REPLACE, not IGNORE — a first-sight pin only ever inserts once (RelayResponder checks `get`
    // first and only calls this when there was no existing row), so on-conflict semantics don't
    // matter for that path in practice; REPLACE is still the safer default should a caller ever
    // insert without checking first, since a stale duplicate row silently winning over IGNORE
    // would defeat the entire point of pinning.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PeerKeyEntity)

    @Query("DELETE FROM peer_keys WHERE groupId = :groupId")
    suspend fun deleteForGroup(groupId: String)
}

@Dao
interface NicknameDao {
    @Query("SELECT * FROM nicknames WHERE groupId = :groupId")
    fun observeForGroup(groupId: String): Flow<List<NicknameEntity>>

    @Query("SELECT * FROM nicknames WHERE groupId = :groupId")
    suspend fun getForGroup(groupId: String): List<NicknameEntity>

    @Query("SELECT * FROM nicknames WHERE groupId = :groupId AND senderId = :senderId LIMIT 1")
    suspend fun get(groupId: String, senderId: String): NicknameEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(n: NicknameEntity)

    @Query("DELETE FROM nicknames WHERE groupId = :groupId")
    suspend fun deleteForGroup(groupId: String)

    @Query("DELETE FROM nicknames WHERE updatedAt < :cutoffMillis")
    suspend fun pruneOlderThan(cutoffMillis: Long)
}
