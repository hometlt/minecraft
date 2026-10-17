package dev.starmine.ability;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.faction.Faction;
import dev.starmine.hive.Hive;
import java.util.Comparator;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * How wild units use their abilities (owner 14.10), once a second, by simple rules — a player's units
 * cast only on their player's command. In short:
 * - cloak with a foe near and energy, uncloak after 10 calm seconds; siege up when foes are within the
 *   siege reach but not too close, unsiege when calm; a lurker burrows to fight, a roach burrows to heal
 *   when badly hurt and no detector of the foe is near, and comes up healed or found;
 * - area spells (bile, fungal growth, storm, nova, time warp, EMP, blinding cloud, guardian shield,
 *   parasitic bomb) when several foes stand together (storm and nova not on their own side);
 * - unit spells on a fitting target: Yamato and neural parasite on a strong one, graviton beam on a
 *   ground one, feedback on a caster, steady targeting on bio, caustic spray and disintegration on
 *   buildings, prismatic alignment against armored; charge, KD8, stimpack and the oracle's beam when
 *   the foe is close; blink away when badly hurt, a battlecruiser jumps home, a mothership recalls;
 * - care: a queen injects and transfuses, an SCV repairs, a viper consumes a building for energy;
 * - forms: a viking lands against ground foes and takes off for air ones, a hellion turns hellbat up
 *   close, a thor switches its payload against air; a bunker loads when foes come; a command center
 *   becomes an orbital (or a fortress) when the base can pay and calls MULEs; production buildings get
 *   add-ons; a nexus boosts busy buildings and batteries under attack; a depot lowers when calm.
 */
final class AbilityAi {
	private static final int CALM = 200;

	/** What a wild sentry fakes (owner 17.10): the army's heavy hitters. */
	private static final List<UnitKind> HALLUCINATED = List.of(UnitKind.STALKER, UnitKind.IMMORTAL, UnitKind.ARCHON, UnitKind.COLOSSUS, UnitKind.VOID_RAY,
		UnitKind.PHOENIX);

	private AbilityAi() {
	}

	static void think(StarUnit unit, ServerLevel level) {
		AbilityState state = unit.abilities;
		if (state.changingMode() || state.channelling() || state.pending != null || Ability.of(unit.kind).isEmpty()) return;
		LivingEntity target = unit.getTarget();
		if (target != null && !target.isAlive()) target = null;
		state.calmTicks = target == null && !foeWithin(unit, 12) ? state.calmTicks + 20 : 0;
		for (Ability ability : Ability.of(unit.kind)) {
			if (Abilities.refusal(unit, ability) != null) continue;
			if (decide(unit, level, ability, target, state)) return; // one spell a second
		}
	}

