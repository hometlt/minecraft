package dev.starmine.ability;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.clear;
import static dev.starmine.ability.TestArena.point;
import static dev.starmine.ability.TestArena.unit;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.Trait;
import dev.starmine.entity.UnitKind;
import dev.starmine.gear.TraitPotions;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.phys.Vec3;

/**
 * Owner 17.10: SC2 attributes for everyone and the bonus damage against them; potions that give them (never to a
 * building); the detector potion; the cyclone, the raven and the sensor tower.
 */
public class TraitGameTests implements FabricGameTest {
	@GameTest(template = ARENA, batch = "traits", timeoutTicks = 40)
	public void everyoneHasAttributes(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayerInLevel();
		IronGolem golem = EntityType.IRON_GOLEM.create(level);
		try {
			helper.assertTrue(Trait.of(player).containsAll(java.util.List.of(Trait.BIOLOGICAL, Trait.LIGHT, Trait.HEROIC)), "a player is " + Trait.of(player));
			helper.assertTrue(Trait.of(golem).containsAll(java.util.List.of(Trait.MECHANICAL, Trait.ARMORED, Trait.MASSIVE)), "a golem is " + Trait.of(golem));
			helper.assertTrue(Trait.of(UnitKind.HIGH_TEMPLAR).contains(Trait.PSIONIC) && Trait.of(UnitKind.ULTRALISK).contains(Trait.MASSIVE)
				&& Trait.of(UnitKind.KARAX).contains(Trait.HEROIC), "unit attributes are wrong");
			// the marauder's +10 against armored: none against a light player, then the armored potion
			double light = UnitKind.MARAUDER.bonusDamage(Trait.of(player));
			player.addEffect(new MobEffectInstance(TraitPotions.effect(Trait.ARMORED), 200));
			double armored = UnitKind.MARAUDER.bonusDamage(Trait.of(player));
			helper.assertTrue(light == 0 && armored == 10, "a marauder's bonus against a player: " + light + " light, " + armored + " armored");
			helper.assertFalse(Trait.of(player).contains(Trait.LIGHT), "armored and light at once");
			var brewing = level.potionBrewing();
			ItemStack awkward = PotionContents.createItemStack(Items.POTION, Potions.AWKWARD);
			helper.assertTrue(brewing.hasMix(awkward, new ItemStack(Items.IRON_INGOT)), "no armored potion brewed from an iron ingot");
			helper.assertTrue(brewing.hasMix(awkward, new ItemStack(Items.GLOW_INK_SAC)), "no radar potion brewed");
		} finally {
			level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
		}
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "traitBuildings", timeoutTicks = 40)
	public void potionsLeaveBuildingsBe(GameTestHelper helper) {
		clear(helper);
		StarUnit depot = unit(helper, UnitKind.SUPPLY_DEPOT, 20, 20, UUID.randomUUID());
		StarUnit marine = unit(helper, UnitKind.MARINE, 26, 20, UUID.randomUUID());
		helper.runAfterDelay(2, () -> {
			depot.addEffect(new MobEffectInstance(TraitPotions.effect(Trait.MASSIVE), 200));
			marine.addEffect(new MobEffectInstance(TraitPotions.effect(Trait.MASSIVE), 200));
			helper.assertFalse(Trait.has(depot, Trait.MASSIVE), "a building took a potion");
			helper.assertTrue(Trait.has(marine, Trait.MASSIVE), "a marine did not take a potion");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "traitDetector", timeoutTicks = 60)
	public void aDetectorPotionSeesCloakedUnits(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		StarUnit templar = unit(helper, UnitKind.DARK_TEMPLAR, 24, 24, null);
		templar.setNoAi(true);
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayerInLevel();
		Vec3 at = point(helper, 20, 24);
		player.teleportTo(at.x, at.y, at.z);
		helper.runAfterDelay(25, () -> helper.assertFalse(Cloak.seen(templar, player), "a dark templar seen without a detector"));
		helper.runAfterDelay(26, () -> player.addEffect(new MobEffectInstance(TraitPotions.effect(Trait.DETECTOR), 400)));
		helper.runAfterDelay(50, () -> {
			boolean seen = Cloak.seen(templar, player);
			level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
			helper.assertTrue(seen, "a detector potion does not see a dark templar");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "traitNewUnits", timeoutTicks = 400)
	public void cycloneAndRavenWork(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		StarUnit cyclone = unit(helper, UnitKind.CYCLONE, 10, 24, UUID.randomUUID());
		cyclone.setNoAi(true);
		StarUnit raven = unit(helper, UnitKind.RAVEN, 10, 30, UUID.randomUUID());
		raven.setNoAi(true);
		StarUnit ultra = unit(helper, UnitKind.ULTRALISK, 20, 24, null);
		ultra.setNoAi(true);
		StarUnit stalker = unit(helper, UnitKind.STALKER, 20, 34, null);
		stalker.setNoAi(true);
		helper.runAfterDelay(2, () -> {
			helper.assertTrue(UnitKind.FACTORY.trains().contains(UnitKind.CYCLONE) && UnitKind.STARPORT.trains().contains(UnitKind.RAVEN)
				&& UnitKind.LURKER.requires() == UnitKind.LURKER_DEN, "the new kinds are not trained where SC2 trains them");
			helper.assertTrue(Abilities.cast(cyclone, level, Ability.LOCK_ON, ultra, null), "no lock on: " + Abilities.refusal(cyclone, Ability.LOCK_ON));
			raven.setEnergy(200);
			helper.assertTrue(Abilities.cast(raven, level, Ability.INTERFERENCE_MATRIX, stalker, null), "no matrix: " + Abilities.refusal(raven, Ability.INTERFERENCE_MATRIX));
			helper.assertTrue(Abilities.cast(raven, level, Ability.AUTO_TURRET, null, point(helper, 12, 32)), "no turret: " + Abilities.refusal(raven, Ability.AUTO_TURRET));
		});
		helper.runAfterDelay(100, () -> {
			helper.assertTrue(ultra.getMaxHealth() - ultra.getHealth() > 50 * UnitKind.SCALE, "the lock on did " + (ultra.getMaxHealth() - ultra.getHealth()));
			helper.assertTrue(Status.has(stalker, Status.INTERFERED) && stalker.weapon().damage() == 0, "the stalker can fight under the matrix");
			helper.assertTrue(level.getEntitiesOfClass(StarUnit.class, raven.getBoundingBox().inflate(10), unit -> unit.kind == UnitKind.AUTO_TURRET).size() == 1,
				"no auto-turret");
		});
		helper.runAfterDelay(300, () -> {
			helper.assertTrue(level.getEntitiesOfClass(StarUnit.class, raven.getBoundingBox().inflate(10), unit -> unit.kind == UnitKind.AUTO_TURRET).isEmpty(),
				"the auto-turret outlived its 10 s");
			helper.succeed();
		});
	}
}
