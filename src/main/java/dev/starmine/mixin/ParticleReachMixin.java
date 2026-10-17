package dev.starmine.mixin;

import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Particles the server sends reach farther (owner 17.10: shots were not seen in the command view): to every player
 * within {@link #REACH} blocks instead of 32, and the client shows them however far its camera is (the command
 * view's camera hangs high over the fight, out of the vanilla 32 blocks).
 */
@Mixin(ServerLevel.class)
abstract class ParticleReachMixin {
	private static final double REACH = 160;

	@ModifyArg(method = "sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/game/ClientboundLevelParticlesPacket;<init>(Lnet/minecraft/core/particles/ParticleOptions;ZDDDFFFFI)V"),
		index = 1)
	private boolean starmine$shownAtAnyDistance(boolean overrideLimiter) {
		return true;
	}

	@ModifyArg(method = "sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;sendParticles(Lnet/minecraft/server/level/ServerPlayer;ZDDDLnet/minecraft/network/protocol/Packet;)Z"),
		index = 1)
	private boolean starmine$sentFarther(ServerPlayer player, boolean longDistance, double x, double y, double z, Packet<?> packet) {
		return longDistance || player.distanceToSqr(x, y, z) < REACH * REACH;
	}
}
