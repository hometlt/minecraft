package dev.starmine.world;

import com.mojang.serialization.Codec;
import dev.starmine.StarMine;
import dev.starmine.entity.UnitKind;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Small dungeons scattered over the world (owner 15.10), about one attempt in {@code 192} chunks
 * (`worldgen/placed_feature/small_dungeon.json`), one of three at random:
 * - a zerg nest underground: a cave of flesh with eggs and a few zerglings and a roach, a tunnel up;
 * - a terran outpost on the surface: a concrete pad, decorative andesite walls with a door and windows, crates,
 *   a landing mark; marines, sometimes a marauder;
 * - a Xel'Naga temple underground: a hall of deepslate with purpur pillars, amethyst and light, an altar
 *   with a chest; two or three zealots, or one or two dark templars; a spiral stair up to a small shrine.
 * Built while the world generates (its chunk and the ones next to it only); the guards appear when a
 * player comes near ({@link DungeonHeart}).
 */
public final class SmallDungeons extends Feature<NoneFeatureConfiguration> {
	public static final Feature<NoneFeatureConfiguration> FEATURE = Registry.register(BuiltInRegistries.FEATURE, StarMine.id("small_dungeon"),
		new SmallDungeons(NoneFeatureConfiguration.CODEC));

	public enum Kind { ZERG_NEST, TERRAN_OUTPOST, XELNAGA_TEMPLE }

	private SmallDungeons(Codec<NoneFeatureConfiguration> codec) {
		super(codec);
	}

	public static void load() {
		BiomeModifications.addFeature(BiomeSelectors.foundInOverworld(), GenerationStep.Decoration.SURFACE_STRUCTURES,
			ResourceKey.create(Registries.PLACED_FEATURE, StarMine.id("small_dungeon")));
	}

