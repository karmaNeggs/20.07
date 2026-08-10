# 20.07 — v0.3.0 implementation plan

Audience: the engineer (or agent) implementing this. Written after a full read of the codebase at
commit `fa918fa`. Every finding below was verified against the source; the two marked **VERIFIED BY
TEST** were confirmed with throwaway unit tests that were run and then deleted.

---

## 0. The framing that drives this plan

Groups in 20.07 are **short-lived and ad hoc**: 2–3 days typical, 6 months absolute maximum, deleted
when the task is done. That is not a detail — it changes which problems are worth solving.

**What the ephemeral framing makes cheap or unnecessary:**

- **Key rotation and forward secrecy.** The group's own lifetime *is* the rotation window. A seized
  phone leaks at most one group-lifetime of traffic. Do not build key ratcheting, do not build Noise
  sessions. This was the single most expensive item on the original list and it is now descoped.
- **Member revocation.** The revocation mechanism is "dismantle the group and make a new one," which
  already exists, is one tap, and is native to how the product is used. Do not build a revocation
  protocol.
- **Room migrations.** With groups that die in days, a destructive schema change costs a user their
  current group, recoverable by rejoining with a code. Real migrations become worth writing only from
  v0.3.0 onward, not retroactively.
- **Wire backward compatibility.** Zero users, no long-lived groups. Break the format freely *now*,
  once, in this release.

**What the framing does NOT make cheaper:**

- The remote crash-loop DoS. A relay that can be bricked by a stranger is broken regardless of how
  long groups live.
- The SOS authentication gap. A three-day window is plenty long for a forged "meet me at X."
- Insider impersonation. At a protest, an infiltrator in a 3-day group is precisely the threat model.
  What the framing changes is that this now needs *only* pin-on-first-sight identity, with no PKI, no
  revocation, and no rotation behind it — a much smaller build than originally scoped.

**And it creates one new opportunity:** group expiry should be a first-class, enforced concept rather
than a social convention. That is Phase 3, and it buys real security properties for free.

---

## 1. Ground rules

