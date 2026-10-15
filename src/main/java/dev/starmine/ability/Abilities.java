package dev.starmine.ability;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.faction.Faction;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The ability engine (owner 14.10, docs/ABILITIES.md), the same for every race:
 * - energy: casters ({@link StarUnit#maxEnergy}) gain SC2 0.7875 a second up to their maximum;
 * - a cast checks the caster, its energy, cooldown, health, base resources, a mode change under way,
 *   its states (a lifted or merging unit casts nothing) and the reach, pays, starts the cooldown, then:
 *   at once; after a channel (the caster stands, the spell breaks if the target dies or gets away); or
 *   after a delay ({@link Delayed}); modes change through {@link Modes};
 * - players cast through commands ({@link #order}: toggles and plain abilities on every selected unit
 *   that has them, aimed ones by the nearest able unit — SC2 smart casting) or modules when they play as
 *   a unit; wild units through {@link AbilityAi}.
 * What each ability does is {@link #effect}: the races' own in {@link ZergAbilities},
 * {@link TerranAbilities}, {@link ProtossAbilities}; the common mechanics in {@link Cloak},
 * {@link Modes}, {@link Transport}, {@link Status}, {@link Passives}, {@link Channels}.
 */
public final class Abilities {
	/** SC2 energy regeneration a second (0.7875) per tick. */
	public static final float ENERGY_PER_TICK = 0.7875f / 20;

	private Abilities() {
	}

	/** Why `unit` cannot cast `ability` now (a lang key), or null. A toggle that is on can always be turned off. */
	public static @Nullable String refusal(StarUnit unit, Ability ability) {
		if (!ability.users().contains(unit.kind) || !unit.isAlive()) return "message.starmine.no_ability";
		if (!available(unit, ability)) return "message.starmine.no_ability";
		if (unit.abilities.changingMode() || unit.abilities.channelling() || unit.underConstruction()) return "message.starmine.busy";
		if (Status.has(unit, Status.LIFTED) || Status.has(unit, Status.STASIS) || Status.has(unit, Status.MERGING)
			|| Status.has(unit, Status.CHANNELLING) || Status.has(unit, Status.HALLUCINATION)) return "message.starmine.busy";
		if (ability == Ability.LIFT_OFF && LiftOff.refusal(unit) != null) return LiftOff.refusal(unit); // idle to lift, room to land
		if (ability.target == Ability.Target.TOGGLE && on(unit, ability)) return null;
		if (!dev.starmine.race.Research.has(unit, dev.starmine.race.Research.of(ability))) return "message.starmine.need_research";
		long now = unit.level().getGameTime();
		if (unit.abilities.cooldownLeft(ability, now) > 0) return "message.starmine.cooldown";
		if (unit.energy() < ability.energy) return "message.starmine.need_energy";
		double health = ability.healthCost(unit.kind) * UnitKind.SCALE;
		if (health > 0 && unit.body().getHealth() <= health) return "message.starmine.need_health";
		if (ability.minerals > 0 || ability.vespene > 0) {
			Hive base = base(unit);
			if (base == null) return "message.starmine.no_base";
			if (base.minerals < ability.minerals) return "message.starmine.need_minerals";
			if (base.vespene < ability.vespene) return "message.starmine.need_vespene";
		}
		return switch (ability) {
			case LOAD, UNLOAD -> unit.pilot() != null ? "message.starmine.pilot_transport" : Transport.refusal(unit, ability);
			case MASS_RECALL -> base(unit) == null ? "message.starmine.no_base" : null;
			case BLINK, SHADOW_STRIDE, CHARGE -> Status.has(unit, Status.ROOTED) ? "message.starmine.rooted" : null;
			case ARCHON_WARP -> ProtossAbilities.partner(unit) == null ? "message.starmine.no_partner" : null;
			case REPAIR, MULE -> base(unit) == null ? "message.starmine.no_base" : null;
			default -> null;
		};
	}

	/**
	 * Whether the unit has this ability in its present form (a command center becomes an orbital or a
	 * fortress, then has their abilities; an add-on is built once; a unit has the abilities of its mode).
	 */
	public static boolean available(StarUnit unit, Ability ability) {
		int mode = unit.mode();
		if (Modes.oneWay(ability)) return mode == Modes.NORMAL;
		if (ability == Ability.LIFT_OFF) return mode == Modes.NORMAL || mode == Modes.AIRBORNE; // an add-on or upgrade keeps it down
		if (mode == Modes.AIRBORNE) return false; // in the air a building only flies and lands
		return ability != Ability.MULE && ability != Ability.SCANNER_SWEEP || mode == Modes.ORBITAL;
	}

	/** The abilities a unit shows on its command card now (its form's). */
	public static List<Ability> shown(StarUnit unit) {
		List<Ability> list = new ArrayList<>();
		for (Ability ability : Ability.of(unit.kind)) if (available(unit, ability)) list.add(ability);
		return list;
	}

	/** The unit's base, or null. */
	public static @Nullable Hive base(StarUnit unit) {
		return unit.level() instanceof ServerLevel server && unit.hiveId() != null ? HiveSavedData.get(server.getServer()).get(unit.hiveId()) : null;
	}

	/** Whether a toggle is on (both sides: the command card asks it too). */
	public static boolean on(StarUnit unit, Ability ability) {
		if (ability == Ability.CLOAK) return unit.cloaked() && !Cloak.forced(unit);
		int mode = Modes.of(ability);
		return mode >= 0 && unit.mode() == mode;
	}

	/** Casts it (paying and starting the cooldown); false when refused. `target` for unit spells, `point` for point spells. */
	public static boolean cast(StarUnit unit, ServerLevel level, Ability ability, @Nullable LivingEntity target, @Nullable Vec3 point) {
		if (refusal(unit, ability) != null) return false;
		if (ability.target == Ability.Target.UNIT) {
			if (target == null || !target.isAlive() || !validTarget(unit, ability, target)) return false;
			if (ability.range > 0 && unit.body().distanceTo(target) > ability.range + target.getBbWidth() / 2 + unit.getBbWidth() / 2) return false;
		}
		if (ability.target == Ability.Target.POINT) {
			if (point == null) return false;
			if (ability.range > 0 && horizontal(unit.body().position(), point) > ability.range + unit.getBbWidth() / 2) return false;
		}
		boolean turningOff = ability.target == Ability.Target.TOGGLE && on(unit, ability);
		if (!turningOff) {
			unit.setEnergy(unit.energy() - ability.energy);
			if (ability.cooldownTicks > 0) unit.abilities.readyAt.put(ability, level.getGameTime() + ability.cooldownTicks);
			double health = ability.healthCost(unit.kind) * UnitKind.SCALE;
			if (health > 0) unit.body().setHealth((float) (unit.body().getHealth() - health));
			Hive base = ability.minerals > 0 || ability.vespene > 0 ? base(unit) : null;
			if (base != null) {
				base.minerals -= ability.minerals;
				base.vespene -= ability.vespene;
				HiveSavedData.get(level.getServer()).setDirty();
			}
		}
		int mode = Modes.of(ability);
		if (mode >= 0) { // a mode change (its own SC2 time; an add-on or upgrade is the change)
			int to = turningOff ? Modes.NORMAL : mode;
			if (ability == Ability.LIFT_OFF && !turningOff) unit.abilities.liftedFrom = unit.position();
			Modes.change(unit, to, Modes.changeTicks(ability, to));
			return true;
		}
		if (ability.channelTicks > 0) {
			AbilityState state = unit.abilities;
			state.channel = ability;
			state.channelTarget = target == null ? null : target.getUUID();
			state.channelPoint = point;
			state.channelTicks = ability.channelTicks;
			unit.getNavigation().stop();
			if (ability == Ability.NUKE && point != null) TerranAbilities.nukeMark(level, point);
			return true;
		}
		if (ability.delayTicks > 0) {
			Delayed.after(level, ability.delayTicks, () -> {
				if (unit.isAlive() || unit.pilot() != null) effect(unit, level, ability, target, point);
			});
			warn(level, unit, ability, point);
			return true;
		}
		effect(unit, level, ability, target, point);
		return true;
	}

	/** Whether a unit spell may go on that target (allies for heals and boosts, foes for attacks, the right kinds). */
	static boolean validTarget(StarUnit unit, Ability ability, LivingEntity target) {
		StarUnit other = target instanceof StarUnit star ? star : null;
		boolean ally = Faction.allied(unit, target);
		return switch (ability) {
			case TRANSFUSION -> ally && target != unit && (other == null || other.kind.race == UnitKind.Race.ZERG) && target.getHealth() < target.getMaxHealth();
			case INJECT_LARVA -> ally && other != null && other.kind == UnitKind.HATCHERY && !Status.has(other, Status.INJECTED);
			case CONSUME -> ally && other != null && other.kind.structure() && other.kind.race == UnitKind.Race.ZERG;
			case CAUSTIC_SPRAY -> !ally && other != null && other.kind.structure();
			case REPAIR -> ally && other != null && other.kind.mechanical() && other.kind.race == UnitKind.Race.TERRAN && target.getHealth() < target.getMaxHealth()
				&& !other.underConstruction();
			case CHRONO_BOOST -> ally && other != null && other.kind.structure() && other.kind.race == UnitKind.Race.PROTOSS && !other.underConstruction();
			case BATTERY_OVERCHARGE -> ally && other != null && other.kind == UnitKind.SHIELD_BATTERY;
			case GRAVITON_BEAM -> !ally && !(other != null && (other.kind.massive() || other.kind.structure() || other.flying()));
			case STEADY_TARGETING -> !ally && (other == null || other.kind.biological() || other.kind.race == UnitKind.Race.ZERG && !other.kind.structure());
			case NEURAL_PARASITE -> !ally && !(target instanceof Player) && !(other != null && (other.kind.structure() || other.kind.massive()));
			case ABDUCT -> target != unit && !(other != null && other.kind.structure());
			case PARASITIC_BOMB -> !ally && (other == null ? !target.onGround() : other.flying());
			case FEEDBACK -> !ally && other != null && other.maxEnergy() > 0;
			case CHARGE, YAMATO -> !ally;
			default -> true;
		};
	}

	/** What an ability does (when its channel or delay is over). */
	static void effect(StarUnit unit, ServerLevel level, Ability ability, @Nullable LivingEntity target, @Nullable Vec3 point) {
		switch (ability) {
			case CLOAK -> Cloak.toggle(unit);
			case LOAD -> Transport.load(unit);
			case UNLOAD -> Transport.unloadAt(unit, point);
			// zerg
			case CORROSIVE_BILE -> ZergAbilities.bile(unit, level, point);
			case CREEP_TUMOR -> ZergAbilities.creepTumor(unit, level, point);
			case INJECT_LARVA -> ZergAbilities.inject(unit, level, target);
			case TRANSFUSION -> ZergAbilities.transfusion(unit, level, target);
			case SPAWN_CHANGELING -> ZergAbilities.changeling(unit, level);
			case NEURAL_PARASITE -> ZergAbilities.neuralParasite(unit, level, target);
			case FUNGAL_GROWTH -> ZergAbilities.fungal(unit, level, point);
			case BLINDING_CLOUD -> ZergAbilities.blindingCloud(unit, level, point);
			case ABDUCT -> ZergAbilities.abduct(unit, level, target);
			case PARASITIC_BOMB -> ZergAbilities.parasiticBomb(unit, level, target);
			case CONSUME -> ZergAbilities.consume(unit, level, target);
			case CAUSTIC_SPRAY -> Channels.start(unit, ability, target, Channels.SPRAY_TICKS);
			// terran
			case STIMPACK -> TerranAbilities.stim(unit, level);
			case KD8_CHARGE -> TerranAbilities.kd8(unit, level, point);
			case EMP -> TerranAbilities.emp(unit, level, point);
			case STEADY_TARGETING -> TerranAbilities.steadyTargeting(unit, level, target);
			case NUKE -> TerranAbilities.nuke(unit, level, point);
			case AFTERBURNERS -> Status.apply(unit, Status.AFTERBURNERS, TerranAbilities.AFTERBURNER_TICKS);
			case YAMATO -> TerranAbilities.yamato(unit, level, target);
			case TACTICAL_JUMP -> TerranAbilities.tacticalJump(unit, level, point);
			case REPAIR -> TerranAbilities.repair(unit, target);
			case MULE -> TerranAbilities.mule(unit, level, point);
			case SCANNER_SWEEP -> TerranAbilities.scan(unit, level, point);
			// protoss
			case CHARGE -> ProtossAbilities.charge(unit, level, target);
			case BLINK, SHADOW_STRIDE -> ProtossAbilities.blink(unit, level, point);
			case PSIONIC_TRANSFER -> ProtossAbilities.blink(unit, level, point);
			case FORCE_FIELD -> ProtossAbilities.forceField(unit, level, point);
			case GUARDIAN_SHIELD -> Status.apply(unit, Status.GUARDIAN, ProtossAbilities.GUARDIAN_TICKS);
			case HALLUCINATION -> ProtossAbilities.hallucination(unit, level);
			case PSIONIC_STORM -> ProtossAbilities.storm(unit, level, point);
			case FEEDBACK -> ProtossAbilities.feedback(unit, level, target);
			case ARCHON_WARP -> ProtossAbilities.archonWarp(unit, level);
			case PURIFICATION_NOVA -> ProtossAbilities.nova(unit, level, point);
			case GRAVITON_BEAM -> Channels.start(unit, ability, target, ProtossAbilities.GRAVITON_TICKS);
			case PRISMATIC_ALIGNMENT -> Status.apply(unit, Status.PRISMATIC, ProtossAbilities.PRISMATIC_TICKS);
			case REVELATION -> ProtossAbilities.revelation(unit, level, point);
			case STASIS_WARD -> ProtossAbilities.stasisWard(unit, level, point);
			case DISINTEGRATION -> ProtossAbilities.disintegration(unit, level, point);
			case MASS_RECALL -> ProtossAbilities.massRecall(unit, level);
			case TIME_WARP -> ProtossAbilities.timeWarp(unit, level, point);
			case CHRONO_BOOST -> {
				if (target instanceof StarUnit building) Status.apply(building, Status.CHRONO, ProtossAbilities.CHRONO_TICKS);
			}
			case BATTERY_OVERCHARGE -> {
				if (target instanceof StarUnit battery) Status.apply(battery, Status.OVERCHARGE, ProtossAbilities.OVERCHARGE_TICKS);
			}
			default -> {
			}
		}
	}

	/** A delayed ability's warning where it will happen: at the point (bile, a KD8 charge, a nova), else around the caster (a jump's shimmer). */
	private static void warn(ServerLevel level, StarUnit unit, Ability ability, @Nullable Vec3 point) {
		if (ability.target == Ability.Target.POINT && point != null && ability != Ability.TACTICAL_JUMP && ability != Ability.PSIONIC_TRANSFER) {
			ring(level, point, ability == Ability.KD8_CHARGE ? 1.5 : 2, ability == Ability.CORROSIVE_BILE ? ParticleTypes.ITEM_SLIME : ParticleTypes.FLAME);
			return;
		}
		Entity body = unit.body();
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, body.getX(), body.getY() + unit.getBbHeight() / 2, body.getZ(), 40,
			unit.getBbWidth() / 2, unit.getBbHeight() / 2, unit.getBbWidth() / 2, 0.05);
		if (point != null && ability == Ability.PSIONIC_TRANSFER)
			level.sendParticles(ParticleTypes.SOUL, point.x, point.y + 0.8, point.z, 20, 0.3, 0.6, 0.3, 0.01);
	}

	/** Every tick of a unit: energy, states, cloak upkeep, the channel, a mode change, passives, a transport's unloading, the AI's spells. */
	public static void tick(StarUnit unit, ServerLevel level) {
		AbilityState state = unit.abilities;
		if (!state.weaponSet) {
			state.weaponSet = true;
			if (!state.changingMode()) unit.refreshWeapon();
		}
		int max = unit.maxEnergy();
		if (max > 0 && unit.energy() < max && !(unit.kind == UnitKind.SHIELD_BATTERY && !unit.kind.race.rules().powered(unit)))
			unit.setEnergy(Math.min(max, unit.energy() + ENERGY_PER_TICK));
		Status.tick(unit, level);
		if (!unit.isAlive() && unit.pilot() == null) return;
		Cloak.tick(unit, level);
		if (state.modeTicks > 0 && --state.modeTicks == 0) Modes.finish(unit);
		if (Ability.LIFT_OFF.users().contains(unit.kind)) LiftOff.tick(unit);
		if (unit.kind == UnitKind.CHANGELING) Changeling.tick(unit, level);
		if (state.channel != null) channel(unit, level, state);
		else if (state.pending != null) approach(unit, level, state);
		Passives.tick(unit, level);
		if (state.unloadAt != null) Transport.tick(unit, level);
		if (unit.tickCount % 20 == 11 && unit.owner().isEmpty() && unit.pilot() == null) AbilityAi.think(unit, level);
	}

	private static void channel(StarUnit unit, ServerLevel level, AbilityState state) {
		Ability ability = state.channel;
		Entity found = state.channelTarget == null ? null : level.getEntity(state.channelTarget);
		LivingEntity target = found instanceof LivingEntity living ? living : null;
		if (ability.target == Ability.Target.UNIT
			&& (target == null || !target.isAlive() || unit.body().distanceTo(target) > ability.range * 1.5 + target.getBbWidth() / 2)) {
			state.channel = null; // broken: the target is gone (the energy and cooldown are spent, as in SC2)
			return;
		}
		unit.getNavigation().stop();
		if (target != null) unit.getLookControl().setLookAt(target);
		if (unit.tickCount % 4 == 0) {
			Entity body = unit.body();
			Vec3 at = body.position().add(body.getLookAngle().scale(unit.getBbWidth() / 2)).add(0, unit.getBbHeight() / 2, 0);
			level.sendParticles(ability == Ability.NUKE ? ParticleTypes.DUST_PLUME : ParticleTypes.FLAME, at.x, at.y, at.z, 6, 0.3, 0.3, 0.3, 0.02);
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
		if (target != null && !validTarget(nearest.get(0), ability, target)) return false;
		// out of reach: the nearest goes closer and casts when it can (for 15 s)
		AbilityState state = nearest.get(0).abilities;
		state.pending = ability;
		state.pendingTarget = target == null ? null : target.getUUID();
		state.pendingPoint = target == null ? point : null;
		state.pendingTicks = 300;
		return true;
	}

	/** A spell waiting for its caster to get into reach of its target unit or point. */
	private static void approach(StarUnit unit, ServerLevel level, AbilityState state) {
		Entity found = state.pendingTarget == null ? null : level.getEntity(state.pendingTarget);
		LivingEntity target = found instanceof LivingEntity living && living.isAlive() ? living : null;
		Vec3 point = state.pendingPoint;
		if (target == null && point == null || --state.pendingTicks <= 0 || refusal(unit, state.pending) != null) {
			state.pending = null;
			return;
		}
		if (cast(unit, level, state.pending, target, point)) {
			state.pending = null;
			return;
		}
		Vec3 goal = target != null ? target.position() : point;
		if (unit.flying()) unit.getMoveControl().setWantedPosition(goal.x, unit.cruiseHeight(goal.x, goal.z), goal.z, 1.0);
		else if (unit.tickCount % 10 == 0) unit.getNavigation().moveTo(goal.x, goal.y, goal.z, 1.0);
	}

	// ---- helpers for the effects ----

	/** Distance between two points across the ground (height left out). */
	public static double horizontal(Vec3 a, Vec3 b) {
		double dx = a.x - b.x, dz = a.z - b.z;
		return Math.sqrt(dx * dx + dz * dz);
	}

	/** Living things within `radius` of a point (horizontally; a few blocks up and down), the caster's foes only or everyone. */
	static List<LivingEntity> around(StarUnit caster, Vec3 centre, double radius, boolean foesOnly) {
		Level level = caster.level();
		AABB box = new AABB(centre, centre).inflate(radius, 3, radius);
		return level.getEntitiesOfClass(LivingEntity.class, box, other -> other.isAlive() && horizontal(other.position(), centre) <= radius + other.getBbWidth() / 2
			&& (!foesOnly || other != caster.body() && Faction.hostile(caster, other)));
	}

	/** A ring of particles on the ground (an area spell's edge). */
	static void ring(ServerLevel level, Vec3 centre, double radius, ParticleOptions particle) {
		int points = (int) Math.max(8, radius * 8);
		for (int i = 0; i < points; i++) {
			double angle = i * Math.PI * 2 / points;
			level.sendParticles(particle, centre.x + Math.cos(angle) * radius, centre.y + 0.2, centre.z + Math.sin(angle) * radius, 1, 0, 0.05, 0, 0);
		}
	}

	/**
	 * Where a unit of its size may stand at or next to a point (feet position): on solid ground with room
	 * for its body, searched a few blocks around and up and down; null when there is none.
	 */
	static @Nullable Vec3 standable(StarUnit unit, Level level, Vec3 point) {
		Entity body = unit.body();
		BlockPos start = BlockPos.containing(point);
		for (int r = 0; r <= 2; r++)
			for (int dy = 2; dy >= -3; dy--)
				for (int dx = -r; dx <= r; dx++)
					for (int dz = -r; dz <= r; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
						BlockPos feet = start.offset(dx, dy, dz);
						if (!level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP)) continue;
						Vec3 at = Vec3.atBottomCenterOf(feet);
						AABB box = body.getDimensions(body.getPose()).makeBoundingBox(at);
						if (level.noCollision(body, box)) return at;
					}
		return null;
	}

	/** Moves the unit (or its pilot's body) to a point at once. */
	static void teleport(StarUnit unit, Vec3 at) {
		Entity body = unit.body();
		body.teleportTo(at.x, at.y, at.z);
		body.setDeltaMovement(Vec3.ZERO);
		if (body == unit) unit.getNavigation().stop();
	}
}
