package org.offlinemesh.app.ble

import android.content.Context
import android.util.Log
import org.offlinemesh.app.crypto.CryptoUtils
import org.offlinemesh.app.crypto.SenderIdentity
import org.offlinemesh.app.data.EvidenceChunkEntity
import org.offlinemesh.app.data.EvidenceEntity
import org.offlinemesh.app.data.GroupRepository
import org.offlinemesh.app.data.NicknameEntity
import org.offlinemesh.app.data.SeenMessageEntity
import org.offlinemesh.app.data.SosEntity
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Chunking, dedup, and reassembly logic — kept independent of the actual BLE plumbing so
 * both the GATT server and client code paths (and tests) can share it.
 */
class RelayEngine(private val context: Context, private val repo: GroupRepository) {

    companion object {
        const val CHUNK_SIZE = 400
        const val DEFAULT_TTL = 8
        private const val TAG = "RelayEngine"

        // This app is for live coordination, not a permanent archive — and every phone relays
        // for groups it isn't even a member of, so without this, storage grows forever from
        // other people's traffic alone. 48h covers a multi-day event without becoming a
        // standing record of it.
        const val CONTENT_MAX_AGE_MILLIS = 48L * 60 * 60 * 1000
        // Matches CONTENT_MAX_AGE_MILLIS rather than a shorter window — this cache existing purely
        // as a flood-dedup short-circuit (see ingestSos/ingestEvidenceMeta's doc) doesn't mean it's
        // safe to expire sooner than the content it's deduping against: while a SOS/evidence row
        // is still alive, seeing that same id again should never be treated as "new" again either.
        // "Is new" is now derived from the DAO insert's own return value regardless (see
        // ingestSos/ingestEvidenceMeta), so this equal window is defence in depth, not the primary
        // fix — but keeping it shorter than content age would still let a later relay skip straight
        // past the seenDao short-circuit into Room on every reconnect, needless DB traffic for
        // something already known to be a duplicate.
        private const val SEEN_ID_MAX_AGE_MILLIS = CONTENT_MAX_AGE_MILLIS

        /** Truncates [text] to at most [maxBytes] UTF-8 bytes — used instead of [String.take] since
         *  the caps this guards ([MeshFrameCodec.MAX_SOS_MESSAGE_BYTES]) are wire byte-length limits,
         *  not character-count limits, and non-ASCII text can be several bytes per character. A cut
         *  landing mid multi-byte sequence decodes its trailing partial character as U+FFFD — never
         *  a crash, and the boundary itself only matters for messages already past the cap. */
        private fun truncateToUtf8Bytes(text: String, maxBytes: Int): String {
            val bytes = text.toByteArray(Charsets.UTF_8)
            return if (bytes.size <= maxBytes) text else String(bytes, 0, maxBytes, Charsets.UTF_8)
        }

        /** Splits [data] into chunks of at most [size] bytes via [ByteArray.copyOfRange] — NOT
         *  `data.toList().chunked(size)`, which boxes every single byte into a `java.lang.Byte`. A
         *  300KB image meant ~300,000 boxed objects plus ~750 throwaway `List<Byte>` sublists plus a
         *  final copy back to `ByteArray`, on exactly the low-RAM phones this app targets — the same
         *  class of unnecessary allocation `maybeReassemble` already avoided on the reassembly side
         *  via pre-sized-array-plus-arraycopy, just not previously applied here on the way out.
         *  `internal`, in the companion (not an instance method) — constructing a real [RelayEngine]
         *  needs a [Context] (for its Room database), which a plain JVM test can't provide; this has
         *  no such dependency, so it's kept directly, deterministically testable on its own. */
        internal fun chunkBytes(data: ByteArray, size: Int): List<ByteArray> {
            val chunks = ArrayList<ByteArray>((data.size + size - 1) / size)
            var offset = 0
            while (offset < data.size) {
                val end = minOf(offset + size, data.size)
                chunks.add(data.copyOfRange(offset, end))
                offset = end
            }
            return chunks
        }

        /** Where a reassembled evidence file lives on disk once [EvidenceEntity.complete] is true —
         *  the single place this naming convention is defined, shared with the UI layer
         *  (GroupChatScreen's "tap to view") so it can find the same file [maybeReassemble] wrote,
         *  without either side hardcoding the other's path logic separately. */
        fun outputFile(context: Context, evidenceId: String, mimeType: String): File {
            val outDir = File(context.filesDir, "evidence")
            val ext = if (mimeType.startsWith("video")) "mp4" else "jpg"
            return File(outDir, "$evidenceId.$ext")
        }
    }

