package dev.starmine.hive;

import dev.starmine.StarMine;
import dev.starmine.block.StarMineBlocks;
import dev.starmine.creep.CreepGrowth;
import dev.starmine.creep.CreepSources;
import dev.starmine.entity.ZergKind;
import dev.starmine.entity.ZergMob;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
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
 * - Site: once per world, 96–144 blocks from the world spawn on dry, fairly flat ground (game rule
 *   `starmineSpawnHive`). It is built when a player comes within {@link #TRIGGER} blocks.
 * - Surface: the hatchery on its foundation with creep already grown, the spawning pool, evolution
 *   chamber, roach warren, spire (always on the surface), spine and spore crawlers, creep tumors,
 *   drones, zerglings, a queen, roaches, hydralisks and overlords. The hive starts as a lair.
 * - Tunnels: 4–6 branches leave the hatchery in different directions, go down by steps (3 wide, 4
 *   high, walkable by every ground zerg) to 12–22 blocks under the surface and wander 40–60 blocks,
 *   some forking. Halls open at their ends, at forks and (most branches) 14–20 blocks out. Water
 *   and lava next to a tunnel are sealed off.
 * - Near the hatchery (within {@link #NEAR}) tunnels and halls are lined with creep and tumors, and
 *   halls hold growths, larvae and sleeping eggs; farther halls hold eggs and guards. Some halls hold a
 *   building (never the spire). Nest dwellers keep to their hall and are not counted in the hive
 *   ({@link ZergMob#makeNest}); a nest egg wakes when disturbed and hatches warriors.
 * - Zerg are not put in the world at once: each waits in {@link HiveSavedData#pending} until its chunk
 *   is entity-ticking (a player near), so remote halls are filled when someone comes.
 *
 * Generation is a runtime build, not a datapack structure: the hive is entity-driven ({@link HiveLogic}),
 * and the world keeps at most {@link HiveSavedData#MAX_HIVES} hives.
 */
public final class HiveStructure {
	public static final int MIN_DISTANCE = 96, MAX_DISTANCE = 144, TRIGGER = 112;
	/** Tunnels and halls this close to the hatchery are the hive's own: creep, tumors, larvae. */
	public static final int NEAR = 40;
	private static final ZergKind[] SURFACE = {ZergKind.SPAWNING_POOL, ZergKind.SPINE_CRAWLER, ZergKind.EVOLUTION_CHAMBER,
		ZergKind.SPORE_CRAWLER, ZergKind.SPIRE, ZergKind.SPINE_CRAWLER, ZergKind.ROACH_WARREN};
	private static final ZergKind[] UNDERGROUND = {ZergKind.HYDRALISK_DEN, ZergKind.BANELING_NEST, ZergKind.INFESTATION_PIT,
		ZergKind.ULTRALISK_CAVERN, ZergKind.EVOLUTION_CHAMBER};
	private static final ZergKind[] GUARDS = {ZergKind.ZERGLING, ZergKind.ZERGLING, ZergKind.ROACH, ZergKind.HYDRALISK};
	private static final ZergKind[] BROOD = {ZergKind.ZERGLING, ZergKind.ZERGLING, ZergKind.ROACH, ZergKind.HYDRALISK,
		ZergKind.BANELING, ZergKind.ROACH, ZergKind.ULTRALISK};

	/** A zerg of a generated hive waiting to be put in the world (see the class comment). */
	public record Pending(UUID hive, ZergKind kind, BlockPos pos, BlockPos hall, ZergKind brood, int broodCount) {
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
			return tag;
		}

		static Pending load(CompoundTag tag) {
			try {
				return new Pending(tag.getUUID("hive"), ZergKind.valueOf(tag.getString("kind")), BlockPos.of(tag.getLong("pos")),
					tag.contains("hall") ? BlockPos.of(tag.getLong("hall")) : null,
					tag.contains("brood") ? ZergKind.valueOf(tag.getString("brood")) : null, tag.getInt("broodCount"));
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
		if (!data.pending.isEmpty()) spawnPending(level, data);
		if (data.spawnHiveDone || !level.getGameRules().getBoolean(StarMine.SPAWN_HIVE) || level.players().isEmpty()) return;
		if (data.spawnHiveSite == null) {
			data.spawnHiveSite = chooseSite(level, level.getSharedSpawnPos(), RandomSource.create(level.getSeed() ^ 0x5A4E5L));
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
		if (!data.full()) build(level, site, RandomSource.create(level.getSeed() ^ site.asLong()));
	}

	/** A dry, fairly flat place 96–144 blocks from `spawn`; null when none is found. */
	public static BlockPos chooseSite(ServerLevel level, BlockPos spawn, RandomSource random) {
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
	static int ground(ServerLevel level, int x, int z) {
		level.getChunk(x >> 4, z >> 4);
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		while (y > level.getMinBuildHeight() + 1) {
			BlockState below = level.getBlockState(new BlockPos(x, y - 1, z));
			if (below.is(BlockTags.LOGS) || below.is(BlockTags.LEAVES) || below.canBeReplaced() && below.getFluidState().isEmpty()) y--;
			else break;
		}
		return y;
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
			if (!level.isPositionEntityTicking(p.pos())) continue;
			it.remove();
			added++;
			spawn(level, data, p);
		}
		if (added > 0) data.setDirty();
	}

	private static void spawn(ServerLevel level, HiveSavedData data, Pending p) {
		Hive hive = data.get(p.hive());
		Vec3 at = Vec3.atBottomCenterOf(p.pos());
		if (p.kind() == ZergKind.HATCHERY) {
			if (hive == null) return;
			ZergMob hatchery = StarMine.type(ZergKind.HATCHERY).create(level);
			if (hatchery == null) return;
			hatchery.setUUID(hive.id);
			hatchery.moveTo(at.x, at.y, at.z, level.random.nextFloat() * 360, 0);
			hatchery.joinHive(hive);
			level.addFreshEntity(hatchery);
		} else if (p.hall() != null) {
			ZergMob mob = HiveLogic.spawnNest(level, hive, p.kind(), at, p.hall());
			if (mob != null && p.brood() != null) mob.becomeCocoon(p.brood(), p.broodCount(), ZergMob.DORMANT);
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
		private final List<ZergKind> undergroundLeft = new ArrayList<>(List.of(UNDERGROUND));

		Builder(ServerLevel level, RandomSource random) {
			this.level = level;
			this.random = random;
			this.data = HiveSavedData.get(level.getServer());
		}

		Report build(BlockPos site) {
			centre = new BlockPos(site.getX(), ground(level, site.getX(), site.getZ()), site.getZ());
			for (int cx = (centre.getX() - 80) >> 4; cx <= (centre.getX() + 80) >> 4; cx++)
				for (int cz = (centre.getZ() - 80) >> 4; cz <= (centre.getZ() + 80) >> 4; cz++) level.getChunk(cx, cz);
			hive = new Hive(UUID.randomUUID(), level.dimension().location().toString(), centre);
			hive.minerals = 400;
			hive.vespene = 200;
			hive.tier = 2;
			data.add(hive);
			flatten(centre, HiveTerrain.HATCHERY_FOUNDATION + 1, 10);
			HiveTerrain.lay(level, centre, HiveTerrain.HATCHERY_FOUNDATION);
			hive.foundation = true;
			wait(ZergKind.HATCHERY, centre, null, null, 0);

			double turn = Math.PI * 2 / SURFACE.length, start = random.nextDouble() * Math.PI * 2;
			// tunnels leave between the buildings
			List<Integer> gaps = new ArrayList<>();
			for (int i = 0; i < SURFACE.length; i++) gaps.add(i);
			java.util.Collections.shuffle(gaps, new java.util.Random(random.nextLong()));
			int branches = 4 + random.nextInt(3);
			for (int b = 0; b < branches; b++) {
				double angle = start + (gaps.get(b) + 0.5) * turn;
				tunnel(centre.getX() + Math.cos(angle) * 9.5, centre.getY(), centre.getZ() + Math.sin(angle) * 9.5, angle,
					40 + random.nextInt(21), centre.getY() - 12 - random.nextInt(11), true);
			}
			for (int i = 0; i < SURFACE.length; i++) {
				ZergKind kind = SURFACE[i];
				double angle = start + i * turn, distance = 8 + HiveTerrain.foundation(kind) + 3 + random.nextInt(2);
				int x = Mth.floor(centre.getX() + 0.5 + Math.cos(angle) * distance), z = Mth.floor(centre.getZ() + 0.5 + Math.sin(angle) * distance);
				BlockPos feet = new BlockPos(x, Mth.clamp(ground(level, x, z), centre.getY() - 3, centre.getY() + 3), z);
				flatten(feet, HiveTerrain.foundation(kind) + 1, (int) Math.ceil(kind.height) + 2);
				HiveTerrain.lay(level, feet, HiveTerrain.foundation(kind));
				wait(kind, feet, null, null, 0);
			}
			// creep grows out from the hatchery now, as if the hive had stood here a while
			for (int i = 0; i < 400; i++) CreepGrowth.grow(level, centre, CreepSources.HATCHERY_RADIUS, HiveTerrain.HATCHERY_FOUNDATION + 1);
			for (int i = 0; i < 3; i++) {
				double angle = start + random.nextDouble() * Math.PI * 2;
				int x = Mth.floor(centre.getX() + Math.cos(angle) * 13), z = Mth.floor(centre.getZ() + Math.sin(angle) * 13);
				BlockPos cell = new BlockPos(x, ground(level, x, z), z);
				if (creepFloor(cell) && CreepGrowth.placeTumor(level, cell, CreepSources.TUMOR_SPACING)) tumors++;
			}
			ZergKind[] army = {ZergKind.DRONE, ZergKind.DRONE, ZergKind.DRONE, ZergKind.DRONE, ZergKind.DRONE, ZergKind.DRONE,
				ZergKind.ZERGLING, ZergKind.ZERGLING, ZergKind.ZERGLING, ZergKind.ZERGLING, ZergKind.ZERGLING, ZergKind.ZERGLING,
				ZergKind.QUEEN, ZergKind.ROACH, ZergKind.ROACH, ZergKind.HYDRALISK, ZergKind.HYDRALISK, ZergKind.OVERLORD, ZergKind.OVERLORD};
			for (ZergKind kind : army) {
				double angle = random.nextDouble() * Math.PI * 2, distance = 8.5 + random.nextDouble() * 3;
				int x = Mth.floor(centre.getX() + Math.cos(angle) * distance), z = Mth.floor(centre.getZ() + Math.sin(angle) * distance);
				wait(kind, new BlockPos(x, ground(level, x, z), z), null, null, 0);
			}
			data.setDirty();
			return new Report(cells, halls, hallBuildings, eggs, tumors);
		}

		private void wait(ZergKind kind, BlockPos pos, BlockPos hall, ZergKind brood, int broodCount) {
			data.pending.add(new Pending(hive.id, kind, pos.immutable(), hall == null ? null : hall.immutable(), brood, broodCount));
		}

		/** Clears a square site (trees, plants, earth above) and fills holes under it, up to `height`. */
		private void flatten(BlockPos feet, int half, int height) {
			for (int dx = -half; dx <= half; dx++)
				for (int dz = -half; dz <= half; dz++) {
					for (int dy = 0; dy <= height + 12; dy++) {
						BlockPos pos = feet.offset(dx, dy, dz);
						BlockState state = level.getBlockState(pos);
						if (state.isAir() || state.getDestroySpeed(level, pos) < 0) continue;
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

		/** The tunnel's cross-section at `feet`: 3 wide, 4 high, with a floor; false when it hits the void. */
		private boolean section(BlockPos feet, double angle) {
			if (feet.getY() <= level.getMinBuildHeight() + 5) return false;
			double px = -Math.sin(angle), pz = Math.cos(angle);
			List<BlockPos> opened = new ArrayList<>();
			for (int side = -1; side <= 1; side++)
				for (int up = 0; up < 4; up++) {
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

		/** A hall: a dome 6–8 blocks across its floor at `feet`, with what lives in it. */
		private void hall(BlockPos feet) {
			if (feet.getY() <= level.getMinBuildHeight() + 6) return;
			boolean close = near(feet);
			ZergKind building = !undergroundLeft.isEmpty() && random.nextInt(100) < 45
				? undergroundLeft.remove(random.nextInt(undergroundLeft.size())) : null;
			int radius = building != null ? 7 : 5 + random.nextInt(3), height = building != null ? 6 : 5;
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
				BlockPos spot = pick(floor, feet, clear);
				if (spot != null && CreepGrowth.placeTumor(level, spot, CreepSources.TUMOR_SPACING)) tumors++;
				for (BlockPos pos : floor) if (random.nextInt(4) == 0) CreepGrowth.growthAt(level, pos);
				for (int i = 1 + random.nextInt(3); i > 0; i--) {
					BlockPos at = pick(floor, feet, clear);
					if (at != null) wait(ZergKind.LARVA, at, feet, null, 0);
				}
			}
			for (int i = (close ? 3 : 2) + random.nextInt(3); i > 0; i--) {
				BlockPos at = pick(floor, feet, clear);
				if (at == null) continue;
				ZergKind brood = BROOD[random.nextInt(BROOD.length)];
				wait(ZergKind.EGG, at, feet, brood, brood == ZergKind.ZERGLING ? 2 : 1);
				eggs++;
			}
			for (int i = 1 + random.nextInt(3); i > 0; i--) {
				BlockPos at = pick(floor, feet, clear);
				if (at != null) wait(GUARDS[random.nextInt(GUARDS.length)], at, feet, null, 0);
			}
		}

		/** A free floor cell of the hall at least `clear` blocks from its centre (around a building). */
		private BlockPos pick(List<BlockPos> floor, BlockPos centre, int clear) {
			for (int attempt = 0; attempt < 12; attempt++) {
				BlockPos pos = floor.get(random.nextInt(floor.size()));
				if (Math.abs(pos.getX() - centre.getX()) <= clear && Math.abs(pos.getZ() - centre.getZ()) <= clear) continue;
				BlockState state = level.getBlockState(pos);
				if ((state.isAir() || state.is(StarMineBlocks.CREEP)) && level.getBlockState(pos.above()).isAir()) return pos;
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
}
