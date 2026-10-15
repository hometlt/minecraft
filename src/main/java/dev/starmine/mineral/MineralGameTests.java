package dev.starmine.mineral;

import static dev.starmine.ability.TestArena.ARENA;

import dev.starmine.ability.TestArena;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Mineral crystals (owner 16.10): gathered, never broken; gone when empty, their earth back to dirt; no main building beside them. */
public class MineralGameTests implements FabricGameTest {
	private static BlockPos crystal(GameTestHelper helper, int x, int z) {
		ServerLevel level = helper.getLevel();
		BlockPos core = helper.absolutePos(new BlockPos(x, 2, z));
		for (BlockPos pos : BlockPos.betweenClosed(core.offset(-2, -1, -2), core.offset(3, -1, 3))) level.setBlock(pos, Blocks.DIRT.defaultBlockState(), 2);
		helper.assertTrue(Minerals.place(level, core, (pos, state) -> level.setBlock(pos, state, 2)), "the crystal did not fit");
		return core;
	}

	@GameTest(template = ARENA, batch = "minerals", timeoutTicks = 100)
	public void crystalIsGatheredAndGone(GameTestHelper helper) {
		TestArena.clear(helper);
		ServerLevel level = helper.getLevel();
		BlockPos core = crystal(helper, 24, 24);
		helper.assertTrue(level.getBlockEntity(core) instanceof Minerals.Core entity && entity.amount() == Minerals.AMOUNT, "not a full crystal");
		helper.assertTrue(level.getBlockState(core.offset(1, 1, 1)).is(Minerals.CRYSTAL), "not 2 x 2 x 2");
		helper.assertTrue(level.getBlockState(core).getDestroySpeed(level, core) < 0, "a crystal can be broken");
		helper.assertTrue(level.getBlockState(core.offset(-2, -1, 0)).is(Minerals.EARTH) && level.getBlockState(core.below()).is(Minerals.EARTH),
			"no mineralized earth");
		helper.assertTrue(Minerals.harvest(level, core.offset(1, 1, 0), Minerals.PER_TRIP) == 5, "a trip is not 5");
		Minerals.Core entity = (Minerals.Core) level.getBlockEntity(core);
		helper.assertTrue(entity.amount() == Minerals.AMOUNT - 5, "the crystal keeps " + entity.amount());
		entity.setAmount(3);
		helper.assertTrue(Minerals.harvest(level, core, Minerals.PER_TRIP) == 3, "the last trip is not what was left");
		helper.assertTrue(!level.getBlockState(core).is(Minerals.CRYSTAL) && !level.getBlockState(core.offset(1, 1, 1)).is(Minerals.CRYSTAL),
			"the empty crystal is still there");
		helper.assertTrue(level.getBlockState(core.below()).is(Blocks.DIRT) && level.getBlockState(core.offset(-2, -1, 0)).is(Blocks.DIRT),
			"the earth stays mineralized");
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "minerals", timeoutTicks = 100)
	public void noMainBuildingNearMinerals(GameTestHelper helper) {
		TestArena.clear(helper);
		ServerLevel level = helper.getLevel();
		crystal(helper, 40, 24);
		helper.assertTrue(Minerals.nearSite(level, helper.absolutePos(new BlockPos(20, 2, 24)), UnitKind.COMMAND_CENTER.width / 2.0),
			"a command center 12 blocks off the crystal's edge is allowed");
		helper.assertTrue(!Minerals.nearSite(level, helper.absolutePos(new BlockPos(8, 2, 24)), 0.5), "a site 31 blocks off is refused");
		helper.succeed();
	}

	/** An SCV of a player's base gathers a crystal: 5 a trip, brought to the command center. */
	@GameTest(template = ARENA, batch = "mineralsScv", timeoutTicks = 1200)
	public void scvGathersMinerals(GameTestHelper helper) {
		TestArena.clear(helper);
		ServerLevel level = helper.getLevel();
		BlockPos core = crystal(helper, 34, 24);
		StarUnit cc = TestArena.unit(helper, UnitKind.COMMAND_CENTER, 10, 24, UUID.randomUUID());
		int[] start = new int[1];
		helper.runAfterDelay(20, () -> {
			Hive base = TestArena.base(helper, cc);
			helper.assertTrue(base != null, "no base");
			start[0] = base.mineralsCollected;
			StarUnit scv = TestArena.member(helper, base, UnitKind.SCV, 26, 24);
			scv.commandWork(StarUnit.Work.GATHER, core.offset(0, 1, 0));
		});
		helper.succeedWhen(() -> {
			Hive base = TestArena.base(helper, cc);
			helper.assertTrue(base != null && base.mineralsCollected - start[0] >= 10, "minerals brought: "
				+ (base == null ? 0 : base.mineralsCollected - start[0]));
			helper.assertTrue(level.getBlockEntity(core) instanceof Minerals.Core entity && entity.amount() <= Minerals.AMOUNT - 10, "the crystal was not mined");
		});
	}
}
