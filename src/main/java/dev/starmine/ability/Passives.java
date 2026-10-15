package dev.starmine.ability;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.faction.Faction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;

/**
 * Passive effects (owner 14.10, docs/ABILITIES.md), hooked in three places for every race:
 * - a hit ({@link #onHit}, {@link #bonus}): SC2 bonus damage against light and armored, the marauder's
 *   concussive shells, the hellbat's fire, the void ray's prismatic alignment;
 * - damage taken ({@link #onHurt}): stasis, a hallucination's double damage, the guardian shield, the
 *   immortal's barrier;
 * - every tick ({@link #tick}): the sentry's guardian shield aura, the shield battery, the carrier's
 *   interceptors, the mothership's cloaking field, the burrowed roach's
 *   regeneration, the oracle's pulsar beam upkeep, lasting channels (graviton beam, caustic spray,
 *   neural parasite) and an SCV's repair.
 */
public final class Passives {
	/** SC2 concussive shells: 50 % slow for 1.07 s. */
	public static final int CONCUSSIVE_TICKS = 21;
	/** SC2 guardian shield: −2 to ranged attacks, radius 4.5. */
	public static final double GUARDIAN_REDUCTION = 2, GUARDIAN_RADIUS = 4.5 * UnitKind.BLOCKS_PER_UNIT;
	/** SC2 barrier: absorbs 100 for 2 s, then ready again after 32 s. */
	public static final double BARRIER = 100;
	public static final int BARRIER_TICKS = 40, BARRIER_COOLDOWN = 32 * 20;
	/** SC2 mothership cloaking field radius 5. */
	public static final double CLOAK_FIELD_RADIUS = 5 * UnitKind.BLOCKS_PER_UNIT + 1;
	/** SC2 burrowed roach (rapid regeneration): 7 hit points a second. */
	public static final double ROACH_REGEN = 7;
	/** SC2 pulsar beam upkeep: 1.96 energy a second. */
	public static final float PULSAR_DRAIN = 1.96f / 20;

	private Passives() {
	}

	/** SC2 bonus damage of the attacker's weapon against the target (Minecraft health), with the void ray's alignment. */
	public static double bonus(StarUnit attacker, LivingEntity target) {
		if (!(target instanceof StarUnit victim)) return 0;
		double bonus = attacker.kind.bonusDamage(victim.kind);
		if (attacker.kind == UnitKind.HELLION && attacker.mode() == Modes.HELLBAT) bonus = victim.kind.light() ? 12 : 0;
		if (attacker.kind == UnitKind.SIEGE_TANK && attacker.mode() == Modes.SIEGED) bonus = victim.kind.armored() ? 30 : 0;
		if (Status.has(attacker, Status.PRISMATIC) && victim.kind.armored()) bonus += 10;
		return bonus * UnitKind.SCALE;
	}

	/** After a hit lands: concussive shells slow, a hellbat sets alight. */
	public static void onHit(StarUnit attacker, LivingEntity target) {
		if (attacker.kind == UnitKind.MARAUDER && dev.starmine.race.Research.has(attacker, dev.starmine.race.Research.CONCUSSIVE_SHELLS))
			Status.apply(target, Status.SLOW, CONCUSSIVE_TICKS);
		if (attacker.kind == UnitKind.HELLION && attacker.mode() == Modes.HELLBAT) target.igniteForSeconds(2);
	}

