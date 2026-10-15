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
 */
public final class Guide {
	/** The number of pages: keys book.starmine.guide.1 .. PAGES in every language. */
	public static final int PAGES = 31;
	private static final String GIVEN = "starmine_guide_given";

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
		ItemStack book = book();
		if (!player.getInventory().add(book)) player.drop(book, false);
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
}
