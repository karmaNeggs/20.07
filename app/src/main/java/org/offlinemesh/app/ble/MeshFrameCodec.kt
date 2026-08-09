package org.offlinemesh.app.ble

import org.offlinemesh.app.crypto.CryptoUtils
import org.offlinemesh.app.crypto.SenderIdentity
import org.offlinemesh.app.data.EvidenceEntity
import org.offlinemesh.app.data.NicknameEntity
import org.offlinemesh.app.data.SosEntity
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/**
 * Encodes/decodes the application-level frames exchanged over the GATT relay characteristic —
 * distinct from MeshProtocol's beacon payload, which never touches a GATT connection.
 *
 * Two properties this codec is responsible for, beyond "turn a struct into bytes":
 *  - **Frugality.** Everything is a compact binary layout, not JSON. On a link where a chunk is
 *    ~400 bytes and MTU is ~517, JSON keys, a 64-char hex digest, and repeated UUID strings were
 *    pure overhead on every single frame. Ids/digests travel as raw bytes; there are no field
 *    names on the wire.
 *  - **Authenticity / confidentiality.** SOS and evidence-meta frames carry an HMAC(group_key) tag
 *    so a phone without the key cannot forge one a member will act on. Position frames are sealed
 *    with AES-GCM under the group key — live GPS never crosses the mesh in the clear, not even to
 *    the blind relays that carry it. This codec only frames the sealed bytes / tags; RelayResponder
 *    owns the key and does the actual seal/open/verify, so decode() stays keyless and pure.
 */
object MeshFrameCodec {
    // 0x10 and 0x14 are RETIRED (were FRAME_MANIFEST/FRAME_EVID_CHUNK through v0.7.13-dev) — never
    // reuse these byte values. Decision 47 (docs/DECISIONS.md) replaced the indexed-chunk/manifest/
    // have-bitset/deficit mechanism with FountainCode.kt's fountain code; see FRAME_SYMBOL_REQUEST/
    // FRAME_EVID_SYMBOL below.
    const val FRAME_SOS: Byte = 0x12        // SOS item + auth tag
    const val FRAME_EVID_META: Byte = 0x13  // evidence header + auth tag
    const val FRAME_POSITION: Byte = 0x15   // AES-GCM-sealed live position — latest-wins, never persisted
    const val FRAME_NICKNAME: Byte = 0x16   // per-group display name + auth tag, latest-updatedAt-wins
    const val FRAME_PRESENCE: Byte = 0x17   // "an authenticated member of this group is on this connection"
    // Bloom filter of held sos/evidence-header/nickname keys — see CatalogFilter.
    const val FRAME_CATALOG_FILTER: Byte = 0x18
    // 0x19/0x1A/0x1B are RETIRED (were FRAME_WIFI_DIRECT_CAP/HANDOFF/ACCEPT through v0.7.15-dev) —
    // never reuse these byte values. Decision 49 (docs/DECISIONS.md) removed Wi-Fi Direct outright
    // (PLAN-v2.md §4.3 item 3), replaced by BLE L2CAP CoC (decision 48, FRAME_L2CAP_CAP below) and
    // — later, a separate slice — Wi-Fi Aware.

    // P4 slice 3 (docs/DECISIONS.md decision 43, PLAN-v2.md §4.2) — a courier envelope, spray-and-
    // wait store-and-carry delivery for a partition flood-relay alone can't bridge in time.
    const val FRAME_COURIER: Byte = 0x1C

    // P5 item 2 slice 2 (docs/DECISIONS.md decision 47, PLAN-v2.md §4.3) — fountain-coded evidence
    // transfer, replacing FRAME_MANIFEST/FRAME_EVID_CHUNK (0x10/0x14, retired above).
    const val FRAME_SYMBOL_REQUEST: Byte = 0x1D // "I still need N more distinct symbols for X"
    const val FRAME_EVID_SYMBOL: Byte = 0x1E    // one fountain-coded symbol: esi + payload

    // P5 item 3 (docs/DECISIONS.md's own entry for this slice, PLAN-v2.md §4.3) — advertises this
    // device's listening BLE L2CAP CoC PSM, the real bulk pipe SymbolRequest/EvidSymbol traffic can
    // move onto instead of GATT. See L2capBulkTransport's own class doc.
    const val FRAME_L2CAP_CAP: Byte = 0x1F // "here's the PSM to open an L2CAP channel to me on"

    /** Display names are a small courtesy label, not an identity — kept short so it stays a
     *  one-line, cheap-to-relay addition rather than a second chat field. */
    const val MAX_USERNAME_CHARS = 20

    /** Absolute ceiling on any wire-carried `totalChunks` (= a [FountainCode] `k`) AND on any
     *  wire-carried symbol `esi` (`Frame.EvidSymbol.esi`, `RelayEngine.ingestSymbol`'s own decode-
     *  time bound — the [FountainDecoder] instance it's fed into allocates state proportional to
     *  esi/k). Without this, an unauthenticated, non-member relay can send one ~120-byte
     *  evidence-meta frame claiming e.g. `totalChunks = Int.MAX_VALUE` and force a huge allocation
     *  on every device that relays it — worse, that header is persisted to Room and re-offered on
     *  every future connection (see [org.offlinemesh.app.ble.RelayResponder.framesToPushOnConnect]),
     *  so the crash recurs until the 48h prune. A blind relay (one that can't resolve this frame's
     *  `handle` to a group key — see `GroupRepository.resolveGroupKeyByHandle`, decision 38) stores
     *  this header regardless, so this frame type has no authentication gate at all for that path —
     *  the length cap here is the only line of defense. 4096 chunks * 400 bytes/chunk
     *  (`RelayEngine.CHUNK_SIZE`) = 1.6MB, generous against `EvidenceCapture`'s 640px/quality-45
     *  JPEGs (typically ~200 chunks). Through v0.7.13-dev this also bounded [MeshProtocol]'s
     *  now-removed `encodeBitset`/`decodeBitset`; decision 47 repurposed it as the esi/k ceiling for
     *  [FountainCode]/[FountainDecoder] instead — same number, same resource-exhaustion role. */
    const val MAX_EVIDENCE_CHUNKS = 4096

    /** Absolute ceiling on an SOS message's UTF-8 byte length. [writeStr16]/[readStr16] can
     *  represent up to 65535 bytes, but nothing upstream ever intends a message that large — this
     *  cap exists so [decode] can reject anything past it as malformed, rather than accepting an
     *  arbitrarily large message from a wire frame with no size hint anyone actually chose. */
    const val MAX_SOS_MESSAGE_BYTES = 2000

    /** P5 slice 1 (`docs/DECISIONS.md` decision 45, `PLAN-v2.md` §4.3's "thumbnail-first, full-res
     *  pull-on-demand") — ceiling on [Frame.EvidMeta.thumbnail], which as of this same decision's
     *  own follow-up carries [sealThumbnail]'s SEALED output, not a raw JPEG. A blind relay stores
     *  this header regardless — it can't resolve a group key, so it can never open the seal either
     *  way — so this decode-time length cap exists purely as the resource-exhaustion guard
     *  [MAX_EVIDENCE_CHUNKS] plays for `totalChunks`: a hostile frame claiming an enormous
     *  "thumbnail" field still costs real storage/bandwidth to carry even though nobody without the
     *  key could ever render it. 256 bytes (sealed, i.e. plaintext + AES-GCM's 28-byte nonce+tag
     *  overhead — see `EvidenceCapture.compressThumbnail`'s own smaller plaintext target) is chosen
     *  by working backward from this app's own proven-reliable write budget, not a "looks nice"
     *  number: a single ATT characteristic write is capped at 512 bytes regardless of negotiated
     *  MTU, [PAD_BUCKETS]' own `512` bucket is the largest size class the rest of this app's
     *  traffic already relies on (`FRAME_EVID_SYMBOL`'s 400-byte symbols + overhead land there), and
     *  an `EvidMeta` frame's own fixed fields already consume roughly ~221 bytes before the
     *  thumbnail — 256 more keeps the whole padded frame inside that same 512 bucket rather than
     *  pushing it into the 1024/2048 zone this codebase already treats as higher-risk (see
     *  [MAX_SOS_MESSAGE_BYTES]'s own frame, similarly sized, similarly flagged in decision 40's own
     *  write-up as an existing MTU-fragmentation gap). */
    const val MAX_THUMBNAIL_BYTES = 256

    /** AES-GCM's fixed per-message overhead — [CryptoUtils.encryptWithNonce]'s 96-bit nonce (12
     *  bytes) prepended to the ciphertext, plus its 128-bit authentication tag (16 bytes) appended
     *  by the cipher itself. Exposed here (not just inlined) so [MAX_THUMBNAIL_BYTES] (the SEALED
     *  ceiling) and `EvidenceCapture.compressThumbnail`'s plaintext target derive from the same
     *  number instead of two call sites silently agreeing on 28 by coincidence. */
    const val GCM_OVERHEAD_BYTES = 28

    /** Largest value a single unsigned wire byte can carry — hop/ttl fields coerce into this. */
    private const val MAX_UNSIGNED_BYTE = 255

    /** millis-to-epoch-seconds conversion, for [groupHandle]'s callers (decision 38). */
    private const val MILLIS_PER_SECOND = 1000L

