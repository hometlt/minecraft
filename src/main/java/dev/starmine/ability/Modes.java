package dev.starmine.ability;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

import static dev.starmine.entity.UnitKind.BLOCKS_PER_UNIT;
import static dev.starmine.entity.UnitKind.SCALE;

/**
 * Unit modes (owner 14.10, docs/ABILITIES.md): a unit switches its form and weapon, one way for every
 * race — the siege tank's siege mode, burrowing (zerg), the viking's landing, the hellion's hellbat
 * form, the warp prism's phasing mode, the oracle's pulsar beam, the thor's high impact payload, a
 * lowered supply depot, a warp gate, an overlord's ventral sacs, a barracks' tech lab or reactor, an orbital command or planetary
 * fortress, a greater spire, a terran building lifted off ({@link LiftOff}). A change takes its SC2 time ({@link #changeTicks}), during which the unit neither moves nor
 * fights; then its weapon, speed and body follow the new mode ({@link #weapon}, {@link #speedFactor},
 * applied by {@link StarUnit#refreshWeapon} and {@link StarUnit#applyModeBody}). Toggles go back and
 * forth; the add-ons, the orbital command and the planetary fortress are for good ({@link #oneWay}).
 * Each change is drawn (owner 17.10, client `ModeLook`): the model's morph clip when it has one, and its body
 * eases into the look of the new mode (a sieged tank low and wide, a liberator nose down, a phasing prism spread…).
 */
public final class Modes {
	public static final int NORMAL = 0, SIEGED = 1, BURROWED = 2, LANDED = 3, HELLBAT = 4, PHASING = 5, PULSAR = 6, HIGH_IMPACT = 7,
		LOWERED = 8, WARPGATE = 9, TECHLAB = 10, REACTOR = 11, ORBITAL = 12, PLANETARY = 13,
		AIRBORNE = 14, DEFENDER = 15, GREATER = 16, SACS = 17,
		UPROOTED = 18, OVERSIGHT = 19, SURVEILLANCE = 20, CREEPING = 21, CREEPING_SACS = 22;
	/** The last mode (forms' own models are looked up for every mode up to it). */
	public static final int LAST = CREEPING_SACS;
	public static final int CHANGE_TICKS = 60;
	/** The mark of a base that has a greater spire (brood lords need it). */
	public static final String GREATER_SPIRE_ID = "greater_spire";

	/** A weapon in a mode: blocks of reach, damage, ticks between shots, splash radius, the closest it can shoot, whether it can move. */
	public record Weapon(double range, double damage, int interval, double splash, double minRange, boolean mobile) {
	}

	private Modes() {
	}

	/** The mode an ability switches to (-1: it is no mode ability). */
	public static int of(Ability ability) {
		return switch (ability) {
			case SIEGE_MODE -> SIEGED;
			case BURROW -> BURROWED;
			case DEFENDER_MODE -> DEFENDER;
			case PHASING_MODE -> PHASING;
			case PULSAR_BEAM -> PULSAR;
			case HIGH_IMPACT -> HIGH_IMPACT;
			case LOWER_DEPOT -> LOWERED;
			case WARP_GATE -> WARPGATE;
			case BUILD_TECHLAB -> TECHLAB;
			case BUILD_REACTOR -> REACTOR;
			case ORBITAL_COMMAND -> ORBITAL;
			case PLANETARY_FORTRESS -> PLANETARY;
			case GREATER_SPIRE -> GREATER;
			case VENTRAL_SACS -> SACS;
			case LIFT_OFF -> AIRBORNE;
			case UPROOT -> UPROOTED;
			case OVERSIGHT -> OVERSIGHT;
			case SURVEILLANCE -> SURVEILLANCE;
			case GENERATE_CREEP -> CREEPING;
			default -> -1;
		};
	}

	/** The mode `ability` turns `unit` to (an overlord with ventral sacs keeps them while it spreads creep). */
	public static int to(StarUnit unit, Ability ability, boolean turningOff) {
		if (ability == Ability.GENERATE_CREEP) {
			boolean sacs = unit.mode() == SACS || unit.mode() == CREEPING_SACS;
			return turningOff ? (sacs ? SACS : NORMAL) : (sacs ? CREEPING_SACS : CREEPING);
		}
		return turningOff ? NORMAL : of(ability);
	}

	/** Modes that are for good (add-ons, command center upgrades): only from the normal mode, never back. */
	public static boolean oneWay(Ability ability) {
		return ability == Ability.BUILD_TECHLAB || ability == Ability.BUILD_REACTOR || ability == Ability.ORBITAL_COMMAND
			|| ability == Ability.PLANETARY_FORTRESS || ability == Ability.GREATER_SPIRE || ability == Ability.VENTRAL_SACS;
	}

