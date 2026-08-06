package org.offlinemesh.app.ble

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

object MeshProtocol {
    // Public, fixed — identifies "this app's phone" at the radio level. Not secret; matching
    // group content still requires the per-group derived key, this UUID just says "same app".
    val SERVICE_UUID: UUID = UUID.fromString("6c1e2e10-6a1a-4b1e-9a4b-0b6f7e2f1a01")
    val RELAY_CHAR_UUID: UUID = UUID.fromString("6c1e2e11-6a1a-4b1e-9a4b-0b6f7e2f1a01")

    const val ADV_TYPE_GENERIC: Byte = 0x00
    const val ADV_TYPE_GROUP: Byte = 0x01

    const val UNKNOWN_HOP: Int = 255
    const val ROTATING_ID_LEN: Int = 6

    private val UTF8 = StandardCharsets.UTF_8
    private const val UNSIGNED_BYTE_MASK = 0xFF
    private const val UNSIGNED_SHORT_MASK = 0xFFFF

    /** BLE's ATT MTU before any negotiation ever succeeds — the floor every connection starts at
     *  and the value to assume if `requestMtu`/`onMtuChanged` never resolved for some reason. */
    const val DEFAULT_ATT_MTU = 23

    /** Every `writeCharacteristic`/notify consumes 3 bytes of the negotiated MTU for the ATT
     *  opcode + attribute handle — the actual usable payload per write is `mtu - ATT_WRITE_OVERHEAD_BYTES`. */
    const val ATT_WRITE_OVERHEAD_BYTES = 3

    /**
     * 8-byte advertisement service-data payload: type(1) + rotatingId(6) + sosHop(1).
     * Kept deliberately tiny — legacy BLE advertising has a hard 31-byte total limit and
     * Android auto-adds 3 bytes of its own (a Flags structure) on top of whatever's built here.
     * There's no "groupHop" field: only an actual group member can compute a valid rotatingId
     * at all (needs the group key), so anyone advertising one is a presence source by
     * construction — always 0, not worth spending a wire byte to say so.
     */
    fun encodeBeacon(type: Byte, rotatingGroupId: ByteArray, sosHop: Int): ByteArray {
        val buf = ByteBuffer.allocate(1 + ROTATING_ID_LEN + 1)
        buf.put(type)
        buf.put(rotatingGroupId.copyOf(ROTATING_ID_LEN)) // zero-padded if shorter
        buf.put(sosHop.coerceIn(0, 255).toByte())
        return buf.array()
    }

    data class Beacon(val type: Byte, val rotatingGroupId: ByteArray, val sosHop: Int)

    fun decodeBeacon(bytes: ByteArray): Beacon? {
        val expected = 1 + ROTATING_ID_LEN + 1
        if (bytes.size < expected) return null
        val type = bytes[0]
        val rid = bytes.copyOfRange(1, 1 + ROTATING_ID_LEN)
        val sHop = bytes[1 + ROTATING_ID_LEN].toInt() and 0xFF
        return Beacon(type, rid, sHop)
    }

    /** Absolute ceiling on [encodeBroadcastTierBeacon]'s optional [BroadcastTierBeacon.positionFrame]
     *  block — defence in depth for [decodeBroadcastTierBeacon]'s length-prefixed read, same
     *  reasoning as [MeshFrameCodec.MAX_EVIDENCE_CHUNKS]/[MeshFrameCodec.MAX_SOS_MESSAGE_BYTES].
     *  Generous against a real `MeshFrameCodec.encodePosition` frame (~150-160B: `groupId` is a
     *  16-char hex id, not UUID-length — see [org.offlinemesh.app.data.JoinCode]'s `GROUP_ID_LEN`
     *  — while `senderId` IS a 36-char UUID) while leaving guaranteed headroom for
     *  [MAX_BROADCAST_TIER_SOS_ID_BYTES] too — see [encodeBroadcastTierBeacon]'s own doc for the
     *  full worst-case budget arithmetic. */
    const val MAX_BROADCAST_TIER_POSITION_FRAME_BYTES = 180

