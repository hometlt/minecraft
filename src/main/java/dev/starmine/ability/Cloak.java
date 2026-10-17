package dev.starmine.ability;

import dev.starmine.entity.StarUnit;
import dev.starmine.faction.Faction;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Cloaking and detection (owner 14.10, docs/ABILITIES.md), one rule for every race:
 * - a cloaked unit (the ghost's and banshee's cloak for SC2 25 energy and 0.9 a second; the dark
 *   templar and the observer always; a burrowed zerg and a unit in a mothership's cloaking field for
 *   free, {@link #forced}) cannot be seen or targeted by its foes; area damage still hits it;
 * - it is seen by its own side and by every side with a detector ({@link dev.starmine.entity.UnitKind#detector})
 *   whose sight reaches it — the detector's allies see it too — or with a scanner sweep over it
 *   ({@link #scan}); an oracle's revelation shows it to everyone ({@link Status#REVEALED});
 * - who targets a cloaked unit it cannot see loses it (players, monsters and units alike: vanilla
 *   targeting asks {@link #seen} through the visibility mixin).
 * The client draws a cloaked unit of its own side see-through, a revealed one plainly, any other one
 * as a faint shimmer.
 */
public final class Cloak {
	/** SC2 cloak upkeep, 0.9 energy a second, per tick. */
	public static final float DRAIN_PER_TICK = 0.9f / 20;
	/** The detectors near each cloaked unit, refreshed twice a second (server). */
	private static final Map<StarUnit, List<Entity>> DETECTORS = new WeakHashMap<>();

	/** A scanner sweep: an area where a side sees cloaked units until a game time (seconds-long, kept in memory). */
	private record Scan(ServerLevel level, Vec3 centre, double radius, Entity side, long until) {
	}

	private static final List<Scan> SCANS = new ArrayList<>();

	private Cloak() {
	}

	/** How far a detector potion detects: SC2 11. */
	public static final double POTION_DETECT = 11 * dev.starmine.entity.UnitKind.BLOCKS_PER_UNIT;

	/** Blocks around a cloaked unit searched for detectors: the farthest detector sight (an observer's, grown with the units). */
	private static final double DETECT_SEARCH = 32;

	static void toggle(StarUnit unit) {
		unit.setCloaked(!unit.cloaked());
	}

	/** Cloaked for free by its kind or its state: the dark templar and observer, a burrowed unit, a unit in a cloaking field. */
	public static boolean forced(StarUnit unit) {
		return unit.kind.permanentlyCloaked() || unit.mode() == Modes.BURROWED || Status.has(unit, Status.CLOAK_FIELD)
			|| unit.getTags().contains(dev.starmine.world.DimensionHeroes.CLOAKED); // Tosh's and Zeratul's cloak (owner 17.10)
	}

	/** The side of `caster` sees cloaked units within `radius` of a point for `ticks` (a scanner sweep). */
	static void scan(ServerLevel level, Entity caster, Vec3 centre, double radius, int ticks) {
		SCANS.add(new Scan(level, centre, radius, caster, level.getGameTime() + ticks));
	}

	static void tick(StarUnit unit, ServerLevel level) {
		AbilityState state = unit.abilities;
		boolean forced = forced(unit);
		if (forced && !unit.cloaked()) {
			unit.setCloaked(true);
			state.forcedCloak = true;
		} else if (!forced && state.forcedCloak) {
			state.forcedCloak = false;
			unit.setCloaked(false);
		}
		if (!unit.cloaked()) {
			if (unit.revealed()) unit.setRevealed(false);
			return;
		}
		if (!forced && !state.forcedCloak) {
			unit.setEnergy(unit.energy() - DRAIN_PER_TICK);
			if (unit.energy() <= 0) {
				unit.setEnergy(0);
				unit.setCloaked(false);
				return;
			}
		}
		if (unit.tickCount % 10 != 0) return;
		Entity body = unit.body();
		List<Entity> detectors = new ArrayList<>(level.getEntitiesOfClass(StarUnit.class, body.getBoundingBox().inflate(DETECT_SEARCH),
			other -> other.isAlive() && other.kind.detector() && !other.underConstruction() && other.distanceTo(body) <= other.kind.sight()));
		// owner 17.10: anyone under a detector potion detects as a detector does, within 11 SC2 units
		detectors.addAll(level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, body.getBoundingBox().inflate(POTION_DETECT),
			other -> other.isAlive() && !(other instanceof StarUnit) && other.distanceTo(body) <= POTION_DETECT
				&& other.hasEffect(dev.starmine.gear.TraitPotions.effect(dev.starmine.entity.Trait.DETECTOR))));
		long now = level.getGameTime();
		for (Iterator<Scan> it = SCANS.iterator(); it.hasNext(); ) {
			Scan scan = it.next();
			if (scan.until < now || scan.level.getServer() != level.getServer()) it.remove();
			else if (scan.level == level && Abilities.horizontal(scan.centre, body.position()) <= scan.radius) detectors.add(scan.side);
		}
		DETECTORS.put(unit, detectors);
		unit.setRevealed(Status.has(unit, Status.REVEALED) || detectors.stream().anyMatch(detector -> !Faction.allied(detector, unit)));
		// whoever hunts it and cannot see it loses it
		for (Mob hunter : level.getEntitiesOfClass(Mob.class, body.getBoundingBox().inflate(48), mob -> mob.getTarget() == body))
			if (!seen(unit, hunter)) hunter.setTarget(null);
	}

	/** Whether `looker` sees `unit` (its own side, or a side with a detector or a scan near it); everyone sees an uncloaked or revealed unit. */
	public static boolean seen(StarUnit unit, @Nullable Entity looker) {
		if (!unit.cloaked() || looker == null || Faction.allied(unit, looker) || Status.has(unit, Status.REVEALED)) return true;
		List<Entity> detectors = DETECTORS.get(unit);
		if (detectors == null) return false;
		for (Entity detector : detectors) if (detector == looker || Faction.allied(detector, looker)) return true;
		return false;
	}
}
