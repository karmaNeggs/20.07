# Design decisions

Numbered record of designs this project tried, walked back, and why — pulled out of source
comments (where they used to live inline, at length) so the code can state the current invariant
in one line and point here for the history. See [`WHITEPAPER.md`](WHITEPAPER.md) for the pieces of
this that are genuinely non-obvious engineering, and `README.md`'s Known Limitations for what's
still open.

## 1. Advertise-churn: touch the radio only when the payload changes, never on a timer

**Where it applies:** `ble/BeaconRadio.kt`, `ble/BleTuning.kt`.

An early version of the advertise loop touched the radio (stop+restart) on every loop tick
regardless of whether the beacon payload had actually changed — roughly every 700–900ms,
continuously, for as long as the service ran. A live 2-phone test went from "unreliable discovery"
to **total, symmetric discovery failure on both phones** under that churn — consistent with the
BLE stack itself getting into a bad state under rapid stop/start cycling, a known category of
chipset instability (an earlier, different instance of the same class of bug is why the app moved
off a fixed wall-clock rendezvous slot for scanning in the first place — that version's app-level
start/stop of the *scanner* every few seconds hit Android's own undocumented ~5-calls-per-30-second
scan throttle, producing a real, device-dependent "A sees B, B doesn't see A" asymmetry).

**Fix, and the invariant that survived:** the radio (both scan and advertise) is only ever
touched when something real changed — a new rotating-ID window, a different group in the
round-robin, a shifted SOS hop — never on a fixed schedule. Scanning runs continuously once
started, with `ScanSettings.scanMode` as the only duty-cycle lever, left to the OS. Advertising
compares the candidate payload against what's currently transmitting and only restarts on a real
difference — a stable single-group beacon now restarts roughly once per 60-second rotating-ID
window, less churn than the original design before any of this started.

## 2. The CCCD-before-data-write race

**Where it applies:** `ble/GattOperationQueue.kt`, `ble/MeshGattClient.kt`.

Android's `BluetoothGatt` allows exactly one outstanding operation per connection, of *any* kind.
An earlier version fired the CCCD (notification-subscription) descriptor write and then, with no
completion handler at all, immediately started writing data frames on the same connection. The
result was a real, reproducible asymmetry: the peer you connected to (the one you could see) never
reliably received anything from you, while their notifications back to you worked fine, since
those weren't racing anything on your side — "I can see them but can't send; they can't see me but
their message gets through."

**Fix:** `GattOperationQueue` serializes every outbound GATT operation — writes, descriptor writes,
notifies — against a single peer address, so nothing on a connection proceeds until the previous
operation's completion callback has actually fired.

## 3. Hardware `ScanFilter` is unreliable across chipsets

**Where it applies:** `ble/BeaconRadio.kt`'s `restartScan`.

An earlier version used a hardware `ScanFilter` (Service-Data-with-mask) to filter beacons at the
radio level before they ever reached app code — the obvious battery-saving lever. In live 2-phone
testing this turned out to silently fail to fire on some BLE chipsets: the filter controlled
whether the scan callback fired at all, not just the matching logic, and on the affected device it
produced a **deterministic, not intermittent**, "this phone never sees anyone" — a correctness bug
masquerading as a battery optimization.

**Fix:** scan unfiltered; match against the per-slot rotating-ID cache in `onScanResult` instead
(already how the matching logic was structured — only the hardware pre-filter was removed). Slower
to wake the CPU on unrelated nearby Bluetooth traffic, but works identically on every chipset.

## 4. Enforcing the inbound-connection cap broke the mesh

**Where it applies:** `ble/MeshGattServer.kt`'s `maxConcurrentServerConnections`.

Unlike the GATT client role (capped at a fixed number of outbound connections), inbound
connections were originally uncapped — anyone who can see the advertisement can connect, with
nothing stopping a dense crowd from piling more simultaneous inbound links onto one phone than a
chipset's shared central+peripheral GATT pool (commonly ~4–7 total) can handle. An attempt to
*enforce* a cap (actively cancelling connections over the limit) was tried and immediately caused
**total, symmetric mesh failure** in live testing with only 2–3 real phones: the tracking set was
keyed by the `BluetoothDevice` object rather than its address, and `BluetoothDevice` has no
guaranteed stable `equals()` across separate callback deliveries from the stack — the same physical
peer wasn't reliably deduped across this app's routine ~45s reconnects, so the (miscounted) cap was
crossed within a couple of cycles and every connection after that got cancelled.

