package dev.starmine.race;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveLogic;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import static dev.starmine.entity.UnitKind.*;
import static dev.starmine.race.Times.normal;
import static dev.starmine.race.Times.seconds;

/**
 * Zerg (SC2 LotV, from VoidMulti Data): units hatch from larvae of a hatchery (the queen trains at the
 * hatchery), a drone mutates into a building (it is used up), overlords give supply, the hatchery
 * becomes a lair and a hive (tier, {@link Research#LAIR}, {@link Research#HIVE}).
 */
public final class ZergRace implements RaceRules {
	static final ZergRace INSTANCE = new ZergRace();

	private ZergRace() {
	}

	@Override
	public Race race() {
		return Race.ZERG;
	}

	@Override
	public UnitKind main() {
		return HATCHERY;
	}

	@Override
	public UnitKind worker() {
		return DRONE;
	}

	@Override
	public UnitKind gasBuilding() {
		return EXTRACTOR;
	}

	@Override
	public List<UnitKind> buildings() {
		return List.of(HATCHERY, EXTRACTOR, SPAWNING_POOL, EVOLUTION_CHAMBER, SPINE_CRAWLER, SPORE_CRAWLER, ROACH_WARREN, BANELING_NEST,
			HYDRALISK_DEN, SPIRE, INFESTATION_PIT, ULTRALISK_CAVERN);
	}

	@Override
	public Economy economy(UnitKind kind) {
		return switch (kind) {
			case DRONE -> new Economy(50, 0, 1, normal(17), 0, 1);
			case ZERGLING -> new Economy(25, 0, 0.5, normal(24), 0, 1);
			case OVERLORD -> new Economy(100, 0, 0, normal(25), 8, 0);
			case OVERSEER -> new Economy(150, 50, 0, normal(16.67), 8, 0);
			case QUEEN -> new Economy(175, 0, 2, normal(50), 0, 2);
			case ROACH -> new Economy(75, 25, 2, normal(27), 0, 2);
			case RAVAGER -> new Economy(100, 100, 3, normal(12.14), 0, 4);
			case BANELING -> new Economy(50, 25, 0.5, normal(20), 0, 2);
			case HYDRALISK -> new Economy(100, 50, 2, normal(33), 0, 2);
			case LURKER -> new Economy(150, 150, 3, normal(25), 0, 4);
			case MUTALISK -> new Economy(100, 100, 2, normal(33), 0, 0);
			case CORRUPTOR -> new Economy(150, 100, 2, normal(40), 0, 0);
			case BROOD_LORD -> new Economy(300, 250, 4, normal(33.57), 0, 0);
			case INFESTOR -> new Economy(100, 150, 2, normal(50), 0, 2);
			case SWARM_HOST -> new Economy(100, 75, 3, normal(40), 0, 4);
			case VIPER -> new Economy(100, 200, 3, normal(40), 0, 0);
			case ULTRALISK -> new Economy(275, 200, 6, normal(55), 0, 8);
			case HATCHERY -> new Economy(325, 0, 0, normal(71), 8, 0);
			case SPAWNING_POOL -> new Economy(200, 0, 0, seconds(46), 0, 0);
			case EVOLUTION_CHAMBER -> new Economy(75, 0, 0, seconds(25), 0, 0);
			case ROACH_WARREN -> new Economy(150, 0, 0, seconds(39), 0, 0);
			case BANELING_NEST -> new Economy(100, 50, 0, seconds(43), 0, 0);
			case HYDRALISK_DEN -> new Economy(100, 100, 0, seconds(29), 0, 0);
			case SPIRE -> new Economy(200, 200, 0, seconds(71), 0, 0);
			case INFESTATION_PIT -> new Economy(100, 100, 0, seconds(36), 0, 0);
			case ULTRALISK_CAVERN -> new Economy(150, 200, 0, seconds(46), 0, 0);
			case EXTRACTOR -> new Economy(25, 0, 0, seconds(21), 0, 0);
			case SPINE_CRAWLER -> new Economy(100, 0, 0, seconds(36), 0, 0);
			case SPORE_CRAWLER -> new Economy(75, 0, 0, seconds(21), 0, 0);
			default -> new Economy(0, 0, 0, 0, 0, kind == LARVA || kind == EGG ? 0 : 1); // broodling, locust, changeling, larva, egg
		};
	}

	@Override
	public @Nullable UnitKind requires(UnitKind kind) {
		return switch (kind) {
			case ZERGLING, QUEEN, SPINE_CRAWLER, SPORE_CRAWLER, ROACH_WARREN, BANELING_NEST, EVOLUTION_CHAMBER -> SPAWNING_POOL;
			case ROACH, RAVAGER -> ROACH_WARREN;
			case BANELING -> BANELING_NEST;
			case HYDRALISK, LURKER -> HYDRALISK_DEN;
			case MUTALISK, CORRUPTOR, BROOD_LORD -> SPIRE;
			case INFESTOR, SWARM_HOST, VIPER -> INFESTATION_PIT;
			case ULTRALISK -> ULTRALISK_CAVERN;
			default -> null;
		};
	}

	@Override
	public int tier(UnitKind kind) {
		return switch (kind) {
			case HYDRALISK_DEN, SPIRE, INFESTATION_PIT, OVERSEER, RAVAGER, LURKER, INFESTOR, SWARM_HOST -> 2;
			case ULTRALISK_CAVERN, VIPER, BROOD_LORD -> 3;
			default -> 1;
		};
	}

	/** Every zerg unit hatches from a larva (morphs from other units are not modelled); the queen trains at a hatchery. */
	@Override
	public @Nullable UnitKind producer(UnitKind kind) {
		return switch (kind) {
			case QUEEN -> HATCHERY;
			case DRONE, ZERGLING, OVERLORD, OVERSEER, ROACH, RAVAGER, BANELING, HYDRALISK, LURKER, MUTALISK, CORRUPTOR, BROOD_LORD,
				INFESTOR, SWARM_HOST, VIPER, ULTRALISK -> LARVA;
			default -> null;
		};
	}

	@Override
	public char hotkey(UnitKind kind) {
		return switch (kind) {
			case DRONE, HYDRALISK_DEN -> 'D';
			case ZERGLING -> 'Z';
			case OVERLORD, EVOLUTION_CHAMBER, VIPER -> 'V';
			case OVERSEER -> 'O';
			case QUEEN -> 'Q';
			case ROACH, ROACH_WARREN, RAVAGER -> 'R';
			case BANELING, BROOD_LORD -> 'B';
			case HYDRALISK, HATCHERY -> 'H';
			case LURKER -> 'L';
			case MUTALISK -> 'T';
			case CORRUPTOR, SPINE_CRAWLER -> 'C';
			case INFESTOR, INFESTATION_PIT -> 'I';
			case SWARM_HOST -> 'A';
			case ULTRALISK, ULTRALISK_CAVERN -> 'U';
			case SPAWNING_POOL, SPIRE -> 'S';
			case EXTRACTOR -> 'E';
			case SPORE_CRAWLER -> 'P';
			case BANELING_NEST -> 'N';
			default -> ' ';
		};
	}

	@Override
	public boolean upgradeAllowed(Hive base, int level) {
		return base.tier >= level; // level 2 needs a lair, 3 a hive
	}

	/** The drone mutates into the building (it is used up). */
	@Override
	public @Nullable StarUnit placeBuilding(StarUnit worker, ServerLevel level, Hive base, UnitKind kind, BlockPos site) {
		return HiveLogic.mutate(worker, level, kind, site);
	}
}
