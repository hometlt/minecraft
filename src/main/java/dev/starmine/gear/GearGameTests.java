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

	/** Owner 17.10: every weapon of the light infantry is crafted. */
	@GameTest(template = ARENA, batch = "gearRecipes", timeoutTicks = 20)
	public void infantryWeaponsAreCrafted(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (net.minecraft.world.item.Item made : java.util.List.of(Guns.GAUSS_RIFLE, Guns.CANISTER_RIFLE, Guns.GRENADE_LAUNCHER, Guns.PUNISHER_GRENADE,
				Guns.REAPER_PISTOLS, Guns.KD8_CHARGE, Guns.PSI_BLADE)) {
			var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(made);
			var recipe = level.getRecipeManager().byKey(id);
			helper.assertTrue(recipe.isPresent(), "no recipe for " + id);
			helper.assertTrue(recipe.get().value().getResultItem(level.registryAccess()).is(made), "the recipe " + id + " makes something else");
		}
		helper.succeed();
	}

	/** A Punisher grenade hurts an armored stalker by its bonus too and slows it; the pistols fire twice. */
	@GameTest(template = ARENA, batch = "gearGrenade", timeoutTicks = 100)
	public void grenadesHitArmoredHarderAndSlow(GameTestHelper helper) {
		TestArena.clear(helper);
		ServerLevel level = helper.getLevel();
		dev.starmine.entity.StarUnit stalker = TestArena.unit(helper, dev.starmine.entity.UnitKind.STALKER, 24, 24, java.util.UUID.randomUUID());
		stalker.setNoAi(true);
		Zombie shooter = helper.spawn(EntityType.ZOMBIE, new BlockPos(14, 2, 24));
		shooter.setNoAi(true);
		float full = stalker.getHealth() + stalker.shield();
		var grenade = dev.starmine.entity.ZergSpit.fromPlayer(level, shooter, dev.starmine.entity.UnitKind.MARAUDER,
			new net.minecraft.world.item.ItemStack(Guns.PUNISHER_GRENADE), 8f, 8f, true, false);
		Vec3 to = stalker.position().add(0, 1, 0).subtract(grenade.position());
		grenade.shoot(to.x, to.y, to.z, 1.6f, 0f);
		level.addFreshEntity(grenade);
		helper.succeedWhen(() -> {
			float lost = full - stalker.getHealth() - stalker.shield();
			helper.assertTrue(lost >= 15, "the stalker lost " + lost + ", not 8 + 8");
			helper.assertTrue(dev.starmine.ability.Status.has(stalker, dev.starmine.ability.Status.SLOW), "the stalker is not slowed");
		});
	}

	/** A KD8 charge hurts what is round it and throws it back. */
	@GameTest(template = ARENA, batch = "gearKd8", timeoutTicks = 100)
	public void aKd8ChargeThrowsBack(GameTestHelper helper) {
		TestArena.clear(helper);
		ServerLevel level = helper.getLevel();
		var target = helper.spawn(EntityType.SHEEP, new BlockPos(24, 2, 24)); // with its AI: a mob without one is not moved by a push
		Zombie thrower = helper.spawn(EntityType.ZOMBIE, new BlockPos(14, 2, 24));
		thrower.setNoAi(true);
		Vec3 start = target.position();
		var charge = dev.starmine.entity.ZergSpit.fromPlayer(level, thrower, dev.starmine.entity.UnitKind.REAPER,
			new net.minecraft.world.item.ItemStack(Guns.KD8_CHARGE), 4f, 0f, false, true);
		charge.setPos(target.getX() - 1.5, target.getY() + 0.5, target.getZ());
		charge.shoot(1, -0.2, 0, 0.8f, 0f);
		level.addFreshEntity(charge);
		helper.succeedWhen(() -> {
			helper.assertTrue(target.getHealth() < target.getMaxHealth(), "the charge did not hurt");
			helper.assertTrue(target.position().distanceTo(start) > 0.5, "the charge did not throw it back");
		});
	}

	/**
	 * Owner 19.10: a large village (a bell, ten beds) gets the sheriff's stone house: an iron door, iron trapdoors over
	 * the windows, a chest of terran loot, and the sheriff with a gauss rifle, who stays by it and sells for minerals.
	 */
	@GameTest(template = ARENA, batch = "gearSheriff", timeoutTicks = 40)
	public void aLargeVillageHasASheriffHouse(GameTestHelper helper) {
		TestArena.clear(helper);
		ServerLevel level = helper.getLevel();
		BlockPos bell = helper.absolutePos(new BlockPos(8, 2, 24));
		level.setBlock(bell, Blocks.BELL.defaultBlockState(), 3);
		for (int i = 0; i < dev.starmine.world.SheriffHouses.LARGE; i++) {
			BlockPos foot = helper.absolutePos(new BlockPos(2 + i * 4, 2, 5));
			level.setBlock(foot, Blocks.WHITE_BED.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.FACING, net.minecraft.core.Direction.NORTH), 3);
			level.setBlock(foot.north(), Blocks.WHITE_BED.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.FACING, net.minecraft.core.Direction.NORTH)
				.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD), 3);
		}
		BlockPos site = dev.starmine.world.SheriffHouses.consider(level, bell, net.minecraft.util.RandomSource.create(7));
		helper.assertTrue(site != null, "no sheriff house by a village of ten beds");
		int doors = 0, shutters = 0;
		net.minecraft.world.level.block.entity.ChestBlockEntity chest = null;
		for (BlockPos pos : BlockPos.betweenClosed(site.offset(-4, -1, -4), site.offset(4, 6, 4))) {
			var state = level.getBlockState(pos);
			if (state.is(Blocks.IRON_DOOR)) doors++;
			if (state.is(Blocks.IRON_TRAPDOOR)) shutters++;
			if (level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity found) chest = found;
		}
		helper.assertTrue(doors == 2, "no iron door: " + doors + " door blocks");
		helper.assertTrue(shutters >= 10, "the windows are not shut with iron trapdoors: " + shutters);
		helper.assertTrue(chest != null, "no chest in the house");
		boolean grenades = false, rounds = false;
		for (int i = 0; i < chest.getContainerSize(); i++) {
			grenades |= chest.getItem(i).is(Guns.PUNISHER_GRENADE);
			rounds |= chest.getItem(i).is(Guns.GAUSS_AMMO);
		}
		helper.assertTrue(grenades && rounds, "no grenades or gauss rounds in the sheriff's chest");
		var sheriffs = level.getEntitiesOfClass(Townsfolk.class, new net.minecraft.world.phys.AABB(site).inflate(6), folk -> folk.role == Townsfolk.Role.SHERIFF);
		helper.assertTrue(sheriffs.size() == 1, "sheriffs by the house: " + sheriffs.size());
		Townsfolk sheriff = sheriffs.get(0);
		helper.assertTrue(sheriff.getMainHandItem().is(Guns.GAUSS_RIFLE), "the sheriff holds " + sheriff.getMainHandItem());
		helper.assertTrue(sheriff.hasRestriction() && sheriff.getRestrictCenter().equals(site), "the sheriff does not keep to his house");
		var offers = sheriff.getOffers();
		helper.assertTrue(offers.size() == Townsfolk.SHERIFF_OFFERS && offers.stream().allMatch(o -> o.getCostA().is(dev.starmine.mineral.Minerals.SHARD)),
			"the sheriff does not sell for minerals: " + offers.size() + " offers");
		sheriff.discard();
		for (BlockPos pos : BlockPos.betweenClosed(site.offset(-4, 0, -4), site.offset(4, 6, 4))) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
		helper.succeed();
	}
}