**Fix, still incomplete:** the tracking set is now address-keyed, which is necessary but — per
Decision 5 below, an analogous client-side bug — not yet confirmed sufficient on its own. Cap
enforcement is currently **disabled** (counts and logs only) until a real dense-crowd session
confirms the address-keyed fix actually holds, per the "let a real crowd find the actual failure
mode" principle this project has otherwise earned the hard way.

## 5. The stuck-`connectGatt` timeout, and the synced-cooldown tuning it constrains

**Where it applies:** `ble/ConnectionAttemptTracker.kt`, `ble/MeshGattClient.kt`.

`connectGatt()` can simply never call back — no success, no failure, nothing — most commonly when
the peer goes out of range mid-attempt or Bluetooth itself toggles during a pending connection.
Without an explicit timeout, that peer's address stayed marked "connecting" forever and was never
retried again. This single bug produced three previously-separate-looking field reports at once:
"breaks after a handful of messages," "breaks if Bluetooth is toggled off and on," and "a far-away
connection doesn't come back" — all the same root cause wearing different symptoms, since every
message exchange opens fresh connection attempts and each one was a fresh chance to hit a peer
stuck this way.

**Fix:** a 15-second timeout keyed off *whether any callback at all* has fired for that address
(not whether it succeeded), so a genuinely slow-but-working connection isn't force-closed, only a
truly stuck one.

**Why this constrains a separate, later tuning idea:** a peer-selection lever
(`syncedReconnectCooldownMs`) was proposed to bias limited connection slots toward not-yet-synced
peers in a dense crowd, by giving an already-fully-synced peer a longer cooldown before reconnecting
(180s was tried). This was reverted: radar position dots are refreshed *only* via a GATT reconnect
(there is no separate lightweight position channel), and both `PositionTracker` and `HopTracker`
expire a peer at 90 seconds of no update — a 180s cooldown would let an in-range, unmoving member's
radar dot silently vanish and reappear, exactly the flicker this project has otherwise worked to
eliminate. The lever is neutralized (equal to the ordinary reconnect cooldown, i.e. no behavior
change) until there's real crowd-density data to tune it against — and any future attempt should be
derived from the *shorter* of the two trackers' staleness windows, not picked independently.

## 6. Open question: a position channel decoupled from GATT reconnects

**Where it applies:** `ble/PositionTracker.kt`, `ble/HopTracker.kt`, `ble/RelayResponder.kt`'s
position-push path.

Per Decision 5, a radar position dot is refreshed *only* by a full GATT reconnect — there is no
lighter-weight channel for "I'm still here, same place" between reconnects. This was scoped down
to the smallest useful step (bounding how many positions one connection relays,
`selectPositionsToRelay`, so a dense crowd's position churn can't crowd out everything else in the
same session) and deliberately did **not** redesign the underlying refresh mechanism — that's a
materially bigger change (a new advertise-payload field or a connectionless broadcast path) with
its own live-testing risk, per this project's "don't redesign the transport in the same pass that
fixes a budget" rule.

**Open question, not yet decided:** could positions instead ride passively in the existing rotating
beacon advertisement (already broadcast continuously per Decision 1, no extra radio touches), so a
peer's dot refreshes on every beacon window it's seen in rather than only on the next reconnect —
and if so, how much beacon payload budget that leaves for the rotating-ID/group data already
riding there, and whether `PositionTracker`/`HopTracker`'s 90-second staleness window still holds
once the update cadence stops being tied to reconnect frequency. Deferred until there's real
crowd-density field data to size the tradeoff against, same standard as Decision 5's cooldown
lever.

## 7. Sender identity (Ed25519): per-group, additive, pin-on-first-sight

**Where it applies:** `crypto/SenderIdentity.kt`, `data/GroupKeyStore.kt`/`PeerKeyEntity`/
`PeerKeyDao`, `ble/MeshFrameCodec.kt`'s per-frame `signature` fields, `ble/RelayResponder.kt`'s
`checkSenderKeyPin`/`signatureCheckPasses`.

Every SOS/evidence-header/nickname/presence frame already carries an HMAC under the group's shared
symmetric key — but every member holds that same key, so an HMAC alone can't tell members apart:
any member (or a phone that stole the key) can forge content that looks like it came from anyone
else in the group. Sender identity adds a second, independent signal on top, without touching the first.

