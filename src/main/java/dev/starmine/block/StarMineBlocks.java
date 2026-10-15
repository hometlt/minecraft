package dev.starmine.block;

import dev.starmine.StarMine;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/**
 * StarMine blocks. Thick creep: the 2-block layer under zerg buildings and a ring around them —
 * unbreakable (hits sound like slime and do nothing), turns to gravel when its building dies
 * ({@link dev.starmine.hive.HiveTerrain}). Creep: the thin layer on any face. Creep tumor.
 * Their textures come from SC2's creep through Modkit (`textures/block/sc2/*`).
 */
public final class StarMineBlocks {
	public static final Block THICK_CREEP = register("thick_creep", new Block(BlockBehaviour.Properties.of()
		.mapColor(MapColor.COLOR_PURPLE).strength(-1f, 3_600_000f).sound(SoundType.SLIME_BLOCK)
		.noLootTable().pushReaction(PushReaction.BLOCK).isValidSpawn((state, level, pos, type) -> false)));

	public static final CreepBlock CREEP = register("creep", new CreepBlock(BlockBehaviour.Properties.of()
		.mapColor(MapColor.COLOR_PURPLE).noCollission().strength(0.2f).sound(SoundType.SLIME_BLOCK)
		.noLootTable().pushReaction(PushReaction.DESTROY).randomTicks()));

	public static final CreepTumorBlock CREEP_TUMOR = register("creep_tumor", new CreepTumorBlock(BlockBehaviour.Properties.of()
		.mapColor(MapColor.COLOR_PURPLE).strength(1.5f).sound(SoundType.SLIME_BLOCK).noOcclusion()
		.noLootTable().pushReaction(PushReaction.DESTROY)));

	/** Creep growths (own art): tendrils, pustules, vessels, two-block tentacles. */
	public static final CreepDecorBlock CREEP_TENDRIL = register("creep_tendril", new CreepDecorBlock(decor()));
	public static final CreepDecorBlock CREEP_PUSTULE = register("creep_pustule", new CreepDecorBlock(decor()));
	public static final CreepDecorBlock CREEP_VESSELS = register("creep_vessels", new CreepDecorBlock(decor()));
	public static final CreepTentacleBlock CREEP_TENTACLE = register("creep_tentacle", new CreepTentacleBlock(decor()));

	/** The sentry's force field (owner 14.10): a see-through wall for 11 s ({@link ForceFieldBlock}). */
	public static final ForceFieldBlock FORCE_FIELD = register("force_field", new ForceFieldBlock(BlockBehaviour.Properties.of()
		.mapColor(MapColor.COLOR_LIGHT_BLUE).strength(-1f, 3_600_000f).sound(SoundType.AMETHYST).noOcclusion().noLootTable()
		.lightLevel(state -> 7).pushReaction(PushReaction.BLOCK).isValidSpawn((state, level, pos, type) -> false)
		.isRedstoneConductor((state, level, pos) -> false).isSuffocating((state, level, pos) -> false)
		.isViewBlocking((state, level, pos) -> false)));

	/**
	 * Zerg flesh (owner 16.10): what the leviathan and the nydus worms are made of. Some of it glows; nothing
	 * spawns on glowing flesh, and its light keeps monsters off round it. Mined without silk touch glowing
	 * flesh comes off as plain flesh (it stops glowing).
	 */
	public static final Block ZERG_FLESH = register("zerg_flesh", new Block(BlockBehaviour.Properties.of()
		.mapColor(MapColor.CRIMSON_NYLIUM).strength(1.2f, 3f).sound(SoundType.WART_BLOCK)));
	public static final Block GLOWING_ZERG_FLESH = register("glowing_zerg_flesh", new Block(BlockBehaviour.Properties.of()
		.mapColor(MapColor.COLOR_PINK).strength(1.2f, 3f).sound(SoundType.WART_BLOCK).lightLevel(state -> 9)
		.isValidSpawn((state, level, pos, type) -> false)));

