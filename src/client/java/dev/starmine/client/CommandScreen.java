package dev.starmine.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.starmine.StarMine;
import dev.starmine.colony.ColonyNetwork;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.race.Research;
import dev.starmine.race.Sc2Buttons;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * The command view's interface (owner 14.10), StarCraft II style:
 *
 * - top right: the resource panel ({@link BaseStatusClient});
 * - bottom right: the command card, 5 columns x 3 rows, with SC2 hotkeys. Units: move (M), stop (S),
 *   hold (H), patrol (P), attack (A); SCVs also gather (G), return cargo (C), build (B) and build
 *   advanced (V), which open pages of buildings (each placed as a translucent ghost: right click puts
 *   it, left click or Esc cancels). Buildings: the units they train, research at the engineering
 *   bay, rally (Y), cancel (X). Greyed when the tech or the money is missing; the tooltip tells the cost;
 * - every action shows its SC2 button icon ({@link Sc2Buttons}, exported by Modkit) when the SC2 assets
 *   carry it, else its name;
 * - bottom middle: what is selected (name, health, construction or training progress and queue).
 *
 * Mouse: left click selects (shift adds, double click takes all of that kind on screen), dragging
 * selects the base's units in a box; right click is the smart command (attack an enemy, gather a tree
 * or ore, return cargo at a command center, otherwise move; for buildings, the rally point). Commands
 * that need a target (move, patrol, attack, gather, rally) take the next right click (owner 18.10: every action is the right button's; a left click
 * only selects, dropping the waiting command). Ctrl+1..9 saves a
 * group, 1..9 selects it. Arrows and the screen edges pan (WASD too, unless the letter is a hotkey of
 * the card), the wheel zooms, Q/E turn the isometric view by a quarter, the middle button pans (held on the minimap it drags the map); the
 * minimap (bottom left) moves the view with a left click and sends the selection with a right click. Esc steps back: cancels placing or
 * targeting, leaves a build page, clears the selection, and finally leaves the view.
 */
public final class CommandScreen extends Screen {
	private static final int CELL = 34, GAP = 2;
	private enum Page { MAIN, BUILD, ADVANCED, HALLUCINATION }

	/** A card button; `auto`: what a right click on it toggles to cast by itself (an ability's id, "gather"), or null. */
	private record Button(int slot, Component label, int key, UnitKind kind, boolean enabled, List<Component> tooltip, Runnable action, String icon,
			ItemStack item, String auto) {
		Button(int slot, Component label, int key, UnitKind kind, boolean enabled, List<Component> tooltip, Runnable action, String icon) {
			this(slot, label, key, kind, enabled, tooltip, action, icon, ItemStack.EMPTY, null);
		}

		Button(int slot, Component label, int key, UnitKind kind, boolean enabled, List<Component> tooltip, Runnable action, String icon, ItemStack item) {
			this(slot, label, key, kind, enabled, tooltip, action, icon, item, null);
		}

		Button autocast(String id) {
			List<Component> lines = new ArrayList<>(tooltip);
			lines.add(Component.translatable("command.starmine.autocast").withColor(0xFFD0A040));
			return new Button(slot, label, key, kind, enabled, lines, action, icon, item, id);
		}

		Button at(int slot) {
			return slot == this.slot ? this : new Button(slot, label, key, kind, enabled, tooltip, action, icon, item, auto);
		}
	}

	/** Size of the exported icon PNGs (`export-starmine-icons.mjs --size`). */
	private static final int ICON = 64;
	/** Which SC2 button icons the resources carry (asked once per button). */
	private static final Map<String, ResourceLocation> ICONS = new HashMap<>();

	/** The icon texture of an SC2 button, or null when the SC2 assets do not carry it. */
	private static ResourceLocation icon(String button) {
		if (button == null) return null;
		// ponytail: cached for the session; a resource reload adding icons shows them after a restart
		return ICONS.computeIfAbsent(button, id -> {
			ResourceLocation texture = StarMine.id(Sc2Buttons.texture(id));
			return Minecraft.getInstance().getResourceManager().getResource(texture).isPresent() ? texture : null;
		});
	}

	private Page page = Page.MAIN;
	/** The middle button went down on the minimap: its drag moves the map ({@link #mouseDragged}). */
	private boolean mapGrab;
	/** A command waiting for its target click (move, patrol, attack, gather, rally). */
	private String targeting;
	private double dragX, dragY;
	private boolean dragging;
	/** The gather command's press: a click mines a block, a drag a whole area. */
	private boolean gatherPress;
	private long lastClick;
	private boolean up, down, left, right, turnLeft, turnRight;
	private static final Map<Integer, List<Integer>> GROUPS = new HashMap<>();

