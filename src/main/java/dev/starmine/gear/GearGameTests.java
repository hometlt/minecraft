package dev.starmine.gear;

import static dev.starmine.ability.TestArena.ARENA;

import dev.starmine.ability.TestArena;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Terran guns and the armed villagers (owner 16.10). */
public class GearGameTests implements FabricGameTest {
	/** A gauss shot hits the first creature on the line; a wall stops it. */
	@GameTest(template = ARENA, batch = "gear", timeoutTicks = 60)
	public void gaussShotHitsAndWallsStopIt(GameTestHelper helper) {
		TestArena.clear(helper);
		ServerLevel level = helper.getLevel();
		Zombie open = helper.spawn(EntityType.ZOMBIE, new BlockPos(20, 2, 10)), covered = helper.spawn(EntityType.ZOMBIE, new BlockPos(20, 2, 30));
		open.setNoAi(true);
		covered.setNoAi(true);
		for (int y = 2; y <= 5; y++) helper.setBlock(new BlockPos(14, y, 30), Blocks.STONE);
		Townsfolk marshal = helper.spawn(Guns.MARSHAL, new BlockPos(6, 2, 10));
		marshal.setNoAi(true);
		helper.runAfterDelay(2, () -> {
			Vec3 eye = marshal.getEyePosition();
			GunItem.shoot(level, marshal, eye, open.position().add(0, 1, 0).subtract(eye).normalize(), 5, 32);
			Vec3 from = Vec3.atCenterOf(helper.absolutePos(new BlockPos(6, 3, 30)));
			GunItem.shoot(level, marshal, from, covered.position().add(0, 1, 0).subtract(from).normalize(), 5, 32);
			helper.assertTrue(open.getHealth() < open.getMaxHealth(), "the shot missed");
			helper.assertTrue(covered.getHealth() == covered.getMaxHealth(), "the shot went through a wall");
			helper.assertTrue(!marshal.getOffers().isEmpty(), "the marshal trades nothing");
			helper.succeed();
		});
	}
}
