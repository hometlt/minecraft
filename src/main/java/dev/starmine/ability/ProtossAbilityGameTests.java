package dev.starmine.ability;

import dev.starmine.block.StarMineBlocks;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.race.ProtossRace;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.clear;
import static dev.starmine.ability.TestArena.point;
import static dev.starmine.ability.TestArena.unit;

/**
 * Protoss abilities (owner 14.10, docs/ABILITIES.md), each checked in the world. The guardian shield,
 * barrier, shield battery and interceptors are in {@link MechanicsGameTests}.
 */
public class ProtossAbilityGameTests implements FabricGameTest {
	private static int count(GameTestHelper helper, UnitKind kind) {
		return helper.getLevel().getEntitiesOfClass(StarUnit.class, new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(80),
			unit -> unit.kind == kind && unit.isAlive()).size();
	}

	private static int fieldBlocks(GameTestHelper helper) {
		int count = 0;
		for (BlockPos rel : BlockPos.betweenClosed(0, 2, 0, 47, 6, 47))
			if (helper.getLevel().getBlockState(helper.absolutePos(rel)).is(StarMineBlocks.FORCE_FIELD)) count++;
		return count;
	}

	@GameTest(template = ARENA, batch = "protossCharge", timeoutTicks = 100)
	public void zealotChargesIn(GameTestHelper helper) {
		clear(helper);
		StarUnit zealot = unit(helper, UnitKind.ZEALOT, 10, 24, UUID.randomUUID());
		StarUnit marine = unit(helper, UnitKind.MARINE, 20, 24, null);
		marine.setNoAi(true);
		ServerLevel level = helper.getLevel();
		double[] before = new double[1];
		helper.runAfterDelay(2, () -> {
			before[0] = zealot.distanceTo(marine);
			helper.assertTrue(Abilities.cast(zealot, level, Ability.CHARGE, marine, null), "no charge");
			// owner 16.10: a run, not a jump
			helper.assertTrue(zealot.distanceTo(marine) > before[0] - 2, "the zealot jumped: " + zealot.distanceTo(marine));
			helper.assertTrue(Status.has(zealot, Status.CHARGING), "not charging");
		});
		helper.runAfterDelay(2 + ProtossAbilities.CHARGE_TICKS, () -> {
			helper.assertTrue(marine.getHealth() < marine.getMaxHealth(), "the charge did not strike; " + zealot.distanceTo(marine) + " off");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "protossBlink", timeoutTicks = 100)
	public void stalkerBlinksOverAWall(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		for (BlockPos rel : BlockPos.betweenClosed(13, 2, 0, 13, 6, 47)) level.setBlock(helper.absolutePos(rel), Blocks.STONE.defaultBlockState(), 2);
		StarUnit stalker = unit(helper, UnitKind.STALKER, 9, 24, UUID.randomUUID());
		stalker.setNoAi(true);
		helper.runAfterDelay(2, () -> {
			helper.assertTrue(Abilities.cast(stalker, level, Ability.BLINK, null, point(helper, 16, 24)), "no blink");
			helper.assertTrue(TestArena.rel(helper, stalker).x > 14, "still behind the wall: " + TestArena.where(helper, stalker));
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "protossForceField", timeoutTicks = 400)
	public void forceFieldStandsElevenSeconds(GameTestHelper helper) {
		clear(helper);
		StarUnit sentry = unit(helper, UnitKind.SENTRY, 10, 24, UUID.randomUUID());
		sentry.setNoAi(true);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> {
			helper.assertTrue(Abilities.cast(sentry, level, Ability.FORCE_FIELD, null, point(helper, 16, 24)), "no force field");
			helper.assertTrue(fieldBlocks(helper) >= 9, "the field has " + fieldBlocks(helper) + " blocks");
		});
		helper.runAfterDelay(200, () -> helper.assertTrue(fieldBlocks(helper) > 0, "the field went before 11 s"));
		helper.runAfterDelay(240, () -> {
			helper.assertTrue(fieldBlocks(helper) == 0, "the field is still there after 11 s");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "protossStorm", timeoutTicks = 200)
	public void stormHurtsEveryoneInside(GameTestHelper helper) {
		clear(helper);
		UUID player = UUID.randomUUID();
		StarUnit templar = unit(helper, UnitKind.HIGH_TEMPLAR, 10, 24, player);
		StarUnit zergling = unit(helper, UnitKind.HYDRALISK, 17, 24, null), own = unit(helper, UnitKind.ZEALOT, 17, 25, player);
		StarUnit outside = unit(helper, UnitKind.HYDRALISK, 17, 32, null);
		for (StarUnit unit : List.of(templar, zergling, own, outside)) unit.setNoAi(true);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> {
			templar.setEnergy(75);
			helper.assertTrue(Abilities.cast(templar, level, Ability.PSIONIC_STORM, null, point(helper, 17, 24.5)), "no storm");
		});
		helper.runAfterDelay(80, () -> {
			float lost = zergling.getMaxHealth() - zergling.getHealth();
			helper.assertTrue(lost >= ProtossAbilities.STORM_DAMAGE * UnitKind.SCALE - 0.5, "the hydralisk lost " + lost);
			helper.assertTrue(own.shield() < own.kind.shields, "the storm spared its own side");
			helper.assertTrue(outside.getHealth() == outside.getMaxHealth(), "the storm reached outside");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "protossArchon", timeoutTicks = 400)
	public void twoTemplarMergeIntoAnArchon(GameTestHelper helper) {
		clear(helper);
		UUID player = UUID.randomUUID();
		StarUnit a = unit(helper, UnitKind.HIGH_TEMPLAR, 10, 24, player), b = unit(helper, UnitKind.HIGH_TEMPLAR, 12, 24, player);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> {
			helper.assertTrue(Abilities.order(level, List.of(a, b), Ability.ARCHON_WARP, null, null), "no archon warp");
			helper.assertTrue(Status.has(a, Status.MERGING) && Status.has(b, Status.MERGING), "they do not merge");
			helper.succeedWhen(() -> {
				helper.assertTrue(count(helper, UnitKind.ARCHON) == 1, "no archon");
				helper.assertTrue(count(helper, UnitKind.HIGH_TEMPLAR) == 0, "the templar are still there");
			});
		});
	}

	@GameTest(template = ARENA, batch = "protossGraviton", timeoutTicks = 300)
	public void gravitonBeamLiftsAUnit(GameTestHelper helper) {
		clear(helper);
		StarUnit phoenix = unit(helper, UnitKind.PHOENIX, 10, 24, UUID.randomUUID());
		phoenix.setNoAi(true);
		StarUnit tank = unit(helper, UnitKind.SIEGE_TANK, 13, 24, null);
		StarUnit ultra = unit(helper, UnitKind.ULTRALISK, 13, 30, null);
		ultra.setNoAi(true);
		ServerLevel level = helper.getLevel();
		double floor = point(helper, 0, 0).y;
		helper.runAfterDelay(2, () -> {
			helper.assertTrue(!Abilities.cast(phoenix, level, Ability.GRAVITON_BEAM, ultra, null), "a massive ultralisk lifted");
			helper.assertTrue(Abilities.cast(phoenix, level, Ability.GRAVITON_BEAM, tank, null), "no graviton beam");
		});
		helper.runAfterDelay(60, () -> {
			helper.assertTrue(tank.getY() > floor + 1, "the tank is not up: " + (tank.getY() - floor));
			helper.assertTrue(tank.weapon().range() == 0 && tank.getAttributeValue(Attributes.MOVEMENT_SPEED) == 0, "a lifted tank can fight or move");
			helper.assertTrue(phoenix.weapon().range() == 0, "the phoenix fights while it holds the beam");
		});
		helper.runAfterDelay(220, () -> {
			helper.assertTrue(!Status.has(tank, Status.LIFTED) && !tank.isNoGravity(), "the lift lasts more than 7 s");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "protossPulsar", timeoutTicks = 300)
	public void oracleFightsOnlyWithItsBeamOn(GameTestHelper helper) {
		clear(helper);
		StarUnit oracle = unit(helper, UnitKind.ORACLE, 10, 24, UUID.randomUUID());
		StarUnit drone = unit(helper, UnitKind.DRONE, 13, 24, null);
		drone.setNoAi(true);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(60, () -> {
			helper.assertTrue(drone.getHealth() == drone.getMaxHealth(), "the oracle attacked with its beam off");
			helper.assertTrue(Abilities.cast(oracle, level, Ability.PULSAR_BEAM, null, null), "no beam");
			helper.assertTrue(oracle.mode() == Modes.PULSAR, "the beam is not on");
			oracle.commandAttack(drone);
			helper.succeedWhen(() -> {
				helper.assertTrue(!drone.isAlive() || drone.getHealth() < drone.getMaxHealth(), "the beam does not hit; target " + oracle.getTarget()
					+ " goals " + oracle.goalsRunning() + " weapon " + oracle.weapon() + " at " + TestArena.where(helper, oracle));
				helper.assertTrue(oracle.energy() < 25, "the beam costs no upkeep");
			});
		});
	}

	@GameTest(template = ARENA, batch = "protossTransfer", timeoutTicks = 300)
	public void adeptTransfersAfterSevenSeconds(GameTestHelper helper) {
		clear(helper);
		StarUnit adept = unit(helper, UnitKind.ADEPT, 10, 24, UUID.randomUUID());
		adept.setNoAi(true);
		ServerLevel level = helper.getLevel();
		Vec3 to = point(helper, 30, 24);
		helper.runAfterDelay(2, () -> helper.assertTrue(Abilities.cast(adept, level, Ability.PSIONIC_TRANSFER, null, to), "no transfer"));
		helper.runAfterDelay(120, () -> helper.assertTrue(adept.position().distanceTo(to) > 5, "it went before 7 s"));
		helper.runAfterDelay(160, () -> {
			helper.assertTrue(adept.position().distanceTo(to) < 2, "it is not at the shade: " + adept.position().distanceTo(to));
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "protossHallucination", timeoutTicks = 100)
	public void hallucinationsDealNoDamage(GameTestHelper helper) {
		clear(helper);
		StarUnit sentry = unit(helper, UnitKind.SENTRY, 10, 24, UUID.randomUUID());
		sentry.setNoAi(true);
		StarUnit marine = unit(helper, UnitKind.MARINE, 11, 26, null);
		marine.setNoAi(true);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> {
			sentry.setEnergy(75);
			helper.assertTrue(Abilities.cast(sentry, level, Ability.HALLUCINATION, null, null), "no hallucination");
			helper.assertTrue(count(helper, UnitKind.ZEALOT) == 2, "no illusions");
		});
		helper.runAfterDelay(80, () -> {
			helper.assertTrue(marine.getHealth() == marine.getMaxHealth(), "an illusion hurt the marine");
			StarUnit illusion = level.getEntitiesOfClass(StarUnit.class, new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(80),
				unit -> unit.kind == UnitKind.ZEALOT).get(0);
			float before = illusion.shield();
			illusion.invulnerableTime = 0;
			illusion.hurt(marine.attackSource(), 2);
			helper.assertTrue(before - illusion.shield() >= 3.9, "an illusion takes no double damage");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "protossFeedback", timeoutTicks = 100)
	public void feedbackBurnsEnergy(GameTestHelper helper) {
		clear(helper);
		StarUnit templar = unit(helper, UnitKind.HIGH_TEMPLAR, 10, 24, UUID.randomUUID());
		StarUnit ghost = unit(helper, UnitKind.GHOST, 18, 24, null);
		templar.setNoAi(true);
		ghost.setNoAi(true);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> {
			templar.setEnergy(50);
			ghost.setEnergy(100);
			helper.assertTrue(Abilities.cast(templar, level, Ability.FEEDBACK, ghost, null), "no feedback");
			helper.assertTrue(ghost.energy() == 0, "energy left");
			helper.assertTrue(Math.abs(ghost.getMaxHealth() - ghost.getHealth() - 50 * UnitKind.SCALE) < 0.1, "feedback did " + (ghost.getMaxHealth() - ghost.getHealth()));
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "protossNova", timeoutTicks = 100)
	public void novaBurstsAfterTwoSeconds(GameTestHelper helper) {
		clear(helper);
		StarUnit disruptor = unit(helper, UnitKind.DISRUPTOR, 10, 24, UUID.randomUUID());
		StarUnit roach = unit(helper, UnitKind.ROACH, 18, 24, null);
		disruptor.setNoAi(true);
		roach.setNoAi(true);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> helper.assertTrue(Abilities.cast(disruptor, level, Ability.PURIFICATION_NOVA, null, roach.position()), "no nova"));
		helper.runAfterDelay(30, () -> helper.assertTrue(roach.getHealth() == roach.getMaxHealth(), "the nova burst early"));
		helper.runAfterDelay(50, () -> {
			helper.assertTrue(!roach.isAlive() || roach.getMaxHealth() - roach.getHealth() >= ProtossAbilities.NOVA_DAMAGE * UnitKind.SCALE - 1, "the nova missed");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "protossPrism", timeoutTicks = 100)
	public void phasingPrismPowersBuildings(GameTestHelper helper) {
		clear(helper);
		StarUnit prism = unit(helper, UnitKind.WARP_PRISM, 10, 24, UUID.randomUUID());
		ServerLevel level = helper.getLevel();
		Vec3 spot = point(helper, 12, 24);
		helper.runAfterDelay(2, () -> {
			helper.assertTrue(!ProtossRace.poweredAt(level, spot.x, spot.y, spot.z, null), "powered before");
			helper.assertTrue(Abilities.cast(prism, level, Ability.PHASING_MODE, null, null), "no phasing");
		});
		helper.runAfterDelay(30, () -> {
			helper.assertTrue(prism.mode() == Modes.PHASING && prism.getAttributeValue(Attributes.FLYING_SPEED) == 0, "not phasing and still");
			helper.assertTrue(ProtossRace.poweredAt(level, spot.x, spot.y, spot.z, null), "a phasing prism gives no power");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "protossRevelation", timeoutTicks = 100)
	public void revelationShowsTheCloaked(GameTestHelper helper) {
		clear(helper);
		StarUnit oracle = unit(helper, UnitKind.ORACLE, 10, 24, UUID.randomUUID());
		StarUnit templar = unit(helper, UnitKind.DARK_TEMPLAR, 16, 24, null);
		oracle.setNoAi(true);
		templar.setNoAi(true);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(15, () -> {
			helper.assertTrue(templar.cloaked() && !Cloak.seen(templar, oracle), "the dark templar is seen before");
			helper.assertTrue(Abilities.cast(oracle, level, Ability.REVELATION, null, templar.position()), "no revelation");
			helper.assertTrue(Cloak.seen(templar, oracle) && Status.has(templar, Status.REVEALED), "the dark templar is not revealed");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "protossStasis", timeoutTicks = 400)
	public void stasisWardFreezesWhoComes(GameTestHelper helper) {
		clear(helper);
		StarUnit oracle = unit(helper, UnitKind.ORACLE, 10, 24, UUID.randomUUID());
		oracle.setNoAi(true);
		StarUnit zergling = unit(helper, UnitKind.ZERGLING, 30, 24, null);
		zergling.setNoAi(true);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> helper.assertTrue(Abilities.cast(oracle, level, Ability.STASIS_WARD, null, point(helper, 12, 24)), "no ward"));
		helper.runAfterDelay(40, () -> {
			helper.assertTrue(!Status.has(zergling, Status.STASIS), "frozen from afar");
			zergling.teleportTo(oracle.getX() + 2, zergling.getY(), zergling.getZ());
		});
		helper.runAfterDelay(70, () -> {
			helper.assertTrue(Status.has(zergling, Status.STASIS), "the ward did not freeze the zergling");
			float health = zergling.getHealth();
			zergling.hurt(oracle.attackSource(), 3);
			helper.assertTrue(zergling.getHealth() == health, "a frozen unit takes damage");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "protossTempest", timeoutTicks = 300)
	public void tempestDisintegratesBuildings(GameTestHelper helper) {
		clear(helper);
		StarUnit tempest = unit(helper, UnitKind.TEMPEST, 8, 24, UUID.randomUUID());
		tempest.setNoAi(true);
		StarUnit depot = unit(helper, UnitKind.SUPPLY_DEPOT, 20, 24, null);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> helper.assertTrue(Abilities.cast(tempest, level, Ability.DISINTEGRATION, null, depot.position()), "no disintegration"));
		helper.runAfterDelay(100, () -> helper.assertTrue(depot.getHealth() == depot.getMaxHealth(), "it struck before 7 s"));
		helper.runAfterDelay(160, () -> {
			helper.assertTrue(!depot.isAlive() || depot.getMaxHealth() - depot.getHealth() >= 100, "the depot lost " + (depot.getMaxHealth() - depot.getHealth()));
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "protossRecall", timeoutTicks = 100)
	public void massRecallBringsTheArmyHome(GameTestHelper helper) {
		clear(helper);
		StarUnit nexus = unit(helper, UnitKind.NEXUS, 36, 36, UUID.randomUUID());
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(5, () -> {
			Hive base = TestArena.base(helper, nexus);
			helper.assertTrue(base != null, "no base");
			StarUnit mothership = TestArena.member(helper, base, UnitKind.MOTHERSHIP, 8, 8);
			StarUnit zealot = TestArena.member(helper, base, UnitKind.ZEALOT, 10, 10);
			helper.assertTrue(Abilities.cast(mothership, level, Ability.MASS_RECALL, null, null), "no recall");
			// the mothership comes back at its hover height (20 blocks up): home is measured on the ground
			helper.assertTrue(zealot.distanceTo(nexus) < 16 && Abilities.horizontal(mothership.position(), nexus.position()) < 20,
				"not recalled: zealot " + zealot.distanceTo(nexus) + ", mothership " + Abilities.horizontal(mothership.position(), nexus.position()));
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "protossTimeWarp", timeoutTicks = 100)
	public void timeWarpSlows(GameTestHelper helper) {
		clear(helper);
		StarUnit mothership = unit(helper, UnitKind.MOTHERSHIP, 8, 24, UUID.randomUUID());
		mothership.setNoAi(true);
		StarUnit zergling = unit(helper, UnitKind.ZERGLING, 16, 24, null);
		zergling.setNoAi(true);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> {
			mothership.setEnergy(75);
			helper.assertTrue(Abilities.cast(mothership, level, Ability.TIME_WARP, null, zergling.position()), "no time warp");
		});
		helper.runAfterDelay(20, () -> {
			helper.assertTrue(Status.has(zergling, Status.SLOW), "not slowed");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "protossChrono", timeoutTicks = 200)
	public void chronoBoostSpeedsTraining(GameTestHelper helper) {
		clear(helper);
		StarUnit nexus = unit(helper, UnitKind.NEXUS, 30, 30, UUID.randomUUID());
		ServerLevel level = helper.getLevel();
		StarUnit[] gates = new StarUnit[2];
		helper.runAfterDelay(5, () -> {
			Hive base = TestArena.base(helper, nexus);
			helper.assertTrue(base != null, "no base");
			base.minerals = 1000;
			base.vespene = 1000;
			for (UnitKind kind : new UnitKind[] {UnitKind.PYLON, UnitKind.GATEWAY}) base.join(kind);
			TestArena.member(helper, base, UnitKind.PYLON, 10, 14);
			gates[0] = TestArena.member(helper, base, UnitKind.GATEWAY, 6, 10);
			gates[1] = TestArena.member(helper, base, UnitKind.GATEWAY, 14, 10);
			nexus.setEnergy(50);
			helper.assertTrue(Abilities.cast(nexus, level, Ability.CHRONO_BOOST, gates[1], null), "no chrono boost");
			helper.assertTrue(gates[0].train(UnitKind.ZEALOT) && gates[1].train(UnitKind.ZEALOT), "the gateways did not take the zealots");
		});
		helper.runAfterDelay(105, () -> {
			helper.assertTrue(gates[1].progress() > gates[0].progress() * 1.3, "chrono " + gates[1].progress() + " against " + gates[0].progress());
			helper.succeed();
		});
	}
}
