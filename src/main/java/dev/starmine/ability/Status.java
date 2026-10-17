package dev.starmine.ability;

import dev.starmine.entity.StarUnit;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Timed states of a unit (owner 14.10, docs/ABILITIES.md), in one place for every race: what a spell
 * leaves on a unit for a while — the stimpack, a slow, roots, blindness, the guardian shield, a graviton
 * lift, stasis, a chrono boost on a building… Each has a speed factor, an attack pace factor and
 * whether the unit cannot fight meanwhile; {@link StarUnit#refreshWeapon} turns them into its weapon
 * and speed when one starts or ends. They are kept with the unit's ability state and saved with it
 * (ticks left), so a stimpack outlasts a save.
 *
 * Others than StarMine units get the closest vanilla effect ({@link #apply}): slowness for a slow or
 * roots, levitation for a lift, blindness, glowing for a reveal. A player who plays as a unit gets the
 * state on the unit (its speed and weapon are the player's) and the vanilla effect on the body.
 */
public enum Status {
	//              speed  pace  disarms
	/** Stimpack (marine, marauder): +50 % speed and attack speed, SC2 11 s. */
	STIM(           1.5,   1.5,  false),
	/** Concussive shells, time warp: half speed. */
	SLOW(           0.5,   1,    false),
	/** Fungal growth: cannot move (still fights). */
	ROOTED(         0,     1,    false),
	/** Blinding cloud: ranged weapons reach only up close. */
	BLINDED(        1,     1,    false),
	/** Under a sentry's guardian shield: ranged attacks do 2 less (SC2). */
	GUARDED(        1,     1,    false),
	/** The sentry's guardian shield is up around it (SC2 11 s). */
	GUARDIAN(       1,     1,    false),
	/** Lifted by a graviton beam: floats, neither moves nor fights. */
	LIFTED(         0,     1,    true),
	/** Frozen by a stasis ward: neither moves, fights nor takes damage. */
	STASIS(         0,     1,    true),
	/** Two templar merging into an archon. */
	MERGING(        0,     1,    true),
	/** A phoenix holding a graviton beam, an infestor holding a neural parasite, a corruptor spraying: they only channel. */
	CHANNELLING(    0,     1,    true),
	/** Taken over by a neural parasite: fights for the infestor's side. */
	PARASITE(       1,     1,    false),
	/** The immortal's barrier is up: absorbs damage (SC2 100 for 2 s). */
	BARRIER(        1,     1,    false),
	/** Zealot charge (owner 16.10: a dash, not a jump): three times as fast toward its foe until it strikes. */
	CHARGING(       3.0,   1,    false),
	/** In liquid creep (owner 16.10): a zerg runs four times as fast. */
	CREEP_RUSH(     4.0,   1,    false),
	/** Medivac afterburners: SC2 speed 5.94 for 4 s. */
	AFTERBURNERS(   1.7,   1,    false),
	/** Void ray prismatic alignment: +10 against armored, slower (SC2 14.3 s). */
	PRISMATIC(      0.75,  1,    false),
	/** Revealed by an oracle or a scan: seen by everyone though cloaked or burrowed. */
	REVEALED(       1,     1,    false),
	/** In a mothership's cloaking field: cloaked while it lasts. */
	CLOAK_FIELD(    1,     1,    false),
	/** A building under chrono boost: works 50 % faster (SC2 20 s). */
	CHRONO(         1,     1,    false),
	/** A shield battery overcharged: restores twice as fast for free (SC2 14 s). */
	OVERCHARGE(     1,     1,    false),
	/** A hatchery after an inject: when it ends, 3 more larvae (SC2 29 s). */
	INJECTED(       1,     1,    false),
	/** A summon that vanishes when it ends: a MULE (64 s), a changeling (150 s). */
	TEMPORARY(      1,     1,    false),
	/** A hallucination: deals no damage, takes double, vanishes when it ends (SC2 43 s). */
	HALLUCINATION(  1,     1,    false),
	/** Stunned (Artanis's lightning dash, SC2 2 s): neither moves nor fights. */
	STUNNED(        0,     1,    true),
	/** An adept's shade (owner 17.10, SC2 7 s): fast, untouchable, harmless; its adept comes to it when it ends. */
	SHADE(          1.6,   1,    true),
	/** Under a raven's interference matrix (SC2 11 s): a mechanical or psionic unit neither fights nor casts. */
	INTERFERED(     1,     1,    true),
	/** Hit by a raven's anti-armor missile (SC2 21 s): 3 armor less. */
	ARMOR_BROKEN(   1,     1,    false),
	/** A battlecruiser charging its tactical jump (owner 17.10, SC2 4 s): neither moves nor fights. */
	WARPING(        0,     1,    true);

	public final double speed, pace;
	public final boolean disarms;

	Status(double speed, double pace, boolean disarms) {
		this.speed = speed;
		this.pace = pace;
		this.disarms = disarms;
	}

	/** Whether it changes how the unit moves or fights (its weapon and speed are rebuilt when it starts or ends). */
	boolean physical() {
		return speed != 1 || pace != 1 || disarms || this == BLINDED;
	}

	public static boolean has(StarUnit unit, Status status) {
		return unit.abilities.statuses.containsKey(status);
	}

	/** Ticks left (0: none). */
	public static int left(StarUnit unit, Status status) {
		return unit.abilities.statuses.getOrDefault(status, 0);
	}

	/**
	 * Puts the state on anyone for `ticks` (a longer one already there stays): a StarMine unit keeps it,
	 * a player playing as a unit keeps it on the unit and gets its vanilla effect, others get the effect.
	 */
	public static void apply(LivingEntity entity, Status status, int ticks) {
		StarUnit unit = entity instanceof StarUnit star ? star
			: entity instanceof ServerPlayer player ? dev.starmine.possess.Possession.puppet(player) : null;
		if (unit != null) {
			Integer was = unit.abilities.statuses.get(status);
			if (was != null && was >= ticks) return;
			unit.abilities.statuses.put(status, ticks);
			if (was == null) started(unit, status);
			if (entity instanceof StarUnit) return;
			// a pilot: speed and weapon come from the unit; the body only floats, goes blind or glows
			if (status != LIFTED && status != BLINDED && status != REVEALED) return;
		}
		Holder<MobEffect> effect = vanilla(status);
		if (effect != null) entity.addEffect(new MobEffectInstance(effect, ticks, amplifier(status), false, status != REVEALED));
	}

	/** Ends a state at once. */
	public static void end(StarUnit unit, Status status) {
		if (unit.abilities.statuses.remove(status) != null) ended(unit, status);
	}

	private static @Nullable Holder<MobEffect> vanilla(Status status) {
		return switch (status) {
			case SLOW, ROOTED, STASIS, STUNNED -> MobEffects.MOVEMENT_SLOWDOWN;
			case LIFTED -> MobEffects.LEVITATION;
			case BLINDED -> MobEffects.BLINDNESS;
			case REVEALED -> MobEffects.GLOWING;
			case STIM, AFTERBURNERS, CHARGING -> MobEffects.MOVEMENT_SPEED;
			default -> null;
		};
	}

	private static int amplifier(Status status) {
		return switch (status) {
			case SLOW -> 2; // about half speed
			case ROOTED, STASIS, STUNNED -> 9; // no speed left
			case STIM, AFTERBURNERS -> 2;
			case CHARGING -> 6;
			default -> 0;
		};
	}

	/** The product of the speed factors of its states. */
	public static double speedFactor(StarUnit unit) {
		double factor = 1;
		for (Status status : unit.abilities.statuses.keySet()) factor *= status.speed;
		return factor;
	}

	/** Its weapon as its states leave it: none when disarmed, faster under a stimpack, close range when blinded. */
	public static Modes.Weapon adjust(StarUnit unit, Modes.Weapon weapon) {
		Map<Status, Integer> statuses = unit.abilities.statuses;
		if (statuses.isEmpty()) return weapon;
		double pace = 1, range = weapon.range();
		for (Status status : statuses.keySet()) {
			if (status.disarms) return new Modes.Weapon(0, 0, 0, 0, 0, weapon.mobile());
			pace *= status.pace;
		}
		if (statuses.containsKey(BLINDED) && range > BLINDED_RANGE) range = BLINDED_RANGE;
		return new Modes.Weapon(range, weapon.damage(), Math.max(4, (int) Math.round(weapon.interval() / pace)), weapon.splash(),
			Math.min(weapon.minRange(), range), weapon.mobile());
	}

	/** SC2 blinding cloud: range 1, from edge to edge like weapons ({@link dev.starmine.entity.UnitGoals}). */
	public static final double BLINDED_RANGE = 1 * dev.starmine.entity.UnitKind.BLOCKS_PER_UNIT + 1;

	/** Every tick of a unit: counts its states down; a lifted one floats up; ending ones take their effect away. */
	static void tick(StarUnit unit, ServerLevel level) {
		Map<Status, Integer> statuses = unit.abilities.statuses;
		if (statuses.isEmpty()) return;
		Status[] now = statuses.keySet().toArray(new Status[0]);
		for (Status status : now) {
			int left = statuses.get(status) - 1;
			if (left <= 0) {
				statuses.remove(status);
				ended(unit, status);
			} else statuses.put(status, left);
		}
		if (statuses.containsKey(LIFTED) && unit.pilot() == null) {
			double floor = unit.groundBelow(unit.getX(), unit.getZ()) + LIFT_HEIGHT;
			Vec3 motion = unit.getDeltaMovement();
			unit.setDeltaMovement(motion.x * 0.5, unit.getY() < floor ? 0.08 : -0.02, motion.z * 0.5);
			if (unit.tickCount % 10 == 0)
				level.sendParticles(ParticleTypes.END_ROD, unit.getX(), unit.getY() - 0.3, unit.getZ(), 3, 0.3, 0.2, 0.3, 0.01);
		}
		if (statuses.containsKey(STASIS)) unit.setDeltaMovement(Vec3.ZERO);
		if (statuses.containsKey(CHARGING)) ProtossAbilities.chargeTick(unit, level);
		if (statuses.containsKey(PARASITE) && unit.tickCount % 10 == 0) {
			StarUnit caster = unit.abilities.parasiteCaster == null ? null
				: level.getEntity(unit.abilities.parasiteCaster) instanceof StarUnit found && found.isAlive() ? found : null;
			if (caster == null || !has(caster, CHANNELLING)) end(unit, PARASITE); // the infestor is gone or let go
		}
	}

	/** Blocks above the ground a graviton beam holds its target. */
	public static final double LIFT_HEIGHT = 2.5;

	private static void started(StarUnit unit, Status status) {
		if (status == LIFTED || status == STASIS) {
			unit.setNoGravity(true);
			unit.getNavigation().stop();
			unit.setTarget(null);
		}
		if (status == PARASITE) {
			unit.setTarget(null);
			unit.getNavigation().stop();
		}
		if (status.physical()) unit.refreshWeapon();
	}

	private static void ended(StarUnit unit, Status status) {
		switch (status) {
			case LIFTED, STASIS -> unit.setNoGravity(unit.flying());
			case PARASITE -> {
				unit.abilities.parasiteSide = null;
				unit.abilities.parasiteCaster = null;
				unit.setTarget(null);
			}
			case INJECTED -> ZergAbilities.injectDone(unit);
			case SHADE -> ProtossAbilities.shadeDone(unit);
			case TEMPORARY, HALLUCINATION -> {
				if (unit.pilot() == null) unit.discard();
			}
			case CHANNELLING -> {
				unit.abilities.channelOn = null;
			}
			default -> {
			}
		}
		if (status.physical()) unit.refreshWeapon();
	}
}