**Per-group, not per-device.** Each device generates a fresh Ed25519 keypair for each group it
joins or creates (`GroupRepository.ensureSenderIdentity`), not one identity reused across every
group. A per-device identity would let anyone who compromises two different groups' traffic link
them as the same physical phone — exactly the kind of cross-group correlation this app's other
anti-tracking choices (rotating BLE ids, no persistent identity beyond a random per-install
`deviceId` that never itself leaves a group's own traffic) already avoid.

**Additive, not a replacement.** The Ed25519 signature covers the exact same canonical bytes
(`sosMacInput`/`evidMacInput`/`nicknameMacInput`/`presenceMacInput`, or the position frame's inner
body) the group HMAC already covers, and travels alongside it on the wire, not instead of it. If
Ed25519 turned out to have some unforeseen weakness, the existing HMAC gate is completely
unaffected — nothing about group-membership authentication changed, this only adds a narrower,
per-sender check on top.

**Pin-on-first-sight, not a CA/trust model.** There's no group "owner" or root of trust in this
app (see Decision on `getShareCode` in `GroupRepository` — any member can invite). Standing up a
real PKI (certificate issuance, revocation, a trusted signer) for a group that typically lives 2-3
days would be a large, high-risk addition for a threat this app's actual size (a dozen-ish people,
days not months) doesn't need. Instead: the first time a sender's presence heartbeat carries a
public key for a (groupId, senderId) pair, that key is trusted and pinned (`PeerKeyEntity`) — like
SSH's classic TOFU (trust-on-first-use). A LATER heartbeat carrying a *different* key for the same
sender is a hard reject, not a re-pin: this app's threat model treats "this sender's key changed"
as indistinguishable from impersonation (a legitimate re-join would get a fresh `senderId` implicit
in a fresh join anyway — see `ensureSenderIdentity`'s doc for why re-scanning the same join code
deliberately does NOT rotate the existing identity). The cost of TOFU — an attacker who's already
present for a sender's very FIRST heartbeat can pin their own forged key uncontested — is accepted
here the same way it's accepted for SSH: it protects against a key changing later, not against
being lied to on the very first handshake, which the group HMAC (a real, if group-wide, shared
secret) already gates independently.

**Enforcement is tolerant until a key is pinned, mandatory after.** Rather than a single global
rollout flag, a missing signature (or a signature with no pinned key yet to check it against) is
tolerated per-sender; once a key IS pinned for that sender, a signature that fails to verify under
it is a hard reject. This falls out naturally from `signatureCheckPasses`' null-handling rather
than needing a separate feature-flag mechanism.

## 8. Relay-dependent presence/position flicker: a flat staleness window had no margin for it

**Where it applies:** `ble/MeshProtocol.kt`'s `HopTracker`, `ble/PositionTracker.kt`, `ble/BeaconRadio.kt`'s long-range channel.

Live 3-phone testing (A-B-C, with A and C never in direct range of each other, B relaying)
surfaced a real, reproducible pattern: direct 1-hop presence/position was reliable, but the
farthest member's relayed reading flickered in and out and never stabilized, instead of settling
at "2 hops away." Root cause, confirmed with real numbers: `HopTracker`'s presence staleness and
`PositionTracker`'s position staleness were both a flat 90 seconds — but a relayed value depends
on the relaying peer's OWN reconnect cooldown (45s) elapsing before it can even attempt to pass
the update on. A 2-hop reading's worst-case propagation delay is therefore ~45s + 45s = 90s — the
*entire* staleness window, with zero margin for connection setup time or ordinary jitter. Anything
relayed even once was living right at the edge of being called stale the moment it arrived.

**Fix:** both trackers now scale their staleness window with the value's own hop depth — one
extra reconnect-cooldown's worth of slack (45s) per hop beyond the first, via
`HopTracker.effectiveStaleMs`/`PositionTracker.effectiveMaxAgeSeconds` (both `internal`, pure,
directly unit-tested). A direct (1-hop) reading is completely unaffected; a 2-hop reading now gets
135s before expiring, a 3-hop reading 180s, and so on — proportional to how many independent
reconnect cycles it actually took to get there.