    /** Absolute ceiling on [encodeBroadcastTierBeacon]'s optional [BroadcastTierBeacon.activeSos]
     *  id — generous against a real SOS id (`UUID.randomUUID().toString()`, 36 chars/bytes, see
     *  `RelayEngine.createSos`). Same defence-in-depth reasoning as
     *  [MAX_BROADCAST_TIER_POSITION_FRAME_BYTES]. */
    const val MAX_BROADCAST_TIER_SOS_ID_BYTES = 48

    /** The sender's own nearest known active SOS for this group — [id] is the real SOS id (matching
     *  `SosEntity.id`), NOT a rough placeholder — see [encodeBroadcastTierBeacon]'s own doc for why
     *  that distinction is the entire point of this type existing. */
    data class SosAlert(val id: String, val hop: Int)

    /**
     * Broadcast-tier (Tier B, PLAN-v2.md §5.1) payload — carried over BLE Extended Advertising
     * (`BeaconRadio`'s broadcast-tier channel), NOT the legacy beacon above, which stays
     * byte-for-byte unchanged. Extended advertising's budget (~1650B chained, 251B per in-place
     * update) has none of [encodeBeacon]'s byte pressure — see that function's own doc: the legacy
     * 31-byte format has exactly 2 spare bytes after Android's Flags structure and this service's
     * own 128-bit UUID overhead, nowhere near enough room for a hop field, let alone a position or
     * SOS block.
     *
     * [presenceHop] is the sender's own current best-known distance to presence for this group —
     * i.e. `HopTracker.myHop(groupId, "PRESENCE")` at encode time, [UNKNOWN_HOP] if the sender
     * doesn't currently know of anyone else either. This is what lets presence propagate a real
     * multi-hop gradient over broadcast alone, with no GATT connection: a receiver feeds it straight
     * into `HopTracker.considerNeighborReport` (which adds its own +1), exactly mirroring how
     * `RelayResponder` already propagates `Frame.Presence.hop` over GATT relay — same distance-
     * vector mechanism, now also available on a connectionless channel.
     *
     * [positionFrame], when present, is `MeshFrameCodec.encodePosition`'s FULL output — the same
     * `FRAME_POSITION`-tagged, groupId+hop+sealed-body bytes a GATT link would carry — reused
     * verbatim rather than re-derived, deliberately at the cost of a redundant groupId string (this
     * beacon already carries [rotatingGroupId] separately), so a receiver decodes it with the exact
     * same `MeshFrameCodec.decode`/`openPosition` pipeline the GATT path already uses, no new
     * crypto or framing code, and inherits [MeshFrameCodec.encodePosition]'s nonce-safety
     * engineering (see its own doc) for free. Deliberately single-hop only: a Tier B receiver does
     * NOT re-broadcast a position it heard from someone else — extended advertising has no natural
     * "relay" the way a GATT store-and-forward blind carrier does, so multi-hop position propagation
     * still goes through the existing GATT path unchanged; Tier B only ever carries the
     * broadcaster's OWN current fix (hop 0).
     *
     * [activeSos], when present, is `HopTracker.bestActiveSos(groupId)`'s result at encode time —
     * the sender's own nearest known active SOS, by its REAL id, not a rough aggregate. This
     * resolves what decision 26 deliberately deferred: an EARLIER version of the legacy beacon
     * carried a rough, sosId-agnostic hop estimate that got fed into a shared "SOS_PENDING" key
     * sitting alongside exact, TTL-derived per-SOS tracking, and a stale rough reading leaked
     * through the `min()` of both (see `docs/DECISIONS.md` decision 13's live-tested finding, and
     * [encodeBeacon]'s own doc — that field is STILL written today but confirmed, by grep, never
     * read by any receiver). Keying on the real id instead avoids that failure mode entirely: a
     * receiver feeds this straight into `HopTracker.considerNeighborReport(groupId, activeSos.id,
     * activeSos.hop, ...)` — just ANOTHER SOURCE for the SAME exact per-SOS key GATT flood-forward
     * already uses, so it composes via ordinary distance-vector relaxation instead of aggregating.
     * Deliberately carries no message/mac/signature — a receiver learns "an SOS exists, this many
     * hops away" for radar/UI purposes only; the authenticated content still arrives over GATT once
     * connected (which `BeaconRadio`'s existing blind-carrier policy already attempts eagerly for
     * every heard device, member or not).
     *
     * Both optional blocks are ALWAYS length-prefixed in the encoded output (a zero length means
     * "absent"), rather than "trailing bytes present or not" — this is what lets two independently-
     * optional variable-length fields coexist unambiguously. Worst-case total, computed not assumed:
     * header(8) + positionLen(2)+[MAX_BROADCAST_TIER_POSITION_FRAME_BYTES](180) +
     * sosIdLen(1)+[MAX_BROADCAST_TIER_SOS_ID_BYTES](48)+sosHop(1) = 240 bytes, comfortably inside
     * extended advertising's ~251B in-place-update budget even at both ceilings simultaneously —
     * which a real sender never hits anyway (realistic ~150B position + 36B sos id ≈ 198B total).
     */
    fun encodeBroadcastTierBeacon(
        type: Byte,
        rotatingGroupId: ByteArray,
        presenceHop: Int,
        positionFrame: ByteArray? = null,
        activeSos: SosAlert? = null,
    ): ByteArray {
        val includePosition = positionFrame != null && positionFrame.size <= MAX_BROADCAST_TIER_POSITION_FRAME_BYTES
        val sosIdBytes = activeSos?.id?.toByteArray(UTF8)
        val includeSos = sosIdBytes != null && sosIdBytes.isNotEmpty() &&
            sosIdBytes.size <= MAX_BROADCAST_TIER_SOS_ID_BYTES
        val headerLen = 1 + ROTATING_ID_LEN + 1
        var size = headerLen + 2 + 1 // header + positionLen prefix + sosIdLen prefix, always present
        if (includePosition) size += positionFrame!!.size
        if (includeSos) size += sosIdBytes!!.size + 1 // + hop byte
        val buf = ByteBuffer.allocate(size)
        buf.put(type)
        buf.put(rotatingGroupId.copyOf(ROTATING_ID_LEN))
        buf.put(presenceHop.coerceIn(0, 255).toByte())
        buf.putShort(if (includePosition) positionFrame!!.size.toShort() else 0)
        if (includePosition) buf.put(positionFrame!!)
        buf.put((if (includeSos) sosIdBytes!!.size else 0).toByte())
        if (includeSos) {
            buf.put(sosIdBytes!!)
            buf.put(activeSos.hop.coerceIn(0, 255).toByte())
        }
        return buf.array()
    }

