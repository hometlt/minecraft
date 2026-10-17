package dev.starmine.ability;

import dev.starmine.entity.UnitKind;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

import static dev.starmine.entity.UnitKind.*;

/**
 * A unit ability (owner 14.10, docs/ABILITIES.md): its SC2 numbers and how it is aimed. What it does is
 * in {@link Abilities#effect} (common mechanics: {@link Cloak}, {@link Modes}, {@link Transport},
 * timed states {@link Status}, passives {@link Passives}, lasting channels {@link Channels}; the races'
 * own: {@link ZergAbilities}, {@link TerranAbilities}, {@link ProtossAbilities}); the engine pays
 * energy, health and resources, keeps cooldowns, channels and delays ({@link Abilities}); wild units cast
 * by {@link AbilityAi}. A player who plays as a unit uses them through modules
 * ({@link dev.starmine.possess.ModuleItem}). A new ability is a constant here, its users in
 * {@link #users()}, its effect in its race's class called from {@link Abilities#effect}, a rule in
 * {@link AbilityAi}, a game test.
 *
 * Numbers: energy in SC2 units (a caster has up to 200), times in real seconds (LotV, Faster), range
 * in SC2 units (blocks = x {@link UnitKind#BLOCKS_PER_UNIT} + 1, like weapons; 0 = anywhere), health
 * cost in SC2 hit points, resources from the base.
 */
