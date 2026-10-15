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
			int books = player.getInventory().countItem(Items.WRITTEN_BOOK);
			helper.assertTrue(books == 1, books + " guides given");
			helper.succeed();
		} finally {
			helper.getLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
		}
	}
}
