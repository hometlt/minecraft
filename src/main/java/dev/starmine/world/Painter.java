package dev.starmine.world;

import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Puts the blocks of a generated structure (owner 14.10: race bases, their dungeons and sky ships):
 * boxes, ellipsoids and cylinders of a block or of a palette, chests with a loot table. Unbreakable
 * blocks (bedrock, end portal frames) and block entities already there are left alone; fluids next to
 * a carved cell are sealed so a dungeon does not flood. Chunks are loaded ({@link #load}) before a build.
 */
public final class Painter {
	/** A block chosen per cell (a wall with lights every few blocks, a hull with stripes…). */
	public interface Palette {
		BlockState at(BlockPos pos);
	}

	private final ServerLevel level;
	final RandomSource random;
	private int placed;

	public Painter(ServerLevel level, RandomSource random) {
		this.level = level;
		this.random = random;
	}

	public ServerLevel level() {
		return level;
	}

	/** Blocks put so far. */
	public int placed() {
		return placed;
	}

	/** Loads (generates) the chunks of a square around `centre`, so a build does not cut through missing chunks. */
	public void load(BlockPos centre, int radius) {
		for (int cx = (centre.getX() - radius) >> 4; cx <= (centre.getX() + radius) >> 4; cx++)
			for (int cz = (centre.getZ() - radius) >> 4; cz <= (centre.getZ() + radius) >> 4; cz++) level.getChunk(cx, cz);
	}

	public static Palette of(BlockState state) {
		return pos -> state;
	}

	public void set(BlockPos pos, BlockState state) {
		if (pos.getY() <= level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight() - 1) return;
		BlockState old = level.getBlockState(pos);
		if (old.getDestroySpeed(level, pos) < 0 || level.getBlockEntity(pos) != null || old == state) return;
		level.setBlock(pos, state, 2);
		placed++;
	}

	/** An empty cell; water or lava beside it is walled with `seal`. */
	public void carve(BlockPos pos, BlockState seal) {
		for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
			BlockPos next = pos.relative(direction);
			if (!level.getFluidState(next).isEmpty()) set(next, seal);
		}
		set(pos, Blocks.AIR.defaultBlockState());
	}

	/** Every cell of the box `from`–`to` (inclusive) that `where` accepts. */
	public void box(BlockPos from, BlockPos to, Palette palette, Predicate<BlockPos> where) {
		for (BlockPos pos : BlockPos.betweenClosed(from, to)) if (where.test(pos)) set(pos.immutable(), palette.at(pos));
	}

	public void box(BlockPos from, BlockPos to, Palette palette) {
		box(from, to, palette, pos -> true);
	}

	/** The cells of the box `from`–`to` made empty (fluids sealed with `seal`). */
	public void hollow(BlockPos from, BlockPos to, BlockState seal) {
		for (BlockPos pos : BlockPos.betweenClosed(from, to)) carve(pos.immutable(), seal);
	}

	/** An ellipsoid around `centre` with radii rx, ry, rz; `shell` > 0 leaves it hollow inside (that many blocks thick). */
	public void ellipsoid(BlockPos centre, double rx, double ry, double rz, Palette palette, double shell) {
		int ix = (int) Math.ceil(rx), iy = (int) Math.ceil(ry), iz = (int) Math.ceil(rz);
		for (int dx = -ix; dx <= ix; dx++)
			for (int dy = -iy; dy <= iy; dy++)
				for (int dz = -iz; dz <= iz; dz++) {
					double r = sq(dx / rx) + sq(dy / ry) + sq(dz / rz);
					if (r > 1) continue;
					BlockPos pos = centre.offset(dx, dy, dz);
					if (shell > 0 && sq(dx / Math.max(0.5, rx - shell)) + sq(dy / Math.max(0.5, ry - shell)) + sq(dz / Math.max(0.5, rz - shell)) < 1)
						set(pos, Blocks.AIR.defaultBlockState());
					else set(pos, palette.at(pos));
				}
	}

	/** A vertical cylinder from `base` up `height` blocks. */
	public void cylinder(BlockPos base, double radius, int height, Palette palette) {
		int r = (int) Math.ceil(radius);
		for (int dx = -r; dx <= r; dx++)
			for (int dz = -r; dz <= r; dz++)
				if (dx * dx + dz * dz <= radius * radius)
					for (int dy = 0; dy < height; dy++) set(base.offset(dx, dy, dz), palette.at(base.offset(dx, dy, dz)));
	}

	/** A chest filled from a vanilla loot table when first opened. */
	public void chest(BlockPos pos, ResourceKey<LootTable> loot) {
		if (pos.getY() <= level.getMinBuildHeight()) return;
		level.setBlock(pos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,
			net.minecraft.core.Direction.Plane.HORIZONTAL.getRandomDirection(random)), 2);
		RandomizableContainer.setBlockEntityLootTable(level, random, pos, loot);
		placed++;
	}

	static double sq(double v) {
		return v * v;
	}
}