    private val db = org.offlinemesh.app.data.AppDatabase.get(context)
    private val seenDao = db.seenMessageDao()
    private val sosDao = db.sosDao()
    private val evidenceDao = db.evidenceDao()
    private val chunkDao = db.evidenceChunkDao()
    private val nicknameDao = db.nicknameDao()

    // Bumped every time something is added to this device's relayable catalog (an authored item,
    // or a newly-ingested one from a peer) — the same set currentCatalogKeys() draws its keys
    // from. See ConnectionAttemptTracker's currentEpoch param: comparing this against the epoch
    // recorded at a peer's last successful sync is what lets a device skip that peer's normal
    // reconnect cooldown when it's now carrying something new for them (the "passerby relay"
    // case), instead of only reconnecting on the old peer-agnostic timer.
    private val epoch = AtomicInteger(0)
    val catalogEpoch: Int get() = epoch.get()

    // ---------- creating local items ----------

    suspend fun createSos(groupId: String, text: String): SosEntity {
        val id = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val senderId = repo.deviceId
        // Caps to the same bound MeshFrameCodec.decode enforces on the receiving end — truncating
        // by UTF-8 byte length (what the wire format and MAC actually measure), not by String
        // length, so this can never author a message the codec's own guard would then reject.
        val truncated = truncateToUtf8Bytes(text, MeshFrameCodec.MAX_SOS_MESSAGE_BYTES)
        val key = repo.getGroupKey(groupId)
        val macInput = MeshFrameCodec.sosMacInput(id, groupId, senderId, truncated, timestamp)
        val mac = key?.let { CryptoUtils.authTag(it, macInput) }
        // Additive on top of the group-key mac above, never a replacement — see SosEntity.
        // signature's doc. Null exactly when mac is (no group key => no sender identity either;
        // see GroupRepository.ensureSenderIdentity, established alongside the key on join/create).
        val signature = repo.getSenderKeyPair(groupId)?.let { SenderIdentity.sign(it.privateKey, macInput) }
        val sos = SosEntity(
            id = id, groupId = groupId, senderId = senderId, senderIsMe = true,
            message = truncated, timestamp = timestamp, ttl = DEFAULT_TTL, mac = mac, signature = signature
        )
        sosDao.insert(sos)
        seenDao.insert(SeenMessageEntity(id, System.currentTimeMillis()))
        epoch.incrementAndGet()
        return sos
    }

    suspend fun createEvidence(groupId: String, plaintext: ByteArray, mimeType: String, originalLocalPath: String?): EvidenceEntity {
        val key = repo.getGroupKey(groupId) ?: error("no key for group")
        val ciphertext = CryptoUtils.encrypt(key, plaintext)
        val id = UUID.randomUUID().toString()
        val hash = CryptoUtils.sha256Hex(ciphertext)
        val chunks = chunkBytes(ciphertext, CHUNK_SIZE)
        val timestamp = System.currentTimeMillis()
        val senderId = repo.deviceId
        val macInput = MeshFrameCodec.evidMacInput(id, groupId, senderId, timestamp, hash, chunks.size, mimeType)
        val mac = CryptoUtils.authTag(key, macInput)
        val signature = repo.getSenderKeyPair(groupId)?.let { SenderIdentity.sign(it.privateKey, macInput) }
        val evidence = EvidenceEntity(
            id = id, groupId = groupId, senderId = senderId, senderIsMe = true,
            timestamp = timestamp, sha256 = hash, totalChunks = chunks.size,
            mimeType = mimeType, ttl = DEFAULT_TTL, originalLocalPath = originalLocalPath, complete = true,
            mac = mac, signature = signature
        )
        evidenceDao.insert(evidence)
        seenDao.insert(SeenMessageEntity(id, System.currentTimeMillis()))
        chunks.forEachIndexed { idx, bytes ->
            val chunkEntity = EvidenceChunkEntity(id, idx, bytes)
            chunkDao.insert(chunkEntity)
            seenDao.insert(SeenMessageEntity("$id:$idx", System.currentTimeMillis()))
        }
        // The sender's own "tap to view" reads from the exact same outputFile() location
        // maybeReassemble() writes to on the RECEIVING end — without this, only receivers ever get
        // a viewable file; the sender's own copy of what they just sent shows "file not found."
        // complete=true above already claims this file exists, so this makes that claim true.
        val outFile = outputFile(context, id, mimeType)
        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use { it.write(plaintext) }
        epoch.incrementAndGet()
        return evidence
    }

