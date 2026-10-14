package dev.starmine.entity;

import dev.starmine.StarMine;
import dev.starmine.colony.TerranLogic;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveLogic;
import dev.starmine.hive.HiveSavedData;
import dev.starmine.vespene.Vespene;
import dev.starmine.vespene.VespeneBlock;
import dev.starmine.vespene.VespeneRootBlockEntity;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * In-game tests of vespene (owner 14.10; arena `starmine:arena` as in {@link DroneGameTests}): an
 * emptied outlet is fed from its vein; an empty vein grows back outlet first; SCVs draw vespene inside
 * a refinery and pump it at a bare outlet; iron golems leave terrans alone until terrans hurt one.
 */
public class VespeneGameTests implements FabricGameTest {
	private static final String ARENA = "starmine:arena";

	private static void arena(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (BlockPos rel : BlockPos.betweenClosed(0, 0, 0, 47, 11, 47))
			level.setBlock(helper.absolutePos(rel), rel.getY() == 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
		HiveSavedData.get(level.getServer()).clear();
	}

	/** A small vein under (x, z): the root, `cells` channel cells, the outlet in the arena floor (y 1). Returns the outlet. */
	private static BlockPos vein(GameTestHelper helper, int x, int z, int cells) {
		ServerLevel level = helper.getLevel();
		BlockPos root = helper.absolutePos(new BlockPos(x, -cells, z));
		List<BlockPos> channel = new ArrayList<>();
		for (int i = 1; i <= cells; i++) channel.add(root.above(i));
		BlockPos outlet = root.above(cells + 1);
		level.setBlock(root, Vespene.ROOT.defaultBlockState(), 2);
		for (BlockPos cell : channel) level.setBlock(cell, Vespene.VESPENE.defaultBlockState(), 2);
		level.setBlock(outlet, Vespene.VESPENE.defaultBlockState().setValue(VespeneBlock.OUTLET, true), 2);
		if (!(level.getBlockEntity(root) instanceof VespeneRootBlockEntity entity)) throw new IllegalStateException("no vein root");
		entity.setVein(channel, List.of(outlet));
		return outlet;
	}

	private static StarUnit commandCenter(GameTestHelper helper) {
		StarUnit cc = StarMine.type(UnitKind.COMMAND_CENTER).create(helper.getLevel());
		Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(24, 2, 24)));
		cc.moveTo(at.x, at.y, at.z, 0, 0);
		helper.getLevel().addFreshEntity(cc);
		return cc;
	}

	/** The base its command center founded (other tests running at the same time have bases too). */
	private static Hive base(GameTestHelper helper, StarUnit cc) {
		return cc.hiveId() == null ? null : HiveSavedData.get(helper.getLevel().getServer()).get(cc.hiveId());
	}

	/** The world feature in a far chunk: a root at the bedrock, a full vein, outlets with cobblestone around and air above. */
	@GameTest(template = ARENA, batch = "vespeneFeature", timeoutTicks = 100)
	public void veinFeatureBuildsAFullVein(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos origin = helper.absolutePos(BlockPos.ZERO).offset(512, 0, 512);
		int cx = (origin.getX() & ~15) + 8, cz = (origin.getZ() & ~15) + 8;
		boolean placed = Vespene.VEIN.place(net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration.INSTANCE, level,
			level.getChunkSource().getGenerator(), net.minecraft.util.RandomSource.create(42), origin);
		helper.assertTrue(placed, "the vein feature placed nothing");
		BlockPos root = new BlockPos(cx, level.getMinBuildHeight() + 1, cz);
		helper.assertTrue(level.getBlockEntity(root) instanceof VespeneRootBlockEntity, "no vein root at " + root.toShortString());
		VespeneRootBlockEntity vein = (VespeneRootBlockEntity) level.getBlockEntity(root);
		helper.assertTrue(vein.outletCount() > 0, "a vein without outlets");
		helper.assertTrue(vein.content(level) == vein.capacity(), "the vein is not full: " + vein.content(level) + " of " + vein.capacity());
		for (int i = 0; i < vein.outletCount(); i++) {
			BlockPos outlet = vein.outlet(i);
			helper.assertTrue(Vespene.isOutlet(level.getBlockState(outlet)), "no outlet at " + outlet.toShortString());
			helper.assertTrue(level.getBlockState(outlet.above()).isAir(), "no air over the outlet at " + outlet.toShortString());
			helper.assertTrue(level.getBlockState(outlet.below().north()).is(Blocks.COBBLESTONE) || level.getBlockState(outlet.north()).is(Blocks.COBBLESTONE),
				"no cobblestone around the outlet at " + outlet.toShortString());
		}
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "vespeneRefill", timeoutTicks = 400)
	public void outletIsFedFromItsVein(GameTestHelper helper) {
		arena(helper);
		BlockPos outlet = vein(helper, 10, 10, 3);
		ServerLevel level = helper.getLevel();
		VespeneBlock.take(level, outlet, level.getBlockState(outlet));
		helper.assertTrue(level.getBlockState(outlet).is(Vespene.VENT), "a taken outlet is not an empty vent");
		helper.succeedWhen(() -> {
			helper.assertTrue(level.getBlockState(outlet).is(Vespene.VESPENE), "the outlet was not filled again");
			helper.assertTrue(level.getBlockState(outlet.below()).isAir(), "the vein did not give its top vespene");
			helper.assertTrue(level.getBlockState(outlet.below(3)).is(Vespene.VESPENE), "the vein bottom emptied");
		});
	}

	@GameTest(template = ARENA, batch = "vespeneRegrow", timeoutTicks = 900)
	public void emptyVeinGrowsBackOutletFirst(GameTestHelper helper) {
		arena(helper);
		BlockPos outlet = vein(helper, 10, 10, 2);
		ServerLevel level = helper.getLevel();
		level.setBlock(outlet, Vespene.VENT.defaultBlockState(), 2);
		level.setBlock(outlet.below(), Blocks.AIR.defaultBlockState(), 2);
		level.setBlock(outlet.below(2), Blocks.AIR.defaultBlockState(), 2);
		helper.succeedWhen(() -> {
			helper.assertTrue(level.getBlockState(outlet).is(Vespene.VESPENE), "the outlet has not grown back");
			helper.assertTrue(level.getBlockState(outlet.below(2)).isAir(), "the vein grew before its outlet");
		});
	}

	@GameTest(template = ARENA, batch = "vespeneRefinery", timeoutTicks = 1600)
	public void scvsDrawVespeneInARefinery(GameTestHelper helper) {
		arena(helper);
		BlockPos outlet = vein(helper, 36, 24, 2);
		StarUnit cc = commandCenter(helper);
		helper.runAfterDelay(5, () -> {
			Hive base = base(helper, cc);
			helper.assertTrue(base != null, "no base was founded");
			HiveLogic.spawn(helper.getLevel(), base, UnitKind.REFINERY, Vec3.atBottomCenterOf(outlet.above()), null);
			for (int i = 0; i < 3; i++) HiveLogic.spawn(helper.getLevel(), base, UnitKind.SCV, Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(30, 2, 20 + i * 2))), null);
			helper.succeedWhen(() -> helper.assertTrue(base.vespene >= Vespene.PER_TRIP && base.collected.getOrDefault("vespene", 0) > 0,
				"no vespene delivered; vespene " + base.vespene));
		});
	}

	@GameTest(template = ARENA, batch = "vespenePump", timeoutTicks = 1200)
	public void scvPumpsABareOutlet(GameTestHelper helper) {
		arena(helper);
		BlockPos outlet = vein(helper, 34, 14, 2);
		StarUnit cc = commandCenter(helper);
		helper.runAfterDelay(5, () -> {
			Hive base = base(helper, cc);
			helper.assertTrue(base != null, "no base was founded");
			// ownerless test command centers join one base (an expansion brings no SCVs): this arena's own SCV
			StarUnit scv = HiveLogic.spawn(helper.getLevel(), base, UnitKind.SCV, Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(28, 2, 18))), null);
			helper.assertTrue(scv != null, "no SCV");
			scv.commandWork(StarUnit.Work.GATHER, outlet);
			helper.succeedWhen(() -> helper.assertTrue(base.vespene >= Vespene.PER_TRIP, "no vespene pumped; vespene " + base.vespene
				+ "; SCV at " + helper.relativePos(scv.blockPosition()).toShortString() + " cargo " + scv.cargoBlocks + " " + scv.goalsRunning()
				+ "; outlet " + helper.getLevel().getBlockState(outlet)));
		});
	}

	/** A hive with 6 drones (its hatchery supplies 6) and a vein outlet nearby mutates a drone into an extractor, and drones bring vespene home. */
	@GameTest(template = ARENA, batch = "vespeneExtractor", timeoutTicks = 2400)
	public void hiveBuildsAnExtractorAndDrawsVespene(GameTestHelper helper) {
		arena(helper);
		BlockPos outlet = vein(helper, 36, 24, 2);
		StarUnit hatchery = helper.spawn(StarMine.type(UnitKind.HATCHERY), new BlockPos(14, 2, 24));
		List<StarUnit> drones = new ArrayList<>();
		for (int i = 0; i < 6; i++) drones.add(helper.spawn(StarMine.type(UnitKind.DRONE), new BlockPos(24 + i % 3 * 2, 2, 30 + i / 3 * 2)));
		helper.runAfterDelay(5, () -> {
			Hive hive = HiveSavedData.get(helper.getLevel().getServer()).get(hatchery.getUUID()); // a hive is keyed by its hatchery
			helper.assertTrue(hive != null, "no hive (hatchery " + (hatchery.isRemoved() ? "removed" : "alive") + ")");
			hive.minerals = 1000;
			for (StarUnit drone : drones) drone.joinHive(hive);
			helper.succeedWhen(() -> {
				helper.assertTrue(!helper.getLevel().getEntitiesOfClass(StarUnit.class, new net.minecraft.world.phys.AABB(outlet).inflate(2, 3, 2),
					mob -> mob.kind == UnitKind.EXTRACTOR).isEmpty(), "no extractor on the outlet; drones " + hive.count(UnitKind.DRONE)
					+ " hatchery " + (hatchery.isRemoved() ? "removed" : "alive") + " same hive " + (HiveSavedData.get(helper.getLevel().getServer()).get(hatchery.getUUID()) == hive)
					+ " timer " + hive.decisionTimer + " larvae " + hive.count(UnitKind.LARVA) + " order " + hive.buildOrder + " tick " + hatchery.tickCount
					+ " extractors " + hive.count(UnitKind.EXTRACTOR) + "+" + hive.pending(UnitKind.EXTRACTOR) + " minerals " + hive.minerals + " outlet " + helper.getLevel().getBlockState(outlet)
					+ " " + drones.stream().map(d -> helper.relativePos(d.blockPosition()).toShortString() + (d.isAlive() ? "" : " dead") + " " + d.buildKind() + d.goalsRunning()).toList());
				helper.assertTrue(hive.vespene >= Vespene.PER_TRIP, "no vespene brought home; vespene " + hive.vespene);
			});
		});
	}

	@GameTest(template = ARENA, batch = "vespeneGolem", timeoutTicks = 400)
	public void golemsLeaveTerransAloneUntilHurt(GameTestHelper helper) {
		arena(helper);
		StarUnit cc = commandCenter(helper);
		helper.runAfterDelay(5, () -> {
			Hive base = base(helper, cc);
			helper.assertTrue(base != null, "no base was founded");
			StarUnit marine = HiveLogic.spawn(helper.getLevel(), base, UnitKind.MARINE, Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(6, 2, 6))), null);
			IronGolem golem = EntityType.IRON_GOLEM.create(helper.getLevel());
			Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(10, 2, 6)));
			golem.moveTo(at.x, at.y, at.z, 0, 0);
			helper.getLevel().addFreshEntity(golem);
			helper.runAfterDelay(100, () -> {
				helper.assertTrue(golem.getTarget() == null && marine.isAlive(), "the golem went for a peaceful marine");
				helper.assertFalse(TerranLogic.villageHostile(marine), "the base is hostile without a reason");
				golem.hurt(helper.getLevel().damageSources().mobAttack(marine), 1);
				helper.succeedWhen(() -> {
					helper.assertTrue(TerranLogic.villageHostile(marine), "hurting a golem did not make the base hostile");
					helper.assertTrue(golem.getTarget() == marine || !marine.isAlive(), "the golem does not fight back");
				});
			});
		});
	}
}
