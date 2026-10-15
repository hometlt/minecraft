package dev.starmine.world;

import com.mojang.serialization.Codec;
import dev.starmine.StarMine;
import dev.starmine.block.LiquidCreepBlock;
import dev.starmine.block.StarMineBlocks;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

/**
 * Nydus worms (owner 16.10), here and there in the world (`worldgen/placed_feature/nydus_worm.json`): two
 * worms of zerg flesh rising from the ground some 30 blocks apart, joined underground by a tunnel 2 x 2
 * lined with flesh and full of liquid creep ({@link LiquidCreepBlock}: zerg rush through it). Inside each
 * worm's head, round the mouth of its shaft, lies a floor of orifices ({@link Orifice}): a zerg that steps
 * on one comes out by the other worm.
 */
public final class Nydus extends Feature<NoneFeatureConfiguration> {
	public static final Feature<NoneFeatureConfiguration> FEATURE = Registry.register(BuiltInRegistries.FEATURE, StarMine.id("nydus_worm"),
		new Nydus(NoneFeatureConfiguration.CODEC));
	public static final Orifice ORIFICE = Registry.register(BuiltInRegistries.BLOCK, StarMine.id("nydus_orifice"),
		new Orifice(BlockBehaviour.Properties.of().mapColor(MapColor.CRIMSON_NYLIUM).strength(1.5f, 6f).sound(SoundType.WART_BLOCK)
			.lightLevel(state -> 6).isValidSpawn((state, level, pos, type) -> false)));
	public static final BlockEntityType<Exit> EXIT = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, StarMine.id("nydus_orifice"),
		BlockEntityType.Builder.of(Exit::new, ORIFICE).build(null));
	/** Half the distance between the two worms, how deep the tunnel runs under the lower one. */
	static final int REACH = 15, DEPTH = 10;
	/** Ticks a zerg that came out of a worm is not taken back in. */
	static final int REST = 100;
	private static final Map<UUID, Long> RESTING = new HashMap<>();

	private Nydus(Codec<NoneFeatureConfiguration> codec) {
		super(codec);
	}

	public static void load() {
		Registry.register(BuiltInRegistries.ITEM, StarMine.id("nydus_orifice"), new BlockItem(ORIFICE, new Item.Properties()));
		BiomeModifications.addFeature(BiomeSelectors.foundInOverworld(), GenerationStep.Decoration.SURFACE_STRUCTURES,
			ResourceKey.create(Registries.PLACED_FEATURE, StarMine.id("nydus_worm")));
	}

	@Override
	public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
		WorldGenLevel level = context.level();
		RandomSource random = context.random();
		int cx = (context.origin().getX() & ~15) + 8, cz = (context.origin().getZ() & ~15) + 8;
		boolean alongX = random.nextBoolean();
		int shift = random.nextInt(9) - 4;
		BlockPos a = column(level, alongX ? cx - REACH : cx + shift, alongX ? cz + shift : cz - REACH);
		BlockPos b = column(level, alongX ? cx + REACH : cx - shift, alongX ? cz - shift : cz + REACH);
		if (a == null || b == null || Math.abs(a.getY() - b.getY()) > 12) return false;
		return build(level, random, a, b, cx, cz);
	}

	/** A worm's place on the surface (the first free cell), or null on water or too low. */
	private static @Nullable BlockPos column(WorldGenLevel level, int x, int z) {
		int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
		if (surface > level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) || surface < level.getMinBuildHeight() + DEPTH + 12) return null;
		return new BlockPos(x, surface, z);
	}

	/** Builds the two worms at `a` and `b` (surface cells) and the tunnel between them; (cx, cz) the chunk's middle. */
	static boolean build(WorldGenLevel level, RandomSource random, BlockPos a, BlockPos b, int cx, int cz) {
		int floor = Math.min(a.getY(), b.getY()) - DEPTH;
		Set<BlockPos> inside = new HashSet<>();
		// the shafts down from each worm, the tunnel between them (a little winding)
		for (BlockPos top : new BlockPos[] {a, b})
			for (int y = floor; y < top.getY(); y++) square(inside, top.getX(), y, top.getZ());
		int x = a.getX(), z = a.getZ();
		while (x != b.getX() || z != b.getZ()) {
			int dx = b.getX() - x, dz = b.getZ() - z;
			if (Math.abs(dx) >= Math.abs(dz) ? dx != 0 : dz == 0) x += Integer.signum(dx);
			else z += Integer.signum(dz);
			square(inside, x, floor, z);
			square(inside, x, floor + 1, z);
		}
		// flesh round all of it, some of it glowing; then the liquid creep inside
		BlockState flesh = StarMineBlocks.ZERG_FLESH.defaultBlockState(), glow = StarMineBlocks.GLOWING_ZERG_FLESH.defaultBlockState();
		for (BlockPos cell : inside)
			for (int dx = -1; dx <= 1; dx++)
				for (int dy = -1; dy <= 1; dy++)
					for (int dz = -1; dz <= 1; dz++) {
						BlockPos at = cell.offset(dx, dy, dz);
						if (inside.contains(at) || at.getY() >= surfaceAt(a, b, at)) continue;
						set(level, cx, cz, at, random.nextInt(12) == 0 ? glow : flesh);
					}
		BlockState creep = StarMineBlocks.LIQUID_CREEP.defaultBlockState();
		for (BlockPos cell : inside) set(level, cx, cz, cell, creep);
		// the heads, their orifices leading to each other
		BlockPos exitA = head(level, random, a, b, cx, cz), exitB = head(level, random, b, a, cx, cz);
		pair(level, a, exitB);
		pair(level, b, exitA);
		return true;
	}

	/** The surface height (a worm's) nearest the cell: the shafts' lining stops there. */
	private static int surfaceAt(BlockPos a, BlockPos b, BlockPos at) {
		return at.distSqr(a) < at.distSqr(b) ? a.getY() : b.getY();
	}

	private static void square(Set<BlockPos> cells, int x, int y, int z) {
		for (int dx = 0; dx <= 1; dx++)
			for (int dz = 0; dz <= 1; dz++) cells.add(new BlockPos(x + dx, y, z + dz));
	}

	/**
	 * A worm's head over its shaft at `top`: a ring of flesh 5 high with a mouth toward the other worm,
	 * bone teeth round its rim, a floor of orifices round the shaft. Returns where a zerg comes out by it.
	 */
	private static BlockPos head(WorldGenLevel level, RandomSource random, BlockPos top, BlockPos other, int cx, int cz) {
		double mx = top.getX() + 1, mz = top.getZ() + 1; // the 2 x 2 shaft's middle (between its cells)
		double toward = Math.atan2(other.getZ() - top.getZ(), other.getX() - top.getX());
		BlockState flesh = StarMineBlocks.ZERG_FLESH.defaultBlockState(), glow = StarMineBlocks.GLOWING_ZERG_FLESH.defaultBlockState(),
			bone = Blocks.BONE_BLOCK.defaultBlockState(), air = Blocks.AIR.defaultBlockState(), orifice = ORIFICE.defaultBlockState();
		for (int dx = -4; dx <= 5; dx++)
			for (int dz = -4; dz <= 5; dz++) {
				int x = top.getX() + dx, z = top.getZ() + dz;
				double r = Math.hypot(x + 0.5 - mx, z + 0.5 - mz);
				if (r > 4.6) continue;
				boolean shaft = dx >= 0 && dx <= 1 && dz >= 0 && dz <= 1;
				double angle = Math.atan2(z + 0.5 - mz, x + 0.5 - mx);
				boolean mouth = Math.abs(Math.atan2(Math.sin(angle - toward), Math.cos(angle - toward))) < 0.5;
				for (int y = -1; y <= 5; y++) {
					BlockPos at = new BlockPos(x, top.getY() + y, z);
					if (r > 3.4) { // the wall
						if (y == -1) set(level, cx, cz, at, flesh);
						else if (mouth && y <= 2) set(level, cx, cz, at, air);
						else if (y == 5) set(level, cx, cz, at, bone);
						else set(level, cx, cz, at, random.nextInt(9) == 0 ? glow : flesh);
					} else if (y == -1) {
						if (!shaft) set(level, cx, cz, at, orifice);
					} else if (y == 5 && r > 2.6) set(level, cx, cz, at, bone); // teeth over the rim
					else if (!shaft || y >= 0) set(level, cx, cz, at, air);
				}
			}
		return new BlockPos((int) Math.floor(mx + Math.cos(toward) * 7), top.getY(), (int) Math.floor(mz + Math.sin(toward) * 7));
	}

	/** The orifices round the shaft at `top` lead to `exit`. */
	private static void pair(WorldGenLevel level, BlockPos top, BlockPos exit) {
		for (BlockPos pos : BlockPos.betweenClosed(top.offset(-4, -1, -4), top.offset(5, -1, 5)))
			if (level.getBlockEntity(pos) instanceof Exit entity) entity.to = exit.immutable();
	}

	private static void set(WorldGenLevel level, int cx, int cz, BlockPos pos, BlockState state) {
		if (Math.abs(pos.getX() - cx) > 22 || Math.abs(pos.getZ() - cz) > 22) return; // outside the writable area
		if (pos.getY() <= level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) return;
		BlockState old = level.getBlockState(pos);
		if (old.is(Blocks.BEDROCK) || level.getBlockEntity(pos) != null && !old.is(ORIFICE)) return;
		level.setBlock(pos, state, Block.UPDATE_CLIENTS);
	}

	/** Where an orifice leads. */
	public static class Exit extends BlockEntity {
		@Nullable BlockPos to;

		public Exit(BlockPos pos, BlockState state) {
			super(EXIT, pos, state);
		}

		@Override
		protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			super.saveAdditional(tag, registries);
			if (to != null) tag.put("to", NbtUtils.writeBlockPos(to));
		}

		@Override
		protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			super.loadAdditional(tag, registries);
			to = NbtUtils.readBlockPos(tag, "to").orElse(null);
		}
	}

	/** A nydus orifice: a zerg stepping on it comes out by the other worm. */
	public static final class Orifice extends Block implements EntityBlock {
		Orifice(Properties properties) {
			super(properties);
		}

		@Override
		public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
			return new Exit(pos, state);
		}

		@Override
		public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
			if (level instanceof ServerLevel server && LiquidCreepBlock.zerg(entity)) travel(server, pos, entity);
			super.stepOn(level, pos, state, entity);
		}
	}

	/** A zerg through the worm: out by the other one (its chunk loaded), not taken back in for a while. */
	static boolean travel(ServerLevel level, BlockPos pos, Entity entity) {
		long now = level.getGameTime();
		Long resting = RESTING.get(entity.getUUID());
		if (resting != null && now < resting) return false;
		if (!(level.getBlockEntity(pos) instanceof Exit exit) || exit.to == null) return false;
		BlockPos to = exit.to;
		level.getChunk(to.getX() >> 4, to.getZ() >> 4);
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, to.getX(), to.getZ());
		RESTING.put(entity.getUUID(), now + REST);
		level.playSound(null, pos, SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 1.0f, 0.6f);
		entity.teleportTo(to.getX() + 0.5, y, to.getZ() + 0.5);
		level.playSound(null, to, SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 1.0f, 0.6f);
		if (RESTING.size() > 512) RESTING.values().removeIf(until -> until < now);
		return true;
	}
}
