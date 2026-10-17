package dev.starmine.mixin;

import dev.starmine.colony.CommanderEye;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The world is loaded and sent round a player's command view, not their body, while it is open
 * ({@link CommanderEye}): the chunk map takes the eye's section for the player's tickets and the chunks it sends.
 */
@Mixin(ChunkMap.class)
abstract class CommanderEyeMixin {
	@Redirect(method = {"move", "updatePlayerPos", "updatePlayerStatus"},
		at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;of(Lnet/minecraft/world/level/entity/EntityAccess;)Lnet/minecraft/core/SectionPos;"))
	private SectionPos starmine$eyeSection(EntityAccess entity) {
		return entity instanceof ServerPlayer player ? CommanderEye.section(player) : SectionPos.of(entity);
	}

	@Redirect(method = "updateChunkTracking", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;chunkPosition()Lnet/minecraft/world/level/ChunkPos;"))
	private ChunkPos starmine$eyeChunk(ServerPlayer player) {
		return CommanderEye.section(player).chunk();
	}
}