    // v3: presence gained an envelope hop too, for the same blind-relay reason.
    // v2 -> v3: the position frame's hop moved from inside the encrypted body into the cleartext
    // envelope, so a non-member can blind-relay positions (see Frame.PositionSealed's doc).
    // v1 -> v2 (0.3.0): every frame type gained a field (Sos/EvidMeta/Nickname/Presence's
    // additive `signature`, Presence's `senderPublicKey`, Position's inner signed body) — bumped so
    // a v1 build and a v2 build talking to each other get a clean, explicit "different version,
    // drop it" via decode()'s own version check, instead of a v2 peer's readBlob() calls silently
    // throwing (caught, returned null) partway through an old-shape v1 frame.
    // Not private — a test that needs to hand-construct a raw frame (to exercise a malformed field
    // encode() itself would never produce, e.g. a hostile totalChunks) must reference this directly
    // rather than duplicate the literal, which is exactly what silently went stale across this bump.
    // v4: SOS frames gained a cleartext envelope hop byte (see SosEntity.hop's doc) — same
    // treatment as v3's position hop move, and for the same reason (docs/DECISIONS.md decision 16).
    // v5: SOS frames gained isAlert (see SosEntity.isAlert's doc, docs/DECISIONS.md decision 35) —
    // splits the loud/broadcast alert treatment from ordinary quiet messages sharing this same
    // entity/frame.
    // v6: SOS frames now AES-GCM seal senderId/message/timestamp/isAlert under the group key
    // instead of cleartext-plus-HMAC (see SosEntity.sealed's doc, docs/DECISIONS.md decision 37) —
    // any nearby non-member relay could previously read the message text directly.
    // v7: every frame type except CatalogFilter drops its cleartext `groupId` field, replaced with
    // an opaque rotating `handle` (see groupHandle's doc, docs/DECISIONS.md decision 38) — a
    // passive observer could previously correlate mesh traffic to a specific group just by reading
    // this field, with no key needed at all.
    // v8: no inner field change (every Frame subclass and decode() branch below is byte-identical
    // to v7). Bumped because MeshGattClient/MeshGattServer now wrap every outgoing frame in a
    // [padGattFrame]/[unpadGattFrame] envelope (2-byte real-length prefix + random padding up to
    // the next size bucket) before it ever reaches this codec's own encode/decode — a build without
    // that wrapper reads the new envelope's length-prefix bytes as if they were a raw frame's own
    // type/version bytes and silently drops it (garbage type, or a version mismatch on the rare byte
    // that happens to collide with a real FRAME_* constant). Bumped anyway, same as every prior wire
    // change here, as the one discoverable "this build's wire format changed" signal — even though
    // the mismatch here is caught by the transport wrapper failing to parse, not by this VERSION
    // check itself. See [padGattFrame]'s own doc for why padding lives outside this codec's
    // encode/decode functions instead of inside them (Tier B reuses [encodePosition]/
    // [encodeCatalogFilter] verbatim for its own, far tighter budget — see MeshProtocol's
    // BROADCAST_TIER_BUDGET_BYTES — and must never see a bucket-padded frame).
    // v9: new FRAME_COURIER (docs/DECISIONS.md decision 43, PLAN-v2.md §4.2's courier/spray-and-wait
    // item) — a genuinely new frame type, not a reused byte, so an old build's decode() would
    // already hit its own `else -> null` branch and drop it safely without a version bump. Bumped
    // anyway, matching this project's own standing discipline (see v8's note above) of treating
    // every wire-affecting change as one discoverable signal rather than distinguishing "needed for
    // safety" from "needed for discoverability."
    // v10: FRAME_EVID_META gains `thumbnail` (docs/DECISIONS.md decision 45, PLAN-v2.md §4.3's
    // thumbnail-first item) — a genuine field-shape change on an existing frame type (unlike v9's
    // new-byte-type addition), so an old build parsing a new-shape frame would misread every field
    // after the new one. This IS load-bearing for safety, not just discoverability.
    // v11: two independent load-bearing changes (docs/DECISIONS.md decision 47, PLAN-v2.md §4.3
    // item 2 — fountain coding replaces indexed chunks/manifest/have-bitset/deficit computation).
    // (1) FRAME_MANIFEST/FRAME_EVID_CHUNK (0x10/0x14) retired, replaced by FRAME_SYMBOL_REQUEST/
    // FRAME_EVID_SYMBOL (0x1D/0x1E) — an old build's decode() would misread the new types as
    // unrecognized bytes and safely drop them (same as v9's new-byte case), but the retired bytes
    // themselves must never be reused while any pre-v11 build might still be reachable. (2)
    // FRAME_EVID_META gains `contentLength` — a genuine field-shape change, same load-bearing
    // reasoning as v10's own thumbnail addition.
    // v12: P5 item 3 (docs/DECISIONS.md's own entry for this slice, PLAN-v2.md §4.3's bulk-pipe
    // item) — new FRAME_L2CAP_CAP (0x1F), a genuinely new byte, same "not strictly required but
    // bumped anyway for discoverability" case v9's FRAME_COURIER already established. Also retires
    // FRAME_WIFI_DIRECT_CAP/HANDOFF/ACCEPT (0x19/0x1A/0x1B, deleted alongside Wi-Fi Direct outright
    // in this same slice's second commit) — pure removal isn't independently load-bearing (an old
    // build's decode() already safely no-ops on any byte it doesn't recognize), but bundled under
    // this same bump rather than left undiscoverable, matching decision 47's own v11 precedent of
    // bundling a retirement and an addition under one bump.
    const val VERSION: Int = 12

    private const val PAD_BUCKET_1 = 256
    private const val PAD_BUCKET_2 = 512
    private const val PAD_BUCKET_3 = 1024
    private const val PAD_BUCKET_4 = 2048

    /** Bucket sizes for [padGattFrame], matching bitchat's own Noise-packet padding scheme (see
     *  `PLAN-v2.md` §4.4) — adopted here for every GATT frame type, not just encrypted ones. */
    val PAD_BUCKETS = intArrayOf(PAD_BUCKET_1, PAD_BUCKET_2, PAD_BUCKET_3, PAD_BUCKET_4)

    /** How many bytes [padGattFrame]/[unpadGattFrame]'s length prefix uses. */
    private const val LENGTH_PREFIX_BYTES = 2

    /** Largest value a [LENGTH_PREFIX_BYTES]-byte unsigned length prefix can represent. */
    private const val MAX_UNSIGNED_SHORT = 0xFFFF

    /** Bit width of one byte — used to split/reassemble [LENGTH_PREFIX_BYTES]'s two bytes. */
    private const val BITS_PER_BYTE = 8

    /** Masks a [Byte] promoted to [Int] back down to its unsigned 8-bit value. */
    private const val BYTE_MASK = 0xFF

    /** Prefix + pad an already-encoded GATT frame to the next [PAD_BUCKETS] size, so a passive
     *  observer sizing GATT writes can't fingerprint frame type/content length (e.g. distinguish a
     *  short SOS from a long one, or a nickname push from a presence heartbeat) from wire length
     *  alone. Deliberately NOT built into [encode]/[decode]/the per-type `encodePosition`/
     *  `encodeCatalogFilter` functions themselves: those two are also called directly by
     *  `BeaconRadio` to embed the identical bytes into a Tier B beacon payload, whose
     *  `MeshProtocol.BROADCAST_TIER_BUDGET_BYTES` (251) budget could never absorb a 256+ byte
     *  bucket floor. Called only from `MeshGattClient`/`MeshGattServer`'s write/notify choke points,
     *  which Tier B's connectionless advertising path never touches.
     *
     *  Wire shape: `[realLen: UShort BE][frame: realLen bytes][padding: random bytes]`. The
     *  length prefix (not a sentinel/terminator inside the padding) is what lets [unpadGattFrame]
     *  recover the exact frame boundary regardless of frame contents — needed because
     *  `FRAME_EVID_SYMBOL`'s own decode branch reads its symbol payload via `buf.remaining()` (no
     *  internal length field of its own) and would otherwise swallow trailing padding as symbol data.
     *  Padding bytes are drawn from [CryptoUtils.randomBytes], not zero-filled, so the padded region
     *  isn't visually distinguishable from the ciphertext/MAC bytes that usually precede it.
     *
     *  A frame already at or above the largest bucket (e.g. a near-[MAX_SOS_MESSAGE_BYTES] SOS) is
     *  sent with the length prefix but no padding — same pre-existing single-write-per-frame/no-MTU-
     *  fragmentation gap either way, not something padding introduces or fixes. */
    fun padGattFrame(frame: ByteArray): ByteArray {
        require(frame.size <= MAX_UNSIGNED_SHORT) {
            "frame too large for a UShort length prefix: ${frame.size}"
        }
        val unpaddedTotal = LENGTH_PREFIX_BYTES + frame.size
        val bucket = PAD_BUCKETS.firstOrNull { it >= unpaddedTotal } ?: unpaddedTotal
        val padLen = bucket - unpaddedTotal
        val out = ByteArray(bucket)
        out[0] = (frame.size ushr BITS_PER_BYTE).toByte()
        out[1] = frame.size.toByte()
        System.arraycopy(frame, 0, out, LENGTH_PREFIX_BYTES, frame.size)
        if (padLen > 0) {
            System.arraycopy(CryptoUtils.randomBytes(padLen), 0, out, unpaddedTotal, padLen)
        }
        return out
    }

