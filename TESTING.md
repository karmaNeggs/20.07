# Testing — three honest tiers

This exists so you only pick up the phones for manual testing once the cheap, fast, deterministic
checks have already passed. It doesn't replace `test_rubric.md` — Tier 3 below is exactly what that
file is for, and stays manual on purpose.

All `./gradlew` commands below run from `android/`, where the Gradle project lives.

## Tier 1 — pure logic, JVM only, no device or emulator, runs in seconds

```
cd android
./gradlew test
```

Covers: crypto round-trips and tamper detection, every wire frame type's encode→decode identity
(including malformed/truncated input), fountain-code encode/decode and its Gaussian-elimination
decoder, courier handoff's copy-conservation property, the hop-count state machine (with a fake
clock, so its multi-minute, per-source staleness windows are tested in milliseconds), the radar
bearing/distance math against known coordinate pairs (Robolectric-backed — the one thing here that
needs a real `android.location.Location.distanceBetween`, not a stub), the join-code round-trip, the
Bloom-filter catalog-sync round trip (`RelayResponderTest.kt` — given a peer's filter, correctly
push what they're missing and skip what they already have), and the state machines that had real,
live-tested bugs: `ConnectionAttemptTracker` (a connection attempt that never gets a callback must
eventually become retryable again, not stay stuck forever — see `docs/DECISIONS.md`, decision 5;
later extended for the epoch-aware cooldown-skip behind the passerby-relay fix) and `HopTracker` (a
tracked hop reading used to be able to freeze indefinitely in a redundant mesh — see decision 60 —
now rebuilt so each reporting source ages out independently, tested with a fake clock the same way).

634 tests (1 opt-in live-relay test skipped), all passing, including a 1000-user logical scale and chaos simulation of the internet path. This is what should catch a broken build *before* you spend twenty minutes
manually testing it — a crypto or wire-format regression shows up here in seconds, not after a
confusing live session.

**Static analysis, same idea, same command family:**

```
./gradlew lint      # Android-specific: leaked receivers/contexts, resource issues, some security rules
./gradlew detekt     # Kotlin-specific static analysis, baselined against pre-Pass-18 code (see below)
```

`detekt-baseline.xml` grandfathers in this codebase's pre-existing style (long, comment-dense lines
is a deliberate choice here, not an oversight) from before detekt was added, so detekt gates *new*
issues going forward rather than flagging 300+ pre-existing style choices on day one. Regenerate the
baseline with
`./gradlew detektBaseline` only after a deliberate cleanup pass, never just to silence a new finding.

**LeakCanary** is wired in for debug builds only (`debugImplementation`, zero code needed — it
auto-installs). It's different in kind from the above: not a static check, a runtime one. It watches
your actual manual testing sessions and reports real leaked Activities/Views/objects if any turn up.

## Tier 2 — Compose UI / screen-state tests

Not yet built out. The plan (join-code validation, "app stays usable with camera permission denied,"
nickname dialog save/cancel) is sound, but Compose+Robolectric UI testing has real version-specific
fragility that's better worked through with a full Android Studio setup and a connected device/
emulator than blind in a headless environment. Tier 1's growing unit-test suite has been the
higher-value, lower-risk investment so far.

## Tier 3 — what genuinely can't be automated, and stays manual

- **Real BLE radio behavior.** This is, by a wide margin, where this project's actual bugs have
  lived — repeated live 2-phone sessions across this project's history surfaced findings (see
  `docs/DECISIONS.md` for the ones that changed a lasting invariant) that no amount of code review
  or emulator testing would have. No emulator reproduces real chipset advertise/scan quirks — this
  is what `test_rubric.md` is for, and Tier 1 passing is what should gate picking it up, not replace
  it.
- **Real power consumption.** Needs Android Studio's on-device Energy Profiler (or Battery
  Historian) over real elapsed time. The rig can at best assert a *proxy* — e.g. "how many times per
  minute does the code actually call `startAdvertising`" as a regression guard against reintroducing
  the advertise-churn bug (`docs/DECISIONS.md`, decision 1) — but that's a call-frequency count, not
  a battery number.
- **Multi-device mesh/relay patterns over real distance and obstacles.** `test_rubric.md`'s job.

## Release build

`./gradlew assembleRelease` runs with R8 minification + resource shrinking on for release builds
(see Known Limitations in `README.md` for the security-crypto/Tink `-dontwarn` rules this needed).
**This build has been compile-verified, not runtime-verified** — I don't have a device or emulator
to install it on. Do a
full manual pass against `test_rubric.md` on a real release build before trusting it for real
distribution; R8 stripping ~90% of the debug size is a large enough surface of change that it
deserves its own dedicated device pass, not an assumption that "it compiled" means "it works."

## Test day — Internet reach (v0.10.0-dev, targetSdk 36)

First real-phone round for the v3 feature and for targetSdk 36. Everything below passed only in simulation
until you run it. Allow about 90 minutes. Record each result as PASS / FAIL / NOTES.

**Before you start**
- [ ] 3 phones minimum, 4 for Win 1 (all Android 12+). Uninstall any earlier 20.07 first (debug and release builds are signed differently).
- [ ] Install `20.07-v0.10.0-dev-debug.apk` on every phone. Grant every permission it asks for.
- [ ] Create one group on phone A, join the same group on all the others by code or QR. Give each phone a nickname.
- [ ] On every phone: Settings > Battery > allow the app to run unrestricted, so Android does not sleep it.
- [ ] Note each phone's model and Android version. Wi-Fi networks need real internet (not a captive portal).

