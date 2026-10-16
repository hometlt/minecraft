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
		String[] entries = {"basics/welcome", "basics/sides", "basics/goals", "base/founding", "base/command", "base/resources", "gear/suits",
			"gear/modules", "gear/guns", "zerg/hive", "zerg/nydus", "terran/army", "protoss/army", "world/wild", "world/heroes"};
		for (String lang : new String[] {"en_us", "ru_ru"})
			for (String entry : entries) {
				var page = json("/assets/starmine/patchouli_books/field_guide/" + lang + "/entries/" + entry + ".json");
				helper.assertTrue(page != null && page.getAsJsonArray("pages").size() > 0, "no " + entry + " in " + lang);
				for (var element : page.getAsJsonArray("pages")) { // every recipe and creature a page shows exists
					var p = element.getAsJsonObject();
					if (p.has("recipe")) helper.assertTrue(helper.getLevel().getRecipeManager().byKey(net.minecraft.resources.ResourceLocation.parse(p.get("recipe").getAsString())).isPresent(),
						entry + ": no recipe " + p.get("recipe"));
					if (p.has("entity")) helper.assertTrue(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(net.minecraft.resources.ResourceLocation.parse(p.get("entity").getAsString())),
						entry + ": no creature " + p.get("entity"));
				}
			}
		if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("patchouli")) {
			ItemStack book = Guide.illustrated();
			helper.assertTrue(book != null && book.getItem().toString().contains("guide_book"), "Patchouli is here but the guide is " + book);
		}
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
