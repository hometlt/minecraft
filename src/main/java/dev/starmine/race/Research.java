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
 *
 * Abilities SC2 opens by research ({@link #of(dev.starmine.ability.Ability)}): the stimpack and concussive
 * shells (a barracks with a tech lab), charge and blink (twilight council). Without them a marine
 * stimmed and zealots charged from the first minute, and the balance stand's early armies were not
 * SC2's early armies. A unit with no base (a lone wild one) keeps them all.
 */
public enum Research {
	WEAPONS('E'), ARMOR('A'), LAIR('L'), HIVE('H'), METABOLIC_BOOST('M'), CENTRIFUGAL_HOOKS('E'), COMBAT_SHIELD('S'),
	STIMPACK('T'), CONCUSSIVE_SHELLS('C'), CHARGE('C'), BLINK('B'),
	// owner 17.10: every SC2 upgrade of weapons, armor and shields, 3 levels each, by the kinds they are for ({@link #weaponsOf}, {@link #armorOf})
	MELEE('Z'), VEHICLE_WEAPONS('V'), SHIP_WEAPONS('S'), PLATING('P'), FLYER_ATTACKS('F'), FLYER_CARAPACE('C'), SHIELDS('S'),
	AIR_WEAPONS('W'), AIR_ARMOR('R');

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
			case COMBAT_SHIELD, STIMPACK, CONCUSSIVE_SHELLS -> building == UnitKind.BARRACKS;
			case CHARGE, BLINK -> building == UnitKind.TWILIGHT_COUNCIL;
			case MELEE -> building == UnitKind.EVOLUTION_CHAMBER;
			case VEHICLE_WEAPONS, SHIP_WEAPONS, PLATING -> building == UnitKind.ARMORY;
			case FLYER_ATTACKS, FLYER_CARAPACE -> building == UnitKind.SPIRE;
			case SHIELDS -> building == UnitKind.FORGE;
			case AIR_WEAPONS, AIR_ARMOR -> building == UnitKind.CYBERNETICS_CORE;
		};
	}

	/** An upgrade of 3 levels (weapons, armor, shields of some kinds). */
	public boolean leveled() {
		return switch (this) {
			case WEAPONS, ARMOR, MELEE, VEHICLE_WEAPONS, SHIP_WEAPONS, PLATING, FLYER_ATTACKS, FLYER_CARAPACE, SHIELDS, AIR_WEAPONS, AIR_ARMOR -> true;
			default -> false;
		};
	}

	/** A unit upgrade done once (not a leveled upgrade or a tier). */
	public boolean once() {
		return !leveled() && this != LAIR && this != HIVE;
	}

	/** The level a base has of a leveled upgrade (weapons and armor are the ground ones of every race). */
	public static int level(Hive base, Research research) {
		if (base == null || research == null) return 0;
		return switch (research) {
			case WEAPONS -> base.attackLevel;
			case ARMOR -> base.armorLevel;
			default -> base.levels.getOrDefault(research.id(), 0);
		};
	}

	/**
	 * The weapons upgrade a kind takes (SC2): terran infantry weapons, vehicle weapons, ship weapons; zerg
	 * melee, missile (WEAPONS) and flyer attacks; protoss ground and air weapons. Null for buildings.
	 */
	public static @org.jetbrains.annotations.Nullable Research weaponsOf(UnitKind kind) {
		if (kind.structure()) return null;
		boolean air = kind.flying || kind == UnitKind.VIKING_ASSAULT;
		return switch (kind.race) {
			case TERRAN -> air ? SHIP_WEAPONS : kind.biological() ? WEAPONS : VEHICLE_WEAPONS;
			case ZERG -> air ? FLYER_ATTACKS : switch (kind.attack) {
				case MELEE, SPLASH, EXPLODE -> MELEE;
				default -> WEAPONS;
			};
			case PROTOSS -> air ? AIR_WEAPONS : WEAPONS;
		};
	}

	/** The armor upgrade a kind takes (SC2): infantry armor, vehicle and ship plating; carapaces; ground and air armor. */
	public static @org.jetbrains.annotations.Nullable Research armorOf(UnitKind kind) {
		if (kind.structure()) return null;
		boolean air = kind.flying || kind == UnitKind.VIKING_ASSAULT;
		return switch (kind.race) {
			case TERRAN -> kind.biological() && kind != UnitKind.HELLBAT ? ARMOR : PLATING;
			case ZERG -> air ? FLYER_CARAPACE : ARMOR;
			case PROTOSS -> air ? AIR_ARMOR : ARMOR;
		};
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
		if (leveled()) return level(base, this) + 1;
		return switch (this) {
			case LAIR -> 2;
			case HIVE -> 3;
			default -> 1;
		};
	}

	/** Its price at a level: `data/starmine/costs/research.json`, else SC2's ({@link #sc2Cost}). */
	public dev.starmine.colony.Costs.Cost price(int level) {
		return dev.starmine.colony.Costs.research(this, level, sc2Cost(level));
	}

	/** Minerals and vespene of its price at a level. */
	public int[] cost(int level) {
		dev.starmine.colony.Costs.Cost price = price(level);
		return new int[] {price.minerals(), price.vespene()};
	}

	/**
	 * Minerals and vespene (SC2 LotV): weapons/armor 100/175/250 each; zerg melee 100/150/200; carapaces,
	 * protoss shields and air armor 150/225/300; lair 150/100; hive 200/150.
	 */
	public int[] sc2Cost(int level) {
		return switch (this) {
			case WEAPONS, ARMOR, VEHICLE_WEAPONS, SHIP_WEAPONS, PLATING, FLYER_ATTACKS, AIR_WEAPONS -> new int[] {25 + 75 * level, 25 + 75 * level};
			case MELEE -> new int[] {50 + 50 * level, 50 + 50 * level};
			case FLYER_CARAPACE, SHIELDS, AIR_ARMOR -> new int[] {75 + 75 * level, 75 + 75 * level};
			case LAIR -> new int[] {150, 100};
			case HIVE -> new int[] {200, 150};
			case METABOLIC_BOOST, CENTRIFUGAL_HOOKS, COMBAT_SHIELD, STIMPACK, CHARGE -> new int[] {100, 100};
			case CONCUSSIVE_SHELLS -> new int[] {50, 50};
			case BLINK -> new int[] {150, 150};
		};
	}

	public int ticks(int level) {
		return switch (this) {
			case WEAPONS, ARMOR, MELEE, VEHICLE_WEAPONS, SHIP_WEAPONS, PLATING, FLYER_ATTACKS, FLYER_CARAPACE, SHIELDS, AIR_WEAPONS, AIR_ARMOR ->
				(114 + 22 * (level - 1)) * 20;
			case LAIR -> 57 * 20;
			case HIVE -> 71 * 20;
			case METABOLIC_BOOST, COMBAT_SHIELD -> 79 * 20; // SC2 LotV
			case CENTRIFUGAL_HOOKS -> 71 * 20;
			case STIMPACK, CHARGE -> 100 * 20;
			case CONCUSSIVE_SHELLS -> 43 * 20;
			case BLINK -> 121 * 20;
		};
	}

	/** Why it cannot start now for this base at this building (a lang key), or null. */
	public String refusal(Hive base, UnitKind building) {
		int level = nextLevel(base);
		switch (this) {
			case WEAPONS, ARMOR, MELEE, VEHICLE_WEAPONS, SHIP_WEAPONS, PLATING, FLYER_ATTACKS, FLYER_CARAPACE, SHIELDS, AIR_WEAPONS, AIR_ARMOR -> {
				if (level > 3) return "message.starmine.research_done";
				if (!building.race.rules().upgradeAllowed(base, level)) return "message.starmine.need_tech";
				// SC2: the protoss air upgrades 2-3 need a fleet beacon
				if ((this == AIR_WEAPONS || this == AIR_ARMOR) && level > 1 && base.count(UnitKind.FLEET_BEACON) == 0) return "message.starmine.need_tech";
			}
			case LAIR -> {
				if (base.tier >= 2) return "message.starmine.research_done";
				if (base.count(UnitKind.SPAWNING_POOL) == 0) return "message.starmine.need_tech";
			}
			case HIVE -> {
				if (base.tier >= 3) return "message.starmine.research_done";
				if (base.tier < 2 || base.count(UnitKind.INFESTATION_PIT) == 0) return "message.starmine.need_tech";
			}
			case METABOLIC_BOOST, CENTRIFUGAL_HOOKS, COMBAT_SHIELD, STIMPACK, CONCUSSIVE_SHELLS, CHARGE, BLINK -> {
				if (base.researched.contains(id())) return "message.starmine.research_done";
				if (this == CENTRIFUGAL_HOOKS && base.tier < 2) return "message.starmine.need_tech";
			}
		}
		return base.lacking(price(level));
	}

	/** Done: the base gets the level. */
	public void apply(Hive base) {
		switch (this) {
			case WEAPONS -> base.attackLevel++;
			case ARMOR -> base.armorLevel++;
			case MELEE, VEHICLE_WEAPONS, SHIP_WEAPONS, PLATING, FLYER_ATTACKS, FLYER_CARAPACE, SHIELDS, AIR_WEAPONS, AIR_ARMOR -> base.levels.merge(id(), 1, Integer::sum);
			case LAIR -> base.tier = Math.max(base.tier, 2);
			case HIVE -> base.tier = Math.max(base.tier, 3);
			default -> base.researched.add(id());
		}
	}

	/** The research an ability (or a passive: concussive shells) needs, or null. */
	public static Research of(dev.starmine.ability.Ability ability) {
		return switch (ability) {
			case STIMPACK -> STIMPACK;
			case CHARGE -> CHARGE;
			case BLINK -> BLINK;
			default -> null;
		};
	}

	/** Whether this unit's base has the research (a unit with no base has it: a lone wild unit). */
	public static boolean has(dev.starmine.entity.StarUnit unit, Research research) {
		if (research == null) return true;
		Hive base = dev.starmine.ability.Abilities.base(unit);
		return base == null || base.researched.contains(research.id());
	}

	public static Research byId(String id) {
		for (Research research : values()) if (research.id().equals(id)) return research;
		return null;
	}
}
