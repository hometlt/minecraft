package dev.starmine.ability;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
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
 * Terran abilities (owner 14.10, docs/ABILITIES.md), each checked in the world. Cloak, siege mode,
 * Yamato and the medivac are in {@link AbilityGameTests}; concussive shells in {@link MechanicsGameTests}.
 */
public class TerranAbilityGameTests implements FabricGameTest {
	/** A player's command center that founded its base, with minerals. */
	private static StarUnit commandCenter(GameTestHelper helper) {
		return unit(helper, UnitKind.COMMAND_CENTER, 34, 34, UUID.randomUUID());
	}

	@GameTest(template = ARENA, batch = "terranStim", timeoutTicks = 300)
	public void stimpackCostsHealthAndHastens(GameTestHelper helper) {
		clear(helper);
		StarUnit marine = unit(helper, UnitKind.MARINE, 10, 24, UUID.randomUUID());
		marine.setNoAi(true);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> {
			double speed = marine.getAttributeValue(Attributes.MOVEMENT_SPEED);
			float health = marine.getHealth();
			helper.assertTrue(Abilities.cast(marine, level, Ability.STIMPACK, null, null), "no stimpack");
			helper.assertTrue(Math.abs(health - marine.getHealth() - 10 * UnitKind.SCALE) < 1e-4, "the stimpack cost " + (health - marine.getHealth()));
			helper.assertTrue(marine.getAttributeValue(Attributes.MOVEMENT_SPEED) > speed * 1.4, "no faster");
		});
		helper.runAfterDelay(240, () -> {
			helper.assertTrue(!Status.has(marine, Status.STIM), "the stimpack lasts more than 11 s");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "terranReaper", timeoutTicks = 400)
	public void reaperClimbsLedgesAMarineCannot(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		// a 2-block ledge across the arena at x >= 20
		for (BlockPos rel : BlockPos.betweenClosed(20, 2, 0, 47, 3, 47)) level.setBlock(helper.absolutePos(rel), Blocks.STONE.defaultBlockState(), 2);
		UUID player = UUID.randomUUID();
		StarUnit reaper = unit(helper, UnitKind.REAPER, 10, 20, player), marine = unit(helper, UnitKind.MARINE, 10, 28, player);
		Vec3 up = point(helper, 30, 20).add(0, 2, 0), up2 = point(helper, 30, 28).add(0, 2, 0);
		helper.runAfterDelay(2, () -> {
			reaper.command(StarUnit.Order.MOVE, up);
			marine.command(StarUnit.Order.MOVE, up2);
		});
		helper.runAfterDelay(300, () -> {
			helper.assertTrue(reaper.getY() >= up.y - 0.1 && reaper.getX() > helper.absolutePos(new BlockPos(20, 0, 0)).getX(),
				"the reaper did not climb: " + TestArena.where(helper, reaper));
			helper.assertTrue(marine.getY() < up2.y - 1, "a marine climbed a 2-block ledge");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "terranEmp", timeoutTicks = 100)
	public void empDrainsShieldsAndEnergy(GameTestHelper helper) {
		clear(helper);
		StarUnit ghost = unit(helper, UnitKind.GHOST, 10, 24, UUID.randomUUID());
		StarUnit zealot = unit(helper, UnitKind.ZEALOT, 18, 24, null), templar = unit(helper, UnitKind.HIGH_TEMPLAR, 18, 25, null);
		for (StarUnit unit : List.of(ghost, zealot, templar)) unit.setNoAi(true);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> {
			ghost.setEnergy(100);
			templar.setEnergy(150);
			helper.assertTrue(Abilities.cast(ghost, level, Ability.EMP, null, point(helper, 18, 24.5)), "no EMP");
			helper.assertTrue(zealot.shield() == 0 && templar.shield() == 0, "shields left: " + zealot.shield() + " " + templar.shield());
			helper.assertTrue(Math.abs(templar.energy() - 50) < 0.1, "the templar keeps " + templar.energy() + " energy");
			helper.assertTrue(zealot.getHealth() == zealot.getMaxHealth(), "EMP hurt the health");
			helper.succeed();
		});
	}

	/** The unit with this UUID in the level now (a morph puts a new unit of another kind in its place). */
	private static StarUnit same(GameTestHelper helper, UUID id) {
		return helper.getLevel().getEntity(id) instanceof StarUnit unit ? unit : null;
	}

	/** Owner 17.10: the landed viking is a unit of its own — the viking turns into an assault viking and back. */
	@GameTest(template = ARENA, batch = "terranViking", timeoutTicks = 300)
	public void vikingLandsAndTakesOff(GameTestHelper helper) {
		clear(helper);
		StarUnit viking = unit(helper, UnitKind.VIKING, 10, 24, UUID.randomUUID());
		UUID id = viking.getUUID();
		int entityId = viking.getId();
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> helper.assertTrue(Abilities.cast(viking, level, Ability.VIKING_LAND, null, null), "no landing"));
		helper.runAfterDelay(100, () -> {
			StarUnit landed = same(helper, id);
			helper.assertTrue(landed != null && landed.kind == UnitKind.VIKING_ASSAULT && landed != viking, "not an assault viking: " + landed);
			helper.assertTrue(landed.getId() == entityId && viking.isRemoved(), "the old viking is still there, or a new id");
			helper.assertTrue(!landed.flying() && !landed.isNoGravity() && Abilities.on(landed, Ability.VIKING_LAND), "not landed");
			helper.assertTrue(landed.weapon().range() < UnitKind.VIKING.range, "still the air weapon");
			helper.assertTrue(Abilities.cast(landed, level, Ability.VIKING_LAND, null, null), "no take-off");
		});
		helper.runAfterDelay(160, () -> {
			StarUnit flying = same(helper, id);
			helper.assertTrue(flying != null && flying.kind == UnitKind.VIKING && flying.flying() && flying.isNoGravity(), "not flying again: " + flying);
			helper.succeed();
		});
	}

	/** Owner 17.10: the hellbat is a unit of its own (its egg, trained at a factory with an armory); a hellion turns into one. */
	@GameTest(template = ARENA, batch = "terranHellbat", timeoutTicks = 200)
	public void hellionBecomesHellbat(GameTestHelper helper) {
		clear(helper);
		StarUnit hellion = unit(helper, UnitKind.HELLION, 10, 24, UUID.randomUUID());
		hellion.setNoAi(true);
		UUID id = hellion.getUUID();
		ServerLevel level = helper.getLevel();
		helper.assertTrue(UnitKind.HELLBAT.producer() == UnitKind.FACTORY && UnitKind.HELLBAT.requires() == UnitKind.ARMORY
			&& UnitKind.FACTORY.trains().contains(UnitKind.HELLBAT), "the factory does not train hellbats");
		helper.runAfterDelay(2, () -> helper.assertTrue(Abilities.cast(hellion, level, Ability.HELLBAT, null, null), "no hellbat"));
		helper.runAfterDelay(60, () -> {
			StarUnit hellbat = same(helper, id);
			helper.assertTrue(hellbat != null && hellbat.kind == UnitKind.HELLBAT && hellbat.biological() && hellbat.kind.mechanical(), "not a hellbat: " + hellbat);
			helper.assertTrue(hellbat.getMaxHealth() > UnitKind.HELLION.health + 10, "no more health: " + hellbat.getMaxHealth());
			// SC2 hellbat flame reach 2 against the hellion's 5
			helper.assertTrue(hellbat.weapon().range() <= 2 * UnitKind.BLOCKS_PER_UNIT + 1 && hellbat.weapon().range() < UnitKind.HELLION.range / 2,
				"not a short flame: " + hellbat.weapon().range());
			helper.assertTrue(hellbat.isNoAi(), "the hellbat lost the hellion's data");
			helper.succeed();
		});
	}

	/**
	 * Owner 17.10: every SC2 upgrade, 3 levels: each unit takes its own (a marine infantry weapons and armor,
	 * a viking ship weapons, a zealot ground weapons and shields): more damage, 1 armor and a tenth more shields a level.
	 */
	@GameTest(template = ARENA, batch = "upgrades", timeoutTicks = 300)
	public void upgradesRaiseDamageArmorAndShields(GameTestHelper helper) {
		clear(helper);
		StarUnit cc = commandCenter(helper);
		helper.runAfterDelay(5, () -> {
			Hive base = dev.starmine.ability.TestArena.base(helper, cc);
			helper.assertTrue(base != null, "no base");
			base.attackLevel = 2;
			base.armorLevel = 1;
			base.levels.put(dev.starmine.race.Research.SHIELDS.id(), 2);
			StarUnit marine = dev.starmine.ability.TestArena.member(helper, base, UnitKind.MARINE, 10, 20);
			StarUnit viking = dev.starmine.ability.TestArena.member(helper, base, UnitKind.VIKING, 10, 26);
			StarUnit zealot = dev.starmine.ability.TestArena.member(helper, base, UnitKind.ZEALOT, 10, 30);
			for (StarUnit unit : List.of(marine, viking, zealot)) unit.setNoAi(true);
			helper.runAfterDelay(120, () -> {
				helper.assertTrue(marine.weaponsUpgrade() == 2 && marine.armorUpgrade() == 1, "the marine has " + marine.weaponsUpgrade() + "/" + marine.armorUpgrade());
				helper.assertTrue(Math.abs(marine.attackBonus() - 2 * UnitKind.SCALE) < 1e-3, "the marine's weapons +2 add " + marine.attackBonus());
				helper.assertTrue(viking.weaponsUpgrade() == 0 && viking.armorUpgrade() == 0, "the infantry upgrades reached a viking");
				helper.assertTrue(zealot.weaponsUpgrade() == 2 && zealot.shieldsUpgrade() == 2, "the zealot has " + zealot.weaponsUpgrade() + "/" + zealot.shieldsUpgrade());
				helper.assertTrue(Math.abs(zealot.maxShield() - UnitKind.ZEALOT.shields * 1.2) < 1e-3, "the zealot's shields: " + zealot.maxShield());
				// 1 armor a level: a zergling's 5 reaches the marine's health as 4
				float before = marine.getHealth();
				StarUnit zergling = unit(helper, UnitKind.ZERGLING, 40, 40, null);
				marine.hurt(zergling.attackSource(), (float) (5 * UnitKind.SCALE));
				helper.assertTrue(Math.abs(before - marine.getHealth() - 4 * UnitKind.SCALE) < 1e-3, "armor 1 let through " + (before - marine.getHealth()));
				helper.assertTrue(dev.starmine.race.Research.of(UnitKind.ARMORY).containsAll(List.of(dev.starmine.race.Research.VEHICLE_WEAPONS,
					dev.starmine.race.Research.SHIP_WEAPONS, dev.starmine.race.Research.PLATING)), "the armory researches " + dev.starmine.race.Research.of(UnitKind.ARMORY));
				helper.assertTrue(dev.starmine.race.Research.of(UnitKind.FORGE).contains(dev.starmine.race.Research.SHIELDS), "no shields at the forge");
				helper.succeed();
			});
		});
	}

	/** Owner 17.10: a burrowed widow mine fires its sentinel missile at a foe in range: 125 to it, then it rearms. */
	@GameTest(template = ARENA, batch = "terranWidowMine", timeoutTicks = 200)
	public void aBurrowedWidowMineStrikes(GameTestHelper helper) {
		clear(helper);
		StarUnit mine = unit(helper, UnitKind.WIDOW_MINE, 10, 24, UUID.randomUUID());
		ServerLevel level = helper.getLevel();
		StarUnit roach = unit(helper, UnitKind.ROACH, 16, 24, null);
		roach.setNoAi(true);
		helper.runAfterDelay(2, () -> helper.assertTrue(Abilities.cast(mine, level, Ability.BURROW, null, null), "the mine does not burrow"));
		helper.succeedWhen(() -> {
			helper.assertTrue(mine.mode() == Modes.BURROWED, "not burrowed");
			float lost = roach.getMaxHealth() - roach.getHealth();
			helper.assertTrue(lost >= 120 * UnitKind.SCALE, "the roach lost only " + lost);
			helper.assertTrue(mine.abilities.mineReadyAt > level.getGameTime() + 20 * 20, "the mine did not rearm");
		});
	}

	/** Owner 17.10: the liberator; in defender mode it stands and hits the ground hard. */
	@GameTest(template = ARENA, batch = "terranLiberator", timeoutTicks = 200)
	public void liberatorTakesDefenderMode(GameTestHelper helper) {
		clear(helper);
		StarUnit liberator = unit(helper, UnitKind.LIBERATOR, 10, 24, UUID.randomUUID());
		liberator.setNoAi(true);
		ServerLevel level = helper.getLevel();
		helper.assertTrue(UnitKind.STARPORT.trains().contains(UnitKind.LIBERATOR), "the starport does not train liberators");
		helper.runAfterDelay(2, () -> helper.assertTrue(Abilities.cast(liberator, level, Ability.DEFENDER_MODE, null, null), "no defender mode"));
		helper.runAfterDelay(60, () -> {
			helper.assertTrue(liberator.mode() == Modes.DEFENDER, "not in defender mode");
			Modes.Weapon weapon = liberator.weapon();
			helper.assertTrue(!weapon.mobile() && weapon.damage() >= 70 * UnitKind.SCALE && weapon.range() > UnitKind.LIBERATOR.range,
				"not the lancer: " + weapon);
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "terranThor", timeoutTicks = 200)
	public void thorSwitchesPayload(GameTestHelper helper) {
		clear(helper);
		StarUnit thor = unit(helper, UnitKind.THOR, 10, 24, UUID.randomUUID());
		thor.setNoAi(true);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> helper.assertTrue(Abilities.cast(thor, level, Ability.HIGH_IMPACT, null, null), "no payload switch"));
		helper.runAfterDelay(60, () -> {
			StarUnit flyer = unit(helper, UnitKind.BATTLECRUISER, 30, 24, null);
			helper.assertTrue(thor.weaponAgainst(flyer).range() > UnitKind.THOR.range + 3, "the high impact payload reaches " + thor.weaponAgainst(flyer).range());
			helper.assertTrue(thor.weapon().damage() == UnitKind.THOR.damage, "its hammers changed for the ground: " + thor.weapon().damage()); // SC2: only its anti-air changes
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "terranKd8", timeoutTicks = 100)
	public void kd8KnocksBack(GameTestHelper helper) {
		clear(helper);
		StarUnit reaper = unit(helper, UnitKind.REAPER, 10, 24, UUID.randomUUID());
		reaper.setNoAi(true);
		StarUnit zergling = unit(helper, UnitKind.ZERGLING, 14, 24, null);
		zergling.setNoAi(true);
		ServerLevel level = helper.getLevel();
		Vec3 point = point(helper, 13.5, 24);
		helper.runAfterDelay(2, () -> helper.assertTrue(Abilities.cast(reaper, level, Ability.KD8_CHARGE, null, point), "no KD8"));
		helper.runAfterDelay(20, () -> helper.assertTrue(zergling.getHealth() == zergling.getMaxHealth(), "the charge went off before 1.5 s"));
		helper.runAfterDelay(40, () -> {
			helper.assertTrue(zergling.getHealth() < zergling.getMaxHealth(), "the charge did not hurt");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "terranSteady", timeoutTicks = 100)
	public void steadyTargetingHitsBio(GameTestHelper helper) {
		clear(helper);
		StarUnit ghost = unit(helper, UnitKind.GHOST, 10, 24, UUID.randomUUID());
		StarUnit ultra = unit(helper, UnitKind.ULTRALISK, 18, 24, null), tank = unit(helper, UnitKind.SIEGE_TANK, 18, 30, null);
		for (StarUnit unit : List.of(ghost, ultra, tank)) unit.setNoAi(true);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> {
			ghost.setEnergy(100);
			helper.assertTrue(!Abilities.cast(ghost, level, Ability.STEADY_TARGETING, tank, null), "steady targeting on a machine");
			helper.assertTrue(Abilities.cast(ghost, level, Ability.STEADY_TARGETING, ultra, null), "no steady targeting");
		});
		helper.runAfterDelay(45, () -> {
			helper.assertTrue(ultra.getMaxHealth() - ultra.getHealth() >= TerranAbilities.STEADY_DAMAGE * UnitKind.SCALE - 1, "it did " + (ultra.getMaxHealth() - ultra.getHealth()));
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "terranNuke", timeoutTicks = 500)
	public void nukeLandsAfterItsGuide(GameTestHelper helper) {
		clear(helper);
		StarUnit cc = commandCenter(helper);
		ServerLevel level = helper.getLevel();
		// out of the crawler's reach (SC2 7, 17 blocks now): the ghost guides the nuke from 24 blocks off
		StarUnit crawler = unit(helper, UnitKind.SPINE_CRAWLER, 30, 10, null);
		java.util.concurrent.atomic.AtomicReference<NukeMissile> missile = new java.util.concurrent.atomic.AtomicReference<>();
		level.getGameRules().getRule(dev.starmine.StarMine.NUKE_GRIEFING).set(false, level.getServer()); // the arena's floor stays
		helper.runAfterDelay(5, () -> {
			Hive base = TestArena.base(helper, cc);
			helper.assertTrue(base != null, "no base");
			// the base's SCVs stand round its command center (15 wide now), within the crawler's reach: they would fight it
			for (StarUnit scv : level.getEntitiesOfClass(StarUnit.class, cc.getBoundingBox().inflate(40), unit -> unit.kind == UnitKind.SCV)) scv.discard();
			StarUnit ghost = TestArena.member(helper, base, UnitKind.GHOST, 6, 10);
			ghost.setNoAi(true);
			helper.assertTrue("message.starmine.no_nuke".equals(Abilities.refusal(ghost, Ability.NUKE)), "a nuke with none armed: " + Abilities.refusal(ghost, Ability.NUKE));
			dev.starmine.race.Research.NUKE.apply(base); // armed at an academy
			helper.assertTrue(Abilities.cast(ghost, level, Ability.NUKE, null, crawler.position()), "no nuke: " + Abilities.refusal(ghost, Ability.NUKE));
			helper.assertTrue(dev.starmine.race.Research.nukes(base) == 0, "the armed nuke is not spent");
			List<NukeMissile> flying = level.getEntitiesOfClass(NukeMissile.class, ghost.getBoundingBox().inflate(80));
			helper.assertTrue(flying.size() == 1, "no missile in the air: " + flying.size());
			missile.set(flying.get(0));
		});
		helper.runAfterDelay(80, () -> helper.assertTrue(missile.get().isAlive() && missile.get().getY() > crawler.getY() + 30, "the missile did not rise: " + missile.get().position()));
		helper.runAfterDelay(185, () -> helper.assertTrue(crawler.getHealth() == crawler.getMaxHealth(), "the nuke landed early: the crawler has "
			+ crawler.getHealth() + " of " + crawler.getMaxHealth() + ", hurt by " + crawler.getLastHurtByMob() + " with " + crawler.getLastDamageSource()));
		helper.runAfterDelay(240, () -> {
			helper.assertTrue(!crawler.isAlive() || crawler.getHealth() < crawler.getMaxHealth() * 0.2, "the nuke left " + crawler.getHealth());
			level.getGameRules().getRule(dev.starmine.StarMine.NUKE_GRIEFING).set(true, level.getServer());
			helper.assertTrue(missile.get().isRemoved(), "the missile is still there");
			helper.succeed();
		});
	}

	/** Owner 17.10: an order to the guiding ghost lets the nuke go: the missile dies in the air with a small blast, nothing below is hurt. */
	@GameTest(template = ARENA, batch = "terranNukeCancel", timeoutTicks = 300)
	public void aCancelledNukeDies(GameTestHelper helper) {
		clear(helper);
		StarUnit cc = commandCenter(helper);
		ServerLevel level = helper.getLevel();
		StarUnit crawler = unit(helper, UnitKind.SPINE_CRAWLER, 30, 10, null);
		java.util.concurrent.atomic.AtomicReference<NukeMissile> missile = new java.util.concurrent.atomic.AtomicReference<>();
		helper.runAfterDelay(5, () -> {
			Hive base = TestArena.base(helper, cc);
			for (StarUnit scv : level.getEntitiesOfClass(StarUnit.class, cc.getBoundingBox().inflate(40), unit -> unit.kind == UnitKind.SCV)) scv.discard();
			StarUnit ghost = TestArena.member(helper, base, UnitKind.GHOST, 6, 10);
			ghost.setNoAi(true);
			dev.starmine.race.Research.NUKE.apply(base);
			helper.assertTrue(Abilities.cast(ghost, level, Ability.NUKE, null, crawler.position()), "no nuke: " + Abilities.refusal(ghost, Ability.NUKE));
			missile.set(level.getEntitiesOfClass(NukeMissile.class, ghost.getBoundingBox().inflate(80)).get(0));
			helper.runAfterDelay(40, () -> ghost.command(StarUnit.Order.NONE, ghost.position()));
		});
		helper.runAfterDelay(60, () -> helper.assertTrue(missile.get().isRemoved(), "the missile flies on without its ghost"));
		helper.runAfterDelay(250, () -> {
			helper.assertTrue(crawler.getHealth() == crawler.getMaxHealth(), "a cancelled nuke hurt the crawler");
			helper.succeed();
		});
	}

	/** Owner 17.10: a battlecruiser charging its tactical jump holds still and holds fire, then comes out at the point. */
	@GameTest(template = ARENA, batch = "terranJump", timeoutTicks = 200)
	public void aJumpingCruiserHoldsStill(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		StarUnit cruiser = unit(helper, UnitKind.BATTLECRUISER, 10, 10, UUID.randomUUID());
		Vec3 to = point(helper, 38, 38);
		java.util.concurrent.atomic.AtomicReference<Vec3> from = new java.util.concurrent.atomic.AtomicReference<>();
		helper.runAfterDelay(2, () -> {
			helper.assertTrue(Abilities.cast(cruiser, level, Ability.TACTICAL_JUMP, null, to), "no jump: " + Abilities.refusal(cruiser, Ability.TACTICAL_JUMP));
			from.set(cruiser.position());
			cruiser.command(StarUnit.Order.MOVE, point(helper, 10, 40));
		});
		helper.runAfterDelay(60, () -> {
			helper.assertTrue(Abilities.horizontal(cruiser.position(), from.get()) < 0.5, "the cruiser moved while charging: " + cruiser.position().subtract(from.get()));
			helper.assertTrue(cruiser.weapon().damage() == 0, "the cruiser can fire while charging");
			helper.assertTrue(Status.has(cruiser, Status.WARPING), "no warping state");
		});
		helper.runAfterDelay(100, () -> {
			helper.assertTrue(Abilities.horizontal(cruiser.position(), to) < 2, "the cruiser did not jump: " + TestArena.rel(helper, cruiser));
			helper.assertFalse(Status.has(cruiser, Status.WARPING), "still warping after the jump");
			helper.assertTrue(cruiser.weapon().damage() > 0, "the cruiser cannot fire after the jump");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "terranRepair", timeoutTicks = 600)
	public void scvRepairsForMinerals(GameTestHelper helper) {
		clear(helper);
		StarUnit cc = commandCenter(helper);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(5, () -> {
			Hive base = TestArena.base(helper, cc);
			helper.assertTrue(base != null, "no base");
			base.minerals = 500;
			StarUnit tank = TestArena.member(helper, base, UnitKind.SIEGE_TANK, 8, 8);
			tank.setNoAi(true);
			tank.setHealth(tank.getMaxHealth() / 2);
			StarUnit scv = TestArena.member(helper, base, UnitKind.SCV, 16, 8);
			helper.assertTrue(Abilities.order(level, List.of(scv), Ability.REPAIR, tank, null), "no repair order");
			helper.succeedWhen(() -> {
				helper.assertTrue(tank.getHealth() >= tank.getMaxHealth() - 0.01, "repaired to " + tank.getHealth());
				helper.assertTrue(base.minerals < 500 && base.minerals >= 500 - 150 * 0.25 / 2 - 2, "the repair cost " + (500 - base.minerals));
			});
		});
	}

	@GameTest(template = ARENA, batch = "terranOrbital", timeoutTicks = 800)
	public void orbitalCallsAMuleAndScans(GameTestHelper helper) {
		clear(helper);
		StarUnit cc = commandCenter(helper);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(5, () -> {
			Hive base = TestArena.base(helper, cc);
			helper.assertTrue(base != null, "no base");
			base.minerals = 1000;
			helper.assertTrue(!Abilities.available(cc, Ability.MULE), "a plain command center calls MULEs");
			if (base.owner != null) { // SC2: an orbital needs a barracks (a player's base; owner 18.10)
				helper.assertTrue(!Abilities.cast(cc, level, Ability.ORBITAL_COMMAND, null, null), "an orbital without a barracks");
				base.join(UnitKind.BARRACKS);
			}
			helper.assertTrue(Abilities.cast(cc, level, Ability.ORBITAL_COMMAND, null, null), "no orbital");
			helper.assertTrue(base.minerals == 850, "the orbital is not paid");
		});
		helper.runAfterDelay(260, () -> { // owner 18.10: halfway, its progress shows like a construction's
			helper.assertTrue(cc.queueIds().equals("morph") && cc.progress() > 300 && cc.progress() < 700,
				"no upgrade progress halfway: " + cc.queueIds() + " " + cc.progress());
		});
		helper.runAfterDelay(530, () -> {
			helper.assertTrue(cc.mode() == Modes.ORBITAL && cc.maxEnergy() == 200 && cc.energy() >= 50, "not an orbital with energy");
			helper.assertTrue(!Abilities.available(cc, Ability.PLANETARY_FORTRESS), "an orbital can still become a fortress");
			StarUnit banshee = unit(helper, UnitKind.BANSHEE, 10, 40, null);
			banshee.setNoAi(true);
			banshee.setEnergy(200);
			Abilities.cast(banshee, level, Ability.CLOAK, null, null);
			cc.setEnergy(100);
			helper.assertTrue(Abilities.cast(cc, level, Ability.MULE, null, point(helper, 30, 30)), "no MULE");
			helper.assertTrue(Abilities.cast(cc, level, Ability.SCANNER_SWEEP, null, banshee.position()), "no scan");
			StarUnit mule = level.getEntitiesOfClass(StarUnit.class, new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(64), StarUnit::mule).stream().findFirst().orElse(null);
			helper.assertTrue(mule != null && Status.left(mule, Status.TEMPORARY) > 0, "no MULE landed");
		});
		helper.runAfterDelay(560, () -> {
			StarUnit banshee = level.getEntitiesOfClass(StarUnit.class, new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(64), unit -> unit.kind == UnitKind.BANSHEE).get(0);
			helper.assertTrue(banshee.cloaked() && Cloak.seen(banshee, cc), "the scan does not show the cloaked banshee");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "terranDepot", timeoutTicks = 100)
	public void loweredDepotLetsItsSidePass(GameTestHelper helper) {
		clear(helper);
		UUID player = UUID.randomUUID();
		StarUnit depot = unit(helper, UnitKind.SUPPLY_DEPOT, 20, 24, player);
		StarUnit marine = unit(helper, UnitKind.MARINE, 14, 24, player), zergling = unit(helper, UnitKind.ZERGLING, 14, 30, null);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> {
			helper.assertTrue(marine.canCollideWith(depot), "a raised depot lets units through");
			helper.assertTrue(Abilities.cast(depot, level, Ability.LOWER_DEPOT, null, null), "no lowering");
			helper.assertTrue(!marine.canCollideWith(depot) && !zergling.canCollideWith(depot), "the lowered depot stops someone (SC2: everyone walks over it)");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "terranBunker", timeoutTicks = 300)
	public void bunkerHoldsAndShelters(GameTestHelper helper) {
		clear(helper);
		UUID player = UUID.randomUUID();
		StarUnit bunker = unit(helper, UnitKind.BUNKER, 10, 24, player);
		StarUnit marine = unit(helper, UnitKind.MARINE, 13, 24, player), ultra = unit(helper, UnitKind.ULTRALISK, 14, 30, player);
		ServerLevel level = helper.getLevel();
		StarUnit[] zergling = new StarUnit[1];
		helper.runAfterDelay(2, () -> {
			helper.assertTrue(Abilities.cast(bunker, level, Ability.LOAD, null, null), "no load");
			helper.assertTrue(marine.getVehicle() == bunker && ultra.getVehicle() == null, "loaded " + bunker.getPassengers());
			helper.assertTrue(!marine.hurt(level.damageSources().generic(), 5) && marine.getHealth() == marine.getMaxHealth(), "the marine is hurt inside");
			zergling[0] = unit(helper, UnitKind.ZERGLING, 16, 24, null);
			zergling[0].setNoAi(true);
		});
		helper.runAfterDelay(102, () -> {
			helper.assertTrue(zergling[0].getHealth() < zergling[0].getMaxHealth(), "the garrison does not shoot");
			helper.assertTrue(Abilities.cast(bunker, level, Ability.UNLOAD, null, bunker.position()), "no unload");
			helper.assertTrue(marine.getVehicle() == null, "the marine is still inside");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "terranTechlab", timeoutTicks = 800)
	public void techLabUnlocksTheMarauder(GameTestHelper helper) {
		clear(helper);
		StarUnit cc = commandCenter(helper);
		StarUnit[] barracks = new StarUnit[1];
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(5, () -> {
			Hive base = TestArena.base(helper, cc);
			helper.assertTrue(base != null, "no base");
			base.minerals = 1000;
			base.vespene = 1000;
			for (UnitKind kind : new UnitKind[] {UnitKind.SUPPLY_DEPOT, UnitKind.BARRACKS}) base.join(kind);
			barracks[0] = TestArena.member(helper, base, UnitKind.BARRACKS, 10, 10);
			helper.assertTrue(!barracks[0].train(UnitKind.MARAUDER), "a marauder without a tech lab");
			helper.assertTrue(Abilities.cast(barracks[0], level, Ability.BUILD_TECHLAB, null, null), "no tech lab");
			helper.assertTrue(base.minerals == 950 && base.vespene == 975, "the tech lab is not paid");
		});
		helper.runAfterDelay(385, () -> {
			helper.assertTrue(barracks[0].mode() == Modes.TECHLAB, "no tech lab after 18 s");
			helper.assertTrue(barracks[0].train(UnitKind.MARAUDER), "no marauder with a tech lab");
			helper.assertTrue(!Abilities.available(barracks[0], Ability.BUILD_REACTOR), "a reactor on a tech lab");
			helper.succeed();
		});
	}

	/**
	 * A barracks lifts off only idle, flies to where it is sent at SC2 speed and trains nothing in the air,
	 * lands only where it fits, and trains again on the ground.
	 */
	@GameTest(template = ARENA, batch = "terranLiftOff", timeoutTicks = 700)
	public void barracksLiftsOffFliesAndLands(GameTestHelper helper) {
		clear(helper);
		StarUnit cc = commandCenter(helper);
		StarUnit[] barracks = new StarUnit[1], depot = new StarUnit[1];
		ServerLevel level = helper.getLevel();
		double floor = helper.absolutePos(BlockPos.ZERO).getY() + 2;
		helper.runAfterDelay(5, () -> {
			Hive base = TestArena.base(helper, cc);
			helper.assertTrue(base != null, "no base");
			base.minerals = 1000;
			for (UnitKind kind : new UnitKind[] {UnitKind.SUPPLY_DEPOT, UnitKind.BARRACKS}) base.join(kind);
			barracks[0] = TestArena.member(helper, base, UnitKind.BARRACKS, 10, 10);
			helper.assertTrue(barracks[0].train(UnitKind.MARINE), "no marine");
			helper.assertTrue(!Abilities.cast(barracks[0], level, Ability.LIFT_OFF, null, null), "lifted off while training");
			barracks[0].cancelLast();
			helper.assertTrue(Abilities.cast(barracks[0], level, Ability.LIFT_OFF, null, null), "no lift-off");
		});
		helper.runAfterDelay(60, () -> {
			helper.assertTrue(barracks[0].mode() == Modes.AIRBORNE && barracks[0].flying(), "not in the air");
			helper.assertTrue(barracks[0].getY() > floor + 2, "only " + (barracks[0].getY() - floor) + " blocks up");
			helper.assertTrue(!barracks[0].train(UnitKind.MARINE), "trains in the air");
			barracks[0].command(StarUnit.Order.MOVE, point(helper, 30, 10));
		});
		helper.runAfterDelay(240, () -> {
			Vec3 at = TestArena.rel(helper, barracks[0]);
			helper.assertTrue(Math.abs(at.x - 30.5) < 0.5 && Math.abs(at.z - 10.5) < 0.5, "not flown there: " + at);
			depot[0] = TestArena.member(helper, TestArena.base(helper, cc), UnitKind.SUPPLY_DEPOT, 30, 10);
			helper.assertTrue(!Abilities.cast(barracks[0], level, Ability.LIFT_OFF, null, null), "landed on a depot");
			depot[0].discard();
			helper.assertTrue(Abilities.cast(barracks[0], level, Ability.LIFT_OFF, null, null), "no landing");
		});
		helper.runAfterDelay(320, () -> {
			helper.assertTrue(barracks[0].mode() == Modes.NORMAL && !barracks[0].flying() && !barracks[0].isNoGravity(), "not landed");
			helper.assertTrue(Math.abs(barracks[0].getY() - floor) < 0.2, "landed " + (barracks[0].getY() - floor) + " blocks up");
			helper.assertTrue(barracks[0].train(UnitKind.MARINE), "no marine after landing");
			helper.succeed();
		});
	}

	/** Owner 16.10: a lifted building took a right-click order (sent as a rally for buildings) and stayed; it flies there. */
	@GameTest(template = ARENA, batch = "terranLiftOffOrder", timeoutTicks = 400)
	public void aLiftedBuildingFliesWhereThePlayerSendsIt(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		net.minecraft.server.level.ServerPlayer player = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayerInLevel();
		player.getInventory().add(new net.minecraft.world.item.ItemStack(dev.starmine.colony.CommandPanel.ITEM));
		Vec3 stand = point(helper, 20, 20);
		player.teleportTo(stand.x, stand.y, stand.z);
		StarUnit cc = unit(helper, UnitKind.COMMAND_CENTER, 34, 34, player.getUUID());
		StarUnit[] barracks = new StarUnit[1];
		helper.runAfterDelay(5, () -> {
			Hive base = TestArena.base(helper, cc);
			helper.assertTrue(base != null, "no base");
			barracks[0] = TestArena.member(helper, base, UnitKind.BARRACKS, 10, 10);
			helper.assertTrue(Abilities.cast(barracks[0], level, Ability.LIFT_OFF, null, null), "no lift-off");
		});
		helper.runAfterDelay(60, () -> {
			Vec3 to = point(helper, 30, 10);
			dev.starmine.colony.Commands.command(player, new dev.starmine.colony.ColonyNetwork.UnitCommand(new int[] {barracks[0].getId()}, "rally", to.x, to.y, to.z, -1, ""));
		});
		helper.runAfterDelay(300, () -> {
			Vec3 at = TestArena.rel(helper, barracks[0]);
			level.removePlayerImmediately(player, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
			helper.assertTrue(Math.abs(at.x - 30.5) < 1 && Math.abs(at.z - 10.5) < 1, "the lifted barracks did not fly there: " + at);
			helper.succeed();
		});
	}

	/**
	 * A command center lifts off, flies and lands again on rough ground (owner 16.10: it could not land):
	 * grass and a bump of earth under it are cut away, it stands on the grid.
	 */
	@GameTest(template = ARENA, batch = "terranLiftOffCommandCenter", timeoutTicks = 700)
	public void commandCenterLandsOnRoughGround(GameTestHelper helper) {
		clear(helper);
		StarUnit cc = commandCenter(helper);
		ServerLevel level = helper.getLevel();
		double floor = helper.absolutePos(BlockPos.ZERO).getY() + 2;
		helper.runAfterDelay(5, () -> helper.assertTrue(Abilities.cast(cc, level, Ability.LIFT_OFF, null, null), "no lift-off"));
		helper.runAfterDelay(60, () -> {
			helper.assertTrue(cc.mode() == Modes.AIRBORNE && cc.flying(), "not in the air");
			cc.command(StarUnit.Order.MOVE, point(helper, 22, 34));
		});
		helper.runAfterDelay(260, () -> {
			Vec3 at = TestArena.rel(helper, cc);
			helper.assertTrue(Math.abs(at.x - 22.5) < 0.6 && Math.abs(at.z - 34.5) < 0.6, "not flown there: " + at);
			for (int dx = -5; dx <= 5; dx++)
				for (int dz = -5; dz <= 5; dz++) helper.setBlock(new BlockPos(22 + dx, 2, 34 + dz), net.minecraft.world.level.block.Blocks.SHORT_GRASS);
			helper.setBlock(new BlockPos(24, 2, 36), net.minecraft.world.level.block.Blocks.DIRT);
			helper.assertTrue(Abilities.cast(cc, level, Ability.LIFT_OFF, null, null), "no landing on rough ground");
		});
		helper.runAfterDelay(380, () -> {
			helper.assertTrue(cc.mode() == Modes.NORMAL && !cc.flying() && !cc.isNoGravity(), "not landed");
			helper.assertTrue(Math.abs(cc.getY() - floor) < 0.2, "landed " + (cc.getY() - floor) + " blocks up");
			helper.assertTrue(helper.getBlockState(new BlockPos(24, 2, 36)).isAir(), "the bump is still under it");
			Vec3 grid = cc.kind.gridCentre(cc.position());
			helper.assertTrue(Math.abs(grid.x - cc.getX()) < 0.01 && Math.abs(grid.z - cc.getZ()) < 0.01, "not on the grid");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "terranAfterburners", timeoutTicks = 200)
	public void afterburnersSpeedUpTheMedivac(GameTestHelper helper) {
		clear(helper);
		StarUnit medivac = unit(helper, UnitKind.MEDIVAC, 10, 24, UUID.randomUUID());
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> {
			double speed = medivac.getAttributeValue(Attributes.FLYING_SPEED);
			helper.assertTrue(Abilities.cast(medivac, level, Ability.AFTERBURNERS, null, null), "no afterburners");
			helper.assertTrue(medivac.getAttributeValue(Attributes.FLYING_SPEED) > speed * 1.5, "no faster");
		});
		helper.runAfterDelay(100, () -> {
			helper.assertTrue(!Status.has(medivac, Status.AFTERBURNERS), "afterburners last more than 4 s");
			helper.succeed();
		});
	}

	/**
	 * A medivac heals for energy (SC2: 1 for every 3 hit points); with none it does not heal — and a
	 * marine does not heal by itself (only zerg regenerate: it did, 0.27 a second).
	 */
	@GameTest(template = ARENA, batch = "terranMedivacEnergy", timeoutTicks = 200)
	public void medivacHealsForEnergy(GameTestHelper helper) {
		clear(helper);
		UUID player = UUID.randomUUID();
		StarUnit medivac = unit(helper, UnitKind.MEDIVAC, 10, 24, player), marine = unit(helper, UnitKind.MARINE, 11, 24, player);
		medivac.setNoAi(true);
		marine.setNoAi(true);
		StringBuilder trace = new StringBuilder();
		float[] last = {-1};
		helper.onEachTick(() -> {
			if (marine.getHealth() != last[0]) trace.append(helper.getTick()).append(':').append(marine.getHealth()).append('/').append(medivac.energy()).append(' ');
			last[0] = marine.getHealth();
		});
		helper.runAfterDelay(2, () -> {
			medivac.setEnergy(0);
			marine.setHealth(marine.getMaxHealth() / 3);
		});
		helper.runAfterDelay(42, () -> {
			helper.assertTrue(marine.getHealth() <= marine.getMaxHealth() / 3 + 1e-3, "healed without energy: " + marine.getHealth() + "; health/energy " + trace);
			medivac.setEnergy(50);
		});
		helper.runAfterDelay(82, () -> {
			float healed = marine.getHealth() - marine.getMaxHealth() / 3;
			helper.assertTrue(healed > 0, "no healing with energy");
			double spent = 50 - medivac.energy() + 40 * 0.7875 / 20; // what it got back over 2 s
			double expected = healed / UnitKind.SCALE / 3;
			helper.assertTrue(Math.abs(spent - expected) < 1.0, "healed " + healed / UnitKind.SCALE + " (SC2) for " + spent + " energy, expected " + expected);
			helper.succeed();
		});
	}

	/** A thor's hammers strike twice, 30 each (SC2): an ultralisk's armor (2) counts twice. */
	@GameTest(template = ARENA, batch = "terranThorHammers", timeoutTicks = 60)
	public void thorStrikesTwice(GameTestHelper helper) {
		clear(helper);
		StarUnit thor = unit(helper, UnitKind.THOR, 10, 24, UUID.randomUUID()), ultralisk = unit(helper, UnitKind.ULTRALISK, 20, 24, null);
		thor.setNoAi(true);
		ultralisk.setNoAi(true);
		helper.runAfterDelay(2, () -> {
			float before = ultralisk.getHealth();
			thor.performRangedAttack(ultralisk, 1);
			float lost = before - ultralisk.getHealth();
			double hit = thor.weapon().damage(), expected = hit - 2 * 2 * UnitKind.SCALE;
			helper.assertTrue(hit >= 59 * UnitKind.SCALE, "a thor's attack is " + hit / UnitKind.SCALE + ", not 60 (30 x2)");
			helper.assertTrue(Math.abs(lost - expected) < 1e-3, "a thor's attack took " + lost / UnitKind.SCALE + " of an ultralisk, expected "
				+ expected / UnitKind.SCALE + " (its armor 2 twice)");
			helper.succeed();
		});
	}


	/**
	 * A marine of a base stims only once its base has researched the stimpack, at a barracks with a tech
	 * lab (SC2); a lone wild marine (no base) keeps it.
	 */
	@GameTest(template = ARENA, batch = "terranStimResearch", timeoutTicks = 100)
	public void stimpackNeedsItsResearch(GameTestHelper helper) {
		clear(helper);
		StarUnit cc = commandCenter(helper);
		StarUnit lone = unit(helper, UnitKind.MARINE, 10, 10, null);
		lone.setNoAi(true);
		helper.runAfterDelay(5, () -> {
			Hive base = TestArena.base(helper, cc);
			helper.assertTrue(base != null, "no base");
			StarUnit marine = TestArena.member(helper, base, UnitKind.MARINE, 10, 24);
			StarUnit barracks = TestArena.member(helper, base, UnitKind.BARRACKS, 24, 10);
			marine.setNoAi(true);
			helper.assertTrue("message.starmine.need_research".equals(Abilities.refusal(marine, Ability.STIMPACK)), "a base's marine stims unresearched");
			helper.assertTrue(Abilities.refusal(lone, Ability.STIMPACK) == null, "a lone wild marine cannot stim");
			base.minerals = 500;
			base.vespene = 500;
			helper.assertFalse(barracks.startResearch("stimpack"), "the stimpack was researched without a tech lab");
			barracks.setMode(Modes.TECHLAB);
			helper.assertTrue(barracks.startResearch("stimpack"), "no stimpack research at a barracks with a tech lab");
			dev.starmine.race.Research.STIMPACK.apply(base);
			helper.assertTrue(Abilities.refusal(marine, Ability.STIMPACK) == null, "researched, the marine still cannot stim: " + Abilities.refusal(marine, Ability.STIMPACK));
			helper.succeed();
		});
	}

}
