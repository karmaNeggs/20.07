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
