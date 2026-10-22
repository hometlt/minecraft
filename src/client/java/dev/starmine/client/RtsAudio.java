package dev.starmine.client;

import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/** Local SC2 command feedback: one speaker per selection/order, and race music while the view is open. */
final class RtsAudio {
	static SimpleSoundInstance voice, music;
	private static UnitKind.Race race;
	private static String selected = "";
	private static long lastVoice = -100;
	private RtsAudio() { }

	static UnitKind.Race race() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return UnitKind.Race.TERRAN;
		UnitKind pilot = dev.starmine.possess.Pilots.kindOf(mc.player);
		if (pilot != null) return pilot.race;
		UnitKind.Race worn = dev.starmine.suit.Suits.worn(mc.player);
		if (worn != null) return worn;
		for (StarUnit unit : CommandView.selected()) if (CommandView.own(unit)) return unit.kind.race;
		for (UnitKind kind : new UnitKind[] {UnitKind.HATCHERY, UnitKind.COMMAND_CENTER, UnitKind.NEXUS})
			if (BaseStatusClient.status.counts().getOrDefault(kind.name(), 0) > 0) return kind.race;
		return UnitKind.Race.TERRAN;
	}

	static void selection() {
		var units = CommandView.selected();
		selected = selectionKey();
		if (!units.isEmpty()) speak(units.get(0).kind, "select", true);
		else if (CommandView.hero && Minecraft.getInstance().player != null) {
			UnitKind kind = dev.starmine.possess.Pilots.kindOf(Minecraft.getInstance().player);
			if (kind != null) speak(kind, "select", true);
		}
	}

	static void order(String action) {
		var units = CommandView.selected().stream().filter(CommandView::own).toList();
		if (!units.isEmpty()) speak(units.get(0).kind, action.startsWith("attack") ? "attack" : "yes", false);
		else if (CommandView.hero && Minecraft.getInstance().player != null) {
			UnitKind kind = dev.starmine.possess.Pilots.kindOf(Minecraft.getInstance().player);
			if (kind != null) speak(kind, action.startsWith("attack") ? "attack" : "yes", false);
		}
	}

	private static String selectionKey() {
		return (CommandView.hero ? "hero;" : "") + CommandView.selected().stream().map(u -> String.valueOf(u.getId())).collect(java.util.stream.Collectors.joining(","));
	}

	private static void speak(UnitKind kind, String line, boolean selection) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !selection && mc.level.getGameTime() - lastVoice < 4) return;
		ResourceLocation id = StarMine.id(kind.id + "." + line);
		if (mc.getSoundManager().getSoundEvent(id) == null && line.equals("attack")) id = StarMine.id(kind.id + ".yes");
		if (mc.getSoundManager().getSoundEvent(id) == null) id = StarMine.id(kind.id + ".ambient");
		if (mc.getSoundManager().getSoundEvent(id) == null) return;
		if (voice != null) mc.getSoundManager().stop(voice);
		voice = SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(id), 1);
		mc.getSoundManager().play(voice);
		lastVoice = mc.level.getGameTime();
	}

	static void tick(Minecraft mc) {
		if (!CommandView.active()) { stop(mc); return; }
		String now = selectionKey();
		if (!now.equals(selected)) selection();
		UnitKind.Race next = race();
		if (next != race) { if (music != null) mc.getSoundManager().stop(music); music = null; race = next; }
		if (music == null || !mc.getSoundManager().isActive(music)) {
			ResourceLocation id = StarMine.id("music.rts." + race.name().toLowerCase(java.util.Locale.ROOT));
			if (mc.getSoundManager().getSoundEvent(id) != null) {
				mc.getMusicManager().stopPlaying();
				music = SimpleSoundInstance.forMusic(SoundEvent.createVariableRangeEvent(id));
				mc.getSoundManager().play(music);
			}
		}
	}

	private static void stop(Minecraft mc) {
		if (music != null) mc.getSoundManager().stop(music);
		if (voice != null) mc.getSoundManager().stop(voice);
		music = voice = null;
		race = null;
		selected = "";
	}
}
