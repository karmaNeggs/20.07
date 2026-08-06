# 20.07 v2 — scaling plan

**RESUME HERE — current status as of 2026-08-06 (session continued past the previous end-of-session
checkpoint).** This is the single status block to trust; anything else in this document (including
inline "STATUS" notes inside Part 7 below) is detail underneath this, not a competing source. If a
phase's own section below ever seems to disagree with this block, this block is current and that
section is what's stale.

- **Shipped and committed, on `main`, PUSHED to origin (public):** P0a (crowd simulator), P0b (peer
  identity), P1 (SOS flood-forward), P3 (persistent links) — v0.6.0-dev through v0.6.3-dev.
- **Hardware-confirmed across four live 3-phone test rounds** (2026-08-05): message-delay fix,
  radar-staleness fix, duplicate-connection-callback fix, Bluetooth off→on recovery fix. Full detail
  in `docs/DECISIONS.md` decisions 18-22.
- **P2 (broadcast tier): both Tier-1 sim open questions resolved (decisions 23-25), AND production
  wiring has now started (decision 26, same day)** — first slice, deliberately narrow, same
  discipline as P1's own "SOS only first" slice. Sim summary: decision 23's 3-way question resolved
  as option (c) (sightings own-group-scoped, already true in production `BeaconRadio`); that
  dissolved decision 23's "D=2 boundary bug" but surfaced decision 24's finding (suppression could
  pin at degree 1); decision 25 found and fixed the real root cause **in production**,
  `TrickleTimer.onSighting(sourceId)` now dedupes within a window. Production summary (decision 26):
  the old Coded-PHY-only "long-range channel" already had every piece Tier B needs, so it was
  **generalized in place** rather than duplicated — gate loosened to `extendedAdvertisingSupported`
  alone (Coded PHY now opportunistic, not required), new payload adds an explicit `presenceHop`
  field so presence can propagate a real multi-hop gradient with zero GATT connections, a hardware
  `ScanFilter` on the service UUID restored (on this new scan only — legacy scan deliberately
  untouched), degree-gated `setReportDelay()` batching added. Resolves `PLAN-v2.md` Part 8's open
  item on the Coded PHY channel (re-scope, not delete). Position and SOS-message broadcast
  deliberately deferred to a later slice, named explicitly. 319 tests, detekt clean, both variants
  compile/test/assemble (incl. `lintVitalRelease`) green — **NOT hardware-confirmed**, same as this
  channel's Coded-PHY-only predecessor always was, now broader (ScanFilter/batching are also new and
  untested on real hardware).
- **GitHub Pages + Releases are caught up**: `README.md`/`releases/` APK/GitHub Release all updated
  to v0.6.3-dev, pushed live at `https://karmaneggs.github.io/20.07/`. **Now stale** — decisions
  23-26's work (source/docs + a real production feature) has not had a version bump or APK/Pages
  refresh yet. Due once this P2 slice (or the next one) is ready to hand off for its own hardware
  smoke-test round, not before.
