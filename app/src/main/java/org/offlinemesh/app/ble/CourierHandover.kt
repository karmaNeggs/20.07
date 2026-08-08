package org.offlinemesh.app.ble

/**
 * P4 slice 4 (`docs/DECISIONS.md` decision 44, `PLAN-v2.md` §4.2) — the copy-budget split
 * arithmetic behind "hand over half on meeting another courier." Pure, no DAO/Room access, so
 * directly unit-testable — an off-by-one here is exactly the kind of thing that's easy to get wrong
 * silently, per this slice's own `PLAN-v2.md` entry.
 *
 * Classic Spray-and-Wait routing (Spyropoulos et al., the scheme `PLAN-v2.md` §4.2 names): a
 * courier holding `N` copies of an envelope, on meeting a peer that doesn't have it yet, gives away
 * `floor(N/2)` and keeps `ceil(N/2)`. Applied uniformly here regardless of which tier is doing the
 * handing over — an own-group holder (this device authored or received the envelope as a member)
 * and a blind carrier (holds it opaquely, no group key) split the same way; nothing about the
 * arithmetic itself depends on membership. `PLAN-v2.md`'s own "cap 8" figure bounds the total
 * copies that could ever exist for one envelope starting from a single injection — this is
 * automatically satisfied by conservation (`keep + give` always equals the input, no copies are
 * ever minted by a split) as long as no envelope is ever re-injected above [RelayEngine.
 * COURIER_INITIAL_COPY_BUDGET] (4); this slice adds no reinjection path, so 8 is never approached,
 * let alone reached — flagged honestly rather than building enforcement for a scenario this
 * codebase has no mechanism to trigger yet.
 */
object CourierHandover {
    /** Below this, there is nothing meaningful left to hand over — see [split]'s own doc. */
    const val MIN_COPIES_TO_SPLIT = 2

    /** Returns `(keep, give)` if [copiesRemaining] is worth splitting, `null` otherwise (fewer than
     *  [MIN_COPIES_TO_SPLIT] — handing away 0 copies is not a handover, and a courier down to its
     *  last copy has nothing to spare). A caller receiving `null` should simply not offer this
     *  envelope onward this connection, not push it unsplit — see
     *  [org.offlinemesh.app.ble.RelayEngine.relayableCourierEnvelopes]'s own doc for why this also
     *  gates which rows are even considered handover candidates in the first place. */
    fun split(copiesRemaining: Int): Pair<Int, Int>? {
        if (copiesRemaining < MIN_COPIES_TO_SPLIT) return null
        val give = copiesRemaining / 2
        val keep = copiesRemaining - give
        return keep to give
    }
}
