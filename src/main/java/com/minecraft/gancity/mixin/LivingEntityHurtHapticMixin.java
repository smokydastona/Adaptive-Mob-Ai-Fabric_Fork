package com.minecraft.gancity.mixin;

import com.minecraft.gancity.GANCityMod;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityHurtHapticMixin {
    @Inject(method = "hurt", at = @At("TAIL"))
    private void adaptivemobai$hapticOnHurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (cir == null || !Boolean.TRUE.equals(cir.getReturnValue())) {
            return;
        }

        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof Mob mob)) {
            return;
        }

        GANCityMod.tryInfectionHiveMindDamageHaptic(mob, amount);
    }
}