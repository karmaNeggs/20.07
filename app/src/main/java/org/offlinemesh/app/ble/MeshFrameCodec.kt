package org.offlinemesh.app.ble

import org.offlinemesh.app.crypto.CryptoUtils
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

    private const val VERSION: Int = 1
    private val UTF8 = StandardCharsets.UTF_8

    sealed class Frame {
        data class Sos(val sos: SosEntity) : Frame()
        data class EvidMeta(val meta: EvidenceEntity) : Frame()
        data class EvidChunk(val chunk: EvidenceChunkEntity) : Frame()
        /** Envelope only — RelayResponder opens [sealed] with the group key via [openPosition]. */
        data class PositionSealed(val groupId: String, val sealed: ByteArray) : Frame()
        data class Manifest(val evidenceId: String, val totalChunks: Int, val peerHave: Set<Int>) : Frame()
        data class Nickname(val nickname: NicknameEntity) : Frame()
        /** Not stored, not relayed — a direct-neighbor heartbeat proving group co-membership over the
         *  GATT link, so presence doesn't depend solely on hearing a beacon (which can be one-way). */
        data class Presence(val groupId: String, val senderId: String, val timestamp: Long, val mac: ByteArray?) : Frame()
        /** One filter per connection, covering ALL of the sender's current relayable sos/evidence-
         *  header/nickname holdings across every group — see [CatalogFilter] and
         *  [RelayResponder.handleIncoming]'s handling of this case for what the receiver does with
         *  it (computes and pushes its own deficit against it). Not stored, not relayed. */
        data class CatalogFilter(val seed: Long, val bits: ByteArray) : Frame()

        /** "I support the WiFi Direct accelerator and my opt-in is on" — device-level, no MAC (see
         *  [WifiDirectHandoffCoordinator]'s doc for why this carries no sensitive claim: it can
         *  only ever cause an extra, harmless proposal attempt, never a forged transfer). */
        data class WifiDirectCap(val version: Int) : Frame()

        /** "I have a chunk deficit for this evidence item worth accelerating over WiFi Direct."
         *  Only ever produced by a sender that holds [groupId]'s key (a blind relay can compute a
         *  chunk deficit but can never produce a verifiable [mac] here, so a forged frame from a
         *  non-member just fails verification and is dropped — same shape as every other authOk
         *  failure in this codec). */
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

    /** Decrypted inner of a position frame. */
    data class PositionBody(
        val senderId: String, val lat: Double, val lon: Double,
        val accuracyM: Int, val timestampSec: Long, val hop: Int
    )

    // ---------- canonical byte layouts the auth tags are computed over ----------
    // These MUST stay byte-for-byte stable: the sender computes the tag over these exact bytes and
    // every receiver recomputes it the same way. Deliberately excludes ttl (mutated per hop).

    fun sosMacInput(id: String, groupId: String, senderId: String, message: String, timestamp: Long): ByteArray =
        build { d ->
            d.writeStr(id); d.writeStr(groupId); d.writeStr(senderId); d.writeStr(message); d.writeLong(timestamp)
        }

    fun evidMacInput(
        id: String, groupId: String, senderId: String, timestamp: Long,
        sha256Hex: String, totalChunks: Int, mimeType: String
    ): ByteArray = build { d ->
        d.writeStr(id); d.writeStr(groupId); d.writeStr(senderId); d.writeLong(timestamp)
        d.write(hexToBytes(sha256Hex)); d.writeInt(totalChunks); d.writeStr(mimeType)
    }

    fun nicknameMacInput(groupId: String, senderId: String, username: String, updatedAt: Long): ByteArray =
        build { d -> d.writeStr(groupId); d.writeStr(senderId); d.writeStr(username); d.writeLong(updatedAt) }

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

    fun encodeSos(sos: SosEntity): ByteArray = frame(FRAME_SOS) { d ->
        d.writeStr(sos.id); d.writeStr(sos.groupId); d.writeStr(sos.senderId)
        d.writeByte(sos.ttl.coerceIn(0, 255)); d.writeLong(sos.timestamp)
        d.writeStr16(sos.message); d.writeBlob(sos.mac)
    }

    fun encodeEvidMeta(e: EvidenceEntity): ByteArray = frame(FRAME_EVID_META) { d ->
        d.writeStr(e.id); d.writeStr(e.groupId); d.writeStr(e.senderId); d.writeLong(e.timestamp)
        d.write(hexToBytes(e.sha256)); d.writeInt(e.totalChunks); d.writeStr(e.mimeType)
        d.writeByte(e.ttl.coerceIn(0, 255)); d.writeBlob(e.mac)
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
     *  comment above [positionNonceCounter] for why this frame type specifically needs it. */
    fun encodePosition(
        groupId: String, key: ByteArray, senderId: String, lat: Double, lon: Double,
        accuracyM: Int, timestampSec: Long, hop: Int
    ): ByteArray {
        val inner = build { d ->
            d.writeStr(senderId)
            d.writeInt((lat * 1e7).toInt()); d.writeInt((lon * 1e7).toInt())
            d.writeByte(accuracyM.coerceIn(0, 255)); d.writeInt(timestampSec.toInt())
            d.writeByte(hop.coerceIn(0, 255))
        }
        val sealed = CryptoUtils.encryptWithNonce(key, inner, positionNonce(senderId, timestampSec))
        return frame(FRAME_POSITION) { d -> d.writeStr(groupId); d.writeStr16Bytes(sealed) }
    }

    fun encodeNickname(n: NicknameEntity): ByteArray = frame(FRAME_NICKNAME) { d ->
        d.writeStr(n.groupId); d.writeStr(n.senderId); d.writeStr(n.username.take(MAX_USERNAME_CHARS))
        d.writeLong(n.updatedAt); d.writeBlob(n.mac)
    }

    /** Computes the tag internally (like encodePosition takes the key) — there's no stored entity
     *  for a presence heartbeat, it's generated fresh each connect. */
    fun encodePresence(groupId: String, senderId: String, timestamp: Long, key: ByteArray): ByteArray {
        val mac = CryptoUtils.authTag(key, presenceMacInput(groupId, senderId, timestamp))
        return frame(FRAME_PRESENCE) { d ->
            d.writeStr(groupId); d.writeStr(senderId); d.writeLong(timestamp); d.writeBlob(mac)
        }
    }

    /** [bits] can be shorter than [CatalogFilter.SIZE_BITS] / 8 bytes (trailing-zero truncation —
     *  see [CatalogFilter.toBits]'s doc), so this uses the 2-byte-length [writeStr16Bytes] rather
     *  than the 1-byte [writeBlob] (which would silently truncate anything over 255 bytes). */
    fun encodeCatalogFilter(seed: Long, bits: ByteArray): ByteArray = frame(FRAME_CATALOG_FILTER) { d ->
        d.writeLong(seed); d.writeStr16Bytes(bits)
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
            PositionBody(senderId, lat, lon, accuracy, ts, hop)
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
                    val id = buf.readStr(); val groupId = buf.readStr(); val senderId = buf.readStr()
                    val ttl = buf.get().toInt() and 0xFF
                    val timestamp = buf.long
                    val message = buf.readStr16()
                    val mac = buf.readBlob()
                    Frame.Sos(SosEntity(id, groupId, senderId, false, message, timestamp, ttl, mac))
                }
                FRAME_EVID_META -> {
                    val id = buf.readStr(); val groupId = buf.readStr(); val senderId = buf.readStr()
                    val timestamp = buf.long
                    val sha = ByteArray(32).also { buf.get(it) }
                    val totalChunks = buf.int
                    val mimeType = buf.readStr()
                    val ttl = buf.get().toInt() and 0xFF
                    val mac = buf.readBlob()
                    Frame.EvidMeta(
                        EvidenceEntity(
                            id = id, groupId = groupId, senderId = senderId, senderIsMe = false,
                            timestamp = timestamp, sha256 = bytesToHex(sha), totalChunks = totalChunks,
                            mimeType = mimeType, ttl = ttl, mac = mac
                        )
                    )
                }
                FRAME_EVID_CHUNK -> {
                    val evidenceId = buf.readStr(); val index = buf.int
                    val data = ByteArray(buf.remaining()).also { buf.get(it) }
                    Frame.EvidChunk(EvidenceChunkEntity(evidenceId, index, data))
                }
                FRAME_POSITION -> {
                    val groupId = buf.readStr(); val sealed = buf.readStr16Bytes()
                    Frame.PositionSealed(groupId, sealed)
                }
                FRAME_MANIFEST -> {
                    val evidenceId = buf.readStr(); val totalChunks = buf.int
                    val bitset = ByteArray(buf.remaining()).also { buf.get(it) }
                    Frame.Manifest(evidenceId, totalChunks, MeshProtocol.decodeBitset(bitset, totalChunks))
                }
                FRAME_NICKNAME -> {
                    val groupId = buf.readStr(); val senderId = buf.readStr(); val username = buf.readStr()
                    val updatedAt = buf.long; val mac = buf.readBlob()
                    Frame.Nickname(NicknameEntity(groupId, senderId, username, updatedAt, mac))
                }
                FRAME_PRESENCE -> {
                    val groupId = buf.readStr(); val senderId = buf.readStr()
                    val timestamp = buf.long; val mac = buf.readBlob()
                    Frame.Presence(groupId, senderId, timestamp, mac)
                }
                FRAME_CATALOG_FILTER -> {
                    val seed = buf.long
                    val bits = buf.readStr16Bytes()
                    Frame.CatalogFilter(seed, bits)
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