    data class BroadcastTierBeacon(
        val type: Byte,
        val rotatingGroupId: ByteArray,
        val presenceHop: Int,
        val positionFrame: ByteArray? = null,
        val activeSos: SosAlert? = null,
    )

    @Suppress("ReturnCount") // malformed-input guard clauses, same shape as every other decode() in this codebase
    fun decodeBroadcastTierBeacon(bytes: ByteArray): BroadcastTierBeacon? {
        val headerLen = 1 + ROTATING_ID_LEN + 1
        if (bytes.size < headerLen + 2 + 1) return null // header + both always-present length prefixes
        val type = bytes[0]
        val rid = bytes.copyOfRange(1, 1 + ROTATING_ID_LEN)
        val pHop = bytes[1 + ROTATING_ID_LEN].toInt() and UNSIGNED_BYTE_MASK
        val buf = ByteBuffer.wrap(bytes, headerLen, bytes.size - headerLen)
        val posLen = buf.short.toInt() and UNSIGNED_SHORT_MASK
        if (posLen > MAX_BROADCAST_TIER_POSITION_FRAME_BYTES || buf.remaining() < posLen) return null
        val positionFrame = if (posLen > 0) ByteArray(posLen).also { buf.get(it) } else null
        if (buf.remaining() < 1) return null // sosIdLen prefix itself missing — malformed
        val sosIdLen = buf.get().toInt() and UNSIGNED_BYTE_MASK
        val sosTailNeeded = if (sosIdLen > 0) sosIdLen + 1 else 0 // + hop byte, only if an id is actually present
        if (sosIdLen > MAX_BROADCAST_TIER_SOS_ID_BYTES || buf.remaining() < sosTailNeeded) return null
        val activeSos = if (sosIdLen > 0) {
            val idBytes = ByteArray(sosIdLen).also { buf.get(it) }
            SosAlert(String(idBytes, UTF8), buf.get().toInt() and UNSIGNED_BYTE_MASK)
        } else null
        return BroadcastTierBeacon(type, rid, pHop, positionFrame, activeSos)
    }

