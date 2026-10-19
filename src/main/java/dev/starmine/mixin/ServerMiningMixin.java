package dev.starmine.mixin;

import dev.starmine.block.BlockWear;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Owner 19.10: a block a player began to mine keeps what was done ({@link BlockWear#mined}): left, its work is saved on it;
 * mined again, the work starts from there (the start of the mining set back by the ticks it was worth).
 */
@Mixin(ServerPlayerGameMode.class)
abstract class ServerMiningMixin {
	@Shadow protected ServerLevel level;
	@Shadow @Final protected ServerPlayer player;
	@Shadow private boolean isDestroyingBlock;
	@Shadow private int destroyProgressStart;
	@Shadow private BlockPos destroyPos;
	@Shadow private int gameTicks;

	@Inject(method = "handleBlockBreakAction", at = @At("HEAD"))
	private void starmine$leave(BlockPos pos, ServerboundPlayerActionPacket.Action action, Direction face, int maxHeight, int sequence, CallbackInfo info) {
		if (action != ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK || !isDestroyingBlock || !pos.equals(destroyPos)) return;
		BlockState state = level.getBlockState(pos);
		BlockWear.mined(level, pos, state.getDestroyProgress(player, level, pos) * (gameTicks - destroyProgressStart + 1));
	}

	@Inject(method = "handleBlockBreakAction", at = @At("RETURN"))
	private void starmine$resume(BlockPos pos, ServerboundPlayerActionPacket.Action action, Direction face, int maxHeight, int sequence, CallbackInfo info) {
		if (action != ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK || !isDestroyingBlock || !pos.equals(destroyPos)) return;
		float worn = BlockWear.progress(level, pos), perTick = level.getBlockState(pos).getDestroyProgress(player, level, pos);
		if (worn > 0 && perTick > 0) destroyProgressStart -= (int) (worn / perTick);
	}
}
