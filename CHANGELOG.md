# Changelog

## Since [0.1.0] — scaling/security hardening + disguise completion (unreleased, no version bump yet)

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