	/** Whether it cast `ability` now. */
	private static boolean decide(StarUnit unit, ServerLevel level, Ability ability, @Nullable LivingEntity target, AbilityState state) {
		boolean on = Abilities.on(unit, ability);
		boolean calm = state.calmTicks >= CALM;
		double reach = target == null ? 0 : unit.distanceTo(target);
		Vec3 at = target == null ? null : target.position();
		return switch (ability) {
			case CLOAK -> !on && target != null && reach < 15 && unit.energy() >= 50 || on && calm ? cast(unit, level, ability) : false;
			case SIEGE_MODE -> {
				Modes.Weapon siege = Modes.weapon(unit.kind, Modes.SIEGED);
				yield !on && foeWithin(unit, siege.range() - 1) && !foeWithin(unit, siege.minRange() + 1) || on && calm && !foeWithin(unit, siege.range())
					? cast(unit, level, ability) : false;
			}
			case YAMATO -> target != null && target.getMaxHealth() >= 30 && cast(unit, level, ability, target);
			case TACTICAL_JUMP -> unit.getHealth() < unit.getMaxHealth() * 0.3f && unit.home() != null && unit.home().distSqr(unit.blockPosition()) > 64 * 64
				&& cast(unit, level, ability, Vec3.atBottomCenterOf(unit.home()));
			case BURROW -> burrow(unit, level, target, on, calm);
			case CORROSIVE_BILE, KD8_CHARGE, DISINTEGRATION, SHADOW_STRIDE, PSIONIC_TRANSFER -> target != null && aimAt(unit, ability, target)
				&& cast(unit, level, ability, at);
			case INJECT_LARVA -> {
				StarUnit hatchery = nearestOwn(unit, 16, other -> other.kind == UnitKind.HATCHERY && !Status.has(other, Status.INJECTED));
				yield hatchery != null && Abilities.order(level, List.of(unit), ability, hatchery, null);
			}
			case TRANSFUSION -> {
				StarUnit wounded = nearestOwn(unit, ability.range + 2, other -> other != unit && other.kind.race == UnitKind.Race.ZERG
					&& other.getHealth() < other.getMaxHealth() * 0.5f);
				yield wounded != null && cast(unit, level, ability, wounded);
			}
			case SPAWN_CHANGELING -> Changeling.due(unit, level) && cast(unit, level, ability); // owner: every 2–3 minutes, at most 2 alive
			case NEURAL_PARASITE -> target != null && target.getMaxHealth() >= 40 && cast(unit, level, ability, target);
			case FUNGAL_GROWTH -> target != null && foesAround(unit, at, ZergAbilities.FUNGAL_RADIUS) >= 3 && cast(unit, level, ability, at);
			case BLINDING_CLOUD -> target != null && foesAround(unit, at, ZergAbilities.CLOUD_RADIUS) >= 3 && cast(unit, level, ability, at);
			case PARASITIC_BOMB -> target instanceof StarUnit air && air.flying() && cast(unit, level, ability, target);
			case CONSUME -> {
				if (unit.energy() > 100) yield false;
				StarUnit building = nearestOwn(unit, 10, other -> other.kind.structure() && other.kind.race == UnitKind.Race.ZERG
					&& other.getHealth() > other.getMaxHealth() * 0.6f);
				yield building != null && cast(unit, level, ability, building);
			}
			case CAUSTIC_SPRAY -> target instanceof StarUnit building && building.kind.structure() && cast(unit, level, ability, target);
			case STIMPACK -> target != null && reach < unit.weapon().range() + 3 && unit.getHealth() > unit.getMaxHealth() * 0.5f
				&& !Status.has(unit, Status.STIM) && cast(unit, level, ability);
			case EMP -> target instanceof StarUnit caster && (caster.shield() > 5 || caster.energy() >= 50) && cast(unit, level, ability, at);
			case STEADY_TARGETING -> target != null && cast(unit, level, ability, target);
			case NUKE -> target instanceof StarUnit building && building.kind.structure() && cast(unit, level, ability, at);
			case HELLBAT -> !on && target != null && reach < 4 && foesAround(unit, unit.position(), 5) >= 2 || on && calm ? cast(unit, level, ability) : false;
			case DEFENDER_MODE -> {
				boolean ground = target != null && !(target instanceof StarUnit star && star.flying());
				yield !on && ground && reach < 10 * UnitKind.BLOCKS_PER_UNIT || on && (calm || target != null && !ground) ? cast(unit, level, ability) : false;
			}
			case HIGH_IMPACT -> {
				boolean air = target instanceof StarUnit star && star.flying();
				yield !on && air || on && (calm || target != null && !air) ? cast(unit, level, ability) : false;
			}
			case VIKING_LAND -> {
				boolean airFoes = flyingFoeWithin(unit, 14);
				// in the air a viking cannot fire at the ground (SC2): foes there and none in the air, it lands
				boolean groundFoes = foeWithin(unit, 14) && !airFoes;
				yield !on && groundFoes || on && (airFoes || calm) ? cast(unit, level, ability) : false;
			}
			case AFTERBURNERS -> unit.getHealth() < unit.getMaxHealth() * 0.5f && foeWithin(unit, 10) && cast(unit, level, ability);
			case REPAIR -> {
				if (state.repairTarget != null || unit.cargoBlocks > 0) yield false;
				StarUnit broken = nearestOwn(unit, 16, other -> other.kind.mechanical() && other.kind.race == UnitKind.Race.TERRAN
					&& !other.underConstruction() && other.getHealth() < other.getMaxHealth() * 0.7f);
				Hive base = Abilities.base(unit);
				yield broken != null && base != null && base.minerals >= 50 && Abilities.order(level, List.of(unit), ability, broken, null);
			}
			case ORBITAL_COMMAND, PLANETARY_FORTRESS -> {
				Hive base = Abilities.base(unit);
				boolean fortress = unit.getUUID().getLeastSignificantBits() % 3 == 0; // one command center in three digs in
				yield base != null && base.minerals >= 400 && fortress == (ability == Ability.PLANETARY_FORTRESS) && cast(unit, level, ability);
			}
			case MULE -> cast(unit, level, ability, unit.position().add(level.random.nextGaussian() * 6, 0, level.random.nextGaussian() * 6));
			case SCANNER_SWEEP -> {
				StarUnit hidden = cloakedFoe(unit, 32);
				yield hidden != null && cast(unit, level, ability, hidden.position());
			}
			case LOWER_DEPOT -> !on && calm || on && foeWithin(unit, 12) ? cast(unit, level, ability) : false;
			case BUILD_TECHLAB, BUILD_REACTOR -> {
				Hive base = Abilities.base(unit);
				boolean reactor = unit.kind == UnitKind.BARRACKS && unit.getUUID().getLeastSignificantBits() % 2 == 0;
				yield base != null && base.minerals >= 200 && base.vespene >= 75 && reactor == (ability == Ability.BUILD_REACTOR)
					&& unit.queue().isEmpty() && cast(unit, level, ability);
			}
			case LOAD -> unit.kind == UnitKind.BUNKER ? foeWithin(unit, 12) && cast(unit, level, ability)
				// a flying transport takes up the badly wounded out of a fight
				: foeWithin(unit, 12) && nearestOwn(unit, 6, other -> !other.flying() && !other.kind.structure() && !other.isPassenger()
					&& other.kind.race == unit.kind.race && other.getHealth() < other.getMaxHealth() * 0.3f) != null && cast(unit, level, ability);
			case UNLOAD -> !unit.getPassengers().isEmpty() && (calm || unit.kind != UnitKind.BUNKER && unit.getHealth() < unit.getMaxHealth() * 0.25f)
				&& cast(unit, level, ability, unit.position());
			case CHARGE -> target != null && reach > 2 && cast(unit, level, ability, target);
			// the heroes: Kerrigan leaps at a foe a little off and shifts through a group; Raynor calls the Hyperion on a strong
			// foe (not a player: 600 damage would end anyone at once); Artanis dashes at a foe
			case LEAPING_STRIKE, LIGHTNING_DASH -> target != null && reach > 3 && reach < ability.range && cast(unit, level, ability, target);
			case PSIONIC_SHIFT -> target != null && reach < ability.range && foesAround(unit, at, 4) >= 2 && cast(unit, level, ability, at);
			case HYPERION_STRIKE -> target != null && target.getMaxHealth() >= 30 && reach < ability.range && cast(unit, level, ability, target);
			case BLINK -> {
				if (target == null || unit.getHealth() + unit.shield() > (unit.getMaxHealth() + unit.kind.shields) * 0.35) yield false;
				Vec3 away = unit.position().subtract(target.position()).multiply(1, 0, 1);
				away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
				yield cast(unit, level, ability, unit.position().add(away.scale(ability.range - 1)));
			}
			case FORCE_FIELD -> target != null && target.distanceTo(unit) < 7 && target.distanceTo(unit) > 3
				&& cast(unit, level, ability, unit.position().lerp(target.position(), 0.6));
			case GUARDIAN_SHIELD -> foesAround(unit, unit.position(), 9) >= 2 && cast(unit, level, ability);
			case HALLUCINATION -> {
				if (target == null || unit.energy() < 150) yield false;
				unit.abilities.hallucinate = HALLUCINATED.get(level.random.nextInt(HALLUCINATED.size())); // owner 17.10: what to fake, its pick
				yield cast(unit, level, ability);
			}
			// owner 17.10: the AI uses every ability
			case CREEP_TUMOR -> calm && unit.energy() >= 50 && cast(unit, level, ability,
				unit.position().add(level.random.nextGaussian() * 5, 0, level.random.nextGaussian() * 5));
			case ABDUCT -> target instanceof StarUnit foe && !foe.kind.structure() && foe.getMaxHealth() >= 30 && reach > 4 && reach < ability.range
				&& cast(unit, level, ability, target);
			case LIFT_OFF -> {
				boolean hurt = unit.getLastHurtByMob() != null && unit.tickCount - unit.getLastHurtByMobTimestamp() < 60
					&& !(unit.getLastHurtByMob() instanceof StarUnit flyer && flyer.flying());
				yield !on && hurt && unit.getHealth() < unit.getMaxHealth() * 0.3f && unit.queue().isEmpty() || on && calm ? cast(unit, level, ability) : false;
			}
			case PHASING_MODE -> !on && target == null && unit.order() != StarUnit.Order.MOVE && alliesAround(unit, unit.position(), 10) >= 3
				|| on && (foeWithin(unit, 10) || unit.order() == StarUnit.Order.MOVE) ? cast(unit, level, ability) : false;
			case WARP_GATE -> !on && unit.queue().isEmpty() && cast(unit, level, ability);
			case PSIONIC_STORM -> target != null && foesAround(unit, at, ProtossAbilities.STORM_RADIUS) >= 3
				&& alliesAround(unit, at, ProtossAbilities.STORM_RADIUS + 1) == 0 && cast(unit, level, ability, at);
			case PURIFICATION_NOVA -> target != null && foesAround(unit, at, ProtossAbilities.NOVA_RADIUS) >= 2
				&& alliesAround(unit, at, ProtossAbilities.NOVA_RADIUS + 1) == 0 && cast(unit, level, ability, at);
			case FEEDBACK -> target instanceof StarUnit caster && caster.energy() >= 50 && cast(unit, level, ability, target);
			case ARCHON_WARP -> (unit.kind == UnitKind.HIGH_TEMPLAR ? calm && unit.energy() < 50 : calm && unit.getHealth() < unit.getMaxHealth() * 0.5f)
				&& cast(unit, level, ability); // a dark templar badly hurt merges too
			case GRAVITON_BEAM -> target != null && cast(unit, level, ability, target);
			case PRISMATIC_ALIGNMENT -> target instanceof StarUnit armored && armored.kind.armored() && reach < unit.weapon().range() + 2
				&& cast(unit, level, ability);
			case PULSAR_BEAM -> !on && target != null && reach < 8 && unit.energy() >= 50 || on && (calm || unit.energy() < 10) ? cast(unit, level, ability) : false;
			case REVELATION -> {
				StarUnit hidden = cloakedFoe(unit, ability.range);
				yield hidden != null && cast(unit, level, ability, hidden.position());
			}
			case STASIS_WARD -> target != null && foesAround(unit, at, ProtossAbilities.WARD_RADIUS) >= 3 && cast(unit, level, ability, at);
			case MASS_RECALL -> { // its own troops losing a fight far off come to it
				StarUnit losing = nearestOwn(unit, unit.kind == UnitKind.NEXUS ? 96 : 48, other -> !other.kind.structure() && other != unit
					&& other.distanceTo(unit) > 16 && other.getHealth() < other.getMaxHealth() * 0.4f && foeWithin(other, 10));
				yield losing != null && cast(unit, level, ability, losing.position());
			}
			case TIME_WARP -> target != null && foesAround(unit, at, ProtossAbilities.TIME_WARP_RADIUS) >= 3 && cast(unit, level, ability, at);
			case CHRONO_BOOST -> {
				StarUnit busy = nearestOwn(unit, 32, other -> other.kind.structure() && !other.queue().isEmpty() && !Status.has(other, Status.CHRONO));
				yield busy != null && cast(unit, level, ability, busy);
			}
			case BATTERY_OVERCHARGE -> {
				StarUnit battery = nearestOwn(unit, 32, other -> other.kind == UnitKind.SHIELD_BATTERY && foeWithin(other, 10));
				yield battery != null && cast(unit, level, ability, battery);
			}
			default -> false;
		};
	}

