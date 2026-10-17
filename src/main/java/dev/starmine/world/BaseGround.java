package dev.starmine.world;

import dev.starmine.entity.UnitKind;
import dev.starmine.hive.HiveResources;
import dev.starmine.hive.HiveStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The ground of a base (owner 17.10).
 *
 * - Under a player's base: {@link #DEPTH} blocks of plain stone with nothing of worth in them (no ores, no caves),
 *   and a top of concrete in the race's colour that workers do not dig ({@link #protectedTop}): the base keeps its
 *   look however hard its workers mine.
 * - Under a generated base: the land is filled in under its plaza (it never hangs over a cave or a drop), and round
 *   it the land slopes smoothly down or up to the plaza's level ({@link #blend}).
 */
public final class BaseGround {
	/** How deep the plain stone goes under a player's base. */
	public static final int DEPTH = 20;

	private BaseGround() {
	}

	/** The concrete top of a player's base, by race. */
	public static BlockState top(UnitKind.Race race) {
		return switch (race) {
			case ZERG -> Blocks.BLACK_CONCRETE.defaultBlockState();
			case TERRAN -> Blocks.GRAY_CONCRETE.defaultBlockState();
			case PROTOSS -> Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
		};
	}

	/** Whether workers leave a block alone: concrete (a base's top, a generated base's plaza). */
	public static boolean protectedTop(BlockState state) {
		return state.is(Blocks.BLACK_CONCRETE) || state.is(Blocks.GRAY_CONCRETE) || state.is(Blocks.LIGHT_GRAY_CONCRETE)
			|| state.is(Blocks.WHITE_CONCRETE) || state.is(Blocks.YELLOW_CONCRETE);
	}

	/** Whether a block may be overwritten here (not unbreakable, no block entity). */
	private static boolean free(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.getDestroySpeed(level, pos) >= 0 && level.getBlockEntity(pos) == null
			&& !(state.getBlock() instanceof dev.starmine.vespene.VespeneBlock) // a vein, its outlets: the base's gas, kept
			&& !(state.getBlock() instanceof dev.starmine.vespene.VespeneCanisterBlock);
	}

	/**
	 * A player's base founded at `feet` (its main building's feet): within `radius` the top under the feet becomes
	 * concrete, and the {@link #DEPTH} blocks under it plain stone wherever there was ore or a cave (water is left be).
	 */
	public static void foundation(ServerLevel level, BlockPos feet, int radius, UnitKind.Race race) {
		BlockState top = top(race), stone = Blocks.STONE.defaultBlockState();
		for (int dx = -radius; dx <= radius; dx++)
			for (int dz = -radius; dz <= radius; dz++) {
				if (dx * dx + dz * dz > (radius + 0.5) * (radius + 0.5)) continue;
				BlockPos column = feet.offset(dx, 0, dz);
				BlockPos under = column.below();
				BlockState ground = level.getBlockState(under);
				boolean solid = !ground.isAir() && ground.getFluidState().isEmpty();
				if (solid && free(level, under)) level.setBlock(under, top, Block.UPDATE_CLIENTS); // water and drops round it stay
				for (int down = 2; down <= DEPTH + 1; down++) {
					BlockPos at = column.below(down);
					if (at.getY() <= level.getMinBuildHeight() + 1) break;
					BlockState state = level.getBlockState(at);
					if (!state.getFluidState().isEmpty()) break; // a lake or river by the base: not filled in
					boolean worth = HiveResources.isOre(state) || solid && state.isAir(); // a cave under the ground, not the open air
					if (!state.isAir()) solid = true;
					if (worth && free(level, at)) level.setBlock(at, stone, Block.UPDATE_CLIENTS);
				}
			}
	}

	/** Fills the land under a generated base's plaza (radius `half` round `feet`) down to {@link #DEPTH}: no cave, no drop under it. */
	public static void fillUnder(ServerLevel level, BlockPos feet, int half) {
		BlockState stone = Blocks.STONE.defaultBlockState(), dirt = Blocks.DIRT.defaultBlockState();
		for (int dx = -half; dx <= half; dx++)
			for (int dz = -half; dz <= half; dz++) {
				if (dx * dx + dz * dz > (half + 0.5) * (half + 0.5)) continue;
				for (int down = 1; down <= DEPTH; down++) {
					BlockPos at = feet.offset(dx, -down, dz);
					if (at.getY() <= level.getMinBuildHeight() + 1) break;
					BlockState state = level.getBlockState(at);
					if ((state.isAir() || !state.getFluidState().isEmpty() || state.canBeReplaced()) && free(level, at))
						level.setBlock(at, down <= 3 ? dirt : stone, Block.UPDATE_CLIENTS);
				}
			}
	}

	/**
	 * The land round a generated base slopes to its plaza: between `inner` and `outer` blocks from `feet` each column
	 * is cut down or built up towards the plaza's level, the more the nearer the plaza, topped with grass.
	 */
	public static void blend(ServerLevel level, BlockPos feet, int inner, int outer) {
		BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState(), dirt = Blocks.DIRT.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
		for (int dx = -outer; dx <= outer; dx++)
			for (int dz = -outer; dz <= outer; dz++) {
				double r = Math.sqrt(dx * dx + dz * dz);
				if (r <= inner || r > outer) continue;
				int x = feet.getX() + dx, z = feet.getZ() + dz;
				int surface = HiveStructure.ground(level, x, z);
				double t = (r - inner) / (outer - inner);
				t = t * t * (3 - 2 * t); // smooth at both ends
				int target = (int) Math.round(Mth.lerp(t, feet.getY(), surface));
				if (surface > target) { // a hill: cut down to the slope
					for (int y = target; y <= surface + 6; y++) {
						BlockPos at = new BlockPos(x, y, z);
						BlockState state = level.getBlockState(at);
						if (!state.isAir() && free(level, at)) level.setBlock(at, air, Block.UPDATE_CLIENTS);
					}
				} else if (surface < target) { // a hollow: built up to the slope
					for (int y = surface; y < target; y++) {
						BlockPos at = new BlockPos(x, y, z);
						if (free(level, at)) level.setBlock(at, dirt, Block.UPDATE_CLIENTS);
					}
				}
				BlockPos top = new BlockPos(x, target - 1, z);
				if (target != surface && free(level, top) && level.getFluidState(top.above()).isEmpty()) level.setBlock(top, grass, Block.UPDATE_CLIENTS);
			}
	}
}