	/** Liquid creep (owner 16.10): fills the nydus tunnels; zerg rush through it and heal, others are poisoned ({@link LiquidCreepBlock}). */
	public static final StarFluid.Kind LIQUID_CREEP_KIND = new StarFluid.Kind(() -> StarMineBlocks.LIQUID_CREEP_SOURCE,
		() -> StarMineBlocks.LIQUID_CREEP_FLOWING, () -> StarMineBlocks.LIQUID_CREEP_BUCKET, () -> StarMineBlocks.LIQUID_CREEP, 15, 2, 2);
	public static final StarFluid LIQUID_CREEP_SOURCE = Registry.register(BuiltInRegistries.FLUID, StarMine.id("liquid_creep"),
		new StarFluid.Source(LIQUID_CREEP_KIND));
	public static final StarFluid LIQUID_CREEP_FLOWING = Registry.register(BuiltInRegistries.FLUID, StarMine.id("flowing_liquid_creep"),
		new StarFluid.Flowing(LIQUID_CREEP_KIND));
	public static final LiquidCreepBlock LIQUID_CREEP = register("liquid_creep", new LiquidCreepBlock(LIQUID_CREEP_SOURCE, BlockBehaviour.Properties.of()
		.mapColor(MapColor.COLOR_PURPLE).replaceable().noCollission().strength(100f).lightLevel(state -> 4).pushReaction(PushReaction.DESTROY)
		.noLootTable().liquid().sound(SoundType.EMPTY)));
	public static final net.minecraft.world.item.Item LIQUID_CREEP_BUCKET = Registry.register(BuiltInRegistries.ITEM, StarMine.id("liquid_creep_bucket"),
		new net.minecraft.world.item.BucketItem(LIQUID_CREEP_SOURCE, new net.minecraft.world.item.Item.Properties()
			.craftRemainder(net.minecraft.world.item.Items.BUCKET).stacksTo(1)));

	private static BlockBehaviour.Properties decor() {
		return BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).noCollission().instabreak()
			.sound(SoundType.SLIME_BLOCK).noLootTable().pushReaction(PushReaction.DESTROY).randomTicks();
	}

	/** Creep and everything growing on it (counts as creep for zerg and players standing on it). */
	public static boolean isCreep(net.minecraft.world.level.block.state.BlockState state) {
		return state.is(CREEP) || state.is(THICK_CREEP) || state.is(CREEP_TUMOR) || state.is(CREEP_TENDRIL)
			|| state.is(CREEP_PUSTULE) || state.is(CREEP_VESSELS) || state.is(CREEP_TENTACLE);
	}

	public static final BlockEntityType<CreepTumorBlockEntity> CREEP_TUMOR_ENTITY = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, StarMine.id("creep_tumor"),
		BlockEntityType.Builder.of(CreepTumorBlockEntity::new, CREEP_TUMOR).build(null));

	private StarMineBlocks() {
	}

	private static <T extends Block> T register(String name, T block) {
		return Registry.register(BuiltInRegistries.BLOCK, StarMine.id(name), block);
	}

	public static void load() {
		for (Block block : new Block[] {ZERG_FLESH, GLOWING_ZERG_FLESH})
			Registry.register(BuiltInRegistries.ITEM, BuiltInRegistries.BLOCK.getKey(block), new net.minecraft.world.item.BlockItem(block,
				new net.minecraft.world.item.Item.Properties()));
		net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents.modifyEntriesEvent(net.minecraft.world.item.CreativeModeTabs.NATURAL_BLOCKS)
			.register(entries -> {
				entries.accept(ZERG_FLESH);
				entries.accept(GLOWING_ZERG_FLESH);
			});
		net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents.modifyEntriesEvent(net.minecraft.world.item.CreativeModeTabs.TOOLS_AND_UTILITIES)
			.register(entries -> entries.accept(LIQUID_CREEP_BUCKET));
	}
}
