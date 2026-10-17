package dev.starmine.world;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.clear;
import static dev.starmine.ability.TestArena.point;

import dev.starmine.entity.DronePlanner;
import dev.starmine.entity.UnitKind;
import dev.starmine.vespene.Vespene;
import dev.starmine.vespene.VespeneBlock;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Owner 17.10: under a player's base 20 blocks of plain stone and a concrete top workers leave alone (its vespene
 * kept); workers see valuables within 10 blocks of themselves through walls, and no farther.
 */
public class BaseGroundGameTests implements FabricGameTest {
	@GameTest(template = ARENA, batch = "baseGround", timeoutTicks = 20)
	public void underAPlayersBaseIsPlainStone(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		BlockPos feet = BlockPos.containing(point(helper, 24, 24)).above(30); // clear of the world floor
		for (BlockPos at : BlockPos.betweenClosed(feet.offset(-8, -12, -8), feet.offset(8, -1, 8)))
			level.setBlock(at, (at.getY() == feet.getY() - 1 ? Blocks.DIRT : Blocks.STONE).defaultBlockState(), Block.UPDATE_ALL);
		BlockPos ore = feet.below(5), hole = feet.below(8), gas = feet.offset(3, -1, 0), pond = feet.offset(-4, -1, 0);
		level.setBlock(pond, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(ore, Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(hole, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(gas, Vespene.VESPENE.defaultBlockState().setValue(VespeneBlock.OUTLET, true), Block.UPDATE_ALL);
		BaseGround.foundation(level, feet, 8, UnitKind.Race.TERRAN);
		helper.assertTrue(level.getBlockState(feet.below()).is(Blocks.GRAY_CONCRETE), "no concrete top: " + level.getBlockState(feet.below()));
		helper.assertTrue(level.getBlockState(ore).is(Blocks.STONE), "ore left under the base: " + level.getBlockState(ore));
		helper.assertTrue(level.getBlockState(hole).is(Blocks.STONE), "a cave left under the base");
		helper.assertTrue(VespeneBlock.isSource(level.getBlockState(gas)), "the base's vespene outlet was covered");
		helper.assertTrue(level.getBlockState(pond).is(Blocks.WATER), "a pond by the base was filled in");
		boolean dug = DronePlanner.breakable(level, feet.below(), null);
		for (BlockPos at : BlockPos.betweenClosed(feet.offset(-9, -24, -9), feet.offset(9, 0, 9))) level.removeBlock(at, false); // the test world is reused
		helper.assertFalse(dug, "workers may dig the base's concrete");
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "baseGroundOre", timeoutTicks = 20)
	public void workersSeeOreTenBlocksThroughWalls(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		BlockPos worker = BlockPos.containing(point(helper, 10, 24));
		for (int y = 0; y <= 4; y++) for (int z = -3; z <= 3; z++) level.setBlock(worker.offset(3, y, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		BlockPos near = worker.offset(8, 1, 0), far = worker.offset(15, 1, 0);
		level.setBlock(far, Blocks.GOLD_ORE.defaultBlockState(), Block.UPDATE_ALL);
		BlockPos none = DronePlanner.oreFor(level, worker);
		level.setBlock(near, Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
		BlockPos seen = DronePlanner.oreFor(level, worker);
		helper.assertTrue(none == null, "a worker saw ore 15 blocks off: " + none);
		helper.assertTrue(near.equals(seen), "a worker did not see ore 8 blocks off behind a wall: " + seen);
		helper.succeed();
	}
}
