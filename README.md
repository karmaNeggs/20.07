# 20.07

A phone-to-phone Bluetooth mesh app for finding your group, navigating toward them, calling for
help, and sharing evidence — when cellular networks are jammed, shut down, overloaded, or not
trusted. No servers, no cellular/Wi-Fi internet dependency, no account.

Built for a specific, recurring shape of problem: a group that needs to stay together, and a
network that can't help them do it.

## Why this exists

Cellular networks assume normal conditions — towers up, signal available, infrastructure standing
between you and the people you're trying to reach. That assumption breaks down in a specific,
recurring set of situations:

- **Natural disasters and blackouts** — towers down or overloaded, power out, a group that was
  together suddenly isn't.
- **Stampedes and crowd crush** — coordination matters most at the exact moment a crowded cell
  sector is most saturated.
- **Crowd-control and unrest situations** — networks jammed, throttled, or not trusted by the
  people who need them.

In every case the same three needs recur: find your group, call for help, and get evidence of
what happened onto more than one device before anything can happen to it. 20.07 is built
specifically for that recurring shape of problem, phone-to-phone, with no infrastructure
required — see "What it does" below for the three pieces that solve it.

## Design choices

A few decisions worth calling out, because they're deliberate rather than default:

- **No accounts, ever.** One QR code creates a group — no creator, no owner, no phone number tied
  to it. Removing one person doesn't expose or kill the group.
- **Every phone relays for every group, whether or not it's a member.** Content is opaque
  ciphertext to anyone without the key — a phone carrying your traffic can pass it along but can't
  read it.
- **The BLE identifier itself rotates every 60 seconds**, so passive scanning sees a changing,
  meaningless value rather than a stable device fingerprint.
- **GPS lives in memory only**, never touches disk, gone the instant the app is force-quit.
- **Evidence photos relay across the mesh as they're captured** — copies exist on other members'
  phones before any single device stops being available.

See Security model below for exactly what this app's own design does *not* protect against.

See [`docs/WHITEPAPER.md`](docs/WHITEPAPER.md) for a short architecture writeup — not a feature
tour, just the handful of pieces (radio-touch discipline, GATT operation queueing, the Bloom-filter
catalog sync, and a few others) that took real engineering to get right, and an honest note on
which parts are borrowed techniques rather than original ones. [`docs/DECISIONS.md`](docs/DECISIONS.md)
records the specific designs this project tried and walked back after live-device testing, and why.

## What it does (3 features, on purpose — nothing else)

1. **Navigate** — a forward-up radar: your group members show up as dots at their real bearing
   and distance from you, computed from GPS coordinates shared over the mesh (never stored —
   kept in memory for about two minutes, then gone), then rotated by your phone's compass so
   "up" means "in front of you right now" — walk toward the dot, don't read a map. If GPS isn't
   available (no fix, indoors, permission denied), it falls back to hop-count hot/cold — no
   direction, but still tells you if you're getting closer or farther, using only Bluetooth.
2. **Send evidence** — pick a photo from your gallery and it propagates through the mesh to your
   group, phone-to-phone, however long that takes.
3. **SOS** — a high-priority broadcast to your group that also lets other members navigate
   toward you.

## Screens

- **Home** — a combined radar showing every group member across all your groups at once, each
  group in its own color, dots pulsing faster the closer they are, fading as a position ages past
  ~30 seconds so a stale fix doesn't look as live as a fresh one. Below it, a row of four square
  toggles (SOS, Power saver, Disguise, Offline), then your group list (tap to open), and a `+` to
  add a group. A light/dark theme toggle sits top-right (defaults to dark; the choice persists
  per install).
- **Add group** — join with a code/link someone shared, or create a new group (just a name —
  generates a random key and a shareable code/link, nothing to type on the other end that could
  get mistyped).
- **General SOS** — every group pre-selected, uncheck whichever this doesn't concern, send.
- **Group chat** — one chronological feed mixing messages and shared files, a mini radar up top
  (tap to expand to the full Navigate screen), an input for a quick in-group-only SOS or file,
  and delete-group.
- **Navigate** — the full-screen radar for one group.

## How it works

