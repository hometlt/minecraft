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
	public List<UnitKind> buildings() {
		return List.of(COMMAND_CENTER, SUPPLY_DEPOT, REFINERY, BARRACKS, ENGINEERING_BAY, BUNKER, MISSILE_TURRET,
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
			case SIEGE_TANK -> new Economy(150, 125, 3, seconds(32), 0, 4);
			case THOR -> new Economy(300, 200, 6, seconds(43), 0, 8);
			case VIKING -> new Economy(150, 75, 2, seconds(30), 0, 0);
			case MEDIVAC -> new Economy(100, 100, 2, seconds(30), 0, 0);
			case BANSHEE -> new Economy(150, 100, 3, seconds(43), 0, 0);
			case BATTLECRUISER -> new Economy(400, 300, 6, seconds(64), 0, 0);
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
			case MISSILE_TURRET -> ENGINEERING_BAY;
			case HELLION, SIEGE_TANK, STARPORT, ARMORY -> FACTORY;
			case THOR -> ARMORY;
			case VIKING, MEDIVAC, BANSHEE, FUSION_CORE -> STARPORT;
			case BATTLECRUISER -> FUSION_CORE;
			default -> null;
		};
	}

	@Override
	public @Nullable UnitKind producer(UnitKind kind) {
		return switch (kind) {
			case SCV -> COMMAND_CENTER;
			case MARINE, MARAUDER, REAPER, GHOST -> BARRACKS;
			case HELLION, SIEGE_TANK, THOR -> FACTORY;
			case VIKING, MEDIVAC, BANSHEE, BATTLECRUISER -> STARPORT;
			default -> null;
		};
	}

	@Override
	public char hotkey(UnitKind kind) {
		return switch (kind) {
			case SCV, SIEGE_TANK, SUPPLY_DEPOT, STARPORT -> 'S';
			case MARINE, ARMORY -> 'A';
			case MARAUDER, MEDIVAC -> 'D';
			case REAPER, REFINERY -> 'R';
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

	/** The SCV places the building and then builds it: it grows only while the SCV stays ({@link StarUnit#setConstructing}). */
	@Override
	public @Nullable StarUnit placeBuilding(StarUnit worker, ServerLevel level, Hive base, UnitKind kind, BlockPos site) {
		StarUnit building = Placement.put(worker, level, base, kind, site);
		if (building != null) worker.setConstructing(building.getUUID());
		return building;
	}
}
