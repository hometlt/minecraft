package dev.starmine.ability;

import dev.starmine.block.StarMineBlocks;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.clear;
import static dev.starmine.ability.TestArena.point;
import static dev.starmine.ability.TestArena.unit;

/**
 * The rest of SC2's abilities (owner 19.10, {@link FieldAbilities}): each cast and its effect checked —
 * the shroud's half damage from the air, a crawler's uproot, crawl and root on creep, an orbital's supply
 * drop, contaminate, oversight and surveillance, an overlord's creep.
 */
public class FieldAbilityGameTests implements FabricGameTest {
	@GameTest(template = ARENA, batch = "fieldShroud", timeoutTicks = 100)
	public void shroudHalvesAirAttacks(GameTestHelper helper) {
		clear(helper);
		UUID player = UUID.randomUUID();
		StarUnit infestor = unit(helper, UnitKind.INFESTOR, 10, 24, player);
		StarUnit shrouded = unit(helper, UnitKind.MARINE, 14, 24, player), bare = unit(helper, UnitKind.MARINE, 40, 24, player);
		StarUnit mutalisk = unit(helper, UnitKind.MUTALISK, 30, 10, null);
		for (StarUnit unit : new StarUnit[] {infestor, shrouded, bare, mutalisk}) unit.setNoAi(true);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> {
			infestor.setEnergy(100);
			helper.assertTrue(Abilities.cast(infestor, level, Ability.MICROBIAL_SHROUD, null, shrouded.position()), "no shroud");
			helper.assertTrue(infestor.energy() == 25, "the shroud costs 75");
		});
		helper.runAfterDelay(5, () -> {
			helper.assertTrue(Status.has(shrouded, Status.SHROUDED) && !Status.has(bare, Status.SHROUDED), "the shroud is not where it was cast");
			float before = shrouded.getHealth(), bareBefore = bare.getHealth();
			shrouded.hurt(mutalisk.attackSource(), 4);
			bare.hurt(mutalisk.attackSource(), 4);
			float taken = before - shrouded.getHealth(), full = bareBefore - bare.getHealth();
			helper.assertTrue(full > 0 && Math.abs(taken - full / 2) < 0.01, "shrouded took " + taken + " of " + full);
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "fieldCrawler", timeoutTicks = 500)
	public void crawlerUprootsCrawlsAndRootsOnCreep(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		for (BlockPos rel : BlockPos.betweenClosed(20, 1, 18, 32, 1, 30)) level.setBlock(helper.absolutePos(rel), StarMineBlocks.THICK_CREEP.defaultBlockState(), 2);
		StarUnit spine = unit(helper, UnitKind.SPINE_CRAWLER, 10, 24, UUID.randomUUID());
		double[] start = new double[1];
		helper.runAfterDelay(2, () -> {
			start[0] = spine.getX();
			helper.assertTrue(Abilities.cast(spine, level, Ability.UPROOT, null, null), "no uproot");
		});
		helper.runAfterDelay(40, () -> {
			helper.assertTrue(FieldAbilities.uprooted(spine) && spine.weapon().damage() == 0, "not uprooted, or it still fights");
			helper.assertTrue("message.starmine.need_creep".equals(Abilities.refusal(spine, Ability.UPROOT)), "it may root off creep");
			spine.command(StarUnit.Order.MOVE, point(helper, 26, 24));
		});
		helper.runAfterDelay(380, () -> {
			helper.assertTrue(spine.getX() - start[0] > 8, "it crawled " + (spine.getX() - start[0]));
			helper.assertTrue(Abilities.refusal(spine, Ability.UPROOT) == null, "it may not root on creep");
			helper.assertTrue(Abilities.cast(spine, level, Ability.UPROOT, null, null), "no root");
		});
		helper.runAfterDelay(480, () -> {
			helper.assertTrue(spine.mode() == Modes.NORMAL && spine.weapon().damage() > 0, "not rooted and armed again");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "fieldSupply", timeoutTicks = 100)
	public void supplyDropGivesADepotEightMore(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		StarUnit cc = unit(helper, UnitKind.COMMAND_CENTER, 34, 34, UUID.randomUUID());
		int[] supplied = new int[1];
		StarUnit[] dead = new StarUnit[1];
		helper.runAfterDelay(5, () -> {
			Hive base = TestArena.base(helper, cc);
			helper.assertTrue(base != null, "no base");
			StarUnit depot = TestArena.member(helper, base, UnitKind.SUPPLY_DEPOT, 10, 10);
			base.join(UnitKind.SUPPLY_DEPOT); // a building counts in its base once built
			dead[0] = depot;
			helper.assertTrue(!Abilities.available(cc, Ability.SUPPLY_DROP), "a plain command center drops supplies");
			cc.setMode(Modes.ORBITAL);
			cc.setEnergy(100);
			int cap = base.supplyCap(UnitKind.Race.TERRAN);
			helper.assertTrue(Abilities.cast(cc, level, Ability.SUPPLY_DROP, depot, null), "no supply drop");
			helper.assertTrue(base.supplyCap(UnitKind.Race.TERRAN) == cap + FieldAbilities.SUPPLY_DROP, "the cap is " + base.supplyCap(UnitKind.Race.TERRAN) + " not " + (cap + 8));
			helper.assertTrue(!Abilities.cast(cc, level, Ability.SUPPLY_DROP, depot, null), "a depot supplied twice");
			supplied[0] = base.supplyCap(UnitKind.Race.TERRAN);
			depot.remove(net.minecraft.world.entity.Entity.RemovalReason.KILLED); // its death over (a building falls for a while)
		});
		helper.runAfterDelay(40, () -> { // gone after its death
			Hive base = TestArena.base(helper, cc);
			helper.assertTrue(base.supplyCap(UnitKind.Race.TERRAN) == supplied[0] - FieldAbilities.SUPPLY_DROP - UnitKind.SUPPLY_DEPOT.economy().supply(),
				"a dead depot's drop still counts: " + base.supplyCap(UnitKind.Race.TERRAN) + " of " + supplied[0] + (dead[0].isRemoved() ? "" : ", the depot is still there"));
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "fieldContaminate", timeoutTicks = 100)
	public void contaminateStopsABuilding(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		UUID player = UUID.randomUUID();
		StarUnit overseer = unit(helper, UnitKind.OVERSEER, 23, 24, player);
		StarUnit own = unit(helper, UnitKind.SPAWNING_POOL, 10, 10, player), foe = unit(helper, UnitKind.BARRACKS, 26, 24, null);
		overseer.setNoAi(true);
		helper.runAfterDelay(2, () -> {
			overseer.setEnergy(200);
			helper.assertTrue(!Abilities.cast(overseer, level, Ability.CONTAMINATE, own, null), "it contaminates its own");
			helper.assertTrue(Abilities.cast(overseer, level, Ability.CONTAMINATE, foe, null), "no contaminate");
			helper.assertTrue(FieldAbilities.contaminated(foe) && Status.left(foe, Status.CONTAMINATED) > 500, "not contaminated for 30 s");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "fieldOversight", timeoutTicks = 100)
	public void oversightAndSurveillanceStopAndLookFarther(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		UUID player = UUID.randomUUID();
		StarUnit overseer = unit(helper, UnitKind.OVERSEER, 20, 24, player), observer = unit(helper, UnitKind.OBSERVER, 26, 24, player);
		helper.runAfterDelay(2, () -> {
			helper.assertTrue(Abilities.cast(overseer, level, Ability.OVERSIGHT, null, null), "no oversight");
			helper.assertTrue(Abilities.cast(observer, level, Ability.SURVEILLANCE, null, null), "no surveillance");
		});
		helper.runAfterDelay(30, () -> {
			helper.assertTrue(overseer.mode() == Modes.OVERSIGHT && observer.mode() == Modes.SURVEILLANCE, "not in their modes");
			helper.assertTrue(overseer.getAttributeValue(Attributes.MOVEMENT_SPEED) == 0 && observer.getAttributeValue(Attributes.MOVEMENT_SPEED) == 0,
				"they still move");
			helper.assertTrue(Modes.sightFactor(overseer.mode()) == 1.5 && Modes.sightFactor(observer.mode()) == 1.25, "they see no farther");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "fieldCreep", timeoutTicks = 400)
	public void overlordDripsCreep(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		StarUnit overlord = unit(helper, UnitKind.OVERLORD, 24, 24, null);
		overlord.setNoAi(true);
		helper.runAfterDelay(2, () -> helper.assertTrue(Abilities.cast(overlord, level, Ability.GENERATE_CREEP, null, null), "no creep"));
		helper.runAfterDelay(300, () -> {
			helper.assertTrue(FieldAbilities.creeping(overlord), "not generating creep");
			int creep = 0;
			for (BlockPos rel : BlockPos.betweenClosed(18, 1, 18, 30, 3, 30))
				if (StarMineBlocks.isCreep(level.getBlockState(helper.absolutePos(rel)))) creep++;
			helper.assertTrue(creep >= 5, "only " + creep + " creep under the overlord");
			helper.assertTrue(dev.starmine.creep.CreepSources.covered(level, helper.absolutePos(new BlockPos(24, 2, 24))), "its creep is not fed");
			helper.succeed();
		});
	}

	/** Owner 19.10, SC2: a thor, a queen, a battlecruiser and a tempest fight flyers with weapons of their own; splash stays on its layer. */
	@GameTest(template = ARENA, batch = "fieldAirWeapons", timeoutTicks = 100)
	public void airWeaponsAreTheirOwn(GameTestHelper helper) {
		clear(helper);
		StarUnit thor = unit(helper, UnitKind.THOR, 6, 24, UUID.randomUUID()), queen = unit(helper, UnitKind.QUEEN, 6, 30, UUID.randomUUID());
		StarUnit tempest = unit(helper, UnitKind.TEMPEST, 6, 36, UUID.randomUUID()), liberator = unit(helper, UnitKind.LIBERATOR, 6, 12, UUID.randomUUID());
		StarUnit mutalisk = unit(helper, UnitKind.MUTALISK, 20, 24, null), other = unit(helper, UnitKind.MUTALISK, 20.5, 24.5, null);
		StarUnit zergling = unit(helper, UnitKind.ZERGLING, 20, 24, null), ultralisk = unit(helper, UnitKind.ULTRALISK, 30, 30, null);
		StarUnit cruiser = unit(helper, UnitKind.BATTLECRUISER, 30, 40, null);
		for (StarUnit unit : new StarUnit[] {thor, queen, tempest, liberator, mutalisk, other, zergling, ultralisk, cruiser}) unit.setNoAi(true);
		helper.runAfterDelay(3, () -> {
			helper.assertTrue(thor.weaponAgainst(zergling).damage() == UnitKind.THOR.damage, "the thor's hammers are not its ground weapon");
			Modes.Weapon javelins = thor.weaponAgainst(mutalisk);
			helper.assertTrue(javelins.range() > UnitKind.THOR.range && javelins.splash() > 0 && javelins.damage() < UnitKind.THOR.damage, "no javelins: " + javelins);
			helper.assertTrue(Passives.bonus(thor, mutalisk) == 24 * UnitKind.SCALE && Passives.bonus(thor, zergling) == 0, "the javelins' bonus against light");
			helper.assertTrue(queen.weaponAgainst(mutalisk).range() > queen.weaponAgainst(zergling).range(), "the queen's acid spines reach no farther");
			helper.assertTrue(tempest.weaponAgainst(cruiser).range() > tempest.weaponAgainst(ultralisk).range(), "the tempest's anti-air reaches no farther");
			helper.assertTrue(Passives.bonus(tempest, cruiser) > 0 && Passives.bonus(tempest, ultralisk) == 0, "the tempest's +22 against massive belongs to its anti-air");
			float muta = other.getHealth(), ling = zergling.getHealth();
			liberator.performRangedAttack(mutalisk, 1);
			helper.assertTrue(other.getHealth() < muta, "the liberator's rockets did not spread to the other flyer");
			helper.assertTrue(zergling.getHealth() == ling, "the liberator's splash hit the ground");
			helper.succeed();
		});
	}

	/** Owner 19.10: an oracle's stasis ward is saved, and armed again (by a stand-in of its side) waits for its foe without its oracle. */
	@GameTest(template = ARENA, batch = "fieldWard", timeoutTicks = 200)
	public void stasisWardOutlastsItsOracle(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		StarUnit oracle = unit(helper, UnitKind.ORACLE, 10, 24, UUID.randomUUID());
		oracle.setNoAi(true);
		int before = StasisWards.count(level);
		helper.runAfterDelay(2, () -> {
			oracle.setEnergy(100);
			helper.assertTrue(Abilities.cast(oracle, level, Ability.STASIS_WARD, null, point(helper, 14, 24)), "no ward");
			helper.assertTrue(StasisWards.count(level) == before + 1, "the ward is not saved");
			oracle.discard();
			StasisWards.rearm(level); // as after a restart
		});
		StarUnit[] ling = new StarUnit[1];
		helper.runAfterDelay(20, () -> {
			ling[0] = unit(helper, UnitKind.ZERGLING, 14, 24, null);
			ling[0].setNoAi(true);
		});
		helper.runAfterDelay(60, () -> {
			helper.assertTrue(Status.has(ling[0], Status.STASIS), "the ward did not hold the zergling");
			helper.assertTrue(StasisWards.count(level) == before, "a spent ward is still saved");
			helper.succeed();
		});
	}

	/** Owner 19.10, SC2: a flying terran building patrols back and forth. */
	@GameTest(template = ARENA, batch = "fieldPatrol", timeoutTicks = 200)
	public void flyingBuildingPatrols(GameTestHelper helper) {
		clear(helper);
		StarUnit barracks = unit(helper, UnitKind.BARRACKS, 12, 24, UUID.randomUUID());
		net.minecraft.world.phys.Vec3[] start = new net.minecraft.world.phys.Vec3[1];
		boolean[] back = new boolean[1];
		helper.runAfterDelay(2, () -> {
			barracks.setMode(Modes.AIRBORNE);
			start[0] = barracks.position();
			barracks.command(StarUnit.Order.PATROL, point(helper, 20, 24));
		});
		helper.onEachTick(() -> { // heading back to where it set out, still patrolling
			if (start[0] != null && barracks.order() == StarUnit.Order.PATROL && barracks.orderPos() != null
				&& Abilities.horizontal(barracks.orderPos(), start[0]) < 1) back[0] = true;
		});
		helper.runAfterDelay(150, () -> {
			helper.assertTrue(back[0], "it never headed back: " + barracks.order() + " " + barracks.orderPos() + " at " + barracks.position());
			helper.succeed();
		});
	}
}
