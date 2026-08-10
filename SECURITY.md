# Security Policy

20.07 handles real GPS positions, SOS alerts, and photo evidence for people who may be relying on
it in an actual emergency. Security reports are taken seriously even though this is a solo-
maintained project with no formal SLA.

## Supported versions

This project is pre-1.0 (`-dev` versioning) and does not maintain parallel release branches — only
the **latest published release** is supported. If you're on an older build, please reproduce
against the [latest release](https://github.com/karmaNeggs/20.07/releases/latest) before reporting.

## Reporting a vulnerability

**Please do not open a public issue for a vulnerability that's actively exploitable against real
users** (e.g. a way to decrypt another group's traffic, deanonymize a device, or remotely crash/
compromise the app from a malicious BLE peer).

Instead, use GitHub's private reporting:

1. Go to this repo's **Security** tab → **Report a vulnerability**.
2. Or, directly: <https://github.com/karmaNeggs/20.07/security/advisories/new>.

This opens a private draft advisory visible only to you and the maintainer — no email address
needed, and nothing is public until a fix is ready (or you choose to disclose earlier).

For anything **not** actively exploitable — a design tradeoff you think is wrong, a theoretical
weakness with no practical attack, or something already listed in
[README's Known Limitations](README.md#known-limitations-honest-ones) — a regular public issue is
fine and preferred, since it's useful for other people evaluating the project too.

## Scope

**In scope**: anything that breaks a claim in [README's Security model](README.md#security-model)
— confidentiality/authenticity of sealed content, the blind-relay privacy property, identity
correlation across groups or over time, key storage, or a memory-safety/crash bug reachable from
an untrusted BLE peer (this app talks to devices it has no reason to trust by design — a crash or
worse from malformed input on that path is a real security bug, not just a stability one).

**Already known, please check first**: [README's Known Limitations](README.md#known-limitations-honest-ones)
documents several real, currently-unresolved gaps honestly (no forward secrecy, the disguise
feature's actual scope, a few narrow connection-lifecycle edge cases, and more) — these don't need
a fresh report unless you've found a way to actually exploit one in practice, which *would* be
useful to know.

**Out of scope**: the release build being unsigned (documented, intentional at this stage),
anything requiring physical access to an unlocked, unencrypted phone (device-level security is
Android's job, not this app's), and social-engineering the group's invite code/QR out of a member
(key exchange is deliberately out-of-band and trust-based — see Security model).

## What to include

- Which build (`versionName`, from Settings or the APK filename).
- Steps to reproduce, or a proof-of-concept if you have one.
- What you'd expect to happen instead, and why the current behavior is a security issue
  specifically (not just a bug) — helps triage severity quickly.

## Response

No formal SLA (solo-maintained project), but security reports get priority over ordinary feature
work. You'll get an acknowledgment via the GitHub advisory thread; a real fix's timeline depends on
severity and whether it needs hardware confirmation before shipping (see `CONTRIBUTING.md` for why
that distinction matters in this codebase specifically).
