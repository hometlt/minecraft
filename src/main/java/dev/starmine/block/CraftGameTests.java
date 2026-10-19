package dev.starmine.block;

import static dev.starmine.ability.TestArena.ARENA;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.fabric.api.registry.FuelRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Owner 19.10: the crafted blocks and items of the races' resources, and their recipes. */
public class CraftGameTests implements FabricGameTest {
	@GameTest(template = ARENA, batch = "craftResources", timeoutTicks = 40)
	public void resourceCraftsWork(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		var recipes = level.getServer().getRecipeManager();
		for (String id : new String[] {"vespene_torch", "vespene_lamp", "mineral_glass", "vespene_fuel", "mineral_block",
			"zerg_armor_shards_from_wall", "terran_armor_shards_from_wall", "protoss_armor_shards_from_wall"})
			helper.assertTrue(recipes.byKey(ResourceLocation.fromNamespaceAndPath("starmine", id)).isPresent(), "no recipe " + id);
		Integer burn = FuelRegistry.INSTANCE.get(CraftBlocks.VESPENE_FUEL);
		helper.assertTrue(burn != null && burn == CraftBlocks.FUEL_SMELTS * 200, "vespene fuel burns " + burn + " ticks, not 64 smeltings");
		helper.assertTrue(new ItemStack(CraftBlocks.VESPENE_FUEL).getItem().getCraftingRemainingItem() == dev.starmine.vespene.Vespene.CANISTER_ITEM,
			"the empty canister does not come back");
		helper.assertTrue(CraftBlocks.MINERAL_GLASS.getExplosionResistance() >= Blocks.OBSIDIAN.getExplosionResistance(), "mineral glass is not blast-proof");
		BlockPos at = helper.absolutePos(new BlockPos(4, 2, 4));
		level.setBlock(at, CraftBlocks.VESPENE_TORCH.defaultBlockState(), Block.UPDATE_ALL);
		helper.assertTrue(level.getBlockState(at).getLightEmission() == 14, "the vespene torch gives no light");
		level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		helper.succeed();
	}
}