**Groups are a random key, shared as a code — not a typed passphrase, not an account.**
Creating a group generates a random group id and a random 256-bit key on the spot, packed into
one shareable code (and a `mesh2007://join?c=...` link for one-tap opening). Whoever has the
exact code is in the exact same group — no two people separately typing "the same" secret and
hoping it matches. The code gets shared "over whatever channel you already trust" — in person,
an existing chat app, read aloud, or scanned as a QR code — same trust model either way, just a
more reliable payload than a typed passphrase.

**Groups are ephemeral by design and expiry is baked into the code itself.** 20.07 is built for
ad hoc coordination, not standing chat rooms — a typical group lives 2-3 days, and 6 months is a
hard ceiling (picked at creation from a lifetime menu: 12h/48h-default/7d/30d/6mo). The expiry
timestamp is encoded directly into the shareable join code, not tracked separately per phone, so
every member — whoever created the group, whoever joined an hour later — agrees on the exact same
expiry moment. Once it passes, every member's own app dismantles the group on its own: the key,
the messages, the evidence, all of it, gone, with nothing to "clean up" on a server because there
isn't one.

**Discovery uses rotating, anonymous beacon IDs.** Instead of broadcasting anything that
identifies your group, your phone advertises `HMAC(group_key, current_60s_time_window)` — a
value that changes every minute and is meaningless to anyone without the group key. Other
members of your group compute the same value independently and recognize it; everyone else just
sees a random-looking string. This is the same principle Bluetooth's own privacy mode (and the
old COVID exposure-notification apps) use for rotating device identifiers.

**Every phone relays for every group, whether or not it's a member.** Encrypted content is
opaque bytes — a phone carrying data for a group it doesn't belong to can't read it, only pass
it along. This means propagation speed depends on the whole local population of app users, not
just your handful of group members: strangers' phones act as blind couriers.

**Connections stay open, and new content reaches every other open link immediately.** Earlier
versions connected to a peer just long enough to exchange a "here's the diff" bitset and sync up,
then disconnected — content only ever moved at the moment two phones happened to connect, which
got slower the more crowded the area got. Links now stay open (minutes, not seconds) for as long
as they're useful, held against real signal-diversity rather than whoever connected first. The
moment something new exists — you send an SOS, one relays to you — it floods immediately across
every other link currently open, no reconnect required. This is also what makes "a passing phone
carries content between two separated members" actually work: if your phone is briefly connected
to both of them at once, content from one reaches the other the instant it arrives. The Bloom-
filter diff-bitset exchange still happens too, once, right when a link first opens — that's what
catches a newly-connected peer up on everything that existed *before* the link opened, so later
contacts exchange a few hundred bytes of bookkeeping instead of resending anything already
delivered.
This immediate-forward path currently covers SOS only (position and presence instead refresh every
15-30s on every open link, so a radar dot or hop count never goes stale for a link's whole
lifetime; evidence headers and nicknames still move only via the one-shot connect-time sync,
a known, smaller gap — see Known Limitations).

**The radar is forward-up: GPS for bearing/distance, compass for rotation.** Each phone shares
its current GPS fix over the mesh (short hop range, latest-fix-wins, kept in memory only — see
Permissions below). Every other phone computes true bearing and distance to each group member,
then rotates that by its own compass heading so "up" on screen always means "in front of you
right now," not true north. The compass reading is smoothed and the app surfaces a
low-confidence warning when the sensor itself reports poor accuracy, since magnetometers drift
and get thrown off by exactly the kind of things common in dense crowds (metal barricades,
vehicles, structures) — that's a real, known weak point, shown rather than hidden. When GPS
isn't available it falls back to a hop-count "hot/cold" indicator: no direction, just whether
the number of relay-hops to your nearest group member is rising or falling as you move. That
part depends on nothing but Bluetooth, so it still works when GPS doesn't. (Hop-count-to-presence
currently only reflects "a group member is in direct Bluetooth range or not," not an extending
multi-hop gradient — see Known Limitations.)

**SOS works the same way, with its own hop-count.** Sending SOS starts a distance-vector field
seeded at the sender; anyone in the group can then see how many hops away that SOS originated
and close in on it the hot/cold way, same as group presence.

**Evidence photos are compressed, encrypted, chunked, then flood-relayed.** BLE bandwidth is
low, so a picked photo gets downscaled and compressed first — "slow but delivered" beats
"never arrives." The whole compressed file is encrypted once (AES-GCM), *then* split into
small chunks, so no partial subset of chunks is ever decryptable — only a device holding the
group key can reconstruct and decrypt the full file once all chunks converge on it. Chunks
propagate independently through whoever's nearby, group member or not.

