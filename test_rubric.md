# 20.07 — Test Rubric v2

Manual end-to-end QC checklist for the current build (post group-identity rework, screen
rebuild, and battery/power-tier pass). This supersedes the earlier version — the join
mechanism, screens, and radar all changed since it was written.

**Minimum hardware:** 2 Android phones (API 26+) for baseline cases; several cases explicitly
need 3+ to exercise multi-hop relay and blind-carrier behavior — noted where that applies.

**Before anything else — read this.** Group identity is now **random per creation**, not
derived from a typed name+passphrase. Two phones independently tapping "Create" with the same
group *name* will end up in two different, unrelated groups with no error shown — this is not a
bug, it's how the mechanism works now, and it is very easy to trigger by accident during
testing. The only correct way to get two phones into the same group is: **one phone creates,
shares the generated code/link, the other phone joins with that exact code.** If section 1 below
fails, re-check this before assuming it's a bug.

---

## 0. Environment setup

| # | Case | Steps | Expected |
|---|------|-------|----------|
| 0.1 | Clean install | Install APK on both phones, first launch | Permission prompt appears before any group UI |
| 0.2 | Permission grant | Grant Bluetooth + Location | App proceeds to Home screen, foreground "Syncing" notification appears |
| 0.3 | Permission deny then retry | Deny, reopen permission gate, grant | Moves past the gate automatically — no force-close needed (this was a fixed bug; confirm it stays fixed) |
| 0.4 | Bluetooth off | Turn Bluetooth off after granting permissions | App doesn't crash; Home radar shows nothing; re-enable and confirm recovery without restart |
| 0.5 | Location off | Turn Location off | Radar falls back to hop-count/no-position state rather than hanging; re-enable mid-session and confirm it recovers without restart (this was a fixed bug) |
| 0.6 | Keyboard covering submit buttons | Open Add Group (Create or Join) and Group Chat, tap a text field | The submit button / send button must remain visible above the keyboard, not hidden behind it |

---

## 1. Group creation & joining — the mechanism most likely to be mistested

| # | Case | Steps | Expected |
|---|------|-------|----------|
| 1.1 | Create | Phone A: Add Group → Create → name "test1" → Create group | Shows a generated code + "Copy code" / "Copy link" options |
| 1.2 | Join via code — the correct flow | Phone B: Add Group → Join → paste **A's exact generated code** → Join group | B lands in the same group as A (verify via 2.x below — B should be able to see A once in range) |
| 1.3 | **Independently creating "the same" group — the trap** | Phone A and Phone B each independently tap Create with name "test1" (nobody shares a code) | A and B are in **two different groups** with different random keys — this is expected/correct behavior, not a bug. If you see this in the group list on both phones and assume they're connected, that's the mistake to check for first when troubleshooting "no one nearby." |
| 1.4 | Join via link | Send A's `mesh2007://join?c=...` link to B (any app — SMS, email, etc.), tap it on B | Opens the app directly to Add Group with the code pre-filled, auto-joins |
| 1.5 | Malformed code | Phone B: Join with garbage text, e.g. "not a real code" | Shows "That code doesn't look right," doesn't crash, doesn't silently create a broken group |
| 1.6 | Re-join same code | Phone B joins A's code twice (e.g. via link tapped twice) | No duplicate group in the list; second join is a harmless no-op (same group id both times) |
| 1.7 | Multiple groups | Create/join 2+ distinct groups on the same phone | Both appear independently in the Home group list; each gets its own stable color |
| 1.8 | Delete group | Open a group's chat screen → Delete group → confirm | Group disappears from Home immediately; its SOS/evidence/chunk rows are actually removed from local storage (not just hidden — verify via 9.x if you have a rooted/debuggable way to inspect the DB) |

---

## 2. Discovery (BLE beacon layer)