    // Relay frame-type constants live in MeshFrameCodec (the one place that encodes/decodes them) —
    // deliberately not duplicated here, where they used to drift out of sync.

    /** 1 bit per chunk index, 1 = have it. Compact even for thousands of chunks (~650B for 5000).
     *  Coerced against [MeshFrameCodec.MAX_EVIDENCE_CHUNKS] as defence in depth — the primary guard
     *  is at [MeshFrameCodec.decode], which every wire-sourced totalChunks value must pass through,
     *  but this is also reachable directly from RelayEngine using a locally-persisted value, so the
     *  allocation itself stays bounded regardless of caller. */
    fun encodeBitset(haveIndexes: Set<Int>, totalChunks: Int): ByteArray {
        val bounded = totalChunks.coerceIn(0, MeshFrameCodec.MAX_EVIDENCE_CHUNKS)
        val bytes = ByteArray((bounded + 7) / 8)
        for (i in haveIndexes) {
            if (i < 0 || i >= bounded) continue
            bytes[i / 8] = (bytes[i / 8].toInt() or (1 shl (i % 8))).toByte()
        }
        return bytes
    }

    /** Same bound as [encodeBitset] — a negative [totalChunks] would otherwise silently "succeed"
     *  with an empty result (`0 until totalChunks` is an empty range for negative values) instead
     *  of being treated as malformed. */
    fun decodeBitset(bytes: ByteArray, totalChunks: Int): Set<Int> {
        val bounded = totalChunks.coerceIn(0, MeshFrameCodec.MAX_EVIDENCE_CHUNKS)
        val result = mutableSetOf<Int>()
        for (i in 0 until bounded) {
            if (i / 8 >= bytes.size) break
            if ((bytes[i / 8].toInt() shr (i % 8)) and 1 == 1) result.add(i)
        }
        return result
    }
}

/** Live, in-memory distance-vector table: how many relay-hops away is my nearest group member / an
 *  active SOS. [now] is injectable (defaults to the real clock) so staleness behavior — a real,
 *  previously-buggy part of this class (see [bestActiveSosHop]'s doc) — is testable without waiting
 *  out a real 90-second window. */
class HopTracker(private val now: () -> Long = System::currentTimeMillis) {
    data class Key(val groupId: String, val target: String) // target = "PRESENCE" or an sosId
    private val table = ConcurrentHashMap<Key, Int>()
    private val lastUpdated = ConcurrentHashMap<Key, Long>()
    // Which reporter's report currently "owns" table[key] — see updateHop's doc for why this
    // exists: without it, a value only ever got BETTER, forever, even after the route that
    // produced it was long gone, as long as something (anything) kept refreshing recency. A
    // peer/connection address is a fine source identity here even though BLE addresses rotate
    // every ~15min — see updateHop's doc for why that rotation doesn't reopen the bug this fixes.
    private val lastSource = ConcurrentHashMap<Key, String>()
    private val _snapshot = MutableStateFlow<Map<Key, Int>>(emptyMap())
    val snapshot: StateFlow<Map<Key, Int>> = _snapshot

