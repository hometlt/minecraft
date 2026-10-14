package dev.starmine.entity;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Every StarMine unit and building, zerg and terran: its StarCraft II numbers and how they become
 * Minecraft behaviour. {@link #race} decides alliances (one race never fights itself) and AI.
 *
 * SC2 values (LotV multiplayer: life, speed, weapon damage and period, range) are converted with
 * the scales the owner accepted on the zergling: health and damage x{@link #SCALE} (10/35; a
 * zergling has half a player's health, a hydralisk more than a player), attack period kept,
 * walking speed x{@link #SPEED_SCALE}, range in blocks = SC2 range x{@link #BLOCKS_PER_UNIT}.
 * Hitboxes follow the imported model sizes (1.153 blocks per SC2 unit, a zergling 1 block tall);
 * drones (2 blocks across) dig and walk 2x2 tunnels.
 * Model and sound names follow the enum name in lower case without underscores.
 */
public enum UnitKind {
	//           sc2Hp  speed  fly    attack                  dmg   period range  aggressive  hitbox w/h (from model size)  egg colours
	ZERGLING(    35,    4.13,  false, Attack.MELEE,           5,    0.497, 0,    true,  0.8f, 0.9f, 0x4b1f4f, 0xc79a52),
	DRONE(       40,    3.94,  false, Attack.MELEE,           5,    1.07,  0,    false, 1.8f, 1.0f,  0x6a4a2a, 0xd8c08a),
	OVERLORD(    200,   0.902, true,  Attack.NONE,            0,    0,     0,    false, 4.0f, 4.0f,  0x5a3a5a, 0xb08ab0),
	OVERSEER(    200,   2.62,  true,  Attack.NONE,            0,    0,     0,    false, 4.0f, 3.8f,  0x3a5a3a, 0xc0d080),
	QUEEN(       175,   1.31,  false, Attack.RANGED,          8,    0.71,  5,    true,  2.0f, 2.6f,  0x40205a, 0xe0b040),
	ROACH(       145,   3.15,  false, Attack.RANGED,          16,   1.43,  4,    true,  1.3f, 1.0f,  0x5a3a20, 0x90b040),
	RAVAGER(     120,   3.85,  false, Attack.RANGED,          16,   1.14,  6,    true,  2.0f, 2.0f,  0x7a3010, 0xf07020),
	BANELING(    30,    3.5,   false, Attack.EXPLODE,         35,   0,     0,    true,  1.0f, 0.95f,  0x30602a, 0x9ae050),
	HYDRALISK(   90,    3.15,  false, Attack.RANGED,          12,   0.59,  5,    true,  1.3f, 2.1f,  0x6a2a40, 0xe0a060),
	LURKER(      190,   4.13,  false, Attack.LINE,            20,   1.43,  8,    true,  3.0f, 1.65f,  0x3a2a40, 0xc0a0c0),
	MUTALISK(    120,   5.6,   true,  Attack.RANGED,          9,    1.09,  3,    true,  2.2f, 1.5f,  0x5a2050, 0x40c0a0),
	CORRUPTOR(   200,   4.725, true,  Attack.RANGED,          14,   1.36,  6,    true,  2.0f, 1.7f,  0x5a2a6a, 0xf0a040),
	BROOD_LORD(  225,   1.97,  true,  Attack.SPAWN_BROODLINGS, 20,  1.79,  10,   true,  4.0f, 1.3f,  0x402a2a, 0xd09060),
	INFESTOR(    90,    3.15,  false, Attack.SLOW,            30,   4.0,   10,   true,  1.8f, 2.2f,  0x40303a, 0xa0e060),
	SWARM_HOST(  160,   3.15,  false, Attack.SPAWN_LOCUSTS,   0,    22.0,  12,   true,  2.0f, 1.9f,  0x4a3a2a, 0xd0b070),
	VIPER(       150,   4.13,  true,  Attack.PULL,            0,    8.0,   9,    true,  2.0f, 2.1f,  0x2a3a5a, 0xe0c060),
	ULTRALISK(   500,   4.13,  false, Attack.SPLASH,          35,   0.61,  0,    true,  2.8f, 3.7f,  0x5a4a3a, 0xe0d0b0),
	BROODLING(   20,    5.37,  false, Attack.MELEE,           4,    0.46,  0,    true,  0.8f, 0.5f,  0x3a2a20, 0x907050),
	LOCUST(      50,    2.62,  false, Attack.MELEE,           10,   0.43,  0,    true,  0.8f, 0.8f,  0x4a5a2a, 0xb0d060),
	CHANGELING(  5,     3.15,  false, Attack.NONE,            0,    0,     0,    false, 0.9f, 1.0f,  0x6a5a3a, 0xf0e0b0),
	// hive: larva and cocoon stay at their hatchery; the hatchery is the hive's anchor (a structure)
	LARVA(       25,    0.79,  false, Attack.NONE,            0,    0,     0,    false, 0.6f, 0.4f,  0x6a4a5a, 0xd0b0c0),
	EGG(         200,   0,     false, Attack.NONE,            0,    0,     0,    false, 0.9f, 0.9f,  0x5a3a4a, 0xa08070),
	HATCHERY(    1500,  0,     false, Attack.NONE,            0,    0,     0,    false, 12.0f, 8.0f, 0x4a2a3a, 0xc08a6a),
	// buildings a drone mutates into (SC2): tech for units, upgrades, defence
	SPAWNING_POOL(     1000, 0, false, Attack.NONE,      0,  0,    0, false, 4.0f, 1.7f, 0x5a2a3a, 0x60a040),
	EVOLUTION_CHAMBER( 750,  0, false, Attack.NONE,      0,  0,    0, false, 4.2f, 1.8f, 0x5a3a2a, 0xd0a060),
	ROACH_WARREN(      850,  0, false, Attack.NONE,      0,  0,    0, false, 3.4f, 2.9f, 0x5a3a20, 0x90b040),
	BANELING_NEST(     850,  0, false, Attack.NONE,      0,  0,    0, false, 3.3f, 3.9f, 0x30602a, 0x9ae050),
	HYDRALISK_DEN(     850,  0, false, Attack.NONE,      0,  0,    0, false, 4.5f, 3.7f, 0x6a2a40, 0xe0a060),
	SPIRE(             850,  0, false, Attack.NONE,      0,  0,    0, false, 2.8f, 4.4f, 0x5a2050, 0x40c0a0),
	INFESTATION_PIT(   850,  0, false, Attack.NONE,      0,  0,    0, false, 3.8f, 1.2f, 0x40303a, 0xa0e060),
	ULTRALISK_CAVERN(  850,  0, false, Attack.NONE,      0,  0,    0, false, 4.6f, 3.0f, 0x5a4a3a, 0xe0d0b0),
	EXTRACTOR(         500,  0, false, Attack.NONE,      0,  0,    0, false, 4.2f, 4.3f, 0x3a5a2a, 0x60e060),
	SPINE_CRAWLER(     300,  0, false, Attack.NONE,      25, 1.32, 7, true,  2.0f, 2.0f, 0x6a3a3a, 0xe0b0a0),
	SPORE_CRAWLER(     400,  0, false, Attack.NONE,      15, 0.61, 7, true,  2.0f, 2.4f, 0x3a5a3a, 0xc0e0a0),

	// terran (owner 14.10): an SCV-built base the player commands (see dev.starmine.colony); hitboxes from the model sizes
	//                          sc2Hp speed  fly    attack         dmg   period range aggressive w/h          egg colours
	SCV(           Race.TERRAN, 45,   3.94,  false, Attack.MELEE,  5,    1.07,  0,    false, 1.2f, 1.2f,  0x3a5a8a, 0xd0d0d0),
	MARINE(        Race.TERRAN, 45,   3.15,  false, Attack.RANGED, 6,    0.61,  5,    true,  0.8f, 1.0f,  0x2a4a9a, 0xb0b0b0),
	MARAUDER(      Race.TERRAN, 125,  3.15,  false, Attack.RANGED, 15,   1.07,  6,    true,  1.4f, 1.3f,  0x2a4a9a, 0x707070),
	REAPER(        Race.TERRAN, 60,   5.25,  false, Attack.RANGED, 8,    0.79,  5,    true,  1.0f, 1.3f,  0x2a4a9a, 0xd04020),
	GHOST(         Race.TERRAN, 100,  3.94,  false, Attack.RANGED, 10,   1.07,  6,    true,  0.8f, 1.6f,  0x2a4a9a, 0x30b0e0),
	HELLION(       Race.TERRAN, 90,   5.95,  false, Attack.LINE,   8,    1.79,  5,    true,  2.0f, 1.5f,  0x3a5a8a, 0xf08020),
	SIEGE_TANK(    Race.TERRAN, 175,  3.15,  false, Attack.RANGED, 15,   0.74,  7,    true,  2.9f, 1.5f,  0x3a5a8a, 0x707050),
	THOR(          Race.TERRAN, 400,  2.62,  false, Attack.RANGED, 30,   0.91,  7,    true,  5.0f, 5.5f,  0x3a5a8a, 0x505050),
	VIKING(        Race.TERRAN, 135,  3.85,  true,  Attack.RANGED, 20,   1.43,  9,    true,  2.5f, 0.9f,  0x3a5a8a, 0xa0a0b0),
	MEDIVAC(       Race.TERRAN, 150,  3.5,   true,  Attack.NONE,   0,    0,     0,    false, 2.8f, 1.2f,  0x3a5a8a, 0xf0f0f0),
	BANSHEE(       Race.TERRAN, 140,  3.85,  true,  Attack.RANGED, 24,   0.89,  6,    true,  2.5f, 0.8f,  0x3a5a8a, 0x404060),
	BATTLECRUISER( Race.TERRAN, 550,  2.62,  true,  Attack.RANGED, 8,    0.16,  6,    true,  4.3f, 2.2f,  0x3a5a8a, 0x808090),
	COMMAND_CENTER(Race.TERRAN, 1500, 0,     false, Attack.NONE,   0,    0,     0,    false, 5.8f, 4.4f,  0x3a5a8a, 0xc0c0c0),
	SUPPLY_DEPOT(  Race.TERRAN, 400,  0,     false, Attack.NONE,   0,    0,     0,    false, 4.4f, 2.0f,  0x3a5a8a, 0x909090),
	BARRACKS(      Race.TERRAN, 1000, 0,     false, Attack.NONE,   0,    0,     0,    false, 4.5f, 3.1f,  0x3a5a8a, 0xa08060),
	FACTORY(       Race.TERRAN, 1250, 0,     false, Attack.NONE,   0,    0,     0,    false, 4.5f, 4.5f,  0x3a5a8a, 0x806040),
	STARPORT(      Race.TERRAN, 1300, 0,     false, Attack.NONE,   0,    0,     0,    false, 5.0f, 3.2f,  0x3a5a8a, 0x6080a0),
	ENGINEERING_BAY(Race.TERRAN, 850, 0,     false, Attack.NONE,   0,    0,     0,    false, 4.5f, 2.6f,  0x3a5a8a, 0x60a0a0),
	ARMORY(        Race.TERRAN, 750,  0,     false, Attack.NONE,   0,    0,     0,    false, 3.5f, 2.7f,  0x3a5a8a, 0xa06060),
	GHOST_ACADEMY( Race.TERRAN, 1250, 0,     false, Attack.NONE,   0,    0,     0,    false, 3.7f, 3.8f,  0x3a5a8a, 0x30b0e0),
	FUSION_CORE(   Race.TERRAN, 750,  0,     false, Attack.NONE,   0,    0,     0,    false, 4.8f, 2.5f,  0x3a5a8a, 0xe0e060),
	BUNKER(        Race.TERRAN, 400,  0,     false, Attack.NONE,   12,   0.61,  6,    true,  3.0f, 1.0f,  0x3a5a8a, 0x605040),
	MISSILE_TURRET(Race.TERRAN, 250,  0,     false, Attack.NONE,   24,   0.61,  7,    true,  1.5f, 2.0f,  0x3a5a8a, 0xe04040),
	REFINERY(      Race.TERRAN, 500,  0,     false, Attack.NONE,   0,    0,     0,    false, 4.1f, 3.3f,  0x3a5a8a, 0x60e060),

	// protoss (owner 14.10): SC2 life and plasma shields; hitboxes from the model sizes
	//                            sc2Hp shield speed  fly    attack         dmg  period range aggressive w/h          egg colours
	PROBE(            Race.PROTOSS, 20,  20,   3.94,  false, Attack.MELEE,  5,   1.07,  0,    false, 1.0f, 0.9f,  0xc8a030, 0x3070d0),
	ZEALOT(           Race.PROTOSS, 100, 50,   3.15,  false, Attack.MELEE,  16,  0.86,  0,    true,  0.8f, 1.5f,  0xc8a030, 0x4080f0),
	STALKER(          Race.PROTOSS, 80,  80,   4.13,  false, Attack.RANGED, 13,  1.34,  6,    true,  2.2f, 3.1f,  0xc8a030, 0x30a0e0),
	SENTRY(           Race.PROTOSS, 40,  40,   3.15,  false, Attack.RANGED, 6,   0.71,  5,    true,  2.0f, 1.3f,  0xc8a030, 0x60c0f0),
	ADEPT(            Race.PROTOSS, 70,  70,   3.5,   false, Attack.RANGED, 10,  1.61,  4,    true,  0.9f, 1.5f,  0xc8a030, 0x50d0c0),
	HIGH_TEMPLAR(     Race.PROTOSS, 40,  40,   2.62,  false, Attack.RANGED, 4,   1.25,  6,    true,  1.0f, 1.6f,  0xc8a030, 0x8080ff),
	DARK_TEMPLAR(     Race.PROTOSS, 40,  80,   3.94,  false, Attack.MELEE,  45,  1.21,  0,    true,  1.0f, 1.8f,  0x403050, 0x80ff80),
	ARCHON(           Race.PROTOSS, 10,  350,  3.94,  false, Attack.RANGED, 25,  1.25,  3,    true,  2.0f, 2.9f,  0xa0c0ff, 0xffffff),
	IMMORTAL(         Race.PROTOSS, 200, 100,  3.15,  false, Attack.RANGED, 20,  1.04,  6,    true,  2.0f, 2.4f,  0xc8a030, 0xe0e0e0),
	COLOSSUS(         Race.PROTOSS, 200, 150,  3.15,  false, Attack.LINE,   20,  1.07,  7,    true,  3.5f, 7.4f,  0xc8a030, 0xff6060),
	DISRUPTOR(        Race.PROTOSS, 100, 100,  3.15,  false, Attack.NONE,   0,   0,     0,    false, 1.9f, 1.1f,  0xc8a030, 0xd0a0ff),
	OBSERVER(         Race.PROTOSS, 40,  20,   2.63,  true,  Attack.NONE,   0,   0,     0,    false, 1.2f, 1.4f,  0xc8a030, 0x404040),
	WARP_PRISM(       Race.PROTOSS, 80,  100,  4.13,  true,  Attack.NONE,   0,   0,     0,    false, 3.0f, 3.5f,  0xc8a030, 0xa0a0ff),
	PHOENIX(          Race.PROTOSS, 120, 60,   5.95,  true,  Attack.RANGED, 10,  0.79,  5,    true,  2.4f, 0.7f,  0xc8a030, 0xff8020),
	VOID_RAY(         Race.PROTOSS, 150, 100,  3.85,  true,  Attack.RANGED, 6,   0.36,  6,    true,  2.5f, 1.7f,  0xc8a030, 0xc040ff),
	ORACLE(           Race.PROTOSS, 100, 60,   5.6,   true,  Attack.RANGED, 15,  0.61,  4,    true,  2.4f, 2.9f,  0xc8a030, 0x40e0ff),
	TEMPEST(          Race.PROTOSS, 200, 100,  3.15,  true,  Attack.RANGED, 40,  2.36,  10,   true,  4.0f, 1.1f,  0xc8a030, 0x6060ff),
	CARRIER(          Race.PROTOSS, 300, 150,  2.62,  true,  Attack.RANGED, 16,  0.5,   8,    true,  3.5f, 2.2f,  0xc8a030, 0xffffff),
	INTERCEPTOR(      Race.PROTOSS, 40,  40,   7.5,   true,  Attack.MELEE,  16,  2.14,  0,    true,  0.7f, 0.4f,  0xc8a030, 0xc0c0c0),
	MOTHERSHIP(       Race.PROTOSS, 350, 350,  2.62,  true,  Attack.RANGED, 24,  2.21,  7,    true,  10.0f, 4.5f, 0x302040, 0xffd040),
	NEXUS(            Race.PROTOSS, 1000, 1000, 0,    false, Attack.NONE,   0,   0,     0,    false, 6.2f, 4.1f,  0xc8a030, 0x30a0ff),
	PYLON(            Race.PROTOSS, 200, 200,  0,     false, Attack.NONE,   0,   0,     0,    false, 1.8f, 3.1f,  0xc8a030, 0x60c0ff),
	ASSIMILATOR(      Race.PROTOSS, 300, 300,  0,     false, Attack.NONE,   0,   0,     0,    false, 4.2f, 2.6f,  0xc8a030, 0x60e060),
	GATEWAY(          Race.PROTOSS, 500, 500,  0,     false, Attack.NONE,   0,   0,     0,    false, 4.5f, 3.0f,  0xc8a030, 0xe0c060),
	FORGE(            Race.PROTOSS, 400, 400,  0,     false, Attack.NONE,   0,   0,     0,    false, 3.6f, 2.7f,  0xc8a030, 0xff8040),
	CYBERNETICS_CORE( Race.PROTOSS, 550, 550,  0,     false, Attack.NONE,   0,   0,     0,    false, 3.5f, 2.5f,  0xc8a030, 0x40a0ff),
	PHOTON_CANNON(    Race.PROTOSS, 150, 150,  0,     false, Attack.NONE,   20,  1.25,  7,    true,  2.9f, 2.2f,  0xc8a030, 0x8080ff),
	SHIELD_BATTERY(   Race.PROTOSS, 150, 150,  0,     false, Attack.NONE,   0,   0,     0,    false, 3.0f, 1.9f,  0xc8a030, 0x40c0ff),
	TWILIGHT_COUNCIL( Race.PROTOSS, 500, 500,  0,     false, Attack.NONE,   0,   0,     0,    false, 3.8f, 3.9f,  0xc8a030, 0xa060ff),
	ROBOTICS_FACILITY(Race.PROTOSS, 450, 450,  0,     false, Attack.NONE,   0,   0,     0,    false, 4.0f, 2.8f,  0xc8a030, 0xe0e0e0),
	STARGATE(         Race.PROTOSS, 600, 600,  0,     false, Attack.NONE,   0,   0,     0,    false, 4.5f, 3.9f,  0xc8a030, 0x6060ff),
	TEMPLAR_ARCHIVES( Race.PROTOSS, 500, 500,  0,     false, Attack.NONE,   0,   0,     0,    false, 3.0f, 2.4f,  0xc8a030, 0x8080ff),
	DARK_SHRINE(      Race.PROTOSS, 500, 500,  0,     false, Attack.NONE,   0,   0,     0,    false, 2.6f, 6.2f,  0x403050, 0x80ff80),
	ROBOTICS_BAY(     Race.PROTOSS, 500, 500,  0,     false, Attack.NONE,   0,   0,     0,    false, 3.3f, 2.0f,  0xc8a030, 0xd0d0d0),
	FLEET_BEACON(     Race.PROTOSS, 500, 500,  0,     false, Attack.NONE,   0,   0,     0,    false, 4.2f, 2.9f,  0xc8a030, 0xffd040);

	/** SC2 hit points and damage to Minecraft health (10 health per 35 hit points). */
	public static final double SCALE = 10.0 / 35.0;
	public static final double SPEED_SCALE = 0.087;
	public static final double BLOCKS_PER_UNIT = 1.153;

	public enum Attack { NONE, MELEE, SPLASH, EXPLODE, RANGED, SLOW, LINE, PULL, SPAWN_BROODLINGS, SPAWN_LOCUSTS }

	/** The three races; what each does its own way is in its rules ({@link dev.starmine.race.RaceRules}). */
	public enum Race {
		ZERG, TERRAN, PROTOSS;

		public dev.starmine.race.RaceRules rules() {
			return dev.starmine.race.Races.of(this);
		}
	}

	public final Race race;
	public final String id;
	public final double health, shields, speed, damage, range;
	public final int interval;
	public final boolean flying, aggressive;
	public final Attack attack;
	public final float width, height;
	public final int eggBase, eggSpots;

	UnitKind(double sc2Hp, double sc2Speed, boolean flying, Attack attack, double sc2Damage, double period, double sc2Range,
			boolean aggressive, float width, float height, int eggBase, int eggSpots) {
		this(Race.ZERG, sc2Hp, 0, sc2Speed, flying, attack, sc2Damage, period, sc2Range, aggressive, width, height, eggBase, eggSpots);
	}

	UnitKind(Race race, double sc2Hp, double sc2Speed, boolean flying, Attack attack, double sc2Damage, double period, double sc2Range,
			boolean aggressive, float width, float height, int eggBase, int eggSpots) {
		this(race, sc2Hp, 0, sc2Speed, flying, attack, sc2Damage, period, sc2Range, aggressive, width, height, eggBase, eggSpots);
	}

	/** With SC2 plasma shields (protoss): absorbed before health, they come back after a while ({@link StarUnit}). */
	UnitKind(Race race, double sc2Hp, double sc2Shields, double sc2Speed, boolean flying, Attack attack, double sc2Damage, double period,
			double sc2Range, boolean aggressive, float width, float height, int eggBase, int eggSpots) {
		this.race = race;
		this.id = name().toLowerCase().replace("_", "");
		this.health = Math.max(1, Math.round(sc2Hp * SCALE));
		this.shields = Math.round(sc2Shields * SCALE);
		this.speed = sc2Speed == 0 ? 0 : Math.max(0.12, sc2Speed * SPEED_SCALE);
		this.flying = flying;
		this.attack = attack;
		this.damage = Math.round(sc2Damage * SCALE * 2) / 2.0;
		this.interval = Math.max(8, (int) Math.round(period * 20));
		this.range = Math.max(2, sc2Range * BLOCKS_PER_UNIT + 1);
		this.aggressive = aggressive;
		this.width = width;
		this.height = height;
		this.eggBase = eggBase;
		this.eggSpots = eggSpots;
	}

	/** Summons live only as long as in SC2 (broodlings 5.7 s, locusts 18 s); 0 = forever. */
	public int lifeTicks() {
		return this == BROODLING ? 114 : this == LOCUST ? 360 : 0;
	}

	/** What a ranged attack throws (drawn as that item until SC2 missile models are imported). */
	public Item missile() {
		return switch (this) {
			case ROACH, INFESTOR -> Items.SLIME_BALL;
			case RAVAGER -> Items.MAGMA_CREAM;
			case MUTALISK, HYDRALISK -> Items.PRISMARINE_SHARD;
			case CORRUPTOR -> Items.FERMENTED_SPIDER_EYE;
			case BROOD_LORD -> Items.EGG;
			default -> Items.PRISMARINE_CRYSTALS;
		};
	}

	/**
	 * Economy from SC2 Data (VoidMulti, resolved through Modkit): total minerals and vespene, supply used
	 * (food), train or build time in ticks, supply provided, and transport size (SC2 cargo; 0 = cannot be
	 * carried). Per race: {@link dev.starmine.race.RaceRules#economy}.
	 */
	public record Economy(int minerals, int vespene, double food, int trainTicks, int supply, int cargo) {
	}

	public Economy economy() {
		return race.rules().economy(this);
	}

	/** Immobile parts of a hive or base: no movement, no knockback, solid to walk on. */
	public boolean structure() {
		return this == HATCHERY || this == EGG || building();
	}

	/** Built buildings: what a drone mutates into, what an SCV builds (the command center too; not the hatchery). */
	public boolean building() {
		return race != Race.ZERG ? speed == 0 : ordinal() >= SPAWNING_POOL.ordinal() && ordinal() <= SPORE_CRAWLER.ordinal();
	}

	/** The building a hive or base is anchored to: the hatchery, the command center, the nexus. */
	public boolean main() {
		return this == HATCHERY || this == COMMAND_CENTER || this == NEXUS;
	}

	/** Vespene buildings (owner 14.10): put on a vein outlet; workers draw vespene inside them. */
	public boolean gas() {
		return this == EXTRACTOR || this == REFINERY || this == ASSIMILATOR;
	}

	/** The worker of a race (a base's first units). */
	public static UnitKind workerOf(Race race) {
		return race.rules().worker();
	}

	/** Workers gather and build: the drone, the SCV. */
	public boolean worker() {
		return this == DRONE || this == SCV || this == PROBE;
	}

	/** Buildings that shoot at foes in range: spine and spore crawlers, bunker, missile turret. */
	public boolean defender() {
		return this == SPINE_CRAWLER || this == SPORE_CRAWLER || this == BUNKER || this == MISSILE_TURRET || this == PHOTON_CANNON;
	}

	/** Defenders that shoot flying foes (spore crawler, missile turret, bunker) and ground ones (spine crawler, bunker). */
	public boolean antiAir() {
		return this == SPORE_CRAWLER || this == MISSILE_TURRET || this == BUNKER || this == PHOTON_CANNON;
	}

	public boolean antiGround() {
		return this == SPINE_CRAWLER || this == BUNKER || this == PHOTON_CANNON;
	}

	/** Bio units a medivac heals (not machines, not buildings). */
	public boolean biological() {
		return this == MARINE || this == MARAUDER || this == REAPER || this == GHOST || this == ZEALOT || this == ADEPT
			|| this == HIGH_TEMPLAR || this == DARK_TEMPLAR;
	}

	/** What this trains (a building, a zerg larva): the kinds whose producer it is, in kind order. */
	public java.util.List<UnitKind> trains() {
		return TRAINS.computeIfAbsent(this, producer -> java.util.Arrays.stream(values()).filter(kind -> kind.producer() == producer).toList());
	}

	private static final java.util.Map<UnitKind, java.util.List<UnitKind>> TRAINS = new java.util.concurrent.ConcurrentHashMap<>();

	/** What trains it (a building, a larva); null for buildings and summons. */
	public UnitKind producer() {
		return race.rules().producer(this);
	}

	/** Its SC2 grid hotkey on the command card. */
	public char hotkey() {
		return race.rules().hotkey(this);
	}

	/** How far it sees, in blocks (about SC2 sight x {@link #BLOCKS_PER_UNIT}): fog of war in command mode. */
	public double sight() {
		if (building()) return this == COMMAND_CENTER ? 13 : 10;
		return switch (this) {
			case SCV, PROBE -> 9;
			case GHOST, VIKING, BANSHEE, BATTLECRUISER, MEDIVAC, THOR, OBSERVER, ORACLE, TEMPEST, CARRIER, MOTHERSHIP -> 13;
			default -> 11;
		};
	}

	/** What it needs in the base first (SC2 tech; per race {@link dev.starmine.race.RaceRules#requires}). */
	public UnitKind requires() {
		return race.rules().requires(this);
	}

	/** Main building tier it needs: 2 = lair, 3 = hive (zerg). */
	public int tier() {
		return race.rules().tier(this);
	}

	/** Owner's sizes: the drone 2 blocks across, the lurker 3, overlord, overseer and brood lord 4. */
	public float modelScale() {
		return switch (this) {
			case ZERGLING -> 1.25f;
			case DRONE -> 1.4f;
			case HATCHERY -> 2.0f;
			case LURKER -> 1.1f;
			case OVERLORD -> 1.6f;
			case OVERSEER -> 1.44f;
			case BROOD_LORD -> 1.06f;
			default -> 1f;
		};
	}

	/** Flying kinds keep at least this many blocks above the ground unless diving at a target. */
	public double hoverHeight() {
		return switch (this) {
			case OVERLORD, OVERSEER -> 6;
			case BROOD_LORD -> 7;
			case MUTALISK, CORRUPTOR, VIPER -> 4;
			case VIKING, MEDIVAC, BANSHEE, WARP_PRISM, PHOENIX, VOID_RAY, ORACLE -> 5;
			case OBSERVER -> 6;
			case INTERCEPTOR -> 4;
			case BATTLECRUISER, TEMPEST, CARRIER -> 8;
			case MOTHERSHIP -> 10;
			default -> 0;
		};
	}

	/** SC2 energy of casters (owner 14.10, abilities): the most it holds; 0 for kinds without energy. */
	public int maxEnergy() {
		return switch (this) {
			case GHOST, BANSHEE, MEDIVAC, QUEEN, INFESTOR, VIPER, OVERSEER, HIGH_TEMPLAR, SENTRY, ORACLE, PHOENIX, MOTHERSHIP, NEXUS -> 200;
			case SHIELD_BATTERY -> 100;
			default -> 0;
		};
	}

	/** SC2 energy a caster starts with. */
	public int startEnergy() {
		return switch (this) {
			case GHOST -> 75;
			case QUEEN -> 25;
			case SHIELD_BATTERY -> 100;
			default -> maxEnergy() > 0 ? 50 : 0;
		};
	}

	/** Detectors see cloaked and burrowed units within their sight (SC2): overseer, observer, spore crawler, missile turret, photon cannon. */
	public boolean detector() {
		return this == OVERSEER || this == OBSERVER || this == SPORE_CRAWLER || this == MISSILE_TURRET || this == PHOTON_CANNON;
	}

	/** Always cloaked (SC2): the dark templar and the observer. */
	public boolean permanentlyCloaked() {
		return this == DARK_TEMPLAR || this == OBSERVER;
	}

	/** SC2 armored units (every building too): bonus damage and some spells look at it ({@link #bonusDamage}). */
	public boolean armored() {
		if (structure()) return true;
		return switch (this) {
			case OVERLORD, OVERSEER, ROACH, RAVAGER, LURKER, CORRUPTOR, BROOD_LORD, INFESTOR, SWARM_HOST, VIPER, ULTRALISK,
				MARAUDER, SIEGE_TANK, THOR, VIKING, MEDIVAC, BATTLECRUISER,
				STALKER, IMMORTAL, COLOSSUS, DISRUPTOR, WARP_PRISM, VOID_RAY, ORACLE, TEMPEST, CARRIER, MOTHERSHIP -> true;
			default -> false;
		};
	}

	/**
	 * SC2 base armor (before upgrades): taken off every hit that reaches its health, at least 0.5 left
	 * ({@link StarUnit#hurt}); shields have none. Buildings 1 (a planetary fortress 3 in its mode).
	 */
	public int armor() {
		if (structure()) return 1;
		return switch (this) {
			case ROACH, RAVAGER, LURKER, QUEEN, BROOD_LORD, SWARM_HOST, VIPER, OVERSEER,
				MARAUDER, SIEGE_TANK, THOR, MEDIVAC,
				ZEALOT, STALKER, SENTRY, ADEPT, DARK_TEMPLAR, IMMORTAL, COLOSSUS, DISRUPTOR -> 1;
			case CORRUPTOR, ULTRALISK, TEMPEST, CARRIER, MOTHERSHIP -> 2;
			case BATTLECRUISER -> 3;
			default -> 0;
		};
	}

	/** Strikes in one attack of its weapon (SC2: a zealot's two blades, 8 each, are 16 here): armor counts once per strike. */
	public int strikes() {
		return switch (this) {
			case ZEALOT, REAPER, COLOSSUS, BANSHEE, VIKING, PHOENIX -> 2;
			default -> 1;
		};
	}

	/** SC2 light units. */
	public boolean light() {
		return switch (this) {
			case ZERGLING, DRONE, BROODLING, LOCUST, HYDRALISK, MUTALISK, CHANGELING, LARVA, SCV, MARINE, REAPER, HELLION, BANSHEE,
				PROBE, ZEALOT, SENTRY, ADEPT, HIGH_TEMPLAR, DARK_TEMPLAR, PHOENIX, OBSERVER, INTERCEPTOR -> true;
			default -> false;
		};
	}

	/** SC2 massive units: a graviton beam cannot lift them, force fields do not stop them. */
	public boolean massive() {
		return this == ULTRALISK || this == BROOD_LORD || this == THOR || this == BATTLECRUISER || this == COLOSSUS || this == ARCHON
			|| this == TEMPEST || this == CARRIER || this == MOTHERSHIP;
	}

	/** SC2 mechanical units and terran and protoss buildings: an SCV repairs the terran ones ({@link dev.starmine.ability.TerranAbilities}). */
	public boolean mechanical() {
		if (structure()) return race != Race.ZERG;
		return race != Race.ZERG && !biological() && this != HIGH_TEMPLAR && this != ARCHON;
	}

	/**
	 * SC2 bonus damage of its weapon against an attribute (light, armored), in SC2 hit points: the
	 * counters of SC2 (the marauder against stalkers, the immortal against tanks, banelings against
	 * marines…) come from it. Applied with every hit ({@link dev.starmine.ability.Passives#bonus}).
	 */
	public double bonusDamage(UnitKind target) {
		boolean armored = target.armored(), light = target.light();
		return switch (this) {
			case MARAUDER -> armored ? 10 : 0;
			case STALKER -> armored ? 5 : 0;
			case IMMORTAL -> armored ? 30 : 0;
			case SIEGE_TANK, VIKING -> armored ? 10 : 0;
			case VOID_RAY -> armored ? 4 : 0;
			case SPINE_CRAWLER -> armored ? 5 : 0;
			case ADEPT, HELLION -> light ? (this == ADEPT ? 12 : 6) : 0;
			case BANELING -> light ? 15 : 0;
			case COLOSSUS -> light ? 10 : 0;
			case GHOST, PHOENIX -> light ? (this == GHOST ? 10 : 5) : 0;
			case ORACLE -> light ? 7 : 0;
			case ARCHON -> target.biological() ? 10 : 0;
			case CORRUPTOR -> target.massive() ? 6 : 0;
			default -> 0;
		};
	}

	/** Ground units the anti-air also hits (SC2: the colossus is tall enough). */
	public boolean hitByAntiAir() {
		return flying || this == COLOSSUS;
	}

	/** Ticks the body stays after death so the death animation can play. */
	public int deathTicks() {
		return flying ? 60 : 50;
	}
}
