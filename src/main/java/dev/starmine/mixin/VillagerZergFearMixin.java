package dev.starmine.mixin;

import dev.starmine.entity.UnitKind;
import dev.starmine.entity.StarUnit;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.sensing.VillagerHostilesSensor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Villagers see zerg as a threat (owner: they hide from them): within 12 blocks, a drone within 6.
 * Their brain then panics and sends them indoors as it does for zombies.
 */
@Mixin(VillagerHostilesSensor.class)
abstract class VillagerZergFearMixin {
	@Inject(method = "isMatchingEntity", at = @At("HEAD"), cancellable = true)
	private void starmine$fearZerg(LivingEntity villager, LivingEntity other, CallbackInfoReturnable<Boolean> result) {
		if (other instanceof StarUnit zerg && zerg.kind.race == UnitKind.Race.ZERG && !zerg.kind.structure()) {
			double reach = zerg.kind == UnitKind.DRONE ? 6 : 12;
			result.setReturnValue(villager.distanceToSqr(zerg) <= reach * reach);
		}
	}
}
