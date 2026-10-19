package dev.starmine.mixin;

import dev.starmine.gear.RaceTools;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Owner 19.10: a terran shield held takes blasts better ({@link RaceTools#blast}). */
@Mixin(LivingEntity.class)
abstract class BlastShieldMixin {
	@ModifyVariable(method = "hurt", at = @At("HEAD"), argsOnly = true)
	private float starmine$blast(float amount, DamageSource source) {
		return RaceTools.blast((LivingEntity) (Object) this, source, amount);
	}
}