**Open question, not yet confirmed:** the same test session showed a far higher number of
distinct peer BLE addresses (46, across ~23 minutes) than the 2-3 physical phones involved should
produce even accounting for the OS's own periodic private-address rotation (commonly assumed
~15 minutes elsewhere in this codebase — see `ConnectionAttemptTracker`/`HopTracker`'s own
`lastSource` doc comments). The legacy advertiser does a full stop-then-restart every time its
payload changes (~every 60s, by design — see decision 1) — plausible, unconfirmed, that this
triggers a fresh private address on some chipsets far more often than the assumed 15-minute
baseline, which would undermine any address-keyed tracking (`ConnectionAttemptTracker`'s cooldown
map, `HopTracker`'s `lastSource` route-ownership) across this codebase. A diagnostic log
(`BeaconRadio`: "legacy advertiser restarted") was added, purely additive, to let the next live
capture directly confirm or rule this out before anything touching the legacy advertiser (a path
this project has been burned by changing blind before — decision 1) is considered.

**Also found in the same session, fixed:** the experimental long-range (BT5 Coded PHY) advertiser
was failing every single attempt (`ADVERTISE_FAILED_TOO_MANY_ADVERTISERS`) on real hardware,
retrying every 2-4 seconds for an entire 18-minute session with zero successes and no backoff —
exactly the "repeated failed radio operation churn" category decision 1 already identified as
capable of destabilizing the whole BLE stack, just from a second, experimental source. It now
gives up after 3 consecutive failures for the rest of the session (reset by a stop()/restart
cycle) instead of retrying forever.

**The actual dominant root cause, found in a follow-up capture:** a phone in 2+ groups was
restarting its legacy advertiser roughly every ~3.1 seconds, continuously, for an entire
19-minute session (343 of 526 captured log lines were this one restart event) — not the ~60
seconds this project's whole advertise design is built around. Cause: `roundRobin` advanced on
*every* check-cycle tick (every `advertiseCheckIntervalMs`, 2-4s), not on any slower dwell timer —
so a 2-group phone's payload alternated groupA/groupB/groupA... every single check, and since two
different groups' payloads are never equal, `ensureAdvertising`'s "only restart if the payload
actually changed" guard could never short-circuit: the payload always looked different, because
it was a different group's turn, not because either group's own state had changed. This is
precisely the "repeated radio churn" category decision 1 already proved can push a chipset into a
bad, unstable state — and plausibly explains a wide spread of symptoms reported together in the
same sessions: connection attempts timing out, address churn, and even side-by-side phones
occasionally failing to discover each other at all until a drastic position change or an app
restart (which cleanly reinitializes the radio objects) — all consistent with a BLE stack
periodically destabilized by this, not several unrelated bugs.

**First attempted fix, tried and reverted the same day:** round-robin dwelling a full rotating-id
window (60s) per group before advancing. This cut the restart frequency as intended, but broke
basic same-room, same-group discovery outright for any phone with 2+ groups: whichever group
ISN'T currently "up" gets zero advertising airtime until its 60s turn comes around, so any test
shorter than that sees total silence for that group — confirmed live, the very next test. Reverted
back to advancing every check-cycle tick (the original, known-working-for-basic-discovery
behavior) rather than risk a second unverified guess in the same session.

**Second fix, current:** the dwell is now **adaptive** rather than a single fixed number, which is
what lets it avoid both known-bad horns instead of trading one for the other.
`BeaconRadio.roundRobinDwellMs(blind)` returns 0 (rotate every check — the original, reliable
discovery behavior) while `isBlind()` is true, i.e. while no group has any fresh presence and
finding *anyone* is the only thing that matters; once presence is established there is nothing left
to discover urgently, so it dwells 10s per group, cutting radio restarts ~3x versus every-tick
rotation without any group going dark for longer than one dwell. This directly fixes the starvation
that broke the 60s attempt: a phone that can't hear its other group's members is by definition not
blind only if some group *is* heard — and if nothing is heard at all, it rotates fast again
automatically. Pure and unit-tested (`BeaconRadioDwellTest`), because both neighboring cadences are
known to misbehave on real hardware. **Still needs field verification.**

**Why 10s and not something else:** it must exceed the advertise check interval (2-4s) to reduce
restarts at all, and stay far below the 60s that starved a group. Nothing narrower is justifiable
from the evidence available — the safe range between "~700-900ms causes total symmetric failure"
(decision 1) and "~3s destabilizes multi-group phones" (this decision) is otherwise unmapped.

**A third churn source, self-inflicted, found and fixed in the same pass:** folding live position
updates into `ConnectionAttemptTracker`'s `currentEpoch` signal (the previous fix above) had a
consequence not thought through at the time — that epoch is a single *global* counter, so with
positions changing every few seconds it moves permanently, which meant the "skip the cooldown, I
have something new for this peer" fast path fired on essentially every scan result for every peer.
With only 3 client connection slots that is a reconnect storm, not a fast path, and it plausibly
starved the very transfers it was added to accelerate — consistent with the many "connect attempt
never got a callback" timeouts and inbound-cap warnings in the same captures. Fixed by rate-limiting
the bypass to one per peer per 10s (`mayBypassCooldown`), which keeps it meaningfully faster than
the full 45s cooldown while staying bounded. **Lesson worth keeping: an epoch/dirty-flag mechanism
sized for rare events (a new SOS) does not transfer unchanged to a continuously-changing signal
(live GPS) — the trigger rate, not just the trigger condition, is part of the design.**

**A second, related gap, also found and fixed in the same investigation:** `ConnectionAttemptTracker`
already had a mechanism (`currentEpoch`, from the passerby-relay fix — see its own class doc) to
skip a peer's reconnect cooldown early when there's genuinely new CONTENT to offer them — but
position had no equivalent. A phone that had just picked up a fresher position for someone had no
way to signal "something new for this peer" the way content already could, so a relayed position
sat out the *entire* un-skippable cooldown regardless of how quickly it actually arrived, while
content relayed almost immediately given a free scan/connection opportunity. Fixed by adding
`PositionTracker.positionEpoch` (bumped only when `offer()` accepts a genuinely newer record) and
folding it into the same combined signal `RelayResponder.catalogEpoch` already feeds
`ConnectionAttemptTracker` — extending an already-proven mechanism rather than inventing a new one.

## 9. Root fix for advertise churn: change the payload in place, don't restart the radio

**Where it applies:** `ble/BeaconRadio.kt`'s `ensureAdvertising`/`startAdvertisingSetOrLegacy`.

Decision 8 established the churn (a multi-group phone restarting its advertiser every ~3s, because
every round-robin group switch is a payload change) and then failed twice to fix it by tuning *how
often* to switch: 60s starved the not-currently-advertised group of all airtime and broke
same-room discovery; every-tick is the churn itself. Both attempts accepted a false premise — that
changing the beacon requires restarting the advertiser.

It doesn't. `BluetoothLeAdvertiser.startAdvertisingSet()` (API 26, i.e. this app's `minSdk`, so
always available) returns an `AdvertisingSet` whose `setAdvertisingData()` replaces the payload
**in place, on the running session, with no radio operation at all**. The legacy beacon now runs
as such a set — `setLegacyMode(true)`/`setConnectable(true)`/`setScannable(true)`, so what goes on
air is byte-identical to before (same 31-byte legacy advertisement, same Service Data layout, same
connectability GATT depends on) — and a group switch costs one data write instead of a
stop+start cycle.

