package dev.starmine.hive;

import dev.starmine.block.StarMineBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import java.util.List;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Foundations of zerg buildings (owner): 2 layers of unbreakable thick creep under the building's
 * footprint and a 1-block ring around it; when the building dies the thick creep becomes gravel.
 * Bedrock and blocks with block entities are left alone.
 */
public final class HiveTerrain {
	/** The hatchery's foundation half-size: half its 15-block footprint (5 cells of 3 blocks) and a ring. */
	public static final int HATCHERY_FOUNDATION = 9;

	/** Foundation half-size of a building: its base plus the 1-block ring. */
	public static int foundation(dev.starmine.entity.UnitKind kind) {
		return kind == dev.starmine.entity.UnitKind.HATCHERY ? HATCHERY_FOUNDATION : (int) Math.ceil(kind.width / 2) + 1;
	}

	/**
	 * Levels a building site (owner): within the footprint and up to the building's height, earth,
	 * stone, sand, gravel and plants are cleared away. Trees, fences, walls, anything harder than stone
	 * or with a block entity forbid the site: false, and nothing is changed. With `apply` false it only
	 * checks (the command view uses that on the client for the ghost of a building being placed).
	 */
	public static boolean prepare(net.minecraft.world.level.Level level, BlockPos feet, int halfSize, int height, boolean apply) {
		List<BlockPos> clear = new java.util.ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-halfSize, 0, -halfSize), feet.offset(halfSize, height, halfSize))) {
			BlockState state = level.getBlockState(pos);
			if (state.isAir()) continue;
			if (!levelable(level, pos, state)) return false;
			clear.add(pos.immutable());
		}
		// the ground under the site must exist: no building over a chasm
		int holes = 0;
		for (int dx = -halfSize; dx <= halfSize; dx++)
			for (int dz = -halfSize; dz <= halfSize; dz++)
				if (level.getBlockState(feet.offset(dx, -3, dz)).isAir()) holes++;
		if (holes > (2 * halfSize + 1) * (2 * halfSize + 1) / 3) return false;
		if (apply) for (BlockPos pos : clear) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		return true;
	}

	private static boolean levelable(net.minecraft.world.level.Level level, BlockPos pos, BlockState state) {
		if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS)
			|| state.is(BlockTags.FENCE_GATES) || level.getBlockEntity(pos) != null || !state.getFluidState().isEmpty()) return false;
		if (state.canBeReplaced() || StarMineBlocks.isCreep(state)) return true;
		if (state.is(BlockTags.DIRT) || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL)
			|| state.is(BlockTags.SNOW) || state.is(Blocks.CLAY) || state.is(Blocks.COBBLESTONE) || state.is(BlockTags.FLOWERS)) return true;
		return false;
	}

	private HiveTerrain() {
	}

	/**
	 * Lays the foundation under a building standing at `feet` (its ground cell); a vespene vein and its
	 * outlet stay (a spine crawler put down by a vent buried it under thick creep, and the hive never got
	 * its extractor).
	 */
	public static void lay(ServerLevel level, BlockPos feet, int halfSize) {
		for (int dx = -halfSize; dx <= halfSize; dx++)
			for (int dz = -halfSize; dz <= halfSize; dz++)
				for (int dy = 1; dy <= 2; dy++) {
					BlockPos pos = feet.offset(dx, -dy, dz);
					BlockState state = level.getBlockState(pos);
					if (state.is(Blocks.BEDROCK) || level.getBlockEntity(pos) != null || state.getBlock() instanceof dev.starmine.vespene.VespeneBlock
						|| dev.starmine.vespene.Vespene.isOutlet(state)) continue;
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
