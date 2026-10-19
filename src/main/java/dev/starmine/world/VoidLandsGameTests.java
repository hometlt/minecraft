package dev.starmine.world;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.clear;
import static dev.starmine.ability.TestArena.point;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Owner 19.10: the Void's islands without end (rarer the farther), wide paths, and Amun's black holes. */
public class VoidLandsGameTests implements FabricGameTest {
	@GameTest(template = ARENA, batch = "voidLands", timeoutTicks = 20)
	public void farIslandsGrowRarerOutward(GameTestHelper helper) {
		int near = 0, far = 0, squares = 0;
		for (int rx = -12; rx <= 12; rx++)
			for (int rz = -12; rz <= 12; rz++) {
				VoidIslands.Island island = VoidIslands.far(77L, rx, rz);
				double d = Math.hypot((rx + 0.5) * VoidIslands.REGION, (rz + 0.5) * VoidIslands.REGION);
				if (d < VoidIslands.FAR_FROM - VoidIslands.REGION) helper.assertTrue(island == null, "a far island among the first ones");
				if (island == null) continue;
				helper.assertTrue(Math.floorDiv(island.centre().getX(), VoidIslands.REGION) == rx, "an island outside its square");
				if (d < 900) near++;
				else if (d > 1500) far++;
			}
		for (int rx = -12; rx <= 12; rx++) for (int rz = -12; rz <= 12; rz++) squares++;
		helper.assertTrue(near > 0 && far > 0, "no far islands: " + near + " near, " + far + " far of " + squares);
		// per square, rarer outward
		int nearSquares = 0, farSquares = 0;
		for (int rx = -12; rx <= 12; rx++)
			for (int rz = -12; rz <= 12; rz++) {
				double d = Math.hypot((rx + 0.5) * VoidIslands.REGION, (rz + 0.5) * VoidIslands.REGION);
				if (d >= VoidIslands.FAR_FROM && d < 900) nearSquares++;
				else if (d > 1500) farSquares++;
			}
		helper.assertTrue(near / (double) nearSquares > far / (double) farSquares, "islands are not rarer outward");
		VoidIslands.Island one = VoidIslands.far(77L, 5, 5);
		if (one != null) helper.assertTrue(VoidIslands.toward(77L, one) != null, "a far island leads nowhere");
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "voidHole", timeoutTicks = VoidBlackHole.GROW + 60)
	public void aBlackHoleDrawsInAndBurstsWithItsBlocks(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		BlockPos centre = BlockPos.containing(point(helper, 24, 24));
		for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) level.setBlockAndUpdate(centre.offset(x, 2, z), Blocks.STONE.defaultBlockState());
		StarUnit marine = dev.starmine.ability.TestArena.unit(helper, UnitKind.MARINE, 24, 31, UUID.randomUUID());
		double before = marine.distanceToSqr(centre.getX() + 0.5, centre.getY(), centre.getZ() + 0.5);
		VoidBlackHole.open(level, null, centre);
		helper.runAfterDelay(VoidBlackHole.GROW / 2, () -> {
			helper.assertTrue(!marine.isAlive() || marine.distanceToSqr(centre.getX() + 0.5, centre.getY(), centre.getZ() + 0.5) < before, "the black hole draws nothing in");
		});
		helper.runAfterDelay(VoidBlackHole.GROW + 20, () -> {
			helper.assertTrue(level.getBlockState(centre.above(2)).isAir() && level.getBlockState(centre.offset(3, 2, 3)).isAir(), "its blocks are still there");
			helper.assertTrue(VoidBlackHole.open().isEmpty(), "it is still open");
			if (marine.isAlive()) marine.discard();
			helper.succeed();
		});
	}
}
