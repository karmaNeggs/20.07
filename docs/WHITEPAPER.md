# 20.07 — architecture notes

This is not a feature tour (see [`README.md`](../README.md) for that) and not a security audit
(see README's Security Model / Known Limitations for what this design does *not* protect
against). It's a short record of how the system is put together, and specifically of the handful
of pieces that took real engineering to get right rather than being a standard choice applied in
the standard way. Where something here is a known technique borrowed from elsewhere, that's
said plainly — most of what follows is applying an existing idea correctly to this problem, not
inventing one.

## Shape of the system

Three layers, each independently swappable in principle:

- **Radio** (`ble/BeaconRadio.kt`, `MeshGattServer.kt`, `MeshGattClient.kt`) — connectionless
  rotating-ID beacons for discovery, GATT for actual data transfer. Two roles (server: peers push
  to us; client: we push to peers) share one `RelayResponder` so they can't drift into different
  behavior.
- **Protocol** (`ble/MeshFrameCodec.kt`, `MeshProtocol.kt`, `RelayEngine.kt`) — binary wire frames,
  flood-relay with dedup, chunked/reassembled evidence transfer. Deliberately keyless where
  possible (`decode()` never touches a group key) so a blind relay — a phone carrying traffic for
  a group it isn't in — can do its job without ever being able to read it.
- **State** (`ble/HopTracker.kt`, `PositionTracker.kt`, Room entities) — in-memory distance-vector
  hop tracking and live position, both intentionally non-persistent; Room only for what's meant
  to survive a restart (groups, SOS/evidence content, nicknames).

Every group is symmetric-key, random, and out-of-band (a QR code/link), with no server anywhere
in the design — see README for why.

## What's actually non-obvious here

### The radio-touch invariant, and what it cost to learn

`BeaconRadio`'s core rule — advertising and scanning are only ever stopped/restarted when the
thing being transmitted has *actually changed*, never on a fixed timer — sounds obvious written
down. It wasn't arrived at that way. An earlier version re-asserted the BLE advertiser on every
loop tick (~every 700–900ms) regardless of whether the payload had changed; that pushed real
hardware into total, symmetric discovery failure on *both* test phones at once, a known category
of chipset instability under rapid stop/start cycling. The fix — compare the candidate payload
against what's currently on the air, touch the radio only on a real difference — cut a stable
single-group beacon down to one radio restart roughly every 60 seconds (the rotating-ID window),
*less* churn than the design before any of this started. Scanning follows the identical rule: one
`startScan()` per power tier, left running, with `ScanSettings.scanMode` as the only duty-cycle
lever — an earlier version that start/stopped scanning every few seconds ran into Android's
undocumented ~5-calls-per-30-seconds throttle, producing a real asymmetric "device A sees B, B
doesn't see A" bug. Both failures were real, both were only found by testing on physical phones,
and the fix in both cases was the same principle, not two different patches.

### Advertise-restart jitter, solving a problem the privacy design itself creates

The rotating beacon ID has to flip on a *shared* wall-clock boundary — every phone needs to
compute the same value at the same moment, or discovery breaks. That requirement has a
side-effect nobody asked for: at real crowd density, every phone's radio restart lands in the
same sub-second window every minute, all at once — a synchronized stop/start burst across
hundreds of radios simultaneously, not one phone's problem. The fix is a stable, per-device offset
derived from `sha256(deviceId)` (0–2000ms), applied before the restart — it changes *when* within
that second the radio touches, never *whether* it does, so it can't reopen the class of bug the
invariant above exists to prevent. This is a different problem from the packet-relay jitter used
by other BLE mesh apps (e.g. bitchat, 10–220ms to let duplicate-suppression win a race) — bitchat
doesn't rotate its on-air identifier at all, so it never has a synchronized-restart problem to
begin with. Solving a problem created by our own privacy choice, not reusing someone else's
jitter for a different purpose.

### One serialized queue per GATT connection

Android's `BluetoothGatt` allows exactly one outstanding operation per connection, of *any* kind —
not "one write," one operation, full stop. `GattOperationQueue` exists because an earlier version
fired the CCCD subscription write and then, with no completion handler at all, immediately started
writing data frames on the same connection. The result was a real, reproducible asymmetry: the
peer you connected to (the one you could see) never reliably received anything from you, while
their notifications back to you worked fine, since those weren't racing anything on your side.
This exact bug — descriptor write racing a data write on a connection with no serialization — is
one of the most common latent defects in hand-rolled Android BLE code; it's usually invisible
until two-device testing under real timing, which is exactly how it was found here.

### A connection-attempt timeout for a failure mode the platform doesn't document