## Permissions — and why each one is there

- **Bluetooth (scan / advertise / connect)** — the entire mesh transport.
- **Location — a real, intentional use now.** Powers the GPS radar. This is a deliberate
  tradeoff your group should knowingly accept, not a hidden cost: your phone briefly holds
  other members' recent positions in memory (never written to disk, never added to the app's
  database) so it can relay them onward and plot the radar. Entries expire on their own after
  about 90 seconds. If a phone is seized, there's no persisted location trail to find — at
  worst, a stale snapshot already sitting in RAM at that moment.
- **Background location — a separate, optional nudge, never at launch.** Live testing found GPS
  fixes going sparse within seconds of the screen turning off, even with the mesh service in the
  foreground — Android treats "screen off" as background for location purposes regardless. This
  permission is requested once, after core setup, only to keep radar positions fresher with the
  screen off; declining it leaves everything else working exactly as before, just with staler
  positions in that specific situation.
- **Notifications** — required to run BLE scanning reliably in the background via a foreground
  service (shows a minimal, low-priority "Syncing" notification, deliberately disguised so a
  glance at your lock screen doesn't reveal a mesh app is running), plus a separate, high-priority
  channel for SOS alerts (sound/vibration — the one thing in this app that should interrupt you).
- **Camera — opt-in only, never requested at launch.** Evidence photos are still chosen via
  Android's built-in Photo Picker (read-only reference to one file, no gallery-wide storage
  permission). Camera access exists for exactly one thing: the QR scan icon on the Join screen,
  for reading someone else's invite code with your own camera instead of retyping it. The
  permission prompt only appears the moment you tap that icon; declining it (or never tapping it)
  leaves the rest of the app fully functional — paste/type a code, tap a `mesh2007://` link, or
  have someone else's camera scan the QR code *this* app displays all still work with zero camera
  access. No photo or video is ever captured or stored by the scanner; frames are decoded in
  memory and discarded.
- **Uninstalling wipes everything.** Groups, keys, messages, and evidence files all live in the
  app's private storage (Room database, `EncryptedSharedPreferences`, internal files dir) with
  `android:allowBackup="false"` — nothing here is written anywhere Android preserves across an
  uninstall or could restore from a backup. Removing the app is a real, complete wipe.

## Power tiers

Two BLE tuning profiles, switched automatically — no setting to think about in the common case:
- **Active** — used while the app is actually on-screen. Favors responsiveness: faster
  scan/advertise, since you're watching the radar right now and want it to update.
- **Relay** — used the rest of the time, including whenever the app is backgrounded. Favors
  battery for the hours the phone just sits there carrying mesh traffic.

A **Power saver** toggle on the home screen (off by default) manually pins the Relay tier even
while the app is open, for someone who wants to trade responsiveness for runtime regardless of
what they're doing. The tradeoff either way is real, not hidden: Relay tier means slower
discovery — fewer chances for a peer's scan window to catch your advertisement, and vice versa.

## Security model

- **Confidentiality**: GPS positions, evidence photo bytes, and SOS message text are sealed with
  **AES-256-GCM** under a key derived from the group's random 256-bit root key (`HKDF-SHA256`,
  re-derived every 24h — domain-separated from the rotating wire handle below, and bounding one
  independently-leaked key to ~24h of exposure instead of the group's whole life) before they ever
  go on the wire — a phone relaying them without the root key sees only opaque ciphertext. The GCM
  tag itself is the authentication for these three (a failed decrypt IS the auth failure — there's
  no separate mac to forge or check). This does **not** provide forward secrecy — the root key
  itself must stay retained for as long as the group exists (re-sharing the invite code needs it),
  so any non-interactive key derived from it is exactly as forward-secret as the root key's own
  confidentiality; see `docs/DECISIONS.md` decision 39 for the full reasoning.
- **Authenticity**: evidence headers, nicknames, and presence heartbeats (none of which carry
  confidential content) carry a truncated **HMAC-SHA256** (128-bit) tag under the group key
  instead of a full seal. A phone without the key can relay these but cannot forge one a real
  member will act on — verified with a constant-time comparison.
