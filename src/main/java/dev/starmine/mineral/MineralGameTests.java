package dev.starmine.mineral;

import static dev.starmine.ability.TestArena.ARENA;

import dev.starmine.ability.TestArena;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Mineral crystals (owner 16.10): gathered, never broken; gone when empty, their earth back to dirt; no main building beside them. */
public class MineralGameTests implements FabricGameTest {
	@GameTest(template = ARENA, batch = "flatMineralField", timeoutTicks = 100)
	public void generatedFieldHasFiveCoresAndRefusesAFloodedBasePad(GameTestHelper h) {
		TestArena.clear(h);
		var level = h.getLevel(); BlockPos origin = h.absolutePos(new BlockPos(24, 2, 8));
		BlockPos centre = new BlockPos(origin.getX(), level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, origin.getX(), origin.getZ()), origin.getZ());
		for (BlockPos at : BlockPos.betweenClosed(centre.offset(-16, -1, -6), centre.offset(16, 4, 36)))
			level.setBlock(at, at.getY() == centre.getY() - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
		var set = (Minerals.Setter) (pos, state) -> level.setBlock(pos, state, 2);
		BlockPos flooded = centre.offset(0, 0, 22); level.setBlock(flooded, Blocks.WATER.defaultBlockState(), 2);
		h.assertTrue(Minerals.field(level, level.random, centre.getX(), centre.getZ(), 22, set) == 0, "field accepted a flooded base pad: water=" + level.getBlockState(flooded)
			+ " centre=" + centre + " floor=" + level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, flooded.getX(), flooded.getZ())
			+ " surface=" + level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, flooded.getX(), flooded.getZ()));
		h.assertTrue(Minerals.coresNear(level, centre, 14).isEmpty(), "refused field left partial crystals");
		level.setBlock(flooded, Blocks.AIR.defaultBlockState(), 2);
		h.assertTrue(Minerals.field(level, level.random, centre.getX(), centre.getZ(), 22, set) == 5, "flat field did not contain all five minerals");
		h.succeed();
	}
	private static BlockPos crystal(GameTestHelper helper, int x, int z) {
		ServerLevel level = helper.getLevel();
		BlockPos core = helper.absolutePos(new BlockPos(x, 2, z));
		for (BlockPos pos : BlockPos.betweenClosed(core.offset(-2, -1, -2), core.offset(3, -1, 3))) level.setBlock(pos, Blocks.DIRT.defaultBlockState(), 2);
		helper.assertTrue(Minerals.place(level, core, (pos, state) -> level.setBlock(pos, state, 2)), "the crystal did not fit");
		return core;
	}

	/** Owner 18.10: a base's deposit — 5 crystals and 2 geysers, each geyser with a site for its gas building. */
	@GameTest(template = ARENA, batch = "minerals", timeoutTicks = 100)
	public void aDepositHasFiveCrystalsAndTwoGeysers(GameTestHelper helper) {
		TestArena.clear(helper);
		ServerLevel level = helper.getLevel();
		BlockPos middle = helper.absolutePos(new BlockPos(24, 2, 30)), base = helper.absolutePos(new BlockPos(24, 2, 8));
		java.util.List<BlockPos> sites = Deposits.build(level, level.random, middle.getX(), middle.getZ(), base.getX(), base.getZ());
		int crystals = Minerals.coresNear(level, middle, 12).size();
		helper.assertTrue(crystals == Deposits.MINERALS, crystals + " crystals, not 5");
		int total = 0, large = 0, small = 0;
		for (BlockPos pos : Minerals.coresNear(level, middle, 12)) {
			Minerals.Core core = (Minerals.Core) level.getBlockEntity(pos);
			helper.assertTrue(core.size() == 3, "deposit crystal footprint is not 3 x 3");
			total += core.amount();
			if (core.amount() == 1300) large++;
			if (core.amount() == 800) small++;
		}
		helper.assertTrue(total == 5500 && large == 3 && small == 2, "deposit must contain 3 x 1300 and 2 x 800 minerals");
		helper.assertTrue(sites.size() == Deposits.GEYSERS, sites.size() + " geysers, not 2");
		for (BlockPos site : sites)
			helper.assertTrue(dev.starmine.vespene.Vespene.outletUnder(level, site) != null, "no outlet under the gas building's site " + site);
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "minerals", timeoutTicks = 100)
	public void depletionKeepsTheFootprintAndReducesHeightAndShards(GameTestHelper helper) {
		TestArena.clear(helper);
		Minerals.Core core = (Minerals.Core) helper.getLevel().getBlockEntity(crystal(helper, 24, 24));
		core.setSize(3);
		core.setAmount(1300);
		helper.assertTrue(Math.abs(core.visualHeight() - 2.2f) < 0.001f && core.visibleShards(8) == 8, "full crystal dimensions");
		core.setAmount(500);
		helper.assertTrue(core.visualHeight() < 2.2f && core.visualHeight() > 1.25f && core.visibleShards(8) < 8, "half-empty crystal must shrink");
		core.setAmount(1);
		helper.assertTrue(core.size() == 3 && Math.abs(core.visualHeight() - 1.25f) < 0.002f, "depletion changed the footprint or minimum height");
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "minerals", timeoutTicks = 100)
	public void crystalIsGatheredAndGone(GameTestHelper helper) {
		TestArena.clear(helper);
		ServerLevel level = helper.getLevel();
		BlockPos core = crystal(helper, 24, 24);
		helper.assertTrue(level.getBlockEntity(core) instanceof Minerals.Core entity && entity.amount() == Minerals.AMOUNT, "not a full crystal");
		helper.assertTrue(level.getBlockState(core.offset(1, 1, 1)).is(Minerals.CRYSTAL), "not 2 x 2 x 2");
		helper.assertTrue(level.getBlockState(core).getDestroySpeed(level, core) < 0, "a crystal can be broken");
		helper.assertTrue(level.getBlockState(core.offset(-2, -1, 0)).is(Minerals.EARTH) && level.getBlockState(core.below()).is(Minerals.EARTH),
			"no mineralized earth");
		helper.assertTrue(Minerals.harvest(level, core.offset(1, 1, 0), Minerals.PER_TRIP) == 5, "a trip is not 5");
		Minerals.Core entity = (Minerals.Core) level.getBlockEntity(core);
		helper.assertTrue(entity.amount() == Minerals.AMOUNT - 5, "the crystal keeps " + entity.amount());
		entity.setAmount(3);
		helper.assertTrue(Minerals.harvest(level, core, Minerals.PER_TRIP) == 3, "the last trip is not what was left");
		helper.assertTrue(!level.getBlockState(core).is(Minerals.CRYSTAL) && !level.getBlockState(core.offset(1, 1, 1)).is(Minerals.CRYSTAL),
			"the empty crystal is still there");
		helper.assertTrue(level.getBlockState(core.below()).is(Blocks.DIRT) && level.getBlockState(core.offset(-2, -1, 0)).is(Blocks.DIRT),
			"the earth stays mineralized");
		helper.succeed();
	}

	/** Owner 16.10: a crystal never hangs in the air — a small gap under it fills with mineralized earth, a deep one refuses it. */
	@GameTest(template = ARENA, batch = "minerals", timeoutTicks = 100)
	public void crystalsStandOnGround(GameTestHelper helper) {
		TestArena.clear(helper);
		ServerLevel level = helper.getLevel();
		BlockPos low = helper.absolutePos(new BlockPos(10, 4, 10)); // two air cells over the floor at y 1
		helper.assertTrue(Minerals.place(level, low, (pos, state) -> level.setBlock(pos, state, 2)), "a crystal 2 over the floor did not fit");
		for (BlockPos pos : BlockPos.betweenClosed(low.offset(0, -2, 0), low.offset(1, -1, 1)))
			helper.assertTrue(level.getBlockState(pos).is(Minerals.EARTH), "a gap under the crystal at " + pos);
		BlockPos high = helper.absolutePos(new BlockPos(30, 10, 30));
		helper.assertFalse(Minerals.place(level, high, (pos, state) -> level.setBlock(pos, state, 2)), "a crystal hangs 8 over the floor");
		helper.assertFalse(level.getBlockState(high).is(Minerals.CRYSTAL), "the refused crystal was put");
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "minerals", timeoutTicks = 100)
	public void noMainBuildingNearMinerals(GameTestHelper helper) {
		TestArena.clear(helper);
		ServerLevel level = helper.getLevel();
		crystal(helper, 40, 24);
		// owner 18.10: 9 blocks from the building's edge
		helper.assertTrue(Minerals.nearSite(level, helper.absolutePos(new BlockPos(26, 2, 24)), UnitKind.COMMAND_CENTER.width / 2.0),
			"a command center 6 blocks off the crystal is allowed");
		helper.assertTrue(!Minerals.nearSite(level, helper.absolutePos(new BlockPos(22, 2, 24)), UnitKind.COMMAND_CENTER.width / 2.0),
			"a command center 10 blocks off the crystal is refused");
		helper.assertTrue(!Minerals.nearSite(level, helper.absolutePos(new BlockPos(8, 2, 24)), 0.5), "a site 31 blocks off is refused");
		helper.succeed();
	}

	/** An SCV of a player's base gathers a crystal: 5 a trip, brought to the command center. */
	@GameTest(template = ARENA, batch = "mineralsScv", timeoutTicks = 1200)
	public void scvGathersMinerals(GameTestHelper helper) {
		TestArena.clear(helper);
		ServerLevel level = helper.getLevel();
		BlockPos core = crystal(helper, 34, 24);
		StarUnit cc = TestArena.unit(helper, UnitKind.COMMAND_CENTER, 10, 24, UUID.randomUUID());
		int[] start = new int[1];
		helper.runAfterDelay(20, () -> {
			Hive base = TestArena.base(helper, cc);
			helper.assertTrue(base != null, "no base");
			start[0] = base.mineralsCollected;
			StarUnit scv = TestArena.member(helper, base, UnitKind.SCV, 26, 24);
			scv.commandWork(StarUnit.Work.GATHER, core.offset(0, 1, 0));
		});
		helper.succeedWhen(() -> {
			Hive base = TestArena.base(helper, cc);
			helper.assertTrue(base != null && base.mineralsCollected - start[0] >= 10, "minerals brought: "
				+ (base == null ? 0 : base.mineralsCollected - start[0]));
			helper.assertTrue(level.getBlockEntity(core) instanceof Minerals.Core entity && entity.amount() <= Minerals.AMOUNT - 10, "the crystal was not mined");
		});
	}

	/**
	 * Owner 19.10: a block of minerals is mined like an ore but not broken — a shard a time (a rich one two), nine times;
	 * a worker takes a charge as minerals; handed in whole it is worth its nine charges; nine shards make one.
	 */
	@GameTest(template = ARENA, batch = "mineralBlock", timeoutTicks = 40)
	public void aBlockOfMineralsGivesShardsAndStays(GameTestHelper helper) {
		TestArena.clear(helper);
		net.minecraft.server.level.ServerLevel level = helper.getLevel();
		BlockPos pos = helper.absolutePos(new BlockPos(10, 2, 10)), rich = helper.absolutePos(new BlockPos(14, 2, 10));
		level.setBlock(pos, Minerals.BLOCK.defaultBlockState(), 3);
		level.setBlock(rich, Minerals.RICH_BLOCK.defaultBlockState(), 3);
		net.minecraft.server.level.ServerPlayer player = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayerInLevel();
		player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
		player.gameMode.destroyBlock(pos);
		helper.assertTrue(level.getBlockState(pos).is(Minerals.BLOCK) && level.getBlockState(pos).getValue(MineralBlock.CHARGES) == MineralBlock.FULL - 1,
			"the block did not stay with a charge less: " + level.getBlockState(pos));
		int shards = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(2),
			item -> item.getItem().is(Minerals.SHARD)).stream().mapToInt(item -> item.getItem().getCount()).sum();
		helper.assertTrue(shards == 1, "mining it dropped " + shards + " shards");
		helper.assertTrue(MineralBlock.harvest(level, rich) == 2 * Minerals.PER_TRIP, "a worker's trip at a rich block is not two shards' worth");
		for (int i = 0; i < MineralBlock.FULL - 1; i++) MineralBlock.harvest(level, pos);
		helper.assertTrue(level.getBlockState(pos).isAir(), "the block is still there after its nine charges");
		helper.assertTrue(dev.starmine.hive.HiveResources.yieldOfItem(new net.minecraft.world.item.ItemStack(Minerals.BLOCK)).minerals() == MineralBlock.FULL * Minerals.PER_TRIP,
			"a block handed in is not worth nine shards");
		helper.assertTrue(dev.starmine.hive.HiveResources.isOre(Minerals.RICH_BLOCK.defaultBlockState()), "workers do not mine rich minerals");
		var recipe = level.getRecipeManager().byKey(dev.starmine.StarMine.id("mineral_block"));
		helper.assertTrue(recipe.isPresent() && recipe.get().value().getResultItem(level.registryAccess()).is(Minerals.BLOCK.asItem()), "no recipe for the block");
		level.removePlayerImmediately(player, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
		helper.succeed();
	}
}
