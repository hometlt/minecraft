package dev.starmine.ability;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.clear;
import static dev.starmine.ability.TestArena.point;
import static dev.starmine.ability.TestArena.unit;

import dev.starmine.block.StarMineBlocks;
import dev.starmine.creep.CreepGrowth;
import dev.starmine.creep.CreepTumors;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * Owner 17.10, SC2 creep tumors: burrowed after their first seconds (seen by their side and detectors only), shot
 * down by units that see them, wounded by arrows.
 */
public class CreepTumorGameTests implements FabricGameTest {
	/** A creep floor and a wild tumor on it at (24, 24). */
	private static BlockPos tumor(GameTestHelper helper, UUID owner) {
		ServerLevel level = helper.getLevel();
		for (int x = 18; x <= 30; x++)
			for (int z = 18; z <= 30; z++) level.setBlock(helper.absolutePos(new BlockPos(x, 2, z)), StarMineBlocks.CREEP.defaultBlockState()
				.setValue(net.minecraft.world.level.block.MultifaceBlock.getFaceProperty(net.minecraft.core.Direction.DOWN), true), Block.UPDATE_ALL);
		BlockPos at = helper.absolutePos(new BlockPos(24, 2, 24));
		helper.assertTrue(CreepGrowth.placeTumor(level, at, 1, owner), "no tumor planted");
		return at;
	}

	@GameTest(template = ARENA, batch = "tumorSeen", timeoutTicks = 400)
	public void aBurrowedTumorIsSeenOnlyWithADetector(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		UUID player = UUID.randomUUID();
		BlockPos at = tumor(helper, null);
		StarUnit marine = unit(helper, UnitKind.MARINE, 19, 24, player); // within its reach
		helper.runAfterDelay(5, () -> helper.assertTrue(CreepTumors.seen(level, at, marine), "a new tumor is not seen while it rises"));
		helper.runAfterDelay(CreepTumors.SHOWN_TICKS + 20, () -> {
			helper.assertFalse(CreepTumors.seen(level, at, marine), "a burrowed tumor seen without a detector");
			StarUnit raven = unit(helper, UnitKind.RAVEN, 14, 24, player);
			raven.setNoAi(true);
			helper.assertTrue(CreepTumors.seen(level, at, marine), "a raven's side does not see the tumor");
		});
		helper.runAfterDelay(CreepTumors.SHOWN_TICKS + 300, () -> {
			helper.assertFalse(level.getBlockState(at).is(StarMineBlocks.CREEP_TUMOR), "the marine did not shoot down the tumor it sees");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "tumorArrow", timeoutTicks = 200)
	public void arrowsWoundATumor(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		BlockPos at = tumor(helper, UUID.randomUUID());
		helper.runAfterDelay(5, () -> {
			for (int i = 0; i < 6; i++) {
				Arrow arrow = EntityType.ARROW.create(level);
				Vec3 from = Vec3.atCenterOf(at).add(0, 3, 0);
				arrow.moveTo(from.x, from.y, from.z);
				arrow.setDeltaMovement(0, -2, 0);
				level.addFreshEntity(arrow);
			}
		});
		helper.runAfterDelay(100, () -> {
			helper.assertFalse(level.getBlockState(at).is(StarMineBlocks.CREEP_TUMOR), "six arrows did not burst the tumor");
			helper.succeed();
		});
	}
}
