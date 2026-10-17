package dev.starmine.vespene;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
 * A vespene vein (owner 14.10). From a root at the bedrock a trunk of vespene climbs, wandering a
 * little; branches leave it upward and end in outlets. Around the vein cobblestone walls: one block in
 * rock, up to three where it passes through caves or water; no wall above an outlet, where an air pocket
 * opens instead. Half the trunks reach the surface (not under water) and end in a cobblestone geyser
 * with the outlet on top. Everything stays within 15 blocks of the chunk centre (a feature may write
 * only into the neighbours of its chunk). The vein starts full.
 */
public final class VespeneVeinFeature extends Feature<NoneFeatureConfiguration> {
	private static final int SPAN = 9;

	public VespeneVeinFeature(Codec<NoneFeatureConfiguration> codec) {
		super(codec);
	}

	@Override
	public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
		int cx = (context.origin().getX() & ~15) + 8, cz = (context.origin().getZ() & ~15) + 8;
		if (dev.starmine.world.MapOverlay.covers(cx, cz)) return false; // a laid-over map has its own geysers
		return build(context.level(), context.random(), cx, cz, Integer.MIN_VALUE);
	}

	/**
	 * A vein under the column (cx, cz), the centre of a chunk. With a `hall` floor (owner 17.10: a mineral hall
	 * underground, {@link dev.starmine.mineral.MineralHallFeature}) its trunk ends there in a geyser on the hall's
	 * floor, and the hall's air is left open; else it ends at the surface (a geyser) or underground.
	 */
	public static boolean build(WorldGenLevel level, RandomSource random, int cx, int cz, int hall) {
		return build(level, random, cx, cz, hall, false, Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG);
	}

	/**
	 * A vein round the column (cx, cz), from the bedrock under it. `geyser`: it always comes up as a
	 * geyser where the ground is dry (a map's geyser, {@link dev.starmine.world.MapOverlay}), and no
	 * minerals are laid by it; else as world generation wants. `floor` and `surface` are the heightmaps
	 * that name the ground and its top (the generation's own while chunks generate, the final ones after).
	 */
	public static boolean build(WorldGenLevel level, RandomSource random, int cx, int cz, boolean geyser,
		Heightmap.Types floor, Heightmap.Types surface) {
		return build(level, random, cx, cz, Integer.MIN_VALUE, geyser, floor, surface);
	}

	private static boolean build(WorldGenLevel level, RandomSource random, int cx, int cz, int hall, boolean geyser,
		Heightmap.Types floor, Heightmap.Types surface) {
		int bottom = level.getMinBuildHeight();
		if (!level.getBlockState(new BlockPos(cx, bottom, cz)).is(Blocks.BEDROCK)) return false; // not a bedrock-floored world
		BlockPos root = new BlockPos(cx, bottom + 1, cz);
		Vein vein = new Vein(level, cx, cz, floor);
		boolean inHall = hall != Integer.MIN_VALUE;
		if (inHall) vein.surface = hall;
		boolean wet = !inHall && level.getHeight(surface, cx, cz) > level.getHeight(floor, cx, cz);
		int ground = vein.ground(cx, cz);
		boolean shallow = ground - bottom < 40; // a flat world: the trunk always comes up
		boolean reach = inHall || !wet && (geyser || shallow || random.nextBoolean());
		int top = reach ? Integer.MAX_VALUE : bottom + 24 + random.nextInt(Math.max(1, ground - bottom - 36));
		if (!reach) top = Math.max(bottom + 3, Math.min(top, ground - 5)); // ends underground (under water too)

		// the trunk
		int x = cx, z = cz, y = bottom + 2;
		vein.add(x, y, z);
		while (true) {
			if (reach && y >= vein.ground(x, z) - 1) break;
			if (!reach && y >= top) break;
			if (random.nextFloat() < 0.12f) {
				int nx = Math.clamp(x + random.nextInt(3) - 1, cx - SPAN, cx + SPAN), nz = Math.clamp(z + random.nextInt(3) - 1, cz - SPAN, cz + SPAN);
				if (nx != x) vein.add(x = nx, y, z);
				if (nz != z) vein.add(x, y, z = nz);
			}
			vein.add(x, ++y, z);
		}
		if (reach) vein.geyser(x, y + 1, z);
		else vein.outlet(x, y, z);

		// branches: up and out from the trunk, ending underground
		List<BlockPos> trunk = new ArrayList<>(vein.channel);
		int branches = trunk.size() < 30 ? 1 : 1 + random.nextInt(4);
		for (int b = 0; b < branches; b++) {
			BlockPos from = trunk.get((int) (trunk.size() * (0.15 + random.nextDouble() * 0.6)));
			double angle = random.nextDouble() * Math.PI * 2, fx = from.getX(), fz = from.getZ();
			int bx = from.getX(), by = from.getY(), bz = from.getZ();
			int length = 6 + random.nextInt(11);
			for (int step = 0; step < length; step++) {
				fx += Math.cos(angle);
				fz += Math.sin(angle);
				int nx = Math.clamp(Math.round(fx), cx - SPAN, cx + SPAN), nz = Math.clamp(Math.round(fz), cz - SPAN, cz + SPAN);
				if (by + 1 >= vein.ground(nx, nz) - 6) break;
				if (nx != bx) vein.add(bx = nx, by, bz);
				if (nz != bz) vein.add(bx, by, bz = nz);
				if (random.nextFloat() < 0.6f) vein.add(bx, ++by, bz);
			}
			if (by > from.getY()) vein.outlet(bx, by, bz);
		}

		vein.build(root);
		// owner 16.10 / 17.10: mineral crystals mostly lie near a geyser, a field of several across the chunk from it
		if (reach && !inHall && !geyser && random.nextInt(6) > 0) {
			int fx = cx + (x >= cx ? -7 : 7), fz = cz + (z >= cz ? -7 : 7);
			dev.starmine.mineral.Minerals.field(level, random, fx, fz, 8, (pos, state) -> level.setBlock(pos, state, Block.UPDATE_CLIENTS));
		}
		return true;
	}

	/** The cells of one vein while it is laid out, then built. */
	private static final class Vein {
		final WorldGenLevel level;
		final int cx, cz;
		final Heightmap.Types floor;
		final LinkedHashSet<BlockPos> channel = new LinkedHashSet<>();
		final List<BlockPos> outlets = new ArrayList<>();
		final Set<BlockPos> open = new HashSet<>(), mound = new HashSet<>();
		/** The floor of a hall the vein ends in: its air is not underground (nothing fills it). */
		int surface = Integer.MAX_VALUE;

		Vein(WorldGenLevel level, int cx, int cz, Heightmap.Types floor) {
			this.level = level;
			this.floor = floor;
			this.cx = cx;
			this.cz = cz;
		}

		/** The first free height of a column above its solid ground (trees and water not counted); a hall's floor. */
		int ground(int x, int z) {
			return Math.min(surface, level.getHeight(floor, x, z));
		}

		void add(int x, int y, int z) {
			channel.add(new BlockPos(x, y, z));
		}

		/** An underground end: the last cell becomes the outlet, with an air pocket above it. */
		void outlet(int x, int y, int z) {
			BlockPos end = new BlockPos(x, y, z);
			channel.remove(end);
			outlets.add(end);
			for (int dx = -2; dx <= 2; dx++)
				for (int dz = -2; dz <= 2; dz++)
					for (int dy = 1; dy <= 4; dy++)
						if (Math.abs(dx) + Math.abs(dz) < 4) open.add(end.offset(dx, dy, dz));
		}

		/** The surface end: a cobblestone mound (radius 4 to 1, on the ground at `y`) with the outlet on its top. */
		void geyser(int x, int y, int z) {
			for (int k = 0; k <= 3; k++) {
				int r = 4 - k;
				for (int dx = -r; dx <= r; dx++)
					for (int dz = -r; dz <= r; dz++)
						if (dx * dx + dz * dz <= r * r + 1) mound.add(new BlockPos(x + dx, y + k, z + dz));
				if (k < 3) add(x, y + k, z);
			}
			BlockPos end = new BlockPos(x, y + 3, z);
			outlets.add(end);
			for (int dx = -1; dx <= 1; dx++)
				for (int dz = -1; dz <= 1; dz++)
					for (int dy = 1; dy <= 3; dy++) open.add(end.offset(dx, dy, dz));
		}

		void build(BlockPos root) {
			BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
			Set<BlockPos> vespene = new HashSet<>(channel);
			vespene.addAll(outlets);
			vespene.add(root);
			for (BlockPos cell : mound) if (!vespene.contains(cell)) set(cell, cobble);
			for (BlockPos cell : vespene) {
				if (cell.equals(root)) continue;
				boolean outlet = outlets.contains(cell);
				for (int dx = -3; dx <= 3; dx++)
					for (int dy = -3; dy <= 3; dy++)
						for (int dz = -3; dz <= 3; dz++) {
							if (outlet && dy > 0) continue; // nothing over an outlet
							BlockPos at = cell.offset(dx, dy, dz);
							if (vespene.contains(at) || open.contains(at) || mound.contains(at)) continue;
							int d = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
							BlockState there = level.getBlockState(at);
							if (there.is(Blocks.BEDROCK) || there.is(Blocks.COBBLESTONE)) continue;
							boolean empty = there.isAir() || !there.getFluidState().isEmpty() || there.canBeReplaced();
							boolean underground = at.getY() < ground(at.getX(), at.getZ());
							if (d == 1 && (underground || empty) || d > 1 && empty && underground) set(at, cobble);
						}
			}
			for (BlockPos cell : open) {
				if (vespene.contains(cell) || mound.contains(cell)) continue;
				BlockState there = level.getBlockState(cell);
				if (!there.is(Blocks.BEDROCK) && !there.is(BlockTags.FEATURES_CANNOT_REPLACE)) set(cell, Blocks.AIR.defaultBlockState());
			}
			BlockState full = Vespene.VESPENE.defaultBlockState();
			for (BlockPos cell : channel) set(cell, full);
			for (BlockPos cell : outlets) set(cell, full.setValue(VespeneBlock.OUTLET, true));
			set(root, Vespene.ROOT.defaultBlockState());
			if (level.getBlockEntity(root) instanceof VespeneRootBlockEntity entity) entity.setVein(new ArrayList<>(channel), outlets);
		}

		private void set(BlockPos pos, BlockState state) {
			if (Math.abs(pos.getX() - cx) > 15 || Math.abs(pos.getZ() - cz) > 15) return; // outside the writable area
			level.setBlock(pos, state, Block.UPDATE_CLIENTS);
		}
	}
}
