package dev.starmine.guide;

import static dev.starmine.ability.TestArena.ARENA;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** The journal of goals and the field guide (owner 16.10, items 7 and 8). */
public class GuideGameTests implements FabricGameTest {
	/** Goals are granted from what the player holds; the next step is the first goal not done. */
	@GameTest(template = ARENA, batch = "guide", timeoutTicks = 40)
	public void goalsFollowThePlayer(GameTestHelper helper) {
		// every goal of the journal is loaded (an advancement with an unknown icon is dropped with its children)
		for (Goals.Goal goal : Goals.Goal.values())
			helper.assertTrue(helper.getLevel().getServer().getAdvancements().get(goal.advancement()) != null, "the goal " + goal.id + " did not load");
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayerInLevel();
		try {
			player.getInventory().clearContent();
			Goals.check(player);
			helper.assertTrue(!Goals.done(player, Goals.Goal.MINERALS), "minerals done with nothing in hand");
			helper.assertTrue(Goals.next(player) == Goals.Goal.COMMAND_PANEL, "the next step is " + Goals.next(player));
			player.getInventory().add(new ItemStack(dev.starmine.mineral.Minerals.SHARD));
			player.getInventory().add(new ItemStack(dev.starmine.colony.CommandPanel.ITEM));
			Goals.check(player);
			helper.assertTrue(Goals.done(player, Goals.Goal.MINERALS), "a mineral shard did not count");
			helper.assertTrue(Goals.done(player, Goals.Goal.COMMAND_PANEL), "the command panel did not count");
			helper.assertTrue(Goals.next(player) == Goals.Goal.OWN_BASE, "the next step is " + Goals.next(player));
			// owner 19.10: more goals — a race tool, terrazine, a void shard count from the inventory; an army does not come from nothing
			player.getInventory().add(new ItemStack(dev.starmine.gear.RaceTools.item(dev.starmine.entity.UnitKind.Race.TERRAN, "pickaxe")));
			player.getInventory().add(new ItemStack(dev.starmine.vespene.Vespene.TERRAZINE_BUCKET));
			player.getInventory().add(new ItemStack(dev.starmine.world.VoidGate.SHARD));
			Goals.check(player);
			for (Goals.Goal goal : new Goals.Goal[] {Goals.Goal.RACE_TOOL, Goals.Goal.TERRAZINE, Goals.Goal.VOID_SHARD})
				helper.assertTrue(Goals.done(player, goal), "the goal " + goal.id + " did not count");
			helper.assertTrue(!Goals.done(player, Goals.Goal.ARMY) && !Goals.done(player, Goals.Goal.TURRET), "an army or a turret out of nothing");
			helper.assertTrue(Goals.journal(player).getString().length() > 0, "an empty journal");
			helper.succeed();
		} finally {
			helper.getLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED); // other tests share the level
		}
	}

	/** The guide is a written book of translated pages, given once on the first join. */
	@GameTest(template = ARENA, batch = "guide", timeoutTicks = 40)
	public void guideIsGivenOnce(GameTestHelper helper) {
		ItemStack book = Guide.book();
		helper.assertTrue(book.is(Items.WRITTEN_BOOK), "not a written book");
		var content = book.get(DataComponents.WRITTEN_BOOK_CONTENT);
		helper.assertTrue(content != null && content.pages().size() == Guide.PAGES, "the guide has the wrong pages");
		helper.assertTrue(content.pages().get(0).raw().getContents() instanceof TranslatableContents, "the guide is not translated");
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayerInLevel();
		try {
			player.getInventory().clearContent();
			player.removeTag("starmine_guide_given");
			Guide.welcome(player);
			Guide.welcome(player);
			ItemStack given = Guide.illustrated() != null ? Guide.illustrated() : Guide.book();
			int books = player.getInventory().countItem(given.getItem());
			helper.assertTrue(books == 1, books + " guides given");
			helper.succeed();
		} finally {
			helper.getLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
		}
	}

	/**
	 * Owner 16.10: the guide reads in Russian and English — every written page in both languages, the illustrated
	 * book (Patchouli) with the same chapters in both; with Patchouli present a player gets the illustrated one.
	 */
	@GameTest(template = ARENA, batch = "guide", timeoutTicks = 40)
	public void guideIsInRussianAndEnglish(GameTestHelper helper) {
		java.util.Map<String, com.google.gson.JsonObject> langs = new java.util.HashMap<>();
		for (String lang : new String[] {"en_us", "ru_ru"}) langs.put(lang, json("/assets/starmine/lang/" + lang + ".json"));
		for (int i = 1; i <= Guide.PAGES; i++)
			for (var lang : langs.entrySet()) helper.assertTrue(lang.getValue().has("book.starmine.guide." + i), "no page " + i + " in " + lang.getKey());
		for (var lang : langs.entrySet())
			helper.assertTrue(lang.getValue().has("book.starmine.field_guide.name") && lang.getValue().has("book.starmine.field_guide.landing"),
				"the illustrated book has no name in " + lang.getKey());
		helper.assertTrue(json("/data/starmine/patchouli_books/field_guide/book.json") != null, "no illustrated book");
		for (Goals.Goal goal : Goals.Goal.values()) // every goal is named, described and hinted in both languages
			for (var lang : langs.entrySet())
				for (String key : new String[] {"advancements.starmine." + goal.id + ".title", "advancements.starmine." + goal.id + ".description", "hint.starmine." + goal.id})
					helper.assertTrue(lang.getValue().has(key), "no " + key + " in " + lang.getKey());
		// owner 17.10: every race's troops and buildings, its general information (base, sky ship, upgrades), every ability; owner 19.10:
		// each thing told once (the bosses under Heroes, the way to the ships under The Stratosphere, one entry for the suits)
		java.util.List<String> entries = new java.util.ArrayList<>(java.util.List.of("basics/welcome", "basics/sides", "basics/goals", "base/founding",
			"base/command", "base/resources", "base/upgrades", "gear/suits", "gear/modules", "gear/guns", "world/wild", "world/stratosphere",
			"heroes/kerrigan", "heroes/raynor", "heroes/artanis", "endgame/artifact", "endgame/void"));
		for (String race : new String[] {"zerg", "terran", "protoss"})
			for (String general : new String[] {"base", "sky", "upgrades"}) entries.add(race + "_general/" + general);
		for (dev.starmine.entity.UnitKind kind : dev.starmine.entity.UnitKind.values()) {
			if (kind.boss() || kind == dev.starmine.entity.UnitKind.LARVA || kind == dev.starmine.entity.UnitKind.EGG) continue;
			boolean building = kind.race.rules().buildings().contains(kind) || kind.speed == 0;
			entries.add(kind.race.name().toLowerCase() + (building ? "_buildings/" : "_units/") + kind.id);
		}
		for (String lang : new String[] {"en_us", "ru_ru"}) {
			StringBuilder all = new StringBuilder();
			java.util.Map<String, String> told = new java.util.HashMap<>(); // owner 19.10: a general page is told once in the book
			for (String entry : entries) {
				var page = json("/assets/starmine/patchouli_books/field_guide/" + lang + "/entries/" + entry + ".json");
				helper.assertTrue(page != null && page.getAsJsonArray("pages").size() > 0, "no " + entry + " in " + lang);
				all.append(page);
				for (var element : page.getAsJsonArray("pages")) { // every recipe and creature a page shows exists
					var p = element.getAsJsonObject();
					if (!entry.contains("_units/") && !entry.contains("_buildings/") && p.has("text") && p.get("text").getAsString().length() > 80) {
						String before = told.put(p.get("text").getAsString(), entry);
						helper.assertTrue(before == null, "the same page in " + before + " and " + entry);
					}
					if (p.has("recipe")) helper.assertTrue(helper.getLevel().getRecipeManager().byKey(net.minecraft.resources.ResourceLocation.parse(p.get("recipe").getAsString())).isPresent(),
						entry + ": no recipe " + p.get("recipe"));
					if (p.has("entity")) helper.assertTrue(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(net.minecraft.resources.ResourceLocation.parse(p.get("entity").getAsString())),
						entry + ": no creature " + p.get("entity"));
				}
			}
			for (String race : new String[] {"zerg", "terran", "protoss"})
				for (String sub : new String[] {"units", "buildings", "general"}) {
					var category = json("/assets/starmine/patchouli_books/field_guide/" + lang + "/categories/" + race + "_" + sub + ".json");
					helper.assertTrue(category != null && ("starmine:" + race).equals(category.get("parent").getAsString()), race + "_" + sub + " not under its race in " + lang);
				}
			var names = langs.get(lang);
			for (dev.starmine.ability.Ability ability : dev.starmine.ability.Ability.values()) {
				if (ability.users().stream().allMatch(dev.starmine.entity.UnitKind::boss)) continue;
				String name = names.get("ability.starmine." + ability.id).getAsString();
				helper.assertTrue(all.indexOf(name) >= 0, "the ability " + ability.id + " is not in the " + lang + " book");
			}
		}
		if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("patchouli")) {
			ItemStack book = Guide.illustrated();
			helper.assertTrue(book != null && book.getItem().toString().contains("guide_book"), "Patchouli is here but the guide is " + book);
		}
		helper.succeed();
	}

	/** Owner 17.10: the guide has a recipe — a book and a mineral shard. */
	@GameTest(template = ARENA, batch = "guide", timeoutTicks = 40)
	public void theGuideIsCrafted(GameTestHelper helper) {
		var input = net.minecraft.world.item.crafting.CraftingInput.of(2, 1, java.util.List.of(new ItemStack(Items.BOOK),
			new ItemStack(dev.starmine.mineral.Minerals.SHARD)));
		var recipe = helper.getLevel().getRecipeManager().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, input, helper.getLevel());
		helper.assertTrue(recipe.isPresent(), "no recipe for a book and a mineral shard");
		ItemStack made = recipe.get().value().assemble(input, helper.getLevel().registryAccess());
		ItemStack guide = Guide.illustrated() != null ? Guide.illustrated() : Guide.book();
		helper.assertTrue(ItemStack.isSameItemSameComponents(made, guide), "the recipe makes " + made);
		helper.succeed();
	}

	private static com.google.gson.JsonObject json(String path) {
		try (var in = GuideGameTests.class.getResourceAsStream(path)) {
			return in == null ? null : com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (java.io.IOException e) {
			return null;
		}
	}
}
