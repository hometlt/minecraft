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
 * Hitboxes follow the imported model sizes (1.153 blocks per SC2 unit, a zergling 1 block tall).
 * Model and sound names follow the enum name in lower case without underscores.
 */
public enum ZergKind {
	//           sc2Hp  speed  fly    attack                  dmg   period range  aggressive  hitbox w/h (from model size)  egg colours
	ZERGLING(    35,    4.13,  false, Attack.MELEE,           5,    0.497, 0,    true,  0.8f, 1.0f, 0x4b1f4f, 0xc79a52),
	DRONE(       40,    3.94,  false, Attack.MELEE,           5,    1.07,  0,    false, 1.2f, 0.7f,  0x6a4a2a, 0xd8c08a),
	OVERLORD(    200,   0.902, true,  Attack.NONE,            0,    0,     0,    false, 2.0f, 3.0f,  0x5a3a5a, 0xb08ab0),
	OVERSEER(    200,   2.62,  true,  Attack.NONE,            0,    0,     0,    false, 2.0f, 2.4f,  0x3a5a3a, 0xc0d080),
	QUEEN(       175,   1.31,  false, Attack.RANGED,          8,    0.71,  5,    true,  2.0f, 2.6f,  0x40205a, 0xe0b040),
	ROACH(       145,   3.15,  false, Attack.RANGED,          16,   1.43,  4,    true,  1.3f, 1.0f,  0x5a3a20, 0x90b040),
	RAVAGER(     120,   3.85,  false, Attack.RANGED,          16,   1.14,  6,    true,  2.0f, 2.0f,  0x7a3010, 0xf07020),
	BANELING(    30,    3.5,   false, Attack.EXPLODE,         35,   0,     0,    true,  1.0f, 0.95f,  0x30602a, 0x9ae050),
	HYDRALISK(   90,    3.15,  false, Attack.RANGED,          12,   0.59,  5,    true,  1.3f, 2.1f,  0x6a2a40, 0xe0a060),
	LURKER(      190,   4.13,  false, Attack.LINE,            20,   1.43,  8,    true,  1.8f, 1.5f,  0x3a2a40, 0xc0a0c0),
	MUTALISK(    120,   5.6,   true,  Attack.RANGED,          9,    1.09,  3,    true,  2.2f, 1.5f,  0x5a2050, 0x40c0a0),
	CORRUPTOR(   200,   4.725, true,  Attack.RANGED,          14,   1.36,  6,    true,  2.0f, 1.7f,  0x5a2a6a, 0xf0a040),
	BROOD_LORD(  225,   1.97,  true,  Attack.SPAWN_BROODLINGS, 20,  1.79,  10,   true,  3.0f, 1.2f,  0x402a2a, 0xd09060),
	INFESTOR(    90,    3.15,  false, Attack.SLOW,            30,   4.0,   10,   true,  1.8f, 2.2f,  0x40303a, 0xa0e060),
	SWARM_HOST(  160,   3.15,  false, Attack.SPAWN_LOCUSTS,   0,    22.0,  12,   true,  2.0f, 1.9f,  0x4a3a2a, 0xd0b070),
	VIPER(       150,   4.13,  true,  Attack.PULL,            0,    8.0,   9,    true,  2.0f, 2.1f,  0x2a3a5a, 0xe0c060),
	ULTRALISK(   500,   4.13,  false, Attack.SPLASH,          35,   0.61,  0,    true,  2.8f, 3.7f,  0x5a4a3a, 0xe0d0b0),
	BROODLING(   20,    5.37,  false, Attack.MELEE,           4,    0.46,  0,    true,  0.8f, 0.5f,  0x3a2a20, 0x907050),
	LOCUST(      50,    2.62,  false, Attack.MELEE,           10,   0.43,  0,    true,  0.8f, 0.8f,  0x4a5a2a, 0xb0d060),
	CHANGELING(  5,     3.15,  false, Attack.NONE,            0,    0,     0,    false, 0.9f, 1.0f,  0x6a5a3a, 0xf0e0b0);

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
		this.speed = Math.max(0.12, sc2Speed * SPEED_SCALE);
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

	/** Ticks the body stays after death so the death animation can play. */
	public int deathTicks() {
		return flying ? 60 : 50;
	}
}