	CommandScreen() {
		super(Component.translatable("screen.starmine.command"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// the world stays visible
	}

	@Override
	public void tick() {
		Minecraft mc = Minecraft.getInstance();
		double mx = mc.mouseHandler.xpos() * width / mc.getWindow().getScreenWidth();
		double my = mc.mouseHandler.ypos() * height / mc.getWindow().getScreenHeight();
		int edge = 3;
		CommandView.panX = (right || mx >= width - edge ? 1 : 0) - (left || mx <= edge ? 1 : 0);
		CommandView.panZ = (up || my <= edge ? 1 : 0) - (down || my >= height - edge ? 1 : 0);
		CommandView.turn = 0;
	}

	@Override
	public void removed() {
		CommandView.panX = CommandView.panZ = CommandView.turn = CommandView.climbing = 0;
		CommandView.exit(Minecraft.getInstance());
	}

	// ---- drawing ----

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		drawHidden(graphics);
		Bars.draw(graphics, width, height);
		BaseStatusClient.drawPanel(graphics, width);
		CommandMinimap.draw(graphics, width, height);
		drawSelection(graphics);
		drawGroups(graphics, mouseX, mouseY);
		List<Button> buttons = buttons();
		Button hover = null;
		for (Button button : buttons) {
			int[] at = cell(button.slot);
			boolean over = mouseX >= at[0] && mouseX < at[0] + CELL && mouseY >= at[1] && mouseY < at[1] + CELL;
			if (over) hover = button;
			graphics.fill(at[0], at[1], at[0] + CELL, at[1] + CELL, button.enabled ? (over ? 0xE0305080 : 0xD0203048) : 0xC0303030);
			graphics.renderOutline(at[0], at[1], CELL, CELL, button.enabled ? 0xFF6080B0 : 0xFF505050);
			ResourceLocation icon = icon(button.icon);
			if (!button.item.isEmpty()) { // the hero's hotbar: the item, its count, its slot number
				graphics.renderItem(button.item, at[0] + 9, at[1] + 5);
				graphics.renderItemDecorations(font, button.item, at[0] + 9, at[1] + 5);
			} else if (button.kind != null) graphics.renderItem(new ItemStack(StarMine.egg(button.kind)), at[0] + 9, at[1] + 5);
			else if (icon != null) {
				// SC2 icons rely on alpha (command glyphs are white with fully transparent white around them);
				// GuiGraphics.blit does not blend by itself, which drew them as solid white silhouettes
				RenderSystem.enableBlend();
				RenderSystem.defaultBlendFunc();
				graphics.blit(icon, at[0] + 1, at[1] + 1, CELL - 2, CELL - 2, 0, 0, ICON, ICON, ICON, ICON);
				RenderSystem.disableBlend();
				if (!button.enabled) graphics.fill(at[0] + 1, at[1] + 1, at[0] + CELL - 1, at[1] + CELL - 1, 0xA0000000);
			} else {
				String text = button.label.getString();
				graphics.drawCenteredString(font, text.length() > 6 ? text.substring(0, 6) : text, at[0] + CELL / 2, at[1] + 13, button.enabled ? 0xFFFFFFFF : 0xFF909090);
			}
			if (button.key > 0) graphics.drawString(font, keyName(button.key), at[0] + 2, at[1] + CELL - 9, 0xFFFFD040);
			if (button.auto != null && autocasting(button.auto)) { // SC2: a turning gold frame round an autocast button
				int phase = (int) (System.currentTimeMillis() / 120 % 4);
				graphics.renderOutline(at[0] + 1, at[1] + 1, CELL - 2, CELL - 2, phase % 2 == 0 ? 0xFFFFC030 : 0xFFE09020);
				graphics.renderOutline(at[0] + 2, at[1] + 2, CELL - 4, CELL - 4, phase < 2 ? 0xC0FFC030 : 0x60FFC030);
			}
			if (button.item != ItemStack.EMPTY && Minecraft.getInstance().player != null
				&& Minecraft.getInstance().player.getInventory().selected == button.slot - 5) // the right hand
				graphics.renderOutline(at[0] + 1, at[1] + 1, CELL - 2, CELL - 2, 0xFFFFFFFF);
		}
		for (int slot = 0; slot < 15; slot++) {
			final int s = slot;
			if (buttons.stream().noneMatch(button -> button.slot == s)) {
				int[] at = cell(slot);
				graphics.fill(at[0], at[1], at[0] + CELL, at[1] + CELL, 0x80101418);
			}
		}
		if (dragging) {
			int x0 = (int) Math.min(dragX, mouseX), y0 = (int) Math.min(dragY, mouseY), x1 = (int) Math.max(dragX, mouseX), y1 = (int) Math.max(dragY, mouseY);
			graphics.fill(x0, y0, x1, y1, 0x2040FF40);
			graphics.renderOutline(x0, y0, x1 - x0, y1 - y0, 0xFF40FF40);
		}
		if (targeting != null)
			graphics.drawCenteredString(font, Component.translatable("command.starmine.target", targeting.startsWith("ability:")
				? Component.translatable("ability.starmine." + targeting.substring(8)) : Component.translatable("command.starmine." + targeting)), width / 2, 24, 0xFFFFFF60);
		else if (CommandView.placing != null)
			graphics.drawCenteredString(font, Component.translatable("command.starmine.place", name(CommandView.placing)), width / 2, 24, 0xFFFFFF60);
		if (hover != null) graphics.renderComponentTooltip(font, hover.tooltip, mouseX, mouseY);
		drawCut(graphics, mouseX, mouseY);
	}

	// ---- the cutaway (owner 18.10): four buttons at the top left cut the land by a level and round the camera ----

	private static final int CUT = 20;
	private static final String[] CUTS = {"cut_down", "cut_up", "cut_near_more", "cut_near_less"}, CUT_LABELS = {"\u25BC", "\u25B2", "+", "-"};

	private int[] cutButton(int i) {
		return new int[] {4 + i * (CUT + 2), 4};
	}

	private int cutAt(double x, double y) {
		for (int i = 0; i < CUTS.length; i++) {
			int[] at = cutButton(i);
			if (x >= at[0] && x < at[0] + CUT && y >= at[1] && y < at[1] + CUT) return i;
		}
		return -1;
	}

	private void drawCut(GuiGraphics graphics, int mouseX, int mouseY) {
		int hover = cutAt(mouseX, mouseY);
		for (int i = 0; i < CUTS.length; i++) {
			int[] at = cutButton(i);
			boolean on = i < 2 ? CommandView.cutDepth >= 0 : CommandView.cutRadius > 0;
			graphics.fill(at[0], at[1], at[0] + CUT, at[1] + CUT, i == hover ? 0xE0305080 : 0xD0203048);
			graphics.renderOutline(at[0], at[1], CUT, CUT, on ? 0xFF60E060 : 0xFF6080B0);
			graphics.drawCenteredString(font, CUT_LABELS[i], at[0] + CUT / 2, at[1] + 6, 0xFFFFFFFF);
		}
		Minecraft mc = Minecraft.getInstance();
		int[] level = cutButton(0), near = cutButton(2);
		if (CommandView.cutDepth >= 0)
			graphics.drawString(font, "y " + (net.minecraft.util.Mth.floor(CommandView.height) - CommandView.cutDepth), level[0], level[1] + CUT + 2, 0xFF60E060);
		if (CommandView.cutRadius > 0) graphics.drawString(font, "r " + CommandView.cutRadius, near[0], near[1] + CUT + 2, 0xFF60E060);
		if (hover >= 0 && mc.level != null)
			graphics.renderComponentTooltip(font, List.of(Component.translatable("command.starmine." + CUTS[hover]),
				Component.translatable("command.starmine.cut.tip").withColor(0xFFC0C0C0)), mouseX, mouseY);
	}

	private static void cut(int i) {
		switch (i) {
			case 0 -> CommandView.cutLevel(1);
			case 1 -> CommandView.cutLevel(-1);
			case 2 -> CommandView.cutNear(1);
			default -> CommandView.cutNear(-1);
		}
	}

	private static String keyName(int key) {
		return key == GLFW.GLFW_KEY_ESCAPE ? "Esc" : String.valueOf((char) key);
	}

	private int[] cell(int slot) {
		int left = width - 5 * (CELL + GAP) - 4, top = height - 3 * (CELL + GAP) - 4;
		return new int[] {left + (slot % 5) * (CELL + GAP), top + (slot / 5) * (CELL + GAP)};
	}

	private boolean onCard(double x, double y) {
		int[] first = cell(0);
		return x >= first[0] && y >= first[1];
	}

	private boolean onPanel(double x, double y) {
		int panelLeft = width / 2 - 140, panelTop = height - 3 * (CELL + GAP) - 4;
		return x >= panelLeft && x < width / 2 + 140 && y >= panelTop || onCard(x, y) || y < 140 && x > width - 210 || cutAt(x, y) >= 0
			|| groupAt(x, y) != 0 || CommandMinimap.contains(x, y, height);
	}

	// ---- army: hidden units, groups, the whole army ----

	private static final int GROUP_SIZE = 28, ARMY_GROUP = -1, HERO_GROUP = -2;

	/** Top left of a group's box (1..9) on the bottom left, or of the army button (ARMY_GROUP) above them. */
	private int[] groupBox(int group) {
		if (group == ARMY_GROUP) return new int[] {CommandMinimap.left(), CommandMinimap.top(height) - GROUP_SIZE};
		if (group == HERO_GROUP) return new int[] {CommandMinimap.left() + 3 * GROUP_SIZE + 4, CommandMinimap.top(height) - GROUP_SIZE};
		int panelTop = height - 3 * (CELL + GAP) - 4;
		return new int[] {width / 2 - 140 + (group - 1) * (GROUP_SIZE + 2), panelTop - GROUP_SIZE - 2};
	}

	/** The group (1..9) or ARMY_GROUP under a screen point; 0 for none. */
	private int groupAt(double x, double y) {
		int[] army = groupBox(ARMY_GROUP);
		if (x >= army[0] && x < army[0] + 3 * GROUP_SIZE && y >= army[1] && y < army[1] + GROUP_SIZE - 4) return ARMY_GROUP;
		int[] hero = groupBox(HERO_GROUP);
		if (x >= hero[0] && x < hero[0] + 3 * GROUP_SIZE && y >= hero[1] && y < hero[1] + GROUP_SIZE - 4) return HERO_GROUP;
		for (int group = 1; group <= 9; group++) {
			if (!GROUPS.containsKey(group)) continue;
			int[] at = groupBox(group);
			if (x >= at[0] && x < at[0] + GROUP_SIZE && y >= at[1] && y < at[1] + GROUP_SIZE) return group;
		}
		return 0;
	}

	/** The base's whole army in loaded range: its own units that are neither workers nor buildings (F2 in SC2). */
	private static List<StarUnit> army() {
		List<StarUnit> found = new ArrayList<>();
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return found;
		for (Entity entity : mc.level.entitiesForRendering())
			if (entity instanceof StarUnit unit && unit.isAlive() && CommandView.own(unit) && !unit.kind.structure() && !unit.kind.worker()) found.add(unit);
		return found;
	}

	private void selectArmy() {
		CommandView.hero = false;
		CommandView.selection.clear();
		for (StarUnit unit : army()) CommandView.selection.add(unit.getId());
		page = Page.MAIN;
	}

	/** The members of a group still alive. */
	private static List<StarUnit> members(int group) {
		List<StarUnit> units = new ArrayList<>();
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return units;
		for (int id : GROUPS.getOrDefault(group, List.of()))
			if (mc.level.getEntity(id) instanceof StarUnit unit && unit.isAlive()) units.add(unit);
		return units;
	}

	/** Bottom left: the army button and the control groups (most common kind's icon, size), as in SC2. */
	/** Owner 17.10: the card of what is looked at — a creature's name and health, a crystal's minerals left, a geyser's gas. */
	private void drawInspected(GuiGraphics graphics) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		int panelLeft = width / 2 - 140, panelTop = height - 3 * (CELL + GAP) - 4, panelWidth = 280, panelHeight = 3 * (CELL + GAP);
		if (CommandView.inspected != null && mc.level.getEntity(CommandView.inspected) instanceof net.minecraft.world.entity.LivingEntity living && living.isAlive()) {
			graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, 0xB0101418);
			net.minecraft.world.item.SpawnEggItem egg = net.minecraft.world.item.SpawnEggItem.byId(living.getType());
			if (egg != null) graphics.renderItem(new ItemStack(egg), panelLeft + 6, panelTop + 6);
			boolean foe = living instanceof net.minecraft.world.entity.monster.Enemy;
			graphics.drawString(font, living.getDisplayName(), panelLeft + 28, panelTop + 6, foe ? 0xFFFF8080 : 0xFFFFE080);
			int hp = (int) Math.ceil(living.getHealth()), max = (int) Math.ceil(living.getMaxHealth());
			graphics.drawString(font, hp + " / " + max, panelLeft + 28, panelTop + 18, 0xFF80FF80);
			bar(graphics, panelLeft + 6, panelTop + 30, panelWidth - 12, living.getHealth() / living.getMaxHealth(), 0xFF40C040);
			graphics.drawString(font, Component.translatable("command.starmine.armor", living.getArmorValue()), panelLeft + 6, panelTop + 40, 0xFFD0D0D0);
			return;
		}
		net.minecraft.core.BlockPos at = CommandView.inspectedBlock;
		if (at == null) return;
		net.minecraft.world.level.block.state.BlockState state = mc.level.getBlockState(at);
		if (!CommandView.resource(state)) return;
		graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, 0xB0101418);
		net.minecraft.core.BlockPos core = dev.starmine.mineral.Minerals.core(mc.level, at);
		if (core != null && mc.level.getBlockEntity(core) instanceof dev.starmine.mineral.Minerals.Core crystal) {
			graphics.renderItem(new ItemStack(dev.starmine.mineral.Minerals.SHARD), panelLeft + 6, panelTop + 6);
			graphics.drawString(font, Component.translatable(crystal.rich() ? "command.starmine.gold_minerals" : "command.starmine.minerals_field"),
				panelLeft + 28, panelTop + 6, crystal.rich() ? 0xFFFFD040 : 0xFF80C0FF);
			graphics.drawString(font, Component.translatable("command.starmine.minerals_left", crystal.amount(), dev.starmine.mineral.Minerals.AMOUNT),
				panelLeft + 28, panelTop + 18, 0xFFFFFFFF);
			bar(graphics, panelLeft + 6, panelTop + 30, panelWidth - 12, crystal.amount() / (float) dev.starmine.mineral.Minerals.AMOUNT,
				crystal.rich() ? 0xFFE0B030 : 0xFF40A0FF);
			if (crystal.rich()) graphics.drawString(font, Component.translatable("command.starmine.worth_twice"), panelLeft + 6, panelTop + 40, 0xFFFFD040);
			return;
		}
		boolean terrazine = dev.starmine.vespene.Vespene.isTerrazine(state);
		graphics.renderItem(new ItemStack(terrazine ? dev.starmine.vespene.Vespene.TERRAZINE_BUCKET : dev.starmine.vespene.Vespene.BUCKET), panelLeft + 6, panelTop + 6);
		graphics.drawString(font, Component.translatable(terrazine ? "command.starmine.terrazine_geyser" : "command.starmine.vespene_geyser"),
			panelLeft + 28, panelTop + 6, terrazine ? 0xFFC080FF : 0xFF80FF80);
		boolean full = dev.starmine.vespene.VespeneBlock.isSource(state);
		graphics.drawString(font, Component.translatable(full ? "command.starmine.geyser_full" : "command.starmine.geyser_empty",
			dev.starmine.vespene.Vespene.perTrip(state)), panelLeft + 28, panelTop + 18, 0xFFFFFFFF);
		if (terrazine) graphics.drawString(font, Component.translatable("command.starmine.worth_twice"), panelLeft + 6, panelTop + 50, 0xFFC080FF);
		gasLeft(graphics, mc.level, dev.starmine.vespene.Vespene.isOutlet(state) ? at : dev.starmine.vespene.Vespene.outletFor(mc.level, at), panelLeft, panelTop, panelWidth);
	}

	/** Owner 18.10: the vespene left in a geyser's vein, with a bar (the geyser looked at, or the one under a gas building). */
	private void gasLeft(GuiGraphics graphics, net.minecraft.world.level.Level level, net.minecraft.core.BlockPos outlet, int panelLeft, int panelTop, int panelWidth) {
		int[] gas = outlet == null ? null : dev.starmine.vespene.Vespene.gasLeft(level, outlet);
		if (gas == null) return;
		graphics.drawString(font, Component.translatable("command.starmine.vespene_left", gas[0], gas[1]), panelLeft + 6, panelTop + 30, 0xFF80FF80);
		bar(graphics, panelLeft + 6, panelTop + 41, panelWidth - 12, gas[0] / (float) Math.max(1, gas[1]), 0xFF40C060);
	}

	private void drawGroups(GuiGraphics graphics, int mouseX, int mouseY) {
		int[] army = groupBox(ARMY_GROUP);
		int armySize = army().size();
		boolean overArmy = groupAt(mouseX, mouseY) == ARMY_GROUP;
		graphics.fill(army[0], army[1], army[0] + 3 * GROUP_SIZE, army[1] + GROUP_SIZE - 4, overArmy ? 0xE0305080 : 0xC0203048);
		graphics.renderOutline(army[0], army[1], 3 * GROUP_SIZE, GROUP_SIZE - 4, 0xFF6080B0);
		graphics.drawString(font, Component.translatable("command.starmine.army", armySize), army[0] + 4, army[1] + 4, 0xFFFFFFFF);
		graphics.drawString(font, "F2", army[0] + 3 * GROUP_SIZE - 14, army[1] + 14, 0xFFFFD040);
		// owner 17.10: the player as a hero (HeroPilot)
		int[] hero = groupBox(HERO_GROUP);
		boolean overHero = groupAt(mouseX, mouseY) == HERO_GROUP;
		graphics.fill(hero[0], hero[1], hero[0] + 3 * GROUP_SIZE, hero[1] + GROUP_SIZE - 4,
			CommandView.heroSelected() ? 0xE0206060 : overHero ? 0xE0305080 : 0xC0203048);
		graphics.renderOutline(hero[0], hero[1], 3 * GROUP_SIZE, GROUP_SIZE - 4, CommandView.heroSelected() ? 0xFF40E0FF : 0xFF6080B0);
		graphics.drawString(font, Component.translatable("command.starmine.hero"), hero[0] + 4, hero[1] + 4, 0xFF80F0FF);
		graphics.drawString(font, "F4", hero[0] + 3 * GROUP_SIZE - 14, hero[1] + 14, 0xFFFFD040);
		for (int group = 1; group <= 9; group++) {
			if (!GROUPS.containsKey(group)) continue;
			List<StarUnit> units = members(group);
			int[] at = groupBox(group);
			boolean over = groupAt(mouseX, mouseY) == group;
			graphics.fill(at[0], at[1], at[0] + GROUP_SIZE, at[1] + GROUP_SIZE, over ? 0xE0305080 : 0xC0203048);
			graphics.renderOutline(at[0], at[1], GROUP_SIZE, GROUP_SIZE, 0xFF6080B0);
			java.util.Map<UnitKind, Long> kinds = new java.util.EnumMap<>(UnitKind.class);
			for (StarUnit unit : units) kinds.merge(unit.kind, 1L, Long::sum);
			kinds.entrySet().stream().max(java.util.Map.Entry.comparingByValue())
				.ifPresent(top -> graphics.renderItem(new ItemStack(StarMine.egg(top.getKey())), at[0] + 6, at[1] + 4));
			graphics.drawString(font, String.valueOf(group), at[0] + 2, at[1] + 1, 0xFFFFD040);
			graphics.drawString(font, String.valueOf(units.size()), at[0] + GROUP_SIZE - 2 - font.width(String.valueOf(units.size())), at[1] + GROUP_SIZE - 9, 0xFFFFFFFF);
		}
	}

	/**
	 * Own units hidden from the camera (behind a hill, in a tunnel) are shown where they are: their
	 * icon in a frame (green when selected), so they can be seen, clicked and box-selected.
	 */
	private static final List<StarUnit> HIDDEN = new ArrayList<>();
	private static long hiddenTick = Long.MIN_VALUE;

	private void drawHidden(GuiGraphics graphics) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
		if (mc.level.getGameTime() != hiddenTick) { // owner 18.10: a ray per unit once a tick, not every frame (a big army cost frames)
			hiddenTick = mc.level.getGameTime();
			HIDDEN.clear();
			for (Entity entity : mc.level.entitiesForRendering()) {
				if (!(entity instanceof StarUnit unit) || !unit.isAlive() || !CommandView.own(unit) || unit.kind.structure()) continue;
				Vec3 centre = unit.position().add(0, unit.getBbHeight() / 2, 0);
				if (centre.distanceToSqr(eye) > 160 * 160) continue;
				net.minecraft.world.phys.BlockHitResult hit = CommandView.drawnClip(mc.level, eye, centre,
					net.minecraft.world.level.ClipContext.Block.VISUAL, net.minecraft.world.level.ClipContext.Fluid.NONE, unit);
				if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && hit.getLocation().distanceTo(centre) >= unit.getBbWidth()) HIDDEN.add(unit);
			}
		}
		for (StarUnit unit : HIDDEN) {
			if (!unit.isAlive()) continue;
			Vec3 centre = unit.position().add(0, unit.getBbHeight() / 2, 0);
			double[] at = CommandView.project(centre, width, height);
			if (at == null) continue;
			int x = (int) at[0] - 10, y = (int) at[1] - 10;
			boolean selected = CommandView.selection.contains(unit.getId());
			graphics.fill(x, y, x + 20, y + 20, 0x70102030);
			graphics.renderOutline(x, y, 20, 20, selected ? 0xFF40FF40 : 0xFF80C0FF);
			graphics.renderItem(new ItemStack(StarMine.egg(unit.kind)), x + 2, y + 2);
		}
	}

	private static Component name(UnitKind kind) {
		return Component.translatable("entity.starmine." + kind.id);
	}

	/** Bottom middle: one unit in detail, or the selected units' icons with their health. */
	private void drawSelection(GuiGraphics graphics) {
		List<StarUnit> units = CommandView.selected();
		if (units.isEmpty()) {
			drawInspected(graphics);
			return;
		}
		int panelLeft = width / 2 - 140, panelTop = height - 3 * (CELL + GAP) - 4, panelWidth = 280, panelHeight = 3 * (CELL + GAP);
		graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, 0xB0101418);
		if (units.size() == 1) {
			StarUnit unit = units.get(0);
			graphics.renderItem(new ItemStack(StarMine.egg(unit.kind)), panelLeft + 6, panelTop + 6);
			graphics.drawString(font, name(unit.kind), panelLeft + 28, panelTop + 6, CommandView.own(unit) ? 0xFFFFFFFF : 0xFFFF8080);
			int hp = (int) Math.ceil(unit.getHealth()), max = (int) Math.ceil(unit.getMaxHealth());
			graphics.drawString(font, hp + " / " + max, panelLeft + 28, panelTop + 18, 0xFF80FF80);
			bar(graphics, panelLeft + 6, panelTop + 30, panelWidth - 12, unit.getHealth() / unit.getMaxHealth(), 0xFF40C040);
			if (unit.kind.gas()) gasLeft(graphics, unit.level(), dev.starmine.vespene.Vespene.outletUnder(unit.level(), unit.blockPosition()), panelLeft, panelTop + 12, panelWidth);
			if (unit.kind.shields > 0) { // protoss plasma shields over the health
				graphics.drawString(font, Component.translatable("command.starmine.shields", (int) Math.ceil(unit.shield()), (int) Math.ceil(unit.maxShield())),
					panelLeft + 120, panelTop + 18, 0xFF80C0FF);
				bar(graphics, panelLeft + 6, panelTop + 25, panelWidth - 12, (float) (unit.shield() / unit.maxShield()), 0xFF40A0FF);
			}
			if (unit.maxEnergy() > 0) { // casters' energy under the health
				graphics.drawString(font, Component.translatable("command.starmine.energy", (int) unit.energy(), unit.maxEnergy()),
					panelLeft + 200, panelTop + 18, 0xFFC080FF);
				bar(graphics, panelLeft + 6, panelTop + 35, panelWidth - 12, unit.energy() / unit.maxEnergy(), 0xFFA040FF);
			}
			if (!unit.kind.structure()) // owner 17.10: its upgrade levels and armor, as SC2's unit panel shows them
				graphics.drawString(font, unit.kind.shields > 0
					? Component.translatable("command.starmine.upgrades_shields", unit.weaponsUpgrade(), unit.kind.armor() + unit.armorUpgrade(), unit.shieldsUpgrade())
					: Component.translatable("command.starmine.upgrades", unit.weaponsUpgrade(), unit.kind.armor() + unit.armorUpgrade()),
					panelLeft + 6, panelTop + 44, 0xFFD0D0D0);
			if (unit.kind.structure() && CommandView.own(unit)) {
				int progress = unit.progress();
				String queue = unit.queueIds();
				if (queue.equals("construct") || queue.equals("morph")) { // owner 18.10: an upgrade's progress where a construction's is
					graphics.drawString(font, Component.translatable(queue.equals("morph") ? "command.starmine.morphing" : "command.starmine.constructing"),
						panelLeft + 6, panelTop + 40, 0xFFFFD040);
					bar(graphics, panelLeft + 6, panelTop + 52, panelWidth - 12, progress / 1000f, 0xFFFFD040);
				} else if (!queue.isEmpty()) {
					int x = panelLeft + 6;
					for (String id : queue.split(",")) {
						if (id.startsWith("research:")) {
							graphics.drawString(font, Component.translatable("command.starmine." + id.substring(9)), x, panelTop + 44, 0xFFFFD040);
							continue;
						}
						UnitKind kind = byId(id);
						if (kind != null) graphics.renderItem(new ItemStack(StarMine.egg(kind)), x, panelTop + 40);
						x += 20;
					}
					bar(graphics, panelLeft + 6, panelTop + 60, panelWidth - 12, Math.abs(progress) / 1000f, 0xFF60A0FF);
				}
			}
			List<Entity> riders = CommandView.own(unit) ? unit.getPassengers() : List.of();
			for (int i = 0; i < riders.size() && i < CARGO_SHOWN; i++) // its cargo (SC2): a left click on one lets it out alone
				if (riders.get(i) instanceof StarUnit rider) {
					int[] at = cargoCell(i);
					graphics.fill(at[0], at[1], at[0] + 18, at[1] + 18, 0x60406080);
					graphics.renderItem(new ItemStack(StarMine.egg(rider.kind)), at[0] + 1, at[1] + 1);
				}
			return;
		}
		int columns = 12, size = 22;
		for (int i = 0; i < units.size() && i < 36; i++) {
			StarUnit unit = units.get(i);
			int x = panelLeft + 4 + (i % columns) * size, y = panelTop + 4 + (i / columns) * size;
			float health = unit.getHealth() / unit.getMaxHealth();
			graphics.fill(x, y, x + size - 2, y + size - 2, health > 0.6f ? 0x6020A020 : health > 0.3f ? 0x60A0A020 : 0x60A02020);
			graphics.renderItem(new ItemStack(StarMine.egg(unit.kind)), x + 2, y + 2);
		}
	}

	/** How many riders of a transport its panel shows (two rows). */
	private static final int CARGO_SHOWN = 26;

	/** Where the `i`th rider's icon is in the selection panel: two rows of 13 under the transport's bars. */
	private int[] cargoCell(int i) {
		int panelLeft = width / 2 - 140, panelTop = height - 3 * (CELL + GAP) - 4;
		return new int[] {panelLeft + 6 + (i % 13) * 20, panelTop + 70 + (i / 13) * 18};
	}

	/** The rider of the one selected transport whose icon is under the mouse, or null. */
	private Entity cargoAt(double mouseX, double mouseY) {
		List<StarUnit> units = CommandView.selected();
		if (units.size() != 1 || !CommandView.own(units.get(0))) return null;
		List<Entity> riders = units.get(0).getPassengers();
		for (int i = 0; i < riders.size() && i < CARGO_SHOWN; i++) {
			int[] at = cargoCell(i);
			if (mouseX >= at[0] && mouseX < at[0] + 18 && mouseY >= at[1] && mouseY < at[1] + 18) return riders.get(i);
		}
		return null;
	}

	private static void bar(GuiGraphics graphics, int x, int y, int width, float fraction, int color) {
		graphics.fill(x, y, x + width, y + 5, 0xFF202020);
		graphics.fill(x, y, x + (int) (width * Math.max(0, Math.min(1, fraction))), y + 5, color);
	}

	private static UnitKind byId(String id) {
		for (UnitKind kind : UnitKind.values()) if (kind.id.equals(id)) return kind;
		return null;
	}

	// ---- the command card ----

	/**
	 * The command card for the selection, for every race (owner 14.10): units get move, stop, hold,
	 * patrol, attack; workers also gather, return and build (the race's buildings,
	 * {@link dev.starmine.race.RaceRules#buildings}, 10 on the first page, the rest on the second);
	 * units that morph (zergling, roach, hydralisk, corruptor, overlord) get their morphs;
	 * producers (a building, zerg larvae) train what they make ({@link UnitKind#trains}) with its
	 * SC2 hotkey, research what they research ({@link Research}) and set a rally point.
	 */
	/** The card as shown now, for the harness's check against SC2's card layouts: slot, SC2 button, hotkey per line. */
	String card() {
		StringBuilder out = new StringBuilder();
		for (Button button : buttons())
			out.append(button.slot()).append('\t').append(button.icon() == null && button.kind() != null ? "@" + button.kind().name() : button.icon()).append('\t').append(button.key() > 0 && button.key() < 0x10000 ? String.valueOf((char) button.key()) : "")
				.append('\n');
		return out.toString();
	}

	/** SC2's slot of a button on a kind's card ("KIND/Button"), from VoidMulti's CardLayouts (assets/starmine/sc2_cards.tsv). */
	private static final Map<String, Integer> SC2_SLOTS = sc2Slots();

	private static Map<String, Integer> sc2Slots() {
		Map<String, Integer> slots = new HashMap<>();
		try (var in = CommandScreen.class.getResourceAsStream("/assets/starmine/sc2_cards.tsv")) {
			if (in == null) return slots;
			for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
				String[] cells = line.trim().split("\t");
				if (cells.length >= 3 && !cells[0].equals("kind")) slots.putIfAbsent(cells[0] + "/" + cells[2], Integer.parseInt(cells[1]));
			}
		} catch (java.io.IOException | NumberFormatException e) {
			return Map.of();
		}
		return slots;
	}

	/**
	 * The card: its buttons in the slots SC2 gives them on this kind's card (a barracks' marine, reaper, marauder, ghost
	 * on the top row, its rally at the end of the middle row…); buttons SC2 has not keep their slot, or the first free one.
	 */
	private List<Button> buttons() {
		List<Button> buttons = cardButtons();
		List<StarUnit> own = CommandView.selected().stream().filter(CommandView::own).toList();
		if (page != Page.MAIN || CommandView.heroSelected() || own.isEmpty()) return buttons;
		String kind = own.get(0).kind.name();
		List<Button> placed = new ArrayList<>(), rest = new ArrayList<>();
		java.util.Set<Integer> taken = new java.util.HashSet<>();
		for (Button button : buttons) {
			Integer slot = button.icon() != null ? SC2_SLOTS.get(kind + "/" + button.icon())
				: button.kind() != null ? SC2_SLOTS.get(kind + "/@" + button.kind().name()) : null; // a unit it trains or morphs into
			if (slot != null && taken.add(slot)) placed.add(button.at(slot));
			else rest.add(button);
		}
		for (Button button : rest) {
			int slot = button.slot();
			for (int free = 0; taken.contains(slot) && free < 15; free++) slot = free;
			if (taken.add(slot)) placed.add(button.at(slot));
		}
		return placed;
	}

	private List<Button> cardButtons() {
		List<Button> buttons = new ArrayList<>();
		if (CommandView.heroSelected()) return heroButtons();
		List<StarUnit> own = CommandView.selected().stream().filter(CommandView::own).toList();
		if (own.isEmpty()) return buttons;
		UnitKind.Race race = own.get(0).kind.race;
		List<StarUnit> mobile = own.stream().filter(unit -> !unit.kind.structure() && unit.kind != UnitKind.LARVA).toList();
		StarUnit worker = mobile.stream().filter(unit -> unit.kind.worker()).findFirst().orElse(null);
		if (page != Page.MAIN && worker != null) {
			List<UnitKind> all = worker.kind.race.rules().buildings();
			List<UnitKind> kinds = page == Page.BUILD ? all.subList(0, Math.min(10, all.size())) : all.subList(Math.min(10, all.size()), all.size());
			for (int i = 0; i < kinds.size(); i++) {
				UnitKind kind = kinds.get(i);
				buttons.add(unitButton(i, kind, kind.hotkey(), () -> {
					CommandView.placing = kind;
					targeting = null;
				}, false));
			}
			buttons.add(new Button(14, Component.translatable("command.starmine.back"), GLFW.GLFW_KEY_ESCAPE, null, true,
				List.of(Component.translatable("command.starmine.back")), () -> page = Page.MAIN, Sc2Buttons.command("back", race)));
			return buttons;
		}
		if (page == Page.HALLUCINATION && mobile.stream().anyMatch(unit -> unit.kind == UnitKind.SENTRY)) { // owner 17.10: what to hallucinate
			List<UnitKind> kinds = dev.starmine.ability.Ability.HALLUCINATIONS;
			for (int i = 0; i < kinds.size(); i++) {
				UnitKind kind = kinds.get(i);
				char key = dev.starmine.ability.Ability.HALLUCINATION_KEYS.charAt(i);
				buttons.add(new Button(i < 10 ? i : i + 1, name(kind), key, kind, true,
					List.of(Component.translatable("command.starmine.hallucinate", name(kind)).append(" (" + key + ")"),
						Component.translatable("ability.starmine.hallucination.tip").withColor(0xFFC0C0C0)),
					() -> {
						CommandView.send("ability", Vec3.ZERO, -1, dev.starmine.ability.Ability.HALLUCINATION.id + ":" + kind.name());
						page = Page.MAIN;
					}, null));
			}
			buttons.add(new Button(14, Component.translatable("command.starmine.back"), GLFW.GLFW_KEY_ESCAPE, null, true,
				List.of(Component.translatable("command.starmine.back")), () -> page = Page.MAIN, Sc2Buttons.command("back", race)));
			return buttons;
		}
		page = Page.MAIN;
		if (!mobile.isEmpty()) {
			buttons.add(simple(0, "move", race, 'M', () -> targeting = "move"));
			buttons.add(simple(1, "stop", race, 'S', () -> CommandView.send("stop", Vec3.ZERO, -1, null)));
			buttons.add(simple(2, "hold", race, 'H', () -> CommandView.send("hold", Vec3.ZERO, -1, null)));
			buttons.add(simple(3, "patrol", race, 'P', () -> targeting = "patrol"));
			buttons.add(simple(4, "attack", race, 'A', () -> targeting = "attack"));
			if (worker != null) {
				buttons.add(simple(5, "gather", race, 'G', () -> targeting = "gather").autocast("gather"));
				buttons.add(simple(6, "return", race, 'C', () -> CommandView.send("return", Vec3.ZERO, -1, null)));
				buttons.add(simple(10, "build", race, 'B', () -> page = Page.BUILD));
				if (worker.kind.race.rules().buildings().size() > 10) buttons.add(simple(11, "advanced", race, 'V', () -> page = Page.ADVANCED));
			}
			// owner 17.10: flyers go up into the stratosphere, or down to the overworld from it
			if (worker == null && mobile.stream().allMatch(unit -> unit.kind.flying) && Minecraft.getInstance().level != null) {
				boolean up = Minecraft.getInstance().level.dimension() != dev.starmine.world.Stratosphere.KEY;
				Component label = Component.translatable(up ? "command.starmine.ascend" : "command.starmine.descend");
				buttons.add(new Button(5, label, 'Y', null, true, List.of(label.copy().append(" (Y)"),
					Component.translatable(up ? "command.starmine.ascend.tip" : "command.starmine.descend.tip").withColor(0xFFC0C0C0)),
					() -> CommandView.send("sky", Vec3.ZERO, -1, null), null));
			}
			// abilities of the first selected kind (dev.starmine.ability): the bottom row, a worker's beside its gather keys
			List<dev.starmine.ability.Ability> abilities = dev.starmine.ability.Abilities.shown(mobile.get(0));
			int[] slots = worker == null ? new int[] {10, 11, 12, 13} : new int[] {7, 8, 9};
			for (int i = 0; i < abilities.size() && i < slots.length; i++) buttons.add(abilityButton(slots[i], abilities.get(i), mobile));
			if (own.size() == 1) // owner 14.10: play as this unit (dev.starmine.possess)
				buttons.add(new Button(14, Component.translatable("command.starmine.possess"), 'U', null, true,
					List.of(Component.translatable("command.starmine.possess").append(" (U)"), Component.translatable("command.starmine.possess.tip").withColor(0xFFC0C0C0)),
					() -> CommandView.send("possess", Vec3.ZERO, -1, null), null));
			// morphs of the first selected kind (a zergling into a baneling…): every selected one of that kind morphs
			List<UnitKind> morphs = worker != null ? List.of() : mobile.get(0).kind.trains();
			for (int i = 0; i < morphs.size() && i < 3; i++) {
				UnitKind kind = morphs.get(i);
				buttons.add(unitButton(7 + i, kind, kind.hotkey(), () -> CommandView.send("train", Vec3.ZERO, -1, kind.name()), true));
			}
			// owner 18.10: aircraft fly higher or lower (down to a block over the ground: into caves and tunnels)
			if (worker == null && mobile.stream().anyMatch(StarUnit::flying)) {
				int[] free = java.util.stream.IntStream.of(6, 8, 9, 7).filter(slot -> buttons.stream().noneMatch(b -> b.slot == slot)).limit(2).toArray();
				String[] actions = {"altitude_up", "altitude_down"};
				for (int i = 0; i < free.length; i++) {
					int step = i == 0 ? 1 : -1;
					Component label = Component.translatable("command.starmine." + actions[i]);
					buttons.add(new Button(free[i], label, 0, null, true, List.of(label,
						Component.translatable("command.starmine." + actions[i] + ".tip", StarUnit.ALTITUDE_STEP).withColor(0xFFC0C0C0)),
						() -> CommandView.send("altitude", Vec3.ZERO, step, null), null));
				}
			}
			return buttons;
		}
		UnitKind producer = own.get(0).kind;
		List<UnitKind> trains = producer.trains();
		for (int i = 0; i < trains.size() && i < 10; i++) {
			UnitKind kind = trains.get(i);
			buttons.add(unitButton(i, kind, kind.hotkey(), () -> CommandView.send("train", Vec3.ZERO, -1, kind.name()), true));
		}
		List<Research> researches = Research.of(producer);
		for (int i = 0; i < researches.size(); i++) buttons.add(research(trains.size() + i, researches.get(i), producer));
		if (!trains.isEmpty() && producer.structure()) buttons.add(simple(10, "rally", race, 'Y', () -> targeting = "rally"));
		List<dev.starmine.ability.Ability> abilities = dev.starmine.ability.Abilities.shown(own.get(0)); // a building's (an orbital, a nexus…), as it is now
		for (int i = 0; i < abilities.size() && i < 3; i++) buttons.add(abilityButton(11 + i, abilities.get(i), own));
		if (producer.structure())
			buttons.add(new Button(14, Component.translatable("command.starmine.cancel"), 'X', null, true,
				List.of(Component.translatable("command.starmine.cancel")), () -> CommandView.send("cancel", Vec3.ZERO, -1, null), Sc2Buttons.command("cancel", race)));
		return buttons;
	}

	/**
	 * The hero's card (owner 17.10): the five commands of every unit on the top row, then the player's hotbar instead
	 * of abilities — a click puts the item in the right hand (the hero's weapon), a second click uses it.
	 */
	private List<Button> heroButtons() {
		List<Button> buttons = new ArrayList<>();
		UnitKind.Race race = UnitKind.Race.TERRAN;
		buttons.add(simple(0, "move", race, 'M', () -> targeting = "move"));
		buttons.add(simple(1, "stop", race, 'S', HeroPilot::stop));
		buttons.add(simple(2, "hold", race, 'H', HeroPilot::hold));
		buttons.add(simple(3, "patrol", race, 'P', () -> targeting = "patrol"));
		buttons.add(simple(4, "attack", race, 'A', () -> targeting = "attack"));
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return buttons;
		for (int i = 0; i < 9; i++) {
			int slot = i;
			ItemStack stack = mc.player.getInventory().getItem(i);
			List<Component> tooltip = new ArrayList<>();
			tooltip.add(stack.isEmpty() ? Component.translatable("command.starmine.empty_hand") : stack.getHoverName());
			tooltip.add(Component.translatable(mc.player.getInventory().selected == i ? "command.starmine.hand_use" : "command.starmine.hand_take")
				.withColor(0xFFC0C0C0));
			buttons.add(new Button(5 + i, stack.isEmpty() ? Component.literal(String.valueOf(i + 1)) : stack.getHoverName(), 0, null, true, tooltip, () -> HeroPilot.hand(slot), null,
				stack.isEmpty() ? new ItemStack(net.minecraft.world.item.Items.AIR) : stack));
		}
		return buttons;
	}

	/**
	 * An ability button: its name (on/off for toggles), energy cost, cooldown and aim. Plain abilities and
	 * toggles go at once; aimed ones wait for a click on a unit or a place ({@link #target}).
	 */
	private Button abilityButton(int slot, dev.starmine.ability.Ability ability, List<StarUnit> selected) {
		Component label = Component.translatable("ability.starmine." + ability.id);
		List<StarUnit> users = selected.stream().filter(unit -> ability.users().contains(unit.kind)).toList();
		boolean on = users.stream().anyMatch(unit -> dev.starmine.ability.Abilities.on(unit, ability));
		boolean energy = ability.energy == 0 || on || users.stream().anyMatch(unit -> unit.energy() >= ability.energy);
		List<Component> tooltip = new ArrayList<>();
		tooltip.add(label.copy().append(" (" + ability.hotkey + ")" + (on ? " ✔" : "")));
		tooltip.add(Component.translatable("ability.starmine." + ability.id + ".tip").withColor(0xFFC0C0C0));
		if (ability.energy > 0) tooltip.add(Component.translatable("command.starmine.energy_cost", ability.energy).withColor(0xFFC080FF));
		if (ability.cooldownTicks > 0) tooltip.add(Component.translatable("command.starmine.cooldown", ability.cooldownTicks / 20));
		Runnable run = ability == dev.starmine.ability.Ability.HALLUCINATION ? () -> page = Page.HALLUCINATION : switch (ability.target) { // a sentry chooses what
			case NONE, TOGGLE -> () -> CommandView.send("ability", Vec3.ZERO, -1, ability.id);
			case UNIT, POINT -> () -> targeting = "ability:" + ability.id;
		};
		return new Button(slot, label, ability.hotkey, null, energy, tooltip, run, Sc2Buttons.ability(ability, users.isEmpty() ? selected.get(0).kind : users.get(0).kind))
			.autocast(ability.id);
	}

	private static Button simple(int slot, String action, UnitKind.Race race, char key, Runnable run) {
		Component label = Component.translatable("command.starmine." + action);
		return new Button(slot, label, key, null, true, List.of(label.copy().append(" (" + key + ")")), run, Sc2Buttons.command(action, race));
	}

	/** A research button: its next level, cost and what it needs (from the base status). */
	private Button research(int slot, Research what, UnitKind building) {
		ColonyNetwork.BaseStatus status = BaseStatusClient.status;
		int tier = status.counts().getOrDefault("tier", 1);
		int level = what.leveled() ? status.counts().getOrDefault("level:" + what.id(), 0) + 1 : switch (what) {
			case LAIR -> 2;
			case HIVE -> 3;
			default -> 1;
		};
		UnitKind need = switch (what) {
			case WEAPONS, ARMOR, MELEE, VEHICLE_WEAPONS, SHIP_WEAPONS, PLATING, FLYER_ATTACKS, FLYER_CARAPACE, SHIELDS -> level <= 1 ? null : switch (building.race) {
				case TERRAN -> UnitKind.ARMORY;
				case PROTOSS -> UnitKind.TWILIGHT_COUNCIL;
				case ZERG -> null;
			};
			case AIR_WEAPONS, AIR_ARMOR -> level <= 1 ? null : UnitKind.FLEET_BEACON;
			case LAIR -> UnitKind.SPAWNING_POOL;
			case HIVE -> UnitKind.INFESTATION_PIT;
			default -> null;
		};
		boolean done = what == Research.NUKE ? status.counts().getOrDefault("level:nuke", 0) >= Math.max(1, status.counts().getOrDefault(UnitKind.GHOST_ACADEMY.name(), 0))
			: what.once() ? status.counts().getOrDefault("research:" + what.id(), 0) > 0
			: what == Research.LAIR ? tier >= 2 : what == Research.HIVE ? tier >= 3 : level > 3;
		int needTier = switch (what) {
			case CENTRIFUGAL_HOOKS, GLIAL_RECONSTITUTION, TUNNELING_CLAWS -> 2;
			case ADRENAL_GLANDS -> 3;
			default -> 1;
		};
		boolean tech = (need == null || status.counts().getOrDefault(need.name(), 0) > 0) && tier >= needTier
			&& (building.race != UnitKind.Race.ZERG || what == Research.LAIR || what.once() || tier >= level);
		int[] cost = what.cost(level);
		boolean enabled = !done && tech && status.minerals() >= cost[0] && status.vespene() >= cost[1];
		String key = "command.starmine." + (what == Research.SHIELDS ? "shields_upgrade" : what.id()); // command.starmine.shields is the shields readout
		Component label = Component.translatable(key);
		List<Component> tooltip = new ArrayList<>();
		tooltip.add(label.copy().append((what.leveled() ? " " + Math.min(level, 3) : "") + " (" + what.hotkey + ")"));
		tooltip.add(Component.translatable("command.starmine.cost", cost[0], cost[1]));
		tooltip.add(Component.translatable(key + ".tip").withColor(0xFFC0C0C0));
		if (what == Research.NUKE) tooltip.add(Component.translatable("command.starmine.nukes", status.counts().getOrDefault("level:nuke", 0)).withColor(0xFFFF8060));
		if (need != null && status.counts().getOrDefault(need.name(), 0) == 0) tooltip.add(Component.translatable("command.starmine.requires", name(need)).withColor(0xFF6060));
		return new Button(slot, label, what.hotkey, null, enabled, tooltip, () -> CommandView.send("research", Vec3.ZERO, -1, what.id()),
			Sc2Buttons.research(what, building.race, level));
	}

	/** A button for a unit or building: its icon, cost tooltip, greyed when locked or too dear. */
	private Button unitButton(int slot, UnitKind kind, char key, Runnable run, boolean supply) {
		ColonyNetwork.BaseStatus status = BaseStatusClient.status;
		UnitKind.Economy cost = dev.starmine.race.Morph.price(kind); // a morph costs what it adds to its unit
		boolean unlocked = ColonyNetwork.unlocked(status.counts(), kind);
		boolean affordable = status.minerals() >= cost.minerals() && status.vespene() >= cost.vespene()
			&& (!supply || BaseStatusClient.supplyOf(status, kind.race).used() + cost.food() <= BaseStatusClient.supplyOf(status, kind.race).cap());
		List<Component> tooltip = new ArrayList<>();
		tooltip.add(name(kind).copy().append(" (" + key + ")"));
		tooltip.add(Component.translatable("command.starmine.cost", cost.minerals(), cost.vespene()));
		if (cost.food() > 0) tooltip.add(Component.translatable("command.starmine.food", cost.food()));
		tooltip.add(Component.translatable("command.starmine.time", Math.round(cost.trainTicks() / 20.0)));
		if (!unlocked && kind.requires() != null) tooltip.add(Component.translatable("command.starmine.requires", name(kind.requires())).withColor(0xFF6060));
		if (dev.starmine.race.ProtossRace.needsPower(kind)) tooltip.add(Component.translatable("command.starmine.needs_power").withColor(0x60C0FF));
		return new Button(slot, name(kind), key, kind, unlocked && affordable, tooltip, run, null);
	}

	// ---- input ----

	@Override
	public boolean keyPressed(int key, int scanCode, int modifiers) {
		// owner 18.10: a letter of the command card is its command first, whatever else it does (WASD pans, Q E turn
		// the camera only where the card has no such letter; the arrows always pan)
		if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z && (modifiers & GLFW.GLFW_MOD_CONTROL) == 0 && hotkey(key)) return true;
		switch (key) {
			case GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_UP -> up = true;
			case GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_DOWN -> {
				down = true;
				return true;
			}
			case GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_LEFT -> {
				left = true;
				return true;
			}
			case GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_RIGHT -> {
				right = true;
				return true;
			}
			case GLFW.GLFW_KEY_Q -> CommandView.rotate(-1);
			case GLFW.GLFW_KEY_PAGE_UP -> CommandView.climbing = 1;
			case GLFW.GLFW_KEY_PAGE_DOWN -> CommandView.climbing = -1;
			case GLFW.GLFW_KEY_E -> {
				CommandView.rotate(1);
				return true;
			}
			case GLFW.GLFW_KEY_ESCAPE -> {
				if (CommandView.placing != null || targeting != null) {
					CommandView.placing = null;
					targeting = null;
				} else if (page != Page.MAIN) {
					page = Page.MAIN;
				} else if (!CommandView.selection.isEmpty() || CommandView.hero) {
					CommandView.selection.clear();
					CommandView.hero = false;
				} else {
					onClose();
				}
				return true;
			}
			default -> {
			}
		}
		if (key == GLFW.GLFW_KEY_F2) {
			selectArmy();
			return true;
		}
		if (key == GLFW.GLFW_KEY_F4) {
			if (CommandView.heroSelected() && Minecraft.getInstance().player != null)
				CommandView.lookAt(Minecraft.getInstance().player.getX(), Minecraft.getInstance().player.getZ());
			CommandView.selectHero();
			return true;
		}
		if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) {
			int group = key - GLFW.GLFW_KEY_0;
			if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0) GROUPS.put(group, new ArrayList<>(CommandView.selection));
			else if (GROUPS.containsKey(group)) {
				CommandView.selection.clear();
				CommandView.selection.addAll(GROUPS.get(group));
			}
			return true;
		}
		return true;
	}

	/** Runs the enabled card button with this hotkey; true when the card has one (a disabled one swallows the key). */
	private boolean hotkey(int key) {
		boolean found = false;
		for (Button button : buttons()) {
			if (button.key != key) continue;
			if (button.enabled) {
				button.action.run();
				return true;
			}
			found = true;
		}
		return found;
	}

	@Override
	public boolean keyReleased(int key, int scanCode, int modifiers) {
		switch (key) {
			case GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_UP -> up = false;
			case GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_DOWN -> down = false;
			case GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_LEFT -> left = false;
			case GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_RIGHT -> right = false;
			case GLFW.GLFW_KEY_Q -> turnLeft = false;
			case GLFW.GLFW_KEY_PAGE_UP, GLFW.GLFW_KEY_PAGE_DOWN -> CommandView.climbing = 0;
			case GLFW.GLFW_KEY_E -> turnRight = false;
			default -> {
			}
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		CommandView.climb(-scrollY * (2 + CommandView.zoom() * 0.12)); // the wheel away lowers the camera, as zooming in
		return true;
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		if (CommandView.placing != null) CommandView.updateGhost(mouseX, mouseY, width, height);
		StarUnit unit = onPanel(mouseX, mouseY) ? null : CommandView.unit(mouseX, mouseY, width, height);
		CommandView.hovered = unit == null ? null : unit.getId();
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE && mapGrab) { // the map follows the hand: the camera goes the other way, at the map's scale
			double scale = 2.0 * CommandMinimap.RADIUS / CommandMinimap.SIZE;
			CommandView.lookAt(CommandView.targetX - dragX * scale, CommandView.targetZ - dragY * scale);
			return true;
		}
		if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) { // the view is isometric: dragging pans it
			double step = 0.02 + CommandView.zoom() * 0.004;
			double sin = Math.sin(Math.toRadians(CommandView.yaw)), cos = Math.cos(Math.toRadians(CommandView.yaw));
			CommandView.lookAt(CommandView.targetX + (cos * dragX - sin * dragY) * step, CommandView.targetZ + (sin * dragX + cos * dragY) * step);
			return true;
		}
		if ((button == GLFW.GLFW_MOUSE_BUTTON_LEFT ? CommandView.placing == null && targeting == null : button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && gatherPress)
			&& !dragging && Math.abs(mouseX - this.dragX) + Math.abs(mouseY - this.dragY) > 4 && !onPanel(this.dragX, this.dragY)) dragging = true;
		return true;
	}

	/** Whether the selected units that can cast `id` cast it by themselves. */
	private static boolean autocasting(String id) {
		List<StarUnit> able = CommandView.selected().stream().filter(CommandView::own)
			.filter(unit -> id.equals("gather") ? unit.kind.worker() : dev.starmine.ability.Ability.of(unit.kind).stream().anyMatch(a -> a.id.equals(id))).toList();
		return !able.isEmpty() && able.stream().allMatch(unit -> unit.autocasts(id));
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && onCard(mouseX, mouseY)) { // owner 19.10: a right click on a button toggles its autocast
			for (Button b : buttons()) {
				int[] at = cell(b.slot);
				if (mouseX >= at[0] && mouseX < at[0] + CELL && mouseY >= at[1] && mouseY < at[1] + CELL) {
					if (b.auto != null) CommandView.send("autocast", Vec3.ZERO, -1, b.auto);
					break;
				}
			}
			return true;
		}
		if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) { // owner 17.10: held on the minimap, the middle button drags the map
			mapGrab = CommandMinimap.contains(mouseX, mouseY, height);
			return true;
		}
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			int cut = cutAt(mouseX, mouseY);
			if (cut >= 0) {
				this.dragX = mouseX;
				this.dragY = mouseY;
				dragging = false;
				cut(cut);
				return true;
			}
			if (onCard(mouseX, mouseY)) {
				// the release must not select in the world under the card (owner 16.10: it cleared the selection and
				// closed the build page the click had just opened)
				this.dragX = mouseX;
				this.dragY = mouseY;
				dragging = false;
				for (Button b : buttons()) {
					int[] at = cell(b.slot);
					if (mouseX >= at[0] && mouseX < at[0] + CELL && mouseY >= at[1] && mouseY < at[1] + CELL && b.enabled) {
						b.action.run();
						break; // one button a click
					}
				}
				return true;
			}
			if (CommandMinimap.contains(mouseX, mouseY, height)) {
				Vec3 at = CommandMinimap.world(mouseX, mouseY, height);
				CommandView.lookAt(at.x, at.z);
				this.dragX = mouseX;
				this.dragY = mouseY;
				dragging = false;
				return true;
			}
			int group = groupAt(mouseX, mouseY);
			if (group != 0) { // the release must not select in the world under the box
				this.dragX = mouseX;
				this.dragY = mouseY;
				dragging = false;
			}
			if (group == ARMY_GROUP) {
				selectArmy();
				return true;
			}
			if (group == HERO_GROUP) {
				if (CommandView.heroSelected() && Minecraft.getInstance().player != null) // twice: the view goes to them
					CommandView.lookAt(Minecraft.getInstance().player.getX(), Minecraft.getInstance().player.getZ());
				CommandView.selectHero();
				return true;
			}
			if (group > 0) {
				CommandView.hero = false;
				CommandView.selection.clear();
				for (StarUnit unit : members(group)) CommandView.selection.add(unit.getId());
				page = Page.MAIN;
				return true;
			}
			Entity rider = cargoAt(mouseX, mouseY);
			if (rider != null) { // SC2: a click on a rider in the cargo lets it out alone
				CommandView.send("unload_one", Vec3.ZERO, rider.getId(), null);
				return true;
			}
			if (onPanel(mouseX, mouseY)) return true;
			if (CommandView.placing != null || targeting != null) { // owner 18.10: the left button only selects; it drops a waiting command
				CommandView.placing = null;
				targeting = null;
				gatherPress = false;
			}
			this.dragX = mouseX;
			this.dragY = mouseY;
			dragging = false;
			return true;
		}
		// owner 18.10: every action is the right button's — the smart command, and a command waiting for its target (attack,
		// move, patrol, gather, rally, an aimed ability, a building's site); the left button only selects
		if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
			if (CommandMinimap.contains(mouseX, mouseY, height)) { // an order to a point on the minimap
				Vec3 at = CommandMinimap.world(mouseX, mouseY, height);
				Minecraft mc = Minecraft.getInstance();
				if (mc.level != null) {
					double y = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(at.x), (int) Math.floor(at.z));
					boolean buildings = CommandView.selected().stream().filter(CommandView::own).allMatch(u -> u.kind.structure());
					if (targeting != null && !targeting.equals("gather") && !CommandView.heroSelected()) {
						// SC2: a command waiting for its target goes to the minimap too (A, then the map: attack-move there; an aimed
						// ability, a scanner sweep, at that place — owner 18.10)
						Vec3 point = new Vec3(at.x, y, at.z);
						if (targeting.startsWith("ability:")) CommandView.send("ability", point, -1, targeting.substring(8));
						else CommandView.send(targeting.equals("attack") ? "attack_move" : targeting, point, -1, null);
						if (!hasShiftDown()) targeting = null;
					} else if (CommandView.heroSelected()) HeroPilot.move(new Vec3(at.x, y, at.z));
					else CommandView.send(buildings ? "rally" : "move", new Vec3(at.x, y, at.z), -1, null);
				}
				return true;
			}
			if (onPanel(mouseX, mouseY)) return true;
			if (CommandView.placing != null) {
				CommandView.updateGhost(mouseX, mouseY, width, height);
				if (CommandView.ghostSite != null && CommandView.ghostValid) {
					CommandView.send("build", Vec3.atBottomCenterOf(CommandView.ghostSite), -1, CommandView.placing.name());
					if (!hasShiftDown()) {
						CommandView.placing = null;
						page = Page.MAIN;
					}
				}
				return true;
			}
			if ("gather".equals(targeting) && !CommandView.heroSelected()) { // a click mines that block, a box mines the area (owner 17.10)
				gatherPress = true;
				this.dragX = mouseX;
				this.dragY = mouseY;
				dragging = false;
				return true;
			}
			if (targeting != null) {
				target(targeting, mouseX, mouseY);
				if (!hasShiftDown()) targeting = null;
				return true;
			}
			smart(mouseX, mouseY);
			return true;
		}
		return true;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && gatherPress) {
			gatherPress = false;
			if (dragging) {
				BlockHitResult a = CommandView.block(dragX, dragY, width, height), b = CommandView.block(mouseX, mouseY, width, height);
				if (a != null && b != null) {
					net.minecraft.core.BlockPos other = b.getBlockPos();
					CommandView.send("gather_area", Vec3.atCenterOf(a.getBlockPos()), -1, other.getX() + "," + other.getY() + "," + other.getZ());
				}
			} else target("gather", mouseX, mouseY);
			dragging = false;
			if (!hasShiftDown()) targeting = null;
			return true;
		}
		if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || CommandView.placing != null || targeting != null || onPanel(dragX, dragY) && !dragging) return true;
		if (dragging) {
			boxSelect(dragX, dragY, mouseX, mouseY);
			dragging = false;
			return true;
		}
		clickSelect(mouseX, mouseY);
		return true;
	}

	private void clickSelect(double mouseX, double mouseY) {
		StarUnit unit = CommandView.unit(mouseX, mouseY, width, height);
		long now = System.currentTimeMillis();
		boolean twice = now - lastClick < 300;
		lastClick = now;
		CommandView.inspected = null;
		CommandView.inspectedBlock = null;
		CommandView.tumor = null;
		if (unit == null) {
			if (CommandView.heroAt(mouseX, mouseY, width, height)) { // the player's own body: the hero
				CommandView.selectHero();
				return;
			}
			// owner 17.10: a creature (a monster, an animal) or a resource (a crystal, a geyser) is looked at
			net.minecraft.world.entity.LivingEntity creature = CommandView.creature(mouseX, mouseY, width, height);
			if (creature != null) {
				CommandView.inspect(creature.getId(), null);
				return;
			}
			net.minecraft.world.phys.BlockHitResult hit = CommandView.block(mouseX, mouseY, width, height);
			Minecraft mc = Minecraft.getInstance();
			if (hit != null && mc.level != null && CommandView.resource(mc.level.getBlockState(hit.getBlockPos()))) {
				CommandView.inspect(null, hit.getBlockPos());
				return;
			}
			// SC2: the player's own creep tumor is selected; a right click then spreads it there (CreepTumors.direct)
			net.minecraft.core.BlockPos tumor = hit == null || mc.level == null ? null : nearTumor(mc.level, hit.getBlockPos());
			if (tumor != null) {
				CommandView.selection.clear();
				CommandView.tumor = tumor;
				mc.gui.setOverlayMessage(Component.translatable("message.starmine.tumor_selected"), false);
				return;
			}
			// owner 18.10: a click on a geyser's mound (its outlet is one small block on top) selects the geyser
			net.minecraft.core.BlockPos geyser = hit == null || mc.level == null ? null : nearOutlet(mc.level, hit.getBlockPos());
			if (geyser != null) {
				CommandView.inspect(null, geyser);
				return;
			}
			if (!hasShiftDown()) {
				CommandView.selection.clear();
				CommandView.hero = false;
			}
			return;
		}
		CommandView.hero = false;
		if (twice && CommandView.own(unit)) { // every own unit of that kind on the screen
			if (!hasShiftDown()) CommandView.selection.clear();
			for (StarUnit other : onScreen(0, 0, width, height)) if (other.kind == unit.kind) CommandView.selection.add(other.getId());
			return;
		}
		if (hasShiftDown() && CommandView.own(unit)) {
			if (!CommandView.selection.remove(unit.getId())) CommandView.selection.add(unit.getId());
			return;
		}
		CommandView.selection.clear();
		CommandView.selection.add(unit.getId());
		page = Page.MAIN;
	}

	/** The player's own creep tumor at or next to a clicked block, or null. */
	private static net.minecraft.core.BlockPos nearTumor(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos at) {
		var player = Minecraft.getInstance().player;
		for (net.minecraft.core.BlockPos pos : net.minecraft.core.BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(1, 1, 1)))
			if (level.getBlockState(pos).is(dev.starmine.block.StarMineBlocks.CREEP_TUMOR) && player != null
				&& level.getBlockEntity(pos) instanceof dev.starmine.block.CreepTumorBlockEntity tumor && player.getUUID().equals(tumor.owner()))
				return pos.immutable();
		return null;
	}

	/** An outlet within 2 blocks of a clicked block, or null. */
	private static net.minecraft.core.BlockPos nearOutlet(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos at) {
		for (net.minecraft.core.BlockPos pos : net.minecraft.core.BlockPos.betweenClosed(at.offset(-2, -2, -2), at.offset(2, 2, 2)))
			if (dev.starmine.vespene.Vespene.isOutlet(level.getBlockState(pos))) return pos.immutable();
		return null;
	}

	private void boxSelect(double x0, double y0, double x1, double y1) {
		List<StarUnit> inside = onScreen(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1));
		List<StarUnit> units = inside.stream().filter(unit -> !unit.kind.structure()).toList();
		if (units.isEmpty()) units = inside;
		if (!hasShiftDown()) CommandView.selection.clear();
		for (StarUnit unit : units) CommandView.selection.add(unit.getId());
		if (!units.isEmpty()) CommandView.hero = false;
		page = Page.MAIN;
	}

	/** The base's own units whose feet are within a screen rectangle. */
	private List<StarUnit> onScreen(double x0, double y0, double x1, double y1) {
		List<StarUnit> found = new ArrayList<>();
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return found;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof StarUnit unit) || !unit.isAlive() || !CommandView.own(unit)) continue;
			double[] at = CommandView.project(unit.position(), width, height);
			if (at != null && at[0] >= x0 && at[0] <= x1 && at[1] >= y0 && at[1] <= y1) found.add(unit);
		}
		return found;
	}

	/** A targeted command at the clicked point or unit. */
	private void target(String action, double mouseX, double mouseY) {
		if (CommandView.heroSelected()) {
			heroTarget(action, mouseX, mouseY, false);
			return;
		}
		StarUnit unit = CommandView.unit(mouseX, mouseY, width, height);
		BlockHitResult block = CommandView.block(mouseX, mouseY, width, height);
		Vec3 at = unit != null ? unit.position() : block != null ? Vec3.atBottomCenterOf(block.getBlockPos().above()) : null;
		if (at == null) return;
		if (action.startsWith("ability:")) { // an aimed ability: the unit under the cursor, or the place
			CommandView.send("ability", at, unit != null ? unit.getId() : -1, action.substring(8));
			return;
		}
		net.minecraft.world.entity.LivingEntity creature = unit == null ? CommandView.creature(mouseX, mouseY, width, height) : null;
		switch (action) {
			case "attack" -> { // owner 17.10: any creature — a unit of another side, a monster, an animal, a villager
				if (unit != null && !CommandView.own(unit)) CommandView.send("attack", at, unit.getId(), null);
				else if (creature != null) CommandView.send("attack", creature.position(), creature.getId(), null);
				else CommandView.send("attack_move", at, -1, null);
			}
			case "gather" -> {
				if (block != null) CommandView.send("gather", Vec3.atCenterOf(block.getBlockPos()), -1, null);
			}
			default -> CommandView.send(action, at, -1, null);
		}
	}

	/**
	 * Right click: buildings set their rally point; units attack an enemy unit, gather an ore, a vein
	 * outlet or at an own refinery, return cargo at an own command center, otherwise move there.
	 */
	private void smart(double mouseX, double mouseY) {
		if (CommandView.heroSelected()) {
			heroTarget("move", mouseX, mouseY, true);
			return;
		}
		if (CommandView.tumor != null) { // a selected creep tumor spreads to the clicked creep
			BlockHitResult hit = CommandView.block(mouseX, mouseY, width, height);
			if (hit != null) net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.starmine.colony.ColonyNetwork.UnitCommand(new int[0],
				"tumor", hit.getBlockPos().getX(), hit.getBlockPos().getY(), hit.getBlockPos().getZ(), -1,
				CommandView.tumor.getX() + "," + CommandView.tumor.getY() + "," + CommandView.tumor.getZ()));
			CommandView.tumor = null;
			return;
		}
		List<StarUnit> own = CommandView.selected().stream().filter(CommandView::own).toList();
		if (own.isEmpty()) return;
		StarUnit unit = CommandView.unit(mouseX, mouseY, width, height);
		BlockHitResult block = CommandView.block(mouseX, mouseY, width, height);
		Vec3 at = unit != null ? unit.position() : block != null ? Vec3.atBottomCenterOf(block.getBlockPos().above()) : null;
		if (at == null) return;
		if (own.stream().allMatch(u -> u.kind.structure())) {
			CommandView.send("rally", at, -1, null);
			return;
		}
		boolean workers = own.stream().anyMatch(u -> u.kind.worker());
		net.minecraft.world.entity.LivingEntity monster = unit == null ? CommandView.creature(mouseX, mouseY, width, height) : null;
		if (unit != null && !CommandView.own(unit)) { // not the player's: attack it
			CommandView.send("attack", at, unit.getId(), null);
		} else if (monster instanceof net.minecraft.world.entity.monster.Enemy) { // a monster: attack it (owner 17.10)
			CommandView.send("attack", monster.position(), monster.getId(), null);
		} else if (unit != null && workers && unit.kind == UnitKind.COMMAND_CENTER && CommandView.own(unit)) {
			CommandView.send("return", at, -1, null);
		} else if (unit != null && workers && unit.kind.structure() && unit.kind.race == UnitKind.Race.TERRAN && CommandView.own(unit)
				&& "construct".equals(unit.queueIds())) {
			CommandView.send("construct", at, unit.getId(), null); // SC2: an SCV sent to an unfinished building goes on building it
		} else if (unit != null && workers && unit.kind.gas() && CommandView.own(unit)) {
			CommandView.send("gather", unit.position(), -1, null);
		} else if (unit == null && workers && block != null && resource(Minecraft.getInstance().level.getBlockState(block.getBlockPos()))) {
			CommandView.send("gather", Vec3.atCenterOf(block.getBlockPos()), -1, null);
		} else {
			CommandView.send("move", at, -1, null);
		}
	}

	/**
	 * An order to the hero: at a creature (not one of the player's own units) an attack — any creature with the
	 * attack command, a foe with a right click —, else a move, attack-move or patrol to the point.
	 */
	private void heroTarget(String action, double mouseX, double mouseY, boolean smart) {
		net.minecraft.world.entity.LivingEntity creature = CommandView.creature(mouseX, mouseY, width, height);
		BlockHitResult block = CommandView.block(mouseX, mouseY, width, height);
		boolean own = creature instanceof StarUnit unit && CommandView.own(unit);
		boolean foe = creature != null && !own && (creature instanceof net.minecraft.world.entity.monster.Enemy
			|| creature instanceof StarUnit unit && Minecraft.getInstance().player != null && dev.starmine.faction.Faction.hostile(unit, Minecraft.getInstance().player));
		if (creature != null && !own && (action.equals("attack") || smart && foe)) {
			HeroPilot.attack(creature);
			return;
		}
		Vec3 at = creature != null ? creature.position() : block != null ? Vec3.atBottomCenterOf(block.getBlockPos().above()) : null;
		if (at == null) return;
		switch (action) {
			case "attack" -> HeroPilot.attackMove(at);
			case "patrol" -> HeroPilot.patrol(at);
			default -> HeroPilot.move(at);
		}
	}

	/** Ores and vein outlets (what workers gather; the server's resources.json decides what ores yield). */
	private static boolean resource(BlockState state) {
		if (dev.starmine.mineral.Minerals.isCrystal(state)) return true; // owner 17.10: a right click on minerals mines them
		String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		return path.endsWith("_ore") || path.equals("ancient_debris") || dev.starmine.vespene.Vespene.isOutlet(state);
	}

}