	/** A lurker burrows to fight and comes up when calm; others burrow to heal when badly hurt and unseen, and come up healed or found. */
	private static boolean burrow(StarUnit unit, ServerLevel level, @Nullable LivingEntity target, boolean on, boolean calm) {
		if (unit.kind == UnitKind.LURKER) {
			double range = Modes.weapon(UnitKind.LURKER, Modes.BURROWED).range();
			return !on && target != null && unit.distanceTo(target) <= range || on && calm ? cast(unit, level, Ability.BURROW) : false;
		}
		if (unit.kind == UnitKind.WIDOW_MINE) return !on && cast(unit, level, Ability.BURROW); // a wild mine lies in wait
		if (unit.kind.race != UnitKind.Race.ZERG || unit.flying()) return false;
		float health = unit.getHealth() / unit.getMaxHealth(); // owner 17.10: every zerg hides underground when badly hurt (a roach sooner: it heals fast there)
		if (!on) return health < (unit.kind == UnitKind.ROACH ? 0.35f : 0.25f) && !foeDetectorWithin(unit, 14) && cast(unit, level, Ability.BURROW);
		return health > 0.85f || unit.revealed() ? cast(unit, level, Ability.BURROW) : false;
	}

	/** Whether a point spell aimed at the target is worth it and within reach (with a little to spare). */
	private static boolean aimAt(StarUnit unit, Ability ability, LivingEntity target) {
		double distance = Abilities.horizontal(unit.position(), target.position());
		return switch (ability) {
			case KD8_CHARGE -> distance < ability.range;
			case DISINTEGRATION -> target instanceof StarUnit building && building.kind.structure() && distance < ability.range;
			case SHADOW_STRIDE -> distance > 3 && distance < ability.range;
			case PSIONIC_TRANSFER -> distance > 6 && distance < 20;
			default -> distance < ability.range;
		};
	}

