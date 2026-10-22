package dev.starmine.entity;

import dev.starmine.StarMine;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveLogic;
import dev.starmine.hive.HiveSavedData;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * In-game tests of terran bases (`gradlew runGametest`, arena `starmine:arena` as in
 * {@link DroneGameTests}): a command center founds a base with SCVs that mine ore and deliver it; a
 * barracks trains a marine; an SCV builds a supply depot where it is told; a marine follows a move
 * order. Each runs in a batch of its own with the world's hives cleared.
 */
public class TerranGameTests implements FabricGameTest {
	private static final String ARENA = "starmine:arena";

	@GameTest(template = ARENA, batch = "idleHomeFootprint", timeoutTicks = 40)
	public void idleUnitsReturnBesideMainInsteadOfIntoIt(GameTestHelper helper) {
		arena(helper);
		StarUnit cc = commandCenter(helper);
		helper.runAfterDelay(5, () -> {
			Hive base = base(helper);
			for (UnitKind kind : new UnitKind[] {UnitKind.MARINE, UnitKind.LARVA}) {
				StarUnit unit = HiveLogic.spawn(helper.getLevel(), base, kind, cc.position().add(10, 0, 0), null);
				unit.setNoAi(true);
				ZergGoals.ReturnHome goal = new ZergGoals.ReturnHome(unit, kind == UnitKind.LARVA ? 3 : 5);
				helper.assertFalse(goal.canUse(), kind + " tried to walk into its main building from the nearby guard ring");
				if (kind == UnitKind.MARINE) {
					unit.moveTo(cc.position().add(21, 0, 0));
					helper.assertTrue(goal.canUse(), "far marine should return to guard its base");
					unit.setNoAi(false);
					helper.succeedWhen(() -> {
						BlockPos to = unit.getNavigation().getTargetPos();
						helper.assertTrue(to != null && !cc.getBoundingBox().contains(Vec3.atBottomCenterOf(to).add(0, .1, 0)),
							"return destination is still inside the main building: " + to + " " + unit.goalsRunning());
					});
				}
			}
		});
	}

