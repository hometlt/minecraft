package dev.starmine.race;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import static dev.starmine.entity.UnitKind.*;
import static dev.starmine.race.Times.seconds;

/**
 * Terran (SC2 LotV, without tech labs and reactors): units train in queues at production buildings,
 * an SCV constructs a building and it grows only while an SCV builds it, supply depots give supply,
 * weapons and armor 2-3 need an armory.
 */
public final class TerranRace implements RaceRules {
	static final TerranRace INSTANCE = new TerranRace();

	private TerranRace() {
	}

	@Override
	public Race race() {
		return Race.TERRAN;
	}

	@Override
	public UnitKind main() {
		return COMMAND_CENTER;
	}

	@Override
	public UnitKind worker() {
		return SCV;
	}

	@Override
	public UnitKind gasBuilding() {
		return REFINERY;
	}

	@Override
	public UnitKind supply() {
		return SUPPLY_DEPOT;
	}

	@Override
	public List<UnitKind> aiBuildOrder() {
		return List.of(BARRACKS, ENGINEERING_BAY, BUNKER, FACTORY, MISSILE_TURRET, BARRACKS, STARPORT, SENSOR_TOWER, ARMORY, GHOST_ACADEMY, FUSION_CORE);
	}

	@Override
	public List<UnitKind> buildings() {
		return List.of(COMMAND_CENTER, SUPPLY_DEPOT, REFINERY, BARRACKS, ENGINEERING_BAY, BUNKER, MISSILE_TURRET, SENSOR_TOWER,
			FACTORY, STARPORT, ARMORY, GHOST_ACADEMY, FUSION_CORE);
	}

	@Override
	public Economy economy(UnitKind kind) {
		return switch (kind) {
			case SCV -> new Economy(50, 0, 1, seconds(12), 0, 1);
			case MARINE -> new Economy(50, 0, 1, seconds(18), 0, 1);
			case MARAUDER -> new Economy(100, 25, 2, seconds(21), 0, 2);
			case REAPER -> new Economy(50, 50, 1, seconds(32), 0, 1);
			case GHOST -> new Economy(150, 125, 2, seconds(29), 0, 2);
			case HELLION -> new Economy(100, 0, 2, seconds(21), 0, 2);
			case HELLBAT -> new Economy(100, 0, 2, seconds(21), 0, 4);
			case WIDOW_MINE -> new Economy(75, 25, 2, seconds(21), 0, 2);
			case SIEGE_TANK -> new Economy(150, 125, 3, seconds(32), 0, 4);
			case THOR -> new Economy(300, 200, 6, seconds(43), 0, 8);
			case VIKING -> new Economy(150, 75, 2, seconds(30), 0, 0);
			case VIKING_ASSAULT -> new Economy(150, 75, 2, seconds(30), 0, 2);
			case LIBERATOR -> new Economy(150, 125, 3, seconds(43), 0, 0);
			case MEDIVAC -> new Economy(100, 100, 2, seconds(30), 0, 0);
			case BANSHEE -> new Economy(150, 100, 3, seconds(43), 0, 0);
			case BATTLECRUISER -> new Economy(400, 300, 6, seconds(64), 0, 0);
			case CYCLONE -> new Economy(125, 50, 3, seconds(32), 0, 4);
			case RAVEN -> new Economy(100, 150, 2, seconds(34), 0, 0);
			case SENSOR_TOWER -> new Economy(125, 100, 0, seconds(18), 0, 0);
			case COMMAND_CENTER -> new Economy(400, 0, 0, seconds(71), 15, 0);
			case SUPPLY_DEPOT -> new Economy(100, 0, 0, seconds(21), 8, 0);
			case BARRACKS -> new Economy(150, 0, 0, seconds(46), 0, 0);
			case FACTORY -> new Economy(150, 100, 0, seconds(43), 0, 0);
			case STARPORT -> new Economy(150, 100, 0, seconds(36), 0, 0);
			case ENGINEERING_BAY -> new Economy(125, 0, 0, seconds(25), 0, 0);
			case ARMORY -> new Economy(150, 50, 0, seconds(46), 0, 0);
			case GHOST_ACADEMY -> new Economy(150, 50, 0, seconds(29), 0, 0);
			case FUSION_CORE -> new Economy(150, 150, 0, seconds(46), 0, 0);
			case BUNKER -> new Economy(100, 0, 0, seconds(29), 0, 0);
			case MISSILE_TURRET -> new Economy(100, 0, 0, seconds(18), 0, 0);
			case REFINERY -> new Economy(75, 0, 0, seconds(21), 0, 0);
			default -> new Economy(0, 0, 0, 0, 0, 0);
		};
	}

