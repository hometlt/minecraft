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
