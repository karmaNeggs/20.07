package org.offlinemesh.app.ble

/**
 * Pure admission policy for the courier envelope pool (P4 slice 3, `docs/DECISIONS.md` decision 43,
 * `PLAN-v2.md` §4.2's "bounded pool with tiers") — no DAO/Room access, so directly unit-testable,
 * matching the extraction discipline [ConnectionAttemptTracker]/[OpaqueFrameRelay]/[HopTracker]
 * already establish for bounded/eviction logic in this codebase.
 *
 * [CAPACITY]/[OWN_GROUP_RESERVED] are taken verbatim from `PLAN-v2.md`'s own courier table entry
 * ("pool 20-of-40 slots") and its own translation of bitchat's "20 of 40 slots reserved for trusted
 * depositors" into this app's ownership model (no reputation/trust tiering here — only "do we hold
 * the key" distinguishes a slot). Same discipline every other courier constant this feature has
 * shipped with: taken from the spec table without rescaling (see [MeshFrameCodec.MAX_COURIER_PAYLOAD_BYTES],
 * [RelayEngine.COURIER_MAX_AGE_MILLIS], [RelayEngine.COURIER_INITIAL_COPY_BUDGET]).
 *
 * The reserved count is a HARD floor on blind-carry capacity ([blindCapacity] = [CAPACITY] -
 * [OWN_GROUP_RESERVED], not [CAPACITY] - current own-group count) — that's what actually guarantees
 * 20 own-group slots exist even when almost none are currently in use, the same "reserved" (not
 * merely prioritized) semantics bitchat's own wording implies. An own-group envelope can still grow
 * past 20 by borrowing unused blind-carry capacity, up to the full 40.
 */
object CourierPool {
    const val CAPACITY = 40
    const val OWN_GROUP_RESERVED = 20

    internal enum class Admission { ACCEPT, EVICT_OLDEST_BLIND, EVICT_OLDEST_OWN }

    /** [ownCount]/[blindCount] are the CURRENT pool contents (before this insert); [isOwnGroup] is
     *  whether the envelope being admitted has a resolvable `groupId` (member path) or is blind
     *  carry (`groupId == null`). Own-group envelopes are never hard-rejected — the only eviction an
     *  own-group insert can ever trigger against another own-group row is the degenerate case where
     *  the entire 40-slot pool is already own-group content (nothing left to evict but a sibling).
     *  Blind-carry never hard-rejects either — same LRU-evict-your-own-oldest behavior
     *  [OpaqueFrameRelay]'s bounded map already gives its own callers, just persisted instead of
     *  in-memory. Eviction is always oldest-by-`createdAt`, never keyed on `copiesRemaining` —
     *  deliberately: that field stays the inert one decision 42 established until a later P4 slice's
     *  handover arithmetic gives it real meaning; using it as an eviction priority signal here would
     *  be reaching into that slice's semantics early. */
    internal fun decide(
        ownCount: Int,
        blindCount: Int,
        isOwnGroup: Boolean,
        capacity: Int = CAPACITY,
        ownReserved: Int = OWN_GROUP_RESERVED,
    ): Admission {
        return if (isOwnGroup) {
            val total = ownCount + blindCount
            when {
                total < capacity -> Admission.ACCEPT
                blindCount > 0 -> Admission.EVICT_OLDEST_BLIND
                else -> Admission.EVICT_OLDEST_OWN
            }
        } else {
            val blindCapacity = capacity - ownReserved
            if (blindCount < blindCapacity) Admission.ACCEPT else Admission.EVICT_OLDEST_BLIND
        }
    }
}
