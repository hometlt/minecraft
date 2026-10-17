package dev.starmine.world;

import dev.starmine.ability.Delayed;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * A scene told in subtitles (owner 17.10: the heroes' words when Amun's hold breaks and when the artifact is whole):
 * one line after another at the bottom of the screen — the speaker's name in their colour, then the line — and in
 * the chat, so it can be read again. Steps of the scene (heroes appearing, armies setting off) run between the lines.
 */
public final class Cutscene {
	/** One beat: a speaker's line (or none) shown for `ticks`, and what happens as it starts. */
	public record Beat(String speaker, String line, int ticks, Runnable action) {
		public static Beat say(String speaker, String line, int ticks) {
			return new Beat(speaker, line, ticks, () -> {
			});
		}

		public static Beat act(int ticks, Runnable action) {
			return new Beat(null, null, ticks, action);
		}
	}

	private Cutscene() {
	}

	private static ChatFormatting colour(String speaker) {
		return switch (speaker) {
			case "kerrigan" -> ChatFormatting.LIGHT_PURPLE;
			case "raynor" -> ChatFormatting.GOLD;
			case "artanis" -> ChatFormatting.AQUA;
			case "amun" -> ChatFormatting.DARK_RED;
			default -> ChatFormatting.GRAY;
		};
	}

	/** Plays the beats to the audience (players by id, so one who left is skipped); `end` after the last. */
	public static void play(ServerLevel level, Collection<ServerPlayer> audience, List<Beat> beats, Runnable end) {
		List<UUID> ids = new ArrayList<>();
		for (ServerPlayer player : audience) ids.add(player.getUUID());
		int at = 0;
		for (Beat beat : beats) {
			Beat now = beat;
			Delayed.after(level, at, () -> {
				now.action().run();
				if (now.line() == null) return;
				Component name = Component.translatable("speaker.starmine." + now.speaker()).withStyle(colour(now.speaker()), ChatFormatting.BOLD);
				Component text = Component.translatable(now.line());
				Component full = Component.empty().append(name).append(Component.literal(": ").withStyle(ChatFormatting.GRAY)).append(text);
				for (UUID id : ids) {
					ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
					if (player == null) continue;
					player.displayClientMessage(full, true); // the subtitle, over the hotbar
					player.sendSystemMessage(full);
					player.playNotifySound(SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.MASTER, 0.4f, 0.7f);
				}
			});
			if (beat.line() != null) // the subtitle stays up as long as the line (an action bar line fades after 3 s)
				for (int again = 50; again < beat.ticks() - 10; again += 50) {
					Beat repeat = beat;
					Delayed.after(level, at + again, () -> {
						Component full = Component.empty().append(Component.translatable("speaker.starmine." + repeat.speaker()).withStyle(colour(repeat.speaker()), ChatFormatting.BOLD))
							.append(Component.literal(": ").withStyle(ChatFormatting.GRAY)).append(Component.translatable(repeat.line()));
						for (UUID id : ids) {
							ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
							if (player != null) player.displayClientMessage(full, true);
						}
					});
				}
			at += beat.ticks();
		}
		Delayed.after(level, at, end);
	}
}
