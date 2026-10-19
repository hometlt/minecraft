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
import net.minecraft.core.Direction;
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
 * Under each a portal hall ({@link PortalHall}) leads to its sky ship.
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
	/** Owner 16.10: the three bases farther apart, 480 blocks (30 chunks) round the first player's place. */
	public static final int MIN_DISTANCE = 480, MAX_DISTANCE = 480, TRIGGER = 128, SEPARATION = 480;
	public static final String LEVIATHAN = "LEVIATHAN";
	/** How high over the base's ground a sky ship's middle hangs (the ships grew 2-2.5 times: their tails and tentacles hang 45 below it). */
	public static final int SKY = 80;
	/**
	 * The middle height of the sky ships — the Hyperion, the mothership and the leviathan (owner 16.10: the leviathan
	 * hung too low, now over the clouds like the others): just over the clouds (y 192).
	 */
	public static final int OVER_CLOUDS = 212;

	/** Where a sky ship's middle hangs over ground at `ground`: {@link #SKY} over it, at least {@link #OVER_CLOUDS}, under the build limit. */
	public static int skyHeight(ServerLevel level, int ground) {
		return Math.min(level.getMaxBuildHeight() - 40, Math.max(ground + SKY, OVER_CLOUDS));
	}

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
		if (farBases(level, data)) return;
		legacyGuardians(level, data);
		if (false && !data.structuresDone.contains(LEVIATHAN) && data.spawnHiveDone) { // owner 17.10: the leviathan comes when Abathur is freed
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

	/**
	 * Owner 17.10: worlds whose main bases were built before their guardians existed get them now (and their ships,
	 * built with the bases then, count as there): each guardian waits by its base's main building.
	 */
	static void legacyGuardians(ServerLevel level, HiveSavedData data) {
		for (UnitKind.Race race : UnitKind.Race.values()) {
			String guard = "GUARD:" + race.name();
			if (data.structuresDone.contains(guard)) continue;
			boolean built = race == UnitKind.Race.ZERG ? data.spawnHiveDone && data.spawnHiveSite != null : data.structuresDone.contains(race.name());
			BlockPos site = race == UnitKind.Race.ZERG ? data.spawnHiveSite : data.structureSites.get(race.name());
			if (!built || site == null) continue;
			data.structuresDone.add(guard);
			if (race == UnitKind.Race.ZERG ? data.structuresDone.contains(LEVIATHAN) : true) data.structuresDone.add("SHIP:" + race.name());
			Hive base = data.all().stream().filter(h -> h.owner == null && h.race == race && h.hatchery.distSqr(site) < 40 * 40).findFirst().orElse(null);
			BlockPos post = groundAt(level, site, Math.PI, race.rules().main().width / 2 + 5);
			data.pending.add(new HiveStructure.Pending(base != null ? base.id : UUID.randomUUID(), UnitKind.guardianOf(race), post, site, null, 0));
			data.setDirty();
		}
	}

	/**
	 * Owner 17.10: a race's ship comes to the stratosphere only once the guardian of its main base is freed — the
	 * Hyperion over the terran base, the mothership over the protoss one, the leviathan over the lair — with its own
	 * wild crew (its hero still Amun's), its arena, its key part and a hangar under it for the heroic transport.
	 */
	public static void shipAfterGuardian(ServerLevel any, UnitKind.Race race) {
		ServerLevel level = any.getServer().overworld();
		HiveSavedData data = HiveSavedData.get(level.getServer());
		String key = "SHIP:" + race.name();
		if (data.structuresDone.contains(key)) return;
		BlockPos site = race == UnitKind.Race.ZERG ? data.spawnHiveSite : data.structureSites.get(race.name());
		if (site == null) return;
		data.structuresDone.add(key);
		if (race == UnitKind.Race.ZERG) data.structuresDone.add(LEVIATHAN);
		ship(level, race, site);
	}

	/** The race's ship over `site` (the base's ground column) with a wild crew of its own; returns the crew's base. */
	public static Hive ship(ServerLevel level, UnitKind.Race race, BlockPos site) {
		HiveSavedData data = HiveSavedData.get(level.getServer());
		RandomSource random = RandomSource.create(level.getSeed() ^ site.asLong() ^ 0x5417L);
		ServerLevel skyLevel = skyLevel(level);
		int ground = HiveStructure.ground(level, site.getX(), site.getZ());
		BlockPos middle = new BlockPos(site.getX(), skyLevel == level ? skyHeight(level, ground) : Stratosphere.SHIP_Y, site.getZ());
		Hive crew = new Hive(UUID.randomUUID(), skyLevel.dimension().location().toString(), middle);
		crew.race = race;
		Painter above = Painter.deferred(skyLevel, random);
		SkyShips.Ship ship = switch (race) {
			case TERRAN -> SkyShips.hyperion(above, middle, random.nextInt(4));
			case PROTOSS -> SkyShips.mothership(above, middle);
			case ZERG -> SkyShips.leviathan(above, middle, random.nextInt(4));
		};
		crew.shipPad = ship.pad().immutable(); // a ship's crew: kept though it has no workers (BaseLogic)
		crew.shipDim = skyLevel == level ? null : skyLevel.dimension().location().toString();
		data.add(crew);
		finish(skyLevel, data, crew.id, ship, above);
		data.setDirty();
		return crew;
	}

	/** Owner 17.10: a region of the world (blocks a side) holds at most one far base, by this chance. */
	public static final int REGION = 1536, REGION_MARGIN = 256, FAR_FROM_SPAWN = 1000;
	public static final double FAR_CHANCE = 0.3; // owner 18.10: rarer (was 0.55)

	/** A far base: its race and its column (the ground is found when it is built). */
	public record Far(String key, UnitKind.Race race, int x, int z) {
	}

	/**
	 * The far base of a region, or null (owner 17.10: the races' bases are rare dungeons all over the world — the
	 * first three near the spawn, the rest far apart): one region in {@link #REGION} a side holds one by
	 * {@link #FAR_CHANCE}, somewhere inside its margins, never within {@link #FAR_FROM_SPAWN} of the first player's place.
	 */
	public static @Nullable Far far(long seed, @Nullable BlockPos origin, int rx, int rz) {
		RandomSource random = RandomSource.create(seed ^ (rx * 341873128712L + rz * 132897987541L) ^ 0xFA5BA5EL);
		if (random.nextDouble() >= FAR_CHANCE) return null;
		int x = rx * REGION + REGION_MARGIN + random.nextInt(REGION - 2 * REGION_MARGIN);
		int z = rz * REGION + REGION_MARGIN + random.nextInt(REGION - 2 * REGION_MARGIN);
		BlockPos home = origin != null ? origin : BlockPos.ZERO;
		if (Mth.square(x - home.getX()) + Mth.square(z - home.getZ()) < (double) FAR_FROM_SPAWN * FAR_FROM_SPAWN) return null;
		UnitKind.Race race = UnitKind.Race.values()[random.nextInt(3)];
		return new Far("FAR:" + rx + ":" + rz, race, x, z);
	}

	/** The far bases near players: the first one a player comes within {@link #TRIGGER} of is built (true then). */
	static boolean farBases(ServerLevel level, HiveSavedData data) {
		if (data.baseOrigin == null) return false; // the first three are placed first
		for (var player : level.players()) {
			if (player.isSpectator()) continue;
			net.minecraft.world.phys.Vec3 eye = dev.starmine.colony.CommanderEye.eye(player); // the regions round the camera, if it is open
			int prx = Math.floorDiv(eye != null ? (int) eye.x : player.getBlockX(), REGION), prz = Math.floorDiv(eye != null ? (int) eye.z : player.getBlockZ(), REGION);
			for (int rx = prx - 1; rx <= prx + 1; rx++)
				for (int rz = prz - 1; rz <= prz + 1; rz++) {
					Far far = far(level.getSeed(), data.baseOrigin, rx, rz);
					if (far == null || data.structuresDone.contains(far.key())) continue;
					if (!scouted(level, far.x(), far.z(), TRIGGER)) continue;
					data.structuresDone.add(far.key());
					data.setDirty();
					buildFar(level, far);
					return true;
				}
		}
		return false;
	}

	/** A far base: a race's base and its dungeon (a lair with its tunnels for the zerg), with no sky ship. */
	public static void buildFar(ServerLevel level, Far far) {
		int y = HiveStructure.ground(level, far.x(), far.z());
		BlockPos site = new BlockPos(far.x(), y, far.z());
		RandomSource random = RandomSource.create(level.getSeed() ^ site.asLong());
		if (far.race() == UnitKind.Race.ZERG) HiveStructure.build(level, site, random);
		else build(level, far.race(), site, random, false);
	}

	private static boolean playerNear(ServerLevel level, BlockPos site) {
		return scouted(level, site.getX(), site.getZ(), TRIGGER);
	}

	/**
	 * Whether a player has come within `reach` of a column: their body, their command view's camera
	 * ({@link dev.starmine.colony.CommanderEye}), or one of their units (owner 18.10: the army reached the protoss
	 * base and nothing was there — only the body counted, and the body stays home while its player commands).
	 */
	public static boolean scouted(ServerLevel level, double x, double z, double reach) {
		for (var player : level.players()) {
			if (player.isSpectator()) continue;
			if (Mth.square(player.getX() - x) + Mth.square(player.getZ() - z) < reach * reach) return true;
			net.minecraft.world.phys.Vec3 eye = dev.starmine.colony.CommanderEye.eye(player);
			if (eye != null && Mth.square(eye.x - x) + Mth.square(eye.z - z) < reach * reach) return true;
		}
		var area = new net.minecraft.world.phys.AABB(x - reach, level.getMinBuildHeight(), z - reach, x + reach, level.getMaxBuildHeight(), z + reach);
		return !level.getEntitiesOfClass(dev.starmine.entity.StarUnit.class, area, unit -> unit.isAlive() && unit.owner().isPresent()
			&& Mth.square(unit.getX() - x) + Mth.square(unit.getZ() - z) < reach * reach).isEmpty();
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
		// owner 17.10: the flattest dry place within 40 blocks of its column (bases on level land)
		BlockPos flat = HiveStructure.chooseSite(level, column, RandomSource.create(level.getSeed() ^ column.asLong()), 0, 40);
		if (flat != null) column = flat;
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
	/**
	 * The plaza's radius (owner 19.10: twice the old 30); the main building and its ring of buildings stand within CORE
	 * (round a main building 15 wide, a ring of buildings 9 wide: 3 blocks a cell), the guns round the edge at EDGE.
	 */
	public static final int PLAZA = 60, CORE = 30, EDGE = PLAZA - 8;

	/** Builds a wild base of `race` at `site` (the main building's ground cell), its dungeon and its sky ship. */
	public static Report build(ServerLevel level, UnitKind.Race race, BlockPos site, RandomSource random) {
		return build(level, race, site, random, true);
	}

	/** {@link #build}; a far base (`ship` false) has no sky ship and no portal hall. */
	public static Report build(ServerLevel level, UnitKind.Race race, BlockPos site, RandomSource random, boolean withShip) {
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

		// the plaza (owner 19.10: twice as wide; the buildings keep round the main one within CORE)
		HiveStructure.flatten(level, centre, PLAZA, 16, true); // the disc only
		BaseGround.fillUnder(level, centre, PLAZA); // owner 17.10: land under the plaza, and the land round it sloping to it
		BaseGround.blend(level, centre, PLAZA + 1, PLAZA + 20);
		BlockState pave = terran ? Blocks.GRAY_CONCRETE.defaultBlockState() : Blocks.SMOOTH_SANDSTONE.defaultBlockState();
		BlockState line = terran ? Blocks.YELLOW_CONCRETE.defaultBlockState() : Blocks.YELLOW_TERRACOTTA.defaultBlockState();
		BlockState inlay = terran ? Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState() : Blocks.BLUE_TERRACOTTA.defaultBlockState();
		for (int dx = -PLAZA; dx <= PLAZA; dx++)
			for (int dz = -PLAZA; dz <= PLAZA; dz++) {
				double r = Math.sqrt(dx * dx + dz * dz);
				if (r > PLAZA + 0.5) continue;
				BlockState state = Math.abs(r - 5.5) < 0.5 || Math.abs(r - CORE) < 0.5 ? line : terran ? ((dx + 100) / 4 + (dz + 100) / 4) % 2 == 0 ? pave : inlay
					: Math.abs(r - PLAZA + 1) < 0.6 ? inlay : pave;
				painter.set(centre.offset(dx, -1, dz), state);
			}
		// owner 19.10: every building on a pedestal a block high with stairs round it
		BlockState top = terran ? Blocks.POLISHED_ANDESITE.defaultBlockState() : Blocks.CHISELED_SANDSTONE.defaultBlockState();
		BlockState stairs = terran ? Blocks.POLISHED_ANDESITE_STAIRS.defaultBlockState() : Blocks.SMOOTH_SANDSTONE_STAIRS.defaultBlockState();
		List<double[]> taken = new ArrayList<>(); // x, z, half width: the buildings' places, kept clear of lamps
		java.util.function.BiConsumer<UnitKind, BlockPos> structure = (kind, at) -> {
			// on the plaza's level (the ground there may be a neighbour's pedestal by now)
			spawns.add(Spawn.member(kind, BaseGround.pedestal(level, kind, new BlockPos(at.getX(), centre.getY(), at.getZ()), top, stairs)));
			taken.add(new double[] {at.getX() + 0.5, at.getZ() + 0.5, kind.width / 2 + 3});
		};
		structure.accept(main, centre);

		// buildings round the main one (east left free for the way down); protoss pylons between them
		UnitKind[] ring = terran ? TERRAN_RING : PROTOSS_RING;
		double step = Math.PI * 2 / (ring.length + 1), radius = main.width / 2 + 7.5;
		for (int i = 0; i < ring.length; i++) {
			double angle = (i + 1) * step;
			structure.accept(ring[i], groundAt(level, centre, angle, radius + ring[i].width / 2));
		}
		// three pylons power the whole ring (owner 16.10: four and more were too many)
		if (!terran) for (int i = 0; i < 3; i++)
			structure.accept(UnitKind.PYLON, groundAt(level, centre, Math.PI / 3 + i * Math.PI * 2 / 3, radius - 1.5));
		// owner 19.10: the base's edge always guarded — every 30 degrees (the way down in the east left free): terran missile
		// turrets and manned bunkers, protoss photon cannons with a pylon between each two
		for (int i = 0; i < 12; i++) {
			double angle = Math.toRadians(15 + i * 30);
			UnitKind gun = terran ? (i % 2 == 0 ? UnitKind.BUNKER : UnitKind.MISSILE_TURRET) : UnitKind.PHOTON_CANNON;
			structure.accept(gun, groundAt(level, centre, angle, EDGE));
			if (!terran && i % 2 == 0) structure.accept(UnitKind.PYLON, groundAt(level, centre, angle + Math.toRadians(15), EDGE));
		}
		lights(painter, centre, terran, taken);
		// owner 18.10: the base's resources west of the core (the east is the way down): 5 crystals, 2 geysers with its gas buildings
		// (the overworld's: a nether or End base's great hall lies where a geyser's vein would climb through it)
		int depositX = centre.getX() - (CORE + 8), depositZ = centre.getZ();
		if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD)
			for (BlockPos gas : dev.starmine.mineral.Deposits.build(level, random, depositX, depositZ, centre.getX(), centre.getZ()))
				spawns.add(Spawn.member(race.rules().gasBuilding(), dev.starmine.vespene.Vespene.collapseUnder(level, gas))); // its mound taken away
		for (UnitKind kind : terran ? TERRAN_ARMY : PROTOSS_ARMY) {
			double angle = random.nextDouble() * Math.PI * 2;
			BlockPos at = groundAt(level, centre, angle, main.width / 2 + kind.width / 2 + 3 + random.nextDouble() * 2.5); // out of the main building
			spawns.add(Spawn.member(kind, kind.flying ? at.above(kind.hoverHeight() > 0 ? (int) kind.hoverHeight() : 4) : at));
		}

		// under the base and over it
		int depth = 26;
		int under = Math.max(level.getMinBuildHeight() + 8, centre.getY() - depth);
		// owner 17.10: the ship hangs in the stratosphere over the base (Stratosphere)
		ServerLevel skyLevel = skyLevel(level);
		int sky = skyLevel == level ? skyHeight(level, centre.getY()) : Stratosphere.SHIP_Y;
		Painter above = Painter.deferred(skyLevel, random);
		SkyShips.Ship ship;
		if (terran) {
			int span = Dungeons.MAZE_CELLS * Dungeons.PITCH;
			spawns.addAll(Dungeons.techLabyrinth(painter, new BlockPos(centre.getX() + PLAZA + depth + 4, under, centre.getZ() - span / 2)));
			ship = null; // owner 17.10: the Hyperion comes when Swann is freed (shipAfterGuardian)
		} else {
			spawns.addAll(Dungeons.ancientTemples(painter, new BlockPos(centre.getX(), under, centre.getZ() + PLAZA + depth + 4 + 34 + 8)));
			ship = null; // owner 17.10: the mothership comes when Karax is freed
		}
		// owner 19.10: levels of tunnels under the base, its army's way down from the plaza, and the guardian in the hall at the bottom
		Undercity.Plan below = Undercity.plan(level, race, centre, random);
		if (withShip) { // owner 17.10: a main base's guardian hero (Swann, Karax)
			if (below != null) below.spawns().add(Spawn.guard(UnitKind.guardianOf(race), below.hall(), below.hall()));
			else spawns.add(Spawn.guard(UnitKind.guardianOf(race), groundAt(level, centre, Math.PI, main.width / 2 + 5), centre));
			data.structuresDone.add("GUARD:" + race.name());
		}
		if (below != null) {
			UUID id = base.id;
			below.painter().then(() -> { // its dwellers once its blocks are in
				for (Spawn spawn : below.spawns()) wait(data, id, spawn, level);
				data.setDirty();
			});
			below.painter().start();
		}
		if (ship == null) { // the base and its dungeon; its ship later
			for (Spawn spawn : spawns) wait(data, base.id, spawn, level); // in the base's own dimension (a nether or End base's)
			data.setDirty();
			return new Report(base.id, painter.placed(), spawns.size());
		}
		spawns.addAll(portalHall(level, painter, race, centre, Direction.EAST, ship, above, base.id)); // under the main building, the stair up in the ring's gap
		for (Spawn spawn : spawns) wait(data, base.id, spawn);
		finish(skyLevel, data, base.id, ship, above);
		data.setDirty();
		return new Report(base.id, painter.placed() + above.placed(), spawns.size() + ship.spawns().size());
	}

	/** Where the races' ships hang: the stratosphere (the overworld itself if it is missing). */
	public static ServerLevel skyLevel(ServerLevel level) {
		ServerLevel sky = Stratosphere.level(level.getServer());
		return sky != null ? sky : level;
	}

	/**
	 * Lights over a base's plaza (owner 16.10: no monsters spawn there): lamps on posts round its edge and
	 * lights set in the paving — terran lamps, protoss sea lanterns.
	 */
	private static void lights(Painter painter, BlockPos centre, boolean terran, List<double[]> taken) {
		BlockState floor = terran ? Blocks.OCHRE_FROGLIGHT.defaultBlockState() : Blocks.SEA_LANTERN.defaultBlockState();
		for (int dx = -PLAZA + 4; dx <= PLAZA - 4; dx += 8)
			for (int dz = -PLAZA + 4; dz <= PLAZA - 4; dz += 8)
				if (dx * dx + dz * dz <= (PLAZA - 2) * (PLAZA - 2)) painter.set(centre.offset(dx, -1, dz), floor);
		BlockState post = terran ? Blocks.POLISHED_ANDESITE.defaultBlockState() : Blocks.CHISELED_SANDSTONE.defaultBlockState();
		BlockState lamp = terran ? Blocks.LANTERN.defaultBlockState() : Blocks.SEA_LANTERN.defaultBlockState();
		for (int a = 10; a < 360; a += 10) { // owner 19.10: none where a gun or a building stands, none before the way down (east)
			if (a < 15 || a > 345) continue;
			double rad = Math.toRadians(a);
			BlockPos foot = centre.offset((int) Math.round(Math.cos(rad) * (PLAZA - 1)), 0, (int) Math.round(Math.sin(rad) * (PLAZA - 1)));
			if (taken.stream().anyMatch(t -> Math.hypot(t[0] - foot.getX() - 0.5, t[1] - foot.getZ() - 0.5) < t[2])) continue;
			for (int up = 0; up < 3; up++) painter.set(foot.above(up), post);
			painter.set(foot.above(3), lamp);
		}
	}

	/**
	 * The portal hall under a base (owner 16.10: a dungeon with a portal like the End's, off while its guards
	 * live; {@link PortalHall}); it leads to the ship once the ship is built ({@link #finish}).
	 */
	private static List<Spawn> portalHall(ServerLevel level, Painter painter, UnitKind.Race race, BlockPos surface, Direction out,
			SkyShips.Ship ship, Painter above, @Nullable UUID base) {
		BlockPos centre = new BlockPos(surface.getX(), Math.max(level.getMinBuildHeight() + 8, surface.getY() - PortalHall.DEPTH), surface.getZ());
		PortalHall.Hall hall = PortalHall.build(painter, race, centre, out);
		above.then(() -> {
			Teleporter.linkPortal(level, hall.portal(), hall.centre(), above.level(), ship.pad());
			// owner 17.10: the way to the ship opens when the base's main building falls — this hall's portal wakes then,
			// and a portal opens where the building stood (RacePortal)
			if (base != null) {
				Teleporter.lock(level, hall.portal(), base);
				Hive owner = HiveSavedData.get(level.getServer()).get(base);
				if (owner != null) {
					owner.shipPad = ship.pad().immutable();
					owner.shipDim = above.level() == level ? null : above.level().dimension().location().toString();
				}
			}
		});
		return hall.guards();
	}

	/** Once a ship is built, its guards wait for their chunks to tick (they would have fallen through it before). */
	private static void finish(ServerLevel level, HiveSavedData data, UUID base, SkyShips.Ship ship, Painter above) {
		above.then(() -> {
			for (Spawn spawn : ship.spawns()) // owner 17.10: the hero's arena and its key part beside it, the hangar under the ship
				if (spawn.kind().boss()) {
					HeroLiberation.prepare(level, ship.bossRoom(), spawn.kind().race);
					HeroicTransport.hangar(level, ship, spawn.kind().race);
				}
			for (Spawn spawn : ship.spawns()) wait(data, base, spawn, level);
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
		ServerLevel skyLevel = skyLevel(level);
		BlockPos middle = new BlockPos(lair.getX(), skyLevel == level ? skyHeight(level, ground) : Stratosphere.SHIP_Y, lair.getZ());
		Hive hive = data.all().stream().filter(h -> h.owner == null && h.race == UnitKind.Race.ZERG && h.hatchery.distSqr(lair) < 32 * 32)
			.findFirst().orElse(null);
		UUID id = hive != null ? hive.id : UUID.randomUUID();
		Painter above = Painter.deferred(skyLevel, random);
		SkyShips.Ship ship = SkyShips.leviathan(above, middle, random.nextInt(4));
		List<Spawn> spawns = portalHall(level, painter, UnitKind.Race.ZERG, groundAt(level, lair, Math.PI, 30), Direction.WEST, ship, above, hive == null ? null : hive.id);
		for (Spawn spawn : spawns) wait(data, id, spawn);
		finish(skyLevel, data, id, ship, above);
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

	/** A spawn waiting in a given level (a ship's guard in the stratosphere). */
	private static void wait(HiveSavedData data, UUID base, Spawn spawn, ServerLevel level) {
		data.pending.add(new HiveStructure.Pending(base, spawn.kind(), spawn.pos(), spawn.hall(), spawn.brood(), spawn.broodCount(),
			level.dimension() == net.minecraft.world.level.Level.OVERWORLD ? null : level.dimension().location().toString()));
	}
}
