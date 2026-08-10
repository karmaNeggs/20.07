# Contributing to 20.07

Thanks for considering it. This is a small, solo-maintained safety-tooling project with an
unusually strict development discipline for its size — read this before opening a PR, it'll save
you a review round-trip.

## Before you write any code

- **Read [`README.md`](README.md)'s Known Limitations section first.** Several things that look
  like bugs are already-documented, deliberate tradeoffs (see the section itself for why). If
  you've found something not listed there, it's probably new — say so in the issue.
- **Read [`docs/WHITEPAPER.md`](docs/WHITEPAPER.md)** for the handful of places this codebase does
  something non-obvious on purpose. If your change touches one of those, explain in the PR why the
  existing approach doesn't work for your case, not just what you changed.
- **For anything touching the BLE/mesh layer** (`app/src/main/java/org/offlinemesh/app/ble/`),
  check [`docs/DECISIONS.md`](docs/DECISIONS.md) for whether this exact thing has been tried
  before. A lot of entries there are "this looked like the obvious fix, here's the live-hardware
  failure it caused" — real prior art, not just a changelog.

## Development setup

```
git clone <this repo>
cd 20.07
export JAVA_HOME=<a JDK 17 install>   # e.g. Homebrew: /opt/homebrew/opt/openjdk@17 on macOS
./gradlew assembleDebug                # APK at app/build/outputs/apk/debug/
./gradlew test detekt                  # Tier 1 test suite + static analysis
```
Needs the Android SDK (platform 34, build-tools 34.0.0) — Android Studio sets this up
automatically, or install the command-line SDK tools yourself. See [`TESTING.md`](TESTING.md) for
the full three-tier test breakdown (pure logic / Compose UI / manual device).

## What a PR needs before it's reviewable

- [ ] `./gradlew test detekt` passes locally. New logic (crypto, wire-format, state machines,
      anything with an edge case) gets a Tier 1 unit test — see `app/src/test/` for the existing
      shape and density of these; a PR that changes behavior with no corresponding test is the
      single most common reason for a slow review here.
- [ ] `./gradlew lint` has no new warnings.
- [ ] If the change touches real device behavior (BLE radio, GATT, background service lifecycle,
      permissions) — **say plainly in the PR whether it's been tested on a real phone or only
      compiled.** This project has a hard-won rule: a fix that's only compile-verified gets shipped
      labeled as such, not implied to be working. Several real regressions in this project's
      history came from skipping that distinction — see `docs/DECISIONS.md` for the receipts. If
      you can't test on hardware yourself, say so and it'll get flagged for a hardware round before
      merge, not silently assumed safe.
- [ ] A one-line description of *why*, not just *what* — this codebase's comments and commit
      history are deliberately why-focused (see any file's existing comments for the tone); PRs
      should match that.

## Code style, briefly

- Comments explain **why**, not what — a well-named function already says what it does. Only
  comment a genuinely non-obvious constraint, a workaround for a specific platform bug, or a
  tradeoff someone reading the code later would otherwise re-litigate.
- No speculative abstraction. If the codebase does something in three lines instead of a new
  helper class, that's usually deliberate, not an oversight — see `docs/WHITEPAPER.md`'s intro for
  the project's own stated bias here.
- Match the detekt config already in place (`app/config/detekt/detekt.yml`) rather than adding
  inline suppressions for style you'd prefer differently. If a rule is genuinely wrong for this
  codebase, that's its own PR with its own justification, not bundled into a feature change.

## Reporting bugs

Open an issue using the bug report template. For anything in the BLE/mesh layer specifically, the
phones/Android versions/chipsets involved are more useful than a stack trace — most real bugs here
have been radio-behavior ones that only show up on physical hardware, not in a compiler or
emulator (see `TESTING.md` for why). If you can, attach a `DiagnosticsLog` export (debug builds
only — the export button is on the Home screen) rather than describing symptoms from memory.

**Security issues**: see [`SECURITY.md`](SECURITY.md) — please don't open a public issue for
anything actively exploitable.

## Legal

By submitting a pull request, you certify that you wrote the contribution yourself (or have the
right to submit it under this project's license) and agree to license it under this project's MIT
license ([`LICENSE`](LICENSE)) — the same lightweight certification of origin used by many open
source projects (the [Developer Certificate of Origin](https://developercertificate.org/)), without
requiring a signed CLA or commit sign-off for a project this size.

## Response times

This is maintained solo, not by a team — review and response times will vary. That's not a
reflection of how welcome the contribution is.

## Acknowledgment

This project's mesh design was informed by comparing approaches with
[bitchat](https://github.com/permissionlesstech/bitchat) (Jack Dorsey / permissionlesstech) —
several routing ideas (forward-first flooding, TTL, fanout) are adapted from its public design; see
`V2_eli15.txt` and `docs/WHITEPAPER.md` for where specifically. This project is independent and not
affiliated with or endorsed by bitchat or its authors.
