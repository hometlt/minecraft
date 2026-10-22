package dev.starmine.world;

import dev.starmine.StarMine;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;

/**
 * The Void's islands (owner 17.10: like the End — islands in the dark joined by paths, few resources). Made once,
 * the first time anyone comes in ({@link VoidWorld#arrival}), over the next ticks ({@link Painter#deferred}):
 *
 * - the first island with the portal back at the middle ({@link VoidWorld#RETURN});
 * - {@link #ISLANDS} more round it farther and farther out, rough masses of void stone with glowing crystals,
 *   amethyst and crying obsidian; on a few a mineral crystal or a chest — little else;
 * - Amun's island, the largest, {@link #AMUN_DISTANCE} out, void shards standing round it on its ground; more shards
 *   stand in the middle of the largest of the other islands (owner 19.10: no more spires); Amun circles low over it;
 * - paths of void bricks, {@link #PATH_WIDTH} wide and even (an army walks them), from every island to the nearest one
 *   before it (Amun's to the two nearest), with glowing posts along them;
 * - owner 19.10: beyond them, islands without end — one in a {@link #REGION}-block square by a chance that falls the
 *   farther it is ({@link #far}), made when a player comes near, each joined by a path to an island nearer the middle.
 */
public final class VoidIslands {
	public static final int ISLANDS = 10, AMUN_DISTANCE = 260, AMUN_RADIUS = 30, SHARDS_ON_AMUN = 6, SHARDS_ELSEWHERE = 4;
	/** Owner 19.10: paths an army walks (units are twice their model size); the far islands' squares and where they begin. */
	public static final int PATH_WIDTH = 7, REGION = 160, FAR_FROM = 360;
	public static final Block STONE, BRICKS, CRYSTAL;

	static {
		STONE = block("void_stone", new Block(BlockBehaviour.Properties.of().strength(3.0f, 9.0f).requiresCorrectToolForDrops().sound(SoundType.DEEPSLATE)));
		BRICKS = block("void_stone_bricks", new Block(BlockBehaviour.Properties.of().strength(3.5f, 9.0f).requiresCorrectToolForDrops().sound(SoundType.DEEPSLATE_BRICKS)));
		CRYSTAL = block("void_crystal", new Block(BlockBehaviour.Properties.of().strength(1.5f).lightLevel(state -> 12).sound(SoundType.AMETHYST)));
	}

	/** What an island holds (owner 17.10): nothing much, a vespene lake and minerals, a terrazine lake, gold minerals. */
	public enum Riches { NONE, VESPENE, TERRAZINE, GOLD }

	/** One island: its middle (the top of its floor), its radius and its riches. */
	public record Island(BlockPos centre, int radius, Riches riches) {
		public Island(BlockPos centre, int radius) {
			this(centre, radius, Riches.NONE);
		}
	}

	/** The large islands of riches (owner 17.10): a lake inside each, and a field of crystals. */
	public static final List<Riches> RICH_ISLANDS = List.of(Riches.VESPENE, Riches.TERRAZINE, Riches.GOLD, Riches.VESPENE);
	public static final int LAKE_DEPTH = 3, CRYSTALS = 6;

	private VoidIslands() {
	}

	private static Block block(String name, Block block) {
		Registry.register(BuiltInRegistries.BLOCK, StarMine.id(name), block);
		Registry.register(BuiltInRegistries.ITEM, StarMine.id(name), new BlockItem(block, new Item.Properties()));
		return block;
	}

