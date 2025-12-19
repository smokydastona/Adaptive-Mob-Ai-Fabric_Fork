# Adaptive Mob AI (Fabric) - Installation Guide

## Requirements

- Minecraft 1.20.1
- Fabric Loader (1.20.1)
- Fabric API (1.20.1)
- Java 17

## Install

1. Download the Fabric build jar from Releases:
   - `Adaptive-Mob-Ai-Fabric-<version>.jar`
2. Put it in your instance/server `mods/` folder.
3. Ensure Fabric API is also installed (example: `fabric-api-0.92.x+1.20.1.jar`).
4. Start the game/server.

This mod is server-side only (clients do not need it).

## Optional: MCA Reborn

MCA Reborn is a soft dependency for villager dialogue features.
If you don’t have MCA installed, the mod still runs and mob AI features still work.

## Uninstall

Remove `Adaptive-Mob-Ai-Fabric-*.jar` from `mods/`. You can also delete `config/adaptivemobai-common.toml` if desired.
