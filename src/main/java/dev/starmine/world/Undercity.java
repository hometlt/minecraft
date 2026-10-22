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
	/** Cells the city carved (the caves' ways out stop where they meet the land's own caves, not these). */
	private final it.unimi.dsi.fastutil.longs.LongOpenHashSet ours = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	/** Floor cells under a lake of creep (no creep layer is laid over them). */
	private final it.unimi.dsi.fastutil.longs.LongOpenHashSet lakes = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	/** The ways of the cave tunnels, kept for the shell, the inside and the ambushes alike. */
	private final java.util.Map<Tunnel, List<double[]>> paths = new java.util.HashMap<>();

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

		// walls first, then the insides, then what furnishes them. Owner 22.10: the zerg's are caves — winding tunnels,
		// spiral ways down, rooms of uneven lobes with lakes of creep, ways out into the caves round them, zerg lying in wait
		boolean cave = race == UnitKind.Race.ZERG;
		for (Tunnel tunnel : tunnels) if (cave) caveTunnel(tunnel, false); else tunnel(tunnel, false);
		for (Room room : rooms) if (cave) caveRoom(room, false); else room(room, false);
		hallShell(hall);
		for (Tunnel tunnel : tunnels) if (cave) caveTunnel(tunnel, true); else tunnel(tunnel, true);
		for (Room room : rooms) if (cave) caveRoom(room, true); else room(room, true);
		hallInside(hall);
		// a ramp's floor where a room or the hall was carved over it: a slope down into the room, not a drop it cannot climb
		if (!cave) for (Tunnel tunnel : tunnels) if (tunnel.entrance() || tunnel.a().getY() != tunnel.b().getY()) tunnel(tunnel, null);
		if (cave) {
			for (Tunnel tunnel : tunnels) ambush(tunnel);
			for (Room room : rooms) wayOut(room);
		}
		for (Room room : rooms) furnish(room);
		storages();
		furnishHall(hall);
		if (race == UnitKind.Race.ZERG) { // creep over the floors, once the blocks are in
			List<BlockPos> lined = List.copyOf(floors);
			painter.then(() -> {
				for (BlockPos pos : lined) {
					BlockState there = level.getBlockState(pos);
					if (!there.isAir() || lakes.contains(pos.asLong())) continue;
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
					if (edge || !arch(s, up)) continue;
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

	/**
	 * Owner 22.10: the protoss' halls and ways are round — a tunnel's hollow under an arched roof (its flat floor kept),
	 * a room round under a dome. The others' are square (the zerg's caves have their own shapes).
	 */
	private boolean arch(int s, int up) {
		if (race != UnitKind.Race.PROTOSS) return true;
		double across = s / (HALF + 0.5), rise = Math.max(0, up - 2) / (HEIGHT - 2.5);
		return across * across + rise * rise <= 1;
	}

	private boolean dome(Room room, int x, int up, int z) {
		if (race != UnitKind.Race.PROTOSS) return true;
		double r = room.half() + 0.5, across = (x * x + z * z) / (r * r), rise = Math.max(0, up - 3) / (room.height() - 3.5);
		return across + rise * rise <= 1;
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
						if (edge || !dome(room, x, up, z)) continue;
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
				if (d > r + 0.5 && race != UnitKind.Race.TERRAN) continue;
				for (int up = -3; up <= HALL_HEIGHT + 1; up++) {
					BlockPos pos = hall.offset(x, up, z);
					if (up < 0) painter.set(pos, up == -1 ? hallFloor(pos, d) : floor(pos, true));
					else if (shellAt(x, up, z)) painter.set(pos, up >= HALL_HEIGHT - 3 ? ceiling(pos, hash(pos) % 9 == 0) : wall(pos, up % 5));
				}
			}
	}

	/** Whether a cell over the floor is in the dome's shell (one block outside its inside). */
	private boolean shellAt(int x, int up, int z) {
		return !insideHall(x, up, z) && (insideHall(x + 1, up, z) || insideHall(x - 1, up, z) || insideHall(x, up, z + 1)
			|| insideHall(x, up, z - 1) || insideHall(x, up - 1, z));
	}

	/**
	 * The great hall's hollow: a dome (owner 22.10: the protoss' round, the zerg's a cave of flesh round it), the terrans'
	 * a hangar — square, straight walls, a flat roof.
	 */
	private boolean insideHall(int x, int up, int z) {
		if (up < 0) return false;
		if (race == UnitKind.Race.TERRAN) return Math.abs(x) <= HALL_RADIUS - 3 && Math.abs(z) <= HALL_RADIUS - 3 && up < HALL_HEIGHT - 4;
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
		if (race == UnitKind.Race.ZERG) { // owner 22.10: a cave room: a lake of creep in some, eggs, a growth of light
			lake(room);
			painter.set(c.above(room.height() - 1), Blocks.SHROOMLIGHT.defaultBlockState());
			UnitKind[] brood = {UnitKind.ZERGLING, UnitKind.ROACH, UnitKind.HYDRALISK, UnitKind.ZERGLING};
			for (int i = 1 + random.nextInt(2); i > 0; i--) {
				UnitKind kind = brood[random.nextInt(brood.length)];
				spawns.add(new Spawn(UnitKind.EGG, spot(room), c.immutable(), kind, kind == UnitKind.ZERGLING ? 2 : 1));
			}
			if (random.nextInt(3) == 0) painter.chest(c.offset(Math.max(1, h - 2), 0, 0), FactionLoot.ZERG);
			return;
		}
		// the corners: a pipe up (terran), a bone rib (zerg), a pillar with a light (protoss)
		for (int sx = -1; sx <= 1; sx += 2)
			for (int sz = -1; sz <= 1; sz += 2) {
				int at = race == UnitKind.Race.PROTOSS ? (int) Math.floor(h * 0.68) : h; // the protoss' on the round wall
				BlockPos corner = c.offset(sx * at, 0, sz * at);
				for (int up = 0; up < room.height(); up++) painter.set(corner.above(up), switch (race) {
					case TERRAN -> up % 3 == 2 ? Blocks.WAXED_COPPER_BLOCK.defaultBlockState() : Blocks.CHAIN.defaultBlockState();
					case ZERG -> Blocks.BONE_BLOCK.defaultBlockState();
					case PROTOSS -> up == room.height() - 2 ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.PRISMARINE_BRICKS.defaultBlockState();
				});
			}
		switch (race) {
			case TERRAN -> { // owner 22.10: a barracks of bunks, a test chamber, or a hangar; consoles by the north wall
				BlockState[] consoles = {Blocks.BLAST_FURNACE.defaultBlockState(), Blocks.LECTERN.defaultBlockState(),
					Blocks.SMITHING_TABLE.defaultBlockState(), Blocks.REDSTONE_LAMP.defaultBlockState(), Blocks.OBSERVER.defaultBlockState()};
				for (int x = -h + 2; x <= h - 2; x += 2) painter.set(c.offset(x, 0, -h), consoles[random.nextInt(consoles.length)]);
				switch (random.nextInt(3)) {
					case 0 -> barracks(c, h);
					case 1 -> testChamber(c, h);
					default -> hangar(c, h);
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

	/** A barracks room: two rows of bunks (beds, their heads to the walls) and a locker by each. */
	private void barracks(BlockPos c, int h) {
		for (int x = -h + 1; x <= h - 2; x += 2)
			for (int side = -1; side <= 1; side += 2) {
				Direction facing = side < 0 ? Direction.NORTH : Direction.SOUTH; // the head towards the wall
				BlockPos head = c.offset(x, 0, side * (h - 1)), foot = head.relative(facing.getOpposite());
				BlockState bed = Blocks.LIGHT_GRAY_BED.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.FACING, facing);
				painter.set(foot, bed.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.FOOT));
				painter.set(head, bed.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD));
				if (x + 1 <= h - 1) painter.set(c.offset(x + 1, 0, side * (h - 1)), Blocks.BARREL.defaultBlockState());
			}
	}

	/**
	 * A test chamber: a cell of glass and iron in the middle with a specimen of another race in it (a captive zergling
	 * or zealot that wakes when the cell breaks), lamps and an observer watching it.
	 */
	private void testChamber(BlockPos c, int h) {
		for (int x = -2; x <= 2; x++)
			for (int z = -2; z <= 2; z++)
				for (int up = 0; up <= 3; up++) {
					boolean wall = Math.abs(x) == 2 || Math.abs(z) == 2 || up == 3;
					if (!wall) continue;
					boolean frame = Math.abs(x) == 2 && Math.abs(z) == 2 || up == 3 && (x == 0 || z == 0);
					painter.set(c.offset(x, up, z), frame ? Blocks.IRON_BLOCK.defaultBlockState() : Blocks.GLASS.defaultBlockState());
				}
		painter.set(c.offset(0, -1, 0), Blocks.REDSTONE_LAMP.defaultBlockState().setValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT, true));
		painter.set(c.offset(0, 4, 0), Blocks.OBSERVER.defaultBlockState());
		for (int sx = -1; sx <= 1; sx += 2) painter.set(c.offset(sx * 3, 0, 0), Blocks.LECTERN.defaultBlockState());
		UnitKind specimen = random.nextBoolean() ? UnitKind.ZERGLING : UnitKind.ZEALOT;
		spawns.add(Spawn.guard(specimen, c.immutable(), c.immutable()));
	}

	/** A hangar: a landing pad of yellow and black stripes, crates of parts round it, a vehicle parked on it at times. */
	private void hangar(BlockPos c, int h) {
		int r = Math.max(2, h - 2);
		for (int x = -r; x <= r; x++)
			for (int z = -r; z <= r; z++)
				if (Math.abs(x) == r || Math.abs(z) == r)
					painter.set(c.offset(x, -1, z), (x + z & 1) == 0 ? Blocks.YELLOW_CONCRETE.defaultBlockState() : Blocks.BLACK_CONCRETE.defaultBlockState());
		for (int k = 0; k < 3; k++) painter.set(c.offset(-h + 1, k / 2, h - 1 - k % 2), Blocks.IRON_BLOCK.defaultBlockState());
		painter.set(c.offset(h - 1, 0, h - 1), Blocks.ANVIL.defaultBlockState());
		if (random.nextInt(3) == 0) spawns.add(Spawn.guard(random.nextBoolean() ? UnitKind.HELLION : UnitKind.WIDOW_MINE, c.immutable(), c.immutable()));
	}

	// ---- the zerg's caves (owner 22.10) ----

	/** A smooth bump of size about one from a position along a way (a few sines). */
	private static double wave(double s, double seed) {
		return (Math.sin(s * 0.21 + seed) + Math.sin(s * 0.47 + seed * 1.7) * 0.6 + Math.sin(s * 0.93 + seed * 2.3) * 0.3) / 1.9;
	}

	/**
	 * The way of a cave tunnel, points (x, y, z) a block apart: the way in climbs to the ground winding; a way between
	 * storeys (or down to the hall) spirals round a pillar of rock; a flat one bends aside. Kept, so its shell, its inside
	 * and its ambush follow one way.
	 */
	private List<double[]> cavePath(Tunnel t) {
		List<double[]> known = paths.get(t);
		if (known != null) return known;
		java.util.Random r = new java.util.Random(t.a().asLong() * 31 + t.b().asLong());
		double ax = t.a().getX() + 0.5, ay = t.a().getY(), az = t.a().getZ() + 0.5, bx = t.b().getX() + 0.5, by = t.b().getY(), bz = t.b().getZ() + 0.5;
		List<double[]> line = new ArrayList<>();
		if (t.entrance()) { // up to the ground, winding a little
			int dx = Integer.signum((int) (bx - ax)), dz = Integer.signum((int) (bz - az));
			double y = ay;
			for (int k = 0; k <= Math.abs(bx - ax) + Math.abs(bz - az); k++) {
				double side = 2 * Math.sin(k * 0.22);
				double x = ax + dx * k - dz * side, z = az + dz * k + dx * side;
				if (k > 0 && k % 2 == 0) y++;
				line.add(new double[] {x, y, z});
				if (k > 0 && y >= HiveStructure.ground(level, (int) Math.floor(x), (int) Math.floor(z))) {
					mouth = BlockPos.containing(x, y, z);
					break;
				}
			}
			paths.put(t, line);
			return line;
		}
		double ux = bx - ax, uz = bz - az, flat = Math.max(1e-6, Math.hypot(ux, uz)), px = -uz / flat, pz = ux / flat;
		double rise = by - ay;
		List<double[]> plan = new ArrayList<>(); // x, z (heights come after, by length)
		if (Math.abs(rise) >= 6) { // a spiral round a pillar beside the line's middle
			double cx = (ax + bx) / 2 + px * 7, cz = (az + bz) / 2 + pz * 7, radius = 6;
			double start = Math.atan2(az - cz, ax - cx), end = Math.atan2(bz - cz, bx - cx);
			double turn = r.nextBoolean() ? 1 : -1, sweep = ((end - start) * turn + Math.PI * 4) % (Math.PI * 2) + Math.PI * 2; // one whole turn and more
			plan.add(new double[] {ax, az});
			for (double a = 0; a <= sweep; a += 0.12) plan.add(new double[] {cx + Math.cos(start + a * turn) * radius, cz + Math.sin(start + a * turn) * radius});
			plan.add(new double[] {bx, bz});
		} else { // a bend aside
			double bulge = (r.nextDouble() * 2 - 1) * 8, mx = (ax + bx) / 2 + px * bulge, mz = (az + bz) / 2 + pz * bulge;
			for (double k = 0; k <= 1.0001; k += 0.02) {
				double u = 1 - k;
				plan.add(new double[] {u * u * ax + 2 * u * k * mx + k * k * bx, u * u * az + 2 * u * k * mz + k * k * bz});
			}
		}
		// points a block apart along the plan, heights by the length gone (a steady slope)
		double total = 0;
		for (int i = 1; i < plan.size(); i++) total += Math.hypot(plan.get(i)[0] - plan.get(i - 1)[0], plan.get(i)[1] - plan.get(i - 1)[1]);
		double gone = 0, next = 0;
		for (int i = 1; i < plan.size(); i++) {
			double[] p0 = plan.get(i - 1), p1 = plan.get(i);
			double piece = Math.hypot(p1[0] - p0[0], p1[1] - p0[1]);
			while (next <= gone + piece) {
				double f = piece < 1e-9 ? 0 : (next - gone) / piece;
				line.add(new double[] {p0[0] + (p1[0] - p0[0]) * f, ay + rise * Math.min(1, next / Math.max(1e-6, total)), p0[1] + (p1[1] - p0[1]) * f});
				next += 1;
			}
			gone += piece;
		}
		line.add(new double[] {bx, by, bz});
		paths.put(t, line);
		return line;
	}

	/** A cave tunnel's walls and floor (`inside` false) or its hollow: a tube a little wider and lower here and there. */
	private void caveTunnel(Tunnel t, boolean inside) {
		List<double[]> line = cavePath(t);
		double seed = (t.a().asLong() & 1023) * 0.37;
		for (int i = 0; i < line.size(); i++) {
			double[] p = line.get(i);
			double radius = 2.6 + 0.7 * wave(i, seed), height = 4.4 + 0.8 * wave(i, seed + 5);
			int ground = t.entrance() ? HiveStructure.ground(level, (int) Math.floor(p[0]), (int) Math.floor(p[2])) : Integer.MAX_VALUE;
			blob(p[0], (int) Math.floor(p[1]), p[2], radius, height, inside, ground);
		}
	}

	/**
	 * One round hollow on a floor at `floorY` (radius across, height up, a dome): its shell and floor of the zerg's
	 * blocks, or its inside carved. Nothing is built at or over `ground` (the way in, out in the open).
	 */
	private void blob(double x, int floorY, double z, double radius, double height, boolean inside, int ground) {
		int r = (int) Math.ceil(radius) + 2, top = (int) Math.ceil(height) + 1;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int bx = (int) Math.floor(x - r); bx <= (int) Math.floor(x + r); bx++)
			for (int bz = (int) Math.floor(z - r); bz <= (int) Math.floor(z + r); bz++) {
				double d = Math.hypot(bx + 0.5 - x, bz + 0.5 - z);
				for (int up = -2; up <= top; up++) {
					pos.set(bx, floorY + up, bz);
					double e = (d / radius) * (d / radius) + (up / height) * (up / height) * 0.7;
					boolean in = up >= 0 && e <= 1;
					if (inside) {
						if (!in) continue;
						BlockPos at = pos.immutable();
						painter.carve(at, seal());
						ours.add(at.asLong());
						if (up == 0) floors.add(at);
						continue;
					}
					if (in || pos.getY() >= ground) continue;
					double shell = (d / (radius + 1.3)) * (d / (radius + 1.3)) + (up / (height + 1.3)) * (up / (height + 1.3)) * 0.7;
					if (up < 0 ? d <= radius + 0.8 : shell <= 1) painter.set(pos.immutable(), up < 0 ? floor(pos, d < radius - 1) : wall(pos, up % 5));
				}
			}
	}

	/** A cave room: the room's middle and a few lobes round it, each a dome of its own size: an uneven hollow. */
	private void caveRoom(Room room, boolean inside) {
		java.util.Random r = new java.util.Random(room.centre().asLong());
		BlockPos c = room.centre();
		blob(c.getX() + 0.5, c.getY(), c.getZ() + 0.5, room.half() + 1.5, room.height(), inside, Integer.MAX_VALUE);
		int lobes = room.big() ? 3 + r.nextInt(2) : 1 + r.nextInt(2);
		for (int i = 0; i < lobes; i++) {
			double a = r.nextDouble() * Math.PI * 2, d = 3 + r.nextDouble() * 3;
			blob(c.getX() + 0.5 + Math.cos(a) * d, c.getY() + (r.nextInt(4) == 0 ? 1 : 0), c.getZ() + 0.5 + Math.sin(a) * d,
				3 + r.nextDouble() * 1.8, room.height() - 1 - r.nextInt(3), inside, Integer.MAX_VALUE);
		}
	}

	/** A lake of creep in some cave rooms: the floor over a few blocks is liquid creep (heals the zerg, slows the rest). */
	private void lake(Room room) {
		if (random.nextInt(5) >= 2) return;
		double a = random.nextDouble() * Math.PI * 2, radius = 2 + random.nextDouble() * 1.5;
		BlockPos c = room.centre().offset((int) Math.round(Math.cos(a) * 3), 0, (int) Math.round(Math.sin(a) * 3));
		for (int dx = -3; dx <= 3; dx++)
			for (int dz = -3; dz <= 3; dz++) {
				if (dx * dx + dz * dz > radius * radius) continue;
				BlockPos floor = c.offset(dx, -1, dz);
				if (!ours.contains(floor.above().asLong())) continue;
				painter.set(floor, dev.starmine.ability.BossPowers.LIQUID_CREEP.defaultBlockState());
				painter.set(floor.below(), Blocks.MUD.defaultBlockState());
				lakes.add(floor.above().asLong());
			}
	}

	/** Zerg lying in wait burrowed halfway along a flat tunnel (some tunnels): they come up at a foe near them. */
	private void ambush(Tunnel t) {
		if (t.entrance() || t.a().getY() != t.b().getY() || random.nextInt(5) >= 3) return;
		List<double[]> line = cavePath(t);
		UnitKind[] kinds = {UnitKind.ZERGLING, UnitKind.ZERGLING, UnitKind.BANELING, UnitKind.ROACH, UnitKind.HYDRALISK, UnitKind.LURKER};
		int count = 1 + random.nextInt(3);
		for (int k = 0; k < count; k++) {
			double[] p = line.get((int) (line.size() * (0.35 + 0.3 * random.nextDouble())));
			BlockPos at = BlockPos.containing(p[0], Math.floor(p[1]), p[2]);
			UnitKind kind = kinds[random.nextInt(kinds.length)];
			spawns.add(Spawn.burrowed(kind, at, at));
		}
	}

	/**
	 * A way out of a cave room into the land's own caves near it (owner 22.10), now and then: a narrow winding tunnel
	 * outward, made only when it meets a cave (air the city did not carve) within {@link #WAY_OUT} blocks, and not water.
	 */
	private static final int WAY_OUT = 48;

	private void wayOut(Room room) {
		if (random.nextBoolean()) return;
		double a = random.nextDouble() * Math.PI * 2, x = room.centre().getX() + 0.5, y = room.centre().getY(), z = room.centre().getZ() + 0.5;
		List<double[]> way = new ArrayList<>();
		BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
		for (int step = 0; step < WAY_OUT; step++) {
			a += (random.nextDouble() - 0.5) * 0.5;
			x += Math.cos(a);
			z += Math.sin(a);
			if (step % 6 == 5) y += random.nextInt(3) - 1;
			way.add(new double[] {x, y, z});
			if (step < room.half() + 3) continue;
			int air = 0;
			boolean wet = false;
			for (int dx = -1; dx <= 1; dx++)
				for (int dy = 0; dy <= 2; dy++)
					for (int dz = -1; dz <= 1; dz++) {
						probe.set(Math.floor(x) + dx, Math.floor(y) + dy, Math.floor(z) + dz);
						if (ours.contains(probe.asLong())) continue;
						var state = level.getBlockState(probe);
						if (!state.getFluidState().isEmpty()) wet = true;
						else if (state.isAir()) air++;
					}
			if (wet) return; // it would let water in
			if (air >= 10) { // a cave of the land's own: the way is carved to it
				for (double[] p : way) {
					BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
					for (int dx = -1; dx <= 1; dx++)
						for (int dz = -1; dz <= 1; dz++)
							for (int dy = 0; dy <= 3; dy++) {
								if (Math.abs(dx) + Math.abs(dz) > 1 && dy == 3) continue;
								at.set(Math.floor(p[0]) + dx, Math.floor(p[1]) + dy, Math.floor(p[2]) + dz);
								painter.carve(at.immutable(), seal());
							}
					at.set(Math.floor(p[0]), Math.floor(p[1]) - 1, Math.floor(p[2]));
					if (level.getBlockState(at).isAir()) painter.set(at.immutable(), floor(at, true)); // across a gap: a floor
				}
				return;
			}
		}
	}

	// ---- storages (owner 22.10) ----

	/**
	 * Stores of minerals and vespene: in two rooms of every city, a stack of mineral blocks (a rich one now and then) and
	 * full vespene canisters against a wall — mined by hand or by workers, carried home.
	 */
	private void storages() {
		List<Room> big = rooms.stream().filter(Room::big).toList();
		if (big.isEmpty()) return;
		for (int n = 0; n < 2; n++) {
			Room room = big.get(random.nextInt(big.size()));
			BlockPos c = room.centre();
			int h = Math.max(2, room.half() - 2);
			BlockPos minerals = c.offset(-h, 0, -h + 1), gas = c.offset(h - 1, 0, -h + 1);
			for (int dx = 0; dx < 3; dx++)
				for (int dz = 0; dz < 2; dz++)
					for (int dy = 0; dy < 2 - dz; dy++)
						painter.set(minerals.offset(dx, dy, dz), (random.nextInt(5) == 0 ? dev.starmine.mineral.Minerals.RICH_BLOCK : dev.starmine.mineral.Minerals.BLOCK)
							.defaultBlockState());
			var canister = dev.starmine.vespene.Vespene.CANISTER.defaultBlockState()
				.setValue(dev.starmine.vespene.VespeneCanisterBlock.FILL, dev.starmine.vespene.VespeneCanisterBlock.CAPACITY);
			for (int k = 0; k < 2 + random.nextInt(3); k++) painter.set(gas.offset(k % 2, k / 2, 0), canister);
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
