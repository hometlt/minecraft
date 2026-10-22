package dev.starmine.client;

import dev.starmine.entity.UnitKind;
import dev.starmine.world.Skirmish;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * A skirmish (owner 22.10): from the main menu (beside "Singleplayer"), the pause menu or the skirmish key. The player
 * picks their race, the AI's race and the map: a prepared map (Amphion, a fresh copy of it is opened), a new world
 * made for the match (from the main menu), or one of the dimensions of the world being played (in a game).
 */
public final class SkirmishScreen extends Screen {
	static boolean active;
	/** The map's bounds on the ground while a skirmish goes on (from the server), or null. */
	static int @Nullable [] bounds;
	/** A skirmish to ask for once a world made for it is open (races), or null. */
	static int @Nullable [] pending;
	static int pendingWait;
	private static int own = UnitKind.Race.TERRAN.ordinal(), enemy = UnitKind.Race.ZERG.ordinal(), mapChoice;
	private final Screen parent;

	/** A map one may choose: its label, and a dimension of the world played (null: a world of its own). */
	private record Choice(Component label, @Nullable ResourceKey<Level> dimension, boolean amphion) {
	}

	SkirmishScreen(Screen parent) {
		super(Component.translatable("skirmish.starmine.title"));
		this.parent = parent;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static Component race(int index) {
		return Component.translatable("race.starmine." + UnitKind.Race.values()[index].name().toLowerCase(java.util.Locale.ROOT));
	}

	/**
	 * The maps: Amphion when its template is in the build (it is converted from SC2 and kept out of the public repository);
	 * in a game the world's dimensions (the one the player is in first), else a new world.
	 */
	private List<Choice> choices() {
		List<Choice> list = new ArrayList<>();
		if (SkirmishScreen.class.getResource("/assets/starmine/skirmish/amphion.zip") != null)
			list.add(new Choice(Component.translatable("skirmish.starmine.map_amphion"), null, true));
		if (minecraft.level == null || minecraft.player == null) list.add(new Choice(Component.translatable("skirmish.starmine.map_new"), null, false));
		else {
			ResourceKey<Level> here = minecraft.level.dimension();
			list.add(new Choice(Component.translatable("skirmish.starmine.map_dimension", dimensionName(here)), here, false));
			for (ResourceKey<Level> key : minecraft.player.connection.levels())
				if (key != here) list.add(new Choice(Component.translatable("skirmish.starmine.map_dimension", dimensionName(key)), key, false));
		}
		return list;
	}

	private static Component dimensionName(ResourceKey<Level> key) {
		String id = key.location().toString();
		return switch (id) {
			case "minecraft:overworld" -> Component.translatable("skirmish.starmine.dim_overworld");
			case "minecraft:the_nether" -> Component.translatable("skirmish.starmine.dim_nether");
			case "minecraft:the_end" -> Component.translatable("skirmish.starmine.dim_end");
			default -> Component.literal(key.location().getPath());
		};
	}

	@Override
	protected void init() {
		List<Choice> maps = choices();
		mapChoice = Math.floorMod(mapChoice, maps.size());
		int x = width / 2 - 110, y = height / 2 - 60;
		addRenderableWidget(Button.builder(Component.translatable("skirmish.starmine.player", race(own)), b -> {
			own = (own + 1) % 3;
			b.setMessage(Component.translatable("skirmish.starmine.player", race(own)));
		}).bounds(x, y, 220, 20).build()).active = !active;
		addRenderableWidget(Button.builder(Component.translatable("skirmish.starmine.ai", race(enemy)), b -> {
			enemy = (enemy + 1) % 3;
			b.setMessage(Component.translatable("skirmish.starmine.ai", race(enemy)));
		}).bounds(x, y + 25, 220, 20).build()).active = !active;
		addRenderableWidget(Button.builder(Component.translatable("skirmish.starmine.map_choice", maps.get(mapChoice).label()), b -> {
			mapChoice = (mapChoice + 1) % maps.size();
			b.setMessage(Component.translatable("skirmish.starmine.map_choice", maps.get(mapChoice).label()));
		}).bounds(x, y + 50, 220, 20).build()).active = !active;
		addRenderableWidget(Button.builder(Component.translatable(active ? "skirmish.starmine.leave" : "skirmish.starmine.start"), b -> {
			b.active = false;
			if (active) {
				if (ClientPlayNetworking.canSend(Skirmish.Request.TYPE)) ClientPlayNetworking.send(new Skirmish.Request(own, enemy, true));
				return;
			}
			Choice map = maps.get(Math.floorMod(mapChoice, maps.size()));
			UnitKind.Race player = UnitKind.Race.values()[own], ai = UnitKind.Race.values()[enemy];
			if (map.amphion()) {
				if (minecraft.level != null) { // the game being played is saved and left first
					minecraft.level.disconnect();
					minecraft.disconnect(new net.minecraft.client.gui.screens.GenericMessageScreen(Component.translatable("menu.savingLevel")));
				}
				SkirmishWorlds.open(minecraft, player, ai);
			} else if (map.dimension() == null) SkirmishWorlds.fresh(minecraft, player, ai);
			else if (ClientPlayNetworking.canSend(Skirmish.Request.TYPE))
				ClientPlayNetworking.send(new Skirmish.Request(own, enemy, false, map.dimension().location().toString(), false));
		}).bounds(x, y + 80, 220, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(x, y + 105, 220, 20).build());
	}

	@Override
	public void onClose() {
		minecraft.setScreen(active ? new CommandScreen() : parent);
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float dt) {
		super.render(g, mx, my, dt);
		g.drawCenteredString(font, title, width / 2, height / 2 - 85, 0xFFFFFF);
		g.drawCenteredString(font, Component.translatable("skirmish.starmine.map_hint"), width / 2, height / 2 + 50, 0xBBBBBB);
	}

	/** Client tick: a world made for a skirmish is open, the player in it: the skirmish is asked for (once). */
	static void tick(net.minecraft.client.Minecraft mc) {
		if (pending == null || mc.player == null || mc.level == null || active) return;
		if (++pendingWait < 60 || !ClientPlayNetworking.canSend(Skirmish.Request.TYPE)) return; // the world settles first
		ClientPlayNetworking.send(new Skirmish.Request(pending[0], pending[1], false, "", true));
		pending = null;
		pendingWait = 0;
	}
}
