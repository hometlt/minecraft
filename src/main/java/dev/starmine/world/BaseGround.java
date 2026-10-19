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

	/** The stone of a dimension's ground (owner 17.10: bases in the Nether and the End): netherrack, end stone, stone. */
	static BlockState stone(ServerLevel level) {
		if (level.dimension() == net.minecraft.world.level.Level.NETHER) return Blocks.NETHERRACK.defaultBlockState();
		if (level.dimension() == net.minecraft.world.level.Level.END) return Blocks.END_STONE.defaultBlockState();
		return Blocks.STONE.defaultBlockState();
	}

	/** Its soil (under the top) and its top: dirt and grass, soul soil and nylium, end stone. */
	static BlockState soil(ServerLevel level) {
		if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD) return Blocks.DIRT.defaultBlockState();
		return level.dimension() == net.minecraft.world.level.Level.NETHER ? Blocks.SOUL_SOIL.defaultBlockState() : stone(level);
	}

	static BlockState top(ServerLevel level) {
		if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD) return Blocks.GRASS_BLOCK.defaultBlockState();
		return level.dimension() == net.minecraft.world.level.Level.NETHER ? Blocks.CRIMSON_NYLIUM.defaultBlockState() : stone(level);
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
		BlockState top = top(race), stone = stone(level);
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

	/**
	 * Owner 19.10: a generated base's building stands a block up — a platform under its footprint (on the grid,
	 * {@link UnitKind#gridCentre}) and a block round it, stairs on every side going up to it. What stands over it (a
	 * lamp, a tree, earth) is cleared to the building's height, and the ground under it is filled so it never hangs.
	 * Returns the feet the building stands at.
	 */
	public static BlockPos pedestal(ServerLevel level, UnitKind kind, BlockPos feet, BlockState top, BlockState stairs) {
		net.minecraft.world.phys.Vec3 c = kind.gridCentre(net.minecraft.world.phys.Vec3.atBottomCenterOf(feet));
		int size = Math.max(1, Math.round(kind.width));
		int minX = Mth.floor(c.x - size / 2.0 + 0.01), minZ = Mth.floor(c.z - size / 2.0 + 0.01);
		int maxX = minX + size - 1, maxZ = minZ + size - 1, y = feet.getY(), clear = (int) Math.ceil(kind.height) + 2;
		for (int x = minX - 2; x <= maxX + 2; x++)
			for (int z = minZ - 2; z <= maxZ + 2; z++) {
				boolean ring = x < minX - 1 || x > maxX + 1 || z < minZ - 1 || z > maxZ + 1;
				BlockPos at = new BlockPos(x, y, z);
				for (int up = 1; up <= clear; up++) {
					BlockPos over = at.above(up);
					if (!level.getBlockState(over).isAir() && free(level, over)) level.setBlock(over, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
				}
				for (int down = 1; down <= 4; down++) {
					BlockPos under = at.below(down);
					BlockState state = level.getBlockState(under);
					if (!state.canBeReplaced() && state.getFluidState().isEmpty()) break;
					if (free(level, under)) level.setBlock(under, stone(level), Block.UPDATE_CLIENTS);
				}
				if (!free(level, at)) continue;
				if (!ring) level.setBlock(at, top, Block.UPDATE_CLIENTS);
				else {
					net.minecraft.core.Direction up = x < minX - 1 ? net.minecraft.core.Direction.EAST : x > maxX + 1 ? net.minecraft.core.Direction.WEST
						: z < minZ - 1 ? net.minecraft.core.Direction.SOUTH : net.minecraft.core.Direction.NORTH;
					level.setBlock(at, stairs.hasProperty(net.minecraft.world.level.block.StairBlock.FACING)
						? stairs.setValue(net.minecraft.world.level.block.StairBlock.FACING, up) : stairs, Block.UPDATE_CLIENTS);
				}
			}
		return feet.above();
	}

	/** Fills the land under a generated base's plaza (radius `half` round `feet`) down to {@link #DEPTH}: no cave, no drop under it. */
	public static void fillUnder(ServerLevel level, BlockPos feet, int half) {
		BlockState stone = stone(level), dirt = soil(level);
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
		BlockState grass = top(level), dirt = soil(level), air = Blocks.AIR.defaultBlockState();
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