	@Override
	public @Nullable UnitKind requires(UnitKind kind) {
		return switch (kind) {
			case SCV, SUPPLY_DEPOT, ENGINEERING_BAY, REFINERY -> COMMAND_CENTER;
			case BARRACKS -> SUPPLY_DEPOT;
			case MARINE, MARAUDER, REAPER, FACTORY, BUNKER, GHOST_ACADEMY -> BARRACKS;
			case GHOST -> GHOST_ACADEMY;
			case MISSILE_TURRET, SENSOR_TOWER -> ENGINEERING_BAY;
			case HELLION, WIDOW_MINE, SIEGE_TANK, CYCLONE, STARPORT, ARMORY -> FACTORY;
			case THOR, HELLBAT -> ARMORY;
			case VIKING, VIKING_ASSAULT, MEDIVAC, BANSHEE, LIBERATOR, RAVEN, FUSION_CORE -> STARPORT;
			case BATTLECRUISER -> FUSION_CORE;
			default -> null;
		};
	}

	@Override
	public @Nullable UnitKind producer(UnitKind kind) {
		return switch (kind) {
			case SCV -> COMMAND_CENTER;
			case MARINE, MARAUDER, REAPER, GHOST -> BARRACKS;
			case HELLION, HELLBAT, WIDOW_MINE, CYCLONE, SIEGE_TANK, THOR -> FACTORY;
			case VIKING, MEDIVAC, RAVEN, BANSHEE, LIBERATOR, BATTLECRUISER -> STARPORT;
			default -> null;
		};
	}

	@Override
	public char hotkey(UnitKind kind) {
		return switch (kind) {
			case SCV, SIEGE_TANK, SUPPLY_DEPOT, STARPORT -> 'S';
			case MARINE, ARMORY -> 'A';
			case MARAUDER, MEDIVAC, WIDOW_MINE -> 'D';
			case REAPER, REFINERY, HELLBAT -> 'R';
			case LIBERATOR -> 'L';
			case CYCLONE -> 'Y';
			case RAVEN, SENSOR_TOWER -> 'R';
			case GHOST, GHOST_ACADEMY -> 'G';
			case HELLION, BANSHEE, ENGINEERING_BAY -> 'E';
			case THOR, MISSILE_TURRET -> 'T';
			case VIKING -> 'V';
			case BATTLECRUISER, BARRACKS -> 'B';
			case COMMAND_CENTER, FUSION_CORE -> 'C';
			case BUNKER -> 'U';
			case FACTORY -> 'F';
			default -> ' ';
		};
	}

	@Override
	public boolean upgradeAllowed(Hive base, int level) {
		return level == 1 || base.count(ARMORY) > 0;
	}

	/**
	 * SC2: the marauder, ghost, siege tank, thor, banshee and battlecruiser need a tech lab on their
	 * building ({@link dev.starmine.ability.Modes#TECHLAB}, built with its ability).
	 */
	@Override
	public boolean producerReady(StarUnit producer, UnitKind kind) {
		return !needsTechLab(kind) || producer.mode() == dev.starmine.ability.Modes.TECHLAB;
	}

	public static boolean needsTechLab(UnitKind kind) {
		return kind == MARAUDER || kind == GHOST || kind == SIEGE_TANK || kind == THOR || kind == BANSHEE || kind == BATTLECRUISER;
	}

	/**
	 * A building of the base under construction that no SCV builds gets the nearest free SCV (it went
	 * on without one forever: the wild base's barracks stood unfinished while the AI waited for it).
	 */
	@Override
	public void tendConstruction(StarUnit main, ServerLevel level, Hive base) {
		net.minecraft.world.phys.AABB around = main.getBoundingBox().inflate(48);
		List<StarUnit> scvs = level.getEntitiesOfClass(StarUnit.class, around, unit -> unit.kind == SCV && unit.isAlive() && base.id.equals(unit.hiveId()));
		for (StarUnit building : level.getEntitiesOfClass(StarUnit.class, around, unit -> unit.kind.building() && unit.isAlive()
				&& unit.underConstruction() && base.id.equals(unit.hiveId()))) {
			if (scvs.stream().anyMatch(scv -> building.getUUID().equals(scv.constructing()))) continue;
			scvs.stream().filter(scv -> scv.buildKind() == null && scv.constructing() == null && scv.raidTarget() == null && scv.cargoBlocks == 0)
				.min(java.util.Comparator.comparingDouble(scv -> scv.distanceToSqr(building))).ifPresent(scv -> {
					scv.orderBuild(building.kind, building.blockPosition());
					scv.setConstructing(building.getUUID());
				});
		}
	}

	/** The SCV places the building and then builds it: it grows only while the SCV stays ({@link StarUnit#setConstructing}). */
	@Override
	public @Nullable StarUnit placeBuilding(StarUnit worker, ServerLevel level, Hive base, UnitKind kind, BlockPos site) {
		StarUnit building = Placement.put(worker, level, base, kind, site);
		if (building != null) worker.setConstructing(building.getUUID());
		return building;
	}

	/** Burning with red fire under black smoke. */
	@Override
	public List<net.minecraft.core.particles.ParticleOptions> damagedParticles() {
		return List.of(net.minecraft.core.particles.ParticleTypes.FLAME, net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE);
	}
}
