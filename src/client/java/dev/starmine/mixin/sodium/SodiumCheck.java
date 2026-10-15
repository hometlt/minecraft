package dev.starmine.mixin.sodium;

import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Never loaded: it only makes the build fail if Sodium's slice stops reading blocks by coordinates, the
 * method {@link SodiumHideUnseenBlocksMixin} hooks (its target is not checked when the mod compiles).
 */
final class SodiumCheck {
	private SodiumCheck() {
	}

	@SuppressWarnings("unused")
	private static BlockState read(LevelSlice slice) {
		return slice.getBlockState(0, 0, 0);
	}
}
