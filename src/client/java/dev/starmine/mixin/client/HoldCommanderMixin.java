package dev.starmine.mixin.client;

import dev.starmine.client.CommandView;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Owner 18.10: while the player holds the command panel's view, their body stays where it stood — only the camera
 * moves. No gravity, no push from the units round it, no knockback, no fall into a world unloaded round it (the
 * world is sent round the camera, {@link dev.starmine.colony.CommanderEye}); unless it walks as the hero ({@link CommandView#bodyHeld}).
 */
@Mixin(Player.class)
abstract class HoldCommanderMixin {
	@Inject(method = "travel", at = @At("HEAD"), cancellable = true)
	private void starmine$holdBody(Vec3 travel, CallbackInfo ci) {
		if (!((Object) this instanceof LocalPlayer player) || !CommandView.bodyHeld()) return;
		player.setDeltaMovement(Vec3.ZERO);
		player.fallDistance = 0;
		ci.cancel();
	}
}
