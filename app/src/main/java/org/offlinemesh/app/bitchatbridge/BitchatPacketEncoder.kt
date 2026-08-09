package org.offlinemesh.app.bitchatbridge

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * P7 spike primitive (`PLAN-v2.md` Part 7 / `docs/DECISIONS.md` decision 51's own "hard
 * dependency, not skippable" note) — encodes a single bitchat v1 wire packet, forged just well
 * enough to test whether a real, unmodified bitchat node relays it. NOT part of the real bridge:
 * no decrypt, no real ChaChaPoly-sealed group-message body, no production wiring anywhere in this
 * app's own mesh. Pure and Android-free by construction so this half — the only half that CAN be
 * verified without real hardware — is directly unit-testable.
 *
 * Wire shape confirmed against bitchat's own source this session (`docs/DECISIONS.md` decision
 * 51/55): a v1 header is 14 bytes, all big-endian —
 * `version(1) type(1) ttl(1) timestamp(8) flags(1) payloadLength(2)` — followed immediately by
 * `senderId` (always exactly 8 raw bytes, fixed position) and then `payload` (`payloadLength`
 * bytes). [encodeGroupMessage] always emits `flags = 0`: no recipient (broadcast), no signature,
 * uncompressed, no route — the real production `broadcastGroupMessage` construction traced from
 * their source uses exactly this shape for an unsigned broadcast. `recipientId` is OMITTED
 * entirely when unset (not zero-filled) — v1's variable section has no fixed-width placeholder for
 * an absent field, per bitchat's own encoder.
 */
internal object BitchatPacketEncoder {

    /** `MessageType.groupMessage` in bitchat's own `MessageType.swift` — a broadcast-addressed,
     *  group-encrypted packet type their own relay logic forwards unconditionally (no group
     *  recognition, no signature, no real Noise session needed) — see decision 51's own research
     *  for why this is the injection vehicle, not `noiseEncrypted`. */
    const val TYPE_GROUP_MESSAGE: Int = 0x25

    /** Bitchat's own default TTL (`TransportConfig.messageTTLDefault`), confirmed this session. */
    const val DEFAULT_TTL: Int = 7

    /** Fixed width of the `senderId` field on the wire — not length-prefixed, always exactly this
     *  many raw bytes at a fixed position (bitchat's own encoder pads/truncates to this). */
    const val SENDER_ID_BYTES = 8

    private const val WIRE_VERSION: Int = 1
    private const val BROADCAST_FLAGS: Int = 0
    private const val MAX_TTL = 0xFF
    private const val MAX_UNSIGNED_SHORT = 0xFFFF

    /** Encodes one v1 `groupMessage` packet. [senderId] must be exactly [SENDER_ID_BYTES] bytes —
     *  callers choosing an arbitrary marker value should pad/truncate themselves rather than rely
     *  on a default here, since a wrong-length id is exactly the kind of silent mistake this spike
     *  exists to catch, not paper over. [payload] is the spike's own opaque marker bytes, not real
     *  bitchat group-message content — see this object's own class doc for why that's fine for
     *  what this spike is actually testing (structural relay, not content interpretation). */
    fun encodeGroupMessage(
        senderId: ByteArray,
        payload: ByteArray,
        ttl: Int = DEFAULT_TTL,
        timestampMs: Long = System.currentTimeMillis(),
    ): ByteArray {
        require(senderId.size == SENDER_ID_BYTES) { "senderId must be exactly $SENDER_ID_BYTES bytes" }
        require(ttl in 1..MAX_TTL) { "ttl out of range: $ttl" }
        require(payload.size <= MAX_UNSIGNED_SHORT) { "payload too large for a UInt16 length prefix: ${payload.size}" }
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeByte(WIRE_VERSION)
        d.writeByte(TYPE_GROUP_MESSAGE)
        d.writeByte(ttl)
        d.writeLong(timestampMs)
        d.writeByte(BROADCAST_FLAGS)
        d.writeShort(payload.size)
        d.write(senderId)
        d.write(payload)
        return out.toByteArray()
    }
}
