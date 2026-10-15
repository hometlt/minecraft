package dev.starmine.mixin;

import dev.starmine.colony.BaseLogic;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Iron golems leave a player's units of any race alone (owner 14.10; wild units are monsters to them) — until terrans of that base hurt a villager or a golem;
 * then the village counts the base as dangerous for a while ({@link BaseLogic#villageHostile}). A changeling that looks like
 * a villager passes for one (owner, dev.starmine.ability.Changeling).
 */
@Mixin(LivingEntity.class)
abstract class GolemTerranPeaceMixin {
	@Inject(method = "canAttack(Lnet/minecraft/world/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true)
	private void starmine$spareTerrans(LivingEntity target, CallbackInfoReturnable<Boolean> result) {
		if ((Object) this instanceof IronGolem && target instanceof StarUnit unit && unit.owner().isPresent()
			&& !BaseLogic.villageHostile(unit)) result.setReturnValue(false);
		if ((Object) this instanceof IronGolem && target instanceof StarUnit changeling && changeling.kind == UnitKind.CHANGELING
			&& dev.starmine.ability.Changeling.VILLAGER.equals(changeling.disguise())) result.setReturnValue(false);
	}
}