	@GameTest(template = ARENA, batch = "terranRepairResume", timeoutTicks = 1800)
	public void scvReturnsFromDefenceRepairsAndMines(GameTestHelper helper) {
		arena(helper);
		StarUnit cc = commandCenter(helper);
		Hive[] observed = {null};
		int[] lowestMinerals = {500};
		helper.onEachTick(() -> { if (observed[0] != null) lowestMinerals[0] = Math.min(lowestMinerals[0], observed[0].minerals); });
		helper.runAfterDelay(5, () -> {
			Hive base = base(helper);
			base.minerals = 500;
			observed[0] = base;
			var workers = helper.getLevel().getEntitiesOfClass(StarUnit.class, cc.getBoundingBox().inflate(48),
				u -> u.kind == UnitKind.SCV && base.id.equals(u.hiveId()));
			StarUnit scv = workers.getFirst();
			workers.stream().skip(1).forEach(StarUnit::discard);
			scv.moveTo(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(38, 2, 24))));
			BlockPos ore = helper.absolutePos(new BlockPos(38, 2, 28));
			helper.getLevel().setBlock(ore, Blocks.IRON_ORE.defaultBlockState(), 2);
			scv.commandWork(StarUnit.Work.GATHER, ore);
			cc.setHealth(cc.getMaxHealth() * .98f);
			var attacker = helper.spawn(net.minecraft.world.entity.EntityType.ZOMBIE, new BlockPos(40, 2, 24));
			attacker.setNoAi(true);
			scv.setTarget(attacker);
			helper.runAfterDelay(10, attacker::discard);
			helper.runAfterDelay(180, () -> {
				helper.assertTrue(scv.getTarget() == null, "SCV retained its dead defensive target");
				helper.assertTrue(cc.getHealth() == cc.getMaxHealth(), "idle SCV did not finish automatic repair");
				helper.assertTrue(lowestMinerals[0] < 500, "automatic repair did not pay minerals");
				helper.succeedWhen(() -> helper.assertTrue(base.collected.getOrDefault("iron", 0) > 0,
					"SCV did not resume gathering after defence and repair: " + scv.goalsRunning()));
			});
		});
	}

	@GameTest(template = ARENA, batch = "terranSnappedSite", timeoutTicks = 20)
	public void barracksChecksItsSnappedFootprintAndOuterTerrain(GameTestHelper helper) {
		arena(helper);
		ServerLevel level = helper.getLevel();
		StarUnit depot = StarMine.type(UnitKind.SUPPLY_DEPOT).create(level);
		depot.setNoAi(true);
		depot.moveTo(UnitKind.SUPPLY_DEPOT.gridCentre(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(20, 2, 20)))));
		level.addFreshEntity(depot);
		boolean found = false;
		for (int x = -12; x <= 12 && !found; x++) for (int z = -12; z <= 12 && !found; z++) {
			BlockPos chosen = depot.blockPosition().offset(x, 0, z);
			var raw = new net.minecraft.world.phys.AABB(chosen).inflate(dev.starmine.race.Placement.half(UnitKind.BARRACKS), 0,
				dev.starmine.race.Placement.half(UnitKind.BARRACKS)).expandTowards(0, UnitKind.BARRACKS.height, 0);
			if (!raw.intersects(depot.getBoundingBox()) && !dev.starmine.race.Placement.siteFree(level, UnitKind.BARRACKS, chosen)) found = true;
		}
		helper.assertTrue(found, "snapping must reject a raw free site that moves into a depot");
		BlockPos chosen = dev.starmine.race.Placement.site(level, UnitKind.BARRACKS, helper.absolutePos(new BlockPos(36, 2, 36)));
		level.setBlock(chosen.offset(dev.starmine.race.Placement.half(UnitKind.BARRACKS), 0, 0), Blocks.OBSIDIAN.defaultBlockState(), 2);
		helper.assertTrue("message.starmine.site_blocked".equals(dev.starmine.race.Placement.siteRefusal(level, UnitKind.BARRACKS, chosen, java.util.UUID.randomUUID())),
			"site search missed an obstruction at the outer footprint edge");
		helper.succeed();
	}

	private static void arena(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (BlockPos rel : BlockPos.betweenClosed(0, 0, 0, 47, 11, 47))
			level.setBlock(helper.absolutePos(rel), rel.getY() == 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
		HiveSavedData.get(level.getServer()).clear();
	}

	/** A command center at the arena's middle; its first tick founds the base (no player near: no owner). */
	private static StarUnit commandCenter(GameTestHelper helper) {
		StarUnit cc = StarMine.type(UnitKind.COMMAND_CENTER).create(helper.getLevel());
		cc.setOwner(java.util.UUID.randomUUID()); // a player's base (each test its own player)
		Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(24, 2, 24)));
		cc.moveTo(at.x, at.y, at.z, 0, 0);
		helper.getLevel().addFreshEntity(cc);
		return cc;
	}

	private static Hive base(GameTestHelper helper) {
		return HiveSavedData.get(helper.getLevel().getServer()).all().stream().filter(hive -> hive.race == UnitKind.Race.TERRAN).findFirst().orElse(null);
	}

	@GameTest(template = ARENA, batch = "terranMine", timeoutTicks = 4500)
	public void scvsMineAndDeliver(GameTestHelper helper) {
		arena(helper);
		commandCenter(helper);
		for (int x = 31; x <= 33; x++)
			for (int z = 31; z <= 33; z++) helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, 2, z)), Blocks.IRON_ORE.defaultBlockState(), 2);
		helper.succeedWhen(() -> {
			Hive base = base(helper);
			helper.assertTrue(base != null, "no base was founded");
			helper.assertTrue(base.count(UnitKind.SCV) == 4, "the base has " + base.count(UnitKind.SCV) + " SCVs");
			String scvs = helper.getLevel().getEntitiesOfClass(StarUnit.class, new net.minecraft.world.phys.AABB(helper.absolutePos(BlockPos.ZERO)).inflate(64),
				mob -> mob.kind == UnitKind.SCV).stream().map(mob -> helper.relativePos(mob.blockPosition()).toShortString() + " abs " + mob.blockPosition().toShortString() + " cargo " + mob.cargoBlocks
					+ " target " + mob.getTarget() + " goals " + mob.goalsRunning()).toList().toString();
			helper.assertTrue(base.collected.getOrDefault("iron", 0) > 0, "no iron delivered yet; minerals " + base.minerals + " home " + base.hatchery.toShortString() + " " + scvs);
			helper.assertTrue(base.minerals > 50, "minerals not counted");
		});
	}

	@GameTest(template = ARENA, batch = "terranTrain", timeoutTicks = 1200)
	public void barracksTrainsMarine(GameTestHelper helper) {
		arena(helper);
		commandCenter(helper);
		helper.runAfterDelay(5, () -> {
			Hive base = base(helper);
			helper.assertTrue(base != null, "no base was founded");
			base.minerals = 1000;
			for (UnitKind kind : new UnitKind[] {UnitKind.SUPPLY_DEPOT, UnitKind.BARRACKS}) base.join(kind);
			StarUnit barracks = HiveLogic.spawn(helper.getLevel(), base, UnitKind.BARRACKS, Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(8, 2, 8))), null);
			helper.assertTrue(barracks != null && barracks.train(UnitKind.MARINE), "the barracks did not take the order");
			helper.succeedWhen(() -> helper.assertTrue(base.count(UnitKind.MARINE) == 1, "no marine yet"));
		});
	}

	@GameTest(template = ARENA, batch = "terranBuild", timeoutTicks = 2100)
	public void scvBuildsSupplyDepot(GameTestHelper helper) {
		arena(helper);
		commandCenter(helper);
		helper.runAfterDelay(5, () -> {
			Hive base = base(helper);
			helper.assertTrue(base != null, "no base was founded");
			base.minerals = 500;
			StarUnit scv = helper.getLevel().getEntitiesOfClass(StarUnit.class, new net.minecraft.world.phys.AABB(helper.absolutePos(BlockPos.ZERO)).inflate(64),
				mob -> mob.kind == UnitKind.SCV && base.id.equals(mob.hiveId())).stream().findFirst().orElse(null); // the world keeps SCVs of earlier runs
			helper.assertTrue(scv != null, "no SCV");
			scv.orderBuild(UnitKind.SUPPLY_DEPOT, helper.absolutePos(new BlockPos(10, 2, 36)));
			helper.succeedWhen(() -> helper.assertTrue(base.count(UnitKind.SUPPLY_DEPOT) == 1, "no finished depot; minerals " + base.minerals));
		});
	}

	/**
	 * Owner 14.10: an army outside a closed stone room waits while SCVs cut a way as wide
	 * and tall as its largest ground unit (a siege tank, 3 blocks), then goes on to the goal.
	 */
	@GameTest(template = ARENA, batch = "terranEngineering", timeoutTicks = 5400)
	public void scvsCutAWayForTheArmy(GameTestHelper helper) {
		arena(helper);
		ServerLevel level = helper.getLevel();
		// a closed stone room (walls 2 thick, 10 high) around the goal: no way around
		for (BlockPos rel : BlockPos.betweenClosed(24, 2, 18, 45, 11, 42)) {
			boolean inside = rel.getX() >= 26 && rel.getX() <= 43 && rel.getZ() >= 20 && rel.getZ() <= 40;
			if (!inside) level.setBlock(helper.absolutePos(rel), Blocks.STONE.defaultBlockState(), 2);
		}
		StarUnit cc = commandCenter(helper);
		helper.runAfterDelay(5, () -> {
			Hive base = cc.hiveId() == null ? null : HiveSavedData.get(level.getServer()).get(cc.hiveId());
			helper.assertTrue(base != null, "no base was founded");
			base.minerals = 500;
			for (int i = 0; i < 3; i++) HiveLogic.spawn(level, base, UnitKind.SCV, Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(14, 2, 10 + 2 * i))), null);
			StarUnit tank = HiveLogic.spawn(level, base, UnitKind.SIEGE_TANK, Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(12, 2, 30))), null);
			StarUnit marine = HiveLogic.spawn(level, base, UnitKind.MARINE, Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(10, 2, 33))), null);
			Vec3 goal = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(35, 2, 30)));
			helper.runAfterDelay(5, () -> {
				java.util.Set<java.util.UUID> taken = dev.starmine.colony.Engineering.order(level, base, java.util.List.of(tank, marine), StarUnit.Order.MOVE, goal);
				helper.assertTrue(taken.contains(tank.getUUID()), "the army was not given a way to cut");
				helper.succeedWhen(() -> {
					if (tank.position().distanceTo(goal) < 4) return;
					net.minecraft.world.level.pathfinder.Path path = tank.getNavigation().getPath();
					StringBuilder others = new StringBuilder();
					for (StarUnit other : level.getEntitiesOfClass(StarUnit.class, tank.getBoundingBox().inflate(8), other -> other != tank && !other.kind.structure()))
						others.append(other.kind.id).append(' ').append(dev.starmine.ability.TestArena.where(helper, other)).append("; ");
					StringBuilder hole = new StringBuilder(); // the open cells of the outer wall (x 24-25) at the tank's feet and head height
					for (int z = 18; z <= 42; z++) {
						boolean open = true;
						for (int x = 24; x <= 25; x++)
							for (int y = 2; y <= 4; y++) open &= level.getBlockState(helper.absolutePos(new BlockPos(x, y, z))).isAir();
						if (open) hole.append(z).append(' ');
					}
					helper.fail("the tank (" + tank.getBbWidth() + " wide) is at " + dev.starmine.ability.TestArena.where(helper, tank)
						+ ", the wall is open at z " + hole + ", order " + tank.order()
						+ ", job " + dev.starmine.colony.Engineering.describe(tank.getUUID()) + ", current path "
						+ (path == null ? "none" : path.canReach() + " to " + path.getTarget() + " in " + path.getNodeCount() + " nodes")
						+ ", near it " + others);
				});
			});
		});
	}

	@GameTest(template = ARENA, batch = "terranMove", timeoutTicks = 600)
	public void marineMoves(GameTestHelper helper) {
		arena(helper);
		commandCenter(helper);
		helper.runAfterDelay(5, () -> {
			Hive base = base(helper);
			helper.assertTrue(base != null, "no base was founded");
			StarUnit marine = HiveLogic.spawn(helper.getLevel(), base, UnitKind.MARINE, Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(6, 2, 6))), null);
			Vec3 goal = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(40, 2, 8)));
			marine.command(StarUnit.Order.MOVE, goal);
			helper.succeedWhen(() -> helper.assertTrue(marine.position().distanceTo(goal) < 2.5 && marine.order() == StarUnit.Order.NONE,
				"the marine is at " + marine.position() + ", order " + marine.order()));
		});
	}

	/** A worker's build order reaches its player's client (the site's ghost) until the building is put down. */
	@GameTest(template = ARENA, batch = "terranPlan", timeoutTicks = 40)
	public void workersBuildPlanIsShown(GameTestHelper helper) {
		StarUnit scv = helper.spawn(StarMine.type(UnitKind.SCV), new BlockPos(10, 2, 10));
		scv.setNoAi(true);
		BlockPos site = helper.absolutePos(new BlockPos(24, 2, 24));
		scv.orderBuild(UnitKind.BARRACKS, site);
		StarUnit.Plan plan = scv.plannedBuild();
		helper.assertTrue(plan != null && plan.kind() == UnitKind.BARRACKS && plan.site().equals(site), "no plan: " + plan);
		scv.setConstructing(java.util.UUID.randomUUID()); // put down: it builds it now
		helper.assertTrue(scv.plannedBuild() == null, "a building under way still shows as planned");
		scv.setConstructing(null);
		scv.cancelBuild();
		helper.assertTrue(scv.plannedBuild() == null, "a cancelled order still shows as planned");
		helper.succeed();
	}
}
