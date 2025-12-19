# Fabric Migration Summary (Forge → Fabric)

**Date:** 2025-12-18  
**Project:** MCA AI Enhanced / Adaptive Mob AI (Fabric fork)

## Outcome
- The mod is now a **Fabric** mod (Fabric Loom build, Fabric Loader + Fabric API dependencies, Fabric entrypoint, and Mixin config wired via `fabric.mod.json`).
- **Forge-specific tracked artifacts and references** were removed or migrated (docs + comments updated to Fabric wording).
- The build/tooling was stabilized for Fabric Loom by:
  - Upgrading the Gradle wrapper distribution to **Gradle 8.6**.
  - Removing the `org.gradle.toolchains.foojay-resolver-convention` plugin from `settings.gradle` to avoid a Gson classpath conflict that prevented Fabric Loom from parsing Minecraft metadata.

## Key Fixes
### 1) Gradle / Loom compatibility
- Updated `gradle/wrapper/gradle-wrapper.properties` to use `gradle-8.6-bin.zip`.
- Updated `settings.gradle` to remove `org.gradle.toolchains.foojay-resolver-convention`.

This resolves the prior failure:
- `Failed to setup Minecraft … IllegalAccessException … Gson 2.9.1 … ManifestVersion.latest`

### 2) Dependency resolution
- After the wrapper update, Gradle can refresh dependencies successfully, so Fabric API types resolve correctly (`net.fabricmc.*`).

## Validation
- Workspace-wide IDE error scan: **no errors detected** after the fixes.
- CI build: **triggered by pushes to `main`** (GitHub Actions will compile).

## Notes / Constraints
- Per fork rules, **no changes were made under `cloudflare-worker/`**.
- GitHub may warn about large files under `cloudflare-worker/node_modules/` if those are tracked; this fork treats that directory as read-only.

## Commits
- Documentation/cleanup & Fabric alignment commit(s).
- Build-fix commit: Gradle wrapper → 8.6 and toolchain resolver removal.
