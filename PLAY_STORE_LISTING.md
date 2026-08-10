# Play Store listing — draft content

Staging doc for Play Console fields. Not published anywhere itself — copy/paste source only. See
`PLAN-v2.md` Part 11 for the rest of the Play Store checklist this fits into.

---

## Short description (≤80 characters)

```
Offline Bluetooth mesh: find your group, send SOS, share photos. No signal.
```
(75 chars, verified via `wc -c`.)

Alternate:
```
Find your group, call for help, share photos - over Bluetooth, no signal.
```
(73 chars, verified via `wc -c` — plain hyphen, not an em-dash, to avoid any encoding surprises
in Play Console's text field.)

---

## Full description (≤4000 characters)

```
20.07 is an offline, phone-to-phone Bluetooth mesh app for staying connected when there's no
signal, too many people on one cell tower, or you just don't have a local data plan — festivals,
hiking, crowds, natural disasters, or anywhere else the network can't help you.

No servers. No accounts. No SIM or data plan needed. Phones relay for each other directly over
Bluetooth — everything encrypted under a key only your group holds.

THREE THINGS, ON PURPOSE — NOTHING ELSE

Navigate — a live radar of your group around you. Walk toward the dot, don't read a map. Works
even without GPS, falling back to a simple hop-count distance using only Bluetooth.

SOS — an authenticated distress alert that hops phone to phone to reach your group, with distance
shown in hops so others can find you.

Incident photos — share photos with your group, encrypted and relayed across the mesh, so a copy
exists on more than one phone.

WHO THIS IS FOR

- Festivals, concerts, and big events where the crowd overloads the local cell tower
- Hiking, camping, and remote areas with no towers nearby at all
- Natural disasters and blackouts, when towers are down
- Traveling without a local data plan
- Demonstrations, where networks may be jammed, throttled, or not trusted

WHY IT'S DIFFERENT

- No accounts, ever — one QR code creates a group. Nobody "owns" it; removing one person doesn't
  expose or kill the group.
- Every phone relays for every group, whether or not it's a member — content is opaque ciphertext
  to anyone without the key, so strangers' phones can extend your range without ever reading your
  data.
- Your Bluetooth identifier rotates every 60 seconds, so passive scanning can't fingerprint your
  phone over time.
- GPS positions live in memory only, never touch disk, and are gone the instant the app closes.
- Groups are ephemeral by design — pick a lifetime from 12 hours to 6 months; every member's app
  deletes everything about that group automatically once it expires.

HONEST ABOUT WHAT THIS IS

This is an open source (MIT licensed), independently developed safety tool — not a product from a
company. It's built and tested carefully, with its real, current limitations documented openly
rather than hidden: see the project's README on GitHub for the full, honest list of what's proven
on real hardware and what isn't yet.

Source code, full documentation, and the project's own engineering decision log are all public:
github.com/karmaNeggs/20.07
```

(2,465 characters, verified — well under the 4,000 limit, room to expand if wanted.)

---

## Data Safety form — reference answers

Google Play's Data Safety form asks about data *collected* and *shared* in its own specific
categories. Answers below, derived directly from README's Security Model / Permissions sections —
paste into Console, don't just describe from memory when actually filling the form (re-check
against current README first, in case anything's changed since this was written 2026-08-10).

**Does your app collect or share any of the required user data types?** Yes (see below) — but note
throughout: "shared" here means peer-to-peer with people *you* invite into your own group, over an
encrypted Bluetooth connection. Nothing is ever sent to the developer or to any server, because
none exists.

| Data type | Collected? | Shared? | Purpose | Notes for the form |
|---|---|---|---|---|
| **Approximate location** | Yes | Yes (peer-to-peer, encrypted, group only) | App functionality (radar/navigate) | In-memory only, never persisted, expires automatically (~90s–3min) |
| **Precise location** | Yes | Yes (peer-to-peer, encrypted, group only) | App functionality (radar/navigate) | Same as above — this app doesn't distinguish the two internally, GPS fixes are used at whatever precision the OS provides |
| **Photos** | Yes | Yes (peer-to-peer, encrypted, group only) | App functionality (incident-photo sharing) | Only photos the user explicitly picks; never uploaded to any server |
| **User IDs / Device IDs** | No | No | — | Deliberately NOT used — Bluetooth identifier rotates every 60s specifically to avoid a persistent, trackable ID. Nicknames are user-chosen, shared only within the user's own group, not a system identifier. |
| **App activity, analytics** | No | No | — | No analytics SDK of any kind is included |
| **Personal info (name, email, etc.)** | No | No | — | No accounts exist |

**Is all of the user data collected by your app encrypted in transit?** Yes — AES-256-GCM for
message/photo content, all data transmitted only over Bluetooth LE, peer-to-peer.

**Do you provide a way for users to request that their data be deleted?** Not applicable in the
traditional sense (no server holds any data to delete), but the app itself lets users delete a
group at any time (wipes it from their own device immediately), and every group auto-expires on
its own regardless. Uninstalling the app deletes everything.

**Data collection required for app functionality?** Yes for location (radar) and Bluetooth
(the entire mesh) — both are core to what the app does, not optional analytics.

**Privacy Policy URL**: `https://karmaneggs.github.io/20.07/privacy.html`
