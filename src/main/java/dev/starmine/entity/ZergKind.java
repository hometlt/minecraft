package dev.starmine.entity;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Every StarMine zerg: its StarCraft II numbers and how they become Minecraft behaviour.
 *
 * SC2 values (LotV multiplayer: life, speed, weapon damage and period, range) are converted with
 * the scales the owner accepted on the zergling: health and damage x{@link #SCALE} (10/35; a
 * zergling has half a player's health, a hydralisk more than a player), attack period kept,
 * walking speed x{@link #SPEED_SCALE}, range in blocks = SC2 range x{@link #BLOCKS_PER_UNIT}.
 * Hitboxes follow the imported model sizes (1.153 blocks per SC2 unit, a zergling 1 block tall);
 * drones (2 blocks across) dig and walk 2x2 tunnels.
 * Model and sound names follow the enum name in lower case without underscores.
 */
public enum ZergKind {
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
	SPINE_CRAWLER(     300,  0, false, Attack.NONE,      25, 1.32, 7, true,  2.0f, 2.0f, 0x6a3a3a, 0xe0b0a0),
	SPORE_CRAWLER(     400,  0, false, Attack.NONE,      15, 0.61, 7, true,  2.0f, 2.4f, 0x3a5a3a, 0xc0e0a0);

	/** SC2 hit points and damage to Minecraft health (10 health per 35 hit points). */
	public static final double SCALE = 10.0 / 35.0;
	public static final double SPEED_SCALE = 0.087;
	public static final double BLOCKS_PER_UNIT = 1.153;

	public enum Attack { NONE, MELEE, SPLASH, EXPLODE, RANGED, SLOW, LINE, PULL, SPAWN_BROODLINGS, SPAWN_LOCUSTS }

	public final String id;
	public final double health, speed, damage, range;
	public final int interval;
	public final boolean flying, aggressive;
	public final Attack attack;
	public final float width, height;
	public final int eggBase, eggSpots;

	ZergKind(double sc2Hp, double sc2Speed, boolean flying, Attack attack, double sc2Damage, double period, double sc2Range,
			boolean aggressive, float width, float height, int eggBase, int eggSpots) {
		this.id = name().toLowerCase().replace("_", "");
		this.health = Math.max(1, Math.round(sc2Hp * SCALE));
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
	 * Hive economy from SC2 Data (VoidMulti, resolved through Modkit): total minerals and vespene,
	 * supply used (food), larva/hatchery train time at Faster speed in ticks (Normal game seconds / 1.4),
	 * supply provided, and transport size in an overlord (SC2 cargo; 0 = cannot be carried).
	 */
	public record Economy(int minerals, int vespene, double food, int trainTicks, int supply, int cargo) {
	}

	private static int ticks(double normalSeconds) {
		return (int) Math.round(normalSeconds / 1.4 * 20);
	}

	public Economy economy() {
		return switch (this) {
			case DRONE -> new Economy(50, 0, 1, ticks(17), 0, 1);
			case ZERGLING -> new Economy(25, 0, 0.5, ticks(24), 0, 1);
			case OVERLORD -> new Economy(100, 0, 0, ticks(25), 8, 0);
			case OVERSEER -> new Economy(150, 50, 0, ticks(16.67), 8, 0);
			case QUEEN -> new Economy(175, 0, 2, ticks(50), 0, 2);
			case ROACH -> new Economy(75, 25, 2, ticks(27), 0, 2);
			case RAVAGER -> new Economy(100, 100, 3, ticks(12.14), 0, 4);
			case BANELING -> new Economy(50, 25, 0.5, ticks(20), 0, 2);
			case HYDRALISK -> new Economy(100, 50, 2, ticks(33), 0, 2);
			case LURKER -> new Economy(150, 150, 3, ticks(25), 0, 4);
			case MUTALISK -> new Economy(100, 100, 2, ticks(33), 0, 0);
			case CORRUPTOR -> new Economy(150, 100, 2, ticks(40), 0, 0);
			case BROOD_LORD -> new Economy(300, 250, 4, ticks(33.57), 0, 0);
			case INFESTOR -> new Economy(100, 150, 2, ticks(50), 0, 2);
			case SWARM_HOST -> new Economy(100, 75, 3, ticks(40), 0, 4);
			case VIPER -> new Economy(100, 200, 3, ticks(40), 0, 0);
			case ULTRALISK -> new Economy(275, 200, 6, ticks(55), 0, 8);
			case HATCHERY -> new Economy(325, 0, 0, ticks(71), 8, 0);
			case SPAWNING_POOL -> new Economy(200, 0, 0, seconds(46), 0, 0);
			case EVOLUTION_CHAMBER -> new Economy(75, 0, 0, seconds(25), 0, 0);
			case ROACH_WARREN -> new Economy(150, 0, 0, seconds(39), 0, 0);
			case BANELING_NEST -> new Economy(100, 50, 0, seconds(43), 0, 0);
			case HYDRALISK_DEN -> new Economy(100, 100, 0, seconds(29), 0, 0);
			case SPIRE -> new Economy(200, 200, 0, seconds(71), 0, 0);
			case INFESTATION_PIT -> new Economy(100, 100, 0, seconds(36), 0, 0);
			case ULTRALISK_CAVERN -> new Economy(150, 200, 0, seconds(46), 0, 0);
			case SPINE_CRAWLER -> new Economy(100, 0, 0, seconds(36), 0, 0);
			case SPORE_CRAWLER -> new Economy(75, 0, 0, seconds(21), 0, 0);
			case BROODLING, LOCUST, CHANGELING, LARVA, EGG -> new Economy(0, 0, 0, 0, 0, this == LARVA || this == EGG ? 0 : 1);
		};
	}

	/** Immobile hive parts: no movement, no knockback, solid to walk on. */
	public boolean structure() {
		return this == HATCHERY || this == EGG || building();
	}

	/** Buildings a drone mutates into (not the hatchery, not cocoons). */
	public boolean building() {
		return ordinal() >= SPAWNING_POOL.ordinal();
	}

	/** Crawlers defend their hive: spines hit ground foes, spores flying ones. */
	public boolean crawler() {
		return this == SPINE_CRAWLER || this == SPORE_CRAWLER;
	}

	/** SC2 build time at Faster speed (real seconds), in ticks. */
	private static int seconds(double s) {
		return (int) Math.round(s * 20);
	}

	/**
	 * What a unit or building needs in the hive first (SC2 tech): the spawning pool for zerglings,
	 * queens and crawlers, the warren for roaches, the nest for banelings, the den for hydralisks and
	 * lurkers, the spire for flyers, the pit for infestors, swarm hosts and vipers, the cavern for
	 * ultralisks; second-tier buildings need a lair, the cavern a hive ({@link #tier()}).
	 */
	public ZergKind requires() {
		return switch (this) {
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

	/** Hatchery tier a building needs: 2 = lair, 3 = hive. */
	public int tier() {
		return switch (this) {
			case HYDRALISK_DEN, SPIRE, INFESTATION_PIT -> 2;
			case ULTRALISK_CAVERN -> 3;
			default -> 1;
		};
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
			default -> 0;
		};
	}

	/** Ticks the body stays after death so the death animation can play. */
	public int deathTicks() {
		return flying ? 60 : 50;
	}
}
