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
 * race ({@link SuitItems}: the rare armor its modules need) with the hero's own helmet ({@link dev.starmine.suit.HeroHelmets}),
 * 16 shards and 8 diamonds — left when Amun's hold on it breaks ({@link dev.starmine.world.HeroLiberation}); its part of the
 * artifact's key stands by it on its ship. Their abilities
 * are the ability engine's ({@link dev.starmine.ability.HeroAbilities}).
 */
public final class Heroes {
	public static final double BAR_RANGE = 48;
	private static final Map<Integer, ServerBossEvent> BARS = new HashMap<>();

	private Heroes() {
	}

	/** Every 10 ticks of a hero: its bar's fill and who sees it. */
	static void tick(StarUnit hero, ServerLevel level) {
		dev.starmine.world.HeroLiberation.tick(hero, level); // owner 17.10: Amun's hold, the arena
		if (hero.kind.dimensionHero()) dev.starmine.world.DimensionHeroes.tick(hero, level); // the Nether's and the End's heroes' ways
		if (hero.kind.guardian() && !dev.starmine.world.HeroLiberation.freed(hero) && hero.isAlive())
			dev.starmine.ability.GuardianFights.tick(hero, level); // owner 19.10: Karax's and Swann's own fights
		if (shipHero(hero.kind) && !dev.starmine.world.HeroLiberation.freed(hero) && hero.isAlive())
			dev.starmine.ability.HeroTactics.tick(hero, level); // owner 19.10: the cleverest bosses dodge, flank and fall back
		if (hero.tickCount % 10 != 0 || dev.starmine.world.HeroLiberation.freed(hero)) return;
		ServerBossEvent bar = BARS.computeIfAbsent(hero.getId(), id -> new ServerBossEvent(hero.getDisplayName(), switch (hero.kind.race) {
			case ZERG -> BossEvent.BossBarColor.PURPLE;
			case TERRAN -> BossEvent.BossBarColor.RED;
			case PROTOSS -> BossEvent.BossBarColor.BLUE;
		}, BossEvent.BossBarOverlay.NOTCHED_10));
		float full = (float) (hero.getMaxHealth() + hero.kind.shields);
		float left = Math.max(0, Math.min(1, (hero.getHealth() + hero.shield()) / full));
		bar.setProgress(left);
		phases(hero, level, bar, left);
		for (ServerPlayer player : level.players()) {
			boolean near = player.distanceTo(hero) <= BAR_RANGE && hero.isAlive();
			if (near && !bar.getPlayers().contains(player)) bar.addPlayer(player);
			else if (!near && bar.getPlayers().contains(player)) bar.removePlayer(player);
		}
	}

	/** The share of health and shields at which a hero enters its second and third phase (owner 16.10: bosses with phases). */
	public static final float PHASE_TWO = 0.66f, PHASE_THREE = 0.33f;

	/** A hero's phase now: 1, 2 or 3 (kept on the hero as a tag, so a reload does not replay a phase). */
	public static int phase(StarUnit hero) {
		return hero.getTags().contains("starmine_phase3") ? 3 : hero.getTags().contains("starmine_phase2") ? 2 : 1;
	}

	/**
	 * Phases: at two thirds the hero calls its guard (four of its race appear round it) and its shields come
	 * back; at a third it rages — faster and quicker to strike for the rest of the fight — and calls more.
	 * The bar names the phase; everyone near hears of it. Owner 19.10: a main base's guardian calls no guard — its
	 * phases bring its own weapons ({@link dev.starmine.ability.GuardianFights}) and its base's army comes to it.
	 */
	private static void phases(StarUnit hero, ServerLevel level, ServerBossEvent bar, float left) {
		int phase = phase(hero);
		int next = left <= PHASE_THREE ? 3 : left <= PHASE_TWO ? 2 : 1;
		if (next > phase) {
			hero.addTag(next == 3 ? "starmine_phase3" : "starmine_phase2");
			if (next == 2) hero.setShield((float) hero.kind.shields);
			if (next == 3) dev.starmine.ability.Status.apply(hero, dev.starmine.ability.Status.STIM, 20 * 60 * 10);
			UnitKind[] guard = hero.kind.guardian() ? new UnitKind[0] : shipHero(hero.kind) ? aviation(hero.kind.race, next) : switch (hero.kind.race) {
				case ZERG -> new UnitKind[] {UnitKind.ZERGLING, UnitKind.ZERGLING, UnitKind.HYDRALISK, UnitKind.ROACH};
				case TERRAN -> new UnitKind[] {UnitKind.MARINE, UnitKind.MARINE, UnitKind.MARAUDER, UnitKind.GHOST};
				case PROTOSS -> new UnitKind[] {UnitKind.ZEALOT, UnitKind.ZEALOT, UnitKind.STALKER, UnitKind.HIGH_TEMPLAR};
			};
			for (int i = 0; i < guard.length; i++) {
				double a = i * Math.PI * 2 / guard.length;
				double out = guard[i].flying ? 10 : 4, up = guard[i].flying ? 6 : 0; // flyers come in round the hall, over it
				net.minecraft.world.phys.Vec3 at = Spots.near(level, guard[i], hero.position().add(Math.cos(a) * out, up, Math.sin(a) * out));
				StarUnit add = dev.starmine.hive.HiveLogic.spawnNest(level, null, guard[i], at, hero.blockPosition());
				if (add != null && hero.getTarget() != null) add.setTarget(hero.getTarget());
			}
			level.sendParticles(next == 3 ? net.minecraft.core.particles.ParticleTypes.ANGRY_VILLAGER : net.minecraft.core.particles.ParticleTypes.FLASH,
				hero.getX(), hero.getY() + hero.getBbHeight() * 0.6, hero.getZ(), next == 3 ? 20 : 1, 1, 1, 1, 0);
			for (ServerPlayer player : bar.getPlayers())
				player.displayClientMessage(net.minecraft.network.chat.Component.translatable(hero.kind.guardian() && next == 2 ? "message.starmine.boss_phase.2_alone"
					: shipHero(hero.kind) && next == 2 ? "message.starmine.boss_phase.2_air" : "message.starmine.boss_phase." + next, hero.getDisplayName()), true);
			phase = next;
		}
		bar.setName(phase == 1 ? hero.getDisplayName()
			: net.minecraft.network.chat.Component.translatable("boss.starmine.phase", hero.getDisplayName(), phase));
		if (phase == 3) bar.setColor(BossEvent.BossBarColor.RED);
	}