**Build and test (verified working on this machine):**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
export PATH="$JAVA_HOME/bin:$PATH"
cd 20.07
./gradlew testDebugUnitTest     # note: --tests is NOT supported on this Gradle/AGP combo
./gradlew assembleDebug
./gradlew assembleRelease       # R8-minified; must also pass
./gradlew detekt
```

**Rules:**

1. **You may break the wire format and the DB schema.** Bump `MeshFrameCodec.VERSION` 1→2 once, at the
   start of Phase 1, and leave it there for the whole release. Bump `JoinCode.VERSION` 1→2 once, in
   Phase 3. Bump `AppDatabase.version` once, at the end. Ship one incompatible version, not five.
2. **Every Phase 1 and Phase 2 fix ships with a regression test that fails before the fix and passes
   after.** These are security fixes; "it compiles" is not evidence. Write the failing test first,
   watch it fail, then fix.
3. **Do not touch `BeaconRadio`'s advertise/scan trigger logic.** The "only touch the radio when the
   payload actually changed" invariant (`ensureAdvertising`, `startScanning`) was earned through
   repeated live hardware failures. Nothing in this plan requires changing it. If you think you need
   to, stop and ask.
4. **Do not "improve" crypto by improvising.** If a primitive isn't available, stop and report rather
   than substituting something you think is equivalent.
5. **UI changes are confined to Phase 3** (group lifetime picker, expiry display) and Phase 6.
   Everything else is below the UI layer.
6. Run the full suite after every task, not every phase. It takes ~5 seconds.

**Live-device gates.** Phases 1, 3, 5 and 6 are safe to land on compile + unit tests. **Phase 2 changes
the delivery path and must be validated on two real phones before Phase 3 starts** — send an SOS both
directions, share one photo, confirm both radars populate. Phase 4 needs the same gate.

---

## 2. Phase 1 — Crashers and authentication

Pure validation and MAC-input changes. Low risk, highest severity. Land this first.

### 1.1 — Reject hostile `totalChunks` (critical: remote permanent DoS)

**VERIFIED BY TEST.** `EvidenceEntity.totalChunks` arrives off the wire unvalidated. On receiving an
evidence header, `RelayResponder.kt:257` calls `encodeManifest`, which allocates
`ByteArray((totalChunks + 7) / 8)` in `MeshProtocol.encodeBitset` (MeshProtocol.kt:53). A measured
`totalChunks = 200_000_000` produces a 25 MB allocation from a ~120-byte frame; `Int.MAX_VALUE`
produces ~268 MB.

Three things make it fatal rather than rude:

- It works against **blind relays** — `authOk` (RelayResponder.kt:84) returns `true` when there is no
  key for the group, so no membership, key, or join code is needed to send it.
- The header is **persisted to Room**, and `framesToPushOnConnect` (RelayResponder.kt:157) re-encodes
  a manifest for every relayable item on *every subsequent connection*. It survives restart. The app
  crash-loops until the 48h prune, and the attacker controls the timestamp.
- `OutOfMemoryError` is an `Error`, so the broad `catch (e: Exception)` at RelayResponder.kt:379 does
  not catch it.

**Do:**

- Add `const val MAX_EVIDENCE_CHUNKS = 4096` to `RelayEngine.Companion` (1.6 MB at `CHUNK_SIZE` 400 —
  generous; `EvidenceCapture` caps images at 640px/quality 45, which is ~200 chunks in practice).
- `MeshFrameCodec.decode`, `FRAME_EVID_META`: after reading `totalChunks`, return `null` unless it is
  in `1..MAX_EVIDENCE_CHUNKS`.
- `MeshFrameCodec.decode`, `FRAME_MANIFEST`: **same guard**. This is a second, independent instance of
  the same bug — `decodeBitset` loops `0 until totalChunks` building a `Set<Int>`, so a hostile
  manifest hangs the coroutine and exhausts the heap just as effectively.
- `MeshProtocol.encodeBitset`: coerce `totalChunks` into the same range as defence in depth.
- `MeshProtocol.decodeBitset`: return an empty set if `bytes.size * 8 < totalChunks` instead of
  relying on an `ArrayIndexOutOfBoundsException` being swallowed upstream.
- `RelayEngine.ingestChunk`: reject `chunkIndex !in 0 until MAX_EVIDENCE_CHUNKS`. (You cannot check
  against the item's real `totalChunks` — chunks legitimately arrive before the header — so the
  absolute cap is the bound.)

**Test:** `MeshFrameCodecTest` — a hand-built evidence-meta frame with `totalChunks = Int.MAX_VALUE`
decodes to `null`; same for a manifest frame; a legitimate 200-chunk header still round-trips.

### 1.2 — Authenticate the whole SOS message (critical: forgeable by any relay)

**VERIFIED BY TEST.** `sosMacInput` (MeshFrameCodec.kt:110) writes the message with `writeStr`, which
is 1-byte-length-prefixed and silently truncates at 255 bytes. `encodeSos` (line 159) puts the full
message on the wire with `writeStr16`. The MAC therefore covers the first 255 bytes and nothing else.
Confirmed: `"A"*255 + "MEET AT THE NORTH GATE"` and `"A"*255 + "MEET AT THE POLICE LINE"` produce
byte-identical tags, and the tampered text survives the codec round-trip intact.

Any relay — including a non-member blind carrier that cannot read the group at all — can rewrite
everything past byte 255 of an SOS and every member will verify it as authentic.

**Do:**

- `sosMacInput`: `d.writeStr(message)` → `d.writeStr16(message)`.
- Add `const val MAX_SOS_MESSAGE_BYTES = 2000`. Enforce it in `RelayEngine.createSos` (truncate on
  authorship) and in `decode`'s `FRAME_SOS` case (return `null` if longer). `writeStr16`'s
  `writeShort` wraps above 65535, so an explicit cap is required, not optional.
- Audit the other three MAC-input builders while you are here. `nicknameMacInput` is safe (usernames
  are capped at 20 chars). `evidMacInput`'s `mimeType` and `presenceMacInput`'s ids are structurally
  short, but add a decode-time length guard on `mimeType` (≤ 64 bytes) so the property is enforced
  rather than assumed.

**Test:** two messages differing only after byte 255 must produce **different** tags — i.e. the exact
inverse of the assertion that currently passes.

### 1.3 — Reject replayed presence heartbeats

`RelayResponder.kt:278` verifies the MAC over `(groupId, senderId, timestamp)` but never checks that
the timestamp is recent. Replay requires no key — just captured bytes. Anyone who has ever sniffed one
presence frame can make a group read "1 hop away" indefinitely, anywhere. For an app whose core promise
is "are my people near me," that is a meaningful lie to be able to inject.

**Do:** add `PRESENCE_MAX_SKEW_MS = 120_000` and drop the frame if
`abs(now - frame.timestamp) > PRESENCE_MAX_SKEW_MS`, before the MAC check (cheaper first). The MAC
already covers the timestamp, so it cannot be adjusted by an attacker. ±2 minutes is consistent with
the clock agreement the rotating-ID scheme already assumes (`CryptoUtils.ID_WINDOW_SECONDS` = 60s with
±1 window tolerance).

**Test:** `RelayResponderTest` — a validly-MAC'd presence frame with a 10-minute-old timestamp does not
update `HopTracker`; a fresh one does.

### 1.4 — Stop re-alarming on six-hour-old SOS

`SEEN_ID_MAX_AGE_MILLIS` is 6h (RelayEngine.kt:33) but content lives 48h. Once the seen-cache row is
pruned, the next relay of the same SOS makes `ingestSos` return `true` — `sosDao.insert` uses `IGNORE`
so the stored row is untouched, but the return value says "new" — and RelayResponder.kt:240 fires
`onSosReceived`, producing a fresh `IMPORTANCE_HIGH`, `CATEGORY_ALARM`, vibrating notification for a
six-hour-old emergency. In a crowd that is a false alarm indistinguishable from a real one.

**Do:** both halves.

- `SosDao.insert` → `suspend fun insert(sos: SosEntity): Long`. Room returns the rowid, or `-1` when
  the conflict strategy ignored the row. `RelayEngine.ingestSos` derives newness from that, not from
  the seen cache. Do the same for `EvidenceDao.insert` / `ingestEvidenceMeta`.
- Raise `SEEN_ID_MAX_AGE_MILLIS` to equal `CONTENT_MAX_AGE_MILLIS` (48h). The seen cache still earns
  its keep by short-circuiting chunk re-ingest before it reaches the DB.

**Test:** ingesting the same SOS twice with the seen cache manually cleared in between returns `false`
the second time.

### 1.5 — Bound the WiFi Direct socket reads

`WifiDirectAccelerator.handshakeToken` (line 255) reads `din.readInt()` and immediately allocates
`ByteArray(peerLen)` — *before* anything is authenticated; that read is the authentication.
`receiveChunks` (line 283) does the same. Any device that wins the `accept()` race on the fixed port
8988 crashes the phone with four bytes.

**Do:**

- Cap the token length at 64 bytes and the chunk-frame length at `CHUNK_SIZE + 512`; close the socket
  and bail on anything larger.
- The token is also written before the peer is verified, so it leaks to whoever connects. Fix cheaply
  by making the two directions non-equal: the initiator sends `HMAC(token, "i")` and expects
  `HMAC(token, "r")`, and vice versa. Neither side ever transmits the value it accepts.

This feature is off by default and honestly labelled unverified, so scope it tightly — do not attempt
to make WFD production-ready in this pass.

**Test:** `WifiDirectHandoffCoordinatorTest` covers the coordinator, not the socket. Add a plain JVM
test over a loopback `ServerSocket` asserting an oversized length prefix is rejected without allocating.

---

## 3. Phase 2 — Delivery-path robustness

**Riskier: this is the code path that carries SOS.** Land Phase 1 first, then do this, then run the
two-phone gate before moving on.

### 2.1 — Size the catalog filter to the catalog (fixes a silent total-delivery failure)

`CatalogFilter.SIZE_BITS` is 2048 → 256 bytes, plus seed and header ≈ 270 bytes on the wire. The
default ATT MTU is 23 (20 usable). `MeshGattClient` requests 517 and then proceeds from `onMtuChanged`
(MeshGattClient.kt:160) **without checking what was actually granted, or the status**. If the grant is
low, the filter frame is truncated, `decode` returns `null`, and — because SOS/evidence-header/nickname
delivery is now *exclusively* reactive to receiving the peer's filter (RelayResponder.kt:299) — that
link delivers **no SOS at all**. Before the catalog-filter redesign these were pushed eagerly, so a low
MTU merely degraded throughput. The redesign converted a graceful degradation into a silent total
failure of the app's most important function.

The ephemeral-group framing makes the primary fix easy: a 3-day group's catalog is tens of items, not
hundreds. A 2048-bit filter for 20 items is absurdly over-provisioned.

**Do, in this order:**

1. **Make the filter size dynamic.** Move `SIZE_BITS` from a compile-time constant to a per-filter
   field carried on the wire: `sizeBits = (items.size * 10).coerceIn(64, 2048)` rounded up to a byte
   boundary. `hashIndexes` must use the instance's `sizeBits`, and `fromBits` must take it as a
   parameter. Add it to `Frame.CatalogFilter` and to `encodeCatalogFilter`/`decode`. A typical group
   now sends ~32 bytes, which fits inside any MTU, and the 10-bits-per-item ratio holds the
   false-positive rate roughly where the current tuning put it.
2. **Track the negotiated MTU.** In `MeshGattClient.onMtuChanged`, record `mtu` per address (and check
   `status`). Add `onMtuChanged(device, mtu)` to `MeshGattServer`'s callback — it exists on
   `BluetoothGattServerCallback` — and track it there too. Default to 23 when unknown.
3. **Add the fallback.** `framesToPushOnConnect` takes a `maxFrameBytes` budget. If the encoded filter
   still will not fit, skip it and push SOS/evidence-headers/nicknames eagerly instead — the
   pre-redesign behavior. Correctness beats efficiency on this path; a redundant push is free, a
   dropped SOS is not.
4. Log which path was taken at `Log.d`, alongside the existing catalog-filter diagnostics.

**Test:** `CatalogFilterTest` — round-trip at several sizes; membership answers preserved across
`toBits`/`fromBits` at a non-default size; the existing false-positive-rate assertion re-tuned.
`RelayResponderTest` — with `maxFrameBytes = 20`, `framesToPushOnConnect` emits the SOS frames
directly rather than a filter.

### 2.2 — Stop the GATT server racing itself across peers

`MeshGattServer.notify` (line 88) does `characteristic.value = data` on the **single shared
characteristic instance** owned by the server's service, then notifies one device. `writeQueue` is
keyed per address, so two concurrent notifies to two different peers have no mutual exclusion at all —
peer A's bytes can be delivered to peer B. Inbound connections are deliberately uncapped
(MeshGattServer.kt:52), so this is live at exactly the density the app targets.

**Do:**

- API 33+: use `notifyCharacteristicChanged(device, characteristic, confirm, value)`, which takes the
  payload as a parameter and does not mutate shared state. Keep the existing per-address queueing.
- Below 33 (`minSdk` is 26, so this path must exist): serialize the **entire** notify operation —
  including awaiting `onNotificationSent` — behind one server-wide `Mutex`, not a per-address one. The
  legacy API gives no guarantee about when the stack reads `characteristic.value`, which is precisely
  why the newer overload exists. Server-side notifies to different peers become serialized on older
  devices; that is the correct trade, and each operation already has a 2s timeout.

**Test:** hard to unit-test against the real API. Extract the "pick the notify strategy" decision into
a tiny testable function if it helps, but the honest verification here is the two-phone gate plus a
code review of the mutex scope.

### 2.3 — Advertise what you hold, not what you relay

`currentCatalogKeys` (RelayResponder.kt:101) is built from `relay.relayableSos()` /
`relayableEvidenceMeta()`, both of which filter `ttl > 0`. An item held at ttl 0 is therefore absent
from your filter, so every peer re-pushes it on every connection for the remaining retention window,
and `seenDao` silently discards it each time.

**Do:** add `heldSosIds()` / `heldEvidenceIds()` DAO queries with no ttl filter and build the filter
from those. Keep `relayableSos()`/`relayableEvidenceMeta()` for what you actually *send*. The two lists
are legitimately different and the current code conflates them.

**Test:** `RelayResponderTest` — an item at ttl 0 appears in the advertised filter but is not pushed in
response to a peer's filter.

---

## 4. Phase 3 — Ephemeral groups as a first-class concept

This is the headline feature of the release and the direct expression of the framing. Today "delete
when done" is a social convention enforced by a button. Make it structural.

### 3.1 — Group lifetime in the join code

Expiry must be an **absolute timestamp**, not a duration, so that everyone who joins — whenever they
join — agrees on the same end without any coordination.

**Do:**

- `JoinCode.Parsed` gains `expiresAtEpochSec: Long`. Wire layout becomes
  `version(1) | groupId(8) | key(32) | expiresAt(4, epoch seconds) | nameLen(1) | name`. Bump
  `JoinCode.VERSION` to 2. The code grows by ~6 base64 characters.
- `JoinCode.generate(name, lifetimeMillis)` computes `expiresAt`.
- `decode` rejects any code whose `expiresAt` is more than **6 months** in the future or already in the
  past — this is what stops a hostile or malformed code creating an effectively immortal group.
- `GroupEntity` gains `expiresAt: Long`. `GroupRepository.createGroup` / `joinGroup` populate it.
- `getShareCode` must reconstruct the *same* `expiresAt`, not a fresh one, or invitees would get a
  different expiry from the original members. This is easy to get wrong; test it explicitly.

**Test:** `JoinCodeTest` — round-trip preserves `expiresAt`; a code with expiry 7 months out decodes to
`null`; an already-expired code decodes to `null`; `getShareCode` after joining yields a code with the
identical `expiresAt`.

### 3.2 — Enforce expiry

**Do:**

- `GroupRepository.expireGroups()`: for every group with `expiresAt <= now`, call the existing
  `dismantleGroup(id)` — it already deletes evidence, chunks, SOS, nicknames, the group row, and the
  key from `EncryptedSharedPreferences`.
- Call it from `MeshService.startPruning`'s existing 30-minute loop, and once on `MeshService.onCreate`
  so a phone that was off past an expiry cleans up on next launch rather than 30 minutes later.
- `GroupDao.getActiveGroups()` should exclude expired rows as a belt-and-braces measure, so an expired
  group can never be advertised or relayed for even in the window before the sweep runs.

**Test:** a `GroupRepository`-level test (Robolectric, in-memory Room) — a group past `expiresAt` is
gone after `expireGroups()`, along with its SOS rows; a live group is untouched.

### 3.3 — UI (the only UI work in Phases 1–5)

- **Create flow** (`AddGroupScreen`, the `else ->` branch at line 160): add a lifetime chooser under
  the name field. Options: **12 hours / 48 hours (default) / 7 days / 30 days / 6 months**. Match the
  existing `ModeToggle` segmented style rather than introducing a new control.
- **Join flow:** after decoding, show the expiry before joining ("This group expires in 2 days") so
  someone scanning a stranger's QR knows what they are agreeing to.
- **`HomeScreen.GroupRow`:** show remaining time next to the hop count ("expires in 41h"), switching to
  `AppColors.Warning` under 2 hours. The row already renders a secondary label, so this is a small edit.
- Copy change in the create-flow explainer: it currently says "Anyone with this code … is in." Add that
  the group deletes itself at the chosen time on every member's phone.

### 3.4 — Retention follows the group

`RelayEngine.CONTENT_MAX_AGE_MILLIS` stays at 48h as an upper bound, but content must never outlive
its group. `dismantleGroup` already handles the deletion path; confirm by inspection that
`pruneExpired`'s orphan file sweep runs after `expireGroups` in the same maintenance pass, so files
belonging to a just-expired group are collected in the same cycle rather than the next one.

---

## 5. Phase 4 — Sender identity (decision gate: recommended, but optional)

**Read this section before starting it, and make a deliberate call.**

Today one symmetric key does everything — HMAC authenticity, AES-GCM confidentiality, and the beacon
pseudonym. `encodePosition` (MeshFrameCodec.kt:205) takes `senderId` as a caller-supplied parameter and
seals it under the shared key. So **any group member can forge positions, SOS messages and nicknames as
any other member**. At a protest, a forged "SOS — come to the north gate" from a trusted name is the
highest-impact attack available against this app, and it needs only a join code.

The ephemeral framing shrinks this build considerably. Because the group itself is the revocation unit
and dies in days, you need **pin-on-first-sight only**: no PKI, no revocation, no rotation, no trust
transitivity.

**Availability, already checked:** `com.google.crypto.tink:tink-android:1.8.0` is on the runtime
classpath transitively via `androidx.security:security-crypto`. If you use it, **declare it explicitly**
in `build.gradle.kts` rather than relying on a transitive dependency, and add the R8 keep rules
alongside the existing Tink ones in `proguard-rules.pro`. If Ed25519 turns out not to be usable at
`minSdk` 26, **stop and report** — do not substitute a different scheme on your own judgement.

**Design:**

- One Ed25519 keypair **per group**, not per device. Same cost, and it removes cross-group correlation
  of a member who is in two groups — an improvement over today's single `deviceId` used everywhere.
- Private keys go in `GroupKeyStore` beside the group key (same `EncryptedSharedPreferences`, keyed
  `sig:$groupId`). They are deleted by `dismantleGroup` automatically if you store them under a key
  prefix that `removeKey` covers — check this, do not assume it.
- The public key rides in the presence heartbeat (already sent per group on every connect).
- Receivers pin `(groupId, senderId) → publicKey` on first sight in a new `PeerKeyEntity`. A **changed**
  key for a known sender is a hard reject plus a visible warning in the chat feed — that is the actual
  security event worth surfacing.
- Sign the existing canonical MAC inputs (`sosMacInput`, `evidMacInput`, `nicknameMacInput`,
  `presenceMacInput`) and the inner position body. **Keep the group-key HMAC as well** — it is what lets
  a blind relay's downstream member reject non-member forgeries, and it is still doing useful work.
  Signature is an additional field, not a replacement.
- Sequence: land it behind a decode-time "signature optional" tolerance first so a half-upgraded
  two-phone test still functions, then make it mandatory once verified on hardware.

**Cost:** ~64 bytes per authenticated frame and one verify. Acceptable against a ~400-byte chunk budget.

**Test:** a signed SOS from an unpinned sender is accepted and pins; the same sender with a different
key is rejected; an unsigned SOS is rejected once mandatory; the pinned key does not survive
`dismantleGroup`.

---

## 6. Phase 5 — Correctness and efficiency

Independent of each other; land in any order.

### 5.1 — Remove the byte boxing in `createEvidence`

`RelayEngine.kt:85`: `ciphertext.toList().chunked(CHUNK_SIZE)` boxes **every single byte** into a
`java.lang.Byte`. A 300 KB image means ~300,000 boxed objects plus ~750 sublists plus a copy back to
`ByteArray` — several MB of churn on exactly the low-RAM phones this app targets. `maybeReassemble`
twenty lines below already hand-rolls `System.arraycopy` with a comment explaining why the naive
version is unacceptable. Replace with a `for` loop over `copyOfRange`.

### 5.2 — Give `HopTracker` route invalidation

`considerNeighborReport` / `considerDirectHop` (MeshProtocol.kt:98–127) only ever *lower* the stored
hop; the `else` branch refreshes recency without touching the value. Once a peer is recorded at 1 hop,
every later report — including a much worse one — keeps it at 1 while also keeping it fresh. "1 hop
away" can stay arbitrarily wrong indefinitely. This is textbook missing route poisoning.

**Do:** store `(hop, sourceId, updatedAt)` per key. A report from the *same* source replaces the value
even when worse; a report from a different source only improves it. Keep the existing staleness window.
`HopTracker` already has an injectable clock, so this is fully testable.

**Test:** source A reports hop 1, then source A reports hop 3 → tracker reads 3. Source B reports hop 5
in between → still 3, not 5.

### 5.3 — Fix Home's radar staleness

`HomeScreen.kt:93` computes dots inside `remember(myLocation, heading, groups)` — `positionTracker` is
read but is not a key, so peer movement alone does not recompute. It only appears to work because
compass heading jitters every second. `GroupChatScreen` does the same computation inside its polling
loop, correctly. Fold this into 6.1 below rather than patching it twice.

### 5.4 — Decouple position freshness from connection churn

`framesToPushOnConnect` pushes one position frame **per known peer per group** (up to 4 hops,
RelayResponder.kt:165), and the catalog-filter response loop (line 299) has no equivalent of
`consumeBudget` — only chunk pushes are budgeted. At 50 members reconnecting every 45s this is the real
scaling wall, and it is why `syncedReconnectCooldownMs` had to be neutralized back to 45s
(MeshGattClient.kt:63): positions refresh *only* on GATT reconnect, so radar freshness is welded to
connection churn.

**Do — smallest useful step only, this is not the pass to redesign it:**

- Apply a per-connection budget to the catalog-filter response loop, mirroring `consumeBudget`.
- Cap relayed positions per group per connection (nearest N by hop, N ≈ 12).
- Write down the larger idea — a lightweight position channel independent of reconnects, or positions
  carried in the beacon — in `docs/DECISIONS.md` as an open design question. Do not build it here.

### 5.5 — Small, real

- `MeshService.onDestroy` does not call `wifiDirectAccelerator.abortCurrent()`, though
  `setMeshActive(false)` does — a WFD group can outlive the service. One line.
- After a destructive DB recreate, `EncryptedSharedPreferences` keeps group keys whose rows are gone.
  Add an orphan-key sweep on startup: any stored key with no matching group row is deleted.
- `HomeScreen.kt:97–119` builds two `Log.d` strings per group per second unconditionally. Guard them or
  drop them now that the bug they were added to diagnose is understood.

---

## 7. Phase 6 — Structure and brevity

The code is **not** long — 6,952 lines of main source for a working BLE mesh with three features, a
radar, crypto, persistence and a full Compose UI is lean, and the package layout is clean. The prose is
what is long: **24.5% of all lines are comments**, far higher in the BLE layer. `BeaconRadio` opens with
25 lines narrating three superseded designs; `MeshGattServer` lines 35–61 are 27 lines of comment
attached to a 2-line log statement; `ConnectionAttemptTracker` has 50 lines of class doc for 80 lines of
code.

The content is genuinely valuable. It is in the wrong place. These comments explain the *history of the
project*, not the *current code* — a new reader needs the invariant, not the archaeology.

### 6.1 — Collapse the three polling loops (do this one first; it also fixes 5.3)

`HomeScreen`, `GroupChatScreen` and `NavigateScreen` each contain the same `while (true)` /
`try` / `catch` / `Log.w` / `delay(1000)` block, with near-identical comments explaining why the catch
exists. Replace with one `MeshUiState` exposed as a `StateFlow` from `MeshService` (or a small
ViewModel), holding location, heading, compass confidence, per-group hop, and per-group placed peers.
The screens then just collect. This is the clearest single structural win available, it removes three
copies of the same comment, and it fixes Home's stale-radar bug as a side effect.

### 6.2 — Move the archaeology to `docs/DECISIONS.md`

Numbered entries — one per hard-won lesson: the advertise-churn failure, the CCCD-before-write race,
the unreliable hardware `ScanFilter`, the inbound-cap enforcement that broke the mesh, the 180s
synced-cooldown that broke radar freshness. At each code site leave a single line:
`// see docs/DECISIONS.md#advertise-churn`. Expect to remove ~600 lines of source comment while making
the knowledge *more* likely to be read.

