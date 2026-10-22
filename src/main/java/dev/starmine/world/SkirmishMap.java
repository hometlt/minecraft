package dev.starmine.world;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.starmine.StarMine;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.HiveSavedData;
import dev.starmine.mineral.Minerals;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Native map templates retain their saved terrain and absolute gas channel coordinates. The templates the build put
 * in the mod (tools/sc2-maps/build-maps.mjs: from the Battle.net depot through Modkit) are listed in
 * assets/starmine/skirmish/maps.json; each has its world (&lt;id&gt;.zip) and manifest (&lt;id&gt;.json: starts, fields, gas).
 */
public final class SkirmishMap {
	public static final String CONFIG = "starmine-skirmish.json";
	private static final String ROOT = "/assets/starmine/skirmish/";

	/** A map template in the mod: its id and display name. */
	public record Template(String id, String name) {
	}

	/** The templates whose world is in this build (none in a build without the converted maps). */
	public static List<Template> templates() {
		List<Template> list = new ArrayList<>();
		try (var input = SkirmishMap.class.getResourceAsStream(ROOT + "maps.json")) {
			if (input == null) return list;
			for (var entry : JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("maps")) {
				String id = entry.getAsJsonObject().get("id").getAsString();
				if (id.matches("[a-z0-9_-]+") && SkirmishMap.class.getResource(ROOT + id + ".zip") != null)
					list.add(new Template(id, entry.getAsJsonObject().get("name").getAsString()));
			}
		} catch (Exception error) {
			org.slf4j.LoggerFactory.getLogger("StarMine/skirmish").warn("Unreadable skirmish map list", error);
		}
		return list;
	}

	/** A template's manifest. */
	public static JsonObject manifest(String id) {
		if (!id.matches("[a-z0-9_-]+")) throw new IllegalArgumentException("Bad map id " + id);
		try (var input = SkirmishMap.class.getResourceAsStream(ROOT + id + ".json")) {
			if (input == null) throw new IllegalStateException("Missing map manifest " + id);
			return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (java.io.IOException error) {
			throw new java.io.UncheckedIOException(error);
		}
	}

	/** A template's world archive (verify it against {@link #manifest}'s zipSha256), or null. */
	public static @org.jetbrains.annotations.Nullable java.io.InputStream world(String id) {
		return id.matches("[a-z0-9_-]+") ? SkirmishMap.class.getResourceAsStream(ROOT + id + ".zip") : null;
	}

	private static JsonObject config;
	private static List<BlockPos> starts = List.of();
	private SkirmishMap() {}
	public static boolean nativeWorld() { return config != null; }
	public static List<BlockPos> starts() { return starts; }
	public static UnitKind.Race playerRace() { return UnitKind.Race.valueOf(config.get("playerRace").getAsString()); }
	public static UnitKind.Race aiRace() { return UnitKind.Race.valueOf(config.get("aiRace").getAsString()); }

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			config = null; starts = List.of();
			var path = server.getWorldPath(LevelResource.ROOT).resolve(CONFIG);
			if (!Files.exists(path)) return;
			try (var reader = Files.newBufferedReader(path)) {
				config = JsonParser.parseReader(reader).getAsJsonObject();
				String id = config.get("mapId").getAsString();
				if (templates().stream().noneMatch(t -> t.id().equals(id))) throw new IllegalArgumentException("Unknown native skirmish map " + id);
				playerRace(); aiRace();
			} catch (Exception error) { throw new IllegalStateException("Invalid native map configuration: " + path, error); }
		});
		ServerLifecycleEvents.SERVER_STARTED.register(SkirmishMap::prepare);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> { config = null; starts = List.of(); });
	}

	private static void prepare(MinecraftServer server) {
		if (!nativeWorld()) return;
		server.getGameRules().getRule(StarMine.SPAWN_BASES).set(false, server);
		server.getGameRules().getRule(StarMine.SPAWN_HIVE).set(false, server);
		server.getGameRules().getRule(StarMine.AI_PRESSURE).set(0, server);
		String id = config.get("mapId").getAsString();
		try {
			var manifest = manifest(id);
			var level = server.overworld();
			List<BlockPos> sites = new ArrayList<>();
			for (var entry : manifest.getAsJsonArray("starts")) {
				var start = entry.getAsJsonObject(); int x = start.get("x").getAsInt(), z = start.get("z").getAsInt();
				level.getChunk(x >> 4, z >> 4);
				sites.add(new BlockPos(x, dev.starmine.hive.HiveStructure.ground(level, x, z), z));
			}
			starts = List.copyOf(sites);
			var data = HiveSavedData.get(server);
			String done = "SKIRMISH:" + id.toUpperCase(java.util.Locale.ROOT) + ":RESOURCES";
			if (!data.structuresDone.contains(done)) {
				for (var entry : manifest.getAsJsonArray("mineralGroups")) {
					int index = 0;
					for (var point : entry.getAsJsonArray()) {
						var pos = point.getAsJsonObject();
						BlockPos corePos = new BlockPos(pos.get("x").getAsInt(), pos.get("y").getAsInt(), pos.get("z").getAsInt());
						level.getChunkAt(corePos);
						BlockPos actual = Minerals.core(level, corePos);
						if (actual != null && level.getBlockEntity(actual) instanceof Minerals.Core core) {
							if (index >= 5) Minerals.harvest(level, actual, Integer.MAX_VALUE);
							else core.setAmount(index < 3 ? 1300 : 800);
							index++;
							}
					}
					if (index < 5) throw new IllegalStateException("Native mineral field incomplete: " + index);
				}
				for (var entry : manifest.getAsJsonArray("gasRoots")) {
					var pos = entry.getAsJsonObject();
					BlockPos at = new BlockPos(pos.get("x").getAsInt(), pos.get("y").getAsInt(), pos.get("z").getAsInt());
					level.getChunkAt(at);
					if (!(level.getBlockEntity(at) instanceof dev.starmine.vespene.VespeneRootBlockEntity root))
						throw new IllegalStateException("Missing native vespene root: " + at);
					for (int i = 0; i < root.outletCount(); i++) root.adoptNativeVolcano(level, i);
				}
				data.structuresDone.add(done); data.structuresDone.add("DEPOSIT:SPAWN"); data.setDirty();
			}
		} catch (Exception error) { throw new IllegalStateException("Cannot prepare native map " + id, error); }
	}
}
