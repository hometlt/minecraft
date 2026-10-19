package dev.starmine.gear;

import static dev.starmine.ability.TestArena.ARENA;

import dev.starmine.entity.UnitKind;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/** Owner 19.10: the races' tools — iron's level, their uses, the protoss shield, the terran shield's blasts; the zerg's mending. */
public class RaceToolGameTests implements FabricGameTest {
	@GameTest(template = ARENA, batch = "raceTools", timeoutTicks = 40)
	public void raceToolsHaveTheirWays(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		var recipes = level.getServer().getRecipeManager();
		for (UnitKind.Race race : UnitKind.Race.values())
			for (String kind : RaceTools.KINDS) {
				String id = race.name().toLowerCase() + "_" + kind;
				helper.assertTrue(recipes.byKey(ResourceLocation.fromNamespaceAndPath("starmine", id)).isPresent(), "no recipe " + id);
			}
		ItemStack zerg = new ItemStack(RaceTools.item(UnitKind.Race.ZERG, "pickaxe"));
		ItemStack terran = new ItemStack(RaceTools.item(UnitKind.Race.TERRAN, "pickaxe"));
		helper.assertTrue(zerg.getMaxDamage() == 250 && terran.getMaxDamage() == 375, "uses " + zerg.getMaxDamage() + " / " + terran.getMaxDamage());
		helper.assertTrue(zerg.isCorrectToolForDrops(Blocks.DIAMOND_ORE.defaultBlockState()) && !zerg.isCorrectToolForDrops(Blocks.OBSIDIAN.defaultBlockState()),
			"not of iron's level");
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayerInLevel();
		// the protoss shield takes 30 wears, then the tool wears
		ItemStack protoss = new ItemStack(RaceTools.item(UnitKind.Race.PROTOSS, "sword"));
		for (int i = 0; i < RaceTools.SHIELD_HITS; i++) protoss.hurtAndBreak(1, level, null, item -> { }); // no player: a mock one has infinite materials
		boolean shielded = protoss.getDamageValue() == 0 && RaceTools.shield(protoss, 0) == 0;
		protoss.hurtAndBreak(1, level, null, item -> { }); // no player: a mock one has infinite materials
		boolean worn = protoss.getDamageValue() == 1;
		// the terran shield held: blasts cut
		player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, new ItemStack(RaceTools.item(UnitKind.Race.TERRAN, "shield")));
		float blast = RaceTools.blast(player, level.damageSources().explosion(null, null), 10);
		level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
		helper.assertTrue(shielded, "the protoss shield did not take the first 30 wears: worn " + protoss.getDamageValue());
		helper.assertTrue(worn, "the protoss tool does not wear once its shield is down");
		helper.assertTrue(Math.abs(blast - 10 * RaceTools.TERRAN_BLAST) < 0.01, "the terran shield does not cut blasts: " + blast);
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "raceToolMend", timeoutTicks = 260)
	public void aZergToolMendsItself(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayerInLevel();
		ItemStack zerg = new ItemStack(RaceTools.item(UnitKind.Race.ZERG, "axe"));
		zerg.setDamageValue(10);
		helper.onEachTick(() -> RaceTools.inventoryTick(UnitKind.Race.ZERG, zerg, level, player));
		helper.runAfterDelay(RaceTools.MEND_TICKS + 30, () -> {
			level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
			helper.assertTrue(zerg.getDamageValue() < 10, "the zerg axe did not mend in " + RaceTools.MEND_TICKS + " ticks");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "findersChests", timeoutTicks = 60)
	public void findersPointAndChestsKeep(GameTestHelper helper) {
		dev.starmine.ability.TestArena.clear(helper);
		ServerLevel level = helper.getLevel();
		for (String id : new String[] {"mineral_compass", "vespene_detector", "zerg_chest", "terran_safe", "protoss_chest"})
			helper.assertTrue(level.getServer().getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath("starmine", id)).isPresent(), "no recipe " + id);
		net.minecraft.core.BlockPos core = helper.absolutePos(new net.minecraft.core.BlockPos(30, 2, 30));
		helper.assertTrue(dev.starmine.mineral.Minerals.place(level, core, (pos, state) -> level.setBlock(pos, state, net.minecraft.world.level.block.Block.UPDATE_ALL)),
			"no crystal placed");
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayerInLevel();
		net.minecraft.core.BlockPos from = helper.absolutePos(new net.minecraft.core.BlockPos(4, 2, 4));
		player.teleportTo(from.getX() + 0.5, from.getY(), from.getZ() + 0.5);
		net.minecraft.core.BlockPos found = Finders.nearest(level, from, false);
		// a chest keeps what is put in, gives it back broken, and is its race's
		net.minecraft.core.BlockPos at = from.east(2);
		level.setBlock(at, RaceChests.BLOCKS.get(UnitKind.Race.ZERG).defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
		if (level.getBlockEntity(at) instanceof RaceChests.Store store) store.setItem(5, new ItemStack(net.minecraft.world.item.Items.DIAMOND, 3));
		level.destroyBlock(at, true);
		boolean dropped = !level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(at).inflate(2),
			e -> e.getItem().is(net.minecraft.world.item.Items.DIAMOND)).isEmpty();
		level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(at).inflate(3)).forEach(Entity::discard);
		level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
		helper.assertTrue(found != null && found.closerThan(core, 3), "the mineral compass finds " + found + ", not the crystal at " + core);
		helper.assertTrue(dropped, "the zerg chest did not give back what it held");
		helper.assertTrue(dev.starmine.block.ArmorBlocks.raceOf(RaceChests.BLOCKS.get(UnitKind.Race.TERRAN)) == UnitKind.Race.TERRAN, "the terran safe is not terran");
		helper.assertTrue(RaceChests.BLOCKS.get(UnitKind.Race.TERRAN).getExplosionResistance() >= 1200, "the terran safe is not blast-proof");
		helper.succeed();
	}
}