`startAdvertising` is kept as a fallback if the advertising-set call fails on some chipset, so the
worst case is exactly the old behavior rather than no beacon. The adaptive round-robin dwell from
decision 8 stays in place but is now largely redundant: with in-place updates, rotating faster no
longer costs radio churn.

**Lesson:** two rounds were spent tuning a tradeoff (restart frequency vs discovery latency) that
turned out not to be a real tradeoff — the platform had an API that removed the cost entirely. Worth
checking for that before tuning a number, especially when both directions of the tuning hurt.

## 10. Positions must be blind-relayable, or the radar can't cross a non-member

**Where it applies:** `ble/OpaquePositionRelay.kt`, `ble/RelayResponder.kt`'s `handlePositionSealed`,
`ble/MeshFrameCodec.kt`'s `Frame.PositionSealed`/`reframePositionForRelay`.

Three captured 3-phone sessions made this unambiguous: **627 positions received, every single one at
hop 0**, while SOS from the same sessions arrived at hop 1 and hop 2 through the same relay. Content
crossed a non-member; positions never did, not once.

Cause: `handlePositionSealed` opened with `repo.getGroupKey(groupId) ?: return`. That line reads as a
privacy guarantee — "a non-member has no key to open this and doesn't relay it, so live GPS never
travels in the clear" — but it conflated two separate things: *reading* a position (which genuinely
requires the key, and must) and *carrying* one (which does not). SOS and evidence never had this
problem because they are stored and forwarded as opaque rows regardless of membership. Positions had
no equivalent, so the test topology this app is built for — a member, a stranger's phone, another
member — could never deliver a radar dot.

**Fix:** a non-member now takes custody of the sealed ciphertext (`OpaquePositionRelay`, in-memory,
TTL- and LRU-bounded, deduped by the ciphertext's own digest so a triangle can't circulate one
position forever) and forwards it verbatim. The ciphertext is never opened, never re-encrypted, never
inspected — the privacy property the original gate was protecting is fully intact.

This required one wire change (`MeshFrameCodec.VERSION` 2 → 3): the position frame's `hop` moved from
inside the sealed body into the cleartext envelope, because a relay that cannot decrypt also cannot
increment a hop it can't see. Receivers now use the envelope hop, since that's the one every relay on
the path actually incremented. Cleartext hop reveals topology depth and nothing about who or where —
the same tradeoff `SosEntity.ttl` has always made.

**Lesson:** "non-members can't read X" and "non-members can't carry X" are different properties, and
one comment asserted both while the code only needed the first.