	/** SC2 time of a change to `mode` by `ability`, in ticks (0: at once). */
	public static int changeTicks(Ability ability, int mode) {
		if (oneWay(ability)) return ability.delayTicks;
		return switch (ability) {
			case BURROW -> mode == BURROWED ? 14 : 7; // SC2 burrow 0.7 s, unburrow 0.36 s
			case VIKING_LAND, HELLBAT, HIGH_IMPACT -> 43; // SC2 2.14 s (the first two turn it into another kind, Morphs)
			case PHASING_MODE -> 21; // SC2 1.07 s
			case DEFENDER_MODE -> mode == DEFENDER ? 34 : 24; // SC2 1.71 s down, 1.21 s up
			case LIFT_OFF -> 29; // SC2 lift-off and landing 1.43 s
			case WARP_GATE -> 142; // SC2 7.1 s
			case UPROOT -> mode == UPROOTED ? 29 : 86; // SC2 uproot 1.43 s, root 4.29 s
			case OVERSIGHT, SURVEILLANCE -> 21; // SC2 1.07 s
			case PULSAR_BEAM, LOWER_DEPOT, GENERATE_CREEP -> 0;
			default -> CHANGE_TICKS;
		};
	}

	/** The weapon of a unit in its mode. */
	public static Weapon weapon(UnitKind kind, int mode) {
		Weapon own = new Weapon(kind.range, kind.damage, kind.interval, 0, 0, kind.speed > 0);
		if (kind == UnitKind.SIEGE_TANK && mode == SIEGED) // SC2 crucio shock cannon: 40 (+30 armored), range 13, min 2, 2.14 s, splash
			return new Weapon(13 * BLOCKS_PER_UNIT + 1, Math.round(40 * SCALE * 2) / 2.0, 43, 1.25 * BLOCKS_PER_UNIT,
				2 * BLOCKS_PER_UNIT + 1, false);
		if (kind == UnitKind.LURKER) return mode == BURROWED ? new Weapon(own.range(), own.damage(), own.interval(), 0, 0, false) : none(true);
		if (mode == BURROWED) return none(kind == UnitKind.ROACH); // a burrowed roach crawls, the others lie still
		if (mode == UPROOTED) return none(true); // a crawler on the move does not fight (SC2)
		if (mode == OVERSIGHT || mode == SURVEILLANCE) return none(false); // it stands and looks
		if (kind == UnitKind.LIBERATOR && mode == DEFENDER) // SC2 lancer missiles: 75 at ground units in its zone (range 5 + zone 5), 1.14 s
			return new Weapon(10 * BLOCKS_PER_UNIT + 1, Math.round(75 * SCALE * 2) / 2.0, 23, 0, 0, false);
		if (kind == UnitKind.LIBERATOR) // SC2 lexington rockets: 2 x 5, splash 1.5 round the target (flyers only)
			return new Weapon(own.range(), own.damage(), own.interval(), 1.5 * BLOCKS_PER_UNIT, 0, true);
		if (kind == UnitKind.ORACLE) return mode == PULSAR ? own : none(true); // the pulsar beam is its only weapon
		if (kind == UnitKind.WARP_PRISM && mode == PHASING) return none(false);
		if (kind == UnitKind.COMMAND_CENTER && mode == PLANETARY) // SC2 ibiks cannon: 40, range 6, 1.43 s, splash 1.25
			return new Weapon(6 * BLOCKS_PER_UNIT + 1, Math.round(40 * SCALE * 2) / 2.0, 29, 1.25 * BLOCKS_PER_UNIT, 0, false); // 1.43 s at Faster
		return own;
	}

	/**
	 * A weapon of its own against flyers (SC2, owner 19.10: one weapon for both was a simplification), or null when its
	 * one weapon does both: the thor's javelin missiles (4 x 6, +6 each against light, splash 0.5, range 10, 2.14 s) or in
	 * its other mode the high impact payload (40, range 11, 0.91 s) — its hammers stay for the ground either way; the
	 * queen's acid spines (9, range 7); the battlecruiser's ATA laser battery (5); the tempest's kinetic overload (30,
	 * +22 against massive, range 14).
	 */
	public static @org.jetbrains.annotations.Nullable Weapon airWeapon(UnitKind kind, int mode) {
		Weapon own = new Weapon(kind.range, kind.damage, kind.interval, 0, 0, kind.speed > 0);
		return switch (kind) {
			case THOR -> mode == HIGH_IMPACT ? new Weapon(11 * BLOCKS_PER_UNIT + 1, Math.round(40 * SCALE * 2) / 2.0, 18, 0, 0, true)
				: new Weapon(10 * BLOCKS_PER_UNIT + 1, Math.round(24 * SCALE * 2) / 2.0, 43, 0.5 * BLOCKS_PER_UNIT + 0.5, 0, true);
			case QUEEN -> new Weapon(7 * BLOCKS_PER_UNIT + 1, Math.round(9 * SCALE * 2) / 2.0, own.interval(), 0, 0, true);
			case BATTLECRUISER -> new Weapon(own.range(), Math.round(5 * SCALE * 2) / 2.0, own.interval(), 0, 0, true);
			case TEMPEST -> new Weapon(14 * BLOCKS_PER_UNIT + 1, Math.round(30 * SCALE * 2) / 2.0, own.interval(), 0, 0, true);
			default -> null;
		};
	}

