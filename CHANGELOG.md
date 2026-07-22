# Changelog

## [0.1.0] — Initial public release

Android BLE mesh app for offline group coordination: a forward-up radar to navigate to your
group, SOS broadcast with hop-count distance, and encrypted evidence-photo sharing — all
phone-to-phone over Bluetooth LE, with no servers, no accounts, and no cellular/Wi-Fi dependency.

Full architecture, protocol design, permissions rationale, and known limitations are documented
in [`README.md`](README.md). Testing approach is in [`TESTING.md`](TESTING.md); the manual
device test plan is in [`test_rubric.md`](test_rubric.md).

This release has been tested on a small number of physical devices (2-3 phones). It has **not**
been validated at the app's actual target scale (~10 people in a dense area) — see
`README.md`'s Known Limitations before relying on it for anything real.
