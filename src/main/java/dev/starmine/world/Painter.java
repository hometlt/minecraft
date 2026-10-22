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
 *
 * A {@link #deferred} painter (the sky ships, CLOUD_TASK_2 stage 6: hundreds of thousands of cells) only
 * plans: its blocks, chests and steps ({@link #then}) are put in order, {@link #BUDGET} a server tick
 * ({@link #serverTick}), so a ship does not stall the server; {@link #stateAt} reads the plan.
 */
public final class Painter {
	/** A block chosen per cell (a wall with lights every few blocks, a hull with stripes…). */
	public interface Palette {
		BlockState at(BlockPos pos);
	}

	private final ServerLevel level;
	final RandomSource random;
	private int placed;
	/** A deferred painter's plan: blocks by position (in order), steps after the blocks put before them. */
	private it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<BlockState> plan;
	private final java.util.ArrayDeque<Object> queue;
	/** Cells or steps a server tick puts for all deferred painters. */
	public static final int BUDGET = 12_000;
	private static final java.util.List<Painter> BUILDS = new java.util.ArrayList<>();

	public Painter(ServerLevel level, RandomSource random) {
		this(level, random, false);
	}

	private Painter(ServerLevel level, RandomSource random, boolean deferred) {
		this.level = level;
		this.random = random;
		this.plan = deferred ? new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<>() : null;
		this.queue = deferred ? new java.util.ArrayDeque<>() : null;
	}

	/** A painter that plans and puts its blocks over the next ticks once {@link #start}ed. */
	public static Painter deferred(ServerLevel level, RandomSource random) {
		return new Painter(level, random, true);
	}

	/** The block planned at a cell (a deferred painter), else the world's. */
	public BlockState stateAt(BlockPos pos) {
		if (plan != null) {
			BlockState planned = plan.get(pos.asLong());
			if (planned != null) return planned;
		}
		return level.getBlockState(pos);
	}

	/** A step once everything planned before it is put (at once for a painter that is not deferred). */
	public void then(Runnable step) {
		if (queue == null) step.run();
		else {
			flushPlan();
			queue.add(step);
		}
	}

	private void flushPlan() {
		if (plan.isEmpty()) return;
		queue.add(plan); // handed over, not copied: a copy of a ship's plan per chest ran the server out of memory (crash 22.10)
		plan = new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<>();
	}

	/** Starts putting a deferred painter's plan, {@link #BUDGET} a tick. */
	public void start() {
		if (queue == null) return;
		flushPlan();
		BUILDS.add(this);
	}

	/** Puts up to `budget` cells or steps; true when all is put. */
	@SuppressWarnings("unchecked")
	private boolean put(int[] budget) {
		while (!queue.isEmpty() && budget[0] > 0) {
			Object next = queue.peek();
			if (next instanceof Runnable step) {
				queue.poll();
				step.run();
				budget[0]--;
				continue;
			}
			var cells = (it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<BlockState>) next;
			while (!cells.isEmpty() && budget[0] > 0) {
				long key = cells.firstLongKey();
				BlockState state = cells.removeFirst();
				write(BlockPos.of(key), state);
				budget[0]--;
			}
			if (cells.isEmpty()) queue.poll();
		}
		return queue.isEmpty();
	}

	/** Server tick: the deferred builds go on. */
	public static void serverTick(net.minecraft.server.MinecraftServer server) {
		int[] budget = {BUDGET};
		for (java.util.Iterator<Painter> it = BUILDS.iterator(); it.hasNext() && budget[0] > 0; ) {
			Painter painter = it.next();
			if (painter.put(budget)) it.remove();
		}
	}

	/** Puts everything planned now (tests). */
	public static void finishAll() {
		int[] budget = {Integer.MAX_VALUE};
		for (Painter painter : java.util.List.copyOf(BUILDS)) painter.put(budget);
		BUILDS.clear();
	}

	/** Whether deferred builds are still going on. */
	public static boolean building() {
		return !BUILDS.isEmpty();
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
		if (plan != null) { // planned: air over air is left out
			long key = pos.asLong();
			if (!plan.containsKey(key) && level.getBlockState(pos) == state) return;
			if (plan.put(key, state) == null) placed++;
			return;
		}
		write(pos, state);
	}

	private void write(BlockPos pos, BlockState state) {
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
		if (queue != null) {
			BlockPos at = pos.immutable();
			then(() -> chestNow(at, loot));
			return;
		}
		chestNow(pos, loot);
	}

	private void chestNow(BlockPos pos, ResourceKey<LootTable> loot) {
		level.setBlock(pos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,
			net.minecraft.core.Direction.Plane.HORIZONTAL.getRandomDirection(random)), 2);
		RandomizableContainer.setBlockEntityLootTable(level, random, pos, loot);
		placed++;
	}

	static double sq(double v) {
		return v * v;
	}
}