    /** Sets/overwrites this device's display name for one group only — not global; the same
     *  device can show a different name in each group. Re-submitting always overwrites, keyed on
     *  [updatedAt] so a peer that already has a newer copy (from us, relayed) doesn't regress. */
    suspend fun setNickname(groupId: String, username: String): NicknameEntity {
        val trimmed = username.trim().take(MeshFrameCodec.MAX_USERNAME_CHARS)
        val updatedAt = System.currentTimeMillis()
        val senderId = repo.deviceId
        val key = repo.getGroupKey(groupId) ?: error("no key for group")
        val macInput = MeshFrameCodec.nicknameMacInput(groupId, senderId, trimmed, updatedAt)
        val mac = CryptoUtils.authTag(key, macInput)
        val signature = repo.getSenderKeyPair(groupId)?.let { SenderIdentity.sign(it.privateKey, macInput) }
        val n = NicknameEntity(groupId, senderId, trimmed, updatedAt, mac, signature)
        nicknameDao.upsert(n)
        epoch.incrementAndGet()
        return n
    }

    suspend fun myNickname(groupId: String): NicknameEntity? = nicknameDao.get(groupId, repo.deviceId)

    // ---------- ingesting items heard over the mesh ----------

    // "Is this new" is derived from the DAO insert's own return value (the rowid, or -1 when
    // OnConflictStrategy.IGNORE dropped it as a duplicate), not from the seenDao check above —
    // seenDao is a short-lived flood-dedup cache (SEEN_ID_MAX_AGE_MILLIS), while the SOS/evidence
    // rows themselves live for the full CONTENT_MAX_AGE_MILLIS. Deriving newness from seenDao alone
    // meant that once its entry expired but the content row hadn't, the next relay of the exact
    // same item made this return true again — ingestSos feeding straight into
    // RelayResponder.onSosReceived, which fires an IMPORTANCE_HIGH/CATEGORY_ALARM notification for
    // what could be a many-hours-old emergency. The seenDao check above is still worth keeping as a
    // cheap short-circuit before ever touching Room for the common case (a flood-relayed duplicate
    // arriving seconds after the first copy).

    suspend fun ingestSos(sos: SosEntity): Boolean {
        if (seenDao.find(sos.id) != null) return false
        seenDao.insert(SeenMessageEntity(sos.id, System.currentTimeMillis()))
        // hop always +1, unconditionally — ttl may drop by more than 1 under a future degree-aware
        // relay clamp (PLAN-v2.md P1), but hop must stay an honest, uniform distance counter
        // regardless of what ttl does. See SosEntity.hop's doc / docs/DECISIONS.md decision 16.
        val rowId = sosDao.insert(sos.copy(senderIsMe = false, ttl = sos.ttl - 1, hop = sos.hop + 1))
        val isNew = rowId != -1L
        if (isNew) epoch.incrementAndGet()
        return isNew
    }

    suspend fun ingestEvidenceMeta(meta: EvidenceEntity): Boolean {
        if (seenDao.find(meta.id) != null) return false
        seenDao.insert(SeenMessageEntity(meta.id, System.currentTimeMillis()))
        val rowId = evidenceDao.insert(
            meta.copy(senderIsMe = false, ttl = meta.ttl - 1, complete = false, originalLocalPath = null)
        )
        val isNew = rowId != -1L
        if (isNew) epoch.incrementAndGet()
        return isNew
    }

    /** Latest-[NicknameEntity.updatedAt]-wins, not flood-dedup — this is mutable per-member state,
     *  not a one-shot event, so there's no seenDao entry to grow unboundedly for it. */
    suspend fun ingestNickname(n: NicknameEntity): Boolean {
        val existing = nicknameDao.get(n.groupId, n.senderId)
        if (existing != null && existing.updatedAt >= n.updatedAt) return false
        nicknameDao.upsert(n)
        epoch.incrementAndGet()
        return true
    }

    suspend fun ingestChunk(chunk: EvidenceChunkEntity): Boolean {
        val seenId = "${chunk.evidenceId}:${chunk.chunkIndex}"
        // FRAME_EVID_CHUNK carries no totalChunks field to validate chunkIndex against (chunks can
        // legitimately arrive before the header), so this absolute cap — the same one
        // MeshFrameCodec.decode enforces on evidence-meta/manifest totalChunks — is the only bound
        // on this path. Without it, a flood of otherwise-valid ~400-byte chunk frames with huge,
        // sparse chunkIndex values grows this table without limit. Combined with the seenDao check
        // via `||` (rather than two separate early returns) to keep this at 2 return statements.
        val outOfBounds = chunk.chunkIndex !in 0 until MeshFrameCodec.MAX_EVIDENCE_CHUNKS
        if (outOfBounds || seenDao.find(seenId) != null) return false
        seenDao.insert(SeenMessageEntity(seenId, System.currentTimeMillis()))
        chunkDao.insert(chunk)
        maybeReassemble(chunk.evidenceId)
        return true
    }

