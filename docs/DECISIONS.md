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

## 16. P1's sim gate reveals it can't deliver its own headline claim alone — found before touching the wire

Built P1's forwarding plane in the simulator first (per decision 14's own discipline — nothing
production-risky lands before its sim gate passes), reusing two new pure production classes:
`DedupCache` (in-memory hot-layer dedup, LRU+time bounded, sized per §9.2 item 6's derivation —
3000 entries, since only SOS/evidence-headers/nicknames enter the flood, not presence/position) and
`ForwardingPolicy` (jitter/TTL-clamp/fanout-subset, all gated on currently-OPEN link count — a
deliberately narrower "degree" than §5.4's general table, matching what §5.3's own bullet literally
measures against). New `ForwardingPlaneEngine` in the sim layers immediate-forward-on-open-links
over the SAME connection-establishment mechanism `CatalogSyncEngine` already uses (a separate
engine, not a modification — per that class's own doc), with catalogue-sync demoted to pure
backfill (no longer itself triggering onward flooding), exactly as §5.3 specifies.

**The measured result does not support the plan's own P1 hardware-gate claim** ("3 phones in a
line — a relayed SOS arrives in seconds, not the current ~45s/hop"). Real numbers from the sim,
D=3, fully connected mesh averaged over 30 injected items: forwarding-plane mean convergence
31.7s vs the v1-baseline `CatalogSyncEngine`'s 36.0s — real, but ~12%, not the order-of-magnitude
the plan's prose implies. The literal "3 phones in a line" topology (the exact hardware-gate
scenario) measured 52s for a 2-hop SOS relay — better than v1's ~90s (2×45s) worst case, but not
"seconds."

**Root cause, and why it's structural, not a bug to fix within P1's own scope:** flood-forwarding
can only use a link that is ALREADY OPEN at the moment a packet needs to cross it. Under the
CURRENT connection lifecycle — still connect/sync/disconnect; P3 ("persistent links") is what
retires that — any specific link is open for roughly
`connectionSessionMs / (connectionSessionMs + reconnectCooldownMs)` of the time, ballpark 15-20s of
every ~60-65s cycle, REGARDLESS of density (the 45s cooldown applies even at D=3 with zero slot
contention forcing it). So roughly 70% of the time, a link a packet needs simply isn't there yet,
and the packet has to wait out that link's own reconnect cycle before flood can even attempt it —
at that point P1's improvement over v1 is real (once the link IS open, delivery is near-instant
across it and any other currently-open links) but bounded by how often "already open" actually
holds, not by anything §5.3's own mechanism controls.

**This means P1 and P3 are more coupled than PLAN-v2's phase ordering (P1 before P2 before P3)
implies.** The plan treats P3 as amplification on top of an already-working P1; the sim says P3's
"retire the 45s cooldown regime" is closer to a CO-REQUISITE for P1's own headline claim to hold,
at least at low degree where fanout/TTL-clamp/dedup (P1's actual novel machinery) are all identity-
functions anyway per §5.4 — meaning at D=3 specifically, P1's whole measurable contribution IS
"use a link if it's already open," and that's gated by link *lifetime*, which is P3's problem, not
P1's. At D=400 the picture may differ (more simultaneous links per node makes "already open"
likelier at any given instant even under the same per-link duty cycle) — the D=400 sim run this
pass measured 100% delivery (50/50 injected items) within a 20-minute window, but did not measure
LATENCY distribution at that density the way the D=3 tests did; that's a gap for whoever picks this
up next, not a claim resolved here.

**Deliberately not yet wired into production.** `MeshFrameCodec` (wire format), `RelayResponder`
(the receive/forward path), and the GATT layer are untouched this pass — P1's own class docs
already flag the specific wire-format risk that must be resolved before any of that lands: a
unified packet header needs its OWN explicit hop field (the pattern positions already use, post-
v0.4.0/decision 8), because `ForwardingPolicy.forwardedTtl`'s degree-based clamp can drop TTL by
more than 1 in a single high-degree hop, which would silently corrupt `HopTracker`'s current
`DEFAULT_TTL - ttl + 1` hop-from-TTL derivation for SOS if the two were ever allowed to share a
value. Given the finding above, committing to that wire-format surgery before deciding how P1 and
P3 should actually be sequenced together would risk building the wrong shape twice.

## 17. P3 (persistent links), sim-first — and it's what actually closes P1's gap

User's explicit call after decision 16's finding: build P3 next, then measure P1+P3 TOGETHER before
touching production. New `LinkSelector` (pure, ble/, unit-tested): given a node's currently-held
links' diversity values and a new candidate's, decides which held link (if any) is redundant enough
to evict in favour of the candidate — §5.4/P3's "select for diversity, evict redundant links, not
the oldest." New `PersistentForwardingEngine` in the sim replaces `ForwardingPlaneEngine`'s fixed
`connectionSessionMs` disconnect with a genuinely persistent link (stays open until diversity-
evicted), periodic backfill sync while open (§5.3's literal "~15s exchange on already-open links"),
and reuses P1's `ForwardingPolicy`/`DedupCache` unchanged for the flood itself.

**Result: dramatic, not modest.** Re-running decision 16's exact 3-phone-line topology (the literal
hardware-gate scenario) under the combined engine: **48ms**, down from P1-alone's 52,000ms — a
~1000x improvement, because both links are already open and idle by the time the SOS is injected,
so flood-forward crosses both hops within one jitter window instead of waiting out a single
`reconnectCooldownMs` cycle. This confirms decision 16's diagnosis exactly: P1's own machinery
(TTL clamp, fanout subset, dedup) was never the bottleneck at low degree — link *availability* was,
and P3 is what actually supplies it. **P1 and P3 should be wired into production together**, not
sequentially as PLAN-v2's phase order implies; shipping P1 alone (as decision 16 flagged as an
option) would have shipped a mechanism that can't demonstrate its own headline claim on hardware.

**P3's own sim gate ("diversity beats first-heard on reachability") needed a spatial topology to
mean anything, and needed a second bug fixed before it was trustworthy.** `randomRegular` (P0a's
existing topology, uniform-random neighbour picks) has no spatial locality at all, so "first-heard"
there is already as diverse as anything — nothing for a diversity rule to fix. New
`SimNetwork.spatialRing` places nodes on a ring and connects everyone within a radius, the genuine
"physically-clustered neighbours" shape §9.2 item 2 describes. First pass at the gate then measured
a razor-thin, seed-fragile margin (0.067 vs 0.066 average held-link spread) — traced to a real bug
in the TEST, not the mechanism: the default `minDiversitySeparation` (0.1) was larger than the
entire neighbourhood diameter at the radius used (2×0.05 = 0.1), so `LinkSelector`'s
`candidateAddsCoverage` check was almost never true and diversity mode was silently behaving
identically to first-heard. Scaling the separation to the neighbourhood size (radius/2.5) produced
the real signal: 0.094 vs 0.066, a genuine, non-fragile margin. Worth remembering: a threshold
parameter needs to be sized relative to the SPACE it's being compared against, not picked as a
fixed absolute — the same class of mistake as P0a's own dedup-LRU sizing needing to be derived, not
copied (§9.2 item 6).

**Still sim-only.** 295 tests (up from 288), detekt clean, both variants green. Production wiring
(MeshFrameCodec wire format, MeshGattClient/MeshGattServer connection-lifecycle rewrite for
persistence, BeaconRadio RSSI capture for `LinkSelector`'s real diversity signal, a connection
registry so RelayResponder can flood-forward across every currently-open link rather than just the
one frame arrived on) is next — this is the largest, most invasive change in the project's history,
touching exactly the subsystem responsible for the most historical regressions (decisions 1, 2, 5,
8, 9, A2 all live here). Not started this pass; see NEXT_STEPS/PLAN-v2.md for the concrete checklist.

## 18. SOS wire format grows an explicit hop field, and immediate forward lands — deliberately scoped to SOS only

First production wiring of PLAN-v2 P1 (§5.3), after decisions 16-17 proved the mechanism in the
simulator. Three pieces, landed as separate, individually-verified steps rather than one large edit
— the same discipline as every hardware-adjacent change in this project since decision 1.

**Wire format (MeshFrameCodec.VERSION 3->4, AppDatabase v6->v7).** `SosEntity` gains `hop: Int = 0`,
a cleartext envelope byte (same treatment as `PositionSealed.hop`, added v0.4.0/decision 8),
incremented by exactly +1 on every `RelayEngine.ingestSos` — independent of `ttl`, which a future
degree-aware relay can drop by more than 1 in a single hop. This is the fix for the exact risk
decision 16 flagged before any of this was implemented.
Tracing through the actual ingest code (`RelayEngine.ingestSos` stores `ttl = sos.ttl - 1` and now
also `hop = sos.hop + 1`) caught a real off-by-one before it shipped: the old formula
(`DEFAULT_TTL - ttl + 1`) has a "+1" baked in because a device's OWN hop-from-origin is always one
more than what it RECEIVED on the wire — `RelayResponder.handleSos` must compute
`hopsFromOrigin = frame.sos.hop + 1`, not `frame.sos.hop` directly. Caught by hand-tracing the
value through three hops before writing any test, not by a test catching it after the fact — worth
remembering as a general lesson: an off-by-one in a *replacement* formula for existing behaviour is
exactly the class of bug that a fresh test suite (which encodes the NEW author's assumptions) won't
catch, only comparing against the OLD formula's actual traced behaviour will.
Testing note: `RelayResponderTest`'s own class doc already documents that `Frame.Sos`/`Frame.EvidMeta`
handling can't be exercised end-to-end under Robolectric (`authOk` touches `GroupRepository.
getGroupKey` -> Android Keystore, unavailable there) — so the hop fix is covered at the two testable
layers instead (`MeshFrameCodecTest`'s wire round-trip, `RelayEngineTest`'s ingest-time increment),
not by a full `handleIncoming` integration test. The one-line arithmetic connecting them
(`frame.sos.hop + 1` in `RelayResponder.handleSos`) stays inline, documented, uncovered directly —
not extracted into its own `internal` testable function, since (unlike `checkSenderKeyPin`/
`signatureCheckPasses`/etc.) there's no real decision logic here worth isolating, just one
now-well-commented expression.

**New `ConnectionRegistry`** (`ble/`, tracks live connections from BOTH GATT roles). Neither
`MeshGattClient` nor `MeshGattServer` had ever needed to know about the other's connections before
— this is the first shared view. Keyed consistently with `PeerIdentityResolver`/
`ConnectionAttemptTracker`'s existing "resolve once at connect time, reuse for the whole attempt's
lifecycle" pattern (decision 15) — `MeshGattServer` needed its own small `registeredKey` map to get
the same guarantee `MeshGattClient`'s `activeTrackerKey` already provided, since re-resolving at
disconnect time risks unregistering the wrong key if identity got learned mid-connection.

**Immediate forward, SOS only (`RelayResponder.floodForwardSos`).** On a genuinely new SOS
(`isNew` from `relay.ingestSos`, not a separate check), forward to a fanout subset of every OTHER
currently-open link (split horizon via `ConnectionRegistry.others`) after a degree-scaled jitter,
with the real `ForwardingPolicy` computing both the jitter and a degree-clamped TTL for the
forwarded copy. Deliberately NOT wiring `DedupCache` here: `RelayEngine.ingestSos`'s existing
`isNew` is already DB-backed (via `seenDao`, itself a short-lived hot cache in front of the main
SOS table) and already authoritative — a second in-memory dedup layer on top would be genuinely
redundant for THIS gate, not wrong, just unneeded complexity. `DedupCache` stays a real, tested,
production-ready class, unwired — if DB-query latency on the flood path ever proves to matter on
real hardware (a synchronous SQLite round-trip on every hop, working against the whole point of a
10-220ms jitter budget), it's ready to drop in as a fast-path layer without redesigning anything.
Evidence-header and nickname flood-forward are the same mechanical pattern, deliberately deferred to
keep this change reviewable — not built this pass.

## 19. Persistent links (P3) — the highest-risk change in the project, and three bugs it would have shipped with

Wired PLAN-v2 P3 into production immediately after decision 18, per the user's explicit sequencing
call (decisions 16-17): P1's flood-forward is only as good as how often a link is actually open when
it's needed, and P3 is what makes that "usually," not "one window in three."

**What changed.** `MeshGattClient` no longer disconnects a healthy connection on a fixed idle/max
timer. A connection reaching `heldConnections` (past CCCD-ready) stays open until: (a) `LinkSelector`
decides — only once every `maxConcurrentClientConnections` slot is already held — that a newly-heard
candidate's RSSI is diverse enough over the current held set to evict the most redundant one for
(§5.4/§9.2 item 2's "first-heard clusters on whoever's nearest" fix); (b) it fails on its own; or
(c) it hits `BleTuning.Profile.connectionBackstopMs`, a distant safety net renamed and bumped from
the old `connectionMaxMs` (~20s) to minutes (10 ACTIVE / 20 RELAY) — a backstop against a bug in the
eviction path monopolising a slot forever, not a normal way for a link to end anymore.
`connectionIdleMs` is gone entirely, along with the `lastActivity`/`touch()` machinery that only
ever fed it — dead code once nothing reads it, deleted rather than left in place.
RSSI is a real measurement (`ScanResult.rssi`, threaded through `BeaconRadio.onDeviceSeen` ->
`MeshGattClient.maybeConnect`, both signatures changed), not the simulator's synthetic 1D "position"
stand-in — `LinkSelector`'s own class doc already anticipated this exact swap.

**Three real bugs found while implementing this, before any of them shipped:**
1. **The hard-deadline watchdog would have killed every healthy persistent link after 60s.** It
   existed to catch a connection stuck in SETUP (decision 5's class of bug) and checked
   `attemptTracker.isTracked(trackerKey)` — true from `attemptStarted` until `connectionEnded`,
   which for a NOW-persistent link never fires just because time passed. The watchdog would have
   found every successful connection "still tracked" 60s after it started and force-disconnected it,
   turning P3 into a no-op that silently reintroduced the old ~20s-ish cycle via a different code
   path. Fixed by also requiring `trackerKey !in heldConnections` — the watchdog now only fires for
   a connection that never made it to a genuinely held state, which is what it was always meant to
   catch; how long a HELD connection may then live is `connectionBackstopMs`'s question entirely,
   not this watchdog's.
2. **`setMeshActive(false)` and `onDestroy` would have leaked every held connection.** Both
   previously relied on connections idling themselves out within the old ~20s cap — a documented,
   accepted "known, small, bounded residual." With `connectionBackstopMs` now minutes, that residual
   would have become minutes long, and `onDestroy`'s `serviceScope.cancel()` doesn't itself call
   `gatt.disconnect()`/`close()` on anything a cancelled coroutine was holding. Fixed with a new
   `MeshGattClient.disconnectAll()`, called from both paths — the exact same gap-found-while-
   reviewing-an-adjacent-comment pattern this file's `onDestroy` doc already recorded once for WFD
   teardown; this is its second occurrence in the same method.
3. **`trackerKey in heldConnections` compiled to `containsValue`, not `containsKey`.** A
   `ConcurrentHashMap<String, HeldConnection>` inherits Java's legacy `contains(Object)` (a
   `Hashtable`-era values check), and Kotlin's `in` operator resolves to it over the `Map` extension
   `containsKey` for this specific type — a known Kotlin/Java interop gotcha (KT-18053). Since
   `HeldConnection` can never equal a `String`, both the "already held, just refresh RSSI" fast path
   and the fixed watchdog's exclusion check would have silently evaluated to `false`/`true` -flipped
   from what the code obviously intends, in every case, permanently — not a rare-timing bug like the
   other two, a constant one. Caught by the Kotlin compiler itself (a hard error, not a lint
   warning, in this project's configuration) before a single test ran. Fixed with explicit
   `.containsKey(...)` calls. Worth a general note: this exact gotcha can recur anywhere a
   `ConcurrentHashMap`'s VALUE type could plausibly be mistaken for able to equal its KEY type by a
   reader skimming quickly — always prefer explicit `containsKey`/`containsValue` on
   `ConcurrentHashMap` over `in`, full stop, rather than re-deriving per call site whether it's safe.

**Tuning constants not derived from measurement.** `MeshGattClient.MIN_RSSI_SEPARATION` (15 dB) and
`BleTuning`'s new `connectionBackstopMs` values are reasoned defaults, not fit to real hardware data
— no BLE hardware is available in this environment (same standing constraint as every other
production pass here). Flagged explicitly for tuning once this is on real phones, same as
`MIN_RSSI_SEPARATION`'s own doc comment already says.

**Not yet hardware-confirmed.** Compiles clean, 304 tests green, detekt clean, `assembleRelease`
(R8-minified) green. This is the single highest-risk change in the project's history by its own
class doc's admission — touching the subsystem behind decisions 1, 2, 5, 8, 9, A2 — and needs a
sustained multi-hour real 3-phone session (P3's own stated hardware gate) before any of the three
bug fixes above, or the mechanism as a whole, should be trusted the way the rest of this codebase's
hardware-verified passes are.

## 20. First live P1+P3 hardware test finds two real gaps — both "only received content moves"

3-phone live test, 2026-08-05, the day after decisions 18-19 shipped. Symptom reported: a message
sent between two ALREADY-CONNECTED phones sat undelivered with a real delay; the radar didn't
update; both resolved themselves the moment a third phone joined the group — "like a relay
happened." Diagnostics logs from two of the three phones (exported via the existing debug-only
`DiagnosticsLog`, the async hardware-verification workflow PLAN-v2.md Part 7 documents) confirmed
two clean ~8.5min and ~4.3min gaps with zero connection activity on both phones, then a burst the
moment the third phone's connections came up.

**Root cause, both gaps: everything P1/P3 built only reacts to content already on the wire.**
`RelayResponder.floodForwardSos` (decision 18) fires from `handleSos` — i.e. only when a frame
*arrives*. `framesToPushOnConnect` fires once, at the moment a connection first opens. Neither path
covers content this device *originates itself*, mid-session, on a link that's already open and now
(decision 19) stays open for minutes. `MeshService.sendSos` just called `RelayEngine.createSos` and
returned — nothing told any open link a new message existed. It only ever left the device when a
NEW connection happened to form and hit the one-shot `framesToPushOnConnect` path, which is exactly
what the third phone joining did (fresh connections → fresh one-shot pushes → the stuck messages
rode out on those). Position/presence had the identical shape but a worse consequence: with no
event that fires "position changed" the way SOS creation does, a peer's radar dot was going stale
for the ENTIRE life of a persistent link, not just until the next message — the app's headline
feature effectively broken for as long as a link stayed healthy, the opposite of what P3 intended.

**Fixes, both additive, no behaviour removed:**
- `RelayResponder.floodForwardLocalSos(sos)` — the SAME flood-forward `handleSos` already does for
  received frames, called from `MeshService.sendSos` right after `RelayEngine.createSos` succeeds.
  `hopsFromOrigin = sos.hop` (already 0 for a freshly authored entity) and no `excludeKey` (nothing
  to exclude — this device IS the origin). `floodForwardSos` was refactored to take the `SosEntity`
  directly instead of a received `Frame.Sos`, so both call sites share one implementation.
- New `RelayResponder.refreshFramesToPush(toPeer)` / `presenceAndPositionFrames` (extracted from
  `framesToPushOnConnect`, which now just calls it): presence + own/relayed/blind-carried position,
  the live, time-sensitive subset — deliberately NOT the catalog filter, WFD cap, or evidence
  manifests, which either don't need this cadence or are already event-driven via P1. Both
  `MeshGattClient` and `MeshGattServer` now run a `periodicRefresh` loop per held connection
  (`BleTuning.Profile.presenceRefreshIntervalMs`, new field — 15s ACTIVE / 30s RELAY, matching
  §5.3's own "~15s exchange on already-open links"), looping for as long as the link stays held and
  stopping itself the moment it's evicted or disconnects — checked fresh at the top of every
  iteration, not assumed from any other loop's lifetime.
- `MeshGattServer` gained a `currentTier: () -> MeshService.PowerTier` constructor param (didn't
  have one before — server-role connection handling never needed tier-driven timing until now).

**What this does NOT fix, and is worth stating plainly:** evidence-header and nickname content
still only move via the one-shot connect-time push — decision 18 already deferred flood-forwarding
them, and this pass didn't revisit that. They have the SAME "stuck until a new connection forms"
exposure SOS had before this fix, now that links persist. Flagged, not resolved — same shape of gap,
smaller blast radius (evidence/nicknames aren't the app's time-critical path the way SOS/radar are).

**Hardware-confirmed as of decision 21's second test round** — both (1) and (2) below hold under a
live 3-phone session: (1) a message sent on an already-open link arrives without waiting for a new
connection, (2) a radar dot keeps refreshing for the full lifetime of a persistent link, not just at
connection start.

## 21. Second live test confirms decision 20's fix; finds a duplicate-`onServicesDiscovered` leak

3-phone live test, 2026-08-05, re-testing 0.6.1-dev (decision 20's fix). `DiagnosticsLog` exports
from all three phones reviewed. User reported one new, unrelated symptom this round — "radar error,
said waiting for GPS fix" on one phone only — traced to that phone's own location-accuracy setting
(the "Waiting for GPS fix…" text in `HomeScreen.kt`/`NavigateScreen.kt` is the app's normal
no-location-yet state, not a mesh fault); the user fixed it locally and confirmed the radar then
worked on all three. No code change for this one.

Confirming decision 20's fix from the logs: `[recv] position` events for a session's whole duration
now cluster in a clean, unbroken ~15s cadence (matching `presenceRefreshIntervalMs`) from first
connection to last log line — the exact opposite of the single-then-stale pattern decision 20 fixed.
A chat message (`[recv] NEW sos ... member=true`) was delivered with the nearest `[conn] synced ok`
tens of seconds earlier and no connection event anywhere near the delivery itself — confirms
delivery is happening on an already-open link, not gated behind a fresh connection forming.

**New finding, unrelated to decision 20's fix, found by auditing the logs for anything else
unusual:** the same peer address logged `[conn] synced ok` two or three times within about a
second — far too fast to be a real disconnect-then-reconnect (a full BLE reconnect cycle, scan +
connect + MTU + service discovery + CCCD, does not complete in under a second). Root cause: several
Android BLE stacks are known to re-fire `onMtuChanged` — which `MeshGattClient`'s callback
unconditionally answers by calling `gatt.discoverServices()` — without an intervening disconnect,
so `onServicesDiscovered` itself fires two or three times for one physical link. Before decision 19
(persistent links) this only meant `pushOnConnect` ran redundantly within the same few seconds a
short-lived connection was open anyway — wasteful but self-limiting. Since decision 19, the exact
same duplicate firing spawns an EXTRA, fully independent `periodicRefresh` loop (decision 20) per
duplicate — each one living and re-pushing frames on its own 15-30s cycle for the connection's
entire persistent lifetime (now minutes), not just the setup window. Purely a radio/battery-waste
bug, not a correctness one: `RelayEngine`'s existing `seenDao`-based dedup already absorbs whatever
duplicate frames the extra loops produce, so nothing here explains any user-visible symptom from
either test round — found by auditing, not by a reported symptom.

**Fix, both GATT roles, matching the existing idiom each file already used for exactly this kind of
guard (`syncedThisSession`, `subscribedDevices`):**
- `MeshGattClient`: new `handledGatts: ConcurrentHashMap.newKeySet<BluetoothGatt>()`. `add()` is
  atomic — `onServicesDiscovered` checks it first and returns immediately on a repeat callback for a
  gatt already handled; removed on disconnect (`BluetoothGatt` instances aren't reused across
  reconnects, so no unbounded growth to guard against).
- `MeshGattServer`: `onDescriptorWriteRequest` already called `subscribedDevices.add(device)`
  unconditionally with the return value unused — now gates the same setup (registration,
  `pushOnConnect`, `periodicRefresh`) on that return value, covering the symmetric case where a
  peer's own duplicate `onServicesDiscovered` sends a second CCCD write for a link the server
  already has open. The GATT-level `sendResponse` ack still happens unconditionally either way —
  Android expects one regardless of what the app does with a repeat write.

304 tests, detekt clean, both variants green. This specific fix is new this pass and NOT yet
hardware-confirmed itself (nothing currently shows it changing user-visible behavior — it removes
waste, not a symptom); the message/radar fixes it sits on top of ARE hardware-confirmed as of this
same round. Per standing instruction, this stays uncommitted until the user re-tests or explicitly
asks to commit.

## 22. Third live test: two hop-count questions explained (not bugs), one real Bluetooth-recovery bug fixed

Third round of the same overall 3-phone live session (2026-08-05), this time spread across real
distance (balcony / hall / far corridor). Reported: messages instant, radar tracking worked at 1-2
hops as expected — genuinely positive result, better than any prior round. Three specific questions
raised, answered by reading `HopTracker`/`PositionTracker` (`MeshProtocol.kt`) directly rather than
from logs:

**Asymmetric hop count (phone 1 sees phone 3 at 2 hops, phone 3 sees phone 1 at 1 hop) — normal, not
a bug.** `HopTracker` is per-device and per-observer by construction: each phone's `table`/
`lastSource`/`lastUpdated` maps track "the best path *this device* has personally heard reported,"
never a value shared or reconciled across phones. `updateHop`'s acceptance rule (a report that
improves the tracked value is always accepted; a report that doesn't is only accepted from the
route's own current owner) means two phones with different actual open links to each other, or
different report timing, can legitimately and simultaneously disagree — nothing here assumes or
requires symmetry.

**"4 hops" observed on a 3-phone setup, radar blank at the time, traced by the user to that phone's
Bluetooth being off — plausible, and here's the mechanism.** Steady-state, 3 physical nodes can never
legitimately need more than 2 hops (direct, or via the third). A `4` requires the FIRST report ever
recorded for that (groupId, target) key to itself be an inflated, bounced value — position frames are
deliberately not deduplicated (unlike SOS/messages), so during the chaotic multi-link connection-
churn phase a relayed copy of a peer's own position can plausibly reach a device before the direct
copy does. A later, better report immediately corrects it (`candidate < current` always wins,
regardless of source) — this is normally invisible and self-healing within seconds. The part worth
flagging: `effectiveStaleMs`/`effectiveMaxAgeSeconds` (`HopTracker`/`PositionTracker` companions) both
GROW staleness grace by 45s per hop beyond the first — a deliberate, previously-justified design
(protects legitimate multi-hop propagation delay from flickering; see `HopTracker.staleMs`'s own
doc comment for the live-tested reasoning behind it). Side effect: a bad `4`
reading, once recorded, is trusted for `180s + 3*45s` ≈ 5.25 minutes — LONGER grace than a correct
`1` reading gets (180s flat) — so if nothing ever corrects it (exactly what happened here: that
peer's Bluetooth went off before a better report could arrive), the wrong value outlives a correct
one would. Self-correcting under normal traffic, cosmetic, NOT fixed this pass — logged as backlog,
not release-blocking, since the app's actual group size (3-8, PLAN-v2 §5.5) keeps the worst case
small and rare.

**Real bug, fixed: OS-level Bluetooth off→on did not self-heal the mesh.** Live-confirmed this same
round: toggling Bluetooth off then back on left presence/radar/messaging dead until the user found,
by trial, that manually tapping the app's own offline/online toggle fixed it. Root cause:
`MeshService`'s `bluetoothStateReceiver` (registered on `ACTION_STATE_CHANGED`, added pre-dating this
decisions log as a radar-safety gate) only ever updated `_bluetoothEnabled`, the flow the three radar
screens read to hide stale-looking dots — it never called into `beaconRadio`/`gattServer`/
`gattClient` at all. `setMeshActive(true)`/`(false)` already had the correct sequence
(`beaconRadio.stop()`+`gattClient.disconnectAll()`+`gattServer.stop()` / the mirrored start calls) —
it simply was never invoked by the real adapter event, only by the user's manual toggle. Since
`BeaconRadio.startAdvertising()`/`startScanning()` already re-fetch `bluetoothLeAdvertiser`/
`bluetoothLeScanner` fresh from the adapter on every call (confirmed by reading the source — not
cached once at construction), a plain stop-then-start on the OS event is sufficient; no deeper
re-initialization is needed.

**Fix:** `setMeshActive`'s radio-handling extracted into `startRadios()`/`stopRadios()` (no behavior
change to `setMeshActive` itself — same calls, just named and shared). `bluetoothStateReceiver` now
calls `stopRadios()`+`startRadios()` on a genuine OFF→ON transition, and `stopRadios()` alone on
ON→OFF, so the app's own connection-side state (`heldConnections`, `subscribedDevices`,
`ConnectionRegistry` entries — all still "believing" links are open when the adapter died under
them) gets cleaned up promptly rather than sitting stale until the next manual toggle. Both branches
are gated on `_meshActive.value` — if the user turned the mesh off deliberately via the app's own
control, a Bluetooth adapter event must not fight that by restarting anything. New `DiagnosticsLog`
events (`"bluetooth back on - restarting radios"` / `"bluetooth off - stopping radios"`) — this
round's own diagnosis needed the user's manual recollection ("turns out the other phone's bluetooth
was off") because nothing in the log said so; future rounds won't need that.

304 tests, detekt clean, both variants green. **Hardware-confirmed 2026-08-05** via a quick 2-phone
check: toggling Bluetooth off then back on recovered the mesh on its own, no manual offline/online
toggle needed. Committed alongside decisions 20-21's work as `de1c97e`.

## 23. P2 Tier-1 sim: TrickleTimer's own default sits on the wrong side of S3's own endpoint

First P2 (broadcast tier, `PLAN-v2.md` §5.1 Tier B / Part 7) sim work, 2026-08-05. Deliberately
narrow first pass: rather than the full presence/position/SOS/hop-gradient payload model, targets
the single riskiest claim Part 7 names as a P2 ACCEPTANCE CRITERION (not a later refinement) —
§5.5's fail-open rule, I5, mechanised in `Invariants.checkFailOpen` since P0a but never exercised
against real Trickle behaviour until now. New `BroadcastTierEngine.kt`/`BroadcastTierNode` wrap the
REAL production `TrickleTimer` (same "no reimplementation" discipline as every other sim engine in
this package), driven through S3's own scripted degree profile ("Walking out": D 300 → 2 over 60s,
`PLAN-v2.md` §6.3) with production's real `BeaconRadio` tuning (`minIntervalMs`=5s,
`maxIntervalMs`=60s, `redundancyConstant`=2 — none of these invented for the sim). New `P2GateTest.kt`.

**Bug caught in the sim harness itself before any finding could mean anything: naive sighting
injection.** `TrickleTimer.onSighting()` expects one call per actual neighbour broadcast heard,
accumulated over however long the window stays open — it is not itself time-aware. The engine's
first draft called it `degree` times on every 1s poll tick, which inflates the count by the
poll-frequency/window-length ratio, not just degree: at a 60s backed-off window that is up to 60
injections of `degree` each in ONE window, so even a genuinely isolated node (D=0) registered
~18,000 sightings and stayed "suppressed" forever regardless of real degree — a bug in the harness,
not in `TrickleTimer`. Fixed by injecting sightings only once per `sightingIntervalMs` (defaults to
`TrickleTimer`'s own `minIntervalMs`, the fastest any real neighbour could plausibly re-announce)
and capping the count per injection (`sightingCap`, default 5) rather than raw degree — a crowd
should read as "clearly over the redundancy constant," not as an unbounded number. Bundled into new
`BroadcastTierTuning` data class (detekt `LongParameterList`, same fix shape as `SimNodeConfig`).

**Real finding #1 (positive): fail-open genuinely works once degree drops meaningfully below the
redundancy constant.** At D=0 after the walk-out ramp, the node resumes transmitting well within
`maxIntervalMs`, matching P2's own "audibly loud again within one interval of leaving" language in
spirit. `isSuppressed()`'s level-style read (not `shouldTransmit()`'s one-shot pulse) is confirmed
as the correct usage for a continuously-running advertising set, matching `BeaconRadio`'s existing
long-range-channel pattern.

**Real finding #2 (refines the "one interval" claim): worst case is closer to two intervals, not
one.** Measured directly: first post-isolation touch landed ~75s after the walk-out ramp completed
against a 60s `maxIntervalMs` — not "one interval," because `TrickleTimer`'s window boundary is not
realigned when conditions change. Whichever window happens to be "in flight" when isolation begins
may have started up to `maxIntervalMs` BEFORE that moment, then takes up to another `maxIntervalMs`
to close and re-evaluate. A real, mechanised correction to file alongside `PLAN-v2.md` P2's own
acceptance text, not a hypothetical — worth tightening that prose to "within two intervals" before
this becomes a hardware-test expectation someone measures against the wrong number.

**Real finding #3 (the one that actually matters before any production wiring): S3's own literal
scenario endpoint sits exactly on `TrickleTimer`'s own default boundary, and does NOT fail open.**
`PLAN-v2.md` §6.3's S3 row says "D 300 → 2," literally — not "→ 0." `TrickleTimer`'s acceptance rule
is `sightingsThisWindow < redundancyConstant` (default 2, from `BeaconRadio`'s real tuning) —
STRICTLY fewer than the constant. A node still hearing exactly 2 same-purpose neighbours reads as
"still redundant" and never fails open, no matter how long it waits — directly contradicting what
"walked out"/isolated is supposed to mean at the tail of this exact scenario. Confirmed by running
the identical scenario at D=2 (unmodified from the plan's own numbers) vs. D=0: D=0 fails open
cleanly (finding #1 above); D=2 never does, mechanised and reproducible, not a one-off timing
artifact. This is a genuine open decision for whenever P2's production wiring starts, not resolved
by this sim pass: (a) lower `redundancyConstant` for presence/position/SOS content specifically so
2 genuinely means "clearly redundant, not borderline," (b) treat real isolation as needing to reach
some degree below 2 before the parameter judges it as such (accepting that S3's own chosen number
was optimistic), or (c) reconsider what "sighting" should even be scoped to before wiring this
in — own-group-only witnessing (matching `BeaconRadio`'s current class doc, "a neighbor's own
long-range beacon for the SAME group") vs. counting any locally-heard broadcast including other
groups' relayed content (which would make sighting counts track swarm density, not group size, and
changes what "D" in S2/S3/S4 even means for this specific mechanism) — flagged, not decided here.

307 tests (up from 304, +3 for `P2GateTest`), detekt clean, both variants green. Sim-only — no
production code touched this pass; `TrickleTimer.kt` itself is unchanged. Tier 2/Tier 3 gates for
P2 (scan-storm measurement, 3-phone hardware pass) not started; per `PLAN-v2.md`'s own phase-gating
discipline, P2 production wiring should not begin until finding #3 above has an actual decision,
and P1+P3 has had the sustained multi-hour session it's still waiting on (see decision 22's update).

## 24. Decision 23 resolved as option (c) — and it dissolves finding #3 rather than fixing it

2026-08-06. User's explicit call on decision 23's three-way open question: **sightings stay scoped
to own-group broadcasts only.** A stranger's beacon — even thousands of them — never counts, no
matter how it's produced. Framed by the user directly: phones running the app but sharing no group
with us are pure relay capacity, not "neighbours" for density purposes, and they keep relaying
(the blind-relay pillar, unaffected) even with the screen off or the app backgrounded. That is
option (c) from decision 23's three, not (a) or (b).

**Production already does this — confirmed, not changed.** `BeaconRadio.longRangeScanCallback`
only calls `longRangeTrickle.onSighting()` after `matchTable[beacon.rotatingGroupId.toHex()]`
resolves, i.e. only for a beacon from a group this device actually holds the key for (see that
callback's own doc: "a neighbor's own long-range beacon for the same group"). `TrickleTimer.kt`
itself needed no change either — its `onSighting()` doc already specified this scope. The gap was
entirely in the **P2 Tier-1 sim**, which — via `P2GateTest`'s `degreeAt` — had been feeding
`BroadcastTierEngine` raw swarm density (S3's literal "D 300 → 2") as the sighting count. That
conflated two different numbers PLAN-v2.md §6.3 never meant as one: swarm size (drives relay/
connection-slot pressure elsewhere in the design, irrelevant to Trickle) and own-group degree,
which — per §9.1's 3–8 person groups — is bounded at 0–7 other members, full stop, independent of
how many strangers surround you.

**Reworked `P2GateTest.kt` to model own-group degree, decoupled from swarm size**, and it changes
the read on decision 23's own headline finding, not just the numbers feeding it:

- **Finding #3 ("S3's D=2 endpoint never fails open") was never actually a bug — it was a labelling
  problem.** Under swarm-density semantics, "2 strangers left after you walked out of 300" reads as
  "basically alone," so staying suppressed there looked wrong. Under the corrected own-group-degree
  semantics, D=2 means **two of your actual group-mates are still in direct mutual range, still
  broadcasting the same group-presence signal** — an ordinary, stable, well-covered state for a
  3–8 person group, not an edge of isolation. Staying suppressed there is `TrickleTimer`'s
  redundancy rule doing exactly its job: your own broadcast genuinely would be redundant. New test
  `exactly 2 group-mates in range is a covered state, not isolation` asserts this directly and
  passes. **None of decision 23's three proposed fixes (lower the constant / redefine the endpoint
  / rescope sightings) turned out to be needed as tuning** — (c) resolves it by correcting what
  "isolated" means, not by moving a number.
- A direct, mechanised proof that swarm size is now provably irrelevant to this mechanism: new test
  `swarm size is irrelevant to own-group Trickle behaviour` runs the identical own-group-degree
  ramp under two swarm-size labels (3 and 3000) that are never read by `degreeAt`, and asserts the
  resulting radio-touch traces are byte-identical.
- Genuine isolation (own-group degree → 0) still cleanly fails open — `genuine isolation (own-group
  degree to 0)- I5 fail-open holds` passes unchanged in spirit from decision 23's own degree-well-
  under-constant case, just relabelled from "walked out of the crowd" to "your own group-mates left
  your range," which is the scenario that actually drives this timer.

**New finding, not present in decision 23, surfaced only because this session tested an explicit
held low-nonzero own-group degree for the first time: `BroadcastTierEngine`'s sighting-cadence model
can pin a node in permanent suppression at degree as low as 1, not just at the boundary constant.**
The engine injects `degreeAt(...)` sightings once every `sightingIntervalMs` (default 5 s, "the
fastest any real neighbour could plausibly re-announce" — decision 23's own fix, unchanged here).
That implicitly assumes a neighbour transmits at that fastest possible rate *forever*, regardless of
whether the neighbour's own `TrickleTimer` has itself backed off. Once this node's own window has
backed off to `maxIntervalMs` (60 s), a single such neighbour contributes 60 s / 5 s = 12 sightings
per window — six times `redundancyConstant` — so the node reads "still redundant" indefinitely no
matter how long degree stays at exactly 1. Test `last buddy remaining (degree 1)- honest negative
finding, sighting-cadence model can pin suppression` documents this the same way decision 23 itself
documented its own honest negative finding: asserts the violation currently happens, with a comment
explaining why, rather than silently tuning a scenario until the test goes green. **Not fixed this
session — deliberately, matching this file's own "narrow first pass" framing for P2 Tier-1.** A real
fix needs the modelled sighting rate to depend on the *neighbour's* own suppression state too (a
suppressed neighbour's advertising set is OFF per `TrickleTimer.isSuppressed`'s own doc, so it
should contribute ~0 sightings while suppressed, not a constant stream) — i.e. coupling multiple
real `TrickleTimer` instances together instead of driving one node off an exogenous degree signal.
That is squarely inside the still-not-started "full presence/position/SOS/hop-gradient payload
model" (`PLAN-v2.md` P2 status), not a quick patch here. **Open question for whoever picks up P2's
fuller sim work: does this cadence assumption also affect S2/S3/S4's still-valid findings at
degree ≥ redundancyConstant, or only the newly-tested degree-strictly-below-constant case?** Not
answered here — those tests never held a nonzero-but-below-constant degree before this session.

309 tests (up from 307, net +2 — `P2GateTest` grew from 3 to 5 tests), detekt clean, both variants
green. Sim-only, same as decision 23 — no production code touched (confirmed already correct,
per above). `BroadcastTierEngine.kt`'s doc comments updated to state the own-group-only contract
explicitly; its actual injection logic is unchanged (that's exactly what the new finding above is
about). Decision 23's boundary-bug framing is superseded by this entry, not merely amended — read
this one first if the two disagree.

## 25. The root cause behind decision 24's open finding: `TrickleTimer` counted packets, not neighbours

2026-08-06, same session as decision 24. User's instruction: dig into the open finding rather than
leave it flagged. That digging found the bug was never really about sim cadence modelling — it's a
real mismatch in `TrickleTimer` itself, live in production code, not a sim-only artefact.

**The actual mechanism.** `TrickleTimer.onSighting()` took no argument and simply incremented a
counter (`sightingsThisWindow++`), compared against `redundancyConstant` at window close. RFC 6206's
own peers self-limit to at most one transmission per interval, so counting raw receptions is
equivalent to counting distinct neighbours *in that protocol*. Ours don't: `BeaconRadio`'s
long-range channel drives its advertising set through `isSuppressed()`'s **level-style** read (see
that method's own doc) — once "not suppressed," the set stays continuously ON for the whole period,
re-transmitting at `AdvertisingSetParameters.INTERVAL_HIGH` (1000 ms) the entire time, not once.
`longRangeScanCallback.onScanResult` — with no `ScanSettings` report-delay batching or match-type
filtering configured — fires once per received advertisement, so a single continuously-present
neighbour genuinely generates **dozens of `onSighting()` calls per minute**, not one. Confirmed by
grepping the actual `ScanSettings`/`AdvertisingSetParameters` configuration, not assumed.

**Consequence, unnoticed until decision 24's own test exercised it:** at `redundancyConstant = 2`,
a SINGLE actively-broadcasting neighbour trips the "already covered" threshold within the first
couple of seconds and then holds it — the timer stays suppressed for as long as that one neighbour
keeps transmitting, regardless of whether 1 or 100 neighbours are actually present. `TrickleTimer`'s
whole purpose — "back off only when genuinely redundant, fail open otherwise" — was quietly not
being measured at all; the real gate was closer to "has anyone at all been heard from recently,"
which contradicts §5.4/§5.5's entire density-driven design and, worse, would have undermined P2's
central acceptance claim (I5 fail-open) the moment production wiring actually started depending on
sustained multi-neighbour presence rather than a single connection.

**The comments in `TrickleTimerTest.kt` already said what was intended** — `t.onSighting();
t.onSighting() // 2 neighbors already covering this` — the implementation just never enforced that
each call represented a *distinct* neighbour. This was a latent bug against the class's own stated
intent from the start, not a regression.

**Fix, in production code, not the sim:** `TrickleTimer.onSighting()` now takes a `sourceId: Any`
and dedupes within a window via a `MutableSet<Any>`, cleared on each window close (same place
`sightingsThisWindow` was reset). `redundancyConstant` now genuinely compares against *distinct
sources heard this window*, matching the class's own doc and its test comments' original intent.
`BeaconRadio`'s call site now passes `result.device.address`. This reopens the exact identity
question decision 15/P0b already answered for *long-lived* peer state — deliberately not reused
here: a Trickle window is tens of seconds to low minutes, BLE address rotation is ~15 minutes, so
the raw scanned address is a perfectly adequate, much simpler key for a dedup scope this short-lived.
Using the stable per-group Ed25519 identity here would need a signed field inside the beacon payload
that the current group-presence beacon doesn't carry (it only carries a *group*-level rotating
handle, not a per-sender one) — out of scope for this fix, and unnecessary for what it's fixing.

