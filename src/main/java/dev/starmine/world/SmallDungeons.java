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
 * - a terran outpost on the surface (rare, one in seven): an abandoned, broken hangar, factory or barracks with
 *   sheds and pipes between them; a few marines, sometimes a marauder;
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
		if (MapOverlay.covers(cx, cz)) return false; // a laid-over map has its own land
		// owner 16.10: terran outposts are rare (one dungeon in seven), the others share the rest
		int roll = random.nextInt(7);
		Kind kind = roll == 0 ? Kind.TERRAN_OUTPOST : roll <= 3 ? Kind.ZERG_NEST : Kind.XELNAGA_TEMPLE;
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

	/**
	 * An abandoned terran outpost (owner 16.10: rare, broken technical buildings): a cracked pad with one
	 * of a hangar (a half-vault of plating), a factory (a hall with a fallen chimney) or barracks (a long low
	 * block of rooms), two or three small sheds, and pipes on posts between them. Walls and roofs have holes,
	 * windows are broken, rubble, cobwebs and moss lie about; nothing in it is a resource block. A few
	 * marines still keep it.
	 */
	private static BlockPos outpost(WorldGenLevel level, RandomSource random, BlockPos ground) {
		BlockState air = Blocks.AIR.defaultBlockState();
		int r = 15;
		for (int dx = -r; dx <= r; dx++)
			for (int dz = -r; dz <= r; dz++) {
				if (dx * dx + dz * dz > r * r + random.nextInt(30)) continue; // a ragged edge
				BlockPos column = ground.offset(dx, 0, dz);
				for (int y = 0; y <= 14; y++) set(level, column.above(y), air);
				for (int y = 2; y <= 5; y++) if (level.getBlockState(column.below(y)).isAir()) set(level, column.below(y), Blocks.STONE.defaultBlockState());
				set(level, column.below(), crackedFloor(random, dx, dz));
			}
		int type = random.nextInt(3);
		switch (type) {
			case 0 -> hangar(level, random, ground);
			case 1 -> factory(level, random, ground);
			default -> barracks(level, random, ground);
		}
		// sheds round it, joined by pipes
		BlockPos main = ground.offset(0, 0, 0);
		int sheds = 2 + random.nextInt(2);
		for (int i = 0; i < sheds; i++) {
			double angle = Math.PI * 2 * i / sheds + random.nextDouble() * 0.6;
			BlockPos at = ground.offset((int) Math.round(Math.cos(angle) * 12), 0, (int) Math.round(Math.sin(angle) * 12));
			shed(level, random, at);
			pipe(level, random, main.above(4), at.above(3));
		}
		// rubble, cobwebs, moss
		for (int i = 0; i < 40; i++) {
			BlockPos at = ground.offset(random.nextInt(2 * r + 1) - r, 0, random.nextInt(2 * r + 1) - r);
			if (!level.getBlockState(at).isAir() || level.getBlockState(at.below()).isAir()) continue;
			int pick = random.nextInt(6);
			set(level, at, pick == 0 ? Blocks.COBWEB.defaultBlockState() : pick == 1 ? Blocks.MOSS_CARPET.defaultBlockState()
				: pick == 2 ? Blocks.GRAVEL.defaultBlockState() : pick == 3 ? Blocks.COBBLESTONE_SLAB.defaultBlockState()
				: pick == 4 ? Blocks.ANDESITE_SLAB.defaultBlockState() : Blocks.BARREL.defaultBlockState());
		}
		chest(level, random, ground.offset(-3, 0, 2), FactionLoot.TERRAN);
		if (random.nextBoolean()) chest(level, random, ground.offset(2, 0, -3), BuiltInLootTables.ABANDONED_MINESHAFT);
		DungeonHeart.Heart heart = heart(level, ground.offset(0, -1, 0), Blocks.GRAY_CONCRETE);
		if (heart == null) return null;
		int marines = 1 + random.nextInt(3);
		for (int i = 0; i < marines; i++) heart.add(UnitKind.MARINE, ground.offset(-4 + i * 4, 0, 4 - (i % 2) * 8), null, 0);
		if (random.nextInt(3) == 0) heart.add(UnitKind.MARAUDER, ground.offset(4, 0, 0), null, 0);
		return heart.getBlockPos();
	}

	private static BlockState crackedFloor(RandomSource random, int dx, int dz) {
		int pick = random.nextInt(10);
		if (pick == 0) return Blocks.GRAVEL.defaultBlockState();
		if (pick == 1) return Blocks.COARSE_DIRT.defaultBlockState();
		if (pick == 2) return Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
		if (pick == 3) return Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
		if (Math.floorMod(dx, 9) == 0 || Math.floorMod(dz, 9) == 0) return random.nextInt(3) == 0 ? Blocks.GRAY_CONCRETE.defaultBlockState()
			: Blocks.YELLOW_TERRACOTTA.defaultBlockState(); // worn marking lines
		return (Math.floorMod(dx, 6) < 3) == (Math.floorMod(dz, 6) < 3) ? Blocks.GRAY_CONCRETE.defaultBlockState() : Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
	}

	/** A wall or roof cell of a ruin: mostly `state`, sometimes cracked, sometimes a hole. */
	private static void ruin(WorldGenLevel level, RandomSource random, BlockPos pos, BlockState state, float holes) {
		float roll = random.nextFloat();
		if (roll < holes) return;
		set(level, pos, roll < holes + 0.12f ? Blocks.CRACKED_DEEPSLATE_TILES.defaultBlockState() : state);
	}

	/** A hangar: a half-vault of plating 9 high over 17 x 23, open at both ends, its roof half fallen in. */
	private static void hangar(WorldGenLevel level, RandomSource random, BlockPos ground) {
		BlockState plate = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), rib = Blocks.POLISHED_DEEPSLATE.defaultBlockState(),
			stripe = Blocks.YELLOW_CONCRETE.defaultBlockState();
		double radius = 8.5;
		for (int dz = -11; dz <= 11; dz++)
			for (int dx = -9; dx <= 9; dx++)
				for (int dy = 0; dy <= 9; dy++) {
					double d = Math.sqrt(dx * dx + dy * dy);
					if (d < radius - 0.5 || d > radius + 0.5) continue;
					BlockPos pos = ground.offset(dx, dy, dz);
					boolean ribCell = Math.floorMod(dz, 5) == 0;
					float holes = dy > 5 ? 0.35f : 0.12f;
					ruin(level, random, pos, ribCell ? rib : dy == 2 ? stripe : plate, ribCell ? 0.05f : holes);
				}
		// a wrecked dropship frame inside, crates
		for (int dz = -4; dz <= 4; dz++) {
			set(level, ground.offset(0, 0, dz), Blocks.POLISHED_DEEPSLATE_SLAB.defaultBlockState());
			if (Math.abs(dz) == 4) for (int dx = -3; dx <= 3; dx++) set(level, ground.offset(dx, 1, dz), Blocks.IRON_BARS.defaultBlockState());
		}
		for (int i = 0; i < 4; i++) set(level, ground.offset(-6 + random.nextInt(3), 0, -9 + i * 5), Blocks.BARREL.defaultBlockState());
		for (int y = 3; y <= 8; y++) set(level, ground.offset(4, y, 6), Blocks.CHAIN.defaultBlockState());
	}

	/** A factory: a hall of tiles 15 x 11, 8 high, its roof half gone, a chimney broken off and fallen across the pad. */
	private static void factory(WorldGenLevel level, RandomSource random, BlockPos ground) {
		BlockState wall = Blocks.DEEPSLATE_TILES.defaultBlockState(), frame = Blocks.POLISHED_BLACKSTONE.defaultBlockState(),
			glass = Blocks.GRAY_STAINED_GLASS.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
		BlockPos a = ground.offset(-7, 0, -5), b = ground.offset(7, 8, 5);
		for (BlockPos pos : BlockPos.betweenClosed(a, b)) {
			boolean x = pos.getX() == a.getX() || pos.getX() == b.getX(), z = pos.getZ() == a.getZ() || pos.getZ() == b.getZ();
			boolean roof = pos.getY() == b.getY();
			int up = pos.getY() - ground.getY();
			if (!x && !z && !roof) {
				set(level, pos.immutable(), air);
				continue;
			}
			if (x && z) set(level, pos.immutable(), frame);
			else if (roof) ruin(level, random, pos.immutable(), Blocks.SMOOTH_STONE_SLAB.defaultBlockState(), 0.45f);
			else if (up >= 3 && up <= 5 && Math.floorMod(pos.getX() + pos.getZ(), 4) != 0) {
				if (random.nextInt(3) != 0) set(level, pos.immutable(), glass); // the rest broken
				else set(level, pos.immutable(), air);
			} else ruin(level, random, pos.immutable(), wall, up > 5 ? 0.25f : 0.06f);
		}
		box(level, ground.offset(7, 0, -1), ground.offset(7, 3, 1), air); // the gate
		// machines inside
		for (int dx = -5; dx <= 3; dx += 4) {
			set(level, ground.offset(dx, 0, -3), Blocks.BLAST_FURNACE.defaultBlockState());
			set(level, ground.offset(dx, 1, -3), Blocks.POLISHED_BASALT.defaultBlockState());
			set(level, ground.offset(dx, 0, 3), Blocks.SMOKER.defaultBlockState());
		}
		// the chimney: standing a while, then fallen along +z
		BlockState brick = Blocks.BRICKS.defaultBlockState();
		int standing = 6 + random.nextInt(5);
		for (int y = 0; y < standing; y++)
			for (int dx = -1; dx <= 1; dx++)
				for (int dz = -1; dz <= 1; dz++)
					if (dx != 0 || dz != 0) ruin(level, random, ground.offset(-5 + dx, y, dz), brick, y > standing - 3 ? 0.4f : 0.03f);
		for (int k = 0; k < 9; k++) ruin(level, random, ground.offset(-5, 0, 7 + k), brick, 0.2f);
	}

	/** Barracks: a long low block of rooms 19 x 7, 5 high, with bunks, broken windows, a door at each end. */
	private static void barracks(WorldGenLevel level, RandomSource random, BlockPos ground) {
		BlockState wall = Blocks.POLISHED_ANDESITE.defaultBlockState(), roof = Blocks.GRAY_CONCRETE.defaultBlockState(),
			glass = Blocks.LIGHT_BLUE_STAINED_GLASS_PANE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
		BlockPos a = ground.offset(-9, 0, -3), b = ground.offset(9, 5, 3);
		for (BlockPos pos : BlockPos.betweenClosed(a, b)) {
			boolean x = pos.getX() == a.getX() || pos.getX() == b.getX(), z = pos.getZ() == a.getZ() || pos.getZ() == b.getZ();
			boolean top = pos.getY() == b.getY();
			int up = pos.getY() - ground.getY();
			if (top) ruin(level, random, pos.immutable(), roof, 0.2f);
			else if (x || z) {
				boolean window = z && up == 2 && Math.floorMod(pos.getX(), 3) == 1;
				if (window) set(level, pos.immutable(), random.nextBoolean() ? glass : air);
				else ruin(level, random, pos.immutable(), wall, 0.05f);
			} else set(level, pos.immutable(), Math.floorMod(pos.getX(), 4) == 0 ? wall : air); // partitions between rooms
		}
		for (int side = -1; side <= 1; side += 2) box(level, ground.offset(side * 9, 0, -1), ground.offset(side * 9, 2, 1), air);
		for (int dx = -8; dx <= 8; dx++) set(level, ground.offset(dx, 0, 0), air); // the corridor through the partitions
		for (int dx = -8; dx <= 8; dx++) set(level, ground.offset(dx, 1, 0), air);
		for (int dx = -7; dx <= 7; dx += 4) {
			set(level, ground.offset(dx, 0, -2), Blocks.WHITE_WOOL.defaultBlockState());
			set(level, ground.offset(dx, 0, 2), Blocks.LIGHT_GRAY_WOOL.defaultBlockState());
		}
		// antennae on the roof, bent
		for (int up = 6; up <= 8; up++) set(level, ground.offset(-6, up, 0), Blocks.END_ROD.defaultBlockState());
	}

	/** A small shed: a box 5 x 5 of plating with a door and a relay box on its roof. */
	private static void shed(WorldGenLevel level, RandomSource random, BlockPos ground) {
		BlockState wall = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
		for (int dx = -2; dx <= 2; dx++)
			for (int dz = -2; dz <= 2; dz++) {
				set(level, ground.offset(dx, -1, dz), Blocks.GRAY_CONCRETE.defaultBlockState());
				for (int y = 0; y <= 3; y++) {
					boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
					BlockPos pos = ground.offset(dx, y, dz);
					if (y == 3 || edge) ruin(level, random, pos, wall, y == 3 ? 0.25f : 0.08f);
					else set(level, pos, air);
				}
			}
		box(level, ground.offset(-2, 0, 0), ground.offset(-2, 1, 0), air);
		set(level, ground.above(4), Blocks.DEEPSLATE_TILES.defaultBlockState());
	}

	/** A pipe on posts from `from` to `to`: logs of basalt along x, then along z, a post every 4 blocks; a few links fallen. */
	private static void pipe(WorldGenLevel level, RandomSource random, BlockPos from, BlockPos to) {
		BlockState alongX = Blocks.POLISHED_BASALT.defaultBlockState().setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, Direction.Axis.X),
			alongZ = Blocks.POLISHED_BASALT.defaultBlockState().setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, Direction.Axis.Z),
			post = Blocks.POLISHED_DEEPSLATE_WALL.defaultBlockState();
		int y = from.getY(), x = from.getX(), z = from.getZ();
		int k = 0;
		while (x != to.getX()) {
			x += Integer.signum(to.getX() - x);
			if (random.nextInt(9) != 0) set(level, new BlockPos(x, y, z), alongX);
			if (++k % 4 == 0) postDown(level, new BlockPos(x, y - 1, z), post);
		}
		while (z != to.getZ()) {
			z += Integer.signum(to.getZ() - z);
			if (random.nextInt(9) != 0) set(level, new BlockPos(x, y, z), alongZ);
			if (++k % 4 == 0) postDown(level, new BlockPos(x, y - 1, z), post);
		}
	}

	private static void postDown(WorldGenLevel level, BlockPos top, BlockState post) {
		for (int i = 0; i < 8 && level.getBlockState(top.below(i)).isAir(); i++) set(level, top.below(i), post);
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
		chest(level, random, centre.offset(-6, -3, 3), FactionLoot.ZERG);
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
		chest(level, random, floorCentre.offset(0, 1, 2), FactionLoot.PROTOSS);
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