public enum Ability {
	//                  id                     key  target          energy cooldown range channel delay
	// ---- zerg ----
	BURROW(             "burrow",              'R', Target.TOGGLE,  0,     0,       0,    0,      0),
	CORROSIVE_BILE(     "corrosive_bile",      'B', Target.POINT,   0,     7,       9,    0,      2.5),
	CREEP_TUMOR(        "creep_tumor",         'C', Target.POINT,   25,    0,       3,    0,      0),
	INJECT_LARVA(       "inject_larva",        'V', Target.UNIT,    25,    0,       1,    0,      0),
	TRANSFUSION(        "transfusion",         'T', Target.UNIT,    50,    1,       7,    0,      0),
	SPAWN_CHANGELING(   "changeling",          'C', Target.NONE,    50,    0,       0,    0,      0),
	NEURAL_PARASITE(    "neural_parasite",     'E', Target.UNIT,    100,   0,       8,    0,      0),
	FUNGAL_GROWTH(      "fungal_growth",       'F', Target.POINT,   75,    0,       10,   0,      0),
	BLINDING_CLOUD(     "blinding_cloud",      'B', Target.POINT,   100,   0,       11,   0,      0),
	ABDUCT(             "abduct",              'G', Target.UNIT,    75,    0,       9,    0,      0),
	PARASITIC_BOMB(     "parasitic_bomb",      'W', Target.UNIT,    125,   0,       8,    0,      0),
	CONSUME(            "consume",             'C', Target.UNIT,    0,     1,       1,    0,      0),
	CAUSTIC_SPRAY(      "caustic_spray",       'C', Target.UNIT,    0,     0,       6,    0,      0),
	// ---- terran ----
	STIMPACK(           "stimpack",            'T', Target.NONE,    0,     1,       0,    0,      0),
	KD8_CHARGE(         "kd8_charge",          'D', Target.POINT,   0,     14,      5,    0,      1.5),
	CLOAK(              "cloak",               'C', Target.TOGGLE,  25,    0,       0,    0,      0),
	EMP(                "emp",                 'E', Target.POINT,   75,    0,       10,   0,      0),
	STEADY_TARGETING(   "steady_targeting",    'R', Target.UNIT,    50,    0,       10,   1.43,   0),
	NUKE(               "nuke",                'N', Target.POINT,   0,     60,      12,   14,     0),
	HELLBAT(            "hellbat",             'E', Target.TOGGLE,  0,     0,       0,    0,      0),
	SIEGE_MODE(         "siege_mode",          'E', Target.TOGGLE,  0,     0,       0,    0,      0),
	HIGH_IMPACT(        "high_impact",         'E', Target.TOGGLE,  0,     0,       0,    0,      0),
	VIKING_LAND(        "viking_land",         'D', Target.TOGGLE,  0,     0,       0,    0,      0),
	DEFENDER_MODE(      "defender_mode",       'E', Target.TOGGLE,  0,     0,       0,    0,      0),
	AFTERBURNERS(       "afterburners",        'I', Target.NONE,    0,     14,      0,    0,      0),
	YAMATO(             "yamato",              'Y', Target.UNIT,    0,     71,      10,   2,      0),
	TACTICAL_JUMP(      "tactical_jump",       'T', Target.POINT,   0,     71,      0,    0,      4),
	REPAIR(             "repair",              'R', Target.UNIT,    0,     0,       1,    0,      0),
	ORBITAL_COMMAND(    "orbital_command",     'O', Target.NONE,    0,     0,       0,    0,      25),
	PLANETARY_FORTRESS( "planetary_fortress",  'P', Target.NONE,    0,     0,       0,    0,      36),
	MULE(               "mule",                'E', Target.POINT,   50,    0,       0,    0,      0),
	SCANNER_SWEEP(      "scanner_sweep",       'C', Target.POINT,   50,    0,       0,    0,      0),
	LOWER_DEPOT(        "lower_depot",         'R', Target.TOGGLE,  0,     0,       0,    0,      0),
	BUILD_TECHLAB(      "techlab",             'X', Target.NONE,    0,     0,       0,    0,      18),
	BUILD_REACTOR(      "reactor",             'C', Target.NONE,    0,     0,       0,    0,      36),
	LIFT_OFF(           "lift_off",            'L', Target.TOGGLE,  0,     0,       0,    0,      0),
	// ---- protoss ----
	CHARGE(             "charge",              'C', Target.UNIT,    0,     7,       4,    0,      0),
	BLINK(              "blink",               'B', Target.POINT,   0,     7,       8,    0,      0),
	PSIONIC_TRANSFER(   "psionic_transfer",    'F', Target.POINT,   0,     11,      0,    0,      7),
	FORCE_FIELD(        "force_field",         'F', Target.POINT,   50,    0,       9,    0,      0),
	GUARDIAN_SHIELD(    "guardian_shield",     'G', Target.NONE,    75,    0,       0,    0,      0),
	HALLUCINATION(      "hallucination",       'C', Target.NONE,    75,    0,       0,    0,      0),
	PSIONIC_STORM(      "psionic_storm",       'T', Target.POINT,   75,    0,       9,    0,      0),
	FEEDBACK(           "feedback",            'F', Target.UNIT,    50,    0,       10,   0,      0),
	ARCHON_WARP(        "archon_warp",         'R', Target.NONE,    0,     0,       0,    0,      0),
	SHADOW_STRIDE(      "shadow_stride",       'B', Target.POINT,   0,     14,      8,    0,      0),
	PURIFICATION_NOVA(  "purification_nova",   'E', Target.POINT,   0,     14,      13,   0,      0),
	PHASING_MODE(       "phasing_mode",        'E', Target.TOGGLE,  0,     0,       0,    0,      0),
	GRAVITON_BEAM(      "graviton_beam",       'G', Target.UNIT,    50,    0,       4,    0,      0),
	PRISMATIC_ALIGNMENT("prismatic_alignment", 'E', Target.NONE,    0,     43,      0,    0,      0),
	PULSAR_BEAM(        "pulsar_beam",         'E', Target.TOGGLE,  25,    0,       0,    0,      0),
	REVELATION(         "revelation",          'R', Target.POINT,   25,    0,       9,    0,      0),
	STASIS_WARD(        "stasis_ward",         'T', Target.POINT,   50,    0,       3,    0,      0),
	DISINTEGRATION(     "disintegration",      'R', Target.POINT,   0,     43,      13,   0,      7),
	MASS_RECALL(        "mass_recall",         'R', Target.NONE,    0,     130,     0,    0,      0),
	TIME_WARP(          "time_warp",           'T', Target.POINT,   75,    0,       9,    0,      0),
	CHRONO_BOOST(       "chrono_boost",        'C', Target.UNIT,    50,    0,       0,    0,      0),
	BATTERY_OVERCHARGE( "battery_overcharge",  'B', Target.UNIT,    0,     60,      0,    0,      0),
	WARP_GATE(          "warp_gate",           'G', Target.TOGGLE,  0,     0,       0,    0,      0),
	// ---- heroes (CLOUD_TASK_2 stage 8; HeroAbilities): cooldowns instead of SC2 energy, a boss fights for minutes ----
	LEAPING_STRIKE(     "leaping_strike",      'Q', Target.UNIT,    0,     8,       6,    0,      0),
	PSIONIC_SHIFT(      "psionic_shift",       'W', Target.POINT,   0,     12,      6,    0,      0),
	HYPERION_STRIKE(    "hyperion_strike",     'H', Target.UNIT,    0,     30,      15,   0,      1.5),
	LIGHTNING_DASH(     "lightning_dash",      'D', Target.UNIT,    0,     10,      8,    0,      0),
	// ---- transports (every race) ----
	LOAD(               "load",                'L', Target.NONE,    0,     0,       0,    0,      0),
	UNLOAD(             "unload",              'D', Target.POINT,   0,     0,       0,    0,      0);

