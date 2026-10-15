package dev.starmine.possess;

import dev.starmine.entity.UnitKind;

/**
 * How a unit's weapon module looks in a player's hand (owner 16.10: each unit its own weapon, chunky
 * industrial guns): the family of its model (`models/item/weapon_<family>.json`, made by
 * `tools/gen_weapon_models.py`), picked by the item property `starmine:weapon`.
 */
public enum WeaponLook {
	GAUSS_RIFLE, LAUNCHER, SNIPER, FLAMER, CANNON, TOOL, PSI_BLADE, DISRUPTOR, CLAWS, SPITTER;

	public static WeaponLook of(UnitKind kind) {
		return switch (kind) {
			case MARINE, RAYNOR, REAPER -> GAUSS_RIFLE;
			case MARAUDER, VIKING, THOR, MISSILE_TURRET, BANSHEE -> LAUNCHER;
			case GHOST -> SNIPER;
			case HELLION -> FLAMER;
			case SCV, PROBE, DRONE -> TOOL;
			case ZEALOT, DARK_TEMPLAR, ARTANIS -> PSI_BLADE;
			case ZERGLING, ULTRALISK, BANELING, BROODLING, KERRIGAN, LOCUST -> CLAWS;
			default -> switch (kind.race) {
				case TERRAN -> CANNON;
				case PROTOSS -> DISRUPTOR;
				case ZERG -> SPITTER;
			};
		};
	}

	/** The value of the item property for a module of this look (0: no unit, the plain module). */
	public float property() {
		return (ordinal() + 0.5f) / 16f;
	}
}
