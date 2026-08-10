## What this changes, and why

<!-- The "why" matters more than the "what" in this codebase's own review culture — see
     CONTRIBUTING.md. If this fixes a bug, what was actually going wrong, not just the symptom. -->

## Testing

- [ ] `./gradlew test detekt` passes locally
- [ ] `./gradlew lint` has no new warnings
- [ ] New/changed logic has a Tier 1 unit test (see `app/src/test/` for shape/density)

**Device testing** (only if this touches BLE/GATT/background service/permissions behavior):
- [ ] Tested on real hardware — describe phones/Android versions and what you observed
- [ ] Not device-tested — compile/unit-test-verified only (this is fine, just say so plainly;
      see `CONTRIBUTING.md` for why this project treats that distinction as load-bearing)

## Version bump

<!-- Does this need a versionCode/versionName bump in app/build.gradle.kts, per this project's
     own convention (one bump per meaningful change)? If yes, has it been done? -->

- [ ] N/A — docs/CI/test-only change
- [ ] Yes, bumped

## Anything else a reviewer should know

<!-- Tradeoffs you considered and rejected, known follow-up work, anything you're specifically
     unsure about. -->