**Presence, the same fix, and why it was still needed.** Relaying positions alone appeared to fix the
hop count too, since `handlePositionSealed` feeds `considerNeighborReport`. But that only works for a
member who HAS a GPS fix: `positionFramesToPush` emits nothing when `locationTracker.location.value`
is null, so a member indoors, with GPS off, or on a cold fix pushes no position for presence to
piggyback on — and read as absent rather than distant, in exactly the GPS-denied conditions this app
exists for. Presence now carries the same envelope hop and gets the same opaque custody
(`OpaqueFrameRelay`, generalized from the position-only version). A relay re-frames it verbatim,
advancing only the hop, so the group-key MAC a real member verifies survives untouched and a relay
cannot forge presence it couldn't already forge.

## 11. Radar blanking was a latency-tail problem, not a refresh problem

**Where it applies:** `ble/PositionTracker.kt`, `ble/MeshProtocol.kt`'s `HopTracker`,
`ui/RadarView.kt`'s stale-dot fade.

Measured across three ~110-minute live sessions rather than guessed: position refresh median was
13-16s and p90 36-52s — healthy — but **5-8% of gaps exceeded the 90s staleness window**, and every
one of those blanked the dot. That tail is the entire "radar goes blank / jittery" report. The window
had only ~1.8x headroom over p90, so ordinary tail latency (a reconnect that took 150-250s, an
address rotation forcing rediscovery) crossed it routinely.

**Fix:** base window 90s -> 180s in both `PositionTracker` and `HopTracker` (kept equal on purpose —
if they diverge, the group row and the radar disagree about whether anyone is there at all, which was
itself a reported confusion).

**The cost, stated plainly:** a member who has genuinely left now lingers up to 3 minutes. At walking
pace that dot can be ~250m out of date — past the radar's own 200m display ceiling. That is a real
hazard for an app whose dot means "walk this way", so it is explicitly paid for by making age
*visible*: the stale-dot fade now spans the full window (30s -> 180s, down to 0.2 alpha) instead of
stopping at 90s. A nearly-expired dot reads as a ghost rather than as current truth. Showing a dimmed
old dot beats blanking; showing a *confident* old dot would be worse than either.

## 12. The relay loop split horizon fixes

**Where it applies:** `ble/PositionTracker.kt`'s `Record.viaPeer`, `ble/RelayResponder.kt`'s
`selectPositionsToRelay`, `ble/OpaqueFrameRelay.kt`'s `framesToRelay`.

The first session with blind relaying working showed relay succeeding — and immediately showed a
textbook distance-vector loop. On one member, **all 267 positions received came from a single
sender**, arriving at hop 0, 1, 2 *and* 3; 121 of them were hop-3 copies that existed only to be
discarded on arrival. One position was circulating the three-phone triangle at escalating hops.

Ciphertext dedup could not catch it: a *member* relaying a position re-encrypts it (fresh nonce →
fresh ciphertext), so each lap looks like a brand-new frame to `OpaqueFrameRelay`. `PositionTracker`'s
latest-wins rule kept the loop out of the stored state, but not off the air — each member kept
re-advertising its own stored copy on every connection until it expired, and widening that window
from 90s to 180s (decision 11) doubled how long each lap kept circulating.

**Fix:** split horizon, the standard distance-vector answer — never advertise a route back toward the
peer that taught it to you. `PositionTracker.Record` now carries `viaPeer`, and both relay paths
(member re-encode and blind custody) filter against the peer being pushed to. A device's own fix has
no source peer and is always relayable.

**Not implemented, deliberately:** poison reverse (advertising the route back at infinity rather than
withholding it). It costs wire bytes on every push to solve a convergence-speed problem this app
doesn't have — positions expire on their own in 180s, so a withdrawn route self-corrects well within
the window.

**Worth noting for the scaling question:** this loop was invisible at 2 phones and only appeared at 3.
Loop amplification is quadratic-ish in a dense mesh, so this was very likely one of the real
"clogging at scale" hazards, found at the smallest topology that can exhibit it.

## 13. The hop count was frozen, not broken — and three of its neighbours were self-inflicted

Found by a three-way audit after relay itself finally worked, so the remaining symptoms could no
longer be blamed on delivery.

**`HopTracker` could never degrade a reading** (`ble/MeshProtocol.kt`, `updateHop`). Every report
refreshed `lastUpdated`, *including rejected ones*. Since the key is per-group, any traffic at all —
a 3-hop relayed frame from a stranger — kept a previously-recorded "1 hop" permanently fresh: the
staleness window could never fire and the value could never rise. Compounded by `lastSource` being a
BLE address, which rotates every ~10-15 minutes, stranding the ownership needed to revise upward on
an address that no longer exists. **This is why the group row read "1 hop(s) away" through every
build and never reached 2 — the relay was working, the display value was frozen.** Fixed: recency
refreshes only on a report that improves or *confirms* the current value, and ownership transfers to
whoever confirms it. Cascade worth noting: `BeaconRadio.isBlind()` means "all groups stale", so a
permanently-fresh entry also meant the phone that had lost everyone was the one that stopped
rotating its beacon quickly to find them again.

