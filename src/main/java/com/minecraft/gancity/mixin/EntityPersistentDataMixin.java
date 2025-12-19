package com.minecraft.gancity.mixin;

import com.minecraft.gancity.util.PersistentDataHolder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityPersistentDataMixin implements PersistentDataHolder {

    @Unique
    private static final String ADAPTIVEMOBAI_PERSISTENT_NBT_KEY = "AdaptiveMobAI_PersistentData";

    @Unique
    private CompoundTag adaptivemobai$persistentData;

    @Override
    public CompoundTag adaptivemobai$getPersistentData() {
        if (this.adaptivemobai$persistentData == null) {
            this.adaptivemobai$persistentData = new CompoundTag();
        }
        return this.adaptivemobai$persistentData;
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void adaptivemobai$writePersistentData(CompoundTag nbt, CallbackInfo ci) {
        nbt.put(ADAPTIVEMOBAI_PERSISTENT_NBT_KEY, this.adaptivemobai$getPersistentData().copy());
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void adaptivemobai$readPersistentData(CompoundTag nbt, CallbackInfo ci) {
        if (nbt.contains(ADAPTIVEMOBAI_PERSISTENT_NBT_KEY, Tag.TAG_COMPOUND)) {
            this.adaptivemobai$persistentData = nbt.getCompound(ADAPTIVEMOBAI_PERSISTENT_NBT_KEY).copy();
        }
    }
}
