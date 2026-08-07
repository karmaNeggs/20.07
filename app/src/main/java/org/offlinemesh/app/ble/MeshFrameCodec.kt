package org.offlinemesh.app.ble

import org.offlinemesh.app.crypto.CryptoUtils
import org.offlinemesh.app.crypto.SenderIdentity
import org.offlinemesh.app.data.EvidenceChunkEntity
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
    const val FRAME_MANIFEST: Byte = 0x10   // "here's exactly which chunks I already have" (bitset)
    const val FRAME_SOS: Byte = 0x12        // SOS item + auth tag
    const val FRAME_EVID_META: Byte = 0x13  // evidence header + auth tag
    const val FRAME_EVID_CHUNK: Byte = 0x14 // one evidence chunk
    const val FRAME_POSITION: Byte = 0x15   // AES-GCM-sealed live position — latest-wins, never persisted
    const val FRAME_NICKNAME: Byte = 0x16   // per-group display name + auth tag, latest-updatedAt-wins
    const val FRAME_PRESENCE: Byte = 0x17   // "an authenticated member of this group is on this connection"
    // Bloom filter of held sos/evidence-header/nickname keys — see CatalogFilter.
    const val FRAME_CATALOG_FILTER: Byte = 0x18
    // WiFi Direct evidence-chunk accelerator handoff — see WifiDirectHandoffCoordinator.
    // Experimental, opt-in, off by default; see WifiDirectAccelerator's class doc.
    const val FRAME_WIFI_DIRECT_CAP: Byte = 0x19      // "I support WFD acceleration, opt-in is on"
    const val FRAME_WIFI_DIRECT_HANDOFF: Byte = 0x1A  // "here's a chunk deficit worth accelerating"
    const val FRAME_WIFI_DIRECT_ACCEPT: Byte = 0x1B   // "yes, forming the link"

    /** Display names are a small courtesy label, not an identity — kept short so it stays a
     *  one-line, cheap-to-relay addition rather than a second chat field. */
    const val MAX_USERNAME_CHARS = 20

    /** Absolute ceiling on any wire-carried `totalChunks` (evidence-meta headers AND manifests —
     *  two independent frame types that both feed [MeshProtocol.encodeBitset]/`decodeBitset`,
     *  whose cost is O(totalChunks)). Without this, an unauthenticated, non-member relay can send
     *  one ~120-byte evidence-meta frame claiming e.g. `totalChunks = Int.MAX_VALUE` and force a
     *  ~268MB allocation on every device that relays it — worse, that header is persisted to Room
     *  and re-encoded into a manifest on every future connection (see
     *  [org.offlinemesh.app.ble.RelayResponder.framesToPushOnConnect]), so the crash recurs until
     *  the 48h prune. A blind relay (one that can't resolve this frame's `handle` to a group key —
     *  see `GroupRepository.resolveGroupKeyByHandle`, decision 38) stores this header regardless,
     *  so this frame type has no authentication gate at all for that path — the length cap here is
     *  the only line of defense. 4096 chunks * 400 bytes/chunk (`RelayEngine.CHUNK_SIZE`) = 1.6MB,
     *  generous against `EvidenceCapture`'s 640px/quality-45 JPEGs (typically ~200 chunks). */
    const val MAX_EVIDENCE_CHUNKS = 4096

    /** Absolute ceiling on an SOS message's UTF-8 byte length. [writeStr16]/[readStr16] can
     *  represent up to 65535 bytes, but nothing upstream ever intends a message that large — this
     *  cap exists so [decode] can reject anything past it as malformed, rather than accepting an
     *  arbitrarily large message from a wire frame with no size hint anyone actually chose. */
    const val MAX_SOS_MESSAGE_BYTES = 2000

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
    const val VERSION: Int = 7
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
         *  `groupId = null`, so the existing manifest/chunk-relay mechanism keeps working without
         *  ever learning which group this belongs to). Every other field here is unchanged from
         *  what [EvidenceEntity] itself carries — this is a 1:1 field list minus `groupId`. */
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
        ) : Frame()
        data class EvidChunk(val chunk: EvidenceChunkEntity) : Frame()
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
        data class Manifest(val evidenceId: String, val totalChunks: Int, val peerHave: Set<Int>) : Frame()
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

        /** "I support the WiFi Direct accelerator and my opt-in is on" — device-level, no MAC (see
         *  [WifiDirectHandoffCoordinator]'s doc for why this carries no sensitive claim: it can
         *  only ever cause an extra, harmless proposal attempt, never a forged transfer). */
        data class WifiDirectCap(val version: Int) : Frame()

        /** "I have a chunk deficit for this evidence item worth accelerating over WiFi Direct."
         *  Only ever produced by a sender that holds [groupId]'s key (a blind relay can compute a
         *  chunk deficit but can never produce a verifiable [mac] here, so a forged frame from a
         *  non-member just fails verification and is dropped). Out of scope for decision 38's
         *  handle-based rewrite — this is a separate, opt-in, already-mac-gated proposal frame, not
         *  one of the always-relayed frame types that needed a blind-relay path in the first place. */
        data class WifiDirectHandoff(
            val evidenceId: String,
            val groupId: String,
            val deficitCount: Int,
            val senderNonce: ByteArray,
            val mac: ByteArray,
        ) : Frame()

        /** Accepts a [WifiDirectHandoff] proposal. [mac] here doubles as the handoff token
         *  presented over the raw WFD socket — see [WifiDirectHandoffCoordinator]'s doc for why
         *  reusing this MAC as the socket token needs no separate key-derivation step. */
        data class WifiDirectAccept(
            val evidenceId: String,
            val groupId: String,
            val senderNonce: ByteArray,
            val receiverNonce: ByteArray,
            val readyAtEpochMs: Long,
            val mac: ByteArray,
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

    fun evidMacInput(
        id: String, groupId: String, senderId: String, timestamp: Long,
        sha256Hex: String, totalChunks: Int, mimeType: String
    ): ByteArray = build { d ->
        d.writeStr(id); d.writeStr(groupId); d.writeStr(senderId); d.writeLong(timestamp)
        d.write(hexToBytes(sha256Hex)); d.writeInt(totalChunks); d.writeStr(mimeType)
    }

    fun nicknameMacInput(groupId: String, senderId: String, username: String, updatedAt: Long): ByteArray =
        build { d ->
            d.writeStr(groupId); d.writeStr(senderId); d.writeNicknameUsername(username); d.writeLong(updatedAt)
        }

    fun presenceMacInput(groupId: String, senderId: String, timestamp: Long): ByteArray =
        build { d -> d.writeStr(groupId); d.writeStr(senderId); d.writeLong(timestamp) }

    fun wifiDirectHandoffMacInput(
        evidenceId: String,
        groupId: String,
        deficitCount: Int,
        senderNonce: ByteArray,
    ): ByteArray = build { d ->
        d.writeStr(evidenceId); d.writeStr(groupId); d.writeInt(deficitCount); d.write(senderNonce)
    }

    // LongParameterList: wire-protocol fields as plain scalars, matching every other MAC-input/
    // encode function in this file (e.g. encodePosition below already has 8) rather than
    // introducing a DTO type just for this pair of functions.
    @Suppress("LongParameterList")
    fun wifiDirectAcceptMacInput(
        evidenceId: String,
        groupId: String,
        senderNonce: ByteArray,
        receiverNonce: ByteArray,
        readyAtEpochMs: Long,
    ): ByteArray = build { d ->
        d.writeStr(evidenceId); d.writeStr(groupId)
        d.write(senderNonce); d.write(receiverNonce)
        d.writeLong(readyAtEpochMs)
    }

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
    @Suppress("LongParameterList") // wire-protocol scalars — see wifiDirectAcceptMacInput's suppress
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
    @Suppress("LongParameterList") // wire-protocol scalars — see wifiDirectAcceptMacInput's suppress
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
    fun encodeEvidMeta(e: EvidenceEntity): ByteArray = frame(FRAME_EVID_META) { d ->
        d.writeStr(e.id); d.writeBlob(e.handle); d.writeStr(e.senderId); d.writeLong(e.timestamp)
        d.write(hexToBytes(e.sha256)); d.writeInt(e.totalChunks); d.writeStr(e.mimeType)
        d.writeByte(e.ttl.coerceIn(0, 255)); d.writeBlob(e.mac); d.writeBlob(e.signature)
    }

    fun encodeChunk(c: EvidenceChunkEntity): ByteArray = frame(FRAME_EVID_CHUNK) { d ->
        d.writeStr(c.evidenceId); d.writeInt(c.chunkIndex); d.write(c.data)
    }

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
    @Suppress("LongParameterList") // wire-protocol scalars — see wifiDirectAcceptMacInput's suppress
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

    @Suppress("LongParameterList") // wire-protocol scalars — see wifiDirectAcceptMacInput's suppress
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
    @Suppress("LongParameterList") // wire-protocol scalars — see wifiDirectAcceptMacInput's suppress
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

    @Suppress("LongParameterList") // wire-protocol scalars — see wifiDirectAcceptMacInput's suppress
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

    fun encodeWifiDirectCap(version: Int): ByteArray = frame(FRAME_WIFI_DIRECT_CAP) { d ->
        d.writeByte(version.coerceIn(0, 255))
    }

    @Suppress("LongParameterList") // see wifiDirectAcceptMacInput's identical suppress above for why
    fun encodeWifiDirectHandoff(
        evidenceId: String,
        groupId: String,
        deficitCount: Int,
        senderNonce: ByteArray,
        mac: ByteArray,
    ): ByteArray = frame(FRAME_WIFI_DIRECT_HANDOFF) { d ->
        d.writeStr(evidenceId); d.writeStr(groupId)
        d.writeInt(deficitCount); d.writeBlob(senderNonce); d.writeBlob(mac)
    }

    @Suppress("LongParameterList") // see wifiDirectAcceptMacInput's identical suppress above for why
    fun encodeWifiDirectAccept(
        evidenceId: String,
        groupId: String,
        senderNonce: ByteArray,
        receiverNonce: ByteArray,
        readyAtEpochMs: Long,
        mac: ByteArray,
    ): ByteArray = frame(FRAME_WIFI_DIRECT_ACCEPT) { d ->
        d.writeStr(evidenceId); d.writeStr(groupId)
        d.writeBlob(senderNonce); d.writeBlob(receiverNonce)
        d.writeLong(readyAtEpochMs); d.writeBlob(mac)
    }

    fun encodeManifest(evidenceId: String, totalChunks: Int, have: Set<Int>): ByteArray {
        val bitset = MeshProtocol.encodeBitset(have, totalChunks)
        return frame(FRAME_MANIFEST) { d -> d.writeStr(evidenceId); d.writeInt(totalChunks); d.write(bitset) }
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
                    // Guards MeshProtocol.encodeBitset's O(totalChunks) allocation, which this
                    // persisted header later feeds via RelayResponder.framesToPushOnConnect — see
                    // MAX_EVIDENCE_CHUNKS's doc. Not gated behind any auth check here (a receiver
                    // that can't resolve `handle` to a group key still stores this header — see
                    // EvidenceEntity.groupId's doc), so this is the only check standing between a
                    // hostile 120-byte frame and a repeating ~268MB allocation.
                    if (totalChunks !in 1..MAX_EVIDENCE_CHUNKS) return null
                    val mimeType = buf.readStr()
                    val ttl = buf.get().toInt() and 0xFF
                    val mac = buf.readBlob()
                    val signature = buf.readBlob()
                    Frame.EvidMeta(
                        id = id, handle = handle, senderId = senderId, timestamp = timestamp,
                        sha256 = bytesToHex(sha), totalChunks = totalChunks, mimeType = mimeType,
                        ttl = ttl, mac = mac, signature = signature,
                    )
                }
                FRAME_EVID_CHUNK -> {
                    val evidenceId = buf.readStr(); val index = buf.int
                    val data = ByteArray(buf.remaining()).also { buf.get(it) }
                    Frame.EvidChunk(EvidenceChunkEntity(evidenceId, index, data))
                }
                FRAME_POSITION -> {
                    val handle = buf.readBlob() ?: return null
                    val hop = buf.get().toInt() and 0xFF
                    val sealed = buf.readStr16Bytes()
                    Frame.PositionSealed(handle, hop, sealed)
                }
                FRAME_MANIFEST -> {
                    val evidenceId = buf.readStr(); val totalChunks = buf.int
                    // Same MAX_EVIDENCE_CHUNKS guard as FRAME_EVID_META above — decodeBitset's loop
                    // is O(totalChunks), and a negative value silently "succeeds" with an empty
                    // peerHave (0 until totalChunks is an empty range) rather than being rejected,
                    // which would otherwise let a nonsensical manifest reach RelayEngine's deficit
                    // calculation downstream.
                    if (totalChunks !in 1..MAX_EVIDENCE_CHUNKS) return null
                    val bitset = ByteArray(buf.remaining()).also { buf.get(it) }
                    Frame.Manifest(evidenceId, totalChunks, MeshProtocol.decodeBitset(bitset, totalChunks))
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
                FRAME_WIFI_DIRECT_CAP -> {
                    val capVersion = buf.get().toInt() and 0xFF
                    Frame.WifiDirectCap(capVersion)
                }
                FRAME_WIFI_DIRECT_HANDOFF -> {
                    val evidenceId = buf.readStr(); val groupId = buf.readStr()
                    val deficitCount = buf.int
                    val senderNonce = buf.readBlob() ?: return null
                    val mac = buf.readBlob() ?: return null
                    Frame.WifiDirectHandoff(evidenceId, groupId, deficitCount, senderNonce, mac)
                }
                FRAME_WIFI_DIRECT_ACCEPT -> {
                    val evidenceId = buf.readStr(); val groupId = buf.readStr()
                    val senderNonce = buf.readBlob() ?: return null
                    val receiverNonce = buf.readBlob() ?: return null
                    val readyAtEpochMs = buf.long
                    val mac = buf.readBlob() ?: return null
                    Frame.WifiDirectAccept(evidenceId, groupId, senderNonce, receiverNonce, readyAtEpochMs, mac)
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
