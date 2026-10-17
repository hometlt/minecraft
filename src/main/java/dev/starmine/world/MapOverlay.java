package dev.starmine.world;

import com.mojang.serialization.Codec;
import dev.starmine.StarMine;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.HiveSavedData;
import dev.starmine.mineral.Minerals;
import dev.starmine.vespene.VespeneVeinFeature;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A map laid over the world (owner 17.10): a StarCraft II map (or any world Modkit exports) as a
 * Modkit world overlay — a data pack with `data/<namespace>/world_overlay/<name>.nbt`, format
 * "modkit-world-overlay" 1, written by Modkit's Minecraft extension (`minecraft-world-overlay`).
 * The world generates as always; in the chunks the map covers this feature (last decoration step)
 * builds the map's land over it: each column's ground top block and three blocks of its under
 * block at the map's height, rock filling caves and gaps for 20 blocks under that, nothing of the
 * world above it (StarMine blocks are kept), the map's water or lava, and the voxels of its objects
 * (rocks, trees, cliffs). Everything deeper than that is the world's own: caves, ores, bedrock.
 *
 * The map's markers become StarMine's: once their chunk and its neighbours are loaded, a mineral
 * field becomes crystals ({@link Minerals#place}) along its outline, a geyser a vespene vein from the
 * bedrock up to a geyser ({@link VespeneVeinFeature#build}). The natural mineral fields and veins
 * keep out of the map. The start locations are the bases' sites: the first the terran base, the
 * second the protoss base, the third the zerg lair ({@link RaceBases}, {@link dev.starmine.hive.HiveStructure}),
 * built as the player nears; the world spawn is a fourth start, else the map's centre.
 */
public final class MapOverlay {
	private static final Logger LOG = LoggerFactory.getLogger("StarMine");
	public static final String FORMAT = "modkit-world-overlay";
	public static final int NONE = Integer.MIN_VALUE;
	/** How deep under a column's ground caves and gaps are filled with rock. */
	static final int FILL = 20;

	public record Marker(int index, String role, @Nullable String kind, @Nullable String variant, @Nullable String state,
		BlockPos pos, int minX, int minZ, int maxX, int maxZ) {
	}

	final String name;
	final int minX, minZ, sizeX, sizeZ, baseY;
	final BlockState[] palette;
	final int[] top, topBlock, under, water, fluid;
	/** Chunk (ChunkPos long) → positions `(y + 4096) << 8 | z << 4 | x` and palette indices. */
	final Map<Long, int[][]> chunks = new HashMap<>();
	final List<Marker> markers = new ArrayList<>();

	private static volatile List<MapOverlay> loaded = List.of();

	private MapOverlay(CompoundTag tag) {
		name = tag.getString("Name");
		minX = tag.getInt("MinX");
		minZ = tag.getInt("MinZ");
		sizeX = tag.getInt("SizeX");
		sizeZ = tag.getInt("SizeZ");
		baseY = tag.getInt("BaseY");
		ListTag states = tag.getList("Palette", Tag.TAG_STRING);
		palette = new BlockState[states.size()];
		HolderLookup<Block> blocks = BuiltInRegistries.BLOCK.asLookup();
		for (int i = 0; i < palette.length; i++) {
			try {
				palette[i] = BlockStateParser.parseForBlock(blocks, states.getString(i), false).blockState();
			} catch (Exception error) {
				LOG.warn("map overlay {}: unknown block {}, using stone", name, states.getString(i));
				palette[i] = Blocks.STONE.defaultBlockState();
			}
		}
		top = tag.getIntArray("Top");
		topBlock = tag.getIntArray("TopBlock");
		under = tag.getIntArray("Under");
		water = tag.getIntArray("Water");
		fluid = tag.getIntArray("Fluid");
		int columns = sizeX * sizeZ;
		if (sizeX <= 0 || sizeZ <= 0 || top.length != columns || topBlock.length != columns || under.length != columns
			|| water.length != columns || fluid.length != columns)
			throw new IllegalArgumentException("columns do not match SizeX × SizeZ");
		for (Tag item : tag.getList("Chunks", Tag.TAG_COMPOUND)) {
			CompoundTag chunk = (CompoundTag) item;
			int[] pos = chunk.getIntArray("Pos"), state = chunk.getIntArray("State");
			if (pos.length == state.length) chunks.put(ChunkPos.asLong(chunk.getInt("X"), chunk.getInt("Z")), new int[][] {pos, state});
		}
		ListTag list = tag.getList("Markers", Tag.TAG_COMPOUND);
		for (int i = 0; i < list.size(); i++) {
			CompoundTag m = list.getCompound(i);
			markers.add(new Marker(i, m.getString("Role"), text(m, "Kind"), text(m, "Variant"), text(m, "State"),
				new BlockPos(m.getInt("X"), m.getInt("Y"), m.getInt("Z")), m.getInt("MinX"), m.getInt("MinZ"), m.getInt("MaxX"), m.getInt("MaxZ")));
		}
	}

	/** An overlay from its NBT root (for the game tests; data packs are read by {@link Loader}). */
	static MapOverlay of(CompoundTag tag) {
		return new MapOverlay(tag);
	}

	private static @Nullable String text(CompoundTag tag, String key) {
		return tag.contains(key, Tag.TAG_STRING) ? tag.getString(key) : null;
	}

	public static List<MapOverlay> loaded() {
		return loaded;
	}

	/** Whether a laid-over map covers the column. */
	public static boolean covers(int x, int z) {
		for (MapOverlay overlay : loaded) if (overlay.column(x, z) >= 0) return true;
		return false;
	}

	/** The column's index, -1 outside the map or where it has no ground. */
	int column(int x, int z) {
		int dx = x - minX, dz = z - minZ;
		if (dx < 0 || dz < 0 || dx >= sizeX || dz >= sizeZ) return -1;
		int i = dz * sizeX + dx;
		return top[i] == NONE ? -1 : i;
	}

	/** The free cell over the column's land (its water's top when it is under water); NONE outside. */
	public int ground(int x, int z) {
		int i = column(x, z);
		return i < 0 ? NONE : Math.max(top[i], water[i]) + 1;
	}

	private static boolean ours(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals(StarMine.MOD_ID);
	}

	/** Builds the map's land in one chunk being generated. */
	void paint(WorldGenLevel level, ChunkPos chunk) {
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		BlockState air = Blocks.AIR.defaultBlockState(), stone = Blocks.STONE.defaultBlockState(), deepslate = Blocks.DEEPSLATE.defaultBlockState();
		int bottom = level.getMinBuildHeight() + 1;
		for (int lz = 0; lz < 16; lz++)
			for (int lx = 0; lx < 16; lx++) {
				int x = chunk.getMinBlockX() + lx, z = chunk.getMinBlockZ() + lz;
				int i = column(x, z);
				if (i < 0) continue;
				int t = top[i];
				int surface = Math.max(level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z), t + 1);
				for (int y = t + 1; y <= surface + 1; y++) {
					BlockState there = level.getBlockState(at.set(x, y, z));
					if (!there.isAir() && !ours(there)) level.setBlock(at, air, Block.UPDATE_CLIENTS);
				}
				for (int y = t; y >= Math.max(bottom, t - 3 - FILL); y--) {
					BlockState there = level.getBlockState(at.set(x, y, z));
					if (ours(there)) continue;
					BlockState want = y == t ? palette[topBlock[i]] : y >= t - 3 ? palette[under[i]]
						: there.isAir() || !there.getFluidState().isEmpty() || there.canBeReplaced() ? (y < 0 ? deepslate : stone) : null;
					if (want != null && there != want) level.setBlock(at, want, Block.UPDATE_CLIENTS);
				}
				if (water[i] != NONE)
					for (int y = t + 1; y <= water[i]; y++) level.setBlock(at.set(x, y, z), palette[fluid[i]], Block.UPDATE_CLIENTS);
			}
		int[][] voxels = chunks.get(chunk.toLong());
		if (voxels != null)
			for (int k = 0; k < voxels[0].length; k++) {
				int p = voxels[0][k];
				at.set(chunk.getMinBlockX() + (p & 15), (p >>> 8) - 4096, chunk.getMinBlockZ() + ((p >> 4) & 15));
				if (at.getY() < bottom || at.getY() >= level.getMaxBuildHeight() || ours(level.getBlockState(at))) continue;
				level.setBlock(at, palette[voxels[1][k]], Block.UPDATE_CLIENTS);
			}
	}

	// ---- loading ----

	/** Reads `data/<namespace>/world_overlay/*.nbt` of the data packs. */
	static final class Loader implements SimpleSynchronousResourceReloadListener {
		@Override
		public ResourceLocation getFabricId() {
			return StarMine.id("map_overlay");
		}

		@Override
		public void onResourceManagerReload(ResourceManager manager) {
			List<MapOverlay> found = new ArrayList<>();
			for (Map.Entry<ResourceLocation, Resource> entry : manager.listResources("world_overlay", path -> path.getPath().endsWith(".nbt")).entrySet()) {
				try (InputStream stream = entry.getValue().open()) {
					CompoundTag tag = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
					if (!FORMAT.equals(tag.getString("Format")) || tag.getInt("Version") != 1) {
						LOG.warn("{} is not a {} 1 file", entry.getKey(), FORMAT);
						continue;
					}
					MapOverlay overlay = new MapOverlay(tag);
					found.add(overlay);
					LOG.info("map overlay {}: {} x {} columns at {}, {}, {} chunks of objects, {} markers", overlay.name,
						overlay.sizeX, overlay.sizeZ, overlay.minX, overlay.minZ, overlay.chunks.size(), overlay.markers.size());
				} catch (Exception error) {
					LOG.error("cannot read map overlay {}", entry.getKey(), error);
				}
			}
			loaded = List.copyOf(found);
		}
	}

	/** The generation step that builds the land (`worldgen/placed_feature/map_overlay.json`, every overworld chunk). */
	static final class OverlayFeature extends Feature<NoneFeatureConfiguration> {
		OverlayFeature(Codec<NoneFeatureConfiguration> codec) {
			super(codec);
		}

		@Override
		public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
			ChunkPos chunk = new ChunkPos(context.origin());
			boolean any = false;
			for (MapOverlay overlay : loaded) {
				if (chunk.getMaxBlockX() < overlay.minX || chunk.getMinBlockX() >= overlay.minX + overlay.sizeX
					|| chunk.getMaxBlockZ() < overlay.minZ || chunk.getMinBlockZ() >= overlay.minZ + overlay.sizeZ) continue;
				overlay.paint(context.level(), chunk);
				any = true;
			}
			return any;
		}
	}

	public static final Feature<NoneFeatureConfiguration> FEATURE = Registry.register(BuiltInRegistries.FEATURE, StarMine.id("map_overlay"),
		new OverlayFeature(NoneFeatureConfiguration.CODEC));

	// ---- markers and sites ----

	/** Which markers a world has made, and whether the sites are set (`data/starmine_map_overlay.dat`). */
	static final class Progress extends SavedData {
		final Map<String, Set<Integer>> placed = new HashMap<>();
		boolean sites;

		static final SavedData.Factory<Progress> FACTORY = new SavedData.Factory<>(Progress::new, Progress::load, null);

		static Progress get(MinecraftServer server) {
			return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "starmine_map_overlay");
		}

		@Override
		public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
			CompoundTag maps = new CompoundTag();
			placed.forEach((name, indices) -> maps.putIntArray(name, indices.stream().mapToInt(Integer::intValue).toArray()));
			tag.put("placed", maps);
			tag.putBoolean("sites", sites);
			return tag;
		}

		static Progress load(CompoundTag tag, HolderLookup.Provider registries) {
			Progress progress = new Progress();
			CompoundTag maps = tag.getCompound("placed");
			for (String name : maps.getAllKeys()) {
				Set<Integer> set = new HashSet<>();
				for (int index : maps.getIntArray(name)) set.add(index);
				progress.placed.put(name, set);
			}
			progress.sites = tag.getBoolean("sites");
			return progress;
		}
	}

	private record Pending(MapOverlay overlay, Marker marker) {
	}

	private static final Deque<Pending> PENDING = new ArrayDeque<>();
	private static final Set<String> QUEUED = new HashSet<>();

	/** A loaded overworld chunk's markers wait until its neighbours are loaded too. */
	private static void chunkLoaded(ServerLevel level, net.minecraft.world.level.chunk.LevelChunk chunk) {
		if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD || loaded.isEmpty()) return;
		ChunkPos pos = chunk.getPos();
		for (MapOverlay overlay : loaded)
			for (Marker marker : overlay.markers)
				if (marker.role().equals("resource") && marker.pos().getX() >> 4 == pos.x && marker.pos().getZ() >> 4 == pos.z
					&& QUEUED.add(overlay.name + "#" + marker.index())) PENDING.add(new Pending(overlay, marker));
	}

	private static void tick(MinecraftServer server) {
		if (PENDING.isEmpty()) return;
		ServerLevel level = server.overworld();
		Progress progress = Progress.get(server);
		for (int n = PENDING.size(); n > 0; n--) {
			Pending pending = PENDING.poll();
			Set<Integer> done = progress.placed.computeIfAbsent(pending.overlay().name, name -> new HashSet<>());
			Marker marker = pending.marker();
			if (done.contains(marker.index())) continue;
			int cx = marker.pos().getX() >> 4, cz = marker.pos().getZ() >> 4;
			boolean ready = true;
			for (int dx = -1; dx <= 1 && ready; dx++)
				for (int dz = -1; dz <= 1 && ready; dz++) ready = level.hasChunk(cx + dx, cz + dz);
			if (!ready) {
				PENDING.add(pending);
				continue;
			}
			place(level, pending.overlay(), marker);
			done.add(marker.index());
			progress.setDirty();
		}
	}

	/** What a resource marker becomes: a geyser's vein, or crystals along a mineral field. */
	static void place(ServerLevel level, MapOverlay overlay, Marker marker) {
		String kind = marker.kind() == null ? "" : marker.kind().toLowerCase(java.util.Locale.ROOT);
		RandomSource random = RandomSource.create(level.getSeed() ^ marker.pos().asLong());
		if (kind.contains("geyser") || kind.contains("gas") || kind.contains("vespene")) {
			VespeneVeinFeature.build(level, random, marker.pos().getX(), marker.pos().getZ(), true,
				Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE);
			return;
		}
		if (!kind.contains("mineral") && !kind.contains("crystal")) return;
		// crystals (2 x 2) along the field's longer side, two blocks apart
		int width = marker.maxX() - marker.minX() + 1, depth = marker.maxZ() - marker.minZ() + 1;
		boolean alongX = width >= depth;
		int length = Math.max(width, depth), across = Math.min(width, depth);
		int count = Math.max(1, (length + 1) / 3);
		int amount = switch (marker.state() == null ? "full" : marker.state()) {
			case "reduced" -> Minerals.AMOUNT * 3 / 5;
			case "depleted" -> Minerals.AMOUNT * 3 / 10;
			default -> Minerals.AMOUNT;
		};
		if ("rich".equals(marker.variant())) amount = amount * 3 / 2;
		for (int k = 0; k < count; k++) {
			int along = (int) Math.round((k + 0.5) * length / count) - 1, side = Math.max(0, (across - 2) / 2);
			int x = marker.minX() + (alongX ? along : side), z = marker.minZ() + (alongX ? side : along);
			int y = overlay.ground(x, z);
			if (y == NONE) y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			BlockPos core = new BlockPos(x, y, z);
			if (Minerals.place(level, core, (pos, state) -> level.setBlock(pos, state, Block.UPDATE_CLIENTS))
				&& level.getBlockEntity(core) instanceof Minerals.Core entity) entity.setAmount(amount);
		}
	}

	/** The starts become the bases' sites, once per world; the world spawn goes to a free start or the map's centre. */
	private static void serverStarted(MinecraftServer server) {
		if (loaded.isEmpty()) return;
		Progress progress = Progress.get(server);
		if (progress.sites) return;
		MapOverlay overlay = loaded.getFirst();
		List<Marker> starts = overlay.markers.stream().filter(marker -> marker.role().equals("start"))
			.sorted(Comparator.comparingInt(Marker::index)).toList();
		HiveSavedData data = HiveSavedData.get(server);
		UnitKind.Race[] races = {UnitKind.Race.TERRAN, UnitKind.Race.PROTOSS, UnitKind.Race.ZERG};
		for (int k = 0; k < Math.min(races.length, starts.size()); k++) {
			BlockPos site = siteOf(overlay, starts.get(k));
			if (races[k] == UnitKind.Race.ZERG) data.spawnHiveSite = site;
			else data.structureSites.put(races[k].name(), site);
			LOG.info("map overlay {}: the {} base at start {} ({})", overlay.name, races[k], starts.get(k).index(), site.toShortString());
		}
		BlockPos spawn = starts.size() > races.length ? siteOf(overlay, starts.get(races.length)) : centreOf(overlay);
		data.baseOrigin = spawn;
		data.setDirty();
		server.overworld().setDefaultSpawnPos(spawn, 0);
		progress.sites = true;
		progress.setDirty();
	}

	private static BlockPos siteOf(MapOverlay overlay, Marker start) {
		int y = overlay.ground(start.pos().getX(), start.pos().getZ());
		return new BlockPos(start.pos().getX(), y == NONE ? start.pos().getY() : y, start.pos().getZ());
	}

	private static BlockPos centreOf(MapOverlay overlay) {
		int x = overlay.minX + overlay.sizeX / 2, z = overlay.minZ + overlay.sizeZ / 2;
		int y = overlay.ground(x, z);
		return new BlockPos(x, y == NONE ? overlay.baseY : y, z);
	}

	public static void load() {
		ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new Loader());
		BiomeModifications.addFeature(BiomeSelectors.foundInOverworld(), GenerationStep.Decoration.TOP_LAYER_MODIFICATION,
			ResourceKey.create(Registries.PLACED_FEATURE, StarMine.id("map_overlay")));
		ServerChunkEvents.CHUNK_LOAD.register(MapOverlay::chunkLoaded);
		ServerTickEvents.END_SERVER_TICK.register(MapOverlay::tick);
		ServerLifecycleEvents.SERVER_STARTED.register(MapOverlay::serverStarted);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			PENDING.clear();
			QUEUED.clear();
		});
	}
}
