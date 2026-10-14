package dev.starmine.world;

import dev.starmine.entity.UnitKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;

/**
 * Underground parts of generated race bases (owner 14.10):
 * - terrans: a technological labyrinth ({@link #techLabyrinth}) — a maze of steel corridors with
 *   laboratories and a reactor core, marines, marauders and ghosts on guard;
 * - protoss: a dungeon of ancient temples ({@link #ancientTemples}) — a great Xel'Naga hall with an
 *   altar and four shrines joined by corridors, guarded by zealots, stalkers, sentries and dark templar.
 * Both are reached from the surface by a stair ({@link #stairsUp}). Guards keep to their room.
 */
public final class Dungeons {
	private static final BlockState AIR = Blocks.AIR.defaultBlockState();

	private Dungeons() {
	}

	// ---- shared ----

	/**
	 * A stair from `door` (the floor cell just outside a dungeon) up to the surface, going `toward`,
	 * 3 wide and 4 high, lined with `wall`; ends in a small gate of `gate` blocks on the surface.
	 * Returns the surface cell it comes out at.
	 */
	public static BlockPos stairsUp(Painter painter, BlockPos door, Direction toward, BlockState wall, BlockState gate) {
		Direction side = toward.getClockWise();
		BlockState step = Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, toward);
		BlockPos feet = door;
		for (int k = 0; k < 120; k++) {
			for (int s = -2; s <= 2; s++)
				for (int up = -1; up <= 5; up++) {
					BlockPos pos = feet.relative(side, s).above(up);
					if (Math.abs(s) == 2 || up == -1 || up == 5) {
						if (painter.level().getBlockState(pos).isAir() || !painter.level().getFluidState(pos).isEmpty()) painter.set(pos, wall);
					} else if (up == 0 && k > 0) painter.set(pos, step);
					else painter.carve(pos, wall);
				}
			int surface = painter.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ());
			if (feet.getY() >= surface - 1) break;
			feet = feet.relative(toward).above();
		}
		// the gate: two posts and a lintel
		for (int s = -2; s <= 2; s += 4)
			for (int up = 0; up <= 4; up++) painter.set(feet.relative(side, s).above(up), gate);
		for (int s = -2; s <= 2; s++) painter.set(feet.relative(side, s).above(5), gate);
		return feet;
	}

	private static BlockPos pick(RandomSource random, List<BlockPos> cells) {
		return cells.get(random.nextInt(cells.size()));
	}

	// ---- the terran labyrinth ----

	public static final int MAZE_CELLS = 11, PITCH = 4;

	/**
	 * A labyrinth of 11 x 11 cells (45 x 45 blocks, corridors 3 wide and 4 high) whose floor's corner is
	 * `corner`: steel walls (deepslate with iron pillars and a copper band), ceiling lights, a maze with
	 * loops, four laboratories (2 x 2 cells: workbenches, cages, a chest) and the reactor core in the
	 * middle (3 x 3 cells: a redstone reactor in iron bars, a library chest). The door is in the middle
	 * of the west side; the stair from it goes up to the surface.
	 */
	public static List<Spawn> techLabyrinth(Painter painter, BlockPos corner) {
		RandomSource random = painter.random;
		int size = MAZE_CELLS * PITCH + 1;
		BlockState floor = Blocks.POLISHED_ANDESITE.defaultBlockState(), wall = Blocks.POLISHED_DEEPSLATE.defaultBlockState(),
			pillar = Blocks.IRON_BLOCK.defaultBlockState(), band = Blocks.WAXED_OXIDIZED_CUT_COPPER.defaultBlockState(),
			ceiling = Blocks.SMOOTH_STONE.defaultBlockState(), light = Blocks.SEA_LANTERN.defaultBlockState();
		painter.box(corner, corner.offset(size - 1, 5, size - 1), pos -> {
			int x = pos.getX() - corner.getX(), y = pos.getY() - corner.getY(), z = pos.getZ() - corner.getZ();
			if (y == 0) return floor;
			if (y == 5) return x % PITCH == 2 && z % PITCH == 2 ? light : ceiling;
			if (x % PITCH == 0 && z % PITCH == 0) return pillar;
			return y == 2 ? band : wall;
		});
		// the maze: depth-first from the middle, then some loops
		boolean[][] seen = new boolean[MAZE_CELLS][MAZE_CELLS];
		List<int[]> stack = new ArrayList<>();
		stack.add(new int[] {MAZE_CELLS / 2, MAZE_CELLS / 2});
		seen[MAZE_CELLS / 2][MAZE_CELLS / 2] = true;
		openCell(painter, corner, MAZE_CELLS / 2, MAZE_CELLS / 2);
		int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		while (!stack.isEmpty()) {
			int[] cell = stack.get(stack.size() - 1);
			List<int[]> next = new ArrayList<>();
			for (int[] d : steps) {
				int nx = cell[0] + d[0], nz = cell[1] + d[1];
				if (nx >= 0 && nz >= 0 && nx < MAZE_CELLS && nz < MAZE_CELLS && !seen[nx][nz]) next.add(new int[] {nx, nz});
			}
			if (next.isEmpty()) {
				stack.remove(stack.size() - 1);
				continue;
			}
			int[] to = next.get(random.nextInt(next.size()));
			seen[to[0]][to[1]] = true;
			openCell(painter, corner, to[0], to[1]);
			openWall(painter, corner, cell[0], cell[1], to[0], to[1]);
			stack.add(to);
		}
		for (int i = 0; i < MAZE_CELLS * MAZE_CELLS / 8; i++) {
			int cx = random.nextInt(MAZE_CELLS - 1), cz = random.nextInt(MAZE_CELLS);
			if (random.nextBoolean()) openWall(painter, corner, cx, cz, cx + 1, cz);
			else openWall(painter, corner, cz, cx, cz, cx + 1);
		}
		List<Spawn> spawns = new ArrayList<>();
		// the reactor core in the middle
		int mid = MAZE_CELLS / 2;
		openRoom(painter, corner, mid - 1, mid - 1, 3);
		BlockPos core = cellFloor(corner, mid, mid);
		for (int y = 0; y < 3; y++) painter.set(core.above(y), Blocks.REDSTONE_BLOCK.defaultBlockState());
		painter.set(core.above(3), Blocks.BEACON.defaultBlockState());
		for (int dx = -1; dx <= 1; dx++)
			for (int dz = -1; dz <= 1; dz++)
				if (dx != 0 || dz != 0) for (int y = 0; y < 3; y++) painter.set(core.offset(dx, y, dz), Blocks.IRON_BARS.defaultBlockState());
		painter.chest(core.offset(3, 0, 3), BuiltInLootTables.STRONGHOLD_LIBRARY);
		painter.chest(core.offset(-3, 0, -3), BuiltInLootTables.SIMPLE_DUNGEON);
		spawns.add(Spawn.guard(UnitKind.GHOST, core.offset(3, 0, -3), core));
		spawns.add(Spawn.guard(UnitKind.GHOST, core.offset(-3, 0, 3), core));
		spawns.add(Spawn.guard(UnitKind.MARAUDER, core.offset(4, 0, 0), core));
		// laboratories in the four quarters
		int[][] labs = {{1, 1}, {MAZE_CELLS - 3, 1}, {1, MAZE_CELLS - 3}, {MAZE_CELLS - 3, MAZE_CELLS - 3}};
		BlockState[] benches = {Blocks.BLAST_FURNACE.defaultBlockState(), Blocks.SMITHING_TABLE.defaultBlockState(),
			Blocks.BREWING_STAND.defaultBlockState(), Blocks.LECTERN.defaultBlockState(), Blocks.CRAFTER.defaultBlockState(),
			Blocks.OBSERVER.defaultBlockState(), Blocks.COPPER_BULB.defaultBlockState()};
		for (int[] lab : labs) {
			openRoom(painter, corner, lab[0], lab[1], 2);
			BlockPos room = cellFloor(corner, lab[0], lab[1]).offset(2, 0, 2);
			List<BlockPos> wallCells = new ArrayList<>();
			for (int d = -2; d <= 2; d++) {
				wallCells.add(room.offset(d, 0, -3));
				wallCells.add(room.offset(d, 0, 3));
			}
			Collections.shuffle(wallCells, new java.util.Random(random.nextLong()));
			for (int i = 0; i < 5; i++) painter.set(wallCells.get(i), benches[random.nextInt(benches.length)]);
			painter.chest(wallCells.get(5), random.nextBoolean() ? BuiltInLootTables.SIMPLE_DUNGEON : BuiltInLootTables.STRONGHOLD_CORRIDOR);
			// a cage
			for (int y = 0; y < 3; y++) {
				painter.set(room.offset(-3, y, -1), Blocks.IRON_BARS.defaultBlockState());
				painter.set(room.offset(-3, y, 1), Blocks.IRON_BARS.defaultBlockState());
			}
			spawns.add(Spawn.guard(random.nextBoolean() ? UnitKind.MARINE : UnitKind.MARAUDER, room, room));
			spawns.add(Spawn.guard(UnitKind.MARINE, room.offset(1, 0, 1), room));
		}
		// patrols in the corridors
		for (int i = 0; i < 6; i++) {
			BlockPos cell = cellFloor(corner, random.nextInt(MAZE_CELLS), random.nextInt(MAZE_CELLS));
			if (painter.level().getBlockState(cell).isAir()) spawns.add(Spawn.guard(UnitKind.MARINE, cell, cell));
		}
		// the door in the middle of the west side and the stair up
		BlockPos door = cellFloor(corner, 0, mid).offset(-2, 0, 0);
		for (int z = -1; z <= 1; z++)
			for (int y = 0; y < 4; y++) painter.carve(cellFloor(corner, 0, mid).offset(-2, y, z), wall);
		stairsUp(painter, door.west(), Direction.WEST, Blocks.STONE_BRICKS.defaultBlockState(), pillar);
		return spawns;
	}

	/** The floor cell in the middle of maze cell (cx, cz) — feet level, one above the floor blocks. */
	private static BlockPos cellFloor(BlockPos corner, int cx, int cz) {
		return corner.offset(cx * PITCH + 2, 1, cz * PITCH + 2);
	}

	private static void openCell(Painter painter, BlockPos corner, int cx, int cz) {
		BlockPos centre = cellFloor(corner, cx, cz);
		painter.hollow(centre.offset(-1, 0, -1), centre.offset(1, 3, 1), Blocks.POLISHED_DEEPSLATE.defaultBlockState());
	}

	/** Opens the wall between two neighbouring cells. */
	private static void openWall(Painter painter, BlockPos corner, int ax, int az, int bx, int bz) {
		BlockPos a = cellFloor(corner, ax, az), b = cellFloor(corner, bx, bz);
		BlockPos gap = new BlockPos((a.getX() + b.getX()) / 2, a.getY(), (a.getZ() + b.getZ()) / 2);
		boolean alongX = ax != bx;
		BlockPos from = alongX ? gap.offset(0, 0, -1) : gap.offset(-1, 0, 0), to = alongX ? gap.offset(0, 3, 1) : gap.offset(1, 3, 0);
		painter.hollow(from, to, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
	}

	/** A room over `cells` x `cells` maze cells from (cx, cz): every wall between them removed, pillars kept in its corners. */
	private static void openRoom(Painter painter, BlockPos corner, int cx, int cz, int cells) {
		BlockPos from = cellFloor(corner, cx, cz).offset(-1, 0, -1), to = cellFloor(corner, cx + cells - 1, cz + cells - 1).offset(1, 3, 1);
		painter.hollow(from, to, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
	}

	// ---- the protoss temples ----

	/**
	 * Ancient temples (Xel'Naga) around `centre` (the great hall's floor middle, feet level): the great
	 * hall (25 x 25, 12 high) of sandstone with prismarine pillars crowned with lights, gold inlays and
	 * an altar of quartz with a crystal and a treasure chest; four shrines (13 x 13, 8 high) of dark
	 * prismarine 34 blocks out, each with a chest and a gold statue; corridors join them to the hall.
	 * The stair to the surface leaves the north shrine.
	 */
	public static List<Spawn> ancientTemples(Painter painter, BlockPos centre) {
		RandomSource random = painter.random;
		BlockState sandstone = Blocks.CUT_SANDSTONE.defaultBlockState(), smooth = Blocks.SMOOTH_SANDSTONE.defaultBlockState(),
			glyph = Blocks.CHISELED_SANDSTONE.defaultBlockState(), prism = Blocks.PRISMARINE_BRICKS.defaultBlockState(),
			dark = Blocks.DARK_PRISMARINE.defaultBlockState(), gold = Blocks.GOLD_BLOCK.defaultBlockState(),
			light = Blocks.SEA_LANTERN.defaultBlockState();
		int[][] shrines = {{0, -34}, {34, 0}, {0, 34}, {-34, 0}};
		// solid shells first: corridors, shrines, the hall
		for (int[] s : shrines) {
			BlockPos end = centre.offset(s[0], 0, s[1]);
			int dx = Integer.signum(s[0]), dz = Integer.signum(s[1]);
			BlockPos a = centre.offset(dx * 12, -1, dz * 12), b = end.offset(-dx * 6, 4, -dz * 6);
			painter.box(min(a, b).offset(-2 * Math.abs(dz), 0, -2 * Math.abs(dx)), max(a, b).offset(2 * Math.abs(dz), 0, 2 * Math.abs(dx)), p -> sandstone);
			painter.box(end.offset(-7, -1, -7), end.offset(7, 8, 7), p -> (p.getX() + p.getZ()) % 4 == 0 && p.getY() == end.getY() + 3 ? light : dark);
		}
		painter.box(centre.offset(-13, -1, -13), centre.offset(13, 12, 13),
			p -> p.getY() == centre.getY() + 3 && (p.getX() + p.getZ()) % 3 == 0 ? glyph : sandstone);
		// then the insides
		painter.hollow(centre.offset(-12, 0, -12), centre.offset(12, 11, 12), sandstone);
		for (int[] s : shrines) {
			BlockPos end = centre.offset(s[0], 0, s[1]);
			int dx = Integer.signum(s[0]), dz = Integer.signum(s[1]);
			BlockPos a = centre.offset(dx * 12, 0, dz * 12), b = end.offset(-dx * 6, 3, -dz * 6);
			painter.hollow(min(a, b).offset(-Math.abs(dz), 0, -Math.abs(dx)), max(a, b).offset(Math.abs(dz), 0, Math.abs(dx)), sandstone);
			painter.hollow(end.offset(-6, 0, -6), end.offset(6, 7, 6), dark);
			// lights along the corridor
			for (int k = 14; k < 28; k += 5) painter.set(centre.offset(dx * k + dz * 2, 2, dz * k + dx * 2), light);
		}
		// the hall: a floor with a gold ring, pillars, the altar
		for (int x = -12; x <= 12; x++)
			for (int z = -12; z <= 12; z++) {
				double r = Math.sqrt(x * x + z * z);
				painter.set(centre.offset(x, -1, z), r >= 7.5 && r < 8.5 ? gold : r >= 9 && r < 10 ? dark : smooth);
			}
		for (int i = 0; i < 8; i++) {
			double rad = Math.toRadians(i * 45 + 22.5);
			BlockPos base = centre.offset((int) Math.round(Math.cos(rad) * 10), 0, (int) Math.round(Math.sin(rad) * 10));
			for (int y = 0; y < 12; y++) painter.set(base.above(y), y == 10 ? light : y % 4 == 0 ? glyph : prism);
		}
		for (int x = -2; x <= 2; x++)
			for (int z = -2; z <= 2; z++) painter.set(centre.offset(x, 0, z), Blocks.CHISELED_QUARTZ_BLOCK.defaultBlockState());
		for (int x = -1; x <= 1; x++)
			for (int z = -1; z <= 1; z++) painter.set(centre.offset(x, 1, z), Blocks.QUARTZ_PILLAR.defaultBlockState());
		painter.set(centre.above(2), Blocks.AMETHYST_BLOCK.defaultBlockState());
		painter.set(centre.above(3), Blocks.AMETHYST_CLUSTER.defaultBlockState());
		painter.chest(centre.offset(0, 1, 2), BuiltInLootTables.DESERT_PYRAMID);
		painter.chest(centre.offset(0, 1, -2), BuiltInLootTables.END_CITY_TREASURE);

		List<Spawn> spawns = new ArrayList<>();
		spawns.add(Spawn.guard(UnitKind.HIGH_TEMPLAR, centre.offset(5, 0, 0), centre));
		spawns.add(Spawn.guard(UnitKind.ARCHON, centre.offset(-6, 0, 0), centre));
		spawns.add(Spawn.guard(UnitKind.ZEALOT, centre.offset(0, 0, 6), centre));
		spawns.add(Spawn.guard(UnitKind.ZEALOT, centre.offset(0, 0, -6), centre));
		UnitKind[][] keepers = {{UnitKind.ZEALOT, UnitKind.ZEALOT}, {UnitKind.STALKER, UnitKind.SENTRY},
			{UnitKind.DARK_TEMPLAR, UnitKind.DARK_TEMPLAR}, {UnitKind.ADEPT, UnitKind.STALKER}};
		for (int i = 0; i < shrines.length; i++) {
			BlockPos end = centre.offset(shrines[i][0], 0, shrines[i][1]);
			// a gold statue on a pedestal and a chest at its feet
			painter.set(end.offset(0, 0, 0), glyph);
			painter.set(end.offset(0, 1, 0), gold);
			painter.set(end.offset(0, 2, 0), gold);
			painter.set(end.offset(0, 3, 0), light);
			painter.chest(end.offset(1, 0, 1), random.nextBoolean() ? BuiltInLootTables.DESERT_PYRAMID : BuiltInLootTables.SIMPLE_DUNGEON);
			for (UnitKind kind : keepers[i]) spawns.add(Spawn.guard(kind, end.offset(random.nextInt(7) - 3, 0, random.nextInt(7) - 3), end));
		}
		// the stair up from the north shrine
		BlockPos north = centre.offset(0, 0, -34);
		painter.hollow(north.offset(-1, 0, -7), north.offset(1, 3, -7), dark);
		stairsUp(painter, north.offset(0, 0, -8), Direction.NORTH, sandstone, gold);
		return spawns;
	}

	private static BlockPos min(BlockPos a, BlockPos b) {
		return new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
	}

	private static BlockPos max(BlockPos a, BlockPos b) {
		return new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
	}
}