    // 90s, not 45s: presence can now be fed by the GATT channel (see RelayResponder — presence /
    // position frames), which only refreshes on each reconnect (~every 48s given the client's
    // reconnect cooldown), not continuously like a beacon does. A 45s window would expire between
    // GATT refreshes and make "nearby" flicker for a peer we can only reach one-directionally. The
    // cost is a peer that has actually left lingers as "nearby" for up to 90s — acceptable for a
    // presence hint, and far better than flicker.
    //
    // Live-tested finding: this flat window has NO margin at all once relay is actually required.
    // A value at hop N depends on N independent GATT reconnect cycles succeeding in a row (each
    // hop needs its own peer's reconnectCooldownMs, ~45s, to elapse before it can even attempt to
    // pass the update on) — a 2-hop reading's worst-case propagation time is ~45s + 45s = 90s, the
    // ENTIRE staleness window, with zero slack for connection setup or ordinary jitter. That's
    // exactly the reported symptom: direct (1-hop) presence was reliable, but a farthest member
    // needing one relay hop flickered in and out rather than stabilizing. effectiveStaleMs adds one
    // more reconnect-cooldown's worth of slack per hop beyond the first, rather than a flat window
    // tuned only for the single-hop case.
    // Kept deliberately equal to PositionTracker's own base window (see its maxAgeSeconds doc for the
    // measured reason it went 90s -> 180s). If these two diverge, the group row and the radar
    // disagree about whether anyone is there at all — the exact hop-vs-radar mismatch already
    // reported as confusing.
    private val staleMs = BASE_STALE_MS

    fun myHop(groupId: String, target: String): Int {
        val key = Key(groupId, target)
        val ts = lastUpdated[key] ?: return MeshProtocol.UNKNOWN_HOP
        val hop = table[key] ?: return MeshProtocol.UNKNOWN_HOP
        if (now() - ts > effectiveStaleMs(staleMs, hop)) return MeshProtocol.UNKNOWN_HOP
        return hop
    }

    /** A direct BLE neighbor is by definition 1 hop away for whatever they're broadcasting as 0/near.
     *  [sourceId] is whoever's actually reporting this (a peer/connection address) — see
     *  [updateHop]'s doc for what it's used for. */
    fun considerNeighborReport(groupId: String, target: String, neighborHop: Int, sourceId: String) {
        if (neighborHop >= MeshProtocol.UNKNOWN_HOP) return
        val candidate = (neighborHop + 1).coerceAtMost(MeshProtocol.UNKNOWN_HOP - 1)
        updateHop(groupId, target, candidate, sourceId)
    }

    /** Set my own hop value directly (not "neighbor + 1") — used when I can derive my true
     *  distance from something other than a live neighbor report, e.g. TTL consumed by a
     *  relayed SOS packet. See [updateHop] for the acceptance rule. */
    fun considerDirectHop(groupId: String, target: String, hopValue: Int, sourceId: String) {
        if (hopValue < 0 || hopValue >= MeshProtocol.UNKNOWN_HOP) return
        updateHop(groupId, target, hopValue, sourceId)
    }

    /** Shared acceptance rule for both public update methods above.
     *
     *  A report that IMPROVES the currently tracked hop is always accepted, from any source —
     *  ordinary distance-vector relaxation. A report that does NOT improve it is only accepted
     *  (replacing the value, possibly upward — i.e. genuinely worse) when it comes from the SAME
     *  source that established the current value: that source is re-asserting its own route got
     *  worse or vanished, which a strictly-better-only rule would otherwise ignore forever — once
     *  a key was recorded at hop 1, it stayed "1 hop away" no matter how stale or wrong, as long as
     *  *anything* kept refreshing recency (which every call here already did, on every report). A
     *  worse report from a DIFFERENT, non-owning source is never allowed to downgrade an existing
     *  better-known route — it has no basis to override what the owning source itself last said.
     *  Recency always refreshes regardless of acceptance, so a stable, unchanged route doesn't go
     *  stale purely from a lack of new reports. */
    private fun updateHop(groupId: String, target: String, candidate: Int, sourceId: String) {
        val key = Key(groupId, target)
        val current = myHop(groupId, target)
        val ownedBySameSource = lastSource[key] == sourceId
        val accept = candidate < current || (ownedBySameSource && candidate != current)
        val confirmsCurrent = candidate == current
        if (accept) {
            table[key] = candidate
            lastSource[key] = sourceId
            lastUpdated[key] = now()
            _snapshot.update { it + (key to candidate) }
        } else if (confirmsCurrent) {
            // Someone still sees the same distance, so the route is real — refresh it, and let
            // ownership follow whoever confirmed it. That second part matters: [lastSource] is a BLE
            // address, which rotates every ~10-15 minutes, so without transfer-on-confirm the
            // ownership needed to later revise a value UPWARD gets permanently stranded on an
            // address that no longer exists.
            lastSource[key] = sourceId
            lastUpdated[key] = now()
        }
        // Otherwise: a WORSE reading from a source that doesn't own this route. Neither the value
        // nor the recency is touched — and the recency part was a real, live-confirmed bug. Every
        // report used to refresh recency, including rejected ones, so once a group had ever recorded
        // "1 hop" ANY later traffic for it (including a 3-hop relayed frame from a stranger) kept
        // that 1 alive forever: the staleness window could never fire and the reading could never
        // degrade. That is why the group row sat at "1 hop(s) away" through every build and never
        // reached 2 or "no one nearby" — the relay was working, the display value was frozen.
    }

