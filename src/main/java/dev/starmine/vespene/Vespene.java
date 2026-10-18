package dev.starmine.vespene;

import dev.starmine.StarMine;
import java.util.List;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.BucketItem;
import dev.starmine.block.StarFluid;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import org.jetbrains.annotations.Nullable;

/**
 * Vespene (owner 14.10): veins of liquid vespene from the bedrock ({@link VespeneVeinFeature}, about
 * one in 48 overworld chunks), their outlets where workers gather it, buckets and canisters for players.
 */
public final class Vespene {
	/** The liquid (owner 16.10): slow and short like lava ({@link StarFluid}). */
	public static final StarFluid.Kind KIND = new StarFluid.Kind(() -> Vespene.SOURCE, () -> Vespene.FLOWING, () -> Vespene.BUCKET,
		() -> Vespene.VESPENE, 30, 2, 2);
	public static final StarFluid SOURCE = Registry.register(BuiltInRegistries.FLUID, StarMine.id("vespene"), new StarFluid.Source(KIND));
	public static final StarFluid FLOWING = Registry.register(BuiltInRegistries.FLUID, StarMine.id("flowing_vespene"), new StarFluid.Flowing(KIND));
	public static final VespeneBlock VESPENE = block("vespene", new VespeneBlock(SOURCE, BlockBehaviour.Properties.of()
		.mapColor(MapColor.COLOR_LIGHT_GREEN).noCollission().strength(100f).noLootTable().lightLevel(state -> 7).liquid()
		.sound(SoundType.EMPTY).pushReaction(PushReaction.DESTROY).isValidSpawn((state, level, pos, type) -> false)));
	/** An emptied outlet: unbreakable, it fills again from its vein. */
	public static final Block VENT = block("vespene_vent", new Block(BlockBehaviour.Properties.of()
		.mapColor(MapColor.STONE).strength(-1f, 3_600_000f).noLootTable().pushReaction(PushReaction.BLOCK)));
	public static final VespeneRootBlock ROOT = block("vespene_root", new VespeneRootBlock(BlockBehaviour.Properties.of()
		.mapColor(MapColor.STONE).strength(-1f, 3_600_000f).noLootTable().pushReaction(PushReaction.BLOCK)));
	public static final VespeneCanisterBlock CANISTER = block("vespene_canister", new VespeneCanisterBlock(BlockBehaviour.Properties.of()
		.mapColor(MapColor.METAL).strength(2f, 6f).sound(SoundType.METAL).noOcclusion().requiresCorrectToolForDrops()));

