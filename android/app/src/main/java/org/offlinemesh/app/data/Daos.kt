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

/** P4 slice 2 (docs/DECISIONS.md decision 41's own follow-up) — insert/getById/deleteForGroup/
 *  pruneOlderThan. P4 slice 3 (decision 43) adds the pool-admission/push queries below: counts and
 *  oldest-id lookups for CourierPool's own-group-vs-blind-carry tiering, allIds/getOwnGroup for the
 *  CatalogFilter advertise/push cycle (see RelayEngine.heldCourierIds/relayableCourierEnvelopes). */
@Suppress("TooManyFunctions") // flat query list, not code-organization pressure — see the doc above
@Dao
interface CourierEnvelopeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(envelope: CourierEnvelopeEntity): Long

    @Query("SELECT * FROM courier_envelopes WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CourierEnvelopeEntity?

    @Query("DELETE FROM courier_envelopes WHERE groupId = :groupId")
    suspend fun deleteForGroup(groupId: String)

    @Query("DELETE FROM courier_envelopes WHERE createdAt < :cutoffMillis")
    suspend fun pruneOlderThan(cutoffMillis: Long)

    @Query("DELETE FROM courier_envelopes WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT id FROM courier_envelopes")
    suspend fun allIds(): List<String>

    // "Own-group" = groupId IS NOT NULL — a row this device authored, or received and successfully
    // resolved to a group it holds the key for (RelayResponder.ingestOpenedCourier). "Blind carry" =
    // groupId IS NULL — accepted from a peer but unresolvable to any group we're a member of
    // (RelayResponder.takeCourierCustody), the courier equivalent of EvidenceEntity's own nullable-
    // groupId blind-relay row (decision 38), not OpaqueFrameRelay's in-memory shape (decision 42).

    @Query("SELECT COUNT(*) FROM courier_envelopes WHERE groupId IS NOT NULL")
    suspend fun countOwnGroup(): Int

    @Query("SELECT COUNT(*) FROM courier_envelopes WHERE groupId IS NULL")
    suspend fun countBlindCarry(): Int

    @Query("SELECT id FROM courier_envelopes WHERE groupId IS NOT NULL ORDER BY createdAt ASC LIMIT 1")
    suspend fun oldestOwnGroupId(): String?

    @Query("SELECT id FROM courier_envelopes WHERE groupId IS NULL ORDER BY createdAt ASC LIMIT 1")
    suspend fun oldestBlindCarryId(): String?

    // Own-group rows are always handover-eligible regardless of copiesRemaining (see
    // RelayEngine.relayableCourierEnvelopes' own doc for the P4 slice 3 -> 4 history: blind-carry
    // rows were excluded entirely until copiesRemaining had a real bound; slice 4 gives it one).
    @Query("SELECT * FROM courier_envelopes WHERE groupId IS NOT NULL")
    suspend fun getOwnGroup(): List<CourierEnvelopeEntity>

    // P4 slice 4 (docs/DECISIONS.md decision 44) — blind-carry rows only become handover-eligible
    // once copiesRemaining actually bounds further propagation (CourierHandover.MIN_COPIES_TO_SPLIT).
    // A row already down to its last copy stays held (and advertised via allIds()) but is never
    // offered onward again — it simply waits out the 24h prune.
    @Query("SELECT * FROM courier_envelopes WHERE groupId IS NULL AND copiesRemaining >= :minCopies")
    suspend fun getBlindCarryWithBudget(minCopies: Int): List<CourierEnvelopeEntity>

    @Query("UPDATE courier_envelopes SET copiesRemaining = :copiesRemaining WHERE id = :id")
    suspend fun updateCopiesRemaining(id: String, copiesRemaining: Int)
}

@Suppress("TooManyFunctions") // flat query list, not code-organization pressure
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

    // P5 slice 1 (docs/DECISIONS.md decision 45) — see RelayEngine.fullResRelayable/
    // requestFullResolution's own docs for the full mechanism this gates.
    @Query("UPDATE evidence SET wantsFullRes = 1 WHERE id = :id")
    suspend fun setWantsFullRes(id: String)

    @Query("SELECT * FROM evidence WHERE ttl > 0 AND groupId IS NOT NULL AND (senderIsMe = 1 OR wantsFullRes = 1)")
    suspend fun getFullResRelayable(): List<EvidenceEntity>
}

// P5 item 2 slice 2 (docs/DECISIONS.md decision 47): renamed from EvidenceChunkDao, chunkIndex ->
// esi, table evidence_chunks -> evidence_symbols. receivedIndexes/receivedCount dropped — no longer
// meaningful once completion is driven by FountainDecoder rank, not a positional count.
@Dao
interface EvidenceSymbolDao {
    // Long, not Unit — same reasoning as EvidenceDao.insert above: -1 means IGNORE dropped the row
    // because this (evidenceId, esi) pair was already stored, which RelayEngine.ingestSymbol uses
    // to decide whether this symbol is genuinely new STORAGE (worth relaying onward to a different
    // peer) independent of whether it was also new RANK to this device's own decoder.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(symbol: EvidenceSymbolEntity): Long

    @Query("SELECT * FROM evidence_symbols WHERE evidenceId = :evidenceId ORDER BY esi ASC")
    suspend fun allSymbols(evidenceId: String): List<EvidenceSymbolEntity>

    @Query("SELECT * FROM evidence_symbols WHERE evidenceId = :evidenceId AND esi = :esi LIMIT 1")
    suspend fun getSymbol(evidenceId: String, esi: Int): EvidenceSymbolEntity?

    @Query("DELETE FROM evidence_symbols WHERE evidenceId = :evidenceId")
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
