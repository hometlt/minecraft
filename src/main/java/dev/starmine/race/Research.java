package dev.starmine.race;

import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import java.util.ArrayList;
import java.util.List;

/**
 * Research at a building (SC2): weapons and armor of the whole base (engineering bay, evolution
 * chamber, forge; 3 levels each, levels 2-3 need the race's tech, {@link RaceRules#upgradeAllowed}),
 * the zerg hatchery's lair and hive (its tier), and unit upgrades done once (owner's plan,
 * docs/ABILITIES.md): metabolic boost (zergling speed, spawning pool), centrifugal hooks (baneling
 * speed, baneling nest), combat shield (marine health, a barracks with a tech lab). One owner for player
 * commands and the AI; a unit upgrade works through {@link #speedFactor} and {@link #extraHealth}.
 */
public enum Research {
	WEAPONS('E'), ARMOR('A'), LAIR('L'), HIVE('H'), METABOLIC_BOOST('M'), CENTRIFUGAL_HOOKS('E'), COMBAT_SHIELD('S');

	public final char hotkey;

	Research(char hotkey) {
		this.hotkey = hotkey;
	}

	public String id() {
		return name().toLowerCase();
	}

	/** Where it is researched. */
	public boolean at(UnitKind building) {
		return switch (this) {
			case WEAPONS, ARMOR -> building == UnitKind.ENGINEERING_BAY || building == UnitKind.EVOLUTION_CHAMBER || building == UnitKind.FORGE;
			case LAIR, HIVE -> building == UnitKind.HATCHERY;
			case METABOLIC_BOOST -> building == UnitKind.SPAWNING_POOL;
			case CENTRIFUGAL_HOOKS -> building == UnitKind.BANELING_NEST;
			case COMBAT_SHIELD -> building == UnitKind.BARRACKS;
		};
	}

	/** A unit upgrade done once (not weapons, armor or a tier). */
	public boolean once() {
		return this == METABOLIC_BOOST || this == CENTRIFUGAL_HOOKS || this == COMBAT_SHIELD;
	}

	/** SC2 speed of a kind with its base's upgrades against its own: a zergling 6.58 against 4.13, a baneling 4.13 against 3.5. */
	public static double speedFactor(Hive base, UnitKind kind) {
		if (kind == UnitKind.ZERGLING && base.researched.contains(METABOLIC_BOOST.id())) return 6.58 / 4.13;
		if (kind == UnitKind.BANELING && base.researched.contains(CENTRIFUGAL_HOOKS.id())) return 4.13 / 3.5;
		return 1;
	}

	/** SC2 hit points the base's upgrades add to a kind (combat shield: a marine +10). */
	public static double extraHealth(Hive base, UnitKind kind) {
		return kind == UnitKind.MARINE && base.researched.contains(COMBAT_SHIELD.id()) ? 10 : 0;
	}

	/** What a building researches, in card order. */
	public static List<Research> of(UnitKind building) {
		List<Research> list = new ArrayList<>();
		for (Research research : values()) if (research.at(building)) list.add(research);
		return list;
	}

	/** The level it would reach next (weapons/armor 1-3; lair 2, hive 3 as tiers). */
	public int nextLevel(Hive base) {
		return switch (this) {
			case WEAPONS -> base.attackLevel + 1;
			case ARMOR -> base.armorLevel + 1;
			case LAIR -> 2;
			case HIVE -> 3;
			default -> 1;
		};
	}

	/** Minerals and vespene (SC2): weapons/armor 100/175/250 each; lair 150/100; hive 200/150. */
	public int[] cost(int level) {
		return switch (this) {
			case WEAPONS, ARMOR -> new int[] {25 + 75 * level, 25 + 75 * level};
			case LAIR -> new int[] {150, 100};
			case HIVE -> new int[] {200, 150};
			case METABOLIC_BOOST, CENTRIFUGAL_HOOKS, COMBAT_SHIELD -> new int[] {100, 100};
		};
	}

	public int ticks(int level) {
		return switch (this) {
			case WEAPONS, ARMOR -> (114 + 22 * (level - 1)) * 20;
			case LAIR -> 57 * 20;
			case HIVE -> 71 * 20;
			case METABOLIC_BOOST, COMBAT_SHIELD -> 79 * 20; // SC2 LotV
			case CENTRIFUGAL_HOOKS -> 71 * 20;
		};
	}

	/** Why it cannot start now for this base at this building (a lang key), or null. */
	public String refusal(Hive base, UnitKind building) {
		int level = nextLevel(base);
		switch (this) {
			case WEAPONS, ARMOR -> {
				if (level > 3) return "message.starmine.research_done";
				if (!building.race.rules().upgradeAllowed(base, level)) return "message.starmine.need_tech";
			}
			case LAIR -> {
				if (base.tier >= 2) return "message.starmine.research_done";
				if (base.count(UnitKind.SPAWNING_POOL) == 0) return "message.starmine.need_tech";
			}
			case HIVE -> {
				if (base.tier >= 3) return "message.starmine.research_done";
				if (base.tier < 2 || base.count(UnitKind.INFESTATION_PIT) == 0) return "message.starmine.need_tech";
			}
			case METABOLIC_BOOST, CENTRIFUGAL_HOOKS, COMBAT_SHIELD -> {
				if (base.researched.contains(id())) return "message.starmine.research_done";
				if (this == CENTRIFUGAL_HOOKS && base.tier < 2) return "message.starmine.need_tech";
			}
		}
		int[] cost = cost(level);
		if (base.minerals < cost[0]) return "message.starmine.need_minerals";
		if (base.vespene < cost[1]) return "message.starmine.need_vespene";
		return null;
	}

	/** Done: the base gets the level. */
	public void apply(Hive base) {
		switch (this) {
			case WEAPONS -> base.attackLevel++;
			case ARMOR -> base.armorLevel++;
			case LAIR -> base.tier = Math.max(base.tier, 2);
			case HIVE -> base.tier = Math.max(base.tier, 3);
			default -> base.researched.add(id());
		}
	}

	public static Research byId(String id) {
		for (Research research : values()) if (research.id().equals(id)) return research;
		return null;
	}
}