	@Override
	public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
		WorldGenLevel level = context.level();
		RandomSource random = context.random();
		int cx = (context.origin().getX() & ~15) + 8, cz = (context.origin().getZ() & ~15) + 8;
		Kind kind = Kind.values()[random.nextInt(Kind.values().length)];
		return build(level, random, kind, cx, cz) != null;
	}

	/** Builds a dungeon of `kind` about the column (x, z); its heart (where the guards wait), or null when the place does not suit. */
	public static BlockPos build(WorldGenLevel level, RandomSource random, Kind kind, int x, int z) {
		int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
		boolean wet = surface > level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
		if (wet) return null;
		return build(level, random, kind, x, z, surface);
	}

	/** {@link #build} on ground whose top is at `surface` (the first air above it). */
	public static BlockPos build(WorldGenLevel level, RandomSource random, Kind kind, int x, int z, int surface) {
		if (surface <= level.getMinBuildHeight() + 30) return null;
		return switch (kind) {
			case TERRAN_OUTPOST -> outpost(level, random, new BlockPos(x, surface, z));
			case ZERG_NEST -> nest(level, random, new BlockPos(x, Math.max(level.getMinBuildHeight() + 14, surface - 14 - random.nextInt(8)), z), surface);
			case XELNAGA_TEMPLE -> temple(level, random, new BlockPos(x, Math.max(level.getMinBuildHeight() + 14, surface - 18 - random.nextInt(10)), z), surface);
		};
	}

	// ---- blocks ----

	private static void set(WorldGenLevel level, BlockPos pos, BlockState state) {
		if (pos.getY() <= level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) return;
		BlockState old = level.getBlockState(pos);
		if (old.is(Blocks.BEDROCK) || level.getBlockEntity(pos) != null) return;
		level.setBlock(pos, state, 2);
	}

	private static void box(WorldGenLevel level, BlockPos a, BlockPos b, BlockState state) {
		for (BlockPos pos : BlockPos.betweenClosed(a, b)) set(level, pos.immutable(), state);
	}

	private static void chest(WorldGenLevel level, RandomSource random, BlockPos pos, ResourceKey<LootTable> loot) {
		level.setBlock(pos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.Plane.HORIZONTAL.getRandomDirection(random)), 2);
		RandomizableContainer.setBlockEntityLootTable(level, random, pos, loot);
	}

	private static DungeonHeart.Heart heart(WorldGenLevel level, BlockPos pos, Block look) {
		level.setBlock(pos, DungeonHeart.BLOCK.defaultBlockState(), 2);
		if (!(level.getBlockEntity(pos) instanceof DungeonHeart.Heart heart)) return null;
		heart.look(look);
		return heart;
	}

	/** A spiral stair round a column from `bottom` (its first step) up to the surface, in a shaft 5 across. */
	private static void spiral(WorldGenLevel level, BlockPos bottom, int surface, BlockState wall, BlockState step) {
		int[][] ring = {{-1, -1}, {0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}};
		for (int y = bottom.getY(); y <= surface + 3; y++)
			for (int dx = -2; dx <= 2; dx++)
				for (int dz = -2; dz <= 2; dz++) {
					BlockPos pos = new BlockPos(bottom.getX() + dx, y, bottom.getZ() + dz);
					boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
					if (edge) {
						if (y < surface && !level.getBlockState(pos).isAir()) set(level, pos, wall);
					} else set(level, pos, dx == 0 && dz == 0 && y < surface ? wall : Blocks.AIR.defaultBlockState());
				}
		for (int y = bottom.getY(), i = 0; y < surface; y++, i++) {
			int[] cell = ring[i % ring.length];
			set(level, new BlockPos(bottom.getX() + cell[0], y, bottom.getZ() + cell[1]), step);
		}
	}

	// ---- the terran outpost ----

	private static BlockPos outpost(WorldGenLevel level, RandomSource random, BlockPos ground) {
		BlockState concrete = Blocks.GRAY_CONCRETE.defaultBlockState(), light = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(),
			yellow = Blocks.YELLOW_CONCRETE.defaultBlockState(), iron = Blocks.POLISHED_ANDESITE.defaultBlockState(), air = Blocks.AIR.defaultBlockState(),
			glass = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState(), lamp = Blocks.SEA_LANTERN.defaultBlockState();
		int r = 11;
		for (int dx = -r; dx <= r; dx++)
			for (int dz = -r; dz <= r; dz++) {
				BlockPos column = ground.offset(dx, 0, dz);
				for (int y = 0; y <= 12; y++) set(level, column.above(y), air);
				for (int y = 2; y <= 5; y++) if (level.getBlockState(column.below(y)).isAir()) set(level, column.below(y), Blocks.STONE.defaultBlockState());
				boolean line = Math.abs(dx) == r || Math.abs(dz) == r;
				set(level, column.below(), line ? yellow : (Math.floorMod(dx, 6) < 3) == (Math.floorMod(dz, 6) < 3) ? concrete : light);
			}
		// the barracks: decorative stone walls (not mineable iron storage), windows and lights
		BlockPos a = ground.offset(-8, 0, -6), b = ground.offset(3, 7, 6);
		for (BlockPos pos : BlockPos.betweenClosed(a, b)) {
			boolean wall = pos.getX() == a.getX() || pos.getX() == b.getX() || pos.getZ() == a.getZ() || pos.getZ() == b.getZ();
			boolean roof = pos.getY() == b.getY();
			int up = pos.getY() - ground.getY();
			BlockState state = roof ? concrete : wall ? (up == 4 && Math.floorMod(pos.getX() + pos.getZ(), 3) != 0 ? glass : iron) : air;
			set(level, pos.immutable(), state);
		}
		box(level, ground.offset(3, 0, -2), ground.offset(3, 4, 2), air); // the door
		for (int dx = -6; dx <= 1; dx += 3) set(level, ground.offset(dx, 7, 0), lamp);
		for (int up = 8; up <= 10; up++) set(level, ground.offset(-6, up, -4), Blocks.LIGHTNING_ROD.defaultBlockState());
		// crates, a landing mark, lamp posts
		chest(level, random, ground.offset(-7, 0, 5), BuiltInLootTables.SIMPLE_DUNGEON);
		chest(level, random, ground.offset(6, 0, -9), BuiltInLootTables.VILLAGE_WEAPONSMITH);
		for (int a2 = 0; a2 < 360; a2 += 15) {
			double rad = Math.toRadians(a2);
			set(level, ground.offset(6 + (int) Math.round(Math.cos(rad) * 3), -1, 6 + (int) Math.round(Math.sin(rad) * 3)), yellow);
		}
		for (int[] post : new int[][] {{-10, -10}, {10, -10}, {-10, 10}, {10, 10}}) {
			box(level, ground.offset(post[0], 0, post[1]), ground.offset(post[0], 3, post[1]), Blocks.IRON_BARS.defaultBlockState());
			set(level, ground.offset(post[0], 4, post[1]), lamp);
		}
		DungeonHeart.Heart heart = heart(level, ground.offset(-2, -1, 0), Blocks.GRAY_CONCRETE);
		if (heart == null) return null;
		int marines = 2 + random.nextInt(3);
		for (int i = 0; i < marines; i++) heart.add(UnitKind.MARINE, ground.offset(6 - i * 3, 0, -6 + (i % 2) * 12), null, 0);
		if (random.nextBoolean()) heart.add(UnitKind.MARAUDER, ground.offset(-3, 0, 0), null, 0);
		return heart.getBlockPos();
	}

	// ---- the zerg nest ----

	private static BlockPos nest(WorldGenLevel level, RandomSource random, BlockPos centre, int surface) {
		BlockState flesh = Blocks.NETHER_WART_BLOCK.defaultBlockState(), floor = Blocks.CRIMSON_NYLIUM.defaultBlockState(),
			air = Blocks.AIR.defaultBlockState(), glow = Blocks.SHROOMLIGHT.defaultBlockState();
		double rx = 9, ry = 5.5, rz = 9;
		for (int dx = -10; dx <= 10; dx++)
			for (int dy = -6; dy <= 6; dy++)
				for (int dz = -10; dz <= 10; dz++) {
					double d = Painter.sq(dx / rx) + Painter.sq(dy / ry) + Painter.sq(dz / rz);
					BlockPos pos = centre.offset(dx, dy, dz);
					if (d <= 0.78) set(level, pos, dy == -4 ? floor : air);
					else if (d <= 1.0 && !level.getBlockState(pos).isAir()) set(level, pos, random.nextInt(14) == 0 ? glow : flesh);
				}
		box(level, centre.offset(-7, -5, -7), centre.offset(7, -5, 7), flesh); // under the floor
		BlockPos floorCentre = centre.offset(0, -3, 0);
		// a tunnel up: a spiral in a shaft of flesh at the cave's side, a mound of flesh round its mouth
		BlockPos shaft = centre.offset(11, -3, 0);
		box(level, centre.offset(7, -3, -1), centre.offset(9, 0, 1), air);
		spiral(level, shaft, surface, flesh, flesh);
		for (int dx = -3; dx <= 3; dx++)
			for (int dz = -3; dz <= 3; dz++)
				if (Math.max(Math.abs(dx), Math.abs(dz)) == 3) set(level, new BlockPos(shaft.getX() + dx, surface, shaft.getZ() + dz), flesh);
		chest(level, random, centre.offset(-6, -3, 3), BuiltInLootTables.ABANDONED_MINESHAFT);
		DungeonHeart.Heart heart = heart(level, centre.offset(0, -4, 0), Blocks.CRIMSON_NYLIUM);
		if (heart == null) return null;
		int eggs = 4 + random.nextInt(3);
		for (int i = 0; i < eggs; i++) {
			double angle = i * Math.PI * 2 / eggs;
			UnitKind brood = random.nextInt(3) == 0 ? UnitKind.ROACH : UnitKind.ZERGLING;
			heart.add(UnitKind.EGG, floorCentre.offset((int) Math.round(Math.cos(angle) * 5), 0, (int) Math.round(Math.sin(angle) * 5)), brood,
				brood == UnitKind.ZERGLING ? 2 : 1);
		}
		heart.add(UnitKind.ZERGLING, floorCentre.offset(2, 0, -2), null, 0);
		heart.add(UnitKind.ZERGLING, floorCentre.offset(-2, 0, 2), null, 0);
		heart.add(UnitKind.ROACH, floorCentre.offset(-3, 0, -3), null, 0);
		return heart.getBlockPos();
	}

	// ---- the Xel'Naga temple ----

	private static BlockPos temple(WorldGenLevel level, RandomSource random, BlockPos floorCentre, int surface) {
		BlockState wall = Blocks.DEEPSLATE_TILES.defaultBlockState(), trim = Blocks.CHISELED_DEEPSLATE.defaultBlockState(),
			pillar = Blocks.PURPUR_PILLAR.defaultBlockState(), crystal = Blocks.AMETHYST_BLOCK.defaultBlockState(),
			light = Blocks.SEA_LANTERN.defaultBlockState(), air = Blocks.AIR.defaultBlockState(), floor = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
		int r = 8, h = 10;
		for (int dx = -r - 1; dx <= r + 1; dx++)
			for (int dz = -r - 1; dz <= r + 1; dz++)
				for (int dy = -1; dy <= h + 1; dy++) {
					BlockPos pos = floorCentre.offset(dx, dy, dz);
					boolean shell = Math.abs(dx) == r + 1 || Math.abs(dz) == r + 1 || dy == -1 || dy == h + 1;
					if (shell) set(level, pos, dy == -1 ? floor : dy == h + 1 ? wall : dy == 5 ? trim : wall);
					else set(level, pos, air);
				}
		for (int[] corner : new int[][] {{-6, -6}, {6, -6}, {-6, 6}, {6, 6}}) {
			box(level, floorCentre.offset(corner[0], 0, corner[1]), floorCentre.offset(corner[0], h, corner[1]), pillar);
			set(level, floorCentre.offset(corner[0], h - 1, corner[1]), light);
		}
		// the altar: a crystal on a dais with lights round it, a chest
		box(level, floorCentre.offset(-2, 0, -2), floorCentre.offset(2, 0, 2), trim);
		set(level, floorCentre.above(1), crystal);
		set(level, floorCentre.above(2), Blocks.END_ROD.defaultBlockState());
		for (int[] side : new int[][] {{-3, 0}, {3, 0}, {0, -3}, {0, 3}}) set(level, floorCentre.offset(side[0], 0, side[1]), light);
		chest(level, random, floorCentre.offset(0, 1, 2), BuiltInLootTables.DESERT_PYRAMID);
		// a spiral stair up to a little shrine of two pillars
		BlockPos shaft = floorCentre.offset(-12, 0, 0);
		box(level, floorCentre.offset(-r - 1, 0, -1), floorCentre.offset(-10, 4, 1), air);
		spiral(level, shaft, surface, wall, Blocks.DEEPSLATE_TILES.defaultBlockState());
		for (int side = -1; side <= 1; side += 2) {
			box(level, new BlockPos(shaft.getX() + side * 3, surface, shaft.getZ() + 3), new BlockPos(shaft.getX() + side * 3, surface + 4, shaft.getZ() + 3), pillar);
			set(level, new BlockPos(shaft.getX() + side * 3, surface + 5, shaft.getZ() + 3), crystal);
		}
		DungeonHeart.Heart heart = heart(level, floorCentre.below(), Blocks.CHISELED_DEEPSLATE);
		if (heart == null) return null;
		if (random.nextBoolean()) {
			int zealots = 2 + random.nextInt(2);
			for (int i = 0; i < zealots; i++) heart.add(UnitKind.ZEALOT, floorCentre.offset(-5 + i * 5, 0, -5), null, 0);
		} else {
			int templars = 1 + random.nextInt(2);
			for (int i = 0; i < templars; i++) heart.add(UnitKind.DARK_TEMPLAR, floorCentre.offset(-4 + i * 8, 0, 5), null, 0);
		}
		return heart.getBlockPos();
	}
}
