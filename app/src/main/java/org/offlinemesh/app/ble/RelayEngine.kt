package org.offlinemesh.app.ble

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.offlinemesh.app.crypto.CryptoUtils
import org.offlinemesh.app.crypto.SenderIdentity
import org.offlinemesh.app.data.CourierEnvelopeEntity
import org.offlinemesh.app.data.EvidenceEntity
import org.offlinemesh.app.data.EvidenceSymbolEntity
import org.offlinemesh.app.data.GroupRepository
import org.offlinemesh.app.data.NicknameEntity
import org.offlinemesh.app.data.SeenMessageEntity
import org.offlinemesh.app.data.SosEntity
import org.offlinemesh.app.diagnostics.DiagnosticsLog
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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

        // PLAN-v2.md §4.2's own "Cap 16 KiB, 24 h" — deliberately shorter than CONTENT_MAX_AGE_MILLIS's
        // 48h: a courier envelope is a supplementary delivery path for a partition, not a second
        // 48h-retained record of the same content (docs/DECISIONS.md decision 41's own P4 slice 1).
        const val COURIER_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000

        // PLAN-v2.md §4.2's own "Copy budget 4" — inert until a later P4 slice's handover logic
        // actually reads/writes CourierEnvelopeEntity.copiesRemaining; this slice only sets the
        // initial value at creation.
        const val COURIER_INITIAL_COPY_BUDGET = 4

        /** millis-to-epoch-seconds conversion, for [MeshFrameCodec.groupHandle]'s callers here
         *  (decision 38, `docs/DECISIONS.md`). */
        private const val MILLIS_PER_SECOND = 1000L

        /** Truncates [text] to at most [maxBytes] UTF-8 bytes — used instead of [String.take] since
         *  the caps this guards ([MeshFrameCodec.MAX_SOS_MESSAGE_BYTES]) are wire byte-length limits,
         *  not character-count limits, and non-ASCII text can be several bytes per character. A cut
         *  landing mid multi-byte sequence decodes its trailing partial character as U+FFFD — never
         *  a crash, and the boundary itself only matters for messages already past the cap. */
        private fun truncateToUtf8Bytes(text: String, maxBytes: Int): String {
            val bytes = text.toByteArray(Charsets.UTF_8)
            return if (bytes.size <= maxBytes) text else String(bytes, 0, maxBytes, Charsets.UTF_8)
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
    private val symbolDao = db.evidenceSymbolDao()
    private val nicknameDao = db.nicknameDao()
    private val courierEnvelopeDao = db.courierEnvelopeDao()

    // Bumped every time something is added to this device's relayable catalog (an authored item,
    // or a newly-ingested one from a peer) — the same set currentCatalogKeys() draws its keys
    // from. See ConnectionAttemptTracker's currentEpoch param: comparing this against the epoch
    // recorded at a peer's last successful sync is what lets a device skip that peer's normal
    // reconnect cooldown when it's now carrying something new for them (the "passerby relay"
    // case), instead of only reconnecting on the old peer-agnostic timer.
    private val epoch = AtomicInteger(0)
    val catalogEpoch: Int get() = epoch.get()

    // P5 item 2 slice 2 (docs/DECISIONS.md decision 47) — one live FountainDecoder per in-flight
    // (not-yet-complete) evidence item, keyed on evidenceId, lazily created and rehydrated from
    // persisted EvidenceSymbolEntity rows on first touch each process lifetime (see
    // getOrCreateDecoder). The persisted rows are the source of truth; this map is a derived,
    // disposable cache — losing it (process restart) costs nothing but a rehydrate replay, never
    // correctness. Entries are removed once an item completes (ingestSymbol) or is pruned
    // (pruneExpired).
    private val liveDecoders = ConcurrentHashMap<String, FountainDecoder>()

    // Shared, item-scoped (NOT peer-scoped) forward-only esi cursor for repair-symbol generation —
    // see symbolsToSend's own doc for why this is the one piece of state a sender keeps, and why it
    // doesn't reintroduce the per-peer-memory problem CatalogFilter's own class doc warns against
    // (PeerDeliveryTracker's old bounded-eviction issue): this is keyed on content, not on who's
    // asking, so there's nothing to evict and nothing that goes stale as peers come and go.
    private val symbolCursors = ConcurrentHashMap<String, AtomicInteger>()

    // CR-16 (PLAN-v2.md Part 10, 2026-08-09 review pass) — was rebuilt on EVERY symbolsToSend call
    // for a complete item: re-read all persisted symbol rows from Room, re-allocate the padded
    // buffer, re-run FountainCode.encoder. liveDecoders/symbolCursors above are both cached with a
    // defined lifecycle for the identical reason; this one wasn't. Same "keyed on content, not on
    // who's asking" shape as symbolCursors — an item's canonical symbol set never changes once
    // `complete = true` (content is immutable once created, this codebase's own established
    // principle for SosEntity/EvidenceEntity alike), so caching the built encoder forever (until
    // cleared alongside symbolCursors below) is safe, not just an optimization with an expiry story.
    private val symbolEncoders = ConcurrentHashMap<String, FountainEncoder>()

    // Guards the rehydrate-or-fetch + addSymbol + persist sequence in ingestSymbol against two GATT
    // connections concurrently feeding symbols for the SAME evidenceId — FountainDecoder itself has
    // no internal synchronization. A single instance-wide mutex, not per-evidenceId, is sufficient
    // at this app's realistic concurrency (a handful of simultaneous connections, not thousands) —
    // same "no need for finer granularity at this scale" call admitCourierEnvelope's own
    // db.withTransaction makes for the analogous DB-row-level race.
    private val decoderMutex = Mutex()

    // ---------- creating local items ----------

    // isAlert defaults false (decision 35, docs/DECISIONS.md) — the normal "Send" action in
    // GroupChatScreen creates a quiet message; only a dedicated SOS action passes true. See
    // SosEntity.isAlert's own doc for what that flag actually gates downstream.
    suspend fun createSos(groupId: String, text: String, isAlert: Boolean = false): SosEntity {
        val id = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val senderId = repo.senderIdFor(groupId)
        // Caps to the same bound MeshFrameCodec.decode/openSos enforce on the receiving end —
        // truncating by UTF-8 byte length (what the wire format actually measures), not by String
        // length, so this can never author a message the codec's own guard would then reject.
        val truncated = truncateToUtf8Bytes(text, MeshFrameCodec.MAX_SOS_MESSAGE_BYTES)
        // Same pattern createEvidence already uses — authoring content for a group we hold no key
        // to can't happen in practice (a user can't type into a group whose key they don't have),
        // and there's nothing valid to seal without one, unlike the old cleartext-plus-HMAC scheme
        // where a null mac was at least a representable (if useless) state.
        val rootKey = repo.getGroupKey(groupId) ?: error("no key for group")
        val signingPrivateKey = repo.getSenderKeyPair(groupId)?.privateKey
        // Decision 39 (docs/DECISIONS.md): sealed under the current epoch's derived content key,
        // not the root key directly — see CryptoUtils.contentEpochKey's own doc for what this does
        // and does not buy.
        val contentKey = CryptoUtils.contentEpochKey(rootKey, timestamp / MILLIS_PER_SECOND)
        // sealSosBody, NOT sealSos — this needs the RAW sealed bytes to store on SosEntity.sealed
        // (see that field's own doc and sealSosBody's), not a full pre-framed wire message. The
        // actual frame is built moments later, from these same stored bytes, when the caller
        // (MeshService.sendSos) invokes RelayResponder.floodForwardLocalSos.
        val sealed =
            MeshFrameCodec.sealSosBody(contentKey, id, senderId, truncated, timestamp, isAlert, signingPrivateKey)
        // Decision 38 (docs/DECISIONS.md): the opaque wire handle, computed once here and stored —
        // see MeshFrameCodec.groupHandle's doc. Stays on the ROOT key, unchanged by decision 39.
        val handle = MeshFrameCodec.groupHandle(rootKey, timestamp / MILLIS_PER_SECOND)
        val sos = SosEntity(
            id = id, groupId = groupId, senderId = senderId, senderIsMe = true,
            message = truncated, timestamp = timestamp, ttl = DEFAULT_TTL, isAlert = isAlert,
            sealed = sealed, handle = handle,
        )
        sosDao.insert(sos)
        seenDao.insert(SeenMessageEntity(id, System.currentTimeMillis()))
        epoch.incrementAndGet()
        return sos
    }

    @Suppress("LongParameterList") // wire-protocol scalars — see MeshFrameCodec.sealSos's identical suppress
    suspend fun createEvidence(
        groupId: String, plaintext: ByteArray, mimeType: String, originalLocalPath: String?,
        thumbnail: ByteArray = ByteArray(0),
    ): EvidenceEntity {
        val rootKey = repo.getGroupKey(groupId) ?: error("no key for group")
        // timestamp computed here, BEFORE the encrypt call below (decision 39, docs/DECISIONS.md) —
        // needs to exist first so the content epoch key can derive from it; previously this field
        // wasn't computed until after encrypt/hash/chunk, since nothing needed it that early.
        val timestamp = System.currentTimeMillis()
        val contentKey = CryptoUtils.contentEpochKey(rootKey, timestamp / MILLIS_PER_SECOND)
        val ciphertext = CryptoUtils.encrypt(contentKey, plaintext)
        val id = UUID.randomUUID().toString()
        val hash = CryptoUtils.sha256Hex(ciphertext)
        // FountainCode.encoder does its own zero-padding internally (see its own doc) — no manual
        // last-symbol handling needed here, a genuine simplification over the old chunkBytes shape.
        val encoder = FountainCode.encoder(ciphertext, CHUNK_SIZE)
        val senderId = repo.senderIdFor(groupId)
        // Sealed under the SAME contentKey the full-res ciphertext already uses — see
        // MeshFrameCodec.sealThumbnail's own doc for why this replaced this decision's original
        // cleartext-plus-MAC design. The mac below covers the SEALED bytes, same "whatever the
        // entity carries" contract evidMacInput always had, now just ciphertext instead of a
        // raw JPEG.
        val sealedThumbnail = MeshFrameCodec.sealThumbnail(contentKey, id, thumbnail)
        val macInput = MeshFrameCodec.evidMacInput(
            id, groupId, senderId, timestamp, hash, encoder.k, mimeType, sealedThumbnail, ciphertext.size,
        )
        val mac = CryptoUtils.authTag(contentKey, macInput)
        val signature = repo.getSenderKeyPair(groupId)?.let { SenderIdentity.sign(it.privateKey, macInput) }
        // Decision 38 (docs/DECISIONS.md): the opaque wire handle, computed once here and stored —
        // see MeshFrameCodec.groupHandle's doc. Stays on the ROOT key, unchanged by decision 39.
        val handle = MeshFrameCodec.groupHandle(rootKey, timestamp / MILLIS_PER_SECOND)
        val evidence = EvidenceEntity(
            id = id, groupId = groupId, senderId = senderId, senderIsMe = true,
            timestamp = timestamp, sha256 = hash, totalChunks = encoder.k,
            mimeType = mimeType, ttl = DEFAULT_TTL, originalLocalPath = originalLocalPath, complete = true,
            mac = mac, signature = signature, handle = handle, thumbnail = sealedThumbnail,
            contentLength = ciphertext.size,
        )
        evidenceDao.insert(evidence)
        seenDao.insert(SeenMessageEntity(id, System.currentTimeMillis()))
        for (esi in 0 until encoder.k) {
            symbolDao.insert(EvidenceSymbolEntity(id, esi, encoder.symbol(esi).data))
            seenDao.insert(SeenMessageEntity("$id:$esi", System.currentTimeMillis()))
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
        val senderId = repo.senderIdFor(groupId)
        val rootKey = repo.getGroupKey(groupId) ?: error("no key for group")
        val contentKey = CryptoUtils.contentEpochKey(rootKey, updatedAt / MILLIS_PER_SECOND)
        val macInput = MeshFrameCodec.nicknameMacInput(groupId, senderId, trimmed, updatedAt)
        val mac = CryptoUtils.authTag(contentKey, macInput)
        val signature = repo.getSenderKeyPair(groupId)?.let { SenderIdentity.sign(it.privateKey, macInput) }
        // Decision 38 (docs/DECISIONS.md): the opaque wire handle, computed once here and stored —
        // see MeshFrameCodec.groupHandle's doc. Stays on the ROOT key, unchanged by decision 39.
        val handle = MeshFrameCodec.groupHandle(rootKey, updatedAt / MILLIS_PER_SECOND)
        val n = NicknameEntity(groupId, senderId, trimmed, updatedAt, mac, signature, handle)
        nicknameDao.upsert(n)
        epoch.incrementAndGet()
        return n
    }

    suspend fun myNickname(groupId: String): NicknameEntity? = nicknameDao.get(groupId, repo.senderIdFor(groupId))

    /** P4 slice 2 (docs/DECISIONS.md decision 41's own follow-up, PLAN-v2.md §4.2) — creates and
     *  persists a courier envelope for OUR OWN authored [payload], mirroring [createSos]'s shape
     *  exactly (raw-body seal via [MeshFrameCodec.sealCourierBody], not a would-be frame-and-seal
     *  call, for the identical reason that function's own doc gives). This is a supplementary
     *  delivery path alongside whatever flood-relay already does for [groupId]'s content — it does
     *  not replace [createSos]/[createEvidence]. As of P4 slice 3 (decision 43), this now routes
     *  through [admitCourierEnvelope] (previously called `courierEnvelopeDao.insert` directly and
     *  deliberately did NOT bump [epoch] — see that decision's own note on why continuing not to
     *  would now be a stale precondition, not a design choice: slice 3 is exactly the point
     *  something starts reading courier envelopes for pushing). */
    suspend fun createCourierEnvelope(groupId: String, payload: ByteArray): CourierEnvelopeEntity {
        val id = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()
        val senderId = repo.senderIdFor(groupId)
        val rootKey = repo.getGroupKey(groupId) ?: error("no key for group")
        val signingPrivateKey = repo.getSenderKeyPair(groupId)?.privateKey
        val contentKey = CryptoUtils.contentEpochKey(rootKey, createdAt / MILLIS_PER_SECOND)
        val sealed =
            MeshFrameCodec.sealCourierBody(contentKey, id, senderId, payload, createdAt, signingPrivateKey)
        val tag = MeshFrameCodec.courierTag(rootKey, createdAt / MILLIS_PER_SECOND)
        val envelope = CourierEnvelopeEntity(
            id = id, groupId = groupId, senderId = senderId, tag = tag, sealed = sealed,
            createdAt = createdAt, copiesRemaining = COURIER_INITIAL_COPY_BUDGET,
        )
        admitCourierEnvelope(envelope)
        return envelope
    }

    /** P4 slice 3 (docs/DECISIONS.md decision 43, PLAN-v2.md §4.2's "bounded pool with tiers") — the
     *  single gate every courier envelope insert goes through, whether self-authored
     *  ([createCourierEnvelope]) or received over GATT ([org.offlinemesh.app.ble.RelayResponder]'s
     *  `ingestOpenedCourier`/`takeCourierCustody`). Applies [CourierPool.decide]'s admission policy
     *  (evicting the oldest same-tier row first if the relevant capacity is already full — own-group
     *  envelopes are never hard-rejected, only ever evict a sibling in the degenerate all-40-own-group
     *  case; see [CourierPool]'s own doc) inside a single Room transaction, closing a real check-
     *  then-act race: [org.offlinemesh.app.ble.MeshGattClient]/[org.offlinemesh.app.ble.MeshGattServer]
     *  can feed this concurrently from different peer connections, the same class of hazard
     *  [ConnectionAttemptTracker]'s own class doc names explicitly for a different piece of shared
     *  state. Bumps [epoch] on a genuinely new insert (the Room-insert-returned-a-real-rowid sense of
     *  "new," same as every other `create*`/`ingest*` here) — the first courier code path to do so;
     *  see [createCourierEnvelope]'s own note on why that's now correct, not an oversight. */
    suspend fun admitCourierEnvelope(envelope: CourierEnvelopeEntity): Boolean = db.withTransaction {
        val isOwnGroup = envelope.groupId != null
        val ownCount = courierEnvelopeDao.countOwnGroup()
        val blindCount = courierEnvelopeDao.countBlindCarry()
        when (CourierPool.decide(ownCount, blindCount, isOwnGroup)) {
            CourierPool.Admission.ACCEPT -> {}
            CourierPool.Admission.EVICT_OLDEST_BLIND ->
                courierEnvelopeDao.oldestBlindCarryId()?.let { courierEnvelopeDao.deleteById(it) }
            CourierPool.Admission.EVICT_OLDEST_OWN ->
                courierEnvelopeDao.oldestOwnGroupId()?.let { courierEnvelopeDao.deleteById(it) }
        }
        val rowId = courierEnvelopeDao.insert(envelope)
        val isNew = rowId != -1L
        if (isNew) epoch.incrementAndGet()
        isNew
    }

    /** What we ADVERTISE holding (own-group AND blind-carry both included) — same "held, not
     *  relayable" reasoning [heldSosIds]/[heldEvidenceIds] already give: this only changes what a
     *  peer's [org.offlinemesh.app.ble.CatalogFilter] sees us claiming, stopping them from re-
     *  offering us something we already carry (blind or not) — see [relayableCourierEnvelopes] for
     *  what's actually eligible to be PUSHED, a strictly narrower set. */
    suspend fun heldCourierIds(): List<String> = courierEnvelopeDao.allIds()

    /** Handover-eligible rows: every own-group row, plus blind-carry rows that still have at least
     *  [CourierHandover.MIN_COPIES_TO_SPLIT] copies left. Through P4 slice 3, this returned
     *  own-group only — a blind-carry row was held and advertised ([heldCourierIds]) but never
     *  proactively pushed onward, because [CourierEnvelopeEntity.copiesRemaining] was still inert
     *  (forwarded verbatim, never split), so nothing bounded further propagation. Slice 4 (decision
     *  44) gives it a real bound: [org.offlinemesh.app.ble.RelayResponder]'s push path now actually
     *  splits the count on handover (see [CourierHandover]), so a blind carrier with copies left to
     *  spare can safely pass some along — a row already down to its last copy still isn't included
     *  here, so it stays held but never offered again, same as before. */
    suspend fun relayableCourierEnvelopes(): List<CourierEnvelopeEntity> {
        val minCopies = CourierHandover.MIN_COPIES_TO_SPLIT
        return courierEnvelopeDao.getOwnGroup() + courierEnvelopeDao.getBlindCarryWithBudget(minCopies)
    }

    /** P4 slice 4 (docs/DECISIONS.md decision 44) — persists the local copy count a handover split
     *  left behind. Called by [org.offlinemesh.app.ble.RelayResponder] right after a successful
     *  split-and-push, never directly by a caller that hasn't actually pushed anything (a query
     *  without a matching action would silently desync the stored count from what was really handed
     *  out). */
    suspend fun updateCourierCopiesRemaining(id: String, copiesRemaining: Int) {
        courierEnvelopeDao.updateCopiesRemaining(id, copiesRemaining)
    }

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

    /** Lazily creates (or returns the already-live) [FountainDecoder] for [meta], rehydrated from
     *  every already-persisted [EvidenceSymbolEntity] row for this id on first touch each process
     *  lifetime — the persisted rows are the source of truth, this in-memory decoder is a derived,
     *  disposable cache (see [liveDecoders]'s own doc: losing it costs a rehydrate replay, never
     *  correctness). Caller must hold [decoderMutex]. */
    private suspend fun getOrCreateDecoder(meta: EvidenceEntity): FountainDecoder {
        liveDecoders[meta.id]?.let { return it }
        val decoder = FountainDecoder(meta.totalChunks, CHUNK_SIZE, meta.contentLength)
        for (row in symbolDao.allSymbols(meta.id)) decoder.addSymbol(Symbol(row.esi, row.data))
        liveDecoders[meta.id] = decoder
        return decoder
    }

    /** Folds one directly-received symbol into [evidenceId]'s decode state, ALWAYS persisting it
     *  first (regardless of decoder outcome) — a partial holder must still be able to usefully
     *  relay its partial symbol set to a THIRD peer, the actual "faster with more carriers" value
     *  PLAN-v2.md §4.3 item 2 names; a design that only persisted at completion would silently
     *  defeat that promise. Returns whether [esi] was genuinely new STORAGE (worth relaying to a
     *  different peer that doesn't have it), independent of whether it also advanced THIS device's
     *  own decode rank — those are different questions (a symbol can be decoder-redundant while
     *  still being storage-new). */
    suspend fun ingestSymbol(evidenceId: String, esi: Int, data: ByteArray): Boolean {
        // No totalChunks/k to validate esi against yet if the header hasn't arrived (a symbol can
        // legitimately arrive before its header, same as the retired chunk mechanism) — this
        // absolute cap, the same one MeshFrameCodec.decode enforces on evidence-meta's totalChunks,
        // is the only bound on this path.
        if (esi !in 0 until MeshFrameCodec.MAX_EVIDENCE_CHUNKS) return false
        val seenId = "$evidenceId:$esi"
        if (seenDao.find(seenId) != null) return false
        seenDao.insert(SeenMessageEntity(seenId, System.currentTimeMillis()))
        val rowId = symbolDao.insert(EvidenceSymbolEntity(evidenceId, esi, data))
        val isNew = rowId != -1L
        // Checked regardless of isNew, not just when this exact symbol was new storage — a rare
        // crash-recovery case (a prior session persisted enough rows to be complete but never ran
        // this check) only self-heals via getOrCreateDecoder's own rehydrate-from-all-persisted-
        // rows step. Cheap when nothing has changed: meta.complete short-circuits once genuinely
        // done.
        maybeCompleteFromSymbol(evidenceId, esi, data)
        return isNew
    }

    /** [esi]/[data] are fed into the decoder EXPLICITLY here, not left to [getOrCreateDecoder]'s own
     *  rehydrate-on-first-creation step alone — that step only re-scans Room when a decoder for this
     *  id doesn't exist yet in [liveDecoders]. If an earlier call (e.g. a [symbolDeficit] read before
     *  any symbols existed) already created and cached the decoder, rehydration never runs again, so
     *  a symbol ingested afterward would otherwise never actually reach it. [FountainDecoder.
     *  addSymbol]'s own `seenEsi` dedup makes a redundant call (this esi was already included via a
     *  fresh rehydration moments earlier) a safe no-op either way. */
    private suspend fun maybeCompleteFromSymbol(evidenceId: String, esi: Int, data: ByteArray) = decoderMutex.withLock {
        val meta = evidenceDao.get(evidenceId) ?: return@withLock
        if (meta.complete) return@withLock
        val decoder = getOrCreateDecoder(meta)
        decoder.addSymbol(Symbol(esi, data))
        if (!decoder.isComplete) return@withLock

        // Decision 38 (docs/DECISIONS.md): meta.groupId is null exactly when we're a blind carrier
        // (never resolved which group this belongs to) — same "stay a blind carrier" outcome as
        // before, now reached via a null groupId instead of a failed getGroupKey lookup.
        val rootKey = meta.groupId?.let { repo.getGroupKey(it) } ?: return@withLock
        val ciphertext = decoder.decode() ?: return@withLock
        val actualHash = CryptoUtils.sha256Hex(ciphertext)
        if (actualHash != meta.sha256) {
            Log.w(TAG, "evidence $evidenceId hash mismatch — corrupted or tampered, discarding reassembly")
            DiagnosticsLog.event(
                "error",
                "evidence hash mismatch, discarding reassembly: " +
                    evidenceId.take(RelayResponder.SENDER_ID_LOG_CHARS)
            )
            liveDecoders.remove(evidenceId)
            return@withLock
        }
        // Decision 39 (docs/DECISIONS.md): single derivation, not a candidate list — meta.timestamp
        // is the content's own authored time, already cleartext (round-tripped through the wire
        // envelope since decision 38), so the exact epoch is known directly, unlike SOS/position
        // whose timestamp lives inside the seal.
        val contentKey = CryptoUtils.contentEpochKey(rootKey, meta.timestamp / MILLIS_PER_SECOND)
        val plaintext = CryptoUtils.decrypt(contentKey, ciphertext) ?: return@withLock

        val outFile = outputFile(context, evidenceId, meta.mimeType)
        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use { it.write(plaintext) }

        // Collapse storage to the canonical k systematic rows — the actual step that turns a
        // device that just finished receiving something into a fully-capable re-sharer through the
        // exact same code path own-authored content uses (symbolsToSend below), no separate
        // "receiver-side serving" logic needed.
        symbolDao.deleteForEvidence(evidenceId)
        val encoder = FountainCode.encoder(ciphertext, CHUNK_SIZE)
        for (i in 0 until encoder.k) symbolDao.insert(EvidenceSymbolEntity(evidenceId, i, encoder.symbol(i).data))

        evidenceDao.update(meta.copy(complete = true))
        liveDecoders.remove(evidenceId)
        symbolCursors.remove(evidenceId)
        symbolEncoders.remove(evidenceId) // CR-16 (PLAN-v2.md Part 10) — defensive, same as symbolCursors above
    }

    /** How many more distinct symbols THIS device still needs for [evidenceId] before it can
     *  complete reassembly — 0 for a complete item, a blind-carried item ([EvidenceEntity.groupId]
     *  null — pull-gating stays member-only, same as [fullResRelayable]'s own guarantee), or an
     *  unknown id. Feeds [RelayResponder]'s replacement for the retired manifest-solicitation flow:
     *  sending a `SymbolRequest` is what solicits symbols back, the same "sending IS what solicits"
     *  mechanism the retired `Manifest` played for chunks. */
    suspend fun symbolDeficit(evidenceId: String): Int {
        val meta = evidenceDao.get(evidenceId) ?: return 0
        if (meta.complete || meta.groupId == null) return 0
        return decoderMutex.withLock { getOrCreateDecoder(meta).deficit }
    }

    /** [FountainDecoder.rank] for [evidenceId] — "how much decode progress has this device made,"
     *  the UI-facing progress-bar counterpart to [symbolDeficit] (replaces the retired
     *  `EvidenceChunkDao.receivedCount`'s role in `GroupChatScreen`'s own "receiving file: X / Y"
     *  display, decision 47, docs/DECISIONS.md — a rank is the more meaningful "progress toward
     *  decodable" number than a raw stored-row count, since a device can hold more rows than its
     *  rank if some turned out redundant). [EvidenceEntity.totalChunks] for a complete item (rank
     *  always equals k once done); 0 for an unknown id. */
    suspend fun decodeRank(evidenceId: String): Int {
        val meta = evidenceDao.get(evidenceId) ?: return 0
        if (meta.complete) return meta.totalChunks
        return decoderMutex.withLock { getOrCreateDecoder(meta).rank }
    }

    /** Symbols to push in response to a peer's [wantCount]-sized request for [evidenceId]. For a
     *  COMPLETE item, generates fresh symbols from a shared, item-scoped (NOT peer-scoped)
     *  forward-only esi cursor — see [symbolCursors]'s own doc for why this is the one piece of
     *  state a sender keeps, and why it doesn't reintroduce per-peer memory. For an INCOMPLETE item
     *  this device only partially holds, returns up to [wantCount] of its own currently-persisted
     *  rows verbatim — no cursor needed, the row set is naturally small; a duplicate offered again
     *  later is a cheap no-op on the far end (same "worst case is wasted bandwidth, never
     *  incorrectness" framing [FountainCode]'s own class doc gives the primitive). Empty for an
     *  unknown id. */
    suspend fun symbolsToSend(evidenceId: String, wantCount: Int): List<Symbol> {
        if (wantCount <= 0) return emptyList()
        val meta = evidenceDao.get(evidenceId) ?: return emptyList()
        if (!meta.complete) {
            return symbolDao.allSymbols(evidenceId).take(wantCount).map { Symbol(it.esi, it.data) }
        }
        // CR-16 (PLAN-v2.md Part 10) — cache hit skips the Room re-read, the padded-buffer
        // re-allocation, and the FountainEncoder rebuild entirely; only a cache miss (first request
        // for this item since it completed, or since process start) pays that cost.
        val encoder = symbolEncoders[evidenceId] ?: run {
            val canonical = symbolDao.allSymbols(evidenceId).sortedBy { it.esi }
            if (canonical.size < meta.totalChunks) return emptyList() // defensive; shouldn't happen once complete
            val padded = ByteArray(canonical.size * CHUNK_SIZE)
            for ((i, row) in canonical.withIndex()) System.arraycopy(row.data, 0, padded, i * CHUNK_SIZE, CHUNK_SIZE)
            FountainCode.encoder(padded, CHUNK_SIZE).also { symbolEncoders[evidenceId] = it }
        }
        val cursor = symbolCursors.getOrPut(evidenceId) { AtomicInteger(0) }
        return List(wantCount) { encoder.symbol(cursor.getAndIncrement()) }
    }

    // ---------- what to offer a peer we just connected to ----------

    suspend fun relayableSos(): List<SosEntity> = sosDao.getRelayable().filter { it.ttl > 0 }

    suspend fun relayableEvidenceMeta(): List<EvidenceEntity> = evidenceDao.getRelayable().filter { it.ttl > 0 }

    /** P5 slice 1 (docs/DECISIONS.md decision 45, PLAN-v2.md §4.3's "thumbnail-first, full-res
     *  pull-on-demand") — the narrower set actually eligible to have its FULL chunk set solicited,
     *  as opposed to [relayableEvidenceMeta] (header + thumbnail, still flooded to everyone
     *  including blind relays, completely unchanged by this slice). Own-authored content
     *  (`senderIsMe`) is always included — a device always wants its own content to actually
     *  reach members. Everything else needs `wantsFullRes` (see [requestFullResolution]) — most
     *  importantly, a blind-carried row (`groupId == null`) can NEVER have `wantsFullRes` set
     *  (that function refuses it outright), so this set is implicitly member-only, closing the gap
     *  `PLAN-v2.md` §9.2 item 8 names: a blind relay in a busy crowd no longer solicits or carries
     *  full-resolution ciphertext for content it can't decrypt, only the small header+thumbnail.
     *
     *  [RelayResponder.framesToPushOnConnect] reads this (not [relayableEvidenceMeta]) to decide
     *  which items to send OUR OWN `SymbolRequest` for — and sending one is what actually solicits
     *  symbols back (decision 47 replaced the manifest this doc originally described with
     *  [symbolDeficit]/[symbolsToSend] underneath, but this pull-gating decision itself — *whether*
     *  to solicit full resolution — stayed conceptually unchanged; see decision 45's own closing
     *  note), so narrowing this set remains the entire mechanism. */
    suspend fun fullResRelayable(): List<EvidenceEntity> = evidenceDao.getFullResRelayable()

    /** Marks [evidenceId] as wanted at full resolution — the user-facing "load full image" action.
     *  Refuses outright (returns `false`, no DB change) for a blind-carried row (`groupId == null`)
     *  — there is nothing to view without the group key, and allowing it would silently defeat
     *  [fullResRelayable]'s own member-only guarantee. Bumps [epoch] on success, the same "worth
     *  reconnecting early for" signal [RelayEngine.admitCourierEnvelope] already uses, so
     *  `ConnectionAttemptTracker` can fast-path a reconnect to actually deliver the request. */
    suspend fun requestFullResolution(evidenceId: String): Boolean {
        val meta = evidenceDao.get(evidenceId) ?: return false
        if (meta.groupId == null) return false
        evidenceDao.setWantsFullRes(evidenceId)
        epoch.incrementAndGet()
        return true
    }

    /** Opens [evidence]'s sealed thumbnail for display — the UI-facing counterpart to
     *  [MeshFrameCodec.sealThumbnail]/`RelayEngine.createEvidence`'s own seal call. Null for a
     *  blind-carried row (`groupId == null` — no key to resolve, same refusal
     *  [requestFullResolution] already makes), an empty/absent thumbnail, or a decrypt failure
     *  (wrong/rotated key, tampered bytes). Never persisted decrypted — this app already keeps
     *  reassembled full-res evidence as plaintext on disk once complete (see `outputFile`), but a
     *  thumbnail is cheap enough to decrypt fresh on every render rather than adding a second
     *  at-rest-plaintext surface for something this small. */
    suspend fun decryptedThumbnail(evidence: EvidenceEntity): ByteArray? {
        val groupId = evidence.groupId ?: return null
        if (evidence.thumbnail.isEmpty()) return null
        val rootKey = repo.getGroupKey(groupId) ?: return null
        val contentKey = CryptoUtils.contentEpochKey(rootKey, evidence.timestamp / MILLIS_PER_SECOND)
        return MeshFrameCodec.openThumbnail(evidence.thumbnail, contentKey)
    }

    // "What do we hold" (any ttl, including 0) is a different question from "what do we still
    // forward" (relayableSos/relayableEvidenceMeta above, ttl > 0 only) — used to build the
    // outgoing CatalogFilter (see RelayResponder.currentCatalogKeys). An item at ttl 0 has stopped
    // propagating but is still held until the 48h prune; advertising it in the catalog filter
    // stops peers from re-pushing it to us on every connection for the rest of its retention
    // window, without relaying it any further ourselves — relayableSos/relayableEvidenceMeta are
    // still what gates actual sends, completely unaffected by this pair.
    suspend fun heldSosIds(): List<String> = sosDao.allIds()

    suspend fun heldEvidenceIds(): List<String> = evidenceDao.allIds()

    /** Looks up one evidence item's header by id — used by `RelayResponder.pushFullResRequestNow`
     *  to re-derive a `groupId` from just an `evidenceId`. (Through v0.7.15-dev this doc also named
     *  `WifiDirectHandoffCoordinator` as a caller; decision 49, docs/DECISIONS.md, removed Wi-Fi
     *  Direct outright, so this is now this function's only caller.) */
    suspend fun evidenceMeta(id: String): EvidenceEntity? = evidenceDao.get(id)

    // symbolsByEsi (mirrored the retired chunksByIndexes' shape) lived here through v0.7.15-dev —
    // deleted by decision 49 (docs/DECISIONS.md) alongside its only caller,
    // WifiDirectHandoffCoordinator's own positional-index handoff path.

    suspend fun nicknamesForGroup(groupId: String): List<NicknameEntity> = nicknameDao.getForGroup(groupId)

    /** Per-group equivalent of [RelayResponder.currentCatalogKeys] (private, in that file) — that
     *  one deliberately COMBINES every active group into one list, matching GATT's "one filter per
     *  connection" design. This is scoped to a single group instead, for `BeaconRadio`'s Tier B
     *  catalogue filter (decision 34, `docs/DECISIONS.md`), which is broadcast per-group (one
     *  beacon per rotating group id) and must not fold another group's activity into it — that
     *  would both misrepresent what a filter for THIS group actually covers and widen the passive-
     *  observable signal decision 34 already accepted a narrower version of. Same exact key format
     *  (`"sos:<id>"` / `"evid:<id>"` / `"nick:<groupId>:<senderId>:<updatedAt>"`) as
     *  [RelayResponder.currentCatalogKeys] — kept in sync by doc only, since the two serve genuinely
     *  different scoping and neither calls the other. */
    suspend fun catalogKeysForGroup(groupId: String): List<String> {
        val keys = mutableListOf<String>()
        for (id in sosDao.idsForGroup(groupId)) keys += "sos:$id"
        for (id in evidenceDao.idsForGroup(groupId)) keys += "evid:$id"
        for (n in nicknamesForGroup(groupId)) keys += "nick:${n.groupId}:${n.senderId}:${n.updatedAt}"
        return keys
    }

    /**
     * Called periodically from MeshService. Not a permanent archive — deletes content past
     * CONTENT_MAX_AGE_MILLIS (this covers both age-expiry and anything a group's own deletion
     * missed, since a group being dismantled removes its evidence/SOS rows immediately via
     * GroupRepository — this is the backstop, not the primary path for that case).
     */
    suspend fun pruneExpired() {
        val cutoff = System.currentTimeMillis() - CONTENT_MAX_AGE_MILLIS
        val expiredIds = evidenceDao.idsOlderThan(cutoff)
        for (id in expiredIds) {
            symbolDao.deleteForEvidence(id)
            // Bounds liveDecoders/symbolCursors/symbolEncoders growth to active items — without
            // this they'd only ever shrink on completion (maybeCompleteFromSymbol), never on an
            // item that expires still incomplete (or, for symbolEncoders, a complete item whose
            // 48h retention window has simply passed — CR-16, PLAN-v2.md Part 10).
            liveDecoders.remove(id)
            symbolCursors.remove(id)
            symbolEncoders.remove(id)
        }
        evidenceDao.pruneOlderThan(cutoff)
        sosDao.pruneOlderThan(cutoff)
        seenDao.prune(System.currentTimeMillis() - SEEN_ID_MAX_AGE_MILLIS)
        // Own cutoff, not the 48h `cutoff` above — PLAN-v2.md §4.2's own "24 h" courier TTL (see
        // COURIER_MAX_AGE_MILLIS's own doc for why it's deliberately shorter).
        courierEnvelopeDao.pruneOlderThan(System.currentTimeMillis() - COURIER_MAX_AGE_MILLIS)
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
