package dev.starmine.world;

import dev.starmine.ability.TestArena;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;

public class DungeonTerrainGameTests implements FabricGameTest {
	@GameTest(template = TestArena.ARENA, batch = "largeBattlePassages", timeoutTicks = 20)
	public void mainPassagesFitEveryCurrentUnit(GameTestHelper helper) {
		for (var kind : dev.starmine.entity.UnitKind.values())
			if (!kind.structure()) helper.assertTrue(BattleSpaces.fits(kind), "main passage too small for " + kind + " (" + kind.width + " x " + kind.height + ")");
		helper.succeed();
	}
	@GameTest(template = TestArena.ARENA, batch = "stairsSurface", timeoutTicks = 40)
	public void stairsStopAtOriginalSurface(GameTestHelper helper) { stairs(helper, false); }

	@GameTest(template = TestArena.ARENA, batch = "deferredStairsSurface", timeoutTicks = 40)
	public void deferredStairsStopAtOriginalSurface(GameTestHelper helper) { stairs(helper, true); }

	private static void stairs(GameTestHelper helper, boolean deferred) {
		TestArena.clear(helper);
		var level = helper.getLevel();
		BlockPos door = helper.absolutePos(new BlockPos(24, 4, 24));
		for (BlockPos at : BlockPos.betweenClosed(door.offset(-3, 40, -3), door.offset(125, 40, 3)))
			level.setBlock(at, Blocks.STONE.defaultBlockState(), 2);
		Painter painter = deferred ? Painter.deferred(level, RandomSource.create(1)) : new Painter(level, RandomSource.create(1));
		BlockPos exit = Dungeons.stairsUp(painter, door, Direction.EAST, Blocks.STONE_BRICKS.defaultBlockState(), Blocks.GOLD_BLOCK.defaultBlockState());
		if (deferred) { painter.start(); Painter.finishAll(); }
		helper.assertTrue(exit.getY() == door.getY() + 40, "stair missed the original surface: " + exit);
		helper.assertTrue(exit.getX() == door.getX() + 40, "stair kept climbing after reaching the ground");
		helper.assertTrue(level.getBlockState(exit).is(Blocks.STONE_BRICK_STAIRS), "missing final step");
		helper.assertTrue(level.getBlockState(exit.east().above()).isAir(), "stair continues into the sky");
		helper.succeed();
	}

	@GameTest(template = TestArena.ARENA, batch = "buriedTemple", timeoutTicks = 40)
	public void templeRoofStaysBelowLocalGround(GameTestHelper helper) {
		TestArena.clear(helper);
		var level = helper.getLevel();
		for (BlockPos rel : BlockPos.betweenClosed(-30, 30, -150, 90, 30, 90))
			level.setBlock(helper.absolutePos(rel), Blocks.STONE.defaultBlockState(), 2);
		Painter painter = new Painter(level, RandomSource.create(2));
		BlockPos requested = helper.absolutePos(new BlockPos(24, 28, 24));
		BlockPos floor = Dungeons.buriedFloor(painter, requested, 41, 12);
		helper.assertTrue(floor != null && floor.getY() + 12 < helper.absolutePos(new BlockPos(24, 30, 24)).getY(), "temple roof would emerge above ground");
		var guards = Dungeons.ancientTemples(painter, requested);
		helper.assertTrue(!guards.isEmpty() && guards.stream().allMatch(s -> s.pos().getY() == floor.getY()), "temple did not use the buried floor");
		helper.assertTrue(level.getBlockState(floor.above(12)).is(Blocks.CUT_SANDSTONE), "buried temple roof is missing");
		helper.succeed();
	}

	@GameTest(template = TestArena.ARENA, batch = "shallowTemple", timeoutTicks = 40)
	public void shallowWorldDoesNotProduceFloatingTemple(GameTestHelper helper) {
		TestArena.clear(helper);
		Painter painter = new Painter(helper.getLevel(), RandomSource.create(3));
		var guards = Dungeons.ancientTemples(painter, helper.absolutePos(new BlockPos(24, 8, 24)));
		helper.assertTrue(guards.isEmpty() && painter.placed() == 0, "a dungeon without underground room was built above ground");
		helper.succeed();
	}
}