**This fully resolves decision 24's open question**, and does so more simply than the coupled
multi-node engine that question's own text anticipated needing: once sightings are deduped by
source, `BroadcastTierEngine`'s existing single-node, exogenous-`degreeAt` model becomes correct on
its own — injecting the same `degree` synthetic source ids on every `sightingIntervalMs` tick no
longer inflates the count, because re-adding an id already in the set is a no-op. No multi-node
coupling was needed after all; the bug was never really about modelling neighbour-side suppression
state, it was about not deduplicating sender identity at all. `BroadcastTierEngine.kt` updated to
inject distinct `"neighbor-$i"` ids; `P2GateTest`'s `last buddy remaining (degree 1)` now asserts
fail-open success instead of documenting a gap. `TrickleTimerTest.kt` updated to pass source ids on
every call (matching what its comments always described) plus one new test proving the dedup
directly: 50 calls with the same source id count as 1.

**Consequence for `PLAN-v2.md`'s P2 gating:** decision 23's original blocker (an unresolved 3-way
question) and decision 24's follow-on blocker (an unresolved sim-fidelity question) are BOTH now
resolved. The only remaining gate on P2 production wiring is the sustained multi-hour 3-phone
session P1+P3 are still waiting on (§6.4/decision 22) — not a new P2-specific one.

