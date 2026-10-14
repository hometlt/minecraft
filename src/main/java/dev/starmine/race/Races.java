package dev.starmine.race;

import dev.starmine.entity.UnitKind;

/** The rules of each race ({@link UnitKind.Race#rules()}). */
public final class Races {
	private Races() {
	}

	public static RaceRules of(UnitKind.Race race) {
		return switch (race) {
			case ZERG -> ZergRace.INSTANCE;
			case TERRAN -> TerranRace.INSTANCE;
			case PROTOSS -> ProtossRace.INSTANCE;
		};
	}
}
