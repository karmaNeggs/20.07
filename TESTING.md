# Testing — three honest tiers

This exists so you only pick up the phones for manual testing once the cheap, fast, deterministic
checks have already passed. It doesn't replace `test_rubric.md` — Tier 3 below is exactly what that
file is for, and stays manual on purpose.

## Tier 1 — pure logic, JVM only, no device or emulator, runs in seconds

```
./gradlew test
```

Covers: crypto round-trips and tamper detection, every wire frame type's encode→decode identity
(including malformed/truncated input), the hop-count state machine (with a fake clock, so the
90-second staleness window is tested in milliseconds), the radar bearing/distance math against known
coordinate pairs (Robolectric-backed — the one thing here that needs a real
`android.location.Location.distanceBetween`, not a stub), the join-code round-trip, the
Bloom-filter catalog-sync round trip (`RelayResponderTest.kt` — given a peer's filter, correctly
push what they're missing and skip what they already have), and the state machines that had real,
live-tested bugs: `ConnectionAttemptTracker` (Pass 16 — a connection attempt that never gets a
callback must eventually become retryable again, not stay stuck forever; later extended for the
epoch-aware cooldown-skip behind the passerby-relay fix).

112 tests as of [0.2.0], all passing. This is what should catch a broken build *before* you spend
twenty minutes manually testing it — a crypto or wire-format regression shows up here in seconds,
not after a confusing live session.

**Static analysis, same idea, same command family:**

```
./gradlew lint      # Android-specific: leaked receivers/contexts, resource issues, some security rules
./gradlew detekt     # Kotlin-specific static analysis, baselined against pre-Pass-18 code (see below)
```

`detekt-baseline.xml` grandfathers in this codebase's existing style (long, comment-dense lines is a
deliberate choice here, not an oversight) so detekt gates *new* issues going forward rather than
flagging 300+ pre-existing style choices on day one. Regenerate the baseline with
`./gradlew detektBaseline` only after a deliberate cleanup pass, never just to silence a new finding.

**LeakCanary** is wired in for debug builds only (`debugImplementation`, zero code needed — it
auto-installs). It's different in kind from the above: not a static check, a runtime one. It watches
your actual manual testing sessions and reports real leaked Activities/Views/objects if any turn up.

## Tier 2 — Compose UI / screen-state tests

Not yet built out. The plan (join-code validation, "app stays usable with camera permission denied,"
nickname dialog save/cancel) is sound, but Compose+Robolectric UI testing has real version-specific
fragility that's better worked through with a full Android Studio setup and a connected device/
emulator than blind in a headless environment. Tier 1's 112 tests were the higher-value, lower-risk
investment for this pass.

## Tier 3 — what genuinely can't be automated, and stays manual

- **Real BLE radio behavior.** This is, by a wide margin, where this project's actual bugs have
  lived (Passes 3, 7, 8, 9, 10, 12, 13, 14 were all findings a live 2-phone session surfaced that no
  amount of code review or emulator testing would have). No emulator reproduces real chipset
  advertise/scan quirks — this is what `test_rubric.md` is for, and Tier 1 passing is what should gate
  picking it up, not replace it.
- **Real power consumption.** Needs Android Studio's on-device Energy Profiler (or Battery
  Historian) over real elapsed time. The rig can at best assert a *proxy* — e.g. "how many times per
  minute does the code actually call `startAdvertising`" as a regression guard against reintroducing
  the Pass 13 churn bug — but that's a call-frequency count, not a battery number.
- **Multi-device mesh/relay patterns over real distance and obstacles.** `test_rubric.md`'s job.

## Release build

`./gradlew assembleRelease` now runs with R8 minification + resource shrinking on (Pass 18 — see
CHANGELOG for why it was off before and what turning it on found). **This build has been
compile-verified, not runtime-verified** — I don't have a device or emulator to install it on. Do a
full manual pass against `test_rubric.md` on a real release build before trusting it for real
distribution; R8 stripping ~90% of the debug size is a large enough surface of change that it
deserves its own dedicated device pass, not an assumption that "it compiled" means "it works."