- **Per-sender authenticity, additive to the above**: every member also has their own per-group
  Ed25519 keypair (not a per-device identity — a device gets a fresh keypair for each group it
  joins, so it can't be linked across groups). A signature under this key rides alongside the
  group HMAC on every frame, so a receiver can tell one group member from another — not just "some
  member sent this," the way the shared HMAC alone can. Trust is pin-on-first-sight (the first
  public key seen for a sender is trusted and remembered; a later, different key for that same
  sender is rejected outright) rather than a certificate authority, matching this app's flat,
  no-owner group model. See `docs/DECISIONS.md`, decision 7, for the full reasoning.
- **Key storage**: group keys live only in `EncryptedSharedPreferences`, backed by the Android
  Keystore (hardware-backed on most devices) — never in the app's regular (Room) database.
- **Key exchange is entirely out-of-band.** There is no server, account, or key-exchange
  protocol: creating a group generates a random id + random 256-bit key on-device, packed into
  one shareable code/QR/link. You share that code over whatever channel you already trust — the
  app never brokers trust for you, it only encrypts once you both have the same key.
- **Discovery is pseudonymous**: phones advertise `HMAC(group_key, current_60s_window)`, not
  anything that identifies the group, so passive BLE scanning by an outsider sees rotating
  random-looking values, not group membership.
- **Group identity is opaque on the GATT wire too**: every relayed frame (SOS, position, evidence
  header, nickname, presence heartbeat) carries a rotating `HMAC(group_key, epoch)` handle instead
  of a cleartext group id — the same rotating-id idea the discovery beacon already used, extended to
  cover a mesh relay too. An adversary capturing mesh traffic can't correlate which packets belong
  to the same group just by reading a field, even without holding any group's key.

**What this does *not* protect against** — read this before relying on the app for anything
where it matters:

