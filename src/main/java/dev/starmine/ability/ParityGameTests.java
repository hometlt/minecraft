package dev.starmine.ability;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.clear;
import static dev.starmine.ability.TestArena.point;
import static dev.starmine.ability.TestArena.unit;

/**
 * Units and buildings doing what they do in SC2 LotV (owner 18.10: the audit of the mod against SC2): add-ons that
 * stay on the ground, a flying orbital, a warp gate's warp-in, a nydus' shared hold, a command center's SCVs, a bunker's
 * salvage and neosteel armor, an overlord's ventral sacs, stacked injects, one rider let out of a transport.
 */
public class ParityGameTests implements FabricGameTest {
	/** SC2: a barracks lifts off its tech lab; another barracks that lands on the site takes it, the first lands bare. */
	@GameTest(template = ARENA, batch = "parityAddOn", timeoutTicks = 600)
	public void anAddOnStaysOnTheGroundForTheNextBuilding(GameTestHelper helper) {
		clear(helper);
		StarUnit cc = unit(helper, UnitKind.COMMAND_CENTER, 34, 34, UUID.randomUUID());
		ServerLevel level = helper.getLevel();
		StarUnit[] barracks = new StarUnit[2];
		Vec3[] site = new Vec3[1];
		helper.runAfterDelay(5, () -> {
			Hive base = TestArena.base(helper, cc);
			helper.assertTrue(base != null, "no base");
			base.minerals = 1000;
			base.vespene = 1000;
			for (UnitKind kind : new UnitKind[] {UnitKind.SUPPLY_DEPOT, UnitKind.BARRACKS}) base.join(kind);
			barracks[0] = TestArena.member(helper, base, UnitKind.BARRACKS, 10, 10);
			barracks[1] = TestArena.member(helper, base, UnitKind.BARRACKS, 30, 10);
			barracks[0].setMode(Modes.TECHLAB);
			site[0] = barracks[0].position();
			helper.assertTrue(Abilities.cast(barracks[0], level, Ability.LIFT_OFF, null, null), "a barracks with a tech lab does not lift off");
			helper.assertTrue(Abilities.cast(barracks[1], level, Ability.LIFT_OFF, null, null), "no lift-off");
			helper.assertTrue(base.addOns.containsValue(Modes.TECHLAB), "the tech lab did not stay on the ground");
		});
		helper.runAfterDelay(60, () -> {
			barracks[0].command(StarUnit.Order.MOVE, point(helper, 10, 30));
			barracks[1].command(StarUnit.Order.MOVE, site[0]);
		});
		helper.runAfterDelay(300, () -> {
			for (StarUnit one : barracks) helper.assertTrue(Abilities.cast(one, level, Ability.LIFT_OFF, null, null), "no landing: " + TestArena.where(helper, one));
		});
		helper.runAfterDelay(400, () -> {
			helper.assertTrue(barracks[1].mode() == Modes.TECHLAB, "the barracks that landed on the site has no tech lab: mode " + barracks[1].mode());
			helper.assertTrue(barracks[0].mode() == Modes.NORMAL, "the barracks that left it kept it: mode " + barracks[0].mode());
			helper.assertTrue(barracks[1].train(UnitKind.MARAUDER), "no marauder at the taken tech lab");
			helper.assertTrue(TestArena.base(helper, cc).addOns.isEmpty(), "the tech lab is still on the ground");
			helper.succeed();
		});
	}

	/** SC2: an orbital command lifts off and lands an orbital again; a planetary fortress stays down. */
	@GameTest(template = ARENA, batch = "parityOrbital", timeoutTicks = 300)
	public void anOrbitalFliesAndLandsAnOrbital(GameTestHelper helper) {
		clear(helper);
		StarUnit cc = unit(helper, UnitKind.COMMAND_CENTER, 34, 34, UUID.randomUUID());
		StarUnit fortress = unit(helper, UnitKind.COMMAND_CENTER, 10, 10, null);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(5, () -> {
			cc.setMode(Modes.ORBITAL);
			fortress.setMode(Modes.PLANETARY);
			helper.assertTrue(!Abilities.available(fortress, Ability.LIFT_OFF), "a planetary fortress lifts off");
			helper.assertTrue(Abilities.cast(cc, level, Ability.LIFT_OFF, null, null), "an orbital does not lift off");
		});
		helper.runAfterDelay(60, () -> {
			helper.assertTrue(LiftOff.airborne(cc), "the orbital is not in the air");
			helper.assertTrue(Abilities.cast(cc, level, Ability.LIFT_OFF, null, null), "no landing");
		});
		helper.runAfterDelay(140, () -> {
			helper.assertTrue(cc.mode() == Modes.ORBITAL, "it landed as mode " + cc.mode());
			helper.assertTrue(Abilities.available(cc, Ability.MULE), "a landed orbital has no MULE");
			helper.succeed();
		});
	}