**Presence could not survive the relay path added in decision 10.** `PRESENCE_MAX_SKEW_MS` was a flat
120s checked *before* the blind-relay branch, but each relay hop costs at least one ~45s reconnect
cycle (presence only moves in `framesToPushOnConnect`, never mid-connection), so a 2-hop heartbeat
needed ~90-135s and was then rejected as a replay. Decision 8's exact no-margin arithmetic, at a gate
nobody widened. Fixed with per-hop slack mirroring the model `HopTracker`/`PositionTracker` already
use; hop-0 replay protection is unchanged at 120s.

**The member relay path re-encrypted every position it forwarded**, so a fresh nonce made the same
position look like a brand-new frame to every downstream blind relay's ciphertext dedup — one
position could occupy many slots of a neighbour's store and be re-pushed to everyone. Fixed by
storing the original sealed bytes and forwarding them verbatim, which also restores the sender's
Ed25519 signature that re-encryption had been silently dropping.

**Blind carriage outranked the mesh's own delivery.** The opaque push was the only unbudgeted relay
path, emitted first, and could reach 400 serialised frames inside a 15-20s connection — so a phone
carrying for strangers' groups could never reach its own evidence manifests. Capped per connection,
with a rotating window so a full store still drains rather than starving its tail forever.

**The meta-lesson, and the reason this entry exists:** every one of the last three items was
introduced by a fix in decisions 10-12, and each was individually reasonable. What they had in common
is that they were validated against the 3-phone topology they were written for and not against the
invariants of the mechanisms they touched. Decisions 8 and 12 already recorded that pattern; this is
its third occurrence. Before changing a relay path, check what else reads the value being changed —
skew gates, staleness windows, dedup keys, and budgets are all coupled to it.

**Field-verified 2026-08-05, v0.4.0:** live 3-phone test confirms the group row correctly shows
"2 hop(s) away" for the far phone (previously frozen at "1" through every prior build) and content
relays through the middle phone as expected. Closes the loop on this decision's headline bug.
Decision 8's adaptive round-robin dwell (a separate, narrower claim about advertise-restart
frequency, not observable from hop count or relay behavior alone) remains unconfirmed on hardware.

## 14. The crowd simulator has to reproduce a known-bad number before it can be trusted with a new one

PLAN-v2.md's whole scaling argument rests on numbers nobody had measured above 2-3 phones. Building
a simulator that just *asserts* v2 will work would be worthless — it would agree with whatever
mechanism it was handed. So P0a's own gate isn't "does the simulator run," it's "does the simulator,
run against the CURRENT (v1) mechanism, reproduce the specific bad numbers already measured on real
hardware" (`20.07 mesh diagnostics 10`: 93% empty catalogue syncs, one connection roughly every 50s
at D=3). Only once that's true does a D=400 projection from the same harness mean anything.

This caught a real bug in the harness itself before it shipped: the first draft let both nodes in a
pair independently decide to initiate a connection to each other, since nothing modelled which side
of a link actually scans-and-connects in production. That doubled the effective connection rate for
every pair, and the calibration test measured a ~31s pair-sync cadence against v1's real ~50s —
almost exactly 2x too fast. Fixed by having only the lexicographically-lower node id initiate toward
a given peer (a real GATT link is one connection per pair, not two) — the arbitrary-looking rule is
fine precisely because it's arbitrary: any consistent total order gives every pair exactly one
initiator, which is the actual property being modelled, not the specific ordering.

Deliberately built as `app/src/test`-only Kotlin, not a new Gradle module: it drives the same
Android-free classes (`ConnectionAttemptTracker`, `CatalogFilter`, `OpaqueFrameRelay`) production
code already uses, via `./gradlew test`, and ships in no APK. A discrete-event scheduler
(`SimEventQueue`, priority-queue driven, not a fixed tick) is what makes D=400 over a 90-simulated-
minute run finish in the same few seconds as every other unit test — real wall-clock time is spent
only on the events that actually happen, not on ticking through idle time between them.

