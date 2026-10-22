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
 * A vespene vein (owner 14.10). From a root at the bedrock a trunk of vespene climbs, wandering a little, and ends in a
 * geyser: a cobblestone mound with the outlet on top. Around the vein cobblestone walls: one block in rock, up to three
 * where it passes through caves or water. Everything stays within 15 blocks of the chunk centre (a feature may write only
 * into the neighbours of its chunk). The vein starts full.
 *
 * Owner 22.10:
 * - a vein's branches no longer end in geysers: each ends underground in a small pocket where vespene is taken by bucket
 *   ({@link VespeneRootBlockEntity#geyser}: no gas building stands there), no great rooms;
 * - most trunks reach the surface; in some regions of the world ({@link #deepRegion}, never near the others' surface
 *   geysers) the trunk ends deep underground in a geyser of its own, in a cave of natural shape — now and then with
 *   mineral crystals round it — and a great cave winds up from it to the surface ({@link #caveToSurface});
 * - a surface geyser's clearing is round, not square.
 */
public final class VespeneVeinFeature extends Feature<NoneFeatureConfiguration> {
	private static final int SPAN = 2;
	/** A deep region: this many chunks a side; its veins keep to its middle (this many chunks off its edges). */
	static final int REGION_CHUNKS = 8, REGION_MARGIN = 2;
	/** A deep geyser's cave: its radius across and its height. */
	static final double CHAMBER = 7, CHAMBER_HEIGHT = 7;

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
	 * floor, and the hall's air is left open; else it ends at the surface (a geyser) or, in a deep region, underground.
	 */
	public static boolean build(WorldGenLevel level, RandomSource random, int cx, int cz, int hall) {
		return level instanceof net.minecraft.server.level.ServerLevel
			? build(level, random, cx, cz, hall, false, Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE)
			: build(level, random, cx, cz, hall, false, Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG);
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

	/**
	 * Whether the chunk of (cx, cz) lies in a deep region (about one region in three): its veins end in deep geysers.
	 * Region-wide, so a deep geyser is never near a surface one of its region; `inside` tells whether the chunk keeps
	 * to the region's middle (its veins are made only there: two chunks at least from the next region's).
	 */
	static boolean deepRegion(long seed, int cx, int cz, boolean[] inside) {
		int chunkX = cx >> 4, chunkZ = cz >> 4, rx = Math.floorDiv(chunkX, REGION_CHUNKS), rz = Math.floorDiv(chunkZ, REGION_CHUNKS);
		long h = seed ^ (rx * 341873128712L + rz * 132897987541L) ^ 0x6A5E5EL;
		h ^= h >>> 33;
		h *= 0xff51afd7ed558ccdL;
		h ^= h >>> 33;
		int lx = Math.floorMod(chunkX, REGION_CHUNKS), lz = Math.floorMod(chunkZ, REGION_CHUNKS);
		inside[0] = lx >= REGION_MARGIN && lx < REGION_CHUNKS - REGION_MARGIN && lz >= REGION_MARGIN && lz < REGION_CHUNKS - REGION_MARGIN;
		return Math.floorMod(h, 3) == 0;
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
		boolean deep = false;
		if (!inHall && !geyser && !shallow) {
			boolean[] middle = new boolean[1];
			deep = deepRegion(level.getSeed(), cx, cz, middle);
			if (deep && !middle[0]) return false; // a deep region's veins keep to its middle, off the surface geysers round it
			if (deep && ground - bottom < 50) return false; // not deep enough for a cave of its own
		}
		if (wet && !deep) return false; // owner 22.10: a surface vein comes up on dry land only (under water it was a stump)
		boolean reach = !deep;
		int top = reach ? Integer.MAX_VALUE : bottom + 16 + random.nextInt(Math.max(1, Math.min(40, ground - bottom - 44)));

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
		// a deep geyser's cave and its way up: planned before anything is built, and given up where it would flood
		List<double[]> way = null;
		if (deep) {
			way = caveWay(level, cx, cz, x + 0.5, y + 1, z + 0.5);
			if (way == null) return false;
		}
		vein.geyser(x, y + 1, z, reach && !inHall ? 12 : 0);

		// branches: up and out from the trunk, each ending underground in a bucket spot (owner 22.10: no geyser)
		List<BlockPos> trunk = new ArrayList<>(vein.channel);
		int branches = trunk.size() < 30 ? 1 : 2;
		int gap = 6;
		for (int b = 0; b < branches; b++) {
			BlockPos from = trunk.get((int) (trunk.size() * (0.15 + random.nextDouble() * 0.5)));
			double angle = random.nextDouble() * Math.PI * 2, fx = from.getX(), fz = from.getZ();
			int bx = from.getX(), by = from.getY(), bz = from.getZ();
			int length = 6 + random.nextInt(11);
			for (int step = 0; step < length; step++) {
				fx += Math.cos(angle);
				fz += Math.sin(angle);
				int nx = Math.clamp(Math.round(fx), cx - SPAN, cx + SPAN), nz = Math.clamp(Math.round(fz), cz - SPAN, cz + SPAN);
				if (by + 1 >= vein.ground(nx, nz) - gap) break;
				if (nx != bx) vein.add(bx = nx, by, bz);
				if (nz != bz) vein.add(bx, by, bz = nz);
				if (random.nextFloat() < 0.6f) vein.add(bx, ++by, bz);
			}
			BlockPos spot = new BlockPos(bx, by + 1, bz);
			if (by > from.getY() && vein.outlets.stream().noneMatch(at -> Math.abs(at.getY() - spot.getY()) < gap && at.distManhattan(spot) < 6))
				vein.outlet(spot.getX(), spot.getY(), spot.getZ());
		}

		vein.build(root);
		if (deep) { // owner 22.10: a cave of natural shape round the deep geyser, a great cave up to the surface, crystals now and then
			BlockPos outlet = vein.outlets.getFirst(), base = outlet.below(VespeneRootBlockEntity.MOUND);
			chamber(vein, random, base);
			carve(vein, way, random);
			if (random.nextInt(2) == 0) dev.starmine.mineral.MineralHallFeature.crystalsAround(level, random, base.getX(), base.getY() + 1, base.getZ(),
				(int) CHAMBER - 2, 2 + random.nextInt(3), (pos, state) -> vein.set(pos, state));
		}
		// owner 16.10 / 17.10: mineral crystals mostly lie near a geyser, a field of several across the chunk from it
		if (reach && !inHall && !geyser && random.nextInt(6) > 0) {
			int fx = cx + (x >= cx ? -7 : 7), fz = cz + (z >= cz ? -7 : 7);
			dev.starmine.mineral.Minerals.field(level, random, fx, fz, 22, (pos, state) -> level.setBlock(pos, state, Block.UPDATE_CLIENTS));
		}
		return true;
	}

	// ---- a deep geyser's cave ----

	/** A smooth bump of size about one along a way. */
	private static double wave(double s, double seed) {
		return (Math.sin(s * 0.23 + seed) + Math.sin(s * 0.51 + seed * 1.7) * 0.6) / 1.6;
	}

	/**
	 * The way of a great cave from a deep geyser at (x, y, z) up to the surface: a spiral round the chunk's middle (the
	 * feature writes within 15 blocks of it), a little under half a block up a block. Points (x, floor y, z, radius,
	 * height); null when it would meet water or lava, or cannot reach the ground.
	 */
	private static List<double[]> caveWay(WorldGenLevel level, int cx, int cz, double x, double y, double z) {
		double angle = Math.atan2(z - cz, x - cx), radius = 9, turn = 1;
		double seed = (cx * 31 + cz) * 0.013;
		List<double[]> way = new ArrayList<>();
		// from the geyser out to the spiral
		double sx = cx + Math.cos(angle) * radius, sz = cz + Math.sin(angle) * radius, d = Math.hypot(sx - x, sz - z);
		for (double k = 0; k < d; k += 1) way.add(new double[] {x + (sx - x) * k / Math.max(1, d), y, z + (sz - z) * k / Math.max(1, d), 3, 5});
		double fy = y;
		for (int step = 0; step < 900; step++) {
			angle += turn / radius;
			fy += 0.42;
			double px = cx + Math.cos(angle) * radius, pz = cz + Math.sin(angle) * radius;
			double r = 2.9 + 0.6 * wave(step, seed), h = 5 + 0.8 * wave(step, seed + 3);
			way.add(new double[] {px, fy, pz, r, h});
			int ground = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, (int) Math.floor(px), (int) Math.floor(pz));
			if (level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, (int) Math.floor(px), (int) Math.floor(pz)) > ground) return null; // it would come out under water
			if (fy >= ground + 1) break;
			if (step == 899) return null;
		}
		// no water or lava along it (it would flood the cave)
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (double[] p : way)
			for (int dx = -4; dx <= 4; dx += 2)
				for (int dz = -4; dz <= 4; dz += 2)
					for (int dy = 0; dy <= 6; dy += 2)
						if (!level.getFluidState(at.set(p[0] + dx, p[1] + dy, p[2] + dz)).isEmpty()) return null;
		return way;
	}

	/** Carves a way: domes of air along it (its floor kept, a floor laid over gaps); the vein's own blocks stay. */
	private static void carve(Vein vein, List<double[]> way, RandomSource random) {
		for (double[] p : way) dome(vein, p[0], (int) Math.floor(p[1]), p[2], p[3], p[4]);
	}

	/** A cave of natural shape round the geyser's foot: a few overlapping domes on its floor, the floor made whole. */
	private static void chamber(Vein vein, RandomSource random, BlockPos base) {
		dome(vein, base.getX() + 0.5, base.getY() + 1, base.getZ() + 0.5, CHAMBER, CHAMBER_HEIGHT);
		for (int i = 0; i < 3; i++) {
			double a = random.nextDouble() * Math.PI * 2, d = 3 + random.nextDouble() * 3;
			dome(vein, base.getX() + 0.5 + Math.cos(a) * d, base.getY() + 1, base.getZ() + 0.5 + Math.sin(a) * d, 4 + random.nextDouble() * 2, CHAMBER_HEIGHT - 2);
		}
	}

	/** Air in a dome (radius across, height up) on a floor at `floorY`; gaps under it floored with stone. */
	private static void dome(Vein vein, double x, int floorY, double z, double radius, double height) {
		int r = (int) Math.ceil(radius);
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		BlockState rock = floorY < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
		for (int bx = (int) Math.floor(x - r); bx <= (int) Math.floor(x + r); bx++)
			for (int bz = (int) Math.floor(z - r); bz <= (int) Math.floor(z + r); bz++) {
				double d = Math.hypot(bx + 0.5 - x, bz + 0.5 - z);
				if (d > radius) continue;
				for (int up = 0; up <= Math.ceil(height); up++) {
					if ((d / radius) * (d / radius) + (up / height) * (up / height) * 0.7 > 1) break;
					at.set(bx, floorY + up, bz);
					BlockState there = vein.level.getBlockState(at);
					if (vein.keeps(at, there)) continue;
					vein.set(at.immutable(), Blocks.CAVE_AIR.defaultBlockState());
				}
				at.set(bx, floorY - 1, bz);
				BlockState under = vein.level.getBlockState(at);
				if ((under.isAir() || under.canBeReplaced()) && !vein.keeps(at, under)) vein.set(at.immutable(), rock);
			}
	}

	/**
	 * A great cave up to the surface from a point underground (a mineral hall's edge, owner 22.10), within 15 blocks of
	 * the chunk's middle (cx, cz); false when it would flood or cannot reach the ground (nothing is carved then).
	 */
	public static boolean caveToSurface(WorldGenLevel level, RandomSource random, int cx, int cz, double x, int y, double z) {
		List<double[]> way = caveWay(level, cx, cz, x, y, z);
		if (way == null) return false;
		Vein none = new Vein(level, cx, cz, Heightmap.Types.OCEAN_FLOOR_WG);
		carve(none, way, random);
		return true;
	}

	/** The cells of one vein while it is laid out, then built. */
	private static final class Vein {
		final WorldGenLevel level;
		final int cx, cz;
		final Heightmap.Types floor;
		final LinkedHashSet<BlockPos> channel = new LinkedHashSet<>();
		final List<BlockPos> outlets = new ArrayList<>();
		/** Which outlets are geysers (the others: a branch's bucket spot). */
		final List<Boolean> geysers = new ArrayList<>();
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

		/** A branch's underground end: the last cell becomes a bucket spot, with a small pocket of air above it. */
		void outlet(int x, int y, int z) {
			BlockPos end = new BlockPos(x, y, z);
			channel.remove(end);
			outlets.add(end);
			geysers.add(false);
			for (int dx = -2; dx <= 2; dx++)
				for (int dz = -2; dz <= 2; dz++)
					for (int dy = 1; dy <= 3; dy++)
						if (Math.abs(dx) + Math.abs(dz) < 3) open.add(end.offset(dx, dy, dz));
		}

		/** A geyser: a cobblestone mound (radius 3 to 1, on the ground at `y`) with the outlet on top; a round clearing over the ground. */
		void geyser(int x, int y, int z, int clearing) {
			BlockPos base = new BlockPos(x, y - 1, z);
			for (int k = 1; k <= VespeneRootBlockEntity.MOUND; k++) {
				int r = VespeneRootBlockEntity.radiusAt(k);
				for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++)
					if (VespeneRootBlockEntity.inLayer(dx, k, dz)) mound.add(base.offset(dx, k, dz));
				if (k < VespeneRootBlockEntity.MOUND) add(x, base.getY() + k, z);
			}
			BlockPos end = base.above(VespeneRootBlockEntity.MOUND);
			outlets.add(end);
			geysers.add(true);
			for (int dx = -clearing; dx <= clearing; dx++) for (int dz = -clearing; dz <= clearing; dz++) {
				if (dx * dx + dz * dz > clearing * clearing) continue; // owner 22.10: round
				for (int dy = 1; dy <= dev.starmine.world.BattleSpaces.HEADROOM; dy++) open.add(base.offset(dx, dy, dz));
			}
		}

		/** A cell that a cave leaves alone: the vein's own blocks, the bedrock, what features may not replace, a block entity. */
		boolean keeps(BlockPos pos, BlockState there) {
			return there.is(Blocks.BEDROCK) || there.is(BlockTags.FEATURES_CANNOT_REPLACE) || there.is(Vespene.VESPENE) || there.is(Vespene.VENT)
				|| there.is(Vespene.ROCK) || there.is(Vespene.ROOT) || mound.contains(pos) || level.getBlockEntity(pos) != null
				|| dev.starmine.mineral.Minerals.isCrystal(there);
		}

		void build(BlockPos root) {
			BlockState cobble = Vespene.ROCK.defaultBlockState();
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
				if (cell.getY() == surface - 1) continue; // A lower chamber must not cut through a mineral hall's floor.
				BlockState there = level.getBlockState(cell);
				if (level.getBlockEntity(cell) == null && !there.is(Blocks.BEDROCK) && !there.is(BlockTags.FEATURES_CANNOT_REPLACE)) set(cell, Blocks.AIR.defaultBlockState());
			}
			BlockState full = Vespene.VESPENE.defaultBlockState();
			for (BlockPos cell : channel) set(cell, full);
			for (BlockPos cell : outlets) set(cell, full.setValue(VespeneBlock.OUTLET, true));
			set(root, Vespene.ROOT.defaultBlockState());
			if (level.getBlockEntity(root) instanceof VespeneRootBlockEntity entity) entity.setVein(new ArrayList<>(channel), outlets, geysers);
		}

		void set(BlockPos pos, BlockState state) {
			if (Math.abs(pos.getX() - cx) > 15 || Math.abs(pos.getZ() - cz) > 15) return; // outside the writable area
			level.setBlock(pos, state, Block.UPDATE_CLIENTS);
		}
	}
}
