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

