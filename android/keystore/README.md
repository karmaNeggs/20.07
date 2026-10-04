# keystore/

Holds `release.jks`, this project's real Android release-signing key (generated 2026-08-10, valid
until 2053). The `.jks` file itself is gitignored — only this note is committed.

**This key must never be lost.** Every future update to a release built with it (including any
Google Play submission using it, or Play App Signing's upload-key flow) depends on the exact same
key existing. Losing it means losing the ability to ever publish an update under this app's
existing signature again — a new key would be a new, unrelated app to users' devices and to Play
Console.

**Back this up now, outside git, in at least one place you control** (a password manager that
supports file attachments, an encrypted drive, etc.) — alongside `keystore.properties` at the repo
root, which holds the store/key passwords and alias needed to actually use this file.
