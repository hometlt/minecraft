package dev.starmine.client;

import dev.starmine.colony.ColonyNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The player's base as the server last reported it (every second), and the resource panel drawn at
 * the top right (owner 14.10): minerals, vespene and supply like SC2, then what the base's workers and
 * the player brought in, by resource (wood, coal, copper, iron, gold, diamond, redstone, lapis,
 * emerald, quartz, netherite — the labels and icons of data/starmine/hive/resources.json).
 * Shown on the HUD whenever the player has a base, and in the command view.
 */
public final class BaseStatusClient {
	static ColonyNetwork.BaseStatus status = ColonyNetwork.BaseStatus.NONE;
	private static final ItemStack MINERALS = new ItemStack(Items.PRISMARINE_CRYSTALS), VESPENE = new ItemStack(Items.LIME_DYE),
		SUPPLY = new ItemStack(Items.IRON_BARS);

	private BaseStatusClient() {
	}

	static void receive(ColonyNetwork.BaseStatus received) {
		status = received;
	}

	/** Draws the panel at the top right of a screen `width` wide. */
	static void drawPanel(GuiGraphics graphics, int width) {
		if (!status.present()) return;
		Font font = Minecraft.getInstance().font;
		int columns = 2, rowHeight = 18, cellWidth = 64;
		int rows = 1 + (status.tallies().size() + columns - 1) / columns;
		int panelWidth = Math.max(3 * cellWidth, columns * cellWidth) + 8;
		int left = width - panelWidth - 4, top = 4;
		graphics.fill(left, top, left + panelWidth, top + rows * rowHeight + 8, 0xB0101418);
		int y = top + 4;
		int x = left + 4;
		item(graphics, font, MINERALS, x, y, String.valueOf(status.minerals()), 0xFF80D0FF);
		item(graphics, font, VESPENE, x + cellWidth, y, String.valueOf(status.vespene()), 0xFF80FF80);
		boolean blocked = status.supplyUsed() >= status.supplyCap();
		item(graphics, font, SUPPLY, x + 2 * cellWidth, y, supply(status.supplyUsed()) + "/" + status.supplyCap(), blocked ? 0xFFFF6060 : 0xFFFFFFFF);
		y += rowHeight;
		int i = 0;
		for (ColonyNetwork.Tally tally : status.tallies()) {
			ResourceLocation icon = ResourceLocation.tryParse(tally.icon());
			ItemStack stack = icon == null ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.get(icon));
			item(graphics, font, stack, x + (i % columns) * cellWidth, y + (i / columns) * rowHeight, String.valueOf(tally.count()), 0xFFE0E0E0);
			i++;
		}
	}

	private static String supply(float used) {
		return used == Math.floor(used) ? String.valueOf((int) used) : String.format("%.1f", used);
	}

	private static void item(GuiGraphics graphics, Font font, ItemStack stack, int x, int y, String text, int color) {
		if (!stack.isEmpty()) graphics.renderItem(stack, x, y);
		graphics.drawString(font, Component.literal(text), x + 18, y + 4, color);
	}
}