| # | Case | Steps | Expected |
|---|------|-------|----------|
| 2.1 | Same-group discovery, both foregrounded | Two phones joined via 1.2, both apps open on-screen, within ~5m | Within a few seconds, Home screen's group row stops showing "no one nearby" and shows "N hop(s) away" |
| 2.2 | Same-group discovery, one backgrounded | Same as 2.1, but background one phone (press home, don't force-stop) | Discovery should still work, just potentially slower — the backgrounded phone drops to the Relay power tier (see section 8) |
| 2.3 | Different-group non-discovery | Two phones in groups created independently per 1.3 (different keys) | Correctly show "no one nearby" for each other — this is the *expected* outcome of 1.3, not a failure |
| 2.4 | Out-of-range drop | Two connected phones, walk one out of BLE range (~30m+ / through walls) | Hop status degrades to "no one nearby" within roughly a minute (45s hop staleness window), not stuck showing stale "in range" state |
| 2.5 | Range re-entry | Bring the phone back in range after 2.4 | Rediscovery happens without restarting the app |
| 2.6 | Non-member blind relay | A phone with zero groups joined, sitting near two phones that share a group | Still scans/advertises (generic beacon) and, per section 5, should relay opaque data for the other two without being able to read it |
| 2.7 | Passerby relay (3 phones, same group) | A and B in the same group, moved out of direct range of each other; C (same group) walks near A, then near B, within a couple minutes | A message/SOS sent on A should reach B via C carrying it — this was a real, fixed gap (a peer-agnostic reconnect cooldown could block C from getting back to B in time even after picking up something new from A) |

---

## 3. Home screen — unified radar & group list

| # | Case | Steps | Expected |
|---|------|-------|----------|
| 3.1 | No GPS fix yet | Open Home before a GPS fix is acquired | Shows a "Waiting for GPS fix…" placeholder card in the radar's spot, not a blank/broken area |
| 3.2 | Radar with peers | Two phones, same group (via 1.2), both GPS fixes, in range | Each shows the other as a colored dot on the combined radar, pulsing — pulse rate should visibly increase as phones move closer together |
| 3.3 | Multi-group color coding | Phone in 2+ groups, members nearby in more than one | Dots render in each group's own distinct color; group list rows show a small colored dot matching, not a full-color-filled row |
| 3.4 | Group row hop text | Compare a group row's "N hop(s) away" / "no one nearby" text against actual physical proximity | Should track reality — this is the exact text from the bug report in this session, worth extra scrutiny |
| 3.5 | Power saver toggle | Toggle "Power saver" on Home | Icon/switch changes state (amber when on); combined with section 8, advertise/scan should visibly become less frequent — best checked via battery stats or discovery latency, not visually |
| 3.6 | General SOS button | Tap the red SOS tile on Home | Navigates to the SOS composer (section 6), does not send anything yet |
| 3.7 | Light/dark theme toggle | Tap the icon top-right on Home | Whole app switches theme immediately (radar, tiles, group rows, system status/nav bars all follow); toggle again, choice persists across an app restart |
| 3.8 | Offline toggle actually stops the radar | Turn "Offline" on (Bluetooth stays on) | Radar is replaced by a "Mesh is offline" message on Home, Navigate, and Group chat alike — not a frozen/stale radar still showing old positions (this was a fixed bug) |

---

## 4. Navigate screen (per-group, expanded radar)

| # | Case | Steps | Expected |
|---|------|-------|----------|
| 4.1 | Reached from Group Chat | Open a group's chat, tap the mini radar | Navigates to the full-screen Navigate view for that specific group |
| 4.2 | Forward-up rotation | With a peer at a known fixed real-world direction, physically rotate the phone | The peer's dot should rotate on screen to stay pointing the correct real-world direction — turning away from them should move their dot away from "ahead" |
| 4.3 | Compass low-accuracy warning | Hold the phone near a metal object/laptop, or right after install before calibrating | Low-accuracy banner should appear under deliberately bad conditions |
| 4.4 | Hop-count fallback | No GPS fix, but a group member is in direct BLE range | Shows "N hop(s) to nearest group member" text, not a blank radar |
| 4.5 | Position staleness | Get a peer showing on the radar, then have their phone lose GPS (walk indoors) and wait 90+ seconds | Their dot disappears — should not remain frozen indefinitely |
| 4.6 | Sweep/glow animation | Just observe | Rings glow, a faint sweep rotates continuously, cardinal ticks are visible — purely cosmetic, shouldn't affect correctness of dot placement |

---

## 5. Evidence relay — chunking, encryption, reassembly

| # | Case | Steps | Expected |
|---|------|-------|----------|
| 5.1 | Send + receive, 2 phones same group, in range | A opens the group chat, taps the attach icon, picks a photo | B's feed shows the item with a chunk-progress count that increases over time, then flips to "File received — tap to view" |
| 5.2 | Hash integrity | After 5.1, compare completed items' hash prefixes on both phones | Should match |
| 5.3 | Blind carrier relay (3 phones) | A and C in the same group, B in a different group or none, physically between A and C but out of A/C direct range | C eventually receives, having relayed through B despite B never being able to decrypt it |
| 5.4 | No camera permission ever appears | Fresh install, use "attach" in a group chat | System Photo Picker opens directly — no camera permission prompt, no gallery-wide storage prompt |
| 5.5 | Two-contact-minimum no longer required | Send evidence, watch the very first contact between sender and a fresh receiver | Chunks should start flowing in the *same* connection the receiver first learns of the item (this was a fixed bug — previously required a second, separate connection ~45-90s later) |

---

## 6. SOS — both the general composer and in-group quick send

| # | Case | Steps | Expected |
|---|------|-------|----------|
| 6.1 | General SOS, default selection | Home → SOS button | Every joined group should be pre-checked by default |
| 6.2 | General SOS, deselect | Uncheck one group, send | Only the checked groups receive it |
| 6.3 | In-group quick SOS | Open a specific group's chat, type a message, tap send (no evidence attached) | Sends as an in-group SOS scoped to just that group, no checkbox UI involved |
| 6.4 | Receive | B (same group as sender, in range) | Message appears in B's feed within one duty-cycle/connection window |
| 6.5 | Hop count to SOS — sender | On the device that sent it | Should not read as "1 hop away from itself" |
| 6.6 | Hop count to SOS — 1-hop receiver | On a direct neighbor of the sender | Should show 1 hop, not 0 (this was a fixed bug — every relayer used to misreport itself as the origin) |
| 6.7 | Hop count to SOS — multi-hop (3+ phones) | A phone only reachable via one relay in between | Should show 2+ hops matching the real relay chain, not 0 or 1 |

---

## 7. Multi-group / cross-group isolation

| # | Case | Steps | Expected |
|---|------|-------|----------|
| 7.1 | Two independent groups, overlapping membership window | A+B in group 1, C+D in group 2, all physically co-located | Group 1 members only see group 1 content in their feed/radar; group 2 members only see theirs — relaying opaque bytes for the other group is fine, *displaying* its content is not |
| 7.2 | Same phone, two groups, simultaneous activity | Send SOS in group 1 and evidence in group 2 around the same time | Both function independently |

---

## 8. Power tiers & battery

| # | Case | Steps | Expected |
|---|------|-------|----------|
| 8.1 | Active tier while foregrounded | App open and on-screen | Should use the faster/more responsive scan+advertise settings (not directly observable in UI — infer from faster discovery in 2.1 vs 2.2) |
| 8.2 | Relay tier while backgrounded | Background the app (don't force-stop) | Should switch to the battery-saving tier automatically — discovery in 2.2 should be somewhat slower than 2.1, not equally fast |
| 8.3 | Power saver forces Relay even in foreground | Toggle Power saver on while app is open, re-run 2.1 | Discovery should behave like the Relay tier despite being foregrounded |
| 8.4 | Android's own battery stats | After 30+ min of mixed foreground/background use | Should show a real reduction vs. the pre-tuning build, though some cost is inherent (continuous BLE + GPS is not free on any implementation) |

---

## 9. Lifecycle / robustness

| # | Case | Steps | Expected |
|---|------|-------|----------|
| 9.1 | Background survival | Send app to background for 10+ minutes | Foreground notification still present; mesh still functioning |
| 9.2 | App restart | Force-stop and relaunch | Groups persist (Room-backed), keys persist (EncryptedSharedPreferences-backed); position data does NOT persist (intentional — re-check 4.5's privacy framing) |
| 9.3 | Data pruning | Leave the app running 48+ hours (or manually inspect on a rooted/debuggable build) | SOS/evidence/chunks older than 48h should be gone; the 30-minute prune cycle should be visible in logs if you have logcat access |
| 9.4 | Delete group actually deletes | Delete a group, then inspect `mesh.db` on a rooted/debuggable build | No `sos_events`/`evidence`/`evidence_chunks` rows should remain for that group id — this was a fixed bug (previously only hid the group, left content behind) |
| 9.5 | Rapid Bluetooth toggle | Toggle Bluetooth off/on 3-4 times quickly while running | Shouldn't crash or end up permanently stuck not scanning |

---

## 10. Security/privacy spot checks

| # | Case | Steps | Expected |
|---|------|-------|----------|
| 10.1 | No camera permission requested | `Settings > Apps > 2007 > Permissions` after full use | CAMERA never appears in the list |
| 10.2 | No secret in the clear over the air | BLE sniffing tooling if available | The group key itself should never appear in any advertised or GATT-transmitted byte sequence — only the derived rotating id and encrypted payloads |
| 10.3 | Non-member can't read evidence | Covered in 5.3 — confirm no decrypted image ever gets written to a non-member's filesystem |
| 10.4 | Position data not in Room DB | Rooted/debuggable build, inspect `mesh.db` | No lat/lon columns or position table at all — positions only ever live in memory |

---

## 11. Known-gap acknowledgment (not bugs — confirm expected behavior)

| # | Case | Expected (per README) |
|---|------|------------------------|
| 11.1 | Decoy launcher icon | Home screen has a "Disguise" toggle; turning it on picks one of four identities (Notes, Files, Weather, Calculator) at random *every time it's toggled on*, not held stable — expect the icon/label to potentially change between one toggle-on and the next, that's correct, not a bug |
| 11.2 | Presence hop-count ceiling | Hop-count-to-presence (not SOS) is capped at "in direct range or not," not a true extending multi-hop gradient — SOS doesn't have this limitation |
| 11.3 | GPS accuracy in dense urban areas / indoors | Expect 10-30m+ error near tall buildings, considerably worse indoors — a physical limitation, not an app bug. **Confirmed live**: a peer can show "N hop(s) away" (needs only a heard beacon) while never getting a radar dot at all, with no on-screen explanation, if combined GPS accuracy exceeds ~150m — don't mistake this for broken relay; check 2.x/6.x (hop count) still updating correctly before concluding anything is actually wrong |
| 11.4 | Large evidence propagation time | A multi-MB item can realistically take hours across a sparse or discontinuous crowd — check chunk progress is moving at all before concluding it's stuck |
| 11.5 | Radar animation battery cost | Continuous sweep/pulse animation only runs while the radar is actually visible on screen; Android stops delivering animation frame callbacks once the app is backgrounded, so this shouldn't add to background battery drain — worth confirming empirically if profiling tools are available |
