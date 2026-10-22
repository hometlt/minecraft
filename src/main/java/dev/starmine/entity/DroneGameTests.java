package dev.starmine.entity;

import dev.starmine.StarMine;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * In-game tests of drone movement and work (run with `gradlew runGametest`): the planner digs
 * through a wall, cuts stairs up to a higher goal, bridges a gap with fill, never plans to break the
 * floor of a hive route; and a real drone of a real hive gets to an ore behind a wall and mines it.
 * The arena (`starmine:arena`, 48x12x48; GameTest walls it with barriers) is cleared and floored with stone at relative y = 1. Tests with
 * a hatchery run in batches of their own: a world holds at most 3 hives.
 */
public class DroneGameTests implements FabricGameTest {
	private static final String ARENA = "starmine:arena";

	/** Clears the arena and lays a stone floor at relative y = 1 (walking height 2). */
	private static void arena(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (BlockPos rel : BlockPos.betweenClosed(0, 0, 0, 47, 11, 47))
			level.setBlock(helper.absolutePos(rel), rel.getY() == 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
	}

	/** A hive test starts with no hives: the test world persists between runs (at most 3 per world). */
	private static void hiveArena(GameTestHelper helper) {
		arena(helper);
		HiveSavedData.get(helper.getLevel().getServer()).clear();
	}

	private static void fill(GameTestHelper helper, int x0, int y0, int z0, int x1, int y1, int z1, Block block) {
		for (BlockPos rel : BlockPos.betweenClosed(x0, y0, z0, x1, y1, z1)) helper.getLevel().setBlock(helper.absolutePos(rel), block.defaultBlockState(), 2);
	}

	/**
	 * A drone on the far side of its hatchery (12 blocks wide), 28 blocks from the minerals and 4 from
	 * the hatchery's edge, goes to mine them: ore is looked for around the base's main building too (only
	 * around itself, it stood idle all game — half a hive's drones did, and zerg mined half as much).
	 */
	@GameTest(template = ARENA, batch = "hiveFarDrone", timeoutTicks = 900)
	public void droneBeyondItsHatcheryFindsTheMinerals(GameTestHelper helper) {
		hiveArena(helper);
		fill(helper, 30, 2, 18, 35, 3, 29, Blocks.IRON_ORE); // 144 ores: enough for the hive's own 4 drones' claims too
		StarUnit hatchery = dev.starmine.ability.TestArena.unit(helper, UnitKind.HATCHERY, 14, 24, java.util.UUID.randomUUID());
		StarUnit drone = dev.starmine.ability.TestArena.unit(helper, UnitKind.DRONE, 4, 24, null);
		drone.setNoAi(true);
		helper.runAfterDelay(5, () -> {
			Hive hive = hatchery.hiveId() == null ? null : HiveSavedData.get(helper.getLevel().getServer()).get(hatchery.hiveId());
			helper.assertTrue(hive != null, "no hive");
			drone.joinHive(hive);
			drone.setNoAi(false);
			helper.succeedWhen(() -> helper.assertTrue(drone.goalsRunning().contains("MINE") || drone.cargoBlocks > 0,
				"the far drone does not go to the minerals: " + drone.goalsRunning() + " at " + dev.starmine.ability.TestArena.where(helper, drone)));
		});
	}

	/**
	 * A generated hive (far from the arena, in real terrain): hatchery and surface buildings wait to be
	 * put in the world, tunnels are dug with halls, sleeping eggs and creep tumors near the hatchery.
	 */
	@GameTest(template = ARENA, batch = "hiveStructure", timeoutTicks = 200)
	public void generatedHiveHasTunnelsAndHalls(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		HiveSavedData data = HiveSavedData.get(level.getServer());
		data.clear();
		data.pending.clear();
		// the test world is superflat: lay 34 blocks of stone to dig in
		BlockPos site = new BlockPos(20000, -26, 20000);
		var stone = Blocks.STONE.defaultBlockState();
		for (int x = -84; x <= 84; x++)
			for (int z = -84; z <= 84; z++) {
				if ((x & 15) == 0 && (z & 15) == 0) level.getChunk((site.getX() + x) >> 4, (site.getZ() + z) >> 4);
				for (int y = -60; y < -27; y++) level.setBlock(new BlockPos(site.getX() + x, y, site.getZ() + z), stone, 2);
				level.setBlock(new BlockPos(site.getX() + x, -27, site.getZ() + z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
			}
		dev.starmine.hive.HiveStructure.Report report = dev.starmine.hive.HiveStructure.build(level, site, net.minecraft.util.RandomSource.create(7));
		helper.assertTrue(data.all().size() == 1, "no hive was added");
		Hive hive = data.all().iterator().next();
		helper.assertTrue(report.halls() >= 4, "too few halls: " + report + " at " + site);
		helper.assertTrue(report.tunnelCells() > 1000, "too few blocks dug: " + report.tunnelCells());
		helper.assertTrue(report.eggs() >= 8, "too few eggs: " + report.eggs());
		helper.assertTrue(report.tumors() >= 3, "too few tumors: " + report.tumors());
		helper.assertTrue(data.pending.stream().anyMatch(p -> p.kind() == UnitKind.HATCHERY && p.hive().equals(hive.id)), "no hatchery waits");
		helper.assertTrue(data.pending.stream().anyMatch(p -> p.kind() == UnitKind.SPIRE && p.hall() == null), "no spire on the surface");
		helper.assertTrue(data.pending.stream().noneMatch(p -> p.kind() == UnitKind.SPIRE && p.pos().getY() < hive.hatchery.getY() - 5), "a spire underground");
		helper.assertTrue(data.pending.stream().anyMatch(p -> p.kind() == UnitKind.EGG && p.brood() != null), "no sleeping eggs");
		helper.assertTrue(data.pending.stream().anyMatch(p -> p.kind() == UnitKind.LARVA && p.hall() != null), "no larvae in halls");
		long deep = data.pending.stream().filter(p -> p.hall() != null && p.pos().getY() < hive.hatchery.getY() - 8).count();
		helper.assertTrue(deep > 5, "too few zerg underground: " + deep);
		data.clear();
		data.pending.clear();
		helper.succeed();
	}

	private static Vec3 centre(GameTestHelper helper, int x, int y, int z) {
		return Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(x, y, z)));
	}

	@GameTest(template = ARENA)
	public void plannerDigsThroughWall(GameTestHelper helper) {
		arena(helper);
		fill(helper, 12, 2, 0, 13, 5, 47, Blocks.STONE); // wall across the whole arena
		List<DronePlanner.Step> plan = DronePlanner.plan(helper.getLevel(), helper.absolutePos(new BlockPos(3, 2, 14)),
			centre(helper, 22, 2, 15), 2.0, Set.of(), true, 0, 4000);
		helper.assertTrue(plan != null && !plan.isEmpty(), "no plan through the wall");
		helper.assertTrue(plan.stream().anyMatch(step -> !step.dig().isEmpty()), "the plan does not dig the wall");
		helper.assertTrue(DronePlanner.centre(plan.get(plan.size() - 1).feet()).distanceTo(centre(helper, 22, 2, 15)) <= 2.0, "the plan stops short");
		helper.succeed();
	}

	@GameTest(template = ARENA)
	public void plannerCutsStairsUp(GameTestHelper helper) {
		arena(helper);
		fill(helper, 14, 2, 0, 31, 7, 31, Blocks.STONE); // a 6-block cliff
		Vec3 goal = centre(helper, 22, 8, 15);
		List<DronePlanner.Step> plan = DronePlanner.plan(helper.getLevel(), helper.absolutePos(new BlockPos(3, 2, 14)), goal, 2.0, Set.of(), true, 0, 6000);
		helper.assertTrue(plan != null && !plan.isEmpty(), "no plan up the cliff");
		int top = plan.stream().mapToInt(step -> step.feet().getY()).max().orElse(0);
		helper.assertTrue(top >= helper.absolutePos(new BlockPos(0, 8, 0)).getY(), "the plan does not climb to the top");
		helper.succeed();
	}

	/** Owner: from underground to the surface by stairs straight up, not by a long level tunnel. */
	@GameTest(template = ARENA)
	public void plannerDigsStairsToSurface(GameTestHelper helper) {
		arena(helper);
		fill(helper, 0, 2, 0, 31, 10, 31, Blocks.STONE); // 9 blocks of rock
		fill(helper, 14, 2, 14, 15, 3, 15, Blocks.AIR); // the drone's pocket at the bottom
		Vec3 goal = centre(helper, 18, 11, 15); // on the surface, 3 blocks aside
		List<DronePlanner.Step> plan = DronePlanner.plan(helper.getLevel(), helper.absolutePos(new BlockPos(14, 2, 14)), goal, 2.0, Set.of(), true, 0, 6000);
		helper.assertTrue(plan != null && !plan.isEmpty(), "no way up");
		BlockPos start = helper.absolutePos(new BlockPos(14, 2, 14));
		int ups = 0, spread = 0;
		for (DronePlanner.Step step : plan) {
			spread = Math.max(spread, Math.max(Math.abs(step.feet().getX() - start.getX()), Math.abs(step.feet().getZ() - start.getZ())));
		}
		for (int i = 1; i < plan.size(); i++) if (plan.get(i).feet().getY() > plan.get(i - 1).feet().getY()) ups++;
		helper.assertTrue(ups >= 7, "not stairs: only " + ups + " steps up");
		helper.assertTrue(spread <= 14, "a long tunnel instead of stairs: " + spread + " blocks aside");
		helper.succeed();
	}

	@GameTest(template = ARENA)
	public void plannerBridgesGap(GameTestHelper helper) {
		arena(helper);
		fill(helper, 0, 1, 0, 31, 1, 31, Blocks.AIR);
		fill(helper, 0, 6, 0, 31, 6, 31, Blocks.STONE); // a floor high above the ground…
		fill(helper, 10, 6, 0, 13, 6, 31, Blocks.AIR); // …with a 4-block gap
		List<DronePlanner.Step> plan = DronePlanner.plan(helper.getLevel(), helper.absolutePos(new BlockPos(3, 7, 14)),
			centre(helper, 20, 7, 15), 2.0, Set.of(), false, 16, 4000);
		helper.assertTrue(plan != null && plan.stream().anyMatch(step -> !step.fill().isEmpty()), "the gap is not bridged");
		helper.succeed();
	}

	@GameTest(template = ARENA)
	public void routeFloorsAreNotBroken(GameTestHelper helper) {
		arena(helper);
		BlockPos route = helper.absolutePos(new BlockPos(6, 2, 6));
		Set<Long> routes = Set.of(route.asLong());
		helper.assertTrue(!DronePlanner.breakable(helper.getLevel(), route.below(), routes), "a route floor may be broken");
		helper.assertTrue(!DronePlanner.breakable(helper.getLevel(), route.offset(1, -1, 1), routes), "a route floor may be broken");
		helper.assertTrue(DronePlanner.breakable(helper.getLevel(), route.offset(4, -1, 4), routes), "an ordinary floor may not be broken");
		helper.succeed();
	}

	/** Owner: a tree on the site forbids a hatchery. */
	@GameTest(template = ARENA, batch = "hiveRefused")
	public void hatcheryRefusedByTree(GameTestHelper helper) {
		hiveArena(helper);
		fill(helper, 10, 2, 10, 10, 5, 10, Blocks.OAK_LOG);
		StarUnit hatchery = helper.spawn(StarMine.type(UnitKind.HATCHERY), new BlockPos(12, 2, 12));
		helper.runAfterDelay(10, () -> {
			helper.assertTrue(hatchery.isRemoved(), "a hatchery was founded over a tree");
			helper.succeed();
		});
	}

	/** Owner: a hatchery levels earth and stone on its site. */
	@GameTest(template = ARENA, batch = "hiveLevel")
	public void hatcheryLevelsItsSite(GameTestHelper helper) {
		hiveArena(helper);
		fill(helper, 9, 2, 9, 14, 4, 14, Blocks.DIRT);
		StarUnit hatchery = helper.spawn(StarMine.type(UnitKind.HATCHERY), new BlockPos(12, 2, 12));
		helper.runAfterDelay(10, () -> {
			helper.assertTrue(!hatchery.isRemoved(), "the hatchery was refused");
			helper.assertBlockNotPresent(Blocks.DIRT, new BlockPos(10, 3, 10));
			helper.assertBlockNotPresent(Blocks.DIRT, new BlockPos(13, 4, 13));
			helper.succeed();
		});
	}

	/** A hive with resources and 4 drones mutates one into a spawning pool. */
	@GameTest(template = ARENA, timeoutTicks = 5400, batch = "hivePool")
	public void droneBecomesSpawningPool(GameTestHelper helper) {
		hiveArena(helper);
		helper.spawn(StarMine.type(UnitKind.HATCHERY), new BlockPos(24, 2, 24));
		List<StarUnit> drones = new java.util.ArrayList<>();
		for (int i = 0; i < 4; i++) drones.add(helper.spawn(StarMine.type(UnitKind.DRONE), new BlockPos(36 + (i % 2) * 3, 2, 36 + (i / 2) * 3)));
		helper.runAfterDelay(5, () -> {
			HiveSavedData data = HiveSavedData.get(helper.getLevel().getServer());
			Hive hive = data.all().stream().min((a, b) -> Double.compare(a.hatchery.distSqr(drones.get(0).blockPosition()), b.hatchery.distSqr(drones.get(0).blockPosition()))).orElse(null);
			if (hive == null) {
				helper.fail("no hive");
				return;
			}
			hive.minerals = 1000;
			for (StarUnit drone : drones) drone.joinHive(hive);
		});
		helper.succeedWhen(() -> helper.assertTrue(!helper.getLevel().getEntitiesOfClass(StarUnit.class,
			new net.minecraft.world.phys.AABB(helper.absolutePos(BlockPos.ZERO)).inflate(48), mob -> mob.kind == UnitKind.SPAWNING_POOL).isEmpty(), "no spawning pool yet"));
	}

	/** Owner 14.10: a zerg wave sent into a closed stone room waits while the hive's drones cut it a way. */
	@GameTest(template = ARENA, timeoutTicks = 5400, batch = "hiveEngineering")
	public void dronesCutAWayForAWave(GameTestHelper helper) {
		hiveArena(helper);
		ServerLevel level = helper.getLevel();
		for (BlockPos rel : BlockPos.betweenClosed(30, 2, 18, 45, 11, 34)) {
			boolean inside = rel.getX() >= 32 && rel.getX() <= 43 && rel.getZ() >= 20 && rel.getZ() <= 32;
			if (!inside) level.setBlock(helper.absolutePos(rel), Blocks.STONE.defaultBlockState(), 2);
		}
		StarUnit hatchery = helper.spawn(StarMine.type(UnitKind.HATCHERY), new BlockPos(10, 2, 10));
		helper.runAfterDelay(5, () -> {
			Hive hive = HiveSavedData.get(level.getServer()).get(hatchery.getUUID());
			helper.assertTrue(hive != null, "no hive");
			hive.minerals = 500;
			for (int i = 0; i < 3; i++) helper.spawn(StarMine.type(UnitKind.DRONE), new BlockPos(20 + 3 * i, 2, 24)).joinHive(hive);
			List<StarUnit> wave = new java.util.ArrayList<>();
			for (int i = 0; i < 2; i++) {
				StarUnit ling = helper.spawn(StarMine.type(UnitKind.ZERGLING), new BlockPos(22 + 2 * i, 2, 28));
				ling.joinHive(hive);
				wave.add(ling);
			}
			BlockPos target = helper.absolutePos(new BlockPos(38, 2, 26));
			helper.runAfterDelay(5, () -> {
				java.util.Set<java.util.UUID> taken = dev.starmine.colony.Engineering.raid(level, hive, wave, target, level.getGameTime() + 6000);
				helper.assertTrue(!taken.isEmpty(), "the wave was not given a way to cut");
				helper.succeedWhen(() -> helper.assertTrue(wave.stream().anyMatch(ling -> ling.position().distanceTo(Vec3.atBottomCenterOf(target)) < 6),
					"no zergling got into the room; job " + dev.starmine.colony.Engineering.describe(wave.get(0).getUUID())));
			});
		});
	}

	/**
	 * Owner 22.10: a real hive's drone does not dig through stone to an iron ore walled in it (it took ore through the
	 * ground before); the same ore laid open, it mines it.
	 */
	@GameTest(template = ARENA, timeoutTicks = 3600, batch = "hiveOre")
	public void droneMinesOreBehindWall(GameTestHelper helper) {
		hiveArena(helper);
		fill(helper, 21, 2, 21, 27, 5, 27, Blocks.STONE);
		BlockPos ore = new BlockPos(24, 3, 24);
		helper.setBlock(ore, Blocks.IRON_ORE);
		// the hatchery is 15 blocks wide (5 cells of 3): its levelled site stays within the arena, short of the stone
		helper.spawn(StarMine.type(UnitKind.HATCHERY), new BlockPos(12, 2, 12));
		StarUnit drone = helper.spawn(StarMine.type(UnitKind.DRONE), new BlockPos(10, 2, 30));
		helper.runAfterDelay(5, () -> {
			HiveSavedData data = HiveSavedData.get(helper.getLevel().getServer());
			Hive hive = data.all().stream().min((a, b) -> Double.compare(a.hatchery.distSqr(drone.blockPosition()), b.hatchery.distSqr(drone.blockPosition()))).orElse(null);
			if (hive == null) helper.fail("no hive");
			else drone.joinHive(hive);
		});
		helper.runAfterDelay(1200, () -> {
			helper.assertBlockPresent(Blocks.IRON_ORE, ore);
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(21, 3, 24)); // no way dug in
			fill(helper, 21, 3, 21, 27, 5, 27, Blocks.AIR); // the ore laid open
			helper.setBlock(ore, Blocks.IRON_ORE);
			helper.succeedWhen(() -> helper.assertBlockNotPresent(Blocks.IRON_ORE, ore));
		});
	}
}