Preserve, do not delete: the short invariant statements ("only touch the radio when the payload
changed"), the units and rationale on tuning constants, and every "NOT device-tested" marker.

### 6.3 — Fix detekt config instead of arguing with it

There are ~15 multi-line `@Suppress` blocks making the identical, correct point that PascalCase is the
Compose convention for composables, plus several defending guard-clause `ReturnCount`. Configure
`FunctionNaming` (composable-friendly pattern) and `ReturnCount` once in `detekt.yml`; delete the
essays. Regenerate the baseline only after the cleanup, never to silence something new.

### 6.4 — Split `RelayResponder.handleIncoming`

165 lines of `when` handling ten frame types with a uniform shape (`authOk` → ingest → maybe respond).
One private handler per frame type; the dispatcher becomes readable at a glance. Do this **after**
Phases 1–2, not before — do not refactor the file while you are also changing its security behavior.

### 6.5 — Move WiFi Direct out of `ble/`

Five files, ~580 lines, opt-in, off by default, explicitly unverified on hardware, currently sitting in
the middle of the proven mesh package. Move to `transport/wifidirect/` so it is obviously separable.
`WifiDirectTuning`'s own class doc already argues for exactly this.

### 6.6 — Make encode/MAC-input asymmetry structurally impossible

Finding 1.2 exists because `encodeSos` and `sosMacInput` are hand-mirrored and drifted. Define each
frame's fields once (a small list of `(name, writer, reader)`) and derive encode, decode and MAC input
from it, so the two can never disagree again. This is the highest-leverage item in Phase 6 and the only
one that prevents a *class* of bug rather than tidying.

---

## 8. Explicitly descoped

Do not build these. They were on the original list and the ephemeral-group framing removed or shrank
them; building them anyway costs weeks and adds attack surface.

| Item | Why not |
|---|---|
| Key rotation / ratcheting / Noise sessions | Group lifetime *is* the rotation window |
| Member revocation protocol | "Dismantle and recreate" already exists and is one tap |
| Retroactive Room migrations | Destructive recreate costs a rejoinable 3-day group; write real migrations from v0.3.0 forward only |
| Wire backward compatibility with v0.2.x | Zero users, no long-lived groups; break once, cleanly |
| Fountain / RaptorQ coding for evidence | Real idea, wrong pass — needs its own device-tested cycle |
| Making WiFi Direct production-ready | Bound its inputs (1.5) and leave it off; it needs a hardware program, not more code |
| BT5 Coded PHY hardening | Unverified on hardware; do not build on it until someone has tested it |
| Ephemeral group handle instead of cleartext `groupId` | Real traffic-analysis gap, but a bigger protocol change than this release should carry — log it in `DECISIONS.md` |

---

## 9. Definition of done

Per task: the regression test written first and observed failing; `./gradlew testDebugUnitTest`,
`assembleDebug`, `assembleRelease`, `detekt` all green.

Per phase: a `CHANGELOG.md` entry in the repo's current squashed-release style (`[0.3.0]` with grouped
bullets) — **not** the per-pass internal format.

Before release:

1. Two-phone live pass against `test_rubric.md`, on the **release** build, since R8 strips ~90% and the
   release build has never been runtime-verified.
2. A hostile-input pass: send an evidence header with `totalChunks = Int.MAX_VALUE`, a 5 KB SOS
   message, and a replayed presence frame from a third device to a phone running the release build.
   None may crash it, and none may produce a visible alert.
3. `README.md` updated for group expiry — it is a user-visible behavior change and the README currently
   promises groups outlive their creator with no mention of a lifetime.

**Suggested order if you want a single sequence:** 1.1 → 1.2 → 1.3 → 1.4 → 1.5 → 6.1 → 2.1 → 2.2 → 2.3
→ *[two-phone gate]* → 3.1 → 3.2 → 3.3 → 3.4 → 5.1 → 5.2 → 5.5 → 6.2 → 6.3 → 6.6 → 6.4 → 6.5 → 5.4 →
*[decision gate]* → Phase 4.

`6.1` is placed early deliberately: it is low-risk, it removes triplication you would otherwise have to
edit three times during Phase 3's UI work, and it fixes 5.3 for free.
