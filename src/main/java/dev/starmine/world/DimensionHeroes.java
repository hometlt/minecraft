package dev.starmine.world;

import dev.starmine.StarMine;
import dev.starmine.ability.Abilities;
import dev.starmine.ability.Modes;
import dev.starmine.ability.ZergAbilities;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The heroes of the other worlds (owner 17.10), what they do beyond their weapons and abilities:
 * - Tosh and Zeratul cloak: hidden for {@link #CLOAK_TICKS}, seen for as long, in turns — a detector sees them always;
 * - Zagara throws banelings at her foe, calls hydralisks, and digs: she burrows, runs under the ground and comes up
 *   behind her foe ({@link #dig}), only where the ground carries her there;
 * - Horner, Stukov and Selendis are heroic ships: Horner fires the Yamato cannon, Stukov drops broodling eggs, Selendis
 *   launches interceptors and warps time ({@link dev.starmine.ability.Ability#users}).
 * Freed ({@link HeroLiberation}), they fight the same way for their player.
 */
public final class DimensionHeroes {
	/** A tag: cloaked now (Cloak.forced). */
	public static final String CLOAKED = "starmine_hero_cloak";
	public static final int CLOAK_TICKS = 160;
	/** Zagara: banelings every 6 s, hydralisks every 15 s (at most 8 of hers about), a dig every 12 s. */
	static final int BANELINGS_EVERY = 120, HYDRAS_EVERY = 300, DIG_EVERY = 240, DIG_TICKS = 30, MAX_BROOD = 8;

	private DimensionHeroes() {
	}

	/** Every tick of a hero of the Nether or the End. */
	public static void tick(StarUnit hero, ServerLevel level) {
		if (hero.deathTime > 0 || hero.pilot() != null) return;
		switch (hero.kind) {
			case TOSH, ZERATUL -> {
				if (hero.tickCount % CLOAK_TICKS == 0) {
					boolean hide = !hero.getTags().contains(CLOAKED) && hero.getTarget() != null;
					if (hide) hero.addTag(CLOAKED);
					else hero.removeTag(CLOAKED);
					level.sendParticles(ParticleTypes.REVERSE_PORTAL, hero.getX(), hero.getY() + 1, hero.getZ(), 25, 0.4, 0.8, 0.4, 0.05);
				}
			}
			case ZAGARA -> zagara(hero, level);
			default -> {
			}
		}
	}

	private static void zagara(StarUnit hero, ServerLevel level) {
		LivingEntity foe = hero.getTarget();
		if (foe == null || !foe.isAlive()) {
			if (hero.mode() == Modes.BURROWED && hero.getTags().contains("starmine_digging")) surface(hero, level);
			return;
		}
		int t = hero.tickCount;
		if (t % BANELINGS_EVERY == 0 && brood(hero, level) < MAX_BROOD) for (int i = 0; i < 2; i++) call(hero, level, UnitKind.BANELING, foe, true);
		if (t % HYDRAS_EVERY == 0 && brood(hero, level) < MAX_BROOD) for (int i = 0; i < 2; i++) call(hero, level, UnitKind.HYDRALISK, foe, false);
		if (t % DIG_EVERY == 0 && hero.distanceTo(foe) > 6) dig(hero, level, foe);
		if (hero.getTags().contains("starmine_digging") && --hero.abilities.digTicks <= 0) surface(hero, level);
	}

	/** Her own brood near her (banelings and hydralisks she called). */
	private static int brood(StarUnit hero, ServerLevel level) {
		return level.getEntitiesOfClass(StarUnit.class, hero.getBoundingBox().inflate(32),
			unit -> unit.isAlive() && hero.getUUID().equals(unit.summoner()) && (unit.kind == UnitKind.BANELING || unit.kind == UnitKind.HYDRALISK)).size();
	}

	/** A baneling thrown at the foe (it lands by it and rolls in), or a hydralisk come out of the ground by her. */
	private static void call(StarUnit hero, ServerLevel level, UnitKind kind, LivingEntity foe, boolean thrown) {
		StarUnit unit = StarMine.type(kind).create(level);
		if (unit == null) return;
		double angle = level.random.nextDouble() * Math.PI * 2;
		Vec3 near = thrown ? foe.position().add(Math.cos(angle) * 3, 0, Math.sin(angle) * 3) : hero.position().add(Math.cos(angle) * 2, 0, Math.sin(angle) * 2);
		Vec3 at = Abilities.standable(unit, level, near);
		if (at == null) at = thrown ? hero.position() : near;
		unit.moveTo(at.x, at.y, at.z, level.random.nextFloat() * 360, 0);
		ZergAbilities.summon(hero, unit, level);
		unit.setTarget(foe);
		level.addFreshEntity(unit);
		level.sendParticles(thrown ? ParticleTypes.ITEM_SLIME : ParticleTypes.SQUID_INK, at.x, at.y + 0.5, at.z, 12, 0.3, 0.3, 0.3, 0.05);
		if (thrown) Abilities.beamOf(level, hero.position().add(0, hero.getBbHeight(), 0), at, ParticleTypes.ITEM_SLIME);
	}

	/** She burrows and runs under the ground: she comes up behind her foe {@link #DIG_TICKS} later, where the ground carries her. */
	static void dig(StarUnit hero, ServerLevel level, LivingEntity foe) {
		Vec3 behind = foe.position().add(foe.position().subtract(hero.position()).multiply(1, 0, 1).normalize().scale(4));
		Vec3 at = Abilities.standable(hero, level, behind);
		if (at == null || Math.abs(at.y - foe.getY()) > 3) return; // no ground on the far side: she stays
		hero.setMode(Modes.BURROWED);
		hero.addTag("starmine_digging");
		hero.abilities.digTicks = DIG_TICKS;
		hero.abilities.digTo = at;
		hero.getNavigation().stop();
		level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, hero.getX(), hero.getY() + 0.3, hero.getZ(), 20, 0.5, 0.2, 0.5, 0.02);
		level.playSound(null, hero.blockPosition(), SoundEvents.ROOTED_DIRT_BREAK, SoundSource.HOSTILE, 1.5f, 0.6f);
	}

	private static void surface(StarUnit hero, ServerLevel level) {
		hero.removeTag("starmine_digging");
		Vec3 to = hero.abilities.digTo;
		hero.abilities.digTo = null;
		if (to != null) Abilities.teleport(hero, to);
		hero.setMode(Modes.NORMAL);
		hero.refreshWeapon();
		level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, hero.getX(), hero.getY() + 0.3, hero.getZ(), 30, 0.6, 0.3, 0.6, 0.03);
		level.playSound(null, hero.blockPosition(), SoundEvents.ROOTED_DIRT_BREAK, SoundSource.HOSTILE, 1.5f, 1.0f);
	}
}
