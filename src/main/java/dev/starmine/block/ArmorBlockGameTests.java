package dev.starmine.block;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.clear;
import static dev.starmine.ability.TestArena.point;

import dev.starmine.entity.UnitKind;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Owner 19.10: weapons wear blocks (cracks that stay, then heal), armour is tougher than obsidian, a door of several
 * blocks opens for a friend and breaks whole.
 */
public class ArmorBlockGameTests implements FabricGameTest {
	@GameTest(template = ARENA, batch = "armorWear", timeoutTicks = 40)
	public void shotsWearBlocksAndArmourHolds(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		BlockPos stone = BlockPos.containing(point(helper, 10, 10)), obsidian = stone.east(2), armour = stone.east(4);
		level.setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
		level.setBlockAndUpdate(obsidian, Blocks.OBSIDIAN.defaultBlockState());
		level.setBlockAndUpdate(armour, ArmorBlocks.wall(UnitKind.Race.TERRAN, 0));
		helper.assertFalse(BlockWear.hit(level, stone, 4, null), "stone broke at one shot");
		helper.assertTrue(BlockWear.progress(level, stone) > 0, "no cracks on the stone");
		boolean broke = false;
		for (int i = 0; i < 10 && !broke; i++) broke = BlockWear.hit(level, stone, 4, null);
		helper.assertTrue(broke && level.getBlockState(stone).isAir(), "stone does not break under fire");
		for (int i = 0; i < 20; i++) {
			BlockWear.hit(level, obsidian, 10, null);
			BlockWear.hit(level, armour, 10, null);
		}
		helper.assertTrue(level.getBlockState(obsidian).is(Blocks.OBSIDIAN) && BlockWear.progress(level, obsidian) > 0, "obsidian not worn");
		helper.assertTrue(BlockWear.progress(level, armour) < BlockWear.progress(level, obsidian), "armour wore as fast as obsidian");
		helper.assertTrue(BlockWear.durability(level, armour, level.getBlockState(armour)) > BlockWear.durability(level, obsidian, Blocks.OBSIDIAN.defaultBlockState()),
			"armour is not tougher than obsidian");
		level.removeBlock(obsidian, false);
		level.removeBlock(armour, false);
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "armorDoor", timeoutTicks = 100)
	public void aDoorOpensWholeForAFriendAndBreaksWhole(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		ArmorDoorBlock door = ArmorBlocks.DOORS.get(UnitKind.Race.TERRAN);
		BlockPos corner = BlockPos.containing(point(helper, 24, 24));
		for (int x = 0; x < 3; x++)
			for (int y = 0; y < 3; y++) level.setBlockAndUpdate(corner.offset(x, y, 0), door.defaultBlockState());
		helper.assertTrue(door.door(level, corner).size() == 9, "the nine blocks are not one door");
		var marine = dev.starmine.ability.TestArena.unit(helper, UnitKind.MARINE, 25, 22, UUID.randomUUID());
		helper.runAfterDelay(30, () -> {
			for (int x = 0; x < 3; x++)
				for (int y = 0; y < 3; y++)
					helper.assertTrue(level.getBlockState(corner.offset(x, y, 0)).getValue(ArmorDoorBlock.OPEN), "a block of the door stays shut");
			marine.discard();
		});
		helper.runAfterDelay(60, () -> {
			helper.assertFalse(level.getBlockState(corner).getValue(ArmorDoorBlock.OPEN), "the door stays open with no one near");
			level.destroyBlock(corner.offset(1, 1, 0), false);
			for (int x = 0; x < 3; x++)
				for (int y = 0; y < 3; y++) helper.assertTrue(level.getBlockState(corner.offset(x, y, 0)).isAir(), "a piece of the door stayed");
			helper.succeed();
		});
	}
}
