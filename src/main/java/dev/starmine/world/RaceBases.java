package dev.starmine.world;

import dev.starmine.StarMine;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import dev.starmine.hive.HiveStructure;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Generated bases of the terrans and the protoss, and the zerg leviathan (owner 14.10). Like the zerg
 * lair ({@link HiveStructure}) each is built once per world, at runtime, when a player comes within
 * {@link #TRIGGER} blocks of its site (game rule `starmineSpawnBases`):
 * - terrans, 176–256 blocks from the spawn: a paved base with a command center, depots, barracks,
 *   factory, starport, engineering bay, bunker and turret, SCVs and an army; a technological labyrinth
 *   under it ({@link Dungeons#techLabyrinth}); the battlecruiser Hyperion in the sky ({@link SkyShips#hyperion});
 * - protoss, as far, at least {@link #SEPARATION} from the others: a sandstone plaza with a nexus,
 *   pylons, gateways, forge, cybernetics core, stargate, cannon and battery, probes and an army; ancient
 *   temples under it ({@link Dungeons#ancientTemples}); the mothership in the sky ({@link SkyShips#mothership});
 * - zerg: a leviathan over the generated lair ({@link SkyShips#leviathan}), also in worlds whose lair
 *   was built before it existed.
 * The bases are wild ({@link dev.starmine.faction.Faction}): their race's AI runs them
 * ({@link dev.starmine.ai.AiCommander}). Units wait in {@link HiveSavedData#pending} until their chunk ticks.
 */
public final class RaceBases {
	public static final int MIN_DISTANCE = 176, MAX_DISTANCE = 256, TRIGGER = 128, SEPARATION = 128;
	public static final String LEVIATHAN = "LEVIATHAN";
	/** How high over the base's ground a sky ship's middle hangs. */
	public static final int SKY = 55;

	private RaceBases() {
	}

	/** What a build made. */
	public record Report(@Nullable UUID base, int blocks, int units) {
	}

	/** Server tick: picks the sites and builds what a player comes near. */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 10) return;
		ServerLevel level = server.overworld();
		if (!level.getGameRules().getBoolean(StarMine.SPAWN_BASES) || level.players().isEmpty()) return;
		HiveSavedData data = HiveSavedData.get(server);
		for (UnitKind.Race race : new UnitKind.Race[] {UnitKind.Race.TERRAN, UnitKind.Race.PROTOSS}) {
			String key = race.name();
			if (data.structuresDone.contains(key)) continue;
			BlockPos site = data.structureSites.get(key);
			if (site == null) {
				site = chooseSite(level, data, key);
				if (site == null) data.structuresDone.add(key);
				else data.structureSites.put(key, site);
				data.setDirty();
				return; // one site per tick: choosing loads chunks
			}
			if (playerNear(level, site)) {
				data.structuresDone.add(key);
				data.setDirty();
				build(level, race, site, RandomSource.create(level.getSeed() ^ site.asLong()));
				return;
			}
		}
		if (!data.structuresDone.contains(LEVIATHAN) && data.spawnHiveDone) {
			if (data.spawnHiveSite == null) {
				data.structuresDone.add(LEVIATHAN);
				data.setDirty();
			} else if (playerNear(level, data.spawnHiveSite)) {
				data.structuresDone.add(LEVIATHAN);
				data.setDirty();
				leviathan(level, data.spawnHiveSite, RandomSource.create(level.getSeed() ^ 0x1E7A7A4L));
			}
		}
	}

	private static boolean playerNear(ServerLevel level, BlockPos site) {
		return level.players().stream().anyMatch(player -> !player.isSpectator()
			&& Mth.square(player.getX() - site.getX()) + Mth.square(player.getZ() - site.getZ()) < TRIGGER * TRIGGER);
	}

	/** A site for `key` away from the spawn lair and the other bases; null when none is found. */
	private static @Nullable BlockPos chooseSite(ServerLevel level, HiveSavedData data, String key) {
		RandomSource random = RandomSource.create(level.getSeed() ^ key.hashCode() * 0x9E3779B97F4A7C15L);
		List<BlockPos> others = new ArrayList<>(data.structureSites.values());
		if (data.spawnHiveSite != null) others.add(data.spawnHiveSite);
		for (int attempt = 0; attempt < 6; attempt++) {
			BlockPos site = HiveStructure.chooseSite(level, level.getSharedSpawnPos(), random, MIN_DISTANCE, MAX_DISTANCE);
			if (site != null && others.stream().allMatch(other -> other.distSqr(site) >= SEPARATION * SEPARATION)) return site;
		}
		return null;
	}

	// ---- the bases ----

	private static final UnitKind[] TERRAN_RING = {UnitKind.SUPPLY_DEPOT, UnitKind.BARRACKS, UnitKind.ENGINEERING_BAY, UnitKind.BUNKER,
		UnitKind.FACTORY, UnitKind.SUPPLY_DEPOT, UnitKind.STARPORT, UnitKind.MISSILE_TURRET};
	private static final UnitKind[] PROTOSS_RING = {UnitKind.GATEWAY, UnitKind.FORGE, UnitKind.PHOTON_CANNON, UnitKind.CYBERNETICS_CORE,
		UnitKind.GATEWAY, UnitKind.STARGATE, UnitKind.SHIELD_BATTERY, UnitKind.TWILIGHT_COUNCIL};
	private static final UnitKind[] TERRAN_ARMY = {UnitKind.SCV, UnitKind.SCV, UnitKind.SCV, UnitKind.SCV, UnitKind.SCV, UnitKind.SCV,
		UnitKind.MARINE, UnitKind.MARINE, UnitKind.MARINE, UnitKind.MARINE, UnitKind.MARINE, UnitKind.MARAUDER, UnitKind.MARAUDER,
		UnitKind.SIEGE_TANK, UnitKind.MEDIVAC};
	private static final UnitKind[] PROTOSS_ARMY = {UnitKind.PROBE, UnitKind.PROBE, UnitKind.PROBE, UnitKind.PROBE, UnitKind.PROBE,
		UnitKind.PROBE, UnitKind.ZEALOT, UnitKind.ZEALOT, UnitKind.ZEALOT, UnitKind.ZEALOT, UnitKind.STALKER, UnitKind.STALKER,
		UnitKind.STALKER, UnitKind.SENTRY, UnitKind.OBSERVER};
	/** The plaza's radius; buildings stand round the main building inside it. */
	private static final int PLAZA = 18;

	/** Builds a wild base of `race` at `site` (the main building's ground cell), its dungeon and its sky ship. */
	public static Report build(ServerLevel level, UnitKind.Race race, BlockPos site, RandomSource random) {
		HiveSavedData data = HiveSavedData.get(level.getServer());
		Painter painter = new Painter(level, random);
		painter.load(site, 144);
		BlockPos centre = new BlockPos(site.getX(), HiveStructure.ground(level, site.getX(), site.getZ()), site.getZ());
		UnitKind main = race.rules().main();
		Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), centre);
		base.race = race;
		base.minerals = 400;
		base.vespene = 200;
		data.add(base);
		List<Spawn> spawns = new ArrayList<>();
		boolean terran = race == UnitKind.Race.TERRAN;

		// the plaza
		HiveStructure.flatten(level, centre, PLAZA, 9);
		BlockState pave = terran ? Blocks.GRAY_CONCRETE.defaultBlockState() : Blocks.SMOOTH_SANDSTONE.defaultBlockState();
		BlockState line = terran ? Blocks.YELLOW_CONCRETE.defaultBlockState() : Blocks.GOLD_BLOCK.defaultBlockState();
		BlockState inlay = terran ? Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState() : Blocks.LAPIS_BLOCK.defaultBlockState();
		for (int dx = -PLAZA; dx <= PLAZA; dx++)
			for (int dz = -PLAZA; dz <= PLAZA; dz++) {
				double r = Math.sqrt(dx * dx + dz * dz);
				if (r > PLAZA + 0.5) continue;
				BlockState state = Math.abs(r - 5.5) < 0.5 ? line : terran ? ((dx + 100) / 4 + (dz + 100) / 4) % 2 == 0 ? pave : inlay
					: Math.abs(r - PLAZA + 1) < 0.6 ? inlay : pave;
				painter.set(centre.offset(dx, -1, dz), state);
			}
		spawns.add(Spawn.member(main, centre));

		// buildings round the main one (east left free for the way down); protoss pylons between them
		UnitKind[] ring = terran ? TERRAN_RING : PROTOSS_RING;
		double step = Math.PI * 2 / (ring.length + 1), radius = main.width / 2 + 7.5;
		for (int i = 0; i < ring.length; i++) {
			double angle = (i + 1) * step;
			BlockPos at = groundAt(level, centre, angle, radius + ring[i].width / 2);
			spawns.add(Spawn.member(ring[i], at));
			if (!terran) spawns.add(Spawn.member(UnitKind.PYLON, groundAt(level, centre, angle + step / 2, radius - 1.5)));
		}
		if (!terran) spawns.add(Spawn.member(UnitKind.PYLON, groundAt(level, centre, step / 2, radius - 1.5)));
		for (UnitKind kind : terran ? TERRAN_ARMY : PROTOSS_ARMY) {
			double angle = random.nextDouble() * Math.PI * 2;
			BlockPos at = groundAt(level, centre, angle, main.width / 2 + kind.width / 2 + 1 + random.nextDouble() * 2.5); // out of the main building
			spawns.add(Spawn.member(kind, kind.flying ? at.above(kind.hoverHeight() > 0 ? (int) kind.hoverHeight() : 4) : at));
		}

		// under the base and over it
		int depth = 26;
		int under = Math.max(level.getMinBuildHeight() + 8, centre.getY() - depth);
		int sky = Math.min(level.getMaxBuildHeight() - 30, centre.getY() + SKY);
		if (terran) {
			int span = Dungeons.MAZE_CELLS * Dungeons.PITCH;
			spawns.addAll(Dungeons.techLabyrinth(painter, new BlockPos(centre.getX() + PLAZA + depth + 4, under, centre.getZ() - span / 2)));
			spawns.addAll(SkyShips.hyperion(painter, new BlockPos(centre.getX(), sky, centre.getZ()), random.nextInt(4)));
		} else {
			spawns.addAll(Dungeons.ancientTemples(painter, new BlockPos(centre.getX(), under, centre.getZ() + PLAZA + depth + 4 + 34 + 8)));
			spawns.addAll(SkyShips.mothership(painter, new BlockPos(centre.getX(), sky, centre.getZ())));
		}
		for (Spawn spawn : spawns) wait(data, base.id, spawn);
		data.setDirty();
		return new Report(base.id, painter.placed(), spawns.size());
	}

	/** A leviathan over the generated lair at `lair`; its guards belong to the lair's hive when it still stands. */
	public static Report leviathan(ServerLevel level, BlockPos lair, RandomSource random) {
		HiveSavedData data = HiveSavedData.get(level.getServer());
		Painter painter = new Painter(level, random);
		painter.load(lair, 64);
		int ground = HiveStructure.ground(level, lair.getX(), lair.getZ());
		BlockPos middle = new BlockPos(lair.getX(), Math.min(level.getMaxBuildHeight() - 30, ground + SKY), lair.getZ());
		Hive hive = data.all().stream().filter(h -> h.owner == null && h.race == UnitKind.Race.ZERG && h.hatchery.distSqr(lair) < 32 * 32)
			.findFirst().orElse(null);
		UUID id = hive != null ? hive.id : UUID.randomUUID();
		List<Spawn> spawns = SkyShips.leviathan(painter, middle, random.nextInt(4));
		for (Spawn spawn : spawns) wait(data, id, spawn);
		data.setDirty();
		return new Report(hive == null ? null : hive.id, painter.placed(), spawns.size());
	}

	/** A sky ship alone (for the command): `what` is hyperion, mothership or leviathan; its guards have no base. */
	public static Report ship(ServerLevel level, String what, BlockPos middle, RandomSource random) {
		HiveSavedData data = HiveSavedData.get(level.getServer());
		Painter painter = new Painter(level, random);
		painter.load(middle, 64);
		List<Spawn> spawns = switch (what) {
			case "hyperion" -> SkyShips.hyperion(painter, middle, random.nextInt(4));
			case "mothership" -> SkyShips.mothership(painter, middle);
			default -> SkyShips.leviathan(painter, middle, random.nextInt(4));
		};
		UUID none = UUID.randomUUID();
		for (Spawn spawn : spawns) wait(data, none, spawn);
		data.setDirty();
		return new Report(null, painter.placed(), spawns.size());
	}

	private static BlockPos groundAt(ServerLevel level, BlockPos centre, double angle, double distance) {
		int x = Mth.floor(centre.getX() + 0.5 + Math.cos(angle) * distance), z = Mth.floor(centre.getZ() + 0.5 + Math.sin(angle) * distance);
		return new BlockPos(x, Mth.clamp(HiveStructure.ground(level, x, z), centre.getY() - 2, centre.getY() + 2), z);
	}

	private static void wait(HiveSavedData data, UUID base, Spawn spawn) {
		data.pending.add(new HiveStructure.Pending(base, spawn.kind(), spawn.pos(), spawn.hall(), spawn.brood(), spawn.broodCount()));
	}
}
