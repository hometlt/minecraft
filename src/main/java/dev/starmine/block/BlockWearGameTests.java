package dev.starmine.block;

import static dev.starmine.ability.TestArena.ARENA;

import dev.starmine.entity.UnitKind;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Owner 19.10: blocks keep their damage — saved, never passing by itself; the races' blocks have their ways; mining resumes. */
public class BlockWearGameTests implements FabricGameTest {
	@GameTest(template = ARENA, batch = "wearKept", timeoutTicks = 400)
	public void damageStaysAndRacesHaveTheirWays(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos stone = helper.absolutePos(new BlockPos(4, 2, 4)), zerg = stone.east(2), terran = stone.east(4), protoss = stone.east(6);
		level.setBlock(stone, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(zerg, ArmorBlocks.wall(UnitKind.Race.ZERG, 0), Block.UPDATE_ALL);
		level.setBlock(terran, ArmorBlocks.wall(UnitKind.Race.TERRAN, 0), Block.UPDATE_ALL);
		level.setBlock(protoss, ArmorBlocks.wall(UnitKind.Race.PROTOSS, 0), Block.UPDATE_ALL);
		BlockWear.hit(level, stone, 4, null);
		BlockWear.hit(level, zerg, 60, null);
		helper.assertTrue(BlockWear.durability(level, terran, level.getBlockState(terran)) == BlockWear.durability(level, zerg, level.getBlockState(zerg)) * BlockWear.TERRAN_TOUGH,
			"terran armour is not half again as tough");
		for (int i = 0; i < BlockWear.SHIELD_HITS; i++) BlockWear.hit(level, protoss, 60, null);
		boolean shielded = BlockWear.progress(level, protoss) == 0 && BlockWear.shield(level, protoss) == 0;
		BlockWear.hit(level, protoss, 60, null);
		boolean worn = BlockWear.progress(level, protoss) > 0;
		// saved with the world
		CompoundTag saved = BlockWear.Data.get(level).save(new CompoundTag(), level.registryAccess());
		float kept = BlockWear.Data.load(saved, level.registryAccess()).wear.get(stone.asLong()).progress;
		float stoneWear = BlockWear.progress(level, stone), zergWear = BlockWear.progress(level, zerg);
		helper.runAfterDelay(BlockWear.ZERG_HEAL + 60, () -> {
			helper.assertTrue(shielded, "the protoss shield did not take the first " + BlockWear.SHIELD_HITS + " hits");
			helper.assertTrue(worn, "the protoss block does not wear once its shield is down");
			helper.assertTrue(Math.abs(kept - stoneWear) < 1e-4, "the wear is not saved: " + kept + " vs " + stoneWear);
			helper.assertTrue(BlockWear.progress(level, stone) == stoneWear, "the stone's cracks passed by themselves");
			helper.assertTrue(BlockWear.progress(level, zerg) < zergWear, "the zerg armour did not heal");
			for (BlockPos at : new BlockPos[] {stone, zerg, terran, protoss}) level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "wearMining", timeoutTicks = 300)
	public void miningResumesWhereItWasLeft(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos planks = helper.absolutePos(new BlockPos(10, 2, 10));
		level.setBlock(planks, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayerInLevel();
		player.setGameMode(GameType.SURVIVAL);
		player.teleportTo(planks.getX() + 0.5, planks.getY() + 1, planks.getZ() + 2.5);
		int top = level.getMaxBuildHeight();
		var start = ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK;
		// mined for a while, left; mined again: it breaks in fewer ticks than a fresh one would take
		int[] tick = {0}, stopAt = {-1};
		float[] left = {0}, perTick = {0};
		helper.onEachTick(() -> {
			player.gameMode.tick(); // a mock player's mining clock runs only when ticked (a real one's every tick)
			int t = ++tick[0];
			if (t == 2) player.gameMode.handleBlockBreakAction(planks, start, Direction.UP, top, 0);
			if (t == 12) player.gameMode.handleBlockBreakAction(planks, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, Direction.UP, top, 0);
			if (t == 20) {
				left[0] = BlockWear.progress(level, planks);
				perTick[0] = level.getBlockState(planks).getDestroyProgress(player, level, planks);
				player.gameMode.handleBlockBreakAction(planks, start, Direction.UP, top, 0);
				stopAt[0] = t + (int) Math.ceil((0.7f - left[0]) / perTick[0]);
			}
			if (t == stopAt[0]) {
				player.gameMode.handleBlockBreakAction(planks, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, Direction.UP, top, 0);
				boolean broken = level.getBlockState(planks).isAir();
				int ticks = stopAt[0] - 20;
				level.setBlock(planks, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
				helper.assertTrue(left[0] > 0, "the work on the planks was not kept");
				helper.assertTrue(perTick[0] * (ticks + 1) < 0.7f, "the test is wrong: fresh planks break in " + ticks + " ticks anyway");
				helper.assertTrue(broken, "the planks mined again from their kept work (" + left[0] + ") did not break in " + ticks + " ticks");
				helper.succeed();
			}
		});
	}

	@GameTest(template = ARENA, batch = "wearRepair", timeoutTicks = 40)
	public void repairToolsMendTheirRacesBlocks(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos terran = helper.absolutePos(new BlockPos(4, 2, 8)), zerg = terran.east(2), protoss = terran.east(4), stone = terran.east(6);
		level.setBlock(terran, ArmorBlocks.wall(UnitKind.Race.TERRAN, 0), Block.UPDATE_ALL);
		level.setBlock(zerg, ArmorBlocks.wall(UnitKind.Race.ZERG, 0), Block.UPDATE_ALL);
		level.setBlock(protoss, ArmorBlocks.wall(UnitKind.Race.PROTOSS, 0), Block.UPDATE_ALL);
		level.setBlock(stone, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		for (BlockPos at : new BlockPos[] {terran, zerg, stone}) BlockWear.hit(level, at, 200, null);
		BlockWear.hit(level, protoss, 60, null);
		float t0 = BlockWear.progress(level, terran), z0 = BlockWear.progress(level, zerg), s0 = BlockWear.progress(level, stone);
		boolean zergOnTerran = dev.starmine.gear.RepairTools.use(UnitKind.Race.ZERG, level, terran);
		boolean terranMends = dev.starmine.gear.RepairTools.use(UnitKind.Race.TERRAN, level, terran);
		boolean zergMends = dev.starmine.gear.RepairTools.use(UnitKind.Race.ZERG, level, zerg);
		boolean stoneMended = dev.starmine.gear.RepairTools.use(UnitKind.Race.TERRAN, level, stone) || dev.starmine.gear.RepairTools.use(UnitKind.Race.ZERG, level, stone);
		boolean recharged = dev.starmine.gear.RepairTools.use(UnitKind.Race.PROTOSS, level, protoss);
		helper.assertFalse(zergOnTerran, "the zerg mender healed terran steel");
		helper.assertTrue(terranMends && BlockWear.progress(level, terran) < t0, "the terran tool did not mend terran armour");
		helper.assertTrue(zergMends && BlockWear.progress(level, zerg) < z0, "the zerg mender did not heal zerg armour");
		helper.assertTrue(!stoneMended && BlockWear.progress(level, stone) == s0, "stone was mended, it is neither machine nor alive");
		helper.assertTrue(recharged && BlockWear.shield(level, protoss) == BlockWear.SHIELD_HITS, "the protoss restorer did not bring the shield back");
		for (BlockPos at : new BlockPos[] {terran, zerg, protoss, stone}) level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "armourBuilding", timeoutTicks = 40)
	public void armourBuildingBlocksAreTheRacesArmour(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		var recipes = level.getServer().getRecipeManager();
		for (UnitKind.Race race : UnitKind.Race.values())
			for (String kind : ArmorBlocks.BUILDING) {
				Block block = ArmorBlocks.BUILDING_BLOCKS.get(race).get(kind);
				String id = race.name().toLowerCase() + "_" + kind;
				helper.assertTrue(ArmorBlocks.raceOf(block) == race && ArmorBlocks.armoured(block.defaultBlockState()), id + " is not " + race + "'s armour");
				helper.assertTrue(recipes.byKey(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("starmine", id)).isPresent(), "no recipe " + id);
			}
		// bars join their neighbours as iron bars do; a zerg slab heals as zerg armour does
		BlockPos a = helper.absolutePos(new BlockPos(4, 2, 12)), b = a.east();
		Block bars = ArmorBlocks.BUILDING_BLOCKS.get(UnitKind.Race.TERRAN).get("armor_bars");
		level.setBlock(a, bars.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(b, bars.defaultBlockState(), Block.UPDATE_ALL);
		boolean joined = level.getBlockState(a).getValue(net.minecraft.world.level.block.IronBarsBlock.EAST);
		Block slab = ArmorBlocks.BUILDING_BLOCKS.get(UnitKind.Race.ZERG).get("armor_slab");
		BlockPos c = a.east(3);
		level.setBlock(c, slab.defaultBlockState(), Block.UPDATE_ALL);
		boolean tough = BlockWear.durability(level, c, slab.defaultBlockState()) > BlockWear.durability(level, c, Blocks.OBSIDIAN.defaultBlockState());
		for (BlockPos at : new BlockPos[] {a, b, c}) level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		helper.assertTrue(joined, "armour bars do not join");
		helper.assertTrue(tough, "an armour slab is weaker than obsidian");
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "wearLoose", timeoutTicks = 500)
	public void looseGroundSettlesBack(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos dirt = helper.absolutePos(new BlockPos(14, 2, 14)), sand = dirt.east(2);
		level.setBlock(dirt, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(sand, Blocks.SANDSTONE.defaultBlockState(), Block.UPDATE_ALL);
		BlockWear.hit(level, dirt, 1, null);
		BlockWear.mined(level, sand, 0.5f); // sandstone is no loose ground: kept
		BlockWear.mined(level, dirt.above(), 0.5f);
		float worn = BlockWear.progress(level, dirt);
		CompoundTag saved = BlockWear.Data.get(level).save(new CompoundTag(), level.registryAccess());
		boolean savedDirt = BlockWear.Data.load(saved, level.registryAccess()).wear.containsKey(dirt.asLong());
		helper.runAfterDelay(BlockWear.LOOSE_HEAL + 60, () -> {
			float now = BlockWear.progress(level, dirt), kept = BlockWear.progress(level, sand);
			level.setBlock(dirt, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			level.setBlock(sand, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			helper.assertTrue(BlockWear.loose(Blocks.DIRT.defaultBlockState()) && BlockWear.loose(Blocks.GRAVEL.defaultBlockState())
				&& BlockWear.loose(dev.starmine.block.StarMineBlocks.CREEP.defaultBlockState()) && !BlockWear.loose(Blocks.STONE.defaultBlockState()), "loose ground misjudged");
			helper.assertTrue(worn > 0 && now < worn, "the dirt's cracks did not settle back: " + worn + " then " + now);
			helper.assertFalse(savedDirt, "the dirt's damage was saved");
			helper.assertTrue(kept > 0, "the sandstone's mining was not kept");
			helper.succeed();
		});
	}
}