`connectGatt()` can simply never call back — no success, no failure, nothing — most commonly when
the peer goes out of range mid-attempt or Bluetooth itself toggles during a pending connection.
Without an explicit timeout, that peer's address stays marked "connecting" forever and is never
retried. `ConnectionAttemptTracker` adds a 15-second timeout keyed off *whether any callback at
all* has fired (not whether it succeeded), so a genuinely slow-but-working connection isn't
force-closed, only a truly stuck one. This one fix resolved three previously-separate-looking
field reports at once ("breaks after a handful of messages," "breaks if Bluetooth is toggled off
and on," "a far-away connection doesn't come back") — they were the same bug wearing different
symptoms.

### Set reconciliation via Bloom filter, not per-peer memory

Early designs remembered, per specific peer address, which items that peer had already been sent.
That's correct at small scale but needs a bounded, evictable cache — and once a peer's entry is
evicted, it silently reverts to "resend them everything." `CatalogFilter` replaces this with a
Bloom filter of what a device currently holds, rebuilt fresh (and re-seeded) every connection: a
peer's filter says what they probably already have, and the sender pushes only what the filter
says they're missing. The correctness argument is what makes this safe to use here: a Bloom filter
can produce a false positive ("probably present" for something that isn't) but never a false
negative, and a false positive here only ever means *this connection* skips sending an item that
would otherwise have been sent — the item stays in the sender's own relayable set and gets a
fresh, independent chance on the very next reconnect, against a differently-salted filter. Nothing
is ever silently lost, and there's no per-peer state to evict or forget. This mirrors the shape of
[bitchat](https://github.com/permissionlesstech/bitchat)'s Golomb-Coded-Set gossip sync (see its
`WHITEPAPER.md` §6.3) for the same class of problem — a plain Bloom filter was used here instead
of literal Golomb-Rice coding as a deliberate simplicity/risk tradeoff for a first pass, not
because it's a novel idea; the design was revised toward this shape specifically after comparing
against bitchat's approach mid-project.

### A deterministic nonce, for the one frame type that needed one

AES-GCM's usual random 96-bit nonce is safe for a huge number of encryptions under one key, but
position frames are the one place in this app that repeatedly re-encrypts under a *single,
never-rotated* group key, potentially for days. A busy multi-day group is the one realistic
traffic pattern here that could approach the birthday bound on nonce collisions — and GCM nonce
reuse is catastrophic (it recovers the XOR of two plaintexts and breaks forgery resistance
outright). Position frames instead use a nonce built to never repeat: a 4-byte prefix derived from
the sender's device ID (stable across restarts, so a restart can't replay an old prefix against an
old counter), the message's own timestamp, and an in-process counter to disambiguate same-second
sends. This is the one crypto call in the codebase that isn't "use the library's default and move
on" — it exists because this specific frame type has a usage pattern the default doesn't cover
well.

### Refusing to plot a dot it can't stand behind

`placePeerOnRadar` computes a peer's bearing and distance from two GPS fixes, and returns nothing
at all when the combined reported accuracy of both fixes is too poor to trust — rather than
plotting a confident-looking dot that might be tens to hundreds of metres wrong. The threshold
isn't just "reject obvious garbage": live testing showed many phones deliberately *widen* their
reported GPS accuracy while stationary (to reduce continuous satellite tracking and save battery),
then tighten it back up the instant motion is detected — which, at a naive threshold, made a
peer's dot flicker away while standing genuinely still side-by-side, then reappear on the next
step. The threshold now sits high enough to absorb that pattern. The underlying decision — show
nothing rather than show something false — is applied consistently (radar dots, ring-scale
labels, staleness fade) rather than left to whichever screen happened to be written first, since
this logic used to be copy-pasted into three places before being pulled into one function.

### A decoy identity library, not a decoy

The disguised-launcher feature doesn't ship one fixed alternate identity ("this app pretends to be
called Notes"); it randomly picks one from a small library, per install for the always-on
background notification and freshly on every toggle for the launcher icon. A single fixed decoy
is itself a stable, greppable signature — "this mesh app always shows up disguised as Notes" is
just as identifying as not disguising it at all, once one installation is known. Spreading the
disguise across several plausible identities means there's no single fingerprint to search for
across different phones running the same app.

### RFC 6206 Trickle, adapted for a supplementary radio channel

The optional BT5 Coded-PHY long-range beacon channel uses a simplified Trickle timer
(`TrickleTimer.kt`) to decide when a device should stay quiet because enough neighbors are already
covering a group on that channel, versus when it should transmit at full rate because coverage
looks thin. The real property this buys: redundant long-range traffic scales down with local
density instead of scaling up with crowd size the way a fixed per-device timer would. This is a
known algorithm (RFC 6206), simplified (no "inconsistency resets the interval" branch, since this
timer's one input has no natural "consistent version" to compare against) and applied to a genuine
sub-problem this project has, not a novel algorithm.

## What this document doesn't cover

Deliberately excluded: the crypto/authentication model as a whole (README's Security Model owns
that, including what it doesn't protect against), every fixed bug (the changelog owns that), and
anything still marked not-device-tested in README's Known Limitations — those are flagged there as
unverified, not claimed here as working engineering.
