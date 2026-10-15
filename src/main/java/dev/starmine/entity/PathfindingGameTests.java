package dev.starmine.entity;

import dev.starmine.ability.Abilities;
import dev.starmine.ability.Ability;
import dev.starmine.ability.TestArena;
import dev.starmine.colony.Engineering;
import dev.starmine.hive.Hive;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.clear;
import static dev.starmine.ability.TestArena.point;
import static dev.starmine.ability.TestArena.unit;

/**
 * Pathfinding (docs/CLOUD_TASK.md, stage D): units of every size and kind sent across terrain built in
 * the arena — open ground, walls with gaps, cliffs, rivers, a mountain, tunnels, a forest, a building in
 * the way, a crowd in a narrow pass — flyers over a mountain, under a ceiling and over water, transports
 * over a chasm, a wild base's wave on a player's base. Each test logs, per unit, the ticks it took
 * (`[pathfinding]` lines in the server log), who got stuck, and what the workers' planner
 * ({@link DronePlanner#STATS}) spent; it fails when a unit that should arrive does not.
 */
public class PathfindingGameTests implements FabricGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("StarMine/pathfinding");

	/** Fills a box of the arena (relative, inclusive) with a block. */
	private static void fill(GameTestHelper helper, int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
		for (BlockPos rel : BlockPos.betweenClosed(x0, y0, z0, x1, y1, z1)) helper.getLevel().setBlock(helper.absolutePos(rel), state, 2);
	}

	/**
	 * Sends each unit to its goal (a move order) and succeeds when every one is within `radius` of it
	 * (horizontally, within 2 blocks up or down); logs every arrival's tick and, when `limit` ticks are
	 * over, who is stuck where.
	 */
	private static void race(GameTestHelper helper, String scenario, Map<StarUnit, Vec3> goals, double radius, int limit) {
		Map<StarUnit, Long> arrived = new LinkedHashMap<>();
		long[] start = new long[] {-1};
		boolean[] over = new boolean[1];
		DronePlanner.STATS.reset();
		helper.onEachTick(() -> {
			if (over[0]) return;
			long now = helper.getTick();
			if (start[0] < 0) {
				start[0] = now;
				goals.forEach((unit, goal) -> unit.command(StarUnit.Order.MOVE, goal));
				return;
			}
			goals.forEach((unit, goal) -> {
				if (!arrived.containsKey(unit) && unit.isAlive() && Abilities.horizontal(unit.position(), goal) <= radius + unit.getBbWidth() / 2
					&& Math.abs(unit.getY() - goal.y) < 2 + (unit.flying() ? unit.kind.hoverHeight() + 4 : 0)) arrived.put(unit, now - start[0]);
			});
			if (arrived.size() == goals.size()) {
				over[0] = true;
				LOG.info("[pathfinding] {}: all {} arrived; ticks {}; planner {}", scenario, goals.size(), times(arrived), DronePlanner.STATS);
				helper.succeed();
			} else if (now - start[0] >= limit) {
				over[0] = true;
				List<String> stuck = new ArrayList<>();
				goals.forEach((unit, goal) -> {
					if (!arrived.containsKey(unit)) stuck.add(unit.kind.id + " at " + TestArena.where(helper, unit) + " order " + unit.order()
						+ (unit.isAlive() ? "" : " (dead)")); // NONE: it gave the order up as stuck (UnitGoals.Orders) and wandered off
				});
				LOG.info("[pathfinding] {}: {} of {} arrived in {} ticks; ticks {}; stuck {}; planner {}", scenario, arrived.size(), goals.size(), limit,
					times(arrived), stuck, DronePlanner.STATS);
				helper.fail(scenario + ": stuck " + stuck);
			}
		});
	}

	/**
	 * The middle of the `i`th unit's lane (arena z): lanes side by side by the units' widths, 1.5 blocks
	 * apart (flyers twice the model size are up to 8.6 wide: lanes 7.5 apart had an overlord and a
	 * mutalisk pushing each other off their ways).
	 */
	private static double lane(UnitKind[] kinds, int i) {
		double edge = 1;
		for (int k = 0; k < i; k++) edge += kinds[k].width + 1.5;
		return edge + kinds[i].width / 2 - 0.5;
	}

	private static String times(Map<StarUnit, Long> arrived) {
		StringBuilder line = new StringBuilder();
		arrived.forEach((unit, ticks) -> line.append(unit.kind.id).append('=').append(ticks).append(' '));
		return line.toString().trim();
	}

	@GameTest(template = ARENA, batch = "pathFlat", timeoutTicks = 800)
	public void everySizeCrossesOpenGround(GameTestHelper helper) {
		clear(helper);
		UUID player = UUID.randomUUID();
		Map<StarUnit, Vec3> goals = new LinkedHashMap<>();
		UnitKind[] kinds = {UnitKind.ZERGLING, UnitKind.MARINE, UnitKind.ROACH, UnitKind.STALKER, UnitKind.ULTRALISK, UnitKind.COLOSSUS, UnitKind.THOR};
		double edge = 0.5; // side by side by their widths (a thor is 10 wide), a block apart
		for (UnitKind kind : kinds) {
			double z = edge + kind.width / 2;
			edge += kind.width + 1;
			goals.put(unit(helper, kind, 6, z - 0.5, player), point(helper, 38, z - 0.5));
		}
		race(helper, "open ground, 32 blocks", goals, 1.5, 700);
	}

	@GameTest(template = ARENA, batch = "pathGaps", timeoutTicks = 800)
	public void unitsFindGapsTheirSize(GameTestHelper helper) {
		clear(helper);
		// a wall 3 high across the arena with gaps 2, 4 and 7 wide: a zergling and a marine (1.3 and 1.6 wide)
		// fit the first, a roach (2.6) the second, an ultralisk (5.6) the third; the game's pathfinder wants
		// a body's width plus one free (`DronePlanner.Body`)
		fill(helper, 24, 2, 0, 24, 4, 47, Blocks.STONE.defaultBlockState());
		fill(helper, 24, 2, 7, 24, 4, 8, Blocks.AIR.defaultBlockState());
		fill(helper, 24, 2, 19, 24, 4, 22, Blocks.AIR.defaultBlockState());
		fill(helper, 24, 2, 33, 24, 4, 39, Blocks.AIR.defaultBlockState());
		UUID player = UUID.randomUUID();
		Map<StarUnit, Vec3> goals = new LinkedHashMap<>();
		goals.put(unit(helper, UnitKind.ZERGLING, 12, 4, player), point(helper, 36, 4));
		goals.put(unit(helper, UnitKind.MARINE, 12, 14, player), point(helper, 36, 14));
		goals.put(unit(helper, UnitKind.ROACH, 12, 24, player), point(helper, 36, 24));
		goals.put(unit(helper, UnitKind.ULTRALISK, 12, 40, player), point(helper, 36, 40));
		race(helper, "wall with gaps 2/4/7", goals, 1.5, 700);
	}

	@GameTest(template = ARENA, batch = "pathCliff", timeoutTicks = 900)
	public void cliffClimbersAndTheRest(GameTestHelper helper) {
		clear(helper);
		// a plateau 4 high from x 24 on, with a ramp of steps at its far end (z 42-46)
		fill(helper, 24, 2, 0, 47, 5, 47, Blocks.STONE.defaultBlockState());
		for (int step = 0; step < 4; step++) fill(helper, 20 + step, 2, 42, 20 + step, 2 + step, 46, Blocks.STONE.defaultBlockState());
		UUID player = UUID.randomUUID();
		Map<StarUnit, Vec3> goals = new LinkedHashMap<>();
		Vec3 top = point(helper, 34, 6).add(0, 4, 0);
		goals.put(unit(helper, UnitKind.REAPER, 12, 6, player), top);
		goals.put(unit(helper, UnitKind.COLOSSUS, 12, 14, player), top.add(0, 0, 8));
		goals.put(unit(helper, UnitKind.MARINE, 12, 22, player), top.add(0, 0, 16));
		goals.put(unit(helper, UnitKind.ZERGLING, 12, 30, player), top.add(0, 0, 24));
		race(helper, "cliff 4 up (ramp at the far end)", goals, 2, 800);
	}

	@GameTest(template = ARENA, batch = "pathCliffDown", timeoutTicks = 700)
	public void unitsComeDownACliff(GameTestHelper helper) {
		clear(helper);
		fill(helper, 0, 2, 0, 23, 5, 47, Blocks.STONE.defaultBlockState());
		for (int step = 0; step < 4; step++) fill(helper, 24 + step, 2, 42, 24 + step, 5 - step, 46, Blocks.STONE.defaultBlockState());
		UUID player = UUID.randomUUID();
		Map<StarUnit, Vec3> goals = new LinkedHashMap<>();
		for (UnitKind kind : new UnitKind[] {UnitKind.MARINE, UnitKind.ROACH, UnitKind.STALKER}) {
			double z = 10 + goals.size() * 10;
			StarUnit unit = unit(helper, kind, 10, z, player);
			unit.moveTo(unit.getX(), unit.getY() + 4, unit.getZ()); // on the plateau
			goals.put(unit, point(helper, 36, z));
		}
		race(helper, "cliff 4 down", goals, 2, 600);
	}

	@GameTest(template = ARENA, batch = "pathRiver5", timeoutTicks = 1200)
	public void armyCrossesANarrowRiver(GameTestHelper helper) {
		river(helper, 5);
	}

	@GameTest(template = ARENA, batch = "pathRiver12", timeoutTicks = 2400)
	public void armyCrossesAWideRiver(GameTestHelper helper) {
		river(helper, 12);
	}

	/** A river across the arena (2 deep) between the army and its goal; the army's base has SCVs to bridge it. */
	private static void river(GameTestHelper helper, int width) {
		clear(helper);
		int x0 = 24 - width / 2;
		fill(helper, x0, 0, 0, x0 + width - 1, 1, 47, Blocks.WATER.defaultBlockState());
		StarUnit cc = unit(helper, UnitKind.COMMAND_CENTER, 8, 40, UUID.randomUUID());
		TestArena.Timeline timeline = TestArena.Timeline.of(helper);
		timeline.at(5, () -> {
			Hive base = TestArena.base(helper, cc);
			helper.assertTrue(base != null, "no base");
			base.minerals = 2000;
			Map<StarUnit, Vec3> goals = new LinkedHashMap<>();
			goals.put(TestArena.member(helper, base, UnitKind.MARINE, 8, 10), point(helper, 40, 10));
			goals.put(TestArena.member(helper, base, UnitKind.MARAUDER, 8, 14), point(helper, 40, 14));
			goals.put(TestArena.member(helper, base, UnitKind.SIEGE_TANK, 8, 18), point(helper, 40, 18));
			goals.put(TestArena.member(helper, base, UnitKind.THOR, 8, 24), point(helper, 40, 24));
			armyRace(helper, timeline, base, "river " + width + " wide", goals, width == 5 ? 1100 : 2300);
		});
	}

	/** An army order through the base's commands (workers cut or bridge the way when it cannot walk there), then a race to the goals. */
	private static void armyRace(GameTestHelper helper, TestArena.Timeline timeline, Hive base, String scenario, Map<StarUnit, Vec3> goals, int limit) {
		ServerLevel level = helper.getLevel();
		List<StarUnit> army = new ArrayList<>(goals.keySet());
		Vec3 centre = goals.values().stream().reduce(Vec3.ZERO, Vec3::add).scale(1.0 / goals.size());
		DronePlanner.STATS.reset();
		java.util.Set<UUID> waiting = Engineering.order(level, base, army, StarUnit.Order.MOVE, centre);
		LOG.info("[pathfinding] {}: {} of {} units wait for workers to cut a way", scenario, waiting.size(), army.size());
		Map<StarUnit, Long> arrived = new LinkedHashMap<>();
		long start = timeline.now();
		boolean[] over = new boolean[1];
		for (StarUnit unit : army) if (!waiting.contains(unit.getUUID())) unit.command(StarUnit.Order.MOVE, goals.get(unit));
		timeline.every(() -> {
			if (over[0]) return;
			long now = timeline.now();
			if ((now - start) % 1000 == 0 && !waiting.isEmpty()) // where the led army is and what it does
				for (StarUnit unit : army) {
					net.minecraft.world.level.pathfinder.Path way = unit.getNavigation().getPath();
					LOG.info("[pathfinding] {} at {} ticks: {} at {} order {} job {} following {}", scenario, now - start, unit.kind.id,
						TestArena.where(helper, unit), unit.order(), Engineering.describe(unit.getUUID()),
						way == null ? "nothing" : way.canReach() + " " + way.getNextNodeIndex() + "/" + way.getNodeCount());
				}
			// a unit the workers led goes with its army to the army's goal (the middle of theirs): there it has arrived
			goals.forEach((unit, goal) -> {
				if (!arrived.containsKey(unit) && unit.isAlive() && (Abilities.horizontal(unit.position(), goal) <= 4 + unit.getBbWidth() / 2
					|| waiting.contains(unit.getUUID()) && Abilities.horizontal(unit.position(), centre) <= 6 + unit.getBbWidth() / 2))
					arrived.put(unit, now - start);
			});
			if (arrived.size() == goals.size()) {
				over[0] = true;
				LOG.info("[pathfinding] {}: all {} arrived; ticks {}; planner {}", scenario, goals.size(), times(arrived), DronePlanner.STATS);
				helper.succeed();
			} else if (now - start >= limit) {
				over[0] = true;
				List<String> stuck = new ArrayList<>();
				for (StarUnit unit : army)
					if (!arrived.containsKey(unit)) {
						net.minecraft.world.level.pathfinder.Path way = unit.getNavigation().createPath(net.minecraft.core.BlockPos.containing(goals.get(unit)), 1);
						stuck.add(unit.kind.id + " at " + TestArena.where(helper, unit) + " job " + Engineering.describe(unit.getUUID()) + " order " + unit.order()
							+ " way now " + (way == null ? "none" : way.canReach() + " in " + way.getNodeCount() + " nodes to " + way.getTarget()));
					}
				LOG.info("[pathfinding] {}: {} of {} arrived in {} ticks; ticks {}; stuck {}; planner {}", scenario, arrived.size(), goals.size(), limit,
					times(arrived), stuck, DronePlanner.STATS);
				helper.fail(scenario + ": stuck " + stuck);
			}
		});
	}

	@GameTest(template = ARENA, batch = "pathMountain", timeoutTicks = 9600)
	public void workersTunnelThroughAMountain(GameTestHelper helper) {
		clear(helper);
		fill(helper, 20, 2, 0, 27, 11, 47, Blocks.STONE.defaultBlockState()); // a ridge up to the arena's top
		fill(helper, 0, 2, 0, 47, 11, 0, Blocks.STONE.defaultBlockState()); // walls along the sides: no way round it outside the arena
		fill(helper, 0, 2, 47, 47, 11, 47, Blocks.STONE.defaultBlockState());
		StarUnit cc = unit(helper, UnitKind.COMMAND_CENTER, 8, 40, UUID.randomUUID());
		TestArena.Timeline timeline = TestArena.Timeline.of(helper);
		timeline.at(5, () -> {
			Hive base = TestArena.base(helper, cc);
			helper.assertTrue(base != null, "no base");
			base.minerals = 2000;
			// a thor's way (11x12 blocks) takes eight workers (Engineering.workers); the base was founded with four
			for (int i = 0; i < 4; i++) TestArena.member(helper, base, UnitKind.SCV, 4 + 2 * i, 44);
			Map<StarUnit, Vec3> goals = new LinkedHashMap<>();
			goals.put(TestArena.member(helper, base, UnitKind.MARINE, 8, 14), point(helper, 38, 20));
			goals.put(TestArena.member(helper, base, UnitKind.SIEGE_TANK, 8, 20), point(helper, 38, 24));
			goals.put(TestArena.member(helper, base, UnitKind.THOR, 8, 27), point(helper, 38, 28));
			armyRace(helper, timeline, base, "mountain, a tunnel for a thor", goals, 9500);
		});
	}

	@GameTest(template = ARENA, batch = "pathTunnels", timeoutTicks = 900)
	public void zergFollowWindingTunnels(GameTestHelper helper) {
		clear(helper);
		// solid rock with a winding 3x3 tunnel (a roach is 2.6 wide, a drone 1.9): east, north, east, south, east
		fill(helper, 0, 2, 0, 47, 6, 47, Blocks.STONE.defaultBlockState());
		int[][] legs = {{2, 22, 14, 22}, {14, 22, 14, 10}, {14, 10, 30, 10}, {30, 10, 30, 30}, {30, 30, 45, 30}};
		for (int[] leg : legs)
			fill(helper, Math.min(leg[0], leg[2]), 2, Math.min(leg[1], leg[3]), Math.max(leg[0], leg[2]) + 2, 4, Math.max(leg[1], leg[3]) + 2,
				Blocks.AIR.defaultBlockState());
		UUID player = UUID.randomUUID();
		Map<StarUnit, Vec3> goals = new LinkedHashMap<>();
		Vec3 end = point(helper, 44, 31);
		goals.put(unit(helper, UnitKind.ZERGLING, 3, 23, player), end);
		goals.put(unit(helper, UnitKind.ROACH, 5, 23, player), end);
		goals.put(unit(helper, UnitKind.DRONE, 8, 23, player), end);
		race(helper, "winding 3x3 tunnel", goals, 2.5, 800);
	}

	@GameTest(template = ARENA, batch = "pathForest", timeoutTicks = 900)
	public void unitsCrossAForest(GameTestHelper helper) {
		clear(helper);
		// tall trees every 9 blocks, staggered: a trunk 9 high and a crown of leaves from 8 up (an ultralisk, 7.4
		// tall, walks under it; a tank, 5.8 wide, between the trunks)
		for (int x = 14; x <= 34; x += 9)
			for (int z = 2; z <= 46; z += 9) {
				int ox = x + (z / 9 % 2) * 4;
				fill(helper, ox - 1, 10, z - 1, ox + 1, 11, z + 1, Blocks.OAK_LEAVES.defaultBlockState()
					.setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
				fill(helper, ox, 2, z, ox, 10, z, Blocks.OAK_LOG.defaultBlockState());
			}
		UUID player = UUID.randomUUID();
		Map<StarUnit, Vec3> goals = new LinkedHashMap<>();
		UnitKind[] kinds = {UnitKind.ZERGLING, UnitKind.MARINE, UnitKind.HYDRALISK, UnitKind.STALKER, UnitKind.SIEGE_TANK, UnitKind.ULTRALISK};
		for (int i = 0; i < kinds.length; i++) {
			double z = lane(kinds, i);
			goals.put(unit(helper, kinds[i], 6, z, player), point(helper, 42, z));
		}
		race(helper, "forest", goals, 2, 800);
	}

	@GameTest(template = ARENA, batch = "pathFlyMountain", timeoutTicks = 1400)
	public void flyersCrossAMountain(GameTestHelper helper) {
		clear(helper);
		fill(helper, 20, 2, 0, 27, 10, 47, Blocks.STONE.defaultBlockState());
		UUID player = UUID.randomUUID();
		Map<StarUnit, Vec3> goals = new LinkedHashMap<>();
		UnitKind[] kinds = {UnitKind.MUTALISK, UnitKind.OVERLORD, UnitKind.VIKING, UnitKind.MEDIVAC, UnitKind.PHOENIX, UnitKind.BATTLECRUISER};
		for (int i = 0; i < kinds.length; i++) {
			double z = lane(kinds, i);
			goals.put(unit(helper, kinds[i], 6, z, player), point(helper, 40, z));
		}
		race(helper, "flyers over a mountain", goals, 2.5, 1300);
	}

	@GameTest(template = ARENA, batch = "pathFlyCeiling", timeoutTicks = 800)
	public void flyersUnderACeiling(GameTestHelper helper) {
		clear(helper);
		fill(helper, 0, 12, 0, 47, 12, 47, Blocks.STONE.defaultBlockState()); // a cave roof 10 blocks over the floor (an overseer is 7.6 tall)
		UUID player = UUID.randomUUID();
		Map<StarUnit, Vec3> goals = new LinkedHashMap<>();
		UnitKind[] kinds = {UnitKind.MUTALISK, UnitKind.VIKING, UnitKind.MEDIVAC, UnitKind.PHOENIX, UnitKind.OVERSEER};
		List<StarUnit> flyers = new ArrayList<>();
		for (int i = 0; i < kinds.length; i++) {
			double z = lane(kinds, i);
			StarUnit flyer = unit(helper, kinds[i], 6, z, player);
			Vec3 low = point(helper, 6, z).add(0, 0.5, 0); // under the roof from the start (spawned flyers come 3 blocks up)
			flyer.moveTo(low.x, low.y, low.z, 0, 0);
			flyers.add(flyer);
			goals.put(flyer, point(helper, 40, z));
		}
		double roof = point(helper, 0, 0).y + 10;
		helper.onEachTick(() -> {
			for (StarUnit flyer : flyers)
				if (flyer.isAlive() && flyer.getY() + flyer.getBbHeight() > roof + 0.01) helper.fail(flyer.kind.id + " is in the roof");
		});
		race(helper, "flyers under a ceiling", goals, 2.5, 750);
	}

	@GameTest(template = ARENA, batch = "pathFlyWater", timeoutTicks = 800)
	public void flyersCrossWater(GameTestHelper helper) {
		clear(helper);
		fill(helper, 10, 1, 0, 36, 1, 47, Blocks.WATER.defaultBlockState());
		UUID player = UUID.randomUUID();
		Map<StarUnit, Vec3> goals = new LinkedHashMap<>();
		UnitKind[] kinds = {UnitKind.MUTALISK, UnitKind.BROOD_LORD, UnitKind.BANSHEE, UnitKind.VOID_RAY, UnitKind.CARRIER};
		for (int i = 0; i < kinds.length; i++) {
			double z = lane(kinds, i);
			goals.put(unit(helper, kinds[i], 4, z, player), point(helper, 42, z));
		}
		race(helper, "flyers over water", goals, 2.5, 750);
	}

	@GameTest(template = ARENA, batch = "pathTransport", timeoutTicks = 900)
	public void transportsCrossAChasm(GameTestHelper helper) {
		clear(helper);
		fill(helper, 18, 0, 0, 29, 1, 47, Blocks.AIR.defaultBlockState()); // a chasm 12 wide down to the void floor of the arena
		UUID player = UUID.randomUUID();
		ServerLevel level = helper.getLevel();
		StarUnit medivac = unit(helper, UnitKind.MEDIVAC, 8, 8, player), prism = unit(helper, UnitKind.WARP_PRISM, 8, 24, player);
		StarUnit overlord = unit(helper, UnitKind.OVERLORD, 8, 40, player);
		StarUnit marine = unit(helper, UnitKind.MARINE, 9, 9, player), zealot = unit(helper, UnitKind.ZEALOT, 9, 25, player);
		StarUnit zergling = unit(helper, UnitKind.ZERGLING, 9, 41, player);
		Map<StarUnit, Vec3> drops = Map.of(medivac, point(helper, 38, 8), prism, point(helper, 38, 24), overlord, point(helper, 38, 40));
		helper.runAfterDelay(3, () -> drops.forEach((transport, drop) -> {
			helper.assertTrue(Abilities.cast(transport, level, Ability.LOAD, null, null), transport.kind.id + " loads nothing");
			helper.assertTrue(Abilities.cast(transport, level, Ability.UNLOAD, null, drop), transport.kind.id + " does not unload");
		}));
		long[] start = {-1};
		helper.onEachTick(() -> {
			if (start[0] < 0) start[0] = helper.getTick();
		});
		helper.succeedWhen(() -> {
			for (StarUnit cargo : List.of(marine, zealot, zergling)) {
				helper.assertTrue(cargo.getVehicle() == null && TestArena.rel(helper, cargo).x > 30, cargo.kind.id + " is not across, at " + TestArena.where(helper, cargo));
				helper.assertTrue(cargo.onGround() && cargo.isAlive(), cargo.kind.id + " is not standing on the far side");
			}
			LOG.info("[pathfinding] transports over a chasm: all three across in {} ticks", helper.getTick() - start[0]);
		});
	}

	@GameTest(template = ARENA, batch = "pathCrowd", timeoutTicks = 1400)
	public void aCrowdSqueezesThroughAPass(GameTestHelper helper) {
		clear(helper);
		fill(helper, 24, 2, 0, 25, 5, 47, Blocks.STONE.defaultBlockState());
		fill(helper, 24, 2, 22, 25, 5, 24, Blocks.AIR.defaultBlockState()); // a pass 3 wide
		UUID player = UUID.randomUUID();
		Map<StarUnit, Vec3> goals = new LinkedHashMap<>();
		for (int i = 0; i < 24; i++) {
			UnitKind kind = i % 3 == 0 ? UnitKind.MARINE : i % 3 == 1 ? UnitKind.ZERGLING : UnitKind.ZEALOT;
			double x = 4 + (i % 6) * 2.2, z = 14 + (i / 6) * 4.5;
			goals.put(unit(helper, kind, x, z, player), point(helper, 34 + (i % 6) * 2, 14 + (i / 6) * 4.5));
		}
		race(helper, "crowd of 24 through a pass 3 wide", goals, 2.5, 1300);
	}

	@GameTest(template = ARENA, batch = "pathBuildings", timeoutTicks = 900)
	public void unitsWalkAroundBuildings(GameTestHelper helper) {
		clear(helper);
		UUID player = UUID.randomUUID(), other = UUID.randomUUID();
		List<StarUnit> buildings = List.of(unit(helper, UnitKind.BARRACKS, 24, 10, other), unit(helper, UnitKind.GATEWAY, 24, 24, other),
			unit(helper, UnitKind.EVOLUTION_CHAMBER, 24, 38, other));
		Map<StarUnit, Vec3> goals = new LinkedHashMap<>();
		goals.put(unit(helper, UnitKind.MARINE, 8, 10, player), point(helper, 40, 10));
		goals.put(unit(helper, UnitKind.STALKER, 8, 24, player), point(helper, 40, 24));
		goals.put(unit(helper, UnitKind.ROACH, 8, 38, player), point(helper, 40, 38));
		double floor = point(helper, 0, 0).y;
		helper.onEachTick(() -> {
			for (StarUnit unit : goals.keySet())
				for (StarUnit building : buildings)
					if (unit.getY() > floor + 1.2 && unit.getBoundingBox().intersects(building.getBoundingBox().inflate(0.5, 1, 0.5)))
						helper.fail(unit.kind.id + " climbs over a " + building.kind.id);
		});
		race(helper, "buildings in the way", goals, 2, 800);
	}

	@GameTest(template = ARENA, batch = "pathWave", timeoutTicks = 2400)
	public void aWildWaveReachesAPlayersBase(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		StarUnit wild = unit(helper, UnitKind.COMMAND_CENTER, 6, 6, null);
		StarUnit player = unit(helper, UnitKind.COMMAND_CENTER, 41, 41, UUID.randomUUID());
		helper.runAfterDelay(5, () -> {
			Hive base = TestArena.base(helper, wild), foe = TestArena.base(helper, player);
			helper.assertTrue(base != null && foe != null && base.owner == null && foe.owner != null, "the bases were not founded");
			for (int i = 0; i < base.waveSize; i++) TestArena.member(helper, base, i % 2 == 0 ? UnitKind.MARINE : UnitKind.MARAUDER, 12 + i % 3 * 2, 12 + i / 3 * 2);
			base.nextWave = 0;
		});
		helper.runAfterDelay(10, () -> {
			Hive base = TestArena.base(helper, wild);
			long start = helper.getTick();
			helper.assertTrue(dev.starmine.ai.AiCommander.wave(wild, level, base), "no wave");
			helper.succeedWhen(() -> {
				helper.assertTrue(player.getHealth() < player.getMaxHealth(), "the wave has not hit the player's base yet");
				LOG.info("[pathfinding] a wild wave over {} blocks hit the player's base after {} ticks", (int) wild.distanceTo(player), helper.getTick() - start);
			});
		});
	}
}
