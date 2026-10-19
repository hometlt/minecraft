package dev.starmine.mixin;

import dev.starmine.block.BlockWear;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Owner 19.10: a chunk sent to a player carries the damage its blocks keep ({@link BlockWear#sendChunk}). */
@Mixin(PlayerChunkSender.class)
abstract class ChunkWearMixin {
	@Inject(method = "sendChunk", at = @At("TAIL"))
	private static void starmine$wear(ServerGamePacketListenerImpl connection, ServerLevel level, LevelChunk chunk, CallbackInfo info) {
		BlockWear.sendChunk(level, connection.player, chunk.getPos());
	}
}
