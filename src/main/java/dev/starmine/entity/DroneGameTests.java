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
 * The arena (`starmine:arena`, 32x12x32) is cleared and floored with stone at relative y = 1.
 */
public class DroneGameTests implements FabricGameTest {
	private static final String ARENA = "starmine:arena";

	/** Clears the arena and lays a stone floor at relative y = 1 (walking height 2). */
	private static void arena(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (BlockPos rel : BlockPos.betweenClosed(0, 0, 0, 31, 11, 31))
			level.setBlock(helper.absolutePos(rel), rel.getY() == 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
	}

	private static void fill(GameTestHelper helper, int x0, int y0, int z0, int x1, int y1, int z1, Block block) {
		for (BlockPos rel : BlockPos.betweenClosed(x0, y0, z0, x1, y1, z1)) helper.getLevel().setBlock(helper.absolutePos(rel), block.defaultBlockState(), 2);
	}

	private static Vec3 centre(GameTestHelper helper, int x, int y, int z) {
		return Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(x, y, z)));
	}

	@GameTest(template = ARENA)
	public void plannerDigsThroughWall(GameTestHelper helper) {
		arena(helper);
		fill(helper, 12, 2, 0, 13, 5, 31, Blocks.STONE);
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

	/** A real hive's drone digs to an iron ore walled in stone and mines it. */
	@GameTest(template = ARENA, timeoutTicks = 2400)
	public void droneMinesOreBehindWall(GameTestHelper helper) {
		arena(helper);
		fill(helper, 21, 2, 21, 27, 5, 27, Blocks.STONE);
		BlockPos ore = new BlockPos(24, 3, 24);
		helper.setBlock(ore, Blocks.IRON_ORE);
		helper.spawn(StarMine.type(ZergKind.HATCHERY), new BlockPos(6, 2, 6));
		ZergMob drone = helper.spawn(StarMine.type(ZergKind.DRONE), new BlockPos(16, 2, 16));
		helper.runAfterDelay(5, () -> {
			HiveSavedData data = HiveSavedData.get(helper.getLevel().getServer());
			Hive hive = data.all().stream().min((a, b) -> Double.compare(a.hatchery.distSqr(drone.blockPosition()), b.hatchery.distSqr(drone.blockPosition()))).orElse(null);
			if (hive == null) helper.fail("no hive");
			else drone.joinHive(hive);
		});
		helper.succeedWhen(() -> helper.assertBlockNotPresent(Blocks.IRON_ORE, ore));
	}
}
