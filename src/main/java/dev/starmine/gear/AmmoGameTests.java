package dev.starmine.gear;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.clear;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;

/** Owner 19.10: the races' rounds — crafted, and fired by a dispenser at what stands before it. */
public class AmmoGameTests implements FabricGameTest {
	@GameTest(template = ARENA, batch = "ammoDispenser", timeoutTicks = 80)
	public void aDispenserFiresNeedles(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		for (String id : new String[] {"bone_needle", "toxic_needle", "gauss_ammo_from_gunpowder", "energy_charge"})
			helper.assertTrue(level.getServer().getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath("starmine", id)).isPresent(), "no recipe " + id);
		BlockPos at = helper.absolutePos(new BlockPos(8, 2, 8));
		level.setBlock(at, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST), Block.UPDATE_ALL);
		if (level.getBlockEntity(at) instanceof DispenserBlockEntity dispenser) dispenser.setItem(0, new ItemStack(Ammo.TOXIC_NEEDLE, 8));
		Pig target = EntityType.PIG.create(level); // not undead: poison takes
		target.moveTo(at.getX() + 4.5, at.getY(), at.getZ() + 0.5, 90, 0);
		target.setNoAi(true);
		level.addFreshEntity(target);
		float health = target.getHealth();
		helper.runAfterDelay(5, () -> level.setBlock(at.below(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL));
		helper.runAfterDelay(40, () -> {
			boolean hurt = target.getHealth() < health || !target.isAlive();
			boolean poisoned = target.hasEffect(net.minecraft.world.effect.MobEffects.POISON);
			target.discard();
			level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			level.setBlock(at.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
			helper.assertTrue(hurt, "the dispenser's needle did not hit the pig");
			helper.assertTrue(poisoned, "the toxic needle did not poison");
			helper.succeed();
		});
	}
}
