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
		for (String id : new String[] {"bone_needle", "toxic_needle", "gauss_ammo", "energy_charge"})
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

	@GameTest(template = ARENA, batch = "turretFires", timeoutTicks = 160)
	public void aTurretShootsMonstersAndMonstersGoForIt(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		for (Turrets.Kind kind : Turrets.Kind.values())
			helper.assertTrue(level.getServer().getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath("starmine", kind.id())).isPresent(), "no recipe " + kind.id());
		Turrets.Turret turret = Turrets.TYPES.get(Turrets.Kind.GAUSS).create(level);
		BlockPos at = helper.absolutePos(new BlockPos(10, 2, 24));
		turret.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
		level.addFreshEntity(turret);
		turret.round = new ItemStack(Guns.GAUSS_AMMO);
		turret.setRounds(20);
		net.minecraft.world.entity.monster.Zombie standing = EntityType.ZOMBIE.create(level);
		standing.moveTo(at.getX() + 7.5, at.getY(), at.getZ() + 0.5, 90, 0);
		standing.setNoAi(true);
		level.addFreshEntity(standing);
		// an empty turret elsewhere: a zombie goes for it unprovoked
		Turrets.Turret empty = Turrets.TYPES.get(Turrets.Kind.PHOTON).create(level);
		empty.moveTo(at.getX() + 20.5, at.getY(), at.getZ() - 14.5, 0, 0);
		level.addFreshEntity(empty);
		net.minecraft.world.entity.monster.Zombie hunter = EntityType.ZOMBIE.create(level);
		hunter.moveTo(at.getX() + 20.5, at.getY(), at.getZ() - 8.5, 0, 0);
		level.addFreshEntity(hunter);
		float health = standing.getHealth();
		helper.runAfterDelay(120, () -> {
			boolean shot = standing.getHealth() < health || !standing.isAlive();
			int left = turret.rounds();
			boolean hunted = hunter.getTarget() == empty;
			standing.discard();
			hunter.discard();
			turret.discard();
			empty.discard();
			helper.assertTrue(shot, "the gauss turret did not shoot the zombie");
			helper.assertTrue(left < 20, "the turret fired without spending rounds");
			helper.assertTrue(hunted, "the zombie does not go for the turret");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "traps", timeoutTicks = 200)
	public void trapsSpringOnFoes(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		for (String id : new String[] {"stasis_trap", "toxic_nest"})
			helper.assertTrue(level.getServer().getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath("starmine", id)).isPresent(), "no recipe " + id);
		BlockPos trap = helper.absolutePos(new BlockPos(8, 2, 36)), nest = trap.east(12);
		level.setBlock(trap, Traps.STASIS_TRAP.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(nest, Traps.TOXIC_NEST.defaultBlockState(), Block.UPDATE_ALL);
		net.minecraft.world.entity.monster.Zombie held = EntityType.ZOMBIE.create(level), stepping = EntityType.ZOMBIE.create(level);
		held.moveTo(trap.getX() + 2.5, trap.getY(), trap.getZ() + 0.5, 0, 0);
		stepping.moveTo(nest.getX() + 0.5, nest.getY(), nest.getZ() + 0.5, 0, 0);
		for (var zombie : new net.minecraft.world.entity.monster.Zombie[] {held, stepping}) {
			zombie.setNoAi(true);
			level.addFreshEntity(zombie);
		}
		float health = stepping.getHealth();
		boolean[] caught = {false};
		helper.runAfterDelay(20, () -> caught[0] = BlockPos.betweenClosedStream(new net.minecraft.world.phys.AABB(held.blockPosition()).inflate(2))
			.anyMatch(at -> level.getBlockState(at).is(Traps.STASIS_FIELD)));
		helper.runAfterDelay(Traps.STASIS_TICKS + 40, () -> {
			boolean gone = BlockPos.betweenClosedStream(new net.minecraft.world.phys.AABB(held.blockPosition()).inflate(2)).noneMatch(at -> level.getBlockState(at).is(Traps.STASIS_FIELD));
			boolean burst = !level.getBlockState(nest).is(Traps.TOXIC_NEST) && (stepping.getHealth() < health || !stepping.isAlive());
			boolean creep = BlockPos.betweenClosedStream(new net.minecraft.world.phys.AABB(nest).inflate(2, 1, 2)).anyMatch(at -> dev.starmine.block.StarMineBlocks.isCreep(level.getBlockState(at)));
			held.discard();
			stepping.discard();
			level.setBlock(trap, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			for (BlockPos at : BlockPos.betweenClosed(nest.offset(-3, -1, -3), nest.offset(3, 1, 3)))
				if (dev.starmine.block.StarMineBlocks.isCreep(level.getBlockState(at))) level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			helper.assertTrue(caught[0], "the stasis trap did not shut the zombie in");
			helper.assertTrue(gone, "the stasis did not pass");
			helper.assertTrue(burst, "the toxic nest did not burst under the zombie");
			helper.assertTrue(creep, "the toxic nest threw no creep");
			helper.succeed();
		});
	}
}
