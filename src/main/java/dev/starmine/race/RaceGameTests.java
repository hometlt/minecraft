package dev.starmine.race;

import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveLogic;
import dev.starmine.hive.HiveSavedData;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Every race playable (owner 14.10): a player's nexus founds a base with probes that warp in a pylon
 * and, in its power, a gateway that trains a zealot (a gateway out of power is refused); a player's
 * hatchery founds a hive whose larva morphs into a drone.
 */
public class RaceGameTests implements FabricGameTest {
	private static final String ARENA = "starmine:arena";

	private static void arena(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (BlockPos rel : BlockPos.betweenClosed(0, 0, 0, 47, 11, 47))
			level.setBlock(helper.absolutePos(rel), rel.getY() == 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
	}

	/** A main building of a fresh player (spawned as from that player's egg). */
	private static StarUnit main(GameTestHelper helper, UnitKind kind, int x, int z) {
		StarUnit unit = StarMine.type(kind).create(helper.getLevel());
		Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(x, 2, z)));
		unit.moveTo(at.x, at.y, at.z, 0, 0);
		unit.setOwner(UUID.randomUUID());
		helper.getLevel().addFreshEntity(unit);
		return unit;
	}

	private static Hive base(GameTestHelper helper, StarUnit main) {
		return main.hiveId() == null ? null : HiveSavedData.get(helper.getLevel().getServer()).get(main.hiveId());
	}

	private static StarUnit nearest(GameTestHelper helper, Hive base, UnitKind kind, StarUnit near) {
		return helper.getLevel().getEntitiesOfClass(StarUnit.class, new AABB(near.blockPosition()).inflate(48),
				mob -> mob.kind == kind && base.id.equals(mob.hiveId()) && mob.isAlive())
			.stream().min(java.util.Comparator.comparingDouble(mob -> mob.distanceToSqr(near))).orElse(null);
	}

	@GameTest(template = ARENA, batch = "protoss", timeoutTicks = 3600)
	public void probesWarpPylonGatewayAndZealot(GameTestHelper helper) {
		arena(helper);
		StarUnit nexus = main(helper, UnitKind.NEXUS, 10, 24);
		helper.runAfterDelay(5, () -> {
			Hive base = base(helper, nexus);
			helper.assertTrue(base != null && base.count(UnitKind.PROBE) == 4, "the nexus founded no base with 4 probes");
			base.minerals = 2000;
			StarUnit probe = nearest(helper, base, UnitKind.PROBE, nexus);
			BlockPos gatewaySite = helper.absolutePos(new BlockPos(32, 2, 24)); // in the pylon's power, clear of it
			// out of power: refused
			helper.assertTrue(ProtossRace.INSTANCE.siteRefusal(helper.getLevel(), UnitKind.GATEWAY, gatewaySite, base.id) != null,
				"a gateway may stand without power");
			BlockPos pylonSite = helper.absolutePos(new BlockPos(26, 2, 24));
			helper.succeedWhen(() -> {
				if (base.count(UnitKind.PYLON) + base.pending(UnitKind.PYLON) == 0 && probe.buildKind() == null) probe.orderBuild(UnitKind.PYLON, pylonSite);
				helper.assertTrue(base.count(UnitKind.PYLON) == 1, "no finished pylon yet; probe " + probe.goalsRunning());
				StarUnit gateway = nearest(helper, base, UnitKind.GATEWAY, nexus);
				if (gateway == null) {
					if (base.pending(UnitKind.GATEWAY) == 0 && probe.buildKind() == null) probe.orderBuild(UnitKind.GATEWAY, gatewaySite);
					helper.fail("no gateway yet; probe " + probe.buildKind() + " " + probe.goalsRunning() + " at " + helper.relativePos(probe.blockPosition()).toShortString()
						+ " refusal " + Placement.refusal(base, UnitKind.GATEWAY) + " power " + ProtossRace.INSTANCE.siteRefusal(helper.getLevel(), UnitKind.GATEWAY, gatewaySite, base.id)
						+ " free " + Placement.siteFree(helper.getLevel(), UnitKind.GATEWAY, gatewaySite));
				}
				helper.assertTrue(!gateway.underConstruction(), "the gateway is still warping in");
				if (gateway.queue().isEmpty() && base.count(UnitKind.ZEALOT) == 0) gateway.train(UnitKind.ZEALOT);
				helper.assertTrue(base.count(UnitKind.ZEALOT) == 1, "no zealot yet");
			});
		});
	}

	/** A wild base (no owner) runs the common AI: supply, workers, its buildings and warriors (`warrior` or another its tech allows). */
	private static void wildBaseGrows(GameTestHelper helper, UnitKind mainKind, UnitKind building, UnitKind warrior) {
		arena(helper);
		StarUnit main = StarMine.type(mainKind).create(helper.getLevel());
		Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(24, 2, 24)));
		main.moveTo(at.x, at.y, at.z, 0, 0);
		helper.getLevel().addFreshEntity(main);
		helper.runAfterDelay(5, () -> {
			Hive base = base(helper, main);
			helper.assertTrue(base != null && base.owner == null, "no wild base");
			base.minerals = 3000;
			base.vespene = 1000;
			// the AI picks its warriors at random among what its tech allows: any warrior of the race counts
			helper.succeedWhen(() -> helper.assertTrue(base.count(building) > 0 && base.members.keySet().stream().anyMatch(kind -> kind == warrior
					|| kind.race == warrior.race && kind.aggressive && !kind.worker() && !kind.structure()),
				"the AI has " + base.members + " pending " + base.pending + " order " + base.buildOrder + " minerals " + base.minerals));
		});
	}

	@GameTest(template = ARENA, batch = "wildTerran", timeoutTicks = 4800)
	public void wildTerranBaseTrainsMarines(GameTestHelper helper) {
		wildBaseGrows(helper, UnitKind.COMMAND_CENTER, UnitKind.BARRACKS, UnitKind.MARINE);
	}

	@GameTest(template = ARENA, batch = "wildProtoss", timeoutTicks = 4800)
	public void wildProtossBaseTrainsZealots(GameTestHelper helper) {
		wildBaseGrows(helper, UnitKind.NEXUS, UnitKind.GATEWAY, UnitKind.ZEALOT);
	}

	/** A zergling of a player's hive morphs into a baneling (owner 14.10): pays the difference, a cocoon, a baneling. */
	@GameTest(template = ARENA, batch = "zergMorph", timeoutTicks = 1200)
	public void zerglingMorphsIntoBaneling(GameTestHelper helper) {
		arena(helper);
		StarUnit hatchery = main(helper, UnitKind.HATCHERY, 24, 24);
		helper.runAfterDelay(5, () -> {
			Hive base = base(helper, hatchery);
			helper.assertTrue(base != null, "no hive");
			base.join(UnitKind.SPAWNING_POOL);
			base.join(UnitKind.BANELING_NEST);
			StarUnit zergling = StarMine.type(UnitKind.ZERGLING).create(helper.getLevel());
			Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(34, 2, 24)));
			zergling.moveTo(at.x, at.y, at.z, 0, 0);
			zergling.joinHive(base);
			helper.getLevel().addFreshEntity(zergling);
			base.minerals = 100;
			base.vespene = 100;
			helper.assertTrue(Morph.price(UnitKind.BANELING).minerals() == 25 && Morph.price(UnitKind.BANELING).vespene() == 25, "a baneling morph is not 25/25");
			helper.assertTrue(Morph.start(zergling, helper.getLevel(), base, UnitKind.BANELING), "the zergling did not morph");
			helper.assertTrue(base.minerals == 75 && base.vespene == 75 && base.pending(UnitKind.BANELING) == 1, "not paid");
			helper.succeedWhen(() -> helper.assertTrue(base.count(UnitKind.BANELING) == 1 && base.count(UnitKind.ZERGLING) == 0,
				"no baneling yet: " + base.members));
		});
	}

	@GameTest(template = ARENA, batch = "zergPlayer", timeoutTicks = 1600)
	public void playerHiveMorphsLarva(GameTestHelper helper) {
		arena(helper);
		StarUnit hatchery = main(helper, UnitKind.HATCHERY, 24, 24);
		helper.runAfterDelay(5, () -> {
			Hive base = base(helper, hatchery);
			helper.assertTrue(base != null && base.owner != null && base.count(UnitKind.DRONE) == 4, "the hatchery founded no player hive with 4 drones");
			base.minerals = 500;
			helper.succeedWhen(() -> {
				StarUnit larva = nearest(helper, base, UnitKind.LARVA, hatchery);
				if (base.count(UnitKind.DRONE) < 5 && larva != null && base.pending(UnitKind.DRONE) == 0) HiveLogic.morph(larva, helper.getLevel(), base, UnitKind.DRONE, 1, false);
				helper.assertTrue(base.count(UnitKind.DRONE) == 5, "no drone hatched from a larva yet");
			});
		});
	}
}
