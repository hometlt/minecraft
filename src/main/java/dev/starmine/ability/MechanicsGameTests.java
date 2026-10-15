package dev.starmine.ability;

import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.possess.ModuleItem;
import dev.starmine.possess.Possession;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.clear;
import static dev.starmine.ability.TestArena.point;
import static dev.starmine.ability.TestArena.unit;

/**
 * The common ability mechanics of stage A (owner 14.10, docs/CLOUD_TASK.md): timed states (speed,
 * saved with the unit), burrowing (cloaked, still, found by an overseer), the lurker's weapon only
 * burrowed, passives on a hit (concussive shells), on damage (guardian shield, barrier) and every tick
 * (shield battery, interceptors), the engine's refusals (energy, health, cooldown), a spell ordered out
 * of reach (the caster walks up and casts) and abilities cast as modules by a player who plays as a unit.
 */
public class MechanicsGameTests implements FabricGameTest {
	private static double speed(StarUnit unit) {
		return unit.getAttributeValue(Attributes.MOVEMENT_SPEED);
	}

	@GameTest(template = ARENA, batch = "mechStatus", timeoutTicks = 100)
	public void statusChangesSpeedAndIsSaved(GameTestHelper helper) {
		clear(helper);
		StarUnit marine = unit(helper, UnitKind.MARINE, 10, 24, UUID.randomUUID());
		helper.runAfterDelay(3, () -> {
			double normal = speed(marine);
			Status.apply(marine, Status.STIM, 200);
			helper.assertTrue(Math.abs(speed(marine) - normal * 1.5) < 1e-6, "stim speed " + speed(marine) + " against " + normal);
			helper.assertTrue(marine.weapon().interval() < UnitKind.MARINE.interval, "stim does not quicken the weapon");
			CompoundTag tag = new CompoundTag();
			marine.saveWithoutId(tag);
			StarUnit copy = StarMine.type(UnitKind.MARINE).create(helper.getLevel());
			copy.load(tag);
			helper.assertTrue(Status.left(copy, Status.STIM) > 150, "the stimpack was not saved: " + Status.left(copy, Status.STIM));
			Status.end(marine, Status.STIM);
			helper.assertTrue(Math.abs(speed(marine) - normal) < 1e-6, "the speed did not come back: " + speed(marine));
			Status.apply(marine, Status.ROOTED, 5);
			helper.assertTrue(speed(marine) == 0, "rooted but moving");
		});
		helper.runAfterDelay(12, () -> {
			helper.assertTrue(!Status.has(marine, Status.ROOTED) && speed(marine) > 0, "the roots did not run out");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "mechBurrow", timeoutTicks = 200)
	public void burrowedUnitIsHiddenUntilAnOverseerComes(GameTestHelper helper) {
		clear(helper);
		UUID zerg = UUID.randomUUID(), other = UUID.randomUUID();
		StarUnit zergling = unit(helper, UnitKind.ZERGLING, 10, 24, zerg);
		StarUnit marine = unit(helper, UnitKind.MARINE, 20, 24, other);
		marine.setNoAi(true);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> helper.assertTrue(Abilities.cast(zergling, level, Ability.BURROW, null, null), "no burrow: " + Abilities.refusal(zergling, Ability.BURROW)));
		helper.runAfterDelay(30, () -> {
			helper.assertTrue(zergling.mode() == Modes.BURROWED && zergling.cloaked(), "not burrowed and hidden");
			helper.assertTrue(speed(zergling) == 0, "a burrowed zergling moves");
			helper.assertTrue(!zergling.isPushable(), "a burrowed zergling can be pushed");
			helper.assertTrue(!Cloak.seen(zergling, marine), "another side sees a burrowed zergling without a detector");
			helper.assertTrue(!Abilities.on(zergling, Ability.CLOAK), "burrowing counts as the unit's own cloak");
			StarUnit overseer = unit(helper, UnitKind.OVERSEER, 16, 24, other);
			overseer.setNoAi(true);
		});
		helper.runAfterDelay(60, () -> {
			helper.assertTrue(Cloak.seen(zergling, marine) && zergling.revealed(), "the overseer does not reveal the burrowed zergling");
			helper.assertTrue(Abilities.cast(zergling, level, Ability.BURROW, null, null), "no unburrow");
		});
		helper.runAfterDelay(80, () -> {
			helper.assertTrue(zergling.mode() == Modes.NORMAL && !zergling.cloaked() && speed(zergling) > 0, "not up again");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "mechLurker", timeoutTicks = 400)
	public void lurkerAttacksOnlyBurrowed(GameTestHelper helper) {
		clear(helper);
		StarUnit lurker = unit(helper, UnitKind.LURKER, 10, 24, UUID.randomUUID());
		StarUnit marine = unit(helper, UnitKind.MARINE, 16, 24, null);
		marine.setNoAi(true);
		ServerLevel level = helper.getLevel();
		Vec3 home = lurker.position();
		helper.runAfterDelay(80, () -> {
			helper.assertTrue(marine.getHealth() == marine.getMaxHealth(), "an unburrowed lurker attacked");
			// back where it was: a zerg strolls about when idle, and the lurker sometimes wandered out of its reach
			// of the marine before it burrowed (and, burrowed, could not come back)
			lurker.teleportTo(home.x, home.y, home.z);
			helper.assertTrue(Abilities.cast(lurker, level, Ability.BURROW, null, null), "no burrow");
			helper.succeedWhen(() -> helper.assertTrue(marine.getHealth() < marine.getMaxHealth(), "the burrowed lurker does not attack: at "
				+ TestArena.where(helper, lurker) + " mode " + lurker.mode() + " target " + lurker.getTarget() + " weapon " + lurker.weapon() + " goals " + lurker.goalsRunning()));
		});
	}

	@GameTest(template = ARENA, batch = "mechConcussive", timeoutTicks = 200)
	public void marauderSlowsWhatItHits(GameTestHelper helper) {
		clear(helper);
		StarUnit marauder = unit(helper, UnitKind.MARAUDER, 10, 24, UUID.randomUUID());
		StarUnit zergling = unit(helper, UnitKind.ZERGLING, 15, 24, null);
		zergling.setNoAi(true);
		helper.runAfterDelay(2, () -> marauder.commandAttack(zergling));
		helper.succeedWhen(() -> {
			helper.assertTrue(zergling.getHealth() < zergling.getMaxHealth(), "not hit yet; target " + marauder.getTarget() + " goals "
				+ marauder.goalsRunning() + " weapon " + marauder.weapon() + " at " + TestArena.where(helper, marauder));
			helper.assertTrue(Status.has(zergling, Status.SLOW), "hit but not slowed");
		});
	}

	/** Zerg missiles hurt StarMine units of other sides (they used to spare every StarMine unit). */
	@GameTest(template = ARENA, batch = "mechSpit", timeoutTicks = 200)
	public void roachSpitHurtsAMarine(GameTestHelper helper) {
		clear(helper);
		unit(helper, UnitKind.ROACH, 10, 24, UUID.randomUUID());
		StarUnit marine = unit(helper, UnitKind.MARINE, 14, 24, null);
		marine.setNoAi(true);
		helper.succeedWhen(() -> helper.assertTrue(marine.getHealth() < marine.getMaxHealth(), "the roach's acid does nothing to a marine"));
	}

	/** Terran rifles hurt what they shoot at (a stand fight showed marines dealing next to nothing). */
	@GameTest(template = ARENA, batch = "mechRifle", timeoutTicks = 200)
	public void marinesShootAZealot(GameTestHelper helper) {
		clear(helper);
		StarUnit a = unit(helper, UnitKind.MARINE, 10, 23, null), b = unit(helper, UnitKind.MARINE, 10, 25, null);
		StarUnit zealot = unit(helper, UnitKind.ZEALOT, 15, 24, null);
		zealot.setNoAi(true);
		helper.succeedWhen(() -> helper.assertTrue(zealot.shield() < zealot.kind.shields || zealot.getHealth() < zealot.getMaxHealth(),
			"the marines do nothing; targets " + a.getTarget() + " / " + b.getTarget() + " goals " + a.goalsRunning() + " weapon " + a.weapon()));
	}

	/**
	 * A hydralisk's spines fly through a zergling of its side in the way and follow a marine walking to
	 * and fro (SC2 missiles neither stop at allies nor miss).
	 */
	@GameTest(template = ARENA, batch = "mechMissile", timeoutTicks = 300)
	public void zergMissilesPassAlliesAndFollowTheirTarget(GameTestHelper helper) {
		clear(helper);
		StarUnit hydralisk = unit(helper, UnitKind.HYDRALISK, 10, 24, null);
		StarUnit zergling = unit(helper, UnitKind.ZERGLING, 12.5, 24, null);
		StarUnit marine = unit(helper, UnitKind.MARINE, 16, 24, UUID.randomUUID());
		zergling.setNoAi(true);
		marine.setNoAi(true);
		marine.setHealth(marine.getMaxHealth());
		Vec3 home = marine.position();
		int[] hits = {0};
		float[] life = {marine.getHealth()};
		helper.onEachTick(() -> {
			if (!marine.isAlive()) return;
			marine.setPos(home.x, home.y, home.z + Math.sin(helper.getTick() / 8.0) * 2); // about a marine's pace
			if (marine.getHealth() < life[0]) hits[0]++;
			life[0] = marine.getHealth();
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(zergling.getHealth() == zergling.getMaxHealth(), "the spines hurt a zergling of their side");
			helper.assertTrue(hits[0] >= 3 || !marine.isAlive(), "the walking marine was hit " + hits[0] + " times");
		});
	}

	/** SC2 armor: a hit on health loses the target's armor once per strike (a zealot's blades are two). */
	@GameTest(template = ARENA, batch = "mechArmor", timeoutTicks = 60)
	public void armorTakesFromEveryStrike(GameTestHelper helper) {
		clear(helper);
		StarUnit marine = unit(helper, UnitKind.MARINE, 5, 10, null), zergling = unit(helper, UnitKind.ZERGLING, 5, 20, null);
		StarUnit zealot = unit(helper, UnitKind.ZEALOT, 5, 30, UUID.randomUUID());
		StarUnit armored = unit(helper, UnitKind.ZEALOT, 20, 10, null), ultralisk = unit(helper, UnitKind.ULTRALISK, 20, 22, UUID.randomUUID());
		StarUnit stalker = unit(helper, UnitKind.STALKER, 20, 36, null);
		for (StarUnit unit : List.of(marine, zergling, zealot, armored, ultralisk, stalker)) unit.setNoAi(true);
		helper.runAfterDelay(2, () -> {
			armored.setShield(0);
			stalker.setShield(0);
			float s = (float) UnitKind.SCALE;
			check(helper, armored, marine, 6 * s, 5 * s, "a marine's shot on a zealot (armor 1)");
			check(helper, ultralisk, zergling, 5 * s, 3 * s, "a zergling's bite on an ultralisk (armor 2)");
			check(helper, stalker, zealot, 16 * s, 14 * s, "a zealot's two blades on a stalker (armor 1)");
			helper.succeed();
		});
	}

	private static void check(GameTestHelper helper, StarUnit target, StarUnit attacker, float hit, float expected, String what) {
		float before = target.getHealth();
		target.hurt(attacker.attackSource(), hit);
		float lost = before - target.getHealth();
		helper.assertTrue(Math.abs(lost - expected) < 1e-3, what + " took " + lost / UnitKind.SCALE + " (SC2 " + expected / UnitKind.SCALE + ")");
	}

	/**
	 * A building at a quarter of its health or less shows it (owner 15.10): a hatchery bleeds, a barracks
	 * burns with red fire, a gateway with blue fire; a healthy building, one under construction and a
	 * wounded unit show nothing.
	 */
	@GameTest(template = ARENA, batch = "mechDamaged", timeoutTicks = 40)
	public void badlyDamagedBuildingsBleedOrBurn(GameTestHelper helper) {
		clear(helper);
		StarUnit hatchery = unit(helper, UnitKind.HATCHERY, 10, 10, UUID.randomUUID()), barracks = unit(helper, UnitKind.BARRACKS, 24, 10, null);
		StarUnit gateway = unit(helper, UnitKind.GATEWAY, 38, 10, null), depot = unit(helper, UnitKind.SUPPLY_DEPOT, 24, 30, null);
		StarUnit marine = unit(helper, UnitKind.MARINE, 10, 30, null);
		marine.setNoAi(true);
		helper.runAfterDelay(2, () -> {
			for (StarUnit unit : List.of(hatchery, barracks, gateway)) {
				helper.assertTrue(!unit.badlyDamaged(), unit.kind.id + " shows damage at full health");
				unit.setHealth(unit.getMaxHealth() * 0.2f);
				helper.assertTrue(unit.badlyDamaged(), unit.kind.id + " at 20 % shows nothing");
			}
			depot.startConstruction(100);
			depot.setHealth(depot.getMaxHealth() * 0.1f);
			marine.setHealth(marine.getMaxHealth() * 0.1f);
			helper.assertTrue(!depot.badlyDamaged() && !marine.badlyDamaged(), "a building under construction or a unit shows damage");
			helper.assertTrue(UnitKind.Race.TERRAN.rules().damagedParticles().contains(net.minecraft.core.particles.ParticleTypes.FLAME),
				"terran buildings do not burn red");
			helper.assertTrue(UnitKind.Race.PROTOSS.rules().damagedParticles().contains(net.minecraft.core.particles.ParticleTypes.SOUL_FIRE_FLAME),
				"protoss buildings do not burn blue");
			helper.assertTrue(UnitKind.Race.ZERG.rules().damagedParticles().stream().noneMatch(particle -> particle.getType()
				== net.minecraft.core.particles.ParticleTypes.FLAME || particle.getType() == net.minecraft.core.particles.ParticleTypes.SOUL_FIRE_FLAME)
				&& UnitKind.Race.ZERG.rules().damagedParticles().stream().anyMatch(particle -> particle.getType()
				== net.minecraft.core.particles.ParticleTypes.BLOCK), "zerg buildings do not bleed");
			helper.succeed();
		});
	}

	/** A flyer at its hover height finds a ground foe under it (vanilla's target search reaches only 4 blocks up and down). */
	@GameTest(template = ARENA, batch = "mechFlyerSight", timeoutTicks = 100)
	public void aFlyerHighUpFindsAGroundFoe(GameTestHelper helper) {
		clear(helper);
		StarUnit carrier = unit(helper, UnitKind.CARRIER, 10, 24, UUID.randomUUID());
		StarUnit zergling = unit(helper, UnitKind.ZERGLING, 12, 24, null);
		zergling.setNoAi(true);
		Vec3 high = point(helper, 10, 24).add(0, 9, 0);
		carrier.teleportTo(high.x, high.y, high.z);
		helper.succeedWhen(() -> helper.assertTrue(carrier.getTarget() == zergling, "the carrier " + (carrier.getY() - zergling.getY())
			+ " blocks up does not see the zergling under it"));
	}

	/** Melee reach is SC2's (0.1 x BLOCKS_PER_UNIT and 0.3 for pushing bodies, edge to edge), not vanilla's 0.83 blocks. */
	@GameTest(template = ARENA, batch = "mechReach", timeoutTicks = 40)
	public void meleeReachIsSc2s(GameTestHelper helper) {
		clear(helper);
		StarUnit zergling = unit(helper, UnitKind.ZERGLING, 10, 24, null), marine = unit(helper, UnitKind.MARINE, 20, 24, null);
		zergling.setNoAi(true);
		marine.setNoAi(true);
		helper.runAfterDelay(2, () -> {
			double half = (zergling.getBbWidth() + marine.getBbWidth()) / 2;
			zergling.setPos(marine.getX() - half - 0.3, marine.getY(), marine.getZ());
			helper.assertTrue(dev.starmine.entity.UnitGoals.withinReach(zergling, marine), "a zergling 0.3 from a marine's edge cannot bite");
			zergling.setPos(marine.getX() - half - 0.8, marine.getY(), marine.getZ());
			helper.assertFalse(dev.starmine.entity.UnitGoals.withinReach(zergling, marine), "a zergling bites from 0.8 blocks (vanilla's reach)");
			helper.succeed();
		});
	}

	/** A zergling bites at its SC2 period (0.497 s, 10 ticks), not at vanilla's one blow a second. */
	@GameTest(template = ARENA, batch = "mechBite", timeoutTicks = 300)
	public void aZerglingBitesAtItsPace(GameTestHelper helper) {
		clear(helper);
		StarUnit zergling = unit(helper, UnitKind.ZERGLING, 10, 24, null);
		StarUnit thor = unit(helper, UnitKind.THOR, 14, 24, null);
		thor.setNoAi(true);
		List<Long> hits = new java.util.ArrayList<>();
		float[] life = {-1};
		helper.onEachTick(() -> {
			float now = thor.getHealth();
			if (life[0] >= 0 && now < life[0]) hits.add(helper.getTick());
			life[0] = now;
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(hits.size() >= 6, "bites so far " + hits);
			double gap = (hits.get(hits.size() - 1) - hits.get(1)) / (double) (hits.size() - 2);
			helper.assertTrue(gap <= UnitKind.ZERGLING.interval + 1.5, "a bite every " + gap + " ticks (" + UnitKind.ZERGLING.interval + " in SC2); bites " + hits);
		});
	}

	/** A force field stops bodies, not shots: a marine shoots a zealot through a wall of it. */
	@GameTest(template = ARENA, batch = "mechFieldSight", timeoutTicks = 200)
	public void shotsPassAForceField(GameTestHelper helper) {
		clear(helper);
		for (BlockPos rel : BlockPos.betweenClosed(13, 2, 1, 13, 4, 46))
			helper.getLevel().setBlock(helper.absolutePos(rel), dev.starmine.block.StarMineBlocks.FORCE_FIELD.defaultBlockState(), 2);
		StarUnit marine = unit(helper, UnitKind.MARINE, 10, 24, null);
		StarUnit zealot = unit(helper, UnitKind.ZEALOT, 16, 24, null);
		zealot.setNoAi(true);
		helper.succeedWhen(() -> helper.assertTrue(zealot.shield() < zealot.kind.shields || zealot.getHealth() < zealot.getMaxHealth(),
			"the marine does not shoot through the force field; sees " + marine.hasLineOfSight(zealot)));
	}

	/** A marine fires at its SC2 period (0.61 s, 12 ticks) at the edge of its range, not slower the farther its target. */
	@GameTest(template = ARENA, batch = "mechPace", timeoutTicks = 300)
	public void aMarineFiresAtItsPaceAtFullRange(GameTestHelper helper) {
		clear(helper);
		StarUnit marine = unit(helper, UnitKind.MARINE, 10, 24, null);
		StarUnit ultralisk = unit(helper, UnitKind.ULTRALISK, 16.3, 24, null);
		ultralisk.setNoAi(true);
		List<Long> hits = new java.util.ArrayList<>();
		float[] life = {-1};
		helper.onEachTick(() -> {
			float now = ultralisk.getHealth();
			if (life[0] >= 0 && now < life[0]) hits.add(helper.getTick());
			life[0] = now;
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(hits.size() >= 6, "hits so far " + hits + " at " + marine.distanceTo(ultralisk) + " blocks");
			double gap = (hits.get(hits.size() - 1) - hits.get(1)) / (double) (hits.size() - 2);
			helper.assertTrue(gap <= UnitKind.MARINE.interval + 1.5, "a shot every " + gap + " ticks (" + UnitKind.MARINE.interval + " in SC2) at "
				+ marine.distanceTo(ultralisk) + " blocks; hits " + hits);
		});
	}

	@GameTest(template = ARENA, batch = "mechGuardian", timeoutTicks = 100)
	public void guardianShieldTakesFromRangedHits(GameTestHelper helper) {
		clear(helper);
		UUID player = UUID.randomUUID();
		StarUnit sentry = unit(helper, UnitKind.SENTRY, 10, 24, player);
		StarUnit near = unit(helper, UnitKind.ZEALOT, 12, 24, player), far = unit(helper, UnitKind.ZEALOT, 10, 40, player);
		StarUnit marine = unit(helper, UnitKind.MARINE, 30, 24, null);
		for (StarUnit unit : List.of(sentry, near, far, marine)) unit.setNoAi(true);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> {
			sentry.setEnergy(75);
			helper.assertTrue(Abilities.cast(sentry, level, Ability.GUARDIAN_SHIELD, null, null), "no guardian shield");
		});
		helper.runAfterDelay(15, () -> {
			float shot = (float) (6 * UnitKind.SCALE);
			float guarded = Passives.onHurt(near, marine.attackSource(), shot), open = Passives.onHurt(far, marine.attackSource(), shot);
			helper.assertTrue(Math.abs(guarded - (shot - 2 * UnitKind.SCALE)) < 1e-4, "under the shield a shot does " + guarded);
			helper.assertTrue(open == shot, "outside the shield a shot does " + open);
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "mechBarrier", timeoutTicks = 100)
	public void immortalBarrierTakesTheFirstHit(GameTestHelper helper) {
		clear(helper);
		StarUnit immortal = unit(helper, UnitKind.IMMORTAL, 10, 24, UUID.randomUUID());
		StarUnit marine = unit(helper, UnitKind.MARINE, 30, 24, null);
		immortal.setNoAi(true);
		marine.setNoAi(true);
		helper.runAfterDelay(3, () -> {
			float shield = immortal.shield();
			immortal.hurt(marine.attackSource(), 10);
			helper.assertTrue(immortal.shield() == shield && immortal.getHealth() == immortal.getMaxHealth(), "the barrier did not take the hit");
			helper.assertTrue(Status.has(immortal, Status.BARRIER), "no barrier");
		});
		helper.runAfterDelay(60, () -> {
			helper.assertTrue(!Status.has(immortal, Status.BARRIER), "the barrier lasts more than 2 s");
			float shield = immortal.shield();
			immortal.invulnerableTime = 0;
			immortal.hurt(marine.attackSource(), 5);
			helper.assertTrue(immortal.shield() < shield, "the barrier came back before its cooldown");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "mechBattery", timeoutTicks = 100)
	public void shieldBatteryRestoresShields(GameTestHelper helper) {
		clear(helper);
		UUID player = UUID.randomUUID();
		unit(helper, UnitKind.PYLON, 8, 24, player);
		StarUnit battery = unit(helper, UnitKind.SHIELD_BATTERY, 12, 24, player);
		StarUnit zealot = unit(helper, UnitKind.ZEALOT, 17, 24, player);
		zealot.setNoAi(true);
		helper.runAfterDelay(3, () -> zealot.setShield(0));
		helper.runAfterDelay(33, () -> {
			helper.assertTrue(zealot.shield() >= 10, "the battery restored only " + zealot.shield());
			helper.assertTrue(battery.energy() < 100, "the battery spent no energy");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "mechCarrier", timeoutTicks = 500)
	public void carrierFightsWithInterceptors(GameTestHelper helper) {
		clear(helper);
		StarUnit carrier = unit(helper, UnitKind.CARRIER, 10, 24, UUID.randomUUID());
		StarUnit zergling = unit(helper, UnitKind.ZERGLING, 18, 24, null);
		zergling.setNoAi(true);
		helper.succeedWhen(() -> {
			List<StarUnit> out = ProtossAbilities.launched(carrier, helper.getLevel());
			helper.assertTrue(!out.isEmpty() || zergling.isDeadOrDying(), "no interceptor out: carrier target " + carrier.getTarget()
				+ ", aboard " + carrier.abilities.interceptors() + ", at " + TestArena.where(helper, carrier) + ", zergling " + carrier.distanceTo(zergling)
				+ " blocks off at " + TestArena.where(helper, zergling));
			helper.assertTrue(zergling.getHealth() < zergling.getMaxHealth(), "the interceptors did not hit");
			helper.assertTrue(carrier.abilities.interceptors() + out.size() <= ProtossAbilities.INTERCEPTORS, "too many interceptors");
		});
	}

	@GameTest(template = ARENA, batch = "mechRefusals", timeoutTicks = 100)
	public void castsAreRefusedWithoutEnergyHealthOrCooldown(GameTestHelper helper) {
		clear(helper);
		UUID player = UUID.randomUUID();
		StarUnit ghost = unit(helper, UnitKind.GHOST, 10, 24, player), marine = unit(helper, UnitKind.MARINE, 12, 24, player);
		StarUnit stalker = unit(helper, UnitKind.STALKER, 14, 24, player);
		for (StarUnit unit : List.of(ghost, marine, stalker)) unit.setNoAi(true);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> {
			ghost.setEnergy(10);
			helper.assertTrue("message.starmine.need_energy".equals(Abilities.refusal(ghost, Ability.EMP)), "EMP without energy");
			helper.assertTrue(!Abilities.cast(ghost, level, Ability.EMP, null, ghost.position()), "EMP cast without energy");
			marine.setHealth(2);
			helper.assertTrue(!Abilities.cast(marine, level, Ability.STIMPACK, null, null), "a stimpack on 2 health");
			helper.assertTrue(Abilities.cast(stalker, level, Ability.BLINK, null, point(helper, 18, 24)), "no blink");
			helper.assertTrue("message.starmine.cooldown".equals(Abilities.refusal(stalker, Ability.BLINK)), "blink without a cooldown");
			helper.assertTrue(!Abilities.cast(stalker, level, Ability.BLINK, null, point(helper, 14, 24)), "a second blink at once");
			helper.assertTrue(!Abilities.cast(marine, level, Ability.BLINK, null, point(helper, 14, 24)), "a marine blinked");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "mechApproach", timeoutTicks = 600)
	public void anOrderOutOfReachWalksUpAndCasts(GameTestHelper helper) {
		clear(helper);
		StarUnit ghost = unit(helper, UnitKind.GHOST, 4, 24, UUID.randomUUID());
		StarUnit zealot = unit(helper, UnitKind.ZEALOT, 40, 24, null);
		zealot.setNoAi(true);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> {
			ghost.setEnergy(100);
			helper.assertTrue(Abilities.order(level, List.of(ghost), Ability.EMP, null, zealot.position()), "the order was refused");
			helper.assertTrue(ghost.energy() == 100, "cast from out of reach");
			// energy comes back slowly (0.79 a second), shields too (once 7 s have passed): the EMP is seen for a while after
			helper.succeedWhen(() -> {
				helper.assertTrue(ghost.energy() < 60, "the ghost has not cast yet, at " + TestArena.where(helper, ghost));
				helper.assertTrue(zealot.shield() < zealot.kind.shields / 2, "EMP left shields: " + zealot.shield());
			});
		});
	}

	/** One player plays as a marine, a roach and a stalker in turn: a module of each casts the unit's ability. */
	@GameTest(template = ARENA, batch = "mechModules", timeoutTicks = 200)
	public void modulesCastForEveryRace(GameTestHelper helper) {
		clear(helper);
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayerInLevel();
		Vec3 start = point(helper, 5, 5);
		player.teleportTo(start.x, start.y, start.z);
		StarUnit marine = unit(helper, UnitKind.MARINE, 10, 24, player.getUUID());
		StarUnit roach = unit(helper, UnitKind.ROACH, 10, 30, player.getUUID());
		StarUnit stalker = unit(helper, UnitKind.STALKER, 10, 36, player.getUUID());
		helper.runAfterDelay(2, () -> {
			try {
				helper.assertTrue(Possession.start(player, marine), "no possession of the marine");
				float health = player.getHealth();
				helper.assertTrue(Possession.useModule(player, ModuleItem.of(Ability.STIMPACK), InteractionHand.MAIN_HAND), "the stimpack module failed");
				StarUnit puppet = Possession.puppet(player);
				helper.assertTrue(puppet != null && Status.has(puppet, Status.STIM), "the stimpack is not on the unit");
				helper.assertTrue(player.getHealth() < health, "the stimpack cost no health");
				Possession.end(player, false);
				helper.assertTrue(Possession.start(player, roach), "no possession of the roach");
				helper.assertTrue(Possession.useModule(player, ModuleItem.of(Ability.BURROW), InteractionHand.MAIN_HAND), "the burrow module failed");
				helper.assertTrue(Possession.puppet(player).abilities.changingMode(), "the roach does not burrow");
				Possession.end(player, false);
				helper.assertTrue(Possession.start(player, stalker), "no possession of the stalker");
				Vec3 from = player.position(), to = point(helper, 16, 36);
				player.lookAt(EntityAnchorArgument.Anchor.EYES, to.add(0, -0.4, 0));
				helper.assertTrue(Possession.useModule(player, ModuleItem.of(Ability.BLINK), InteractionHand.MAIN_HAND), "the blink module failed");
				helper.assertTrue(player.position().distanceTo(from) > 4, "the player did not blink: " + player.position() + " from " + from);
			} finally { // a mock player left in the world would make every other test's mobs despawn (far from a player)
				if (Possession.piloting(player)) Possession.end(player, false);
				helper.getLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
			}
			helper.succeed();
		});
	}
	/**
	 * Buildings stand on the grid of 3-block cells (1 SC2 cell = 3 blocks, owner 15.10) at their SC2 footprint,
	 * and do not move: not by water, an explosion or a push. A lifted terran building flies.
	 */
	@GameTest(template = ARENA, batch = "buildingsHold", timeoutTicks = 200)
	public void buildingsStandOnTheGridAndDoNotMove(GameTestHelper helper) {
		clear(helper);
		var level = helper.getLevel();
		StarUnit barracks = unit(helper, UnitKind.BARRACKS, 20.3, 20.8, java.util.UUID.randomUUID());
		helper.assertTrue(UnitKind.BARRACKS.width == 9 && UnitKind.COMMAND_CENTER.width == 15 && UnitKind.PYLON.width == 6,
			"footprints: barracks " + UnitKind.BARRACKS.width + ", command center " + UnitKind.COMMAND_CENTER.width + ", pylon " + UnitKind.PYLON.width);
		helper.runAfterDelay(2, () -> {
			net.minecraft.world.phys.Vec3 at = barracks.position();
			double corner = at.x - barracks.kind.width / 2;
			helper.assertTrue(Math.abs(corner / UnitKind.CELL - Math.round(corner / UnitKind.CELL)) < 1e-6
				&& Math.abs((at.z - barracks.kind.width / 2) / UnitKind.CELL - Math.round((at.z - barracks.kind.width / 2) / UnitKind.CELL)) < 1e-6,
				"the barracks is off the grid at " + at);
			// a push, a blow, water and an explosion
			barracks.setDeltaMovement(2, 0.5, 2);
			barracks.push(3, 0, 3);
			for (net.minecraft.core.BlockPos pos : net.minecraft.core.BlockPos.betweenClosed(barracks.blockPosition().offset(-6, 0, -6), barracks.blockPosition().offset(-5, 0, 6)))
				level.setBlock(pos, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(), 3);
			level.explode(null, at.x + 5, at.y + 1, at.z, 3, net.minecraft.world.level.Level.ExplosionInteraction.NONE);
			helper.runAfterDelay(60, () -> {
				helper.assertTrue(barracks.position().distanceTo(at) < 1e-3, "the barracks moved from " + at + " to " + barracks.position());
				helper.succeed();
			});
		});
	}
}
