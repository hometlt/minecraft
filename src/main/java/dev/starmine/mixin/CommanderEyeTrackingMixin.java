package dev.starmine.mixin;

import dev.starmine.colony.CommanderEye;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Creatures are sent to a player whose command view is near them, wherever the body is ({@link CommanderEye}). */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
abstract class CommanderEyeTrackingMixin {
	@Redirect(method = "updatePlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;position()Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 starmine$eyePosition(ServerPlayer player) {
		return CommanderEye.centre(player);
	}
}