	/** SC2 LotV: a warp gate warps a zealot in at its powered rally point at once (16 s there), then cools down 20 s. */
	@GameTest(template = ARENA, batch = "parityWarpGate", timeoutTicks = 200)
	public void aWarpGateWarpsInAtItsRallyPoint(GameTestHelper helper) {
		clear(helper);
		StarUnit nexus = unit(helper, UnitKind.NEXUS, 40, 40, UUID.randomUUID());
		StarUnit[] gate = new StarUnit[1];
		Vec3 rally = point(helper, 8, 30);
		helper.runAfterDelay(5, () -> {
			Hive base = TestArena.base(helper, nexus);
			helper.assertTrue(base != null, "no base");
			base.minerals = 1000;
			for (UnitKind kind : new UnitKind[] {UnitKind.PYLON, UnitKind.GATEWAY}) base.join(kind);
			TestArena.member(helper, base, UnitKind.PYLON, 8, 20);
			gate[0] = TestArena.member(helper, base, UnitKind.GATEWAY, 8, 6);
			gate[0].setMode(Modes.WARPGATE);
			gate[0].setRally(rally);
			helper.assertTrue(gate[0].train(UnitKind.ZEALOT) && gate[0].train(UnitKind.ZEALOT), "no zealots ordered");
		});
		helper.runAfterDelay(60, () -> {
			List<StarUnit> zealots = helper.getLevel().getEntitiesOfClass(StarUnit.class, new net.minecraft.world.phys.AABB(rally, rally).inflate(4),
				unit -> unit.kind == UnitKind.ZEALOT);
			helper.assertTrue(zealots.size() == 1, zealots.size() + " zealots at the rally point after 3 s, not one");
			int left = Status.left(zealots.get(0), Status.WARPING);
			helper.assertTrue(left > 200, "the zealot warps in for " + left + " more ticks, not about 16 s");
			helper.assertTrue(gate[0].queue().size() == 1, "the second zealot came before the gate cooled down");
			helper.succeed();
		});
	}

	/** SC2: zerg loaded at one nydus end come out at another end of the base. */
	@GameTest(template = ARENA, batch = "parityNydus", timeoutTicks = 100)
	public void aNydusHoldIsSharedByItsEnds(GameTestHelper helper) {
		clear(helper);
		StarUnit hatchery = unit(helper, UnitKind.HATCHERY, 24, 24, UUID.randomUUID());
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(5, () -> {
			Hive base = TestArena.base(helper, hatchery);
			helper.assertTrue(base != null, "no hive");
			StarUnit network = TestArena.member(helper, base, UnitKind.NYDUS_NETWORK, 8, 8);
			StarUnit worm = TestArena.member(helper, base, UnitKind.NYDUS_CANAL, 40, 8);
			List<StarUnit> zerglings = new ArrayList<>();
			for (int i = 0; i < 3; i++) {
				StarUnit zergling = TestArena.member(helper, base, UnitKind.ZERGLING, 8 + i, 14);
				zergling.setNoAi(true);
				zerglings.add(zergling);
			}
			helper.assertTrue(Abilities.cast(network, level, Ability.LOAD, null, null), "the network loads nothing");
			helper.assertTrue(network.getPassengers().size() == 3, network.getPassengers().size() + " zerglings in the network, not 3");
			helper.assertTrue(Abilities.cast(worm, level, Ability.UNLOAD, null, worm.position()), "the worm lets nothing out");
			for (StarUnit zergling : zerglings)
				helper.assertTrue(!zergling.isPassenger() && zergling.distanceTo(worm) < 12, "a zergling did not come out of the worm: " + TestArena.where(helper, zergling));
			helper.succeed();
		});
	}