- **Committed and pushed, on `main`, through `dc7f341`** (decision 26's broadcast-tier slice).
  Working tree clean as of this checkpoint. If you find uncommitted changes when resuming, they're
  from a session after this one.
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
| **Non-interactive epoch ratchet** | **Adopt.** `K_e = HKDF(root, e)` where `e = floor(now / epochLen)`. Every member derives it independently — no commits, no coordination, no delivery service. Keep N previous epoch keys for skew and store-and-forward. Gives forward secrecy against *later* seizure, which is exactly our threat model, and composes with the join-code-v2 expiry field we already ship. |
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

The gap between "JVM simulator" and "3 real phones" is precisely where every v1 bug lived. Close it
with a cheap third tier:

- **Tier 1 — Simulator.** D = 3 → 400. Everything in §6.1–6.3 that is logic, timing and state
  machines. Cannot tell us anything about a real radio.
- **Tier 2 — Synthetic radio load (new).** A handful of ESP32s or a Linux box with BlueZ, each
  advertising and responding as many virtual nodes, producing **real BLE traffic at real density**
  against real phones. Precedent exists: `fvolcic/bitchat-relay` is exactly this shape for bitchat —
  an ESP32 node holding up to 8 concurrent BLE connections. Two or three boards emulating dozens of
  advertisers costs about the price of a takeaway and catches the entire class the simulator cannot:
  scan-callback storms, OS-level scan throttling, advertising-set limits, chipset churn under load.
  **This is the highest-value new piece of test infrastructure in the plan**, because §9.2 item 1
  (the scan storm) is currently a prediction we cannot otherwise verify without 400 phones.
- **Tier 3 — Three real phones.** The final word on chipset behaviour, callback ordering and battery.
  Per §5.4, this is where all adaptations are off, so it is a clean test of core logic.

A phase is done when all three tiers that apply to it pass. Tier 2 is what makes the 200–400 target
honest rather than aspirational.

---

## Part 7 — Phased roadmap

Same discipline as v1: **one phase, one APK, hardware-gated, nothing else lands until it passes.**
That rule is not bureaucracy — three separate v1 rounds shipped an unverified radio change and each
broke something.

**Every phase is gated on the three tiers in §6.4** — simulator, synthetic radio load, three real
phones — for whichever apply. No tier is sufficient alone: the simulator cannot see a radio, the load
generator cannot see chipset-specific behaviour, and three phones cannot produce density. Gates below
name the tiers explicitly; scenario IDs refer to §6.3.

**How hardware gates actually get checked (2026-08-05 clarification).** This has never meant real-time
supervision — every hardware-verified fix since pass 10 has worked the same asynchronous way: a
phase's code + sim gate land first, a debug APK is built and handed off, the user installs it on real
phones, and the on-device `DiagnosticsLog` (debug-only, exportable, never positions/message bodies —
see its class doc) captures what the sim gate's claim predicts. The user exports and sends that log
(or describes what they observed) back for review. **A hardware gate is a checkpoint on the claim, not
a precondition for writing the next phase's code** — implementation keeps moving on the sim/compile/
test-verified track; a phase is only marked *hardware-confirmed* once that log/report comes back,
same as decisions 8-13's confirmation trail in `docs/DECISIONS.md`. Where a phase's hardware gate
needs infrastructure nobody has yet (P0a's Tier 2 ESP32/BlueZ boards), that gate stays open and
explicitly flagged — sim-verified, not hardware-verified — rather than blocking the phases after it.

**P0a — Test rig (prerequisite, no APK). Specified in full in Part 6.**
**STATUS (2026-08-05): Tier 1 harness built and gated, `app/src/test/java/org/offlinemesh/app/sim/`
— see `docs/DECISIONS.md` decision 14. Tier 2 (ESP32/BlueZ boards) not started — needs physical
hardware nobody has yet; stays open, not a blocker on later phases per the Part 7 preamble. 7 of 11
§6.3 scenarios implemented (S1/S2/S6/S7/S8/S9/S11); S3/S4/S5/S10 documented as blocked on P2/P4/P5.**
Tier 1 headless JVM harness plus the Tier 2 synthetic-load boards. The harness drives the *real*
routing classes and is tractable because the decision logic is already extracted into Android-free
classes (`ConnectionAttemptTracker`, `CatalogFilter`, `TrickleTimer`, `HopTracker`,
`OpaqueFrameRelay`). Crowd-scale simulation was explicitly dropped in v1; v2 is a scaling project and
**every claim in it is unfalsifiable without this.** Must come first. D = 3 and D = 400 are both
first-class configurations from day one, along with the §6.1 injection list and the §6.2 invariants.
*Gate: reproduce v1's measured behaviour at D = 3 (93 % empty syncs, one connection per ~50 s) and
project it to D = 400 (~8 min sync round). If the harness cannot reproduce the known-bad numbers it
is not modelling anything, and nothing built on it can be trusted. Tier 2 boards must independently
reproduce the scan-callback storm predicted in §9.2 item 1 — that prediction is currently unverified
and gates P2's design.*

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

**No P2-specific blocker remains on continuing this work.** Per the RESUME HERE block's sequencing
note: the sustained multi-hour field session is planned for AFTER P2 is built, as the one
comprehensive field validation of the whole v2 stack — it is not a gate P2 needs to wait behind.
Still refines "audibly loud again within one interval of leaving" to closer to two intervals,
measured — unaffected by any of the above. **Still not started**: position/SOS broadcast, the
Tier 2/3 gates. 319 tests, detekt clean, both variants compile/test/assemble
(`assembleDebug`/`assembleRelease`, incl. `lintVitalRelease`) green. **NOT hardware-confirmed** —
this channel's Coded-PHY-only predecessor never had hardware to test on either; now broader, since
the ScanFilter and report-delay-batching pieces are also new and untested on real hardware this
session.
*Tier 1: S2, S3, S4, S7 — Trickle holds per-node broadcast cost flat as density rises; presence
freshness stays inside the window with connections disabled entirely; S3 shows the device audibly
loud again within one interval of leaving.*
*Tier 2: the scan storm is measured, not predicted, and the ScanFilter fix is shown to remove it.*
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
*Sim gate: message delivered across a partition healed 20 min later; copy budget stays bounded.*
*Hardware gate: 3 phones, one carried out of range and back — message arrives.*

**P5 — Media (§4.3).** Thumbnail-first + pull-on-demand **first** (§9.2 item 8 — it is what makes
48 h retention viable at 400), then fountain coding, then L2CAP CoC and Wi-Fi Aware as bulk pipes.
Wi-Fi Direct removed.
*Sim gate: at D = 400, per-node media storage stays bounded with 20 items circulating.*
*Hardware gate: 3 phones — a 300 KB photo delivered member-to-member without entering the flood.*

**P6 — Crypto (§4.4).** Epoch key ratchet, rotating group handle replacing cleartext `groupId`,
padding for all frame types, SOS body encryption.
*Sim gate: handle rotation does not break dedup or forwarding across an epoch boundary.*
*Hardware gate: security review pass; wire capture shows no cleartext group identifier.*

**P7 — bitchat bridge (§3 Level 1). Confirmed in scope**, opt-in and off by default.
*Sim gate: n/a.*
*Hardware gate: a 20.07 message relayed through a bitchat-only device and received by a second
20.07 device.*

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
   group's emergency traffic."*
2. **Wi-Fi Direct: remove now or after P5?** It is compile-verified only and its system dialog
   breaks the disguise. Recommendation: remove at P5 when Aware replaces it, not before, so the
   removal is one change rather than a regression window.
3. **Do we accept a stable *local* identity (§5.2)?** Nothing new goes on the wire, but it does mean
   the device holds a per-group durable key that survives address rotation. Recommendation: yes —
   it is a key we already generate and pin today.
4. **Is 48 h still the right content lifetime** once couriers exist and groups expire by join code?
   Courier envelopes at 24 h and content at 48 h may want to converge. In regime R2 this is also a
   storage question: a blind relay carrying 400 people's traffic for 48 h is a different proposition
   from carrying three people's. Interacts with item 1.
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
