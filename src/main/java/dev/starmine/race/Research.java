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
	AIR_WEAPONS('W'), AIR_ARMOR('R'),
	// owner 17.10: the rest of SC2's unit and building upgrades ({@link #speedFactor}, {@link #adjust}, {@link #extraArmor}, {@link #of(dev.starmine.ability.Ability, UnitKind)})
	HISEC_AUTO_TRACKING('H'), NEOSTEEL_ARMOR('N'), INFERNAL_PRE_IGNITER('I'), DRILLING_CLAWS('D'), SMART_SERVOS('S'),
	CLOAKING_FIELD('C'), HYPERFLIGHT_ROTORS('H'), ADVANCED_BALLISTICS('B'), CADUCEUS_REACTOR('R'),
	PERSONAL_CLOAKING('C'), ENHANCED_SHOCKWAVES('E'), NUKE('N'),
	BURROW('B'), PNEUMATIZED_CARAPACE('P'), ADRENAL_GLANDS('G'), GLIAL_RECONSTITUTION('G'), TUNNELING_CLAWS('T'),
	GROOVED_SPINES('G'), MUSCULAR_AUGMENTS('M'), SEISMIC_SPINES('S'), ADAPTIVE_TALONS('A'), NEURAL_PARASITE('E'),
	CHITINOUS_PLATING('C'), ANABOLIC_SYNTHESIS('A'),
	WARP_GATE_RESEARCH('W'), RESONATING_GLAIVES('G'), PSIONIC_STORM('T'), SHADOW_STRIDE('B'), EXTENDED_THERMAL_LANCE('E'),
	GRAVITIC_BOOSTERS('B'), GRAVITIC_DRIVE('D'), FLUX_VANES('F'), ANION_PULSE_CRYSTALS('P'), TECTONIC_DESTABILIZERS('T'),
	// owner 17.10: the Yamato cannon waits for its refit (an older SC2's), for a player's cruisers; the spire's greater spire (brood lords)
	WEAPON_REFIT('Y'), GREATER_SPIRE('G');

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
			case AIR_WEAPONS, AIR_ARMOR, WARP_GATE_RESEARCH -> building == UnitKind.CYBERNETICS_CORE;
			case HISEC_AUTO_TRACKING, NEOSTEEL_ARMOR -> building == UnitKind.ENGINEERING_BAY;
			case INFERNAL_PRE_IGNITER, DRILLING_CLAWS, SMART_SERVOS -> building == UnitKind.FACTORY;
			case CLOAKING_FIELD, HYPERFLIGHT_ROTORS -> building == UnitKind.STARPORT;
			case ADVANCED_BALLISTICS, CADUCEUS_REACTOR, WEAPON_REFIT -> building == UnitKind.FUSION_CORE;
			case PERSONAL_CLOAKING, ENHANCED_SHOCKWAVES, NUKE -> building == UnitKind.GHOST_ACADEMY;
			case BURROW, PNEUMATIZED_CARAPACE -> building == UnitKind.HATCHERY;
			case ADRENAL_GLANDS -> building == UnitKind.SPAWNING_POOL;
			case GLIAL_RECONSTITUTION, TUNNELING_CLAWS -> building == UnitKind.ROACH_WARREN;
			case GROOVED_SPINES, MUSCULAR_AUGMENTS -> building == UnitKind.HYDRALISK_DEN;
			case SEISMIC_SPINES, ADAPTIVE_TALONS -> building == UnitKind.LURKER_DEN;
			case GREATER_SPIRE -> building == UnitKind.SPIRE;
			case NEURAL_PARASITE -> building == UnitKind.INFESTATION_PIT;
			case CHITINOUS_PLATING, ANABOLIC_SYNTHESIS -> building == UnitKind.ULTRALISK_CAVERN;
			case RESONATING_GLAIVES -> building == UnitKind.TWILIGHT_COUNCIL;
			case PSIONIC_STORM -> building == UnitKind.TEMPLAR_ARCHIVES;
			case SHADOW_STRIDE -> building == UnitKind.DARK_SHRINE;
			case EXTENDED_THERMAL_LANCE, GRAVITIC_BOOSTERS, GRAVITIC_DRIVE -> building == UnitKind.ROBOTICS_BAY;
			case FLUX_VANES, ANION_PULSE_CRYSTALS, TECTONIC_DESTABILIZERS -> building == UnitKind.FLEET_BEACON;
		};
	}

	/** An upgrade of 3 levels (weapons, armor, shields of some kinds). */
	public boolean leveled() {
		return switch (this) {
			case WEAPONS, ARMOR, MELEE, VEHICLE_WEAPONS, SHIP_WEAPONS, PLATING, FLYER_ATTACKS, FLYER_CARAPACE, SHIELDS, AIR_WEAPONS, AIR_ARMOR -> true;
			default -> false;
		};
	}

	/** A unit upgrade done once (not a leveled upgrade, a tier or a nuke). */
	public boolean once() {
		return !leveled() && this != LAIR && this != HIVE && this != NUKE;
	}

	/** Nukes armed and not yet launched (SC2: one silo a ghost academy). */
	public static int nukes(Hive base) {
		return base == null ? 0 : base.levels.getOrDefault(NUKE.id(), 0);
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
		Research by = switch (kind) {
			case ZERGLING -> METABOLIC_BOOST;
			case BANELING -> CENTRIFUGAL_HOOKS;
			case ROACH -> GLIAL_RECONSTITUTION;
			case HYDRALISK -> MUSCULAR_AUGMENTS;
			case OVERLORD -> PNEUMATIZED_CARAPACE;
			case ULTRALISK -> ANABOLIC_SYNTHESIS;
			case BANSHEE -> HYPERFLIGHT_ROTORS;
			case OBSERVER -> GRAVITIC_BOOSTERS;
			case WARP_PRISM -> GRAVITIC_DRIVE;
			case VOID_RAY -> FLUX_VANES;
			default -> null;
		};
		if (by == null || !base.researched.contains(by.id())) return 1;
		return switch (kind) { // SC2 speeds with and without
			case ZERGLING -> 6.58 / 4.13;
			case BANELING -> 4.13 / 3.5;
			case ROACH -> 4.2 / 3.15;
			case HYDRALISK -> 3.94 / 3.15;
			case OVERLORD -> 2.63 / 0.902;
			case ULTRALISK -> 4.95 / 4.13;
			case BANSHEE -> 5.25 / 3.85;
			case OBSERVER -> 3.94 / 2.63;
			case WARP_PRISM -> 4.13 / 2.95;
			default -> 4.65 / 3.85; // void ray
		};
	}

	/**
	 * Its weapon with its base's upgrades (SC2): range (grooved spines a hydralisk +1, extended thermal lance a colossus
	 * +2, anion pulse-crystals a phoenix +2, seismic spines a lurker +2, advanced ballistics a liberator in defender
	 * mode +2, hi-sec auto tracking a turret, a bunker and a planetary fortress +1) and pace (adrenal glands a
	 * zergling ×1.42, resonating glaives an adept ×1.45).
	 */
	public static dev.starmine.ability.Modes.Weapon adjust(dev.starmine.entity.StarUnit unit, dev.starmine.ability.Modes.Weapon weapon) {
		Hive base = dev.starmine.ability.Abilities.base(unit);
		UnitKind kind = unit.kind;
		// a burrowed roach crawls only with tunneling claws (a player's; the wild and Amun's need no research)
		if (kind == UnitKind.ROACH && unit.mode() == dev.starmine.ability.Modes.BURROWED && weapon.mobile() && !has(unit, TUNNELING_CLAWS))
			return new dev.starmine.ability.Modes.Weapon(weapon.range(), weapon.damage(), weapon.interval(), weapon.splash(), weapon.minRange(), false);
		if (base == null || base.researched.isEmpty() || weapon.damage() <= 0) return weapon;
		double range = 0, pace = 1;
		if (kind == UnitKind.HYDRALISK && base.researched.contains(GROOVED_SPINES.id())) range = 1;
		if (kind == UnitKind.COLOSSUS && base.researched.contains(EXTENDED_THERMAL_LANCE.id())) range = 2;
		if (kind == UnitKind.PHOENIX && base.researched.contains(ANION_PULSE_CRYSTALS.id())) range = 2;
		if (kind == UnitKind.LURKER && base.researched.contains(SEISMIC_SPINES.id())) range = 2;
		if (kind == UnitKind.LIBERATOR && unit.mode() == dev.starmine.ability.Modes.DEFENDER && base.researched.contains(ADVANCED_BALLISTICS.id())) range = 2;
		if ((kind == UnitKind.MISSILE_TURRET || kind == UnitKind.BUNKER || kind == UnitKind.COMMAND_CENTER) && base.researched.contains(HISEC_AUTO_TRACKING.id())) range = 1;
		if (kind == UnitKind.ZERGLING && base.researched.contains(ADRENAL_GLANDS.id())) pace = 0.497 / 0.35;
		if (kind == UnitKind.ADEPT && base.researched.contains(RESONATING_GLAIVES.id())) pace = 1.45;
		if (range == 0 && pace == 1) return weapon;
		return new dev.starmine.ability.Modes.Weapon(weapon.range() + range * UnitKind.BLOCKS_PER_UNIT, weapon.damage(),
			Math.max(4, (int) Math.round(weapon.interval() / pace)), weapon.splash(), weapon.minRange(), weapon.mobile());
	}

	/** SC2 armor its base's upgrades add (chitinous plating an ultralisk +2, neosteel armor terran buildings +2). */
	public static int extraArmor(Hive base, UnitKind kind) {
		if (base == null || base.researched.isEmpty()) return 0;
		if (kind == UnitKind.ULTRALISK && base.researched.contains(CHITINOUS_PLATING.id())) return 2;
		if (kind.structure() && kind.race == UnitKind.Race.TERRAN && base.researched.contains(NEOSTEEL_ARMOR.id())) return 2;
		return 0;
	}

	/** SC2 damage its base's upgrades add to a hit (infernal pre-igniter: a hellion +5, a hellbat +12 to light targets; tectonic destabilizers: a tempest +40 to buildings). */
	public static double extraDamage(Hive base, UnitKind attacker, java.util.Set<dev.starmine.entity.Trait> target) {
		if (base == null || base.researched.isEmpty() || target == null) return 0;
		if (target.contains(dev.starmine.entity.Trait.LIGHT) && base.researched.contains(INFERNAL_PRE_IGNITER.id()))
			return attacker == UnitKind.HELLION ? 5 : attacker == UnitKind.HELLBAT ? 12 : 0;
		if (attacker == UnitKind.TEMPEST && target.contains(dev.starmine.entity.Trait.STRUCTURE) && base.researched.contains(TECTONIC_DESTABILIZERS.id())) return 40;
		return 0;
	}

	/** How much faster its base's upgrades make a change of mode: smart servos (hellion, viking, thor ×1.5), drilling claws (a widow mine ×3), adaptive talons (a lurker ×1.5). */
	public static double changeFactor(Hive base, UnitKind kind) {
		if (base == null || base.researched.isEmpty()) return 1;
		return switch (kind) {
			case HELLION, HELLBAT, VIKING, VIKING_ASSAULT, THOR -> base.researched.contains(SMART_SERVOS.id()) ? 1.5 : 1;
			case WIDOW_MINE -> base.researched.contains(DRILLING_CLAWS.id()) ? 3 : 1;
			case LURKER -> base.researched.contains(ADAPTIVE_TALONS.id()) ? 1.5 : 1;
			default -> 1;
		};
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
			case DRILLING_CLAWS -> new int[] {75, 75};
			case WARP_GATE_RESEARCH -> new int[] {50, 50};
			case HISEC_AUTO_TRACKING, INFERNAL_PRE_IGNITER, SMART_SERVOS, CLOAKING_FIELD, CADUCEUS_REACTOR, NUKE, BURROW, PNEUMATIZED_CARAPACE,
				GLIAL_RECONSTITUTION, TUNNELING_CLAWS, GROOVED_SPINES, MUSCULAR_AUGMENTS, RESONATING_GLAIVES, SHADOW_STRIDE, GRAVITIC_BOOSTERS,
				GRAVITIC_DRIVE -> new int[] {100, 100};
			case HYPERFLIGHT_ROTORS -> new int[] {125, 125};
			case WEAPON_REFIT, GREATER_SPIRE -> new int[] {150, 150};

			case ADRENAL_GLANDS, PSIONIC_STORM -> new int[] {200, 200};
			default -> new int[] {150, 150}; // neosteel, ballistics, cloaking, shockwaves, seismic spines, talons, neural, plating, synthesis, lance, vanes, crystals, destabilizers
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
			case NUKE, PNEUMATIZED_CARAPACE, ANABOLIC_SYNTHESIS -> 43 * 20;
			case CADUCEUS_REACTOR, GROOVED_SPINES -> 50 * 20;
			case HISEC_AUTO_TRACKING, SEISMIC_SPINES, ADAPTIVE_TALONS, GRAVITIC_BOOSTERS, GRAVITIC_DRIVE, FLUX_VANES -> 57 * 20;
			case MUSCULAR_AUGMENTS, ANION_PULSE_CRYSTALS -> 64 * 20;
			case BURROW -> 71 * 20;
			case PERSONAL_CLOAKING -> 86 * 20;
			case ADRENAL_GLANDS -> 93 * 20;
			case NEOSTEEL_ARMOR, WEAPON_REFIT, WARP_GATE_RESEARCH, RESONATING_GLAIVES, SHADOW_STRIDE, EXTENDED_THERMAL_LANCE, TECTONIC_DESTABILIZERS -> 100 * 20;
			case HYPERFLIGHT_ROTORS -> 121 * 20;
			case GREATER_SPIRE -> 71 * 20;
			default -> 79 * 20;
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
			case NUKE -> {
				if (nukes(base) >= Math.max(1, base.count(UnitKind.GHOST_ACADEMY))) return "message.starmine.nuke_armed";
			}
			default -> {
				if (base.researched.contains(id())) return "message.starmine.research_done";
				if ((this == CENTRIFUGAL_HOOKS || this == GLIAL_RECONSTITUTION || this == TUNNELING_CLAWS) && base.tier < 2) return "message.starmine.need_tech";
				if ((this == ADRENAL_GLANDS || this == GREATER_SPIRE) && base.tier < 3) return "message.starmine.need_tech";
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
			case NUKE -> base.levels.merge(id(), 1, Integer::sum);
			default -> base.researched.add(id());
		}
	}

	/** The research an ability (or a passive: concussive shells) needs, or null. */
	public static Research of(dev.starmine.ability.Ability ability) {
		return of(ability, null);
	}

	/** The research an ability of a kind needs (SC2): burrow (zerg but the lurker), the banshee's and the ghost's cloaks, storm, shadow stride, warp gates, neural parasite. */
	public static Research of(dev.starmine.ability.Ability ability, @org.jetbrains.annotations.Nullable UnitKind kind) {
		return switch (ability) {
			case STIMPACK -> STIMPACK;
			case CHARGE -> CHARGE;
			case BLINK -> BLINK;
			case BURROW -> kind != null && kind.race == UnitKind.Race.ZERG && kind != UnitKind.LURKER ? BURROW : null;
			case CLOAK -> kind == UnitKind.BANSHEE ? CLOAKING_FIELD : kind == UnitKind.GHOST ? PERSONAL_CLOAKING : null;
			case PSIONIC_STORM -> PSIONIC_STORM;
			case SHADOW_STRIDE -> SHADOW_STRIDE;
			case WARP_GATE -> WARP_GATE_RESEARCH;
			case NEURAL_PARASITE -> NEURAL_PARASITE;
			case YAMATO -> kind == UnitKind.BATTLECRUISER ? WEAPON_REFIT : null;
			default -> null;
		};
	}

	/**
	 * Whether this unit may use what the research opens: a player's unit when its base has it; the wild (a lone unit,
	 * a wild base's) and Amun's always (owner 17.10: they need no research for their abilities).
	 */
	public static boolean has(dev.starmine.entity.StarUnit unit, Research research) {
		if (research == null || dev.starmine.faction.Faction.amun(unit)) return true;
		Hive base = dev.starmine.ability.Abilities.base(unit);
		return base == null || base.owner == null || base.researched.contains(research.id());
	}

	public static Research byId(String id) {
		for (Research research : values()) if (research.id().equals(id)) return research;
		return null;
	}
}