**Phone roles for Test 2 and 3**

| Phone | Bluetooth | Wi-Fi / data | Internet tile |
|---|---|---|---|
| A | on | off | off |
| B (the bridge) | on | on | on |
| C | **off** | on | on |
| D (only for Test 1) | **off** | on | on |

**Test 0 — switch off means "exactly as v2" (15 min)**
- [ ] With the Internet tile OFF on all phones and Bluetooth on, do your normal v2 checks from `test_rubric.md`: radar, message, SOS.
- [ ] Confirm no "Internet reach" status line shows on any phone.
- [ ] PASS = nothing behaves differently from the last v2 round.

**Test 1 — Win 1: two phones, Bluetooth off, only Internet reach (20 min)**
- [ ] On C and D: Bluetooth off, Wi-Fi on, tap the Internet tile, read the consent text, confirm. Status should go from "connecting" to "connected".
- [ ] C sends a message. D receives it within about 30 seconds. D replies. C receives it.
- [ ] Put C and D in different places (different rooms or buildings). Each shows the other on the radar and the distance/direction changes as one walks.
- [ ] PASS = messages both ways and a moving location both ways, with Bluetooth off on both.

**Test 2 — Win 2: the bridge (30 min)**
- [ ] Roles as in the table. A and B within Bluetooth range of each other; C far enough away that it can never reach A by Bluetooth.
- [ ] A sends a message. C receives it. C replies. A receives it. Note the delay each way.
- [ ] A and C each appear on the other's radar. Walk A around: C's view follows within about 15 seconds.
- [ ] PASS = A and C talk both ways through B, and the location is shared both ways.
- [ ] Turn B's Internet tile OFF. Within a minute A and C stop receiving each other's new messages. Turn it back on: they resume.

**Test 2b — "Last seen" (15 min)**
- [ ] With A and C both showing on each other's radar (Test 2), switch C's Wi-Fi off, or walk C out of any coverage. After about 3 minutes C's dot leaves A's radar.
- [ ] A's home screen now shows a **Last seen** line for C: name, "N min ago", and a distance with a compass direction (for example "1.2 km NE"). Check the direction and distance against where C really is.
- [ ] Bring C back online. The Last seen line disappears and C is a live dot again.
- [ ] Reverse it: C should show A under Last seen when A goes quiet, if B is still online and relaying.
- [ ] A phone that joins late (turn Internet reach on for a fourth phone after C went quiet) shows C under Last seen within about a minute. If not, record it.
- [ ] PASS = last-seen age, distance and direction are right and it never appears as a live dot.

**Test 2c — Files and SOS priority (15 min)**
- [ ] On A (Bluetooth only) send a small photo to the group. B should receive it over Bluetooth and relay it; C (Wi-Fi/mobile data only) should get a thumbnail within seconds and the full photo within a minute or two. Repeat from C towards A.
- [ ] Do it once on mobile data and once on Wi-Fi. Both should work.
- [ ] While a photo is still sending, press SEND SOS on C. A must show the SOS in a few seconds, not after the photo.
- [ ] A file over 400 KB must not be sent over the internet (it still moves over Bluetooth). Note what the sender sees.
- [ ] PASS = photo arrives intact on the far phone, and SOS overtakes the photo.

**Test 2d — Stranger carrying (20 min)**
- [ ] Add a 4th phone S that is NOT in the group, with Internet reach ON and Wi-Fi on. A (group member, Bluetooth only, NO internet, Internet reach ON) sends a message and stays near S only; keep B and C away from A.
- [ ] C (member, internet) should receive A's message within about a minute, though A has no internet and S is not a member. Reply from C: S should pick it up and A should show it.
- [ ] Turn Internet reach OFF on S. A and C stop hearing each other within a minute. Turn it back on: they resume.
- [ ] Record battery use on A and S, and whether any phone gets warm or lags.
- [ ] PASS = A and C talk through a stranger; nothing readable by S (S shows no group content).

**Test 3 — the awkward cases (20 min)**
- [ ] Turn C's Wi-Fi off, wait 30 seconds, turn it on. Status goes "waiting for Wi-Fi or mobile data", then back to connected. Messages sent meanwhile arrive afterwards.
- [ ] Turn on the app's Offline tile on C. Status shows "paused by Offline mode" and C stops sending.
- [ ] Switch C from Wi-Fi to mobile data. Still connected.
- [ ] Lock the screen on C for 10 minutes. Does it still receive? (Android 16 may limit background networking; record what happens.)
- [ ] Reboot C. After unlocking, is the Internet tile still on or off as you left it, and does it reconnect?

**What to capture for every failure**
- [ ] Phone model, Android version, which role, what you did, what you expected, what happened, and the time.
- [ ] On each phone involved, tap "Export diagnostics" (debug build) straight after, and share the file.
- [ ] A screenshot of the home screen showing the status line.

**Do NOT report these as bugs, they are known and not built yet**
- Only direct Bluetooth neighbours exchange held frames and interest tags; carrying through two strangers in a row is unproven.
- Phones that only have Bluetooth and are not members of the group do not carry anything over the internet.

**Also record, because it is unmeasured**
- [ ] Battery percentage on B and C at the start and after the 90 minutes, with the tile on.
- [ ] Mobile data used (Settings > Network) on C during the test.
- [ ] Anything that looks wrong at targetSdk 36: content hidden behind the status bar or navigation bar, notification not showing, the foreground notification disappearing.

**Stop the round and tell me if:** the app crashes on open, a message from the wrong group appears, a location shows for a person who is not in the group, or the app uses mobile data with the tile OFF.
