package dev.starmine.world;

import com.mojang.serialization.Codec;
import dev.starmine.StarMine;
import dev.starmine.block.StarMineBlocks;
import dev.starmine.entity.UnitKind;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.jetbrains.annotations.Nullable;

/**
 * Wild packs of the three races met about the world like monsters (owner 16.10), now and then
 * (`worldgen/placed_feature/wild_pack.json`), caves too. Each waits in a {@link DungeonHeart} under it
 * and comes out when a player comes near:
 * - zerg, on the surface or in a cave: zerglings, banelings, roaches, a hydralisk, sometimes a lurker,
 *   eggs and dormant creep tumors (lumps of flesh: they spread no creep);
 * - protoss, on the surface: a few zealots, a stalker, an adept or a sentry by a lone pylon of stone and light;
 * - dark templar, in a cave: one or two, by a crystal of amethyst;
 * - terrans, on the surface: marines, a marauder, a ghost or a hellbat (the firebat's heir), round a
 *   campfire with logs to sit on.
 */
public final class WildPacks extends Feature<NoneFeatureConfiguration> {
	public static final Feature<NoneFeatureConfiguration> FEATURE = Registry.register(BuiltInRegistries.FEATURE, StarMine.id("wild_pack"),
		new WildPacks(NoneFeatureConfiguration.CODEC));

	public enum Kind { ZERG, PROTOSS, DARK_TEMPLAR, TERRAN }

	private WildPacks(Codec<NoneFeatureConfiguration> codec) {
		super(codec);
	}

	public static void load() {
		BiomeModifications.addFeature(BiomeSelectors.foundInOverworld(), GenerationStep.Decoration.SURFACE_STRUCTURES,
			ResourceKey.create(Registries.PLACED_FEATURE, StarMine.id("wild_pack")));
	}

