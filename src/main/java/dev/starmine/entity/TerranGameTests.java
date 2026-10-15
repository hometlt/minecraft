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

	@GameTest(template = ARENA, batch = "terranMine", timeoutTicks = 3000)
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

	@GameTest(template = ARENA, batch = "terranTrain", timeoutTicks = 800)
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

	@GameTest(template = ARENA, batch = "terranBuild", timeoutTicks = 1400)
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
	@GameTest(template = ARENA, batch = "terranEngineering", timeoutTicks = 3600)
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
					net.minecraft.world.level.pathfinder.Path path = tank.getNavigation().createPath(net.minecraft.core.BlockPos.containing(goal), 1);
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
						+ ", job " + dev.starmine.colony.Engineering.describe(tank.getUUID()) + ", a way now "
						+ (path == null ? "none" : path.canReach() + " to " + path.getTarget() + " in " + path.getNodeCount() + " nodes")
						+ ", near it " + others);
				});
			});
		});
	}

	@GameTest(template = ARENA, batch = "terranMove", timeoutTicks = 400)
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
}
