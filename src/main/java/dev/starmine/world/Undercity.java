package dev.starmine.world;

import dev.starmine.block.StarMineBlocks;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.HiveStructure;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * The levels under a race's main base (owner 19.10): {@link #LEVELS} storeys of tunnels {@link #WIDTH} wide and
 * {@link #HEIGHT} high — room for the base's army, which walks down to them from the plaza by a ramp — joined by
 * ramps (a block down every two forward), with rooms at the crossings, guards in them, and at the bottom the boss's
 * great hall ({@link #HALL_RADIUS} round), where the base's guardian waits (Abathur, Swann, Karax).
 *
 * Each race in its own way: the zerg's are flesh and creep with eggs in their rooms; the terrans' technical tunnels
 * of steel with pipes along the ceiling, consoles and lockers; the protoss' hidden temples of sandstone and
 * prismarine with statues and khaydarin crystals.
 *
 * Every storey is a 3 x 3 grid of crossings {@link #SPACING} apart; a random tree of tunnels joins them (with a loop
 * or two), one ramp leads down to the next storey and one from the lowest to the hall. The way in leaves the grid's
 * east crossing of the top storey and climbs east to the surface. Walls, floors and ceilings are built (not only
 * carved), so the tunnels hold over caves and water; the blocks go in over a few ticks ({@link Painter#deferred}),
 * their dwellers after them.
 */
public final class Undercity {
	public static final int LEVELS = 3, NODES = 3, SPACING = 24, DROP = 12, WIDTH = 5, HEIGHT = 6, TOP = 14;
	public static final int ROOM = 6, ROOM_HEIGHT = 8, JUNCTION = 3, HALL_RADIUS = 16, HALL_HEIGHT = 14, HALL_DROP = 18;
	private static final int HALF = WIDTH / 2;

	/** A build: its painter (started by the caller once it adds its own steps), the dwellers, the way in and the hall. */
	public record Plan(Painter painter, List<Spawn> spawns, BlockPos mouth, BlockPos hall, int levels) {
	}

	/** A tunnel from `a` to `b` (feet cells on one axis; a ramp when their heights differ); `entrance` keeps to the ground. */
	private record Tunnel(BlockPos a, BlockPos b, boolean entrance) {
	}

	/** A room round a crossing (feet at `centre`), `half` blocks each way of its middle. */
	private record Room(BlockPos centre, int half, int height, boolean big) {
	}

	private final ServerLevel level;
	private final UnitKind.Race race;
	private final RandomSource random;
	private final Painter painter;
	private final List<Tunnel> tunnels = new ArrayList<>();
	private final List<Room> rooms = new ArrayList<>();
	private final List<Spawn> spawns = new ArrayList<>();
	private final List<BlockPos> floors = new ArrayList<>();

	private Undercity(ServerLevel level, UnitKind.Race race, RandomSource random) {
		this.level = level;
		this.race = race;
		this.random = random;
		this.painter = Painter.deferred(level, random);
	}

	/** How many storeys fit under ground at `surface` (with the hall under them); 0 when not even one does. */
	public static int levelsUnder(ServerLevel level, int surface) {
		for (int n = LEVELS; n >= 1; n--)
			if (surface - TOP - DROP * (n - 1) - HALL_DROP - 4 > level.getMinBuildHeight() + 4) return n;
		return 0;
	}

	/**
	 * Plans the levels under a base whose plaza's middle is `centre` (feet level); null when the ground is too shallow
	 * (a superflat world) or the base is not in the overworld. The caller adds its steps and {@link Painter#start}s it.
	 */
	public static @Nullable Plan plan(ServerLevel level, UnitKind.Race race, BlockPos centre, RandomSource random) {
		if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return null;
		int levels = levelsUnder(level, centre.getY());
		if (levels == 0) return null;
		return new Undercity(level, race, random).layout(centre, levels);
	}

	private BlockPos node(BlockPos centre, int i, int j, int storey) {
		return new BlockPos(centre.getX() + (i - 1) * SPACING, centre.getY() - TOP - storey * DROP, centre.getZ() + (j - 1) * SPACING);
	}

	private Plan layout(BlockPos centre, int levels) {
		painter.load(centre, SPACING * 2 + HALL_DROP * 2 + HALL_RADIUS * 2);
		// the ramps down: on the first storey's north row, the second's south row (their flat tunnels left out)
		int[][] ramps = {{0, 0, 1, 0}, {2, 2, 1, 2}};
		for (int storey = 0; storey < levels; storey++) {
			List<int[]> edges = new ArrayList<>();
			for (int i = 0; i < NODES; i++)
				for (int j = 0; j < NODES; j++) {
					if (i + 1 < NODES) edges.add(new int[] {i, j, i + 1, j});
					if (j + 1 < NODES) edges.add(new int[] {i, j, i, j + 1});
				}
			for (int k = Math.max(0, storey - 1); k <= Math.min(storey, levels - 2); k++) {
				int[] ramp = ramps[k % ramps.length];
				edges.removeIf(e -> same(e, ramp));
			}
			for (int[] e : tree(edges)) tunnels.add(new Tunnel(node(centre, e[0], e[1], storey), node(centre, e[2], e[3], storey), false));
			for (int i = 0; i < NODES; i++)
				for (int j = 0; j < NODES; j++) {
					boolean big = random.nextInt(5) < 3;
					rooms.add(new Room(node(centre, i, j, storey), big ? ROOM : JUNCTION, big ? ROOM_HEIGHT : HEIGHT, big));
				}
			if (storey + 1 < levels) {
				int[] ramp = ramps[storey % ramps.length];
				tunnels.add(new Tunnel(node(centre, ramp[0], ramp[1], storey), node(centre, ramp[2], ramp[3], storey + 1), false));
			}
		}
		// the way in: from the top storey's east crossing up to the surface; the way down to the hall from the lowest's west
		BlockPos east = node(centre, 2, 1, 0);
		tunnels.add(new Tunnel(east, east.east(SPACING * 4), true));
		BlockPos west = node(centre, 0, 1, levels - 1);
		BlockPos landing = west.offset(-HALL_DROP * 2, -HALL_DROP, 0);
		tunnels.add(new Tunnel(west, landing, false));
		BlockPos hall = landing.west(HALL_RADIUS - 2);

		// walls first, then the insides, then what furnishes them
		for (Tunnel tunnel : tunnels) tunnel(tunnel, false);
		for (Room room : rooms) room(room, false);
		hallShell(hall);
		for (Tunnel tunnel : tunnels) tunnel(tunnel, true);
		for (Room room : rooms) room(room, true);
		hallInside(hall);
		// a ramp's floor where a room or the hall was carved over it: a slope down into the room, not a drop it cannot climb
		for (Tunnel tunnel : tunnels) if (tunnel.entrance() || tunnel.a().getY() != tunnel.b().getY()) tunnel(tunnel, null);
		for (Room room : rooms) furnish(room);
		furnishHall(hall);
		if (race == UnitKind.Race.ZERG) { // creep over the floors, once the blocks are in
			List<BlockPos> lined = List.copyOf(floors);
			painter.then(() -> {
				for (BlockPos pos : lined) {
					BlockState there = level.getBlockState(pos);
					if (!there.isAir()) continue;
					BlockState creep = StarMineBlocks.CREEP.getStateForPlacement(there, level, pos, Direction.DOWN);
					if (creep != null) level.setBlock(pos, creep, 2);
				}
				for (int i = 0; i < 4; i++) { // creep tumors round the hall: they heal Abathur (GuardianFights)
					double a = Math.PI / 4 + i * Math.PI / 2;
					dev.starmine.creep.CreepGrowth.placeTumor(level, hall.offset((int) Math.round(Math.cos(a) * 8), 0, (int) Math.round(Math.sin(a) * 8)), 4);
				}
			});
		}
		return new Plan(painter, spawns, mouth == null ? east : mouth, hall, levels);
	}

	private static boolean same(int[] e, int[] ramp) {
		return e[0] == ramp[0] && e[1] == ramp[1] && e[2] == ramp[2] && e[3] == ramp[3]
			|| e[0] == ramp[2] && e[1] == ramp[3] && e[2] == ramp[0] && e[3] == ramp[1];
	}

	/** A random spanning tree of the grid over `edges` (depth first) and two more of them for loops. */
	private List<int[]> tree(List<int[]> edges) {
		List<int[]> shuffled = new ArrayList<>(edges);
		Collections.shuffle(shuffled, new java.util.Random(random.nextLong()));
		boolean[][] seen = new boolean[NODES][NODES];
		List<int[]> chosen = new ArrayList<>(), left = new ArrayList<>();
		List<int[]> stack = new ArrayList<>(List.of(new int[] {1, 1}));
		seen[1][1] = true;
		while (!stack.isEmpty()) {
			int[] at = stack.get(stack.size() - 1);
			int[] next = null;
			for (int[] e : shuffled) {
				int[] other = e[0] == at[0] && e[1] == at[1] ? new int[] {e[2], e[3]} : e[2] == at[0] && e[3] == at[1] ? new int[] {e[0], e[1]} : null;
				if (other != null && !seen[other[0]][other[1]]) {
					next = other;
					chosen.add(e);
					break;
				}
			}
			if (next == null) {
				stack.remove(stack.size() - 1);
				continue;
			}
			seen[next[0]][next[1]] = true;
			stack.add(next);
		}
		for (int[] e : shuffled) if (!chosen.contains(e)) left.add(e);
		for (int i = 0; i < 2 && i < left.size(); i++) chosen.add(left.get(i));
		return chosen;
	}

	// ---- the race's blocks ----

	private static int hash(BlockPos pos) {
		return Math.floorMod(pos.getX() * 73 + pos.getY() * 151 + pos.getZ() * 37, 97);
	}

	/** A wall block (sides of tunnels and rooms). */
	private BlockState wall(BlockPos pos, int up) {
		return switch (race) {
			case ZERG -> switch (hash(pos) % 7) {
				case 0, 1 -> Blocks.MUD.defaultBlockState();
				case 2 -> up == 2 ? Blocks.SHROOMLIGHT.defaultBlockState() : Blocks.CRIMSON_HYPHAE.defaultBlockState();
				default -> Blocks.NETHER_WART_BLOCK.defaultBlockState();
			};
			case TERRAN -> up == 2 ? Blocks.CYAN_TERRACOTTA.defaultBlockState()
				: (pos.getX() + pos.getZ()) % 6 == 0 ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState() : Blocks.POLISHED_DEEPSLATE.defaultBlockState();
			case PROTOSS -> up == 2 ? (hash(pos) % 3 == 0 ? Blocks.CHISELED_SANDSTONE.defaultBlockState() : Blocks.PRISMARINE_BRICKS.defaultBlockState())
				: Blocks.CUT_SANDSTONE.defaultBlockState();
		};
	}

	private BlockState floor(BlockPos pos, boolean middle) {
		return switch (race) {
			case ZERG -> hash(pos) % 4 == 0 ? Blocks.NETHER_WART_BLOCK.defaultBlockState() : Blocks.MUD.defaultBlockState();
			case TERRAN -> middle ? Blocks.POLISHED_ANDESITE.defaultBlockState() : Blocks.YELLOW_CONCRETE.defaultBlockState();
			case PROTOSS -> middle ? Blocks.SMOOTH_SANDSTONE.defaultBlockState() : Blocks.BLUE_TERRACOTTA.defaultBlockState();
		};
	}

	private BlockState ceiling(BlockPos pos, boolean light) {
		if (light) return race == UnitKind.Race.ZERG ? Blocks.SHROOMLIGHT.defaultBlockState() : Blocks.SEA_LANTERN.defaultBlockState();
		return switch (race) {
			case ZERG -> Blocks.NETHER_WART_BLOCK.defaultBlockState();
			case TERRAN -> Blocks.SMOOTH_STONE.defaultBlockState();
			case PROTOSS -> Blocks.SANDSTONE.defaultBlockState();
		};
	}

	private BlockState seal() {
		return race == UnitKind.Race.PROTOSS ? Blocks.CUT_SANDSTONE.defaultBlockState() : Blocks.COBBLED_DEEPSLATE.defaultBlockState();
	}

	// ---- shapes ----

	private BlockPos mouth;

	/**
	 * A tunnel: its shell (`inside` false), its inside, or (null) its floor alone. A ramp steps a block every two; the way in climbs from its
	 * crossing until its floor reaches the ground, building no walls or roof above the ground there.
	 */
	private void tunnel(Tunnel t, @Nullable Boolean inside) {
		int dx = Integer.signum(t.b().getX() - t.a().getX()), dz = Integer.signum(t.b().getZ() - t.a().getZ());
		int length = Math.abs(t.b().getX() - t.a().getX()) + Math.abs(t.b().getZ() - t.a().getZ());
		int rise = t.b().getY() - t.a().getY();
		int px = -dz, pz = dx; // across
		int y = t.a().getY();
		for (int k = 0; k <= length; k++) {
			int x = t.a().getX() + dx * k, z = t.a().getZ() + dz * k;
			if (t.entrance()) {
				if (k > 0 && k % 2 == 0) y++;
				int ground = HiveStructure.ground(level, x, z);
				if (k > 0 && y >= ground) { // out on the surface: a gate over the way in
					if (inside == Boolean.TRUE) gate(new BlockPos(x, y, z), px, pz);
					else if (inside == Boolean.FALSE) mouth = new BlockPos(x, y, z);
					return;
				}
				section(new BlockPos(x, y, z), px, pz, dx, dz, k, inside, ground);
			} else {
				y = t.a().getY() + Math.round(rise * k / (float) Math.max(1, length));
				section(new BlockPos(x, y, z), px, pz, dx, dz, k, inside, Integer.MAX_VALUE);
			}
		}
	}

	/** One cross-section at `feet`: walls, floor and roof (none at or over `ground`), or the empty inside and its pipes. */
	private void section(BlockPos feet, int px, int pz, int dx, int dz, int k, @Nullable Boolean inside, int ground) {
		for (int s = -HALF - 1; s <= HALF + 1; s++)
			for (int up = -1; up <= HEIGHT; up++) {
				BlockPos pos = feet.offset(px * s, up, pz * s);
				boolean edge = Math.abs(s) == HALF + 1 || up == -1 || up == HEIGHT;
				if (inside == null) { // the floor alone
					if (up == -1 && Math.abs(s) <= HALF) painter.set(pos, floor(pos, Math.abs(s) < HALF));
					continue;
				}
				if (inside) {
					if (edge) continue;
					painter.carve(pos, seal());
					if (up == 0) floors.add(pos);
					if (up == HEIGHT - 1 && Math.abs(s) == HALF && race == UnitKind.Race.TERRAN) // pipes along the ceiling
						painter.set(pos, Blocks.LIGHTNING_ROD.defaultBlockState().setValue(LightningRodBlock.FACING,
							Direction.fromDelta(dx, 0, dz) == null ? Direction.EAST : Direction.fromDelta(dx, 0, dz)));
					continue;
				}
				if (up == -1) painter.set(pos, floor(pos, Math.abs(s) < HALF));
				else if (pos.getY() >= ground) continue; // the way in: no walls or roof in the open
				else if (up == HEIGHT) painter.set(pos, ceiling(pos, s == 0 && k % 6 == 3));
				else painter.set(pos, wall(pos, up));
			}
	}

	/** The gate over the way in: two posts and a lintel of the race's wall. */
	private void gate(BlockPos feet, int px, int pz) {
		BlockState post = race == UnitKind.Race.TERRAN ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState()
			: race == UnitKind.Race.PROTOSS ? Blocks.CHISELED_SANDSTONE.defaultBlockState() : Blocks.BONE_BLOCK.defaultBlockState();
		for (int s = -HALF - 1; s <= HALF + 1; s += 2 * HALF + 2)
			for (int up = 0; up < HEIGHT; up++) painter.set(feet.offset(px * s, up, pz * s), post);
		for (int s = -HALF - 1; s <= HALF + 1; s++) painter.set(feet.offset(px * s, HEIGHT, pz * s), post);
		BlockState light = race == UnitKind.Race.ZERG ? Blocks.SHROOMLIGHT.defaultBlockState() : Blocks.SEA_LANTERN.defaultBlockState();
		for (int s = -HALF - 1; s <= HALF + 1; s += 2 * HALF + 2) painter.set(feet.offset(px * s, HEIGHT + 1, pz * s), light);
	}

	private void room(Room room, boolean inside) {
		int h = room.half();
		BlockPos c = room.centre();
		for (int x = -h - 1; x <= h + 1; x++)
			for (int z = -h - 1; z <= h + 1; z++)
				for (int up = -1; up <= room.height(); up++) {
					BlockPos pos = c.offset(x, up, z);
					boolean edge = Math.abs(x) == h + 1 || Math.abs(z) == h + 1 || up == -1 || up == room.height();
					if (inside) {
						if (edge) continue;
						painter.carve(pos, seal());
						if (up == 0) floors.add(pos);
					} else if (up == -1) painter.set(pos, floor(pos, Math.abs(x) < h - 1 && Math.abs(z) < h - 1 || x == 0 || z == 0));
					else if (up == room.height()) painter.set(pos, ceiling(pos, (x == 0 || Math.abs(x) == h - 1) && Math.abs(z) == h - 1));
					else painter.set(pos, wall(pos, up));
				}
	}

	/** The great hall's shell: a floor three blocks thick and a dome over it. */
	private void hallShell(BlockPos hall) {
		int r = HALL_RADIUS + 1;
		for (int x = -r; x <= r; x++)
			for (int z = -r; z <= r; z++) {
				double d = Math.sqrt(x * x + z * z);
				if (d > r + 0.5) continue;
				for (int up = -3; up <= HALL_HEIGHT + 1; up++) {
					BlockPos pos = hall.offset(x, up, z);
					if (up < 0) painter.set(pos, up == -1 ? hallFloor(pos, d) : floor(pos, true));
					else if (shellAt(x, up, z)) painter.set(pos, up >= HALL_HEIGHT - 3 ? ceiling(pos, hash(pos) % 9 == 0) : wall(pos, up % 5));
				}
			}
	}

	/** Whether a cell over the floor is in the dome's shell (one block outside its inside). */
	private static boolean shellAt(int x, int up, int z) {
		return !insideHall(x, up, z) && (insideHall(x + 1, up, z) || insideHall(x - 1, up, z) || insideHall(x, up, z + 1)
			|| insideHall(x, up, z - 1) || insideHall(x, up - 1, z));
	}

	private static boolean insideHall(int x, int up, int z) {
		if (up < 0) return false;
		double r = HALL_RADIUS, h = HALL_HEIGHT;
		return (x * x + z * z) / (r * r) + up * up / (h * h) <= 1;
	}

	private BlockState hallFloor(BlockPos pos, double d) {
		boolean ring = d >= 9.5 && d < 10.5 || d >= 4 && d < 4.8;
		return switch (race) {
			case ZERG -> ring ? Blocks.BONE_BLOCK.defaultBlockState() : floor(pos, true);
			case TERRAN -> ring ? Blocks.YELLOW_CONCRETE.defaultBlockState() : (pos.getX() + pos.getZ()) % 2 == 0 ? Blocks.POLISHED_ANDESITE.defaultBlockState()
				: Blocks.IRON_BLOCK.defaultBlockState();
			case PROTOSS -> ring ? Blocks.GOLD_BLOCK.defaultBlockState() : d < 4 ? Blocks.CHISELED_QUARTZ_BLOCK.defaultBlockState() : Blocks.SMOOTH_SANDSTONE.defaultBlockState();
		};
	}

	private void hallInside(BlockPos hall) {
		for (int x = -HALL_RADIUS; x <= HALL_RADIUS; x++)
			for (int z = -HALL_RADIUS; z <= HALL_RADIUS; z++)
				for (int up = 0; up <= HALL_HEIGHT; up++)
					if (insideHall(x, up, z)) {
						BlockPos pos = hall.offset(x, up, z);
						painter.carve(pos, seal());
						if (up == 0) floors.add(pos);
					}
	}

	// ---- what is in them ----

	private UnitKind[] guards() {
		return switch (race) {
			case ZERG -> new UnitKind[] {UnitKind.ZERGLING, UnitKind.ZERGLING, UnitKind.ROACH, UnitKind.HYDRALISK};
			case TERRAN -> new UnitKind[] {UnitKind.MARINE, UnitKind.MARINE, UnitKind.MARINE, UnitKind.MARAUDER};
			case PROTOSS -> new UnitKind[] {UnitKind.ZEALOT, UnitKind.ZEALOT, UnitKind.ADEPT, UnitKind.ADEPT};
		};
	}

	private BlockPos spot(Room room) {
		int r = Math.max(1, room.half() - 2);
		return room.centre().offset(random.nextInt(2 * r + 1) - r, 0, random.nextInt(2 * r + 1) - r);
	}

	/** A room's things against its walls, its guards (and, in a zerg room, sleeping eggs). */
	private void furnish(Room room) {
		BlockPos c = room.centre();
		int h = room.half();
		UnitKind[] guards = guards();
		int count = room.big() ? 2 + random.nextInt(2) : random.nextInt(2);
		for (int i = 0; i < count; i++) spawns.add(Spawn.guard(guards[random.nextInt(guards.length)], spot(room), c));
		if (!room.big()) return;
		// the corners: a pipe up (terran), a bone rib (zerg), a pillar with a light (protoss)
		for (int sx = -1; sx <= 1; sx += 2)
			for (int sz = -1; sz <= 1; sz += 2) {
				BlockPos corner = c.offset(sx * h, 0, sz * h);
				for (int up = 0; up < room.height(); up++) painter.set(corner.above(up), switch (race) {
					case TERRAN -> up % 3 == 2 ? Blocks.WAXED_COPPER_BLOCK.defaultBlockState() : Blocks.CHAIN.defaultBlockState();
					case ZERG -> Blocks.BONE_BLOCK.defaultBlockState();
					case PROTOSS -> up == room.height() - 2 ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.PRISMARINE_BRICKS.defaultBlockState();
				});
			}
		switch (race) {
			case TERRAN -> { // consoles and lockers by the north wall, a cell of bars by the south
				BlockState[] consoles = {Blocks.BLAST_FURNACE.defaultBlockState(), Blocks.LECTERN.defaultBlockState(),
					Blocks.SMITHING_TABLE.defaultBlockState(), Blocks.REDSTONE_LAMP.defaultBlockState(), Blocks.OBSERVER.defaultBlockState()};
				for (int x = -h + 2; x <= h - 2; x += 2) painter.set(c.offset(x, 0, -h), consoles[random.nextInt(consoles.length)]);
				for (int up = 0; up < 3; up++) {
					painter.set(c.offset(-2, up, h), Blocks.IRON_BARS.defaultBlockState());
					painter.set(c.offset(2, up, h), Blocks.IRON_BARS.defaultBlockState());
				}
				if (random.nextBoolean()) painter.chest(c.offset(h, 0, 0), FactionLoot.TERRAN);
			}
			case ZERG -> { // a growth of light in the middle of the roof, sleeping eggs on the floor
				painter.set(c.above(room.height() - 1), Blocks.SHROOMLIGHT.defaultBlockState());
				UnitKind[] brood = {UnitKind.ZERGLING, UnitKind.ROACH, UnitKind.HYDRALISK, UnitKind.ZERGLING};
				for (int i = 1 + random.nextInt(2); i > 0; i--) {
					UnitKind kind = brood[random.nextInt(brood.length)];
					spawns.add(new Spawn(UnitKind.EGG, spot(room), c.immutable(), kind, kind == UnitKind.ZERGLING ? 2 : 1));
				}
				if (random.nextInt(3) == 0) painter.chest(c.offset(h, 0, 0), FactionLoot.ZERG);
			}
			case PROTOSS -> { // a statue on a pedestal and a khaydarin crystal
				BlockPos statue = c.offset(0, 0, -h + 1);
				painter.set(statue, Blocks.CHISELED_SANDSTONE.defaultBlockState());
				painter.set(statue.above(), Blocks.YELLOW_TERRACOTTA.defaultBlockState());
				painter.set(statue.above(2), Blocks.YELLOW_TERRACOTTA.defaultBlockState());
				painter.set(statue.above(3), Blocks.SEA_LANTERN.defaultBlockState());
				BlockPos crystal = c.offset(h - 1, 0, h - 1);
				painter.set(crystal, Blocks.AMETHYST_BLOCK.defaultBlockState());
				painter.set(crystal.above(), Blocks.AMETHYST_CLUSTER.defaultBlockState());
				if (random.nextBoolean()) painter.chest(c.offset(-h + 1, 0, h - 1), FactionLoot.PROTOSS);
			}
		}
	}

	/** The great hall: pillars round its edge, lights, the race's mark round the middle, a chest; the boss is the caller's. */
	private void furnishHall(BlockPos hall) {
		for (int i = 0; i < 8; i++) {
			double a = Math.PI * 2 * i / 8 + Math.PI / 8;
			BlockPos base = hall.offset((int) Math.round(Math.cos(a) * 12), 0, (int) Math.round(Math.sin(a) * 12));
			for (int up = 0; up <= HALL_HEIGHT; up++) {
				if (!insideHall(base.getX() - hall.getX(), up, base.getZ() - hall.getZ())) break;
				painter.set(base.above(up), switch (race) {
					case ZERG -> up % 4 == 3 ? Blocks.SHROOMLIGHT.defaultBlockState() : Blocks.BONE_BLOCK.defaultBlockState();
					case TERRAN -> up % 4 == 3 ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.IRON_BLOCK.defaultBlockState();
					case PROTOSS -> up % 4 == 3 ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.PRISMARINE_BRICKS.defaultBlockState();
				});
			}
		}
		painter.chest(hall.offset(0, 0, HALL_RADIUS - 3), switch (race) {
			case ZERG -> FactionLoot.ZERG;
			case TERRAN -> FactionLoot.TERRAN;
			case PROTOSS -> FactionLoot.PROTOSS;
		});
	}
}
