package dev.starmine.client;

import dev.starmine.StarMine;
import dev.starmine.colony.ColonyNetwork;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
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
 *   advanced (V), which open pages of buildings (each placed as a translucent ghost: left click puts
 *   it, right click or Esc cancels). Buildings: the units they train, research at the engineering
 *   bay, rally (Y), cancel (X). Greyed when the tech or the money is missing; the tooltip tells the cost;
 * - bottom middle: what is selected (name, health, construction or training progress and queue).
 *
 * Mouse: left click selects (shift adds, double click takes all of that kind on screen), dragging
 * selects the base's units in a box; right click is the smart command (attack an enemy, gather a tree
 * or ore, return cargo at a command center, otherwise move; for buildings, the rally point). Commands
 * that need a target (move, patrol, attack, gather, rally) take the next left click. Ctrl+1..9 saves a
 * group, 1..9 selects it. Arrows and the screen edges pan (WASD too, unless the letter is a hotkey of
 * the card), the wheel zooms, Q/E turn the isometric view by a quarter, the middle button pans; the
 * minimap (bottom left) moves the view with a left click and sends the selection with a right click. Esc steps back: cancels placing or
 * targeting, leaves a build page, clears the selection, and finally leaves the view.
 */
public final class CommandScreen extends Screen {
	private static final int CELL = 34, GAP = 2;
	private enum Page { MAIN, BUILD, ADVANCED }

	private record Button(int slot, Component label, int key, UnitKind kind, boolean enabled, List<Component> tooltip, Runnable action) {
	}

