package dev.starmine.mixin.client;

import dev.starmine.client.BlockFog;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fog of war for blocks in the command view: the chunk mesher reads blocks nobody has seen as air ({@link BlockFog}). */
@Mixin(RenderChunkRegion.class)
abstract class HideUnseenBlocksMixin {
	@Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
	private void starmine$hideBlock(BlockPos pos, CallbackInfoReturnable<BlockState> result) {
		if (BlockFog.hidden(pos)) result.setReturnValue(Blocks.AIR.defaultBlockState());
	}

	@Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
	private void starmine$hideFluid(BlockPos pos, CallbackInfoReturnable<FluidState> result) {
		if (BlockFog.hidden(pos)) result.setReturnValue(Fluids.EMPTY.defaultFluidState());
	}
}
