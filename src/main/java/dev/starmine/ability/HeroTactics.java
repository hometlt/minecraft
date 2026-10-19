package dev.starmine.ability;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The ship heroes' wits (owner 19.10: Kerrigan, Raynor and Artanis are the cleverest bosses). They dodge — a shot or
 * missile coming at them, a player taking aim — with a quick sidestep; the melee ones (Kerrigan, Artanis) come at the
 * player from the side; Raynor keeps his distance; hurt badly in a moment, they fall back a little, then come again.
 *
 * Artanis: his shields come back only out of the fight ({@link StarUnit}); when they are gone he kneels (he crouches,
 * light gathers round him) and bursts in a flash — the foes within {@link #SURGE_RADIUS} blocks thrown back and hurt,
 * his shields whole again — once every {@link #SURGE_EVERY} ticks.
 */
public final class HeroTactics {
	public static final int DODGE_EVERY = 30, FLANK_EVERY = 70, RETREAT_TICKS = 30, RETREAT_EVERY = 160, SURGE_EVERY = 2 * 60 * 20, KNEEL = 30;
	public static final double SURGE_RADIUS = 5;
	public static final float SURGE_DAMAGE = 8, RETREAT_SHARE = 0.06f;

	private static final class State {
		final float[] health = new float[3]; // health + shields a second ago, two seconds ago…
		int dodge, flank = FLANK_EVERY, retreat, retreatCooldown, kneel, surge;
		@Nullable LivingEntity saved;
	}

	private static final Map<UUID, State> STATES = new HashMap<>();

	private HeroTactics() {
	}

	public static void tick(StarUnit hero, ServerLevel level) {
		State state = STATES.computeIfAbsent(hero.getUUID(), id -> new State());
		if (state.dodge > 0) state.dodge--;
		if (state.flank > 0) state.flank--;
		if (state.retreatCooldown > 0) state.retreatCooldown--;
		if (state.surge > 0) state.surge--;
		if (hero.kind == UnitKind.ARTANIS && surge(hero, level, state)) return;
		if (state.retreat > 0) { // falling back: no foe for a moment, then the same one again
			if (--state.retreat == 0 && state.saved != null && state.saved.isAlive()) hero.setTarget(state.saved);
			return;
		}
		LivingEntity foe = hero.getTarget();
		float now = hero.getHealth() + hero.shield();
		if (hero.tickCount % 20 == 0) {
			float lost = state.health[1] - now, full = (float) (hero.getMaxHealth() + hero.kind.shields * UnitKind.SCALE);
			System.arraycopy(state.health, 0, state.health, 1, 2);
			state.health[0] = now;
			if (foe != null && state.retreatCooldown == 0 && lost > full * RETREAT_SHARE && state.health[2] > 0) {
				retreat(hero, foe, state);
				return;
			}
		}
		if (foe == null || !foe.isAlive()) return;
		if (state.dodge == 0 && dodge(hero, level, foe)) state.dodge = DODGE_EVERY;
		double d = hero.distanceTo(foe);
		if (hero.kind == UnitKind.RAYNOR) { // keeps his distance: steps back from one closer than 4
			if (d < 4 && state.flank == 0) {
				dash(hero, hero.position().subtract(foe.position()), 0.9);
				state.flank = FLANK_EVERY / 2;
			}
		} else if (state.flank == 0 && d > 3 && d < 12) { // round to its side, then in
			Vec3 look = foe.getLookAngle().multiply(1, 0, 1);
			if (look.lengthSqr() < 1e-4) look = new Vec3(1, 0, 0);
			Vec3 side = new Vec3(-look.z, 0, look.x).normalize().scale(hero.getRandom().nextBoolean() ? 3 : -3).subtract(look.normalize().scale(1.5));
			dash(hero, foe.position().add(side).subtract(hero.position()), 1.1);
			state.flank = FLANK_EVERY;
		}
	}

	/** A shot coming at it, or a player aiming at it: a quick step aside. */
	static boolean dodge(StarUnit hero, ServerLevel level, LivingEntity foe) {
		Vec3 centre = hero.position().add(0, hero.getBbHeight() / 2, 0);
		for (Projectile shot : level.getEntitiesOfClass(Projectile.class, hero.getBoundingBox().inflate(7), p -> p.isAlive() && p.getOwner() != hero)) {
			Vec3 v = shot.getDeltaMovement();
			Vec3 to = centre.subtract(shot.position());
			if (v.lengthSqr() < 0.01 || v.normalize().dot(to.normalize()) < 0.85) continue;
			dash(hero, new Vec3(-v.z, 0, v.x).scale(hero.getRandom().nextBoolean() ? 1 : -1), 1.0);
			return true;
		}
		if (foe instanceof Player player && player.distanceTo(hero) < 32) {
			Vec3 to = centre.subtract(player.getEyePosition()).normalize();
			boolean aimed = player.getLookAngle().dot(to) > 0.985;
			boolean armed = player.isUsingItem() || player.getMainHandItem().getItem() instanceof dev.starmine.gear.GunItem
				|| player.getMainHandItem().getItem() instanceof dev.starmine.gear.LauncherItem;
			if (aimed && armed && hero.getRandom().nextInt(3) == 0) {
				dash(hero, new Vec3(-to.z, 0, to.x).scale(hero.getRandom().nextBoolean() ? 1 : -1), 0.9);
				return true;
			}
		}
		return false;
	}

	/** A quick push the way `way` points (flat), with a little hop. */
	static void dash(StarUnit hero, Vec3 way, double speed) {
		Vec3 flat = way.multiply(1, 0, 1);
		if (flat.lengthSqr() < 1e-4) return;
		flat = flat.normalize().scale(speed);
		hero.setDeltaMovement(flat.x, Math.max(hero.getDeltaMovement().y, 0.25), flat.z);
		hero.hurtMarked = true;
		if (hero.level() instanceof ServerLevel level)
			level.sendParticles(ParticleTypes.CLOUD, hero.getX(), hero.getY() + 0.3, hero.getZ(), 6, 0.3, 0.1, 0.3, 0.02);
	}

	/** Badly hurt in a moment: it falls back from its foe for a moment (inside its arena), then comes again. */
	static void retreat(StarUnit hero, LivingEntity foe, State state) {
		state.saved = foe;
		state.retreat = RETREAT_TICKS;
		state.retreatCooldown = RETREAT_EVERY;
		hero.setTarget(null);
		Vec3 away = hero.position().subtract(foe.position()).multiply(1, 0, 1);
		if (away.lengthSqr() < 1e-4) away = new Vec3(1, 0, 0);
		Vec3 to = hero.position().add(away.normalize().scale(7));
		BlockPos home = hero.nest();
		if (home != null && to.distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(home)) > dev.starmine.world.HeroLiberation.STAY_RADIUS - 1)
			to = net.minecraft.world.phys.Vec3.atBottomCenterOf(home).lerp(to, 0.5);
		hero.getNavigation().moveTo(to.x, to.y, to.z, 1.4);
		dash(hero, away, 0.8);
	}

	/** Artanis's surge; true while he kneels (nothing else then). */
	static boolean surge(StarUnit hero, ServerLevel level, State state) {
		if (state.kneel > 0) {
			hero.setDeltaMovement(0, hero.getDeltaMovement().y, 0);
			hero.getNavigation().stop();
			level.sendParticles(ParticleTypes.ENCHANT, hero.getX(), hero.getY() + 1, hero.getZ(), 12, 1.2, 1, 1.2, 0.6);
			if (--state.kneel > 0) return true;
			hero.setPose(Pose.STANDING);
			level.sendParticles(ParticleTypes.FLASH, hero.getX(), hero.getY() + 1, hero.getZ(), 1, 0, 0, 0, 0);
			level.sendParticles(ParticleTypes.END_ROD, hero.getX(), hero.getY() + 1, hero.getZ(), 80, SURGE_RADIUS / 2, 0.8, SURGE_RADIUS / 2, 0.3);
			level.playSound(null, hero.blockPosition(), SoundEvents.TRIDENT_THUNDER.value(), SoundSource.HOSTILE, 1.6f, 1.4f);
			for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, hero.getBoundingBox().inflate(SURGE_RADIUS),
				e -> e != hero && dev.starmine.faction.Faction.hostile(hero, e) && e.distanceTo(hero) <= SURGE_RADIUS + 0.5)) {
				other.hurt(hero.attackSource(), SURGE_DAMAGE);
				Vec3 away = other.position().subtract(hero.position()).multiply(1, 0, 1);
				if (away.lengthSqr() > 1e-4) other.push(away.normalize().x * 1.6, 0.5, away.normalize().z * 1.6);
				other.hurtMarked = true;
			}
			hero.setShield((float) hero.maxShield());
			return false;
		}
		if (state.surge > 0 || hero.shield() > 0.5f || hero.getTarget() == null) return false;
		state.kneel = KNEEL;
		state.surge = SURGE_EVERY;
		hero.setPose(Pose.CROUCHING);
		level.playSound(null, hero.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.HOSTILE, 1.5f, 0.7f);
		return true;
	}

	/** Whether Artanis kneels now (tests). */
	public static boolean kneeling(StarUnit hero) {
		State state = STATES.get(hero.getUUID());
		return state != null && state.kneel > 0;
	}

	/** Forgets a hero (freed, gone). */
	public static void forget(StarUnit hero) {
		STATES.remove(hero.getUUID());
	}
}