	private Page page = Page.MAIN;
	/** A command waiting for its target click (move, patrol, attack, gather, rally). */
	private String targeting;
	private double dragX, dragY;
	private boolean dragging;
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
		CommandView.panX = CommandView.panZ = CommandView.turn = 0;
		CommandView.exit(Minecraft.getInstance());
	}

	// ---- drawing ----

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		drawHidden(graphics);
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
			if (button.kind != null) graphics.renderItem(new ItemStack(StarMine.egg(button.kind)), at[0] + 9, at[1] + 5);
			else {
				String text = button.label.getString();
				graphics.drawCenteredString(font, text.length() > 6 ? text.substring(0, 6) : text, at[0] + CELL / 2, at[1] + 13, button.enabled ? 0xFFFFFFFF : 0xFF909090);
			}
			if (button.key > 0) graphics.drawString(font, keyName(button.key), at[0] + 2, at[1] + CELL - 9, 0xFFFFD040);
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
			graphics.drawCenteredString(font, Component.translatable("command.starmine.target", Component.translatable("command.starmine." + targeting)), width / 2, 8, 0xFFFFFF60);
		else if (CommandView.placing != null)
			graphics.drawCenteredString(font, Component.translatable("command.starmine.place", name(CommandView.placing)), width / 2, 8, 0xFFFFFF60);
		if (hover != null) graphics.renderComponentTooltip(font, hover.tooltip, mouseX, mouseY);
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
		return x >= panelLeft && x < width / 2 + 140 && y >= panelTop || onCard(x, y) || y < 140 && x > width - 210
			|| groupAt(x, y) != 0 || CommandMinimap.contains(x, y, height);
	}

	// ---- army: hidden units, groups, the whole army ----

	private static final int GROUP_SIZE = 28, ARMY_GROUP = -1;

	/** Top left of a group's box (1..9) on the bottom left, or of the army button (ARMY_GROUP) above them. */
	private int[] groupBox(int group) {
		if (group == ARMY_GROUP) return new int[] {CommandMinimap.left(), CommandMinimap.top(height) - GROUP_SIZE};
		int panelTop = height - 3 * (CELL + GAP) - 4;
		return new int[] {width / 2 - 140 + (group - 1) * (GROUP_SIZE + 2), panelTop - GROUP_SIZE - 2};
	}

	/** The group (1..9) or ARMY_GROUP under a screen point; 0 for none. */
	private int groupAt(double x, double y) {
		int[] army = groupBox(ARMY_GROUP);
		if (x >= army[0] && x < army[0] + 3 * GROUP_SIZE && y >= army[1] && y < army[1] + GROUP_SIZE - 4) return ARMY_GROUP;
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
	private void drawGroups(GuiGraphics graphics, int mouseX, int mouseY) {
		int[] army = groupBox(ARMY_GROUP);
		int armySize = army().size();
		boolean overArmy = groupAt(mouseX, mouseY) == ARMY_GROUP;
		graphics.fill(army[0], army[1], army[0] + 3 * GROUP_SIZE, army[1] + GROUP_SIZE - 4, overArmy ? 0xE0305080 : 0xC0203048);
		graphics.renderOutline(army[0], army[1], 3 * GROUP_SIZE, GROUP_SIZE - 4, 0xFF6080B0);
		graphics.drawString(font, Component.translatable("command.starmine.army", armySize), army[0] + 4, army[1] + 4, 0xFFFFFFFF);
		graphics.drawString(font, "F2", army[0] + 3 * GROUP_SIZE - 14, army[1] + 14, 0xFFFFD040);
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
	private void drawHidden(GuiGraphics graphics) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof StarUnit unit) || !unit.isAlive() || !CommandView.own(unit) || unit.kind.structure()) continue;
			Vec3 centre = unit.position().add(0, unit.getBbHeight() / 2, 0);
			if (centre.distanceToSqr(eye) > 160 * 160) continue;
			net.minecraft.world.phys.BlockHitResult hit = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, centre,
				net.minecraft.world.level.ClipContext.Block.VISUAL, net.minecraft.world.level.ClipContext.Fluid.NONE, unit));
			if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK || hit.getLocation().distanceTo(centre) < unit.getBbWidth()) continue;
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
		if (units.isEmpty()) return;
		int panelLeft = width / 2 - 140, panelTop = height - 3 * (CELL + GAP) - 4, panelWidth = 280, panelHeight = 3 * (CELL + GAP);
		graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, 0xB0101418);
		if (units.size() == 1) {
			StarUnit unit = units.get(0);
			graphics.renderItem(new ItemStack(StarMine.egg(unit.kind)), panelLeft + 6, panelTop + 6);
			graphics.drawString(font, name(unit.kind), panelLeft + 28, panelTop + 6, CommandView.own(unit) ? 0xFFFFFFFF : 0xFFFF8080);
			int hp = (int) Math.ceil(unit.getHealth()), max = (int) Math.ceil(unit.getMaxHealth());
			graphics.drawString(font, hp + " / " + max, panelLeft + 28, panelTop + 18, 0xFF80FF80);
			bar(graphics, panelLeft + 6, panelTop + 30, panelWidth - 12, unit.getHealth() / unit.getMaxHealth(), 0xFF40C040);
			if (unit.kind.structure() && CommandView.own(unit)) {
				int progress = unit.progress();
				String queue = unit.queueIds();
				if (queue.equals("construct")) {
					graphics.drawString(font, Component.translatable("command.starmine.constructing"), panelLeft + 6, panelTop + 40, 0xFFFFD040);
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

	private static void bar(GuiGraphics graphics, int x, int y, int width, float fraction, int color) {
		graphics.fill(x, y, x + width, y + 5, 0xFF202020);
		graphics.fill(x, y, x + (int) (width * Math.max(0, Math.min(1, fraction))), y + 5, color);
	}

	private static UnitKind byId(String id) {
		for (UnitKind kind : UnitKind.values()) if (kind.id.equals(id)) return kind;
		return null;
	}

	// ---- the command card ----

	private List<Button> buttons() {
		List<Button> buttons = new ArrayList<>();
		List<StarUnit> own = CommandView.selected().stream().filter(CommandView::own).toList();
		if (own.isEmpty()) return buttons;
		List<StarUnit> mobile = own.stream().filter(unit -> !unit.kind.structure()).toList();
		boolean workers = mobile.stream().anyMatch(unit -> unit.kind.worker());
		if (page != Page.MAIN && workers) {
			List<UnitKind> kinds = page == Page.BUILD
				? List.of(UnitKind.COMMAND_CENTER, UnitKind.SUPPLY_DEPOT, UnitKind.BARRACKS, UnitKind.ENGINEERING_BAY, UnitKind.BUNKER, UnitKind.MISSILE_TURRET)
				: List.of(UnitKind.FACTORY, UnitKind.STARPORT, UnitKind.ARMORY, UnitKind.GHOST_ACADEMY, UnitKind.FUSION_CORE);
			for (int i = 0; i < kinds.size(); i++) {
				UnitKind kind = kinds.get(i);
				buttons.add(unitButton(i, kind, buildKey(kind), () -> {
					CommandView.placing = kind;
					targeting = null;
				}, false));
			}
			buttons.add(new Button(14, Component.translatable("command.starmine.back"), GLFW.GLFW_KEY_ESCAPE, null, true,
				List.of(Component.translatable("command.starmine.back")), () -> page = Page.MAIN));
			return buttons;
		}
		page = Page.MAIN;
		if (!mobile.isEmpty()) {
			buttons.add(simple(0, "move", 'M', () -> targeting = "move"));
			buttons.add(simple(1, "stop", 'S', () -> CommandView.send("stop", Vec3.ZERO, -1, null)));
			buttons.add(simple(2, "hold", 'H', () -> CommandView.send("hold", Vec3.ZERO, -1, null)));
			buttons.add(simple(3, "patrol", 'P', () -> targeting = "patrol"));
			buttons.add(simple(4, "attack", 'A', () -> targeting = "attack"));
			if (workers) {
				buttons.add(simple(5, "gather", 'G', () -> targeting = "gather"));
				buttons.add(simple(6, "return", 'C', () -> CommandView.send("return", Vec3.ZERO, -1, null)));
				buttons.add(simple(10, "build", 'B', () -> page = Page.BUILD));
				buttons.add(simple(11, "advanced", 'V', () -> page = Page.ADVANCED));
			}
			return buttons;
		}
		UnitKind building = own.get(0).kind;
		List<UnitKind> trains = building.trains();
		for (int i = 0; i < trains.size(); i++) {
			UnitKind kind = trains.get(i);
			buttons.add(unitButton(i, kind, trainKey(kind), () -> CommandView.send("train", Vec3.ZERO, -1, kind.name()), true));
		}
		if (building == UnitKind.ENGINEERING_BAY) {
			int weapons = BaseStatusClient.status.attackLevel() + 1, armor = BaseStatusClient.status.armorLevel() + 1;
			buttons.add(research(0, "weapons", 'E', weapons));
			buttons.add(research(1, "armor", 'A', armor));
		}
		if (!trains.isEmpty()) buttons.add(simple(10, "rally", 'Y', () -> targeting = "rally"));
		buttons.add(new Button(14, Component.translatable("command.starmine.cancel"), 'X', null, true,
			List.of(Component.translatable("command.starmine.cancel")), () -> CommandView.send("cancel", Vec3.ZERO, -1, null)));
		return buttons;
	}

	private static Button simple(int slot, String action, char key, Runnable run) {
		Component label = Component.translatable("command.starmine." + action);
		return new Button(slot, label, key, null, true, List.of(label.copy().append(" (" + key + ")")), run);
	}

	private Button research(int slot, String what, char key, int level) {
		int cost = StarUnit.researchCost(level);
		ColonyNetwork.BaseStatus status = BaseStatusClient.status;
		boolean armory = status.counts().getOrDefault(UnitKind.ARMORY.name(), 0) > 0;
		boolean enabled = level <= 3 && (level == 1 || armory) && status.minerals() >= cost && status.vespene() >= cost;
		List<Component> tooltip = new ArrayList<>();
		tooltip.add(Component.translatable("command.starmine." + what).append(" " + Math.min(level, 3) + " (" + key + ")"));
		tooltip.add(Component.translatable("command.starmine.cost", cost, cost));
		if (level > 1 && !armory) tooltip.add(Component.translatable("command.starmine.requires", name(UnitKind.ARMORY)).withColor(0xFF6060));
		return new Button(slot, Component.translatable("command.starmine." + what), key, null, enabled, tooltip,
			() -> CommandView.send("research", Vec3.ZERO, -1, what));
	}

	/** A button for a unit or building: its egg icon, cost tooltip, greyed when locked or too dear. */
	private Button unitButton(int slot, UnitKind kind, char key, Runnable run, boolean supply) {
		ColonyNetwork.BaseStatus status = BaseStatusClient.status;
		UnitKind.Economy cost = kind.economy();
		boolean unlocked = ColonyNetwork.unlocked(status.counts(), kind);
		boolean affordable = status.minerals() >= cost.minerals() && status.vespene() >= cost.vespene()
			&& (!supply || status.supplyUsed() + cost.food() <= status.supplyCap());
		List<Component> tooltip = new ArrayList<>();
		tooltip.add(name(kind).copy().append(" (" + key + ")"));
		tooltip.add(Component.translatable("command.starmine.cost", cost.minerals(), cost.vespene()));
		if (cost.food() > 0) tooltip.add(Component.translatable("command.starmine.food", cost.food()));
		tooltip.add(Component.translatable("command.starmine.time", Math.round(cost.trainTicks() / 20.0)));
		if (!unlocked) tooltip.add(Component.translatable("command.starmine.requires", name(kind.requires())).withColor(0xFF6060));
		return new Button(slot, name(kind), key, kind, unlocked && affordable, tooltip, run);
	}

	private static char buildKey(UnitKind kind) {
		return switch (kind) {
			case COMMAND_CENTER -> 'C';
			case SUPPLY_DEPOT -> 'S';
			case BARRACKS -> 'B';
			case ENGINEERING_BAY -> 'E';
			case BUNKER -> 'U';
			case MISSILE_TURRET -> 'T';
			case FACTORY -> 'F';
			case STARPORT -> 'S';
			case ARMORY -> 'A';
			case GHOST_ACADEMY -> 'G';
			case FUSION_CORE -> 'C';
			default -> ' ';
		};
	}

	private static char trainKey(UnitKind kind) {
		return switch (kind) {
			case SCV, SIEGE_TANK -> 'S';
			case MARINE -> 'A';
			case MARAUDER, MEDIVAC -> 'D';
			case REAPER -> 'R';
			case GHOST -> 'G';
			case HELLION, BANSHEE -> 'E';
			case THOR -> 'T';
			case VIKING -> 'V';
			case BATTLECRUISER -> 'B';
			default -> ' ';
		};
	}

	// ---- input ----

	@Override
	public boolean keyPressed(int key, int scanCode, int modifiers) {
		switch (key) {
			case GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_UP -> up = true;
			case GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_DOWN -> {
				if (key == GLFW.GLFW_KEY_DOWN || !press('S')) down = true;
				return true;
			}
			case GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_LEFT -> {
				if (key == GLFW.GLFW_KEY_LEFT || !press('A')) left = true;
				return true;
			}
			case GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_RIGHT -> {
				if (key == GLFW.GLFW_KEY_RIGHT || !press('D')) right = true;
				return true;
			}
			case GLFW.GLFW_KEY_Q -> CommandView.rotate(-1);
			case GLFW.GLFW_KEY_E -> {
				if (!press('E')) CommandView.rotate(1);
				return true;
			}
			case GLFW.GLFW_KEY_ESCAPE -> {
				if (CommandView.placing != null || targeting != null) {
					CommandView.placing = null;
					targeting = null;
				} else if (page != Page.MAIN) {
					page = Page.MAIN;
				} else if (!CommandView.selection.isEmpty()) {
					CommandView.selection.clear();
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
		if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) {
			int group = key - GLFW.GLFW_KEY_0;
			if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0) GROUPS.put(group, new ArrayList<>(CommandView.selection));
			else if (GROUPS.containsKey(group)) {
				CommandView.selection.clear();
				CommandView.selection.addAll(GROUPS.get(group));
			}
			return true;
		}
		if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) press(key);
		return true;
	}

	/** Runs the enabled card button with this hotkey; true when there was one. */
	private boolean press(int key) {
		for (Button button : buttons())
			if (button.key == key && button.enabled) {
				button.action.run();
				return true;
			}
		return false;
	}

	@Override
	public boolean keyReleased(int key, int scanCode, int modifiers) {
		switch (key) {
			case GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_UP -> up = false;
			case GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_DOWN -> down = false;
			case GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_LEFT -> left = false;
			case GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_RIGHT -> right = false;
			case GLFW.GLFW_KEY_Q -> turnLeft = false;
			case GLFW.GLFW_KEY_E -> turnRight = false;
			default -> {
			}
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		CommandView.distance = Math.max(8, Math.min(70, CommandView.distance - scrollY * 3));
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
		if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) { // the view is isometric: dragging pans it
			double step = 0.02 + CommandView.distance * 0.004;
			double sin = Math.sin(Math.toRadians(CommandView.yaw)), cos = Math.cos(Math.toRadians(CommandView.yaw));
			CommandView.lookAt(CommandView.targetX + (cos * dragX - sin * dragY) * step, CommandView.targetZ + (sin * dragX + cos * dragY) * step);
			return true;
		}
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && !dragging && CommandView.placing == null && targeting == null
			&& Math.abs(mouseX - this.dragX) + Math.abs(mouseY - this.dragY) > 4 && !onPanel(this.dragX, this.dragY)) dragging = true;
		return true;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			if (onCard(mouseX, mouseY)) {
				for (Button b : buttons()) {
					int[] at = cell(b.slot);
					if (mouseX >= at[0] && mouseX < at[0] + CELL && mouseY >= at[1] && mouseY < at[1] + CELL && b.enabled) b.action.run();
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
			if (group > 0) {
				CommandView.selection.clear();
				for (StarUnit unit : members(group)) CommandView.selection.add(unit.getId());
				page = Page.MAIN;
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
			if (targeting != null) {
				target(targeting, mouseX, mouseY);
				if (!hasShiftDown()) targeting = null;
				return true;
			}
			this.dragX = mouseX;
			this.dragY = mouseY;
			dragging = false;
			return true;
		}
		if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
			if (CommandView.placing != null || targeting != null) {
				CommandView.placing = null;
				targeting = null;
				return true;
			}
			if (CommandMinimap.contains(mouseX, mouseY, height)) { // an order to a point on the minimap
				Vec3 at = CommandMinimap.world(mouseX, mouseY, height);
				Minecraft mc = Minecraft.getInstance();
				if (mc.level != null) {
					double y = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(at.x), (int) Math.floor(at.z));
					boolean buildings = CommandView.selected().stream().filter(CommandView::own).allMatch(u -> u.kind.structure());
					CommandView.send(buildings ? "rally" : "move", new Vec3(at.x, y, at.z), -1, null);
				}
				return true;
			}
			if (!onPanel(mouseX, mouseY)) smart(mouseX, mouseY);
			return true;
		}
		return true;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
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
		if (unit == null) {
			if (!hasShiftDown()) CommandView.selection.clear();
			return;
		}
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

	private void boxSelect(double x0, double y0, double x1, double y1) {
		List<StarUnit> inside = onScreen(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1));
		List<StarUnit> units = inside.stream().filter(unit -> !unit.kind.structure()).toList();
		if (units.isEmpty()) units = inside;
		if (!hasShiftDown()) CommandView.selection.clear();
		for (StarUnit unit : units) CommandView.selection.add(unit.getId());
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
		StarUnit unit = CommandView.unit(mouseX, mouseY, width, height);
		BlockHitResult block = CommandView.block(mouseX, mouseY, width, height);
		Vec3 at = unit != null ? unit.position() : block != null ? Vec3.atBottomCenterOf(block.getBlockPos().above()) : null;
		if (at == null) return;
		switch (action) {
			case "attack" -> CommandView.send(unit != null && !CommandView.own(unit) ? "attack" : "attack_move", at, unit != null ? unit.getId() : -1, null);
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
		if (unit != null && !CommandView.own(unit) && unit.kind.race != UnitKind.Race.TERRAN) {
			CommandView.send("attack", at, unit.getId(), null);
		} else if (unit != null && workers && unit.kind == UnitKind.COMMAND_CENTER && CommandView.own(unit)) {
			CommandView.send("return", at, -1, null);
		} else if (unit != null && workers && unit.kind.gas() && CommandView.own(unit)) {
			CommandView.send("gather", unit.position(), -1, null);
		} else if (unit == null && workers && block != null && resource(Minecraft.getInstance().level.getBlockState(block.getBlockPos()))) {
			CommandView.send("gather", Vec3.atCenterOf(block.getBlockPos()), -1, null);
		} else {
			CommandView.send("move", at, -1, null);
		}
	}

	/** Ores and vein outlets (what workers gather; the server's resources.json decides what ores yield). */
	private static boolean resource(BlockState state) {
		String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		return path.endsWith("_ore") || path.equals("ancient_debris") || dev.starmine.vespene.Vespene.isOutlet(state);
	}

}
