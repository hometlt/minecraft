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
