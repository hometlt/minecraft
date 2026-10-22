package dev.starmine.mineral;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * A mineral hall (owner 17.10): minerals underground at any depth, in a big cave of their own. Under a chunk, between
 * the bedrock and well below the surface, a hall (about 20 blocks across, 7 to 9 high, a flat floor of the rock
 * there) is carved; a vespene vein comes up from the bedrock into its middle and ends in a geyser on its floor
 * ({@link dev.starmine.vespene.VespeneVeinFeature#build}); 4 to 7 crystals stand round the geyser. Not where the
 * hall would cut into water or lava. Rare (`worldgen/placed_feature/mineral_hall.json`).
 */
public final class MineralHallFeature extends Feature<NoneFeatureConfiguration> {
	/** Half the hall's width (it must stay within 15 blocks of the chunk centre: a feature writes only into the neighbours). */
	static final int RADIUS_MIN = 15, RADIUS_MAX = 15;
	static final int HEIGHT = dev.starmine.world.BattleSpaces.HEADROOM + 4;

	public MineralHallFeature(Codec<NoneFeatureConfiguration> codec) {
		super(codec);
	}

	@Override
	public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
		WorldGenLevel level = context.level();
		RandomSource random = context.random();
		int cx = (context.origin().getX() & ~15) + 8, cz = (context.origin().getZ() & ~15) + 8;
		int bottom = level.getMinBuildHeight();
		int ground = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, cx, cz);
		int low = bottom + 8, high = ground - HEIGHT - 4;
		if (high <= low) return false;
		int floor = low + random.nextInt(high - low + 1);
		int rx = RADIUS_MIN + random.nextInt(RADIUS_MAX - RADIUS_MIN + 1), rz = RADIUS_MIN + random.nextInt(RADIUS_MAX - RADIUS_MIN + 1);
		int height = HEIGHT;
		// owner 22.10: a great cave winds up from the hall to the surface (none: the hall is not made, it would flood)
		double a = random.nextDouble() * Math.PI * 2;
		return carve(level, cx, floor, cz, rx, height, rz)
			&& dev.starmine.vespene.VespeneVeinFeature.build(level, random, cx, cz, floor)
			&& crystals(level, random, cx, floor, cz, rx, rz) > 0
			&& dev.starmine.vespene.VespeneVeinFeature.caveToSurface(level, random, cx, cz, cx + Math.cos(a) * 9, floor, cz + Math.sin(a) * 9);
	}

	/**
	 * `count` crystals (3 x 3, 1300 then 800 minerals) on a floor round (x, floorY, z), `radius` blocks off at most (owner
	 * 22.10: a deep geyser's cave has a few now and then); returns how many stand.
	 */
	public static int crystalsAround(WorldGenLevel level, RandomSource random, int x, int floorY, int z, int radius, int count, Minerals.Setter set) {
		int made = 0;
		double rotation = random.nextDouble() * Math.PI * 2;
		for (int i = 0; i < 60 && made < count; i++) {
			double angle = rotation + i * 2.399963229728653, distance = Math.max(4, radius - 1) + random.nextDouble() * 2;
			BlockPos core = new BlockPos(x - 1 + (int) Math.round(Math.cos(angle) * distance), floorY, z - 1 + (int) Math.round(Math.sin(angle) * distance));
			if (Minerals.crystalWithin(level, core, 2)) continue;
			if (Minerals.place(level, core, 3, set) && level.getBlockEntity(core) instanceof Minerals.Core crystal) {
				crystal.setSize(3);
				crystal.setAmount(made < 3 ? 1300 : 800);
				made++;
			}
		}
		return made;
	}

	/** Whether (dx, dy, dz) from the hall's floor centre is inside it: a half-ellipsoid on a flat floor. */
	static boolean inside(int dx, int dy, int dz, int rx, int height, int rz) {
		if (dy < 0 || dy >= height) return false;
		double x = dx / (rx + 0.5), z = dz / (rz + 0.5), y = dy / (height - 0.5);
		return x * x + z * z + y * y * 0.8 <= 1;
	}

	/** The hall's air and its solid floor; nothing when it would open into water or lava. */
	static boolean carve(WorldGenLevel level, int cx, int floor, int cz, int rx, int height, int rz) {
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (int dx = -rx - 1; dx <= rx + 1; dx++)
			for (int dz = -rz - 1; dz <= rz + 1; dz++)
				for (int dy = -1; dy <= height; dy++) {
					if (!inside(dx, Math.max(0, Math.min(height - 1, dy)), dz, rx + 1, height + 1, rz + 1)) continue;
					if (!level.getFluidState(at.set(cx + dx, floor + dy, cz + dz)).isEmpty()) return false; // it would flood
				}
		BlockState rock = floor < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
		for (int dx = -rx; dx <= rx; dx++)
			for (int dz = -rz; dz <= rz; dz++) {
				if (!inside(dx, 0, dz, rx, height, rz)) continue;
				at.set(cx + dx, floor - 1, cz + dz);
				BlockState under = level.getBlockState(at);
				if (!under.isFaceSturdy(level, at, net.minecraft.core.Direction.UP) && !under.is(Blocks.BEDROCK)) level.setBlock(at, rock, Block.UPDATE_CLIENTS);
				for (int dy = 0; dy < height; dy++) {
					if (!inside(dx, dy, dz, rx, height, rz)) continue;
					at.set(cx + dx, floor + dy, cz + dz);
					BlockState there = level.getBlockState(at);
					if (there.is(Blocks.BEDROCK) || there.is(BlockTags.FEATURES_CANNOT_REPLACE)) continue;
					level.setBlock(at, Blocks.CAVE_AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
				}
				// a few lights in the ceiling, as glow lichen in caves
				if ((dx * 7 + dz * 13) % 23 == 0) {
					int top = floor;
					while (top + 1 < floor + height && inside(dx, top + 1 - floor, dz, rx, height, rz)) top++;
					level.setBlock(at.set(cx + dx, top, cz + dz), Blocks.GLOW_LICHEN.defaultBlockState()
						.setValue(net.minecraft.world.level.block.GlowLichenBlock.getFaceProperty(net.minecraft.core.Direction.UP), true), Block.UPDATE_CLIENTS);
				}
			}
		return true;
	}

	/** Five crystals, retrying sites around the vein's additional outlets. */
	static int crystals(WorldGenLevel level, RandomSource random, int cx, int floor, int cz, int rx, int rz) {
		int count = 5, made = 0;
		double rotation = random.nextDouble() * Math.PI * 2;
		for (int i = 0; i < 160 && made < count; i++) {
			double angle = rotation + i * 2.399963229728653, distance = 7.5 + random.nextDouble() * 5;
			int x = cx - 1 + (int) Math.round(Math.cos(angle) * distance), z = cz - 1 + (int) Math.round(Math.sin(angle) * distance);
			BlockPos core = new BlockPos(x, floor, z);
			if (Minerals.crystalWithin(level, core, 2)) continue;
			if (Minerals.place(level, core, 3, (pos, state) -> level.setBlock(pos, state, Block.UPDATE_CLIENTS))) {
				if (level.getBlockEntity(core) instanceof Minerals.Core crystal) {
					crystal.setSize(3);
					crystal.setAmount(made < 3 ? 1300 : 800);
				}
				made++;
			}
		}
		return made;
	}

	/** Tests: a hall of the largest size at `floor` under (cx, cz). */
	public static boolean carveForTest(WorldGenLevel level, int cx, int floor, int cz) {
		return carve(level, cx, floor, cz, RADIUS_MAX, HEIGHT, RADIUS_MAX);
	}

	/** Tests: the crystals of such a hall. */
	public static int crystalsForTest(WorldGenLevel level, int cx, int floor, int cz) {
		return crystals(level, net.minecraft.util.RandomSource.create(7), cx, floor, cz, RADIUS_MAX, RADIUS_MAX);
	}
}
