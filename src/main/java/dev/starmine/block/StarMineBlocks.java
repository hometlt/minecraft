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
	}
}