	private static boolean cast(StarUnit unit, ServerLevel level, Ability ability) {
		return Abilities.cast(unit, level, ability, null, null);
	}

	private static boolean cast(StarUnit unit, ServerLevel level, Ability ability, LivingEntity target) {
		return Abilities.cast(unit, level, ability, target, null);
	}

	private static boolean cast(StarUnit unit, ServerLevel level, Ability ability, @Nullable Vec3 point) {
		return point != null && Abilities.cast(unit, level, ability, null, point);
	}

	private static boolean foeWithin(StarUnit unit, double range) {
		return !unit.level().getEntitiesOfClass(LivingEntity.class, unit.getBoundingBox().inflate(range),
			other -> Faction.hostile(unit, other) && other.distanceTo(unit) <= range
				&& !(other instanceof StarUnit cloaked && !Cloak.seen(cloaked, unit))).isEmpty();
	}

	private static boolean flyingFoeWithin(StarUnit unit, double range) {
		return !unit.level().getEntitiesOfClass(StarUnit.class, unit.getBoundingBox().inflate(range),
			other -> other.flying() && Faction.hostile(unit, other) && other.distanceTo(unit) <= range && Cloak.seen(other, unit)).isEmpty();
	}

	private static boolean foeDetectorWithin(StarUnit unit, double range) {
		return !unit.level().getEntitiesOfClass(StarUnit.class, unit.getBoundingBox().inflate(range),
			other -> other.kind.detector() && Faction.hostile(unit, other) && other.distanceTo(unit) <= range).isEmpty();
	}

