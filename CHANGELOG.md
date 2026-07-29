# Changelog

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
