package dev.starmine.mixin.client;

import dev.starmine.client.BlockFog;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The command view's cutaway lights what it opens ({@link BlockFog#cutOpen}): a face whose light is read in a cut
 * block (the cut surface, a tunnel's walls under it) is lit as under an open sky, not black as deep in the rock.
 * Sodium has its own light pipeline: there the cut surface keeps the world's light.
 */
@Mixin(LevelRenderer.class)
abstract class CutawayLightMixin {
	@Inject(method = "getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I",
		at = @At("HEAD"), cancellable = true)
	private static void starmine$lightTheCut(BlockAndTintGetter level, BlockState state, BlockPos pos, CallbackInfoReturnable<Integer> result) {
		if (BlockFog.cutOpen(pos)) result.setReturnValue(LightTexture.FULL_BRIGHT);
	}
}
