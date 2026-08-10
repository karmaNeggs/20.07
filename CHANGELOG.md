# Changelog

## [0.7.25-dev] — `HopTracker` rebuilt around per-source aging, closing the "always 1 hop away" freeze

Decision 60 (`docs/DECISIONS.md`), CR-33. The old design kept one ownership-gated value per group:
a report could only make it WORSE if it came from the exact source that established it, so in a
redundant multi-member mesh, any OTHER source merely confirming the old value kept it alive
indefinitely — a group row could sit at "1 hop away" long after that member had genuinely walked
out of range, live-confirmed by watching it happen. Replaced with one independent report per
reporting source, each aging out on its own clock; the displayed hop is simply the minimum among
whichever sources are currently fresh — distance to the nearest reachable member, with nothing left
to freeze. No fresh source now reads as "no one nearby" rather than falling back to a stale value.
One accepted, bounded tradeoff: `BeaconRadio`'s two BLE-address-keyed call sites can show a stale
reading for up to one staleness window after an address rotation, instead of the old design's
instant handoff for that case.

525 tests (`HopTrackerTest` rewritten alongside the class, 35 tests), detekt clean, both variants
green. **Not yet hardware-tested** — needs a round that walks a phone far enough away, for long
enough, to watch the number actually rise.

## [0.7.24-dev] — Round 2 hardware findings: catalog-filter refresh gap and split-horizon MAC-rotation gap, both fixed