	public static final BlockEntityType<VespeneRootBlockEntity> ROOT_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
		StarMine.id("vespene_root"), BlockEntityType.Builder.of(VespeneRootBlockEntity::new, ROOT).build(null));

	public static final Item BUCKET = Registry.register(BuiltInRegistries.ITEM, StarMine.id("vespene_bucket"),
		new BucketItem(SOURCE, new Item.Properties().stacksTo(1).craftRemainder(Items.BUCKET)));
	public static final Item CANISTER_ITEM = Registry.register(BuiltInRegistries.ITEM, StarMine.id("vespene_canister"),
		new BlockItem(CANISTER, new Item.Properties().stacksTo(1)) {
			@Override
			public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
				lines.add(Component.translatable("item.starmine.vespene_canister.fill", VespeneCanisterBlock.fill(stack), VespeneCanisterBlock.CAPACITY));
			}
		});

	public static final Feature<NoneFeatureConfiguration> VEIN = Registry.register(BuiltInRegistries.FEATURE, StarMine.id("vespene_vein"),
		new VespeneVeinFeature(NoneFeatureConfiguration.CODEC));

	/** Owner 17.10: terrazine — purple, of the Void's lakes, worth twice vespene ({@link #perTrip}). */
	public static final StarFluid.Kind TERRAZINE_KIND = new StarFluid.Kind(() -> Vespene.TERRAZINE_SOURCE, () -> Vespene.TERRAZINE_FLOWING,
		() -> Vespene.TERRAZINE_BUCKET, () -> Vespene.TERRAZINE, 30, 2, 2);
	public static final StarFluid TERRAZINE_SOURCE = Registry.register(BuiltInRegistries.FLUID, StarMine.id("terrazine"), new StarFluid.Source(TERRAZINE_KIND));
	public static final StarFluid TERRAZINE_FLOWING = Registry.register(BuiltInRegistries.FLUID, StarMine.id("flowing_terrazine"), new StarFluid.Flowing(TERRAZINE_KIND));
	public static final VespeneBlock TERRAZINE = block("terrazine", new VespeneBlock(TERRAZINE_SOURCE, BlockBehaviour.Properties.of()
		.mapColor(MapColor.COLOR_PURPLE).noCollission().strength(100f).noLootTable().lightLevel(state -> 9).liquid()
		.sound(SoundType.EMPTY).pushReaction(PushReaction.DESTROY).isValidSpawn((state, level, pos, type) -> false)));
	public static final Item TERRAZINE_BUCKET = Registry.register(BuiltInRegistries.ITEM, StarMine.id("terrazine_bucket"),
		new BucketItem(TERRAZINE_SOURCE, new Item.Properties().stacksTo(1).craftRemainder(Items.BUCKET)));

	/** Vespene a worker brings home from one outlet (SC2: 4 per trip). */
	public static final int PER_TRIP = 4;

	/** Whether a block is terrazine (twice the worth of vespene). */
	public static boolean isTerrazine(BlockState state) {
		return state.is(TERRAZINE);
	}

	/** What a worker brings home from a full outlet: {@link #PER_TRIP}, twice that of terrazine. */
	public static int perTrip(BlockState state) {
		return isTerrazine(state) ? PER_TRIP * 2 : PER_TRIP;
	}

	private Vespene() {
	}

	private static boolean never(BlockState state, BlockGetter level, BlockPos pos) {
		return false;
	}

	private static <T extends Block> T block(String name, T block) {
		return Registry.register(BuiltInRegistries.BLOCK, StarMine.id(name), block);
	}

	/** An outlet: full (vespene marked outlet) or empty (a vent). */
	public static boolean isOutlet(BlockState state) {
		return state.is(VENT) || VespeneBlock.isSource(state) && state.getValue(VespeneBlock.OUTLET);
	}

	/** The outlet a building put at `site` stands on: under the site, at most a block aside or two down. */
	public static @Nullable BlockPos outletUnder(BlockGetter level, BlockPos site) {
		for (int dy = 1; dy <= 2; dy++)
			for (BlockPos pos : BlockPos.betweenClosed(site.offset(-1, -dy, -1), site.offset(1, -dy, 1)))
				if (isOutlet(level.getBlockState(pos))) return pos.immutable();
		return null;
	}

	/**
	 * Owner 17.10: a gas building's site over a volcano (an outlet risen from the ground) — the volcano is destroyed,
	 * the outlet is back at the ground; the site over it now (the same site when it was no volcano).
	 */
	public static BlockPos collapseUnder(net.minecraft.world.level.Level level, BlockPos site) {
		BlockPos outlet = outletFor(level, site);
		if (outlet == null) return site;
		VespeneRootBlockEntity vein = VespeneRootBlockEntity.veinOf(level, outlet);
		return vein == null ? site : vein.collapse(level, outlet).above();
	}

	/**
	 * The outlet a gas building put at `chosen` takes: the one under it, else the nearest one a little aside or up to
	 * {@link VespeneRootBlockEntity#VOLCANO_HEIGHT} above (owner 18.10: a click on a volcano's cone takes the outlet
	 * on its top, the extractor destroys the volcano and stands where it rose from). Null when there is none.
	 */
	public static @Nullable BlockPos outletFor(BlockGetter level, BlockPos chosen) {
		BlockPos under = outletUnder(level, chosen);
		if (under != null) return under;
		BlockPos best = null;
		for (BlockPos pos : BlockPos.betweenClosed(chosen.offset(-6, -2, -6), chosen.offset(6, VespeneRootBlockEntity.VOLCANO_HEIGHT + 2, 6)))
			if (isOutlet(level.getBlockState(pos)) && (best == null || pos.distSqr(chosen) < best.distSqr(chosen))) best = pos.immutable();
		return best;
	}

	/** Where a gas building at `chosen` will stand: over its outlet, at the ground it rose from when it is a volcano. */
	public static @Nullable BlockPos siteFor(net.minecraft.world.level.Level level, BlockPos chosen) {
		BlockPos outlet = outletFor(level, chosen);
		if (outlet == null) return null;
		VespeneRootBlockEntity vein = VespeneRootBlockEntity.veinOf(level, outlet);
		return outlet.below(vein == null ? 0 : vein.risen(outlet)).above();
	}

	/** How far round a gas building's site a worker may stop to put it down: a volcano's cone keeps it off (0: no volcano). */
	public static double coneReach(net.minecraft.world.level.Level level, BlockPos site) {
		BlockPos outlet = outletFor(level, site);
		VespeneRootBlockEntity vein = outlet == null ? null : VespeneRootBlockEntity.veinOf(level, outlet);
		int risen = vein == null ? 0 : vein.risen(outlet);
		return risen == 0 ? 0 : Math.ceil(1 + (risen + 2) * 0.75) + 1;
	}

	/**
	 * The vespene left in an outlet's vein and what it holds full, both in vespene (a block is a trip's worth); null
	 * when its vein is not known here. Works on the client too (the vein is sent with its chunk).
	 */
	public static int @Nullable [] gasLeft(net.minecraft.world.level.Level level, BlockPos outlet) {
		VespeneRootBlockEntity vein = VespeneRootBlockEntity.veinOf(level, outlet);
		if (vein == null || vein.capacity() == 0) return null;
		int per = perTrip(level.getBlockState(outlet));
		return new int[] {vein.content(level) * per, vein.capacity() * per};
	}

	/** The outlets within `radius` of a point (nearest first). */
	public static List<BlockPos> outletsNear(BlockGetter level, BlockPos centre, int radius, int height) {
		List<BlockPos> found = new java.util.ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-radius, -height, -radius), centre.offset(radius, height, radius)))
			if (isOutlet(level.getBlockState(pos))) found.add(pos.immutable());
		found.sort(java.util.Comparator.comparingDouble(pos -> pos.distSqr(centre)));
		return found;
	}

	public static void load() {
		BiomeModifications.addFeature(BiomeSelectors.foundInOverworld(), GenerationStep.Decoration.UNDERGROUND_STRUCTURES,
			ResourceKey.create(Registries.PLACED_FEATURE, StarMine.id("vespene_vein")));
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
			entries.accept(BUCKET);
			entries.accept(TERRAZINE_BUCKET);
			entries.accept(CANISTER_ITEM);
		});
	}
}
