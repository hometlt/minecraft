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

	@GameTest(template = ARENA, batch = "terranViking", timeoutTicks = 300)
	public void vikingLandsAndTakesOff(GameTestHelper helper) {
		clear(helper);
		StarUnit viking = unit(helper, UnitKind.VIKING, 10, 24, UUID.randomUUID());
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> helper.assertTrue(Abilities.cast(viking, level, Ability.VIKING_LAND, null, null), "no landing"));
		helper.runAfterDelay(100, () -> {
			helper.assertTrue(viking.mode() == Modes.LANDED && !viking.flying() && !viking.isNoGravity(), "not landed");
			helper.assertTrue(viking.onGround(), "not on the ground: y " + (viking.getY() - helper.absolutePos(BlockPos.ZERO).getY()));
			helper.assertTrue(viking.weapon().range() < UnitKind.VIKING.range, "still the air weapon");
			helper.assertTrue(Abilities.cast(viking, level, Ability.VIKING_LAND, null, null), "no take-off");
		});
		helper.runAfterDelay(160, () -> {
			helper.assertTrue(viking.mode() == Modes.NORMAL && viking.flying() && viking.isNoGravity(), "not flying again");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "terranHellbat", timeoutTicks = 200)
	public void hellionBecomesHellbat(GameTestHelper helper) {
		clear(helper);
		StarUnit hellion = unit(helper, UnitKind.HELLION, 10, 24, UUID.randomUUID());
		hellion.setNoAi(true);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> helper.assertTrue(Abilities.cast(hellion, level, Ability.HELLBAT, null, null), "no hellbat"));
		helper.runAfterDelay(60, () -> {
			helper.assertTrue(hellion.mode() == Modes.HELLBAT && hellion.biological(), "not a hellbat");
			helper.assertTrue(hellion.getMaxHealth() > UnitKind.HELLION.health + 10, "no more health: " + hellion.getMaxHealth());
			// SC2 hellbat flame reach 2 against the hellion's 5
			helper.assertTrue(hellion.weapon().range() <= 2 * UnitKind.BLOCKS_PER_UNIT + 1 && hellion.weapon().range() < UnitKind.HELLION.range / 2,
				"not a short flame: " + hellion.weapon().range());
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
			helper.assertTrue(thor.weapon().range() > UnitKind.THOR.range + 3, "the high impact payload reaches " + thor.weapon().range());
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
		helper.runAfterDelay(5, () -> {
			Hive base = TestArena.base(helper, cc);
			helper.assertTrue(base != null, "no base");
			base.minerals = 1000;
			base.vespene = 1000;
			// the base's SCVs stand round its command center (15 wide now), within the crawler's reach: they would fight it
			for (StarUnit scv : level.getEntitiesOfClass(StarUnit.class, cc.getBoundingBox().inflate(40), unit -> unit.kind == UnitKind.SCV)) scv.discard();
			StarUnit ghost = TestArena.member(helper, base, UnitKind.GHOST, 6, 10);
			ghost.setNoAi(true);
			helper.assertTrue(Abilities.cast(ghost, level, Ability.NUKE, null, crawler.position()), "no nuke: " + Abilities.refusal(ghost, Ability.NUKE));
			helper.assertTrue(base.minerals == 900 && base.vespene == 900, "the nuke is not paid");
		});
		helper.runAfterDelay(205, () -> helper.assertTrue(crawler.getHealth() == crawler.getMaxHealth(), "the nuke landed early: the crawler has "
			+ crawler.getHealth() + " of " + crawler.getMaxHealth() + ", hurt by " + crawler.getLastHurtByMob() + " with " + crawler.getLastDamageSource()));
		helper.runAfterDelay(305, () -> {
			helper.assertTrue(!crawler.isAlive() || crawler.getHealth() < crawler.getMaxHealth() * 0.2, "the nuke left " + crawler.getHealth());
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
			helper.assertTrue(Abilities.cast(cc, level, Ability.ORBITAL_COMMAND, null, null), "no orbital");
			helper.assertTrue(base.minerals == 850, "the orbital is not paid");
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
			helper.assertTrue(!marine.canCollideWith(depot), "the lowered depot stops its own marine");
			helper.assertTrue(zergling.canCollideWith(depot), "the lowered depot lets a foe through");
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
