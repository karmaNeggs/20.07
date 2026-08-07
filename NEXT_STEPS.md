# Working plan — reliable relay on a sideloaded debug APK

**Superseded by `PLAN-v2.md` for scaling work (2026-08-05):** where the two disagree, PLAN-v2.md is
now the source of truth (explicit user decision) — e.g. its P0a crowd simulator is IN scope despite
this file's own "Dropped" section below, which predates that decision. This file's narrower 3-phone-
reliability items below are still real and still tracked here; PLAN-v2.md's phases (P0a-P1-P3 shipped
as v0.6.0-dev through v0.6.3-dev, hardware-confirmed across four live rounds, pushed to origin; P2
Tier-1 sim started — see PLAN-v2.md's own "RESUME HERE" note at the top, the single most current
status pointer for this whole project, and `docs/DECISIONS.md` decisions 14-29) are tracked there.
**This file's own next step is stale as of this checkpoint** — the live items below (E1/E2) predate
this session's P1/P3 work and should be re-read against decisions 20-23 before acting on them, not
executed as-is.

**Scope, deliberately narrow:** get the debug/sideloaded APK to relay reliably with bugless
connections across **3 phones**. Not v1. Everything else is parked below.

Each step is **hardware-gated**: it ships as one APK, gets tested, and nothing else lands until it
passes. Batching unverified changes is what cost several wasted rounds — two separate "fixes" of
mine went to a live test and each broke something (a 60s advertise dwell starved discovery; folding
live GPS into the reconnect-skip epoch caused a reconnect storm).

## Steps

**Shipped and field-verified 2026-08-05:** blind position relay + hop-tracking frozen-value fix,
released as v0.4.0 (`releases/20.07-v0.4.0-debug.apk`, 247 tests green, detekt clean, both variants
build; see docs/DECISIONS.md decisions 8-13 for the full root-cause chain). Live 3-phone test
confirms the group row now correctly shows "2 hop(s) away" (previously frozen at "1" through every
prior build) and content relays through the middle phone. NOT separately confirmed by this test:
decision 8's adaptive round-robin dwell (advertise-restart-frequency reduction) — needs its own
logcat capture, not just observing hop count/relay working.
Earlier: A1, A2, B1, C1.

- [x] **A1. Stale sender-key pin must not block traffic.** A changed Ed25519 key currently hard-
      rejects every signed frame from that peer (presence, SOS, position) — a stale pin silently
      kills all GATT content while beacon-derived hop count keeps working, which matches the
      "1 hop away, but no messages and no dots" symptom exactly. Fix: a changed key in a presence
      heartbeat **re-pins and warns** instead of dropping. A signature that fails under a
      *current* pin still rejects (that's the real forgery check) — but a stale pin now self-heals
      within one connection instead of poisoning the peer pair forever.
- [x] **B1. On-device diagnostics log.** Debug builds only (same precedent as LeakCanary, already
      `debugImplementation`-only). Rotating file in app-private storage, exported via the existing
      FileProvider → share to Drive. Event types, counts, reject reasons, truncated peer ids only —
      **never positions or message bodies**, so the "nothing persisted to find on a seized phone"
      property still holds. Release builds keep stripping all logging.
- [x] **A2. Hung-connection slot leak (severe).** Once a connection is past `CONNECTED`, the
      stuck-attempt timeout no longer watches it; if the radio goes silent without ever firing
      `DISCONNECTED`, that peer's queue entries leak and it can never be reconnected to. Needs a
      second, connection-lifecycle-level timeout.
- [x] **C1. Stop restarting the radio to change the beacon.** `AdvertisingSet.setAdvertisingData()`
      (API 26 = our minSdk) updates the payload **in place**. This removes the ~3s multi-group
      restart churn outright rather than tuning its frequency, and makes the current adaptive-dwell
      workaround unnecessary. Root fix for the beacon-vs-GATT radio contention.
- [x] **D1. Stop keying peer state on the BLE MAC** (= PLAN-v2.md P0b, docs/DECISIONS.md decision
      15). New `PeerIdentityResolver` resolves an address to the sender's stable `senderId` once an
      authenticated frame reveals it; `HopTracker` route-ownership and `MeshGattClient`'s
      `ConnectionAttemptTracker` cooldowns both key on it once known, falling back to the raw
      address for a peer not yet identified this session. Compile/test-verified (270 tests green);
      NOT yet hardware-confirmed — needs a live session's exported `DiagnosticsLog` showing a
      stable `distinct=` count against a growing `addresses=` count.
- [x] **D2a. Positions now blind-relay through non-members** (decision 10) — the actual reason the
      far phone never appeared. Refresh *cadence* is still open: Positions only refresh on a GATT reconnect (~45s/hop against a
      ~90s useful life — structurally marginal at 2 hops). Options: carry position in the beacon, or
      a dedicated lightweight channel. Decide *after* C1, since C1 changes the beacon's budget.
- [ ] **E1. Hop count vs radar.** Re-examined 2026-08-05 (`docs/DECISIONS.md` decision 22) after a
      live report of two phones showing different hop counts to each other: confirmed as expected,
      not a bug — `HopTracker` is per-device/per-observer by design, not a shared value, so
      disagreement between two phones' own readings is normal. This item's original framing (a
      per-group minimum vs. per-person distance labeling question) is still open and still worth a
      UI pass, but is a labeling/clarity question now, not a correctness bug.
- [ ] **E2. Verify screen-off GPS** (background-location permission + `location` foreground-service
      type already added, unverified on hardware).

## Parked by decision — not v1 blockers, but real

- **Release signing key** — none exists; release APK is unsigned/uninstallable. Accepted for now.
- **R8 release build never device-tested** (~90% stripped, compile-verified only). To check later.
- **Scale** — target 3 phones working first, then widen. ~10-person target and crowd scale
  (the 100k-in-4km² question) remain completely unvalidated.

## Open decisions (real, deferred)

- **WiFi Direct accelerator** — experimental, compile-verified only; `WifiP2pManager.connect()` may
  raise a system dialog on the peer that visibly breaks the disguise. Remove, or keep off?
- **Long-range BT5 Coded PHY** — confirmed failing 100% on this hardware (circuit-broken now).
  Remove, or leave disabled?
- **Group ids are cleartext on the wire** — enables traffic analysis / participant enumeration.

## Dropped

- **Member eviction / kick.** Cut deliberately. Note that `docs/DECISIONS.md`'s ephemeral-groups
  entry already argued against a revocation protocol (dismantle-and-recreate is one tap and native
  to 2-3-day groups); per-sender identity made an authenticated version *possible*, but it can
  never actually revoke — an evicted member keeps the group key and can still read traffic — so the
  honest mechanism remains dismantle-and-recreate.
- ~~Crowd-scale simulation~~ — un-dropped 2026-08-05: built as PLAN-v2.md P0a (see above). CI,
  Tier-2 Compose UI tests remain dropped.

## Unknown that may matter

- Android version of the test phones — decides which `notifyCharacteristicChanged` path runs
  (API 33+ vs legacy) and how strictly the `location` foreground-service type is enforced.