	@Override
	public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
		WorldGenLevel level = context.level();
		RandomSource random = context.random();
		int x = (context.origin().getX() & ~15) + 8, z = (context.origin().getZ() & ~15) + 8;
		Kind kind = Kind.values()[random.nextInt(Kind.values().length)];
		return build(level, random, kind, x, z) != null;
	}

	/** A pack of `kind` about the column (x, z); its heart, or null where it does not fit. */
	public static @Nullable BlockPos build(WorldGenLevel level, RandomSource random, Kind kind, int x, int z) {
		boolean cave = kind == Kind.DARK_TEMPLAR || kind == Kind.ZERG && random.nextBoolean();
		BlockPos ground = cave ? cave(level, x, z) : surface(level, x, z);
		return ground == null ? null : build(level, random, kind, ground);
	}

	/** {@link #build} on `ground` (the first free cell over its floor). */
	public static @Nullable BlockPos build(WorldGenLevel level, RandomSource random, Kind kind, BlockPos ground) {
		return switch (kind) {
			case ZERG -> zerg(level, random, ground);
			case PROTOSS -> protoss(level, random, ground);
			case DARK_TEMPLAR -> darkTemplars(level, random, ground);
			case TERRAN -> terrans(level, random, ground);
		};
	}

	/** The first free cell over dry land, or null on water. */
	private static @Nullable BlockPos surface(WorldGenLevel level, int x, int z) {
		int top = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
		if (top > level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) || top <= level.getMinBuildHeight() + 10) return null;
		return new BlockPos(x, top, z);
	}

	/** A cave's floor under the column: an air cell over solid ground with room above it, or null. */
	private static @Nullable BlockPos cave(WorldGenLevel level, int x, int z) {
		int top = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 8;
		for (int y = top; y > level.getMinBuildHeight() + 8; y--) {
			BlockPos at = new BlockPos(x, y, z);
			if (level.getBlockState(at).isAir() && level.getBlockState(at.above()).isAir() && level.getBlockState(at.above(2)).isAir()
				&& level.getBlockState(at.below()).isSolid() && level.getFluidState(at.below()).isEmpty()) return at;
		}
		return null;
	}

	private static void set(WorldGenLevel level, BlockPos pos, BlockState state) {
		if (pos.getY() <= level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) return;
		BlockState old = level.getBlockState(pos);
		if (old.is(Blocks.BEDROCK) || level.getBlockEntity(pos) != null) return;
		level.setBlock(pos, state, Block.UPDATE_CLIENTS);
	}

	/** The heart under `ground`, looking like `look` once its guards are out. */
	private static @Nullable DungeonHeart.Heart heart(WorldGenLevel level, BlockPos ground, Block look) {
		BlockPos pos = ground.below();
		level.setBlock(pos, DungeonHeart.BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
		if (!(level.getBlockEntity(pos) instanceof DungeonHeart.Heart heart)) return null;
		heart.look(look);
		return heart;
	}

	/** A spot round the pack's middle, `r` blocks off, on the ground of `ground`'s height. */
	private static BlockPos around(BlockPos ground, int i, int count, double r) {
		double a = i * Math.PI * 2 / count;
		return ground.offset((int) Math.round(Math.cos(a) * r), 0, (int) Math.round(Math.sin(a) * r));
	}

	private static BlockPos zerg(WorldGenLevel level, RandomSource random, BlockPos ground) {
		DungeonHeart.Heart heart = heart(level, ground, StarMineBlocks.ZERG_FLESH);
		if (heart == null) return null;
		// dormant tumors: lumps of flesh with a glowing top; flesh underfoot about them
		int tumors = 1 + random.nextInt(2);
		for (int i = 0; i < tumors; i++) {
			BlockPos at = around(ground, i, tumors, 4 + random.nextInt(3));
			set(level, at.below(), StarMineBlocks.ZERG_FLESH.defaultBlockState());
			set(level, at, StarMineBlocks.ZERG_FLESH.defaultBlockState());
			set(level, at.above(), StarMineBlocks.GLOWING_ZERG_FLESH.defaultBlockState());
		}
		for (int dx = -3; dx <= 3; dx++)
			for (int dz = -3; dz <= 3; dz++)
				if (dx * dx + dz * dz <= 9 && random.nextInt(3) > 0 && level.getBlockState(ground.offset(dx, -1, dz)).isSolid()
					&& !(dx == 0 && dz == 0)) set(level, ground.offset(dx, -1, dz), StarMineBlocks.ZERG_FLESH.defaultBlockState());
		List<UnitKind> pack = new ArrayList<>();
		for (int i = 0, n = 2 + random.nextInt(3); i < n; i++) pack.add(UnitKind.ZERGLING);
		for (int i = 0, n = random.nextInt(3); i < n; i++) pack.add(UnitKind.BANELING);
		for (int i = 0, n = random.nextInt(3); i < n; i++) pack.add(UnitKind.ROACH);
		if (random.nextBoolean()) pack.add(UnitKind.HYDRALISK);
		if (random.nextInt(4) == 0) pack.add(UnitKind.LURKER);
		for (int i = 0; i < pack.size(); i++) heart.add(pack.get(i), around(ground, i, pack.size(), 2 + random.nextInt(3)), null, 0);
		int eggs = 2 + random.nextInt(2);
		for (int i = 0; i < eggs; i++) {
			UnitKind brood = random.nextBoolean() ? UnitKind.ZERGLING : UnitKind.ROACH;
			heart.add(UnitKind.EGG, around(ground, i, eggs, 6), brood, brood == UnitKind.ZERGLING ? 2 : 1);
		}
		return heart.getBlockPos();
	}

	private static BlockPos protoss(WorldGenLevel level, RandomSource random, BlockPos ground) {
		DungeonHeart.Heart heart = heart(level, ground, Blocks.CHISELED_SANDSTONE);
		if (heart == null) return null;
		// a lone pylon: a sandstone foot, a crystal of light and blue glass, gold-coloured fins
		BlockPos pylon = ground.offset(3, 0, 0);
		for (int dx = -1; dx <= 1; dx++)
			for (int dz = -1; dz <= 1; dz++) set(level, pylon.offset(dx, -1, dz), Blocks.SMOOTH_SANDSTONE.defaultBlockState());
		set(level, pylon, Blocks.CHISELED_SANDSTONE.defaultBlockState());
		for (int y = 1; y <= 3; y++) set(level, pylon.above(y), y == 2 ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState());
		set(level, pylon.above(4), Blocks.END_ROD.defaultBlockState());
		for (Direction side : Direction.Plane.HORIZONTAL) set(level, pylon.relative(side).above(2), Blocks.YELLOW_TERRACOTTA.defaultBlockState());
		List<UnitKind> pack = new ArrayList<>(List.of(UnitKind.ZEALOT, UnitKind.ZEALOT));
		if (random.nextBoolean()) pack.add(UnitKind.ZEALOT);
		pack.add(UnitKind.STALKER);
		pack.add(random.nextBoolean() ? UnitKind.ADEPT : UnitKind.SENTRY);
		for (int i = 0; i < pack.size(); i++) heart.add(pack.get(i), around(ground, i, pack.size(), 3 + random.nextInt(2)), null, 0);
		return heart.getBlockPos();
	}

	private static BlockPos darkTemplars(WorldGenLevel level, RandomSource random, BlockPos ground) {
		DungeonHeart.Heart heart = heart(level, ground, Blocks.CHISELED_DEEPSLATE);
		if (heart == null) return null;
		set(level, ground.offset(2, 0, 1), Blocks.AMETHYST_BLOCK.defaultBlockState());
		set(level, ground.offset(2, 1, 1), Blocks.AMETHYST_CLUSTER.defaultBlockState());
		set(level, ground.offset(-2, 0, -1), Blocks.LARGE_AMETHYST_BUD.defaultBlockState());
		int templars = 1 + random.nextInt(2);
		for (int i = 0; i < templars; i++) heart.add(UnitKind.DARK_TEMPLAR, around(ground, i, templars, 2), null, 0);
		return heart.getBlockPos();
	}

	private static BlockPos terrans(WorldGenLevel level, RandomSource random, BlockPos ground) {
		DungeonHeart.Heart heart = heart(level, ground.offset(0, 0, 0), Blocks.COARSE_DIRT);
		if (heart == null) return null;
		// a campfire, logs round it to sit on, a crate
		BlockPos fire = ground.offset(0, 0, 0);
		for (int dx = -1; dx <= 1; dx++)
			for (int dz = -1; dz <= 1; dz++) for (int y = 0; y <= 2; y++) set(level, fire.offset(dx, y, dz), Blocks.AIR.defaultBlockState());
		set(level, fire, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
		BlockState alongX = Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X),
			alongZ = Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
		for (int k = -1; k <= 1; k++) {
			set(level, fire.offset(k, 0, -3), alongX);
			set(level, fire.offset(k, 0, 3), alongX);
			set(level, fire.offset(-3, 0, k), alongZ);
		}
		set(level, fire.offset(3, 0, 2), Blocks.BARREL.defaultBlockState());
		List<UnitKind> pack = new ArrayList<>(List.of(UnitKind.MARINE, UnitKind.MARINE));
		if (random.nextBoolean()) pack.add(UnitKind.MARINE);
		pack.add(random.nextBoolean() ? UnitKind.MARAUDER : UnitKind.HELLION); // a hellion comes out a hellbat ({@link DungeonHeart})
		if (random.nextInt(3) == 0) pack.add(UnitKind.GHOST);
		for (int i = 0; i < pack.size(); i++) heart.add(pack.get(i), around(ground, i, pack.size(), 2), null, 0);
		return heart.getBlockPos();
	}
}
