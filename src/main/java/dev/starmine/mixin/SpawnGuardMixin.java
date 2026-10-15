package dev.starmine.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No monsters spawn near creep tumors and buildings ({@link dev.starmine.world.SpawnGuard}). */
@Mixin(NaturalSpawner.class)
abstract class SpawnGuardMixin {
	@Inject(method = "isValidPositionForMob", at = @At("HEAD"), cancellable = true)
	private static void starmine$guard(ServerLevel level, Mob mob, double distance, CallbackInfoReturnable<Boolean> result) {
		if (dev.starmine.world.SpawnGuard.refuses(level, mob, mob.blockPosition())) result.setReturnValue(false);
	}
}
