package dev.starmine.mixin.client;

import dev.starmine.client.AmunSkyClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Owner 19.10: the sky reddens when Amun wakes ({@link AmunSkyClient}). */
@Mixin(ClientLevel.class)
abstract class AmunSkyMixin {
	@Inject(method = "getSkyColor", at = @At("RETURN"), cancellable = true)
	private void starmine$redSky(Vec3 pos, float partialTick, CallbackInfoReturnable<Vec3> result) {
		if (AmunSkyClient.strength(partialTick) > 0) result.setReturnValue(AmunSkyClient.tint(result.getReturnValue(), partialTick));
	}
}
