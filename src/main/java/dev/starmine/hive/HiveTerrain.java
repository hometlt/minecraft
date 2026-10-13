package dev.starmine.hive;

import dev.starmine.block.StarMineBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Foundations of zerg buildings (owner): 2 layers of unbreakable thick creep under the building's
 * footprint and a 1-block ring around it; when the building dies the thick creep becomes gravel.
 * Bedrock and blocks with block entities are left alone.
 */
public final class HiveTerrain {
	/** A hatchery's footprint half-size (its 6-block base, about 8 blocks of model) plus the ring. */
	public static final int HATCHERY_FOUNDATION = 4;

	private HiveTerrain() {
	}

	/** Lays the foundation under a building standing at `feet` (its ground cell). */
	public static void lay(ServerLevel level, BlockPos feet, int halfSize) {
		for (int dx = -halfSize; dx <= halfSize; dx++)
			for (int dz = -halfSize; dz <= halfSize; dz++)
				for (int dy = 1; dy <= 2; dy++) {
					BlockPos pos = feet.offset(dx, -dy, dz);
					BlockState state = level.getBlockState(pos);
					if (state.is(Blocks.BEDROCK) || level.getBlockEntity(pos) != null) continue;
					level.setBlockAndUpdate(pos, StarMineBlocks.THICK_CREEP.defaultBlockState());
				}
	}

	/** The building is gone: its thick creep turns to gravel (which then falls where it can). */
	public static void ruin(ServerLevel level, BlockPos feet, int halfSize) {
		for (int dx = -halfSize; dx <= halfSize; dx++)
			for (int dz = -halfSize; dz <= halfSize; dz++)
				for (int dy = 1; dy <= 2; dy++) {
					BlockPos pos = feet.offset(dx, -dy, dz);
					if (level.getBlockState(pos).is(StarMineBlocks.THICK_CREEP)) level.setBlockAndUpdate(pos, Blocks.GRAVEL.defaultBlockState());
				}
	}
}
