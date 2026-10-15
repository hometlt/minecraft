package dev.starmine.entity;

import dev.starmine.suit.SuitItems;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The heroes of the sky ships' boss rooms (CLOUD_TASK_2 stage 8: Kerrigan on the leviathan, Raynor on the
 * Hyperion, Artanis on the mothership): a boss health bar on the screen of every player within
 * {@link #BAR_RANGE} (health and shields together), and their reward when killed — a whole suit of their
 * race ({@link SuitItems}: the rare armor its modules need), 16 shards and 8 diamonds. Their abilities
 * are the ability engine's ({@link dev.starmine.ability.HeroAbilities}).
 */
public final class Heroes {
	public static final double BAR_RANGE = 48;
	private static final Map<Integer, ServerBossEvent> BARS = new HashMap<>();

	private Heroes() {
	}

	/** Every 10 ticks of a hero: its bar's fill and who sees it. */
	static void tick(StarUnit hero, ServerLevel level) {
		if (hero.tickCount % 10 != 0) return;
		ServerBossEvent bar = BARS.computeIfAbsent(hero.getId(), id -> new ServerBossEvent(hero.getDisplayName(), switch (hero.kind.race) {
			case ZERG -> BossEvent.BossBarColor.PURPLE;
			case TERRAN -> BossEvent.BossBarColor.RED;
			case PROTOSS -> BossEvent.BossBarColor.BLUE;
		}, BossEvent.BossBarOverlay.NOTCHED_10));
		float full = (float) (hero.getMaxHealth() + hero.kind.shields);
		bar.setProgress(Math.max(0, Math.min(1, (hero.getHealth() + hero.shield()) / full)));
		for (ServerPlayer player : level.players()) {
			boolean near = player.distanceTo(hero) <= BAR_RANGE && hero.isAlive();
			if (near && !bar.getPlayers().contains(player)) bar.addPlayer(player);
			else if (!near && bar.getPlayers().contains(player)) bar.removePlayer(player);
		}
	}

	/** Whether a hero shows a bar now (tests). */
	public static boolean hasBar(StarUnit hero) {
		return BARS.containsKey(hero.getId());
	}

	/** The hero is gone: so is its bar. */
	static void gone(StarUnit hero) {
		ServerBossEvent bar = BARS.remove(hero.getId());
		if (bar != null) bar.removeAllPlayers();
	}

	/** What a hero leaves when killed. */
	public static List<ItemStack> reward(StarUnit hero) {
		List<ItemStack> loot = new ArrayList<>();
		for (ArmorItem.Type type : SuitItems.TYPES) loot.add(new ItemStack(SuitItems.piece(hero.kind.race, type)));
		loot.add(new ItemStack(SuitItems.SHARDS.get(hero.kind.race), 16));
		loot.add(new ItemStack(Items.DIAMOND, 8));
		return loot;
	}
}
