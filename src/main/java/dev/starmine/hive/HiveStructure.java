package dev.starmine.hive;

import dev.starmine.StarMine;
import dev.starmine.block.StarMineBlocks;
import dev.starmine.creep.CreepGrowth;
import dev.starmine.creep.CreepSources;
import dev.starmine.entity.UnitKind;
import dev.starmine.entity.StarUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Generated hives (owner, 14.10): a lair near the world spawn — not right beside the player — with
 * its buildings, units and a tunnel network.
 *
 * - Site: once per world, 256 blocks at 60 degrees from the first player's captured heading (game rule
 *   `starmineSpawnHive`). It is built when a player comes within {@link #TRIGGER} blocks.
 * - Surface: the hatchery on its foundation with creep already grown, the spawning pool, evolution
 *   chamber, roach warren, spire (always on the surface), spine and spore crawlers, creep tumors,
 *   drones, zerglings, a queen, roaches, hydralisks and overlords. The hive starts as a lair.
 * - Tunnels: 4–6 branches leave the hatchery in different directions, go down by steps ({@link #TUNNEL_WIDTH}
 *   wide, {@link #TUNNEL_HEIGHT} high: its guards walk them — a hydralisk is 2.6 wide and 4.2 tall) to
 *   20–40 blocks under the surface and wander 80–130 blocks,
 *   some forking. Halls open at their ends, at forks and (most branches) 14–20 blocks out. Water
 *   and lava next to a tunnel are sealed off.
 * - Near the hatchery (within {@link #NEAR}) tunnels and halls are lined with creep and tumors, and
 *   halls hold growths, larvae and sleeping eggs; farther halls hold eggs and guards. Some halls hold a
 *   building (never the spire). Nest dwellers keep to their hall and are not counted in the hive
 *   ({@link StarUnit#makeNest}); a nest egg wakes when disturbed and hatches warriors.
 * - Zerg are not put in the world at once: each waits in {@link HiveSavedData#pending} until its chunk
 *   is entity-ticking (a player near), so remote halls are filled when someone comes.
 *
 * Generation is a runtime build, not a datapack structure: the hive is entity-driven ({@link HiveLogic}),
 * and the world keeps at most {@link HiveSavedData#MAX_HIVES} hives.
 */
public final class HiveStructure {
	public static final int MIN_DISTANCE = 256, MAX_DISTANCE = 256, TRIGGER = 112;
	/** A tunnel's cross-section and a hall's headroom for the units twice the model size (an ultralisk hatched in a hall is 7.4 tall). */
	public static final int TUNNEL_WIDTH = 5, TUNNEL_HEIGHT = 6, HALL_HEIGHT = 10;
	/** Tunnels and halls this close to the hatchery are the hive's own: creep, tumors, larvae. */
	public static final int NEAR = 40;
	/** How far from the hatchery tunnels may reach (owner 14.10: farther and deeper); chunks are loaded so far. */
	private static final int REACH = 150;
	private static final UnitKind[] SURFACE = {UnitKind.SPAWNING_POOL, UnitKind.SPINE_CRAWLER, UnitKind.EVOLUTION_CHAMBER,
		UnitKind.SPORE_CRAWLER, UnitKind.SPIRE, UnitKind.SPINE_CRAWLER, UnitKind.ROACH_WARREN, UnitKind.NYDUS_NETWORK};
	private static final UnitKind[] UNDERGROUND = {UnitKind.HYDRALISK_DEN, UnitKind.BANELING_NEST, UnitKind.INFESTATION_PIT,
		UnitKind.ULTRALISK_CAVERN, UnitKind.EVOLUTION_CHAMBER};
	private static final UnitKind[] GUARDS = {UnitKind.ZERGLING, UnitKind.ZERGLING, UnitKind.ROACH, UnitKind.HYDRALISK};
	private static final UnitKind[] BROOD = {UnitKind.ZERGLING, UnitKind.ZERGLING, UnitKind.ROACH, UnitKind.HYDRALISK,
		UnitKind.BANELING, UnitKind.ROACH, UnitKind.ULTRALISK};

	/** A zerg of a generated hive waiting to be put in the world (see the class comment). */
	public record Pending(UUID hive, UnitKind kind, BlockPos pos, BlockPos hall, UnitKind brood, int broodCount, @org.jetbrains.annotations.Nullable String dimension) {
		/** In the overworld. */
		public Pending(UUID hive, UnitKind kind, BlockPos pos, BlockPos hall, UnitKind brood, int broodCount) {
			this(hive, kind, pos, hall, brood, broodCount, null);
		}

		/** Whether it waits in this level (the overworld unless it says otherwise: the stratosphere's ship guards). */
		public boolean in(net.minecraft.world.level.Level level) {
			return dimension == null ? level.dimension() == net.minecraft.world.level.Level.OVERWORLD : level.dimension().location().toString().equals(dimension);
		}

		CompoundTag save() {
			CompoundTag tag = new CompoundTag();
			tag.putUUID("hive", hive);
			tag.putString("kind", kind.name());
			tag.putLong("pos", pos.asLong());
			if (hall != null) tag.putLong("hall", hall.asLong());
			if (brood != null) {
				tag.putString("brood", brood.name());
				tag.putInt("broodCount", broodCount);
			}
			if (dimension != null) tag.putString("dimension", dimension);
			return tag;
		}

		static Pending load(CompoundTag tag) {
			try {
				return new Pending(tag.getUUID("hive"), UnitKind.valueOf(tag.getString("kind")), BlockPos.of(tag.getLong("pos")),
					tag.contains("hall") ? BlockPos.of(tag.getLong("hall")) : null,
					tag.contains("brood") ? UnitKind.valueOf(tag.getString("brood")) : null, tag.getInt("broodCount"),
					tag.contains("dimension") ? tag.getString("dimension") : null);
			} catch (IllegalArgumentException e) {
				return null;
			}
		}
	}

	/** What a build made (for tests and the command). */
	public record Report(int tunnelCells, int halls, int hallBuildings, int eggs, int tumors) {
	}

	private HiveStructure() {
	}

	/** Server tick: puts waiting zerg in the world; picks the spawn hive's site and builds it when a player nears. */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0) return;
		ServerLevel level = server.overworld();
		HiveSavedData data = HiveSavedData.get(server);
		if (!data.pending.isEmpty()) for (ServerLevel each : server.getAllLevels()) spawnPending(each, data); // the ships' guards wait in the stratosphere
		if (data.spawnHiveDone || !level.getGameRules().getBoolean(StarMine.SPAWN_HIVE) || level.players().isEmpty()) return;
		if (data.spawnHiveSite == null) {
			data.spawnHiveSite = dev.starmine.world.RaceBases.spawnSite(level, data, UnitKind.Race.ZERG);
			if (data.spawnHiveSite == null) data.spawnHiveDone = true;
			data.setDirty();
			return;
		}
		BlockPos site = data.spawnHiveSite;
		boolean near = level.players().stream().anyMatch(player -> !player.isSpectator()
			&& Mth.square(player.getX() - site.getX()) + Mth.square(player.getZ() - site.getZ()) < TRIGGER * TRIGGER);
		if (!near) return;
		data.spawnHiveDone = true;
		data.setDirty();
		if (!data.full()) {
			build(level, site, RandomSource.create(level.getSeed() ^ site.asLong()));
			// owner 17.10: Abathur guards the lair (the leviathan comes when he is freed)
			Hive lair = data.all().stream().filter(h -> h.owner == null && h.race == UnitKind.Race.ZERG && h.hatchery.distSqr(site) < 32 * 32).findFirst().orElse(null);
			if (lair != null) {
				int x = site.getX() - 12, z = site.getZ();
				data.pending.add(new Pending(lair.id, UnitKind.ABATHUR, new BlockPos(x, ground(level, x, z), z), lair.hatchery, null, 0));
				data.structuresDone.add("GUARD:ZERG");
				data.setDirty();
			}
		}
	}

	/** A dry, fairly flat place at the configured radius from `spawn`; null when none is found. */
	public static BlockPos chooseSite(ServerLevel level, BlockPos spawn, RandomSource random) {
		return chooseSite(level, spawn, random, MIN_DISTANCE, MAX_DISTANCE);
	}

	/** A dry, fairly flat place `min`–`max` blocks from `spawn`; null when none is found (also used for other races' bases). */
	public static BlockPos chooseSite(ServerLevel level, BlockPos spawn, RandomSource random, int min, int max) {
		final int MIN_DISTANCE = min, MAX_DISTANCE = max;
		BlockPos best = null;
		int bestRange = Integer.MAX_VALUE;
		for (int attempt = 0; attempt < 32 && bestRange > 2; attempt++) {
			double angle = random.nextDouble() * Math.PI * 2, distance = MIN_DISTANCE + random.nextDouble() * (MAX_DISTANCE - MIN_DISTANCE);
			int x = spawn.getX() + (int) (Math.cos(angle) * distance), z = spawn.getZ() + (int) (Math.sin(angle) * distance);
			int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE, centre = 0;
			boolean wet = false;
			for (int sample = 0; sample < 9 && !wet; sample++) {
				int sx = x + (sample == 0 ? 0 : (int) (Math.cos(sample * Math.PI / 4) * 9));
				int sz = z + (sample == 0 ? 0 : (int) (Math.sin(sample * Math.PI / 4) * 9));
				int y = ground(level, sx, sz);
				if (!level.getFluidState(new BlockPos(sx, y - 1, sz)).isEmpty() || !level.getFluidState(new BlockPos(sx, y, sz)).isEmpty()) wet = true;
				if (sample == 0) centre = y;
				low = Math.min(low, y);
				high = Math.max(high, y);
			}
			if (wet || high - low > 6 || high - low >= bestRange) continue;
			bestRange = high - low;
			best = new BlockPos(x, centre, z);
		}
		return best;
	}

	/**
	 * The ground (feet level) of a column: the highest free cell over a solid block, not counting trees,
	 * plants or snow (it may be in water: callers check). Loads the chunk.
	 */
	public static int ground(ServerLevel level, int x, int z) {
		level.getChunk(x >> 4, z >> 4);
		if (level.dimensionType().hasCeiling()) return caveFloor(level, x, z); // owner 17.10: the Nether: under its roof
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		while (y > level.getMinBuildHeight() + 1) {
			BlockState below = level.getBlockState(new BlockPos(x, y - 1, z));
			if (below.is(BlockTags.LOGS) || below.is(BlockTags.LEAVES) || below.canBeReplaced() && below.getFluidState().isEmpty()) y--;
			else break;
		}
		return y;
	}

	/**
	 * A dimension with a roof (the Nether): the highest floor under it with room over it — a free cell with 4 free ones
	 * above, over a solid block that is not lava — searched down from the middle of its height; its sea level if none.
	 */
	public static int caveFloor(ServerLevel level, int x, int z) {
		int top = Math.min(level.getMinBuildHeight() + level.dimensionType().logicalHeight() - 8, 110);
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos(x, top, z);
		int free = 0;
		for (int y = top; y > level.getMinBuildHeight() + 4; y--) {
			at.setY(y);
			BlockState state = level.getBlockState(at);
			if (state.isAir()) {
				free++;
				continue;
			}
			if (free >= 5 && state.getFluidState().isEmpty() && state.isCollisionShapeFullBlock(level, at)) return y + 1;
			free = 0;
		}
		return level.getSeaLevel() + 1;
	}

	/** Builds a hive at `site` (its hatchery's ground cell). The hive is added to the world's hives. */
	public static Report build(ServerLevel level, BlockPos site, RandomSource random) {
		return new Builder(level, random).build(site);
	}

	/** Puts waiting zerg whose place is entity-ticking in the world. */
	private static void spawnPending(ServerLevel level, HiveSavedData data) {
		int added = 0;
		for (Iterator<Pending> it = data.pending.iterator(); it.hasNext() && added < 32; ) {
			Pending p = it.next();
			if (!p.in(level) || !level.isPositionEntityTicking(p.pos())) continue;
			it.remove();
			added++;
			spawn(level, data, p);
		}
		if (added > 0) data.setDirty();
	}

	private static void spawn(ServerLevel level, HiveSavedData data, Pending p) {
		Hive hive = data.get(p.hive());
		Vec3 at = Vec3.atBottomCenterOf(p.pos());
		// a unit (twice the model size) where its body fits: beside its base's buildings, off a dungeon's walls and statues
		if (!p.kind().structure()) at = dev.starmine.entity.Spots.near(level, p.kind(), at);
		if (p.kind().main() && p.hall() == null) { // the base's anchor: hatchery, command center, nexus
			if (hive == null) return;
			StarUnit main = StarMine.type(p.kind()).create(level);
			if (main == null) return;
			main.setUUID(hive.id);
			at = HiveLogic.seat(p.kind(), at);
			main.moveTo(at.x, at.y, at.z, 0, 0); // owner 18.10: square on the grid, never turned
			main.joinHive(hive);
			if (p.kind() != UnitKind.HATCHERY) hive.join(p.kind());
			level.addFreshEntity(main);
		} else if (p.hall() != null) {
			StarUnit mob = HiveLogic.spawnNest(level, hive, p.kind(), at, p.hall());
			if (mob != null && p.brood() != null) mob.becomeCocoon(p.brood(), p.broodCount(), StarUnit.DORMANT);
		} else if (hive != null) {
			HiveLogic.spawn(level, hive, p.kind(), at, null);
			if (p.kind().building()) hive.join(p.kind());
		}
	}

	/** One build: blocks now, zerg as {@link Pending}. */
	private static final class Builder {
		private final ServerLevel level;
		private final RandomSource random;
		private final HiveSavedData data;
		private Hive hive;
		private BlockPos centre;
		private int cells, halls, hallBuildings, eggs, tumors;
		private final List<UnitKind> undergroundLeft = new ArrayList<>(List.of(UNDERGROUND));

		Builder(ServerLevel level, RandomSource random) {
			this.level = level;
			this.random = random;
			this.data = HiveSavedData.get(level.getServer());
		}

		Report build(BlockPos site) {
			centre = new BlockPos(site.getX(), Math.max(site.getY(), ground(level, site.getX(), site.getZ())), site.getZ());
			for (int cx = (centre.getX() - REACH) >> 4; cx <= (centre.getX() + REACH) >> 4; cx++)
				for (int cz = (centre.getZ() - REACH) >> 4; cz <= (centre.getZ() + REACH) >> 4; cz++) level.getChunk(cx, cz);
			hive = new Hive(UUID.randomUUID(), level.dimension().location().toString(), centre);
			hive.minerals = 400;
			hive.vespene = 200;
			hive.tier = 2;
			data.add(hive);
			flatten(centre, HiveTerrain.HATCHERY_FOUNDATION + 1, 14);
			dev.starmine.world.BaseGround.fillUnder(level, centre, 14); // owner 17.10: land under the lair, sloping to it round about
			dev.starmine.world.BaseGround.blend(level, centre, 15, 28);
			HiveTerrain.lay(level, centre, HiveTerrain.HATCHERY_FOUNDATION);
			hive.foundation = true;
			wait(UnitKind.HATCHERY, centre, null, null, 0);

			double turn = Math.PI * 2 / SURFACE.length, start = random.nextDouble() * Math.PI * 2;
			// tunnels leave between the buildings
			List<Integer> gaps = new ArrayList<>();
			for (int i = 0; i < SURFACE.length; i++) gaps.add(i);
			java.util.Collections.shuffle(gaps, new java.util.Random(random.nextLong()));
			int branches = 4 + random.nextInt(3);
			for (int b = 0; b < branches; b++) {
				double angle = start + (gaps.get(b) + 0.5) * turn;
				tunnel(centre.getX() + Math.cos(angle) * 9.5, centre.getY(), centre.getZ() + Math.sin(angle) * 9.5, angle,
					80 + random.nextInt(51), centre.getY() - 20 - random.nextInt(21), true);
			}
			for (int i = 0; i < SURFACE.length; i++) {
				UnitKind kind = SURFACE[i];
				double angle = start + i * turn, distance = 8 + HiveTerrain.foundation(kind) + 3 + random.nextInt(2);
				int x = Mth.floor(centre.getX() + 0.5 + Math.cos(angle) * distance), z = Mth.floor(centre.getZ() + 0.5 + Math.sin(angle) * distance);
				BlockPos feet = new BlockPos(x, Mth.clamp(ground(level, x, z), centre.getY() - 3, centre.getY() + 3), z);
				flatten(feet, HiveTerrain.foundation(kind) + 1, (int) Math.ceil(kind.height) + 2);
				HiveTerrain.lay(level, feet, HiveTerrain.foundation(kind));
				wait(kind, feet, null, null, 0);
			}
			// owner 18.10: the hive's resources between two of its buildings: 5 crystals, 2 geysers with extractors
			double toward = start + 0.5 * turn;
			int depositX = Mth.floor(centre.getX() + Math.cos(toward) * 28), depositZ = Mth.floor(centre.getZ() + Math.sin(toward) * 28);
			for (BlockPos gas : dev.starmine.mineral.Deposits.build(level, random, depositX, depositZ, centre.getX(), centre.getZ()))
				wait(UnitKind.EXTRACTOR, gas, null, null, 0);
			// creep grows out from the hatchery now, as if the hive had stood here a while
			for (int i = 0; i < 400; i++) CreepGrowth.grow(level, centre, CreepSources.HATCHERY_RADIUS, HiveTerrain.HATCHERY_FOUNDATION + 1);
			for (int i = 0; i < 3; i++) {
				double angle = start + random.nextDouble() * Math.PI * 2;
				int x = Mth.floor(centre.getX() + Math.cos(angle) * 13), z = Mth.floor(centre.getZ() + Math.sin(angle) * 13);
				BlockPos cell = new BlockPos(x, ground(level, x, z), z);
				if (creepFloor(cell) && CreepGrowth.placeTumor(level, cell, CreepSources.TUMOR_SPACING)) tumors++;
			}
			UnitKind[] army = {UnitKind.DRONE, UnitKind.DRONE, UnitKind.DRONE, UnitKind.DRONE, UnitKind.DRONE, UnitKind.DRONE,
				UnitKind.ZERGLING, UnitKind.ZERGLING, UnitKind.ZERGLING, UnitKind.ZERGLING, UnitKind.ZERGLING, UnitKind.ZERGLING,
				UnitKind.QUEEN, UnitKind.ROACH, UnitKind.ROACH, UnitKind.HYDRALISK, UnitKind.HYDRALISK, UnitKind.OVERLORD, UnitKind.OVERLORD};
			for (UnitKind kind : army) {
				// out of the hatchery by both bodies' half widths: an overlord (8 wide) stood in it
				double angle = random.nextDouble() * Math.PI * 2, distance = UnitKind.HATCHERY.width / 2 + kind.width / 2 + 1 + random.nextDouble() * 3;
				int x = Mth.floor(centre.getX() + Math.cos(angle) * distance), z = Mth.floor(centre.getZ() + Math.sin(angle) * distance);
				wait(kind, new BlockPos(x, ground(level, x, z), z), null, null, 0);
			}
			data.setDirty();
			return new Report(cells, halls, hallBuildings, eggs, tumors);
		}

		private void wait(UnitKind kind, BlockPos pos, BlockPos hall, UnitKind brood, int broodCount) {
			data.pending.add(new Pending(hive.id, kind, pos.immutable(), hall == null ? null : hall.immutable(), brood, broodCount,
				level.dimension() == net.minecraft.world.level.Level.OVERWORLD ? null : level.dimension().location().toString())); // a nether or End lair's own
		}

		private void flatten(BlockPos feet, int half, int height) {
			HiveStructure.flatten(level, feet, half, height);
		}

		/**
		 * One tunnel: steps down (one block every two forward) to `bottom`, then wanders level-ish;
		 * a hall at its end, maybe a fork with a hall halfway.
		 */
		private void tunnel(double x, int y, double z, double angle, int length, int bottom, boolean mayFork) {
			bottom = Math.max(bottom, level.getMinBuildHeight() + 8);
			int forkAt = mayFork && random.nextInt(2) == 0 ? length / 2 : -1;
			// owner: halls of the hive's own (creep, larvae, eggs) close to the hatchery
			int nestAt = mayFork && random.nextInt(10) < 7 ? 14 + random.nextInt(6) : -1;
			for (int step = 0; step < length; step++) {
				x += Math.cos(angle);
				z += Math.sin(angle);
				angle += random.nextGaussian() * 0.12;
				if (y > bottom && step % 2 == 1) y--;
				else if (y <= bottom && step % 9 == 0 && random.nextInt(3) == 0) y += y > level.getMinBuildHeight() + 10 && random.nextBoolean() ? -1 : 1;
				BlockPos feet = BlockPos.containing(x, y, z);
				if (!section(feet, angle)) return;
				if (near(feet) && step % 9 == 8) {
					if (CreepGrowth.placeTumor(level, feet, CreepSources.TUMOR_SPACING)) tumors++;
				}
				if (step % 18 == 17 && random.nextInt(3) == 0 && !near(feet))
					wait(GUARDS[random.nextInt(GUARDS.length)], feet, feet, null, 0);
				if (step == nestAt) hall(feet);
				else if (step % 40 == 39 && step + 10 < length && random.nextBoolean()) hall(feet);
				if (step == forkAt) {
					if (random.nextBoolean()) hall(feet);
					tunnel(x, y, z, angle + (random.nextBoolean() ? 0.9 : -0.9), length / 2 + random.nextInt(10), bottom + random.nextInt(5) - 2, false);
				}
			}
			hall(BlockPos.containing(x, y, z));
		}

		private boolean near(BlockPos pos) {
			return pos.distSqr(centre) < NEAR * NEAR;
		}

		/**
		 * The tunnel's cross-section at `feet`: {@link #TUNNEL_WIDTH} wide (sampled every half block across, so
		 * a diagonal stretch is as wide), {@link #TUNNEL_HEIGHT} high, with a floor; false when it hits the void.
		 */
		private boolean section(BlockPos feet, double angle) {
			if (feet.getY() <= level.getMinBuildHeight() + 5) return false;
			double px = -Math.sin(angle), pz = Math.cos(angle);
			List<BlockPos> opened = new ArrayList<>();
			for (double side = -TUNNEL_WIDTH / 2; side <= TUNNEL_WIDTH / 2; side += 0.5)
				for (int up = 0; up < TUNNEL_HEIGHT; up++) {
					BlockPos pos = BlockPos.containing(feet.getX() + 0.5 + px * side, feet.getY() + up, feet.getZ() + 0.5 + pz * side);
					if (carve(pos)) opened.add(pos);
					if (up == 0) floor(pos);
				}
			hive.addRoute(feet);
			if (near(feet)) for (BlockPos pos : opened) CreepGrowth.lineCavity(level, pos);
			return true;
		}

		/** Empties a cell (sealing water or lava around it); false for unbreakable cells and the hive's foundations. */
		private boolean carve(BlockPos pos) {
			BlockState state = level.getBlockState(pos);
			if (state.isAir()) return true;
			if (state.getDestroySpeed(level, pos) < 0 || level.getBlockEntity(pos) != null || state.is(StarMineBlocks.THICK_CREEP)) return false;
			for (Direction direction : Direction.values()) {
				BlockPos next = pos.relative(direction);
				if (!level.getFluidState(next).isEmpty()) level.setBlock(next, filler(next), 2);
			}
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
			cells++;
			return true;
		}

		/** A floor under a tunnel cell that opens onto a cave or water. */
		private void floor(BlockPos pos) {
			BlockPos below = pos.below();
			BlockState state = level.getBlockState(below);
			if ((state.isAir() || !state.getFluidState().isEmpty() || state.canBeReplaced()) && state.getDestroySpeed(level, below) >= 0)
				level.setBlock(below, filler(below), 2);
		}

		private BlockState filler(BlockPos pos) {
			return (pos.getY() < 0 ? Blocks.COBBLED_DEEPSLATE : Blocks.COBBLESTONE).defaultBlockState();
		}

		/** A hall: a dome 16–20 blocks across its floor at `feet`, {@link #HALL_HEIGHT} high, with what lives in it. */
		private void hall(BlockPos feet) {
			if (feet.getY() <= level.getMinBuildHeight() + 6) return;
			boolean close = near(feet);
			UnitKind building = !undergroundLeft.isEmpty() && random.nextInt(100) < 45
				? undergroundLeft.remove(random.nextInt(undergroundLeft.size())) : null;
			// a hall with a building has room round its footprint (3x3 cells: 9 blocks) and over its height
			int radius = building != null ? (int) Math.ceil(building.width / 2) + 7 : 8 + random.nextInt(3);
			int height = building != null ? Math.max(HALL_HEIGHT, (int) Math.ceil(building.height) + 2) : HALL_HEIGHT;
			List<BlockPos> floor = new ArrayList<>(), opened = new ArrayList<>();
			for (int dx = -radius; dx <= radius; dx++)
				for (int dz = -radius; dz <= radius; dz++)
					for (int dy = 0; dy <= height; dy++) {
						double r = (dx * dx + dz * dz) / (double) (radius * radius) + dy * dy / (double) (height * height);
						if (r > 1) continue;
						BlockPos pos = feet.offset(dx, dy, dz);
						if (!carve(pos)) continue;
						opened.add(pos);
						if (dy == 0) {
							floor(pos);
							floor.add(pos);
						}
					}
			if (floor.isEmpty()) return;
			halls++;
			hive.addRoute(feet);
			int clear = 0;
			if (building != null) {
				clear = HiveTerrain.foundation(building) + 1;
				HiveTerrain.lay(level, feet, HiveTerrain.foundation(building));
				wait(building, feet, null, null, 0);
				hallBuildings++;
			}
			if (close) {
				for (BlockPos pos : opened) CreepGrowth.lineCavity(level, pos);
				BlockPos spot = pick(floor, feet, clear, UnitKind.LARVA);
				if (spot != null && CreepGrowth.placeTumor(level, spot, CreepSources.TUMOR_SPACING)) tumors++;
				for (BlockPos pos : floor) if (random.nextInt(4) == 0) CreepGrowth.growthAt(level, pos);
				for (int i = 1 + random.nextInt(3); i > 0; i--) {
					BlockPos at = pick(floor, feet, clear, UnitKind.LARVA);
					if (at != null) wait(UnitKind.LARVA, at, feet, null, 0);
				}
			}
			for (int i = (close ? 3 : 2) + random.nextInt(3); i > 0; i--) {
				UnitKind brood = BROOD[random.nextInt(BROOD.length)];
				BlockPos at = pick(floor, feet, clear, brood); // room for what hatches from it
				if (at == null) continue;
				wait(UnitKind.EGG, at, feet, brood, brood == UnitKind.ZERGLING ? 2 : 1);
				eggs++;
			}
			for (int i = 1 + random.nextInt(3); i > 0; i--) {
				UnitKind guard = GUARDS[random.nextInt(GUARDS.length)];
				BlockPos at = pick(floor, feet, clear, guard);
				if (at != null) wait(guard, at, feet, null, 0);
			}
		}

		/**
		 * A free floor cell of the hall at least `clear` blocks from its centre (around a building) where a body
		 * of `kind` fits under the dome (an ultralisk only near the middle).
		 */
		private BlockPos pick(List<BlockPos> floor, BlockPos centre, int clear, UnitKind kind) {
			for (int attempt = 0; attempt < 16; attempt++) {
				BlockPos pos = floor.get(random.nextInt(floor.size()));
				if (Math.abs(pos.getX() - centre.getX()) <= clear + kind.width / 2 && Math.abs(pos.getZ() - centre.getZ()) <= clear + kind.width / 2) continue;
				BlockState state = level.getBlockState(pos);
				if ((state.isAir() || state.is(StarMineBlocks.CREEP)) && dev.starmine.entity.Spots.fits(level, kind, Vec3.atBottomCenterOf(pos))) return pos;
			}
			return null;
		}

		/** Creep on the floor of an empty cell (for a tumor to stand on). */
		private boolean creepFloor(BlockPos cell) {
			BlockState state = level.getBlockState(cell);
			if (state.is(StarMineBlocks.CREEP)) return true;
			if (!state.isAir()) return false;
			BlockState placed = StarMineBlocks.CREEP.getStateForPlacement(state, level, cell, Direction.DOWN);
			if (placed == null) return false;
			level.setBlockAndUpdate(cell, placed);
			return true;
		}
	}

	/** Clears a square site (trees, plants, earth above) and fills holes under it, up to `height`. */
	public static void flatten(ServerLevel level, BlockPos feet, int half, int height) {
		flatten(level, feet, half, height, false);
	}

	/**
	 * A laid-over map's resources stay when a site is cleared: StarMine's blocks (vespene, crystals) and,
	 * on the map, the cobblestone of a geyser's mound and walls ({@link dev.starmine.world.MapOverlay}).
	 */
	private static boolean keep(BlockState state, BlockPos pos) {
		if (BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals(dev.starmine.StarMine.MOD_ID)) return true;
		return state.is(Blocks.COBBLESTONE) && dev.starmine.world.MapOverlay.covers(pos.getX(), pos.getZ());
	}

	/** {@link #flatten}; `round`: only the disc of radius `half` (owner 16.10: the land round a base's plaza stays as it was). */
	public static void flatten(ServerLevel level, BlockPos feet, int half, int height, boolean round) {
		for (int dx = -half; dx <= half; dx++)
			for (int dz = -half; dz <= half; dz++) {
				if (round && dx * dx + dz * dz > (half + 0.5) * (half + 0.5)) continue;
				for (int dy = 0; dy <= height + 12; dy++) {
					BlockPos pos = feet.offset(dx, dy, dz);
					BlockState state = level.getBlockState(pos);
					if (state.isAir() || state.getDestroySpeed(level, pos) < 0 || keep(state, pos)) continue;
					if (dy <= height || state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
				}
				for (int dy = 1; dy <= 6; dy++) {
					BlockPos pos = feet.offset(dx, -dy, dz);
					BlockState state = level.getBlockState(pos);
					if (!state.canBeReplaced() && state.getFluidState().isEmpty() && !state.is(BlockTags.LOGS)) break;
					level.setBlock(pos, Blocks.DIRT.defaultBlockState(), 2);
				}
			}
	}
}