	public static void load() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(VoidIslands::tick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(VoidBlackHole::tick);
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.BUILDING_BLOCKS).register(entries -> {
			entries.accept(STONE);
			entries.accept(BRICKS);
			entries.accept(CRYSTAL);
		});
	}

	/** Where the islands are (the same for a world's seed): the first, the others, Amun's last. */
	public static List<Island> layout(long seed) {
		RandomSource random = RandomSource.create(seed ^ 0x5A17_A3C1L);
		List<Island> islands = new ArrayList<>();
		islands.add(new Island(VoidWorld.RETURN.below(), 13));
		double heading = random.nextDouble() * Math.PI * 2;
		for (int i = 1; i <= ISLANDS; i++) {
			heading += 2.39996 + (random.nextDouble() - 0.5) * 0.5; // the golden angle: spread all round
			double distance = 40 + i * 19 + random.nextInt(14);
			int y = VoidWorld.RETURN.getY() - 1 + Mth.clamp(random.nextInt(25) - 10, -4, 4); // owner 19.10: near one height, the paths even
			islands.add(new Island(new BlockPos(Mth.floor(Math.cos(heading) * distance), y, Mth.floor(Math.sin(heading) * distance)), 8 + random.nextInt(10)));
		}
		double away = random.nextDouble() * Math.PI * 2;
		Island amun = new Island(new BlockPos(Mth.floor(Math.cos(away) * AMUN_DISTANCE), VoidWorld.RETURN.getY() + 4,
			Mth.floor(Math.sin(away) * AMUN_DISTANCE)), AMUN_RADIUS);
		for (Riches riches : RICH_ISLANDS) // the large islands of riches, clear of the others and nearer than Amun's
			for (int attempt = 0; attempt < 60; attempt++) {
				double a = random.nextDouble() * Math.PI * 2, d = 70 + random.nextInt(130);
				int r = 20 + random.nextInt(5);
				BlockPos at = new BlockPos(Mth.floor(Math.cos(a) * d), VoidWorld.RETURN.getY() - 2 + Mth.clamp(random.nextInt(9), 0, 5), Mth.floor(Math.sin(a) * d));
				boolean clear = Math.sqrt(at.distSqr(amun.centre())) - r - amun.radius() > 30
					&& islands.stream().allMatch(other -> Math.sqrt(other.centre().distSqr(at)) - r - other.radius() > 6);
				if (!clear) continue;
				islands.add(new Island(at, r, riches));
				break;
			}
		islands.add(amun);
		return islands;
	}

	/** Makes the islands, their paths, the shards and Amun, over the next ticks. */
	public static void build(ServerLevel level) {
		RandomSource random = RandomSource.create(level.getSeed() ^ 0x1D_A11DL);
		List<Island> islands = layout(level.getSeed());
		Painter painter = Painter.deferred(level, random);
		Island amun = islands.get(islands.size() - 1);
		for (int i = 1; i < islands.size(); i++) island(painter, islands.get(i), random, i == islands.size() - 1);
		for (int i = 1; i < islands.size(); i++) { // each to the nearest before it; Amun's to the two nearest
			Island from = islands.get(i);
			List<Island> before = new ArrayList<>(islands.subList(0, i));
			before.sort(java.util.Comparator.comparingDouble(other -> other.centre().distSqr(from.centre())));
			for (int link = 0; link < (from == amun ? 2 : 1) && link < before.size(); link++) path(painter, from, before.get(link));
		}
		List<BlockPos> spires = new ArrayList<>(); // where the shards stand (owner 19.10: on the ground, no spires)
		for (int i = 0; i < SHARDS_ON_AMUN; i++) { // round Amun's island
			double a = Math.PI * 2 * i / SHARDS_ON_AMUN;
			spires.add(amun.centre().offset(Mth.floor(Math.cos(a) * (amun.radius() - 8)), 0, Mth.floor(Math.sin(a) * (amun.radius() - 8))));
		}
		List<Island> largest = new ArrayList<>(islands.subList(1, islands.size() - 1)); // in the middle of the largest others
		largest.removeIf(island -> island.riches() != Riches.NONE);
		largest.sort(java.util.Comparator.comparingInt(Island::radius).reversed());
		for (int i = 0; i < SHARDS_ELSEWHERE && i < largest.size(); i++) spires.add(largest.get(i).centre());
		for (Island island : islands) if (island.riches() != Riches.NONE) riches(level, painter, island, random);
		for (int i = 2; i < islands.size() - 1; i += 3) { // a little to take: a mineral crystal here and there, a chest
			BlockPos core = islands.get(i).centre().offset(-3, 1, 3);
			painter.then(() -> dev.starmine.mineral.Minerals.place(level, core, (pos, state) -> level.setBlock(pos, state, 2)));
			if (i % 2 == 0) painter.chest(islands.get(i).centre().offset(3, 1, -4), BuiltInLootTables.END_CITY_TREASURE);
		}
		painter.then(() -> {
			VoidWorld.State state = VoidWorld.State.get(level.getServer());
			state.shards += spires.size();
			state.setDirty();
			for (BlockPos spire : spires) {
				BlockPos top = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, spire);
				VoidShard shard = VoidForces.SHARD.create(level);
				if (shard == null) continue;
				shard.moveTo(spire.getX() + 0.5, top.getY() + 1, spire.getZ() + 0.5, 0, 0);
				level.addFreshEntity(shard);
			}
			if (VoidWorld.State.get(level.getServer()).amunDead) return;
			Amun boss = VoidForces.AMUN.create(level);
			if (boss == null) return;
			boss.home(amun.centre());
			boss.moveTo(amun.centre().getX() + 0.5, amun.centre().getY() + 24, amun.centre().getZ() + 0.5, 0, 0);
			level.addFreshEntity(boss);
		});
		painter.start();
	}

	/**
	 * An island's riches (owner 17.10): a lake inside it — vespene, or terrazine on a terrazine island — fed by its own
	 * vein (its outlets, two of them, take gas buildings; emptied, they fill from the lake), and opposite it a field of
	 * mineral crystals, of gold on a gold island (each trip worth twice).
	 */
	static void riches(ServerLevel level, Painter painter, Island island, RandomSource random) {
		BlockPos c = island.centre();
		double side = random.nextDouble() * Math.PI * 2;
		BlockPos lakeAt = c.offset(Mth.floor(Math.cos(side) * island.radius() * 0.35), 0, Mth.floor(Math.sin(side) * island.radius() * 0.35));
		boolean terrazine = island.riches() == Riches.TERRAZINE;
		BlockState liquid = (terrazine ? dev.starmine.vespene.Vespene.TERRAZINE : dev.starmine.vespene.Vespene.VESPENE).defaultBlockState();
		List<BlockPos> channel = new ArrayList<>(), outlets = new ArrayList<>();
		for (int dy = -LAKE_DEPTH + 1; dy <= 0; dy++) // the bottom up, so the vein drains from the top
			for (int dx = -6; dx <= 6; dx++)
				for (int dz = -5; dz <= 5; dz++) {
					if (Painter.sq(dx / 6.5) + Painter.sq(dz / 5.5) > 1 - dy * 0.12) continue;
					BlockPos at = lakeAt.offset(dx, dy, dz);
					boolean outlet = dy == 0 && dz == 0 && (dx == -3 || dx == 3);
					painter.set(at, liquid.setValue(dev.starmine.vespene.VespeneBlock.OUTLET, outlet));
					(outlet ? outlets : channel).add(at.immutable());
					if (dy == 0) for (int up = 1; up <= 3; up++) painter.set(at.above(up), Blocks.AIR.defaultBlockState());
				}
		for (int dx = -7; dx <= 7; dx++) // a rim of void bricks round the lake
			for (int dz = -6; dz <= 6; dz++) {
				double e = Painter.sq(dx / 6.5) + Painter.sq(dz / 5.5);
				if (e > 1 && e <= 1.45) painter.set(lakeAt.offset(dx, 0, dz), BRICKS.defaultBlockState());
			}
		BlockPos root = lakeAt.below(LAKE_DEPTH + 1);
		painter.set(root, dev.starmine.vespene.Vespene.ROOT.defaultBlockState());
		painter.then(() -> {
			if (level.getBlockEntity(root) instanceof dev.starmine.vespene.VespeneRootBlockEntity vein) {
				vein.setVein(channel, outlets);
				vein.setLake(terrazine);
			}
		});
		boolean gold = island.riches() == Riches.GOLD;
		for (int i = 0; i < CRYSTALS; i++) { // the field: an arc on the far side from the lake
			double a = side + Math.PI + (i - (CRYSTALS - 1) / 2.0) * 0.32;
			double r = island.radius() - 6;
			BlockPos core = c.offset(Mth.floor(Math.cos(a) * r), 1, Mth.floor(Math.sin(a) * r));
			for (BlockPos cell : BlockPos.betweenClosed(core.offset(-1, 0, -1), core.offset(2, 3, 2))) painter.set(cell.immutable(), Blocks.AIR.defaultBlockState());
			painter.then(() -> {
				if (!dev.starmine.mineral.Minerals.place(level, core, (pos, state) -> level.setBlock(pos, state, 2))) return;
				if (gold && level.getBlockEntity(core) instanceof dev.starmine.mineral.Minerals.Core crystal) crystal.setRich(true);
			});
		}
	}

	/** A rough mass of void stone under a flat top with crystals and a little amethyst and crying obsidian on it. */
	static void island(Painter painter, Island island, RandomSource random, boolean amun) {
		BlockPos c = island.centre();
		int r = island.radius();
		double[] wobble = new double[8];
		for (int i = 0; i < wobble.length; i++) wobble[i] = random.nextDouble() * Math.PI * 2;
		for (int dx = -r - 3; dx <= r + 3; dx++)
			for (int dz = -r - 3; dz <= r + 3; dz++) {
				double a = Math.atan2(dz, dx);
				double edge = r * (1 + 0.18 * Math.sin(a * 3 + wobble[0]) + 0.1 * Math.sin(a * 5 + wobble[1]));
				double d = Math.hypot(dx, dz);
				if (d > edge) continue;
				double k = 1 - d / edge;
				int depth = 1 + (int) Math.round(k * r * 0.9 + Math.sin(dx * 0.7 + wobble[2]) * 1.5 + Math.cos(dz * 0.6 + wobble[3]) * 1.5);
				int top = (int) Math.round(Math.sin(dx * 0.25 + wobble[4]) * 0.7 + Math.cos(dz * 0.3 + wobble[5]) * 0.7);
				for (int dy = top; dy > top - depth; dy--) {
					BlockState state = dy == top ? (random.nextInt(9) == 0 ? Blocks.CRYING_OBSIDIAN : STONE).defaultBlockState()
						: dy > top - 3 ? STONE.defaultBlockState() : random.nextInt(5) == 0 ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.BLACKSTONE.defaultBlockState();
					painter.set(c.offset(dx, dy, dz), state);
				}
				for (int up = 1; up <= 6; up++) painter.set(c.offset(dx, top + up, dz), Blocks.AIR.defaultBlockState());
				if (d < edge - 2 && random.nextInt(amun ? 40 : 18) == 0) // a glowing crystal spike
					for (int up = 1; up <= 1 + random.nextInt(3); up++) painter.set(c.offset(dx, top + up, dz), CRYSTAL.defaultBlockState());
				else if (d < edge - 1 && random.nextInt(70) == 0)
					painter.set(c.offset(dx, top + 1, dz), Blocks.AMETHYST_CLUSTER.defaultBlockState().setValue(AmethystClusterBlock.FACING, net.minecraft.core.Direction.UP));
			}
		if (amun) { // his throne: a dais of void bricks with a crystal at each corner
			painter.box(c.offset(-5, 1, -5), c.offset(5, 1, 5), Painter.of(BRICKS.defaultBlockState()));
			for (int[] corner : new int[][] {{-5, -5}, {5, -5}, {-5, 5}, {5, 5}})
				for (int up = 2; up <= 4; up++) painter.set(c.offset(corner[0], up, corner[1]), CRYSTAL.defaultBlockState());
		}
	}

	/**
	 * A path of void bricks {@link #PATH_WIDTH} wide from one island's middle to another's, its height straight between
	 * them (rising a block at most every two), clear over it for the tallest walkers.
	 */
	static void path(Painter painter, Island from, Island to) {
		BlockPos a = from.centre(), b = to.centre();
		double length = Math.sqrt(a.distSqr(b));
		double dx = (b.getX() - a.getX()) / length, dz = (b.getZ() - a.getZ()) / length;
		for (int step = 0; step <= (int) length; step++) {
			double t = step / length;
			int x = Mth.floor(a.getX() + (b.getX() - a.getX()) * t), z = Mth.floor(a.getZ() + (b.getZ() - a.getZ()) * t);
			int y = Mth.floor(a.getY() + (b.getY() - a.getY()) * t);
			int half = PATH_WIDTH / 2;
			for (double side = -half; side <= half; side += 0.5) { // every half block across: no gaps where it runs aslant
				BlockPos at = new BlockPos(Mth.floor(x + 0.5 - dz * side), y, Mth.floor(z + 0.5 + dx * side));
				if (painter.stateAt(at).isAir() || !painter.stateAt(at).is(STONE)) painter.set(at, BRICKS.defaultBlockState());
				painter.set(at.below(), STONE.defaultBlockState());
				for (int up = 1; up <= 6; up++) if (!painter.stateAt(at.above(up)).is(CRYSTAL)) painter.set(at.above(up), Blocks.AIR.defaultBlockState());
			}
			if (step % 9 == 4) { // posts along both edges, a crystal on each
				for (int edge = -1; edge <= 1; edge += 2) {
					BlockPos post = new BlockPos(Mth.floor(x + 0.5 - dz * (half + 1) * edge), y, Mth.floor(z + 0.5 + dx * (half + 1) * edge));
					painter.set(post, BRICKS.defaultBlockState());
					painter.set(post.above(), Blocks.POLISHED_BLACKSTONE_WALL.defaultBlockState());
					painter.set(post.above(2), CRYSTAL.defaultBlockState());
				}
			}
		}
	}

	// ---- the far islands (owner 19.10) ----

	/**
	 * The island of a far square, or null: none within {@link #FAR_FROM} of the middle (the first islands are there),
	 * beyond it one by a chance falling with the distance (0.55 at first, half that every 900 blocks, never under 0.06).
	 */
	public static @org.jetbrains.annotations.Nullable Island far(long seed, int rx, int rz) {
		RandomSource random = RandomSource.create(seed ^ (rx * 341873128712L + rz * 132897987541L) ^ 0x701D_15L);
		int x = rx * REGION + 30 + random.nextInt(REGION - 60), z = rz * REGION + 30 + random.nextInt(REGION - 60);
		double d = Math.hypot(x, z);
		if (d < FAR_FROM) return null;
		double chance = Math.max(0.06, 0.55 * Math.pow(0.5, (d - FAR_FROM) / 900));
		if (random.nextDouble() >= chance) return null;
		int y = VoidWorld.RETURN.getY() - 1 + random.nextInt(11) - 5;
		return new Island(new BlockPos(x, y, z), 9 + random.nextInt(14));
	}

	/** The island a far one is joined to: the nearest island (first or far) nearer the middle than it. */
	public static Island toward(long seed, Island island) {
		List<Island> near = new ArrayList<>(layout(seed));
		int rx = Math.floorDiv(island.centre().getX(), REGION), rz = Math.floorDiv(island.centre().getZ(), REGION);
		for (int dx = -2; dx <= 2; dx++)
			for (int dz = -2; dz <= 2; dz++) {
				Island other = far(seed, rx + dx, rz + dz);
				if (other != null) near.add(other);
			}
		double mine = Math.hypot(island.centre().getX(), island.centre().getZ());
		return near.stream().filter(other -> Math.hypot(other.centre().getX(), other.centre().getZ()) < mine - 1)
			.min(java.util.Comparator.comparingDouble(other -> other.centre().distSqr(island.centre()))).orElse(near.get(0));
	}

	/** Each second: the far islands round players in the Void that are not made yet. */
	public static void tick(net.minecraft.server.MinecraftServer server) {
		if (server.getTickCount() % 20 != 7) return;
		ServerLevel level = server.getLevel(VoidWorld.KEY);
		if (level == null || level.players().isEmpty()) return;
		VoidWorld.State state = VoidWorld.State.get(server);
		if (!state.islands) return;
		for (var player : level.players()) {
			int prx = Math.floorDiv(player.getBlockX(), REGION), prz = Math.floorDiv(player.getBlockZ(), REGION);
			for (int rx = prx - 1; rx <= prx + 1; rx++)
				for (int rz = prz - 1; rz <= prz + 1; rz++) {
					long key = ((long) rx << 32) ^ (rz & 0xffffffffL);
					if (state.farIslands.contains(key)) continue;
					state.farIslands.add(key);
					state.setDirty();
					Island island = far(level.getSeed(), rx, rz);
					if (island == null) continue;
					buildFar(level, island);
					return; // one a second
				}
		}
	}

	/** A far island and its path in toward the middle; now and then a mineral crystal or a chest on it. */
	public static void buildFar(ServerLevel level, Island island) {
		RandomSource random = RandomSource.create(level.getSeed() ^ island.centre().asLong());
		Painter painter = Painter.deferred(level, random);
		island(painter, island, random, false);
		path(painter, island, toward(level.getSeed(), island));
		BlockPos core = island.centre().offset(-3, 1, 3);
		if (random.nextInt(3) == 0) painter.then(() -> dev.starmine.mineral.Minerals.place(level, core, (pos, state) -> level.setBlock(pos, state, 2)));
		if (random.nextInt(4) == 0) painter.chest(island.centre().offset(3, 1, -4), BuiltInLootTables.END_CITY_TREASURE);
		painter.start();
	}
}
