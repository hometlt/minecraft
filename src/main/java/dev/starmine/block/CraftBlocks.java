package dev.starmine.block;

import dev.starmine.StarMine;
import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.fabricmc.fabric.api.registry.FuelRegistry;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;

/**
 * Crafted blocks and items of the races' resources (owner 19.10):
 * - the vespene torch (a vespene bucket over a stick: four), burning with a green flame ({@link #VESPENE_FLAME});
 * - the vespene lamp (vespene, glass and iron), a green light as bright as glowstone;
 * - mineral glass (glass among four mineral shards: four), clear and as strong against blasts as obsidian;
 * - vespene fuel (an empty canister and four buckets of vespene): {@link #FUEL_SMELTS} smeltings in a furnace — eight
 *   coal's worth —, and the empty canister comes back.
 */
public final class CraftBlocks {
	/** How many items a vespene fuel canister smelts (a furnace takes 200 ticks an item). */
	public static final int FUEL_SMELTS = 64;
	public static final SimpleParticleType VESPENE_FLAME = Registry.register(BuiltInRegistries.PARTICLE_TYPE, StarMine.id("vespene_flame"),
		FabricParticleTypes.simple());
	public static final Block VESPENE_TORCH = block("vespene_torch", new TorchBlock(VESPENE_FLAME, BlockBehaviour.Properties.of()
		.noCollission().instabreak().lightLevel(state -> 14).sound(SoundType.WOOD).pushReaction(PushReaction.DESTROY)));
	public static final Block VESPENE_WALL_TORCH = block("vespene_wall_torch", new WallTorchBlock(VESPENE_FLAME, BlockBehaviour.Properties.of()
		.noCollission().instabreak().lightLevel(state -> 14).sound(SoundType.WOOD).dropsLike(VESPENE_TORCH).pushReaction(PushReaction.DESTROY)));
	public static final Block VESPENE_LAMP = block("vespene_lamp", new Block(BlockBehaviour.Properties.of()
		.strength(0.3f).lightLevel(state -> 15).sound(SoundType.GLASS)));
	public static final Block MINERAL_GLASS = block("mineral_glass", new TransparentBlock(BlockBehaviour.Properties.of()
		.strength(1.5f, 1200).sound(SoundType.GLASS).noOcclusion().isValidSpawn((s, l, p, t) -> false)
		.isRedstoneConductor((s, l, p) -> false).isSuffocating((s, l, p) -> false).isViewBlocking((s, l, p) -> false)));
	public static final Item VESPENE_TORCH_ITEM = Registry.register(BuiltInRegistries.ITEM, StarMine.id("vespene_torch"),
		new StandingAndWallBlockItem(VESPENE_TORCH, VESPENE_WALL_TORCH, new Item.Properties(), Direction.DOWN));
	public static final Item VESPENE_FUEL = Registry.register(BuiltInRegistries.ITEM, StarMine.id("vespene_fuel"),
		new Item(new Item.Properties().stacksTo(1).craftRemainder(dev.starmine.vespene.Vespene.CANISTER_ITEM)));

	static {
		for (Block block : new Block[] {VESPENE_LAMP, MINERAL_GLASS})
			Registry.register(BuiltInRegistries.ITEM, BuiltInRegistries.BLOCK.getKey(block), new BlockItem(block, new Item.Properties()));
	}

	private CraftBlocks() {
	}

	private static Block block(String name, Block block) {
		return Registry.register(BuiltInRegistries.BLOCK, StarMine.id(name), block);
	}

	public static void load() {
		FuelRegistry.INSTANCE.add(VESPENE_FUEL, FUEL_SMELTS * 200);
		net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents.modifyEntriesEvent(net.minecraft.world.item.CreativeModeTabs.FUNCTIONAL_BLOCKS).register(entries -> {
			entries.accept(VESPENE_TORCH_ITEM);
			entries.accept(VESPENE_LAMP);
		});
		net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents.modifyEntriesEvent(net.minecraft.world.item.CreativeModeTabs.BUILDING_BLOCKS)
			.register(entries -> entries.accept(MINERAL_GLASS));
		net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents.modifyEntriesEvent(net.minecraft.world.item.CreativeModeTabs.INGREDIENTS)
			.register(entries -> entries.accept(VESPENE_FUEL));
	}
}