	/** How it is aimed: nothing (at once), a toggle (on/off), a unit, a point. */
	public enum Target { NONE, TOGGLE, UNIT, POINT }

	public final String id;
	public final char hotkey;
	public final Target target;
	public final int energy, cooldownTicks, channelTicks, delayTicks;
	/** Reach in blocks (0: anywhere). */
	public final double range;
	/** Base resources it costs when cast (a nuke, an orbital command, an add-on); a repair is paid as it goes ({@link TerranAbilities#repairTick}). */
	public int minerals, vespene;

	static {
		NUKE.minerals = 100; // SC2: armed at the ghost academy
		NUKE.vespene = 100;
		ORBITAL_COMMAND.minerals = 150;
		PLANETARY_FORTRESS.minerals = 150;
		PLANETARY_FORTRESS.vespene = 150;
		BUILD_TECHLAB.minerals = 50;
		BUILD_TECHLAB.vespene = 25;
		BUILD_REACTOR.minerals = 50;
		BUILD_REACTOR.vespene = 50;
	}

	Ability(String id, char hotkey, Target target, int energy, double cooldown, double sc2Range, double channel, double delay) {
		this.id = id;
		this.hotkey = hotkey;
		this.target = target;
		this.energy = energy;
		this.cooldownTicks = (int) Math.round(cooldown * 20);
		this.range = sc2Range == 0 ? 0 : sc2Range * BLOCKS_PER_UNIT + 1;
		this.channelTicks = (int) Math.round(channel * 20);
		this.delayTicks = (int) Math.round(delay * 20);
	}

	/** SC2 hit points it costs the caster (the stimpack: a marine 10, a marauder 20). */
	public double healthCost(UnitKind kind) {
		return this == STIMPACK ? kind == MARAUDER ? 20 : 10 : 0;
	}

