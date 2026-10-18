package dev.starmine.vespene;

import static dev.starmine.ability.TestArena.ARENA;

import dev.starmine.ability.TestArena;
import dev.starmine.mineral.Minerals;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Owner 17.10: a gas building put on a volcano destroys it; minerals underground in a hall with a geyser. */
public class VolcanoGameTests implements FabricGameTest {
	/** A vein whose outlet is on the arena floor, risen 3 blocks as a volcano, collapses under a gas building's site. */
	@GameTest(template = ARENA, batch = "volcano", timeoutTicks = 40)
	public void aGasBuildingDestroysTheVolcano(GameTestHelper helper) {
		TestArena.clear(helper);
		ServerLevel level = helper.getLevel();
		BlockPos root = helper.absolutePos(new BlockPos(24, -2, 24)), outlet = root.above(3);
		List<BlockPos> channel = new ArrayList<>(List.of(root.above(), root.above(2)));
		level.setBlock(root, Vespene.ROOT.defaultBlockState(), 2);
		for (BlockPos cell : channel) level.setBlock(cell, Vespene.VESPENE.defaultBlockState(), 2);
		level.setBlock(outlet, Vespene.VESPENE.defaultBlockState().setValue(VespeneBlock.OUTLET, true), 2);
		VespeneRootBlockEntity vein = (VespeneRootBlockEntity) level.getBlockEntity(root);
		vein.setVein(channel, List.of(outlet));
		for (int i = 0; i < 3; i++) vein.riseNow(level, 0);
		BlockPos top = vein.outlet(0);
		helper.assertTrue(top.equals(outlet.above(3)), "the volcano did not rise: " + top.toShortString());
		helper.assertTrue(level.getBlockState(top.below().north()).is(Blocks.COBBLESTONE), "no cone round the volcano");
		// owner 18.10: a click on the cone's side takes the outlet on top; the building's site is where the volcano rose from
		BlockPos chosen = dev.starmine.race.Placement.site(level, dev.starmine.entity.UnitKind.REFINERY, top.below(2).north(2));
		helper.assertTrue(outlet.above().equals(chosen), "a click on the cone gave the site " + chosen);
		helper.assertTrue(Vespene.coneReach(level, chosen) > 0, "no cone reach round a volcano's site");
		BlockPos site = Vespene.collapseUnder(level, chosen);
		helper.assertTrue(site.equals(outlet.above()), "the site is not back on the ground: " + site.toShortString());
		helper.assertTrue(vein.outlet(0).equals(outlet) && Vespene.isOutlet(level.getBlockState(outlet)), "the outlet is not back at the ground");
		for (int y = 1; y <= 4; y++)
			for (BlockPos at : BlockPos.betweenClosed(outlet.offset(-3, y, -3), outlet.offset(3, y, 3)))
				helper.assertFalse(level.getBlockState(at).is(Blocks.COBBLESTONE) || level.getBlockState(at).is(Vespene.VESPENE),
					"the volcano is left at " + helper.relativePos(at).toShortString());
		helper.succeed();
	}

