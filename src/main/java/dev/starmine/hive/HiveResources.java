package dev.starmine.hive;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.starmine.StarMine;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What drones get from blocks: `data/starmine/hive/resources.json` (datapacks can override it).
 *
 * Entries match a block id or a block tag (`#minecraft:logs`) and give minerals, vespene or hoarded
 * diamonds; `deepslateBonus` multiplies blocks whose id contains "deepslate". Trees are chopped for
 * minerals; coal, redstone and lapis give vespene; diamonds are only hoarded at the hatchery.
 * `precious` ores (gold, emerald, redstone, lapis, diamond) send a drone home as soon as it has one.
 * `label` names the resource on the player's resource panel (wood, iron, ...), `icon` is the item shown
 * for it; `items` are what a player may hand in at a command center for one block of it (raw ore,
 * ingots, gems). Block items of a matching block count too.
 */
public final class HiveResources implements SimpleSynchronousResourceReloadListener {
	private static final Logger LOG = LoggerFactory.getLogger("StarMine");
	private static final ResourceLocation FILE = StarMine.id("hive/resources.json");

	/** One block's yield. */
	public record Yield(int minerals, int vespene, int diamonds, boolean precious, String label) {
		public static final Yield NONE = new Yield(0, 0, 0, false, null);

		public boolean any() {
			return minerals > 0 || vespene > 0 || diamonds > 0;
		}

		public int value() {
			return minerals + vespene * 2 + diamonds * 50;
		}
	}

	private record Entry(ResourceLocation block, TagKey<Block> tag, Yield yield, List<String> items) {
	}

	/** A panel line: a resource label and the item drawn for it. */
	public record Label(String label, String icon) {
	}

	private static List<Entry> entries = List.of();
	private static List<Label> labels = List.of();
	private static double deepslateBonus = 1.5;

	public static Yield yieldOf(BlockState state) {
		for (Entry entry : entries) {
			boolean match = entry.tag != null ? state.is(entry.tag) : BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(entry.block);
			if (!match) continue;
			if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().contains("deepslate")) return entry.yield;
			Yield y = entry.yield;
			return new Yield((int) Math.round(y.minerals * deepslateBonus), (int) Math.round(y.vespene * deepslateBonus), y.diamonds, y.precious, y.label);
		}
		return Yield.NONE;
	}

	/** What one item handed in by a player is worth: a listed item, or the block of a block item. */
	public static Yield yieldOfItem(net.minecraft.world.item.ItemStack stack) {
		if (stack.isEmpty()) return Yield.NONE;
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		for (Entry entry : entries) if (entry.items.contains(id)) return entry.yield;
		if (stack.getItem() instanceof net.minecraft.world.item.BlockItem block) return yieldOf(block.getBlock().defaultBlockState());
		return Yield.NONE;
	}

	/** Panel lines in file order (each label once). */
	public static List<Label> labels() {
		return labels;
	}

	/** Ores and other underground yields (not logs): what drones dig tunnels toward. */
	public static boolean isOre(BlockState state) {
		return yieldOf(state).any() && !state.is(net.minecraft.tags.BlockTags.LOGS);
	}

	@Override
	public ResourceLocation getFabricId() {
		return StarMine.id("hive_resources");
	}

	@Override
	public void onResourceManagerReload(ResourceManager manager) {
		List<Entry> loaded = new ArrayList<>();
		List<Label> newLabels = new ArrayList<>();
		java.util.Set<String> labelsSeen = new java.util.HashSet<>();
		var resource = manager.getResource(FILE);
		if (resource.isEmpty()) {
			LOG.warn("StarMine: {} is missing; drones collect nothing", FILE);
			entries = List.of();
			return;
		}
		try (Reader reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
			if (root.has("deepslateBonus")) deepslateBonus = root.get("deepslateBonus").getAsDouble();
			for (JsonElement element : root.getAsJsonArray("entries")) {
				JsonObject object = element.getAsJsonObject();
				String id = object.get("block").getAsString();
				String label = object.has("label") ? object.get("label").getAsString() : null;
				Yield yield = new Yield(get(object, "minerals"), get(object, "vespene"), get(object, "diamonds"),
					object.has("precious") && object.get("precious").getAsBoolean(), label);
				List<String> items = new ArrayList<>();
				if (object.has("items")) for (JsonElement item : object.getAsJsonArray("items")) items.add(item.getAsString());
				loaded.add(id.startsWith("#")
					? new Entry(null, TagKey.create(Registries.BLOCK, ResourceLocation.parse(id.substring(1))), yield, List.copyOf(items))
					: new Entry(ResourceLocation.parse(id), null, yield, List.copyOf(items)));
				if (label != null && object.has("icon") && labelsSeen.add(label)) newLabels.add(new Label(label, object.get("icon").getAsString()));
			}
			entries = List.copyOf(loaded);
			labels = List.copyOf(newLabels);
		} catch (Exception error) {
			LOG.error("StarMine: {} could not be read", FILE, error);
		}
	}

	private static int get(JsonObject object, String key) {
		return object.has(key) ? object.get(key).getAsInt() : 0;
	}
}
