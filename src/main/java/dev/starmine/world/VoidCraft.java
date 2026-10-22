package dev.starmine.world;

import dev.starmine.StarMine;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** Building and lighting made from the Void's principal resource, the crystal (stored as void_shard in old saves). */
public final class VoidCraft {
	public static final Map<String, Block> BLOCKS = new LinkedHashMap<>();
	public static final Block BLOCK = register("void_crystal_block", new Block(properties()));
	public static final Block SLAB = register("void_crystal_slab", new SlabBlock(properties()));
	public static final Block STAIRS = register("void_crystal_stairs", new StairBlock(BLOCK.defaultBlockState(), properties()) { });
	public static final Block WALL = register("void_crystal_wall", new WallBlock(properties().forceSolidOn()));
	public static final Block CHISELED = register("chiseled_void_crystal", new Block(properties()));
	public static final Block LANTERN = register("void_lantern", new LanternBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.LANTERN)
		.lightLevel(state -> 15).sound(SoundType.AMETHYST)));

	private VoidCraft() { }

	private static BlockBehaviour.Properties properties() {
		return BlockBehaviour.Properties.of().strength(3, 9).requiresCorrectToolForDrops().sound(SoundType.AMETHYST).lightLevel(state -> 7);
	}

	private static Block register(String name, Block block) {
		Registry.register(BuiltInRegistries.BLOCK, StarMine.id(name), block);
		Registry.register(BuiltInRegistries.ITEM, StarMine.id(name), new BlockItem(block, new Item.Properties()));
		BLOCKS.put(name, block);
		return block;
	}

	public static void load() {
		net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents.modifyEntriesEvent(net.minecraft.world.item.CreativeModeTabs.BUILDING_BLOCKS)
			.register(entries -> BLOCKS.values().forEach(entries::accept));
	}
}