    /** Inverse of [padGattFrame]. Returns null on anything malformed/truncated (too short for even
     *  the length prefix, or a claimed length longer than what actually arrived) rather than
     *  throwing — same "drop rather than misparse" posture as [decode]'s own VERSION check, since
     *  this runs on bytes from an unauthenticated peer before any frame-level auth check applies. */
    fun unpadGattFrame(bytes: ByteArray): ByteArray? {
        if (bytes.size < LENGTH_PREFIX_BYTES) return null
        val realLen = ((bytes[0].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or (bytes[1].toInt() and BYTE_MASK)
        if (LENGTH_PREFIX_BYTES + realLen > bytes.size) return null
        return bytes.copyOfRange(LENGTH_PREFIX_BYTES, LENGTH_PREFIX_BYTES + realLen)
    }

    private val UTF8 = StandardCharsets.UTF_8

    sealed class Frame {
        /** Envelope only — RelayResponder opens [sealed] with the group key via [openSos]. Same
         *  shape as [PositionSealed] and the same reasoning: [id]/[ttl]/[hop] live out here in the
         *  cleartext envelope so a phone holding no key for [handle]'s group can still dedup,
         *  flood-control, and carry this frame onward without ever learning its content — see
         *  [SosEntity.sealed]'s own doc (decision 37, `docs/DECISIONS.md`) for why this replaced
         *  the old cleartext-message-plus-HMAC shape, and [groupHandle]'s doc (decision 38) for why
         *  [handle] replaced the cleartext `groupId` this envelope used to carry directly. [id]
         *  specifically has to stay cleartext (not just inside the seal) because it's what both a
         *  member's `seenDao` dedup AND a blind relay's own ciphertext-independent dedup key off —
         *  see [RelayResponder]'s `takeOpaqueSosCustody`. */
        data class SosSealed(val handle: ByteArray, val id: String, val ttl: Int, val hop: Int, val sealed: ByteArray) :
            Frame()
        /** Envelope only, since decision 38 (`docs/DECISIONS.md`) — [handle] replaces the cleartext
         *  `groupId` this used to decode directly into a ready [EvidenceEntity] with. A receiver
         *  now has to resolve [handle] to a real groupId first (see
         *  [org.offlinemesh.app.data.GroupRepository.resolveGroupKeyByHandle]) before it can verify
         *  [mac]/[signature] or construct a full entity — see [EvidenceEntity.groupId]'s own doc
         *  for what happens when that resolution fails (a blind relay still stores a row, with
         *  `groupId = null`, keeping ONLY this header — see [thumbnail]'s own doc for why, as of
         *  P5 slice 1, a blind relay no longer solicits or carries the full chunk set at all).
         *
         *  [thumbnail] (P5 slice 1, `docs/DECISIONS.md` decision 45, `PLAN-v2.md` §4.3) — carries
         *  [sealThumbnail]'s AES-GCM output, NOT a raw preview. The decision's own first pass shipped
         *  this cleartext-plus-MAC, matching every other field here; caught before landing as a real
         *  passive-exposure increase (any nearby device that connects — automatic, no interaction
         *  needed — would otherwise see a genuine visual hint: crowd vs. document vs. night scene,
         *  a step up from this header's existing metadata-only fields) and corrected to seal it
         *  under the same content-epoch key SOS/position bodies already use. A blind relay still
         *  stores and forwards the opaque bytes (same "carry without reading" role every other
         *  sealed field in this app plays) but can never render a preview — only a member holding
         *  the key can [openThumbnail] it. Empty (never sealed) when no thumbnail exists — see
         *  [sealThumbnail]'s own no-op-on-empty shortcut. Capped at [MAX_THUMBNAIL_BYTES] — see that
         *  constant's own doc for the exact byte-budget reasoning, now against the SEALED size.
         *  Still covered by [evidMacInput] on top of the seal's own GCM tag — binds this specific
         *  sealed blob to this specific header's other fields, so a relay can't splice a
         *  validly-sealed thumbnail from a DIFFERENT evidence item onto this one; the two checks
         *  cover different substitution attacks, not the same one twice. Every other field here is
         *  unchanged from what [EvidenceEntity] itself carries.
         *
         *  [contentLength] (P5 item 2 slice 2, `docs/DECISIONS.md` decision 47) — the exact
         *  ciphertext byte length, appended at v11. [totalChunks] already equals a [FountainCode]
         *  `k` (`ceil(contentLength / RelayEngine.CHUNK_SIZE)`, unchanged in meaning from the old
         *  chunk-count field it always was), but `k` alone only bounds a RANGE of possible content
         *  lengths — [FountainDecoder] needs the exact byte count to strip the final symbol's zero
         *  padding at `decode()` time, which `k` cannot supply on its own. */
        data class EvidMeta(
            val id: String,
            val handle: ByteArray,
            val senderId: String,
            val timestamp: Long,
            val sha256: String,
            val totalChunks: Int,
            val mimeType: String,
            val ttl: Int,
            val mac: ByteArray?,
            val signature: ByteArray?,
            val thumbnail: ByteArray,
            val contentLength: Int,
        ) : Frame()
        /** One fountain-coded symbol for [evidenceId] — verbatim source data if `esi < k`
         *  (systematic) or an XOR combination if `esi >= k` (repair); see [FountainCode]'s own class
         *  doc. Not bound-checked here (matches the retired `EvidChunk`'s identical choice not to
         *  bound-check `chunkIndex` in `decode()`) — [RelayEngine.ingestSymbol] is where esi gets
         *  checked against [MAX_EVIDENCE_CHUNKS], the same architectural split `decode()` stays dumb/
         *  keyless, domain logic owns the bound check that this whole file's other frames follow.
         *  [ByteArray] equality is referential (same as every other `data class` here carrying one —
         *  [SosSealed]/[PositionSealed]/etc. — tests use `.contentEquals` directly rather than a
         *  per-class override). */
        data class EvidSymbol(val evidenceId: String, val esi: Int, val data: ByteArray) : Frame()
        /** "I still need [stillNeed] more distinct symbols for [evidenceId]" — replaces the retired
         *  `Manifest`'s bitset with a single scalar, matching what fountain coding actually needs to
         *  ask for (see [FountainDecoder.deficit]'s own doc: any [stillNeed] distinct symbols close
         *  the gap, not a specific positional set). No `handle`/mac, same as `Manifest` never carried
         *  either — [evidenceId] is already cleartext on every [EvidMeta] this app floods, so it is
         *  not new information; the only thing protecting content is that the requested symbols are
         *  still ciphertext the requester can't decrypt without the group key. [stillNeed] is
         *  intentionally NOT bound-checked in [decode] — unlike [EvidMeta.totalChunks], which feeds
         *  directly into an O(totalChunks) allocation inside decode() itself, nothing here allocates
         *  proportional to [stillNeed]; the existing per-connection symbol budget (`RelayResponder.
         *  consumeSymbolBudget`) is what actually bounds the cost of an inflated value, downstream in
         *  `RelayResponder.handleSymbolRequest`, not here. */
        data class SymbolRequest(val evidenceId: String, val stillNeed: Int) : Frame()
        /** Envelope only — RelayResponder opens [sealed] with the group key via [openPosition].
         *
         *  [hop] lives out here in the cleartext envelope, NOT inside [sealed], specifically so a
         *  phone that holds no key for [handle]'s group can still carry this frame onward and
         *  increment its hop — the same store-and-forward blind relaying SOS/evidence already get.
         *  Without that, positions could only ever travel member-to-member: a non-member relay
         *  dropped them outright, so a member two hops away behind a stranger's phone never
         *  appeared on the radar at all (confirmed live — every position ever received was hop=0).
         *  The sealed body still carries its own copy of the hop for the signature to cover;
         *  receivers use THIS one, since it reflects the path actually travelled. Exposing hop
         *  depth in cleartext reveals topology distance and nothing about who or where — the same
         *  tradeoff `SosEntity.ttl` already makes. [handle] replaced this envelope's own cleartext
         *  `groupId` in decision 38 — see [groupHandle]'s doc. */
        data class PositionSealed(val handle: ByteArray, val hop: Int, val sealed: ByteArray) : Frame()
        /** Envelope only, since decision 38 (`docs/DECISIONS.md`) — same shape [EvidMeta] gained:
         *  [handle] replaces the cleartext `groupId` this used to decode directly into a ready
         *  [NicknameEntity] with. Unlike [EvidMeta], a nickname a receiver can't resolve [handle]
         *  for never becomes a Room row at all — see [org.offlinemesh.app.ble.RelayResponder]'s
         *  `takeOpaqueNicknameCustody` (a genuinely new in-memory blind-relay path this decision
         *  adds, since nicknames never had one before: tracing the existing push paths showed a
         *  blind-relay-held nickname was already a dead end pre-decision-38, never re-served to
         *  anyone, so this is a strict improvement, not a new risk). */
        data class Nickname(
            val handle: ByteArray,
            val senderId: String,
            val username: String,
            val updatedAt: Long,
            val mac: ByteArray?,
            val signature: ByteArray?,
        ) : Frame()
        /** Not stored, not relayed — a direct-neighbor heartbeat proving group co-membership over the
         *  GATT link, so presence doesn't depend solely on hearing a beacon (which can be one-way).
         *  [senderPublicKey] is what a receiver pins per (groupId, senderId) on first sight — see
         *  [RelayResponder]'s pin-on-first-sight doc and `docs/DECISIONS.md`, decision 7. [signature]
         *  is the same additive per-sender Ed25519 tag every other frame type carries, over
         *  [presenceMacInput]'s bytes (computed over the REAL groupId, resolved from [handle] — see
         *  [groupHandle]'s doc, decision 38, for why the wire field itself is now opaque). */
        data class Presence(
            val handle: ByteArray,
            val senderId: String,
            val timestamp: Long,
            val mac: ByteArray?,
            val senderPublicKey: ByteArray? = null,
            val signature: ByteArray? = null,
            /** Hops travelled, in the cleartext envelope for exactly the same reason
             *  [PositionSealed.hop] is: a phone holding no key for [handle]'s group can neither
             *  verify [mac] nor read anything here, but it CAN still carry this onward and advance
             *  the hop (see [OpaqueFrameRelay]). Without it, a member with no GPS fix — who
             *  therefore pushes no position for the position path to piggyback on — was invisible
             *  past a non-member relay rather than merely distant. */
            val hop: Int = 0,
        ) : Frame()
        /** One filter per connection, covering ALL of the sender's current relayable sos/evidence-
         *  header/nickname holdings across every group — see [CatalogFilter] and
         *  [RelayResponder.handleIncoming]'s handling of this case for what the receiver does with
         *  it (computes and pushes its own deficit against it). Not stored, not relayed.
         *  [sizeBits] must travel alongside [seed]/[bits] — see [CatalogFilter.sizeBits]'s doc for
         *  why the receiver can't assume a fixed bit-space anymore now that filter size scales with
         *  catalog size. */
        data class CatalogFilter(val seed: Long, val sizeBits: Int, val bits: ByteArray) : Frame()

        /** "Here's the PSM to open a BLE L2CAP CoC channel to me on" (P5 item 3, decision 48,
         *  `docs/DECISIONS.md`) — device-level, no MAC: a forged/stale [psm] just makes
         *  [android.bluetooth.BluetoothDevice.createInsecureL2capChannel]`(psm)`'s `connect()`
         *  throw, never a forged transfer — nothing downstream trusts anything off the resulting
         *  raw socket without going through this same [decode] every other frame does (see
         *  [L2capBulkTransport]'s own class doc). Announced once per connection whenever this
         *  device has an active listening socket — see `RelayResponder.framesToPushOnConnect`.
         *  Replaces the retired `WifiDirectCap` (decision 49) as this codec's "here's how to reach
         *  my own bulk-transfer listener" announcement. */
        data class L2capCap(val psm: Int) : Frame()

        /** P4 slice 3 (`docs/DECISIONS.md` decision 43, `PLAN-v2.md` §4.2) — a courier envelope on
         *  the wire. [tag] replaces what would otherwise be a cleartext `groupId`, same role
         *  [SosSealed.handle]/[PositionSealed.handle] play — see [courierTag]'s own doc. [id] stays
         *  cleartext (not just inside the seal) for the identical reason [SosSealed.id] does: both a
         *  member's own dedup and a blind carrier's ciphertext-independent dedup key off it.
         *
         *  [createdAt] is cleartext here, unlike [SosBody.timestamp] (which lives only inside the
         *  seal) — a deliberate difference, not an oversight. A blind carrier storing this as a
         *  [org.offlinemesh.app.data.CourierEnvelopeEntity] row needs an honest age for
         *  [org.offlinemesh.app.ble.RelayEngine.pruneExpired]'s 24h cutoff, but can't open the seal
         *  to read a timestamp kept inside it — exposing creation time in the clear reveals nothing
         *  about who or where, the same tradeoff [PositionSealed.hop]/[SosSealed.hop] already make
         *  for topology distance instead of timing. It also lets the member-path open derive the
         *  exact content-epoch key in one shot (`contentEpochKey(rootKey, createdAt/1000)`) rather
         *  than SOS's [CryptoUtils.candidateContentEpochKeys] search — the same single-exact-epoch
         *  treatment decision 39 already gives evidence-meta/nickname/presence precisely because
         *  their own timestamp is likewise cleartext.
         *
         *  [copiesRemaining] travels verbatim, forwarded unchanged by this slice — see
         *  [org.offlinemesh.app.ble.RelayEngine.admitCourierEnvelope]'s own doc for why the actual
         *  copy-budget-halving arithmetic is a later P4 slice's job, not this one's. */
        data class Courier(
            val tag: ByteArray,
            val id: String,
            val createdAt: Long,
            val copiesRemaining: Int,
            val sealed: ByteArray,
        ) : Frame()
    }

    /** Decrypted inner of a position frame. [signature] is the sender's Ed25519 signature
     *  over [signedBytes] — carried INSIDE the AES-GCM-sealed envelope, not alongside it, so a
     *  blind relay (or anyone else without the group key) never sees this 64-byte, effectively
     *  per-sender-static fingerprint, which would otherwise let position traffic be correlated
     *  across time/movement even by an observer who can't read a single position's actual lat/lon.
     *  The caller ([RelayResponder]) verifies it against the pinned public key for [senderId] —
     *  [MeshFrameCodec] stays keyless/pure for that check, same as every other frame type here.
     *
     *  [signedBytes] is the exact wire bytes the signature covers, captured verbatim from the
     *  decrypted buffer rather than re-derived from [lat]/[lon] — those two have already round-
     *  tripped through a `/1e7` division into a [Double], and re-encoding via `* 1e7` is NOT
     *  guaranteed to reproduce the original signed integer bit-for-bit (float rounding can land one
     *  ULP short, e.g. `1234567 -> 0.1234567 -> 1234566.999999998 -> 1234566` after truncation) —
     *  that would make a perfectly genuine signature spuriously fail to verify. Capturing the raw
     *  bytes sidesteps the whole class of float round-trip bugs instead of trying to avoid it. */
    data class PositionBody(
        val senderId: String, val lat: Double, val lon: Double,
        val accuracyM: Int, val timestampSec: Long, val hop: Int,
        val signature: ByteArray?,
        val signedBytes: ByteArray,
    )

    /** Decrypted inner of a sealed SOS frame (decision 37, `docs/DECISIONS.md`) — same shape and
     *  reasoning as [PositionBody]: [signature] travels inside the seal rather than alongside it,
     *  verified once by the caller against the pinned public key for [senderId] and never persisted
     *  ([SosEntity] stores the plaintext fields directly, not this intermediate). [signedBytes] is
     *  captured verbatim from the decrypted buffer for the same reason [PositionBody.signedBytes]
     *  is — a signature must verify against the EXACT bytes it was computed over, not a re-derived
     *  encoding that could drift (SOS's own fields are all strings/longs/booleans here, no lossy
     *  float round-trip like position's lat/lon, but capturing verbatim costs nothing and keeps both
     *  body types following the identical, easy-to-audit pattern). */
    data class SosBody(
        val senderId: String, val message: String, val timestamp: Long, val isAlert: Boolean,
        val signature: ByteArray?,
        val signedBytes: ByteArray,
    )

    // ---------- canonical byte layouts the auth tags are computed over ----------
    // These MUST stay byte-for-byte stable: the sender computes the tag over these exact bytes and
    // every receiver recomputes it the same way. Deliberately excludes ttl (mutated per hop).
    //
    // sosMacInput (the old cleartext-plus-HMAC scheme) removed in decision 37, docs/DECISIONS.md —
    // superseded by sealSos's AES-GCM seal, whose own tag now provides this authentication.

    /** Broadcast-tier counterpart to the GATT-authoritative SOS scheme (decision 29,
     *  `docs/DECISIONS.md`) — deliberately excludes [senderId]. `BeaconRadio`'s Tier B SOS content
     *  broadcast is passively readable by ANY nearby BLE scanner (no connection needed), unlike a
     *  GATT [Frame.SosSealed] which now requires both connecting AND holding the group key (decision
     *  37) — carrying a per-install `senderId` here would still be a meaningfully larger, purely
     *  passive tracking surface than this app broadcasts anywhere else (position's own `senderId`
     *  stays inside its own AES-GCM seal; this field has no seal to hide behind).
     *
     *  **Deliberately still cleartext-by-design, unlike GATT's now-sealed content** — this isn't an
     *  oversight decision 37 left behind, it's a different purpose: decision 29's whole point was
     *  that an emergency alert being loud/discoverable to anyone nearby, member or not, is a
     *  legitimate feature (a bystander should be able to tell something's wrong), while decision 37
     *  is specifically about the AUTHORITATIVE record used for actual group coordination, which has
     *  no reason to be readable by a non-member. A SEPARATE mac from the GATT scheme's own — not
     *  reusable, not interchangeable, computed fresh under the same group key at broadcast time from
     *  whichever `SosEntity` is being mentioned, regardless of whether this device originated it or
     *  is holding a relayed copy (the content was already verified once, under `sealSos`'s own
     *  scheme, before being stored — see `RelayResponder.handleSos`). */
    fun broadcastSosMacInput(id: String, groupId: String, message: String, timestamp: Long): ByteArray =
        build { d -> d.writeStr(id); d.writeStr(groupId); d.writeSosMessage(message); d.writeLong(timestamp) }

    // P5 slice 1 (docs/DECISIONS.md decision 45): thumbnail added to the covered bytes — without
    // this, a relay could swap the thumbnail for different content while sha256/mac (computed over
    // the full-res ciphertext, never touching the thumbnail) stayed valid, exactly the class of gap
    // decision 37 already fixed once for SOS's own writeStr16-vs-writeStr mac-input mismatch.
    // P5 item 2 slice 2 (decision 47): contentLength added to the covered bytes for the identical
    // reason -- an uncovered contentLength would otherwise be a field a relay could tamper with
    // while every other mac'd field stayed valid (in practice self-defeating once sha256 is checked
    // post-decode, but cheap defense-in-depth matching this project's own repeated closing of
    // exactly this class of gap).
    @Suppress("LongParameterList") // wire-protocol scalars — see sealSos's identical suppress
    fun evidMacInput(
        id: String, groupId: String, senderId: String, timestamp: Long,
        sha256Hex: String, totalChunks: Int, mimeType: String, thumbnail: ByteArray,
        contentLength: Int,
    ): ByteArray = build { d ->
        d.writeStr(id); d.writeStr(groupId); d.writeStr(senderId); d.writeLong(timestamp)
        d.write(hexToBytes(sha256Hex)); d.writeInt(totalChunks); d.writeStr(mimeType)
        d.writeStr16Bytes(thumbnail); d.writeInt(contentLength)
    }

    fun nicknameMacInput(groupId: String, senderId: String, username: String, updatedAt: Long): ByteArray =
        build { d ->
            d.writeStr(groupId); d.writeStr(senderId); d.writeNicknameUsername(username); d.writeLong(updatedAt)
        }

    fun presenceMacInput(groupId: String, senderId: String, timestamp: Long): ByteArray =
        build { d -> d.writeStr(groupId); d.writeStr(senderId); d.writeLong(timestamp) }

    // wifiDirectHandoffMacInput/wifiDirectAcceptMacInput lived here through v0.7.15-dev — deleted
    // by decision 49 (docs/DECISIONS.md), Wi-Fi Direct's removal (PLAN-v2.md §4.3 item 3).

    // ---------- encode ----------

    /** The opaque wire handle for [key]'s group at [epochSeconds] — `HMAC(groupKey, epoch)` under
     *  the GATT-specific epoch window (decision 38, `docs/DECISIONS.md`; see
     *  [CryptoUtils.GATT_GROUP_HANDLE_WINDOW_SECONDS]'s own doc for why GATT needs a much wider
     *  window than the beacon's own 60s rotating id). Replaces the cleartext `groupId` field every
     *  relayed frame type used to carry, closing a passive-correlation gap `PLAN-v2.md` §4.4 names
     *  explicitly. The single computation point for all 5 relayed frame types: `RelayEngine` calls
     *  this ONCE at creation/first-ingest time and stores the result on the entity (`SosEntity`/
     *  `EvidenceEntity`/`NicknameEntity.handle`) for SOS/evidence/nickname; position/presence,
     *  never stored, call it fresh on every push instead — either way, once computed, a handle is
     *  forwarded VERBATIM on every subsequent relay hop, never recomputed (a blind relay has no key
     *  to recompute it with — see e.g. `reframeSosForRelay`'s doc). */
    fun groupHandle(key: ByteArray, epochSeconds: Long): ByteArray =
        CryptoUtils.rotatingAdvertisementId(key, epochSeconds, CryptoUtils.GATT_GROUP_HANDLE_WINDOW_SECONDS)

    // SOS frames are the second place in this app that repeatedly encrypts under a single, never-
    // rotated group key (decision 37, docs/DECISIONS.md) — same birthday-bound reasoning
    // positionNonceCounter's own doc gives, so this needs the identical deterministic-nonce
    // treatment position already has. Unlike position (resealed on every ~20s periodic push, so its
    // nonce needs a counter to disambiguate same-second sends from the same sender), a given SOS
    // [id] is sealed EXACTLY ONCE, ever — content is immutable once created (decision 29's own
    // note) — so hashing [id] alone into a nonce is sufficient: re-sealing the same id always
    // reproduces the same nonce AND the same plaintext, which is a no-op for GCM's safety property
    // (nonce reuse is only catastrophic across DIFFERENT plaintexts), and gives the same "same
    // content -> same ciphertext" stability `reframeSosForRelay` depends on to forward verbatim
    // without re-encrypting on every hop.
    private fun sosNonce(id: String): ByteArray = CryptoUtils.sha256(id.toByteArray(UTF8)).copyOf(GCM_NONCE_LEN)

    /** Produces JUST the raw AES-GCM sealed bytes — no envelope — for storing as
     *  [SosEntity.sealed]: that field's own doc says it "mirrors [PositionTracker.Record.sealed]'s
     *  exact shape," which is always raw ciphertext (what [decode]'s `FRAME_SOS` case extracts from
     *  an arriving frame), never a full framed wire message. [sealSos] wraps this with
     *  [reframeSosForRelay] for the "encode a frame to send right now" case; [RelayEngine.createSos]
     *  calls this directly instead, since IT needs the raw bytes for storage, and the actual send
     *  goes through [RelayResponder.floodForwardLocalSos] -> `reframeSosForRelay` moments later —
     *  calling [sealSos] there instead would double-frame (an entire wire frame nested inside what
     *  every reader downstream, e.g. [RelayResponder]'s `reframeStoredSos`, treats as raw
     *  ciphertext). [signingPrivateKey] optional for the same reason [encodePosition]'s is (this
     *  device may have no sender identity for [groupId] yet). */
    // LongParameterList: wire-protocol fields as plain scalars, matching every other MAC-input/
    // encode function in this file (e.g. encodePosition below already has 8) rather than
    // introducing a DTO type just for this one function — every other `@Suppress("LongParameterList")`
    // in this file points back to this comment rather than repeating it.
    @Suppress("LongParameterList")
    fun sealSosBody(
        key: ByteArray,
        id: String,
        senderId: String,
        message: String,
        timestamp: Long,
        isAlert: Boolean,
        signingPrivateKey: ByteArray? = null,
    ): ByteArray {
        val inner = build { d ->
            d.writeStr(senderId); d.writeSosMessage(message); d.writeLong(timestamp)
            d.writeByte(if (isAlert) 1 else 0)
        }
        val signature = signingPrivateKey?.let { SenderIdentity.sign(it, inner) }
        val innerWithSignature = build { d -> d.write(inner); d.writeBlob(signature) }
        return CryptoUtils.encryptWithNonce(key, innerWithSignature, sosNonce(id))
    }

    /** Seals the sensitive body with the group key AND frames it as a ready-to-send wire message in
     *  one call — same shape as [encodePosition]. Only a member holding the key can produce or read
     *  this, non-members that relay it move opaque bytes. See [sealSosBody]'s doc for why a caller
     *  that needs to STORE the sealed bytes (rather than send them immediately) must call that
     *  instead of this. [handle] is computed here (via [groupHandle], from [timestamp]) rather than
     *  taking a `groupId` param — since decision 38, the envelope carries an opaque handle, not a
     *  cleartext groupId. [rootKey]/[contentKey] are deliberately separate params (decision 39,
     *  `docs/DECISIONS.md`): [groupHandle] must keep using the permanent [rootKey] unchanged, while
     *  the actual seal uses [contentKey] (the caller's already-derived `CryptoUtils.
     *  contentEpochKey(rootKey, ...)`) — this function never derives one itself, so it stays a pure
     *  function of whatever keys it's handed, same as every other function in this file. */
    @Suppress("LongParameterList") // wire-protocol scalars — see sealSosBody's suppress
    fun sealSos(
        rootKey: ByteArray,
        contentKey: ByteArray,
        id: String,
        senderId: String,
        message: String,
        timestamp: Long,
        isAlert: Boolean,
        ttl: Int,
        hop: Int,
        signingPrivateKey: ByteArray? = null,
    ): ByteArray {
        val sealed = sealSosBody(contentKey, id, senderId, message, timestamp, isAlert, signingPrivateKey)
        val handle = groupHandle(rootKey, timestamp / MILLIS_PER_SECOND)
        return reframeSosForRelay(handle, id, ttl, hop, sealed)
    }

    /** Re-frames an already-sealed SOS for another hop **without needing the group key** — a blind
     *  relay (or a member forwarding someone else's SOS) moves the exact same opaque ciphertext
     *  along, only the envelope's ttl/hop differ, so it never learns the message while still
     *  carrying it. Same role [reframePositionForRelay] plays for position. [handle] is likewise
     *  forwarded verbatim, never recomputed — a blind relay has no key to recompute it with (see
     *  [groupHandle]'s doc, decision 38). */
    fun reframeSosForRelay(handle: ByteArray, id: String, ttl: Int, hop: Int, sealed: ByteArray): ByteArray =
        frame(FRAME_SOS) { d ->
            d.writeBlob(handle); d.writeStr(id); d.writeByte(ttl.coerceIn(0, MAX_UNSIGNED_BYTE))
            d.writeByte(hop.coerceIn(0, MAX_UNSIGNED_BYTE)); d.writeStr16Bytes(sealed)
        }

    // e.handle should always be populated by the time this is called — see EvidenceEntity.handle's
    // doc — writeBlob's null tolerance is defensive only, matching every other blob field here.
    // e.thumbnail already carries the SEALED bytes (see sealThumbnail's own doc) — encodeEvidMeta
    // itself stays keyless, same as every other encode function in this file; it never seals
    // anything, only frames whatever the caller already produced.
    fun encodeEvidMeta(e: EvidenceEntity): ByteArray = frame(FRAME_EVID_META) { d ->
        d.writeStr(e.id); d.writeBlob(e.handle); d.writeStr(e.senderId); d.writeLong(e.timestamp)
        d.write(hexToBytes(e.sha256)); d.writeInt(e.totalChunks); d.writeStr(e.mimeType)
        d.writeByte(e.ttl.coerceIn(0, 255)); d.writeBlob(e.mac); d.writeBlob(e.signature)
        d.writeStr16Bytes(e.thumbnail); d.writeInt(e.contentLength)
    }

    /** Domain-separated from [sosNonce]/[courierNonce] by a fixed label prefix, not just by
     *  whatever id happens to be passed — those two functions hash a bare id with no prefix, so an
     *  evidence id that ever collided with a SOS/courier envelope id under the SAME derived key
     *  would otherwise risk a nonce collision across genuinely different plaintexts. A thumbnail is
     *  sealed EXACTLY ONCE, ever, same "content is immutable once created" reasoning [sosNonce]'s
     *  own doc gives — hashing the id (now prefixed) is sufficient. */
    private fun thumbnailNonce(id: String): ByteArray =
        CryptoUtils.sha256("evid-thumb:$id".toByteArray(UTF8)).copyOf(GCM_NONCE_LEN)

    /** Seals a thumbnail under [contentKey] (P5 slice 1, `docs/DECISIONS.md` decision 45's own
     *  follow-up correcting that decision's original cleartext-plus-MAC design) — a non-member
     *  blind relay stores and forwards the resulting opaque bytes without ever being able to render
     *  a preview, closing the passive-exposure gap the cleartext version would have opened (any
     *  nearby device that connects — automatic, no interaction needed — would otherwise see a
     *  genuine visual hint: crowd vs. document vs. night scene). Only a member holding
     *  [contentKey] can [openThumbnail] it. Returns an empty array unchanged for an absent
     *  thumbnail — sealing zero bytes would just add ciphertext overhead for nothing to protect. */
    fun sealThumbnail(contentKey: ByteArray, id: String, thumbnail: ByteArray): ByteArray =
        if (thumbnail.isEmpty()) thumbnail else CryptoUtils.encryptWithNonce(contentKey, thumbnail, thumbnailNonce(id))

    /** Inverse of [sealThumbnail]. Null on a wrong key / tampered bytes — same "failed decrypt IS
     *  the auth failure" contract [openSos]/[openPosition] already establish. An empty [sealed]
     *  round-trips to an empty result without needing a real key, matching [sealThumbnail]'s own
     *  no-op-on-empty shortcut. */
    fun openThumbnail(sealed: ByteArray, contentKey: ByteArray): ByteArray? =
        if (sealed.isEmpty()) sealed else CryptoUtils.decrypt(contentKey, sealed)

    fun encodeEvidSymbol(s: Frame.EvidSymbol): ByteArray = frame(FRAME_EVID_SYMBOL) { d ->
        d.writeStr(s.evidenceId); d.writeInt(s.esi); d.write(s.data)
    }

    fun encodeSymbolRequest(evidenceId: String, stillNeed: Int): ByteArray =
        frame(FRAME_SYMBOL_REQUEST) { d -> d.writeStr(evidenceId); d.writeInt(stillNeed) }

    // Position frames are the one place in this app that repeatedly encrypts under a SINGLE key
    // shared by every member of a group, for as long as that group exists (days, potentially —
    // there's no key rotation). CryptoUtils.encrypt's random 96-bit IV is safe per NIST SP 800-38D
    // only up to roughly 2^32 encryptions under one key before the birthday bound becomes a real
    // (not just theoretical) collision risk — and GCM nonce reuse is catastrophic: it recovers the
    // XOR of both plaintexts AND breaks the authentication tag's forgery resistance. A busy group
    // over a multi-day event is the one traffic pattern in this app that could plausibly approach
    // that ceiling, so position frames get a nonce built to never repeat instead: a 4-byte prefix
    // derived from the sender's deviceId (stable forever, so restarts can't reuse an old prefix
    // against an old counter) plus the message's own timestampSec (4 bytes) plus an in-process
    // monotonic counter (4 bytes) disambiguating same-second sends. Two different senders'
    // 4-byte deviceId-hash prefixes colliding is already a ~1-in-4-billion-per-pair event; an
    // actual nonce collision additionally requires their timestampSec+counter to also coincide —
    // astronomically less likely than that. No persistence needed: the counter only has to be
    // unique *within* the current process's lifetime for a given prefix+second, and timestampSec
    // itself is what protects against reuse *across* restarts.
    private const val GCM_NONCE_LEN = 12 // AES-GCM's standard 96-bit nonce length, in bytes
    private const val NONCE_PREFIX_LEN = 4 // bytes of sha256(senderId) used as the nonce's sender prefix
    private const val NANO_TIME_SIGN_MASK = 0x7fffffffL // clears the sign bit so the seed is a non-negative Int
    private val positionNonceCounter = AtomicInteger((System.nanoTime() and NANO_TIME_SIGN_MASK).toInt())

    private fun positionNonce(senderId: String, timestampSec: Long): ByteArray {
        val buf = ByteBuffer.allocate(GCM_NONCE_LEN)
        buf.put(CryptoUtils.sha256(senderId.toByteArray(UTF8)), 0, NONCE_PREFIX_LEN)
        buf.putInt(timestampSec.toInt())
        buf.putInt(positionNonceCounter.incrementAndGet())
        return buf.array()
    }

    /** Seals the sensitive body with the group key before framing. Only a member holding the key
     *  can produce or read this — non-members that relay it move opaque bytes. Uses a deterministic
     *  nonce (see [positionNonce]) rather than [CryptoUtils.encrypt]'s random one — see the doc
     *  comment above [positionNonceCounter] for why this frame type specifically needs it.
     *
     *  [signingPrivateKey] is optional (this device may not have a sender identity for [groupId]
     *  yet, or the caller may be a blind relay authoring nothing of its own) — see
     *  [PositionBody.signature]'s doc for why the resulting signature travels INSIDE the seal, not
     *  alongside it. [rootKey]/[contentKey] deliberately separate (decision 39, `docs/DECISIONS.md`)
     *  — [groupHandle] must keep using the permanent [rootKey], the actual seal uses the caller's
     *  already-derived `CryptoUtils.contentEpochKey(rootKey, ...)`. */
    // groupId dropped (decision 38) — key is already a param, and the handle it derives (via
    // groupHandle) is all the envelope needs; a caller that also needs the real groupId string
    // (e.g. for a signing-key lookup) already has it from wherever it got `key`.
    @Suppress("LongParameterList") // wire-protocol scalars — see sealSosBody's suppress
    fun encodePosition(
        rootKey: ByteArray, contentKey: ByteArray, senderId: String, lat: Double, lon: Double,
        accuracyM: Int, timestampSec: Long, hop: Int, signingPrivateKey: ByteArray? = null,
    ): ByteArray {
        val inner = build { d ->
            d.writeStr(senderId)
            d.writeInt((lat * 1e7).toInt()); d.writeInt((lon * 1e7).toInt())
            d.writeByte(accuracyM.coerceIn(0, 255)); d.writeInt(timestampSec.toInt())
            d.writeByte(hop.coerceIn(0, 255))
        }
        val signature = signingPrivateKey?.let { SenderIdentity.sign(it, inner) }
        val innerWithSignature = build { d -> d.write(inner); d.writeBlob(signature) }
        val sealed =
            CryptoUtils.encryptWithNonce(contentKey, innerWithSignature, positionNonce(senderId, timestampSec))
        val handle = groupHandle(rootKey, timestampSec)
        return reframePositionForRelay(handle, hop, sealed)
    }

    /** Re-frames an already-sealed position for another hop **without needing the group key** —
     *  the whole point of keeping hop in the envelope (see [Frame.PositionSealed]). A blind relay
     *  moves the exact same opaque ciphertext along, only the hop byte differs, so it never learns
     *  a member's position while still carrying it. [handle] is likewise forwarded verbatim, never
     *  recomputed (see [groupHandle]'s doc, decision 38). */
    fun reframePositionForRelay(handle: ByteArray, hop: Int, sealed: ByteArray): ByteArray =
        frame(FRAME_POSITION) { d ->
            d.writeBlob(handle); d.writeByte(hop.coerceIn(0, MAX_UNSIGNED_BYTE)); d.writeStr16Bytes(sealed)
        }

    // n.handle should always be populated by the time this is called — see NicknameEntity.handle's
    // doc — writeBlob's null tolerance is defensive only, matching every other blob field here.
    fun encodeNickname(n: NicknameEntity): ByteArray =
        encodeNicknameFrame(n.handle, n.senderId, n.username, n.updatedAt, n.mac, n.signature)

    /** Re-frames an already-encoded nickname for another hop **without needing the group key** —
     *  new in decision 38 (`docs/DECISIONS.md`): nickname never needed this before (its old
     *  vacuous-auth blind-relay path never actually re-served a held row to anyone — see
     *  `RelayResponder.takeOpaqueNicknameCustody`'s doc), but a genuine `OpaqueFrameRelay` custody
     *  path needs a way to move the ORIGINAL bytes forward, same as every other frame type. Nothing
     *  in this frame mutates per hop (no hop/ttl field, unlike Sos/Position/Presence), so this is a
     *  structural no-op re-encode — kept as its own named function purely for consistency with the
     *  other 4 frame types' `reframeXForRelay` shape. */
    fun reframeNicknameForRelay(frame: Frame.Nickname): ByteArray =
        encodeNicknameFrame(frame.handle, frame.senderId, frame.username, frame.updatedAt, frame.mac, frame.signature)

    @Suppress("LongParameterList") // wire-protocol scalars — see sealSosBody's suppress
    private fun encodeNicknameFrame(
        handle: ByteArray?,
        senderId: String,
        username: String,
        updatedAt: Long,
        mac: ByteArray?,
        signature: ByteArray?,
    ): ByteArray = frame(FRAME_NICKNAME) { d ->
        d.writeBlob(handle); d.writeStr(senderId); d.writeNicknameUsername(username)
        d.writeLong(updatedAt); d.writeBlob(mac); d.writeBlob(signature)
    }

    /** Computes the tag internally (like encodePosition takes the key) — there's no stored entity
     *  for a presence heartbeat, it's generated fresh each connect. [senderPublicKey]/
     *  [signingPrivateKey] are both optional and independent of each other in principle, but in
     *  practice a caller either has a sender identity for this group (and passes both) or doesn't
     *  (and passes neither) — see [RelayResponder.framesToPushOnConnect]'s only real call site.
     *  [groupId] is still needed here (unlike [encodePosition], which dropped it) — it's part of
     *  [presenceMacInput]'s authenticated bytes, computed identically by both the resolved-groupId
     *  sender and a resolved-groupId receiver; only the WIRE envelope itself carries the opaque
     *  [handle] derived from it (decision 38), not [groupId] directly. [rootKey]/[contentKey]
     *  deliberately separate (decision 39, `docs/DECISIONS.md`) — [groupHandle] must keep using the
     *  permanent [rootKey], the mac uses the caller's already-derived `CryptoUtils.
     *  contentEpochKey(rootKey, ...)`. */
    @Suppress("LongParameterList") // wire-protocol scalars — see sealSosBody's suppress
    fun encodePresence(
        groupId: String,
        senderId: String,
        timestamp: Long,
        rootKey: ByteArray,
        contentKey: ByteArray,
        senderPublicKey: ByteArray? = null,
        signingPrivateKey: ByteArray? = null,
    ): ByteArray {
        val macInput = presenceMacInput(groupId, senderId, timestamp)
        val mac = CryptoUtils.authTag(contentKey, macInput)
        val signature = signingPrivateKey?.let { SenderIdentity.sign(it, macInput) }
        val handle = groupHandle(rootKey, timestamp / MILLIS_PER_SECOND)
        return encodePresenceFrame(handle, senderId, timestamp, mac, senderPublicKey, signature, hop = 0)
    }

    /** Re-frames a received presence for another hop **without needing the group key** — the point of
     *  keeping hop in the envelope (see [Frame.Presence.hop]). Every field is copied verbatim from
     *  what arrived; only the hop advances, so the [Frame.Presence.mac] a real member will verify is
     *  untouched and a relay cannot forge presence it couldn't already forge. [handle] is likewise
     *  copied verbatim, never recomputed (see [groupHandle]'s doc, decision 38). */
    fun reframePresenceForRelay(frame: Frame.Presence, hop: Int): ByteArray =
        encodePresenceFrame(
            frame.handle, frame.senderId, frame.timestamp, frame.mac,
            frame.senderPublicKey, frame.signature, hop
        )

    @Suppress("LongParameterList") // wire-protocol scalars — see sealSosBody's suppress
    private fun encodePresenceFrame(
        handle: ByteArray,
        senderId: String,
        timestamp: Long,
        mac: ByteArray?,
        senderPublicKey: ByteArray?,
        signature: ByteArray?,
        hop: Int,
    ): ByteArray = frame(FRAME_PRESENCE) { d ->
        d.writeBlob(handle); d.writeStr(senderId); d.writeLong(timestamp); d.writeBlob(mac)
        d.writeBlob(senderPublicKey); d.writeBlob(signature)
        d.writeByte(hop.coerceIn(0, MAX_UNSIGNED_BYTE))
    }

    /** [sizeBits] is written as an unsigned short — [CatalogFilter]'s MAX_SIZE_BITS (4096)
     *  comfortably fits. [bits] can be shorter than `sizeBits / 8` bytes (trailing-zero truncation
     *  — see [CatalogFilter.toBits]'s doc), so this uses the 2-byte-length [writeStr16Bytes] rather
     *  than the 1-byte [writeBlob] (which would silently truncate anything over 255 bytes). */
    fun encodeCatalogFilter(seed: Long, sizeBits: Int, bits: ByteArray): ByteArray =
        frame(FRAME_CATALOG_FILTER) { d ->
            d.writeLong(seed); d.writeShort(sizeBits); d.writeStr16Bytes(bits)
        }

    fun encodeL2capCap(psm: Int): ByteArray = frame(FRAME_L2CAP_CAP) { d -> d.writeInt(psm) }

    // encodeWifiDirectCap/Handoff/Accept lived here through v0.7.15-dev — deleted by decision 49
    // (docs/DECISIONS.md), Wi-Fi Direct's removal (PLAN-v2.md §4.3 item 3).

    /** Frames an already-stored/sealed courier envelope for the wire — one function suffices here
     *  (unlike SOS's `sealSos` vs. `sealSosBody`+`reframeSosForRelay` split) since a courier envelope
     *  is never sealed-and-sent in one call the way a freshly-authored SOS is: `RelayEngine.
     *  createCourierEnvelope` already stores everything a push needs on `CourierEnvelopeEntity`, and
     *  this just reads that row and frames it — the same "reframe a stored row" role
     *  `reframeStoredSos` plays for SOS, not `sealSos`'s role. See [Frame.Courier]'s own doc for why
     *  [createdAt] and [copiesRemaining] are cleartext here. */
    fun encodeCourier(tag: ByteArray, id: String, createdAt: Long, copiesRemaining: Int, sealed: ByteArray): ByteArray =
        frame(FRAME_COURIER) { d ->
            d.writeBlob(tag); d.writeStr(id); d.writeLong(createdAt)
            d.writeByte(copiesRemaining.coerceIn(0, MAX_UNSIGNED_BYTE)); d.writeStr16Bytes(sealed)
        }

    /** Opens a sealed position body. Null if the key is wrong / not our group / tampered (GCM tag). */
    fun openPosition(sealed: ByteArray, key: ByteArray): PositionBody? {
        val inner = CryptoUtils.decrypt(key, sealed) ?: return null
        return try {
            val buf = ByteBuffer.wrap(inner)
            val senderId = buf.readStr()
            val lat = buf.int / 1e7
            val lon = buf.int / 1e7
            val accuracy = buf.get().toInt() and 0xFF
            val ts = buf.int.toLong()
            val hop = buf.get().toInt() and 0xFF
            val signedBytes = inner.copyOfRange(0, buf.position()) // see PositionBody.signedBytes' doc
            val signature = buf.readBlob()
            PositionBody(senderId, lat, lon, accuracy, ts, hop, signature, signedBytes)
        } catch (e: Exception) {
            null
        }
    }

    /** Opens a sealed SOS body. Null if the key is wrong / not our group / tampered (GCM tag) —
     *  this replaces the old separate `sosMacInput`+`authOk` check entirely (decision 37): a
     *  failure to decrypt IS the auth failure now, same as [openPosition]. */
    fun openSos(sealed: ByteArray, key: ByteArray): SosBody? {
        val inner = CryptoUtils.decrypt(key, sealed) ?: return null
        return try {
            val buf = ByteBuffer.wrap(inner)
            val senderId = buf.readStr()
            val message = buf.readStr16()
            if (message.toByteArray(UTF8).size > MAX_SOS_MESSAGE_BYTES) return null
            val timestamp = buf.long
            val isAlert = buf.get().toInt() != 0
            val signedBytes = inner.copyOfRange(0, buf.position()) // see SosBody.signedBytes' doc
            val signature = buf.readBlob()
            SosBody(senderId, message, timestamp, isAlert, signature, signedBytes)
        } catch (e: Exception) {
            null
        }
    }

    // ---------- couriers (P4 slice 1, decision 40 continued — PLAN-v2.md §4.2) ----------
    // Crypto construction only in this slice: no Frame subtype, no FRAME_COURIER wire byte, no
    // storage, no relay wiring. Deliberately isolated the same way decision 39's contentEpochKey
    // was directly unit-testable with zero Robolectric/Room/BLE surface — and split into a body-
    // only seal (mirroring sealSosBody, not sealSos) from day one specifically because decision 37's
    // own real bug (RelayEngine.createSos once stored sealSos's FULLY-FRAMED output where raw
    // ciphertext was expected, double-framing every self-authored SOS) happened exactly at this
    // seal/frame boundary — building couriers' storage-vs-send split correctly from the start avoids
    // reintroducing that same trap once a Frame.Courier/FRAME_COURIER wire type lands in a later
    // slice.

    /** Envelope recognition tag for a courier (`PLAN-v2.md` §4.2) — `HMAC(groupKey, UTC-day)`, 16
     *  bytes. Same construction as [groupHandle], a different window and length (see
     *  [CryptoUtils.COURIER_TAG_WINDOW_SECONDS]/[CryptoUtils.COURIER_TAG_LEN]'s own docs): a courier
     *  tag deliberately rolls over daily — the spec's own choice, so an envelope's recognizability
     *  window is bounded independent of its 24h TTL — where [groupHandle] deliberately stays stable
     *  for a GATT frame's whole relay life instead. Any member can recognise and open an envelope
     *  carrying this tag; a non-member courier that only sees the tag learns neither the group, the
     *  sender, nor the content — the same blind-carry property [groupHandle] already gives GATT
     *  frames, extended to a per-day rather than per-relay-life granularity. */
    fun courierTag(key: ByteArray, epochSeconds: Long): ByteArray =
        CryptoUtils.rotatingAdvertisementId(
            key, epochSeconds, CryptoUtils.COURIER_TAG_WINDOW_SECONDS, CryptoUtils.COURIER_TAG_LEN,
        )

    /** Candidate tags for the current UTC day plus its immediate neighbours, mirroring
     *  [candidateAdvertisementIds]' own ±1-window tolerance — an envelope created near a day
     *  boundary, or held by a courier for hours before being offered to a member, must still resolve
     *  on either side of that boundary. */
    fun candidateCourierTags(key: ByteArray, nowSeconds: Long): List<ByteArray> =
        CryptoUtils.candidateAdvertisementIds(
            key, nowSeconds, CryptoUtils.COURIER_TAG_WINDOW_SECONDS, CryptoUtils.COURIER_TAG_LEN,
        )

    /** Spec's own "Cap 16 KiB" (`PLAN-v2.md` §4.2) on a courier envelope's carried payload. */
    const val MAX_COURIER_PAYLOAD_BYTES = 16 * 1024

    /** Deterministic per-envelope nonce, same reasoning as [sosNonce]: a courier envelope's [id] is
     *  sealed exactly once, ever — content is immutable once created — so hashing the id alone is
     *  sufficient (nonce reuse is only unsafe across DIFFERENT plaintexts under one key; re-sealing
     *  the same id reproduces the same nonce AND the same plaintext, a GCM no-op). */
    private fun courierNonce(id: String): ByteArray = CryptoUtils.sha256(id.toByteArray(UTF8)).copyOf(GCM_NONCE_LEN)

    /** Mirrors [SosBody]'s own shape and doc — see that class for why [signedBytes] is captured
     *  verbatim rather than re-derived. [payload] is opaque to this codec: what a courier actually
     *  carries is a later slice's decision, this only proves arbitrary bytes seal/open correctly
     *  under the group's content epoch key. */
    data class CourierBody(
        val senderId: String,
        val payload: ByteArray,
        val createdAt: Long,
        val signature: ByteArray?,
        val signedBytes: ByteArray,
    )

    /** Produces JUST the raw AES-GCM sealed bytes — no envelope — mirroring [sealSosBody]'s exact
     *  split from a would-be `sealCourier` that also frames a wire message: a future slice's storage
     *  entity must be handed raw ciphertext, never a pre-framed message, for the identical reason
     *  [sealSosBody]'s own doc gives. [key] is the caller's already-derived
     *  `CryptoUtils.contentEpochKey(rootKey, createdAt / 1000)` — this function never derives one
     *  itself, same as every other seal function in this file. */
    @Suppress("LongParameterList") // wire-protocol scalars — see sealSosBody's suppress
    fun sealCourierBody(
        key: ByteArray,
        id: String,
        senderId: String,
        payload: ByteArray,
        createdAt: Long,
        signingPrivateKey: ByteArray? = null,
    ): ByteArray {
        require(payload.size <= MAX_COURIER_PAYLOAD_BYTES) {
            "courier payload exceeds $MAX_COURIER_PAYLOAD_BYTES bytes"
        }
        val inner = build { d -> d.writeStr(senderId); d.writeStr16Bytes(payload); d.writeLong(createdAt) }
        val signature = signingPrivateKey?.let { SenderIdentity.sign(it, inner) }
        val innerWithSignature = build { d -> d.write(inner); d.writeBlob(signature) }
        return CryptoUtils.encryptWithNonce(key, innerWithSignature, courierNonce(id))
    }

    /** Inverse of [sealCourierBody]. Null on a wrong key / tampered bytes / truncated buffer, or a
     *  payload past [MAX_COURIER_PAYLOAD_BYTES] — same "failed decrypt/oversized IS the auth
     *  failure" contract [openSos] establishes. */
    fun openCourierBody(sealed: ByteArray, key: ByteArray): CourierBody? {
        val inner = CryptoUtils.decrypt(key, sealed) ?: return null
        return try {
            val buf = ByteBuffer.wrap(inner)
            val senderId = buf.readStr()
            val payload = buf.readStr16Bytes()
            if (payload.size > MAX_COURIER_PAYLOAD_BYTES) return null
            val createdAt = buf.long
            val signedBytes = inner.copyOfRange(0, buf.position()) // see CourierBody.signedBytes' doc
            val signature = buf.readBlob()
            CourierBody(senderId, payload, createdAt, signature, signedBytes)
        } catch (e: Exception) {
            null
        }
    }

    // ---------- decode (keyless: no crypto here, only envelope parsing) ----------

    fun decode(bytes: ByteArray): Frame? {
        if (bytes.size < 2) return null
        val type = bytes[0]
        val buf = ByteBuffer.wrap(bytes, 1, bytes.size - 1).slice()
        val version = buf.get().toInt() and 0xFF
        if (version != VERSION) return null // frame from an incompatible build — drop rather than misparse
        return try {
            when (type) {
                FRAME_SOS -> {
                    // Envelope only (decision 37, docs/DECISIONS.md) — sealed is opened separately
                    // via openSos, once a caller has the group key; see Frame.SosSealed's own doc.
                    // handle (decision 38) replaces the old cleartext groupId here.
                    val handle = buf.readBlob() ?: return null
                    val id = buf.readStr()
                    val ttl = buf.get().toInt() and 0xFF
                    val hop = buf.get().toInt() and 0xFF
                    val sealed = buf.readStr16Bytes()
                    Frame.SosSealed(handle, id, ttl, hop, sealed)
                }
                FRAME_EVID_META -> {
                    // Envelope only, since decision 38 (docs/DECISIONS.md) — handle is resolved to
                    // a real groupId separately, by the caller, via
                    // GroupRepository.resolveGroupKeyByHandle; see Frame.EvidMeta's own doc.
                    val id = buf.readStr(); val handle = buf.readBlob() ?: return null
                    val senderId = buf.readStr()
                    val timestamp = buf.long
                    val sha = ByteArray(32).also { buf.get(it) }
                    val totalChunks = buf.int
                    // Guards RelayEngine's FountainDecoder allocation (proportional to totalChunks =
                    // k), which this persisted header later feeds via
                    // RelayResponder.framesToPushOnConnect — see MAX_EVIDENCE_CHUNKS's doc. Not
                    // gated behind any auth check here (a receiver that can't resolve `handle` to a
                    // group key still stores this header — see EvidenceEntity.groupId's doc), so
                    // this is the only check standing between a hostile 120-byte frame and a
                    // repeating oversized allocation.
                    if (totalChunks !in 1..MAX_EVIDENCE_CHUNKS) return null
                    val mimeType = buf.readStr()
                    val ttl = buf.get().toInt() and 0xFF
                    val mac = buf.readBlob()
                    val signature = buf.readBlob()
                    // MAX_THUMBNAIL_BYTES' own doc: sealed, so a blind relay could never open this
                    // regardless — this length cap is a resource-exhaustion guard, not an auth check.
                    val thumbnail = buf.readStr16Bytes()
                    if (thumbnail.size > MAX_THUMBNAIL_BYTES) return null
                    val contentLength = buf.int
                    if (contentLength < 0) return null
                    Frame.EvidMeta(
                        id = id, handle = handle, senderId = senderId, timestamp = timestamp,
                        sha256 = bytesToHex(sha), totalChunks = totalChunks, mimeType = mimeType,
                        ttl = ttl, mac = mac, signature = signature, thumbnail = thumbnail,
                        contentLength = contentLength,
                    )
                }
                FRAME_EVID_SYMBOL -> {
                    // Not bound-checked here — same architectural split the retired FRAME_EVID_CHUNK
                    // always used: decode() stays dumb/keyless, RelayEngine.ingestSymbol is where esi
                    // gets checked against MAX_EVIDENCE_CHUNKS (see Frame.EvidSymbol's own doc).
                    val evidenceId = buf.readStr(); val esi = buf.int
                    val data = ByteArray(buf.remaining()).also { buf.get(it) }
                    Frame.EvidSymbol(evidenceId, esi, data)
                }
                FRAME_POSITION -> {
                    val handle = buf.readBlob() ?: return null
                    val hop = buf.get().toInt() and 0xFF
                    val sealed = buf.readStr16Bytes()
                    Frame.PositionSealed(handle, hop, sealed)
                }
                FRAME_SYMBOL_REQUEST -> {
                    // stillNeed is intentionally NOT bound-checked here — see Frame.SymbolRequest's
                    // own doc: unlike the retired Manifest/totalChunks case, nothing in decode()
                    // allocates proportional to this value; RelayResponder's existing per-connection
                    // symbol budget is what actually bounds the cost downstream.
                    val evidenceId = buf.readStr(); val stillNeed = buf.int
                    Frame.SymbolRequest(evidenceId, stillNeed)
                }
                FRAME_NICKNAME -> {
                    // Envelope only, since decision 38 (docs/DECISIONS.md) — see Frame.Nickname's
                    // own doc for why a receiver that can't resolve `handle` never stores this at all.
                    val handle = buf.readBlob() ?: return null
                    val senderId = buf.readStr(); val username = buf.readStr()
                    val updatedAt = buf.long; val mac = buf.readBlob()
                    val signature = buf.readBlob()
                    Frame.Nickname(handle, senderId, username, updatedAt, mac, signature)
                }
                FRAME_PRESENCE -> {
                    val handle = buf.readBlob() ?: return null
                    val senderId = buf.readStr()
                    val timestamp = buf.long; val mac = buf.readBlob()
                    val senderPublicKey = buf.readBlob()
                    val signature = buf.readBlob()
                    // Appended last, so a hop-less v3 presence still decodes as hop 0 rather than
                    // throwing — the same tolerance readBlob-appended fields already rely on.
                    val hop = if (buf.hasRemaining()) buf.get().toInt() and 0xFF else 0
                    Frame.Presence(handle, senderId, timestamp, mac, senderPublicKey, signature, hop)
                }
                FRAME_CATALOG_FILTER -> {
                    val seed = buf.long
                    val sizeBits = buf.short.toInt() and 0xFFFF
                    val bits = buf.readStr16Bytes()
                    Frame.CatalogFilter(seed, sizeBits, bits)
                }
                FRAME_L2CAP_CAP -> Frame.L2capCap(buf.int)
                FRAME_COURIER -> {
                    val tag = buf.readBlob() ?: return null
                    val id = buf.readStr()
                    val createdAt = buf.long
                    val copiesRemaining = buf.get().toInt() and 0xFF
                    val sealed = buf.readStr16Bytes()
                    Frame.Courier(tag, id, createdAt, copiesRemaining, sealed)
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- primitive read/write helpers (1-byte-length strings/blobs, 2-byte for payloads) ----------

    private inline fun frame(type: Byte, build: (DataOutputStream) -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { d -> d.writeByte(type.toInt()); d.writeByte(VERSION); build(d) }
        return bos.toByteArray()
    }

    private inline fun build(build: (DataOutputStream) -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { d -> build(d) }
        return bos.toByteArray()
    }

    private fun DataOutputStream.writeStr(s: String) {
        val b = s.toByteArray(UTF8); writeByte(b.size.coerceAtMost(255)); write(b, 0, b.size.coerceAtMost(255))
    }
    private fun DataOutputStream.writeStr16(s: String) {
        val b = s.toByteArray(UTF8); writeShort(b.size); write(b)
    }
    private fun DataOutputStream.writeStr16Bytes(b: ByteArray) { writeShort(b.size); write(b) }
    private fun DataOutputStream.writeBlob(b: ByteArray?) {
        if (b == null) { writeByte(0) } else { writeByte(b.size.coerceAtMost(255)); write(b, 0, b.size.coerceAtMost(255)) }
    }

    // ---------- shared per-field writers ----------
    // These two exist specifically so a frame's encode function and its MAC-input function can
    // never independently disagree on how to write a shared variable-length field again — that
    // exact class of drift (encodeSos used writeStr16 for message; sosMacInput used writeStr,
    // silently truncating at 255 bytes and leaving everything past that point unauthenticated) is
    // what let a relay rewrite the tail of any long SOS message undetected. Of every MAC-input/
    // encode pair in this file, only Sos/message and Nickname/username carry a variable-length,
    // caller-supplied field at all — every other MAC-input (position, presence, WFD handoff/accept)
    // covers only short, fixed-shape ids/nonces with no such risk, which is why only these two get
    // a shared writer rather than restructuring every frame type in this file around one.

    /** writeStr16 (2-byte length), not writeStr (1-byte, silently truncates at 255) — see
     *  MAX_SOS_MESSAGE_BYTES for the actual enforced size bound. */
    private fun DataOutputStream.writeSosMessage(message: String) = writeStr16(message)

    /** Truncates to MAX_USERNAME_CHARS on BOTH the encode and MAC-input path via this one function,
     *  so a mac computed over a truncated value can never need verifying against an untruncated
     *  one (or vice versa) because the two call sites disagreed on whether truncation happened. */
    private fun DataOutputStream.writeNicknameUsername(username: String) = writeStr(username.take(MAX_USERNAME_CHARS))

    private fun ByteBuffer.readStr(): String { val n = get().toInt() and 0xFF; val b = ByteArray(n); get(b); return String(b, UTF8) }
    private fun ByteBuffer.readStr16(): String { val n = short.toInt() and 0xFFFF; val b = ByteArray(n); get(b); return String(b, UTF8) }
    private fun ByteBuffer.readStr16Bytes(): ByteArray { val n = short.toInt() and 0xFFFF; val b = ByteArray(n); get(b); return b }
    private fun ByteBuffer.readBlob(): ByteArray? { val n = get().toInt() and 0xFF; if (n == 0) return null; val b = ByteArray(n); get(b); return b }

    private val HEX = "0123456789abcdef".toCharArray()
    private fun bytesToHex(b: ByteArray): String {
        val out = CharArray(b.size * 2)
        for (i in b.indices) { val v = b[i].toInt() and 0xFF; out[i * 2] = HEX[v ushr 4]; out[i * 2 + 1] = HEX[v and 0x0F] }
        return String(out)
    }
    private fun hexToBytes(s: String): ByteArray {
        val clean = if (s.length % 2 == 0) s else "0$s"
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) out[i] = ((Character.digit(clean[i * 2], 16) shl 4) or Character.digit(clean[i * 2 + 1], 16)).toByte()
        return out
    }
}
