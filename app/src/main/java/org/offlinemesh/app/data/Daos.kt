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

    @Query("SELECT * FROM groups")
    suspend fun getActiveGroups(): List<GroupEntity>

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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(sos: SosEntity)

    @Query("SELECT * FROM sos_events WHERE ttl > 0")
    suspend fun getRelayable(): List<SosEntity>

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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(evidence: EvidenceEntity)

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
