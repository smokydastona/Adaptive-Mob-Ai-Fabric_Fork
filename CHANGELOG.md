# Changelog

## 1.0.11

- Parity (Forge): Allow hostile mobs to spawn with and use bows/crossbows/tridents (universal weapon loadouts).
- Parity (Forge): Ensure the generic ranged-weapon goal is injected even if ML is unavailable (reduced-feature/safe-mode behavior).
- Parity (Forge): Batch federation uploads per mobType to avoid single-action uploads locking a round; remove synthetic seeding uploads.
- CI: trigger build to validate auto version bump.
- Rename output jar to `Adaptive-Mob-Ai-Fabric-<version>.jar` to avoid mixing it up with Forge/legacy builds.
- Update docs to reference the Fabric jar name.
- Add Fabric dependencies as `compileOnly` for better IDE/static analysis resolution.
- Fix federated download parsing to tolerate backend schema variants (prevents "Missing tactics field" rejections).
- Fix chat hook registration to avoid reflective access / event registration crashes on some Fabric environments.
- Harden federated tactic handling to accept list/map shapes (prevents `LinkedTreeMap` cast failures).
- Make persistent entity data mixin injection more tolerant across mapping/method-shape differences.
- Validation: full `latest.log` scan shows 0 `ERROR`/`FATAL`/`Exception`/`Stacktrace` matches; remaining `WARN`s are non-mod noise.

## 1.0.0

- Initial Fabric release.

