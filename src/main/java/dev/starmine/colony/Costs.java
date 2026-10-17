package dev.starmine.colony;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.starmine.StarMine;
import dev.starmine.ability.Ability;
import dev.starmine.entity.UnitKind;
import dev.starmine.race.Research;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What units, buildings, research and abilities cost (CLOUD_TASK_2 stage 2): `data/starmine/costs/*.json`,
 * read when a world starts and on `/reload`; datapacks override a file by its name.
 *
 * - `units.json`: by kind id (`marine`, `siegetank`); `research.json`: by research id, weapons and armor
 *   by level (`weapons_1`), lair and hive by name; `abilities.json`: by ability id (`nuke`).
 * - A cost: `minerals` and `vespene` (the SC2 currency: what ores and vespene give by
 *   `hive/resources.json`) and any resource of the panel by its label (`iron`, `diamond`, `netherite`,
 *   ...), taken from the base's stock of that resource ({@link dev.starmine.hive.Hive#collected}).
 *   «Тор — 10 алмазов + 100 железа»: `"thor": {"diamond": 10, "iron": 100}`.
 * - `platform.json`: what calling a main building from a supply platform takes ({@link PlatformBlock}): any one
 *   of its prices (500 minerals, or 500 iron, or 100 diamonds).
 * - What a file does not name costs what SC2 asks (the race tables, {@link dev.starmine.race.RaceRules#economy});
 *   the shipped files hold exactly those numbers.
 */
public final class Costs implements SimpleSynchronousResourceReloadListener {
	private static final Logger LOG = LoggerFactory.getLogger("StarMine");
	public static final String DIRECTORY = "costs";

	/** A price: minerals, vespene and resources of the stock by label. */
	public record Cost(int minerals, int vespene, Map<String, Integer> materials) {
		public static final Cost FREE = new Cost(0, 0, Map.of());

		public boolean any() {
			return minerals > 0 || vespene > 0 || !materials.isEmpty();
		}
	}

	private static Map<String, Cost> units = Map.of(), research = Map.of(), abilities = Map.of();
	/** Every file by its name (units, research, abilities, platform, ...). */
	private static Map<String, Map<String, Cost>> categories = Map.of();

	/** A kind's economy with the configured minerals and vespene (the rest — supply, time, cargo — stays SC2's). */
	public static UnitKind.Economy economy(UnitKind kind, UnitKind.Economy sc2) {
		Cost cost = units.get(kind.id);
		return cost == null ? sc2 : new UnitKind.Economy(cost.minerals, cost.vespene, sc2.food(), sc2.trainTicks(), sc2.supply(), sc2.cargo());
	}

	/** The resources of the stock a kind costs besides minerals and vespene. */
	public static Map<String, Integer> materials(UnitKind kind) {
		Cost cost = units.get(kind.id);
		return cost == null ? Map.of() : cost.materials;
	}

	/** The configured price of a research at a level, else SC2's minerals and vespene. */
	public static Cost research(Research what, int level, int[] sc2) {
		Cost cost = research.get(researchId(what, level));
		return cost != null ? cost : new Cost(sc2[0], sc2[1], Map.of());
	}

	/** `weapons_2`, `armor_1`; other research by its id. */
	public static String researchId(Research what, int level) {
		return what.leveled() ? what.id() + "_" + level : what.id();
	}

	/** The configured price of casting an ability, else SC2's. */
	public static Cost ability(Ability ability) {
		Cost cost = abilities.get(ability.id);
		return cost != null ? cost : new Cost(ability.minerals, ability.vespene, Map.of());
	}

	/** Sets a price by hand (tests); null takes it out. */
	public static void set(String category, String id, Cost cost) {
		Map<String, Cost> map = new HashMap<>(loaded(category));
		if (cost == null) map.remove(id);
		else map.put(id, cost);
		Map<String, Map<String, Cost>> all = new HashMap<>(categories);
		all.put(category, Map.copyOf(map));
		use(all);
	}

	/** The prices of a file (`units`, `research`, `abilities`, `platform`, ...), empty when there is none. */
	public static Map<String, Cost> loaded(String category) {
		return categories.getOrDefault(category, Map.of());
	}

	private static void use(Map<String, Map<String, Cost>> all) {
		categories = Map.copyOf(all);
		units = loaded("units");
		research = loaded("research");
		abilities = loaded("abilities");
	}

	@Override
	public ResourceLocation getFabricId() {
		return StarMine.id("costs");
	}

	@Override
	public void onResourceManagerReload(ResourceManager manager) {
		Map<String, Map<String, Cost>> read = new HashMap<>();
		for (var entry : manager.listResources(DIRECTORY, path -> path.getPath().endsWith(".json")).entrySet()) {
			if (!entry.getKey().getNamespace().equals(StarMine.MOD_ID)) continue;
			String path = entry.getKey().getPath();
			String category = path.substring(DIRECTORY.length() + 1, path.length() - ".json".length());
			try (Reader reader = new InputStreamReader(entry.getValue().open(), StandardCharsets.UTF_8)) {
				JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
				Map<String, Cost> costs = read.computeIfAbsent(category, k -> new HashMap<>());
				for (Map.Entry<String, JsonElement> price : root.entrySet()) costs.put(price.getKey(), parse(price.getValue().getAsJsonObject()));
			} catch (Exception error) {
				LOG.error("StarMine: {} could not be read", entry.getKey(), error);
			}
		}
		Map<String, Map<String, Cost>> all = new HashMap<>();
		read.forEach((category, costs) -> all.put(category, Map.copyOf(costs)));
		use(all);
		LOG.info("StarMine: costs of {} units, {} research, {} abilities", units.size(), research.size(), abilities.size());
	}

	static Cost parse(JsonObject object) {
		int minerals = 0, vespene = 0;
		Map<String, Integer> materials = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> part : object.entrySet()) {
			int amount = part.getValue().getAsInt();
			switch (part.getKey()) {
				case "minerals" -> minerals = amount;
				case "vespene" -> vespene = amount;
				default -> {
					if (amount > 0) materials.put(part.getKey(), amount);
				}
			}
		}
		return new Cost(minerals, vespene, Map.copyOf(materials));
	}
}
