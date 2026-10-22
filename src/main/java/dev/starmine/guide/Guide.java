package dev.starmine.guide;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

/**
 * The field guide (owner 16.10, item 8): a written book given to every player on their first join (and
 * by `/starguide` again) on the mod's mechanics, the three races and their creatures, the world and its
 * heroes. Its pages are lang keys (`book.starmine.guide.<n>`), so it reads in the player's language.
 *
 * With Patchouli installed (optional, owner 16.10) the guide is the illustrated book instead
 * ({@link #illustrated}): the same chapters with the units' models, the crafting grids of the gear and
 * more pages, in English and Russian (`assets/starmine/patchouli_books/field_guide/<lang>`). StarMine
 * does not depend on Patchouli: the book is data, the item is looked up by id.
 */
public final class Guide {
	/** The number of pages: keys book.starmine.guide.1 .. PAGES in every language. */
	public static final int PAGES = 36; // owner 22.10: the pedestal, dungeons and bosses (34-36)
	/** The Patchouli book (`data/starmine/patchouli_books/field_guide`). */
	public static final net.minecraft.resources.ResourceLocation BOOK = dev.starmine.StarMine.id("field_guide");
	private static final String GIVEN = "starmine_guide_given";
	/** The guide's recipe (owner 17.10): a book and a mineral shard (`data/starmine/recipe/field_guide.json`). */
	public static final net.minecraft.world.item.crafting.RecipeSerializer<Recipe> RECIPE = net.minecraft.core.Registry.register(
		net.minecraft.core.registries.BuiltInRegistries.RECIPE_SERIALIZER, dev.starmine.StarMine.id("field_guide"),
		new net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer<>(Recipe::new));

	private Guide() {
	}

	public static void load() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> welcome(handler.getPlayer())));
		net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) ->
			dispatcher.register(Commands.literal("starguide").executes(context -> {
				give(context.getSource().getPlayerOrException());
				return 1;
			})));
	}

	/** A player's first join: the guide and the journal's first page. */
	public static void welcome(ServerPlayer player) {
		if (player.getTags().contains(GIVEN)) return;
		player.addTag(GIVEN);
		give(player);
		player.sendSystemMessage(Component.translatable("message.starmine.welcome").withStyle(net.minecraft.ChatFormatting.GOLD));
		Goals.award(player, null);
	}

	/** Puts a guide in the player's inventory (or at their feet when it is full). */
	public static void give(ServerPlayer player) {
		ItemStack illustrated = illustrated();
		ItemStack book = illustrated != null ? illustrated : book();
		if (!player.getInventory().add(book)) player.drop(book, false);
	}

	/** The illustrated guide when Patchouli is installed: its guide book item bound to {@link #BOOK}; else null. */
	@SuppressWarnings("unchecked")
	public static @org.jetbrains.annotations.Nullable ItemStack illustrated() {
		if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("patchouli")) return null;
		var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("patchouli", "guide_book"));
		var component = net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("patchouli", "book"));
		if (item.isEmpty() || component == null) return null;
		ItemStack stack = new ItemStack(item.get());
		stack.set((net.minecraft.core.component.DataComponentType<net.minecraft.resources.ResourceLocation>) component, BOOK);
		return stack;
	}

	/** The guide: a written book of translated pages. */
	public static ItemStack book() {
		List<Filterable<Component>> pages = new ArrayList<>();
		for (int i = 1; i <= PAGES; i++) pages.add(Filterable.passThrough(Component.translatable("book.starmine.guide." + i)));
		ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
		book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough("StarMine"), "Adjutant", 0, pages, true));
		book.set(DataComponents.CUSTOM_NAME, Component.translatable("item.starmine.guide").setStyle(Style.EMPTY.withItalic(false)
			.withColor(net.minecraft.ChatFormatting.GOLD)));
		return book;
	}

	/** A book and a mineral shard make the guide — the illustrated one when Patchouli is installed, else the written one. */
	public static final class Recipe extends net.minecraft.world.item.crafting.CustomRecipe {
		public Recipe(net.minecraft.world.item.crafting.CraftingBookCategory category) {
			super(category);
		}

		@Override
		public boolean matches(net.minecraft.world.item.crafting.CraftingInput input, net.minecraft.world.level.Level level) {
			int books = 0, shards = 0, other = 0;
			for (ItemStack stack : input.items()) {
				if (stack.isEmpty()) continue;
				if (stack.is(Items.BOOK)) books++;
				else if (stack.is(dev.starmine.mineral.Minerals.SHARD)) shards++;
				else other++;
			}
			return books == 1 && shards == 1 && other == 0;
		}

		@Override
		public ItemStack assemble(net.minecraft.world.item.crafting.CraftingInput input, net.minecraft.core.HolderLookup.Provider registries) {
			ItemStack illustrated = illustrated();
			return illustrated != null ? illustrated : book();
		}

		@Override
		public boolean canCraftInDimensions(int width, int height) {
			return width * height >= 2;
		}

		@Override
		public net.minecraft.world.item.crafting.RecipeSerializer<?> getSerializer() {
			return RECIPE;
		}
	}
}
