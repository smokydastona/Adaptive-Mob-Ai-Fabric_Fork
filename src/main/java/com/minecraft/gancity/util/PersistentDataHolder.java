package com.minecraft.gancity.util;

import net.minecraft.nbt.CompoundTag;

/**
 * Fabric-compatible replacement for Forge's Entity#getPersistentData().
 *
 * Implemented via mixin on all entities so mod code can store custom NBT
 * that persists across saves.
 */
public interface PersistentDataHolder {
    CompoundTag adaptivemobai$getPersistentData();
}
