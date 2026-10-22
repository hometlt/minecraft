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
	@GameTest(template = ARENA, batch = "finiteVespeneSource", timeoutTicks = 160)
	public void sourceKeepsItsBudgetAcrossSaveAndRefineryRebuild(GameTestHelper h) {
		TestArena.clear(h); var level = h.getLevel();
		BlockPos pos = h.absolutePos(new BlockPos(24, -2, 24)), outlet = pos.above(4);
		level.setBlock(pos, Vespene.ROOT.defaultBlockState(), 2);
		var source = (VespeneRootBlockEntity) level.getBlockEntity(pos);
		source.setVein(List.of(), List.of(outlet));
		var full = Vespene.VESPENE.defaultBlockState().setValue(VespeneBlock.OUTLET, true);
		int extracted = 0;
		for (int i = 0; i < 25; i++) { level.setBlock(outlet, full, 2); extracted += VespeneBlock.take(level, outlet, full); }
		h.assertTrue(extracted == 100 && Vespene.gasLeft(level, outlet)[0] == 1000, "source did not debit worker/bucket extraction");
		var saved = source.saveWithoutMetadata(level.registryAccess());
		source.loadWithComponents(saved, level.registryAccess());
		source.riseNow(level, 0);
		BlockPos collapsed = source.collapse(level, source.outlet(0));
		h.assertTrue(source.remaining(collapsed) == 1000, "saving or rebuilding a refinery reset the gas budget");
		h.assertTrue(source.extract(level, collapsed, 2000) == 1000, "source allowed overdrawing its reserve");
		level.setBlock(collapsed, full, 2);
		h.assertTrue(VespeneBlock.take(level, collapsed, full) == 0, "depleted source still yields gas");
		h.runAfterDelay(140, () -> {
			h.assertTrue(level.getBlockState(collapsed).is(Vespene.VENT) && Vespene.gasLeft(level, collapsed)[0] == 0, "empty source regenerated gas");
			h.succeed();
		});
	}
	@GameTest(template = ARENA, batch = "nativeGeyserAdoption", timeoutTicks = 40)
	public void nativeGeyserKeepsItsChannelAndGetsTheUnbreakableShape(GameTestHelper h) {
		TestArena.clear(h); var level = h.getLevel();
		BlockPos rootPos = h.absolutePos(new BlockPos(24, -3, 24)), base = rootPos.above(4), top = base.above(4);
		List<BlockPos> channel = new ArrayList<>();
		for (int dy = 1; dy <= 7; dy++) channel.add(rootPos.above(dy));
		for (BlockPos at : BlockPos.betweenClosed(base.offset(-3, 1, -3), base.offset(3, 4, 3))) level.setBlock(at, Blocks.COBBLED_DEEPSLATE.defaultBlockState(), 2);
		level.setBlock(rootPos, Vespene.ROOT.defaultBlockState(), 2);
		for (BlockPos at : channel) level.setBlock(at, Vespene.VESPENE.defaultBlockState(), 2);
		level.setBlock(top, Vespene.VESPENE.defaultBlockState().setValue(VespeneBlock.OUTLET, true), 2);
		var root = (VespeneRootBlockEntity) level.getBlockEntity(rootPos); root.setVein(channel, List.of(top));
		var underground = level.getBlockState(rootPos.above().east(2));
		root.adoptNativeVolcano(level, 0);
		h.assertTrue(root.outlet(0).equals(top) && root.capacity() == 8 && root.baseOf(level, top).equals(base), "native channel or ground moved");
		h.assertTrue(Vespene.gasLeft(level, top)[0] == 1100 && level.getBlockState(rootPos.above().east(2)).equals(underground), "source reserve must be logical, without an excavated lake");
		for (BlockPos at : channel) h.assertTrue(VespeneBlock.isSource(level.getBlockState(at)), "an original absolute channel cell was lost");
		for (int y = 1; y <= 4; y++) for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
			if (dx == 0 && dz == 0) continue;
			BlockPos at = base.offset(dx, y, dz);
			h.assertTrue(level.getBlockState(at).is(Vespene.ROCK) == VespeneRootBlockEntity.inLayer(dx, y, dz), "wrong imported volcano layer");
		}
		h.assertTrue(level.getBlockState(base.east(3).above()).getDestroySpeed(level, base.east(3).above()) < 0, "imported geyser can be mined");
		root.collapse(level, top);
		h.assertTrue(root.outlet(0).equals(base), "imported gas building would float over its geyser");
		h.succeed();
	}
	@GameTest(template = ARENA, batch = "geyserRestoration", timeoutTicks = 100)
	public void destroyedRefineryRestoresAnUnbreakableGeyser(GameTestHelper h) {
		TestArena.clear(h);
		var level = h.getLevel();
		BlockPos root = h.absolutePos(new BlockPos(24, -2, 24)), ground = root.above(3);
		level.setBlock(root, Vespene.ROOT.defaultBlockState(), 2);
		level.setBlock(ground, Vespene.VESPENE.defaultBlockState().setValue(VespeneBlock.OUTLET, true), 2);
		var vein = (VespeneRootBlockEntity) level.getBlockEntity(root);
		vein.setVein(List.of(root.above(), root.above(2)), List.of(ground));
		vein.riseNow(level, 0);
		h.assertTrue(vein.outlet(0).getY() - ground.getY() == 4, "geyser is not four Minecraft blocks high");
		for (int y = 1; y <= 4; y++) for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
			if (dx == 0 && dz == 0) continue;
			int r = y <= 2 ? 3 : y == 3 ? 2 : 1;
			boolean inside = Math.max(Math.abs(dx), Math.abs(dz)) <= r && Math.abs(dx) + Math.abs(dz) <= r + 1;
			h.assertTrue(level.getBlockState(ground.offset(dx, y, dz)).is(Vespene.ROCK) == inside, "wrong volcano layer " + dx + "," + y + "," + dz);
		}
		h.assertTrue(level.getBlockState(ground.east(3).above()).getDestroySpeed(level, ground.east(3).above()) < 0, "volcano rock can be mined");
		h.assertTrue(dev.starmine.race.Placement.siteRefusal(level, dev.starmine.entity.UnitKind.BARRACKS, vein.outlet(0).above(), java.util.UUID.randomUUID()) != null,
			"ordinary building allowed on geyser");
		BlockPos site = Vespene.collapseUnder(level, vein.outlet(0).above());
		var refinery = dev.starmine.StarMine.type(dev.starmine.entity.UnitKind.REFINERY).create(level);
		refinery.moveTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(site)); refinery.setNoAi(true); level.addFreshEntity(refinery);
		h.runAfterDelay(25, () -> { h.assertTrue(vein.outlet(0).equals(ground), "geyser regrew through a living refinery"); refinery.kill(); });
		h.runAfterDelay(60, () -> {
			h.assertTrue(vein.outlet(0).equals(ground.above(4)), "geyser did not regrow after refinery death");
			h.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "geyserResize", timeoutTicks = 80)
	public void oversizedSavedGeyserShrinksOnLoad(GameTestHelper h) {
		TestArena.clear(h);
		var level = h.getLevel();
		BlockPos root = h.absolutePos(new BlockPos(24, -2, 24)), ground = root.above(3), top = ground.above(12);
		List<BlockPos> channel = new ArrayList<>();
		for (int y = 1; y <= 12; y++) {
			int r = y <= 6 ? 10 : Math.max(1, 10 - (int) Math.ceil((y - 6) * 1.5));
			for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++)
				level.setBlock(ground.offset(dx, y, dz), Vespene.ROCK.defaultBlockState(), 2);
		}
		for (BlockPos p = root.above(); p.getY() < top.getY(); p = p.above()) {
			channel.add(p); level.setBlock(p, Vespene.VESPENE.defaultBlockState(), 2);
		}
		level.setBlock(top, Vespene.VESPENE.defaultBlockState().setValue(VespeneBlock.OUTLET, true), 2);
		level.setBlock(root, Vespene.ROOT.defaultBlockState(), 2);
		var vein = (VespeneRootBlockEntity) level.getBlockEntity(root);
		vein.setVein(channel, List.of(top)); // Generated saved mounds have no Risen counter.
		BlockPos playerBlock = ground.offset(9, 10, 0);
		level.setBlock(playerBlock, Blocks.COBBLESTONE.defaultBlockState(), 2);
		h.assertTrue(vein.baseOf(level, top).equals(ground), "old mound ground was lost");
		h.runAfterDelay(25, () -> {
			h.assertTrue(level.getBlockState(playerBlock).is(Blocks.COBBLESTONE), "resize removed a player's block");
			h.assertTrue(vein.outlet(0).equals(ground.above(4)), "old outlet was not lowered");
			h.assertTrue(level.getBlockState(ground.east(3).above(2)).is(Vespene.ROCK), "new two-block wall missing");
			for (BlockPos p : BlockPos.betweenClosed(ground.offset(-10, 1, -10), ground.offset(10, 13, 10)))
				if (!p.equals(playerBlock) && (p.getY() > ground.getY() + 4 || Math.max(Math.abs(p.getX() - ground.getX()), Math.abs(p.getZ() - ground.getZ())) > 3))
					h.assertTrue(level.getBlockState(p).isAir(), "oversized base remains at " + p);
			h.succeed();
		});
	}

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
		vein.riseNow(level, 0);
		BlockPos top = vein.outlet(0);
		helper.assertTrue(top.equals(outlet.above(VespeneRootBlockEntity.MOUND)), "the volcano did not rise: " + top.toShortString());
		helper.assertTrue(level.getBlockState(top.below().north()).is(Vespene.ROCK), "no cone round the volcano");
		// owner 18.10: a click on the cone's side takes the outlet on top; the building's site is where the volcano rose from
		BlockPos chosen = dev.starmine.race.Placement.site(level, dev.starmine.entity.UnitKind.REFINERY, top.below(2).north(2));
		helper.assertTrue(outlet.above().equals(chosen), "a click on the cone gave the site " + chosen);
		helper.assertTrue(Vespene.coneReach(level, chosen) > 0, "no cone reach round a volcano's site");
		BlockPos site = Vespene.collapseUnder(level, chosen);
		helper.assertTrue(site.equals(outlet.above()), "the site is not back on the ground: " + site.toShortString());
		helper.assertTrue(vein.outlet(0).equals(outlet) && Vespene.isOutlet(level.getBlockState(outlet)), "the outlet is not back at the ground");
		for (int y = 1; y <= 4; y++)
			for (BlockPos at : BlockPos.betweenClosed(outlet.offset(-3, y, -3), outlet.offset(3, y, 3)))
				helper.assertFalse(level.getBlockState(at).is(Vespene.ROCK) || level.getBlockState(at).is(Vespene.VESPENE),
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
		BlockPos root = helper.absolutePos(new BlockPos(24, -2, 24)), ground = root.above(3), top = ground.above(VespeneRootBlockEntity.MOUND);
		List<BlockPos> channel = new ArrayList<>();
		for (BlockPos cell = root.above(); cell.getY() < top.getY(); cell = cell.above()) channel.add(cell.immutable());
		for (int k = 1; k <= VespeneRootBlockEntity.MOUND; k++) {
			int r = VespeneRootBlockEntity.radiusAt(k);
			for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++)
				level.setBlock(ground.offset(dx, k, dz), Vespene.ROCK.defaultBlockState(), 2);
		}
		level.setBlock(root, Vespene.ROOT.defaultBlockState(), 2);
		for (BlockPos cell : channel) level.setBlock(cell, Vespene.VESPENE.defaultBlockState(), 2);
		level.setBlock(top, Vespene.VESPENE.defaultBlockState().setValue(VespeneBlock.OUTLET, true), 2);
		((VespeneRootBlockEntity) level.getBlockEntity(root)).setVein(channel, List.of(top));
		BlockPos chosen = dev.starmine.race.Placement.site(level, dev.starmine.entity.UnitKind.REFINERY, top.below(2).east(3));
		helper.assertTrue(chosen != null && chosen.getY() == top.getY() - VespeneRootBlockEntity.MOUND + 1, "the site is not under the mound: " + chosen);
		BlockPos site = Vespene.collapseUnder(level, chosen);
		helper.assertTrue(site.equals(chosen), "the building stands at " + site + ", not " + chosen);
		helper.assertTrue(Vespene.outletUnder(level, site) != null, "no outlet under the building");
		for (BlockPos at : BlockPos.betweenClosed(site.offset(-5, 0, -5), site.offset(5, VespeneRootBlockEntity.MOUND + 1, 5)))
			helper.assertFalse(level.getBlockState(at).is(Vespene.ROCK), "cobblestone left at " + helper.relativePos(at).toShortString());
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
		helper.assertTrue(level.getBlockState(new BlockPos(cx + 13, floor + 2, cz)).isAir(), "no hall");
		helper.assertTrue(level.getBlockState(new BlockPos(cx + 6, floor - 1, cz)).isSolid(), "no floor");
		helper.assertTrue(VespeneVeinFeature.build(level, level.random, cx, cz, floor), "no vein");
		helper.assertTrue(level.getBlockState(new BlockPos(cx + 9, floor - 1, cz)).isSolid(), "lower gas chamber cut through the mineral hall floor");
		BlockPos rootPos = new BlockPos(cx, level.getMinBuildHeight() + 1, cz);
		helper.assertTrue(level.getBlockEntity(rootPos) instanceof VespeneRootBlockEntity vein && vein.outletCount() > 0
			&& vein.outlet(0).getY() >= floor && vein.outlet(0).getY() <= floor + VespeneRootBlockEntity.MOUND, "the vein does not end in a geyser on the hall's floor");
		int crystals = dev.starmine.mineral.MineralHallFeature.crystalsForTest(level, cx, floor, cz);
		helper.assertTrue(crystals == 5, "only " + crystals + " crystals in the hall");
		helper.assertTrue(!Minerals.coresNear(level, new BlockPos(cx, floor, cz), 14).isEmpty(), "no crystal cores in the hall");
		helper.succeed();
	}
}