	/** SC2: a command center takes in 5 SCVs (an orbital none), and lets one chosen out alone. */
	@GameTest(template = ARENA, batch = "parityCommandCenter", timeoutTicks = 100)
	public void aCommandCenterCarriesFiveScvs(GameTestHelper helper) {
		clear(helper);
		StarUnit cc = unit(helper, UnitKind.COMMAND_CENTER, 24, 24, UUID.randomUUID());
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(5, () -> {
			Hive base = TestArena.base(helper, cc);
			helper.assertTrue(base != null, "no base");
			for (int i = 0; i < 6; i++) TestArena.member(helper, base, UnitKind.SCV, 20 + i, 18).setNoAi(true);
			TestArena.member(helper, base, UnitKind.MARINE, 28, 30).setNoAi(true);
			helper.assertTrue(Abilities.cast(cc, level, Ability.LOAD, null, null), "the command center loads nothing");
			helper.assertTrue(cc.getPassengers().size() == 5, cc.getPassengers().size() + " aboard, not 5 SCVs");
			helper.assertTrue(cc.getPassengers().stream().allMatch(rider -> rider instanceof StarUnit unit && unit.kind == UnitKind.SCV), "not only SCVs aboard");
			helper.assertTrue(Transport.unloadOne(cc, cc.getPassengers().get(0)) && cc.getPassengers().size() == 4, "one SCV was not let out alone");
			helper.assertTrue(Abilities.cast(cc, level, Ability.UNLOAD, null, cc.position()) && !cc.isVehicle(), "the SCVs stayed in");
			cc.setMode(Modes.ORBITAL);
			helper.assertTrue(!Abilities.available(cc, Ability.LOAD), "an orbital loads SCVs");
			helper.succeed();
		});
	}

	/** SC2: neosteel armor gives a bunker 2 more slots; salvage takes it down in 5 s, its garrison out, 75 minerals back. */
	@GameTest(template = ARENA, batch = "parityBunker", timeoutTicks = 200)
	public void aBunkerGrowsWithNeosteelAndIsSalvaged(GameTestHelper helper) {
		clear(helper);
		StarUnit cc = unit(helper, UnitKind.COMMAND_CENTER, 40, 40, UUID.randomUUID());
		ServerLevel level = helper.getLevel();
		StarUnit[] bunker = new StarUnit[1];
		int[] minerals = new int[1];
		List<StarUnit> marines = new ArrayList<>();
		helper.runAfterDelay(5, () -> {
			Hive base = TestArena.base(helper, cc);
			helper.assertTrue(base != null, "no base");
			bunker[0] = TestArena.member(helper, base, UnitKind.BUNKER, 12, 12);
			for (int i = 0; i < 7; i++) {
				StarUnit marine = TestArena.member(helper, base, UnitKind.MARINE, 9 + i, 17);
				marine.setNoAi(true);
				marines.add(marine);
			}
			helper.assertTrue(Abilities.cast(bunker[0], level, Ability.LOAD, null, null), "the bunker loads nothing");
			helper.assertTrue(bunker[0].getPassengers().size() == 4, bunker[0].getPassengers().size() + " marines in a bunker, not 4");
			dev.starmine.race.Research.NEOSTEEL_ARMOR.apply(base);
			helper.assertTrue(Abilities.cast(bunker[0], level, Ability.LOAD, null, null), "no more room with neosteel armor");
			helper.assertTrue(bunker[0].getPassengers().size() == 6, bunker[0].getPassengers().size() + " marines with neosteel armor, not 6");
			minerals[0] = base.minerals;
			helper.assertTrue(Abilities.cast(bunker[0], level, Ability.SALVAGE, null, null), "no salvage");
		});
		helper.runAfterDelay(80, () -> helper.assertTrue(bunker[0].isAlive(), "salvaged before 5 s"));
		helper.runAfterDelay(120, () -> {
			helper.assertTrue(bunker[0].isRemoved(), "the bunker still stands");
			helper.assertTrue(marines.stream().noneMatch(StarUnit::isPassenger) && marines.stream().allMatch(StarUnit::isAlive), "the garrison did not walk out");
			int back = TestArena.base(helper, cc).minerals - minerals[0];
			helper.assertTrue(back == UnitKind.BUNKER.economy().minerals() * 3 / 4, back + " minerals back, not 75 %");
			helper.succeed();
		});
	}

