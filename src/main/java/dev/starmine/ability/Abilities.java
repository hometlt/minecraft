package dev.starmine.ability;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The ability engine (owner 14.10, docs/ABILITIES.md), the same for every race:
 * - energy: casters ({@link UnitKind#maxEnergy}) gain SC2 0.7875 a second up to their maximum;
 * - a cast checks the caster, its energy, cooldown, a mode change under way and the reach, pays the
 *   energy, starts the cooldown, then: at once; after a channel (the caster stands, the spell breaks
 *   if the target dies or gets away); or after a delay ({@link Delayed});
 * - players cast through commands ({@link #order}: toggles and plain abilities on every selected unit
 *   that has them, aimed ones by the nearest able unit — SC2 smart casting); wild units through
 *   {@link AbilityAi}.
 * What each ability does is {@link #effect}.
 */
public final class Abilities {
	/** SC2 energy regeneration a second (0.7875) per tick. */
	public static final float ENERGY_PER_TICK = 0.7875f / 20;

	private Abilities() {
	}

	/** Why `unit` cannot cast `ability` now (a lang key), or null. A toggle that is on can always be turned off. */
	public static @Nullable String refusal(StarUnit unit, Ability ability) {
		if (!ability.users().contains(unit.kind) || !unit.isAlive()) return "message.starmine.no_ability";
		if (unit.abilities.changingMode() || unit.abilities.channelling()) return "message.starmine.busy";
		if (ability.target == Ability.Target.TOGGLE && on(unit, ability)) return null;
		long now = unit.level().getGameTime();
		if (unit.abilities.cooldownLeft(ability, now) > 0) return "message.starmine.cooldown";
		if (unit.energy() < ability.energy) return "message.starmine.need_energy";
		if (ability == Ability.LOAD || ability == Ability.UNLOAD) return Transport.refusal(unit, ability);
		return null;
	}

	/** Whether a toggle is on. */
	public static boolean on(StarUnit unit, Ability ability) {
		return switch (ability) {
			case CLOAK -> unit.cloaked() && !unit.kind.permanentlyCloaked();
			case SIEGE_MODE -> unit.mode() == Modes.SIEGED;
			default -> false;
		};
	}

	/** Casts it (paying and starting the cooldown); false when refused. `target` for unit spells, `point` for point spells. */
	public static boolean cast(StarUnit unit, ServerLevel level, Ability ability, @Nullable LivingEntity target, @Nullable Vec3 point) {
		if (refusal(unit, ability) != null) return false;
		if (ability.target == Ability.Target.UNIT && (target == null || !target.isAlive() || unit.distanceTo(target) > ability.range + target.getBbWidth() / 2))
			return false;
		if (ability.target == Ability.Target.POINT && point == null) return false;
		boolean turningOff = ability.target == Ability.Target.TOGGLE && on(unit, ability);
		if (!turningOff) {
			unit.setEnergy(unit.energy() - ability.energy);
			if (ability.cooldownTicks > 0) unit.abilities.readyAt.put(ability, level.getGameTime() + ability.cooldownTicks);
		}
		if (ability.channelTicks > 0) {
			AbilityState state = unit.abilities;
			state.channel = ability;
			state.channelTarget = target == null ? null : target.getUUID();
			state.channelPoint = point;
			state.channelTicks = ability.channelTicks;
			unit.getNavigation().stop();
			return true;
		}
		if (ability.delayTicks > 0) {
			Delayed.after(level, ability.delayTicks, () -> {
				if (unit.isAlive()) effect(unit, level, ability, target, point);
			});
			warn(level, unit, ability);
			return true;
		}
		effect(unit, level, ability, target, point);
		return true;
	}

	/** What an ability does (when its channel or delay is over). */
	static void effect(StarUnit unit, ServerLevel level, Ability ability, @Nullable LivingEntity target, @Nullable Vec3 point) {
		switch (ability) {
			case CLOAK -> Cloak.toggle(unit);
			case SIEGE_MODE -> Modes.change(unit, unit.mode() == Modes.SIEGED ? Modes.NORMAL : Modes.SIEGED);
			case YAMATO -> TerranAbilities.yamato(unit, level, target);
			case TACTICAL_JUMP -> TerranAbilities.tacticalJump(unit, level, point);
			case LOAD -> Transport.load(unit);
			case UNLOAD -> Transport.unloadAt(unit, point);
		}
	}

	/** A delayed ability's warning where it will happen (a jump's shimmer). */
	private static void warn(ServerLevel level, StarUnit unit, Ability ability) {
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, unit.getX(), unit.getY() + unit.getBbHeight() / 2, unit.getZ(), 40,
			unit.getBbWidth() / 2, unit.getBbHeight() / 2, unit.getBbWidth() / 2, 0.05);
	}

	/** Every tick of a unit: energy, cloak upkeep, the channel, a mode change, a transport's unloading, the AI's spells. */
	public static void tick(StarUnit unit, ServerLevel level) {
		int max = unit.kind.maxEnergy();
		if (max > 0 && unit.energy() < max) unit.setEnergy(Math.min(max, unit.energy() + ENERGY_PER_TICK));
		Cloak.tick(unit, level);
		AbilityState state = unit.abilities;
		if (state.modeTicks > 0 && --state.modeTicks == 0) Modes.finish(unit);
		if (state.channel != null) channel(unit, level, state);
		else if (state.pending != null) approach(unit, level, state);
		if (state.unloadAt != null) Transport.tick(unit, level);
		if (unit.tickCount % 20 == 11 && unit.owner().isEmpty()) AbilityAi.think(unit, level);
	}

	private static void channel(StarUnit unit, ServerLevel level, AbilityState state) {
		Ability ability = state.channel;
		Entity found = state.channelTarget == null ? null : level.getEntity(state.channelTarget);
		LivingEntity target = found instanceof LivingEntity living ? living : null;
		if (ability.target == Ability.Target.UNIT
			&& (target == null || !target.isAlive() || unit.distanceTo(target) > ability.range * 1.5 + target.getBbWidth() / 2)) {
			state.channel = null; // broken: the target is gone (the energy and cooldown are spent, as in SC2)
			return;
		}
		unit.getNavigation().stop();
		if (target != null) unit.getLookControl().setLookAt(target);
		if (unit.tickCount % 4 == 0) {
			Vec3 at = unit.position().add(unit.getLookAngle().scale(unit.getBbWidth() / 2)).add(0, unit.getBbHeight() / 2, 0);
			level.sendParticles(ParticleTypes.FLAME, at.x, at.y, at.z, 6, 0.3, 0.3, 0.3, 0.02);
		}
		if (--state.channelTicks > 0) return;
		state.channel = null;
		effect(unit, level, ability, target, state.channelPoint);
	}

	/**
	 * A player's ability command to the selected `units`: toggles and plain abilities go to every one
	 * that has them; aimed ones to the nearest able one (SC2 smart casting). False when none could.
	 */
	public static boolean order(ServerLevel level, List<StarUnit> units, Ability ability, @Nullable LivingEntity target, @Nullable Vec3 point) {
		List<StarUnit> able = units.stream().filter(unit -> refusal(unit, ability) == null).toList();
		if (able.isEmpty()) return false;
		if (ability.target == Ability.Target.TOGGLE) {
			// all to one state: on unless every one is on already
			boolean allOn = able.stream().allMatch(unit -> on(unit, ability));
			boolean done = false;
			for (StarUnit unit : able) if (on(unit, ability) == allOn) done |= cast(unit, level, ability, target, point);
			return done;
		}
		if (ability.target == Ability.Target.NONE) {
			boolean done = false;
			for (StarUnit unit : able) done |= cast(unit, level, ability, target, point);
			return done;
		}
		Vec3 aim = target != null ? target.position() : point;
		if (aim == null) return false;
		List<StarUnit> nearest = able.stream().sorted(Comparator.comparingDouble(unit -> unit.position().distanceToSqr(aim))).toList();
		if (nearest.stream().anyMatch(unit -> cast(unit, level, ability, target, point))) return true;
		if (ability.target != Ability.Target.UNIT || target == null) return false;
		// out of reach: the nearest goes closer and casts when it can (for 15 s)
		AbilityState state = nearest.get(0).abilities;
		state.pending = ability;
		state.pendingTarget = target.getUUID();
		state.pendingTicks = 300;
		return true;
	}

	/** A spell waiting for its caster to get into reach. */
	private static void approach(StarUnit unit, ServerLevel level, AbilityState state) {
		Entity found = state.pendingTarget == null ? null : level.getEntity(state.pendingTarget);
		if (!(found instanceof LivingEntity target) || !target.isAlive() || --state.pendingTicks <= 0 || refusal(unit, state.pending) != null) {
			state.pending = null;
			return;
		}
		if (cast(unit, level, state.pending, target, null)) {
			state.pending = null;
			return;
		}
		if (unit.kind.flying) unit.getMoveControl().setWantedPosition(target.getX(), target.getY() + unit.kind.hoverHeight(), target.getZ(), 1.0);
		else if (unit.tickCount % 10 == 0) unit.getNavigation().moveTo(target, 1.0);
	}
}
