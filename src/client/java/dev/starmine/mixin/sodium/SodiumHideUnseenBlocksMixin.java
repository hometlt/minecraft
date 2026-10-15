package dev.starmine.mixin.sodium;

import dev.starmine.client.BlockFog;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The fog of blocks with Sodium (and Iris, whose shader packs draw terrain through Sodium's meshes, the
 * shadow pass too): Sodium meshes chunks from its own copy of them, {@link LevelSlice}, not the game's
 * chunk region, so blocks nobody has seen are read as air there as well ({@link BlockFog}). Every read
 * of a block or fluid in the slice comes through this method. Applied only when Sodium is installed
 * ({@link SodiumMixins}).
 */
@Mixin(value = LevelSlice.class, remap = false)
abstract class SodiumHideUnseenBlocksMixin {
	// Sodium is not remapped with the game's mappings here (remap = false): its method takes the block state
	// class by the development name in a development run and by the intermediary name (class_2680) in the
	// released game, so both are named; one of them is found
	@Inject(method = {"getBlockState(III)Lnet/minecraft/world/level/block/state/BlockState;", "getBlockState(III)Lnet/minecraft/class_2680;"},
		at = @At("HEAD"), cancellable = true, remap = false)
	private void starmine$hideBlock(int x, int y, int z, CallbackInfoReturnable<BlockState> result) {
		if (BlockFog.hidden(x, y, z)) result.setReturnValue(Blocks.AIR.defaultBlockState());
	}
}