    private suspend fun maybeReassemble(evidenceId: String) {
        val meta = evidenceDao.get(evidenceId) ?: return
        if (meta.complete) return
        val have = chunkDao.receivedCount(evidenceId)
        if (have < meta.totalChunks) return

        val key = repo.getGroupKey(meta.groupId) ?: return // not a group we're in — stay a blind carrier
        val chunks = chunkDao.allChunks(evidenceId).sortedBy { it.chunkIndex }
        // Pre-sized array + arraycopy, not repeated `+=` (O(n) instead of O(n^2) — matters once
        // this is thousands of chunks, found during QC while checking the large-file path).
        val ciphertext = ByteArray(chunks.sumOf { it.data.size })
        var offset = 0
        for (c in chunks) {
            System.arraycopy(c.data, 0, ciphertext, offset, c.data.size)
            offset += c.data.size
        }
        val actualHash = CryptoUtils.sha256Hex(ciphertext)
        if (actualHash != meta.sha256) {
            Log.w(TAG, "evidence $evidenceId hash mismatch — corrupted or tampered, discarding reassembly")
            return
        }
        val plaintext = CryptoUtils.decrypt(key, ciphertext) ?: return

        val outFile = outputFile(context, evidenceId, meta.mimeType)
        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use { it.write(plaintext) }

        evidenceDao.update(meta.copy(complete = true))
    }

    // ---------- what to offer a peer we just connected to ----------

    suspend fun relayableSos(): List<SosEntity> = sosDao.getRelayable().filter { it.ttl > 0 }

    suspend fun relayableEvidenceMeta(): List<EvidenceEntity> = evidenceDao.getRelayable().filter { it.ttl > 0 }

    // "What do we hold" (any ttl, including 0) is a different question from "what do we still
    // forward" (relayableSos/relayableEvidenceMeta above, ttl > 0 only) — used to build the
    // outgoing CatalogFilter (see RelayResponder.currentCatalogKeys). An item at ttl 0 has stopped
    // propagating but is still held until the 48h prune; advertising it in the catalog filter
    // stops peers from re-pushing it to us on every connection for the rest of its retention
    // window, without relaying it any further ourselves — relayableSos/relayableEvidenceMeta are
    // still what gates actual sends, completely unaffected by this pair.
    suspend fun heldSosIds(): List<String> = sosDao.allIds()

    suspend fun heldEvidenceIds(): List<String> = evidenceDao.allIds()

    /** Looks up one evidence item's header by id — used by [WifiDirectHandoffCoordinator] to
     *  re-derive a `groupId`/`mimeType` from just the `evidenceId` a Manifest frame carries. */
    suspend fun evidenceMeta(id: String): EvidenceEntity? = evidenceDao.get(id)

    suspend fun haveIndexSet(evidenceId: String): Set<Int> = chunkDao.receivedIndexes(evidenceId).toSet()

    suspend fun chunksByIndexes(evidenceId: String, indexes: List<Int>): List<EvidenceChunkEntity> =
        indexes.mapNotNull { chunkDao.getChunk(evidenceId, it) }

    suspend fun nicknamesForGroup(groupId: String): List<NicknameEntity> = nicknameDao.getForGroup(groupId)

    /**
     * Called periodically from MeshService. Not a permanent archive — deletes content past
     * CONTENT_MAX_AGE_MILLIS (this covers both age-expiry and anything a group's own deletion
     * missed, since a group being dismantled removes its evidence/SOS rows immediately via
     * GroupRepository — this is the backstop, not the primary path for that case).
     */
    suspend fun pruneExpired() {
        val cutoff = System.currentTimeMillis() - CONTENT_MAX_AGE_MILLIS
        val expiredIds = evidenceDao.idsOlderThan(cutoff)
        for (id in expiredIds) chunkDao.deleteForEvidence(id)
        evidenceDao.pruneOlderThan(cutoff)
        sosDao.pruneOlderThan(cutoff)
        seenDao.prune(System.currentTimeMillis() - SEEN_ID_MAX_AGE_MILLIS)
        // Deliberately not pruning nicknames on the same 48h "content" cadence: unlike SOS/evidence
        // (one-shot events from a live incident) a nickname is meant to persist for as long as
        // you're in the group, per the requirement it stay set until explicitly changed again — it's
        // one small row per (group, member), bounded by group size, not an unbounded event stream.
        // deleteForGroup on group deletion (GroupRepository.dismantleGroup) is what actually clears it.

        // Orphan sweep: delete any reassembled evidence file with no matching DB row left —
        // covers both the age-expiry above and files orphaned by a group being deleted outright.
        try {
            val outDir = File(context.filesDir, "evidence")
            val liveIds = evidenceDao.allIds().toSet()
            outDir.listFiles()?.forEach { file ->
                val id = file.nameWithoutExtension
                if (id !in liveIds) file.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "evidence file sweep failed: ${e.message}")
        }
    }
}
