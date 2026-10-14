package dev.starmine.mixin;

import dev.starmine.entity.StarUnit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Who sees whom (vanilla targeting range is scaled by this): a cloaked StarMine unit is seen only by its
 * side and by sides with a detector near it ({@link dev.starmine.ability.Cloak#seen}); a zerg that has an
 * overseer near sees an invisible player or creature fully (vanilla shrinks the targeting range of
 * invisible ones to a few percent).
 */
@Mixin(LivingEntity.class)
abstract class OverseerSightMixin {
	@Inject(method = "getVisibilityPercent", at = @At("HEAD"), cancellable = true)
	private void starmine$overseerSight(Entity looker, CallbackInfoReturnable<Double> result) {
		if ((Object) this instanceof StarUnit unit && unit.cloaked() && !dev.starmine.ability.Cloak.seen(unit, looker)) {
			result.setReturnValue(0.0);
			return;
		}
		if (looker instanceof StarUnit zerg && zerg.seesInvisible()) result.setReturnValue(1.0);
	}
}
