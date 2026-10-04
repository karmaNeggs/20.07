# iOS — not built yet

Reserved for the iOS app. Nothing here yet.

The Android app is in [`../android/`](../android/). Everything shared — the product docs,
`../docs/DECISIONS.md`, `../PLAN-v2.md`, `../CHANGELOG.md`, `../TESTING.md`, `../SECURITY.md` —
lives at the repo root and applies to both platforms, so an iOS build starts here rather than in a
separate repo.

Worth knowing before starting: the mesh design is built on Bluetooth LE with a GATT
client/server on each phone. iOS restricts background BLE advertising far more than Android does,
so the transport layer is the part that needs real design work, not a port. See `../PLAN-v2.md`
Part 7 and `../docs/DECISIONS.md`.
