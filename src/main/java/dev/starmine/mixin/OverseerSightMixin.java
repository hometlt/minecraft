package dev.starmine.mixin;

import dev.starmine.entity.ZergMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Overseer detection: a zerg that has an overseer near sees an invisible player or creature fully
 * (vanilla shrinks the targeting range of invisible ones to a few percent).
 */
@Mixin(LivingEntity.class)
abstract class OverseerSightMixin {
	@Inject(method = "getVisibilityPercent", at = @At("HEAD"), cancellable = true)
	private void starmine$overseerSight(Entity looker, CallbackInfoReturnable<Double> result) {
		if (looker instanceof ZergMob zerg && zerg.seesInvisible()) result.setReturnValue(1.0);
	}
}