	/** Owner 18.10: a gas building on a generated geyser takes its whole cobblestone mound away and stands on the ground. */
	@GameTest(template = ARENA, batch = "volcano", timeoutTicks = 40)
	public void aGasBuildingTakesTheMoundAway(GameTestHelper helper) {
		TestArena.clear(helper);
		ServerLevel level = helper.getLevel();
		// a geyser as the world makes one (VespeneVeinFeature): its trunk up through the ground, a mound 4 layers high
		// (radius 4 to 1) on the ground, the outlet on its top
		BlockPos root = helper.absolutePos(new BlockPos(24, -2, 24)), ground = root.above(3), top = ground.above(1 + VespeneRootBlockEntity.MOUND);
		List<BlockPos> channel = new ArrayList<>();
		for (BlockPos cell = root.above(); cell.getY() < top.getY(); cell = cell.above()) channel.add(cell.immutable());
		for (int k = 0; k <= 3; k++) {
			int r = 4 - k;
			for (int dx = -r; dx <= r; dx++)
				for (int dz = -r; dz <= r; dz++)
					if (dx * dx + dz * dz <= r * r + 1) level.setBlock(ground.above(1 + k).offset(dx, 0, dz), Blocks.COBBLESTONE.defaultBlockState(), 2);
		}
		level.setBlock(root, Vespene.ROOT.defaultBlockState(), 2);
		for (BlockPos cell : channel) level.setBlock(cell, Vespene.VESPENE.defaultBlockState(), 2);
		level.setBlock(top, Vespene.VESPENE.defaultBlockState().setValue(VespeneBlock.OUTLET, true), 2);
		((VespeneRootBlockEntity) level.getBlockEntity(root)).setVein(channel, List.of(top));
		BlockPos chosen = dev.starmine.race.Placement.site(level, dev.starmine.entity.UnitKind.REFINERY, top.below(2).east(3));
		helper.assertTrue(chosen != null && chosen.getY() <= top.getY() - VespeneRootBlockEntity.MOUND, "the site is not under the mound: " + chosen);
		BlockPos site = Vespene.collapseUnder(level, chosen);
		helper.assertTrue(site.equals(chosen), "the building stands at " + site + ", not " + chosen);
		helper.assertTrue(Vespene.outletUnder(level, site) != null, "no outlet under the building");
		for (BlockPos at : BlockPos.betweenClosed(site.offset(-5, 0, -5), site.offset(5, VespeneRootBlockEntity.MOUND + 1, 5)))
			helper.assertFalse(level.getBlockState(at).is(Blocks.COBBLESTONE), "cobblestone left at " + helper.relativePos(at).toShortString());
		helper.succeed();
	}

	/** A mineral hall under a stone hill: a carved hall with a flat floor, a geyser of its own vein, crystals round it. */
	@GameTest(template = ARENA, batch = "mineralHall", timeoutTicks = 100)
	public void mineralsLieInAHallUnderground(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos origin = helper.absolutePos(BlockPos.ZERO).offset(640, 0, 640);
		int cx = (origin.getX() & ~15) + 8, cz = (origin.getZ() & ~15) + 8;
		int ground = level.getMinBuildHeight() + 60; // a hill of stone over the flat world, 56 deep
		for (BlockPos at : BlockPos.betweenClosed(cx - 15, level.getMinBuildHeight() + 1, cz - 15, cx + 15, ground - 1, cz + 15))
			level.setBlock(at, Blocks.STONE.defaultBlockState(), 2);
		int floor = level.getMinBuildHeight() + 20;
		helper.assertTrue(dev.starmine.mineral.MineralHallFeature.carveForTest(level, cx, floor, cz), "the hall was refused");
		helper.assertTrue(level.getBlockState(new BlockPos(cx + 6, floor + 2, cz)).isAir(), "no hall");
		helper.assertTrue(level.getBlockState(new BlockPos(cx + 6, floor - 1, cz)).isSolid(), "no floor");
		helper.assertTrue(VespeneVeinFeature.build(level, level.random, cx, cz, floor), "no vein");
		BlockPos rootPos = new BlockPos(cx, level.getMinBuildHeight() + 1, cz);
		helper.assertTrue(level.getBlockEntity(rootPos) instanceof VespeneRootBlockEntity vein && vein.outletCount() > 0
			&& vein.outlet(0).getY() >= floor && vein.outlet(0).getY() <= floor + 4, "the vein does not end in a geyser on the hall's floor");
		int crystals = dev.starmine.mineral.MineralHallFeature.crystalsForTest(level, cx, floor, cz);
		helper.assertTrue(crystals >= 3, "only " + crystals + " crystals in the hall");
		helper.assertTrue(!Minerals.coresNear(level, new BlockPos(cx, floor, cz), 14).isEmpty(), "no crystal cores in the hall");
		helper.succeed();
	}
}