	private static int foesAround(StarUnit unit, Vec3 centre, double radius) {
		return (int) Abilities.around(unit, centre, radius, true).stream().filter(other -> !(other instanceof StarUnit hidden && !Cloak.seen(hidden, unit))).count();
	}

	private static int alliesAround(StarUnit unit, Vec3 centre, double radius) {
		return (int) Abilities.around(unit, centre, radius, false).stream().filter(other -> other != unit && Faction.allied(unit, other)).count();
	}

	/** The nearest unit or building of its own side within `range` that fits. */
	private static @Nullable StarUnit nearestOwn(StarUnit unit, double range, java.util.function.Predicate<StarUnit> fits) {
		return unit.level().getEntitiesOfClass(StarUnit.class, unit.getBoundingBox().inflate(range),
				other -> other.isAlive() && Faction.allied(unit, other) && fits.test(other))
			.stream().min(Comparator.comparingDouble(other -> other.distanceToSqr(unit))).orElse(null);
	}

	/** A cloaked foe near (the AI knows where they hide: it reveals or scans them). */
	private static @Nullable StarUnit cloakedFoe(StarUnit unit, double range) {
		return unit.level().getEntitiesOfClass(StarUnit.class, new AABB(unit.blockPosition()).inflate(range),
				other -> other.isAlive() && other.cloaked() && !Cloak.seen(other, unit) && Faction.hostile(unit, other))
			.stream().findFirst().orElse(null);
	}
}