	/** Damage a unit takes, after its passives (in Minecraft health). */
	public static float onHurt(StarUnit unit, DamageSource source, float amount) {
		if (amount <= 0) return amount;
		if (Status.has(unit, Status.STASIS)) return 0;
		if (Status.has(unit, Status.HALLUCINATION)) amount *= 2;
		if (Status.has(unit, Status.GUARDED) && ranged(unit, source))
			amount = (float) Math.max(0.5 * UnitKind.SCALE, amount - GUARDIAN_REDUCTION * UnitKind.SCALE);
		if (unit.kind == UnitKind.IMMORTAL && source.getEntity() != null) {
			AbilityState state = unit.abilities;
			long now = unit.level().getGameTime();
			if (!Status.has(unit, Status.BARRIER) && now >= state.barrierReadyAt) { // the barrier rises on the first hit
				state.barrier = (float) (BARRIER * UnitKind.SCALE);
				state.barrierReadyAt = now + BARRIER_COOLDOWN;
				Status.apply(unit, Status.BARRIER, BARRIER_TICKS);
			}
			if (Status.has(unit, Status.BARRIER) && state.barrier > 0) {
				float taken = Math.min(state.barrier, amount);
				state.barrier -= taken;
				amount -= taken;
				if (unit.level() instanceof ServerLevel server)
					server.sendParticles(ParticleTypes.ENCHANT, unit.getX(), unit.getY() + unit.getBbHeight() / 2, unit.getZ(), 8,
						unit.getBbWidth() / 2, unit.getBbHeight() / 2, unit.getBbWidth() / 2, 0.2);
			}
		}
		return amount;
	}

	/** A ranged attack: a projectile, or an attacker standing off (guardian shield). */
	private static boolean ranged(StarUnit unit, DamageSource source) {
		if (source.getDirectEntity() instanceof Projectile) return true;
		return source.getEntity() != null && source.getEntity().distanceTo(unit) > 3;
	}

	/** Every tick of a unit (after its states). */
	static void tick(StarUnit unit, ServerLevel level) {
		switch (unit.kind) {
			case SENTRY -> {
				if (Status.has(unit, Status.GUARDIAN) && unit.tickCount % 5 == 0) {
					for (LivingEntity ally : level.getEntitiesOfClass(LivingEntity.class, unit.getBoundingBox().inflate(GUARDIAN_RADIUS),
						other -> other.isAlive() && Faction.allied(unit, other) && other.distanceTo(unit) <= GUARDIAN_RADIUS))
						if (ally instanceof StarUnit friend) Status.apply(friend, Status.GUARDED, 10);
					if (unit.tickCount % 10 == 0)
						level.sendParticles(ParticleTypes.ENCHANT, unit.getX(), unit.getY() + 1, unit.getZ(), 10, GUARDIAN_RADIUS / 2, 0.5,
							GUARDIAN_RADIUS / 2, 0.1);
				}
			}
			case SHIELD_BATTERY -> ProtossAbilities.batteryTick(unit, level);
			case CARRIER -> ProtossAbilities.carrierTick(unit, level);
			case INTERCEPTOR -> ProtossAbilities.interceptorTick(unit, level);
			case MOTHERSHIP -> {
				if (unit.tickCount % 5 == 0)
					for (StarUnit ally : level.getEntitiesOfClass(StarUnit.class, unit.getBoundingBox().inflate(CLOAK_FIELD_RADIUS),
						other -> other != unit && other.isAlive() && !other.kind.structure() && Faction.allied(unit, other)
							&& other.distanceTo(unit) <= CLOAK_FIELD_RADIUS))
						Status.apply(ally, Status.CLOAK_FIELD, 10);
			}
			case ROACH -> {
				if (unit.mode() == Modes.BURROWED && unit.tickCount % 20 == 0 && unit.getHealth() < unit.getMaxHealth())
					unit.heal((float) (ROACH_REGEN * UnitKind.SCALE));
			}
			case ORACLE -> {
				if (unit.mode() == Modes.PULSAR) {
					unit.setEnergy(unit.energy() - PULSAR_DRAIN);
					if (unit.energy() <= 0) Modes.change(unit, Modes.NORMAL, 0);
				}
			}
			default -> {
			}
		}
		if (unit.abilities.channelOn != null) Channels.tick(unit, level);
		if (unit.abilities.repairTarget != null) TerranAbilities.repairTick(unit, level);
	}
}
