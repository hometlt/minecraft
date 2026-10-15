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
 * - terrans, 256 blocks at 180 degrees from the first player's captured heading: a paved base with a command center, depots, barracks,
 *   factory, starport, engineering bay, bunker and turret, SCVs and an army; a technological labyrinth
 *   under it ({@link Dungeons#techLabyrinth}); the battlecruiser Hyperion in the sky ({@link SkyShips#hyperion});
 * - protoss, as far at 120 degrees, at least {@link #SEPARATION} from the others: a sandstone plaza with a nexus,
 *   four pylons, gateways, forge, cybernetics core, stargate, cannon and battery, probes and an army; ancient
 *   temples under it ({@link Dungeons#ancientTemples}); the mothership in the sky ({@link SkyShips#mothership});
 * - zerg: a leviathan over the generated lair ({@link SkyShips#leviathan}), also in worlds whose lair
 *   was built before it existed.
 * The bases are wild ({@link dev.starmine.faction.Faction}): their race's AI runs them
 * ({@link dev.starmine.ai.AiCommander}). Units wait in {@link HiveSavedData#pending} until their chunk ticks.
 */
public final class RaceBases {
	public static final int MIN_DISTANCE = 256, MAX_DISTANCE = 256, TRIGGER = 128, SEPARATION = 256;
	public static final String LEVIATHAN = "LEVIATHAN";
	/** How high over the base's ground a sky ship's middle hangs (the ships grew 2-2.5 times: their tails and tentacles hang 45 below it). */
	public static final int SKY = 80;
	/**
	 * The middle height of the Hyperion and the mothership (owner 16.10): just over the clouds (y 192), higher
	 * than the leviathan, which keeps under {@link #LEVIATHAN_CEILING}.
	 */
	public static final int OVER_CLOUDS = 212, LEVIATHAN_CEILING = 176;

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

	/** A fixed 16-chunk radial offset relative to the first player's Minecraft heading. */
	public static BlockPos baseOffset(BlockPos origin, float heading, UnitKind.Race race) {
		double degrees = switch (race) { case ZERG -> 60; case PROTOSS -> 120; case TERRAN -> 180; };
		double angle = Math.toRadians(heading + degrees);
		return origin.offset((int) Math.round(-Math.sin(angle) * MIN_DISTANCE), 0,
			(int) Math.round(Math.cos(angle) * MIN_DISTANCE));
	}

	/** Captures the first player's origin once, then resolves the chosen radial column to its surface. */
	public static BlockPos spawnSite(ServerLevel level, HiveSavedData data, UnitKind.Race race) {
		if (data.baseOrigin == null) {
			var player = level.players().stream().filter(p -> !p.isSpectator()).findFirst().orElse(null);
			data.baseOrigin = player == null ? level.getSharedSpawnPos() : player.blockPosition();
			data.baseHeading = player == null ? 0 : player.getYRot();
			data.setDirty();
		}
		BlockPos column = baseOffset(data.baseOrigin, data.baseHeading, race);
		return new BlockPos(column.getX(), Math.max(HiveStructure.ground(level, column.getX(), column.getZ()),
			level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ())), column.getZ());
	}

	/** New sites use the shared fixed layout; saved sites and already built structures are not moved. */
	private static @Nullable BlockPos chooseSite(ServerLevel level, HiveSavedData data, String key) {
		return spawnSite(level, data, UnitKind.Race.valueOf(key));
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
	private static final int PLAZA = 30; // round a main building 15 wide and a ring of buildings 9 wide (3 blocks a cell)

	/** Builds a wild base of `race` at `site` (the main building's ground cell), its dungeon and its sky ship. */
	public static Report build(ServerLevel level, UnitKind.Race race, BlockPos site, RandomSource random) {
		HiveSavedData data = HiveSavedData.get(level.getServer());
		Painter painter = new Painter(level, random);
		painter.load(site, 160);
		BlockPos centre = new BlockPos(site.getX(), Math.max(site.getY(), HiveStructure.ground(level, site.getX(), site.getZ())), site.getZ());
		UnitKind main = race.rules().main();
		Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), centre);
		base.race = race;
		base.minerals = 400;
		base.vespene = 200;
		data.add(base);
		List<Spawn> spawns = new ArrayList<>();
		boolean terran = race == UnitKind.Race.TERRAN;

		// the plaza
		HiveStructure.flatten(level, centre, PLAZA, 16, true); // the disc only: the land round it stays as it was
		BlockState pave = terran ? Blocks.GRAY_CONCRETE.defaultBlockState() : Blocks.SMOOTH_SANDSTONE.defaultBlockState();
		BlockState line = terran ? Blocks.YELLOW_CONCRETE.defaultBlockState() : Blocks.YELLOW_TERRACOTTA.defaultBlockState();
		BlockState inlay = terran ? Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState() : Blocks.BLUE_TERRACOTTA.defaultBlockState();
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
		}
		// three pylons power the whole ring (owner 16.10: four and more were too many)
		if (!terran) for (int i = 0; i < 3; i++)
			spawns.add(Spawn.member(UnitKind.PYLON, groundAt(level, centre, Math.PI / 3 + i * Math.PI * 2 / 3, radius - 1.5)));
		lights(painter, centre, terran);
		for (UnitKind kind : terran ? TERRAN_ARMY : PROTOSS_ARMY) {
			double angle = random.nextDouble() * Math.PI * 2;
			BlockPos at = groundAt(level, centre, angle, main.width / 2 + kind.width / 2 + 1 + random.nextDouble() * 2.5); // out of the main building
			spawns.add(Spawn.member(kind, kind.flying ? at.above(kind.hoverHeight() > 0 ? (int) kind.hoverHeight() : 4) : at));
		}

		// under the base and over it
		int depth = 26;
		int under = Math.max(level.getMinBuildHeight() + 8, centre.getY() - depth);
		int sky = Math.min(level.getMaxBuildHeight() - 40, Math.max(centre.getY() + SKY, OVER_CLOUDS));
		Painter above = Painter.deferred(level, random);
		SkyShips.Ship ship;
		if (terran) {
			int span = Dungeons.MAZE_CELLS * Dungeons.PITCH;
			spawns.addAll(Dungeons.techLabyrinth(painter, new BlockPos(centre.getX() + PLAZA + depth + 4, under, centre.getZ() - span / 2)));
			ship = SkyShips.hyperion(above, new BlockPos(centre.getX(), sky, centre.getZ()), random.nextInt(4));
		} else {
			spawns.addAll(Dungeons.ancientTemples(painter, new BlockPos(centre.getX(), under, centre.getZ() + PLAZA + depth + 4 + 34 + 8)));
			ship = SkyShips.mothership(above, new BlockPos(centre.getX(), sky, centre.getZ()));
		}
		spawns.addAll(groundPad(level, painter, race, groundAt(level, centre, Math.PI, PLAZA + 6), terran ? Blocks.POLISHED_DIORITE.defaultBlockState()
			: Blocks.YELLOW_TERRACOTTA.defaultBlockState(), ship, above));
		for (Spawn spawn : spawns) wait(data, base.id, spawn);
		finish(level, data, base.id, ship, above);
		data.setDirty();
		return new Report(base.id, painter.placed() + above.placed(), spawns.size() + ship.spawns().size());
	}

	/**
	 * Lights over a base's plaza (owner 16.10: no monsters spawn there): lamps on posts round its edge and
	 * lights set in the paving — terran lamps, protoss sea lanterns.
	 */
	private static void lights(Painter painter, BlockPos centre, boolean terran) {
		BlockState floor = terran ? Blocks.OCHRE_FROGLIGHT.defaultBlockState() : Blocks.SEA_LANTERN.defaultBlockState();
		for (int dx = -PLAZA + 4; dx <= PLAZA - 4; dx += 8)
			for (int dz = -PLAZA + 4; dz <= PLAZA - 4; dz += 8)
				if (dx * dx + dz * dz <= (PLAZA - 2) * (PLAZA - 2)) painter.set(centre.offset(dx, -1, dz), floor);
		BlockState post = terran ? Blocks.POLISHED_ANDESITE.defaultBlockState() : Blocks.CHISELED_SANDSTONE.defaultBlockState();
		BlockState lamp = terran ? Blocks.LANTERN.defaultBlockState() : Blocks.SEA_LANTERN.defaultBlockState();
		for (int a = 0; a < 360; a += 20) {
			double rad = Math.toRadians(a);
			BlockPos foot = centre.offset((int) Math.round(Math.cos(rad) * (PLAZA - 1)), 0, (int) Math.round(Math.sin(rad) * (PLAZA - 1)));
			for (int up = 0; up < 3; up++) painter.set(foot.above(up), post);
			painter.set(foot.above(3), lamp);
		}
	}

	/** The teleporter by a base, its guards; it is paired with the ship's once the ship is built ({@link #finish}). */
	private static List<Spawn> groundPad(ServerLevel level, Painter painter, UnitKind.Race race, BlockPos at, BlockState rim, SkyShips.Ship ship, Painter above) {
		Teleporter.pad(painter, at, race, rim);
		above.then(() -> Teleporter.link(level, at, ship.pad()));
		UnitKind[] guards = switch (race) {
			case ZERG -> new UnitKind[] {UnitKind.ZERGLING, UnitKind.ROACH, UnitKind.ZERGLING, UnitKind.HYDRALISK};
			case TERRAN -> new UnitKind[] {UnitKind.MARINE, UnitKind.MARAUDER, UnitKind.MARINE, UnitKind.MARINE};
			case PROTOSS -> new UnitKind[] {UnitKind.ZEALOT, UnitKind.STALKER, UnitKind.ZEALOT, UnitKind.ADEPT};
		};
		List<Spawn> spawns = new ArrayList<>();
		for (int i = 0; i < guards.length; i++) {
			double angle = i * Math.PI / 2 + Math.PI / 4;
			spawns.add(Spawn.guard(guards[i], groundAt(level, at, angle, 7), at));
		}
		return spawns;
	}

	/** Once a ship is built, its guards wait for their chunks to tick (they would have fallen through it before). */
	private static void finish(ServerLevel level, HiveSavedData data, UUID base, SkyShips.Ship ship, Painter above) {
		above.then(() -> {
			for (Spawn spawn : ship.spawns()) wait(data, base, spawn);
			data.setDirty();
		});
		above.start();
	}

	/** A leviathan over the generated lair at `lair`; its guards belong to the lair's hive when it still stands. */
	public static Report leviathan(ServerLevel level, BlockPos lair, RandomSource random) {
		HiveSavedData data = HiveSavedData.get(level.getServer());
		Painter painter = new Painter(level, random);
		painter.load(lair, 112);
		int ground = HiveStructure.ground(level, lair.getX(), lair.getZ());
		BlockPos middle = new BlockPos(lair.getX(), Math.min(Math.min(level.getMaxBuildHeight() - 40, LEVIATHAN_CEILING), ground + SKY), lair.getZ());
		Hive hive = data.all().stream().filter(h -> h.owner == null && h.race == UnitKind.Race.ZERG && h.hatchery.distSqr(lair) < 32 * 32)
			.findFirst().orElse(null);
		UUID id = hive != null ? hive.id : UUID.randomUUID();
		Painter above = Painter.deferred(level, random);
		SkyShips.Ship ship = SkyShips.leviathan(above, middle, random.nextInt(4));
		List<Spawn> spawns = groundPad(level, painter, UnitKind.Race.ZERG, groundAt(level, lair, Math.PI, 24),
			Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState(), ship, above);
		for (Spawn spawn : spawns) wait(data, id, spawn);
		finish(level, data, id, ship, above);
		data.setDirty();
		return new Report(hive == null ? null : hive.id, painter.placed() + above.placed(), spawns.size() + ship.spawns().size());
	}

	/** A sky ship alone (for the command): `what` is hyperion, mothership or leviathan; its guards have no base. */
	public static Report ship(ServerLevel level, String what, BlockPos middle, RandomSource random) {
		HiveSavedData data = HiveSavedData.get(level.getServer());
		Painter painter = Painter.deferred(level, random);
		painter.load(middle, 112);
		SkyShips.Ship ship = switch (what) {
			case "hyperion" -> SkyShips.hyperion(painter, middle, random.nextInt(4));
			case "mothership" -> SkyShips.mothership(painter, middle);
			default -> SkyShips.leviathan(painter, middle, random.nextInt(4));
		};
		finish(level, data, UUID.randomUUID(), ship, painter);
		return new Report(null, painter.placed(), ship.spawns().size());
	}

	private static BlockPos groundAt(ServerLevel level, BlockPos centre, double angle, double distance) {
		int x = Mth.floor(centre.getX() + 0.5 + Math.cos(angle) * distance), z = Mth.floor(centre.getZ() + 0.5 + Math.sin(angle) * distance);
		return new BlockPos(x, Mth.clamp(HiveStructure.ground(level, x, z), centre.getY() - 2, centre.getY() + 2), z);
	}

	private static void wait(HiveSavedData data, UUID base, Spawn spawn) {
		data.pending.add(new HiveStructure.Pending(base, spawn.kind(), spawn.pos(), spawn.hall(), spawn.brood(), spawn.broodCount()));
	}
}