Decision 59. The second live round (against the build that fixed v0.7.23-dev's regression)
confirmed comms and radar both work, and surfaced two real protocol bugs. SOS/evidence/nickname
delivery silently stopped roughly 3 minutes into a session, on all three phones: the catalog filter
— the only thing that kind of delivery is reactive to — was sent exactly once, at connect; fixed by
folding it into the periodic presence/position refresh that already ran on every open link. Hop
count reached 3-4 in a 3-phone mesh, which should be topologically impossible: split-horizon
compared raw, rotation-prone BLE addresses instead of resolved peer identity, silently breaking
every time an address rotated; fixed via `PeerIdentityResolver.resolve()` on both sides of the
comparison. A third finding from this round — the hop-count freeze — was diagnosed but deliberately
deferred, becoming CR-33 (v0.7.25-dev, above).

525 tests, detekt clean, both variants green.

## [0.7.23-dev] — Live regression: CR-13's `ScanFilter` broke discovery outright, reverted

Decision 58. The first live hardware round (3 phones) against v0.7.22-dev showed total discovery
failure — zero open links for the entire session, every SOS blocked with "no open links." Root
cause, found from the exported diagnostics alone: this app's beacon advertises via
`addServiceData`, never `addServiceUuid`, and `ScanFilter.setServiceUuid()` structurally cannot
match a `ServiceData` advertisement — a deterministic mismatch on every device, not chipset
flakiness. The identical bug existed in Tier B's own scan filter since the day it shipped, never
caught because Tier B carried its own separate "not device-tested" caveat. Both filters reverted to
the unfiltered, software-matching scan that was live-tested working across four prior rounds. Cost:
the crowd-scale battery optimization this filter was meant to provide (matching in the radio chip
instead of app code) is undone until a properly pre-verified filter type replaces it.

525 tests (unaffected — this is Android BLE advertising-data semantics, entirely outside what
Robolectric/JVM tests can exercise).

## [0.7.22-dev] — Tracked tier (CR-21..30) closed out

Decision 57. Second pass over the full-codebase review's remaining findings, user-directed. Two
product decisions: shake-to-panic-delete cut from scope entirely (it was never built or even
scoped, despite being in the original brief); reassembled evidence staying plaintext at rest closed
as working-as-intended, not a bug (the actual goal is redundancy against single-device loss, and
encrypting it would work against that for no compensating benefit). Five more findings fixed
(fountain-decoder cost bounded by capping `MAX_EVIDENCE_CHUNKS` 4096→1024, `JoinCode` expiry
widened past a Y2038-class overflow, three smaller correctness fixes), one shipped partially
(connection-degree logging landed, its threshold re-derivation waits on real round data), one left
alone deliberately (already gated on its own hardware-evidence condition), one closed as a
meta-observation with nothing separately actionable.

525 tests.

## [0.7.21-dev] — Pre-hardware-round fix pass: 20 findings from a full-codebase review

Decision 56. A full read-through of the app's ~13.4k lines looking for redundant code, regressions,
bugs, memory leaks, and over-engineering, ahead of a device-test round. 30 findings total, 20
shipped this pass. Most consequential: Tier B's SOS content-preview MAC length had silently drifted
to 32 bytes against the real 16-byte tag, meaning the preview had never once transmitted correctly
in production — and the test suite encoded the bug as its own passing case; frame padding's
256-byte floor could silently break a GATT write below MTU 259; group deletion never cleaned up
that group's courier envelopes, letting them keep circulating for up to 24 hours after the group
was gone; a hardware scan filter had been applied to Tier B's scan but never to the legacy scan
that actually feeds connection attempts — the exact gap v0.7.23-dev's regression came from. Also
fixed unbounded in-memory growth in three of the mesh's own tracking tables.

525 tests (15 new), detekt clean, both variants green. **Not yet hardware-confirmed** at this point.

## [0.7.20-dev] — P7 spike tooling: bitchat packet encoder + BLE injector

Decision 55. First concrete step toward an optional, off-by-default bridge to bitchat's own mesh
(Part 7 of the scaling plan). New, deliberately isolated package that nothing in the real mesh
touches: a pure, unit-tested encoder matching bitchat's own wire format, and a debug-only BLE
transport that finds a real bitchat node and writes one forged, unsigned packet with a random
marker. A clean write confirms the first half of P7's hard dependency — that an unmodified bitchat
install's peripheral GATT accepts a well-formed packet from a sender it's never seen before. It does
not, by itself, confirm multi-hop relay.

510 tests (5 new).

## [0.7.19-dev] — `senderId` de-globalized: fixes a cross-group correlation leak

Decisions 53-54. `senderId` — the identity string every presence/position broadcast carries in
cleartext — used to be exactly one random id shared across every group a device belongs to. Any
member of two overlapping groups, or a passive listener watching both, could correlate a device's
presence across them. Now derived per (device, group) from the same already-persisted keypair
(`sha256Hex(publicKey).take(16)`) — no new key material, purely a computed value, all 12 call sites
updated. Real one-time cost on upgrade: existing peers see a "new" sender once.

505 tests (unchanged).

## [0.7.18-dev] — L2CAP bulk-pipe padding, plus four follow-ups from the first hardware round

Decision 52. The first hardware round since v0.7.17-dev confirmed the blind-relay pillar and the
L2CAP bulk pipe both work on real hardware — 3 phones, one deleted its own group mid-session and
correctly kept carrying the other two members' traffic as a blind, non-decrypting carrier. Traced
that round's SOS/presence delays (17-113 seconds) to a real, previously-undocumented property of
the design rather than a bug: blind/opaque custody only propagates at the next connection cycle,
not immediately the way member traffic does. Shipped: the L2CAP bulk pipe's frames now get the same
size-bucket padding GATT writes already had.

505 tests (unchanged).

## [0.7.17-dev] — Diagnostics logging widened ahead of the device-test round

Decision 50. Added event-log coverage for the L2CAP path, the catch-all incoming-frame handler, and
reject/error cases, plus message-level hop tracing — so an exported log from a live round could
actually explain a failure instead of only confirming one happened. No behavior change.

505 tests (unchanged).

## [0.7.16-dev] — Wi-Fi Direct removed outright

Decision 49. Closes out P5. The experimental Wi-Fi Direct evidence accelerator (opt-in, always
default off) is deleted entirely — every transport file, its settings screen, its own test files,
its wire frame types, the Home-screen toggle, and its manifest permissions. `WifiP2pManager.connect()`
is widely reported to trigger a system "Invitation to connect" dialog on the *other* phone, which
would have visibly defeated this app's own disguise feature the moment it fired — not an acceptable
risk for a feature whose only benefit (faster large-evidence transfer) the L2CAP bulk pipe and
fountain coding below already substantially cover.

505 tests (down from 522 — Wi-Fi Direct's own tests removed with it). **P5 fully code-complete.**

## [0.7.15-dev] — BLE L2CAP CoC bulk pipe, additive

Decision 48. A proper two-way socket (SDK-gated, API 29+) for large transfers on phones that
support it, instead of one-message-at-a-time GATT writes. Purely additive — nothing existing
depends on it yet. **Not device-tested**: Robolectric's own L2CAP channel listener is a
non-functional stub, so this is compile- and unit-test-verified only until a real round.

19 new tests.

## [0.7.14-dev] — Fountain coding wired in; P5's chunking rework is code-complete

Decision 47. Replaces the old numbered-chunk-plus-manifest-plus-have-bitset system outright with
the fountain-code primitive from v0.7.13-dev: any sufficient combination of received symbols, from
any combination of nearby phones, reconstructs the file — so more helpful phones nearby means
faster download, not slower, the opposite of the old scheme.

511 tests.

## [0.7.13-dev] — Fountain-code encode/decode primitive

Decision 46. New, construction-only fountain code: systematic random-linear encoding with
Gaussian-elimination decoding. Rejected both an existing open-source library (unmaintained) and a
full RFC 6330 implementation as more than this app needs; switched from a robust-soliton degree
distribution to dense random coefficients after measuring a real 1.3-2.6x overhead difference
between the two at this app's typical evidence-photo sizes. No production call sites yet.

497 tests (15 new).

## [0.7.12-dev] — Thumbnail-first, full-resolution pulled on demand

Decision 45. P5's first slice. Previously every phone that could relay an evidence photo got the
entire multi-MB file pushed to it, wanted or not. Now a small thumbnail plus a "full photo
available" tag goes out to everyone first, and the full file only moves to a phone that actually
requests it — a multi-MB-to-under-100KB win for anyone who doesn't. The thumbnail itself is sealed
with AES-GCM under the content-epoch key.

482 tests.

## [0.7.11-dev] — Courier handover mechanics; P4 is code-complete

Decision 44. Implements Spray-and-Wait: a courier splits its remaining copy count with whoever it
hands part of its load to (conservation-property tested — copies never appear or vanish across a
split), rate-limited to one handover attempt per envelope per ten minutes so a crowded connection
doesn't spray the same envelope at everyone at once. Multi-hop blind-carry of courier content is
now actually enabled.

462 tests.

## [0.7.10-dev] — GATT wiring for couriers

Decision 43. Couriers get a real wire type and a bounded pool (40 total, 20 reserved per group, so
one group's courier traffic can't starve another's), plus the handlers to send/receive/hold one
over an active connection. Still single-hop only — the copy-count field multi-hop spray will use
exists but is deliberately inert until the next version. **Not yet hardware-confirmed.**

442 tests.

## [0.7.9-dev] — Persisted courier envelope + local creation

Decision 42. Couriers get a real, Room-backed entity and a creation path, with their own 24-hour
pruning cutoff independent of evidence/SOS's 48-hour one. Storage and lifecycle only — nothing can
be sent over the wire yet.

416 tests.

## [0.7.8-dev] — Courier tag + envelope seal/open: the crypto, on its own

Decision 41. P4 (couriers — carrying a message across a physical gap the mesh can't currently
bridge on its own) starts with just the cryptographic primitives, mirroring how SOS content is
already sealed. Purely additive and unreachable until the slices that follow wire it in.

412 tests.

## [0.7.7-dev] — Frame padding to size buckets; P6 is code-complete

Decision 40. Every GATT frame now pads to one of a small set of fixed size buckets before it goes
on the wire, so message length alone can't hint at what type of message it is to a passive
listener. Tier B's own frames are excluded by construction — its advertising budget can't absorb
even the smallest bucket's floor. Padding bytes are random, not zero-filled.

399 tests. Closes out P6 (decisions 37-40) in full. **Not yet hardware-confirmed.**

## [0.7.6-dev] — Content-sealing epoch key (P6's third slice)

Decision 39 (`docs/DECISIONS.md`), P6's third slice: seals AES-GCM bodies (SOS/position) and macs
(evidence-meta/nickname/presence/Tier-B-SOS-preview) under a key derived from the group's root key
rather than the root key directly — `CryptoUtils.contentEpochKey(rootKey, epoch)` =
`HKDF-SHA256(rootKey, info = epoch)` via Tink's `subtle.Hkdf`, 24h epochs, fully stateless.

**Corrects a claim in `PLAN-v2.md` §4.4**: the epoch key ratchet does not provide forward secrecy
against phone seizure, contrary to what that section originally said. The group's root key must stay
permanently retained regardless — decision 38's rotating wire handle and `GroupRepository.getShareCode`'s
re-shareable invite code both need it forever — so any non-interactive derivation from it (this one
included) is exactly as forward-secret as the root key's own confidentiality. Real forward secrecy
needs an interactive Diffie-Hellman step, which this project already rejected for a mesh that
partitions constantly. What this feature actually provides: domain separation from decision 38's
(unchanged, unrelated) wire handle, and bounding one independently-leaked epoch key to ~24h of
exposure instead of the group's whole multi-day-to-months life.

`MeshFrameCodec.sealSos`/`encodePosition`/`encodePresence` each gained a second key parameter
(`rootKey` for the wire handle, `contentKey` for the actual seal). No `MeshFrameCodec.VERSION` bump —
wire byte layouts are unchanged, only which key opens/verifies them, so old-build/new-build interop
now fails silently (open/verify failure) rather than via decode rejection.

390 tests (up from 381), detekt clean, both variants compile/test/assemble
(`assembleDebug`/`assembleRelease`, incl. `lintVitalRelease`) green — no `missing_rules.txt`, so the
new `Hkdf` proguard keep rule is sufficient. **NOT hardware-confirmed** — same caveat as decisions
37-38, next live round needed for all three together.

## [0.7.5-dev] — Rotating group handle (P6's second slice)

Decision 38 (`docs/DECISIONS.md`), P6's second slice: closes `PLAN-v2.md` §4.4's cleartext-`groupId`
traffic-analysis gap. Every relayed frame type (SOS, position, evidence-meta, nickname, presence) has
always carried its `groupId` in the clear — any nearby phone capturing mesh traffic could correlate
packets to a group with no key needed at all, even after decision 37 sealed SOS content itself.
`groupId` is now replaced everywhere with an opaque rotating handle, `HMAC(groupKey, epoch)`.

Reuses the beacon's own existing HMAC construction (`CryptoUtils.rotatingAdvertisementId`),
generalized with a `windowSeconds` param rather than duplicated — every existing beacon call site is
unaffected. GATT frames get a new, much wider 72h window (`CryptoUtils.
GATT_GROUP_HANDLE_WINDOW_SECONDS`) instead of the beacon's own 60s one: a GATT handle is computed
once at creation and forwarded verbatim for a frame's entire relay life (a blind relay has no key to
recompute it with), so it has to keep resolving correctly up to this app's 48h content-retention
ceiling, not just a beacon payload's sub-minute one. New `GroupRepository.resolveGroupKeyByHandle`
(receiver-side resolution, modeled on `BeaconRadio.refreshCaches()`'s existing pattern) and
`MeshFrameCodec.groupHandle` (the shared computation point).

`Frame.EvidMeta`/`Nickname` became envelope-only structs (previously they decoded directly into a
ready entity) since `groupId` can no longer travel in the clear for them either.
`EvidenceEntity.groupId` went nullable so a blind carrier can keep relaying evidence chunks without
ever learning which group they belong to (an existing mechanism, traced and preserved).
`NicknameEntity` gained a genuinely new in-memory blind-relay path — its old cleartext-scheme
equivalent was already a dead end (every nickname push path only ever serves active groups), so this
is a real new capability, not a like-for-like port. `MeshFrameCodec.VERSION` 6 → 7, `AppDatabase`
v9 → v10.

Found and fixed a real bug while building this: `opaqueSos`'s carried frames were never actually
pushed back out anywhere — decision 37 added the accept-side but never wired the forward-side, so SOS
blind relay has been silently inert since it shipped. Fixed alongside the new nickname path.

381 tests (up from 370), detekt clean, both variants compile/test/assemble
(`assembleDebug`/`assembleRelease`, incl. `lintVitalRelease`) green. **NOT hardware-confirmed** — the
`VERSION` 7 wire break means no pre-checkpoint test APK can talk to this build until reflashed
(decision 37's own `VERSION` 6 was never hardware-confirmed either — both need the same next round).

## [0.7.4-dev] — SOS body encryption (P6's first slice)

Decision 37 (`docs/DECISIONS.md`), P6's first slice: `docs/DECISIONS.md`/`NEXT_STEPS.md`'s long-
flagged "SOS message text is authenticated but not encrypted" gap is closed. Since this app's
earliest build passes, `SosEntity.message`/`senderId`/`timestamp`/`isAlert` travelled over GATT as
cleartext plus a separate `HMAC(group_key)` tag — readable by any nearby non-member relay that
simply connects, no key required to read (only to forge). Position got the equivalent AES-GCM
treatment back at v2/v3; SOS now gets it too.

`Frame.Sos` replaced with `Frame.SosSealed(groupId, id, ttl, hop, sealed)` — only `groupId`/`id`/
`ttl`/`hop` stay in the cleartext envelope (same shape `Frame.PositionSealed` already uses), so a
non-member blind relay can still dedup, flood-control, and carry an SOS onward without ever reading
it. New `MeshFrameCodec.sealSosBody`/`sealSos`/`openSos`/`reframeSosForRelay`, mirroring
`encodePosition`/`openPosition`/`reframePositionForRelay` exactly — a failed decrypt IS the auth
failure now, replacing the old separate mac check for this one frame type (evidence-meta and
nicknames are unchanged, still HMAC-plus-cleartext). `RelayResponder.handleSos` gained a genuine
blind-relay path (`takeOpaqueSosCustody`) — impossible before this decision, since the old scheme's
auth check vacuously passed for a non-member (nothing was gained by refusing to read already-
cleartext content). `MeshFrameCodec.VERSION` 5 → 6, `AppDatabase` v8 → v9
(`SosEntity.mac`/`signature` → one `sealed: ByteArray?`).

Found and fixed a real bug while finishing this checkpoint: `RelayEngine.createSos` was storing
`sealSos`'s fully-framed wire output directly as `SosEntity.sealed`, but every consumer of that
field treats it as raw ciphertext only — left as originally committed, every self-authored SOS would
have double-framed on its first send. Fixed by splitting the raw sealing step into a new
`MeshFrameCodec.sealSosBody`, which `createSos` now calls directly.

370 tests (up from 367), detekt clean, both variants compile/test/assemble
(`assembleDebug`/`assembleRelease`, incl. `lintVitalRelease`) green. **NOT hardware-confirmed** —
the `VERSION` 6 wire break means no pre-checkpoint test APK can talk to this build until reflashed.

## [0.7.3-dev] — Closes out P2: SOS preview UI, multi-hop position, raised hop ceiling, catalogue filter, and the SOS/message split

Five decisions land in this checkpoint (31-35), finishing everything in P2's own scope except
receive-side use of the catalogue filter. Full reasoning in `docs/DECISIONS.md`; this is a summary.

**Decision 31 closes the last named follow-up from decision 29**: a Tier B SOS content preview was
verified but never shown to the user, only logged. New `BroadcastSosPreview` caches the verified
preview in memory only (never persisted — still not a real `SosEntity`, missing fields a stored
record requires, same as before). Deliberately has no staleness clock of its own: it only returns a
cached preview when its id still matches `HopTracker.bestActiveSos`'s current answer — the same
source its hop-gradient already comes from — rather than tracking freshness a second, independent
way. Wired through the same group-teardown lifecycle decision 30 gave `PositionTracker` (cleared on
group delete, swept on the existing periodic prune). `NavigateScreen` now shows it: quoted, labeled
"unconfirmed preview, connecting to verify," and suppressed once the real, GATT-confirmed SOS
message already exists.

**Decision 32 closes decision 27's "own gossip design, out of scope" deferral**: position broadcast
over Tier B was single-hop only (the originating device's own fix, heard only by its direct
neighbours). Now, whenever a device has no GPS fix of its own to broadcast, it relays the closest
position it's holding for someone else instead of leaving that slot empty — reusing the exact same
relay-selection and verbatim-forwarding machinery GATT relay already uses, no new loop-prevention
mechanism needed (distance-vector relaxation is already loop-safe on its own, the same property
presence/SOS hop-gradients already relied on). Also fixed a real bug found while wiring this up: the
receive side was reading a stale, frozen hop value baked into the encrypted position body instead of
the envelope hop that relaying actually updates — invisible while Tier B was single-hop, would have
silently mis-tracked every relayed position's distance once hops started varying.

**Decision 33: position relay hop ceiling raised 4 → 120.** Direct ask: maximize real reach through
an unbroken relay chain, not bounded by this app's own 3-8-person group size. 120 is the largest
value the wire format's 1-byte hop field safely supports below its reserved "unknown" sentinel (255)
— reaching hundreds of kilometers would need a 2-byte field, a real wire break deliberately not made
this pass. Also fixed a real precision gap this surfaced: the radar's stale-dot fade used to be a
flat 180-second window, unrelated to a position's real (now much larger) staleness budget — a dot
now fades relative to its own actual eligibility window instead.

**Decision 34: catalogue digests over the broadcast tier — the last P2-scoped Tier B payload item.**
Stopped first: sizing the filter by held-item count (the obvious, GATT-matching approach) would let
any passive scanner infer roughly how much content a group holds, and watch that estimate change
over time, without ever connecting. After seeing the real functional cost of the privacy-preserving
alternative (a fixed-size filter's usefulness collapses past ~50 items), item-count-scaled sizing was
kept anyway — a deliberate, weighed exception. Found and fixed a real budget bug before shipping:
position plus the bare SOS hop-gradient (no content) plus a maxed filter overruns the ~251-byte
budget on its own — the filter is now the lowest-priority field in the beacon, dropped first whenever
it doesn't fit. Broadcast side only this pass; nothing yet consumes a received filter.

**Decision 35: the SOS/message split.** Every message sent in this app has always been modeled as an
SOS — there was never a separate casual-chat type, so every ordinary message silently triggered the
loud emergency notification and the Tier B SOS broadcast machinery. Fixed with one new flag
(`SosEntity.isAlert`) rather than a parallel message type: storage, relay, and catalog sync stay
exactly as they were for every message, alert or not; only the hop-gradient, the alarm-style
notification, and the Tier B content preview now require it. The group chat screen gained a
dedicated, clearly-marked SOS action, separate from its now-quiet default Send button. The SOS
broadcast preview's byte cap also dropped from 100 to 65 bytes, sized for a short, actionable alert
now that it only ever governs genuine emergencies.

367 tests (up from 337), detekt clean, both variants compile/test/assemble green. **Wire-format
note:** decision 35 bumped the shared frame version — a pre-this-checkpoint test build cannot talk
to this one until reflashed.

## [0.7.1-dev] — First v0.7.0-dev hardware round: two bugs and a gap fixed, radar polish

First live 3-phone test of P2's Tier B broadcast work (2026-08-06). Found and fixed two real bugs
and one real gap; confirmed one more report as design-intentional, not a bug. Full reasoning for
every fix below is in `docs/DECISIONS.md` decision 30; this is a summary.

**Fixed: presence hop count sometimes read 3-4 between phones that should never see more than 1-2.**
`BeaconRadio.handleResult` was calling `HopTracker.considerNeighborReport` twice per scan result
from the same source — once for the direct hearing, once for the propagated value — and the second,
worse call could undercut the first, better one it had just set. Merged into a single call.

**Fixed: a deleted group's last-known member positions kept showing on the radar long after the
group was gone.** `PositionTracker` (in-memory, ble-layer) had no way to hear about
`GroupRepository.dismantleGroup` (data-layer). Now cleared immediately at the delete-group call
site, with a periodic orphan sweep as a safety net for automatic group expiry too.

**Fixed: a nickname set after a persistent link was already open never reached that peer.**
Nicknames were only ever pushed once, on connect — unlike presence/position, which already got a
periodic refresh fix for exactly this P3 persistent-link scenario. Now nicknames get the same
periodic push.

**Explained, not a bug: position sometimes read 3-4 hops on a 3-phone test.** Traced to other nearby
devices' blind-relay traffic combined with the existing, deliberate 4-hop position relay ceiling —
correct behavior once more than 3 devices are in range.

**Radar UI polish**, per this round's live feedback: grays and the reserved presence/"safe" green
both brightened again (contrast was still too low in bright/outdoor light); the radar's background
wash, glow rings, crosshair, and cardinal ticks all raised further; peer dots tightened for a
sharper look (smaller, fainter halo; higher minimum core brightness) without touching the
distance-based blink cadence, which tested well as-is.

342 tests (up from 337), detekt clean, both variants compile/test/assemble green.

## [0.7.0-dev] — P2 broadcast tier (Tier B): first production wiring, four slices

The first real payload on PLAN-v2.md's Tier B connectionless broadcast channel — generalized from
what was a Coded-PHY-only "long-range channel" into the actual §5.1 broadcast tier, then built out
across four deliberately narrow slices in one session, each hardware-gate-ready (compile/test/
detekt/lint clean) but **none of it hardware-confirmed yet** — this channel has never run on a real
phone. Full reasoning for every decision below is in `docs/DECISIONS.md`; this is a summary.

**Tier-1 sim work first (decisions 23-25):** resolved P2's own open sighting-scope question —
Trickle suppression sightings are scoped to own-group broadcasts only (already true in production).
That dissolved an earlier "boundary bug" finding but surfaced a real production bug in the process:
`TrickleTimer.onSighting()` counted raw radio-reception calls instead of distinct neighbours, so a
single present group-mate could permanently pin broadcast suppression regardless of true
redundancy. Fixed — `onSighting(sourceId)` now dedupes within a window.

**Slice 1, presence hop-gradient (decision 26):** capability gate loosened to
`extendedAdvertisingSupported` alone (Coded PHY now an opportunistic range upgrade, not required —
broader hardware support than before). New payload adds an explicit `presenceHop` field so presence
can propagate a real multi-hop gradient over broadcast with zero GATT connections. Hardware
`ScanFilter` on the service UUID restored, but only on this new scan (legacy scan stays untouched).
Degree-gated `setReportDelay()` scan-report batching added.

**Slice 2, single-hop position (decision 27):** reuses `MeshFrameCodec.encodePosition`'s full
output verbatim — no new crypto or framing — resealed on a fixed ~20s cadence so a stationary
sender's dot doesn't go stale on others' radar. Verified via `RelayResponder.signatureCheckPasses`,
the same trust bar a GATT position gets. Single-hop only: a receiver never re-broadcasts a position
heard from someone else (extended advertising has no natural "relay"); multi-hop position still
rides the existing, unaffected GATT relay path.

**Slice 3, SOS hop-gradient (decision 28):** closes P2's other original deferral, deliberately NOT
the way an earlier, already-reverted mechanism did it (a rough, sosId-agnostic hop estimate mixed
with exact per-SOS tracking — confirmed by `grep` that the legacy beacon's own `sosHop` field has
sat unread ever since). Fixed by keying on the real SOS id (`HopTracker.bestActiveSos`, split out of
`bestActiveSosHop`) so a Tier B sighting composes with GATT flood-forward's own tracking instead of
aggregating. Forced a wire-format redesign (both optional blocks now always length-prefixed) — a
safe breaking change, since nothing has ever run this format.

**Slice 4, SOS content preview (decision 29):** the one genuine threat-model call in this batch —
stopped mid-design to ask before shipping it. The obvious approach (reuse the existing
`sosMacInput` scheme) needs the sender's device id in the clear to verify, which would escalate an
already-flagged, unresolved gap (SOS content is cleartext even over GATT — see `NEXT_STEPS.md`) from
"readable if you connect" to "passively readable by any nearby BLE scanner." User chose to avoid the
new exposure: new `MeshFrameCodec.broadcastSosMacInput` deliberately excludes `senderId` — a second,
non-interchangeable mac scheme under the same group key. Broadcasts a short (≤120B) preview of
whichever SOS is nearest, ours or a relayed one held; position is dropped from any cycle carrying
SOS content (budget forces the priority call). Verified on receipt but not stored — a preview is
missing fields a real `SosEntity` needs; full UI surfacing is a named follow-up.

**Not yet started, named explicitly:** full UI surfacing of the SOS content preview; multi-hop
position propagation over broadcast; thumbnails/catalogue digests; the Tier 2 (synthetic radio load)
and Tier 3 (real hardware) gates this whole channel still needs.

337 tests (up from 304), detekt clean, both variants compile/test/assemble (incl.
`lintVitalRelease`) green.

## [0.6.3-dev] — Bluetooth off/on no longer strands the mesh

Third live test round, same overall 3-phone session (2026-08-05): messages instant, radar tracked
correctly at 1-2 hops. Two hop-count questions turned out to be expected behavior, not bugs (see
`docs/DECISIONS.md` decision 22 for the full reasoning): asymmetric hop readings between two phones
are normal — `HopTracker` is per-device/per-observer, not a shared value — and an observed "4 hops"
on a 3-phone setup is a rare, self-correcting artifact of position updates being deliberately
undeduplicated, made to look worse than it is by staleness grace *growing* with hop count.

One real bug found: toggling Bluetooth off then back on (manual toggle, airplane mode, some battery
savers) left the mesh permanently stranded — confirmed in this same test round, only recovered by
manually tapping the app's own offline/online toggle. Root cause: `MeshService`'s
`ACTION_STATE_CHANGED` receiver only ever updated a UI-facing flag; it never actually restarted the
radios. `setMeshActive(true)`/`(false)` already had the correct stop/restart sequence — it just
wasn't wired to the real Bluetooth-adapter event, only to the manual toggle.

- `MeshService`: `setMeshActive`'s radio-handling split into `startRadios()`/`stopRadios()`. The
  Bluetooth-adapter receiver now calls these on a genuine off→on or on→off transition, gated on the
  mesh actually being meant to be active (`_meshActive.value`) so it never fights a deliberate
  manual "offline" toggle. New `DiagnosticsLog` events (`bluetooth back on - restarting radios` /
  `bluetooth off - stopping radios`) so a future log review doesn't need this round's guesswork to
  tell whether an adapter cycle happened.

304 tests, detekt clean, both variants green. Hardware-confirmed 2026-08-05 via a quick 2-phone
check: Bluetooth off→on recovered the mesh on its own.

## [0.6.2-dev] — second live test confirms the 0.6.1-dev fix; finds a duplicate-callback leak

Second hardware test, this time of 0.6.1-dev (3 phones, `DiagnosticsLog` exports from all three
reviewed). Both symptoms from the first test are confirmed fixed by log evidence: position updates
now arrive in a continuous ~15s cadence for the entire session (previously one-shot, then stale)
and a chat message was delivered on an already-open connection with no new connect event anywhere
nearby (previously stuck until a fresh connection formed). The one new symptom reported — "radar
error, said waiting for GPS fix" on one phone, not the others — was a per-device location-accuracy
setting, not a mesh bug; resolved by the user and confirmed working after.

Investigating the logs for unrelated anomalies found one real bug: the same peer address logging
`synced ok` two or three times within about a second — far too fast to be a genuine disconnect and
reconnect. Root cause: some Android BLE stacks re-fire `onMtuChanged` (which unconditionally calls
`discoverServices()`) without an intervening disconnect, so `onServicesDiscovered` fires more than
once for one physical link. Before P3 this was harmless (a short-lived connection just ran
`pushOnConnect` twice in the same few seconds); now it silently spawns a second, independent
`periodicRefresh` loop that lives and keeps re-pushing frames for the connection's entire persistent
lifetime (minutes) — wasted radio/battery, not a correctness bug (dedup already absorbs the
duplicate frames on the receiving end).

- `MeshGattClient`: new `handledGatts` identity-set guards `onServicesDiscovered`'s setup
  (registration, `pushOnConnect`, `periodicRefresh`) to run at most once per connection; cleaned up
  on disconnect.
- `MeshGattServer`: `onDescriptorWriteRequest`'s existing (previously unused) `subscribedDevices.add`
  return value now gates the same setup, for the symmetric case where a client's duplicate CCCD
  write reaches the server side.

304 tests, detekt clean, both variants green. NOT yet hardware-confirmed itself — this specific fix
is new this pass; the message/radar fixes it sits on top of ARE hardware-confirmed as of this
round.

## [0.6.1-dev] — first live P1+P3 test finds and fixes two "only received content moves" gaps

First hardware test of 0.6.0-dev (2 of 3 phones' `DiagnosticsLog` exports reviewed). Reported: a
message between two already-connected phones sat delayed; the radar didn't update; both resolved
the moment a third phone joined — "like a relay happened." Root cause, both symptoms: P1's flood-
forward and the one-shot connect-time push both only ever moved content that either just *arrived*
or that existed at the *moment a connection opened* — nothing told an already-open, now-persistent
link about content *originated locally* mid-session. Full diagnosis in `docs/DECISIONS.md`
decision 20.

- `MeshService.sendSos` now calls the same flood-forward a received SOS gets, immediately after
  creating it — a message no longer waits for the next connection to form.
- New periodic presence/position refresh (`RelayResponder.refreshFramesToPush`, run every 15-30s by
  both `MeshGattClient` and `MeshGattServer` on every held connection) — without this, a radar dot
  only ever refreshed once, at connection start, and then went stale for the *entire* life of a
  persistent link (now minutes), the opposite of what P3 was for.
- Evidence-header/nickname content still only moves via the one-shot connect-time push — same shape
  of gap, deliberately not revisited this pass (smaller blast radius than SOS/radar).

304 tests, detekt clean, both variants green. Hardware-confirmed in the 0.6.2-dev round above: both
fixes hold under a second live 3-phone test.

## [0.6.0-dev] — P1 forwarding + P3 persistent links land in production

First production wiring of PLAN-v2 P1 (forwarding plane) and P3 (persistent links), built together
after the P1+P3 simulator (0.5.0-dev) found P1 alone doesn't deliver its own headline latency claim
without P3 supplying links that are actually open when needed. Landed as separately-verified steps,
not one large change. 304 tests, detekt clean, both build variants (incl. R8-minified release)
green. Bumps `MeshFrameCodec.VERSION` 3→4 (SOS gains a cleartext hop byte) and `AppDatabase.version`
6→7 (`SosEntity.hop`). Full reasoning in `docs/DECISIONS.md`, decisions 18-19.
**NOT hardware-confirmed — this is the single highest-risk change in the project's history**,
touching the connection-lifecycle subsystem behind the most historical regressions (decisions 1, 2,
5, 8, 9, A2). Needs a sustained multi-hour real 3-phone session before any of it is trusted the way
the rest of this codebase's hardware-verified passes are.

**P1 — immediate forward, SOS only:**
- `SosEntity` gains an explicit `hop` field, incremented by exactly +1 on every ingest, decoupled
  from `ttl` (which a degree-aware relay may now drop by more than 1 in one hop) — closes the risk
  flagged before any of this was built. Caught and fixed a real off-by-one while wiring it: a
  device's own hop-from-origin is `frame.sos.hop + 1`, not `frame.sos.hop` directly (the old
  ttl-derived formula had a "+1" baked in that the naive replacement missed).
- New `ConnectionRegistry` gives `RelayResponder` a shared view of every currently-open connection
  from BOTH GATT roles, for the first time — needed to flood-forward across links other than the
  one a frame arrived on.
- `RelayResponder.handleSos` now immediately forwards a genuinely new SOS to a fanout subset of
  every other open link (real `ForwardingPolicy`: jitter, degree-clamped TTL, fanout subset),
  instead of waiting for each link's own next catalogue-sync. Evidence-header/nickname forwarding is
  the same pattern, deliberately deferred to keep this change reviewable.
- `DedupCache` (built alongside `ForwardingPolicy` in the simulator work) is deliberately NOT wired
  in — the existing DB-backed `ingestSos` dedup already serves the same purpose; adding a second
  layer now would be redundant complexity with no current benefit, not a missing piece.

**P3 — persistent links:**
- `MeshGattClient` no longer disconnects a healthy connection on a fixed idle/max timer
  (`connectionIdleMs` is gone entirely). A connection stays open until diversity-evicted, fails on
  its own, or hits a distant safety-net backstop (`BleTuning.Profile.connectionBackstopMs`, minutes
  now instead of the old ~20s `connectionMaxMs`).
- Real RSSI (`ScanResult.rssi`, not the simulator's synthetic stand-in) feeds a new
  `LinkSelector`-based eviction check — only considered once every concurrent-connection slot is
  already held — so a node can still discover better-spread peers instead of permanently locking
  onto whichever three happened to connect first.
- Found and fixed three real bugs while implementing this, before any shipped: (1) the existing
  hard-deadline watchdog would have force-disconnected every healthy persistent link after 60s,
  silently undoing P3 entirely; (2) `setMeshActive(false)`/`onDestroy` would have leaked every held
  connection past the old ~20s residual they were designed around; (3) a `ConcurrentHashMap`
  `in`-operator gotcha (resolves to `containsValue`, not `containsKey` — KT-18053) that would have
  made two safety checks silently no-ops in every case, caught by the Kotlin compiler as a hard
  error before any test ran. Full detail on all three in decision 19.

## [0.5.0-dev] — v2 scaling plan begins: a crowd-scale test rig, and peer state stops being keyed on a rotating address

First implementation work on `PLAN-v2.md` (the scaling plan, source of truth over `NEXT_STEPS.md`
where the two disagree). Two phases, both required before P1's forwarding-plane rewrite can start:
**P0a** (the crowd-scale simulator — a prerequisite, since v2's scaling claims were otherwise
unfalsifiable) and **P0b** (re-keying peer state off the BLE MAC). 270 tests, up from 247, detekt
clean, `assembleDebug`/`assembleRelease` both green. Compile/sim/test-verified only — **not yet
hardware-confirmed**; see PLAN-v2.md Part 7's updated preamble for how that verification now works
(debug APK → real-phone test → exported `DiagnosticsLog` sent back for review, asynchronously, not
a precondition for landing code). Full reasoning in `docs/DECISIONS.md`, decisions 14-15.

**P0a — the Tier-1 JVM crowd simulator** (`app/src/test/java/org/offlinemesh/app/sim/`, test-only,
ships in no APK):
- A discrete-event harness (`SimClock`/`SimEventQueue`) driving the REAL extracted decision classes
  (`ConnectionAttemptTracker`, `CatalogFilter`, `OpaqueFrameRelay`) instead of re-implementing their
  logic, from D=2 (a 3-phone test) to D=400 (a static crowd) in the same JVM test run.
- PLAN-v2.md §6.2's eight invariants (I1-I8) mechanised as assertions over a run's recorded trace,
  each independently unit-tested for both its pass and fail path.
- §6.1's platform-quirk injection (address rotation, advertise-incapable nodes, radio-churn
  instability with a total-failure breaker, callbacks that never arrive, half-open connections,
  malicious nodes) as configurable knobs, each named after the pass that found the real bug it models.
- The P0a gate itself: reproduces v1's measured diagnostics-10 numbers at D=3 (mostly-empty
  catalogue syncs, pair-sync cadence anchored on the 45s reconnect cooldown) and shows D=400's
  full-sync convergence lands in minutes, not seconds — confirming the harness models something
  real before anything gets built on top of it.
- Seven of the eleven named §6.3 scenarios running (S1, S2, S6, S7, S8, S9, S11); S3/S4 need a
  broadcast tier to have anything to test (P2), S5 needs couriers (P4), S10 needs a media-transfer
  model (P5) — documented as not-yet-built rather than shipped shallow.
- A real bug in the simulator itself, caught by its own calibration gate: the first draft let both
  sides of a pair independently initiate a connection, measuring a pair-sync cadence at roughly
  half the real reconnect-cooldown-governed rate. Fixed by having only one side (deterministically)
  initiate per pair, matching how a real GATT link is one connection, not two.

**P0b — peer state re-keyed off the BLE MAC** (PLAN-v2.md §5.2):
- New `PeerIdentityResolver` (`ble/`, pure/Android-free, unit-tested): resolves a transient BLE
  address to the stable `senderId` behind it once an authenticated frame on that connection reveals
  who it is. Falls back to the address itself for an unresolved peer — a brand-new address always
  costs what v1 always cost; the benefit is every reconnect after the first one this session.
- `HopTracker`'s route-ownership (`considerNeighborReport`/`considerDirectHop`) now sources on the
  frame's own `senderId` instead of the transient peer address, at all three call sites (SOS,
  presence, relayed position) — closes the exact gap `NEXT_STEPS.md` D1 and PLAN-v2.md §1.3 named:
  route ownership no longer strands on an address that rotated out of existence mid-session.
- `MeshGattClient`'s `ConnectionAttemptTracker` cooldowns now key on the resolved identity too, once
  known — the address-rotation-causes-reconnect-storm bug §1.3 also names. Required care: the
  resolved key is captured ONCE per connection attempt and threaded through every callback of that
  same attempt, never re-resolved mid-flight — a mid-connection identity resolution updating the
  key a later callback used would leak the original attempt's tracker entry forever.
- Found and fixed a second, independent, already-confirmed bug while in this code:
  `RelayResponder.sessionBudget`/`catalogItemBudget` were reset to 0 (not removed) at the start of
  every connection, accumulating one entry per address ever seen, forever — unbounded regardless of
  address rotation. Fixed with `.remove()`, which is both simpler and stricter than the LRU bound
  `peerWfdCapable` already used for the same class of problem.
- `DiagnosticsLog` gains an `identity` event logged only when a peer's address→identity mapping
  actually changes, reporting both `addresses=` (raw BLE addresses tracked) and `distinct=` (unique
  peers resolved) — the concrete thing the P0b hardware gate asks a real 3-phone log to show:
  `distinct` should stay near the physical phone count even as `addresses` climbs with rotation,
  unlike the pre-P0b diagnostics' 19-prefixes-for-3-phones.
- Deliberately NOT re-keyed: `sessionBudget`/`catalogItemBudget`/`peerWfdCapable`/`negotiatedMtu`/
  `syncedThisSession`. All five are reset at the start of every connection already — re-keying them
  changes nothing observable, since they never survive a reconnect regardless of key type. PLAN-v2.md
  §1.3 lists these among the "keyed on a value that churns" concern, but the actual fix for a
  per-connection-scoped value is bounding it (done, above), not re-keying it.

### P1 forwarding-plane simulator work (sim-only, no APK change, same 0.5.0-dev cycle)

Built P1 (PLAN-v2.md §5.3, "the change that matters") in the simulator, per the same discipline as
P0a/P0b: nothing production-risky until its sim gate passes. Two new pure classes — `DedupCache`
(in-memory hot-layer dedup, 3000 entries per §9.2 item 6's derivation) and `ForwardingPolicy`
(jitter/TTL-clamp/fanout-subset, gated on currently-open link count) — plus `ForwardingPlaneEngine`
in the sim, layering immediate-forward-on-open-links over the same connection lifecycle
`CatalogSyncEngine` already models. 288 tests (up from 270), detekt clean, both variants green.

**Real finding, not a positive result to gloss over:** measured against the sim, P1 alone does NOT
deliver its own hardware-gate headline claim ("3 phones in a line — a relayed SOS arrives in
seconds, not the current ~45s/hop"). The literal 3-phone-line topology measured 52s (vs v1's ~90s
worst case); D=3 average convergence measured 31.7s vs the v1-baseline engine's 36.0s — real, but
~12%, not order-of-magnitude. Root cause: flood can only use a link that's already open, and under
the still-connect/sync/disconnect lifecycle (P3 "persistent links" not yet built) a link is open
only ~15-20s of every ~60-65s cycle regardless of density. Full reasoning and the D=400 result
(100% delivery, 50/50 items, latency distribution unmeasured) in `docs/DECISIONS.md` decision 16.
Deliberately NOT wired into production (`MeshFrameCodec`/`RelayResponder`/GATT layer untouched) —
the wire-format work (a packet header needing its own explicit hop field, decoupled from TTL) is
real and scoped, but committing to it before resolving how P1 and P3 should actually be sequenced
together risks building the wrong shape twice.

### P3 link-management simulator work (sim-only, no APK change, same 0.5.0-dev cycle)

Built P3 (persistent links) in the simulator immediately after P1, per the user's explicit
sequencing call following P1's own finding above: measure P1+P3 together before touching
production. New `LinkSelector` (pure, ble/) makes the diversity-vs-first-heard eviction decision;
new `PersistentForwardingEngine` in the sim replaces the fixed-session connection lifecycle with a
genuinely persistent one, reusing P1's `ForwardingPolicy`/`DedupCache` unchanged. 295 tests (up
from 288), detekt clean, both variants green.

**Result: P3 closes P1's gap, dramatically.** Re-running P1's own 3-phone-line hardware-gate
scenario under the combined engine measured **48ms**, down from P1-alone's 52,000ms — because both
links are already open by the time the SOS is injected, so the flood crosses both hops in one
jitter window instead of waiting out a reconnect cycle. Confirms P1 and P3 need to land together,
not sequentially. Full reasoning, plus a real test bug found and fixed along the way (a diversity
threshold not scaled to the neighbourhood size it was being compared against), in
`docs/DECISIONS.md` decision 17.

Still sim-only — production wiring (wire format, GATT connection-lifecycle rewrite, RSSI capture,
a connection registry) is the next, largest piece of work, not started this pass.

## [0.4.0] — positions and presence are now blind-relayable, and the hop count actually moves

The headline bug this release finally root-causes: on a live 3-phone A-B-C topology, the group row
had read "1 hop(s) away" through every prior build and content relayed fine while the radar never
showed the far phone. Root cause was three compounding bugs, not one, all found and fixed this pass
via repeated live 3-phone capture-and-audit (real numbers cited below, not estimates). Bumps
`MeshFrameCodec.VERSION` 2→3 (position frame's `hop` moved from inside the sealed body to the
cleartext envelope — a relay that can't decrypt a position can't increment a hop it can't see).
`AppDatabase.version` unchanged at 6 — no schema change, this is all wire/logic. 247 tests, up from
203, still detekt-clean; `assembleRelease` (R8-minified) also verified green. Full reasoning for
every item below is in `docs/DECISIONS.md`, decisions 8-13.

**Positions and presence can now cross a non-member phone (decision 10):**
- Three captured sessions showed 627 positions received, every single one at hop 0 — a non-member
  phone was refusing to carry a sealed position at all, so the exact test topology this app is built
  for (member, stranger, member) could never deliver a radar dot. A non-member now takes custody of
  the sealed ciphertext and forwards it verbatim, never opening or re-encrypting it — the original
  privacy guarantee is unchanged, it just no longer also (wrongly) blocked relay.
- Presence gets the same fix, needed separately: relaying positions alone only fixes presence for a
  member who currently has a GPS fix, so anyone indoors or on a cold fix still read as absent rather
  than distant.

**The relay loop this immediately exposed, fixed with split horizon (decision 12):**
- The first working blind-relay session showed a textbook distance-vector loop: one member received
  267 positions from a single sender, arriving at hop 0, 1, 2 *and* 3 — one position circulating the
  three-phone triangle at escalating hops, invisible at 2 phones and only appearing at 3. Fixed the
  standard way: never advertise a route back toward the peer that taught it to you.

**The hop count itself was frozen, not broken (decision 13):**
- `HopTracker` refreshed its "last updated" timestamp on every report *including rejected ones*, so
  any traffic on a group — even a 3-hop frame from a stranger — kept a previously-recorded "1 hop"
  permanently fresh and unable to ever rise. This, not a relay failure, is why the group row never
  moved. Fixed: recency now only refreshes on a report that improves or confirms the current value.
- Two of this fix's own neighbours broke as a direct result of the decision-10/12 changes and are
  fixed in the same pass: presence's replay-skew check didn't account for relay hop latency and
  rejected legitimately-relayed heartbeats as replays; the member relay path was re-encrypting every
  forwarded position, defeating downstream ciphertext dedup and silently dropping the sender's
  signature.

**Advertise churn root-caused and fixed twice over (decisions 8-9):**
- A phone in 2+ groups was restarting its BLE advertiser every ~3.1 seconds continuously for a full
  19-minute session (343 of 526 captured log lines were this one event) — round-robin group
  switching looked like a payload change on every tick, so the "only restart if payload changed"
  guard could never short-circuit. A first fix (dwell 60s per group) stopped the churn but broke
  same-room discovery for any 2-group phone outright; reverted the same day. Second, adaptive fix:
  dwell 0 (rotate every check) while blind to any group's presence, 10s once presence is established
  — cuts restarts ~3x without starving discovery.
- Root fix, found after both tuning attempts: `AdvertisingSet.setAdvertisingData()` updates the
  beacon payload in place, with no radio restart at all, making the whole churn-vs-latency tradeoff
  moot. The adaptive dwell stays in place but is now largely redundant.
- The experimental long-range (BT5 Coded PHY) advertiser was failing every attempt on real hardware
  and retrying every 2-4s for an 18-minute session with zero successes — now gives up after 3
  consecutive failures per session instead of retrying forever.
- A position-driven fast-path bypass of the reconnect cooldown had no rate limit, so continuously
  changing GPS meant it fired on nearly every scan result — a reconnect storm through only 3 client
  slots. Rate-limited to one bypass per peer per 10s.

**Radar staleness window widened, and its cost made visible (decision 11):**
- Measured (not guessed) across three ~110-minute sessions: refresh p90 was 36-52s against a 90s
  staleness window — only ~1.8x headroom, so ordinary tail latency blanked the dot in 5-8% of gaps.
  Window widened 90s → 180s in both `PositionTracker` and `HopTracker` (kept equal on purpose).
  Paid for explicitly: the stale-dot fade now spans the full 180s instead of stopping at 90s, so an
  old dot reads as a fading ghost rather than either vanishing or looking current.

**Also this pass:**
- On-device diagnostics log (debug builds only, same precedent as LeakCanary) — event types, counts,
  reject reasons, truncated peer IDs only, never positions or message bodies. Exported via the
  existing `FileProvider`; `capture_debug_log.sh` added for pulling the full logcat stream during a
  live test.
- A stale Ed25519 sender-key pin no longer hard-blocks all future traffic from that peer — a changed
  key in a presence heartbeat now re-pins and warns instead of permanently poisoning the pair; a
  signature that fails under the *current* pin still rejects.
- A connection stuck past `CONNECTED` with the radio gone silent (no `DISCONNECTED` ever fires) no
  longer leaks that peer's slot forever — a second, connection-lifecycle timeout now catches it.
- Blind carriage of other groups' opaque frames (SOS/evidence-header/nickname) is now capped and
  rotated per connection, so a phone carrying heavily for strangers can't starve pushing its own
  group's content.
- Background location permission requested once, after core setup, never at launch — live testing
  found GPS fixes going sparse within seconds of screen-off regardless of foreground-service state;
  declining it leaves everything else working, just with staler positions in that one situation.

## [0.3.0] — groups are ephemeral by design, sender identity, and a security/efficiency pass

The framing shift behind this release: groups in 20.07 are ad hoc and short-lived (2-3 days
typical, 6 months an absolute ceiling), not standing chat rooms — which changes which problems are
worth solving. Bumps `MeshFrameCodec.VERSION` 1→2 and `AppDatabase.version` to 6 (destructive
migration, as with every schema bump so far — see Known Limitations). 203 tests, up from 112, still
detekt-clean; `assembleRelease` (R8-minified) also verified green.

**Ephemeral group expiry — the headline feature:**
- A group's lifetime (12h/48h-default/7d/30d/6mo, chosen at creation) is baked directly into the
  shareable join code's binary format, not tracked per-phone — so every member, whoever created it
  and whoever joined later, agrees on the exact same expiry moment.
- Every member's own app dismantles an expired group on its own — key, messages, evidence, all of
  it — checked on a periodic sweep and once on every app startup.
- Home screen shows a countdown per group, flagged as expiring soon within the last 2 hours.

**Sender identity (Ed25519), additive to the existing group-key authentication:**
- Every member gets a per-(device, group) — not per-device — Ed25519 keypair, so a device can't be
  linked across the different groups it's in.
- A signature under this key rides alongside the existing group HMAC on SOS, evidence headers,
  nicknames, presence heartbeats, and (inside the encrypted envelope, so it's invisible to blind
  relays) position updates — telling members apart, not just confirming "someone with the group key
  sent this."
- Trust is pin-on-first-sight, not a certificate authority: a receiver pins whichever public key it
  sees first from a given sender in a given group, and hard-rejects any later, different key for
  that same sender — matching this app's flat, no-owner group model. Full reasoning in
  `docs/DECISIONS.md`, decision 7.

**Security fixes:**
- A hostile `totalChunks` value in an evidence header or manifest could force a large allocation on
  every device that relayed it, repeatedly, until the 48h prune — now capped and rejected at decode.
- The SOS auth tag only covered the first 255 bytes of a message (a length-prefix mismatch between
  the MAC input and the wire encoding) — a relay could rewrite everything past that point
  undetected. Fixed, and the two can no longer independently drift again (shared writer functions).
- A captured presence heartbeat could be replayed indefinitely and still verify as authentic —
  added a skew check on the timestamp before any key/MAC work happens.
- A stale SOS re-ingested after its short-lived dedup cache (not the content itself) expired could
  re-fire the high-priority alarm notification for hours-old content.
- Unbounded WiFi Direct accelerator socket reads driven by an attacker-controlled length prefix.

**Efficiency / correctness:**
- Catalog-sync filter now sizes itself to the group's actual catalog instead of a fixed worst-case,
  with an MTU-aware fallback so a filter that still doesn't fit a low-MTU connection can't silently
  drop delivery.
- Fixed a cross-peer GATT notification race on the server side (API 33+ path takes the value as a
  parameter instead of shared mutable state).
- Hop-tracking now tracks which peer "owns" the current best value, so a route that's genuinely
  gotten worse is reflected instead of frozen at its best-ever reading forever.
- A per-connection cap on catalog-item pushes, so one connection with an unusually large deficit
  can't starve the rotation through other peers — anything left over is retried next reconnect.

**Structure:**
- Verbose inline "why" comments explaining past live-testing failures extracted into
  `docs/DECISIONS.md`, leaving one-line pointers in the code.
- `RelayResponder`'s 165-line frame dispatcher split into one small handler per frame type.
- WiFi Direct files moved into their own `transport/wifidirect/` package.
- `detekt-baseline.xml`'s ad hoc per-function `@Suppress` annotations mostly replaced by two
  targeted rule-config changes (`detekt.yml`).

## [0.2.1] — loosened the radar's GPS-accuracy gate

Live-confirmed trigger for the "peer shows a hop count but never gets a radar dot" gap from
0.2.0: many phones deliberately widen their reported GPS accuracy while stationary (to save
battery), then tighten it back up the instant motion is detected — so a dot could disappear
while genuinely standing still, even right next to the other phone, and reappear on the next
step. `ROUGH_FIX_METERS` raised from 150m to 250m combined, comfortably clearing the radar's
own 200m display ceiling instead of sitting just under it, so this triggers less often while
still rejecting genuinely rough (network-location-grade) fixes. The underlying silent-failure
UX gap (no on-screen explanation when this does trigger) is unchanged, still tracked in Known
Limitations.

## [0.2.0] — radar overhaul, relay reliability, theming, and a round of live-testing fixes

The biggest pass since 0.1.0, driven directly by live 2-3-phone testing rather than review alone.
112 tests, up from 99, still detekt-clean.

**Radar:**
- SOS senders now render as a red dot on the radar (both Home and per-group) instead of only a
  text distance line — the actual answer to "how do I find who sent it."
- Peer dots fade with age past ~30s (positions can be up to ~90s old by design) instead of
  looking exactly as live as a fresh one.
- North indicator is a 2x arrowhead instead of a plain dot.
- **Fixed a real, live-confirmed bug: compass heading was raw magnetic-north with no declination
  correction**, while peer bearings are computed relative to true north — a steady directional
  bias (can be many degrees depending on location), not sensor noise. Now corrected via
  `GeomagneticField` using the phone's own GPS fix.
- **Fixed: the "Offline" toggle didn't actually stop the radar UI.** It only ever checked the OS's
  Bluetooth state, never the app's own mesh-active flag, so toggling Offline left a frozen,
  stale-but-plausible-looking radar on screen indefinitely. All three radar screens now gate on
  both.
- **Known, not yet fixed**: a group member with only a low-accuracy GPS fix (common indoors) can
  show "N hop(s) away" while never getting a radar dot, with no on-screen explanation — the
  accuracy gate that prevents plotting an untrustworthy position is working as designed, but the
  silent failure mode reads exactly like a bug. Documented in Known Limitations; a real fix
  (surface *something* instead of silence) is scoped, not built.

**Relay reliability:**
- **Fixed a real gap found live-testing a 3-phone "passerby relay" scenario**: two phones out of
  range of each other, a third phone meant to carry content between them, didn't reliably work —
  the reconnect cooldown was peer-agnostic (remembered *that* you synced with someone, not *what*
  you picked up since). `ConnectionAttemptTracker` now skips a peer's cooldown once your own
  holdings have changed since you last synced with that specific peer.
- New `RelayResponderTest.kt` — first test coverage for the Bloom-filter catalog-sync round trip
  itself (previously only the filter math was tested in isolation), confirming the reconciliation
  decision logic is correct. Extended `ConnectionAttemptTrackerTest.kt` for the cooldown-skip
  behavior above.
- Added diagnostic logging to `RelayResponder` (item counts, push/skip decisions) so a future
  "message/position didn't arrive" report can be read from logcat directly instead of guessed at.

**Theming:**
- Full light/dark theme toggle, top-right on Home, persisted per install — on top of a brightened
  default dark palette (radar rings/crosshair and muted text were losing contrast in bright/
  outdoor light).
- Fixed a stray white system status/navigation bar on some large-screen devices — the manifest's
  system Activity theme was light behind an all-dark app UI; now dark, and re-synced live when the
  new theme toggle flips.

**Disguise:**
- Decoy launcher icon library expanded from one fixed identity ("Notes") to four (Notes, Files,
  Weather, Calculator) — **re-picked at random every time the Disguise toggle is turned on**, not
  held stable per install, since this is a deliberate user action each time rather than passive
  background state (unlike the notification icon, which stays stable per install for exactly that
  reason).

**Reliability hardening:**
- All three radar screens' refresh loops now catch and log instead of silently dying forever on
  any thrown exception.
- Release builds now strip every `Log.*` call via a new ProGuard rule — debug-only diagnostics
  (including the new RelayResponder logging above) no longer ship in the release APK. Verified
  directly against the built dex, not just assumed from the rule compiling.

**Copy:** README and the GitHub Pages site reframed away from protest-first framing and
competitor comparisons (Bridgefy, Briar, bitchat, Meshtastic) toward leading with the actual use
cases in ranked order — natural disasters and blackouts, stampedes, crowd-control situations —
and describing this app's own design choices directly rather than against anyone else's.

## [0.1.1]–[0.1.3] — scaling/security hardening + disguise completion

A senior-engineer-style review targeting crowd scale (10,000+ concurrent users), plus a full
security/UX/readability pass across the codebase. All changes compile-verified, unit-tested
(88 tests, up from ~70), and detekt-clean; see README's Known Limitations for exactly which parts
are additionally device-tested and which aren't yet.

**Scaling/security (5 changes):**
- `ConnectionAttemptTracker`'s cooldown map is now LRU-bounded and fully thread-safe (was
  unbounded and touched from three different threads without synchronization).
- AES-GCM position frames use a deterministic, never-repeating nonce instead of a random one —
  closes a real birthday-bound collision risk for a key shared by an entire group indefinitely.
- Beacon radio restarts are jittered per-device, so a crowd's shared rotating-ID boundary doesn't
  synchronize every phone's radio restart into one instant.
- New, additive, capability-gated BT5 Coded PHY long-range beacon channel — extends discovery
  range on supporting hardware without touching the proven legacy path. Not device-tested.
- Replaced the old per-peer `PeerDeliveryTracker` (bounded, evictable memory of every peer ever
  met) with a Bloom-filter catalog sync — each connection freshly advertises its own holdings, no
  per-peer memory needed at all. Not device-tested; the highest-stakes change in this batch since
  it changes core SOS delivery.

**Disguise features completed:**
- The decoy/disguised launcher icon (scaffolded in the manifest since an earlier pass, never
  actually reachable from the app) now has a real in-app toggle — a "Disguise app icon" switch on
  the Home screen.
- The "Notes"/"Syncing" foreground-service notification's icon is now randomized per install from
  a small library of generic-looking icons, so it isn't identical across every phone running this
  app.

**Fixes found during the review:** a duplicated/misplaced doc comment that made a clipboard
function's docs read like bitmap-downsampling docs; `NavigateScreen`'s peer list not resolving
nicknames the way `GroupChatScreen`'s feed already did for the same people; a cross-thread
`@Volatile` gap in the new long-range channel's state; `SosEntity`/`EvidenceEntity`/
`EvidenceChunkEntity`/`NicknameEntity` all had `ByteArray`-field `equals`/`hashCode` that compared
by reference, not content, causing spurious Compose recomposition.

**Follow-up pass — mesh offline switch, UI cleanup, WiFi Direct accelerator (99 tests, up from
88; still detekt-clean):**
- Fixed a real on-device bug: tapping "File received — tap to view" in the chat feed did nothing
  — no click handler had ever been wired to it. Now opens the file via a new `FileProvider`
  (scoped to only the app's private evidence folder), with a clear message if the file's gone or
  nothing can open it.
- **New: an actual "go offline" switch.** Until now there was no way to stop the mesh from inside
  the app at all — closing it only lowered the power tier; the service, both radios, and the
  sticky notification ran forever until a force-stop or uninstall. `MeshService.setMeshActive`
  stops (and can cleanly restart) both radios, both sensors, and drops the persistent notification
  on demand. Found and fixed a real bug this exposed: `BeaconRadio.stop()` never reset its
  payload-tracking state, so a stop-then-restart would have silently failed to re-advertise for
  up to a minute.
- Home screen: `Power saver`/`Disguise app icon` (and the new `Offline`) collapsed from two
  full-width descriptive rows into three compact glow-when-active tiles in one row — same
  behavior, less space, full accessibility semantics preserved (`Role.Switch` + explicit
  descriptions) despite the compact one-word labels.
- Chat feed: replaced the card/bubble row style with a flatter, denser, monospace "console" format
  — same information, less visual chrome.
- **New, experimental, opt-in: a WiFi Direct evidence accelerator.** Default OFF. For evidence
  deficits large enough that BLE alone would need multiple reconnects, two phones that already
  share a group key can negotiate (over the already-authenticated BLE link) an ephemeral WiFi
  Direct handoff for just those chunk bytes — SOS/position/presence/normal-size evidence always
  stay BLE-only regardless, and any WFD failure falls back to the existing BLE push silently and
  completely. See README's Known Limitations for the one specific, unverified risk this carries
  (`WifiP2pManager.connect()` may show a system dialog on the other phone) before ever turning
  this on for anything real.

## [0.1.0] — Initial public release

Android BLE mesh app for offline group coordination: a forward-up radar to navigate to your
group, SOS broadcast with hop-count distance, and encrypted evidence-photo sharing — all
phone-to-phone over Bluetooth LE, with no servers, no accounts, and no cellular/Wi-Fi dependency.

Full architecture, protocol design, permissions rationale, and known limitations are documented
in [`README.md`](README.md). Testing approach is in [`TESTING.md`](TESTING.md); the manual
device test plan is in [`test_rubric.md`](test_rubric.md).

This release has been tested on a small number of physical devices (2-3 phones). It has **not**
been validated at the app's actual target scale (~10 people in a dense area) — see
`README.md`'s Known Limitations before relying on it for anything real.
