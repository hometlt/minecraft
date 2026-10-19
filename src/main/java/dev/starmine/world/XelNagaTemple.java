package dev.starmine.world;

import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.HiveSavedData;
import dev.starmine.hive.HiveStructure;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The xel'naga temples (owner 19.10): one in each world with heroes — the overworld, the Nether, the End — where that
 * world's key opens the way to the Void.
 *
 * - Where: one only, {@link #site} — the overworld's 50-100 chunks from the spawn (on land), the Nether's 200-400 blocks
 *   from the spawn's place there, grown into its rock at {@link #NETHER_FLOOR}, the End's 1100-1500 blocks out, on an
 *   island of its own where the End is empty. Built when a player comes near ({@link #TRIGGER}).
 * - A pyramid of xel'naga metal ({@link #METAL}, {@link #GLYPH}), which nothing breaks, {@link #HALF} blocks to each side
 *   and as high; a wide passage leads in from the south to its door ({@link #DOOR}), which nothing breaks either and which
 *   opens only to a part of that world's key ({@link Door}).
 * - Inside, on the ground, a labyrinth of {@link #CELLS}×{@link #CELLS} rooms joined by passages {@link #PASSAGE} wide and
 *   {@link #HEIGHT} high — thors, colossi and flyers pass — kept by the xel'naga constructs and sentinels and void
 *   monoliths (Zeratul's co-op avatars and monolith, wild, of no side), chests in some of its rooms.
 * - In its middle the hall of keys: three sockets ({@link #SOCKET}) before a great triangle of glowing glyphs. A part of
 *   that world's key set on each socket (three races) opens the portal in the triangle ({@link #PORTAL}); taking one
 *   off closes it. Through it a player and their army ({@link #ESCORT} blocks round them) go to the Void, to a triangle
 *   of that world by the Void's first island, and back — so from the Void to any temple whose portal is open
 *   ({@link #pass}).
 * - A part of a key in hand, used in the air, tells where its world's temple is ({@link #pointTo}).
 */
public final class XelNagaTemple {
	/** The labyrinth: rooms a side, a room's pitch, its walls' thickness, the passages' clear height and the roof over them. */
	public static final int CELLS = 7, CELL = 14, WALL = 4, PASSAGE = CELL - WALL, HEIGHT = 14, ROOF = 3;
	/** The pyramid's half side at the ground (it is as high), the labyrinth's first wall line from the middle. */
	public static final int HALF = 76, EDGE = -51;
	/** The hall of keys: its half side (rooms 2-4 of the labyrinth), its clear height; the portal's plane, half base and rows. */
	public static final int HALL_LOW = EDGE + 2 * CELL + WALL, HALL_HIGH = EDGE + 5 * CELL - 1, HALL_HEIGHT = 26;
	public static final int PORTAL_Z = -12, PORTAL_HALF = 9, PORTAL_ROWS = 2 * PORTAL_HALF;
	/** The sockets: before the portal, a part on each. */
	public static final int SOCKET_Z = PORTAL_Z + 8;
	public static final int[] SOCKET_X = {-6, 0, 6};
	/** The door across the entrance passage (under the pyramid's slope); the passage's sides. */
	public static final int DOOR_Z = 60, GATE_LOW = EDGE + 3 * CELL + WALL, GATE_HIGH = GATE_LOW + PASSAGE - 1;
	/** How near a player comes before a temple is built; how far round a player their units go with them through the portal. */
	public static final int TRIGGER = 220, ESCORT = 16;
	public static final int NETHER_FLOOR = 40;
	/** The temples' keepers wait as nest dwellers of no base. */
	static final UUID KEEPERS = new UUID(0x7E3B1EL, 0x1L);
	public static final Block METAL, GLYPH, DOOR, SOCKET, PORTAL;
	static final Map<UUID, Long> RESTING = new HashMap<>();

	static {
		METAL = block("xelnaga_metal", new Block(unbreakable().sound(SoundType.NETHERITE_BLOCK)));
		GLYPH = block("xelnaga_glyph", new Block(unbreakable().sound(SoundType.NETHERITE_BLOCK).lightLevel(state -> 15)));
		DOOR = block("xelnaga_door", new Door(unbreakable().sound(SoundType.NETHERITE_BLOCK).lightLevel(state -> 7)));
		SOCKET = block("xelnaga_socket", new Block(unbreakable().sound(SoundType.AMETHYST).lightLevel(state -> 9)));
		PORTAL = Registry.register(BuiltInRegistries.BLOCK, StarMine.id("xelnaga_portal"), new Portal(unbreakable().noCollission().noOcclusion()
			.lightLevel(state -> 13)));
	}

	private static BlockBehaviour.Properties unbreakable() {
		return BlockBehaviour.Properties.of().strength(-1.0f, 3600000.0f).noLootTable().pushReaction(PushReaction.BLOCK);
	}

	private static Block block(String name, Block block) {
		Registry.register(BuiltInRegistries.BLOCK, StarMine.id(name), block);
		Registry.register(BuiltInRegistries.ITEM, StarMine.id(name), new BlockItem(block, new Item.Properties().rarity(Rarity.RARE)));
		return block;
	}

	private XelNagaTemple() {
	}

	public static void load() {
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(entries -> {
			for (Block block : List.of(METAL, GLYPH, DOOR, SOCKET)) entries.accept(block);
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(XelNagaTemple::tick);
	}

	// ---- where ----

	/** Where a world's temple stands (the middle of its floor once built), chosen the first time it is asked. */
	public static BlockPos site(MinecraftServer server, Artifact.World world) {
		VoidWorld.State state = VoidWorld.State.get(server);
		BlockPos known = state.templeSites.get(world.name());
		if (known != null) return known;
		ServerLevel level = server.getLevel(world.dimension());
		RandomSource random = RandomSource.create(server.overworld().getSeed() ^ 0x7E3B1E_5A17L ^ (world.ordinal() + 1) * 0x9E3779B97F4A7C15L);
		BlockPos spawn = server.overworld().getSharedSpawnPos();
		BlockPos origin = switch (world) {
			case OVERWORLD -> spawn;
			case NETHER -> new BlockPos(spawn.getX() / 8, 0, spawn.getZ() / 8);
			case END -> BlockPos.ZERO;
		};
		int min = switch (world) { case OVERWORLD -> 800; case NETHER -> 200; case END -> 1100; };
		int spread = switch (world) { case OVERWORLD -> 800; case NETHER -> 200; case END -> 400; };
		BlockPos pick = null;
		for (int attempt = 0; attempt < 24 && pick == null; attempt++) {
			double angle = random.nextDouble() * Math.PI * 2, distance = min + random.nextInt(spread + 1);
			BlockPos column = origin.offset(Mth.floor(Math.cos(angle) * distance), 0, Mth.floor(Math.sin(angle) * distance));
			if (world == Artifact.World.OVERWORLD && level != null && attempt < 23 && wet(level, column)) continue; // on land
			pick = column;
		}
		state.templeSites.put(world.name(), pick);
		state.setDirty();
		return pick;
	}

	/** Whether the land round a column is sea, river or shore (read from the biomes, no chunk made). */
	private static boolean wet(ServerLevel level, BlockPos column) {
		var source = level.getChunkSource();
		for (int[] d : new int[][] {{0, 0}, {HALF, 0}, {-HALF, 0}, {0, HALF}, {0, -HALF}}) {
			Holder<Biome> biome = source.getGenerator().getBiomeSource().getNoiseBiome((column.getX() + d[0]) >> 2, 16, (column.getZ() + d[1]) >> 2,
				source.randomState().sampler());
			if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER) || biome.is(BiomeTags.IS_BEACH)) return true;
		}
		return false;
	}

	/** Each second: a world's temple is built when a player (or their unit, or eye) comes near its site. */
	static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 11 || !server.overworld().getGameRules().getBoolean(StarMine.SPAWN_BASES)) return;
		VoidWorld.State state = VoidWorld.State.get(server);
		for (Artifact.World world : Artifact.World.values()) {
			if (state.temples.contains(world.name())) continue;
			ServerLevel level = server.getLevel(world.dimension());
			if (level == null || level.players().isEmpty()) continue;
			BlockPos site = site(server, world);
			if (!RaceBases.scouted(level, site.getX(), site.getZ(), TRIGGER)) continue;
			build(level, world, site);
			return; // one a tick: building loads chunks
		}
	}

	// ---- the labyrinth ----

	/** The labyrinth's open walls: east[i][j] between rooms (i, j) and (i+1, j), south[i][j] between (i, j) and (i, j+1). */
	record Maze(boolean[][] east, boolean[][] south) {
		static boolean hall(int i, int j) {
			return i >= 2 && i <= 4 && j >= 2 && j <= 4;
		}

		/** A labyrinth through every room from the entrance (room 3, 6), with one way into the hall from the room furthest in. */
		static Maze make(RandomSource random) {
			boolean[][] east = new boolean[CELLS][CELLS], south = new boolean[CELLS][CELLS];
			int[][] depth = new int[CELLS][CELLS];
			for (int[] row : depth) java.util.Arrays.fill(row, -1);
			ArrayDeque<int[]> stack = new ArrayDeque<>();
			depth[3][CELLS - 1] = 0;
			stack.push(new int[] {3, CELLS - 1});
			int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
			while (!stack.isEmpty()) {
				int[] at = stack.peek();
				List<int[]> next = new ArrayList<>();
				for (int[] s : steps) {
					int i = at[0] + s[0], j = at[1] + s[1];
					if (i >= 0 && j >= 0 && i < CELLS && j < CELLS && !hall(i, j) && depth[i][j] < 0) next.add(new int[] {i, j});
				}
				if (next.isEmpty()) {
					stack.pop();
					continue;
				}
				int[] to = next.get(random.nextInt(next.size()));
				open(east, south, at[0], at[1], to[0], to[1]);
				depth[to[0]][to[1]] = depth[at[0]][at[1]] + 1;
				stack.push(to);
			}
			for (int extra = 0; extra < 5; extra++) { // a few loops, so it is not one long corridor
				int i = random.nextInt(CELLS - 1), j = random.nextInt(CELLS);
				if (!hall(i, j) && !hall(i + 1, j)) east[i][j] = true;
			}
			int[] door = null;
			for (int i = 0; i < CELLS; i++)
				for (int j = 0; j < CELLS; j++) {
					if (hall(i, j)) continue;
					boolean byHall = hall(i + 1, j) || hall(i - 1, j) || hall(i, j + 1) || hall(i, j - 1);
					if (byHall && (door == null || depth[i][j] > depth[door[0]][door[1]])) door = new int[] {i, j};
				}
			int hi = door[0] + (hall(door[0] + 1, door[1]) ? 1 : hall(door[0] - 1, door[1]) ? -1 : 0);
			int hj = door[1] + (hi == door[0] ? (hall(door[0], door[1] + 1) ? 1 : -1) : 0);
			open(east, south, door[0], door[1], hi, hj);
			return new Maze(east, south);
		}

		private static void open(boolean[][] east, boolean[][] south, int i, int j, int i2, int j2) {
			if (i2 != i) east[Math.min(i, i2)][j] = true;
			else south[i][Math.min(j, j2)] = true;
		}

		/** Whether the labyrinth's floor plan is open at (dx, dz) from the middle (inside its outer walls). */
		boolean open(int dx, int dz) {
			int lx = dx - EDGE, lz = dz - EDGE;
			int ci = lx / CELL, ox = lx % CELL, cj = lz / CELL, oz = lz % CELL;
			boolean wx = ox < WALL, wz = oz < WALL;
			if (!wx && !wz) return ci < CELLS && cj < CELLS;
			if (wx && !wz) return ci > 0 && ci < CELLS && cj < CELLS && (east[ci - 1][cj] || hall(ci - 1, cj) && hall(ci, cj));
			if (wz && !wx) return cj > 0 && cj < CELLS && ci < CELLS && (south[ci][cj - 1] || hall(ci, cj - 1) && hall(ci, cj));
			return hall(ci - 1, cj - 1) && hall(ci, cj - 1) && hall(ci - 1, cj) && hall(ci, cj);
		}
	}

	/** The middle of a room's floor, from the temple's middle. */
	static BlockPos room(int i, int j) {
		return new BlockPos(EDGE + i * CELL + WALL + PASSAGE / 2, 0, EDGE + j * CELL + WALL + PASSAGE / 2);
	}

	// ---- building ----

	/** Builds a world's temple round `column` (over the next ticks); its floor's middle. */
	public static BlockPos build(ServerLevel level, Artifact.World world, BlockPos column) {
		RandomSource random = RandomSource.create(level.getSeed() ^ column.asLong() ^ 0x7E3B1EL);
		Painter painter = Painter.deferred(level, random);
		painter.load(column, HALF + 16);
		int floor = switch (world) {
			case NETHER -> NETHER_FLOOR;
			case OVERWORLD -> Math.max(level.getSeaLevel() + 1, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()));
			case END -> {
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
				yield y <= level.getMinBuildHeight() + 4 ? 64 : y;
			}
		};
		BlockPos centre = new BlockPos(column.getX(), floor, column.getZ());
		Maze maze = Maze.make(random);
		BlockState base = switch (world) {
			case OVERWORLD -> Blocks.SMOOTH_SANDSTONE.defaultBlockState();
			case NETHER -> Blocks.POLISHED_BLACKSTONE.defaultBlockState();
			case END -> Blocks.END_STONE_BRICKS.defaultBlockState();
		};
		for (int dx = -HALF - 2; dx <= HALF + 2; dx++) // the ground under it: no overhang, no cave, no sea
			for (int dz = -HALF - 2; dz <= HALF + 2; dz++)
				for (int down = 4; down < 48; down++) {
					BlockPos at = centre.offset(dx, -down, dz);
					BlockState there = level.getBlockState(at);
					if (!there.isAir() && there.getFluidState().isEmpty() && !there.canBeReplaced()) break;
					painter.set(at, base);
				}
		for (int dx = -HALF; dx <= HALF; dx++)
			for (int dz = -HALF; dz <= HALF; dz++)
				for (int y = -3; y <= HALF; y++) {
					BlockState state = cell(maze, dx, y, dz);
					if (state != null) painter.set(centre.offset(dx, y, dz), state);
				}
		for (int dz = HALF + 1; dz <= HALF + 12; dz++) // the way up to the passage: open to the sky (or the Nether's rock carved)
			for (int dx = GATE_LOW - 1; dx <= GATE_HIGH + 1; dx++) {
				painter.set(centre.offset(dx, -1, dz), base);
				for (int y = 0; y < HEIGHT + 2; y++) painter.carve(centre.offset(dx, y, dz), base);
			}
		if (world == Artifact.World.NETHER) // in the Nether's rock: the passage goes on till it meets open air
			for (int dz = HALF + 13; dz <= HALF + 60; dz++) {
				if (level.getBlockState(centre.offset(0, HEIGHT / 2, dz)).isAir() && level.getBlockState(centre.offset(0, 2, dz)).isAir()) break;
				for (int dx = GATE_LOW; dx <= GATE_HIGH; dx++) {
					painter.set(centre.offset(dx, -1, dz), base);
					for (int y = 0; y < HEIGHT; y++) painter.carve(centre.offset(dx, y, dz), Blocks.BASALT.defaultBlockState());
				}
			}
		keepers(level, world, centre, maze, random, painter);
		painter.start();
		VoidWorld.State state = VoidWorld.State.get(level.getServer());
		state.temples.add(world.name());
		state.templeSites.put(world.name(), centre);
		state.setDirty();
		return centre;
	}

	/** What stands at (dx, y, dz) from the temple's floor middle; null: leave the world's block. */
	static @Nullable BlockState cell(Maze maze, int dx, int y, int dz) {
		int m = Math.max(Math.abs(dx), Math.abs(dz));
		if (m > HALF) return null;
		if (y < 0) { // the floor, three thick; glyphs under the portal and in a ring round the hall's middle
			double r = Math.sqrt(dx * dx + dz * dz);
			boolean glyph = y == -1 && (dz == PORTAL_Z && Math.abs(dx) <= PORTAL_HALF || r > 13.4 && r < 14.4);
			return (glyph ? GLYPH : METAL).defaultBlockState();
		}
		if (m > HALF - y) return null; // outside the pyramid
		boolean gate = dx >= GATE_LOW && dx <= GATE_HIGH;
		if (dz >= EDGE + CELLS * CELL && dx >= GATE_LOW - 3 && dx <= GATE_HIGH + 3 && y < HEIGHT + ROOF) { // the entrance passage, walled, its door
			if (!gate || y >= HEIGHT) return METAL.defaultBlockState();
			return (dz == DOOR_Z ? DOOR : Blocks.AIR).defaultBlockState();
		}
		boolean hall = dx >= HALL_LOW && dx <= HALL_HIGH && dz >= HALL_LOW && dz <= HALL_HIGH;
		if (hall && y < HALL_HEIGHT) return hallCell(dx, y, dz);
		if (hall && y < HALL_HEIGHT + ROOF) return METAL.defaultBlockState();
		boolean inMaze = dx >= EDGE && dx < EDGE + CELLS * CELL + WALL && dz >= EDGE && dz < EDGE + CELLS * CELL + WALL;
		if (inMaze && y < HEIGHT + ROOF) {
			if (y < HEIGHT && maze.open(dx, dz)) return Blocks.AIR.defaultBlockState();
			return (y == 7 && Math.floorMod(dx + dz, 7) == 0 ? GLYPH : METAL).defaultBlockState(); // lights in the walls
		}
		int depth = HALF - y - m;
		if (depth <= 2) return (depth == 0 && y % 9 == 4 ? GLYPH : METAL).defaultBlockState(); // the shell, glowing bands round it
		return null; // its hollow: unseen
	}

	/** The hall of keys: the portal's triangle on its north side, the sockets before it, glowing pillars in its corners. */
	private static BlockState hallCell(int dx, int y, int dz) {
		if (dz == PORTAL_Z) {
			BlockState frame = triangle(dx, y);
			if (frame != null) return frame;
		}
		if (y == 0 && dz == SOCKET_Z && (dx == SOCKET_X[0] || dx == SOCKET_X[1] || dx == SOCKET_X[2])) return SOCKET.defaultBlockState();
		boolean corner = Math.abs(dx - HALL_LOW) <= 1 || Math.abs(dx - HALL_HIGH) <= 1;
		boolean cornerZ = Math.abs(dz - HALL_LOW) <= 1 || Math.abs(dz - HALL_HIGH) <= 1;
		return (corner && cornerZ ? GLYPH : Blocks.AIR).defaultBlockState();
	}

	/** A cell of the portal's triangle (its rows from the floor up): its frame of glyphs; null inside and outside it. */
	private static @Nullable BlockState triangle(int across, int y) {
		int row = y + 1, half = PORTAL_HALF - row / 2;
		if (row > PORTAL_ROWS || Math.abs(across) > half) return null;
		return Math.abs(across) >= half - 1 ? GLYPH.defaultBlockState() : null;
	}

	/** Whether a cell of the triangle's plane is inside its frame (where the portal is). */
	static boolean inside(int across, int y) {
		int row = y + 1, half = PORTAL_HALF - row / 2;
		return row >= 1 && row <= PORTAL_ROWS && Math.abs(across) < half - 1;
	}

	/** Its keepers in the rooms (monoliths, constructs, sentinels), two sentinels and constructs in the hall, chests in dead ends. */
	private static void keepers(ServerLevel level, Artifact.World world, BlockPos centre, Maze maze, RandomSource random, Painter painter) {
		HiveSavedData data = HiveSavedData.get(level.getServer());
		String dimension = level.dimension() == Level.OVERWORLD ? null : level.dimension().location().toString();
		List<Spawn> spawns = new ArrayList<>();
		for (int i = 0; i < CELLS; i++)
			for (int j = 0; j < CELLS; j++) {
				if (Maze.hall(i, j) || i == 3 && j == CELLS - 1) continue;
				BlockPos room = centre.offset(room(i, j));
				int roll = random.nextInt(100);
				if (roll < 22) spawns.add(Spawn.guard(UnitKind.VOID_MONOLITH, room, room));
				else if (roll < 58) for (int n = 0; n < 2 + random.nextInt(2); n++) spawns.add(Spawn.guard(UnitKind.XELNAGA_CONSTRUCT, room.offset(n * 3 - 3, 0, 2), room));
				else if (roll < 70) spawns.add(Spawn.guard(UnitKind.XELNAGA_SENTINEL, room, room));
				int ways = (i > 0 && maze.east()[i - 1][j] ? 1 : 0) + (i < CELLS - 1 && maze.east()[i][j] ? 1 : 0)
					+ (j > 0 && maze.south()[i][j - 1] ? 1 : 0) + (j < CELLS - 1 && maze.south()[i][j] ? 1 : 0);
				if (ways == 1 && random.nextInt(3) == 0) painter.chest(room.offset(3, 0, 3), BuiltInLootTables.END_CITY_TREASURE);
			}
		BlockPos hall = centre.offset(0, 0, 4);
		for (int side = -1; side <= 1; side += 2) {
			spawns.add(Spawn.guard(UnitKind.XELNAGA_SENTINEL, hall.offset(side * 10, 0, 0), hall));
			spawns.add(Spawn.guard(UnitKind.XELNAGA_CONSTRUCT, hall.offset(side * 5, 0, 6), hall));
		}
		for (Spawn spawn : spawns)
			data.pending.add(new HiveStructure.Pending(KEEPERS, spawn.kind(), spawn.pos(), spawn.hall(), null, 0, dimension));
		data.setDirty();
	}

	// ---- the door ----

	/** The temple's door: nothing breaks it; a part of this world's key opens it (all of it, and for good). */
	static final class Door extends Block {
		Door(Properties properties) {
			super(properties);
		}

		@Override
		protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
				BlockHitResult hit) {
			if (!(stack.getItem() instanceof BlockItem item) || !(item.getBlock() instanceof Artifact.Part part))
				return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
			if (level instanceof ServerLevel server) {
				if (part.world != Artifact.World.of(level)) player.displayClientMessage(Component.translatable("message.starmine.temple_door_locked"), true);
				else open(server, pos);
			}
			return ItemInteractionResult.sidedSuccess(level.isClientSide);
		}

		@Override
		protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
			if (!level.isClientSide) player.displayClientMessage(Component.translatable("message.starmine.temple_door_locked"), true);
			return InteractionResult.sidedSuccess(level.isClientSide);
		}
	}

	/** The door at `pos` opens: every block of it goes. */
	public static void open(ServerLevel level, BlockPos pos) {
		ArrayDeque<BlockPos> todo = new ArrayDeque<>(List.of(pos.immutable()));
		java.util.Set<BlockPos> seen = new java.util.HashSet<>(todo);
		while (!todo.isEmpty() && seen.size() < 2000) {
			BlockPos at = todo.poll();
			if (!level.getBlockState(at).is(DOOR)) continue;
			level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			if (level.random.nextInt(4) == 0) level.sendParticles(ParticleTypes.END_ROD, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 3, 0.3, 0.3, 0.3, 0.05);
			for (Direction d : Direction.values()) if (seen.add(at.relative(d))) todo.add(at.relative(d));
		}
		level.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 3.0f, 0.6f);
		level.playSound(null, pos, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 3.0f, 0.5f);
		Artifact.World world = Artifact.World.of(level);
		if (world != null) {
			VoidWorld.State state = VoidWorld.State.get(level.getServer());
			state.doors.add(world.name());
			state.setDirty();
		}
		for (ServerPlayer player : level.players())
			if (player.blockPosition().closerThan(pos, 64)) player.displayClientMessage(Component.translatable("message.starmine.temple_door_opened"), true);
	}

	// ---- the hall of keys ----

	/** A key's part was set on or taken off a block at `pos`: on a socket of its world's temple, the portal opens or closes. */
	static void socketChanged(ServerLevel level, BlockPos pos) {
		if (!level.getBlockState(pos.below()).is(SOCKET)) return;
		Artifact.World world = Artifact.World.of(level);
		if (world == null) return;
		VoidWorld.State state = VoidWorld.State.get(level.getServer());
		BlockPos centre = state.templeSites.get(world.name());
		if (centre == null || !state.temples.contains(world.name())) return;
		boolean whole = whole(level, centre, world), open = state.portals.contains(world.name());
		if (whole && !open) openPortal(level, world, centre);
		else if (!whole && open) closePortal(level, world, centre);
	}

	/** Whether the three races' parts of this world's key stand on the temple's sockets. */
	static boolean whole(ServerLevel level, BlockPos centre, Artifact.World world) {
		java.util.Set<UnitKind.Race> races = java.util.EnumSet.noneOf(UnitKind.Race.class);
		for (int x : SOCKET_X)
			if (level.getBlockState(centre.offset(x, 1, SOCKET_Z)).getBlock() instanceof Artifact.Part part && part.world == world) races.add(part.race);
		return races.size() == 3;
	}

	static void openPortal(ServerLevel level, Artifact.World world, BlockPos centre) {
		fill(level, centre, Direction.SOUTH, PORTAL.defaultBlockState());
		VoidWorld.State state = VoidWorld.State.get(level.getServer());
		state.portals.add(world.name());
		state.setDirty();
		ServerLevel voidLevel = VoidWorld.level(level.getServer());
		if (voidLevel != null) voidSide(voidLevel, world);
		level.playSound(null, centre, SoundEvents.END_PORTAL_SPAWN, SoundSource.BLOCKS, 4.0f, 0.8f);
		for (ServerPlayer player : level.players())
			if (player.blockPosition().closerThan(centre, 96)) player.displayClientMessage(Component.translatable("message.starmine.temple_portal_opened"), false);
	}

	static void closePortal(ServerLevel level, Artifact.World world, BlockPos centre) {
		fill(level, centre, Direction.SOUTH, Blocks.AIR.defaultBlockState());
		VoidWorld.State state = VoidWorld.State.get(level.getServer());
		state.portals.remove(world.name());
		state.setDirty();
		level.playSound(null, centre, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 2.0f, 0.6f);
	}

	/** Fills the inside of the temple's triangle (`base`: the temple's floor middle; the triangle faces `facing`). */
	private static void fill(ServerLevel level, BlockPos base, Direction facing, BlockState state) {
		BlockPos plane = base.offset(0, 0, PORTAL_Z);
		Direction along = facing.getClockWise();
		for (int y = 0; y <= PORTAL_ROWS; y++)
			for (int a = -PORTAL_HALF; a <= PORTAL_HALF; a++)
				if (inside(a, y)) level.setBlock(plane.relative(along, a).above(y), state, Block.UPDATE_CLIENTS);
	}

	// ---- the Void's side ----

	/** Where a world's triangle stands in the Void (the middle of its bottom row, in the floor) and which way it faces. */
	static BlockPos voidBase(Artifact.World world) {
		return switch (world) {
			case OVERWORLD -> VoidWorld.RETURN.offset(26, -1, 0);
			case NETHER -> VoidWorld.RETURN.offset(-26, -1, 0);
			case END -> VoidWorld.RETURN.offset(0, -1, -26);
		};
	}

	static Direction voidFacing(Artifact.World world) {
		return switch (world) {
			case OVERWORLD -> Direction.WEST;
			case NETHER -> Direction.EAST;
			case END -> Direction.SOUTH;
		};
	}

	/** A world's triangle in the Void: a shelf joined to the first island, the frame of glyphs, the portal in it. */
	static void voidSide(ServerLevel voidLevel, Artifact.World world) {
		BlockPos base = voidBase(world);
		voidLevel.getChunk(base.getX() >> 4, base.getZ() >> 4);
		if (voidLevel.getBlockState(base.above(1).relative(voidFacing(world).getClockWise(), PORTAL_HALF - 1)).is(GLYPH)) return; // there already
		Direction facing = voidFacing(world), along = facing.getClockWise();
		for (int dx = -11; dx <= 11; dx++)
			for (int dz = -11; dz <= 11; dz++) {
				double r = Math.sqrt(dx * dx + dz * dz);
				if (r > 11) continue;
				int depth = 2 + (int) ((11 - r) / 3);
				for (int down = 0; down < depth; down++)
					voidLevel.setBlock(base.offset(dx, -down, dz), (down == 0 ? VoidIslands.BRICKS : VoidIslands.STONE).defaultBlockState(), Block.UPDATE_CLIENTS);
				for (int up = 1; up <= PORTAL_ROWS + 2; up++) voidLevel.setBlock(base.offset(dx, up, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
		BlockPos toward = VoidWorld.RETURN.below();
		for (int step = 0; step < 26; step++) { // a causeway to the first island
			BlockPos at = base.relative(facing, step);
			if (at.distManhattan(toward) < 12) break;
			for (int w = -3; w <= 3; w++) voidLevel.setBlock(at.relative(along, w), VoidIslands.BRICKS.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
		for (int a = -PORTAL_HALF; a <= PORTAL_HALF; a++) voidLevel.setBlock(base.relative(along, a), GLYPH.defaultBlockState(), Block.UPDATE_CLIENTS);
		for (int y = 0; y <= PORTAL_ROWS; y++)
			for (int a = -PORTAL_HALF; a <= PORTAL_HALF; a++) {
				BlockState frame = triangle(a, y);
				BlockPos at = base.relative(along, a).above(y + 1);
				if (frame != null) voidLevel.setBlock(at, frame, Block.UPDATE_CLIENTS);
				else if (inside(a, y)) voidLevel.setBlock(at, PORTAL.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
	}

	// ---- passing ----

	/** The portal's sheet: whatever of a player's side enters goes through, to the Void or from it. */
	static final class Portal extends Block {
		Portal(Properties properties) {
			super(properties);
		}

		@Override
		protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
			return Shapes.empty();
		}

		@Override
		protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
			if (level instanceof ServerLevel server && entity.canUsePortal(false)) pass(server, pos, entity.getRootVehicle());
		}

		@Override
		public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
			if (random.nextInt(3) == 0)
				level.addParticle(random.nextBoolean() ? ParticleTypes.END_ROD : ParticleTypes.REVERSE_PORTAL, pos.getX() + random.nextDouble(),
					pos.getY() + random.nextDouble(), pos.getZ() + random.nextDouble(), 0, 0.02, 0);
		}
	}

	/** Through a temple's portal: to its world's triangle in the Void; through a triangle of the Void: to its world's temple. */
	public static boolean pass(ServerLevel level, BlockPos pos, Entity entity) {
		if (!(entity instanceof ServerPlayer) && !(entity instanceof StarUnit unit && unit.owner().isPresent())) return false;
		long now = level.getGameTime();
		Long resting = RESTING.get(entity.getUUID());
		if (resting != null && now < resting) return false;
		MinecraftServer server = level.getServer();
		ServerLevel to;
		Vec3 at;
		if (VoidWorld.is(level)) {
			Artifact.World world = null;
			for (Artifact.World each : Artifact.World.values())
				if (world == null || voidBase(each).distSqr(pos) < voidBase(world).distSqr(pos)) world = each;
			VoidWorld.State state = VoidWorld.State.get(server);
			BlockPos centre = state.templeSites.get(world.name());
			to = server.getLevel(world.dimension());
			if (to == null || centre == null || !state.temples.contains(world.name())) return false;
			to.getChunk(centre.getX() >> 4, (centre.getZ() + PORTAL_Z) >> 4);
			at = Vec3.atBottomCenterOf(centre.offset(0, 0, PORTAL_Z + 6));
		} else {
			Artifact.World world = Artifact.World.of(level);
			to = VoidWorld.level(server);
			if (world == null || to == null) return false;
			VoidWorld.arrival(to); // the Void's first island (and the rest) made the first time
			voidSide(to, world);
			at = Vec3.atBottomCenterOf(voidBase(world).relative(voidFacing(world), 6).above());
		}
		List<Entity> going = new ArrayList<>(List.of(entity));
		if (entity instanceof ServerPlayer player) // their army round them goes with them
			going.addAll(level.getEntitiesOfClass(StarUnit.class, entity.getBoundingBox().inflate(ESCORT), unit -> unit.isAlive() && !unit.isPassenger()
				&& !unit.kind.structure() && unit.owner().filter(player.getUUID()::equals).isPresent()));
		level.playSound(null, pos, SoundEvents.PORTAL_TRAVEL, SoundSource.BLOCKS, 0.4f, 1.4f);
		int i = 0;
		for (Entity mover : going) {
			for (Entity rider : mover.getSelfAndPassengers().toList()) RESTING.put(rider.getUUID(), now + 100);
			Vec3 spot = at;
			if (mover != entity) {
				double angle = i++ * 2.4, spread = 3 + mover.getBbWidth() + i * 0.5;
				spot = at.add(Math.cos(angle) * spread, mover instanceof StarUnit unit && unit.kind.flying ? unit.kind.hoverHeight() : 0, Math.sin(angle) * spread);
			}
			if (mover instanceof ServerPlayer player) {
				player.teleportTo(to, spot.x, spot.y, spot.z, java.util.Set.of(), player.getYRot(), player.getXRot());
				if (VoidWorld.is(to)) {
					player.displayClientMessage(Component.translatable("message.starmine.void_in"), true);
					VoidArmy.arrive(to, player);
				} else player.displayClientMessage(Component.translatable("message.starmine.void_out"), true);
			} else {
				if (mover instanceof StarUnit unit) unit.command(StarUnit.Order.NONE, null);
				mover.changeDimension(new DimensionTransition(to, spot, Vec3.ZERO, mover.getYRot(), mover.getXRot(), DimensionTransition.DO_NOTHING));
			}
		}
		to.playSound(null, BlockPos.containing(at), SoundEvents.ENDERMAN_TELEPORT, SoundSource.BLOCKS, 1.0f, 0.7f);
		if (RESTING.size() > 256) RESTING.values().removeIf(until -> until < now);
		return true;
	}

	// ---- the key shows the way ----

	/** Where its world's temple is, told to a player holding a part of that world's key. */
	public static void pointTo(ServerLevel level, ServerPlayer player, Artifact.World world) {
		BlockPos site = site(level.getServer(), world);
		String worldName = "world.starmine." + world.name().toLowerCase(java.util.Locale.ROOT);
		if (player.level().dimension() != world.dimension()) {
			player.displayClientMessage(Component.translatable("message.starmine.temple_elsewhere", Component.translatable(worldName)), true);
			return;
		}
		double dx = site.getX() + 0.5 - player.getX(), dz = site.getZ() + 0.5 - player.getZ(), distance = Math.sqrt(dx * dx + dz * dz);
		if (distance < HALF) {
			player.displayClientMessage(Component.translatable("message.starmine.temple_here"), true);
			return;
		}
		String[] winds = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};
		double degrees = Math.toDegrees(Math.atan2(dx, -dz)); // 0: north (-z), 90: east (+x)
		String wind = winds[Math.floorMod((int) Math.round(degrees / 45), 8)];
		player.displayClientMessage(Component.translatable("message.starmine.temple_way", (int) distance,
			Component.translatable("direction.starmine." + wind)), true);
		for (int i = 1; i <= 8; i++) // a trail of light toward it
			level.sendParticles(player, ParticleTypes.END_ROD, true, player.getX() + dx / distance * i, player.getEyeY() - 0.3, player.getZ() + dz / distance * i,
				2, 0.05, 0.05, 0.05, 0);
		level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0f, 1.2f);
	}
}
