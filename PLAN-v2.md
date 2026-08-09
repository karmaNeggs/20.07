# 20.07 v2 — scaling plan

**RESUME HERE — current status as of 2026-08-09 (session continued past the P6 checkpoint below).**
This is the single status block to trust; anything else in this document (including inline "STATUS"
notes inside Part 7 below) is detail underneath this, not a competing source. If a phase's own
section below ever seems to disagree with this block, this block is current and that section is
what's stale.

- **NEXT SESSION STARTS HERE — the first live round happened, found a total-failure regression in
  the ONE fix that was flagged as unverified (CR-13), which is now diagnosed and fixed. The round
  needs to be RE-RUN against a new APK before anything else proceeds.** Round 1 (v0.7.22-dev, 3
  phones): `openLinkCount=0` for the entire session on every phone, no radar, every SOS
  `BLOCKED: no open links` — total discovery failure, not a partial degradation. Root cause found
  from the user's own `DiagnosticsLog` exports (no guessing, no re-test needed to isolate it — see
  decision 58, `docs/DECISIONS.md`, for the full diagnosis): CR-13's `ScanFilter` matched a BLE AD
  structure (Service UUIDs List) this app's beacon has never emitted (it advertises via Service Data
  instead, deliberately, to fit legacy BLE's 31-byte limit) — a filter that could never match, on
  any device, not chipset flakiness. Fixed by reverting BOTH the legacy scan's filter (CR-13) and an
  identical, previously-undetected instance of the exact same bug in the Tier B scan (present since
  decision 34, never caught because Tier B was separately marked untested). Both scans are back to
  the original unfiltered, software-matching shape that was live-tested working across four prior
  rounds. Committed, pushed, compile-verified (525 tests, 0 failures, detekt clean, both variants +
  `lintVitalRelease` green, no `missing_rules.txt`). **The APK to test now is
  `releases/20.07-v0.7.23-dev-debug.apk` (versionCode 34, `aapt`-confirmed) — NOT v0.7.22-dev, which
  is the build that just failed.** **What to do when the user returns with round 2's results:**
  1. Read whatever they report first — don't assume the fix worked just because the diagnosis was
     confident; a confident diagnosis of round 1's failure is not the same thing as proof round 2
     succeeds.
  2. If round 2 is clean: core comms/radar/SOS delivery working across all test phones is the actual
     bar now — not "as fast as before" (the original CR-13 framing), given what round 1 showed. Pull
     the `[degree]` log lines too (`MeshService.startDegreeLogging`, CR-24) — real `openLinkCount`
     numbers are what `ForwardingPolicy`'s degree thresholds (§9.2 item 7) need to be re-derived
     from, if at all.
  3. If round 2 ALSO fails: do not re-apply a hardware `ScanFilter` anywhere as a fix for anything
     without first checking, in code, which AD structure the relevant beacon actually advertises
     against which AD structure the proposed filter type matches — the exact check that would have
     caught CR-13 before it ever reached a live round. Treat any other new failure as its own fresh
     diagnosis from the logs, the same way this one was — don't assume it's a repeat of CR-13.
  4. Anything else the round surfaces gets triaged the normal way (a new `PLAN-v2.md` finding, or
     straight to a decision + fix if it's small and clear).
  5. Once a round is clean (or issues from it are fixed and re-verified), P7 (the real bitchat
     bridge implementation, not the spike tooling already shipped in decision 55) is next — see
     Part 7's own P7 section for the design, already researched and locked, just not built.

- **2026-08-09 — TRACKED TIER (CR-21..30) CLOSED OUT + PANIC-DELETE CUT FROM SCOPE. Committed as
  v0.7.22-dev, compile-verified. This is the current top-of-file status; everything below is older.**
  Second pass over Part 10, user-directed: "whatever can be done, do it" for the 10 items left in
  the Tracked tier after Gate A/B shipped (decision 56), plus two explicit product calls.
  - **CR-28 (shake-to-panic-delete) removed from this plan entirely** — the user's decision was to
    cut it, not build it, so it's gone from Part 10 rather than left as a tracked gap. No code ever
    existed for it (confirmed by grep before removal), so nothing to delete there; the original
    product brief (`20072026.md`, outside this repo's own docs) was deliberately left untouched as
    the historical record of what was originally asked for — flag if you also want that file edited,
    it wasn't assumed.
  - **CR-29 (plaintext evidence at rest) closed as working-as-intended, not a bug** — user's own
    framing: the design goal is redundancy against single-device loss/seizure, not confidentiality
    of evidence content (which isn't illegal to hold), so encrypting the reassembled file would work
    against the actual goal. See CR-29's own entry.
  - **CR-22/23/25/26/27 fixed**, **CR-24 partially** (its logging half shipped — `MeshService
    .startDegreeLogging()`, 60s cadence, `DiagnosticsLog` tag `degree` — its threshold-re-derivation
    half genuinely needs the round's own data and was correctly left for it), **CR-21 checked and
    deliberately left unchanged** (already gated on real-hardware evidence per decision 18 that this
    pass's own scope excludes — see CR-21's own entry for why "do it" meant "don't" here), **CR-30
    is a meta-note with nothing separately actionable**. CR-23 turned out to be a corrected finding,
    not a real gap — the DAO's own `@Query` already filtered `ttl > 0`; only a redundant Kotlin-side
    re-filter was removed.
  - 525 tests (unchanged — no new tests added this pass; all fixes were low-risk mechanical changes
    already covered by the existing suite), 0 failures, detekt clean, both variants green
    (`assembleDebug`/`assembleRelease`/`lintVitalRelease`), no `missing_rules.txt`. Version bumped to
    v0.7.22-dev (versionCode 33), fresh debug APK built and `aapt`-confirmed. Full detail in
    decision 57, `docs/DECISIONS.md`.

- **2026-08-09 — FULL-CODEBASE REVIEW PASS. THE NEXT HARDWARE ROUND IS BLOCKED UNTIL PART 10's
  GATE A IS CLEAR. Read `Part 10` before doing anything else.** A read-through of all ~13.4k LOC of
  `app/src/main` (no code changed, nothing committed) found **30 issues**, of which **12 would
  either invalidate the round's own measurements, crash or silently degrade the session mid-run, or
  send someone chasing a phantom.** The most consequential four, because each one is a shipped
  feature or a plan claim that is *not actually true in the code*:
  - **CR-1**: `MeshProtocol.MAC_LEN = 32` vs `CryptoUtils.authTag`'s 16-byte truncation means the
    **Tier B SOS content preview (decisions 29/30/31) has never once gone on the wire**. Not a
    regression this session — it has been dead since decision 29 shipped, and the unit tests
    encode the production behaviour as their *failure* case, which is why nothing caught it.
  - **CR-3**: `padGattFrame`'s 256-byte minimum bucket is not MTU-aware. **Any peer that negotiates
    below MTU 259 loses the entire server→client notify path** (notifications cannot fragment) and
    has its client→server writes silently promoted to prepared writes the GATT server never
    reassembles. The three test phones all granted 517, which is exactly why decision 52's round
    looked clean.
  - **CR-2**: `dismantleGroup` does **not** delete courier envelopes, contradicting `§9.3 item 4`'s
    own "checked against the actual code… already handled, no gap" conclusion. That §9.3 entry is
    now corrected in place below.
  - **CR-13**: `§9.2 item 1`'s hardware `ScanFilter` — which this plan itself calls a P1/P2
    **blocker** — was only ever applied to the Tier B scan. The legacy scan, the one that actually
    feeds `onDeviceSeen`/`maybeConnect` and therefore all GATT connectivity, is still unfiltered
    and unbatched, still citing decision 3 (which was about service-*data* filtering — the exact
    conflation §9.2 item 1 was written to call out).

  Nothing in `Part 10` is speculative: every finding names a file and line and was verified against
  the source, not inferred from a comment. Several are cases where a doc comment asserts a property
  the code next to it does not have — see `Part 10`'s own closing note on why that pattern matters
  more than any individual bug here. **Sequencing decision (2026-08-09, user): fix Gate A, then run
  the device-test round, then P7** — the round is otherwise spending real-hardware time measuring a
  degree signal that is corrupted (CR-4), a link-diversity mechanism that is switched off (CR-8),
  and a broadcast feature that cannot transmit (CR-1).

- **2026-08-09 — GATE A + GATE B APPLIED AND COMPILE-VERIFIED (all 20 of CR-1..CR-20). Committed as
  v0.7.21-dev (decision 56, `docs/DECISIONS.md`) — see the newer bullet above this one for the
  follow-up pass that closed out the Tracked tier too.** Corrected same day: initially reported
  "not compile-verified" — no JDK was found on
  the standard `java_home` search path, but a Homebrew-installed JDK 17 (`/opt/homebrew/opt/openjdk@17`,
  matching this project's own `sourceCompatibility`) was actually present and just not on the
  non-interactive shell's PATH. With it: `./gradlew testDebugUnitTest detekt` found one real issue
  (`HopTracker` hit detekt's `TooManyFunctions` threshold after CR-6's four new methods — fixed with
  the same `@Suppress` + justifying-comment convention this codebase already uses elsewhere), then
  525 tests (510 prior + 15 new from this pass) passed with 0 failures/errors, detekt clean,
  `assembleDebug`/`assembleRelease`/`lintVitalRelease` all green, no `missing_rules.txt`. Every
  "not compile-verified" note below is stale as of this correction. Every fix in Part 10's Gate A
  (13 items) and Gate B (7 items) has been written into the working tree, one logical commit's
  worth of changes per finding, each with a `CR-n` comment at the change site pointing back to its
  own Part 10 entry. Two corrections surfaced during the fix pass itself, both already folded into
  Part 10 and this RESUME block:
  - **CR-15 was overstated and has been downgraded.** The original finding claimed `RadarView`'s
    `elapsedMs` read forces a full recomposition of `RadarCanvas` at display refresh rate. On
    inspection this is wrong: `elapsedMs` is read only inside `Canvas{}`'s own draw lambda (`Canvas`
    = `Spacer(Modifier.drawBehind(...))`), and a State read inside `drawBehind`'s lambda is
    attributed to the DRAW phase's own snapshot-observation scope, not the composition scope that
    created the call — mutating it triggers redraw only, the same mechanism the sweep animation's
    own `animateFloat` already relies on, and that sweep already redraws the canvas continuously
    regardless of dot-blink state, so there was no incremental per-frame cost either way. Applied
    only the genuinely real part: `mutableStateOf(0L)` → `mutableLongStateOf(0L)`, avoiding a Long
    box per animation frame. Recorded here because it's exactly the class of "verify before you fix"
    lesson `Part 10`'s own closing note argues for.
  - **CR-12 additionally applies to `HopTracker.effectiveStaleMs`, not just the two functions named
    in the original finding.** Same unauthenticated-hop-scales-the-window shape, same fix (capped
    slack at 6 hops), now in all three places (`PositionTracker.effectiveMaxAgeSeconds`,
    `RelayResponder.presenceWithinSkew`, `HopTracker.effectiveStaleMs`) instead of two.

  **The honest gap: no JDK is available in this session's environment, so none of this has been
  run through `./gradlew test detekt` or built.** Verification instead was a full manual pass —
  every changed file re-read via `git diff`, every existing test whose behavior could plausibly
  shift traced by hand against the new code (particularly the courier-handover budget refund in
  CR-14, and the fountain-encoder cache in CR-16), new tests added alongside each Gate A/B item
  that has a unit-test tier at all (CR-1, CR-3, CR-6, CR-7, CR-10, CR-12 across `MeshFrameCodecTest`/
  `HopTrackerTest`/`PositionTrackerTest`/`RelayResponderPresenceSkewTest`/`TrickleTimerTest`/
  `MeshProtocolBroadcastTierTest`), and every added line checked against this project's 120-char
  detekt limit and brace-balanced per file. Six items (CR-2, CR-4, CR-5, CR-8, CR-9, CR-11, CR-17,
  CR-18) have **no unit-test tier at all** under this project's own established constraints (BLE
  radio classes and Keystore-gated `GroupRepository`/`GroupKeyStore` code are both already
  documented elsewhere in this file as compile-verified-only) — those are correctness-reviewed by
  hand only, same posture this project already applies to L2CAP/bitchat-spike work. **Before the
  device-test round: run the full suite, run detekt, fix whatever a real compiler catches that this
  manual pass didn't, then rebuild the debug APK per this project's own standing discipline.**

  Also folded in during this pass, not originally in Part 10 (both trivial, found while fixing
  adjacent code): `CryptoUtils.MAC_TAG_LEN` made public (CR-1 needed a single source of truth
  instead of a duplicated literal) and a dangling two-KDoc-blocks-stacked-on-one-function artifact
  in `pushCouriersWithHandover` (introduced by the CR-14 edit itself, caught and merged during
  review — exactly the kind of small drift `Part 10`'s own CR-30 note is about).

  Part 10's own checklist below is updated to reflect this — Gate A and Gate B are struck through,
  not deleted, so the reasoning stays attached to what was actually done.

- **2026-08-09, decision 55 (`docs/DECISIONS.md`): P7 spike tooling shipped — encoder + BLE
  injector, still needs a real bitchat install to actually run against.** New isolated package
  `org.offlinemesh.app.bitchatbridge`: `BitchatPacketEncoder` (pure, unit-tested, exact v1 header
  byte layout confirmed against bitchat's own source — 14-byte header, `senderId` always exactly 8
  raw bytes fixed-position, broadcast `groupMessage` at `flags=0`/TTL 7, not auto-fragmented for a
  small payload) and `BitchatSpikeTransport` (debug-only — scans for either of bitchat's TWO
  service UUIDs, since a debug bitchat build advertises a different one than release; connects;
  writes one forged packet with a random marker; logs every step to `DiagnosticsLog` tag
  `bitchat-spike`). Debug-only trigger row in `HomeScreen.kt`. **A clean write confirms bitchat's
  peripheral GATT accepted the packet — the first half of decision 51's "hard dependency, not
  skippable" note — but NOT multi-hop relay itself**, which needs a third device or packet capture,
  a separate manual step. 510 tests (5 new), detekt clean, both variants green, version v0.7.20-dev,
  committed locally, **not pushed**. **This is genuinely not further automatable from here** — the
  next step needs the user's own phone with bitchat actually installed.

- **2026-08-09, decision 54 (`docs/DECISIONS.md`): senderId de-globalization SHIPPED — implements
  decision 53's scoped fix.** `senderId` was `GroupRepository.deviceId`, a single random-per-install
  id reused across every group and sent in CLEARTEXT on presence/position broadcasts — any member of
  two overlapping groups could correlate a device across them, and any passive non-member BLE
  observer could track the id with no key at all. New `GroupRepository.senderIdFor(groupId)`,
  derived from the per-group Ed25519 public key already generated/persisted
  (`sha256Hex(publicKey).take(16)`, no new key/storage). All 12 call sites across
  `RelayEngine.kt`/`RelayResponder.kt`/`BeaconRadio.kt` updated (full list in decision 54).
  `BeaconRadio.advertiseJitterMs`'s own `deviceId` use deliberately untouched — local-only, never
  transmitted. No `MeshFrameCodec.VERSION` bump (byte layout unchanged, value-only). 505 tests,
  detekt clean, both variants green, no `missing_rules.txt`. Version v0.7.19-dev, committed locally,
  **not pushed**. **NOT hardware-confirmed** — real one-time transition cost on upgrade (every
  existing group membership's `senderId` changes at once, peers see a "new" sender briefly, same
  class as a rejoin already exercised harmlessly in decision 52) needs watching on the next live
  round. See P0b's own section below for the full write-up, and decision 53 for the original
  scoping (including the correction of an inaccurate claim made earlier this session about §9.3
  item 3 already being closed — it wasn't).

- **2026-08-09, decision 52 (`docs/DECISIONS.md`): first hardware round since v0.7.17-dev + 4
  follow-up decisions + L2CAP padding shipped.** Blind-relay pillar and L2CAP CoC both confirmed
  working on real hardware for the first time (3 phones, relay deleted its own group mid-session).
  Found and documented a real, previously-unwritten mechanism: blind/opaque custody only propagates
  at the next connection cycle, not immediately like member traffic — this fully explains the
  round's SOS/presence delays (17-113s) and presence-skew-reject bursts, not a bug. Radar staleness
  and multi-path hop-count variance both confirmed as already-expected behavior, not gaps. Four
  follow-ups decided with the user: blind-relay budget cap folded into P7's own work (not standalone);
  content-lifetime clamp-to-group-expiry question checked against actual code and found **already
  handled** (`expireGroups` runs every ≤30 min, no gap); L2CAP bulk-pipe padding **shipped this
  commit** (trigger condition — L2CAP proven working — was met); blind-relay speed itself deferred
  to decide during P7's actual build (P7 doesn't inherit it by design). Version v0.7.18-dev
  (versionCode 29), 505 tests, detekt clean, both variants green, fresh debug APK built and
  `aapt`-confirmed. Full detail in decision 52's own entry.

- **2026-08-09, decision 51 (`docs/DECISIONS.md`): P7 planning pass, no code.** Resolved 3 stale/
  open `§9.3` items (blind-relay budget stays unbounded for now; content lifetime stays 48h/24h
  deliberately separate — locked; a newly-found L2CAP bulk-pipe padding gap accepted as known for
  now) plus confirmed test phones (Android 12-13/14+) clear the L2CAP API-29 floor. **Designed P7
  itself**, grounded in fresh research against bitchat's actual current source (not the week-old
  summary table): inject via bitchat's `groupMessage` (0x25) packet type, which their own relay
  logic forwards unconditionally with no real handshake/identity needed; route a bitchat-received
  message through the SAME existing frame-ingestion pipeline a GATT frame already uses, so P1's
  flood-forward gives free multi-hop bridging between two separated 20.07 clusters with zero new
  relay logic. **Hard dependency before any implementation: a live spike against a real bitchat
  build** — this design is from reading their source, not testing against a running instance.
  See P7's own rewritten section in Part 7 below for the full design. Still no code — implementation
  waits on the device-test round per the user's own sequencing.

- **2026-08-09, decision 50 (`docs/DECISIONS.md`): diagnostics logging widened ahead of the
  device-test round — no behavior change.** The user asked, before greenlighting the test round
  below: is the exportable `DiagnosticsLog` actually capturing this session's own changes and their
  failure modes, and message-level send/receive timestamps for delay/hop analysis? Audit found two
  real gaps: the entire new L2CAP CoC path (decision 48) wrote zero exportable diagnostics (only
  `logcat`), and `RelayResponder.handleIncoming`'s own top-level catch-all — wrapping every frame
  type's processing — only logged to `logcat` too, meaning a regression anywhere in this session's
  changes could have thrown silently as far as the exported log is concerned. Fixed: L2CAP listen/
  connect/accept/close now all log to `DiagnosticsLog`; `handleSymbolRequest` logs which path
  (`l2cap`/`gatt`) a push actually took; `handleL2capCap` logs channel-open success/failure; the
  `handleIncoming` catch-all now also exports; every remaining bare-`Log.w` auth/signature "dropping"
  path across SOS/courier/evidence/position/presence/nickname now mirrors to `DiagnosticsLog` too.
  For delay/hop tracing: the existing `"NEW sos"` receive log now includes a truncated message id
  (not a person identifier) alongside its hop count; `floodForwardSos` — the one function both a
  freshly-authored message and every relay forward already funnel through — now logs every call,
  including a `BLOCKED` event with reason when TTL is exhausted or no links are open (previously
  invisible either way); `pushFullResRequestNow` (full-res pull requests) went from silent to logged.
  Deliberately did NOT touch `BeaconRadio`/`MeshGattServer`'s own pre-existing `Log.w` sites — those
  are Tier B/connection-layer conditions already covered by prior hardware-confirmed rounds, and
  widening every single warning in the codebase risks flooding the 512KB rotating cap with noise
  during a real multi-hour session, burying the actually-new signal this exists to surface. Same
  export mechanism as before — no new UI, `HomeScreen`'s existing "Export diagnostics" row and its
  `ACTION_SEND` chooser (Drive/email/anything installed) is unchanged. 505 tests (unchanged — pure
  logging, no wire/schema change). detekt clean, both variants green, no `missing_rules.txt`. Version
  v0.7.17-dev, committed locally, **not pushed**. **This is the build going out for the device-test
  round.** Full detail in decision 50's own entry.

- **2026-08-09, decision 49 (`docs/DECISIONS.md`): §4.3 item 3 — Wi-Fi Direct removed outright.
  Item 3 status: L2CAP CoC done (48), WFD removed (this one), Wi-Fi Aware deferred to a later
  slice — item 3 is otherwise done; P5 (Media) is now fully code-complete across all 3 items.**
  Deleted all 5 `transport/wifidirect/` files, `ui/WifiDirectSettings.kt`, both WFD test files,
  every `FRAME_WIFI_DIRECT_CAP`/`HANDOFF`/`ACCEPT` frame/encode/decode/wiring reference, the
  `HomeScreen` opt-in toggle, and the WFD-only manifest permissions/feature (`ACCESS_WIFI_STATE`/
  `CHANGE_WIFI_STATE`/`NEARBY_WIFI_DEVICES`/`wifi.direct` — `NEARBY_WIFI_DEVICES` deliberately not
  kept in anticipation of the still-unimplemented Aware slice; that slice should check its own real
  requirement, not inherit WFD's). No `VERSION` bump — pure removal isn't independently load-bearing
  (decode() already safely no-ops on any unrecognized byte, and decision 48's own v12 bump already
  retired these bytes in anticipation). 505 tests (down from 522 — two whole WFD test files plus 3
  WFD-specific round-trip tests deleted; decision 48's own 11 new tests unaffected). detekt clean on
  the first pass. Both variants green, no `missing_rules.txt`. Version v0.7.16-dev, committed
  locally, **not pushed**. Full detail in decision 49's own entry.

  **Per the user's own explicit instruction this session: next is a device-test round** (covering
  the accumulated backlog since P3, this session's `VERSION` 11/12 wire breaks, and the first-ever
  L2CAP CoC connection-establishment check — nothing in the automated suite touches the real
  `listenUsingInsecureL2capChannel`/`createInsecureL2capChannel` calls), **then P7 (bitchat bridge)**
  — the last phase before the project's sustained field-test milestone.

- **2026-08-09, decision 48 (`docs/DECISIONS.md`): §4.3 item 3 (bulk pipe) — BLE L2CAP CoC,
  additive, DONE.** Per the user's explicit instruction: complete item 3 now, device-test round
  before P7. New `ble/BulkChannel.kt`/`L2capBulkTransport.kt` — a real bulk pipe for
  `FRAME_SYMBOL_REQUEST`/`FRAME_EVID_SYMBOL` traffic only (every other frame type stays on GATT),
  negotiated via new `FRAME_L2CAP_CAP` (0x1F). **Real, confirmed gap**: this project's `minSdk` 26
  is below L2CAP CoC's own API-29 floor — GATT's 400-byte chunking is therefore not merely "the
  universal fallback" in name, it's the ONLY path on three OS versions this app still targets, and
  must stay correct indefinitely. No initiator/responder role restriction (unlike the retired WFD
  accelerator) — a deliberate, reasoned divergence: L2CAP `connect()` has no shared group state to
  corrupt the way `WifiP2pManager.connect()` did, so a race is a harmless duplicate channel, not
  corrupted topology; `openFor`'s per-address `Mutex` just collapses concurrent attempts.
  `RelayResponder.handleSymbolRequest` now prefers an open bulk channel over GATT's own `respond`,
  deliberately WITHOUT GATT's `delay(15)` pacing (a real socket has its own flow control; the pacing
  would defeat the whole throughput point). **NOT device-tested** — a Robolectric spike this
  session (not kept) found `listenUsingInsecureL2capChannel()` returns a non-functional stub
  (`psm=-1`) under test, no real loopback simulation exists for this API; `BulkFraming`'s pure
  stream-framing logic is what's actually unit-tested (11 new tests), connection establishment
  itself is compile-verified only. `MeshFrameCodec.VERSION` 11 → 12. detekt clean, both variants
  green, no `missing_rules.txt`. Version v0.7.15-dev. Full detail in decision 48's own entry.
  (Wi-Fi Direct itself was left untouched by this step, removed separately in decision 49 above.)

- **2026-08-09, decision 47 (`docs/DECISIONS.md`): §4.3 item 2 (fountain coding) slice 2 —
  wiring, DONE. Item 2 is now fully code-complete end to end**, continuing the same autonomous
  session decision 46 started. One clean cutover (a Plan agent designed it first, re-verifying
  everything — `VERSION`, `AppDatabase.version`, migration policy — against actual current source
  rather than trusting the prior session's summary): deletes `FRAME_MANIFEST`/`FRAME_EVID_CHUNK`
  (0x10/0x14, retired) and `MeshProtocol.encodeBitset`/`decodeBitset` outright, replaces them with
  `FRAME_SYMBOL_REQUEST`/`FRAME_EVID_SYMBOL` (0x1D/0x1E) wired through `RelayEngine.ingestSymbol`/
  `symbolDeficit`/`symbolsToSend` and `RelayResponder.handleSymbolRequest`. `EvidenceChunkEntity`/
  `EvidenceChunkDao` renamed to `EvidenceSymbolEntity`/`EvidenceSymbolDao` (`chunkIndex` → unbounded
  `esi`); `EvidenceEntity`/`Frame.EvidMeta` gain `contentLength`. `MeshFrameCodec.VERSION` 10 → 11,
  `AppDatabase.version` 12 → 13 (free — this project has no real `Migration` objects anywhere,
  `fallbackToDestructiveMigration()` unconditionally, a genuinely new finding this slice confirmed).
  **Session chunk budget kept, renamed to `maxSymbolsPerSession`/`consumeSymbolBudget` — deliberately
  NOT deleted**, a considered re-reading of §4.3's own "deletes... the session chunk budget" line
  (that describes the full Tier X target architecture depending on item 3's dedicated bulk pipe, not
  this slice — until item 3 lands, symbols still share the one GATT connection with everything
  else, and the budget's fairness purpose doesn't evaporate just because chunks became symbols; also
  now the sole backstop against a hostile `stillNeed`, no longer bound-checked at decode time).
  WiFi Direct: `maybeAccelerateOverWifiDirect`/`isWfdCapable` deleted (their one call site/reader
  gone); the 5 `transport/wifidirect/` files needed real (if mechanical) fixes the design pass
  hadn't anticipated — they call `MeshFrameCodec.encodeChunk`/`RelayEngine.ingestChunk` directly in
  their own bodies, not just types, so deleting the old mechanism broke their compilation regardless
  of reachability; adapted type-for-type, the actual handoff logic left untouched and still
  effectively dead pending item 3's already-planned WFD removal.

  **A real bug, caught by this slice's own new tests, not by review**: the first version of
  `maybeCompleteFromSymbol` fed a newly-ingested symbol into the decoder only via
  `getOrCreateDecoder`'s rehydrate-on-first-creation step — silently wrong once a decoder had
  already been cached by an earlier read (`symbolDeficit`, called on every connection via
  `framesToPushOnConnect`, routinely runs before any symbol arrives) — rank would never advance.
  `RelayEngineTest`'s own progression test caught it immediately (rank stuck at 0); fixed by calling
  `decoder.addSymbol` explicitly every time, not relying on rehydration alone.

  511 tests (up from 497: 5 deleted with `chunkBytes`, 14 net new — `RelayEngineTest`/
  `EvidenceSymbolDaoTest`/`MeshFrameCodecTest` additions, none of which the retired mechanism ever
  had, including a decoder-rehydration-across-a-fresh-`RelayEngine`-instance restart-survival test).
  detekt clean, both variants green, no `missing_rules.txt`. Version bumped to v0.7.14-dev, fresh
  debug APK built and `aapt`-confirmed, committed locally — **not pushed** (no explicit ask this
  session for that specific action). **NOT hardware-confirmed** — a real `VERSION` 11 wire break on
  top of decision 46's own unconfirmed primitive; adds to the existing next-live-round backlog.

  **§4.3 item 2 (fountain coding) is now fully done, old mechanism deleted, nothing parallel left
  running. Start next here: §4.3 item 3 — a real bulk pipe (BLE L2CAP CoC / Wi-Fi Aware), replacing
  GATT's 400-byte-write-per-round-trip ceiling and finally removing Wi-Fi Direct outright** (closing
  the loop on decision 47's own "left inert" WFD work), neither designed yet. This is the last P5
  item — P5 is complete once it lands, leaving only P7 (bitchat bridge) before the project's
  sustained field-test milestone.

- **SESSION PAUSED-THEN-RESUMED 2026-08-09**: decision 45 (P5 item 1, below) is fully shipped,
  tested, committed — see its own entry for the full story, including a real mid-slice correction:
  the evidence thumbnail shipped cleartext-plus-MAC first, was flagged to the user as new
  passive-exposure surface before committing (any device that merely connects would see a genuine
  visual preview, not just metadata), and the user chose to seal it under the content-epoch key
  instead — same AES-GCM treatment SOS/position bodies already get. 482 tests, detekt clean, both
  variants green, no `missing_rules.txt`. Version v0.7.12-dev, pushed at the user's explicit request
  at the end of that session.

- **2026-08-09, decision 44 (`docs/DECISIONS.md`): P4's fourth and final slice, handover
  mechanics — copy-budget split + rate limiting, DONE. P4 is now fully code-complete.** New
  `CourierHandover.split(copiesRemaining)` — the classic Spray-and-Wait arithmetic §4.2 names
  (`floor(N/2)` given away, `ceil(N/2)` kept, `null` below `MIN_COPIES_TO_SPLIT`=2), pure and
  directly unit-tested including a conservation-property test (`keep+give` always equals the input,
  no copy minted or lost) across every N from 2-20. §4.2's own "cap 8" figure is honestly NOT
  independently enforced by anything this slice builds — automatically satisfied by conservation as
  long as no envelope is ever re-injected above the initial budget of 4, and this slice adds no
  reinjection path, so 8 is never approached; flagged explicitly rather than building enforcement
  for a scenario nothing in this codebase can trigger yet. New `CourierHandoverTracker` (mirrors
  `ConnectionAttemptTracker`'s bounded/LRU/protect-on-access shape) enforces the "1 attempt per
  envelope per 10 min" rate limit, keyed on the peer's resolved stable identity, not the rotating BLE
  address. `RelayResponder.pushCouriersWithHandover` replaces slice 3's placeholder verbatim-forward
  (`reframeStoredCourier`) with a real split: persists the local `keep` value before pushing a frame
  carrying `give`. `RelayEngine.relayableCourierEnvelopes` now also includes blind-carry rows with
  spare budget (own-group rows were already always included) — this is the actual multi-hop spray
  step slice 3 deliberately left disabled pending a real bound on propagation. Two slice-3 tests that
  asserted a blanket "blind-carry always excluded" were updated (not reverted) to the real, narrower
  boundary: excluded only below `MIN_COPIES_TO_SPLIT`, included and genuinely split-and-pushed above
  it. 462 tests (up from 442), detekt clean, both variants green, no `missing_rules.txt`. Version
  bumped to v0.7.11-dev, fresh debug APK built and `aapt`-confirmed. No wire-format change —
  `Frame.Courier`'s shape and `MeshFrameCodec.VERSION` (9) are unchanged from slice 3. **NOT
  hardware-confirmed** — adds to the existing next-live-round backlog (37/38/40/43). Full detail in
  decision 44's own entry and Part 7's own P4 entry.

  **P4 is now fully code-complete (decisions 41-44).** Per the project's sequencing directive, next
  is **P5 (Media)**; the sustained field-test milestone still waits on P5 and P7 (bitchat bridge).

- **2026-08-08, decision 43 (`docs/DECISIONS.md`): P4's third slice, GATT wiring — wire type,
  resolver, member/blind-carry paths, bounded pool with tiers, DONE.** The first slice where a
  courier envelope actually crosses a GATT connection. A Plan agent designed it first (comparable
  scope to decision 38). Key finding that shaped the whole slice: `copiesRemaining` stays inert
  until a later slice's handover arithmetic, so a blind carrier stores an envelope but never
  re-offers it onward — multi-hop propagation without a real bound would be an unbounded flood
  wearing a "bounded" name. `FRAME_COURIER: Byte = 0x1C`, `MeshFrameCodec.VERSION` 8 → 9.
  `GroupRepository.resolveGroupKeyByCourierTag`/`matchCourierTag` mirror the handle resolver exactly
  (with a dedicated regression test for the one real gotcha: `COURIER_TAG_LEN` must be passed
  explicitly, not left at `candidateAdvertisementIds`' 6-byte default). New `CourierPool` object
  (`ble/`) — pure, unit-tested admission policy for the "20-of-40 reserved" bounded pool, taken
  verbatim from `PLAN-v2.md`'s own spec table, own-group slots a hard floor (never soft-prioritized),
  neither tier ever hard-rejects (evicts its own oldest sibling instead). `RelayEngine.
  admitCourierEnvelope` is the single gate both insertion paths now share, wrapped in this
  codebase's first `db.withTransaction` use to close a real concurrent-connection race. Push-on-
  connect wiring fits entirely inside the existing `CatalogFilter` mechanism — no new per-connection
  step, no new budget constant; own-group envelopes get proactively pushed, blind-carry rows are
  held/advertised but never proactively re-offered to a third peer. 442 tests (up from 416), detekt
  clean, both variants green, no `missing_rules.txt`. Version bumped to v0.7.10-dev, fresh debug APK
  built and `aapt`-confirmed. **NOT hardware-confirmed** — adds to the existing next-live-round
  backlog (37/38/40). Full detail in decision 43's own entry and Part 7's own P4 entry.

- **2026-08-08, decision 42 (`docs/DECISIONS.md`): P4's second slice, persisted courier envelope +
  local creation, no relay wiring, DONE.** Genuinely new persisted storage — confirmed
  `OpaqueFrameRelay` (SOS/position/presence's existing blind-relay mechanism) is the wrong shape by
  checking `RelayResponder`'s `opaqueSos` directly: it uses the same 3-minute default max age as
  position/presence, not a longer one, so SOS's own blind custody was never meant to survive a real
  partition either — confirming couriers need real Room persistence, not a config tweak to the
  existing in-memory class. `CourierEnvelopeEntity` (`AppDatabase` v10 → v11) mirrors `SosEntity`'s
  shape field-for-field, including nullable `groupId`/`senderId` from day one (mirroring
  `EvidenceEntity.groupId`'s decision-38 precedent for the blind-carry case, even though this slice
  only ever populates both). `RelayEngine.createCourierEnvelope` mirrors `createSos` exactly,
  deliberately does NOT bump the catalog epoch (nothing reads courier envelopes for pushing yet, so
  that signal would be false). Pruning wired into the existing periodic sweep on its own 24h cutoff,
  proven independent of evidence/SOS's 48h one by a dedicated test. `createCourierEnvelope` itself
  has no direct test — same pre-existing Keystore/Robolectric constraint `createSos`/`createEvidence`
  already live with, not a new gap; the persistence layer (the actually-new part) gets direct DAO-
  level coverage instead. 416 tests (up from 412), detekt clean, both variants green, no
  `missing_rules.txt`. Version bumped to v0.7.9-dev, fresh debug APK built and `aapt`-confirmed.
  Zero new production call sites — nothing in the running app calls `createCourierEnvelope` yet.
  Full detail in decision 42's own entry and Part 7's own P4 entry.

- **2026-08-08, decision 41 (`docs/DECISIONS.md`): P4's first slice, courier tag + envelope
  seal/open, crypto construction only, DONE.** First step of P4 (Couriers, §4.2) — bitchat's
  group-addressed courier model, an opportunistic supplementary delivery path for partitions flood-
  relay + 48h content retention alone can't bridge in time. A Plan agent mapped existing patterns
  first (mirroring decision 38's own parallel-research approach) and recommended crypto-only as
  slice 1 — SOS's own decision-37 bug (a fully-framed message stored where raw ciphertext was
  expected) happened exactly at the seal/frame boundary this slice proves correct in isolation,
  before storage-shape or handover-arithmetic decisions (deferred to slices 2-4) get bolted on.
  `CryptoUtils.rotatingAdvertisementId`/`candidateAdvertisementIds` gained a `truncateLen` param
  (generalized in place, same precedent decision 38 set for `windowSeconds` — every existing call
  site passes none, unchanged) plus `COURIER_TAG_WINDOW_SECONDS` (86400s, a UTC day) and
  `COURIER_TAG_LEN` (16, per §4.2's own wording) — a THIRD domain-separated partition of the same
  construction, proven pairwise-disjoint from the beacon's 60s and the GATT handle's 72h windows for
  2020-2100. `MeshFrameCodec.courierTag`/`candidateCourierTags` wrap it; `sealCourierBody`/
  `openCourierBody` mirror `sealSosBody`/`openSos` field-for-field, sealing under the SAME
  `contentEpochKey` SOS/position already use (24h window already matches the courier tag's UTC-day
  window exactly — no new key derivation needed). Zero production call sites: no `Frame.Courier`, no
  `FRAME_COURIER` byte, no storage, no GATT wiring, no `VERSION` bump — purely additive, unreachable
  from any existing code path. 412 tests (up from 399), detekt clean, both variants green. Version
  bumped to v0.7.8-dev, fresh debug APK built and `aapt`-confirmed. Nothing to hardware-confirm yet
  (no behavior change) — that starts once slice 3 wires an actual GATT exchange. Full detail in
  decision 41's own entry; slices 2-4's breakdown is in Part 7's own P4 entry below.

- **2026-08-08, decision 40 (`docs/DECISIONS.md`): P6's fourth and final slice, frame padding to
  size buckets, DONE — P6 is now code-complete.** Closed the one remaining P6 item from the
  2026-08-07 checkpoint. The Explore research pass that had failed at the end of that session
  (spend limit, before returning any findings) was relaunched fresh first, per that checkpoint's own
  instruction — it confirmed this app has no MTU-aware fragmentation anywhere (every frame is one
  ATT write, no chunking fallback) and produced the definitive per-frame-type GATT-vs-Tier-B table
  the padding design needed. `MeshFrameCodec.padGattFrame`/`unpadGattFrame` wrap the GATT
  **transport** (`MeshGattClient.write`/`MeshGattServer.notify` and their two receive-side
  counterparts), not `encode`/`decode` themselves — `FRAME_POSITION`/`FRAME_CATALOG_FILTER` are
  byte-identical whether sent over GATT or embedded in a Tier B beacon, and Tier B's 251-byte budget
  could never absorb a 256-byte padding floor; placing padding at the transport choke point excludes
  Tier B automatically (it never calls those four functions) without needing a per-frame-type
  allowlist. `MeshFrameCodec.VERSION` 7 → 8 (outer wrapper, no inner field change — see decision 40's
  own note on why the bump still happened). 399 tests (up from 390), detekt clean, both variants
  compile/test/assemble green, no `missing_rules.txt`. Version bumped to v0.7.7-dev, fresh debug APK
  built and `aapt`-confirmed. **NOT hardware-confirmed** — stacks on decisions 37/38's own
  unconfirmed `VERSION` bumps, next live round needs all of 37/38/40 together (39 is independently
  unconfirmed via a different mechanism and can ride the same round). Full detail in decision 40's
  own entry and Part 7's own P6 entry.

  **P6 is fully shipped (decisions 37-40): SOS body encryption, rotating group handle,
  content-sealing epoch key, frame padding — all code-complete, none hardware-confirmed yet.** Per
  the project's explicit sequencing directive, next is **P4 (Couriers)**; the sustained field-test
  milestone still waits until only P5 (Media) and P7 (bitchat bridge) remain outstanding.

- **2026-08-07, decision 39 (`docs/DECISIONS.md`): P6's third slice, content-sealing epoch key,
  DONE — and a correction to this document's own §4.4.** §4.4 originally claimed the epoch key
  ratchet "gives forward secrecy against later seizure." It does not, and cannot as a non-interactive
  design: the root key must stay permanently retained regardless (decision 38's wire handle and
  `GroupRepository.getShareCode`'s invite code both need it forever), so any non-interactive
  derivation from it — stateless or a stateful hash-ratchet with deletion — is trivially
  recomputable by anyone holding that root key. Real forward secrecy needs an interactive DH step,
  which §4.4 already rejected for non-interactivity (this mesh partitions constantly). Built the
  honest stateless version anyway (`CryptoUtils.contentEpochKey` = `HKDF(rootKey, epoch)` via Tink's
  `subtle.Hkdf`, 24h epochs, fully stateless), which now seals SOS/position bodies and macs
  evidence-meta/nickname/presence/Tier-B-SOS-preview instead of the root key directly — genuinely
  useful for bounding an independently-leaked single derived key to ~24h instead of the group's whole
  life, just not what the old wording implied. See decision 39's own entry for the full ELI5 and the
  two-independent-epoch-concepts distinction from decision 38's (unrelated, unchanged) wire handle.
  390 tests (up from 381), detekt clean, both variants compile/test/assemble green (no
  `missing_rules.txt` — the new Tink `Hkdf` proguard rule is sufficient). Version bumped to
  v0.7.6-dev, fresh debug APK built and `aapt`-confirmed before committing. **No wire version bump**
  this time (byte layouts unchanged, only which key opens them) — **NOT hardware-confirmed**, same
  caveat as 37-38 via a different mechanism (silent open/verify failure, not decode rejection). Full
  detail in Part 7's own P6 entry. (Frame padding, then the last P6 item, shipped the next session —
  see decision 40 above.)

- **2026-08-07, decision 38 (`docs/DECISIONS.md`): P6's second slice, rotating group handle, DONE.**
  Closes `PLAN-v2.md` §4.4's cleartext-`groupId` traffic-analysis gap — every relayed frame type
  (SOS/position/evidence-meta/nickname/presence) now carries an opaque `HMAC(groupKey, epoch)`
  handle instead, reusing (generalized, not duplicated) the beacon's own existing
  `CryptoUtils.rotatingAdvertisementId` construction under a new, much wider 72h window (a GATT
  handle is computed once and forwarded verbatim for a frame's whole relay life, unlike a beacon id
  re-derived every ~60s — see decision 38's own entry for the full derivation). Found and fixed a
  real bug along the way: `opaqueSos`'s `.framesToRelay(...)` was never called anywhere since
  decision 37 added it — SOS blind custody accepted frames but never forwarded them. Also gave
  nickname a genuinely new blind-relay path (it never had one; the old scheme's version was already
  a dead end, traced via every nickname push path being scoped to active groups only). 381 tests (up
  from 370), detekt clean, both variants compile/test/assemble green. Version bumped to v0.7.5-dev,
  fresh debug APK built and `aapt`-confirmed before committing. **NOT hardware-confirmed** —
  `MeshFrameCodec.VERSION` 7 is a wire break, same as decision 37's own (also still unconfirmed) —
  next live round needed for both together. Full detail in Part 7's own P6 entry.

- **2026-08-07, decision 37 (`docs/DECISIONS.md`): P6's first slice, SOS body encryption, DONE.**
  Closes `NEXT_STEPS.md`'s long-flagged "SOS text is authenticated but not encrypted" gap — SOS
  content is now AES-GCM sealed under the group key, same treatment position has had since v2/v3.
  This decision started as a mid-session WIP checkpoint (commit `405073e`: main source compiled,
  tests did not) and was finished this session: the test rewrite surfaced and fixed a real
  double-framing bug in `RelayEngine.createSos` (see decision 37's own entry for the mechanism) that
  would have broken every self-authored SOS message on send — caught before it ever reached a real
  device. 370 tests, detekt clean, both variants compile/test/assemble green. Version bumped to
  v0.7.4-dev, fresh debug APK built and confirmed via `aapt dump badging` (not assumed) before
  committing. **NOT hardware-confirmed** — `MeshFrameCodec.VERSION` 6 is a wire break, no
  pre-checkpoint test APK can talk to this build until reflashed (superseded by decision 38's
  VERSION 7 above — the two need the same next live round). Full detail in Part 7's own P6 entry.

- **2026-08-07, user directive (decision 36, `docs/DECISIONS.md`): Tier 2 (synthetic radio load,
  ESP32/BlueZ boards) REMOVED from this project's testing methodology entirely** — never had a
  realistic path to actually happening, had become pure process weight. §6.4 now defines a two-tier
  model (Tier 1 simulator + Tier 3 three real phones only); every remaining "Tier 2" reference in
  this document is either historical or has been struck. **Same directive marks P2 STATUS = PASSED**
  — code-complete and Tier 1-verified, AWAITING (not blocked on) Tier 3 confirmation, which is in
  progress as of this edit on the v0.7.3-dev APK. Full detail in Part 7's own P2 entry.
- **Shipped and committed, on `main`, PUSHED to origin (public):** P0a (crowd simulator), P0b (peer
  identity), P1 (SOS flood-forward), P3 (persistent links) — v0.6.0-dev through v0.6.3-dev.
- **Hardware-confirmed across four live 3-phone test rounds** (2026-08-05): message-delay fix,
  radar-staleness fix, duplicate-connection-callback fix, Bluetooth off→on recovery fix. Full detail
  in `docs/DECISIONS.md` decisions 18-22.
- **P2 (broadcast tier): both Tier-1 sim open questions resolved (decisions 23-25), AND production
  wiring is now FOUR slices deep (decisions 26-29, same day)** — deliberately narrow slices, same
  discipline as P1's own "SOS only first" phase. Full reasoning for each is in `docs/DECISIONS.md`;
  this is a pointer, not a replay:
  - **Sim (23-25):** decision 23's 3-way question resolved as option (c) (sightings own-group-
    scoped); dissolved a boundary-bug finding but surfaced decision 24's (suppression could pin at
    degree 1); decision 25 found and fixed the real root cause **in production**,
    `TrickleTimer.onSighting(sourceId)` now dedupes within a window.
  - **Slice 1 (26) — presence hop-gradient:** the old Coded-PHY-only "long-range channel" was
    **generalized in place** into Tier B (gate loosened to `extendedAdvertisingSupported` alone,
    Coded PHY now opportunistic), new payload adds `presenceHop` for a real multi-hop gradient with
    zero GATT connections, hardware `ScanFilter` + degree-gated batching added.
  - **Slice 2 (27) — single-hop position:** reuses `MeshFrameCodec.encodePosition`'s full output
    verbatim, resealed on a fixed ~20s cadence, verified via `RelayResponder.signatureCheckPasses`.
    Single-hop only — no natural "relay" for a connectionless channel.
  - **Slice 3 (28) — SOS hop-gradient, done right:** keys on the REAL SOS id (new
    `HopTracker.bestActiveSos`), deliberately NOT the way an earlier, reverted mechanism mixed a
    rough aggregate with exact tracking (confirmed via `grep` that the legacy beacon's own `sosHop`
    field has sat unread ever since). Forced a wire-format redesign (both optional blocks now
    always length-prefixed) — a safe breaking change, zero devices have run this format.
  - **Slice 4 (29) — SOS content preview, a genuine threat-model call:** stopped and asked before
    building this one — reusing the existing `sosMacInput` scheme would broadcast `senderId` in the
    clear, escalating `NEXT_STEPS.md`'s already-flagged-but-unresolved "SOS is cleartext" gap from
    connection-gated (GATT) to passively-readable-by-anyone (Tier B). **User chose to build a
    version that avoids the new exposure**: new `MeshFrameCodec.broadcastSosMacInput` deliberately
    excludes `senderId` — a second, non-interchangeable mac scheme, not a reuse. Position is
    dropped from any cycle carrying SOS content (budget forces the priority call). Verified on
    receipt but not stored (missing fields a real `SosEntity` needs) — full UI surfacing is a named
    follow-up.
  - 337 tests, detekt clean, both variants compile/test/assemble (incl. `lintVitalRelease`) green
    — **NOT hardware-confirmed**, same as this channel's Coded-PHY-only predecessor always was, now
    broader still (every Tier B mechanism is new and untested on real hardware).
- **First hardware round on v0.7.0-dev (2026-08-06, 3 phones) found and fixed two real bugs, one
  gap, and confirmed one report as design-intentional (decision 30):** a presence hop-count bug in
  this same session's own decision 26 code (two `HopTracker` calls per scan result with the same
  source let a worse reading undercut a better one it had just set — merged into one call, two
  regression tests added); a dismantled group's positions lingering on the radar (`PositionTracker`
  had no way to hear about `dismantleGroup` — fixed with an immediate `clearForGroup` at the delete
  call site plus a periodic `pruneOrphaned` safety net in `MeshService`'s existing sweep); and a
  nickname set after a P3 link was already open never reaching that peer (nicknames were only ever
  pushed once, on connect — now also pushed every periodic `refreshFramesToPush`, same fix decision
  20 already gave presence/position). Radar contrast/brightness/dot-sharpness also raised a second
  time per this round's UI feedback. Full detail in `docs/DECISIONS.md` decision 30. **These fixes
  are NOT yet hardware-confirmed themselves** — next round needed.
- **Same day, decision 29's own named follow-up closed (decision 31): full UI surfacing of the
  broadcast SOS content preview.** New `BroadcastSosPreview` — in-memory-only, no staleness clock of
  its own, freshness delegated entirely to `HopTracker.bestActiveSos` (avoiding a second,
  independently-aging channel feeding one SOS display — the exact bug shape the historical Pass 13
  SOS-hop bug already was). Wired through the identical group-teardown lifecycle decision 30 just
  gave `PositionTracker`. `NavigateScreen` now shows it, quoted and labeled "unconfirmed preview,"
  suppressed once the real `SosEntity` for that id exists in Room. Full detail in
  `docs/DECISIONS.md` decision 31. **Also NOT hardware-confirmed** — no live SOS was raised during
  decision 30's round.
- **Same day, decision 27's own "own gossip design, out of scope" deferral closed (decision 32):
  multi-hop position propagation over the broadcast tier.** Our own GPS fix always wins Tier B's one
  position slot when we have one; when we don't, `BeaconRadio.relayedPositionFrameForBroadcastTier`
  relays the closest position we're holding for someone else instead, reusing `RelayResponder.
  selectPositionsToRelay` and `MeshFrameCodec.reframePositionForRelay` unchanged — no new loop-
  prevention mechanism needed, since plain distance-vector relaxation (already relied on for
  presence/SOS hop-gradients) is loop-safe on a broadcast medium without split horizon, which has no
  broadcast equivalent anyway. Found and fixed a real bug while wiring the receive side: it was
  reading the sealed body's frozen inner hop instead of the envelope hop relaying actually
  increments — invisible while single-hop, would have silently mis-tracked distance the moment hops
  started varying. Full detail in `docs/DECISIONS.md` decision 32. **Also NOT hardware-confirmed.**
  - 348 tests (unchanged — pure composition of already-tested pieces plus one bug fix in existing
    call paths), detekt clean, both variants compile/test/assemble (incl. `lintVitalRelease`) green.
- **Same day, three more decisions (33-35) closed the rest of P2's own scope, per explicit user
  direction — `maxPositionRelayHops` raised, the catalogue filter shipped, and a real architectural
  miss fixed.** Full detail in `docs/DECISIONS.md`; pointer only:
  - **Decision 33 — position relay hop ceiling 4 -> 120** (user's own ask: real multi-km reach
    through an unbroken relay chain). Landed on 120, the largest value the 1-byte hop field safely
    supports below `UNKNOWN_HOP`'s 255 sentinel — hundreds of km would need a 2-byte field, a real
    wire break deliberately not made this pass. Also fixed `RadarView`'s stale-dot fade, previously a
    flat 180s window blind to a position's real (now much larger) staleness budget.
  - **Decision 34 — catalogue digests over Tier B, the last P2-scoped payload item.** Stopped first:
    GATT's own item-count-scaled filter sizing would leak a group's approximate content volume to any
    passive scanner, worse still since Tier B beacons are per-group. **User chose dynamic sizing
    anyway**, after seeing the concrete functional cost of a fixed size (false-positive rate collapses
    past ~50 items) — a deliberate, weighed exception, not a reversal of the standing privacy-first
    preference. Found and fixed a real budget bug first: position + the bare SOS hop-gradient (no
    content) + a maxed filter overruns 251B on its own — the filter is now the lowest-priority field,
    dropped first when it doesn't fit. Broadcast side only; receive-side consumption is a named
    follow-up.
  - **Decision 35 — the SOS/message split.** Every message in this app has always been an
    `SosEntity` — there was never a separate casual-chat type, so every chat message silently
    triggered the loud alarm notification and Tier B's SOS hop-gradient/preview. **User's fix: reuse
    everything, gate only the alert-specific side effects behind one new `SosEntity.isAlert` flag** —
    storage/relay/catalog-sync stay unconditional for every message; only the hop-gradient,
    notification, and (transitively, for free) the Tier B preview require it. `GroupChatScreen`
    gained a dedicated SOS action separate from its now-quiet default Send. Bumped
    `MeshFrameCodec.VERSION` 4 -> 5 (a real wire break — no pre-decision-35 device can talk to this
    build until reflashed). Landed alongside: the SOS preview cap trimmed 100 -> 65 bytes.
  - 367 tests, detekt clean, both variants compile/test/assemble green. **NOT hardware-confirmed.**
- **Version bumped to v0.7.3-dev and a fresh in-tree debug APK is ready to hand off for its own
  hardware smoke-test round**: `releases/20.07-v0.7.3-dev-debug.apk` (replaces the v0.7.1-dev one —
  the v0.7.2-dev bump was superseded before its own APK was ever built, folded into this checkpoint
  instead), built from the exact commit this checkpoint describes, `versionCode`/`versionName`
  confirmed via `aapt dump badging` before committing, not assumed. This is the FIRST APK containing
  decisions 31-35's work — nothing before this point was installable with it, and because of decision
  35's `VERSION` bump, no PRIOR test APK can talk to it. **GitHub Pages is still stale** (still serves
  v0.6.3-dev at `https://karmaneggs.github.io/20.07/`) — that refresh is a separate, not-yet-done
  step; the in-tree APK is what's ready right now.
- **Committed, on `main`, through `1e63778`** (the v0.7.3-dev version bump + debug APK; decisions
  31-35's own code+docs landed first as `5737c81`; decision 30's own checkpoint landed earlier as
  `ccd29f6`) **as of two checkpoints ago — since then, decision 36 (`c3730bc`), decision 37
  (`594973a`, v0.7.4-dev), and now decision 38 have landed on top** (decision 38's own commit is
  this checkpoint's own commit, version-bumped to v0.7.5-dev with a fresh debug APK, per the same
  "confirm via `aapt`, not assumed" discipline as every prior version bump). Working tree clean as
  of this checkpoint. If you find uncommitted changes when resuming, they're from a session after
  this one.
- **Sequencing, stated explicitly (corrected 2026-08-06, user clarification): the sustained
  multi-hour/multi-device field test is planned to happen AFTER P2 is built and sim-hardened, not
  as a gate P2 has to wait behind.** A 10-device/multi-hour session is expensive and not easily
  repeatable, so the intent is one field test that validates the WHOLE v2 stack (P0a/P0b/P1/P2/P3)
  at once — "logic test harshly [in sim], then take to field for deep QC," in the user's own words —
  rather than spending it early on just P1+P3 and needing a second one later once P2 exists. This
  means: **P1+P3's four short ad hoc 3-phone rounds are their interim validation and that's expected
  to be enough to keep building on for now** — they are not blocked waiting on the big session, it's
  deliberately deferred. And **P2 should keep being built now** (full presence/position/SOS/
  hop-gradient payload model, degree-gated scan batching, actual production wiring, with its own
  short ad hoc hardware smoke-tests as it goes, same pattern P1/P3 used) so it's ready in time for
  that one eventual field test, rather than sitting idle waiting for a session that is itself
  waiting on P2. (This corrects framing in earlier entries below and in Part 7's P2 status that read
  the sustained-session gate as blocking P2 production wiring — it does not; read this bullet as
  authoritative where they disagree.)
- **Next planned test**: user is running a full-day session across up to 10 devices, date TBD from
  their side (as of 2026-08-06, still not run) — will bring back logs afterward for review. This is
  a bigger test than anything done so far (device count and duration both); treat its findings as
  the most current information available, ahead of everything summarized above.
- **Sequencing, refined again (2026-08-07, user directive): the field test now also waits on P4 and
  P6, not just P0a-P3.** Explicit scope: P5 (Media) and P7 (bitchat bridge) are the only phases
  allowed to still be outstanding when that field test happens — P4 (Couriers) and the rest of P6
  (frame padding — 3 of 4 P6 items landed 2026-08-07 as decisions 37-39) must be
  code-complete first. Practical consequence: keep building P4 and P6 now, same "build ahead of the
  one field test" discipline the P2 bullet above already established — this just widens which phases
  that applies to.

**Written:** 2026-07-31, after reading bitchat's WHITEPAPER.md end-to-end and re-reading our own
transport layer and live diagnostics 8/9/10 against it.

---

## Verdict in one paragraph

We did not build a slower version of bitchat. We built a **different class of protocol** and then
asked it to do bitchat's job. bitchat is a *forwarding* protocol: a packet arriving on any open link
is re-emitted on the other links within 10–220 ms, and the only state involved is a 1000-entry
dedup set. 20.07 is a *synchronisation* protocol: two phones must complete a connection handshake,
negotiate an MTU, exchange Bloom filters over their whole catalogue, compute a deficit, transfer,
and disconnect — and only then has one edge of the graph moved data. The consequence is the whole
scaling story:

> **A forwarding protocol only needs the graph to be _connected_. A sync protocol needs every edge
> to be _visited_ inside the message's useful lifetime.** Staying connected costs O(1) work per
> node. Visiting every edge costs O(degree) work per node — and degree *is* crowd density.

Everything below is downstream of that one sentence.

---

## Part 1 — What is actually wrong (with evidence)

### 1.1 The primary delivery path is the expensive path

We have both mechanisms bitchat has, but wired in the opposite order.

| | bitchat | 20.07 |
|---|---|---|
| Primary delivery | immediate flood on open links | connect → catalogue-sync → disconnect |
| Backfill | GCS gossip sync every ~15 s | *(none — the sync **is** the primary path)* |
| Per-hop latency | jitter 10–220 ms + link RTT ≈ **0.1–0.3 s** | one reconnect cycle ≈ **45 s** |
| 7-hop reach | ~1–2 s | not reachable inside content lifetime |

`RelayResponder.framesToPushOnConnect()` is the only way SOS, evidence headers and nicknames leave a
device. There is no code path anywhere that forwards a frame the moment it arrives on an already-open
link. That is not a tuning problem; it is the architecture.

Our own code already documents the consequence. `HopTracker`:

```
// a 2-hop reading's worst-case propagation time is ~45s + 45s = 90s, the ENTIRE staleness window,
// with zero slack for connection setup or ordinary jitter.
```

and the fix applied was `effectiveStaleMs = 180_000 + 45_000 × (hop − 1)` — i.e. we widened the
*staleness window* to accommodate the propagation delay rather than removing the delay. At 4 hops
that window is 315 s. A crowd-crush warning that is allowed to be five minutes old is not a warning.

### 1.2 The connection machinery is running flat out and delivering almost nothing

From `20.07 mesh diagnostics 10` (24.7 minutes, 2–3 phones, `[sync]` lines report per-connection yield):

- **61 catalogue syncs completed. 57 of them pushed zero items** (`pushed=0`) — a 93 % waste rate.
- 30 `synced ok` events ≈ one completed connection every 50 s, matching `reconnectCooldownMs = 45_000`
  exactly. The radio is busy essentially continuously.
- 19 distinct peer-address prefixes appeared, for 2–3 physical phones (see 1.3).
- `[relay] forwarding N opaque frame(s)` climbs 4 → 27 → 36 → **38** across the session. Every single
  connection re-pushes the entire accumulated opaque carry set.

Diagnostics 9 is the same shape (24 of 53 syncs pushed nothing). So: on a 3-phone test, the majority
of all connection work produces no delivery, and the per-connection cost is *growing* with how much
traffic the device has carried. Both curves point the wrong way as density rises.

### 1.3 Peer state is keyed on an identifier that does not survive

BLE resolvable private addresses rotate (~15 min nominally, faster in practice). `NEXT_STEPS.md` item
D1 records 46 distinct addresses in 23 minutes for 2–3 phones; the diagnostics above show 19 distinct
truncated prefixes in 25 minutes. Everything downstream is keyed on that address:

- `ConnectionAttemptTracker.cooldownUntil` — so the 45 s cooldown frequently does not apply, and a
  reconnect storm is indistinguishable from a new peer arriving.
- `HopTracker.lastSource` route ownership — the mechanism that lets a route be revised *upward*
  gets stranded on an address that no longer exists (the code comments already admit this and add a
  "transfer ownership on confirm" patch to work around it).
- `RelayResponder.peerWfdCapable`, `sessionBudget`, `catalogItemBudget`, `negotiatedMtu`,
  `syncedThisSession` — all LRU-bounded maps of a value that churns.

bitchat keys everything on a stable 8-byte peer ID (first 8 bytes of SHA-256 of the Noise static
key). They pay for that with linkability — which their own §8 names as their weakest property. We
pay for *not* having it with a peer-state layer that is structurally unreliable. There is a middle
option (§5.2) that neither project currently takes.

### 1.4 Connection slots are a hard ceiling that density eats

Android chipsets share a central+peripheral GATT pool commonly around 4–7 links.
`MeshGattClient.maxConcurrentClientConnections = 3` plus `MeshGattServer.maxConcurrentServerConnections = 4`
(soft, deliberately unenforced) already sits at that ceiling with zero headroom.

Both projects hit the same ceiling. The difference is what the ceiling *means*:

- **bitchat:** 7 persistent links is plenty. A packet injected anywhere floods the entire connected
  component in ~200 ms per hop. You need the graph connected, nothing more.
- **20.07:** with D neighbours, a full sync round takes `D/3 × (setup ≈3 s + session 15–20 s)` ≈ **6 × D
  seconds**. D = 20 → 2 minutes. D = 100 → 10 minutes. D = 500 → 50 minutes. The 45 s cooldown stops
  being the binding constraint past about D = 8, because you cannot get around the ring that fast
  anyway. **Density monotonically degrades delivery.**

### 1.5 The latency-sensitive traffic is stuck behind the slowest transport

Presence, position and SOS-hop are small (≈70–120 B), frequent, and worthless when stale. They
currently travel *only* over GATT connections — the highest-setup-cost channel we have. The radar,
which is the app's headline feature, therefore refreshes once per reconnect (~45 s/hop) against a
180 s useful life. `NEXT_STEPS.md` D2a already flags this as "structurally marginal at 2 hops."

Meanwhile the 8-byte beacon (`type + rotatingId(6) + sosHop`) is broadcast continuously to every
scanner in range for free, and carries almost nothing. We are broadcasting our cheapest data and
connection-syncing our most time-critical data. That is inverted.

### 1.6 Media is pushed to everyone, including people who cannot read it

`RelayEngine.CHUNK_SIZE = 400`, `maxChunksPerSession = 150`, `CONTENT_MAX_AGE_MILLIS = 48 h`, and
blind relay means non-member phones carry and re-offer every chunk. A 300 KB photo is 750 chunks =
5+ full sessions = 4+ minutes against one perfect peer — and then every phone that carried it
re-offers it to every phone it meets for 48 hours. In a crowd that is a broadcast storm of
ciphertext that most carriers can never decrypt and most recipients never asked for.

### 1.7 What v1 gets right and v2 must not lose

Not everything here is worse than bitchat. Keep, deliberately:

- **Rotating group beacon IDs** (`HMAC(groupKey, 60 s window)`). bitchat's never-rotating peer ID is
  their self-declared weakest point; we already avoid it at the beacon layer.
- **Blind relay of opaque frames for groups we hold no key for.** bitchat relays opaque *packets*;
  we relay for *group contexts we cannot read at all*. That is a stronger property and it is ours.
- **Ephemeral group expiry baked into the join code** (absolute timestamp, every joiner agrees).
  bitchat has no equivalent — their groups are indefinite.
- **Per-sender Ed25519 + TOFU pinning**, additive over the group-key HMAC.
- **In-place `setAdvertisingData` on an `AdvertisingSet`** — no radio restart to change the beacon.
- The engineering discipline: 233 tests, detekt gate, `docs/DECISIONS.md`, hardware-gated rollout.

---

## Part 2 — bitchat, point by point, with what we should take

| bitchat mechanism | Concrete parameters | Do we have it | v2 verdict |
|---|---|---|---|
| Immediate relay on open links | jitter 10–220 ms, wider when dense | **No** | **Adopt.** The core change. |
| TTL flood | origin TTL 7; clamp to 5 when degree ≥ 6; full depth when degree ≤ 2 | TTL 8 exists but is only decremented on ingest, never drives a forward | **Adopt**, incl. degree clamping |
| Dedup LRU | 1000 entries, 5 min, keyed (sender, timestamp, type, payload digest) | `SeenMessageEntity` in Room, 48 h | **Adopt** the in-memory hot layer; keep Room as cold layer |
| Fanout subsetting | deterministic message-ID-seeded subset ≈ log₂(degree) | No | **Adopt.** This is what makes density cheap. |
| Directed traffic | TTL−1, tight jitter, never subset | No | Adopt with private messaging (P4+) |
| Source routing | used when a bidirectional path is confirmed; falls back to flood | No | **Defer.** Needs stable identity first; flooding is sufficient at our scale. |
| Announces | 4 s isolated → 15–30 s jittered when connected; reachable 60 s after contact; carries ≤10 neighbour IDs | Beacon is continuous; presence rides GATT | **Adopt the cadence.** **Reject the neighbour list** — it hands a sniffer the local adjacency graph (their §8 admits this) and our threat model cannot absorb that. |
| Gossip sync | 1000-packet cache, GCS filters every ~15 s, 6 h window | `CatalogFilter` Bloom, once per connection | **Keep, demote to backfill**, move to a periodic exchange on a live link |
| Fragmentation | ~469 B fragments (Android build uses 150 B for iOS parity), 128 assemblies, 30 s timeout, 1 MiB cap | 400 B chunks + manifest/bitset | Replace with fountain coding (§4.3) |
| Couriers / store-and-forward | ≤3 peers, 5 envelopes (mutual favourites) / 2 (verified), pool 20-of-40, 16 KiB, 24 h, spray budget 4 cap 8, half-handover on meeting, 16-B tag = HMAC(recipient key, UTC day), handover throttle 1/envelope/10 min | No | **Adopt, group-addressed** (§4.2) |
| Noise XX live sessions | Curve25519 / ChaCha20-Poly1305 / SHA-256 | No — one static group key, forever | See §4.4 — different answer for us |
| Padding | 256/512/1024/2048 buckets, Noise packets only | No | Adopt for all frame types (they list this as future work; we can do it first) |
| Nostr fallback | private msgs to mutual favourites over public relays, optional Tor | No | **Reject for v2** (§3, §4.5) |

---

## Part 3 — Can v2 be bitchat-compatible?

Short answer: **partially, and only at a layer that does not cost us our threat model.** Three
levels, in increasing cost:

### Level 0 — adopt the architecture, keep our wire (RECOMMENDED, this is where the value is)

Take the routing design — immediate forward, TTL with degree clamping, dedup LRU, relay jitter,
fanout subsetting, announce cadence, courier spray-and-wait — and implement it on **our own service
UUID, our own rotating IDs, our own crypto**. Zero interoperability, ~95 % of the scaling benefit,
zero threat-model cost. Nothing about bitchat's *design* requires bitchat's *identity model*.

### Level 1 — optional bitchat bridge (RECOMMENDED as a later, opt-in phase)

A separate, user-toggled advertiser/scanner pair on bitchat's UUIDs
(`F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C` / `A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D`) that injects our
group traffic into their mesh as opaque payload packets. They already relay opaque `noiseEncrypted`
packets without reading them, so their network can carry our ciphertext without us becoming a
bitchat client, without exposing our identities to it, and without adopting their peer ID.

Value: in any crowd where both apps are present, we inherit their relay density for free. Cost: a
second radio session (battery, and contention with our own advertising), and a public
"this device runs bitchat" signature — which is a real problem in jurisdictions where bitchat has
been geoblocked or banned. **Must be off by default and clearly labelled.**

### Level 2 — full bitchat client compatibility (RECOMMENDED AGAINST for v2)

To actually interoperate as a peer we would have to adopt: their packet header, their fragment size
(150 B for cross-platform parity — a third of our current chunk), Noise XX sessions, and critically
their **stable 8-byte peer ID derived from a never-rotating key**.

That last one is a direct contradiction of 20.07's entire discovery design. Our rotating
`HMAC(key, 60 s window)` beacon exists precisely so a passive listener cannot enumerate participants
or follow a device between places. bitchat's whitepaper §8 names that exact capability as their own
weakest property. Our users are protesters facing phone seizure and state-grade passive collection;
theirs are conference-goers and festival crowds. **Adopting their identity model to gain their
network is a bad trade for our threat model**, and their protocol has no versioned compatibility
commitment, so it would be a permanent chase.

**Recommendation: build Level 0 in v2 core. Ship Level 1 as an opt-in phase at the end. Do not do
Level 2.**

---

## Part 4 — Other protocols worth using, with verdicts

### 4.1 Routing and scaling

| Protocol / technique | Verdict | Why |
|---|---|---|
| **Trickle (RFC 6206)** | **Adopt, promote** | We already have `TrickleTimer.kt` from pass 22, wired only to the (now circuit-broken) Coded PHY channel. Trickle makes periodic broadcast cost O(1) per node *regardless of density* — hear enough consistent copies, stay quiet. It should govern every periodic broadcast we emit. Highest value-per-line change in this document. |
| **BLE Extended Advertising as a connectionless broadcast tier** | **Adopt — biggest single lever** | `startAdvertisingSet(setLegacyMode(false), setConnectable(false))` carries up to `getLeMaximumAdvertisingDataLength()` (≈1650 B chained, 251 B per in-place update while enabled) to **every scanner in range at once**: no connection, no MTU negotiation, no address stability, no slot contention. Presence, position, SOS and hop-gradient all fit. bitchat does not use this at all. Caveat: gate on `isLeExtendedAdvertisingSupported()`, keep the 31-byte legacy path, and circuit-break on failure — we learned from Coded PHY that hardware lies about its capabilities. |
| **Gossipsub-style probabilistic fanout** | Adopt (via bitchat's log₂(degree) subsetting) | Same idea, already parameterised by them. |
| **DTN / Bundle Protocol v7 (RFC 9171)** | Adopt the *pattern*, not the wire format | BPv7 framing is heavy for 251-byte broadcasts. Its custody/bundle-lifetime model is exactly what our blind relay already is, informally. Use it as the vocabulary, not the encoding. |
| **Spray-and-Wait (n-copy DTN)** | **Adopt** | Bounded copies (budget 4, cap 8, hand over half on meeting) gives eventual delivery across partitions with *no* state about who meets whom. |
| **PRoPHET (RFC 6693)** | **Reject** | Encounter-history-based routing means every phone holds a social contact graph. Under seizure that is the single most damaging artefact we could possibly persist. Spray-and-wait gets most of the benefit with none of that. |
| **Reticulum / LXMF** | **Reject as a dependency, mine for ideas** | Genuinely good transport-agnostic stack with real Android BLE clients (Sideband, Columba). But adopting it means adopting its addressing and announce model, a Python-origin stack, and a second cryptographic identity system — and it is optimised for long-haul LoRa/packet-radio links, not 200-node dense BLE. Its *destination-hash + announce + opportunistic-delivery* design is worth reading before finalising P1. |
| **Meshtastic** | Reject | LoRa hardware. Different problem. |
| **Briar / Bramble** | Reject as dependency | Closest thing to a peer project philosophically (BTC/Wi-Fi/Tor, store-and-forward, no servers) but it is contact-graph based and Java-heavy; its value to us is prior art on transport abstraction, not code. |

### 4.2 Message eventuality (delivery across partitions)

**Adopt bitchat's courier model, group-addressed instead of recipient-addressed.**

- Envelope tag = `HMAC(groupKey, UTC-day)` — 16 bytes. Any member can recognise and open it; a
  non-member courier learns neither the group, the sender, nor the content. This composes exactly
  with our existing rotating-beacon-ID construction and our blind-relay pillar.
- Bounded pool with tiers (theirs: 20 of 40 slots reserved for trusted depositors; ours: reserve for
  groups we are a member of, remainder for blind carry).
- Copy budget 4, cap 8, hand over half on meeting another courier. Cap 16 KiB, 24 h.
- Rate-limit handover to 1 attempt per envelope per 10 min.

This is what gives "the message gets there eventually" a real mechanism instead of hoping a
connection happens during the content's 48 h lifetime.

### 4.3 Media sharing

Three changes, in dependency order:

1. **Thumbnail-first, full-res pull-on-demand.** Today we push every chunk of every photo to every
   phone including blind relays. Instead: flood a small signed thumbnail + content hash + size, and
   transfer full resolution only to members who explicitly request it. This is the single biggest
   media-scaling win and it requires no new transport.
2. **RaptorQ / fountain coding (RFC 6330)** to replace indexed chunks + manifest + bitset. With
   fountain coding a receiver reconstructs from *any* k(1+ε) distinct symbols from *any* combination
   of sources. That deletes `FRAME_MANIFEST`, the have-bitset, the per-peer deficit computation, and
   the session chunk budget — and it makes media *faster* with more carriers instead of slower.
   Already on our backlog since pass 18; this is where it pays off.
3. **A real bulk pipe**, negotiated per link:
   - **BLE L2CAP CoC** (`createInsecureL2capChannel`, API 29+) — a socket with credit-based flow
     control instead of one 400-byte ATT write per round trip through `GattOperationQueue`. Large
     throughput gain for the same radio.
   - **Wi-Fi Aware (NAN)** (API 26+, `isAvailable()`-gated) — **replaces Wi-Fi Direct**. Aware is
     publish/subscribe many-to-many with data paths and *no group-owner election and no system
     connection dialog*, which is precisely the flaw `NEXT_STEPS.md` flags as breaking our disguise.
     It is a strictly better fit for our topology than WFD ever was.
   - GATT 400-byte chunks stay as the universal fallback.

### 4.4 Crypto: forward secrecy and the cleartext-groupId gap

Both gaps are flagged in the pass-23 backlog. bitchat's answer (Noise XX per live session) does not
transfer cleanly, because our unit of encryption is a *group*, not a *pair*.

| Option | Verdict |
|---|---|
| **MLS (RFC 9420)** | **Reject.** MLS needs a delivery service that totally orders commits (RFC 9420 §14). A partition-heavy BLE mesh forks constantly and MLS forks are not recoverable without out-of-band resync. Wrong tool for this network. |
| **Non-interactive epoch ratchet** | **Adopt — but see decision 39's correction below.** `K_e = HKDF(root, e)` where `e = floor(now / epochLen)`. Every member derives it independently — no commits, no coordination, no delivery service. Keep N previous epoch keys for skew and store-and-forward. **Does NOT give forward secrecy against seizure** (the root key must stay permanently retained regardless, for the rotating handle and the invite-code reconstruction below — any non-interactive derivation from a retained secret is exactly as forward-secret as that secret is confidential, whether stateless or a stateful ratchet with deletion; real forward secrecy needs an interactive DH step this partition-heavy mesh can't rely on). What it actually buys: domain separation from the wire handle, and bounding one independently-leaked epoch key to a single epoch's exposure window instead of the group's whole life. Composes with the join-code-v2 expiry field we already ship. |
| **Rotating group handle on the wire** | **Adopt.** Replace cleartext `groupId` in every frame with the beacon's existing `HMAC(groupKey, epoch)` handle. Relays dedup and forward on the handle without learning group membership. Receiver cost is bounded by number of groups joined (small). Closes the traffic-analysis gap in the pass-23 backlog. |
| **Per-sender ratchet / sender keys for PCS** | Defer past v2. Real value, but needs coordination we do not have yet. |
| **Padding to size buckets** | Adopt for *all* frame types (bitchat pads only Noise packets and lists the rest as future work). |

### 4.5 Internet fallback — is the Nostr relay needed at all?

**No. Not in v2, and probably not ever for this app.** Three independent reasons, any one of which
would be sufficient:

1. **It solves a problem we defined away.** Nostr's job in bitchat is delivering to mutual favourites
   when neither is in BLE range — over the internet. Our stated premise is that the internet is
   jammed, shut off, or untrusted. Building the fallback on the very thing whose absence is the
   reason the app exists is circular.
2. **It requires the two things we deliberately refuse.** A stable per-user public key, and a
   persistent contact relationship ("mutual favourites"). We have no accounts, no contact list, and
   per-group ephemeral identities that expire with the group. Adding Nostr means adding a durable
   user identity — the same trade §3 already rejected for bitchat's peer ID, arriving by a different
   door.
3. **The job it does is already done offline.** "Message arrives eventually across a partition" is
   P4's couriers (§4.2). Same outcome, no internet, no relay operator.

There is also a threat-model asymmetry worth stating plainly: a public relay observes connection
patterns even when content is sealed, and a relay is an **addressable** point — it can be blocked,
subpoenaed, or watched. A courier network has no such point. For users facing phone seizure and
network-level adversaries, that difference is the whole ballgame.

Revisit only if the product changes from *"coordinate in a crowd, now"* to *"coordinate across
cities and days."* That would be a different app, and it should be scoped as one.

---

## Part 5 — v2 target architecture

### 5.1 Three transport tiers instead of one

```
Tier B — BROADCAST (connectionless)     presence, position, SOS, hop gradient, thumbnails,
  extended advertising, ≤251 B/update   courier tags, catalogue digests
  Trickle-governed, no connections      → reaches every neighbour at once, no slot contention

Tier L — LINK (persistent GATT)         forwarded packets (TTL flood), catalogue reconciliation,
  ~4-6 persistent links, kept open      courier handover, control
  immediate forward + 10-220 ms jitter  → the graph only needs to be connected, not fully visited

Tier X — BULK (negotiated, on demand)   full-resolution evidence, fountain symbol streams
  L2CAP CoC → Wi-Fi Aware → GATT chunks
  pull-based, member-to-member only     → media stops being a broadcast storm
```

The current design collapses all three into Tier L, which is why Tier B traffic is stale and Tier X
traffic is a flood.

### 5.2 Peer identity — the middle path

Neither our rotating-address-keyed state nor bitchat's permanent public peer ID. Instead:

- **On the wire:** keep the rotating handle. Nothing linkable is ever broadcast. Unchanged privacy.
- **In local state:** key everything on the **per-(device, group) Ed25519 public key** we already
  establish at join (`GroupRepository.ensureSenderIdentity`) and already pin TOFU-style from the
  presence heartbeat. It is stable, it is already authenticated, it never goes on the wire in
  cleartext, and it is scoped per-group so it cannot correlate a device across groups.
- BLE MAC becomes what it actually is: a transient handle for one connection, never a state key.

This resolves `NEXT_STEPS.md` D1 without importing bitchat's linkability weakness. It is a
prerequisite for almost everything else in v2, because a forwarding protocol needs to know which
neighbours it has in order to compute degree and fanout.

### 5.3 Message plane

Every relayable item becomes a **packet** with a uniform header — version, type, TTL, timestamp,
flags, rotating group handle, sender handle, payload, signature (signature excludes TTL so relays can
decrement without invalidating, exactly as bitchat does). Then:

- On receipt: verify → check dedup LRU (1000 entries / 5 min in memory, Room as the 48 h cold layer)
  → if new, deliver locally and schedule a forward.
- Forward: after 10–220 ms jitter, on a deterministic message-ID-seeded subset of ≈log₂(degree)
  links, with TTL−1 and degree clamping (≥6 links → cap at 5; ≤2 links → full depth).
- Catalogue reconciliation demotes to a **periodic ~15 s exchange on already-open links** — backfill
  for what the flood missed, not the delivery mechanism.

### 5.4 Density adaptation — the rule that makes 3 phones and 400 phones the same code

The design point is 200–400 devices in range, but the app must also work on three phones in a room,
and three phones is the only configuration we can actually put on hardware. The way to get both
without two code paths:

> **Every density adaptation is a function of measured local degree, and the low-density case is its
> identity function.** Nothing is "enabled for crowds." Things are *suppressed* as degree rises.

| Mechanism | D ≤ 4 (3-phone case) | D ≥ 5 (crowd case) |
|---|---|---|
| Fanout subsetting | forward to **all** links | deterministic subset ≈ log₂(degree) |
| TTL | full incoming depth | clamp (≥6 links → 4–5) |
| Relay jitter | ~10–30 ms | 10–220 ms, widening with degree |
| Trickle suppression | never fires (no redundant copies to hear) | governs all periodic broadcast |
| Scan report batching | off — snappy discovery | 1–2 s batches |
| Link selection | take everything we can hold | select for diversity, evict redundant |

This matters more than it looks. Fanout subsetting **below a degree floor is actively harmful** — at
5 links, forwarding to 2 of them risks partitioning delivery, which is why bitchat has its own
"≤2 links → relay at full incoming depth" rule. Getting the floor wrong makes the 3-phone case
*worse* than v1 while the simulator says everything is fine. This table is the specification, and
each row needs a unit test at both ends.

Corollary for testing: the 3-phone case is not a degraded mode to check at the end. It is where every
adaptation is switched **off**, so it is the cleanest possible test of the core forwarding logic —
and P1's headline improvement (per-hop latency 45 s → sub-second) is directly measurable with three
phones in a line.

### 5.5 Operating envelope — one device, changing density

§5.4 treats degree as something to tune for. That is still not right. The real requirement is that
**degree is time-varying for a single device inside a single session**, and the transitions are where
the failures live.

Start from a decomposition that v1 never made explicit:

- **Group size is always small — 3 to 8 people.** An affinity group does not grow into a crowd.
  Presence, radar, nicknames, positions: all O(group), permanently. This is a *discovery* problem
  (find 3 people among 400), never a *tracking* problem.
- **Swarm size is variable — 2 to 400+.** Only *relay* traffic scales with it.
- Therefore **the only thing that actually scales is other people's traffic we blind-relay.**
  Group-scoped work is constant no matter how big the crowd gets.

That reframing means we are not "engineering for 400 people." We are engineering for a 5-person group
whose *carrier medium* varies from 2 to 400 nodes.

#### The four regimes one device passes through

| Regime | Degree | What it is | What must hold |
|---|---|---|---|
| **R1 Isolated / tiny** | 0–4 | the 3-phone test; a group in a side street | every adaptation off; identity behaviour; this is the baseline, not a degraded mode |
| **R2 Group inside swarm** | 100–400 | **the primary real-world case** — 5 people inside a march | strangers are *relay capacity*, not noise; blind relay pays off hardest here; must find 5 among 400 |
| **R3 Boundary crossing** | changing fast, either way | walking in or out of the crowd | no storm on entry; no silence on exit |
| **R4 Partitioned** | group split across a gap | half the group round a corner | couriers (P4) carry across |

R2 is the case the app actually exists for, and it is the one v1 never modelled. It is also the
happiest case for our design: several hundred strangers running blind relay is exactly the carrier
medium our architecture assumes and bitchat's does not.

#### R3 is the dangerous one, and the danger is asymmetric

Walking **in** to a swarm is a storm risk: degree jumps 2 → 300 in seconds, every adaptation engages
at once, and connection/scan machinery can thrash. Recoverable, and the simulator will show it.

Walking **out** is worse, and it is a silence risk. If Trickle has suppressed your broadcasts because
you were hearing hundreds of consistent copies, and you then step out of range, **you go quiet at
exactly the moment your 5-person group most needs to find you.** Nothing in the current design would
catch that; it would present as "the app worked in the crowd and then stopped."

bitchat's parameters already encode the fix: announce **every 4 s while isolated, backing off to
15–30 s when connected**. Isolation is the *fast, loud* state; connectedness is what earns quiet.
Adopt that shape, and generalise it into a hard rule:

> **Every suppression mechanism fails open.** Suppression must be driven by live evidence of
> redundancy. Loss of that evidence reverts to loud behaviour within one interval — never stay quiet
> on stale evidence.

This is a testable invariant (I5 in §6.2), not a design sentiment.

#### R2 forces one thing v1 does not have: a blind-relay budget

Today blind relay is unbounded — we carry and re-offer everything for every group we can hear. At
D = 3 that is the whole point and costs nothing. At D = 400 it is unbounded work on someone else's
behalf, and it competes for the same radio our own group's SOS needs.

v2 needs a **density-aware relay budget**: cap blind-relay work as a fraction of radio time and
storage, prioritising (1) groups we are a member of, (2) SOS over everything, (3) oldest-unserved
among the rest. The pillar survives — we still relay for strangers — but it stops being able to
starve our own group. See §9.3 for the open question of where that fraction sits.

---

## Part 6 — The test rig

The user request that produced this section: *"what can we learn from prev attempts and bitchat use
cases to make a better test rig with deep use cases."* This part is the answer, and it is P0a's
specification.

### 6.1 What v1's own history says the rig must do

Every significant v1 bug was found on hardware **after** an APK shipped, and on two occasions a fix
itself broke something new (`NEXT_STEPS.md`: *"two separate 'fixes' of mine went to a live test and
each broke something"*). The rig's whole purpose is to move that discovery left. So it should be
specified from the actual bug history, not from a generic idea of a network simulator:

| v1 failure | Pass | What the rig must be able to inject |
|---|---|---|
| `startScan`/`stopScan` silently throttled (~5 calls/30 s), inconsistently across OS versions | 11 | **Android API rate limits and throttles**, parameterised by OS version — not just radio physics |
| One phone could not advertise at all (null `bluetoothLeAdvertiser`) — invisible but fully functional | 12 | **Heterogeneous device capability**: advertise-incapable, scan-only, no-BT5, low-MTU nodes |
| Continuous advertise stop/start → chipset instability → **total** radio failure on both phones | 13→14 | A **radio-churn budget with an instability threshold** whose failure mode is total, not graceful |
| `connectGatt()` never fired `onConnectionStateChange` | 16 | **Callbacks that never arrive** |
| Connection past `CONNECTED` went silent without `DISCONNECTED` → slot leak, peer unreachable forever | A2 | **Half-open connections** |
| BLE address rotation → cooldowns never applied, maps grew unbounded | 22 | **Address rotation** at configurable rate |
| Hostile `totalChunks`, MAC-truncation bypass, replayed presence heartbeat | 23 | A **malicious node profile** |
| 60 s advertise dwell starved discovery | NEXT_STEPS | Starvation must be an **automatic assertion**, not something a human notices |
| Folding live GPS into the reconnect-skip epoch caused a reconnect storm | NEXT_STEPS | Storm must be an **automatic assertion** |
| 93 % of syncs delivered nothing | diag 10 | **Yield as a first-class metric**, not just delivery ratio |

Note what this table implies: a rig that only models radio propagation would have caught almost none
of these. The v1 failure distribution is dominated by **Android platform behaviour and state-machine
lifecycle bugs**, not by physics. Weight the rig accordingly.

### 6.2 Invariants — assertions that fail a run, not dashboards

Metrics get looked at when someone suspects a problem. Invariants catch the problem nobody suspected,
which is the entire v1 failure pattern.

- **I1 — Radio touched only when payload actually changed.** Three consecutive v1 passes (12 scan,
  13 advertise, 14 fix) converged on this rule independently. It should be mechanically enforced now.
- **I2 — No peer-keyed structure grows unbounded** under address rotation.
- **I3 — No connection slot held past its deadline**, and slot count returns to baseline after churn.
- **I4 — Every §5.4 adaptation's low-degree case equals identity.** Tested at both ends of every row.
- **I5 — No node goes silent.** Every node emits something within N seconds regardless of suppression
  state (the §5.5 fail-open rule, mechanised).
- **I6 — Yield floor.** More than X % of connections/exchanges carry something new. v1 scored 7 %.
- **I7 — Group delivery.** Every group member receives every group message inside the scenario budget.
- **I8 — Bounded per-node work.** CPU, radio time and storage stay flat as swarm size rises, given
  constant *group* size. This is the one that directly tests the §5.5 decomposition.

### 6.3 Scenario catalogue

Named, versioned scenarios with pass criteria. The ones marked ★ are the new ones that come out of
the §5.5 reframing and have no v1 equivalent.

| # | Scenario | Setup | What it tests | 3-phone analogue |
|---|---|---|---|---|
| S1 | **Three in a room** | D = 2, static, all capable | baseline; every adaptation off; I4 low end | direct — this *is* the hardware gate |
| S2 ★ | **Five in a march** | group of 5 inside 400 strangers, slow mobility | **the primary use case**; discovery of 5 among 400; I7, I8 | partial: 3 phones + synthetic load (§6.4) |
| S3 ★ | **Walking out** | D 300 → 2 over 60 s | I5 fail-open; suppression must disengage before the group loses you | partial: 3 phones + load generator switched off |
| S4 ★ | **Walking in** | D 2 → 300 over 60 s | no connection/scan storm on entry; I3 | partial: load generator switched on |
| S5 | **Split and rejoin** | group splits 20 min, reunites | couriers (P4); message eventuality | direct: carry one phone out of range and back |
| S6 | **The one old phone** | one member advertise-incapable | Pass 12's real bug, permanently regression-tested | direct if an old handset is available |
| S7 | **Kettle** | D = 400, static, multi-hour | sustained battery, storage growth, I2, I8 | load generator, hours |
| S8 | **Stampede** | SOS from one node under full load | time-to-all-group-members; the app's whole reason to exist | direct, measurable with 3 phones in a line |
| S9 | **Hostile node** | malformed frames, replays, flood | Pass 23 security fixes stay fixed | sim only |
| S10 | **Relay dies mid-transfer** | carrier node vanishes during media | no stuck transfers, no orphaned state | direct: power off phone 2 mid-send |
| S11 ★ | **Blind-relay load** | we are in 1 group of 3, carrying for 50 other groups | the §5.5 relay budget; I8; our distinguishing pillar under stress | sim + load generator |

S2, S8 and S11 are the three that matter most. S8 is the product claim. S2 is the deployment reality.
S11 is the pillar that separates us from bitchat, and it is completely unmeasured today.

### 6.4 Three gate tiers — and the missing middle

The gap between "JVM simulator" and "3 real phones" is precisely where every v1 bug lived. Originally
closed with a third tier — synthetic radio load (ESP32s/BlueZ boards, D=200-400 real BLE traffic) —
but that tier is REMOVED as of 2026-08-07 (user directive, `docs/DECISIONS.md` decision 36): it never
had a realistic path to actually happening (no hardware acquired across this entire v2 effort, every
phase that named it as a gate treated it as "open, not blocking" in practice anyway per the Part 7
preamble below) and had become pure process weight — scope creep on a methodology, not a real
validation step. This project now runs a two-tier model:

- **Tier 1 — Simulator.** D = 3 → 400. Everything in §6.1–6.3 that is logic, timing and state
  machines. Cannot tell us anything about a real radio.
- **Tier 3 — Three real phones.** The final word on chipset behaviour, callback ordering and battery.
  Per §5.4, this is where all adaptations are off, so it is a clean test of core logic. (Numbered 3,
  not 2, kept as-is rather than renumbered — every existing decision/status reference in this
  document already calls it "Tier 3," and renumbering would just create a second thing to keep in
  sync for no real benefit.)

A phase is done when both tiers that apply to it pass. The 200-400 target itself is now trusted on
Tier 1 (simulator) plus real-world Tier 3 rounds alone — no synthetic-load cross-check, a real,
named tradeoff, not silently dropped.

---

## Part 7 — Phased roadmap

Same discipline as v1: **one phase, one APK, hardware-gated, nothing else lands until it passes.**
That rule is not bureaucracy — three separate v1 rounds shipped an unverified radio change and each
broke something.

**Every phase is gated on the two tiers in §6.4** — simulator, three real phones — for whichever
apply (Tier 2/synthetic radio load removed 2026-08-07, `docs/DECISIONS.md` decision 36 — see §6.4's
own note). No tier is sufficient alone: the simulator cannot see a radio, and three phones cannot
produce crowd density. Gates below name the tiers explicitly; scenario IDs refer to §6.3.

**How hardware gates actually get checked (2026-08-05 clarification).** This has never meant real-time
supervision — every hardware-verified fix since pass 10 has worked the same asynchronous way: a
phase's code + sim gate land first, a debug APK is built and handed off, the user installs it on real
phones, and the on-device `DiagnosticsLog` (debug-only, exportable, never positions/message bodies —
see its class doc) captures what the sim gate's claim predicts. The user exports and sends that log
(or describes what they observed) back for review. **A hardware gate is a checkpoint on the claim, not
a precondition for writing the next phase's code** — implementation keeps moving on the sim/compile/
test-verified track; a phase is only marked *hardware-confirmed* once that log/report comes back,
same as decisions 8-13's confirmation trail in `docs/DECISIONS.md`. A phase can be marked **PASSED**
(code-complete, Tier 1-verified) while still *awaiting* Tier 3 confirmation — awaiting is not
blocking, per this same asynchronous discipline (first applied explicitly to P2 itself, decision 36).

**P0a — Test rig (prerequisite, no APK). Specified in full in Part 6.**
**STATUS (2026-08-05): Tier 1 harness built and gated, `app/src/test/java/org/offlinemesh/app/sim/`
— see `docs/DECISIONS.md` decision 14. 7 of 11
§6.3 scenarios implemented (S1/S2/S6/S7/S8/S9/S11); S3/S4/S5/S10 documented as blocked on P2/P4/P5.**
Tier 1 headless JVM harness. The harness drives the *real* routing classes and is tractable because
the decision logic is already extracted into Android-free classes (`ConnectionAttemptTracker`,
`CatalogFilter`, `TrickleTimer`, `HopTracker`, `OpaqueFrameRelay`). Crowd-scale simulation was
explicitly dropped in v1; v2 is a scaling project and **every claim in it is unfalsifiable without
this.** Must come first. D = 3 and D = 400 are both first-class configurations from day one, along
with the §6.1 injection list and the §6.2 invariants.
*Gate: reproduce v1's measured behaviour at D = 3 (93 % empty syncs, one connection per ~50 s) and
project it to D = 400 (~8 min sync round). If the harness cannot reproduce the known-bad numbers it
is not modelling anything, and nothing built on it can be trusted. §9.2 item 1's scan-callback storm
prediction now rests on Tier 1 (simulator) plus real-world Tier 3 rounds alone, per the two-tier
model — no independent synthetic-load cross-check (decision 36's named tradeoff).*

**P0b — Stable peer identity (§5.2).**
**STATUS (2026-08-05): implemented, compile/test-verified (270 tests), NOT hardware-confirmed —
see `docs/DECISIONS.md` decision 15 for the actual shape landed (keys on `senderId`, not the raw
pubkey; a new `PeerIdentityResolver`, not a literal re-key of `ConnectionAttemptTracker` itself; a
second, independent unbounded-map bug fixed alongside it).**
Re-key all peer state onto the per-group Ed25519 pubkey.
Nothing new on the wire. Unblocks degree computation, fanout, and courier handover.
*Sim gate: peer-state entries track node count, not address-rotation rate, at both D = 3 and D = 400.*
*Hardware gate (async, see Part 7 preamble): ship the debug APK, user runs 3 phones for ~30 min,
exports the `DiagnosticsLog` and sends it back — check for a stable peer count instead of the
current 19-prefixes-for-3-phones. Not a precondition for starting P1.*

**P0b correction — `senderId` is not actually per-group (found 2026-08-09, scoped in decision 53,
IMPLEMENTED in decision 54). §5.2 always specified "keys on the per-group Ed25519 pubkey," and decision 15's own
text says the same — but the actual `senderId` STRING value used everywhere (`SosEntity.senderId`,
`Frame.Presence.senderId`, `Frame.Position`'s equivalent, `NicknameEntity.senderId`, hop-tracking,
`PeerIdentityResolver`'s `stableKey`) is `GroupRepository.deviceId` — a single random UUID
generated once per install and reused identically across every group a device joins.**
`PeerIdentityResolver`'s own class doc already states this plainly ("a random-per-install id... 
global across a device's groups, and already sent in cleartext on presence heartbeats") — decision
15 built local convenience on top of this fact without treating the fact itself as something to
fix. Two real consequences: (1) any member of two overlapping groups can trivially link a device
across them by comparing `senderId` values, defeating the unlinkability §5.2 states as the goal;
(2) since `encodePresenceFrame`/`encodePosition` write `senderId` into the CLEARTEXT envelope
(needed so a non-member relay can still hop-track/dedup), **any passive BLE observer — not just
group members — can track this ID over time with no key at all**, reintroducing exactly the
"permanent linkable peer ID" weakness bitchat's own whitepaper names as its worst property and
this project's own Part 1.7/§5.2 claims to avoid.

**Fix shipped (decision 54).** New `GroupRepository.senderIdFor(groupId): String`, derived from the
per-group Ed25519 public key `ensureSenderIdentity` already generates and persists — no new key
generation or storage, purely a computed value: `CryptoUtils.sha256Hex(publicKey).take(16)` (16 hex
chars ≈ 64 bits, comfortably collision-resistant at this app's group sizes). Preserves the exact
property `PeerIdentityResolver`/`HopTracker` actually need — stability for a given (device, group)
across BLE address rotation — while finally matching what §5.2 always specified: no longer shared
across groups, and derived from something that only ever reveals per-group participation, not a
device-linking secret.

**All 12 call sites updated**: 5× in `RelayEngine.kt` (`createSos`/`createEvidence`/`setNickname`/
`myNickname`/`createCourierEnvelope`), 5× in `RelayResponder.kt` (presence/position encode,
position-relay self-exclusion, two self-detection checks), 3× in `BeaconRadio.kt` (Tier B position
encode, Tier B position-relay self-exclusion, self-broadcast filter) — all now call
`repo.senderIdFor(groupId)` instead of the flat `repo.deviceId`. `BeaconRadio.advertiseJitterMs`'s
own `repo.deviceId` use deliberately left alone — a purely local, never-transmitted radio-restart
timing offset, no reason to be per-group.

**No `MeshFrameCodec.VERSION` bump needed** — confirmed: `senderId`'s wire byte layout (a
length-prefixed string field) is unchanged, only the string's value changes; same category as
decision 39's "semantic change, not byte-layout change" precedent.

**Real, one-time transition cost on upgrade, stated honestly:** every device's `senderId` changes
for every EXISTING group it's already in, the moment it upgrades to this build. Existing peers see
what looks like a brand-new, unrecognized sender — hop-tracking/route-ownership resets, and a
previously-set nickname (keyed on the old `senderId`) stops resolving until the device
re-broadcasts it under the new one (should self-heal within one connection cycle — both
`framesToPushOnConnect` and `refreshFramesToPush` push current nicknames unconditionally on their
own cadence, independent of `senderId`'s value). Same class of harmless discontinuity as a fresh
reinstall or group rejoin — which this session's own hardware round already exercised (eg3's
mid-test rejoin) without incident. **NOT hardware-confirmed** — this real wire-semantics change
(even without a `VERSION` bump) and the self-healing claim above should both be watched on the next
live round. 505 tests, detekt clean, both variants green, no `missing_rules.txt`. Version
v0.7.19-dev. Full detail in `docs/DECISIONS.md` decisions 53 (scoping) and 54 (implementation).

**P1 — The forwarding plane (§5.3).**
**STATUS (2026-08-05): wired into production, scoped to SOS only — see `docs/DECISIONS.md`
decision 18. Wire format bumped (MeshFrameCodec.VERSION 3->4, SosEntity.hop, decoupled from ttl —
closes the risk decision 16 flagged). New ConnectionRegistry + RelayResponder.floodForwardSos push
a new SOS across every other open link via the real ForwardingPolicy. DedupCache deliberately left
unwired (existing DB-backed ingestSos dedup already serves the purpose). Evidence-header/nickname
forwarding deferred, same mechanical pattern. Sim finding that led here: P1 ALONE doesn't hit its
own latency claim (measured 52s for the 3-phone-line topology vs v1's ~90s — real but modest) because
flood can only use a link that's already open; P3 (below) is what actually closes that gap. Compile/
test-verified (304 tests), detekt clean, both variants green — HARDWARE-CONFIRMED across two live
3-phone rounds (2026-08-05): round 1 found and fixed two "only received content moves" gaps
(v0.6.1-dev, docs/DECISIONS.md decision 20); round 2 confirmed both fixes hold and found/fixed an
unrelated duplicate-`onServicesDiscovered` radio-waste bug (v0.6.2-dev, decision 21). The headline
claim below — relayed SOS in seconds, not ~45s/hop — is confirmed by round 2's logs.**
Immediate forward with TTL, dedup LRU, jitter, fanout
subsetting, degree clamping — all degree-gated per §5.4. Catalogue sync demoted to periodic backfill.
**This is the change that matters**; everything before it is groundwork and everything after it is
amplification.
*Sim gate: at D = 400, delivery ratio and per-hop latency hold up under the derived dedup-LRU size;
at D = 3, fanout subsetting is confirmed OFF and delivery is strictly better than v1.*
*Hardware gate: 3 phones in a line — a relayed SOS arrives in seconds, not the current ~45 s/hop.
This is directly measurable and is the headline claim of the whole plan.*

**P2 — Broadcast tier (§5.1 Tier B). Required, not optional — see §9.2 item 7.** Extended
advertising, Trickle-governed, carrying presence, position, SOS and hop gradient. Legacy 31-byte
fallback and capability circuit-breaker mandatory. Hardware `ScanFilter` on the service UUID and
degree-gated report batching land here (§9.2 item 1). **The §5.5 fail-open rule (I5) is a P2
acceptance criterion, not a later refinement** — Trickle without it turns "the last of your own
group-mates drift out of range" into "went silent." (Corrected per decision 24, 2026-08-06: sightings
are own-group-scoped, so it is specifically *your group* thinning out that risks silence, not swarm
density falling — walking out of a crowd of strangers, by itself, moves nothing here.)
**STATUS (2026-08-06): Tier 1 sim done for now (I5/fail-open pass), PRODUCTION WIRING STARTED —
see `docs/DECISIONS.md` decisions 23-26.** Decision 23's three-way open question is resolved as
option (c) — own-group-only sighting scope, already true in production `BeaconRadio`, no production
code changed there. Reworking the sim to match dissolved decision 23's "S3 D=2 never fails open"
finding: under corrected own-group-degree semantics, D=2 means two real group-mates still in range,
an ordinary covered state, not isolation — staying suppressed there is correct, not a bug. That
surfaced a follow-on finding (decision 24): the sim could pin suppression at own-group degree as low
as 1. Digging into it (decision 25) found the real cause and fixed it **in production code**:
`TrickleTimer.onSighting()` was counting raw calls instead of distinct sources, which is a genuine
mismatch against `BeaconRadio`'s continuously-broadcasting advertising-set sender model (confirmed
via its actual `AdvertisingSetParameters`/`ScanSettings` config, not assumed) — a single present
neighbour could generate dozens of "sightings" per window and pin suppression regardless of true
redundancy. Fixed by deduping `onSighting(sourceId)` within a window.

**Then, same day, production wiring's first slice (decision 26):** rather than build Tier B as a
new, separate channel, the existing Coded-PHY-only "long-range channel" — which already had extended
advertising, in-place updates, non-connectable mode, and Trickle governance — was **generalized in
place**, avoiding a second advertising set competing for the same scarce chipset slot. Capability
gate loosened to `extendedAdvertisingSupported` alone (Coded PHY now an opportunistic add-on via
`codedPhySupported`, not a requirement) — broader hardware support than before. New payload
(`MeshProtocol.encodeBroadcastTierBeacon`) adds an explicit `presenceHop` field, so presence now
propagates a real multi-hop gradient over broadcast with zero GATT connections — this is what
actually delivers §9.2 item 7's claim, not just makes it theoretically possible. Hardware
`ScanFilter` on the service UUID restored, but ONLY on this brand-new scan (§9.2 item 1) — the
legacy scan stays deliberately unfiltered/untouched, consistent with decision 3's lesson and every
other "additive only" precedent in this file. Degree-gated `setReportDelay()` batching added
(§9.2 item 1's other half), symmetric — drops back to immediate once measured degree falls back at
or below the floor. Resolves `PLAN-v2.md` Part 8's open item on the Coded PHY channel (re-scope, not
delete). **Position and SOS-message broadcast deliberately deferred to a later slice** — position
needs encryption/nonce-budget work, SOS hop-gradient is per-SOS-id and ambiguous the same way the
legacy beacon's own `sosHop` already is — named explicitly rather than silently dropped, matching
P1's own "SOS only first" precedent.

**Same day, production wiring's second slice (decision 27): single-hop position.** Reuses
`MeshFrameCodec.encodePosition`'s full output verbatim as the wire payload — no new crypto, no new
framing — embedded length-prefixed inside the Tier B beacon; decode reuses the ordinary
`MeshFrameCodec.decode`/`openPosition` pipeline unchanged. Resealed on a fixed ~20s cadence
(matching `RelayResponder.positionFramesToPush`'s own GATT refresh rhythm), independent of whether
the underlying GPS fix itself changed — mirrors that function's "stamp now as the timestamp, not the
fix's age" behaviour, specifically so a stationary sender doesn't go stale on other people's radar.
Real finding on the receive side: opening/verifying a position needs a suspend DB lookup
(`repo.peerKeyDao.get`, the pinned-sender-key check) that the raw BLE scan-callback thread cannot
make directly, so ingestion is dispatched onto `serviceScope` per received position rather than
duplicating a synchronous cache of pinned keys. Reuses `RelayResponder.signatureCheckPasses`
directly (already `internal`, no new coupling needed) so a Tier B position gets the *exact same*
trust bar a GATT position does — not weaker just because it arrived connectionless. **Deliberately
single-hop only**: a Tier B receiver does not re-broadcast a position it heard from someone else —
extended advertising has no natural "relay" the way GATT store-and-forward has one, so genuine
multi-hop position propagation over broadcast would need its own gossip design, out of scope here.
The existing GATT `PositionSealed` relay is completely unaffected and still handles multi-hop.

**Same day, production wiring's third slice (decision 28): SOS hop-gradient — closing decision 26's
other deferral, correctly this time.** The naive approach (feed the legacy beacon's existing
`sosHop` field into hop tracking) was exactly what an EARLIER, reverted mechanism already tried and
broke: a rough, sosId-agnostic hop estimate sharing a key with exact TTL-derived per-SOS tracking,
where `min()` let a stale rough reading leak through (decision 13's live-tested "frozen at 2 hops
with 2 test phones" bug). Confirmed by `grep` that `beacon.sosHop` is written but genuinely never
read by any receiver today — removed it from the Tier B format (legacy beacon's own copy stays
untouched) and replaced it with `MeshProtocol.SosAlert(id, hop)`, keyed on the REAL SOS id via a new
`HopTracker.bestActiveSos(groupId)` (split out of `bestActiveSosHop`, same staleness check, now also
names which sosId). A receiver feeds this into `HopTracker.considerNeighborReport(groupId,
activeSos.id, activeSos.hop, ...)` — just another source for the SAME exact per-SOS key GATT
flood-forward already uses, composing via ordinary distance-vector relaxation instead of
aggregating. The old bug was never "hop-gradient over broadcast is unsafe," it was conflating rough
and exact measurements under one key — keying on the real id removes the conflation, not the
feature. Carries id+hop only, deliberately — no message/mac/signature; the authenticated content
still arrives over GATT once connected (already attempted eagerly for every heard device via the
existing blind-carrier policy). Required redesigning the wire format (both position and SOS blocks
now always length-prefixed, zero = absent, since two independently-optional fields can't share the
old "trailing bytes present or not" shape) — a safe breaking change given zero devices have ever run
this format. Also found and fixed a real bug before it shipped: the position-ingestion early return
was silently skipping SOS handling whenever position was absent from a beacon.

**Same day, production wiring's fourth slice (decision 29): SOS content preview — stopped for an
explicit user decision before building it.** Reusing `MeshFrameCodec.sosMacInput`'s existing scheme
(the obvious approach) needs `senderId` in cleartext to verify, which would escalate
`NEXT_STEPS.md`'s already-flagged, unresolved "SOS is cleartext" gap from connection-gated (GATT) to
passively-readable-by-anyone-with-a-scanner (Tier B broadcast) — a real, not hypothetical, exposure
increase. Presented three options; **user chose to build a version that avoids the new exposure**.
New `MeshFrameCodec.broadcastSosMacInput` deliberately excludes `senderId` — a second, non-
interchangeable mac scheme under the same group key, computed fresh at broadcast time, not reused
from `sosMacInput`'s own mac. Broadcasts a ≤120-byte preview of whichever SOS `HopTracker.
bestActiveSos` names as nearest (ours or a relayed one we hold — re-authenticating already-verified
content under the new scheme extends no new trust). Budget forced an explicit priority call:
position is dropped from any cycle carrying SOS content (worst case with both maxed is ~400B, well
over the ~251B budget; content alone is 222B, comfortable) — an emergency preview outranks a
routine position refresh for however long the SOS stays nearest. Verified on receipt
(`verifyBroadcastSosContent`, mirroring `RelayResponder.authOk`'s exact acceptance shape) but
deliberately NOT stored — a preview is missing fields (`senderId`, `ttl`, the GATT-authoritative
mac/signature) a real `SosEntity` requires; full UI surfacing ahead of the GATT-confirmed record is
a named follow-up, not silently dropped.

**First hardware round on this work (decision 30, 2026-08-06, 3 phones) found two real bugs and one
real gap, all fixed; one report explained as design-intentional, not a bug.** A presence hop-count
bug in decision 26's own code (`BeaconRadio.handleResult` called `HopTracker.considerNeighborReport`
twice per scan result with the same source, letting a worse propagated reading undercut a better
direct one it had just set — merged into a single call). A dismantled group's positions lingering on
the radar (`PositionTracker` had no route to hear about `GroupRepository.dismantleGroup` across the
ble/data layer split — fixed with an immediate `clearForGroup` at the delete-group call site plus a
periodic `pruneOrphaned` safety net in `MeshService`'s existing sweep loop, also catching automatic
`expireGroups`). A nickname set after a P3 link was already open never reaching that peer — nicknames
were only ever pushed once, on connect, unlike presence/position which decision 20 already made
periodic; now pushed on every `refreshFramesToPush` too. Separately, a reported "3-4 hop position"
was traced (via `DiagnosticsLog`) to other nearby devices' blind-relay traffic plus the existing,
deliberate `maxPositionRelayHops = 4` ceiling — correct behavior for more than 3 devices in range,
not a defect. Full detail in `docs/DECISIONS.md` decision 30.

**Same day, closing decision 29's own named follow-up (decision 31): full UI surfacing of the
broadcast SOS content preview.** New `BroadcastSosPreview` — in-memory-only, one entry per group,
deliberately no staleness clock of its own: `forGroupIfBest(groupId, currentBestSosId)` only
returns a match when the caller-supplied id agrees with `HopTracker.bestActiveSos(groupId)`'s
current answer, the same source the preview's own hop-gradient (decision 28) already comes from —
avoiding a second, independently-aging channel feeding the same SOS display, the exact bug shape
this app was bitten by once before (the historical Pass 13 SOS-hop bug). Wired through the same
group-teardown lifecycle decision 30 just established for `PositionTracker` (`clearForGroup` at the
delete-group call site, `pruneOrphaned` in the existing periodic sweep) — a second ble-layer
in-memory tracker without that same wiring would have been a foreseeable repeat of the gap decision
30 just fixed. UI: `NavigateScreen` (both the GPS-fix and hop-fallback branches) shows it quoted,
labeled "unconfirmed preview, connecting to verify," and suppresses it entirely once a real
`SosEntity` for that id already exists in Room — so it never sits alongside or disagrees with the
fuller confirmed message already in the group's normal chat feed. Still not a real `SosEntity` —
same missing-fields reasoning as decision 29. New `BroadcastSosPreviewTest.kt` (pure JVM, no
Android deps) covers the freshness contract and the teardown paths.

**Same day, closing decision 27's own "own gossip design, out of scope" deferral (decision 32):
multi-hop position propagation over the broadcast tier.** The real design question — what loop
prevention even means on an omnidirectional broadcast medium, where GATT relay's split-horizon
premise ("never route back toward the peer that taught it to us") has no equivalent since there is
no single "peer" to route away from — turns out to already be answered: plain distance-vector
relaxation (a report only replaces the current value if strictly better) is loop-safe on its own,
the same property decisions 26/28 already relied on for presence/SOS hop-gradients and
`PositionTracker.offer` already implements for GATT relay. Our own GPS fix always wins Tier B's one
position slot when we have one; new `BeaconRadio.relayedPositionFrameForBroadcastTier` only runs
when we don't, reusing `RelayResponder.selectPositionsToRelay` (called with `toPeer = null`, no
peer to exclude on a broadcast) and forwarding the winning record's sealed bytes verbatim via
`MeshFrameCodec.reframePositionForRelay` — same building blocks GATT relay already uses, unmodified.
**Found and fixed a real bug while wiring the receive side**: `ingestBroadcastTierPosition` was
reading the sealed body's frozen inner hop instead of the envelope's hop that relaying actually
increments — invisible while Tier B was single-hop (both always 0), but would have silently stored
every relayed position as a direct hop-0 fix once this decision made the envelope hop vary. Fixed to
match `RelayResponder.ingestOpenedPosition`'s own already-documented choice. No dedicated new test —
every building block composed here (`selectPositionsToRelay`, the envelope-vs-inner-hop divergence,
`PositionTracker.offer`'s self-correction) is already covered elsewhere and unmodified in its own
logic; `BeaconRadio` itself has no direct unit test file (needs real Android BLE APIs), same
constraint every Tier B decision has carried.

**Same day, three more decisions (33-35) closed the rest of P2's own scope, per explicit user
direction to maximize position reach and fix a real architectural miss:**
- **Decision 33: `maxPositionRelayHops` raised 4 -> 120** (user's own request — real multi-km reach
  through an unbroken relay chain, not bounded by group size), landing on 120 as the largest value
  the existing 1-byte hop field safely supports (255 reserved as `UNKNOWN_HOP`'s sentinel); reaching
  "hundreds of km" would need a 2-byte field, a real wire break deliberately not made this pass.
  Surfaced a real precision gap: `RadarView`'s stale-dot fade was a flat 180s window, unrelated to a
  position's real (now much larger) staleness budget — fixed to scale per-dot via new
  `PositionTracker.effectiveMaxAgeSecondsFor`.
- **Decision 34: catalogue digests over Tier B, the last P2-scoped payload item.** Stopped first —
  a size that scales with held item count (GATT's own default) would let a passive scanner infer a
  group's content volume, and watch it change over consecutive beacons, without ever connecting.
  Presented the tradeoff; **user chose dynamic sizing anyway**, weighing it against the concrete
  functional cost of a fixed size (false-positive rate collapses past ~50 items — nearly useless for
  exactly the busy/escalating case the leak mattered most for) — a deliberate, case-by-case exception
  to the standing privacy-first preference, not a reversal of it. Found and fixed a real budget bug
  before shipping: position + the bare SOS hop-gradient (no content) + a maxed filter overruns 251B
  on its own, so the filter is the lowest-priority field, dropped first when it doesn't fit.
  Broadcast side only — receive-side consumption is a named follow-up.
- **Decision 35: the SOS/message split — a real, not hypothetical, miss caught mid-session.** Every
  message in this app has always been an `SosEntity` — there was never a separate casual-chat type,
  so every chat message was silently triggering the loud alarm notification and Tier B's SOS hop-
  gradient/preview. **User's fix, chosen over a parallel entity: reuse everything, gate only the
  alert-specific side effects behind one new `SosEntity.isAlert` flag** — hop-gradient, notification,
  and (for free, transitively) the Tier B preview all now require it; storage/relay/catalog-sync stay
  unconditional for every message. `GroupChatScreen` gained a dedicated, always-visible SOS action
  separate from its now-quiet default Send. Landed alongside this: the SOS preview cap trimmed
  100 -> 65 bytes (`"SOS: "` + ~60 chars), now that it only ever governs genuine emergencies.

**P2: STATUS = PASSED (2026-08-07, user directive, decision 36) — code-complete and Tier
1-verified, AWAITING Tier 3 confirmation, not blocking further work.** Per the Part 7 preamble's own
asynchronous-gate discipline: implementation does not wait on a hardware round that's already
scheduled, and per user direction P2 itself is the first phase explicitly marked PASSED under that
rule rather than left in limbo pending a live round. The sustained multi-hour field session is still
planned for AFTER P2 is built, as the one comprehensive validation of the whole v2 stack — this
PASSED status doesn't change that plan, it just stops treating "no live round yet" as an open
question about whether to keep building. **Still not started**: thumbnails (P5, not actually P2's
scope) and Tier B catalogue filter receive-side consumption (decision 34's own named follow-up — the
filter is broadcast and decoded, nothing yet tests a peer's holdings against it or acts on the
result). 367 tests, detekt clean, both variants compile/test/assemble (`assembleDebug`/
`assembleRelease`, incl. `lintVitalRelease`) green. **Partially hardware-confirmed as of decision 30**
(2026-08-06, 3 phones): presence hop-gradient and single-hop position both ran live and surfaced the
two real bugs + one gap fixed in decision 30 — SOS hop-gradient, SOS content preview, and decisions
31-35's work (UI surfacing, multi-hop position, the raised hop ceiling, the catalogue filter, and the
SOS/message split) are all still awaiting their own live round on v0.7.3-dev, in progress as of this
edit. Decision 35 in particular bumped the shared `MeshFrameCodec.VERSION` (4 -> 5) — no
pre-decision-35 device can talk to a build past this checkpoint until reflashed.
*Tier 1: S2, S3, S4, S7 — Trickle holds per-node broadcast cost flat as density rises; presence
freshness stays inside the window with connections disabled entirely; S3 shows the device audibly
loud again within one interval of leaving.*
*Tier 3: 3 phones — radar dots refresh at broadcast cadence with connections deliberately disabled,
Trickle confirmed never firing, discovery latency unchanged (batching off below the floor).*

**P3 — Link management.**
**STATUS (2026-08-05): wired into production — see `docs/DECISIONS.md` decision 19. MeshGattClient
no longer disconnects a healthy connection on a fixed idle/max timer; a link stays open until
LinkSelector's real-RSSI-based diversity eviction (only considered when every slot is held), a
genuine failure, or a distant safety-net backstop (BleTuning.connectionBackstopMs, now minutes, not
the old ~20s connectionMaxMs). Found and fixed 3 real bugs before shipping: the existing hard-
deadline watchdog would have force-disconnected every healthy persistent link after 60s (would have
silently made this whole phase a no-op); setMeshActive(false)/onDestroy would have leaked every
held connection past the old ~20s residual they assumed; a ConcurrentHashMap `in`-operator gotcha
(KT-18053, resolves to containsValue not containsKey) would have made two safety checks permanent
no-ops, caught by the Kotlin compiler as a hard error before any test ran. Compile/test-verified
(304 tests), detekt clean, both variants green. Four live 3-phone rounds (2026-08-05) confirm the
core mechanism holds — persistent links stay open, deliver on demand (decision 20), diversity
eviction fires correctly (decision 21) — and found/fixed a duplicate-`onServicesDiscovered`
radio-waste bug (decision 21) and a Bluetooth off→on recovery gap (decision 22), both purely by
auditing logs / probing reported behavior, not from a failure the sim gate could have predicted.
Round 3 (real distance — balcony/hall/corridor) was unambiguously positive: instant messages, correct
1-2 hop tracking. Two hop-count questions from round 3 turned out to be expected per-observer
behavior, not bugs (decision 22). Round 4 (quick 2-phone check) confirmed decision 22's Bluetooth-
recovery fix directly. **None of the four rounds yet was the sustained multi-hour session this
phase's own class doc calls for — all were short (tens of minutes) ad hoc tests. That longer session
is still the open bar before this is fully trusted.**
Persistent links replacing connect/sync/disconnect; retire the 45 s
cooldown regime; diversity-based link selection; RSSI-gated scheduling.
*Sim gate: at D = 400, diversity selection beats first-heard on reachability by a measurable margin.*
*Hardware gate: sustained multi-hour 3-phone session, no slot leaks, battery measured against the
v1 baseline.*

**P4 — Couriers (§4.2).** Group-addressed spray-and-wait for partition-crossing eventuality.
**STATUS (2026-08-09): all 4 slices done, P4 code-complete — see `docs/DECISIONS.md` decisions 41-44.**

**Slice 1 (41) — courier tag + envelope seal/open, crypto construction only.** `MeshFrameCodec.
courierTag`/`candidateCourierTags` (`HMAC(groupKey, UTC-day)`, 16 bytes) and `sealCourierBody`/
`openCourierBody` (mirroring `sealSosBody`/`openSos`, sealed under the existing `contentEpochKey` —
no new key derivation needed). Zero production call sites, no wire type, no storage, no `VERSION`
bump. 412 tests (up from 399). See decision 41's own entry for the full design and why crypto-only
was chosen as the narrowest first slice (same seal/frame-boundary lesson decision 37's own real bug
taught).

**Slice 2 (42) — persisted envelope + local creation, no relay wiring.** New `CourierEnvelopeEntity`
(`AppDatabase` v10 → v11) mirrors `SosEntity`'s shape — `groupId`/`senderId` nullable from day one
(mirroring `EvidenceEntity.groupId`'s decision-38 precedent for a future blind-carry row), `tag`/
`sealed` mirror `handle`/`sealed`, `copiesRemaining` stored (§4.2's "Copy budget 4") but inert until
slice 4. New minimal `CourierEnvelopeDao` (insert/getById/deleteForGroup/pruneOlderThan only).
`RelayEngine.createCourierEnvelope(groupId, payload): CourierEnvelopeEntity` mirrors `createSos`
exactly, deliberately does NOT bump the catalog epoch (nothing pushes courier envelopes yet). Own
24h `COURIER_MAX_AGE_MILLIS` prune constant (distinct from `CONTENT_MAX_AGE_MILLIS`'s 48h), wired
into the existing `pruneExpired` periodic sweep, proven independent by a dedicated test. **Still no
`Frame.Courier`/`FRAME_COURIER` wire type or `VERSION` bump** — deferred to slice 3 below, where a
wire type is actually needed for a GATT exchange; this slice stayed Room-only, even narrower than
originally sketched, on the same "prove one layer before the next" discipline slice 1 established.
`createCourierEnvelope` itself has no direct test (same pre-existing Keystore/Robolectric constraint
`createSos`/`createEvidence` already live with) — the persistence layer gets direct DAO-level
coverage instead. 416 tests (up from 412). No GATT push/receive yet — testable end-to-end at the
Room-entity level only.

**Slice 3 (43) — GATT wiring: push/receive, member path, bounded pool with tiers.** New
`Frame.Courier` + `FRAME_COURIER: Byte = 0x1C` + `encode`/`decode` branch, `MeshFrameCodec.VERSION`
8 → 9 (deferred here from slice 2, as planned). `GroupRepository.resolveGroupKeyByCourierTag`/
`matchCourierTag`, mirroring `resolveGroupKeyByHandle`/`matchHandle` — with a dedicated regression
test for the one real gotcha (`COURIER_TAG_LEN` must be passed explicitly, not left at
`candidateAdvertisementIds`' 6-byte default). `RelayResponder.handleCourier`/`ingestOpenedCourier`/
`takeCourierCustody` mirror `handleSos`'s resolve-then-branch shape, with blind carry storing a real
`CourierEnvelopeEntity` row (never `OpaqueFrameRelay` — confirmed wrong fit: `opaqueSos` itself uses
the same 3-minute default `MAX_AGE_MILLIS` position/presence do) that is **never re-offered to a
further peer** — the key structural finding this slice surfaced: `copiesRemaining` stays inert until
slice 4, so nothing bounds multi-hop blind propagation yet; single-hop-only is the correct scope
line, not an oversight. New `CourierPool` object (`ble/`) implements the bounded-pool-with-tiers
policy — 40 total, 20 hard-reserved for own-group (never soft-prioritized), neither tier ever hard-
rejects. `RelayEngine.admitCourierEnvelope` is the single admission gate (both self-authored and
received envelopes), using this codebase's first `db.withTransaction` to close a real concurrent-
connection race. Push/receive folds entirely into the existing `CatalogFilter` deficit-sync — no new
per-connection step or budget constant; own-group envelopes get proactively pushed, blind-carry rows
are held/advertised (stopping redundant re-pushes) but never proactively offered onward. 442 tests
(up from 416). Sim/hardware gates below become meaningful starting here — the first slice where an
envelope actually crosses a GATT connection. **NOT hardware-confirmed** — adds to the existing
next-live-round backlog (37/38/40).

**Slice 4 (44) — handover mechanics: copy-budget split + rate limiting.** New `CourierHandover.
split(copiesRemaining)` — classic Spray-and-Wait arithmetic (`floor(N/2)` given, `ceil(N/2)` kept,
`null` below `MIN_COPIES_TO_SPLIT`=2), pure, unit-tested including a conservation-property sweep
(`keep+give` always equals the input for every N 2-20). §4.2's "cap 8" is honestly NOT independently
enforced — automatically satisfied by conservation as long as nothing re-injects an envelope above
the initial budget of 4, and this slice adds no reinjection path, so 8 is never approached; flagged
explicitly rather than building enforcement for a scenario nothing in this codebase can trigger yet.
New `CourierHandoverTracker` (mirrors `ConnectionAttemptTracker`'s bounded/LRU/protect-on-access
shape) enforces the 10-minute rate limit, keyed on the peer's resolved stable identity rather than
the rotating BLE address. `RelayResponder.pushCouriersWithHandover` replaces slice 3's placeholder
verbatim-forward with a real split: persists the local `keep` value before pushing a frame carrying
`give`. `RelayEngine.relayableCourierEnvelopes` now also includes blind-carry rows with spare
budget — the actual multi-hop spray step slice 3 deliberately left disabled pending a real bound.
462 tests (up from 442). No wire-format change — `Frame.Courier`/`MeshFrameCodec.VERSION` (9)
unchanged from slice 3, only what value the already-existing `copiesRemaining` field carries.
**NOT hardware-confirmed** — adds to the existing next-live-round backlog (37/38/40/43).

**P4 is fully code-complete.** Per the project's sequencing directive, next is **P5 (Media)**.

*Sim gate: message delivered across a partition healed 20 min later; copy budget stays bounded.*
*Hardware gate: 3 phones, one carried out of range and back — message arrives.*

**P5 — Media (§4.3).** Thumbnail-first + pull-on-demand **first** (§9.2 item 8 — it is what makes
48 h retention viable at 400), then fountain coding, then L2CAP CoC and Wi-Fi Aware as bulk pipes.
Wi-Fi Direct removed.
**STATUS (2026-08-09): P5 is fully code-complete across items 1 and 2, and item 3's own two
completed steps — thumbnail-first (45), fountain coding both slices (46-47), BLE L2CAP CoC (48),
Wi-Fi Direct removed (49). Only Wi-Fi Aware itself remains within item 3, deferred to a later
slice — not started, not designed.** Next: a device-test round (per the user's own explicit
instruction), then P7 (bitchat bridge).

**Slice 1 (45) — thumbnail-first, full-res pull-on-demand.** `RelayEngine.fullResRelayable()`
(own-authored, or explicitly `wantsFullRes`-requested) replaces `relayableEvidenceMeta()` as what
`framesToPushOnConnect`'s manifest loop sends — sending a manifest is what solicits chunks, so
narrowing this set is the whole mechanism; `handleManifest` itself is untouched. `Frame.EvidMeta`
gains `thumbnail` — shipped cleartext-plus-MAC first, flagged to the user as new passive-exposure
surface before committing, **sealed under the content-epoch key per the user's choice** (same
AES-GCM treatment SOS/position already get; `MeshFrameCodec.sealThumbnail`/`openThumbnail`,
`MAX_THUMBNAIL_BYTES=256` as the sealed ceiling, `GCM_OVERHEAD_BYTES=28` exposed so
`EvidenceCapture.compressThumbnail`'s plaintext target derives from the same number).
`MeshFrameCodec.VERSION` 9 → 10. Blind relays stop carrying full-res chunks entirely, by
construction (`fullResRelayable`/`requestFullResolution` both exclude `groupId == null` rows) — the
actual 6MB→~100KB win §9.2 item 8 describes. New async `RelayEngine.decryptedThumbnail` + UI
`FeedThumbnail`/three-state `FeedRow`. 482 tests (up from 462). **NOT hardware-confirmed.** Full
detail in decision 45's own entry, including the mid-slice sealing correction.

**Slice 2 (46) — fountain-code encode/decode primitive, construction only.** First slice of item 2
(RaptorQ/fountain coding). A Plan agent scoped it first; **not literal RFC 6330** — a hand-rolled
systematic random-linear fountain code (`ble/FountainCode.kt`, new file) satisfying §4.3's own stated
property ("any k(1+ε) symbols from any combination of sources") with Gaussian elimination (GF(2))
decoding instead of belief propagation, the same kind of simplification-over-the-named-spec precedent
`CatalogFilter` already set. Real RFC 6330 (checked: OpenRQ dead since 2017/not on Maven Central; the
one Kotlin-native alternative is single-author and unpublished) and full hand-rolled RFC 6330 (LDPC+
HDPC precoding, thousands of lines) were both rejected — full detail and reasoning in decision 46's
own entry. **A real correction caught by measurement, not review, mid-slice**: the first version used
the textbook robust soliton distribution (correct choice for belief-propagation decoding, wrong one
for this class's GE decoding) and measured 1.3-2.6x the true minimum repair symbols needed; switched
to dense random coefficients (random-linear-coding territory) and re-measured at 1-2 extra symbols
regardless of k. 497 tests (up from 482, all 15 new). detekt clean, both variants green, no
`missing_rules.txt`. Version bumped to v0.7.13-dev, fresh debug APK built and `aapt`-confirmed.
**Zero production call sites** — no wire change, nothing in the running app calls this yet; nothing
to hardware-confirm. Full detail in decision 46's own entry.

**Slice 2 (47) — wiring, item 2 now fully code-complete.** One clean cutover: deletes
`FRAME_MANIFEST`/`FRAME_EVID_CHUNK`/`MeshProtocol.encodeBitset`/`decodeBitset` outright, replaces
with `FRAME_SYMBOL_REQUEST`/`FRAME_EVID_SYMBOL` wired through `RelayEngine.ingestSymbol`/
`symbolDeficit`/`symbolsToSend` and `RelayResponder.handleSymbolRequest`.
`EvidenceChunkEntity`/`Dao` renamed to `EvidenceSymbolEntity`/`Dao` (`chunkIndex` → unbounded `esi`);
`EvidenceEntity`/`Frame.EvidMeta` gain `contentLength`. `MeshFrameCodec.VERSION` 10 → 11,
`AppDatabase.version` 12 → 13 (free — no real `Migration` objects exist in this codebase, confirmed
this slice). Session chunk budget kept (renamed `maxSymbolsPerSession`), deliberately NOT deleted —
§4.3's "deletes... the session chunk budget" line describes the full Tier X architecture item 3
builds, not this slice; the budget's fairness/anti-abuse purpose still holds on the shared GATT link
until then. WFD's `maybeAccelerateOverWifiDirect`/`isWfdCapable` deleted (unreachable); the other 5
WFD files needed real mechanical fixes (they called the retired mechanism directly, not just its
types) but their actual handoff logic stays untouched and still dead, pending item 3's planned WFD
removal. **A real bug caught by this slice's own new tests**: a newly-ingested symbol wasn't reaching
an already-cached decoder (only rehydration-on-first-creation fed it), so rank could get stuck at 0
after the first connection touched `symbolDeficit` before any symbol arrived — `RelayEngineTest`'s
progression test caught it immediately. 511 tests (up from 497). detekt clean, both variants green,
no `missing_rules.txt`. Version bumped to v0.7.14-dev, fresh debug APK built and `aapt`-confirmed.
**NOT hardware-confirmed** — a real `VERSION` 11 wire break, adds to the next-live-round backlog.
Full detail in decision 47's own entry.

*Sim gate: at D = 400, per-node media storage stays bounded with 20 items circulating.*
*Hardware gate: 3 phones — a 300 KB photo delivered member-to-member without entering the flood.*

**Item 3 step 1 (48) — BLE L2CAP CoC bulk pipe, additive.** New `ble/BulkChannel.kt`/
`L2capBulkTransport.kt` — a real socket-based bulk pipe for `FRAME_SYMBOL_REQUEST`/
`FRAME_EVID_SYMBOL` traffic only (every other frame type stays on GATT), negotiated via new
`FRAME_L2CAP_CAP` (0x1F). **Confirmed gap: `minSdk` 26 is below L2CAP CoC's own API-29 floor** —
GATT's 400-byte chunking is the ONLY path on three OS versions this app still targets, not merely
"the fallback" in name; every new code path is `SDK_INT`-gated, falling straight back to GATT's
existing `respond`. No initiator/responder role restriction (unlike the retired WFD accelerator) —
an L2CAP `connect()` has no shared group state to corrupt the way WFD's did, so a race between both
sides is a harmless duplicate channel, collapsed via a per-address `Mutex`, not prevented by role.
`RelayResponder.handleSymbolRequest` prefers an open bulk channel over GATT, without GATT's own
`delay(15)` pacing (a real socket already has flow control). **NOT device-tested** — a Robolectric
spike this session found `listenUsingInsecureL2capChannel()` returns a non-functional stub
(`psm=-1`) under test; `BulkFraming`'s pure stream-framing logic is what's actually unit-tested (11
new tests), connection establishment is compile-verified only. `MeshFrameCodec.VERSION` 11 → 12.
detekt clean, both variants green, no `missing_rules.txt`. Version v0.7.15-dev. Full detail in
decision 48's own entry.

**Item 3 step 2 (49) — Wi-Fi Direct removed outright.** All 5 `transport/wifidirect/` files,
`ui/WifiDirectSettings.kt`, both WFD test files, every `FRAME_WIFI_DIRECT_CAP`/`HANDOFF`/`ACCEPT`
frame/encode/decode/wiring reference, the `HomeScreen` opt-in toggle, and the WFD-only manifest
permissions/feature (`ACCESS_WIFI_STATE`/`CHANGE_WIFI_STATE`/`NEARBY_WIFI_DEVICES`/`wifi.direct`)
all deleted. `NEARBY_WIFI_DEVICES` deliberately not kept in anticipation of the still-unimplemented
Wi-Fi Aware slice — that slice checks its own real requirement when it exists, rather than
inheriting WFD's. No `VERSION` bump (pure removal isn't independently load-bearing; decision 48's
own v12 already retired these bytes in anticipation). 505 tests (down from 522 — two whole WFD test
files plus 3 WFD-specific round-trip tests deleted). detekt clean on the first pass. Both variants
green, no `missing_rules.txt`. Version v0.7.16-dev. Full detail in decision 49's own entry.

**§4.3 item 3: L2CAP CoC done, Wi-Fi Direct removed, Wi-Fi Aware itself deferred to a later slice
(materially more novel — new radio, an unverified "no system dialog" disguise claim needing real
hardware, no local test story for the actual data-path mechanics).** P5 (Media) is otherwise now
fully code-complete across all three items.

*Sim gate: n/a (transport-layer change, no protocol-level behavior to simulate).*
*Hardware gate: 3 phones — confirm `listenUsingInsecureL2capChannel`/`createInsecureL2capChannel`
actually establish a channel at all (nothing in the automated suite touches this), then confirm a
full-res pull actually prefers it over GATT once open.*

**P6 — Crypto (§4.4).** Epoch key ratchet, rotating group handle replacing cleartext `groupId`,
padding for all frame types, SOS body encryption.
**STATUS (2026-08-08): all four slices done, P6 code-complete — see `docs/DECISIONS.md` decisions
37-40.**

**Slice 1 (37) — SOS body encryption.** `SosEntity.message`/`senderId`/`timestamp`/`isAlert` are now
AES-GCM sealed under the group key (`MeshFrameCodec.sealSosBody`/`sealSos`/`openSos`), replacing the
old cleartext-plus-HMAC scheme — closes `NEXT_STEPS.md`'s long-flagged "SOS text is authenticated but
not encrypted" gap, the same treatment position got back at v2/v3. `MeshFrameCodec.VERSION` 5 -> 6,
`AppDatabase` v8 -> v9. Found and fixed a real bug while finishing this slice's checkpoint:
`RelayEngine.createSos` was storing `sealSos`'s FULL FRAMED output as `SosEntity.sealed` instead of
raw ciphertext, which would have double-framed every self-authored SOS on send — fixed by splitting
out `sealSosBody` (raw bytes only) for the storage path. 370 tests.

**Slice 2 (38) — rotating group handle.** Every relayed frame type (SOS/position/evidence-meta/
nickname/presence) now carries an opaque `HMAC(groupKey, epoch)` handle instead of cleartext
`groupId` — closes this section's own traffic-analysis gap. Reuses (generalized, not duplicated) the
beacon's own `CryptoUtils.rotatingAdvertisementId` construction under a new 72h window (a GATT handle
is computed once and forwarded verbatim for a frame's whole relay life, unlike a beacon id re-derived
every ~60s — see decision 38's own entry for the full window-length derivation and its domain-
separation proof from the beacon's 60s one). `Frame.EvidMeta`/`Nickname` became envelope-only structs
(the same shape Sos/Position already had) since `groupId` can no longer travel in the clear;
`EvidenceEntity.groupId` went nullable (a blind carrier keeps working, just never learns which group)
while `NicknameEntity` gained a genuinely new in-memory blind-relay path (traced: its old cleartext
scheme's blind-relay case was already a dead end, never re-served to anyone). Found and fixed a real
bug: `opaqueSos.framesToRelay(...)` was never called anywhere since decision 37 added it — SOS blind
custody accepted frames but never forwarded them. `MeshFrameCodec.VERSION` 6 -> 7, `AppDatabase`
v9 -> v10. 381 tests (up from 370).

**Slice 3 (39) — content-sealing epoch key, and a correction to this section's own table above.**
The epoch key ratchet does NOT give forward secrecy against seizure, contrary to what §4.4's table
originally claimed — the root key must stay permanently retained regardless (slice 2's wire handle
and `GroupRepository.getShareCode`'s invite-code reconstruction both need it forever), so any
non-interactive derivation from it is exactly as forward-secret as the root key's own confidentiality.
Real forward secrecy needs an interactive DH step this partition-heavy mesh can't rely on. Built the
honest stateless version anyway (`CryptoUtils.contentEpochKey` = `HKDF(rootKey, epoch)` via Tink's
`subtle.Hkdf`, 24h epochs, fully stateless, no schema changes) — it now seals SOS/position bodies and
macs evidence-meta/nickname/presence/Tier-B-SOS-preview instead of the root key directly, kept fully
separate from slice 2's (unchanged) wire handle. Real value it does provide: domain separation between
the two derivations, and bounding one independently-leaked epoch key to ~24h instead of the group's
whole life. `MeshFrameCodec.VERSION` unchanged (byte layouts didn't move, only which key opens them).
390 tests (up from 381).

**Slice 4 (40) — frame padding to size buckets, P6's last item.** `MeshFrameCodec.padGattFrame`/
`unpadGattFrame` wrap every GATT frame in `[realLen: UShort][frame][random padding to the nearest of
256/512/1024/2048 bytes]` so wire length alone can't fingerprint frame type/content length. Lives at
the GATT transport choke point (`MeshGattClient.write`/`MeshGattServer.notify` and their receive-side
counterparts), not inside `encode`/`decode` — `FRAME_POSITION`/`FRAME_CATALOG_FILTER` are reused
verbatim by `BeaconRadio` for Tier B, whose 251-byte budget could never absorb a 256-byte floor, and
Tier B never touches those four transport functions, so this placement excludes it automatically. A
frame at/past the largest bucket ships unpadded (length-prefixed only) — same pre-existing no-MTU-
fragmentation gap either way, not something this slice introduces or fixes (confirmed via a fresh
Explore research pass: every GATT frame is one ATT write, no chunking fallback exists for anything
but evidence, which has its own separate indexed-chunk mechanism SOS never reuses).
`MeshFrameCodec.VERSION` 7 -> 8 (outer wrapper only, no `Frame`/`decode()` field changed). 399 tests
(up from 390).

All four slices: detekt clean, both variants compile/test/assemble (`assembleDebug`/`assembleRelease`,
incl. `lintVitalRelease`, R8-minified) green. **NOT hardware-confirmed** — decisions 38/40's `VERSION`
7/8 wire breaks mean no pre-checkpoint test APK can talk to this build until reflashed (decision 37's
own `VERSION` 6 was never hardware-confirmed either — 37/38/40 all need the same next live round;
decision 39 adds a silent open/verify-failure mode on top, since it isn't a version break, and can
ride the same round).
*Sim gate: handle rotation does not break dedup or forwarding across an epoch boundary.*
*Hardware gate: security review pass; wire capture shows no cleartext group identifier, and GATT
frame sizes cluster on the four padding buckets rather than varying with content.*

**P7 — bitchat bridge (§3 Level 1). Confirmed in scope**, opt-in and off by default.
**STATUS (2026-08-09): planning only, no code written yet — design pass completed ahead of the
device-test round, per the user's own explicit sequencing (device test first, then P7). See
`docs/DECISIONS.md` decision 51 for the research this rests on.**

**What it is.** A separate, user-toggled advertiser/scanner pair on bitchat's own BLE UUIDs
(service `F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C`, characteristic `A1B2C3D4-E5F6-4A5B-8C9D-
0E1F2A3B4C5D` — confirmed current against bitchat's own source this pass, not just restated from
Part 3's original table). A bridge-enabled device is simultaneously an **injector** (puts our own
sealed group traffic onto bitchat's mesh) and a **listener** (watches bitchat's mesh for OUR OWN
traffic, recognisable only to us because we hold the group key to open it — every other bitchat
node just sees opaque bytes and relays them per their own design, exactly like our own blind-relay
pillar in reverse). We never become a bitchat client, never run their Noise handshake, never adopt
their peer ID.

**The concrete mechanism, grounded in this pass's research against bitchat's actual source (not
just the week-old summary table in Part 2):** bitchat has a packet type built almost exactly for
this — `groupMessage` (`0x25`, "group-encrypted broadcast, cleartext group ID + ChaChaPoly body").
Unlike `noiseEncrypted` (session-addressed, 1:1, needs a real handshake), `groupMessage` is
broadcast-addressed and — critically — bitchat's own relay-scheduling logic forwards it
**unconditionally**: no group recognition, no signature, no real identity required to be relayed.
The only hard structural requirement found is a receive-side ingress guard rejecting anything more
than 120 s of clock skew from the timestamp field — trivial to satisfy (use real device time). A
forged, unsigned `groupMessage` packet — broadcast `recipientID`, current timestamp, default TTL
7, an arbitrary 8-byte `senderID`, our own already-sealed ciphertext as payload — appears to
satisfy every check a relaying bitchat node applies.

**The free multi-hop insight.** A device that receives one of our own messages via the bitchat
listener should feed it into the **exact same ingestion pipeline** an ordinary received GATT frame
already goes through (`RelayResponder.handleSos`'s dedup → hop-track → `floodForwardSos`), just
with the bitchat mesh as the "arrival link" instead of a GATT connection. If it does, P1's existing
flood-forward logic (already built, already tested) automatically re-floods the message onto that
device's own currently-open BLE links to other 20.07 devices — meaning a message can cross a real
gap between two separate physical clusters of 20.07 users, bridged by ordinary bitchat traffic in
between, **with no new relay logic to write**. The only new engineering is the injection/listening
layer and mapping "arrived via bitchat" onto whatever identity `excludeKey`/hop-tracking currently
expects a `peerAddress` to be — a real design detail for the implementation slice itself, not
resolved here.

**Hard dependency before any of this becomes production code: a live spike against a real,
current bitchat build.** Everything above comes from reading bitchat's own source this session,
not from testing against a running instance — the research agent's own words: *"a strong lead, not
a proven-safe conclusion."* bitchat is also more developed than Part 2's table assumed: real
signature verification on several other packet types, a `NoiseRateLimiter`, `VouchAttestation`
(a web-of-trust layer), and an in-progress peer-ID-rotation effort not yet live. None of it
contradicts this design, but whether any of it quietly deprioritises traffic from a `senderID`
bitchat has never seen before is genuinely unknown until tested against a real device running a
real bitchat build. **First implementation task, not skippable: confirm a forged `groupMessage`
packet actually gets relayed by a real bitchat node, before writing anything else.**

**Spike tooling shipped (2026-08-09, decision 55) — the test itself still needs a real bitchat
install to run against, which is not something buildable from here.** New, deliberately isolated
package `org.offlinemesh.app.bitchatbridge` (nothing in `MeshService`/`RelayResponder`/the real
mesh touches it): `BitchatPacketEncoder` (pure, unit-tested — exact v1 header byte layout confirmed
against bitchat's own source this pass, more precisely than the structural summary above: 14-byte
header, `senderId` always exactly 8 raw bytes at a fixed position, broadcast `groupMessage` sent
with `flags=0`/TTL 7, confirmed NOT auto-fragmented for a small payload) and
`BitchatSpikeTransport` (debug-only, same boundary `DiagnosticsLog` sits behind — scans for either
of bitchat's two service UUIDs, since **a debug bitchat build advertises a DIFFERENT UUID than its
release build**, connects, writes one forged packet with a random marker, logs every step under
`DiagnosticsLog` tag `bitchat-spike`). Debug-only `BitchatSpikeRow` in `HomeScreen.kt`, one tap.
**What a clean write confirms, precisely: bitchat's own peripheral GATT accepted a well-formed
packet from an unfamiliar sender — the first half of the dependency above. It does NOT by itself
confirm multi-hop relay** — that needs a third device or a packet capture watching whether the
marker keeps showing up further out than one hop should reach, a separate manual step. 510 tests
(5 new), detekt clean, both variants green, version v0.7.20-dev. Full detail in decision 55.

**Standing constraints carried over from Part 3, unchanged:** off by default, clearly labelled —
advertising bitchat's own service UUID is a public "this device runs bitchat" signature, a real
problem in any jurisdiction where bitchat itself is restricted. Battery/radio cost of a second
advertise+scan session running alongside our own is real and unmeasured; expect this to need its
own hardware round once built, separate from the P0b-P6 backlog.

*Sim gate: n/a — this is entirely about a third party's live radio behaviour, nothing to simulate.*
*Hardware gate (two-stage): (1) the spike above — a forged packet from a 20.07 device is actually
relayed by a real, unmodified bitchat install; (2) once (1) holds, the full gate — a 20.07 message
relayed through a bitchat-only device and received by a second 20.07 device.*

---

## Part 8 — What v2 deletes

Deleting is most of the win here; v1's transport carries a lot of machinery that exists only to
compensate for the sync-primary design.

- `PeerDeliveryTracker`-style per-peer sync bookkeeping (already gone) and the per-connection
  catalogue-push path as a *primary* mechanism.
- `maxChunksPerSession` / `maxCatalogItemsPerSession` session budgets — artefacts of "one connection
  must carry everything."
- `HopTracker.effectiveStaleMs` per-hop slack — a workaround for 45 s/hop propagation that becomes
  unnecessary once propagation is sub-second.
- `syncedReconnectCooldownMs` / the epoch-based cooldown-skip machinery.
- The evidence manifest + have-bitset + deficit computation (replaced by fountain coding).
- Wi-Fi Direct entirely (`transport/wifidirect/`, 5 files) — replaced by Wi-Fi Aware, and it raises a
  system dialog that breaks the disguise.
- ~~BT5 Coded PHY long-range channel~~ — **resolved 2026-08-06, decision 26**: re-scoped (not
  deleted) behind the P2 broadcast tier, exactly as this item proposed. It's no longer a standalone
  Coded-PHY-only channel — `BeaconRadio`'s broadcast tier now gates on `extendedAdvertisingSupported`
  alone and requests Coded PHY only as an opportunistic add-on. The 100%-failure-on-test-hardware
  history was specific to the OLD narrower gate; not yet re-tested against the new one (still not
  hardware-confirmed either way).

---

## Part 9 — Decisions

### 9.1 LOCKED (2026-07-31)

**Scope is an operating envelope, not a target number.** v2 serves **a group of 3–8 people whose
carrier medium varies from 2 to ~400 nodes, within a single session.** All four regimes in §5.5
(R1 isolated, R2 group-inside-swarm, R3 boundary crossing, R4 partitioned) are in scope; R2 is the
primary real-world case and R3 is where the failures live.

Three consequences of stating it this way rather than as "tune for N":

- **We are not engineering for 400 people.** Group-scoped work (presence, radar, nicknames,
  positions) is O(3–8) forever. Only blind-relay traffic scales, which is why §5.5's relay budget is
  a new v2 requirement rather than a nicety.
- **The 3-phone case is not a degraded mode.** It is regime R1, a first-class supported state that a
  real user enters every time they leave a crowd — and per §5.4 it is where every adaptation is
  switched off, making it the cleanest test of core logic we have.
- **1000+ is an explicit non-goal.** Noted in this document where it would change an answer (§9.2
  item 5), never designed for.

**bitchat interop: Level 0 core + Level 1 bridge as P7, opt-in and off by default.** Level 2 is
explicitly out of scope.

**Nostr / internet fallback: not required, rejected for v2** — see §4.5 for the three reasons.

### 9.2 What "200–400 in range, working at 3" actually forces

Fold these into the relevant phases before starting them. Numbers below assume D ≈ 400 unless stated.

1. **Scan-callback storm is a new first-order problem (blocks P1/P2).** Pass 12 removed hardware
   scan filtering entirely — scanning is unfiltered with app-level matching in `onScanResult`. At
   400 app devices advertising on `ADVERTISE_MODE_BALANCED` (~250 ms) that is ~1600 matching adverts
   per second, and in a real crowd another few hundred *unrelated* BLE devices (earbuds, trackers,
   beacons, POS terminals) push raw callback volume several thousand per second — all landing on
   the BLE thread before any of our logic runs. Required: **restore a hardware `ScanFilter` on the
   service UUID**, which drops non-app devices in controller firmware rather than in our callback.
   Note this is *service-UUID* filtering, which is reliable — not the *service-data* filtering
   correctly removed in Pass 12 as chipset-unreliable. They are different filter types, and
   conflating them is why we ended up with no filtering at all. Add `ScanSettings.setReportDelay()`
   batching (1–2 s) **above the degree floor only**, so 3-phone discovery stays immediate.
   Acceptable latency cost: the broadcast tier's freshness window is 180 s, so 1–2 s of batching is
   free; SOS tolerates it too.

2. **Link selection matters more than link count.** With D ≈ 400 heard and ~4–6 links maintainable,
   we hold about 1.5 % of the graph — *which* 1.5 % dominates reachability entirely. First-heard
   (current behaviour) is close to worst-case: it clusters links on whoever is physically nearest.
   Select for **diversity** — spread across RSSI bands, evict redundant links rather than old ones.

3. **TTL is not the sensitive parameter; fanout and dedup are.** A ~5-regular graph over 400 nodes
   has expected diameter ≈ log₅(400) ≈ 3.7 hops, so **TTL 6 covers the target network with real
   margin** (and would still cover 1000 nodes at ≈ 4.3). Our existing `DEFAULT_TTL = 8` is already
   sufficient; it just never drives a forward. Fanout is where the tuning risk lives — see §5.4's
   degree floor, and note that at these sizes full flood costs ~2000 transmissions per message
   versus ~920 with log₂ subsetting, so subsetting is worth having but is not load-bearing at 400
   the way it would be at 10,000.

4. **Trickle is mandatory for broadcast, and must not fire at 3 phones.** Ungoverned announce at
   D = 400 is 400 adverts per interval per neighbourhood. Trickle's "hear enough consistent copies,
   stay quiet" keeps that O(1) per node. At D = 3 there are never enough consistent copies to
   suppress anything, so it self-disables — which is the correct behaviour, and is exactly the
   §5.4 identity-function property. Promote `TrickleTimer.kt` in **P2**.

5. **The catalogue Bloom filter survives at this scale — barely — so keep it and design for
   replacement.** A blind relay in a 400-person crowd might hold ~500–2000 catalogue keys. At 2000
   items and a 1 % false-positive target, a Bloom filter needs ~9.6 bits/item ≈ 2.4 KB, or roughly
   five writes at a negotiated 517-byte MTU — wasteful but workable, *once per periodic backfill
   exchange instead of once per connection*. (`CatalogFilter`'s original 2048-bit/5-hash tuning is
   for "hundreds of items" and would be near-useless at 2000; the dynamic sizing added in pass 23
   is what makes this survivable.) **Range-based set reconciliation stays on the shelf, not in v2** —
   it is the right answer at 1000+ where Bloom's linear growth stops fitting, but it is meaningful
   implementation risk to hand-roll and the target scale does not force it yet. Keep the
   reconciliation interface narrow enough that RBSR can replace Bloom behind it later.

6. **Dedup LRU sizing must be derived, not copied — and the broadcast tier is what keeps it small.**
   Only *relayed* traffic enters the dedup set. If Tier B carries presence/position (one-hop
   broadcast, never flooded), the flood sees only SOS, evidence headers and courier envelopes —
   order 1–5 unique packets/second at busy moments, so ~1500 entries over a 5-minute window and
   2000–4000 entries is safe (~100 KB). If Tier B slips and presence/position end up in the flood
   instead, the same window needs ~15,000 entries and the whole thing becomes fragile. An
   undersized dedup set in a flood protocol causes re-flooding storms, which is the failure mode
   that takes a mesh down. **Derive the number in P0a; treat "what is allowed to enter the flood"
   as the primary lever, not the LRU size.**

7. **The broadcast tier (P2) is required, not deferrable.** At D = 400, connection-based presence
   cannot work: 400 peers through ~5 slots at ~6 s each is ~8 minutes per round against a 180 s
   freshness window — off by 3× before accounting for any failure. P2 is the only mechanism that
   makes the radar function at target scale, which promotes it from "amplification" to "required
   for the headline feature."

8. **Media retention only works at this scale because of thumbnail-first.** A blind relay carrying a
   400-person crowd's traffic for 48 h is fine for text-sized items (~40 SOS/hour × 48 h × 200 B ≈
   400 KB). Evidence is what breaks it: 20 circulating photos at 300 KB is 6 MB per phone of
   ciphertext most carriers can never read. With thumbnail-first (§4.3) the same 20 items cost
   ~100 KB. This is the concrete justification for putting P5's thumbnail work ahead of fountain
   coding, and for revisiting the 48 h window (§9.3 item 3).

### 9.3 Still open

1. **Where does the blind-relay budget sit (§5.5)?** At D = 400 we cannot carry everything for
   everyone without competing with our own group's SOS for radio. Proposed default: blind relay
   capped at ~30 % of radio time and ~30 % of content storage, with SOS exempt from the cap in both
   directions. This weakens a stated pillar under load and is therefore a product decision, not an
   engineering one — the honest framing is *"we relay for strangers, but never at the cost of our own
   group's emergency traffic."* **Decision (2026-08-09, user, revisited after the hardware round):
   fold into P7's own implementation work, not a standalone slice.** The hardware round gave real
   data first: relay ran as a pure blind (non-member) carrier for a full ~26-minute session with no
   observed radio contention or starvation — explained by decision 52's own finding (item 7 below):
   blind custody only pushes at the next connection cycle, not immediate flood, which is itself a
   natural throttle on how much radio time it can consume. Given that self-throttling, a hard cap
   isn't urgent — but P7 is the next thing touching blind-relay-adjacent code (bitchat-mesh
   injection), so design both together rather than as two separate passes.
2. ~~Wi-Fi Direct: remove now or after P5?~~ **Resolved (2026-08-09, decision 49): removed
   outright at P5**, alongside L2CAP CoC landing as its replacement bulk pipe (decision 48) —
   see the P5 write-up above.
3. ~~Do we accept a stable *local* identity (§5.2)?~~ **Resolved (2026-08-05, decision 15,
   P0b): yes.** `GroupRepository.ensureSenderIdentity` + `PeerIdentityResolver` already ship this —
   state keys on the per-group Ed25519 pubkey, not the rotating BLE address. Nothing new went on
   the wire, per the original recommendation.
4. ~~Is 48 h still the right content lifetime~~ once couriers exist and groups expire by join code?
   Courier envelopes at 24 h and content at 48 h may want to converge. **Decision (2026-08-09,
   user): keep them deliberately separate — locked in.** Couriers stay short-lived/urgent-focused at
   24h; content stays 48h as a genuinely different kind of data (bounding storage/radio cost, not a
   membership question) — that reasoning holds regardless of how long any one group itself lives.
   **Follow-up question raised (2026-08-09, revisited): does content need to be clamped to the
   group's own remaining expiry, so it can't meaningfully outlive a group that expires sooner than
   24-48h out?** ~~Checked against the actual code before answering: **already handled, no gap.**~~
   **CORRECTED 2026-08-09 by the Part 10 review pass — this conclusion was WRONG for couriers.**
   The half that holds: `GroupRepository.expireGroups()` does run both at every service start AND
   every 30 minutes while active (`MeshService.startPruning`), and `dismantleGroup` does delete a
   group's SOS/evidence/nickname/peer-key rows immediately, so *those* can outlive their own group
   by at most the ~30-minute sweep interval. The half that was wrong: `dismantleGroup`
   (`GroupRepository.kt:217`) **never touches `courier_envelopes` at all** — `GroupRepository`
   doesn't even hold that DAO, and `CourierEnvelopeDao.deleteForGroup` (`Daos.kt:122`) is called
   only from tests. A dismantled group's own courier envelopes therefore survive with a non-null
   `groupId`, stay in `relayableCourierEnvelopes()`, and keep being handed to peers for up to the
   full 24h `COURIER_MAX_AGE_MILLIS`. Tracked as **CR-2** in Part 10, Gate A. The original locked
   decision above (couriers 24h, content 48h, deliberately separate) is unaffected and stands.
5. **Manual relay-pattern tuning knobs, user-facing (proposed 2026-08-06, not yet built).** A
   settings section exposing the actual §5.4/§5.1 tuning constants (Trickle intervals/redundancy
   constant, fanout subsetting thresholds, connection counts, TTL) as user-adjustable, for someone
   who knows their own situation is dense/sparse/indoor/outdoor better than degree-measurement can
   react to. Sensible defaults preset, a clear "don't touch this unless you know what you're doing"
   warning, and a matching guide in the README/site. **Sequencing matters more than the feature
   itself**: this only makes sense to build AFTER §5.4's automatic, degree-driven adaptation (P2
   plus the rest of §5.4) is implemented and hardware-confirmed, not before — building it earlier
   means "presets" are just raw, untested knobs with no proven automatic baseline to preset *from*,
   and risks a user picking the wrong manual preset and getting WORSE behaviour than the automatic
   system would have given them for free (the whole point of §5.4's "adapt from measured degree,
   not a fixed schedule" design). Revisit once P2 ships and the sustained-session gate (§6.4) is
   cleared for P1+P3, not before.
6. **Bulk-pipe (L2CAP CoC) traffic is not size-padded, unlike everything else (found 2026-08-09,
   planning pass ahead of P7).** P6 slice 4 (decision 40)'s `padGattFrame`/`unpadGattFrame` wraps
   every frame at the GATT transport choke point (`MeshGattClient.write`/`MeshGattServer.notify`);
   decision 48's L2CAP bulk channel is a separate raw socket (`BulkFraming`, plain 4-byte length
   prefix, no bucket rounding) that bypasses that choke point entirely by construction — see
   `BulkChannel.kt`'s own doc for why padding was deliberately not silently bolted on there. Net
   effect: fountain-code symbol traffic over L2CAP leaks its exact size, while every other frame
   type doesn't. **Resolved (2026-08-09, decision 52): the trigger condition (L2CAP proven to work)
   was met by the hardware round** — both edge phones completed real L2CAP bulk symbol transfers.
   Padding added: `RealBulkChannel.send`/`receiveLoop` now wrap/unwrap each frame through the same
   `MeshFrameCodec.padGattFrame`/`unpadGattFrame` GATT already uses, applied before `BulkFraming`'s
   own length-prefix framing — safe reuse since by that point a complete in-memory frame already
   exists (the original objection in `BulkChannel.kt`'s doc was about a raw stream having no frame
   boundary at all, which `BulkFraming` already solves independently of padding). Full detail in
   decision 52.
7. **New finding (2026-08-09, hardware round): blind-relay (non-member) traffic only propagates at
   the next connection cycle, not immediately.** `RelayResponder`'s opaque/blind custody paths
   (`takeOpaqueSosCustody`/position/presence) never call `floodForwardSos`-style immediate forward —
   only a group MEMBER's own traffic gets that. A blind carrier only offers what it's holding via
   `framesToPushOnConnect` at its next fresh connection with a peer, and P3 deliberately keeps links
   open for long stretches, so "next connection" can be a real wait. This is what caused this
   round's multi-second-to-two-minute SOS/presence delays once the relay phone deleted its own group
   and became a blind carrier for eg1↔eg3 traffic — confirmed from the exported diagnostics, not
   speculation. Not a bug — an inherent, currently-unaddressed consequence of the existing design.
   **Decision (2026-08-09, user): decide once P7 is actually being built**, not now — P7's own design
   already avoids inheriting this (bitchat-bridged content a device can decrypt is ingested via the
   normal MEMBER path, not blind custody, so it gets immediate forward already), but whether to also
   speed up blind custody itself is a real open question to weigh during P7's implementation, not
   before it.

---

## Part 10 — Pre-hardware-test fix list (full-codebase review, 2026-08-09)

**Origin.** A read-through of every file in `app/src/main` (~13.4k LOC, 56 Kotlin files) looking
for: redundant/dead code, regressions, bugs, variable/parsing issues, memory leaks, over-engineering
with a shorter honest alternative, UI/UX gaps, and bad parameters/ranges/constraints. No code was
changed and nothing was committed — this section is the entire output, written to be resumable cold.

**How to read an entry.** Each has a stable id (`CR-n`), a one-line claim, the exact evidence
(file:line), why it matters *for this project specifically*, the proposed fix, and how to verify.
Every claim was checked against source. Where a finding contradicts something this plan or a code
comment currently asserts, that is called out explicitly — those are the dangerous ones, because
the wrong statement is what stopped anyone looking.

**Gates.**
- **Gate A — blocks the device-test round.** Each of these either makes the round measure something
  that isn't real, degrades or crashes the session mid-run, or would send the next debugging session
  chasing a phantom. 13 items — all fixed and compile-verified 2026-08-09.
- **Gate B — fix in the same pass if cheap; each is small and low-risk.** 7 items — all fixed and
  compile-verified 2026-08-09.
- **Tracked — after the round.** Originally 10 items, including two genuine product decisions that
  were the user's call, not an engineering one. **Both decided and this whole tier closed out
  2026-08-09** (a second pass, "whatever can be done, do it"): CR-28 (shake-to-panic-delete) was
  never scoped and is now cut — removed from this plan entirely, not tracked as a gap; CR-29
  (plaintext evidence at rest) is working as intended, not a bug — see its own entry. Of the
  remaining 8, 6 were fixed (CR-22/23/25/26/27 code fixes, CR-24 partially — its logging half
  shipped, its threshold-re-derivation half still needs the round's own data), 1 (CR-21) was checked
  and deliberately left alone (already gated on hardware evidence that doesn't exist yet, per
  decision 18 — see its own entry for why "do it" meant "don't" here), and 1 (CR-30) is a
  meta-observation with nothing to separately action. **29 total findings, all dispositioned.**

Suggested execution: land Gate A as one commit per logical cluster (they group naturally into
crypto/wire, connection lifecycle, and trackers), Gate B as one cleanup commit, then rebuild the
debug APK and run the round. Full suite + detekt + both variants green after each, per this
project's standing discipline.

---

### Gate A — must be fixed before the next hardware round

#### CR-1 — The Tier B SOS content preview has never been transmitted (decisions 29/30/31 are dead on the wire) — ✅ FIXED 2026-08-09

**Evidence.** `MeshProtocol.kt:113` declares `private const val MAC_LEN = 32`, documented as "Fixed
HMAC-SHA256 output length". `CryptoUtils.kt:243-252`'s `authTag` returns a **truncated 16-byte** tag
(`MAC_TAG_LEN = 16`, `.copyOf(MAC_TAG_LEN)`). `encodeBroadcastTierBeacon` gates the whole content
block on `content.mac.size == MAC_LEN` (`MeshProtocol.kt:244`). The only production producer of that
mac is `BeaconRadio.sosContentFor` (`BeaconRadio.kt:575`), which calls `CryptoUtils.authTag`. So
`includeContent` is **always false in production**, on every device, since decision 29 shipped.

It is broken symmetrically on receipt too: `BeaconRadio.verifyBroadcastSosContent`
(`BeaconRadio.kt:1046`) compares a freshly-computed 16-byte `authTag` against `content.mac`, and
`constantTimeEquals` returns false on a size mismatch — so even a hypothetical 32-byte mac arriving
from somewhere would be rejected.

**Why the tests didn't catch it.** `MeshProtocolBroadcastTierTest.kt:214` builds the mac as
`ByteArray(32) { it.toByte() } // stand-in for a real CryptoUtils.authTag output` — the comment
states the assumption that is false. Worse, line 267 asserts that a **16-byte** mac is correctly
*rejected* as "wrong length": the suite encodes exact production behaviour as its failure case. Every
other broadcast-tier test uses `ByteArray(32)` literals too, so the whole file is testing a shape
production never produces.

**Why it matters.** This is the single feature that lets an SOS reach someone with **no GATT
connection at all** — decision 29's stated purpose, and the thing that makes the alert "instant" in
a crowd where connection slots are the scarce resource. Everything downstream of it is also dead:
`MAX_BROADCAST_TIER_SOS_MESSAGE_BYTES` (65) and the entire 251-byte budget arithmetic in
`encodeBroadcastTierBeacon`'s doc, `BroadcastSosPreview` (the whole class), `NavigateScreen`'s
preview surface, and — user-visibly — `GroupChatScreen.kt:216-228`'s live byte counter which
currently tells the user their message *"fits the instant SOS broadcast preview"*. That is a false
statement shown in the UI on every keystroke.

Going into a hardware round without this fixed means either not testing the preview at all, or
testing it, seeing nothing, and spending the round debugging the radio.

**Fix.** Make the two constants share one source of truth rather than agreeing by coincidence:
expose `CryptoUtils.MAC_TAG_LEN` as `const` and set `MeshProtocol.MAC_LEN = CryptoUtils.MAC_TAG_LEN`
(or inline 16 with a doc pointing at `authTag`). Do **not** widen `authTag` to 32 — 16 bytes is a
deliberate wire-budget choice documented at `CryptoUtils.kt:245-247` and is used by every other MAC
in the app; changing it is a `MeshFrameCodec.VERSION` break for no benefit. Changing `MAC_LEN` alone
is a Tier B wire-shape change (the content block shrinks by 16 bytes) — it only affects a field that
has never been transmitted, so nothing can be out there parsing the old shape, but bump `VERSION`
anyway per this project's standing "every wire-affecting change is one discoverable signal" rule.

**Verify.** Add a test that builds `SosAlert.Content` via a real `CryptoUtils.authTag(...)` call and
asserts `decodeBroadcastTierBeacon(encodeBroadcastTierBeacon(...)).activeSos?.content != null` — i.e.
prove the round trip works with a *production-produced* mac, not a literal. Then delete or rewrite
`MeshProtocolBroadcastTierTest.kt:267`'s "wrong length" case, which asserts the bug. On hardware:
`DiagnosticsLog` tag `recv`, message "broadcast-tier SOS content preview verified" should appear
without any GATT connection having formed.

#### CR-2 — `dismantleGroup` leaves courier envelopes behind (and §9.3 item 4 said otherwise) — ✅ FIXED 2026-08-09

**Evidence.** `GroupRepository.dismantleGroup` (`GroupRepository.kt:217-228`) deletes evidence
symbols, evidence, SOS, nicknames, peer keys, the group row, the group key and the signing keypair.
It never touches `courier_envelopes`, and `GroupRepository` holds no reference to that DAO.
`CourierEnvelopeDao.deleteForGroup` (`Daos.kt:122`) exists and is called **only** from
`RelayEngineTest.kt:420/424`.

**Why it matters.** `dismantleGroup`'s own doc says *"Actually deletes the group and everything
relayed for it — not just hides it."* After a manual delete or an automatic `expireGroups`, that
group's courier envelopes keep a non-null `groupId`, so `relayableCourierEnvelopes()`
(`RelayEngine.kt:323`) keeps returning them and `pushCouriersWithHandover` keeps handing them to
peers for up to the full 24h `COURIER_MAX_AGE_MILLIS`. The device is relaying sealed content for a
group the user believes they destroyed — a direct violation of the product's own delete promise, and
the exact scenario decision 52's hardware round exercised (a phone deleting its own group mid-session
and becoming a blind carrier).

**This is also a plan correction**: `§9.3 item 4` claimed this was "checked against the actual code…
already handled, no gap." It has been corrected in place above.

**Fix.** Add `courierEnvelopeDao` to `GroupRepository` and call `deleteForGroup(groupId)` inside
`dismantleGroup`, alongside the existing deletes. One line plus a field.

**Verify.** Extend the existing `GroupRepository` dismantle test (or add one) asserting a courier row
for the group is gone afterwards, and a row for a *different* group survives. `RelayEngineTest`
already has the DAO-level coverage — this is about the caller.

#### CR-3 — `padGattFrame`'s 256-byte floor is not MTU-aware; any link below MTU 259 loses delivery — ✅ FIXED 2026-08-09

**Evidence.** `MeshFrameCodec.padGattFrame` (`:226-241`) rounds every frame to the first of
`PAD_BUCKETS = [256, 512, 1024, 2048]` that fits, so **the minimum size of any GATT frame this app
emits is 256 bytes**, regardless of content or connection. Usable ATT payload is `mtu - 3`
(`MeshProtocol.ATT_WRITE_OVERHEAD_BYTES`). Both transport choke points pad unconditionally:
`MeshGattClient.write` (`:265`) and `MeshGattServer.notify` (`:137`).

On a connection that negotiates **MTU < 259**:
- **Server→client notify has no fragmentation path.** `notifyCharacteristicChanged` with a 256-byte
  value on e.g. a 247-MTU link (244 usable) fails. This kills the *entire* server-role push
  direction — catalog-filter responses, presence and position refreshes, symbol pushes.
- **Client→server write silently becomes a prepared (long) write.** Android's stack promotes any
  `WRITE_TYPE_DEFAULT` value longer than `mtu-3` to the ATT prepare/execute procedure. The peer's
  `MeshGattServer.onCharacteristicWriteRequest` (`:226-237`) **ignores both `preparedWrite` and
  `offset`, and there is no `onExecuteWrite` override anywhere in the file** — each fragment is fed
  to `unpadGattFrame` as though it were a whole frame and dropped as malformed.

**Compounding it**, the MTU-fallback logic that exists specifically to handle this
(`RelayResponder.framesToPushOnConnect`, `:322-369`) compares the **unpadded** `filterFrame.size`
against `maxFrameBytes` — so on a 247-MTU link a 40-byte filter "fits" a 244-byte budget, is sent,
and is then padded to 256 by the transport underneath. The check cannot ever be right as written.
And its fallback branch (eager push of every relayable item) emits *more* oversized frames, not
fewer, so the safety net makes the failure worse.

**Why it matters for the round.** Decision 52's three phones all granted the requested 517, which is
why this looked clean. It will surface on the first phone that caps lower (247 and 185 are both
common OEM values), or any time `requestMtu` fails at all — in which case `negotiatedMtu` has no
entry and `pushOnConnect` falls back to `DEFAULT_ATT_MTU` (23), where nothing can be delivered.
A round that happens to include one such phone would present as "one phone is invisible/silent",
which is the single most expensive symptom to misdiagnose in this codebase's history.

**Fix (two parts, both needed).**
1. Make padding MTU-aware: pass the connection's usable payload into `padGattFrame` and select the
   largest bucket that still fits, falling back to "length-prefix, no padding" when even the smallest
   bucket doesn't. This gives up some size-obfuscation on constrained links — an acceptable, and
   explicitly documented, trade against total delivery failure. Note `padGattFrame`'s existing doc
   already contemplates an unpadded case for oversized frames; this extends the same escape hatch
   downward.
2. Make `framesToPushOnConnect`'s budget check use the **padded** size, so the catalog-filter-vs-
   eager-push decision is made against what will actually go on the wire.

Separately consider implementing `onExecuteWrite` + offset reassembly in `MeshGattServer` as
defence in depth, since a peer on a future build could still long-write. That part is optional for
the round; parts 1 and 2 are not.

**Verify.** A unit test asserting `padGattFrame(frame, usable = 244).size <= 244` for a small frame
and that `unpadGattFrame` still round-trips it. On hardware: force a low MTU on one phone (or add a
debug flag that requests 185 instead of 517) and confirm two-way delivery still works — this is the
one Gate A item that genuinely needs a deliberate test setup rather than just "run the round".

#### CR-4 — `MeshGattServer.stop()` leaks `ConnectionRegistry` entries, corrupting the degree signal — ✅ FIXED 2026-08-09

**Evidence.** `MeshGattServer.stop()` (`:126-128`) is `try { gattServer?.close() } catch {}` and
nothing else. It does not clear `connectedDevices`, `subscribedDevices`, `negotiatedMtu` or
`registeredKey`, and — critically — never calls `connectionRegistry.unregister(...)` for its
registered links. `MeshGattClient.disconnectAll()` (`:255-257`) does the full cleanup, so the two
roles are asymmetric.

**Why it matters.** `stopRadios()` is called on every "Offline" toggle *and* on every Bluetooth
adapter off→on transition handled by `MeshService.bluetoothStateReceiver` (`MeshService.kt:200-223`,
which deliberately does `stopRadios(); startRadios()`). Each cycle leaves dead push callbacks in the
registry pointing at a closed `BluetoothGattServer`. Consequences:
- `ConnectionRegistry.openLinkCount()` is permanently inflated. That count is the **only** degree
  signal `ForwardingPolicy` uses — for TTL clamping, fanout subsetting and jitter. Every §5.4
  adaptation the round is meant to observe is now driven by a number that ratchets upward and never
  comes down.
- `RelayResponder.floodForwardSos` picks targets from `connectionRegistry.others(...)` and burns its
  fanout subset on links that cannot deliver, while `sentCount/targets.size` in the diagnostics line
  quietly under-reports for reasons that look like radio failure.
- `periodicRefresh` loops keep running because `connectedDevices` still holds the addresses.

Since decision 52's own round involved Bluetooth toggling as a recovery step, this is very likely
already polluting past data.

**Fix.** Give `MeshGattServer` a symmetric teardown: iterate `registeredKey` unregistering each,
then clear all four collections, then close. Mirror `MeshGattClient.disconnectAll`'s shape.

**Verify.** A test asserting `connectionRegistry.openLinkCount() == 0` after `server.stop()` with a
registered link. On hardware: toggle Bluetooth off/on three times and confirm the `send` diagnostics
line's target count doesn't climb.

#### CR-5 — `handledGatts` is never cleaned on the eviction or hard-deadline paths (BluetoothGatt leak) — ✅ FIXED 2026-08-09

**Evidence.** `MeshGattClient.handledGatts` (`:103`) is only removed in `onConnectionStateChange`'s
DISCONNECTED branch (`:335`). But both `disconnectHeld` (`:237-248`) and the hard-deadline watchdog
(`:189-206`) call `gatt.disconnect()` **immediately followed by `gatt.close()`** — closing generally
suppresses the subsequent state-change callback, so the entry is never removed. The watchdog cleans
`writeQueue`, `negotiatedMtu`, `activeTrackerKey`, `connectionRegistry`, `heldConnections`,
`heldRssi` and `syncedThisSession` — and misses this one, which reads as an oversight rather than a
decision.

**Why it matters.** Each retained `BluetoothGatt` holds a `Context` and a `BluetoothDevice`. The set
grows once per diversity eviction and once per hung connection, for the life of the process — i.e.
fastest exactly in the dense-crowd scenario the round is meant to stress. LeakCanary will not catch
it (a live, reachable set is not a leak by its definition). It is also correctness-adjacent: if a
`BluetoothGatt` object were ever reused by the stack, the stale entry would make
`onServicesDiscovered` no-op and the link would never register.

**Fix.** Add `handledGatts.remove(gatt)` to both cleanup paths. Two lines.

**Verify.** No clean unit test for this (needs a real `BluetoothGatt`); assert by inspection plus a
debug-only size log alongside the existing `conn` diagnostics during the round.

#### CR-6 — `HopTracker` state grows without bound and survives group deletion — ✅ FIXED 2026-08-09

**Evidence.** `MeshProtocol.kt:345-356` — `table`, `lastUpdated`, `lastSource` and the `_snapshot`
`MutableStateFlow` are keyed on `Key(groupId, target)` where `target` is `"PRESENCE"` **or a per-
message SOS UUID**. Nothing anywhere removes an entry. `myHop`/`bestActiveSos` only *filter* by
staleness at read time (`:384`, `:470`); the underlying entries persist forever.
`_snapshot.update { it + (key to candidate) }` (`:428`) allocates a strictly larger map on every
accepted report.

**Why it matters.** Two hot readers walk this structure: `BeaconRadio.bestSosHopFor` (`:308-311`)
filters the whole snapshot on **every advertise-check tick** (2s in ACTIVE tier), and
`HopTracker.bestActiveSos` scans `table.entries` on every Tier B advertise evaluation. Growth is
per-alert-SOS-id and unbounded within a session. This is precisely the shape of bug §6.4's
sustained-session gate exists to catch, and a multi-hour round is where it first bites.

It also has no group-deletion hook, unlike `PositionTracker.clearForGroup`/`pruneOrphaned` and
`BroadcastSosPreview.clearForGroup`/`pruneOrphaned`, which decision 30 added for exactly this reason
— `HopTracker` was missed in that pass. So a dismantled group's hop entries persist too.

**Fix.** Two parts, both mirroring what `PositionTracker` already does:
1. Prune entries whose `lastUpdated` is older than `effectiveStaleMs` inside `MeshService`'s existing
   30-minute `startPruning` sweep — and remove them from `_snapshot` as well as the three maps.
2. Add `clearForGroup(groupId)` / `pruneOrphaned(activeGroupIds)` and call them from the same place
   `positionTracker.pruneOrphaned` / `broadcastSosPreview.pruneOrphaned` are already called
   (`MeshService.kt:289-291`), plus the manual-delete path in `GroupChatScreen`.

**Verify.** `HopTrackerTest` already injects `now` — add a test that inserts N sos keys, advances the
clock past staleness, prunes, and asserts the snapshot shrank. Assert `pruneOrphaned` drops a
deleted group's entries.

#### CR-7 — `TrickleTimer` has an unsynchronised set mutated from the BLE binder thread — ✅ FIXED 2026-08-09

**Evidence.** `TrickleTimer.sightingSourcesThisWindow` is a plain `mutableSetOf<Any>()`, and none of
`onSighting` / `shouldTransmit` / `reset` is synchronised. `onSighting` is called from
`BeaconRadio.handleResult` (`BeaconRadio.kt:969`) which runs on the **raw BLE scan-callback binder
thread**. `shouldTransmit()` reads `.size` and calls `.clear()` from the **advertise coroutine**
(`BeaconRadio.kt:277`). `reset()` is called from that same coroutine (`:273`).

**Why it matters.** Concurrent `add` during `clear`/`size` on a `LinkedHashSet` is undefined: at best
a wrong redundancy count, at worst a `ConcurrentModificationException` that kills the advertise
coroutine (which has no handler — see CR-9's note on `serviceScope`) and therefore **stops all
advertising for the rest of the session**. Every other genuinely cross-thread field in `BeaconRadio`
is carefully `@Volatile` or a `ConcurrentHashMap`, with a class comment explaining the convention —
this one instance was missed, and it sits directly on the highest-frequency thread in the app.

Trickle governs whether Tier B transmits at all. A round trying to characterise Tier B behaviour
against a racy suppression counter produces unexplainable data.

**Fix.** Mark all three methods `@Synchronized` (the class is small and the critical sections are
trivial), or make the set a `ConcurrentHashMap.newKeySet()`. Prefer `@Synchronized` — `shouldTransmit`
also mutates `intervalMs`/`windowStart`/`suppressed`, which have the same exposure.

**Verify.** `TrickleTimerTest` is pure and clock-injectable; add a concurrency smoke test hammering
`onSighting` from several threads while `shouldTransmit` rolls windows.

#### CR-8 — Diversity-based link eviction (P3 / §9.2 item 2) is switched off in practice — ✅ FIXED 2026-08-09

**Evidence.** `MeshGattClient.considerEvicting` (`:219`):

```kotlin
val heldRssiValues = heldKeys.map { (heldRssi[it] ?: return).toDouble() }
```

`heldRssi` is written in exactly **one** place: `maybeConnect`'s "already holding this peer"
early-return branch (`:144-147`). It is never written when a connection is first established. So
until every currently-held peer has been heard beaconing at least once *after* it became held, that
non-local `return` aborts and **nothing is ever evicted**. If a held peer stops advertising, or its
address rotation resolves to a `trackerKey` with no recorded RSSI, eviction stays off indefinitely.

**Why it matters.** This is the mechanism §9.2 item 2 calls out as dominating reachability
("first-heard is close to worst-case… select for diversity"), and P3's persistent links removed the
only other way a held set could ever change — the class doc at `:210-215` says exactly that. So the
current behaviour is precisely the failure mode P3 was supposed to eliminate, while `LinkSelector`
and `LinkSelectorTest` both look healthy in isolation. A round intended to validate P3 link selection
would measure nothing.

**Fix.** One line: record `heldRssi[trackerKey] = rssi` at `attemptStarted` in `maybeConnect`
(`:156-157`), so a link has a diversity value from the moment it is attempted. Optionally also log
an `conn` diagnostics line when `considerEvicting` bails for a missing RSSI, so this class of silent
disablement is visible next time.

**Verify.** On hardware with 4+ phones and `maxConcurrentClientConnections = 3`, the `conn`
diagnostics should show at least one "diversity evict" line over a session with real movement. If it
shows zero, the mechanism is still off.

#### CR-9 — `senderIdFor`'s `requireNotNull` sits on hot loop paths with no exception handler — ✅ FIXED 2026-08-09

**Evidence.** `GroupRepository.senderIdFor` (`:193-198`) uses `requireNotNull`, deliberately: *"a
caller bug worth surfacing loudly rather than silently falling back to something wrong"* (decision
54). It is called from `BeaconRadio.refreshBroadcastTierPositionIfDue` (`:508`),
`BeaconRadio.relayedPositionFrameForBroadcastTier` (`:545`), `BeaconRadio.ingestBroadcastTierPosition`
(`:1011`), and `RelayResponder.presenceAndPositionFrames` / `positionFramesToPush` / `selectPositionsToRelay`
(`:414`, `:483`, `:500`) — i.e. the advertise loop and the per-connection push path.
`MeshService.serviceScope` is `CoroutineScope(SupervisorJob() + Dispatchers.IO)` (`MeshService.kt:57`)
with **no `CoroutineExceptionHandler`**, so an uncaught exception in a `launch` child reaches the
thread's default handler — process crash.

**Reachable case.** A group row exists in Room while the `EncryptedSharedPreferences` signing keypair
does not: Android Keystore reset, restore-to-a-new-device, an OEM backup/migration, or the
`GroupKeyStore` file failing to decrypt. Note this is the *mirror* of the case `sweepOrphanKeys`
already handles (keys without rows); nothing handles rows without keys. Pre-decision-54 this path
fell back to `deviceId` and merely degraded.

**Why it matters.** "Surfacing loudly" in practice means either killing the advertise coroutine
permanently or crashing the app mid-round. Both lose the session, and the crash is far from the
cause.

**Fix.** Either (a) have `senderIdFor` return `String?` and let the two frame-building call sites skip
that group's frames for this cycle, or (b) keep `require` but add a `CoroutineExceptionHandler` to
`serviceScope` that logs to `DiagnosticsLog` and, for the advertise/scan jobs specifically, restarts
them. (a) is smaller and more honest about what the caller can actually do; (b) is worth doing anyway
as general hardening — an unhandled throw anywhere in `serviceScope` currently takes the app down.

**Verify.** Unit-test `senderIdFor` on a group id with no keypair. Add the handler and assert a
`DiagnosticsLog` `error` event rather than a crash.

#### CR-10 — `CatalogFilter.sizeBits` is the one wire field with no bound check; `sizeBits = 0` throws — ✅ FIXED 2026-08-09

**Evidence.** `MeshFrameCodec.decode`'s `FRAME_CATALOG_FILTER` branch (`:1104-1109`) reads
`sizeBits` as an unsigned short and validates nothing. Every neighbouring branch bounds its fields
carefully (`totalChunks !in 1..MAX_EVIDENCE_CHUNKS` at `:1044`, `thumbnail.size > MAX_THUMBNAIL_BYTES`
at `:1052`, `contentLength < 0` at `:1054`). `CatalogFilter.hashIndexes` then computes
`Math.floorMod(h1 + i * h2, sizeBits)` — **`sizeBits == 0` throws `ArithmeticException`** (divide by
zero).

**Why it matters.** The throw is caught by `RelayResponder.handleIncoming`'s top-level catch
(`:1412`), so no crash — but the frame is abandoned before `handleCatalogFilter` pushes anything, so
one malformed frame per connection silently suppresses that connection's entire catalog sync. Cheap
denial of delivery from an unauthenticated peer, against the mechanism all SOS/evidence/nickname
delivery is now exclusively reactive to. Values above `MAX_SIZE_BITS` (4096) are also accepted and
just waste work.

**Fix.** `if (sizeBits !in 1..CatalogFilter.MAX_SIZE_BITS) return null` in the decode branch
(promote `MAX_SIZE_BITS` from `private`). Matches the file's own established posture exactly.

**Verify.** `MeshFrameCodecTest` — add a hand-constructed frame with `sizeBits = 0` and one with
`sizeBits = 65535`, assert both decode to null. This is the same shape as the existing hostile-
`totalChunks` test at `MeshFrameCodecTest.kt:948`.

#### CR-11 — `subscribedDevices` is keyed by `BluetoothDevice`, against this file's own stated rule — ✅ FIXED 2026-08-09

**Evidence.** `MeshGattServer.kt:68` — `subscribedDevices = ConcurrentHashMap.newKeySet<BluetoothDevice>()`.
Lines 79-81 of the same file state the opposite rule for everything else: *"Keyed by address, like
every other per-peer structure in this class and in MeshGattClient — **NOT by the BluetoothDevice
object itself, which has no guaranteed stable equals() across separate callback deliveries from the
stack** (see docs/DECISIONS.md, decision 4)."*

**Why it matters.** If decision 4's premise holds, the `if (!subscribedDevices.add(device)) return`
guard at `:209` — whose entire job is preventing duplicate `periodicRefresh` loops on a re-fired CCCD
write, the server-side twin of `MeshGattClient.handledGatts` — fails exactly when needed. Duplicate
refresh loops on a persistent link double presence/position airtime for that link's whole lifetime,
which is both a battery cost and a confound for any airtime measurement during the round.

Either the premise or the code is wrong; both cannot stand.

**Fix.** Key `subscribedDevices` by `device.address` like every other collection in the class, and
keep the `BluetoothDevice` only where the API needs it (passed through to `notify`). If instead
decision 4's premise is believed to be wrong, correct the comment and say why — but don't leave the
file contradicting itself.

**Verify.** Inspection plus the round: two `periodicRefresh` loops on one link would show as
presence/position frames arriving at half the configured `presenceRefreshIntervalMs`.

#### CR-12 — Hop-scaled staleness windows reach ~93 minutes, and `hop` is unauthenticated — ✅ FIXED 2026-08-09

**Applied to all three shape-alike functions**, not just the two named below: `PositionTracker
.effectiveMaxAgeSeconds`, `RelayResponder.presenceWithinSkew`, AND `HopTracker.effectiveStaleMs`
(found during the fix pass — identical shape, identical unauthenticated-`hop` exposure, missed in
the original finding). Each got its own `MAX_SLACK_HOPS = 6` constant (kept in sync by doc only,
matching this codebase's existing `PER_HOP_SLACK_MS`/`PER_HOP_SLACK_SECONDS` cross-file convention),
clamping `hop` before the slack multiplication. Tests added/corrected in
`PositionTrackerTest`/`RelayResponderPresenceSkewTest`/`HopTrackerTest` — the pre-existing
`PositionTrackerTest` assertion at hop 120 (`5580L`) was itself testing the unbounded-scaling bug as
correct behavior and has been corrected to `450L`. See the RESUME HERE block's own entry for this
pass. **Not compile-verified** — no JDK in this session's environment; see that same entry.

**Evidence.** `PositionTracker.effectiveMaxAgeSeconds = baseMaxAgeSeconds + hop * PER_HOP_SLACK_SECONDS`
(`PositionTracker.kt:162`, base 180s, slack 45s/hop). `RelayResponder.presenceWithinSkew` has the
same shape (`RelayResponder.kt:1466-1470`, base 120s, slack 45s/hop). Both are bounded by
`maxPositionRelayHops = 120` (`RelayResponder.kt:82`, mirrored at `BeaconRadio.kt:1089`), raised from
4 to 120 by decision 33 for an unrelated reason (multi-kilometre relay reach).

At hop 120: position staleness budget = `180 + 120*45` = **5580 s ≈ 93 minutes**; presence replay
window = `120 + 120*45` s ≈ **90 minutes**.

`hop` lives in the **cleartext envelope by design** — `Frame.PositionSealed.hop` and
`Frame.Presence.hop` both document this explicitly, because a blind relay must be able to increment
it without the group key. It is therefore covered by no MAC and no signature.

**Why it matters — two separate problems.**
1. *Product.* A position up to 93 minutes old is admitted by `PositionTracker.forGroup` and rendered
   as a (faded) dot on the radar that someone may walk toward. `RadarView`'s fade goes to
   `STALE_FADE_MIN_ALPHA = 0.2` and no further — it cannot communicate "this may be an hour and a
   half old" versus "three minutes old". For a crowd-navigation tool during a stampede this is the
   most dangerous class of wrong output the app can produce.
2. *Security.* Anyone can capture one presence or position frame and replay it with `hop` rewritten
   to 119, obtaining a ~90-minute acceptance window instead of 2 minutes. The replay protection
   `presenceWithinSkew` was added to provide (its own doc: *"anyone who ever captured one valid
   presence frame could replay it indefinitely"*) is substantially defeated by a field the same
   design deliberately leaves unauthenticated.

The per-hop reasoning itself is sound — each hop genuinely costs one reconnect cycle. The error is
letting it scale to the *ceiling* of a field that grew 30× for an unrelated reason.

**Fix.** Clamp the hop term in both functions, e.g. `hop.coerceAtMost(MAX_SLACK_HOPS)` with
`MAX_SLACK_HOPS = 6` — which covers any realistic topology for this app's stated 3-8 person group in
a crowd (§5.5) and caps both windows at ~7 minutes. Put the constant in one place both files
reference, and note in the doc that it is deliberately decoupled from `maxPositionRelayHops`
(propagation depth) because the two answer different questions. Consider additionally showing an
explicit age label on a dot past some threshold rather than relying on alpha alone.

**Verify.** `PositionTrackerTest`/`RelayResponderPresenceSkewTest` both already test these functions
directly — add cases at hop 6, 7 and 120 asserting the window stops growing.

#### CR-13 — §9.2 item 1's `ScanFilter` was only applied to Tier B; the legacy scan is still unfiltered — ❌ REVERTED 2026-08-09 (broke discovery outright on first live round — see decision 58)

**This was the one Gate A item explicitly flagged as "not hardware-confirmed... needs its own
dedicated live round" — it failed the first round it got.** 3 phones, `openLinkCount=0` for the
entire session on every phone, no radar, every SOS `BLOCKED: no open links`. Full diagnosis and root
cause in `docs/DECISIONS.md` decision 58; summary here.

**Root cause — not the chipset flakiness this entry originally worried about, a deterministic
mismatch on every device.** `ensureAdvertising` builds this app's beacon with `addServiceData(uuid,
payload)`, deliberately — `addServiceUuid()` alongside it would overflow legacy BLE's 31-byte
advertising limit. `ScanFilter.setServiceUuid()` matches a completely different AD structure (the
Service UUIDs List) than Service Data — it cannot match an advertisement that never carries a
Service UUIDs List at all, on any chipset. This entry's own "service-UUID filtering is reliable, not
the chipset-flaky kind decision 3 removed" claim is still true in the abstract; it just doesn't
apply to an advertisement that was never given the AD structure that filter type looks for. Same
bug, same root cause, found in a second place while fixing the first: Tier B's own `ScanFilter`
(decision 34) has had this identical, never-actually-matching filter since it was written — never
caught because Tier B carries its own "NOT device-tested" caveat.

**Fix — both `ScanFilter`s removed.** `restartScan` (legacy) reverted to its exact pre-CR-13 shape:
unfiltered, matching in `scanCallback.onScanResult`, the precise code live-tested working across all
four prior hardware rounds. `restartBroadcastTierScan` (Tier B) also unfiltered, matching in
`handleResult` (already correctly discarded non-matching devices in software) — deliberately not
"fixed" by adding a real Service UUIDs List AD to Tier B's advertisement instead (extended
advertising's budget could technically afford it), since that would stack a second never-verified
radio change on an already-never-hardware-confirmed channel in the same pass that just shipped one
unverified radio change as a live regression.

**What this costs.** §9.2 item 1's crowd-scale battery win (filtering BLE noise in the radio chip
instead of app code) is genuinely undone, not deferred for free — both scans are back to
software-only matching. Proven correct at the 2-8 phone scale this app's own operating envelope
(§9.1) actually targets; not battery-optimized at hundreds-of-nearby-devices crowd density. Needs a
different, actually-checkable-in-advance approach before being re-attempted — verify the beacon's
own AD structure against the exact filter type being proposed BEFORE it reaches a live round, not
after.

**Re-verify.** Same as originally planned: with the fix, 3-phone discovery must work at all (the bar
just dropped from "as fast as before" to "at all," given what just happened) — needs the same round
to re-run against `20.07-v0.7.23-dev-debug.apk`.

---

### Gate B — small, low-risk, fix in the same pass

#### CR-14 — `handleCatalogFilter` consumes push budget for items it then skips — ✅ FIXED 2026-08-09

`RelayResponder.kt:1212-1221` draws `allowedToPush = consumeCatalogItemBudget(peerAddress, wantToPush)`
**before** pushing, but `pushCouriersWithHandover` (`:1281-1301`) skips items for two independent
reasons that don't consume from `pushed`: `CourierHandover.split` returning null (too few copies) and
`courierHandoverTracker.canAttempt` rate-limiting. Those skips still spent budget. Since a peer can
send multiple `CatalogFilter` frames per connection, the 200-item session budget can be drained
without delivering anything. Also `budgetSkipped = wantToPush - pushed` conflates handover-skips with
budget-skips in the `sync` diagnostics line, which will make this hard to spot in a log pull during
the round. **Fix:** draw budget per item as it is actually pushed, or return the unused amount; and
report handover-skips as their own counter.

#### CR-15 — `RadarCanvas` writes Compose state on every animation frame — ⚠️ CORRECTED, PARTIALLY FIXED 2026-08-09

**Correction (2026-08-09, found during the fix pass):** the claim below that this "forces a
recomposition... at display refresh rate" is wrong. `elapsedMs` is read only inside `Canvas{}`'s own
draw lambda (`Canvas` = `Spacer(Modifier.drawBehind(...))`), and a State read inside `drawBehind`'s
lambda is attributed to the DRAW phase's own snapshot-observation scope, not the composition scope
that created the call — mutating it invalidates redraw only, the same mechanism the sweep
animation's own `animateFloat` already relies on a few lines below. The sweep already redraws the
canvas continuously regardless of dot-blink state, so this read added no incremental per-frame cost
either. **What was actually fixed:** `mutableStateOf(0L)` → `mutableLongStateOf(0L)` — a genuine,
small, real fix (avoids boxing a `Long` on every animation frame) on its own merits, independent of
the overstated recomposition claim. The more invasive rewrite this entry originally proposed (drive
blink phase from `rememberInfiniteTransition` instead) was NOT done — it would solve a problem that
doesn't exist, with real regression risk to a working animation for zero benefit. See the RESUME
HERE block's own entry for this pass.

`RadarView.kt:210-216` — `var elapsedMs by remember { mutableStateOf(0L) }` updated inside
`withInfiniteAnimationFrameMillis` in an unconditional `while (true)`. This forces a recomposition
plus a full canvas redraw at display refresh rate for as long as any radar is visible, and the radar
is on Home, Group chat **and** Navigate. It also boxes a `Long` per frame (`mutableStateOf`, not
`mutableLongStateOf`). Given "eats less energy" is a stated product pillar and the round will be
measuring battery, this is worth removing first so it doesn't confound the numbers. **Fix:** drive
the dot blink phase from the `rememberInfiniteTransition` already running for the sweep, so there is
no second per-frame state write; or at minimum switch to `mutableLongStateOf`.

#### CR-16 — `RelayEngine.symbolsToSend` rebuilds the whole encoder on every request — ✅ FIXED 2026-08-09

`RelayEngine.kt:512-525` — for a complete item, every `SymbolRequest` re-reads all persisted symbol
rows from Room, allocates `ByteArray(k * CHUNK_SIZE)`, rebuilds a `FountainEncoder`, then generates
up to `maxSymbolsPerSession` (150) dense repair symbols, each XOR-ing ~k/2 × 400 bytes. `liveDecoders`
and `symbolCursors` are both cached with a defined lifecycle; the encoder is not, despite having the
same one. **Fix:** cache the encoder in a `ConcurrentHashMap` alongside `symbolCursors`, evicted in
the same places (`maybeCompleteFromSymbol`, `pruneExpired`).

#### CR-17 — `MeshService`'s "Offline" state does not survive a service restart — ✅ FIXED 2026-08-09

`setMeshActive(false)` (`MeshService.kt:121-136`) calls `stopForeground(STOP_FOREGROUND_REMOVE)`
while `onStartCommand` returns `START_STICKY` (`:298`). Backgrounded with no foreground notification,
the service is an ordinary killable service; on restart Android re-runs `onCreate`, which
unconditionally calls `beaconRadio.startAdvertising()` / `startScanning()` (`:263-264`). **The user's
explicit "stop transmitting" silently turns itself back on.** For this app's threat model that is a
meaningful failure, not a cosmetic one. **Fix:** persist `_meshActive` to the existing `mesh_device`
prefs and gate `onCreate`'s radio start on it. Relevant to the round because the round will toggle
Offline.

#### CR-18 — `sweepOrphanKeys` leaves orphaned signing keypairs behind — ✅ FIXED 2026-08-09

`GroupRepository.sweepOrphanKeys` (`:250-255`) removes group keys with no matching Room row but never
calls `keyStore.removeSigningKeyPair`, even though `dismantleGroup` removes both. After a destructive
schema migration the Ed25519 keypairs persist indefinitely. **Fix:** one extra call in the loop.

#### CR-19 — `presencePassesSenderIdentityChecks` logs the same rejection twice — ✅ FIXED 2026-08-09

`RelayResponder.kt:1096-1103` fires two different `DiagnosticsLog.event("reject", …)` lines for a
single signature failure. Double-counts in any log analysis, and the round's whole purpose is log
analysis. **Fix:** delete one.

#### CR-20 — dead imports left by the Wi-Fi Direct removal — ✅ FIXED 2026-08-09

`HomeScreen.kt` retains eight imports with no remaining usage after decision 49 deleted
`WifiDirectRow`/`handleWifiDirectToggle`: `Manifest`, `Uri`, `PackageManager`, `Build`,
`rememberLauncherForActivityResult`, `ActivityResultContracts`, `ContextCompat`, and
`Icons.Filled.Speed`. **Fix:** delete. Consider enabling detekt's `UnusedImports` so this class of
leftover is caught at the gate rather than by inspection.

---

### Tracked — after the round

#### CR-21 — `DedupCache` has zero production call sites — ⏸️ DELIBERATELY LEFT UNCHANGED 2026-08-09

**Checked, not fixed, on purpose.** During the 2026-08-09 second pass ("whatever can be done, do it"
for CR-21..30) this was the one item where "doing it" would mean reversing a considered decision
without the evidence that decision was itself waiting on. Decision 18 (`docs/DECISIONS.md`) left
`DedupCache` deliberately unwired for a stated reason: `seenDao` (Room-backed) is already
authoritative, wiring a second in-memory layer on top would be "genuinely redundant for THIS gate,
not wrong, just unneeded complexity," and the trigger condition for wiring it in was explicit —
*"if DB-query latency on the flood path ever proves to matter on real hardware."* That evidence
doesn't exist yet (hardware testing is this pass's own excluded scope), so wiring it now would be
guessing at exactly the thing decision 18 said to wait for. Left as-is: real, tested, ready to drop
in the moment the round's own data calls for it — see decision 18's full reasoning before touching
this.

#### CR-22 — `FountainDecoder`'s cost is not consistent with `MAX_EVIDENCE_CHUNKS = 4096` — ✅ FIXED 2026-08-09

`MAX_EVIDENCE_CHUNKS` lowered 4096 → 1024 (`MeshFrameCodec.kt`). Worst-case decode cost drops from
~6.7 GB to ~419 MB of XOR work — over 16× smaller — while staying ~5× above `EvidenceCapture`'s
realistic 640px/quality-45 JPEGs (~200 chunks; 1024 chunks ≈ 400 KB). Chose lowering the ceiling over
making `decoderMutex` per-evidence-id (the other fix candidate) — smaller diff, no new lock
granularity to reason about, and the ceiling itself was never justified against decode time in the
first place, only memory. `FountainCodeTest`'s own k=1000 smoke test comment updated to match. Not
compile-verified against hardware timing (no device to measure real XOR throughput on), but the
arithmetic itself is a straight multiplication, not something a device can disagree with.

#### CR-23 — `relayableSos`/`relayableEvidenceMeta` filter in Kotlin, not SQL — ✅ FIXED 2026-08-09 (corrected finding)

**Correction:** the original finding assumed the DAO returned unfiltered rows. It doesn't —
`SosDao.getRelayable()`/`EvidenceDao.getRelayable()` (`Daos.kt:79,192`) already carry
`WHERE ttl > 0` in their own `@Query`. `RelayEngine.kt`'s `.filter { it.ttl > 0 }` was a harmless
but pointless *re*-filter on an already-filtered result, not a missing SQL optimization. Removed the
redundant Kotlin-side filter; the DAO's own SQL is now the sole authority.

#### CR-24 — `ForwardingPolicy`'s degree thresholds may never be reached — 🔶 PARTIALLY DONE (logging shipped 2026-08-09; threshold re-derivation still needs the round)

The measurement groundwork is done: `MeshService.startDegreeLogging()` logs
`connectionRegistry.openLinkCount()` to `DiagnosticsLog` every 60s for the whole session, tagged
`degree` — ready for the round without any further action. **What genuinely still needs the round's
own data, and was correctly left alone:** whether `JITTER_WIDE_FLOOR = 5`/`HIGH_DEGREE_TTL_CLAMP_FLOOR
= 6` (`ForwardingPolicy.kt:38-39`) are ever actually reached given `maxConcurrentClientConnections = 3`
+ an unenforced server cap of 4 (`MeshGattServer.kt:78`). Re-deriving those thresholds now, with no
measured degree to derive them from, would be exactly the "guess at now" this entry originally warned
against — the logging exists so the NEXT session can answer this from real numbers instead. CR-4's
fix (this same pass) already removed the corruption that would have made this signal meaningless.

#### CR-25 — `JoinCode` expiry is a 4-byte int (Y2038) — ✅ FIXED 2026-08-09

`expiresAtEpochSec` widened from a 4-byte `Int` to an 8-byte `Long` (`putInt`/`buf.int` →
`putLong`/`buf.long`), `JoinCode.VERSION` bumped 2 → 3 (deliberately not wire-compatible with v2,
same "acceptable given zero deployed users" precedent v2 itself already set when it broke v1
compatibility). Existing `JoinCodeTest` round-trip/rejection tests pass unchanged — none hardcoded
the old 4-byte layout in a way sensitive to the width change.

#### CR-26 — Stale docs that contradict the code next to them — ✅ FIXED 2026-08-09

All three corrected:
- `RelayResponder.checkSenderKeyPin`'s KDoc rewritten to name the real
  `FIRST_SIGHT`/`UNCHANGED`/`CHANGED` enum and describe the actual *re-pin and continue* behaviour,
  instead of a two-value `OK`/`MISMATCH` result that hasn't existed since the live-testing fix this
  same doc block sits 20 lines below.
- `BulkChannel.kt:26`'s dangling KDoc `[...]` link to the deleted `WifiDirectAccelerator` class
  changed to plain backticks, matching every other reference to a retired class in that same file.
- `RelayResponder.kt`'s "WFD cap" mislabel corrected to "L2CAP cap" (the field it actually describes
  since decision 49).

#### CR-27 — Tombstone-comment volume — ✅ TRIMMED 2026-08-09 (partial, by design)

Removed standalone "X lived here through vN — deleted by decision M" tombstone blocks with no other
content: `HomeScreen.kt` (`WifiDirectRow`), `RelayEngine.kt` (`symbolsByEsi`, plus a parenthetical in
`evidenceMeta`'s doc), `RelayResponder.kt` (two blocks — the WFD accelerator subsystem note and the
`handleWifiDirectCap`/etc. note), and trimmed WFD-specific footnotes out of two otherwise-still-valid
`MeshService.kt` comments (`l2capTransport`'s field-placement reasoning, `onDestroy`'s bulk-channel
close reasoning) and one `GroupRepository.kt` comment (the lazy-`keyStore` reasoning). **Deliberately
NOT touched:** `MeshFrameCodec.kt`'s frame-byte retirement notes and `VERSION` changelog (load-bearing
— "never reuse this wire byte" genuinely needs to live at the point someone might reuse it);
`L2capBulkTransport.kt`'s and `BulkChannel.kt`'s comparative-design-reasoning mentions of the retired
`WifiDirectAccelerator` (these explain *why* the current class is shaped the way it is, not just that
something used to exist — removing them would delete real reasoning, not just noise); the manifest's
own WFD-permission-removal comment (explains an absence, preventing someone from re-adding permissions
unnecessarily); `RelayEngine.kt`/`RelayResponder.kt`'s `PeerDeliveryTracker` mentions (comparative
design reasoning, same category). The distinction applied throughout: delete a tombstone that is
*only* a tombstone; keep a comment that happens to mention a deleted class while doing real
explanatory work.

#### CR-29 — Reassembled evidence is stored as plaintext at rest — ✅ RESOLVED 2026-08-09, not a bug

**Decision (2026-08-09, user): working as intended, closed.** The threat model this app actually
optimizes for is loss of a single device, not confidentiality of evidence at rest — redundancy
across every phone that has relayed or received a copy is the anti-single-point-of-failure design
(the same reason content floods to every relay, not just the origin), and encrypting the
reassembled file would work against that goal for no compensating benefit: the content itself isn't
illegal to hold, so there's nothing to hide from a legal standpoint, only something to keep from
being lost if one phone is seized or destroyed. `PositionTracker`'s "nothing durable to find"
property was never meant to extend to evidence — that class's own doc already scopes the claim to
positions specifically. No code change; this closes the original finding rather than acting on
either option it proposed.

#### CR-30 — On doc-versus-code drift generally — ↔️ APPLIED, NOT SEPARATELY ACTIONABLE

CR-1, CR-2, CR-8 and CR-11 share a shape: **prose next to the code asserts a property the code does
not have, and the prose is why nobody looked.** In three of those four the drift was introduced by the
very decision the comment cites. This codebase's comment density is deliberate and it genuinely
speeds up review — but comments asserting *invariants* ("dismantle deletes everything for this
group"; "the watchdog cleans up everything"; "every per-peer structure is keyed by address") have
become a second artifact that can drift silently. Proposal, not urgent: for each such claim, prefer a
test that fails when it stops being true over a paragraph that says it is. `§6.2`'s invariants-as-
assertions framing already argues exactly this for the simulator; the same argument applies to the
production code's own stated invariants.

**Note (2026-08-09):** this is a meta-observation, not a standalone fix — nothing to check off on its
own. It was already acted on twice this same session: CR-26 corrected exactly this class of drift
where found, and this lesson is the reason CR-23's "fix" turned into a correction of the original
finding instead (re-verified against source rather than assumed) once the DAO's own `@Query` turned
out to already do the filtering the finding claimed was missing.

---

### Resume checklist

Tick these in order; each is independently committable.

- [x] **CR-1** `MAC_LEN` → `CryptoUtils.MAC_TAG_LEN` (single source of truth), tests that asserted the
      bug corrected — compile-verified 2026-08-09. No `VERSION` bump: Tier B's beacon has no
      version byte at all (checked before assuming decision 47/48's precedent applied here).
- [x] **CR-2** `dismantleGroup` deletes courier envelopes — compile-verified 2026-08-09.
- [x] **CR-3** MTU-aware padding + padded-size budget check — compile-verified 2026-08-09.
      *Low-MTU test setup still not run on real hardware* — unit tests cover the arithmetic, not a
      real constrained radio.
- [x] **CR-4** `MeshGattServer.stop()` symmetric teardown — compile-verified 2026-08-09.
- [x] **CR-5** `handledGatts.remove(gatt)` on both cleanup paths — compile-verified 2026-08-09.
- [x] **CR-6** `HopTracker` pruning + `clearForGroup`/`pruneOrphaned` — compile-verified 2026-08-09.
- [x] **CR-7** `TrickleTimer` synchronisation — compile-verified 2026-08-09.
- [x] **CR-8** seed `heldRssi` at `attemptStarted` (plus a follow-up leak in the connect-timeout
      watchdog, found while fixing this one) — compile-verified 2026-08-09.
- [x] **CR-9** `CoroutineExceptionHandler` on `serviceScope` — compile-verified 2026-08-09.
      Chose option (b) from the original entry (general hardening) over (a) (nullable `senderIdFor`),
      since (a) would have rippled through 13 call sites for a narrower fix.
- [x] **CR-10** bound `sizeBits` in `decode` — compile-verified 2026-08-09.
- [x] **CR-11** key `subscribedDevices` by address — compile-verified 2026-08-09.
- [x] **CR-12** clamp hop-derived staleness/skew slack — compile-verified 2026-08-09. Applied to
      all THREE shape-alike functions (added `HopTracker.effectiveStaleMs`, missed in the original
      finding), not just the two named originally.
- [x] ~~**CR-13** legacy-scan `ScanFilter` + degree-gated report delay~~ — **FAILED its live round,
      REVERTED, root cause fixed** (decision 58, 2026-08-09). Broke discovery totally on all 3 test
      phones (`openLinkCount=0` the whole session, every SOS blocked). Root cause: our beacon
      advertises via `addServiceData`, never `addServiceUuid` — a service-UUID `ScanFilter`
      structurally cannot match that, on any device, not a chipset-flakiness issue. Both the legacy
      AND Tier B scan filters removed (Tier B had the identical bug since decision 34, never caught
      since it was untested). v0.7.23-dev, versionCode 34 — this is the build to re-test.
- [x] Gate B: **CR-14** … **CR-20** — all 7 applied 2026-08-09, compile-verified 2026-08-09. CR-15 was
      corrected (overstated finding, only the trivial half was real) before being applied — see its
      own Part 10 entry.
- [x] `./gradlew testDebugUnitTest detekt assembleDebug assembleRelease lintVitalRelease` — all
      green 2026-08-09 (525 tests, 0 failures, detekt clean after one real `TooManyFunctions` fix, no
      `missing_rules.txt`).
- [x] Version bump (v0.7.21-dev, versionCode 32) + fresh debug APK `aapt`-confirmed + committed
      (decision 56, `docs/DECISIONS.md`) — 2026-08-09
- [x] `openLinkCount` periodic logging for **CR-24**'s measurement — shipped as part of the
      CR-21..30 pass (decision 57), `MeshService.startDegreeLogging()`, ready before the round starts.
- [x] **CR-21..CR-30 dispositioned** (decision 57, 2026-08-09) — CR-22/23/25/26/27 fixed, CR-24
      partial (logging shipped, threshold re-derivation still needs round data), CR-21 deliberately
      left alone (gated on decision 18's own hardware-evidence condition), CR-28 cut from scope,
      CR-29 closed as intended, CR-30 meta-only.
- [x] Version bump (v0.7.22-dev, versionCode 33) + fresh debug APK `aapt`-confirmed + committed
      (decision 57, `docs/DECISIONS.md`) — 2026-08-09
- [ ] Run the device-test round, then P7
