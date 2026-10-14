package dev.starmine.race;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * What one race does its own way (docs/ARCHITECTURE.md). Common code — bases, player commands, the AI,
 * the command card — asks these rules instead of comparing races; a new race is a new implementation.
 *
 * Data: SC2 numbers per kind of the race ({@link #economy}, {@link #requires}, {@link #tier},
 * {@link #producer}, {@link #hotkey}). Behaviour: how a worker puts a building down
 * ({@link #placeBuilding}: an SCV constructs it, a drone becomes it, a probe warps it in) and where a
 * building may stand ({@link #siteRefusal}: protoss need pylon power).
 */
public interface RaceRules {
	UnitKind.Race race();

	/** The main building (founds a base), the worker, the vespene building. */
	UnitKind main();

	UnitKind worker();

	UnitKind gasBuilding();

	/** What gives supply (a unit for zerg, a building for the others). */
	UnitKind supply();

	/** The buildings a wild base of the race puts up, in order (a kind listed twice: two of them). */
	List<UnitKind> aiBuildOrder();

	/** What its worker builds, in command-card order (the first page holds 10, the rest go on the second). */
	List<UnitKind> buildings();

	/** SC2 costs, supply, build time (only for kinds of this race). */
	UnitKind.Economy economy(UnitKind kind);

	/** The building a kind needs first (SC2 tech), or null. */
	@Nullable UnitKind requires(UnitKind kind);

	/** The main building tier a kind needs (zerg: 2 lair, 3 hive); 1 for the others. */
	default int tier(UnitKind kind) {
		return 1;
	}

	/** What trains a unit: a building, a zerg larva, or the unit it morphs from ({@link #morphFrom}); null for buildings and summons. */
	@Nullable UnitKind producer(UnitKind kind);

	/** The unit a kind morphs from (zerg: a zergling becomes a baneling…), or null; see {@link Morph}. */
	default @Nullable UnitKind morphFrom(UnitKind kind) {
		return null;
	}

	/** The kind's SC2 grid hotkey on the command card. */
	char hotkey(UnitKind kind);

	/** Whether this building's weapons/armor research may reach `level` (2 and 3 need more tech). */
	boolean upgradeAllowed(Hive base, int level);

	/** Why a building of this race may not stand at `site` for this base (a lang key), or null. */
	default @Nullable String siteRefusal(Level level, UnitKind kind, BlockPos site, @Nullable java.util.UUID baseId) {
		return null;
	}

	/**
	 * A worker of the race has reached its site: the building is put down the race's way (paid then).
	 * Returns the new building, or null when it was refused (the worker gives the order up).
	 */
	@Nullable StarUnit placeBuilding(StarUnit worker, ServerLevel level, Hive base, UnitKind kind, BlockPos site);

	/** Whether this producer may train `kind` now (terran: advanced units need a tech lab on the building). */
	default boolean producerReady(StarUnit producer, UnitKind kind) {
		return true;
	}

	/** Whether this building works now (protoss: powered by a pylon). */
	default boolean powered(StarUnit building) {
		return true;
	}

	/**
	 * A wild base's AI looks after its buildings under construction (terran: one an SCV left — called
	 * off, killed — gets a free SCV, as in SC2 any SCV may go on with it). Nothing for the other races:
	 * their buildings build themselves.
	 */
	default void tendConstruction(StarUnit main, ServerLevel level, Hive base) {
	}
}
