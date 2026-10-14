package dev.starmine.ability;

import dev.starmine.entity.StarUnit;
import dev.starmine.faction.Faction;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

/**
 * Cloaking and detection (owner 14.10, docs/ABILITIES.md), one rule for every race:
 * - a cloaked unit (the ghost's and banshee's cloak for SC2 25 energy and 0.9 a second; the dark
 *   templar and the observer always) cannot be seen or targeted by its foes; area damage still hits it;
 * - it is seen by its own side and by every side with a detector ({@link dev.starmine.entity.UnitKind#detector})
 *   whose sight reaches it — the detector's allies see it too;
 * - who targets a cloaked unit it cannot see loses it (players, monsters and units alike: vanilla
 *   targeting asks {@link #seen} through the visibility mixin).
 * The client draws a cloaked unit of its own side see-through, a revealed one plainly, any other one
 * as a faint shimmer.
 */
public final class Cloak {
	/** SC2 cloak upkeep, 0.9 energy a second, per tick. */
	public static final float DRAIN_PER_TICK = 0.9f / 20;
	/** The detectors near each cloaked unit, refreshed twice a second (server). */
	private static final Map<StarUnit, List<StarUnit>> DETECTORS = new WeakHashMap<>();

	private Cloak() {
	}

	static void toggle(StarUnit unit) {
		unit.setCloaked(!unit.cloaked());
	}

	static void tick(StarUnit unit, ServerLevel level) {
		boolean permanent = unit.kind.permanentlyCloaked();
		if (permanent && !unit.cloaked()) unit.setCloaked(true);
		if (!unit.cloaked()) {
			if (unit.revealed()) unit.setRevealed(false);
			return;
		}
		if (!permanent) {
			unit.setEnergy(unit.energy() - DRAIN_PER_TICK);
			if (unit.energy() <= 0) {
				unit.setEnergy(0);
				unit.setCloaked(false);
				return;
			}
		}
		if (unit.tickCount % 10 != 0) return;
		List<StarUnit> detectors = level.getEntitiesOfClass(StarUnit.class, unit.getBoundingBox().inflate(16),
			other -> other.isAlive() && other.kind.detector() && !other.underConstruction() && other.distanceTo(unit.body()) <= other.kind.sight());
		DETECTORS.put(unit, detectors);
		unit.setRevealed(detectors.stream().anyMatch(detector -> !Faction.allied(detector, unit)));
		// whoever hunts it and cannot see it loses it
		for (Mob hunter : level.getEntitiesOfClass(Mob.class, unit.getBoundingBox().inflate(48), mob -> mob.getTarget() == unit.body()))
			if (!seen(unit, hunter)) hunter.setTarget(null);
	}

	/** Whether `looker` sees `unit` (its own side, or a side with a detector near it); everyone sees an uncloaked unit. */
	public static boolean seen(StarUnit unit, @Nullable Entity looker) {
		if (!unit.cloaked() || looker == null || Faction.allied(unit, looker)) return true;
		List<StarUnit> detectors = DETECTORS.get(unit);
		if (detectors == null) return false;
		for (StarUnit detector : detectors) if (detector == looker || Faction.allied(detector, looker)) return true;
		return false;
	}
}