	/** SC2 LotV: a player's overlord carries nothing until it morphs its ventral sacs (25/25, 12 s, a lair first). */
	@GameTest(template = ARENA, batch = "parityOverlord", timeoutTicks = 400)
	public void anOverlordCarriesOnlyWithVentralSacs(GameTestHelper helper) {
		clear(helper);
		StarUnit hatchery = unit(helper, UnitKind.HATCHERY, 24, 24, UUID.randomUUID());
		ServerLevel level = helper.getLevel();
		StarUnit[] overlord = new StarUnit[1], zergling = new StarUnit[1];
		helper.runAfterDelay(5, () -> {
			Hive base = TestArena.base(helper, hatchery);
			helper.assertTrue(base != null, "no hive");
			base.minerals = 100;
			base.vespene = 100;
			overlord[0] = TestArena.member(helper, base, UnitKind.OVERLORD, 12, 12);
			overlord[0].setNoAi(true);
			zergling[0] = TestArena.member(helper, base, UnitKind.ZERGLING, 12, 13);
			zergling[0].setNoAi(true);
			helper.assertTrue(!Abilities.available(overlord[0], Ability.LOAD), "an overlord without sacs offers to load");
			helper.assertTrue("message.starmine.need_tech".equals(Abilities.refusal(overlord[0], Ability.VENTRAL_SACS)), "ventral sacs without a lair");
			base.tier = 2;
			helper.assertTrue(Abilities.cast(overlord[0], level, Ability.VENTRAL_SACS, null, null), "no ventral sacs with a lair");
			helper.assertTrue(base.minerals == 75 && base.vespene == 75, "the sacs are not paid");
		});
		helper.runAfterDelay(300, () -> {
			helper.assertTrue(overlord[0].mode() == Modes.SACS, "no sacs after 12 s");
			helper.assertTrue(Abilities.cast(overlord[0], level, Ability.LOAD, null, null) && zergling[0].getVehicle() == overlord[0], "the overlord with sacs took nobody");
			helper.succeed();
		});
	}

	/** SC2 LotV: a second inject on an injected hatchery queues and starts when the first is over. */
	@GameTest(template = ARENA, batch = "parityInject", timeoutTicks = 100)
	public void injectsStack(GameTestHelper helper) {
		clear(helper);
		StarUnit hatchery = unit(helper, UnitKind.HATCHERY, 24, 24, UUID.randomUUID());
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(5, () -> {
			Hive base = TestArena.base(helper, hatchery);
			helper.assertTrue(base != null, "no hive");
			StarUnit queen = TestArena.member(helper, base, UnitKind.QUEEN, 24, 30);
			queen.setNoAi(true);
			queen.setEnergy(50);
			helper.assertTrue(Abilities.cast(queen, level, Ability.INJECT_LARVA, hatchery, null), "no first inject");
			helper.assertTrue(Abilities.cast(queen, level, Ability.INJECT_LARVA, hatchery, null), "no second inject on an injected hatchery");
			helper.assertTrue(hatchery.abilities.queuedInjects == 1, "the second inject is not queued");
			hatchery.abilities.statuses.put(Status.INJECTED, 1); // the first is over now
		});
		helper.runAfterDelay(8, () -> {
			helper.assertTrue(Status.left(hatchery, Status.INJECTED) > ZergAbilities.INJECT_TICKS - 10, "the queued inject did not start");
			helper.assertTrue(hatchery.abilities.queuedInjects == 0, "the queued inject is still waiting");
			helper.succeed();
		});
	}

	/** Owner 18.10: the command card flies aircraft lower (down to a block over the ground) and higher. */
	@GameTest(template = ARENA, batch = "parityAltitude", timeoutTicks = 400)
	public void aircraftFlyLowerAndHigher(GameTestHelper helper) {
		clear(helper);
		StarUnit medivac = unit(helper, UnitKind.MEDIVAC, 24, 24, UUID.randomUUID());
		double[] usual = new double[1];
		helper.runAfterDelay(80, () -> {
			usual[0] = medivac.getY() - medivac.groundBelow(medivac.getX(), medivac.getZ());
			medivac.climb(-100);
		});
		helper.runAfterDelay(200, () -> {
			double low = medivac.getY() - medivac.groundBelow(medivac.getX(), medivac.getZ());
			// a block over the ground, and up to 3 more where it wanders idly (ZergGoals.HoverWander)
			helper.assertTrue(low < 5.5 && low < usual[0] - 3, "the medivac did not come down: " + low + " over the ground, usually " + usual[0]);
			medivac.climb(10);
		});
		helper.runAfterDelay(320, () -> {
			double now = medivac.getY() - medivac.groundBelow(medivac.getX(), medivac.getZ());
			helper.assertTrue(now > usual[0] + 3, "the medivac did not climb: " + now + " over the ground, usually " + usual[0]);
			helper.succeed();
		});
	}
}
