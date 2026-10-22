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

/** Native map templates retain their saved terrain and absolute gas channel coordinates. */
public final class SkirmishMap {
	public static final String CONFIG = "starmine-skirmish.json";
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
				if (!"amphion".equals(config.get("mapId").getAsString())) throw new IllegalArgumentException("Unknown native skirmish map");
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
		try (var input = SkirmishMap.class.getResourceAsStream("/assets/starmine/skirmish/amphion.json")) {
			if (input == null) throw new IllegalStateException("Missing Amphion manifest");
			var manifest = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
			var level = server.overworld();
			List<BlockPos> sites = new ArrayList<>();
			for (var entry : manifest.getAsJsonArray("starts")) {
				var start = entry.getAsJsonObject(); int x = start.get("x").getAsInt(), z = start.get("z").getAsInt();
				level.getChunk(x >> 4, z >> 4);
				sites.add(new BlockPos(x, dev.starmine.hive.HiveStructure.ground(level, x, z), z));
			}
			starts = List.copyOf(sites);
			var data = HiveSavedData.get(server);
			if (!data.structuresDone.contains("SKIRMISH:AMPHION:RESOURCES")) {
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
				data.structuresDone.add("SKIRMISH:AMPHION:RESOURCES"); data.structuresDone.add("DEPOSIT:SPAWN"); data.setDirty();
			}
		} catch (Exception error) { throw new IllegalStateException("Cannot prepare native Amphion map", error); }
	}
}