	/** Whether a target counts as flying for weapons (a colossus walks: the ground weapon takes it). */
	public static boolean airborne(LivingEntity target) {
		return target instanceof StarUnit unit ? unit.flying() : target instanceof net.minecraft.world.entity.FlyingMob || target.isFallFlying()
			|| target instanceof net.minecraft.world.entity.monster.Phantom || target instanceof net.minecraft.world.entity.monster.Vex;
	}

	private static Weapon none(boolean mobile) {
		return new Weapon(0, 0, 0, 0, 0, mobile);
	}

	/** Speed in a mode against its own (SC2: a burrowed roach 2.0 against 3.15). */
	public static double speedFactor(UnitKind kind, int mode) {
		if (kind == UnitKind.ROACH && mode == BURROWED) return 2.0 / 3.15;
		return 1;
	}

	/** How much farther a unit sees in a mode (SC2 oversight: 11 to 16.5; surveillance: 11 to 13.75). */
	public static double sightFactor(int mode) {
		return mode == OVERSIGHT ? 1.5 : mode == SURVEILLANCE ? 1.25 : 1;
	}

	/** SC2 hit points a mode adds (a planetary fortress' armor counted as 50 % more). */
	public static double extraHealth(UnitKind kind, int mode) {
		if (kind == UnitKind.COMMAND_CENTER && mode == PLANETARY) return 750;
		return 0;
	}

	/** Starts a change to `mode` taking `ticks` (the unit stands still until it is done); at once with 0. */
	static void change(StarUnit unit, int mode, int ticks) {
		AbilityState state = unit.abilities;
		unit.setTarget(null);
		unit.getNavigation().stop();
		if (unit.level() instanceof ServerLevel server && ticks > 0)
			server.sendParticles(mode == BURROWED || unit.mode() == BURROWED ? ParticleTypes.CAMPFIRE_COSY_SMOKE : ParticleTypes.SMOKE,
				unit.getX(), unit.getY() + 0.5, unit.getZ(), 20, unit.getBbWidth() / 2, 0.4, unit.getBbWidth() / 2, 0.02);
		state.nextMode = mode;
		if (ticks <= 0) {
			finish(unit);
			return;
		}
		state.modeTicks = ticks;
		state.modeTotal = ticks;
		unit.showChange(mode, state.morphTo, ticks); // drawn as a morph (client UnitRenderer)
		unit.applyWeapon(new Weapon(0, 0, 0, 0, 0, false)); // nothing while it changes
	}

	static void finish(StarUnit unit) {
		unit.abilities.modeTicks = 0;
		unit.showChange(0, null, 0);
		if (unit.abilities.morphTo != null) { // a change into another kind (Morphs)
			dev.starmine.entity.UnitKind to = unit.abilities.morphTo;
			unit.abilities.morphTo = null;
			Morphs.into(unit, to);
			return;
		}
		boolean landing = unit.mode() == AIRBORNE && unit.abilities.nextMode != AIRBORNE;
		unit.setMode(unit.abilities.nextMode);
		if (unit.mode() == GREATER) { // its base may morph corruptors into brood lords now
			dev.starmine.hive.Hive base = Abilities.base(unit);
			if (base != null && base.researched.add(GREATER_SPIRE_ID) && unit.level() instanceof ServerLevel server)
				dev.starmine.hive.HiveSavedData.get(server.getServer()).setDirty();
		}
		if (landing) LiftOff.landed(unit, Abilities.base(unit), unit.abilities.liftedFrom);
		unit.applyModeBody();
		unit.refreshWeapon();
	}

	/**
	 * A splash shot (a sieged tank, a planetary fortress' cannon, a thor's javelins, a liberator's rockets): nothing too
	 * close; the target is hit fully, the foes round it on its layer (ground or air, SC2) too — half for a tank's and a
	 * fortress' shells, which fall off with distance, fully for missiles that spread.
	 */
	public static boolean siegeShot(StarUnit unit, LivingEntity target) {
		Weapon weapon = unit.weaponAgainst(target);
		if (weapon.splash() <= 0) return false;
		if (unit.distanceTo(target) < weapon.minRange()) return true;
		float damage = (float) (weapon.damage() + unit.attackBonus() + Passives.bonus(unit, target));
		target.hurt(unit.attackSource(), damage);
		boolean air = airborne(target);
		float share = unit.kind == UnitKind.SIEGE_TANK || unit.kind == UnitKind.COMMAND_CENTER ? 0.5f : 1f;
		for (LivingEntity other : unit.level().getEntitiesOfClass(LivingEntity.class, target.getBoundingBox().inflate(weapon.splash()),
			other -> other != target && airborne(other) == air && dev.starmine.faction.Faction.hostile(unit, other)))
			other.hurt(unit.attackSource(), (float) (weapon.damage() + unit.attackBonus() + Passives.bonus(unit, other)) * share);
		if (unit.level() instanceof ServerLevel server)
			server.sendParticles(ParticleTypes.EXPLOSION, target.getX(), target.getY() + 0.5, target.getZ(), 1, 0, 0, 0, 0);
		return true;
	}
}
