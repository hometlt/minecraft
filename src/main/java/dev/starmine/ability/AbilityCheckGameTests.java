package dev.starmine.ability;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.clear;
import static dev.starmine.ability.TestArena.point;
import static dev.starmine.ability.TestArena.unit;

import dev.starmine.block.StarMineBlocks;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.Vec3;

/**
 * Owner 17.10, checked against SC2: the storm's reach (its radius, the air over it), the neural parasite (creatures and
 * units, never heroes or players), a graviton beam on a creature (held up, not carried off), a massive unit shattering a
 * force field by touch, a changeling taking a soldier's look among another race's troops.
 */
public class AbilityCheckGameTests implements FabricGameTest {
	@GameTest(template = ARENA, batch = "checkStorm", timeoutTicks = 120)
	public void stormHurtsItsCircleAndTheAirOverIt(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		StarUnit templar = unit(helper, UnitKind.HIGH_TEMPLAR, 6, 24, UUID.randomUUID());
		templar.setNoAi(true);
		Vec3 centre = point(helper, 24, 24);
		double r = ProtossAbilities.STORM_RADIUS;
		StarUnit inside = unit(helper, UnitKind.ZERGLING, 24 + r - 0.6, 24, null);
		StarUnit outside = unit(helper, UnitKind.ZERGLING, 24 + r + 1.5, 24, null);
		StarUnit above = unit(helper, UnitKind.MUTALISK, 24, 24 - 1, null);
		for (StarUnit unit : new StarUnit[] {inside, outside, above}) unit.setNoAi(true);
		helper.runAfterDelay(2, () -> {
			above.teleportTo(above.getX(), centre.y + 7, above.getZ());
			templar.setEnergy(200);
			helper.assertTrue(Abilities.cast(templar, level, Ability.PSIONIC_STORM, null, centre), "no storm: " + Abilities.refusal(templar, Ability.PSIONIC_STORM));
		});
		helper.runAfterDelay(80, () -> {
			helper.assertTrue(inside.getHealth() < inside.getMaxHealth(), "a zergling inside the radius was not hurt");
			helper.assertTrue(outside.getHealth() == outside.getMaxHealth(), "a zergling outside the radius was hurt");
			helper.assertTrue(above.getHealth() < above.getMaxHealth(), "a mutalisk over the storm was not hurt");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "checkParasite", timeoutTicks = 60)
	public void parasiteTakesCreaturesNotHeroesOrPlayers(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		StarUnit infestor = unit(helper, UnitKind.INFESTOR, 10, 24, UUID.randomUUID());
		infestor.setNoAi(true);
		StarUnit karax = unit(helper, UnitKind.KARAX, 16, 24, null);
		karax.setNoAi(true);
		StarUnit thor = unit(helper, UnitKind.THOR, 16, 30, null);
		thor.setNoAi(true);
		Zombie zombie = EntityType.ZOMBIE.create(level);
		Vec3 at = point(helper, 14, 18);
		zombie.moveTo(at.x, at.y, at.z, 0, 0);
		level.addFreshEntity(zombie);
		helper.runAfterDelay(2, () -> {
			helper.assertFalse(Abilities.validTarget(infestor, Ability.NEURAL_PARASITE, karax), "a hero taken over");
			helper.assertTrue(Abilities.validTarget(infestor, Ability.NEURAL_PARASITE, thor), "a massive thor cannot be taken over");
			helper.assertTrue(Abilities.validTarget(infestor, Ability.NEURAL_PARASITE, zombie), "a creature cannot be taken over");
			net.minecraft.world.entity.player.Player player = helper.makeMockServerPlayerInLevel();
			helper.assertFalse(Abilities.validTarget(infestor, Ability.NEURAL_PARASITE, player), "a player taken over");
			level.removePlayerImmediately((net.minecraft.server.level.ServerPlayer) player, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
			zombie.discard();
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "checkGraviton", timeoutTicks = 200)
	public void gravitonHoldsACreatureUp(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		StarUnit phoenix = unit(helper, UnitKind.PHOENIX, 10, 24, UUID.randomUUID());
		phoenix.setNoAi(true);
		Zombie zombie = EntityType.ZOMBIE.create(level);
		Vec3 at = point(helper, 13, 24);
		zombie.moveTo(at.x, at.y, at.z, 0, 0);
		level.addFreshEntity(zombie);
		helper.runAfterDelay(2, () -> {
			phoenix.setEnergy(200);
			helper.assertTrue(Abilities.cast(phoenix, level, Ability.GRAVITON_BEAM, zombie, null), "no beam: " + Abilities.refusal(phoenix, Ability.GRAVITON_BEAM));
		});
		helper.runAfterDelay(120, () -> {
			double up = zombie.getY() - at.y;
			zombie.discard();
			helper.assertTrue(up > 1.5 && up < 4, "the zombie is " + up + " blocks up, not held at about 2.5");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "checkForceField", timeoutTicks = 100)
	public void aMassiveUnitShattersAForceField(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		StarUnit sentry = unit(helper, UnitKind.SENTRY, 6, 24, UUID.randomUUID());
		sentry.setNoAi(true);
		StarUnit ultra = unit(helper, UnitKind.ULTRALISK, 24, 24, null);
		ultra.setNoAi(true);
		Vec3 field = point(helper, 24 + ultra.getBbWidth() / 2 + 2.5, 24);
		helper.runAfterDelay(2, () -> {
			sentry.setEnergy(200);
			ProtossAbilities.forceField(sentry, level, field);
			helper.assertTrue(level.getBlockState(BlockPos.containing(field)).is(StarMineBlocks.FORCE_FIELD), "no force field");
			ultra.teleportTo(field.x - ultra.getBbWidth() / 2 - 1.2, ultra.getY(), field.z); // up against it
		});
		helper.runAfterDelay(30, () -> {
			helper.assertFalse(level.getBlockState(BlockPos.containing(field)).is(StarMineBlocks.FORCE_FIELD), "the ultralisk did not shatter the field");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "checkChangeling", timeoutTicks = 300)
	public void aChangelingLooksLikeTheirSoldier(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		StarUnit overseer = unit(helper, UnitKind.OVERSEER, 10, 24, UUID.randomUUID());
		overseer.setNoAi(true);
		StarUnit marine = unit(helper, UnitKind.MARINE, 18, 24, null);
		marine.setNoAi(true);
		helper.runAfterDelay(2, () -> {
			overseer.setEnergy(50);
			helper.assertTrue(Abilities.cast(overseer, level, Ability.SPAWN_CHANGELING, null, null), "no changeling");
		});
		helper.runAfterDelay(200, () -> {
			StarUnit changeling = level.getEntitiesOfClass(StarUnit.class, overseer.getBoundingBox().inflate(30), unit -> unit.kind == UnitKind.CHANGELING).stream()
				.findFirst().orElse(null);
			helper.assertTrue(changeling != null, "no changeling");
			helper.assertTrue(Changeling.disguisedAs(changeling) == UnitKind.MARINE, "no marine look: '" + changeling.disguise() + "'");
			helper.assertFalse(dev.starmine.faction.Faction.hostile(marine, changeling), "the marine sees through it");
			changeling.discard();
			helper.succeed();
		});
	}
}