310 tests (up from 309, +1 — the new dedup-proof test in `TrickleTimerTest.kt`), detekt clean, both
variants green. **Production code touched this time**, unlike decisions 23/24: `TrickleTimer.kt`
(`onSighting` signature + internal storage) and `BeaconRadio.kt` (one call site). The long-range
channel this affects is currently circuit-broken on the only hardware tested so far (100% advertise
failure, self-disables after 3 attempts — see `NEXT_STEPS.md`'s open decisions), so this fix has not
yet been, and cannot yet be, hardware-confirmed on this project's own test devices; it is
compile/test-verified only. Recorded here rather than silently folded into decision 24 because it
changes shipped production code, which decisions 23 and 24 explicitly did not.

## 26. P2 production wiring, first slice: the broadcast tier is the long-range channel, generalized

2026-08-06, same day as decisions 23-25, after the user's explicit go-ahead to start building P2
production wiring (corrected sequencing: the field session comes after P2 is built, not before —
see `PLAN-v2.md`'s RESUME HERE block). First production slice, deliberately scoped narrow, same
discipline as P1's own "SOS only first" first slice.

**Central design finding: the "long-range supplementary channel" (BT5 Coded PHY, `BeaconRadio.kt`)
already WAS a prototype of Tier B.** It already had extended advertising, in-place payload update,
non-connectable mode, and Trickle-governed suppression — everything §5.1's Tier B needs except two
things: it required Coded PHY specifically (narrower hardware support than Tier B needs) and it
carried only the bare legacy 8-byte beacon (no room to be more than a coverage extender). Rather
than build a second, parallel connectionless channel — which would have meant two advertising sets
competing for the same scarce per-chipset slot, exactly the resource contention `BleCapabilities`'
class doc already warns about — this slice **generalizes the existing channel into Tier B**, which
also resolves `PLAN-v2.md` Part 8's explicit open item on this channel ("delete or re-scope... where
it actually belongs") by choosing re-scope.

**What changed, concretely:**

- **Capability gate loosened.** `BleCapabilities.longRangeBeaconSupported` (required BOTH extended
  advertising AND Coded PHY) is retired. The channel now gates on `extendedAdvertisingSupported`
  alone — Tier B's actual value (a connectionless tier with no slot contention) doesn't need range
  extension, only extended advertising. Coded PHY becomes an opportunistic, additive upgrade,
  requested only when `codedPhySupported` is ALSO true (`evaluateBroadcastTierAdvertising`), so
  hardware with the former but not the latter (a broader set of devices) now gets Tier B at all,
  where before it got nothing.
- **New payload**, `MeshProtocol.encodeBroadcastTierBeacon`/`decodeBroadcastTierBeacon`: the legacy
  beacon's fields (type, rotating group id, sosHop) plus one new field, `presenceHop` — the sender's
  own `HopTracker.myHop(groupId, "PRESENCE")` at encode time. A receiver feeds it straight into
  `considerNeighborReport`, exactly mirroring how `RelayResponder` already propagates
  `Frame.Presence.hop` over GATT relay — the same distance-vector mechanism, now also running on a
  connectionless channel. This is the change that actually delivers §9.2 item 7's claim ("the
  broadcast tier is the only mechanism that makes the radar function at target scale"): presence can
  now propagate a real multi-hop gradient without ANY GATT connection ever opening. Deliberately
  NOT extended to carry position or an authenticated SOS message yet — position needs encryption/
  nonce-budget engineering and SOS hop-gradient is per-SOS-id (ambiguous the same way the legacy
  beacon's own `sosHop` already is, documented at that field's decode call sites) — both left for a
  later slice, named explicitly rather than silently dropped, same pattern as P1 deferring evidence-
  header/nickname forwarding.
- **Hardware `ScanFilter` restored — on the broadcast tier's own new scan only.** Decision 3
  (`docs/DECISIONS.md`) found *service-DATA-with-mask* filtering unreliable across chipsets years
  ago; `PLAN-v2.md` §9.2 item 1 is explicit that *service-UUID* filtering is a different mechanism
  (matched in controller firmware against the AD structure's UUID list, not a masked byte compare)
  and conflating the two is why legacy scanning ended up with no hardware filter at all. Added here,
  not to the legacy scan (`restartScan`), because this is a brand-new scan session with no live-
  tested history to regress — the legacy path stays byte-for-byte unfiltered and untouched, matching
  every other "additive only" caveat already established for this channel.
- **Degree-gated report-delay batching** (`PLAN-v2.md` §9.2 item 1's other half): a new periodic
  loop counts distinct addresses heard on this channel (any valid Tier B beacon, member or not —
  deliberately raw local density, a different "degree" than Trickle's own-group-scoped one) over a
  rolling 30s window, and only restarts the scan with `ScanSettings.setReportDelay()` set when the
  batching decision actually flips across `BROADCAST_TIER_DEGREE_BATCHING_FLOOR` (5, matching every
  other §5.4 low/high-degree split already used elsewhere in this codebase — `ForwardingPolicy`,
  `LinkSelector`). Symmetric: drops back to immediate delivery the moment measured degree falls back
  at or below the floor, matching §5.4's "every adaptation's low-degree case is the identity
  function" rule. Required overriding `ScanCallback.onBatchScanResults` too, not just `onScanResult`
  — Android delivers batched results on a different callback entirely once a report delay is set;
  missing this would have made batching silently stop delivering anything the moment it engaged.
  Scanner-side PHY also had to change from a hardcoded `PHY_LE_CODED` to `PHY_LE_ALL_SUPPORTED` as
  part of the same edit — with advertising now opportunistically 1M-PHY-only on hardware without
  Coded PHY, a scanner still hardcoded to Coded-PHY-only would have silently gone deaf to exactly
  the broader hardware set this slice's capability-gate change was meant to include.
- **`BleCapabilities.longRangeBeaconSupported` deleted** (not deprecated) — no remaining callers
  after the above; both `extendedAdvertisingSupported` and `codedPhySupported` are now consulted
  independently at their own call sites instead of being pre-combined into one check.

**Not done this slice, named explicitly rather than silently deferred:** position and SOS-message
broadcast; the full presence/position/SOS/hop-gradient payload model `PLAN-v2.md`'s P2 entry
describes is therefore still only partially built (presence hop-gradient done; the rest queued).
Legacy 31-byte beacon: completely untouched, by design — it had exactly 2 spare bytes of headroom
(see `MeshProtocol.encodeBeacon`'s own doc) and every other additive channel in this file already
established the pattern of leaving it alone rather than spending that headroom.

319 tests (up from 310, +9: 6 new in `MeshProtocolBroadcastTierTest.kt` covering the new codec
round-trip/coercion/malformed-input handling, 3 new in a new `BeaconRadioBroadcastTierBatchingTest`
covering the degree-gated report-delay pure function), detekt clean, both variants compile, `test`,
`assembleDebug`, and `assembleRelease` (incl. `lintVitalRelease`) all green. **Production code
touched**: `BeaconRadio.kt` (the generalization itself), `MeshProtocol.kt` (new codec),
`BleCapabilities.kt` (retired the narrower gate). **NOT hardware-confirmed** — same caveat this
channel's Coded-PHY-only predecessor already carried (never had hardware to test Coded PHY on),
now broader: the ScanFilter and report-delay-batching pieces are ALSO new and untested on real
hardware this session. Needs a live-device pass before being trusted the way the legacy beacon path
is. Sim-side P2 work (decisions 23-25) is unaffected — that Tier-1 harness models `TrickleTimer`
directly and doesn't touch `BeaconRadio`.

## 27. P2 production wiring, second slice: single-hop position broadcast on Tier B

2026-08-06, same day as decisions 23-26, continuing straight on from decision 26's own "not done
this slice, deferred explicitly" list.

**Scope decision, made explicit up front:** Tier B position broadcast in this slice is
**single-hop only** — a device broadcasts its OWN current fix; a receiver stores it but does NOT
re-broadcast it on Tier B. Extended advertising has no natural "relay" the way a GATT blind carrier
does (a connection exists to receive-then-retransmit over; a broadcast is heard directly by whoever
is in range and nothing more), so genuine multi-hop position propagation over a connectionless
channel would need a real gossip/epidemic design of its own — out of scope here, deliberately, same
"narrow first pass" discipline as every other phase. The existing GATT `PositionSealed` relay path
is completely unaffected and still does multi-hop exactly as before; this slice only adds a faster,
connectionless path for the 1-hop case, which is also the case §1.5 of `PLAN-v2.md` originally
flagged as "structurally marginal" (radar refreshing only on GATT reconnect, ~45s/hop against a
180s useful life).

**Wire format — reuses existing crypto verbatim rather than inventing a broadcast-specific
encoding.** `MeshProtocol.encodeBroadcastTierBeacon` gained an optional length-prefixed
`positionFrame` block: `MeshFrameCodec.encodePosition`'s FULL output (the same `FRAME_POSITION`-
tagged, groupId+hop+sealed-body bytes a GATT link would carry), embedded as-is. This is deliberately
NOT the raw AES-GCM ciphertext alone — carrying the whole GATT frame costs ~40 redundant bytes (a
duplicate groupId string; this beacon already carries `rotatingGroupId` separately) but means the
receive side reuses `MeshFrameCodec.decode`/`openPosition` completely unchanged, with zero new
crypto or framing code and the exact same nonce-safety engineering `encodePosition`'s own doc
describes. Total worst-case payload (9-byte header + 2-byte length prefix + ~170-200B position
frame for UUID-length ids) stays comfortably inside extended advertising's ~251B in-place-update
budget — checked directly, not assumed (`MAX_BROADCAST_TIER_POSITION_FRAME_BYTES = 220`).

**Sending side (`refreshBroadcastTierPositionIfDue`):** reseals from `locationTracker.location.value`
on a fixed cadence (`BROADCAST_TIER_POSITION_REFRESH_MS = 20_000L`, matching
`RelayResponder.positionFramesToPush`'s own "~15-20s" GATT refresh cadence), not on every advertise-
check tick and not only when the underlying `Location` object changes. That second part matters and
mirrors an existing, easy-to-miss GATT behaviour: `positionFramesToPush` stamps the position's
timestamp as "now" at push time, not the fix's own age, specifically so a **stationary** sender's
dot doesn't go stale on other people's radar just because the GPS provider stopped delivering new
`Location` updates. Missing this would have made Tier B position broadcast silently worse than GATT
for exactly the case (someone standing still) it should have made strictly better. The refresh
timestamp is folded into `evaluateBroadcastTierAdvertising`'s existing payloadKey, so the established
"only touch the radio when the payload actually changed" invariant (decision 1) covers position too,
for free, with no separate change-detection mechanism.

**Receiving side, real finding: position ingestion needs suspend DAO access the scan-callback
thread cannot make, AND must not get a weaker trust bar than GATT.** `ScanCallback.onScanResult`/
`onBatchScanResults` run on a raw BLE binder thread; `repo.peerKeyDao.get` (the pinned-sender-key
lookup `RelayResponder.verifySignatureIfPinned` uses) is a suspend Room query. Rather than block
that thread or build a second synchronously-readable cache of pinned keys duplicating what
`RelayResponder` already tracks, `handleResult` dispatches `ingestBroadcastTierPosition` onto
`serviceScope` per received position. That function reuses `RelayResponder.signatureCheckPasses`
directly (an `internal` companion function, already callable module-wide with no new coupling) —
so a position without a valid signature under an already-pinned sender key is dropped exactly as it
would be over GATT, not silently trusted just because it arrived over a connectionless channel.
Also passes the recovered raw ciphertext through to `PositionTracker.offer`'s `sealed` parameter
(not left null) — a position learned over Tier B stays eligible for further GATT relay onward,
extending its reach past this device's own radio range, matching `PositionTracker.Record.sealed`'s
own documented purpose.

**What this slice does NOT do, named explicitly:** multi-hop position propagation over Tier B
(see the scope decision above); SOS message/hop-gradient broadcast (still deferred from decision
26, same per-SOS-id ambiguity reasoning); peer-identity learning from a Tier B position the way
`RelayResponder.ingestOpenedPosition` does via `learnPeerIdentity` (would need `PeerIdentityResolver`
as a new `BeaconRadio` dependency — out of scope for an additive feature this narrow; Tier B already
separately feeds presence hop-tracking via decision 26's `presenceHop` field regardless of whether a
position happens to be attached).

323 tests (up from 319, +4: `MeshProtocolBroadcastTierTest.kt` gained round-trip/oversized/
truncated-length-prefix coverage for the new `positionFrame` field), detekt clean, both variants
compile/test/assemble (`assembleDebug`/`assembleRelease`, incl. `lintVitalRelease`) green.
**Production code touched**: `BeaconRadio.kt` (position sealing/ingestion, new `PositionTracker`/
`LocationTracker` constructor dependencies), `MeshProtocol.kt` (wire format extension),
`MeshService.kt` (updated the one `BeaconRadio(...)` call site). **NOT hardware-confirmed** — same
caveat as decision 26 in full: this touches the same never-hardware-tested channel, now carrying
real GPS data end-to-end for the first time. Needs a live-device pass before being trusted.

## 28. P2 production wiring, third slice: SOS hop-gradient — done right this time

2026-08-06, same day as decisions 23-27, closing out decision 26's other explicit deferral.

**The exact bug this had to avoid repeating.** Decision 26 deferred SOS deliberately, citing that
the legacy beacon's own `sosHop` field is "already deliberately NOT fed into hop tracking" because
an EARLIER version tried exactly that and broke: a rough, sosId-agnostic hop estimate was fed into a
shared `"SOS_PENDING"` key sitting alongside exact, TTL-derived per-SOS tracking, and
`HopTracker.bestActiveSosHop`'s `min()` of both let a stale rough reading leak through — the
live-tested symptom (decision 13) was a hop count frozen at "2" with only 2 test phones in the mesh,
where the exact channel could only ever produce 0 or 1. Simply wiring the existing `sosHop` field
into hop tracking on receipt would have reintroduced that exact failure mode.

**The fix that actually resolves it: key on the REAL SOS id, not a rough aggregate.** Confirmed by
`grep` before touching anything: `beacon.sosHop` is written but never read by ANY receiver, legacy
or Tier B — genuinely dead weight, not a field anything currently depends on. Removed it from the
Tier B format entirely (the legacy 31-byte beacon's own `sosHop` field is untouched — this project's
established "additive only, never touch the byte-starved legacy format" discipline) and replaced it
with `MeshProtocol.SosAlert(id, hop)`: the sender's own nearest known active SOS, sourced from a new
`HopTracker.bestActiveSos(groupId)` (split out of `bestActiveSosHop`, same staleness-checked logic,
now also returning WHICH sosId the hop belongs to). A receiver feeds this straight into
`HopTracker.considerNeighborReport(groupId, activeSos.id, activeSos.hop, ...)` — just ANOTHER SOURCE
for the SAME exact per-SOS key GATT flood-forward already uses. There is no aggregation, no shared
placeholder key, and no `min()` across sources of different fidelity: two reports for the same real
sosId compose via `HopTracker`'s ordinary distance-vector relaxation (already correct, already
tested), exactly as if both had arrived over GATT from different peers. The old bug was never really
about "hop-gradient over broadcast is unsafe" — it was about conflating a rough measurement with an
exact one under one key; keying on the real id removes the conflation, not the feature.

**Scope, deliberately narrow, matching position's own precedent:** carries hop-gradient only — id +
hop, no message, no mac, no signature. A receiver learns "an SOS exists for this group, N hops away"
for radar/UI purposes; the authenticated message content still arrives over GATT once connected,
which `BeaconRadio`'s existing blind-carrier policy already attempts for every heard device, member
or not, so this doesn't need to (and doesn't try to) accelerate message delivery itself — only the
awareness that something is active, before any connection completes.

**Wire format redesign, not purely additive this time — and why that's safe.** Adding a second
independently-optional variable-length field (alongside decision 27's position) meant the old
"trailing bytes present or not" encoding could no longer distinguish "position but no SOS" from "SOS
but no position." Redesigned both blocks to be ALWAYS length-prefixed in the output (zero length =
absent), a strictly better-defined shape than what shipped in decisions 26-27. This is a breaking
change to a format that has never been on real hardware or in any release — zero deployed devices,
zero compatibility obligation, same reasoning `JoinCode`'s own v1→v2 bump already used. Worst-case
combined size is computed, not assumed, and asserted in a test:
`MAX_BROADCAST_TIER_POSITION_FRAME_BYTES` (180, lowered from decision 27's 220 to guarantee
headroom) + `MAX_BROADCAST_TIER_SOS_ID_BYTES` (48) + fixed overhead = 240 bytes, inside the ~251B
in-place-update budget even with BOTH ceilings hit simultaneously — which a real sender never does
anyway (realistic ~150B position, since `groupId` turns out to be a 16-char hex id, not UUID-length
— confirmed against `JoinCode.GROUP_ID_LEN`, not assumed — plus a 36-char UUID `senderId`; realistic
36-byte SOS id).

**Receive-side bug caught before it shipped: an early return was skipping SOS entirely whenever
position was absent.** `handleResult`'s existing `val positionFrame = beacon.positionFrame ?: return`
guard (from decision 27) would have short-circuited past the SOS-handling code for any beacon
carrying an SOS alert but no position — i.e. most of them, since GPS fixes aren't always available.
Fixed by moving SOS handling before that guard, so the two optional fields are processed
independently, matching how they're now independently encoded.

31 tests added across two files (`HopTrackerTest.kt`: `bestActiveSos` naming the nearest id, and its
null case; `MeshProtocolBroadcastTierTest.kt`: substantially rewritten for the new always-length-
prefixed format, including a direct assertion on the worst-case combined size). 330 tests total (up
from 323), detekt clean, both variants compile/test/assemble (`assembleDebug`/`assembleRelease`,
incl. `lintVitalRelease`) green. **Production code touched**: `MeshProtocol.kt` (format redesign),
`HopTracker.kt` (`bestActiveSos` split out), `BeaconRadio.kt` (SOS sourcing on send, ingestion on
receive, the early-return fix). **NOT hardware-confirmed** — same caveat as decisions 26-27 in full.

**What P2 still doesn't carry, named explicitly:** the actual SOS message/mac/signature (deliberate,
see scope above); multi-hop position propagation over broadcast (decision 27's own deferral, still
open — no natural "relay" exists for a connectionless channel); thumbnails and catalogue digests
(§5.1's own Tier B payload list, not yet started). Tier 2/3 hardware gates remain not started for
all of P2.

## 29. P2 production wiring, fourth slice: SOS content preview — a genuine threat-model call, asked not assumed

2026-08-06, same day as decisions 23-28, going back to close decision 28's own remaining deferral
("the actual SOS message/mac/signature").

**Stopped and asked before writing code, because this one is a real tradeoff, not an implementation
detail.** The obvious design — reuse `MeshFrameCodec.sosMacInput`'s existing scheme so a receiver
can verify content the same way GATT already does — requires `senderId` in the mac input, which
means broadcasting it in the clear alongside the message. Two things made this worth stopping for
rather than just shipping: (1) `NEXT_STEPS.md` already carries an explicit, UNRESOLVED open decision
that SOS content is cleartext even over GATT ("any nearby phone can read SOS content... needs a
call before any real deployment") — so this isn't a new problem, but Tier B genuinely escalates it:
GATT requires an active connection to pull the content, Tier B broadcast makes it **passively
readable by anyone with a BLE scanner, zero interaction required**. (2) it would broadcast a
per-install `deviceId` in cleartext — currently the app never does this anywhere (position keeps
`senderId` inside its AES-GCM seal). Presented the user three options — ship it as-is (a crisis
alert being loud/discoverable could be a legitimate feature, not just a bug), skip content
broadcast entirely, or build a version that avoids the new exposure. **User chose the third.**

**The fix: a separate mac scheme that doesn't need `senderId` at all.** New
`MeshFrameCodec.broadcastSosMacInput(id, groupId, message, timestamp)` — same HMAC-SHA256 under the
group key, just missing the one field that would have leaked identity. Not interchangeable with
`sosMacInput`'s own mac (different input shape, different value) — computed fresh at broadcast time
under the group key we already hold, not reused from `SosEntity.mac`. This is deliberately a SECOND
crypto surface, not a reuse of the existing one (unlike decisions 27-28's whole "reuse existing
crypto verbatim" discipline) — the explicit cost of the privacy fix, called out rather than glossed
over. Dropping `senderId` also freed up wire-format budget (removing a ~36-byte field gave more room
than it cost), a nice side effect of the privacy-preserving path, not the reason for it.

**Scope: broadcasts a short (≤120B), authenticated preview of whichever SOS `HopTracker.
bestActiveSos` already names as nearest — ours or a relayed one we're holding.** Extending past
"our own SOS only" turned out to be free: the message was already verified once (under
`sosMacInput`'s scheme) before being stored by `RelayResponder.handleSos`, so re-authenticating it
under the new broadcast-only scheme for re-broadcast is safe regardless of origin — no new trust is
extended, we're vouching for content we already checked. Cached per-sosId (not time-based like
position's cache): SOS content is immutable once created, nothing to go stale.

**Budget forced an explicit priority call: position is dropped from any broadcast cycle where SOS
content is being sent.** Worst case with both maxed (max position + max SOS content) is ~400 bytes,
well over the ~251B in-place-update budget; worst case with content alone (no position) is 222
bytes, comfortable. `BeaconRadio` now deliberately omits `positionFrame` whenever `activeSos.content
!= null` — an emergency preview outranks a routine position refresh for however many seconds the
SOS stays the nearest active one. Documented as a deliberate trade, not a bug: position resumes the
moment the SOS goes stale or drops out of range.

**Receive side: verified but NOT stored.** A broadcast preview is missing fields a real `SosEntity`
requires (`senderId`, `ttl`, the GATT-authoritative mac/signature) — inserting a partial record
would misrepresent what's actually known. `verifyBroadcastSosContent` checks the mac (mirroring
`RelayResponder.authOk`'s exact acceptance shape via `CryptoUtils` directly, no `RelayResponder`
reference needed) and logs a bare confirmation via `DiagnosticsLog` (event type only — no message
body, no id, matching that class's own "never message bodies" constraint, verified against its
class doc before writing the log line). **Full UI surfacing of this preview — showing it to the user
before the GATT-confirmed record arrives — is a named follow-up, not silently dropped.** Unlike
position/presence ingestion, this needed no suspend dispatch: `repo.getGroupKey` is synchronous, so
verification runs inline on the scan-callback thread.

Two small supporting changes: `SosDao.getById(id)` (new query — nothing existed to fetch a single
held SOS by id) and `GroupRepository.sosDao` made public (matching `peerKeyDao`'s existing pattern,
both exist for exactly this kind of cross-class Tier B access without growing the repository with
one-off wrapper methods).

337 tests (up from 330: +6 net, since one existing worst-case-size test was updated in place for
the format change rather than replaced). detekt clean, both variants compile/test/assemble (incl.
`lintVitalRelease`) green. **Production code touched**: `MeshFrameCodec.kt` (new mac-input
function), `MeshProtocol.kt` (wire format grows a third optional block), `HopTracker.kt` (unchanged
this decision — `bestActiveSos` from decision 28 already gave exactly what was needed),
`BeaconRadio.kt` (content sourcing/caching on send, verification on receive), `Daos.kt`/
`GroupRepository.kt` (the new query + visibility change). **NOT hardware-confirmed** — same caveat
as decisions 26-28 in full.

**What P2 still doesn't carry:** full UI surfacing of the broadcast SOS preview (verified but only
logged, named above); multi-hop position propagation over broadcast; thumbnails and catalogue
digests. Tier 2/3 hardware gates remain not started for all of P2.

## 30. Fourth live test (v0.7.0-dev, 3 phones): two real bugs found and fixed, one report explained, one non-bug ruled out

First hardware round on decisions 23-29's Tier B work (2026-08-06), 3-phone setup: phones 1 and 3 in
the same group, phone 2 in no group at all (pure blind relay). Four things reported; diagnosed by
reading code (`BeaconRadio`/`HopTracker`/`PositionTracker`/`RelayResponder`) and cross-referencing
`DiagnosticsLog` exports, not by guessing.

**Real bug, fixed: presence hop count read 3-4 between two phones that should never see more than
1-2.** The user's own follow-up detail — phone 2 held no group key and was doing nothing but
relaying, yet 1 and 3 still showed each other at 4 hops — ruled out `PositionTracker`'s already-
understood `maxPositionRelayHops` ceiling (that only bounds position, and needs several distinct
devices to reach 4 anyway) and pointed at presence instead. Root cause, found in this session's own
decision 26 code: `BeaconRadio.handleResult` called `HopTracker.considerNeighborReport` TWICE per
scan result with the identical `sourceId` — once for the direct 1-hop hearing, once for the
propagated `presenceHop + 1` value. `HopTracker.updateHop`'s ownership rule (a worse reading is
accepted over the current one only from the SAME source that currently owns the key — legitimate for
"my route degraded," see decision 22's own note on this exact mechanism) doesn't distinguish "the
same peer reporting again later" from "the same peer's two calls in one event," so the second,
worse call could immediately overwrite the first, better one it had just set. **Fix:** merge the two
candidate hop values (`minOf(DIRECT_HEARING_HOP, propagatedHop)`) before calling
`considerDirectHop` once, not twice, per scan result — same output when only one value would have
won anyway, but no longer lets one event's second call undercut its own first. Two regression tests
added directly at the `HopTracker` level (one reproducing the bug pattern, one proving the merge
fixes it) rather than only at `BeaconRadio`'s level, since the vulnerable rule lives in
`HopTracker.updateHop`, not in the caller.

**Real bug, fixed: a deleted group's last-known member positions kept showing on the radar for a
long time afterward.** "Delete a group member" maps to this app's only deletion primitive —
`GroupChatScreen`'s "Delete group" (there is no per-member kick by design; a group is a shared key,
not individually revocable membership) — which calls `GroupRepository.dismantleGroup`. That method
correctly wipes evidence/SOS/nicknames/peerKeys/group/key from Room, but has no way to reach
`PositionTracker`: it's in-memory-only, owned by `MeshService` in the ble layer, a different package
`GroupRepository`'s data layer was never wired to. Without a fix, a dismantled group's positions sat
in the table until their own ordinary staleness window expired on its own (180s, plus up to 45s per
hop — see `PositionTracker`'s class doc) — which reads as "stale position" exactly as reported, even
though nothing was actually stale about the mechanism, just orphaned. **Fix, two parts:** an
immediate `PositionTracker.clearForGroup(groupId)` call added at the delete-group dialog's confirm
button in `GroupChatScreen`, alongside the existing `dismantleGroup` call; and a periodic
`PositionTracker.pruneOrphaned(activeGroupIds)` safety net added to `MeshService.startPruning`'s
existing sweep loop (same shape as `GroupRepository.sweepOrphanKeys`), which also catches automatic
`expireGroups()` dismantling — a path with no single call site to hook an immediate clear into.

**Real gap, fixed: a nickname set after a P3 persistent link was already open never reached that
peer.** Traced by reading code, not logs — `DiagnosticsLog` deliberately never records nickname/
message content, so this needed tracing from `GroupChatScreen`'s UI fallback (raw id shown when no
nickname was known) upstream through storage (fine) to the actual gap: nickname content was only
ever pushed via `framesToPushOnConnect`'s once-per-connection catalog-filter exchange, never via the
periodic `refreshFramesToPush` decision 20 already built for presence/position specifically because
P3 links can now stay open for minutes, not seconds. This exact gap was pre-flagged in decision 18's
own text ("deferred, same mechanical pattern") and sat unaddressed until this report reproduced it
live. **Fix:** `RelayResponder.presenceAndPositionFrames` (the private function backing
`refreshFramesToPush`) now also pushes `relay.nicknamesForGroup(g.id)` per active group, unconditionally
every refresh rather than tracked for whether it changed — this app's groups are small (3-8 people,
PLAN-v2.md §5.5), so the cost of pushing unconditionally is bounded and not worth a change-tracking
mechanism. No dedicated new test: `refreshFramesToPush`'s per-group loop calls `GroupRepository.
getGroupKey`, which touches Android Keystore-backed `EncryptedSharedPreferences` — already documented
as unavailable under Robolectric (`RelayEngineTest`'s and `RelayResponderTest`'s own class docs both
call this out and design their coverage around it) — so this function has never had integration-level
test coverage for ANY of its frames, not just nicknames. The change itself reuses `relay.
nicknamesForGroup`/`MeshFrameCodec.encodeNickname` verbatim from the already-tested
`framesToPushOnConnect` path (same call, same line, added to a second call site) — full suite still
342 tests, detekt clean, both variants compile/test/assemble green.

**Reported, explained, not a bug: position sometimes read 3-4 hops, "should be maximum 2."** Isolated
via `DiagnosticsLog` (grepping `member=true` SOS senders to separate the real 3-phone group's traffic
from other nearby phones' blind-relay traffic passing through) — the extra hop count came from
devices outside the 3-phone group relaying position for each other, combined with
`maxPositionRelayHops = 4`'s existing, deliberate ceiling (PLAN-v2.md's own design cap on how far a
position is allowed to propagate). Correct behavior for a mesh with more than 3 devices in range,
not a defect in this 3-phone test. No code change.

**Reported, not investigated further this pass: reconnection after a disconnect takes a while on
radar before it "eventually works well."** In the user's own words, this already resolves correctly
— read as within the existing, deliberate reconnect-cooldown/backstop timing P3 already documents
(decisions 19-20), not a new finding. Left alone; revisit only if a future round reports it as
broken rather than just slow.

**UI polish, also from this round's notes ("contrast/brightness of grays and greens needs
increasing... more whiter... in radar more green, more luminiscent, bright... dots blink well, could
be a bit sharper"):** `Theme.kt`'s `DarkOnSurfaceMuted` brightened a second time (0xA3ADB8 ->
0xC3CCD4) and the app-wide reserved `Safe` green brightened (0x34D399 -> 0x6EE7B7, Tailwind
emerald-300) — a single shared constant, so the boost lifts every use of it, not just the radar.
`RadarView.kt`'s own green elements (background wash, sweep, glow rings, label, crosshair, cardinal
ticks) all raised a second time on top of an earlier pass. Dot sharpness: blink cadence/frequency
left untouched per the "blink well" feedback, but the halo tightened (+6f/0.25 alpha -> +4f/0.18
alpha) and the core's alpha floor raised (0.5 -> `DOT_BLINK_MIN_ALPHA` 0.65) so the pulse dips softer
without ever reading as washed-out, leaving the core as the crisp edge the eye locks onto.

342 tests (up from 337: +5 net, two new `HopTrackerTest` cases, three new `PositionTrackerTest`
cases, no new `RelayResponderTest` case for the reason given above). detekt clean, both variants
compile/test/assemble green. **Production code touched:** `BeaconRadio.kt` (presence-hop merge
fix), `PositionTracker.kt` (`clearForGroup`/`pruneOrphaned`), `GroupChatScreen.kt` (wires
`clearForGroup` into the delete-group flow), `MeshService.kt` (wires `pruneOrphaned` into the
existing pruning loop), `RelayResponder.kt` (nicknames added to periodic refresh), `Theme.kt`/
`RadarView.kt` (contrast/brightness/sharpness). **These fixes are NOT yet hardware-confirmed** — the
bugs they address were; the fixes themselves are new this pass and need their own live round.

## 31. P2: full UI surfacing of the broadcast SOS content preview — the last named follow-up from decision 29

Closes decision 29's own "full UI surfacing of this preview — showing it to the user before the
GATT-confirmed record arrives — is a named follow-up, not silently dropped." Until now,
`BeaconRadio.verifyBroadcastSosContent` verified a broadcast SOS content preview's mac and did
nothing with it beyond a bare `DiagnosticsLog` line — never shown to the user, never reachable from
the UI layer at all.

**Design constraint carried over from decision 29: still not a real `SosEntity`.** A preview is
missing fields (`senderId`, `ttl`, the GATT-authoritative mac/signature) a stored record requires,
so inserting it into Room would misrepresent what's actually known — same reasoning, unchanged.

**New `BroadcastSosPreview`: in-memory-only, one entry per group, deliberately no staleness clock
of its own.** Same "never persisted" shape as `PositionTracker`, for the same privacy reason. The
one real design choice: rather than give this cache its own age-based expiry — a second,
independent notion of "is this still current" — `forGroupIfBest(groupId, currentBestSosId)` only
returns a match when the caller-supplied id agrees with `HopTracker.bestActiveSos(groupId)`'s
current answer, the SAME source this preview's own hop-gradient (decision 28) already comes from.
Two independently-aging channels feeding one SOS display is the exact bug shape this app was
already bitten by once (the historical Pass 13 SOS-hop bug, `NEXT_STEPS.md`/this log's own early
history) — delegating freshness to `HopTracker` rather than duplicating it avoids reintroducing
that class of bug by construction, not by discipline. SOS content is immutable once created
(decision 29's own note), so `offer()` is a plain overwrite — no dedup/compare needed.

**Wired through the same in-memory-state lifecycle decision 30 just established for
`PositionTracker`**: `MeshService` holds it publicly alongside `hopTracker`/`positionTracker`,
`GroupChatScreen`'s delete-group flow clears it immediately (`clearForGroup`), and the existing
periodic pruning loop sweeps orphans (`pruneOrphaned`) as the safety net for automatic
`expireGroups`. Introducing a second ble-layer per-group in-memory tracker without the identical
teardown wiring decision 30 just added for the first one would have been a foreseeable repeat of
the same gap one decision later.

**UI: `NavigateScreen`, both branches (GPS fix present or not).** Reads
`hopTracker.bestActiveSos(groupId)` once (previously two separate lookups —
`bestActiveSosHop` for the hop count, nothing for the id — now one, since the preview needs the id
too), then looks up the preview keyed against that same id, and — the actual "don't duplicate the
confirmed message" rule — suppresses it entirely once a real `SosEntity` for that id already exists
in `sosList` (Room), so a user never sees a preview sitting alongside or disagreeing with the fuller
confirmed message already in the group's normal chat feed. Displayed quoted, explicitly labeled
"unconfirmed preview, connecting to verify" (in `AppColors.Danger`, same as the hop-count line
above it) — never presented with the same visual weight as a confirmed record.

New `BroadcastSosPreviewTest.kt` (pure JVM, no Android deps — same tier as `HopTrackerTest`/
`PositionTrackerTest`): the id-match/id-mismatch/no-cache-entry freshness contract, overwrite
behavior, and the `clearForGroup`/`pruneOrphaned` teardown paths, mirroring
`PositionTrackerTest`'s equivalent decision-30 cases.

348 tests (up from 342: +6, all in the new `BroadcastSosPreviewTest`). detekt clean, both variants
compile/test/assemble (incl. `lintVitalRelease`) green. **Production code touched:** new
`BroadcastSosPreview.kt`, `BeaconRadio.kt` (constructor param, `offer()` call site),
`MeshService.kt` (holds it, wires teardown), `GroupChatScreen.kt` (wires `clearForGroup`),
`NavigateScreen.kt` (the actual UI surfacing). **NOT hardware-confirmed** — no live SOS was raised
during decision 30's hardware round, so this path (like SOS hop-gradient/content before it) has
never run on real hardware.

**What P2 still doesn't carry:** multi-hop position propagation over broadcast; thumbnails and
catalogue digests (§5.1's own Tier B payload list). Tier 2/3 hardware gates remain not started for
all of P2.

## 32. P2: multi-hop position propagation over the broadcast tier — closing decision 27's "own gossip design, out of scope" deferral

Decision 27 shipped single-hop Tier B position broadcast deliberately: "a Tier B receiver does not
re-broadcast a position it heard from someone else — extended advertising has no natural 'relay'
the way GATT store-and-forward has one, so genuine multi-hop position propagation over broadcast
would need its own gossip design, out of scope here." This closes that deferral.

**The core design question: what does loop prevention even mean on a broadcast medium?** GATT
relay's existing `RelayResponder.selectPositionsToRelay` uses split horizon — never advertise a
route back toward the specific peer that taught it to us — because GATT links are point-to-point,
so "back toward" is meaningful. A Tier B beacon is omnidirectional: everyone in range hears the
exact same payload, so there is no single "peer" to route a broadcast away from; split horizon's
own premise doesn't have a broadcast equivalent. Decision 26/28 already answered this question once
for presence/SOS hop-gradients, just never named it as the same question: plain distance-vector
relaxation — `HopTracker.considerNeighborReport`, "a report only replaces the current value if it's
actually better" — is loop-safe on its own without split horizon, because a worse or equal-or-later
copy of the same fact is simply rejected wherever it's heard, so it can never get re-picked-up for
further relay. `PositionTracker.offer` already has the identical shape (`staleOrWorse` rejection,
"the shorter path wins at an equal timestamp"), already proven for GATT relay — extending it to
Tier B needed no new mechanism, just recognizing the existing one already applies.

**Design: our own fix always wins the one position slot; relay only fills what would otherwise be
dead airtime.** Tier B has room for exactly one position frame per beacon (the same ~251B budget
decision 29 already fights over with SOS content). When `BeaconRadio` has a current GPS fix, that's
still always the highest-value thing to put in that slot — nothing else can source it. New
`BeaconRadio.relayedPositionFrameForBroadcastTier(groupId)` only runs when `broadcastTierPositionFrame`
is null (no fix this cycle) and no SOS content is claiming the slot instead: it calls
`RelayResponder.selectPositionsToRelay(positionTracker.forGroup(groupId), repo.deviceId,
MAX_POSITION_RELAY_HOPS, limit = 1)` — the SAME function GATT relay uses, called with `toPeer = null`
(no peer to exclude on a broadcast), reused unchanged rather than duplicated — and forwards the
winning record's original sealed bytes verbatim via `MeshFrameCodec.reframePositionForRelay`, same
"never re-encrypt a relayed position" reasoning `positionFramesToPush`'s own doc gives for GATT.
`MAX_POSITION_RELAY_HOPS = 4` in `BeaconRadio`'s companion object, kept in sync with
`RelayResponder`'s own private `maxPositionRelayHops` by doc only — same cross-file-constant pattern
`PositionTracker.PER_HOP_SLACK_SECONDS`/`HopTracker.PER_HOP_SLACK_MS` already use, since there's no
shared ble-internal type to hang a single source of truth off instead. The relay candidate's
identity (`senderId:hop:timestampSec`, not the frame's own bytes) is folded into
`evaluateBroadcastTierAdvertising`'s existing payloadKey so the radio still only restarts when
something actually changed, this time including "the relay candidate itself changed."

**Real bug found and fixed while wiring the receive side: `ingestBroadcastTierPosition` was reading
the wrong hop.** `MeshFrameCodec.decode` on a position frame returns `Frame.PositionSealed(groupId,
hop, sealed)` — an ENVELOPE hop, visible without the group key, which is what every relay on the
path actually increments (`reframePositionForRelay`'s whole reason for existing). The AES-GCM-sealed
inner body ALSO carries its own hop field, baked in once by the ORIGINAL sender at seal time and
frozen forever after — `RelayResponder.ingestOpenedPosition`'s own comment already documents
choosing the envelope's `frame.hop` over the inner `body.hop` for exactly this reason. `BeaconRadio`'s
Tier B receive path was using `body.hop` (the frozen inner one) instead — invisible while Tier B was
single-hop-only (both were always 0, decision 27), but wrong the moment this decision made the
envelope hop actually vary: every relayed position would have been stored as if it were hop 0, a
direct fix, defeating `PositionTracker`'s own hop-based staleness/relay-limiting entirely. Fixed to
match GATT's own established, documented choice — decode the whole `Frame.PositionSealed`, use
`frame.hop`, not `body.hop`, everywhere `positionTracker.offer` is called. Also now enforces the
same relay ceiling GATT's receive path already does (`frame.hop >= MAX_POSITION_RELAY_HOPS` is
dropped) rather than trusting an arbitrarily high envelope hop.

**No dedicated new `BeaconRadio`-level test** — same constraint every Tier B decision so far has
carried: `BeaconRadio` has no direct unit test file at all (needs real Android BLE APIs). Every
building block this decision composes is already tested elsewhere and unmodified in its own logic:
`RelayResponder.selectPositionsToRelay` (`RelayResponderPositionSelectionTest`, including the
`toPeer` omitted/null case this decision actually uses), `MeshFrameCodec.reframePositionForRelay`/
`openPosition`'s envelope-vs-inner-hop divergence after a relay (`MeshFrameCodecTest`, "reframe
PositionForRelay changes only the hop, never the sealed bytes" — the exact property this decision's
bug fix depends on), and `PositionTracker.offer`'s self-correcting distance-vector semantics
(`PositionTrackerTest`). 348 tests (unchanged — this decision is pure composition plus one bug fix
in already-covered call paths, not new pure-JVM-testable logic). detekt clean, both variants
compile/test/assemble green. **Production code touched:** `BeaconRadio.kt`
(`relayedPositionFrameForBroadcastTier`, `evaluateBroadcastTierAdvertising`'s slot-choice/payloadKey,
`ingestBroadcastTierPosition`'s hop-source fix), `MeshProtocol.kt` (doc only — `positionFrame`'s
class doc updated to describe the relay fallback). **NOT hardware-confirmed** — no multi-device,
multi-hop-in-range scenario has been tested on real hardware for this path yet.

**What P2 still doesn't carry:** thumbnails and catalogue digests (§5.1's own Tier B payload list —
thumbnails specifically belongs to the not-yet-started P5 media phase, not P2; catalogue digests is
P2-scoped but genuinely underdesigned — the existing GATT `CatalogFilter` alone (~256B) doesn't fit
Tier B's entire ~251B budget, a real tradeoff question rather than a mechanical extension, deferred
pending that design call). Tier 2/3 hardware gates remain not started for all of P2.

## 33. Position relay hop ceiling raised 4 -> 120, and RadarView's stale-dot fade made hop-aware

User asked directly: `maxPositionRelayHops = 4` should maximize real reach (multi-km, "even 100s of
km... given the relays are in place and no gap in ranges") rather than stay pinned near this app's
own 3-8-person group-size scope — a position/presence frame's relay depth isn't actually bounded by
group membership at all, since ANY phone (member or not) can carry a frame one hop further via the
blind-relay architecture; a long, unbroken chain of relays can legitimately span far more physical
distance than the group itself has members.

**Checked first: was 4 ever actually justified?** No — `RelayResponder.kt:59`'s `maxPositionRelayHops
= 4` had zero documented reasoning anywhere in the codebase or this decision log, unlike nearly every
other tuned constant here. Best inference: it happened to comfortably cover the stated 3-8 person
scope (PLAN-v2.md §5.5), never revisited once the blind-relay chain could legitimately span far more
devices than that.

**The real ceiling: the wire format, not an arbitrary safety margin.** The envelope hop field is a
single unsigned byte, and `MeshProtocol.UNKNOWN_HOP = 255` is already reserved as a sentinel — so any
real value has to sit meaningfully below 255. At realistic outdoor BLE range per hop, that caps
*achievable* reach at tens of km, not hundreds — hundreds of km would need thousands of hops, which
cannot fit in one byte regardless of the constant's value. Reaching that would need a real wire-format
break (widening the hop field to 2 bytes) — bigger and riskier than any format change made so far this
session, since it would land after v0.7.0/0.7.1-dev are already out on real test phones. **User chose
not to make that break now**, opting for the largest value the current format safely supports instead:
120, comfortably below the 255 sentinel, shared (by doc only, matching `PositionTracker
.PER_HOP_SLACK_SECONDS`/`HopTracker.PER_HOP_SLACK_MS`'s precedent) between `RelayResponder
.maxPositionRelayHops` and `BeaconRadio.MAX_POSITION_RELAY_HOPS` — the latter also reused unchanged
for opaque (blind-carried) presence custody, which already shared one ceiling with position before
this change.

**A real precision gap this surfaced: `RadarView`'s stale-dot fade was a flat 180s window,
independent of hop.** `PositionTracker.effectiveMaxAgeSeconds` already scales a position's real
eligibility window with hop count (+45s per hop, matching each hop's own independent reconnect-cycle
delay) — at hop 120 that's over 90 minutes. But `RadarView`'s fade curve capped out at a flat 180s
(`STALE_FADE_END_SECONDS`), sized back when the practical hop range was 4-8 hops (180-360s). Past 180s,
every dot — 3 minutes old or 90 minutes old — read identically at the same minimum-alpha "ghost" level,
losing all precision exactly where a genuinely long relay chain would make it matter most. **Fixed**:
new `RadarDot.maxAgeSeconds` (per-dot, sourced from `PositionTracker.effectiveMaxAgeSecondsFor(hop)`,
a new public convenience wrapper avoiding a duplicated 180s literal) replaces the flat constant in the
fade calculation — a dot now fades relative to its OWN real staleness budget, not an unrelated fixed
window. All three call sites that construct `RadarDot` (`NavigateScreen`, `HomeScreen`,
`GroupChatScreen`) updated; `NavigateScreen`'s own `PlacedPeer` intermediate gained the matching field.

350 tests (up from 348: 3 new `PositionTrackerTest` cases for `effectiveMaxAgeSecondsFor`). detekt
clean, both variants compile/test/assemble green. **Production code touched:** `RelayResponder.kt`/
`BeaconRadio.kt` (the constant, now documented), `PositionTracker.kt` (`effectiveMaxAgeSecondsFor`),
`RadarView.kt` (`RadarDot.maxAgeSeconds`, fade calc), `NavigateScreen.kt`/`HomeScreen.kt`/
`GroupChatScreen.kt` (threading the real per-dot value through). **NOT hardware-confirmed** — no
long relay chain has been tested on real hardware.

## 34. P2: catalogue digests over the broadcast tier, deliberately fixed-size — the last P2-scoped Tier B payload item

Closes §5.1's Tier B payload table's remaining P2-scoped item (thumbnails belongs to the not-yet-
started P5 media phase, not P2). Stopped before building this one too: the obvious approach — reuse
GATT's existing `CatalogFilter.build()`, whose size scales with held item count (`sizeBitsFor
(itemCount) = (itemCount * 10).coerceIn(64, 4096)`) — would let ANY passive BLE scanner, member or
not, infer roughly how much content a group holds just from the broadcast filter's byte length, and
watch that estimate change **over consecutive beacons**. Given this app's actual threat model (a
hostile state actor scanning during a protest), a rising filter size is a passively-readable "an
incident is escalating here" signal, obtainable without ever connecting to a device — sharper than
the abstract "reveals content volume" framing first given, since Tier B beacons are per-group (one
per rotating group id), so the leak is precise to a single group's location and moment, not blurred
across every group the way GATT's own combined filter already deliberately is.

**Presented three options; user picked dynamic sizing, accepting the leak** — after being shown the
concrete cost of the alternative: at a FIXED small size (128 bits, chosen to fit Tier B's budget),
false-positive rate degrades sharply past this app's own stated common case (`CatalogFilter`'s class
doc: "tens of items, not hundreds") — ~4.6% at 20 items, ~46% at 50, ~90% at 100 — meaning a fixed
filter goes nearly USELESS for exactly the busy/escalating-incident case where the security concern
was sharpest, while a dynamic filter stays close to the tuned ~1% rate at any scale. **User chose
functionality over closing this specific leak, a deliberate exception weighed case-by-case, not a
reversal of the standing "stop before shipping new passive-exposure surface" preference** — SOS
content specifically (decision 29) still got the privacy-preserving path when asked, because that
leak was sharper (identity) and the cost of avoiding it was lower.

**Design: new `RelayEngine.catalogKeysForGroup(groupId)`** — a per-group sibling of
`RelayResponder.currentCatalogKeys()` (which deliberately COMBINES every active group for GATT's
"one filter per connection" design); this one is scoped to a single group, matching Tier B's own
per-group beacon structure, and must not fold another group's activity in. New `SosDao.idsForGroup`
added to match `EvidenceDao`'s existing equivalent. `CatalogFilter.build` gained an optional
`forcedSizeBits` param (used here; GATT's own call sites are unaffected, still item-count-scaled by
default). `BeaconRadio` caches the built filter per group, keyed on the group's actual held-item SET
(not a timer or the global `catalogEpoch`, which would rebuild too eagerly for unrelated groups'
changes) — `CatalogFilter.build` re-randomizes its seed on every call by design (GATT's own anti-
repeat-false-positive reasoning), which would otherwise look like a changed payload on every
advertise-check tick, fighting this channel's hard-won "only touch the radio when something real
changed" discipline (Pass 12-14's history).

**A real bug found and fixed before it shipped: the filter can't just always be attached.**
Decision 29's position/SOS-content exclusion only fires when actual SOS *content* is present — the
bare SOS hop-gradient (id+hop, no content) can legitimately coexist with position. That combination,
maxed, plus a maxed catalog filter totals 274 bytes — genuinely over `BROADCAST_TIER_BUDGET_BYTES`
(251, promoted from an assumed-not-enforced test-only literal to a real named constant this
decision, since this is the first time anything needed a RUNTIME check rather than a compile-time-
provable one). Not assumed safe — found live while computing the real worst-case matrix, the same
discipline every prior Tier B budget claim in this file has followed. **Fix**: new
`BeaconRadio.buildBroadcastTierPayload` computes the beacon WITHOUT the filter first, and only
attaches it if what's left still fits; the filter is the lowest-priority field of the four this
beacon carries, since a dropped filter costs nothing today (nothing consumes it on receipt yet — see
next paragraph) while a dropped position or hop-gradient would be a real regression. The OTHER
achievable combination (SOS content maxed, no position, plus a maxed filter — 199 bytes) fits with
real margin, confirmed directly in a test alongside the overflow case, not assumed either way.

**Broadcast side only this pass — receive-side consumption is a named follow-up, not silently
dropped**, same shape decision 29 left its own UI surfacing (closed two decisions later, in decision
31). The filter is decoded and available (`MeshProtocol.BroadcastTierBeacon.catalogFilter`); nothing
yet tests a peer's holdings against it or acts on the result (e.g. prioritizing a reconnect).

359 tests (up from 350: `CatalogFilter.forcedSizeBits` coverage, `RelayEngine.catalogKeysForGroup`'s
per-group scoping, the wire round-trip/malformed-input/budget-overflow cases for the new field).
detekt clean, both variants compile/test/assemble green. **Production code touched:** `CatalogFilter
.kt` (`forcedSizeBits`), `Daos.kt` (`SosDao.idsForGroup`), `RelayEngine.kt`
(`catalogKeysForGroup`), `MeshProtocol.kt` (wire format's fourth optional block,
`BROADCAST_TIER_BUDGET_BYTES`), `BeaconRadio.kt` (`catalogFilterFrameForBroadcastTier`,
`buildBroadcastTierPayload`). **NOT hardware-confirmed.**

## 35. The SOS/message split: every message was secretly an emergency alert — a real, not hypothetical, miss

Surfaced while explaining why the SOS broadcast-preview byte cap mattered: this app has never had a
separate "casual message" concept. `GroupChatScreen`'s ordinary compose box has always called
`MeshService.sendSos()` — the SAME `SosEntity`, mac scheme, hop-gradient tracking, and
`IMPORTANCE_HIGH`/`CATEGORY_ALARM` push notification, for every single message, not just genuine
emergencies. This was the app's original, deliberate design from its earliest build passes (treat
everything as if it might matter, since a protest-coordination context can't assume most messages
are casual) — not a bug introduced this session. But it meant every byte-budget decision this whole
P2 session (decisions 29, 31, 32, 34) was implicitly calibrated against "SOS fires on every message,"
inflating how often position/content/filter would actually contend for the same Tier B slot, and
meant the loud alarm notification fired for routine chat too.

**Chosen fix: reuse everything, gate only the alert-specific side effects on one new flag** — not a
parallel `MessageEntity`/DAO/frame from scratch. New `SosEntity.isAlert: Boolean = false`. The
storage/relay/catalog-filter pipeline is completely unchanged and unconditional for every message,
alert or not (this is deliberate: `RelayEngine.catalogKeysForGroup`/`currentCatalogKeys` must keep
including everything, or normal message sync breaks). Only three things gate on `isAlert == true`:
- **The SOS hop-gradient.** `MeshService.sendSos`'s `hopTracker.markSosOrigin` (our own authored
  alert) and `RelayResponder.handleSos`'s `hopTracker.considerDirectHop` (a received one) both now
  check `isAlert` first. Every OTHER path that ever feeds this table (`BeaconRadio`'s Tier B receive
  side, decision 26/28) only ever propagates a hop/id pair that already passed through one of these
  two gates upstream — so the invariant "this table only ever holds alert-flagged ids" holds
  transitively across the whole mesh without needing to re-check it at every hop.
- **The alarm-style notification.** `RelayResponder.handleSos`'s `onSosReceived(frame.sos,
  groupName)` call is now inside the same `isAlert` check as the hop-gradient feed above.
- **The Tier B broadcast content preview.** No code change needed here at all — `BeaconRadio
  .sosContentFor` sources exclusively from `hopTracker.bestActiveSos(groupId)`, which (per the first
  bullet) can now only ever name an alert-flagged id.

**Wire format**: `Frame.Sos` gains an `isAlert` byte (`MeshFrameCodec.VERSION` 4 -> 5, same
established "bump when a frame type's own shape gains a field" precedent as v4's own hop-byte
addition — this is a shared version byte across every frame type, a blunt but simple and safe choice
given the only devices in the field are the user's own test phones, freshly reflashed each round,
same reasoning as decision 28's earlier format break). `sosMacInput` folds `isAlert` in as an
authenticated byte — without this, a relay could silently flip an emergency into a routine message
(suppressing a real alert) or a routine message into an emergency (manufacturing a false alarm),
neither detectable by a receiver. `RelayEngine.createSos(groupId, text, isAlert = false)` — the
default keeps every existing call site (aside from the two updated below) behaviorally identical.
`AppDatabase` version 7 -> 8 (`fallbackToDestructiveMigration`, already this app's established,
deliberate pre-1.0 policy — nothing worth preserving across a schema change yet).

**UI**: `GroupChatScreen`'s existing Send action stays the default (`isAlert` unset — quiet). A NEW,
separate, always-Danger-tinted "Send as SOS" action sits beside it — deliberately its OWN control,
not a mode toggle on the same button, so raising a real emergency is never a matter of remembering to
flip a setting mid-crisis. `SosComposeScreen` (the multi-group broadcast composer) is inherently
`isAlert = true` throughout — its whole purpose already was broadcasting an emergency. Both compose
screens show a live byte counter against `MAX_BROADCAST_TIER_SOS_MESSAGE_BYTES`, relevant to the
alert path specifically (a quiet message never competes for the Tier B preview slot at all).
`GroupChatScreen`'s feed (`FeedRow`) now visually marks `isAlert` items in `AppColors.Danger` (the
one color this app reserves exclusively for SOS) with an inline "SOS" label — previously every
message rendered identically regardless of what it actually was.

**Also landed in the same pass: the SOS broadcast-preview cap trimmed 100 -> 65 bytes** —
`"SOS: "` (5 bytes) plus roughly 60 characters, matching what a genuine short, keyword-style
emergency alert ("medical emergency, gate 3") actually needs, now that this cap only ever governs
real emergencies rather than arbitrary chat content. Recomputed worst-case Tier B budget arithmetic
throughout `MeshProtocol.kt`'s doc and `MeshProtocolBroadcastTierTest.kt`'s assertions accordingly
(SOS-content-plus-filter worst case: 199 bytes, down from 234 at the old 100-byte cap).

367 tests (up from 359: wire round-trip + mac-sensitivity coverage for `isAlert` in
`MeshFrameCodecTest`, `ingestSos` preserving `isAlert` through relay in `RelayEngineTest`). No new
test for the `isAlert`-gating decision logic itself in `RelayResponder.handleSos`/`MeshService
.sendSos` — both hit the same documented Android Keystore/Robolectric wall as every other
`authOk`-gated handler in this file (`RelayResponderSenderIdentityTest`'s own class doc), and the
gating itself is a trivial conditional wrap around already-covered calls, not new decision logic
worth extracting into its own pure function the way `checkSenderKeyPin`/`signatureCheckPasses` were.
detekt clean, both variants compile/test/assemble green. **Production code touched:** `Entities.kt`
(`SosEntity.isAlert`), `AppDatabase.kt` (v8), `MeshFrameCodec.kt` (`VERSION` 5, `sosMacInput`,
`encodeSos`/`decodeSos`), `RelayEngine.kt` (`createSos`), `MeshService.kt` (`sendSos`),
`RelayResponder.kt` (`handleSos`'s gating), `MeshProtocol.kt` (the 65-byte cap),
`GroupChatScreen.kt`/`SosComposeScreen.kt` (UI). **NOT hardware-confirmed** — the wire-format bump
means this needs its own fresh test round; no v0.7.x-era device can talk to a build past this point
without updating.

## 36. Tier 2 removed from the testing methodology; P2 marked PASSED, awaiting Tier 3

Two related, explicit user directives, both process/methodology decisions rather than code changes.

**Tier 2 (synthetic radio load — a few ESP32 boards or a BlueZ Linux box emulating dozens of virtual
BLE devices, §6.4 of `PLAN-v2.md`) is removed from this project's testing methodology entirely.**
User's framing: it had become scope creep — process weight with no realistic path to actually
happening. Across this entire v2 effort, no hardware toward it was ever acquired, and every phase
that named it as a gate (P0a explicitly, P2 implicitly via §9.2 item 1's scan-storm prediction)
already treated it as "open, not blocking" in practice per the Part 7 preamble's own asynchronous-
gate discipline — this decision just makes that permanent and explicit rather than leaving it as a
perpetually-open, never-resolved line item. `PLAN-v2.md` §6.4 now defines a two-tier model (Tier 1
simulator, Tier 3 three real phones); kept the "Tier 3" number as-is rather than renumbering it to
"Tier 2," since every existing decision/status reference in this document already calls it Tier 3
and renumbering would only create a second thing to keep in sync for no real benefit. The real,
named cost: §9.2 item 1's crowd-scale scan-storm prediction (200-400 devices) now rests on Tier 1
(simulator) plus real-world Tier 3 rounds (3 devices) alone — no independent synthetic-load
cross-check at intermediate density. Not silently dropped; the tradeoff is written into §6.4 and
P0a's own gate description directly.

**P2 is marked STATUS = PASSED** — code-complete and Tier 1-verified, explicitly AWAITING (not
blocked on) Tier 3 confirmation, which is in progress on the v0.7.3-dev APK as of this decision. This
is the first phase explicitly marked PASSED under the Part 7 preamble's own "a hardware gate is a
checkpoint on the claim, not a precondition for the next phase's code" rule — previously that rule
was applied implicitly (implementation kept moving without ever formally declaring a phase "done"
pending hardware); this decision makes the practice explicit and gives P2 the actual status label.
Nothing about the code changed — this decision is purely process/documentation, updating
`PLAN-v2.md`'s RESUME HERE block, §6.4, the Part 7 preamble, P0a's STATUS block, and P2's own closing
summary to reflect both changes.

No test/code impact — 367 tests unchanged, detekt clean, both variants compile/test/assemble green
(same state as decision 35 left it). **Production code touched: none.** `PLAN-v2.md` only.

## 37. SOS body encryption — closes `NEXT_STEPS.md`'s long-flagged "SOS text is authenticated but not encrypted" gap

P6's first slice (`PLAN-v2.md` §4.4), user's explicit choice over P4/Couriers given this session's
heavy privacy/security emphasis. Since this app's earliest build passes, `SosEntity.message` (and
`senderId`/`timestamp`/`isAlert`) travelled over GATT as cleartext plus a separate `HMAC(group_key)`
tag (`mac`) and an optional Ed25519 `signature` — authenticated, but readable by ANY nearby non-
member relay that simply connects (no key needed to read, only to forge). Position solved the
equivalent problem back at v2/v3 (AES-GCM sealing under the group key, decision 8-era); SOS never got
the same treatment because it long predates that pattern. This decision brings SOS to parity.

**Wire format**: `Frame.Sos` (four cleartext fields + `mac` + `signature`) replaced with
`Frame.SosSealed(groupId, id, ttl, hop, sealed: ByteArray)` — `id`/`ttl`/`hop` stay in the cleartext
envelope for exactly the reason position's `hop` does (a non-member blind relay must still be able to
dedup on `id`, flood-control on `ttl`, and advance `hop` without ever reading the message); everything
sensitive (`senderId`/`message`/`timestamp`/`isAlert`) moves inside one AES-GCM seal. New
`MeshFrameCodec.sealSosBody`/`sealSos`/`openSos`/`reframeSosForRelay`, mirroring
`encodePosition`/`openPosition`/`reframePositionForRelay`'s exact shape — a failed decrypt (wrong
key, tampered ciphertext, bad GCM tag) IS the auth failure now, replacing the old separate `authOk`
check entirely for this one frame type (evidence-meta and nicknames still use `authOk`, unchanged).
Nonce is deterministic, derived from `sha256(id)` alone (`sosNonce`) rather than position's
per-second counter — a given SOS `id` is sealed exactly once, ever (content is immutable once
created, decision 29's own note), so re-sealing the same `id` always reproduces the same ciphertext,
which is what lets `reframeSosForRelay` forward the ORIGINAL bytes verbatim across every hop instead
of re-encrypting (same "stable ciphertext for dedup" reasoning decision 22-era position work landed).
`MeshFrameCodec.VERSION` 5 -> 6. `AppDatabase` v8 -> v9 (`SosEntity.mac`/`signature` columns replaced
with one `sealed: ByteArray?`), `fallbackToDestructiveMigration` as usual pre-1.0.

**`RelayResponder.handleSos` split into member/blind-relay paths, same shape `handlePositionSealed`/
`takeOpaqueCustody` already use for position** — this split was IMPOSSIBLE before this decision: the
old scheme's `authOk` vacuously passed for a group we hold no key to (cleartext was already readable
either way, so there was nothing gained by refusing to read it), so every SOS silently took the
member-shaped path regardless of membership. Now a non-member genuinely cannot read a sealed SOS, so
it needs its own opaque-custody path — new `takeOpaqueSosCustody`, reusing `OpaqueFrameRelay` the same
way position's own opaque path does, `RelayEngine.DEFAULT_TTL` as the blind-relay hop ceiling (no
group-key-derived signal to size this from otherwise).

**A real bug found today while fixing this session's checkpoint**, not present in decision 35 or
earlier: `RelayEngine.createSos` was calling `sealSos` (which returns a FULLY FRAMED wire message —
type/version/groupId/id/ttl/hop plus the sealed blob) and storing that directly as `SosEntity.sealed`.
Every consumer of `SosEntity.sealed` (`RelayResponder.reframeStoredSos`, `floodForwardSos`, and the
receive-side `handleSos` itself) treats that field as RAW ciphertext only — exactly what `decode()`
extracts from an arriving frame's envelope, and exactly what `SosEntity.sealed`'s own doc comment
says it holds ("mirrors `PositionTracker.Record.sealed`'s exact shape"). Left as committed, every
locally-authored SOS (`MeshService.sendSos` -> `createSos` -> `floodForwardLocalSos`) would have
double-framed on its very first send — an entire wire frame nested inside what `reframeSosForRelay`
treats as opaque ciphertext — breaking every self-sent SOS message, both the immediate flood-forward
and any later catalog-filter push of that same stored row. Root cause: `sealSos` and `encodePosition`
share one shape (seal, then immediately frame for sending), which is right for position (never stored,
only ever pushed fresh each ~20s cycle) but wrong for SOS (persisted, re-sent many times from storage
across the item's whole relay lifetime). Fixed by splitting the seal step out: new
`MeshFrameCodec.sealSosBody(key, id, senderId, message, timestamp, isAlert, signingPrivateKey):
ByteArray` returns just the raw sealed bytes; `sealSos` now calls it then wraps with
`reframeSosForRelay` for the "encode and send right now" case. `RelayEngine.createSos` switched to
call `sealSosBody` directly. Caught only because finishing this checkpoint's test rewrite required
building a realistic `SosEntity` fixture in `RelayResponderTest.kt` and running it through the actual
catalog-filter push path — the exact kind of miss unit tests that only check envelope fields (not an
end-to-end open) would never have caught; recorded as a reason to keep at least one round-trip test
per frame type that goes all the way through `openX`, not just `decode`.

**Two detekt findings fixed while getting this checkpoint back to green**, both pre-existing from the
WIP commit's main-source changes, unrelated to the bug above: `RelayResponder.handleSos` had 3 return
statements (limit 2) — split into `handleSos` (key lookup + opaque-custody dispatch + open) and a new
`ingestOpenedSos` (signature check + store + hop-tracking + flood-forward), the exact same split
`handlePositionSealed`/`ingestOpenedPosition` already established for position; and one line in
`framesToPushOnConnect`'s MTU-fallback branch exceeded the max line length, wrapped.

**Test rewrite** (`MeshFrameCodecTest.kt`, `RelayResponderTest.kt`): every SOS test now builds via
`sealSos`/`sealSosBody` and asserts through `openSos`, mirroring `PositionSealed`'s existing coverage
shape — opaque-without-key/opens-with-key, hop-0 origin case, `isAlert` true/false, signature
round-trip, impersonation detection, no-signing-key case, envelope fields readable without any key,
`reframeSosForRelay` changes only `ttl`/`hop` never the sealed bytes. The old `sosMacInput`-based
tests (mac sensitive to `isAlert`, mac sensitive to the full message not just first 255 bytes) are
superseded by one `tampering with any byte of a sealed sos` test — AES-GCM authenticates the entire
plaintext as one unit, so a single test now covers what needed two dedicated tests under the old
per-field-mac scheme. Two NEW tests with no old equivalent: `openSos rejects/still accepts a message
at exactly MAX_SOS_MESSAGE_BYTES` (this cap moved from a `decode()`-time check to an `openSos`-time
one, since `decode()` no longer looks inside the seal at all) and `sealing the same sos id twice
produces identical ciphertext` (regression guard for the deterministic nonce's stability property,
which `reframeSosForRelay` depends on). `RelayResponderTest.kt`'s `sosFixture` now seals with a fixed
in-test 32-byte key (never `GroupRepository.getGroupKey`, unavailable under Robolectric — same
constraint documented on this file's own class doc) via `sealSosBody`, matching the corrected
production pattern.

370 tests (up from 367 before this decision's test rewrite — net new coverage: the
`MAX_SOS_MESSAGE_BYTES`-at-`openSos` pair and the nonce-stability test above; several old tests were
consolidated 1:1 into their sealed-shape equivalents rather than adding a net-new count matching
every old test 1:1). detekt clean, both variants compile/test/assemble (`assembleDebug`/
`assembleRelease`) green. **Production code touched:** `MeshFrameCodec.kt` (`Frame.SosSealed`,
`sealSosBody`/`sealSos`/`openSos`/`reframeSosForRelay`, `SosBody`, `VERSION` 6, `sosMacInput` removed),
`Entities.kt` (`SosEntity.sealed` replacing `mac`/`signature`, doc updates on `EvidenceEntity`/
`NicknameEntity` that referenced the now-removed `sosMacInput` by name), `AppDatabase.kt` (v9),
`RelayEngine.kt` (`createSos` uses `sealSosBody`), `RelayResponder.kt` (`handleSos`/`ingestOpenedSos`
split, `takeOpaqueSosCustody`, MTU-fallback line wrap), `MeshProtocol.kt`/`BeaconRadio.kt` (doc-only —
updated stale comments naming the removed `sosMacInput`/old `Frame.Sos`; decision 29's
`broadcastSosMacInput` broadcast-preview scheme is UNCHANGED, still deliberately separate from this
GATT-authoritative scheme). **NOT hardware-confirmed** — the `VERSION` 6 wire break means no
pre-checkpoint test APK can talk to this build until reflashed; next live round needed.

## 38. Rotating group handle — closes `PLAN-v2.md` §4.4's cleartext-`groupId` traffic-analysis gap

P6's second slice. Every GATT-relayed frame (SOS, position, evidence-meta, nickname, presence) has
always carried its `groupId` in cleartext — an observer capturing mesh traffic could correlate which
packets belong to the same group with no key needed at all, even after decision 37 sealed SOS
content itself. This decision replaces cleartext `groupId` with an opaque rotating handle,
`HMAC(groupKey, epoch)`, on every one of those frame types (`Frame.CatalogFilter` excluded — it
never carried `groupId`, confirmed by inspection: it's one filter per connection covering every
group's items, keyed only by item-type+item-id strings).

**Reuses the beacon's own construction, generalized, not duplicated.** `BeaconRadio`'s discovery
layer already solves this exact problem for BLE advertisements
(`CryptoUtils.rotatingAdvertisementId`, 60s window, 6-byte truncated HMAC-SHA256) with a working
resolve-by-iterating-active-groups pattern (`BeaconRadio.refreshCaches()`). `rotatingAdvertisementId`/
`candidateAdvertisementIds` gained a `windowSeconds: Long = ID_WINDOW_SECONDS` param — every existing
beacon call site passes none, so that behavior is byte-for-byte unchanged — and a new
`MeshFrameCodec.groupHandle(key, epochSeconds)` calls the same function with a new
`CryptoUtils.GATT_GROUP_HANDLE_WINDOW_SECONDS` (72h) instead.

**Why 72h, not the beacon's 60s.** A beacon id is re-derived fresh every ~60s advertise cycle; a GATT
handle is computed ONCE at creation/first-ingest and forwarded verbatim for a frame's whole relay
life (a blind relay has no key to recompute it with). For a receiver's ±1-window tolerance to still
catch a handle computed at time T when checked at any later receive time, the window must EXCEED this
app's 48h content-retention ceiling (`RelayEngine.CONTENT_MAX_AGE_MILLIS`), not just cover it — 72h
gives 24h of margin, absorbing decision 33's multi-hour 120-hop transit time and ordinary clock skew.
Domain separation from the beacon's own 60s window is provable, not assumed: for any realistic
calendar date this app runs at (2020-2100), `epoch/60` and `epoch/259200` land in disjoint integer
ranges, so one HMAC construction safely serves both purposes under the same group key — confirmed by
a new test (`CryptoUtilsTest`), not just argued in the doc comment.

**Receiver-side resolution**: new `GroupRepository.resolveGroupKeyByHandle(handle, epochSeconds)`,
modeled directly on `BeaconRadio.refreshCaches()`'s shape — iterate every active group's key, compute
3 candidate handles per group (adjacent-window tolerance), match via `.contentEquals()` (not
`constantTimeEquals` — a handle isn't secret once it's on the wire, no timing-attack surface to
defend). Deliberately NOT cached (unlike `BeaconRadio.matchTable`) — GATT frame receipt is bounded by
open-connection count × per-connection frame cadence, several orders of magnitude cooler than the
beacon's genuinely hot per-scan-result path, and this app's own group counts are small (a few groups,
3-8 members each). Pure matching core factored into `GroupRepository.matchHandle` (no DAO/Keystore
access) so it's directly unit-testable despite this class's real-Keystore construction constraint
under Robolectric — new `GroupRepositoryHandleTest.kt`, including a test proving a handle computed at
creation still resolves when checked up to just under 48h later, the empirical proof of the 72h
derivation.

**Every relayed frame's own field list changed.** `Frame.SosSealed`/`PositionSealed`/`Presence` swap
`groupId: String` for `handle: ByteArray` directly. `Frame.EvidMeta`/`Nickname` needed a bigger
structural change: they used to decode DIRECTLY into a ready-to-use `EvidenceEntity`/`NicknameEntity`
(no separate open/verify step, unlike Sos/Position), which is no longer possible once `groupId` isn't
in the envelope — both are now envelope-only structs (entity fields present, `groupId` deferred to a
resolve step downstream), the same shape Sos/Position have had since decision 8/37.
`SosEntity`/`EvidenceEntity`/`NicknameEntity` each gain a stored `handle: ByteArray?` (computed once
in `RelayEngine.createSos`/`createEvidence`/`setNickname`, forwarded verbatim on every relay — same
discipline `SosEntity.sealed` already established in decision 37). `MeshFrameCodec.VERSION` 6 → 7,
`AppDatabase` v9 → v10 (`fallbackToDestructiveMigration`, no manual migration needed).

**Evidence-meta and nickname get asymmetric treatment on a resolution failure — verified via tracing
the actual code, not assumed:**
- **`EvidenceEntity.groupId` becomes nullable (`String?`)**, keeping its existing Room-backed relay
  mechanism intact rather than moving to an in-memory `OpaqueFrameRelay` custody. Traced why:
  offering chunks onward to a peer (`RelayResponder`'s manifest push) reads `totalChunks` from a
  **stored** entity row — a purely in-memory custody has nowhere to keep that. A blind relay still
  stores the row, just with `groupId = null` ("blind-relay-held, group unresolved"), so the
  already-working blind chunk-relay mechanism (unauthenticated for a non-member either way, before
  and after this decision) keeps working unchanged.
- **`NicknameEntity` gets a genuinely new `OpaqueFrameRelay`-based opaque-custody path**
  (`takeOpaqueNicknameCustody`, `opaqueNickname` store). Verified via grep: every nickname push path
  (`currentCatalogKeys`/`framesToPushOnConnect`/`presenceAndPositionFrames`/`handleCatalogFilter`) is
  scoped to `repo.groupDao.getActiveGroups()` only — a blind-relay-held nickname (stored under the
  OLD vacuous-auth-pass scheme) was never re-served to anyone. A comment already in
  `RelayResponder.kt` confirms this by contrast: it explains position/presence's blind-relay frames
  live in a separate path "outside the loop... on purpose... because we're not a member," while
  nickname code had no such treatment. So this is a strict improvement, not a new risk — new
  `MeshFrameCodec.reframeNicknameForRelay`/`encodeNicknameFrame` (nickname never had a reframe
  function before, since it never needed to survive a blind hop; it's a structural no-op re-encode,
  no hop/ttl field on this frame type — confirmed by a new round-trip test).

**Real bug found and fixed as part of this slice, unrelated to the design above**: `opaqueSos`
(added in decision 37) was populated via `takeOpaqueSosCustody`'s `.offer(...)` but
**`.framesToRelay(...)` was never called anywhere** — confirmed via grep, `opaquePositions`/
`opaquePresence` both already fed `presenceAndPositionFrames`'s `carried` list, `opaqueSos` didn't.
SOS blind custody has accepted frames but never actually forwarded them since decision 37 shipped.
Fixed by adding it (and the new `opaqueNickname`) to that same `carried` list. This bug — plus the
whole nickname dead-end finding above — is exactly the kind of miss a real end-to-end test catches
that a signature-shape test doesn't: new `RelayResponderTest` coverage feeds a crafted SOS/position/
presence/nickname frame for an unresolvable group into `handleIncoming` and asserts all four come
back out via `refreshFramesToPush` to a different peer (and NOT back to the peer that supplied them
— split horizon) — genuinely possible for the first time, since this class's `repo` holds zero
groups, so `resolveGroupKeyByHandle` never touches Keystore on this path either.

`presenceMacInput`/`evidMacInput`/`nicknameMacInput` are unchanged — they authenticate the REAL
resolved `groupId` (computed identically by a sender who knows it and a receiver who resolved it),
not the transport-scoping handle, so nothing about the authenticated-bytes contract needed to move.

381 tests (up from 370), detekt clean, both variants compile/test/assemble
(`assembleDebug`/`assembleRelease`, incl. `lintVitalRelease`, R8-minified) green. **Production code
touched**: `CryptoUtils.kt` (`GATT_GROUP_HANDLE_WINDOW_SECONDS`, generalized `windowSeconds` param),
`Entities.kt` (`handle` on all three; `EvidenceEntity.groupId` nullable), `AppDatabase.kt` (v10),
`GroupRepository.kt` (`resolveGroupKeyByHandle`/`matchHandle`), `MeshFrameCodec.kt` (`VERSION` 7,
`groupHandle`, every relayed frame type's new shape, `reframeNicknameForRelay`), `PositionTracker.kt`
(`Record.handle`), `RelayEngine.kt` (`createSos`/`createEvidence`/`setNickname` compute+store
`handle`; null-safe `maybeReassemble`), `RelayResponder.kt` (the bulk — every handler's
resolve-then-branch rewrite, `authOk` deleted, `takeOpaqueNicknameCustody`, the `opaqueSos` bugfix),
`BeaconRadio.kt` (3 mechanical call-site edits — Tier B's embedded position sub-frame reuses
`encodePosition`/`reframePositionForRelay` unchanged, so it stops leaking cleartext `groupId` too, a
bonus not separately designed for), `MeshProtocol.kt` (doc-only). **NOT hardware-confirmed** — the
`VERSION` 7 wire break means no pre-checkpoint test APK can talk to this build until reflashed; next
live round needed for all of decisions 37-38 together (37 was never hardware-confirmed either).

## 39. Content-sealing epoch key — and why it is NOT forward secrecy, correcting `PLAN-v2.md` §4.4

P6's third item. `PLAN-v2.md` §4.4 called for a "non-interactive epoch key ratchet:
`K_e = HKDF(root, e)` ... gives forward secrecy against later seizure." Before implementing, two
research passes (one mapping every call site, one validating the actual cryptographic claim) found
that **no non-interactive design — stateless or a stateful hash-ratchet with deletion — can deliver
real forward secrecy here**, and this is worth stating plainly rather than burying in a footnote,
since it corrects this project's own prior planning document:

- The group's root key **must** stay permanently retained in `GroupKeyStore`, unconditionally, for
  two independent reasons already shipped before this decision: decision 38's wire-obfuscation
  handle (`MeshFrameCodec.groupHandle`/`GroupRepository.resolveGroupKeyByHandle`) needs it stable
  forever, and `GroupRepository.getShareCode` reconstructs the original raw 32-byte key to
  regenerate the shareable invite code (`JoinCode.encode` puts the raw key on the wire verbatim —
  "no owner role, every member can invite" depends on this).
- Any non-interactive derivation from a permanently-retained secret — no matter how many hash-chain
  hops sit in between, even with each intermediate state deleted after use — is trivially
  recomputable by anyone holding that secret, since the derivation algorithm is public and needs no
  fresh/random input at each step. "Delete `state_e`" protects nothing when `state_e` is a pure,
  public function of a key the attacker already has. This is a general result about non-interactive
  ratchets, not a narrow bug in one design attempt.
- Real forward secrecy needs an interactive step (a fresh Diffie-Hellman exchange, the way Signal/
  TLS 1.3/Noise all do it) — exactly what `PLAN-v2.md` §4.4 already rejected in favor of
  non-interactivity, deliberately, because this mesh partitions constantly and can't rely on two
  members successfully coordinating a live exchange.

**ELI5 of what's actually lost/kept, for the record**: if a phone is seized, the group's whole
history is exposed either way — this feature changes nothing about that, because the root key has
to survive on-device regardless (wire handle + invite code both need it). What this feature actually
buys: domain separation between the wire-obfuscation handle and the content seal (so a compromise of
one derivation's output doesn't hand over the other), and bounding a single *independently*-leaked
`K_e` (a crash dump, a memory scrape catching one epoch's derived key but not the root) to ~24h of
exposure instead of the group's whole life (typically 4-5 days, up to `JoinCode.MAX_LIFETIME_MILLIS`
= 180 days). Given that framing, the honest stateless version — matching §4.4's own literal formula —
was built anyway, since it's genuinely useful for that narrower, real threat, just not the seizure
threat the old wording implied.

**Design**: `CryptoUtils.contentEpochKey(rootKey, epochSeconds, epochLenSeconds = CONTENT_EPOCH_SECONDS)`
computes `HKDF-SHA256(ikm = rootKey, salt = null, info = "20.07-content-epoch-v1:" + epoch, len = 32)`
via Tink's `subtle.Hkdf.computeHkdf` — already a pinned dependency (`tink-android:1.8.0`), same
"subtle API, not `KeysetHandle`" precedent `SenderIdentity.kt` already established for Ed25519, so no
new dependency and no new API style. `CONTENT_EPOCH_SECONDS` = 24h — matches this codebase's existing
day-scale constant family (`RelayEngine.CONTENT_MAX_AGE_MILLIS` = 48h, `GATT_GROUP_HANDLE_WINDOW_SECONDS`
= 72h, `JoinCode.DEFAULT_LIFETIME_MILLIS` = 48h) and gives real rotation granularity across this app's
actual group lifetimes; a 72h epoch (matching the wire-handle window) was rejected since a
default-length group might not even cross one boundary, collapsing the feature to "no rotation" in the
common case. **Fully stateless** — no `GroupKeyStore`/`AppDatabase`/`GroupRepository` schema changes at
all; every call site already holds the root key locally and derives fresh, which is also what makes
the whole feature directly unit-testable with no Robolectric/Keystore constraint, unlike everything
else key-adjacent in this codebase.

**Two independent, non-conflated "epoch" concepts.** Decision 38's wire-obfuscation handle stays
exactly as-is, keyed off the permanent root key, completely unchanged by this work. The new
content-sealing key is a separate derivation with its own (shorter) epoch length, used only for the
actual AES-GCM seal (position/SOS bodies) and HMAC auth tags (evidence-meta/nickname/presence macs,
Tier B's SOS broadcast-preview mac) that previously used the root key directly. `MeshFrameCodec.sealSos`/
`encodePosition`/`encodePresence` each gained a second key parameter (`rootKey` for their internal
`groupHandle` call, unchanged; `contentKey` for the actual seal/mac) — `sealSosBody`/`openSos`/
`openPosition`/bare `authTag`/`encrypt` calls keep their existing single-key shape, callers now just
pass a derived key instead of the root key in.

**Candidate-key list only where the timestamp is hidden pre-decrypt.** `candidateContentEpochKeys`
returns 5 keys (epoch+1 down through epoch-3 — 96h backward coverage, exceeding decision 38's own 72h
worst-case content-lifetime figure with margin, +1 forward mirroring `candidateAdvertisementIds`' own
±1 skew tolerance) and is needed only for SOS/position, whose timestamp lives inside the AES-GCM seal
itself (unknowable before a successful open). Evidence-meta/nickname/presence/Tier B's SOS
broadcast-preview all derive a single exact epoch key from an already-cleartext envelope timestamp
field — no candidate list needed there, same distinction decision 38 already drew for its own
±1-window tolerance.

**Explicitly scoped out, unchanged**: `RelayResponder`'s Wi-Fi Direct handoff path
(`maybeAccelerateOverWifiDirect`/`handleWifiDirectHandoff`/`handleWifiDirectAccept`) still macs
directly under the root key — same precedent decision 38 already set never migrating WFD onto the
handle scheme, and `NEXT_STEPS.md`'s open "remove or keep off?" question already covers this
experimental, off-by-default path.

**No `MeshFrameCodec.VERSION` bump** — wire byte layouts are completely unchanged, only which key
opens/verifies them. The resulting old-build/new-build interop failure mode is different from
decisions 37-38's: frames still decode fine (envelope/version checks pass), they just fail to
open/verify (wrong key), surfacing as silent drops rather than a decode rejection. Same "reflash
before cross-build testing" caveat those decisions carry, via a different mechanism.

390 tests (up from 381) — 7 new in `CryptoUtilsTest` (epoch-key stability within a window, rotation
across a boundary, determinism, differs under a different root key, never collides with
`rotatingAdvertisementId`'s output under the same key — domain separation confirmed empirically,
mirroring decision 38's own "never collide" test — plus `candidateContentEpochKeys` coverage of both
the current epoch and a creation-time key checked just under decision 38's 72h figure later) and 2 new
in `MeshFrameCodecTest` (`sos`/`position` body opens under its content epoch key but explicitly fails
to open under the raw root key directly — the empirical proof the key migration actually took effect,
not just the two-param signature). detekt clean (two new `@Suppress("LongParameterList")` test helpers
mirroring `MeshFrameCodec.sealSos`'s own suppress, one new `@Suppress("LargeClass")` on
`MeshFrameCodecTest` mirroring `CryptoUtils`'s own `TooManyFunctions` suppress), both variants
compile/test/assemble (`assembleDebug`/`assembleRelease`, incl. `lintVitalRelease`, R8-minified) green
— no `missing_rules.txt` emitted, confirming the new `-keep class
com.google.crypto.tink.subtle.Hkdf { *; }` proguard rule (mirroring the existing Ed25519 rules) is
sufficient. **Production code touched**: `CryptoUtils.kt` (`contentEpochKey`/`candidateContentEpochKeys`
+ constants, class doc rewritten to explain the not-forward-secrecy property up front),
`proguard-rules.pro` (`Hkdf` keep rule), `MeshFrameCodec.kt` (`sealSos`/`encodePosition`/
`encodePresence` gain `contentKey`), `RelayEngine.kt` (`createSos`/`createEvidence`/`setNickname`/
`maybeReassemble` derive and use `contentEpochKey`), `RelayResponder.kt` (every outgoing/incoming site
for SOS/position/presence/evidence-meta/nickname), `BeaconRadio.kt` (Tier B position + SOS
broadcast-preview mac). **NOT hardware-confirmed** — same as decisions 37-38, flagged for the next
live round: old-build/new-build phones should fail to interoperate on sealed content (silently, not
via decode rejection); two new-build phones should round-trip correctly.

## 40. Frame padding to size buckets — closes `PLAN-v2.md` §4.4's last open item, P6 code-complete

P6's fourth and final slice. §4.4 calls for adopting bitchat's own Noise-packet padding scheme
(256/512/1024/2048-byte buckets) for *every* GATT frame type, not just encrypted ones — a passive
observer sizing raw GATT writes could otherwise fingerprint frame type and rough content length from
wire length alone (a 70-byte write is a presence heartbeat; a 900-byte write is a long SOS; a filter
push over ~180 bytes is a busy catalogue), independent of anything already sealed/authenticated inside
the frame.

**Where padding lives, and why not inside `MeshFrameCodec.encode`/`decode`.** Two frame types —
`FRAME_POSITION` and `FRAME_CATALOG_FILTER` — are byte-identical whether sent over GATT or embedded
verbatim into a Tier B beacon (`BeaconRadio.refreshBroadcastTierPositionIfDue`/
`catalogFilterFrameForBroadcastTier` call `MeshFrameCodec.encodePosition`/`encodeCatalogFilter`
directly, confirmed by grep). Tier B's whole beacon payload is capped at
`MeshProtocol.BROADCAST_TIER_BUDGET_BYTES` (251) — a 256-byte padding floor baked into those two
functions would blow that budget on every single Tier B beacon. Padding therefore does not live in
the codec's own encode/decode functions at all. Instead it wraps the GATT **transport**, at the one
choke point every outgoing/incoming byte already funnels through regardless of frame type:
`MeshGattClient.write()`/`MeshGattServer.notify()` on the way out, `onCharacteristicChanged`/
`onCharacteristicWriteRequest` on the way in. `BeaconRadio`'s connectionless advertising path never
calls any of those four functions, so this placement excludes Tier B for free — no per-frame-type
allowlist/denylist needed, "for all frame types" (§4.4's own wording) falls out automatically for
every GATT frame while Tier B stays untouched.

**Wire shape**: `MeshFrameCodec.padGattFrame`/`unpadGattFrame`, a thin envelope wrapped *around* an
already-fully-encoded frame — `[realLen: UShort BE][frame bytes][random padding]`. The explicit
length prefix (not a sentinel scanned for inside the padding) is what makes this safe for every frame
type uniformly: `FRAME_EVID_CHUNK` is the one type with no length field of its own — `decode()`'s own
branch for it reads the chunk payload via `buf.remaining()` — so it would silently absorb trailing
padding as chunk data if padding were left unstripped before reaching `decode()`. Stripping happens
in `MeshGattClient`/`MeshGattServer` before `RelayResponder.handleIncoming` is ever called, so
`decode()` itself needed zero changes for this. Padding bytes are drawn from a new
`CryptoUtils.randomBytes` (reusing the process-shared `SecureRandom` instance already established for
`encrypt`, not a fresh one per call) rather than zero-filled, so the padded tail isn't visually
distinguishable from the ciphertext/MAC bytes that usually precede it — an all-zero tail would itself
be a fingerprint.

**A frame already at or past the largest bucket is sent length-prefixed but unpadded** (no bucket to
round up to). This matters for `FRAME_SOS`: a near-`MAX_SOS_MESSAGE_BYTES` (2000) sealed SOS, once
GCM-tagged and framed, lands close to or past the 2048 bucket already — the Explore research pass run
before this slice confirmed this app has **no MTU-aware fragmentation anywhere** (every frame is
handed to `writeCharacteristic`/`notifyCharacteristicChanged` as exactly one ATT write; the evidence
chunker's indexed/manifest reassembly is never reused for SOS). A frame that large already risked
failing outright against a real ~517-byte-MTU link before this slice; padding does not create that
gap, and fixing it (real GATT fragmentation, or capping SOS length to something MTU-safe) is
explicitly out of scope here — flagged as a follow-up, not silently absorbed into this change.

**`MeshFrameCodec.VERSION` 7 → 8**, even though every `Frame` subclass and every `decode()` branch is
byte-identical to v7 — the version bump is about the *outer* transport wrapper added in
`MeshGattClient`/`MeshGattServer`, not an inner field change. Documented explicitly in the constant's
own comment, since a future reader diffing v7→v8 inside `decode()` would otherwise find nothing and
reasonably wonder why the bump happened. Real compatibility consequence: a build without this
wrapper reads the new envelope's 2-byte length prefix as if it were a raw frame's own leading
type/version bytes — garbage type in the common case (drops silently, no matching `when` branch), or,
rarely, a byte collision with a real `FRAME_*` constant that then fails the inner version check
anyway. Same "reflash before cross-build testing" posture as decisions 35/37/38.

**Cost, stated plainly, not silently absorbed**: this inflates every small, high-cadence GATT frame
(presence ~70B, a short nickname push, a WFD capability announcement) up to a 256-byte floor — real
extra airtime on links this app already treats as scarce (`MeshFrameCodec`'s own class doc names
"Frugality" as a first-class design goal). This is the deliberate cost of the already-decided §4.4
tradeoff (traffic-analysis resistance over minimal bytes-on-the-wire for *every* frame type,
following bitchat's lead and going further than bitchat itself, which only pads Noise packets) rather
than a fresh decision made in this slice — no new privacy-tradeoff conversation needed here, unlike
decisions 29/34, since the bucket sizes and "adopt for all frame types" scope were already chosen in
`PLAN-v2.md` before this session.

9 new tests in `MeshFrameCodecTest` (bucket rounding at every boundary including exact-fit edges,
oversized-frame-sent-unpadded, random- not zero-fill padding, truncated/malformed input rejected,
full `padGattFrame`→`unpadGattFrame`→`decode` round trip, and one specifically targeting
`FRAME_EVID_CHUNK`'s `buf.remaining()` read to prove padding never leaks into decoded chunk data).
399 tests total (up from 390), detekt clean (four new named constants —
`LENGTH_PREFIX_BYTES`/`MAX_UNSIGNED_SHORT`/`BITS_PER_BYTE`/`BYTE_MASK` — added rather than inlining
magic numbers, since this is new code and the file's existing inline `0xFF` usages elsewhere are
detekt-baseline-grandfathered, not exempt going forward), both variants compile/test/assemble
(`assembleDebug`/`assembleRelease`, incl. `lintVitalRelease`, R8-minified) green, no
`missing_rules.txt`. Version bumped to v0.7.7-dev, fresh debug APK built and `aapt`-confirmed
(`versionCode='18' versionName='0.7.7-dev'`) before committing. **Production code touched**:
`CryptoUtils.kt` (`randomBytes`), `MeshFrameCodec.kt` (`VERSION` 8, `PAD_BUCKETS`, `padGattFrame`,
`unpadGattFrame`), `MeshGattClient.kt` (`write` pads, `onCharacteristicChanged` unpads),
`MeshGattServer.kt` (`notify` pads, `onCharacteristicWriteRequest` unpads). **NOT hardware-confirmed**
— stacks on top of decisions 37-38's own unconfirmed `VERSION` bumps (7 was never live-tested either),
so the next live round needs all of 37/38/40 together; decision 39 is independently unconfirmed via a
different mechanism (wrong-key silent-drop, not decode rejection) and can be tested in the same round.

**P6 is now code-complete** — all four items (SOS body encryption, rotating group handle,
content-sealing epoch key, frame padding) shipped across decisions 37-40. Per the project's explicit
sequencing directive (`PLAN-v2.md`), next is P4 (Couriers); the sustained field-test milestone still
waits until only P5 (Media) and P7 (bitchat bridge) remain outstanding.

## 41. P4 slice 1 — courier tag + envelope seal/open, crypto construction only

First slice of P4 (Couriers, `PLAN-v2.md` §4.2), which adopts bitchat's courier model
(group-addressed instead of recipient-addressed) as a supplementary, opportunistic delivery path
for the case where flood-relay plus this app's own 48h content retention isn't enough to bridge a
partition before content expires — SOS/position/etc. keep working exactly as they do today; couriers
add a second, independent mechanism on top.

**Scope, deliberately narrow.** A Plan agent mapped the existing patterns this should reuse before
any code was written (mirroring how decision 38 used parallel research agents for a comparably-sized
change) and recommended crypto-only as slice 1, over bundling in storage or GATT wiring, for a
concrete reason grounded in this project's own history: decision 37's real bug (`RelayEngine.createSos`
once stored `sealSos`'s fully-framed output where raw ciphertext was expected, double-framing every
self-authored SOS) happened exactly at the seal/frame boundary, before that boundary had been proven
correct in isolation. Couriers add a genuinely new hard problem on top of that same split — copy-budget/
handover state (deferred to a later slice) — so proving the tag and the seal/open round-trip work
before any storage-shape or handover-arithmetic decisions get bolted on keeps the same discipline.

**The tag: `HMAC(groupKey, UTC-day)`, 16 bytes (`PLAN-v2.md` §4.2's own wording).** `CryptoUtils.
rotatingAdvertisementId`/`candidateAdvertisementIds` already serve two purposes off one construction
under different `windowSeconds` (60s beacon, decision 38's 72h GATT handle) — this adds a THIRD
partition, `COURIER_TAG_WINDOW_SECONDS = 86400L` (a UTC day; Unix epoch seconds are already UTC, so
`epochSeconds / 86400` **is** the day number, no timezone handling needed). The existing functions'
truncation length (`ROTATING_ID_LEN = 6`, private) wasn't a parameter — generalized in place the same
way decision 38 generalized `windowSeconds`, adding `truncateLen: Int = ROTATING_ID_LEN` (every
existing call site passes none, byte-for-byte unchanged) plus `COURIER_TAG_LEN = 16`. Domain
separation extends decision 38's own empirical proof one more partition: for 2020-01-01 to 2100-01-01
UTC, the beacon window (`epoch/60`) ranges [26297280, 68374080], the GATT-handle window
(`epoch/259200`) ranges [6087, 15827], and the courier window (`epoch/86400`) ranges [18262, 47482] —
three pairwise disjoint integer ranges, so the underlying HMAC input can never collide across any two
purposes sharing one group key, independent of truncation length. `MeshFrameCodec.courierTag`/
`candidateCourierTags` wrap the generalized functions the same way `groupHandle` already does for its
own window. New `CryptoUtilsTest` coverage proves separation at a FIXED `truncateLen=16` across all
three windows (not incidentally from differing output lengths), plus day-boundary rotation and
±1-day skew tolerance mirroring the GATT handle's own ±1-window test.

**The seal: `sealCourierBody`/`openCourierBody`, mirroring `sealSosBody`/`openSos` field-for-field.**
Raw sealed bytes only — no envelope, no `Frame.Courier`, no `FRAME_COURIER` wire byte — split from a
would-be `sealCourier` that also frames a wire message the exact way `sealSosBody` is split from
`sealSos`, and for the identical reason (a future storage entity must be handed raw ciphertext, never
a pre-framed message). Content key is the caller's already-derived `CryptoUtils.contentEpochKey
(rootKey, createdAt/1000)` — **no new key derivation needed at all**, since the existing 24h
content-epoch window (decision 39) already matches the courier tag's UTC-day window exactly; this
slice's only genuinely new primitive is the tag itself. Deterministic per-envelope nonce
(`courierNonce`, hash of the envelope id) mirrors `sosNonce`'s reasoning: an envelope id is sealed
exactly once, so re-sealing identical content must reproduce identical ciphertext. `payload:
ByteArray` is opaque to this codec — what a courier actually carries is a later slice's decision; this
only proves arbitrary bytes seal/open correctly. Spec's own "Cap 16 KiB" enforced as
`MAX_COURIER_PAYLOAD_BYTES`, checked at seal time (`require`, unlike SOS's message cap which is only
checked on open — a deliberate stricter choice here since this is new code, not a retrofit of an
established asymmetry) and again at open time (defense against a malicious group member hand-crafting
oversized ciphertext directly, bypassing this codec's own seal function).

**Zero production call sites.** No `Frame.Courier`, no `FRAME_COURIER` byte, no `decode()` branch, no
`VERSION` bump, no `CourierEnvelopeEntity`, no `AppDatabase` migration (stays v10), no `RelayEngine`/
`RelayResponder` wiring, no copy-budget, no handover, no rate-limiting, no bounded pool/tiers — all
named as slices 2-4 in `PLAN-v2.md`'s own P4 entry. `docs/DECISIONS.md`'s and `PLAN-v2.md`'s usual
"Production code touched" list is genuinely just the two crypto files below; nothing else in the app
changed behavior this slice.

13 new tests: 4 in `CryptoUtilsTest` (custom `truncateLen`, three-way window collision proof, UTC-day
stability/rotation, ±1-day candidate skew tolerance) and 9 in `MeshFrameCodecTest` (opaque-without-key/
opens-with-key, tamper detection, deterministic same-id ciphertext, signature + impersonation
detection, no-signing-key null round-trip, oversized-payload rejection at both the exact cap and one
byte over, opens-under-content-key-not-root-key, and a crypto-only candidate-tag resolution test
against several groups — the equivalent of `GroupRepositoryHandleTest`'s coverage before any
DAO-backed resolver exists). 412 tests (up from 399), detekt clean (one new `@Suppress
("LongParameterList")` test helper mirroring `sealSosFixture`'s own), both variants compile/test/
assemble green, no `missing_rules.txt`. Version bumped to v0.7.8-dev, fresh debug APK built and
`aapt`-confirmed (`versionCode='19' versionName='0.7.8-dev'`) before committing. **Production code
touched**: `CryptoUtils.kt` (`truncateLen` param on `rotatingAdvertisementId`/
`candidateAdvertisementIds`, `COURIER_TAG_WINDOW_SECONDS`, `COURIER_TAG_LEN`), `MeshFrameCodec.kt`
(`courierTag`, `candidateCourierTags`, `MAX_COURIER_PAYLOAD_BYTES`, `CourierBody`,
`sealCourierBody`, `openCourierBody`). No app behavior change — this slice is purely additive,
unreachable from any existing code path, so there is nothing to hardware-confirm yet; that starts
once slice 3 wires an actual GATT exchange.

## 42. P4 slice 2 — persisted courier envelope + local creation, no relay wiring

Second slice of P4 (Couriers, `PLAN-v2.md` §4.2), following slice 1's crypto construction (decision
41). Adds genuinely new persisted storage — this app's first, since `OpaqueFrameRelay`'s existing
"carry a frame for a group we hold no key for" mechanism (used for position/presence) is deliberately
in-memory-only with a 3-minute default max age, wrong shape for a 24h-TTL envelope that must survive
a real multi-hour partition crossing (the whole point of a courier) or an app restart. This was the
Plan agent's own conclusion from slice 1's design pass, confirmed here by checking `RelayResponder`'s
`opaqueSos` (SOS's own blind-relay custody): it uses `OpaqueFrameRelay()` with the SAME 3-minute
default, not a longer one — SOS's blind custody was never meant to survive a real partition either,
just a few minutes of two strangers being in range together, unlike what a courier needs to do.

**`CourierEnvelopeEntity` (`data/Entities.kt`) mirrors `SosEntity`'s shape**, including its
ByteArray-safe `equals`/`hashCode` override (Room reconstructs entities from the cursor on every
query, so two byte-for-byte-identical rows with different array instances must still compare equal —
same reasoning `SosEntity`'s own override gives). `groupId`/`senderId` are nullable from day one,
mirroring `EvidenceEntity.groupId`'s own decision-38 precedent — a blind carrier holding an envelope
for a group it has no key for can resolve neither — even though this slice's own
`RelayEngine.createCourierEnvelope` only ever populates both (a device always knows which of its own
groups it's authoring for; the blind-carry case is a later slice). `sealed`/`tag` mirror
`SosEntity.sealed`/`handle` field-for-field, including the "raw bytes only, never a pre-framed
message" discipline decision 37's own real bug taught. `copiesRemaining` (§4.2's "Copy budget 4, cap
8") is stored starting now but inert until a later slice's handover arithmetic actually reads/writes
it — deliberately no default value on the entity field itself, matching `SosEntity.ttl`'s own
precedent (`RelayEngine.DEFAULT_TTL` is passed explicitly at the `createSos` call site, never
duplicated as a field default); `RelayEngine.COURIER_INITIAL_COPY_BUDGET` is the single source of
truth for the starting value.

**`AppDatabase` v10 → v11**, adding `CourierEnvelopeEntity` to the entity list and a new
`CourierEnvelopeDao` (`data/Daos.kt`) kept deliberately minimal for this slice's own scope —
`insert`/`getById`/`deleteForGroup`/`pruneOlderThan` only, no `observeForGroup`/`getRelayable`
equivalent yet, since nothing reads courier envelopes for push/receive until a later slice actually
needs it.

**`RelayEngine.createCourierEnvelope(groupId, payload)` mirrors `createSos` exactly**: resolve the
group's root key, derive the content epoch key, `sealCourierBody` (raw bytes, not a would-be
frame-and-seal call), compute the tag via `courierTag` on the permanent root key (unchanged by
decision 39, same split `groupHandle` already established), store the row. Deliberately does **not**
bump `RelayEngine.epoch`, unlike `createSos`/`createEvidence`/`setNickname` — that signal means
"something new is pushable to a peer," and nothing reads courier envelopes for pushing yet; bumping
it now would be a false signal to `ConnectionAttemptTracker`'s "skip the cooldown, I have something
new" logic, not an oversight.

**Pruning wired into the existing periodic sweep** (`RelayEngine.pruneExpired`, called from
`MeshService`), using its own `COURIER_MAX_AGE_MILLIS` (24h) cutoff — deliberately shorter than
evidence/SOS's 48h `CONTENT_MAX_AGE_MILLIS`, matching §4.2's own "24 h" spec. A new test
(`pruneExpired removes a courier envelope past its own 24h cutoff, independent of the 48h SOS one`)
proves this cutoff is genuinely applied, not just documented — a courier row aged past 24h but still
inside the 48h SOS window must be gone, a fresh one must survive.

**Testing boundary, same as `createSos`/`createEvidence` always had, not a new gap this slice
introduces**: `createCourierEnvelope` itself has no direct test — it calls `GroupRepository.
getGroupKey`, Android Keystore-backed, unavailable under Robolectric (`RelayEngineTest`'s own class
doc already states this exact constraint for `createSos`/`createEvidence`, confirmed still true here
by grep before writing a single test). What's new and testable in this slice is the persistence
layer, not the crypto (already covered in isolation by `MeshFrameCodecTest`/`CryptoUtilsTest`,
decision 41) — new `RelayEngineTest` coverage exercises `CourierEnvelopeDao` directly with hand-built
fixtures (Room round-trip including `ByteArray` fields, `getById` on a missing row, the 24h-cutoff
prune test above, `deleteForGroup` scoping), the same way `ingestSos`'s own tests exercise
`RelayEngine`'s logic without ever touching the Keystore-dependent create path.

**Zero new production call sites reading courier data.** `createCourierEnvelope` exists to be
exercised directly (by a test, or a later slice's caller) with a working, persisted, round-trippable
row — nothing in this slice calls it from anywhere in the running app. GATT push/receive, the
blind-carry path, and the bounded-pool acceptance policy are P4 slice 3; handover arithmetic is
slice 4 — both broken out in `PLAN-v2.md`'s own P4 entry.

416 tests (up from 412), detekt clean, both variants compile/test/assemble green, no
`missing_rules.txt` (no new native/reflection-heavy dependency, so no proguard surprise expected or
found). Version bumped to v0.7.9-dev, fresh debug APK built and `aapt`-confirmed
(`versionCode='20' versionName='0.7.9-dev'`) before committing. **Production code touched**:
`data/Entities.kt` (`CourierEnvelopeEntity`), `data/Daos.kt` (`CourierEnvelopeDao`),
`data/AppDatabase.kt` (`version` 11, entity + DAO registered), `ble/RelayEngine.kt`
(`COURIER_MAX_AGE_MILLIS`, `COURIER_INITIAL_COPY_BUDGET`, `createCourierEnvelope`, `pruneExpired`
now also prunes courier envelopes). No app behavior change reachable from any existing UI/GATT path
— same "nothing to hardware-confirm yet" status as slice 1, unchanged until slice 3.

## 43. P4 slice 3 — GATT wiring: wire type, resolver, member/blind-carry paths, bounded pool with tiers

Third slice of P4 (Couriers, `PLAN-v2.md` §4.2) — the first slice where a courier envelope actually
crosses a GATT connection. Given the size (comparable to decision 38), a Plan agent mapped the
design before any code was written, the same discipline slice 1 used.

**Key structural finding that shaped the whole slice.** Courier envelopes are bounded by a copy
budget (spec: "spray budget 4, cap 8, half-handover on meeting"), not a decrementing hop/TTL the way
SOS/evidence are — `PLAN-v2.md`'s own comparison table lists "TTL flood" and "Spray-and-Wait (n-copy
DTN)" as two separate techniques. `CourierEnvelopeEntity.copiesRemaining` (decision 42) stays inert
until a later slice's handover arithmetic gives it real meaning — which means **this slice has no
bounding mechanism at all for multi-hop blind-carry propagation** if a blind carrier were allowed to
re-offer a held envelope onward. Every other persisted, relayed row in this codebase already has a
real per-hop bound (evidence's ttl-decrement, `OpaqueFrameRelay`'s hop ceiling) before it's built.
**Design consequence: a blind carrier accepts and stores an envelope, single-hop from a member, but
never re-offers it to a further peer.** Multi-hop spray is exactly what `copiesRemaining`'s real
semantics (a later slice) is for — building propagation before the budget exists would be an
unbounded flood wearing a "bounded" name.

**Wire type.** `FRAME_COURIER: Byte = 0x1C` (next unused after `FRAME_WIFI_DIRECT_ACCEPT`'s `0x1B`).
`Frame.Courier(tag, id, createdAt, copiesRemaining, sealed)` — `id` stays cleartext for the same
dedup reason `SosSealed.id` does. `createdAt` is cleartext too, a deliberate difference from
`SosBody.timestamp` (kept inside the seal): a blind carrier storing this as a
`CourierEnvelopeEntity` row needs an honest age for `RelayEngine.pruneExpired`'s 24h cutoff but can't
open the seal to read a hidden one — same tradeoff `PositionSealed.hop`/`SosSealed.hop` already make
for topology distance instead of timing. It also lets the member-path open derive the exact content-
epoch key in one shot (`contentEpochKey(rootKey, createdAt/1000)`) instead of SOS's candidate-key
search, the same single-exact-epoch treatment decision 39 gives evidence-meta/nickname/presence for
the identical reason. `copiesRemaining` travels verbatim, forwarded unchanged — never read, split,
or decremented this slice. `MeshFrameCodec.VERSION` 8 → 9: technically not load-bearing for safety
the way v7's field-shape change was (a genuinely new frame type just hits `decode()`'s own
`else -> null` on an old build, no misparse risk) — bumped anyway, matching this project's own
standing discipline (see v8's own note) of treating every wire-affecting change as one discoverable
signal.

**`GroupRepository.resolveGroupKeyByCourierTag`/`matchCourierTag`** mirror `resolveGroupKeyByHandle`/
`matchHandle` exactly, calling `CryptoUtils` directly rather than `MeshFrameCodec`'s convenience
wrapper (`matchHandle`'s own precedent — `data` never depends on `ble`). One real gotcha a naive
copy-paste would introduce: `candidateAdvertisementIds`' own `truncateLen` defaults to
`ROTATING_ID_LEN` (6, the beacon/GATT-handle length) — `matchCourierTag` must pass
`CryptoUtils.COURIER_TAG_LEN` (16) explicitly or it would silently never match a real courier tag.
Caught before shipping by a dedicated regression test, not just careful reading.

**`RelayResponder.handleCourier`/`ingestOpenedCourier`/`takeCourierCustody`** mirror
`handleSos`/`ingestOpenedSos`/`takeOpaqueSosCustody`'s resolve-then-branch shape, with three
deliberate differences: (1) blind carry stores a real `CourierEnvelopeEntity` row via
`RelayEngine.admitCourierEnvelope`, not `OpaqueFrameRelay` custody — decision 42 already found that
mechanism's 3-minute default max age wrong for a 24h-TTL envelope; (2) no notification-equivalent to
`onSosReceived` — `payload` is still an opaque `ByteArray` with no schema (decision 41), nothing to
render even if a callback fired; (3) no immediate flood-forward on receipt — delivery flows
exclusively through the catalog-filter deficit-push cycle, the same one-shot-per-connection
treatment SOS/evidence-header/nickname *content* already get. A freshly received envelope might
therefore wait for the next reconnect on an already-open link before reaching a third peer — the
same class of gap decision 19 found and fixed for SOS, deliberately left open here since couriers
exist specifically to survive multi-hour partitions, so "might wait for the next connection" costs
little against that baseline.

**Bounded pool with tiers: `CourierPool`, a new pure admission-policy object in `ble/`** (no
DAO/Room access, directly unit-testable, matching `ConnectionAttemptTracker`/`OpaqueFrameRelay`/
`HopTracker`'s own extraction discipline). `CAPACITY = 40`, `OWN_GROUP_RESERVED = 20` — taken
verbatim from `PLAN-v2.md`'s own table entry, the same discipline every other courier constant this
feature has shipped with (no rescaling). The reservation is a **hard floor** on blind-carry capacity
(`capacity - ownReserved`, not `capacity - ownCount`) — what actually guarantees 20 own-group slots
exist even when almost none are in use, matching bitchat's literal "reserved" semantics rather than
soft prioritization. An own-group envelope can grow past 20 by borrowing unused blind-carry
capacity, up to the full 40; **own-group is never hard-rejected**, only in the degenerate all-40-
own-group case does an own-group insert evict a sibling (oldest by `createdAt`, never by
`copiesRemaining` — that field stays the inert one decision 42 established, using it for eviction
priority here would reach into a later slice's semantics early). Blind-carry never hard-rejects
either — LRU-evicts its own oldest, mirroring `OpaqueFrameRelay`'s own `removeEldestEntry` behavior,
just persisted instead of in-memory.

**`RelayEngine.admitCourierEnvelope`** is the single gate both insertion paths (self-authored via
`createCourierEnvelope`, received via `RelayResponder`) now go through, wrapped in `db.withTransaction`
(this codebase's first use of Room's `withTransaction` — `room-ktx` was already a pinned dependency,
just unused until now) to close a real check-then-act race: `MeshGattClient`/`MeshGattServer` can
feed this concurrently from different peer connections, the same class of hazard
`ConnectionAttemptTracker`'s own class doc names for different shared state. Bumps `epoch` on a
genuinely new insert — the un-defer decision 42 flagged: `createCourierEnvelope` previously did NOT
bump it because nothing read courier envelopes for pushing yet, and this slice is exactly the point
that stops being true. `createCourierEnvelope` was updated to route through `admitCourierEnvelope`
instead of a raw DAO insert, a mechanical consequence of that stale precondition, not a new decision.

**Push-on-connect wiring fits entirely inside the existing `CatalogFilter` mechanism** — no new
per-connection step, no new budget constant. `currentCatalogKeys()` gains `heldCourierIds()` (own-
group AND blind, same "held, not relayable" reasoning `heldSosIds`/`heldEvidenceIds` already give —
it's what stops a peer re-pushing us something we're already blind-carrying forever).
`handleCatalogFilter` gains a fourth `partitionByFilter`/`pushUpTo` pair using
`relayableCourierEnvelopes()` (own-group only), folded into the existing per-connection item budget.
This is the concrete enforcement point for the single-hop-only rule above: a device only ever
proactively offers an envelope it holds the key for; a blind-carry row is held and advertised
(stopping redundant re-pushes) but never reaches a third peer through this path.

442 tests (up from 416): new `MeshFrameCodecTest` coverage (encode/decode round trip, cleartext
envelope readable without a key, `copiesRemaining` unsigned-byte coercion, full encode→decode→open
round trip proving the wire and crypto layers compose), a new `GroupRepositoryCourierTagTest.kt`
mirroring `GroupRepositoryHandleTest.kt` (including the dedicated `truncateLen` regression test
above), a new `CourierPoolTest.kt` (plain JVM, exhaustive truth table over every admission branch
including the reservation-is-a-hard-cap property), `RelayEngineTest` additions (`admitCourierEnvelope`
bumps epoch on a new insert / not on a duplicate, pool eviction deletes the right row, `heldCourierIds`
includes both tiers, `relayableCourierEnvelopes` excludes blind), and `RelayResponderTest` additions
(an unresolvable courier envelope stores as blind carry without opening; a blind-carried envelope is
never re-offered via `refreshFramesToPush` — the single most important behavior this slice enforces;
an own-group envelope is both advertised in our own outgoing filter and actually pushed when a peer's
filter lacks it; a peer's filter correctly suppresses one it already has). detekt clean (`LargeClass`
suppress added to `RelayResponder`, `TooManyFunctions` added to `GroupRepository`/`CourierEnvelopeDao`
— both flat growth from one more resolver/DAO-query-set, not code-organization pressure). Both
variants compile/test/assemble green, no `missing_rules.txt`. Version bumped to v0.7.10-dev, fresh
debug APK built and `aapt`-confirmed (`versionCode='21' versionName='0.7.10-dev'`) before committing.
**Production code touched**: `ble/MeshFrameCodec.kt` (`FRAME_COURIER`, `Frame.Courier`,
`encodeCourier`, decode branch, `VERSION` 9), `data/GroupRepository.kt`
(`resolveGroupKeyByCourierTag`/`matchCourierTag`), `ble/CourierPool.kt` (new), `data/Daos.kt`
(`CourierEnvelopeDao`'s pool/push queries), `ble/RelayEngine.kt` (`admitCourierEnvelope`,
`heldCourierIds`, `relayableCourierEnvelopes`, `createCourierEnvelope` now routes through the pool),
`ble/RelayResponder.kt` (`handleCourier`/`ingestOpenedCourier`/`takeCourierCustody`,
`currentCatalogKeys`/`handleCatalogFilter`/`reframeStoredCourier`). **NOT hardware-confirmed** — the
`VERSION` 9 wire addition and every path above are new and untested on real hardware; adds to the
existing next-live-round backlog (37/38/40 already queued).

## 44. P4 slice 4 — handover mechanics: copy-budget split + rate limiting. P4 is now code-complete

Fourth and final slice of P4 (Couriers, `PLAN-v2.md` §4.2). Closes the scope line slice 3 drew
deliberately: `CourierEnvelopeEntity.copiesRemaining` finally gets real semantics, which is what
lets a blind carrier safely pass an envelope on to a *further* peer (genuine multi-hop spray) instead
of only ever holding what arrived.

**`CourierHandover.split(copiesRemaining): Pair<Int, Int>?`** — the classic Spray-and-Wait
arithmetic §4.2 names (Spyropoulos et al.): `floor(N/2)` given away, `ceil(N/2)` kept, `null` below
`MIN_COPIES_TO_SPLIT` (2) since handing away 0 copies isn't a handover. Applied uniformly regardless
of which tier is splitting — an own-group holder and a blind carrier use the identical function;
nothing about the arithmetic itself depends on membership. Pure, no DAO access, directly
unit-tested (conservation property — `keep + give` always equals the input for every N from 2 to 20,
proving no copy is ever minted or lost — plus the boundary cases at 0/1/2 and a negative-input
defensive check). **§4.2's "cap 8" figure is honestly not independently enforced by anything this
slice builds**: it's automatically satisfied by conservation as long as no envelope is ever
re-injected above `RelayEngine.COURIER_INITIAL_COPY_BUDGET` (4), and this slice adds no reinjection
path — flagged explicitly rather than building enforcement machinery for a scenario nothing in this
codebase can trigger yet.

**`CourierHandoverTracker`** — new bounded, injectable-clock rate limiter (mirrors
`ConnectionAttemptTracker`'s own LRU-with-`removeEldestEntry` shape and its "checking an entry
protects it from eviction" precedent) enforcing §4.2's "1 attempt per envelope per 10 min," keyed on
`(envelopeId, peerKey)` where `peerKey` is the peer's *resolved stable identity*
(`PeerIdentityResolver.resolve`), not the raw BLE address — the address rotates roughly every ~15
minutes, which would make a 10-minute rate limit nearly useless keyed on it directly. Falls back
gracefully to the raw address when identity isn't resolved yet, the same "worst case is one
redundant attempt, not a correctness bug" degradation `ingestOpenedSos`'s own `excludeKey`
computation already accepts.

**A real, intended behavior change from slice 3's placeholder verbatim-forward.** Slice 3's
`reframeStoredCourier` forwarded `copiesRemaining` unchanged on every push, own-group or blind — a
deliberate stopgap while the field was still inert. This slice replaces it with
`RelayResponder.pushCouriersWithHandover`: for each handover-eligible envelope, checks the rate
limiter, computes `CourierHandover.split`, **persists the local `keep` value** (`RelayEngine.
updateCourierCopiesRemaining`, a new DAO update query) before pushing a frame carrying `give`, and
records the attempt. Not built on the generic `pushUpTo` helper — that helper's `encode` step is a
pure, synchronous mapper, but a handover needs a suspend, side-effecting sequence per item (rate-
limit check → split → persist → encode/respond), and conflating that into `pushUpTo` would either
lose the persistence step or force every other item category into the same suspend/side-effect shape
for no benefit.

**`RelayEngine.relayableCourierEnvelopes` grows to include blind-carry rows with spare budget.**
Through slice 3 this returned own-group only, by design (nothing bounded further blind propagation).
Now: own-group rows always (regardless of `copiesRemaining` — an own-group holder can still directly
deliver to a resolving peer even down to its last copy, and `pushCouriersWithHandover` itself skips
the actual push if `split` returns null) plus blind-carry rows with at least
`CourierHandover.MIN_COPIES_TO_SPLIT` copies (`CourierEnvelopeDao.getBlindCarryWithBudget`, new). A
blind row already down to its last copy stays held and advertised (stopping redundant re-pushes,
unchanged from slice 3) but is still never offered onward — the "wait" half of spray-and-wait,
naturally falling out of `split`'s own `null` case rather than a separate check.

**Existing slice 3 tests updated to reflect the real, narrower boundary, not reverted.** Two tests
asserted a blanket "blind-carry rows are always excluded from `relayableCourierEnvelopes`" — correct
through slice 3, now imprecise. Replaced with tests proving the actual boundary: a blind row *with*
budget is now included and gets a genuine split-and-push (asserting the pushed frame carries exactly
`floor(4/2)=2` and the stored local row drops to `ceil(4/2)=2`, not left at 4); a blind row *without*
budget (`copiesRemaining=1`) is still excluded, same as before. A new rate-limit test proves a second
`CatalogFilter` round trip to the same peer within the window does not re-split/re-push.

462 tests (up from 442): 8 new in `CourierHandoverTest` (split arithmetic — conservation property,
every boundary case, negative-input defensive check), 6 new in `CourierHandoverTrackerTest` (window
boundary at exactly the rate limit, per-pair scoping, LRU eviction, protect-on-access), `RelayEngineTest`
additions (own-group always included, blind-with-budget now included, blind-without-budget still
excluded), `RelayResponderTest` additions (opaque-relay path never used by couriers regardless of
budget, blind-without-budget never offered, blind-with-budget genuinely split-and-pushed with the
exact halved numbers asserted, rate-limit window enforced end to end). detekt clean, both variants
compile/test/assemble green, no `missing_rules.txt`. Version bumped to v0.7.11-dev, fresh debug APK
built and `aapt`-confirmed (`versionCode='22' versionName='0.7.11-dev'`) before committing.
**Production code touched**: `ble/CourierHandover.kt` (new), `ble/CourierHandoverTracker.kt` (new),
`data/Daos.kt` (`getBlindCarryWithBudget`, `updateCopiesRemaining`), `ble/RelayEngine.kt`
(`relayableCourierEnvelopes` extended, `updateCourierCopiesRemaining`), `ble/RelayResponder.kt`
(`courierHandoverTracker` field, `pushCouriersWithHandover` replacing `reframeStoredCourier`). No
wire-format change — `Frame.Courier`'s shape and `MeshFrameCodec.VERSION` (9) are unchanged from
slice 3, this slice only changes what value the already-existing `copiesRemaining` field carries.
**NOT hardware-confirmed** — adds to the existing next-live-round backlog (37/38/40/43).

**P4 is now fully code-complete** — all four slices (courier tag + seal/open, persisted storage,
GATT wiring, handover mechanics) shipped across decisions 41-44. Per the project's sequencing
directive, next is **P5 (Media)**; the sustained field-test milestone still waits on P5 and P7
(bitchat bridge).

## 45. P5 slice 1 — thumbnail-first, full-res pull-on-demand (sealed thumbnail, corrected mid-slice)

First slice of P5 (Media, `PLAN-v2.md` §4.3), the phase's own explicitly-sequenced-first item
("requires no new transport" — items 2/3, RaptorQ fountain coding and a real bulk pipe, stay out of
scope). A Plan agent traced the existing evidence pipeline first and confirmed the fact the whole
design hinges on: every connected peer — member or blind relay — has always automatically received
the full chunk set for every evidence item, because `RelayResponder.framesToPushOnConnect`
unconditionally sends this device's own manifest for every held item on every connection, and
sending a manifest IS what solicits chunks back. `PLAN-v2.md` §9.2 item 8's own arithmetic (20
circulating 300KB photos = 6MB of ciphertext most blind carriers can never read) is the measured
cost.

**Fix: gate WHICH items get their own manifest sent, nothing about how manifests/chunks work.**
`RelayEngine.fullResRelayable()` (own-authored content, or anything a member explicitly requested
via a new `wantsFullRes` flag) replaces `relayableEvidenceMeta()` as what
`framesToPushOnConnect`'s manifest loop reads. `handleManifest`'s push logic is untouched — it only
ever fires reactively off an incoming manifest, so once a device stops sending its own, nothing
triggers it. The header (`relayableEvidenceMeta`) keeps flooding to everyone exactly as before.

**A real mid-slice correction, not a footnote: the thumbnail shipped cleartext-plus-MAC first, then
was sealed before landing.** `Frame.EvidMeta` gains `thumbnail: ByteArray`, chosen over a new frame
type since the field needs the header's own existing unconditional-flood treatment. The first pass
matched the header's own existing cleartext-plus-MAC discipline — implemented, tested, green. Before
committing, this was flagged to the user directly (following this project's own standing rule on new
passive-exposure surface): a nearby device that merely connects — automatic, no interaction needed —
would see a genuine visual hint (crowd vs. document vs. night scene), a real step up from this
header's existing metadata-only fields (mimeType/size/hash). **User's choice: seal it**, not ship
cleartext, not redesign to avoid flooding it to non-members entirely.

Final design: `MeshFrameCodec.sealThumbnail(contentKey, id, thumbnail)`/`openThumbnail(sealed,
contentKey)`, AES-GCM under the same content-epoch key SOS/position bodies already use, deterministic
per-id nonce (domain-separated from `sosNonce`/`courierNonce` by a label prefix, not just a bare id
hash) mirroring `sosNonce`'s own "sealed exactly once, ever" reasoning. A blind relay still stores
and forwards the opaque bytes (unchanged storage-cost win — the 6MB→~100KB arithmetic is about
carrying *ciphertext of the right size*, not about being able to read it) but can never render a
preview. `evidMacInput` still covers `thumbnail` on top of the seal's own GCM tag — binds the sealed
blob to this specific header, a different substitution attack than the seal's own tamper detection.
`MAX_THUMBNAIL_BYTES = 256` is now the SEALED ceiling; new `MeshFrameCodec.GCM_OVERHEAD_BYTES = 28`
(12-byte nonce + 16-byte tag) is exposed so `EvidenceCapture.compressThumbnail`'s plaintext target
(`256 - 28 = 228`) derives from the same number rather than two call sites agreeing by coincidence.
`MeshFrameCodec.VERSION` 9 → 10 — a genuine field-shape change, load-bearing for safety (an old build
would misread every field after the new one), unlike decision 43's new-byte-type addition.

**UI needs an async decrypt path now, not a synchronous field read.** `RelayEngine.decryptedThumbnail
(evidence)` resolves the group key, derives the single exact content-epoch key (timestamp is
cleartext, no candidate search needed — same treatment decision 39 gives evidence-meta generally),
opens the seal; null for a blind row, empty thumbnail, or decrypt failure. Never persisted decrypted
— cheap enough to decrypt fresh per render, unlike full-res evidence's own on-disk plaintext cache.
`GroupChatScreen`'s `FeedThumbnail` composable calls this via `LaunchedEffect`, one decrypt per row
render. `EvidenceCapture.compressThumbnail` (48px, quality-stepdown loop targeting the plaintext
budget) and `FeedRow`'s three-state interaction (complete / not-yet-requested / pulling) are
otherwise unchanged from the original design — split into `feedRowClickAction`/`fileBodyText`/
`FeedRowHeader`/`FeedThumbnail` to keep `FeedRow` within detekt's limits
(`@file:Suppress("TooManyFunctions")` added to the file — Compose screens naturally decompose into
many small composables).

**Storage**: blind relays stop carrying full-res chunks entirely, by construction —
`fullResRelayable()` excludes every `groupId == null` row, `requestFullResolution` refuses one
outright (mirrors `admitCourierEnvelope`'s early-exit shape), so nothing ever solicits a chunk from
a blind-carrying device. `RelayResponder.pushFullResRequestNow` closes the same already-open-link
gap decision 19 (`floodForwardLocalSos`) closed for SOS — sent to every open link directly, no
fanout/jitter (a manifest is small, cheap, idempotent, unlike a one-shot content event).

482 tests (up from 462): `MeshFrameCodecTest` (thumbnail wire round-trip at empty/near-cap/exact-cap,
decode rejects over-cap, `evidMacInput` sensitivity, plus dedicated `sealThumbnail`/`openThumbnail`
coverage — opaque-without-key/opens-with-key, tamper detection, same-id-twice determinism,
different-id-different-ciphertext, empty-input no-op), `RelayEngineTest` (`fullResRelayable`
exclude/include, `requestFullResolution` refuse-blind/refuse-unknown/bump-epoch,
`decryptedThumbnail`'s reachable-without-Keystore branches: blind row and empty thumbnail both null),
`RelayResponderTest` (`framesToPushOnConnect` sends no manifest unrequested / sends one once
requested / never for a blind row — the core regression). `createEvidence`/`requestFullResolution`/
`decryptedThumbnail`'s key-touching paths stay untestable directly under Robolectric, same
pre-existing constraint `createSos`/`createEvidence`/`createCourierEnvelope` already live with.
detekt clean (`TooManyFunctions` on `EvidenceDao`/`GroupChatScreen.kt`, `LongParameterList` on
`FeedRow`). Both variants compile/test/assemble green, no `missing_rules.txt`. Version bumped to
v0.7.12-dev, fresh debug APK built and `aapt`-confirmed (`versionCode='23' versionName='0.7.12-dev'`)
before committing. **Production code touched**: `ble/MeshFrameCodec.kt` (`MAX_THUMBNAIL_BYTES`,
`GCM_OVERHEAD_BYTES`, `Frame.EvidMeta.thumbnail`, `sealThumbnail`/`openThumbnail`, `encodeEvidMeta`,
decode branch, `evidMacInput`, `VERSION` 10), `data/Entities.kt` (`EvidenceEntity.thumbnail`/
`wantsFullRes`), `data/AppDatabase.kt` (`version` 12), `data/Daos.kt` (`setWantsFullRes`,
`getFullResRelayable`), `ble/RelayEngine.kt` (`createEvidence` seals the thumbnail before storing,
`fullResRelayable`, `requestFullResolution`, `decryptedThumbnail`), `ble/RelayResponder.kt`
(`framesToPushOnConnect`'s manifest loop, `handleEvidMeta`, `evidMetaIsAuthentic`,
`pushFullResRequestNow`), `ble/MeshService.kt` (`sendEvidence` gains `thumbnail`,
`requestFullResolution`, `decryptedThumbnail`), `evidence/EvidenceCapture.kt` (`compressThumbnail`),
`ui/GroupChatScreen.kt` (picker computes both compress+thumbnail, `FeedRow` rewritten, async
`FeedThumbnail`). **NOT hardware-confirmed** — a genuine field-shape `VERSION` bump, adds to the
existing next-live-round backlog (37/38/40/43).

**Explicitly deferred to later P5 slices**: §4.3 item 2 (RaptorQ/fountain coding, replacing
`FRAME_MANIFEST`/the have-bitset/the per-peer deficit computation entirely) and item 3 (a real bulk
pipe — BLE L2CAP CoC, Wi-Fi Aware). This slice's pull-gating decision (*whether* to solicit full
resolution) stays conceptually valid underneath either future transport — only the mechanism for
actually moving symbols/bytes once solicited would change.

## 46. P5 slice 1 of §4.3 item 2 — fountain-code encode/decode primitive, construction only

First slice of §4.3 item 2 (RaptorQ/fountain coding), the phase's own next item per decision 45's
closing note. A Plan agent read the existing chunk/manifest/deficit mechanism first
(`RelayEngine.CHUNK_SIZE`/`chunkBytes`/`maybeReassemble`, `Frame.Manifest`/`FRAME_MANIFEST`,
`MeshProtocol.encodeBitset`/`decodeBitset`, `RelayResponder.handleManifest`'s deficit computation and
session chunk budget) and confirmed the actual load-bearing requirement is the PROPERTY §4.3 states —
"a receiver reconstructs from any k(1+ε) distinct symbols from any combination of sources" — not
literal RFC 6330 conformance.

**Library vs. hand-rolled RFC 6330 vs. a simpler hand-rolled scheme: checked and rejected the first
two.** Two real Android-viable libraries exist and both fail this project's own dependency bar (every
existing dependency in `build.gradle.kts` — Tink, CameraX, zxing, LeakCanary — carries institutional
maintenance): OpenRQ, the only mature RFC 6330 Java implementation, has had no commits since 2017 and
isn't published to Maven Central; the one Kotlin-native alternative found is a single-author,
effectively unpublished project about a year old. Full hand-rolled RFC 6330 (LDPC+HDPC precoding over
GF(256), inactivation decoding) was rejected as disproportionate — thousands of lines, easy to get
subtly wrong, and its whole value proposition (near-zero decoding overhead via precoding) is CDN-scale
efficiency this app doesn't need, since realistic evidence items are ~200 symbols
(`MeshFrameCodec.MAX_EVIDENCE_CHUNKS`'s own doc). Direct precedent: `CatalogFilter.kt` already made
the identical substitution (a plain Bloom filter standing in for bitchat's heavier Golomb-Coded Set)
for the identical reason.

**Built: a systematic random-linear fountain code (`ble/FountainCode.kt`, new file) with incremental
Gaussian elimination over GF(2) for decoding, not belief propagation.** `FountainCode.encoder(data,
symbolSize)` splits input into `k` fixed-size systematic source symbols (esi `0 until k`, zero-padded
like `chunkBytes` pads its last chunk today); `FountainEncoder.symbol(esi)` returns source data
verbatim for `esi < k` or an XOR combination of a deterministically-derived subset of source symbols
for `esi >= k` (repair, unbounded esi space, no per-peer state — mirrors
`framesToPushOnConnect`'s own "no memory of any specific peer" design).
`FountainDecoder.addSymbol(symbol)` folds a symbol into an incrementally-maintained reduced row-
echelon system (`isComplete` is a rank test, `decode()` a plain concatenation once every column has
its own pivot row — see the class's own proof-shaped doc comment for why the invariant the insertion
loop maintains guarantees that). GE was chosen deliberately over the RaptorQ/LT-standard
belief-propagation decoder specifically because GE's correctness never depends on which degrees or
indices a repair symbol was built from — only on the received rows having full rank — making a bug in
the repair-symbol construction a pure efficiency risk (more symbols needed) rather than a correctness
one, the one place this design is deliberately more forgiving than hand-rolled RaptorQ would have
been.

**A real mid-slice correction, caught by measurement, not review.** The first version of
`RepairPlan` (the module deriving a repair symbol's source-index subset from `esi` alone) used the
textbook robust soliton distribution (Luby 2002) — standard for LT codes, but standard specifically
for belief-propagation decoding. Diagnostic tests (not kept in the suite) measured it against this
class's own GE decoder directly: 1.3-2.6x the information-theoretic minimum number of repair symbols
needed across k=20..1000, because most low-degree draws landed entirely inside the region a receiver
already held, contributing nothing. Switched `RepairPlan` to dense random coefficients (each of the k
source indices included independently with ~1/2 probability, drawn directly from the PRNG's raw bits
rather than a degree-then-indices two-step) — standard random-linear-coding territory. Re-measured:
1-2 EXTRA symbols past the true deficit, independent of k. Simpler code too (no floating-point
robust-soliton math, no `StrictMath` cross-device-determinism concern that construction would have
carried). The tradeoff, accepted deliberately: more XOR work per repair symbol (touches ~k/2 source
symbols instead of a handful) in exchange for far less wire bandwidth — the right trade for a
BLE-bottlenecked mesh, where bandwidth, not phone CPU, is the scarce resource.

**Determinism**: repair-symbol construction is seeded from `(k, esi)` alone via a hand-rolled
SplitMix64 PRNG, not `java.util.Random` — same precedent `CatalogFilter.hashIndexes`'s doc already
set (avoid depending on a platform-provided algorithm for anything two independent devices must
derive identically).

**Known cost, not yet hardware-measured**: `FountainDecoder.addSymbol` is O(k) BitSet checks per call
(the "clear this pivot from every other row" step) plus data-dependent XOR work; end to end this is
an O(k²)-ish incremental Gaussian elimination, not RaptorQ's near-linear message passing, and dense
repair symbols add real O(k) XOR cost per symbol on top. At `k` near `MAX_EVIDENCE_CHUNKS` (4096) this
is untested on real phone hardware — added to the existing next-live-round backlog alongside every
other unconfirmed slice. A bounded-time smoke test at k=1000 (well under 10s) is in the regular suite
as a sanity check, not a hardware benchmark.

15 new tests (`FountainCodeTest`, 497 total up from 482): exact round-trip with no loss; round-trip
surviving dropped systematic symbols via repair at k=1/2/5/37/200/4096 (the real
`MAX_EVIDENCE_CHUNKS` ceiling); the literal §4.3 property tested directly — two disjoint simulated
sources (disjoint systematic indices + disjoint repair-esi ranges) assembled into one decoder;
duplicate esi as a harmless no-op; out-of-order/shuffled arrival; small-overhead reliability across 30
random trials; malformed-symbol rejection (wrong size, negative esi); empty input; k=1; non-multiple
`originalLength`; two independent encoder instances for the same k deriving identical repair symbols;
the k=1000 bounded-time smoke test. detekt clean (a `ReturnCount`/`LoopWithTooManyJumpStatements`
pair in `addSymbol` fixed by extracting `tryInsert`/`clearPivotFromOtherRows`, five `MagicNumber`
findings fixed by naming the SplitMix64 shift constants and the seed-packing width/mask). Both
variants compile/test/assemble green (`assembleDebug`/`assembleRelease`, `lintVitalRelease`,
R8-minified), no `missing_rules.txt`. Version bumped to v0.7.13-dev, fresh debug APK built and
`aapt`-confirmed (`versionCode='24' versionName='0.7.13-dev'`). **Zero production call sites** — no
`Frame` subtype, no new `FRAME_*` byte, no `MeshFrameCodec.VERSION` bump, no `AppDatabase` migration,
no `RelayEngine`/`RelayResponder` change; nothing in the running app calls `FountainCode` yet. **Not
hardware-confirmed** (nothing to hardware-confirm yet — no behavior change, same as decision 41's own
slice 1 note).

**Deferred to slice 2 (wiring, not designed here)**: delete `FRAME_MANIFEST`/`Frame.Manifest`/
`encodeManifest` and `MeshProtocol.encodeBitset`/`decodeBitset` outright; replace
`RelayResponder.handleManifest`'s deficit computation with a new minimal `Frame.SymbolRequest`
(`evidenceId`/handle, `stillNeed: Int` — no bitset, no per-peer state, matching what §4.3 itself says
this deletes); decide the session chunk budget's fate (this project's own reading is it's genuinely
dropped, not replaced with a symbol-count equivalent, but that's a real fairness-across-peers
question worth confirming, not assuming); rewire `RelayEngine.createEvidence`/`ingestChunk`/
`maybeReassemble` around `FountainCode.encoder`/`FountainDecoder`, likely keeping one live decoder
per in-flight item across a connection's symbol stream rather than recomputing from all-persisted-rows
per ingest; `MeshFrameCodec.VERSION` bump (11) since a reused `FRAME_EVID_CHUNK`-shaped wire layout
reinterpreting its `Int` field as `esi` instead of `chunkIndex` is a semantic change even if the byte
layout doesn't move; and an explicit decision on `maybeAccelerateOverWifiDirect`'s WFD handoff path,
which currently keys off a positional `deficit: List<Int>` that has no fountain-coding equivalent (a
symbol-count trigger, or leave it inert until §4.3 item 3 removes Wi-Fi Direct outright as already
planned). Full scoping detail — file-by-file, function-by-function — is in the Plan agent's own
design write-up this session started from; not reproduced verbatim here.

## 47. P5 item 2 slice 2 — wiring: deletes FRAME_MANIFEST/have-bitset/deficit computation, wires FountainCode into the live relay path

Slice 2 (wiring) of §4.3 item 2, one clean cutover per a Plan agent's own design pass (comparable
scope to decision 43's GATT-wiring slice — no live compatibility window to protect either, since
nothing is hardware-confirmed yet, so a dual-path additive-then-delete design would have doubled
this slice's own surface for no real benefit). The Plan agent re-verified everything against actual
current source rather than trusting the prior session's summary — confirmed `MeshFrameCodec.VERSION`
was 10 (not a guess), `AppDatabase.version` was 12, and — the one genuinely new finding —
**this project has no real `Migration` objects anywhere**; `AppDatabase.get()` calls
`.fallbackToDestructiveMigration()` unconditionally ("pre-release testing app, nothing worth
preserving across a schema change yet"), so every entity rename/field addition below was free, no
migration code written.

**New wire frames, replacing the retired `FRAME_MANIFEST` (0x10, deleted)/`FRAME_EVID_CHUNK` (0x14,
deleted) — both bytes retired outright, never reused:**
`FRAME_SYMBOL_REQUEST = 0x1D` (`Frame.SymbolRequest(evidenceId, stillNeed: Int)`, no handle/mac,
same as the retired `Manifest` never carried either — `evidenceId` is already cleartext on every
flooded `EvidMeta`) and `FRAME_EVID_SYMBOL = 0x1E` (`Frame.EvidSymbol(evidenceId, esi, data)`, a
genuinely new byte rather than reinterpreting `FRAME_EVID_CHUNK`'s old layout — the version bump this
slice requires anyway makes the "old build misreads it" risk a non-issue either way, so the deciding
factor was avoiding a byte whose retired name/types would permanently mislead future readers about
what it now carries). Neither new frame bound-checks its own numeric field at `decode()` time
(`stillNeed`, `esi`) — unlike the retired `Manifest.totalChunks`, nothing in `decode()` allocates
proportional to either; `RelayResponder`'s existing per-connection budget and `RelayEngine.
ingestSymbol`'s own bound check are where that responsibility actually lives now, mirroring
`Frame.WifiDirectHandoff.deficitCount`'s pre-existing precedent for "not validated at the transport's
keyless parsing layer." `Frame.EvidMeta` gains `contentLength: Int` (exact ciphertext byte length —
`totalChunks` already equals a `FountainCode` `k`, but only bounds a *range* of possible lengths;
`FountainDecoder` needs the exact value to strip the last symbol's zero padding at `decode()` time),
covered by `evidMacInput` for the same tamper-binding reason decision 45 added `thumbnail` there.
`MeshFrameCodec.VERSION` 10 → 11 (two independent load-bearing reasons: the retired/new frame bytes,
and `EvidMeta`'s genuine field-shape change).

**Storage**: `EvidenceChunkEntity`/`EvidenceChunkDao` renamed to `EvidenceSymbolEntity`/
`EvidenceSymbolDao`, `chunkIndex` (bounded `[0, totalChunks)`) renamed to `esi` (unbounded —
systematic or repair); `receivedIndexes`/`receivedCount` dropped (no longer meaningful once
completion is driven by decoder rank, not a positional count). `EvidenceEntity` gains
`contentLength: Int`. `AppDatabase.version` 12 → 13. A real, deliberate design call: **every directly-
received symbol is persisted immediately, regardless of whether it advances this device's own decode
rank** — a partial holder must still be able to usefully relay its partial symbol set to a THIRD
peer, the actual "faster with more carriers instead of slower" value §4.3 item 2 names; a design that
only persisted at completion would silently defeat that promise. `RelayEngine.ingestSymbol`'s return
value reflects DAO-insert-new-storage, not decoder-rank-new — a symbol can be decoder-redundant
(this device already has enough rank) while still being new, storable data worth relaying onward.

**Sender side**: no per-peer state (matches `CatalogFilter`'s own "no memory of any specific peer"
philosophy, quoted directly in `framesToPushOnConnect`'s doc) — but NOT the naive "always start at
esi 0" a fully stateless reading of that philosophy would suggest, which the Plan agent identified as
a genuine liveness bug (a peer that already holds most systematic symbols from a prior connection
would receive the same low esi range again on every reconnect, its `stillNeed` never converging).
Fixed with a single monotonically-increasing esi cursor **per evidence item** (`RelayEngine.
symbolCursors`, `ConcurrentHashMap<String, AtomicInteger>`), shared across every requester and never
reset except on process restart — state keyed on content, not on a peer's identity, so it doesn't
reintroduce `PeerDeliveryTracker`'s old bounded-eviction problem.

**Receiver side**: one live `FountainDecoder` per in-flight item (`RelayEngine.liveDecoders`), lazily
created and rehydrated from every already-persisted row on first touch each process lifetime — the
persisted rows are the source of truth, this map a derived, disposable cache (losing it on restart
costs a rehydrate replay, never correctness). **A real bug caught by this slice's own new tests, not
by review**: the first version fed a newly-ingested symbol into the decoder only via
`getOrCreateDecoder`'s rehydrate-on-first-creation step — correct the FIRST time a decoder is built
for an item, but silently wrong forever after, because a decoder created earlier by an unrelated read
(`symbolDeficit`, called on every connection via `framesToPushOnConnect`, routinely runs BEFORE any
symbol has arrived) stays cached and never re-scans Room. `RelayEngineTest`'s own progression test
(`symbolDeficit` before and after two `ingestSymbol` calls) caught this immediately — rank never
advanced past 0. Fixed by having `maybeCompleteFromSymbol` call `decoder.addSymbol(esi, data)`
explicitly for the just-ingested symbol every time, not relying on rehydration alone;
`FountainDecoder.addSymbol`'s own `seenEsi` dedup makes the redundant call safe when rehydration
already included it. On completion: decrypt, sha256-verify, write `outputFile` (unchanged from the
retired `maybeReassemble`'s own logic), then **collapse storage to the canonical `k` systematic rows**
— the step that makes a device which just finished receiving something a fully-capable re-sharer
through the exact same `symbolsToSend` path own-authored content uses, no separate "receiver-side
serving" logic needed. A `Mutex` (`RelayEngine.decoderMutex`) guards the whole rehydrate-fetch-
addSymbol-persist sequence — `FountainDecoder` has no internal synchronization, and decision 43
already established `MeshGattClient`/`MeshGattServer` can feed `RelayEngine` concurrently from
different peer connections.

**Session budget: kept, renamed, NOT deleted — a deliberate re-reading of `PLAN-v2.md`'s own literal
text, confirmed rather than assumed.** §4.3 item 2's own line says fountain coding "deletes... the
session chunk budget," and Part 9's "what v2 deletes" list repeats it — but that passage describes
the full Tier X target architecture (§5.1), which depends on §4.3 item 3's dedicated bulk pipe
(BLE L2CAP CoC / Wi-Fi Aware) running OFF the shared GATT link entirely. This slice is not that:
until item 3 lands, symbols still share the exact same connection carrying SOS/catalog-sync/
presence/position traffic, and the budget's real purpose ("keeps one busy item from starving the
rotation through other peers," `maxChunksPerSession`'s own pre-existing doc) doesn't evaporate just
because chunks became symbols. Renamed `maxChunksPerSession`/`sessionBudget`/`consumeBudget` →
`maxSymbolsPerSession`/`symbolSessionBudget`/`consumeSymbolBudget` (same value, 150), now also the
sole backstop against a hostile/inflated `stillNeed` (no longer bound-checked at decode time, so this
budget is what actually limits the cost downstream in `handleSymbolRequest`).

**WiFi Direct: left inert, one call site removed, the other 5 `transport/wifidirect/` files
mechanically adapted to compile, not redesigned.** `maybeAccelerateOverWifiDirect` (RelayResponder's
own wrapper) is deleted outright — its one call site, inside the retired `handleManifest`, no longer
exists, and there is no fountain-coding equivalent of "the peer is missing exactly these N indices"
to hand `WifiDirectHandoffCoordinator.maybeProposeHandoff`, whose positional `deficit: List<Int>` API
would need real redesign to accept a symbol count instead — throwaway work against `PLAN-v2.md` §4.3
item 3's own already-planned wholesale removal of Wi-Fi Direct. `isWfdCapable` (the read side, its
only caller) removed alongside it; `markWfdCapable` stays (still records a peer's own capability
announcement, now write-only on this device's side). **Found late, not anticipated by the design
pass**: `WifiDirectAccelerator`/`WifiDirectTransport`/`WifiDirectHandoffCoordinator` all reference
`EvidenceChunkEntity`/`Frame.EvidChunk`/`encodeChunk`/`RelayEngine.ingestChunk`/`chunksByIndexes`
directly in their own signatures and bodies (`WifiDirectAccelerator.sendChunks`/`receiveChunks`
literally call `MeshFrameCodec.encodeChunk`) — deleting those types broke compilation regardless of
whether the subsystem is reachable. Mechanically adapted (type/name substitution only — `List<
EvidenceChunkEntity>` → `List<Frame.EvidSymbol>`, `ingestChunk` → `ingestSymbol`, new small
`RelayEngine.symbolsByEsi` mirroring the retired `chunksByIndexes`' exact shape) rather than left
broken; the actual handoff/deficit-selection logic inside `WifiDirectHandoffCoordinator` untouched
and still effectively dead (nothing calls `maybeProposeHandoff` anymore, so its accept-side handlers
are now asymmetrically unreachable from this device's own propose direction, per the class's own
updated doc).

**Testing**: `RelayEngineChunkBytesTest.kt` deleted outright (`chunkBytes` no longer exists).
`MeshFrameCodecTest`/`RelayResponderTest`/the two WFD test files updated for the new types (manifest/
bitset-specific tests removed, `SymbolRequest`/`EvidSymbol` round-trip and hostile-input coverage
added, including a dedicated test proving `decode` does NOT reject a negative/huge `stillNeed` —
documenting the deliberate decode-time-vs-downstream split so a future session doesn't "fix" it by
accident). New coverage, not a port — the retired mechanism had none of this: `RelayEngineTest`
gained `ingestSymbol`/`symbolDeficit`/`symbolsToSend`/`decodeRank` tests (esi-bound rejection, dedup,
progression, a complete-item cursor-advancement test, and a decoder-rehydration-across-a-fresh-
`RelayEngine`-instance test proving restart-survival) — all deliberately staying below full rank
(`evidenceFixture`'s `totalChunks = 3`, tests ingest at most 2 distinct esi) to avoid
`maybeCompleteFromSymbol`'s `repo.getGroupKey` call, the same pre-existing Keystore/Robolectric
constraint this file already documents for `createSos`/`createEvidence`/`decryptedThumbnail` — a real
completion+reassembly test needs hardware or an instrumented test, not covered here. New
`EvidenceSymbolDaoTest.kt` (mirrors `PeerKeyDaoTest.kt`'s shape) covers the storage layer directly.
`GroupChatScreen`'s "receiving file: X / Y" progress display moved from the retired `EvidenceChunkDao.
receivedCount` to a new `RelayEngine.decodeRank`/`MeshService.decodeRank` passthrough — decoder rank
is the more meaningful "progress toward decodable" number than a raw stored-row count, since a device
can hold more rows than its rank once some turn out redundant.

511 tests (up from 497, 14 new: `RelayEngineTest` +11, `EvidenceSymbolDaoTest` +8 minus the 5 deleted
`RelayEngineChunkBytesTest`, `MeshFrameCodecTest` net +2 after removing 4 manifest-specific tests and
adding 6). detekt clean (`UnusedPrivateMember` on `maybeAccelerateOverWifiDirect`/`isWfdCapable` after
their call sites were removed — both deleted rather than suppressed, matching this project's own
preference for deleting genuinely dead code over leaving inert wrappers; a few `MaxLineLength` wraps).
Both variants compile/test/assemble green (`assembleDebug`/`assembleRelease`, `lintVitalRelease`,
R8-minified), no `missing_rules.txt`. Version bumped to v0.7.14-dev, fresh debug APK built and
`aapt`-confirmed (`versionCode='25' versionName='0.7.14-dev'`). **NOT hardware-confirmed** — a real
wire-format break (`VERSION` 11) on top of decision 46's own already-unconfirmed primitive; adds to
the existing next-live-round backlog alongside every other unconfirmed slice.

**What's left for P5 item 2 to be "done"**: nothing — item 2 (fountain coding) is now fully wired,
end to end, deleting the old mechanism outright rather than running both in parallel. Next per
`PLAN-v2.md` §4.3's own stated order is item 3 (a real bulk pipe — BLE L2CAP CoC / Wi-Fi Aware,
replacing GATT's 400-byte-write-per-round-trip ceiling and finally removing Wi-Fi Direct outright,
closing the loop on this decision's own "left inert" WFD work above).

## 48. P5 item 3 — BLE L2CAP CoC bulk pipe, additive (Wi-Fi Direct removal deferred to decision 49)

First half of §4.3 item 3 ("a real bulk pipe"), continuing the same autonomous session that shipped
decisions 46-47, per the user's explicit instruction to complete item 3 now and do a device-test
round before P7. A Plan agent designed it first, re-verifying everything against actual current
source (confirmed `minSdk`/`targetSdk`/`compileSdk` = 26/34/34, `MeshFrameCodec.VERSION` = 11,
`RelayEngine.CHUNK_SIZE` = 400, and the exact Wi-Fi Direct wiring surface across 5 `transport/
wifidirect/` files, `ui/WifiDirectSettings.kt`, `ui/HomeScreen.kt`, `MeshFrameCodec.kt`,
`RelayResponder.kt`, `MeshService.kt`, and the manifest).

**The minSdk-vs-API-29 gap is real, not theoretical.** `BluetoothDevice.createInsecureL2capChannel`/
`BluetoothAdapter.listenUsingInsecureL2capChannel` need API 29; this project's own `minSdk` is 26.
Devices on 26-28 can never use this path — GATT's existing 400-byte chunking is therefore not merely
"the universal fallback" in name, it is the ONLY path on three OS versions this app still targets,
and stays load-bearing indefinitely, not legacy code on its way out. Every entry point into the new
code is `Build.VERSION.SDK_INT`-gated; a pre-29 device simply never advertises a PSM and
`RelayResponder.handleSymbolRequest` always uses its GATT `respond` fallback, unchanged from
decision 47's own behavior.

**No initiator/responder role restriction, unlike the retired WFD accelerator — a deliberate
divergence from that precedent, with real reasoning, not an oversight.** `WifiP2pManager.connect()`
performed stateful group formation; two sides racing to call it could corrupt shared P2P state,
which is why only WFD's initiator was ever allowed to dial. An L2CAP CoC `connect()` is an ordinary
socket connect over a BLE ACL link that ALREADY exists (both devices are already GATT-connected) —
there is no shared group state to corrupt. The worst case of both sides racing to open a channel is
a harmless duplicate, not corrupted topology, so `L2capBulkTransport.openFor` only needs a
per-address `Mutex` to collapse concurrent attempts into one, not prevent them by role.

**New `ble/BulkChannel.kt`**: the `BulkChannel` interface (`send`/`close`) `RelayResponder` codes
against, decoupled from the concrete transport — same role the retired `WifiDirectTransport` played
for WFD. `BulkFraming` (length-prefixed stream I/O, pure `java.io`, zero Android dependency) is the
actual framing mechanism — **deliberately NOT `MeshFrameCodec.padGattFrame`/`unpadGattFrame`**,
despite the Plan agent's own initial suggestion to reuse it: that wrapper was built for
MESSAGE-ORIENTED GATT writes, where the platform callback already delivers one bounded blob per
call; a raw byte stream has no such boundary at all. Re-reading the actual existing precedent for a
byte-stream transport in this codebase — the retired `WifiDirectAccelerator.sendChunks`/
`receiveChunks` — showed it never used `padGattFrame` either, just a plain 4-byte length prefix
around the raw encoded frame. Reused that exact, already-established pattern instead, resolving the
Plan agent's own flagged-open "should the bulk pipe pad for disguise or skip padding for throughput"
question by simply following what this codebase already does for stream transports — genuinely
unresolved for GATT's own future padding tuning, but not ambiguous here.

**New `ble/L2capBulkTransport.kt`**: owns the device-level listening socket (`startListening`, called
once from `MeshGattServer.start`, mirroring the GATT server's own "one object for the whole radio
session" shape), the dial-out path (`openFor`, `RelayResponder`'s `bulkChannelOpener` collaborator),
and per-peer channel/device bookkeeping (`noteDevice`/`closeFor`, called from both GATT roles'
connect/disconnect handling, mirroring `resetSessionBudget`'s own "called by both roles" precedent).
**NOT device-tested** — a Robolectric spike this session (not kept as a permanent test) confirmed
`listenUsingInsecureL2capChannel()` does not throw under Robolectric but returns a non-functional
stub server socket (`psm = -1`) — no real loopback simulation exists for this API, unlike the
`java.net.Socket` loopback pairs the retired `WifiDirectAcceleratorSocketTest` used for WFD's own
plain-TCP sockets. `BulkFraming` (pure stream framing) is what's actually unit-tested; connection
establishment itself is compile-verified only, same category `WifiP2pManager` mechanics already
lived in for WFD.

**`RelayResponder` changes**: two new optional collaborators (`bulkChannelOpener`,
`localL2capPsm`), a new `peerBulkChannel` map (cleared in `resetSessionBudget`, same lifecycle as
the retired `peerWfdCapable`), `handleL2capCap` (opens a channel via the collaborator, no role
check — see above), and `handleSymbolRequest` now prefers an open bulk channel over GATT's own
`respond` — deliberately WITHOUT the `delay(15)` GATT pacing between pushes, since a real socket
with credit-based flow control makes that artificial pacing pure overhead defeating the pipe's own
throughput purpose. A bulk-send failure mid-run does not fall back to GATT for the remaining
symbols in that same call (the channel is presumed dead and dropped); whatever didn't send is
simply requested again on the peer's next `SymbolRequest` — the same "worst case is wasted
bandwidth, never incorrectness" framing `FountainCode`'s own class doc already gives the primitive
riding on top of this pipe.

**New wire frame**: `FRAME_L2CAP_CAP` (0x1F), `Frame.L2capCap(psm: Int)` — device-level, no MAC,
same "carries no sensitive claim" reasoning `Frame.WifiDirectCap` already established (a forged/
stale PSM just makes `connect()` throw, never a forged transfer). `MeshFrameCodec.VERSION` 11 → 12
— bundles this addition with retiring `FRAME_WIFI_DIRECT_CAP`/`HANDOFF`/`ACCEPT` (0x19/0x1A/0x1B,
actually deleted in decision 49 below, not this one) under one bump rather than two, since pure
removal isn't independently load-bearing on its own (an old build's `decode()` already safely
no-ops on any unrecognized byte) but is bundled here for discoverability, matching decision 47's own
v11 precedent of combining a retirement and an addition under one version number.

**Scope stays narrow, deliberately**: only `FRAME_SYMBOL_REQUEST`/`FRAME_EVID_SYMBOL` traffic ever
moves over the bulk pipe. SOS, position, presence, catalog filter, nickname, and courier frames all
stay on GATT — matches §4.3's own problem statement (media specifically, not the low-frequency
control plane GATT already handles adequately) and keeps this already-novel, hardware-unconfirmed
slice's blast radius bounded.

19 new tests: `BulkFramingTest` (round-trip, multiple frames back-to-back on one stream, clean EOF,
hostile oversized/negative length prefix, a real `PipedInputStream`/`PipedOutputStream` pair to
prove this isn't just an in-memory-buffer artifact), `MeshFrameCodecTest`'s `FRAME_L2CAP_CAP`
round-trip, and `RelayResponderTest`'s `handleSymbolRequest`/`handleL2capCap` coverage via a fake
`BulkChannel` (prefers the bulk channel over `respond` once open, drops it and stops — no mid-run
GATT fallback — on a send failure, and confirms `handleL2capCap` no-ops safely when no
`bulkChannelOpener` is wired, the shape every pre-existing `RelayResponder` construction site still
has). detekt clean (`ReturnCount` on `L2capBulkTransport.openFor` fixed by collapsing to a single
guard + one final `if`/`else` return rather than three separate early returns — mirrors
`FountainDecoder`'s own `addSymbol`/`tryInsert` split precedent for the identical reason;
`SwallowedException` fixed by renaming genuinely-ignored catch variables to `_`, matching
`WifiDirectAccelerator`'s own established convention; `LongParameterList` suppressed on
`MeshGattClient`'s now-7-param constructor, same shape `MeshGattServer`/`RelayResponder` already
carry). Both variants compile/test/assemble green (`assembleDebug`/`assembleRelease`,
`lintVitalRelease`, R8-minified), no `missing_rules.txt`. Version bumped to v0.7.15-dev, fresh debug
APK built and `aapt`-confirmed (`versionCode='26' versionName='0.7.15-dev'`). **NOT
hardware-confirmed** — a real `VERSION` 12 wire break, adds to the existing next-live-round backlog;
this slice specifically also needs the actual connection-establishment mechanics checked on real
hardware, since nothing in the automated suite touches them (see `L2capBulkTransport`'s own class
doc).

**Wi-Fi Direct itself is untouched by this decision** — still fully wired, still functionally inert
per decision 47 (nothing calls `maybeAccelerateOverWifiDirect`, which no longer exists). Removing it
outright is decision 49, landed as a separate commit/version bump in the same session, deliberately
not bundled into this one so each stays independently revertible (mirrors decisions 43/44's own
GATT-wiring-vs-handover-mechanics split — two genuinely separate units of behavior change that
happened to ship back to back, not one change artificially split in two).

## 49. P5 item 3 — Wi-Fi Direct removed outright

Second half of §4.3 item 3, same session as decision 48. Wi-Fi Direct's propose-a-handoff direction
was already functionally dead as of decision 47 (nothing called `maybeAccelerateOverWifiDirect`,
which no longer existed); decision 48 shipped BLE L2CAP CoC as its replacement bulk pipe. This
decision removes the subsystem outright, per `PLAN-v2.md` §9.3 item 2's own already-stated plan
("remove at P5 when [the replacement] arrives, not before").

**Deleted outright**: all 5 files under `transport/wifidirect/` (`WifiDirectAccelerator.kt`,
`WifiDirectCapabilities.kt`, `WifiDirectHandoffCoordinator.kt`, `WifiDirectTransport.kt`,
`WifiDirectTuning.kt`), `ui/WifiDirectSettings.kt`, and the two WFD-specific test files
(`WifiDirectAcceleratorSocketTest.kt`, `WifiDirectHandoffCoordinatorTest.kt`).

**`MeshFrameCodec.kt`**: `FRAME_WIFI_DIRECT_CAP`/`HANDOFF`/`ACCEPT` (0x19/0x1A/0x1B) join the
existing "never reuse these bytes" retirement block (alongside 0x10/0x14); `Frame.WifiDirectCap`/
`WifiDirectHandoff`/`WifiDirectAccept` and their `encode*` functions and `decode()` branches
deleted. `wifiDirectHandoffMacInput`/`wifiDirectAcceptMacInput` deleted too — the latter had been
the canonical `@Suppress("LongParameterList")` reference comment for ~8 unrelated functions
throughout this file (`sealSosBody`, `encodePosition`, etc.); moved that canonical reasoning to
`sealSosBody` (the next survivor in file order) and repointed every other reference to it, rather
than leaving them dangling. No `VERSION` bump — this decision's own removal isn't independently
load-bearing (an old build's `decode()` already safely no-ops on any unrecognized byte, and the
bytes were already retired-not-reused as of decision 48's own v12 bump, which anticipated this
exact removal).

**Wiring removed**: `RelayResponder`'s `wifiDirectCoordinator` constructor param (and its import),
`peerWfdCapable`/`markWfdCapable`, the CAP-announcement block in `framesToPushOnConnect`,
`handleWifiDirectCap`/`Handoff`/`Accept`, the three `handleIncoming` dispatch branches, and
`MAX_TRACKED_WFD_PEERS`/`WFD_PEER_MAP_INITIAL_CAPACITY`/`WFD_PEER_MAP_LOAD_FACTOR`.
`RelayEngine.symbolsByEsi` (existed only for `WifiDirectHandoffCoordinator`'s own positional-index
handoff path, per decision 47's own note) deleted alongside its only caller. `MeshService`'s
`wifiDirectAccelerator` field, its construction, the `wifiDirectCoordinator` local, and both
`abortCurrent()` call sites (`setMeshActive(false)`/`onDestroy`) removed — `l2capTransport.
closeAll()`, already added in decision 48, was already covering the equivalent "close radio
resources on teardown" role for the replacement transport at both sites.

**UI**: `HomeScreen.kt`'s `WifiDirectRow`/`handleWifiDirectToggle` and their call site deleted — the
opt-in switch had no replacement to wire in its place; L2CAP CoC (decision 48) has no user-facing
toggle at all, it activates automatically whenever both peers' builds support it.

**Manifest**: `ACCESS_WIFI_STATE`/`CHANGE_WIFI_STATE`/`NEARBY_WIFI_DEVICES` and the `wifi.direct`
`<uses-feature>` all removed. `NEARBY_WIFI_DEVICES` deliberately NOT kept in anticipation of the
still-unimplemented Wi-Fi Aware slice §4.3 item 3 also names — that slice should check its own
actual permission requirements against current Play policy when it's real, not inherit whatever WFD
happened to need. BLE L2CAP CoC itself needs no Wi-Fi permission at all.

505 tests (down from 522 — decision 48's own count after its 11 new tests — two whole WFD test
files deleted, `WifiDirectAcceleratorSocketTest` 6 and `WifiDirectHandoffCoordinatorTest` 8, plus 3
retired WFD frame round-trip tests inside `MeshFrameCodecTest`; decision 48's own 11 new tests are
unaffected). detekt clean on the first pass — no new suppressions needed, removing code and
permissions rather than adding them. Both variants compile/test/assemble green
(`assembleDebug`/`assembleRelease`, `lintVitalRelease`, R8-minified), no `missing_rules.txt`.
Version bumped to v0.7.16-dev, fresh debug APK built and `aapt`-confirmed (`versionCode='27'
versionName='0.7.16-dev'`). No wire-format change, so nothing new to hardware-confirm from this
decision specifically — it rides on decision 48's own already-unconfirmed `VERSION` 12 bump.

**§4.3 item 3 status: BLE L2CAP CoC done (decision 48), Wi-Fi Direct removed (this decision), Wi-Fi
Aware itself not started — deferred to a later slice** (materially more novel: new radio, an
unverified "no system dialog" disguise claim that specifically needs real hardware per decision
48's own honest assessment, and no local/Robolectric test story for the actual data-path mechanics
the way L2CAP CoC at least partially has via `BulkFraming`). P5 (Media) is otherwise now fully
code-complete across all three items. Per the user's own explicit instruction this session: next is
a device-test round (covering the accumulated backlog since P3, plus this session's own
`VERSION` 11/12 wire breaks and the first-ever L2CAP CoC connection-establishment check), then P7
(bitchat bridge) — the last phase before the project's sustained field-test milestone.

## 50. Diagnostics logging widened ahead of the device-test round — no behavior change

Prompted directly by the user before greenlighting the device-test round decision 49 set up: the
exportable `DiagnosticsLog` (see its own class doc — debug-only, no positions/message bodies/keys,
bounded 512KB rotating file, shared via the existing `DiagnosticsExportRow`/`shareDiagnostics`
`ACTION_SEND` chooser in `HomeScreen.kt`, which already reaches Drive/email/etc. with no cable) had
NOT kept pace with this session's own changes. Two concrete gaps found by auditing every `Log.w`/
`Log.e` call site under `ble/` against what `DiagnosticsLog.event` actually mirrors:

1. **The entire new L2CAP CoC path (decision 48) wrote zero exportable diagnostics.** The single
   riskiest, newest, hardware-unverified code this session shipped — `listenUsingInsecureL2capChannel`/
   `createInsecureL2capChannel` connection establishment has no local/Robolectric coverage at all (see
   decision 48's own Robolectric-stub finding) — was invisible outside a `logcat` cable session. A
   silent L2CAP failure on-device would have been indistinguishable from "GATT fallback is just what
   happened," with no exported evidence either way.
2. **`RelayResponder.handleIncoming`'s own top-level `catch (e: Exception)` — the catch-all wrapping
   *every* frame type's processing (SOS, position, evidence, presence, nickname, courier, catalog
   filter, L2CAP cap, symbol request) — only logged to `logcat`.** Any exception this session's
   changes introduced anywhere in that dispatch would never appear in an exported log.

**What changed, no wire/schema/behavior change anywhere — purely additive logging:**

- `L2capBulkTransport.kt`: new `DiagnosticsLog.event("l2cap", ...)` calls at every state transition
  that previously only had a `Log.w` (or nothing) — `startListening` success (with PSM) and failure,
  outbound `connectAndWrap` success/timeout/failure, inbound `acceptLoop` accept, and
  `RealBulkChannel.receiveLoop`'s close. `closeAll`'s routine teardown catch deliberately left
  `Log.w`-only — not a diagnosable failure mode, just socket-close noise.
- `RelayResponder.kt`: `handleSymbolRequest` now logs which path a symbol push actually took
  (`l2cap` vs `gatt`) and any bulk mid-run send failure; `handleL2capCap` logs whether a peer's
  advertised PSM actually turned into an open channel; the `handleIncoming` catch-all now also
  writes `DiagnosticsLog.event("error", ...)` with the frame type and exception class, closing gap 2
  above; every remaining bare `Log.w` "dropping" path (SOS/courier/evidence/position/presence/
  nickname auth or signature failures, oversized catalog filter) now has a matching
  `DiagnosticsLog.event("reject", ...)` mirror — extending the dual-logging convention a few of
  these paths (e.g. the broadcast-tier presence-signature check) already had, to the ones that
  didn't.
- `RelayEngine.kt`: the evidence hash-mismatch path (`decodeAndPersist`) now also logs to
  `DiagnosticsLog` under tag `error`, with a truncated evidence id — a real fountain-coding
  correctness signal (decision 46/47 changed this whole reassembly path this session) that was
  previously `logcat`-only. The unrelated file-sweep-failure catch left as-is (routine housekeeping,
  not something this test round is trying to diagnose).
- **Message-level send/receive tracing for delay and hop analysis**, per the user's explicit ask:
  `handleSos`'s existing `"NEW sos"` receive-side log now includes a truncated `frame.id` (a
  per-message UUID, not a person identifier — deliberately not subject to the class doc's
  sender/group-id exclusions) alongside the hop count it already logged, so exported logs from
  separate phones can be joined on "the same message" to compute actual origin-to-receipt delay per
  hop. `floodForwardSos` — the single function both a freshly-authored local message
  (`floodForwardLocalSos`) and every relayed forward already funnel through — now logs on every
  call: a `BLOCKED` event with reason (`ttl exhausted` / `no open links`) on either early return, and
  otherwise the message id, hop, actual sent/attempted target count, and jitter delay applied. This
  is the "blocks" half of what the user asked for — previously neither block condition, nor a
  successful send, produced any exported evidence at all. `pushFullResRequestNow` (the full-res pull
  request path, previously entirely silent) now logs the evidence id, symbols still needed, and link
  count it went out on.

**Deliberately not touched**: `BeaconRadio.kt`'s advertise/scan start/stop failures and
`MeshGattServer.kt`'s soft-connection-cap warning — these are Tier B/connection-layer conditions
already covered by this project's existing hardware-confirmed rounds (decisions 21/22/30 all found
and fixed real bugs in exactly this area via `logcat` auditing, not a gap in what's exported), and
widening every single `Log.w` in the codebase indiscriminately risks flooding the 512KB rotating
cap with routine noise during a real multi-hour session, burying the new, actually-unverified
signal (L2CAP, fountain-coding reassembly, forward blocks) this decision exists to surface. Scope
was deliberately: everything touched or left unverified by this session's own changes (decisions
45-49), plus the one pre-existing catch-all gap (`handleIncoming`) that would have silently
swallowed evidence of a regression in any of them.

505 tests (unchanged — pure logging, no new test surface). detekt clean on the first pass. Both
variants compile/test/assemble green (`assembleDebug`/`assembleRelease`, `lintVitalRelease`,
R8-minified), no `missing_rules.txt`. Version bumped to v0.7.17-dev, fresh debug APK built and
`aapt`-confirmed (`versionCode='28' versionName='0.7.17-dev'`). This is the build going out for the
device-test round.

## 51. P7 planning pass — bitchat bridge design, plus three §9.3 open items resolved (no code)

Pure planning/research, no code written — per the user's own explicit sequencing (device test
before P7), this is design-only, done so P7 can start immediately once the device-test round
clears, not blocked on a fresh design pass at that point.

**Three stale/open items in `PLAN-v2.md` §9.3, resolved this pass:**

- **Item 3 (stable local identity) was actually already stale-open, not genuinely open** — traced
  to decision 15/P0b, which already shipped exactly this (`GroupRepository.ensureSenderIdentity` +
  `PeerIdentityResolver`, keyed on the per-group Ed25519 pubkey). Marked resolved, mirroring the
  same kind of doc-drift decision 49's own commit already fixed for item 2 (Wi-Fi Direct).
- **Item 1 (blind-relay budget cap, still fully unbuilt — unbounded today).** Asked the user: leave
  unbounded for now, revisit once the device-test round (and later P7's own bitchat-mesh injection,
  a second relay surface) produces real numbers. **Decision: leave unbounded, not blocking P7.**
- **Item 4 (48h content vs 24h courier envelope lifetime convergence).** Asked the user.
  **Decision: keep deliberately separate — locked in as final, not a placeholder.** Couriers stay
  short-lived/urgent; content stays 48h as a genuinely different kind of data.
- **New item found this pass (added to §9.3 as item 6): L2CAP bulk-pipe traffic isn't size-padded**,
  unlike every other frame type since decision 40 — `BulkFraming`'s raw 4-byte length prefix bypasses
  the GATT transport choke point `padGattFrame`/`unpadGattFrame` lives at, by construction (see
  `BulkChannel.kt`'s own doc). Asked the user. **Decision: accept as a known gap for now** — a bulk
  transfer is already visible as "a transfer is happening" regardless of padding, and L2CAP itself
  hasn't been hardware-confirmed yet; revisit once the pipe is proven to work at all.

Also asked what Android versions the test phones run, since L2CAP CoC needs API 29+ and phones
below that floor will silently only ever exercise the GATT fallback during the device-test round —
**answer: Android 12-13 and 14+, both above the floor**, so the upcoming round can actually exercise
the L2CAP path on real hardware, not just GATT.

**P7 design, grounded in a research pass against bitchat's actual current source** (not just Part
2's summary table, which was about a week and a half old and — per this pass's findings — undersold
how developed bitchat's codebase now is: real signature verification on several packet types, a
`NoiseRateLimiter`, `VouchAttestation` web-of-trust, an in-progress peer-ID-rotation effort):

- **UUIDs confirmed current**: service `F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C`, characteristic
  `A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D` — Part 2's original table was right.
- **Chosen injection vehicle: bitchat's `groupMessage` packet type (`0x25`)**, not `noiseEncrypted`
  as Part 2 might have implied — `groupMessage` is broadcast-addressed and, per this pass's reading
  of bitchat's own relay-scheduling code, forwarded **unconditionally** by relaying nodes: no group
  recognition, no signature, no real Noise session needed to be carried. The only hard structural
  requirement found is a 120s timestamp-skew ingress guard, trivially satisfied with real device
  time. This is a materially better fit than trying to forge a session-addressed `noiseEncrypted`
  packet, and it mirrors our own blind-relay pillar almost exactly (a relay that carries opaque
  bytes for a scheme it can't read).
- **Free multi-hop design insight**: route a bitchat-received message through the SAME existing
  ingestion pipeline (`RelayResponder.handleSos`'s dedup → hop-track → `floodForwardSos`) any GATT-
  received frame already uses, with the bitchat mesh standing in for "arrival link." P1's existing
  flood-forward then automatically re-floods it onto the receiving device's own open BLE links —
  bridging two separate physical clusters of 20.07 users through ordinary bitchat traffic in
  between, with zero new relay logic. Real implementation detail left open: how "arrived via
  bitchat" maps onto the `peerAddress`/`excludeKey` identity hop-tracking and dedup currently
  expect from a live GATT connection — a design question for the implementation slice itself.
- **Hard dependency, explicitly not skippable: a live spike against a real, current bitchat build**
  before any of this becomes production code. Everything above is from reading bitchat's own source
  this session, not from testing against a running instance — in the research agent's own words,
  "a strong lead, not a proven-safe conclusion." Whether bitchat's newer trust/rate-limiting layers
  quietly deprioritise traffic from a `senderID` they've never seen is genuinely unconfirmed until
  tested against a real device. First P7 implementation task: confirm a forged `groupMessage`
  packet is actually relayed by a real, unmodified bitchat install.
- Standing constraints from Part 3 unchanged: off by default, clearly labelled (advertising
  bitchat's own service UUID is a public "this device runs bitchat" signature — a real problem
  where bitchat itself is restricted); second advertise+scan session's battery/radio cost is real
  and unmeasured, needs its own hardware round once built.

No wire/schema/code change — `PLAN-v2.md`'s Part 7 P7 section rewritten from a 4-line stub into the
above, §9.3 updated with the three resolutions plus the new item 6. Nothing to test/build/version-
bump; this decision is pure documentation. Not pushed.

## 52. Hardware round (3 phones, group delete mid-session) + 4 §9.3 follow-ups + L2CAP padding shipped

**The hardware round itself, first since decision 49/50's v0.7.17-dev build.** Setup: group created
by relay + eg1, eg3 added later. Relay then deleted the group mid-session while eg3's phone
independently crashed/restarted (hardware issue, unrelated to the app) and had to rejoin via eg1's
QR code. Confirmed from the three exported `DiagnosticsLog` files (cross-referenced by message id,
the tracing capability decision 50 built specifically for this):

- **Blind-relay pillar confirmed working on real hardware for the first time this concretely**: once
  relay deleted the group, its entire remaining log (~26 minutes) shows nothing but
  `carrying opaque presence/position/sos (not a member)` — it kept physically forwarding eg1↔eg3
  traffic it could no longer decrypt. This is why the two edge phones stayed in contact after
  deletion.
- **L2CAP CoC confirmed working on real hardware for the first time** (decision 48 shipped it
  compile-verified only) — both edge phones completed real bulk symbol transfers over L2CAP
  (`l2cap channel ready`, `sending N symbol(s)...via l2cap`), and one real failure was captured
  cleanly by decision 50's own widened logging (`l2cap send failed mid-run, dropped channel:
  ...Connection reset by peer`) — the diagnostics work done immediately before this round paid off
  directly.
- **Multi-second-to-two-minute delays on messages routed through the now-deleted-group relay
  phone, traced to a real, previously-undocumented mechanism**: `RelayResponder`'s blind/opaque
  custody paths (`takeOpaqueSosCustody` etc.) never call `floodForwardSos` — only a group member's
  own traffic gets P1's immediate forward. A blind carrier only offers what it holds via
  `framesToPushOnConnect` at its *next* fresh connection with a peer, and P3 deliberately keeps
  links open for long stretches, so that wait can be real. Confirmed by timestamp correlation across
  all three exported logs (17–113s delays, all through the blind-relay hop). Not a bug — an inherent
  consequence of today's design, not previously written down anywhere.
- **Presence-reject bursts explained, not a clock-sync problem**: the *receiver's* skew check already
  scales with hop count (`PRESENCE_MAX_SKEW_MS` 120s + `PRESENCE_PER_HOP_SLACK_MS` 45s/hop), but the
  *relay's own* `opaquePresence` custody window is a flat 120s, not hop-scaled — combined with the
  connection-cycle delay above, some relayed heartbeats arrive just past what the receiver will still
  accept.
- **Radar staleness already has a graceful fade** (decision 33) — 30s fade-start, dimming to 20%
  opacity by the dot's own max-age budget (180s base + 45s/hop). Not a gap; explains the "still on
  radar for a while" observation as expected, not a bug.
- Two hop-count values seen "at the same time" for the same peer, differing between observers:
  confirmed as expected — P1's fanout sends the same message down multiple redundant paths, and raw
  log lines show the identical peer's position arriving 3x within milliseconds at different hop
  counts, repeatedly, on the same phone. Same category as decision 22's prior finding.

**Four follow-up decisions from the user, asked fresh with this round's findings as context (not a
re-ask of stale info):**

1. **Blind-relay budget cap (§9.3 item 1)**: still unbounded, no radio contention observed in a real
   26-minute blind-relay session — explained by the connection-cycle throttling found above, which
   is itself a natural cap on how much radio time blind relay can consume. **Decision: fold into
   P7's own implementation** rather than a standalone slice — P7 is the next thing touching
   blind-relay-adjacent code (bitchat-mesh injection).
2. **Content lifetime (§9.3 item 4)**: re-raised a real follow-up question — does content need
   clamping to its group's own remaining expiry, so it can't outlive a group that expires sooner
   than 24-48h out? Checked against the actual code (`GroupRepository.expireGroups`,
   `MeshService.startPruning`) before answering: **already handled** — `expireGroups()` runs at
   every service start AND every 30 minutes while active, and `dismantleGroup` deletes a group's
   content immediately, not on the independent 24h/48h cadence. Content can outlive its own group by
   at most ~30 minutes (the sweep interval), not by anything close to the TTL window. No code
   change needed; this was a real question worth checking, and the code already does the right
   thing. The 24h/48h split itself stays locked as-is (priority-based, per the user's own framing).
3. **L2CAP bulk-pipe padding gap (§9.3 item 6)**: the stated trigger condition ("revisit once L2CAP
   is proven to work") was met by this round. **Decision: add it now.** Shipped this same commit —
   see below.
4. **New finding, blind-relay speed (§9.3 item 7, new)**: whether to also give blind custody
   immediate-forward treatment. **Decision: decide once P7 is actually being built**, not now — P7's
   own design (decision 51) already avoids inheriting this, since bitchat-bridged content a device
   can decrypt is ingested via the normal member path, which already gets immediate forward.

**L2CAP bulk-pipe padding, shipped this commit.** `L2capBulkTransport.kt`'s `RealBulkChannel.send`
now wraps each frame with `MeshFrameCodec.padGattFrame` before handing it to `BulkFraming.writeFrame`;
`receiveLoop` unwraps with `unpadGattFrame` after `BulkFraming.readFrame` returns a complete blob.
Safe reuse of the existing GATT padding despite `BulkChannel.kt`'s own doc originally ruling it out —
that objection was about a raw byte stream having no frame boundary at all, which `BulkFraming`'s own
length-prefix framing already solves independently; by the time padding is applied, a complete
in-memory frame already exists, same precondition `padGattFrame` needs. One detekt fix needed
(`LoopWithTooManyJumpStatements` — the receive loop's `break`-on-read-failure and what would have
been a second jump for the unpad check collapsed into a single `?: break` via `?.let`). No new
tests — same "connection establishment is compile-verified only" limitation decision 48 already
documented; this wiring can't be exercised without a real `BluetoothSocket`.

505 tests (unchanged — pure wiring, no new test surface). detekt clean after the one fix above. Both
variants green (`assembleDebug`/`assembleRelease`, `lintVitalRelease`), no `missing_rules.txt`.
Version bumped to v0.7.18-dev (versionCode 29), fresh debug APK built and `aapt`-confirmed. This is
the build that should carry padded bulk-pipe traffic into the next hardware round. Not pushed.

## 53. Scoping pass (no code): senderId is global, not per-group — a real privacy gap in P0b

Found this session, from the user directly asking whether the fallback display id shown for a
message sender ("default device id") was a security concern. It is, and worse than a display-label
issue.

**The finding.** `senderId` — used everywhere (`SosEntity`/`EvidenceEntity`/`NicknameEntity`,
`Frame.Presence`/`Frame.Position`'s equivalent, hop-tracking, `PeerIdentityResolver`'s stable key)
— is `GroupRepository.deviceId`, a single random UUID generated once per app install and reused
identically across every group a device joins. `PeerIdentityResolver`'s own class doc already
stated this plainly, in passing, without flagging it as a problem: "a random-per-install id...
global across a device's groups, and already sent in cleartext on presence heartbeats." Confirmed
by reading `encodePresenceFrame`/`encodePosition` directly: `senderId` is written into the
CLEARTEXT frame envelope (needed so a non-member relay can hop-track/dedup without a key) — meaning
any passive BLE scanner in range, group member or not, can read and track this exact ID with zero
decryption.

**Why this matters, precisely.** §5.2 (`PLAN-v2.md`) and decision 15 (P0b) both describe the design
goal as "keys on the per-group Ed25519 pubkey... scoped per-group so it cannot correlate a device
across groups." The per-group Ed25519 SIGNING key genuinely is scoped correctly
(`ensureSenderIdentity` generates one per (device, group)). But `senderId` — the value it travels
alongside, used for display, hop-tracking, and hop/hop pinning — is not; it's the flat global
`deviceId`. Two real consequences: cross-group correlation by any member of two overlapping groups
(comparing the same `senderId` in both), and passive tracking by non-members entirely, since it's
broadcast in the clear regardless of group membership.

**Corrects something said earlier in this same session**: resolving `PLAN-v2.md` §9.3 item 3, I
described the per-group identity work as already fully closing the "cannot correlate a device
across groups" goal. That was wrong — I'd verified the signing key's scope but not `senderId`'s own
value, and conflated the two. Caught this turn by actually reading `GroupRepository.deviceId`'s
definition and `encodePresenceFrame`'s wire encoding before answering the user's question, rather
than reasoning from memory of the earlier (correct, but incomplete) P0b review.

**The user's own first proposed fix (a single global, once-set nickname instead of per-group) was
weighed and rejected**: nicknames are a cosmetic display label, unrelated to the actual leaking
field, and a global nickname would make cross-group correlation *easier* for human members (compare
names directly, no decryption needed) without touching the cleartext broadcast problem at all.

**Scoped fix (not yet implemented, this decision is scoping only):** new
`GroupRepository.senderIdFor(groupId): String`, derived from the existing per-group Ed25519 public
key (`sha256Hex(publicKey).take(16)`) — no new key generation or storage, purely computed. Keeps
the actual property `PeerIdentityResolver`/`HopTracker` need (stability across BLE address rotation
within a group) while finally matching what §5.2 always specified. 12 known call sites across
`RelayEngine.kt`/`RelayResponder.kt`/`BeaconRadio.kt` need `repo.deviceId` replaced with
`repo.senderIdFor(groupId)` — full list in `PLAN-v2.md`'s P0b-correction write-up.
`BeaconRadio.advertiseJitterMs`'s own `deviceId` use is explicitly NOT in scope — a purely local,
never-transmitted radio-timing offset, no correlation risk.

**No `MeshFrameCodec.VERSION` bump needed** — `senderId`'s wire byte layout doesn't change, only
its value, same category as decision 39.

**Real, one-time transition cost, stated honestly**: every existing group membership's `senderId`
changes on upgrade, so existing peers see what looks like a brand-new sender once — hop-tracking
resets, a previously-set nickname stops resolving until re-broadcast. Same class of harmless
discontinuity as a fresh reinstall/rejoin, which this session's own hardware round (decision 52)
already exercised without incident (eg3's mid-test rejoin).

No code changed this decision — scoping only, per the user's own "scope it" request, ahead of an
explicit go-ahead to implement. `PLAN-v2.md`'s P0b section carries the full write-up.

## 54. senderId de-globalized — implements decision 53's scoped fix

New `GroupRepository.senderIdFor(groupId): String`, derived from the per-group Ed25519 public key
`ensureSenderIdentity` already generates and persists: `sha256Hex(publicKey).take(16)` (16 hex
chars, 64 bits — comfortably collision-resistant at this app's realistic group sizes). No new key
material, no new storage — purely a computed value over what already exists. `require`s a keypair
already exists for the group rather than silently falling back to something wrong; every real
join/create path already establishes one via `ensureSenderIdentity` first, so a miss here would be
a real caller bug worth surfacing loudly.

**All 12 identified call sites updated**, `repo.deviceId` → `repo.senderIdFor(groupId)`:
- `RelayEngine.kt` (5): `createSos`, `createEvidence`, `setNickname`, `myNickname`,
  `createCourierEnvelope` — all had `groupId` already in scope.
- `RelayResponder.kt` (5): presence encode (`presenceAndPositionFrames`), position encode
  (`positionFramesToPush`), position-relay self-exclusion (`selectPositionsToRelay` call), and two
  self-detection checks (`ingestOpenedPosition`, `handlePresence`) — `body.senderId != repo.deviceId`
  / `frame.senderId != repo.deviceId` became `!= repo.senderIdFor(groupId)`.
- `BeaconRadio.kt` (3): Tier B position encode (`refreshBroadcastTierPositionIfDue`), Tier B
  position-relay self-exclusion (`relayedPositionFrameForBroadcastTier`), Tier B self-broadcast
  filter (`ingestBroadcastTierPosition`).

**Deliberately NOT touched**: `BeaconRadio.advertiseJitterMs`'s own `repo.deviceId` use — a purely
local, never-transmitted per-device offset that spreads advertise-restart timing jitter across
phones to avoid a synchronized radio-restart stampede. No correlation risk; scoping it per-group
would be pointless churn on something that never leaves the device.

**Doc corrections alongside the code change**: `PeerIdentityResolver`'s own class doc (which
previously stated the "random-per-install id... global across a device's groups" fact in passing,
without flagging it as a problem — this is what let decision 15/P0b build on top of it uncritically)
now describes `senderId` accurately as per-(device, group), pointing at `senderIdFor` and this
decision. One stale `RelayResponder.kt` comment ("stable, global per device") corrected to "stable
per (device, group)".

**No `MeshFrameCodec.VERSION` bump** — confirmed by inspection: `senderId`'s wire byte layout (a
length-prefixed string field in `Frame.Presence`/`Frame.PositionSealed`'s body) is unchanged, only
the string's value differs. Same category as decision 39's "semantic change, not byte-layout
change."

505 tests (unchanged — this touches authoring/self-detection paths already covered by existing
`RelayEngine`/`RelayResponder`/`BeaconRadio` tests, no new test surface needed since the fix is a
value-derivation change, not new branching logic). detekt clean after one `MaxLineLength` fix
(BeaconRadio.kt's self-broadcast-filter comment split onto its own line once the call got longer).
Both variants green (`assembleDebug`/`assembleRelease`, `lintVitalRelease`), no `missing_rules.txt`.
Version bumped to v0.7.19-dev (versionCode 30), fresh debug APK built and `aapt`-confirmed.

**Real, one-time transition cost on upgrade, as scoped in decision 53**: every device's `senderId`
changes for every group it's already in the moment it installs this build. Existing peers will see
what looks like a brand-new sender once per group — hop-tracking/route-ownership resets, and a
previously-set nickname (keyed on the old `senderId`) stops resolving until re-broadcast, which
should self-heal within one connection cycle (`framesToPushOnConnect`/`refreshFramesToPush` both
push current nicknames unconditionally on their own cadence, independent of `senderId`'s value).
**NOT hardware-confirmed** — this is a real wire-semantics change even without a `VERSION` bump, and
the self-healing claim above should be watched for on the next hardware round, alongside confirming
peer/hop-count discontinuity on upgrade is as harmless in practice as decision 52's own rejoin
observation suggests it should be.