Scoped to seven of PLAN-v2.md §6.3's eleven named scenarios (S1, S2, S6, S7, S8, S9, S11).
Deliberately not built yet: S3/S4 (mobility) need a broadcast tier with Trickle suppression to have
anything meaningful to assert — I5 (fail-open) is trivially satisfied by an engine with no
suppression mechanism at all, and a test that always passes for the wrong reason is worse than no
test; S5 needs courier/store-and-forward logic (P4); S10 needs a bulk-media-transfer model (P5).
`SimNetwork.degreeRamp` (the mobility primitive S3/S4 need) is already built and unused, waiting for
P2's broadcast-tier engine to share this same rig against.

## 15. Peer state moves off the BLE MAC — the middle path, not full re-identification

PLAN-v2.md §5.2 calls for keying local peer state on "the per-(device,group) Ed25519 public key."
The literal reading turned out to be the wrong shape for this codebase: the pubkey itself is only
useful for cryptographic verification, and `RelayResponder` already has a simpler stable handle
sitting right next to it — `senderId`, which is `GroupRepository.deviceId` (a random-per-install
UUID, global across a device's every group, already sent in cleartext on presence heartbeats). Using
`senderId` as the local-state key achieves exactly what §5.2 asks for (a device identity that
survives address rotation, never new on the wire) without threading pubkey bytes through call sites
that only ever needed a stable string to compare.

**Where this landed, and where it deliberately didn't:**
- `HopTracker.considerNeighborReport`/`considerDirectHop`'s `sourceId` param, at all three call
  sites (SOS, presence, relayed position) — this was the concrete, load-bearing case: route
  ownership (`updateHop`'s `lastSource` — see decision 13) was getting stranded on a BLE address
  that had since rotated out of existence. Every one of these call sites already runs AFTER the
  frame's group-key MAC (and, for presence, the sender-key pin) has verified — so trusting the
  frame's own `senderId` at that point is no less trustworthy than the routing decision it already
  drives; nothing new is being trusted that wasn't already.
- `MeshGattClient`'s `ConnectionAttemptTracker` cooldowns — the harder case, because at
  `maybeConnect` time (deciding whether to dial a freshly-heard address) there IS no stable identity
  available yet; `senderId` is only learned once a connection to that address has already gone far
  enough to receive an authenticated frame. New `PeerIdentityResolver` (pure/Android-free, mirrors
  `ConnectionAttemptTracker`'s own LRU-bounded style) resolves an address to its learned identity,
  falling back to the address itself when nothing is known yet — the same "low-information case is
  the identity function" shape §5.4 already uses elsewhere (HopTracker, TrickleTimer). Stated
  honestly: the FIRST connection to any freshly-rotated address always costs exactly what v1 always
  cost; the fix is every reconnect *after* that within the same session, which is where the measured
  46-addresses-in-23-minutes churn actually lived.
- Getting `MeshGattClient` right needed one subtlety `HopTracker`'s fix didn't: the resolved key has
  to be captured ONCE, at `attemptStarted`, and reused for every later callback of that same
  connection attempt — never re-resolved mid-flight. `RelayResponder` can call `learn()` mid-
  connection (a presence frame arriving on the very connection whose cooldown is being decided), and
  if a later callback re-resolved the address at that point, `connectionEnded` would try to release
  a tracker key `attemptStarted` never actually acquired — a silent, permanent leak of the original
  key's slot. Fixed with a small `activeTrackerKey` map, populated once per attempt and read (never
  re-resolved) by every subsequent callback for that attempt.
- Deliberately NOT re-keyed: `RelayResponder.sessionBudget`/`catalogItemBudget`/`peerWfdCapable`/
  `MeshGattClient.negotiatedMtu`/`syncedThisSession`. All five are already reset at the start of
  every connection (`resetSessionBudget`, called from both GATT roles) — they are per-connection-
  session state by design, not accumulated peer history, so re-keying them changes nothing
  observable. PLAN-v2.md §1.3 lists them among "peer state keyed on a value that churns," but the
  actual bug in two of them (`sessionBudget`/`catalogItemBudget`) turned out to be independent of
  address rotation entirely: `resetSessionBudget` set them to 0 rather than removing the entry, so
  they accumulated one stale zero-value entry per address ever seen, forever, regardless of key
  type. Fixed with `.remove()` — simpler than an LRU bound, and stricter (bounded by *actual*
  concurrent connections, not a fixed cap) — this was a real, confirmed leak independent of P0b,
  found while already in this code for the re-keying work.

**Not yet hardware-confirmed.** Compiles clean, 270 tests green (up from 247), `assembleRelease`
(R8-minified) green. The actual claim — that a real 3-phone session's exported `DiagnosticsLog` now
shows a stable `distinct=` peer count instead of growing with address churn — needs the debug APK
tested on real hardware first; see PLAN-v2.md Part 7's preamble for the (now explicit) async
verification workflow this and every future phase uses.