- **No forward secrecy, no membership revocation — mitigated, not solved, by groups being
  short-lived by design.** The group key is a single static secret for the group's lifetime, and
  there's still no way to remove one member's access short of dismantling the group and recreating
  it with an entirely new key/code for everyone remaining. What's changed: groups now carry a
  built-in expiry (2-3 days is the typical/intended use, 6 months is the hard ceiling — see "How it
  works" above), enforced by every member's own app, not a social convention — so the blast radius
  of a static key is bounded to however long that particular group actually lived, not forever.
- **The decoy/disguised launcher icon is a UI-level disguise only, not a hidden-app one.** It
  changes what shows on the home screen and app switcher (paired with `FLAG_SECURE`, see below),
  not the installed package name, requested permissions, or its presence in Android
  Settings → Apps — anyone who knows to check there will find it regardless of which launcher
  identity is active. A "Disguise app icon" toggle on the Home screen flips it (`AppIdentity.kt`),
  picking one of a small library of plausible identities (Notes, Files, Weather, Calculator) at
  random *every time it's turned on* — a single fixed "shows as Notes" identity would itself be a
  greppable signature, same reasoning as the notification icon below. The notification icon shown
  while the mesh service runs is independently randomized per install (not per toggle, since it's
  passive background state rather than a deliberate user action each time) from its own small
  library of plain, generic-looking icons, so it isn't the same across every phone running this
  app either.
- **Clipboard copies of a join code/link are marked sensitive (`EXTRA_IS_SENSITIVE`, Android 13+)**
  so the OS should skip clipboard history and cross-device sync for them — but that flag is
  advisory, ignored entirely on older Android, and not guaranteed to be honored by every keyboard
  or clipboard-manager app. A copied code contains the group's raw key; treat "copy code" as
  leaving a trace outside the app's control.

## Known limitations (honest ones)

- **BLE stack behavior varies by phone manufacturer.** Connection stability, MTU negotiation,
  and background-scanning limits are inconsistent across Android OEMs. Real debugging happens
  on real devices, not in a compiler.
- **Two phones minimum to see anything work.** Discovery, navigation, and relay need at least
  two devices in the same group, physically near each other.
- **Only hardware-tested at small scale so far (2-3 physical phones, many live rounds since —
  see `CHANGELOG.md` for the full history).**
  The actual operating envelope is a small group (3-8 people) whose *carrier medium* — the total
  local population of app users acting as blind relays, per "Every phone relays for every group"
  above — can vary from a couple of people to hundreds (`PLAN-v2.md` §5.5). The high end of that
  is validated only in a JVM discrete-event simulator driving the real connection/relay/dedup
  classes from D=3 to D=400 (`app/src/test/.../sim/`), not on physical hardware — no crowd-scale
  live test has been run. A defensive cap on simultaneous inbound BLE connections exists
  (`MeshGattServer`) but its enforcement is currently **disabled** (logging only) pending real
  dense-crowd data on whether it's needed and safe.
- **Large files take real time.** A short low-res photo should propagate through a populated
  area in minutes. Anything larger depends heavily on how continuously populated the physical
  area is between sender and destination — a gap in coverage is a gap no protocol can bridge.
- **Hop-count-to-presence is capped at "in direct range or not," not a true extending gradient.**
  Only actual group members can emit a group's rotating beacon (it requires the group key), and
  every member always advertises itself at hop 0 — there's no mechanism yet for a phone to relay
  "I heard someone 1 hop away" further outward the way SOS, evidence, and position data already
  do over GATT. SOS hop-count does not have this limitation — it's relayed properly and gives a
  true multi-hop distance.
- **Background survival is best-effort.** The app runs a correctly-declared Android foreground
  service and asks once for a battery-optimization exemption, but aggressive OEM battery
  managers (common on some manufacturers) are known to kill foreground services regardless of
  correct API usage — there is no code-level guarantee against this, only the standard mitigation.
- **The release build has no signing config and is currently unsigned** — `./gradlew assembleRelease`
  produces `app-release-unsigned.apk`, which Android will refuse to install as-is. It's also only
  compile-verified and unit-test-verified, not yet run end-to-end on a physical device — R8
  minification (~90% smaller than debug) is a large enough surface of change to warrant its own
  device pass regardless. The APK actually distributed below is the **debug** build, signed with
  the standard auto-generated debug keystore, which is why it's what `releases/` and the download
  link point to. Nobody has generated a release signing key for this project — do that yourself
  (`keytool`/Android Studio's signing wizard) before distributing a release build; this repo will
  never contain one.
- **One dependency (`androidx.security:security-crypto`) is pinned to an alpha release** — used
  only to wrap local key storage (see Security model above), not for any cryptographic operation
  itself. This reflects the state of that library upstream (no stable release exists), not a
  chosen risk.
- **Broadcast tier (Tier B) is new, compile-verified only, not device-tested.**
  (`BeaconRadio.kt`/`BleCapabilities.kt`/`TrickleTimer.kt`/`HopTracker.kt`, PLAN-v2.md
  §5.1/decisions 26-29) — a connectionless, Trickle-governed extended-advertising channel carrying
  group presence, a multi-hop presence-distance gradient, a single-hop live position (reuses the
  same AES-GCM sealing/signing GATT position frames already use — no new crypto), and an SOS
  hop-gradient plus a short authenticated content preview (id + hop + a ≤120-byte message, keyed on
  the real SOS id so the hop-gradient composes with GATT flood-forward's own tracking instead of a
  separate rough estimate — the full authoritative record still arrives over GATT once connected).
  The content preview deliberately uses a SEPARATE mac scheme from GATT's own SOS authentication
  (`MeshFrameCodec.broadcastSosMacInput`, excludes `senderId`) specifically so it doesn't broadcast
  a per-install device id in the clear — a real threat-model tradeoff (Tier B makes content
  passively readable by any nearby BLE scanner, not just connection-gated as over GATT) that was
  raised explicitly and decided by the user, not assumed. Hardware `ScanFilter` and degree-gated
  scan-report batching also included. Generalized from what was originally a Coded-PHY-only
  long-range beacon channel; Coded PHY is now used opportunistically for extra range on hardware
  that supports it, not required. Position broadcast is single-hop only, and is deliberately omitted
  from any broadcast carrying SOS content (budget prioritizes the emergency) — multi-hop position
  still goes through the existing GATT relay path, unaffected. Capability-gated and purely additive
  — on unsupported hardware, or if anything about it misbehaves, it's a silent no-op that can't
  affect the proven legacy discovery path.
- **Delivery is a forwarding protocol, not just a sync protocol — hardware-confirmed across many
  live rounds since, most recently a real ~1-hour multi-phone session (2026-08-10) that included
  mid-session group deletes/rejoins, a sender/receiver role swap, and an app uninstall on a relay
  phone.** SOS floods immediately across every open link the moment it's created or received, and
  links stay open for minutes instead of seconds (`docs/DECISIONS.md` decisions 18-19). Two real
  gaps found by live rounds since the original four are now closed: SOS/evidence-header/nickname
  delivery used to silently stop once a link had been open for a few minutes, because the catalog
  exchange those depend on was only ever sent once per connection (decision 59, fixed by folding it
  into the same periodic refresh presence/position already used); and hop count could read higher
  than the mesh's real topology because a distance-vector loop guard compared raw, rotating BLE
  addresses instead of stable peer identity (decision 59, fixed). One thing intentionally NOT fixed
  yet: a group row's "N hop(s) away" reading can still read stale for up to one staleness window
  (up to several minutes) after a member walks out of range, tracked as its own item and rebuilt
  (decision 60) but **not yet hardware-confirmed** — the fix needs a round that walks a phone
  genuinely far away for a sustained period to prove the number actually rises.
- **A peer with only a low-accuracy GPS fix doesn't show up on the radar at all, with no
  on-screen explanation why.** `placePeerOnRadar` deliberately refuses to plot a dot when your
  accuracy plus theirs exceeds ~250m combined, rather than show a confidently-wrong position —
  correct behavior for trusting the dot, but the failure mode is silent: a group member can show
  "N hop(s) away" in the group list (hop-count only needs a heard beacon, not GPS) while their
  radar dot never appears, and nothing on screen says why. Confirmed live to trigger more often
  than just "indoors": many phones deliberately widen their reported GPS accuracy while
  stationary (less continuous satellite tracking to save battery when not moving), then tighten
  it back up the instant motion is detected — so a dot can disappear while genuinely standing
  still, even right next to the other phone, and reappear on the next step. The threshold was
  raised (from an original 150m) specifically to make this less common, but the underlying
  silent-failure mode is unchanged: a real fix (surface *something* for a hop-away-but-imprecise
  peer instead of silence) is scoped but not yet built.
- **`GattOperationQueue`'s per-peer write lock isn't guaranteed to release if a connection hangs
  between `CONNECTED` and `DISCONNECTED`.** Once a connection gets past the initial `CONNECTED`
  callback, `MeshGattClient`'s stuck-attempt timeout (see `docs/DECISIONS.md`, decision 5) no
  longer watches it — if the underlying radio then goes silent without ever firing `DISCONNECTED`
  (the same class of undocumented Android BLE failure decision 5 fixed for the *pre-connect* case),
  that peer's queue entries leak and it can never be reconnected to. Real but narrow; a proper fix
  needs a second, later connection-lifecycle timeout — deliberately not attempted blind here given
  how much live 2-phone testing this exact GATT lifecycle code has already needed to get right.
- **Automated test coverage is logic-only.** 525 pure-JVM/Robolectric unit tests cover crypto,
  wire-format encode/decode, connection/dedup state machines, the catalog-sync round trip, and (as
  of `PLAN-v2.md`'s scaling work) a discrete-event crowd simulator driving the real connection/
  relay classes from D=3 to D=400 (`./gradlew test`). There are no automated UI tests and no CI
  pipeline — both are manual/planned, not built.

See [`TESTING.md`](TESTING.md) for how the test suite is organized, and
[`test_rubric.md`](test_rubric.md) for the manual, physical-device test plan real bugs in this
project have actually been found by (BLE radio behavior at this level doesn't show up in a
compiler or an emulator).

## Roadmap

Full plan and reasoning in [`PLAN-v2.md`](PLAN-v2.md); every version's actual changes in
[`CHANGELOG.md`](CHANGELOG.md). Shipped and code-complete: the crowd-scale simulator and stable
per-group peer identity (P0), immediate flood-forwarding and persistent connections (P1/P3), a
connectionless broadcast tier over BLE Extended Advertising for presence/position/SOS reaching
every phone in range at once with no connection needed, governed by a Trickle-style (RFC 6206)
suppression timer (P2 — see Known Limitations above for its hardware-confirmed status), courier
handoff for bridging a physical gap the mesh can't currently reach across (P4), thumbnail-first
evidence sharing with fountain-coded chunk transfer so any combination of helpful nearby phones
speeds up a download instead of slowing it down, plus an optional faster BLE L2CAP bulk pipe (P5),
and further privacy hardening — per-group (not per-device) sender identity, an epoch-rotating
content-sealing key, and fixed-size frame padding so message length itself doesn't leak message
type (P6).

**In progress: an optional bridge to bitchat's own mesh (P7).** Off by default. A proof-of-concept
packet encoder and one-shot BLE write probe have been tested against a real bitchat install — some
writes succeed, confirming a real bitchat node's GATT will accept a well-formed packet from a
sender it's never seen, which is the first of two things this needs to be provably safe. The actual
listener/injector bridge (and confirming an actual multi-hop relay, not just one accepted packet)
isn't built yet.

**Also open:** a group's "N hop(s) away" reading was recently rebuilt to stop freezing on a stale
value (see Known Limitations above) but hasn't had its own dedicated hardware round yet.

## Specs

- **Platform**: Android only. Min SDK 26 (Android 8.0+), target/compile SDK 34.
- **Package**: `org.offlinemesh.app`. `versionName` `0.7.25-dev` — pre-1.0, see Known Limitations.
- **Distribution**: **APK only, no Play Store.** Download the APK from this repo (see below) or
  build it yourself; sideloading is the only install path by design.
- **Language/stack**: Kotlin, Jetpack Compose (Material 3), Room (SQLite), plain
  `android.location`/`android.bluetooth.le` — no Google Play Services dependency anywhere, so it
  works on de-Googled/custom-ROM phones.
- **Wire transport**: Bluetooth LE only — GATT for content, BLE advertising for discovery/beacons.
  No internet, cellular, or Wi-Fi involved at any point.

## Get the app

**Prebuilt APK**: download the latest `.apk` from this repo's
[**Releases**](https://github.com/karmaNeggs/20.07/releases/latest) page and install it —
you'll need to allow installs from that source once in Android's settings. (The same file also
sits in the [`releases/`](releases/) folder in-tree if you're browsing the source rather than the
Releases page.) This is always a **debug** build — no signed release build exists yet; see Known
Limitations above for exactly why.

**Build from source**:
```
git clone <this repo>
cd 20.07
export JAVA_HOME=<a JDK 17 install>   # e.g. Homebrew: /opt/homebrew/opt/openjdk@17 on macOS
./gradlew assembleDebug                # APK at app/build/outputs/apk/debug/
./gradlew test detekt                  # Tier 1 test suite + static analysis, see TESTING.md
```
Needs the Android SDK (platform 34, build-tools 34.0.0) — Android Studio sets this up
automatically, or install the command-line SDK tools and set `ANDROID_HOME`/`local.properties`
yourself.

## How to use it

1. **Install** the APK on your phone (sideload via `adb install`, or download it and tap to
   install — you'll need to allow installs from that source once).
2. **Grant permissions** when asked on first launch (Bluetooth, and location for the radar —
   see above for what that is and isn't used for).
3. **Create or join a group.** Creating one just needs a name — it generates a random key and a
   shareable code/link/QR code. Joining just needs that code, pasted, scanned, or tapped from a
   link — nothing to type that could get mistyped.
4. **Open the group.** You'll see:
   - A red **SOS** button — tap it, optionally add a short message, send. It broadcasts to
     the group and lets others navigate toward you.
   - A **Navigate** button — opens the radar. With a GPS fix, group members show up as dots at
     their real bearing and distance, rotated so "up" is always the direction you're currently
     facing — walk toward the dot. Without a fix, it falls back to a hop-count number and a
     trend ("getting closer" / "getting farther") that only needs Bluetooth.
   - A **Send evidence** button — opens your photo gallery, pick an image, it gets compressed,
     encrypted, and queued for relay. You'll see it listed with a chunk-progress count until
     the group (or you, for images received from others) has all pieces and it's marked
     complete.
5. **Leave it running.** The mesh only works while the app's background service is alive —
   don't force-stop it, and if your phone aggressively kills background apps (common on some
   Android OEMs), you may need to exempt it from battery optimization in system settings.

## Contributing

Issues and pull requests are welcome. If you're reporting a bug in the BLE/mesh layer
specifically, a description of the phones/Android versions involved is more useful than a stack
trace — see [`TESTING.md`](TESTING.md) for why (most real bugs here have been radio-behavior
ones that only show up on physical hardware). If you're reporting a security issue, please open
it as a regular issue unless it's actively exploitable against real users — there's no dedicated
security-contact channel set up yet.

## License

MIT — see [`LICENSE`](LICENSE).