	/** The heroes of the sky ships (Kerrigan, Raynor, Artanis): the cleverest bosses (owner 19.10). */
	public static boolean shipHero(UnitKind kind) {
		return kind == UnitKind.KERRIGAN || kind == UnitKind.RAYNOR || kind == UnitKind.ARTANIS;
	}

	/**
	 * Owner 19.10: the ship heroes call aircraft, not a guard — at the second phase Kerrigan mutalisks and a corruptor,
	 * Raynor vikings and banshees, Artanis void rays and phoenixes; at the third the heavy ones: brood lords, a
	 * battlecruiser and liberators, a tempest and a carrier.
	 */
	public static UnitKind[] aviation(UnitKind.Race race, int phase) {
		return switch (race) {
			case ZERG -> phase == 2 ? new UnitKind[] {UnitKind.MUTALISK, UnitKind.MUTALISK, UnitKind.MUTALISK, UnitKind.CORRUPTOR}
				: new UnitKind[] {UnitKind.BROOD_LORD, UnitKind.BROOD_LORD, UnitKind.MUTALISK, UnitKind.MUTALISK};
			case TERRAN -> phase == 2 ? new UnitKind[] {UnitKind.VIKING, UnitKind.VIKING, UnitKind.BANSHEE, UnitKind.BANSHEE}
				: new UnitKind[] {UnitKind.BATTLECRUISER, UnitKind.LIBERATOR, UnitKind.LIBERATOR};
			case PROTOSS -> phase == 2 ? new UnitKind[] {UnitKind.VOID_RAY, UnitKind.VOID_RAY, UnitKind.PHOENIX, UnitKind.PHOENIX}
				: new UnitKind[] {UnitKind.TEMPEST, UnitKind.CARRIER};
		};
	}

	/** Whether a hero shows a bar now (tests). */
	public static boolean hasBar(StarUnit hero) {
		return BARS.containsKey(hero.getId());
	}

	/** The hero is gone: so is its bar. */
	public static void gone(StarUnit hero) {
		ServerBossEvent bar = BARS.remove(hero.getId());
		if (bar != null) bar.removeAllPlayers();
		if (hero.kind.guardian()) dev.starmine.ability.GuardianFights.gone(hero);
		dev.starmine.ability.HeroTactics.forget(hero);
	}

	/** What a hero leaves when killed. */
	public static List<ItemStack> reward(StarUnit hero) {
		List<ItemStack> loot = new ArrayList<>();
		if (hero.kind.guardian()) { // a main base's guardian: shards and diamonds; the suits are the ships' heroes'
			loot.add(new ItemStack(SuitItems.SHARDS.get(hero.kind.race), 16));
			loot.add(new ItemStack(Items.DIAMOND, 4));
			return loot;
		}
		for (ArmorItem.Type type : SuitItems.TYPES) // owner 17.10: the hero's own helmet, with its perk
			loot.add(new ItemStack(type == ArmorItem.Type.HELMET ? dev.starmine.suit.HeroHelmets.HELMETS.get(hero.kind.race) : SuitItems.piece(hero.kind.race, type)));
		loot.add(new ItemStack(SuitItems.SHARDS.get(hero.kind.race), 16));
		loot.add(new ItemStack(Items.DIAMOND, 8));
		return loot;
	}
}
