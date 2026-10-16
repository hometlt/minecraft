package dev.starmine.client;

import dev.starmine.colony.ColonyNetwork;
import dev.starmine.entity.UnitKind;
import java.util.ArrayList;
import java.util.List;
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
 * the top right (owner 14.10): minerals, vespene and supply like SC2, then the base's stock of each
 * resource of data/starmine/hive/panel.json (netherite, diamond, gold, iron, copper, coal, redstone,
 * lapis, emerald; icons from data/starmine/hive/resources.json).
 * Shown in the command view only (CLOUD_TASK_2: the HUD outside it stays clean).
 */
public final class BaseStatusClient {
	static ColonyNetwork.BaseStatus status = ColonyNetwork.BaseStatus.NONE;
	/** The bases on the map (owner 16.10): the races' main bases and the player's own ({@link CommandMinimap}). */
	static List<ColonyNetwork.Mark> marks = List.of();
	private static final ItemStack MINERALS = new ItemStack(dev.starmine.mineral.Minerals.SHARD),
		VESPENE = new ItemStack(dev.starmine.vespene.Vespene.BUCKET);

	private BaseStatusClient() {
	}

	static void receive(ColonyNetwork.BaseStatus received) {
		status = received;
	}

	/**
	 * Draws the panel at the top right of a screen `width` wide, in the command view only (CLOUD_TASK_2:
	 * small, strategic mode only; owner 16.10: a row along the top as in SC2): small icons with numbers —
	 * minerals, vespene, supply, then the base's stock of each resource of `data/starmine/hive/panel.json`.
	 */
	static void drawPanel(GuiGraphics graphics, int width) {
		if (!status.present()) return;
		Font font = Minecraft.getInstance().font;
		List<ItemStack> icons = new ArrayList<>();
		List<String> texts = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();
		icons.add(MINERALS);
		texts.add(String.valueOf(status.minerals()));
		colors.add(0xFF80D0FF);
		icons.add(VESPENE);
		texts.add(String.valueOf(status.vespene()));
		colors.add(0xFF80FF80);
		int sc2 = 2; // minerals, vespene, then one supply per race the player commands (owner 16.10: its SC2 icon — overlord, depot, pylon)
		for (ColonyNetwork.Supply supply : status.supplies()) {
			icons.add(new ItemStack(dev.starmine.StarMine.egg(supplyIcon(supply.race()))));
			texts.add(supply(supply.used()) + "/" + supply.cap());
			colors.add(supply.used() >= supply.cap() ? 0xFFFF6060 : 0xFFFFFFFF);
			sc2++;
		}
		for (ColonyNetwork.Tally tally : status.tallies()) {
			ResourceLocation icon = ResourceLocation.tryParse(tally.icon());
			icons.add(icon == null ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.get(icon)));
			texts.add(String.valueOf(tally.count()));
			colors.add(tally.count() > 0 ? 0xFFE0E0E0 : 0xFF808080);
		}
		float scale = 0.75f;
		int[] cells = new int[icons.size()];
		int rowWidth = 0;
		for (int i = 0; i < icons.size(); i++) {
			cells[i] = 18 + Math.max(font.width(texts.get(i)), i < sc2 ? 34 : 14) + 8; // the SC2 ones wider, the stock narrower
			rowWidth += cells[i];
		}
		int panelWidth = (int) Math.ceil(rowWidth * scale) + 4, panelHeight = (int) Math.ceil(17 * scale) + 4;
		int left = width - panelWidth - 2, top = 2;
		graphics.fill(left, top, left + panelWidth, top + panelHeight, 0x90101418);
		graphics.pose().pushPose();
		graphics.pose().translate(left + 2, top + 2, 0);
		graphics.pose().scale(scale, scale, 1);
		for (int i = 0, x = 0; i < icons.size(); x += cells[i], i++) item(graphics, font, icons.get(i), x, 0, texts.get(i), colors.get(i));
		graphics.pose().popPose();
	}

	/** A race's supply icon, as in SC2: the overlord, the supply depot, the pylon. */
	static UnitKind supplyIcon(String race) {
		return switch (race) {
			case "ZERG" -> UnitKind.OVERLORD;
			case "PROTOSS" -> UnitKind.PYLON;
			default -> UnitKind.SUPPLY_DEPOT;
		};
	}

	/** A race's supply in the base status (none: nothing given, nothing taken). */
	static ColonyNetwork.Supply supplyOf(ColonyNetwork.BaseStatus status, UnitKind.Race race) {
		return status.supplies().stream().filter(supply -> supply.race().equals(race.name())).findFirst()
			.orElse(new ColonyNetwork.Supply(race.name(), 0, 0));
	}

	private static String supply(float used) {
		return used == Math.floor(used) ? String.valueOf((int) used) : String.format("%.1f", used);
	}

	private static void item(GuiGraphics graphics, Font font, ItemStack stack, int x, int y, String text, int color) {
		if (!stack.isEmpty()) graphics.renderItem(stack, x, y);
		graphics.drawString(font, Component.literal(text), x + 18, y + 4, color);
	}
}
