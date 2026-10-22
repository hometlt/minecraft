package dev.starmine.ability;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.clear;
import static dev.starmine.ability.TestArena.unit;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.race.Research;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;

/**
 * Owner 17.10: SC2's unit upgrades. Burrow, cloaks, storm and the rest wait for their research at a base (a lone wild
 * unit has them all); speed, range and armor upgrades change the units; a sentry hallucinates what it is told.
 */
public class UpgradeGameTests implements FabricGameTest {
	@GameTest(template = ARENA, batch = "wildResearch", timeoutTicks = 40)
	public void aiBaseNeedsBurrowAndBlindDiggingStillHurts(GameTestHelper h) {
		clear(h);
		var level = h.getLevel();
		Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), h.absolutePos(new net.minecraft.core.BlockPos(34, 2, 34)));
		dev.starmine.hive.HiveSavedData.get(level.getServer()).add(base);
		StarUnit roach = TestArena.member(h, base, UnitKind.ROACH, 20, 20);
		roach.setNoAi(true);
		StarUnit marine = unit(h, UnitKind.MARINE, 24, 20, UUID.randomUUID()); marine.setNoAi(true);
		h.assertTrue("message.starmine.need_research".equals(Abilities.refusal(roach, Ability.BURROW)), "AI bypassed burrow research");
		Research.BURROW.apply(base);
		h.assertTrue(Abilities.refusal(roach, Ability.BURROW) == null, "research did not unlock burrow for AI");
		roach.setMode(Modes.BURROWED); roach.setCloaked(true);
		h.assertFalse(Cloak.seen(roach, marine), "blind opponent sees buried roach");
		float before = roach.getHealth();
		h.assertTrue(BurrowDamage.strike(marine, roach.blockPosition().below()) && roach.getHealth() < before, "blind ground strike did no damage");
		h.assertFalse(Cloak.seen(roach, marine), "blind damage revealed the roach");
		h.succeed();
	}

	@GameTest(template = ARENA, batch = "allResearch", timeoutTicks = 40)
	public void everyUpgradeHasATechGateAndAppliesOnlyItsNextLevel(GameTestHelper h) {
		for (Research what : Research.values()) {
			Hive base = new Hive(UUID.randomUUID(), h.getLevel().dimension().location().toString(), net.minecraft.core.BlockPos.ZERO);
			base.minerals = base.vespene = 100000;
			UnitKind building = java.util.Arrays.stream(UnitKind.values()).filter(what::at).findFirst().orElseThrow();
			base.race = building.race;
			if (what == Research.LAIR || what == Research.HIVE || what == Research.CENTRIFUGAL_HOOKS || what == Research.GLIAL_RECONSTITUTION
				|| what == Research.TUNNELING_CLAWS || what == Research.ADRENAL_GLANDS)
				h.assertTrue("message.starmine.need_tech".equals(what.refusal(base, building)), "missing tier requirement: " + what);
			for (UnitKind kind : UnitKind.values()) if (kind.race == base.race && kind.structure()) base.join(kind);
			base.tier = what == Research.LAIR ? 1 : what == Research.HIVE ? 2 : 3;
			h.assertTrue(what.refusal(base, building) == null, "fully funded/unlocked research refused: " + what);
			what.apply(base);
			if (what.once() || what == Research.LAIR || what == Research.HIVE)
				h.assertTrue("message.starmine.research_done".equals(what.refusal(base, building)), "research can repeat: " + what);
			if (what.leveled()) {
				h.assertTrue(Research.level(base, what) == 1, "incorrect level after applying " + what);
				what.apply(base); what.apply(base);
				h.assertTrue("message.starmine.research_done".equals(what.refusal(base, building)), "fourth level allowed: " + what);
			}
		}
		h.succeed();
	}

	@GameTest(template = ARENA, batch = "upgradeGates", timeoutTicks = 60)
	public void abilitiesWaitForTheirResearch(GameTestHelper helper) {
		clear(helper);
		StarUnit hatchery = unit(helper, UnitKind.HATCHERY, 34, 34, UUID.randomUUID());
		StarUnit wild = unit(helper, UnitKind.ZERGLING, 6, 40, null);
		helper.runAfterDelay(5, () -> {
			Hive base = TestArena.base(helper, hatchery);
			helper.assertTrue(base != null, "no base");
			StarUnit zergling = TestArena.member(helper, base, UnitKind.ZERGLING, 10, 10);
			helper.assertTrue("message.starmine.need_research".equals(Abilities.refusal(zergling, Ability.BURROW)),
				"a zergling burrows unresearched: " + Abilities.refusal(zergling, Ability.BURROW));
			helper.assertTrue("message.starmine.need_research".equals(Abilities.refusal(wild, Ability.BURROW)), "a lone wild zergling bypassed burrow research");
			helper.assertTrue(Research.of(Ability.BURROW, UnitKind.LURKER) == null, "a lurker needs burrow researched");
			helper.assertTrue(Research.of(Ability.CLOAK, UnitKind.BANSHEE) == Research.CLOAKING_FIELD
				&& Research.of(Ability.CLOAK, UnitKind.GHOST) == Research.PERSONAL_CLOAKING, "the cloaks need no research");
			Research.BURROW.apply(base);
			helper.assertTrue(Abilities.refusal(zergling, Ability.BURROW) == null, "a zergling cannot burrow researched: " + Abilities.refusal(zergling, Ability.BURROW));
			for (Research research : Research.values()) {
				helper.assertTrue(UnitKind.values().length > 0 && java.util.Arrays.stream(UnitKind.values()).anyMatch(research::at), research + " is researched nowhere");
				helper.assertTrue(research.ticks(1) > 0 && research.cost(1)[0] > 0, research + " is free");
			}
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "upgradeGreaterSpire", timeoutTicks = 80)
	public void theSpireGrowsIntoAGreaterSpire(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		StarUnit hatchery = unit(helper, UnitKind.HATCHERY, 34, 34, UUID.randomUUID());
		helper.runAfterDelay(5, () -> {
			Hive base = TestArena.base(helper, hatchery);
			helper.assertTrue(base != null, "no base");
			base.minerals = 1000;
			base.vespene = 1000;
			base.join(UnitKind.SPIRE);
			StarUnit spire = TestArena.member(helper, base, UnitKind.SPIRE, 12, 12);
			base.tier = 2;
			helper.assertTrue("message.starmine.need_tech".equals(Abilities.refusal(spire, Ability.GREATER_SPIRE)), "a greater spire without a hive");
			base.tier = 3;
			helper.assertTrue(!base.unlocked(UnitKind.BROOD_LORD), "brood lords before the greater spire");
			helper.assertTrue(Abilities.cast(spire, level, Ability.GREATER_SPIRE, null, null), "the spire does not grow: " + Abilities.refusal(spire, Ability.GREATER_SPIRE));
			helper.assertTrue(base.minerals == 900 && base.vespene == 850, "the greater spire is not paid: " + base.minerals + "/" + base.vespene);
			helper.assertTrue(spire.abilities.changingMode(), "the spire is not changing");
			Modes.finish(spire); // its 71 s at once
			helper.assertTrue(spire.mode() == Modes.GREATER, "the spire is not greater: " + spire.mode());
			helper.assertTrue(base.unlocked(UnitKind.BROOD_LORD), "no brood lords with a greater spire");
			helper.assertTrue(!Abilities.available(spire, Ability.GREATER_SPIRE), "a greater spire grows again");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "upgradeEffects", timeoutTicks = 60)
	public void upgradesChangeTheUnits(GameTestHelper helper) {
		clear(helper);
		StarUnit hatchery = unit(helper, UnitKind.HATCHERY, 34, 34, UUID.randomUUID());
		helper.runAfterDelay(5, () -> {
			Hive base = TestArena.base(helper, hatchery);
			StarUnit hydra = TestArena.member(helper, base, UnitKind.HYDRALISK, 10, 10);
			StarUnit ultra = TestArena.member(helper, base, UnitKind.ULTRALISK, 14, 10);
			hydra.refreshWeapon();
			double range = hydra.weapon().range();
			Research.GROOVED_SPINES.apply(base);
			hydra.refreshWeapon();
			helper.assertTrue(Math.abs(hydra.weapon().range() - range - UnitKind.BLOCKS_PER_UNIT) < 1e-6,
				"grooved spines: range " + range + " -> " + hydra.weapon().range());
			helper.assertTrue(Research.speedFactor(base, UnitKind.HYDRALISK) == 1, "muscular augments before their research");
			Research.MUSCULAR_AUGMENTS.apply(base);
			helper.assertTrue(Research.speedFactor(base, UnitKind.HYDRALISK) > 1.2, "no muscular augments");
			Research.CHITINOUS_PLATING.apply(base);
			helper.assertTrue(Research.extraArmor(base, ultra.kind) == 2, "no chitinous plating");
			helper.assertTrue(Research.changeFactor(base, UnitKind.LURKER) == 1, "adaptive talons before their research");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "upgradeHallucination", timeoutTicks = 60)
	public void aSentryHallucinatesItsChoice(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		StarUnit sentry = unit(helper, UnitKind.SENTRY, 24, 24, UUID.randomUUID());
		sentry.setNoAi(true);
		helper.runAfterDelay(2, () -> {
			sentry.setEnergy(200);
			sentry.abilities.hallucinate = UnitKind.COLOSSUS;
			helper.assertTrue(Abilities.cast(sentry, level, Ability.HALLUCINATION, null, null), "no hallucination: " + Abilities.refusal(sentry, Ability.HALLUCINATION));
			List<StarUnit> fakes = level.getEntitiesOfClass(StarUnit.class, sentry.getBoundingBox().inflate(8), unit -> Status.has(unit, Status.HALLUCINATION));
			helper.assertTrue(fakes.size() == 1 && fakes.get(0).kind == UnitKind.COLOSSUS, "hallucinated " + fakes.stream().map(unit -> unit.kind).toList());
			fakes.forEach(StarUnit::discard);
			helper.succeed();
		});
	}
}