    fun markSosOrigin(groupId: String, sosId: String) {
        val key = Key(groupId, sosId)
        table[key] = 0
        lastSource[key] = "self"
        lastUpdated[key] = now()
        _snapshot.update { it + (key to 0) }
    }

    /** The nearest currently-*fresh* SOS tracked for a group (excludes PRESENCE) — its real sosId
     *  alongside its hop distance, expiring old entries the same way [myHop] does for presence.
     *  `null` if none is currently fresh. Split out from [bestActiveSosHop] (decision 28,
     *  `docs/DECISIONS.md`) so a caller that needs to name WHICH SOS is nearest — not just how near
     *  — doesn't have to re-derive it from the raw table itself: `BeaconRadio`'s Tier B SOS
     *  hop-gradient broadcast needs the id specifically so it can feed a receiver's
     *  [considerNeighborReport] with the SAME real per-SOS key GATT flood-forward already uses,
     *  rather than a second, id-agnostic aggregate — see [MeshProtocol.encodeBroadcastTierBeacon]'s
     *  `activeSos` doc for why that distinction is the whole point (a prior, now-removed mechanism
     *  mixed a rough aggregate with this exact tracking and let a stale rough reading leak through). */
    fun bestActiveSos(groupId: String): Pair<String, Int>? {
        val nowMs = now()
        return table.entries
            .asSequence()
            .filter { it.key.groupId == groupId && it.key.target != "PRESENCE" }
            .filter { (key, hop) -> nowMs - (lastUpdated[key] ?: 0L) <= effectiveStaleMs(staleMs, hop) }
            .minByOrNull { it.value }
            ?.let { it.key.target to it.value }
    }

    /** Minimum hop distance among currently-*fresh* SOS trackers for a group — i.e. "how far to the
     *  nearest known active SOS." Previously the SOS display read the raw table directly, bypassing
     *  the staleness check entirely — a stale reading could sit there and be shown as current. */
    fun bestActiveSosHop(groupId: String): Int = bestActiveSos(groupId)?.second ?: MeshProtocol.UNKNOWN_HOP

    companion object {
        // See staleMs's doc above for the live-tested reasoning: one more reconnect-cooldown's
        // worth of slack per hop beyond the first, since each additional hop depends on one more
        // independent GATT reconnect cycle succeeding before a value can propagate further.
        // Matches MeshGattClient.reconnectCooldownMs (45s) — not referenced directly to avoid a
        // ble-internal dependency from this shared MeshProtocol.kt file; kept in sync by doc only.
        private const val PER_HOP_SLACK_MS = 45_000L

        /** Deliberately equal to PositionTracker's BASE_MAX_AGE_SECONDS — see staleMs' note. */
        private const val BASE_STALE_MS = 180_000L

        /** `internal`, no instance state — directly unit-testable (see [HopTrackerTest]). [hop]
         *  is the CURRENTLY STORED value at a key, not a hop being newly considered — a hop of 0
         *  or 1 (self, or a direct single-connection neighbor) gets exactly [baseStaleMs], since
         *  those need only one connection to refresh; each hop beyond that adds [PER_HOP_SLACK_MS]. */
        internal fun effectiveStaleMs(baseStaleMs: Long, hop: Int): Long =
            baseStaleMs + (hop - 1).coerceAtLeast(0) * PER_HOP_SLACK_MS
    }
}