	/** The kinds that have it. */
	public List<UnitKind> users() {
		return switch (this) {
			case BURROW -> List.of(DRONE, ZERGLING, BANELING, ROACH, RAVAGER, HYDRALISK, LURKER, QUEEN, INFESTOR, SWARM_HOST, WIDOW_MINE);
			case CORROSIVE_BILE -> List.of(RAVAGER);
			case CREEP_TUMOR, INJECT_LARVA, TRANSFUSION -> List.of(QUEEN);
			case SPAWN_CHANGELING -> List.of(OVERSEER);
			case NEURAL_PARASITE, FUNGAL_GROWTH -> List.of(INFESTOR);
			case BLINDING_CLOUD, ABDUCT, PARASITIC_BOMB, CONSUME -> List.of(VIPER);
			case CAUSTIC_SPRAY -> List.of(CORRUPTOR);
			case STIMPACK -> List.of(MARINE, MARAUDER);
			case KD8_CHARGE -> List.of(REAPER, RAYNOR); // Raynor's grenade
			case CLOAK -> List.of(GHOST, BANSHEE);
			case EMP, STEADY_TARGETING, NUKE -> List.of(GHOST);
			case HELLBAT -> List.of(HELLION, UnitKind.HELLBAT); // the two kinds turn into each other (Morphs)
			case SIEGE_MODE -> List.of(SIEGE_TANK);
			case HIGH_IMPACT -> List.of(THOR);
			case VIKING_LAND -> List.of(VIKING, VIKING_ASSAULT);
			case DEFENDER_MODE -> List.of(LIBERATOR);
			case LIFT_OFF -> List.of(COMMAND_CENTER, BARRACKS, FACTORY, STARPORT);
			case AFTERBURNERS -> List.of(MEDIVAC);
			case YAMATO, TACTICAL_JUMP -> List.of(BATTLECRUISER);
			case REPAIR -> List.of(SCV);
			case ORBITAL_COMMAND, PLANETARY_FORTRESS, MULE, SCANNER_SWEEP -> List.of(COMMAND_CENTER);
			case LOWER_DEPOT -> List.of(SUPPLY_DEPOT);
			case BUILD_TECHLAB, BUILD_REACTOR -> List.of(BARRACKS, FACTORY, STARPORT);
			case CHARGE -> List.of(ZEALOT);
			case BLINK -> List.of(STALKER);
			case PSIONIC_TRANSFER -> List.of(ADEPT);
			case FORCE_FIELD, GUARDIAN_SHIELD, HALLUCINATION -> List.of(SENTRY);
			case PSIONIC_STORM -> List.of(HIGH_TEMPLAR, ARTANIS);
			case FEEDBACK -> List.of(HIGH_TEMPLAR);
			case LEAPING_STRIKE, PSIONIC_SHIFT -> List.of(KERRIGAN);
			case HYPERION_STRIKE -> List.of(RAYNOR);
			case LIGHTNING_DASH -> List.of(ARTANIS);
			case ARCHON_WARP -> List.of(HIGH_TEMPLAR, DARK_TEMPLAR);
			case SHADOW_STRIDE -> List.of(DARK_TEMPLAR);
			case PURIFICATION_NOVA -> List.of(DISRUPTOR);
			case PHASING_MODE -> List.of(WARP_PRISM);
			case GRAVITON_BEAM -> List.of(PHOENIX);
			case PRISMATIC_ALIGNMENT -> List.of(VOID_RAY);
			case PULSAR_BEAM, REVELATION, STASIS_WARD -> List.of(ORACLE);
			case DISINTEGRATION -> List.of(TEMPEST);
			case MASS_RECALL, TIME_WARP -> List.of(MOTHERSHIP);
			case CHRONO_BOOST, BATTERY_OVERCHARGE -> List.of(NEXUS);
			case WARP_GATE -> List.of(GATEWAY);
			case LOAD, UNLOAD -> List.of(MEDIVAC, WARP_PRISM, OVERLORD, BUNKER);
		};
	}

	/** A kind's abilities, in command-card order. */
	public static List<Ability> of(UnitKind kind) {
		List<Ability> list = new ArrayList<>();
		for (Ability ability : values()) if (ability.users().contains(kind)) list.add(ability);
		return list;
	}

	public static @Nullable Ability byId(String id) {
		for (Ability ability : values()) if (ability.id.equals(id)) return ability;
		return null;
	}
}
