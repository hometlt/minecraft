package dev.starmine.entity;

import dev.starmine.StarMine;
import dev.starmine.creep.CreepGrowth;
import dev.starmine.creep.CreepTouch;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveTerrain;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import dev.starmine.hive.HiveLogic;
import dev.starmine.hive.HiveSavedData;
import dev.starmine.hive.HiveResources;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomFlyingGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;

/**
 * One StarMine unit or building, zerg or terran; its {@link UnitKind} (found from the entity type)
 * decides everything. Units of one race are allies; zerg and terrans fight each other.
 *
 * Terrans (owner 14.10, dev.starmine.colony): a base the player commands. Units follow orders
 * ({@link #command}: move, attack-move, patrol, hold, stop; SCVs also gather, return cargo and build),
 * stand where they were sent and defend themselves; buildings train units in a queue of up to 5
 * (paid when queued) toward a rally point, the engineering bay researches weapons and armor, the
 * bunker and missile turret shoot, the medivac heals bio units. A building grows only while an SCV
 * builds it. Terran shots are instant (no projectile).
 *
 * Ground kinds walk, flying kinds hover with flying navigation and fall when they die. Attacks:
 * melee (zergling, drone, broodling, locust), splash melee (ultralisk), explode on contact
 * (baneling), spit projectiles (queen, roach, ravager, hydralisk, mutalisk, corruptor), a slowing
 * spit (infestor), a spine line through everything ahead (lurker), pulling the target close (viper),
 * eggs that hatch broodlings (brood lord), and locust waves (swarm host). The queen also heals
 * nearby zerg. Every attack swings, which the client turns into an attack animation. Zerg never
 * fight each other.
 *
 * Hive: a member knows its hive and home hatchery, counts in the hive's supply and returns home when
 * idle. The hatchery runs the hive ({@link HiveLogic}); larvae stay by it; a cocoon hatches its units
 * when its SC2 train time is over. Hatchery and cocoon are structures: immobile and solid. Flying
 * kinds hover above the ground; the overlord ferries zerg that cannot reach their target; zerg near
 * an overseer see invisible players and creatures, which glow around the overseer.
 *
 * Drone: works for its hive ({@link DroneWork}: trees, ores, tunnels, carrying), carries its load in
 * front of it (dropped if it dies), never starts a fight and gives up defending itself after 10 s or
 * 6 blocks. Buildings shove off whoever stands on top of them.
 */
public class StarUnit extends Monster implements RangedAttackMob {
	public final UnitKind kind;
	/** Client only: tick of the current attack swing and which attack clip it plays. */
	public int attackStartTick = Integer.MIN_VALUE / 2;
	public int attackVariant;
	private int lifeLeft;
	private @org.jetbrains.annotations.Nullable Vec3 blindAttack;
	/** The hive this zerg belongs to (its hatchery's UUID) and where that hatchery stands. */
	private UUID hiveId;
	private BlockPos home;
	/** A cocoon's future: what hatches, how many, in how many ticks. */
	private UnitKind cocoonKind;
	private int cocoonCount, cocoonTicks;
	/**
	 * A nest dweller of a generated hive (see {@link dev.starmine.hive.HiveStructure}): it keeps to its
	 * hall underground instead of the hatchery and is not counted in the hive's members or supply.
	 * A nest egg sleeps until a creature disturbs it (or, rarely, by itself) and then hatches guards.
	 */
	private BlockPos nest;
	/** Cocoon ticks meaning "dormant": far more than any real train time. */
	public static final int DORMANT = 1 << 30;
	/** Ticks this ground zerg has had a target it has no path to (the overlord ferries such zerg). */
	private int unreachable;
	/** An overseer is near: invisible players and creatures are seen (see the visibility mixin). */
	private boolean overseerNear;
	/**
	 * Drone inventory (owner): one slot of a valuable resource — one kind at a time, up to
	 * {@link #LOAD} blocks, shown in front of it — and {@link #FILL_SLOTS} slots of cobblestone and other
	 * stone it builds bridges and steps with. Earth and gravel are not kept. All of it drops if it dies.
	 */
	public static final int LOAD = 8, FILL_SLOTS = 8;
	public int cargoMinerals, cargoVespene, cargoDiamonds, cargoBlocks;
	private ItemStack valuable = ItemStack.EMPTY;
	private boolean precious;
	private final List<ItemStack> fillSlots = new ArrayList<>();
	private static final net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> FILL_TAG =
		net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK, StarMine.id("drone_fill"));
	private static final EntityDataAccessor<ItemStack> CARRIED = SynchedEntityData.defineId(StarUnit.class, EntityDataSerializers.ITEM_STACK);
	/** Drone defending itself: ticks since it took up its attacker. */
	private int defendTicks;
	/** Drone sent to mutate into a building at a site; a building still being built (ticks left). */
	private UnitKind buildKind;
	/** A supply platform's own minerals and vespene while it belongs to no base (owner 17.10; dev.starmine.colony.Storage). */
	public int bankMinerals, bankVespene;
	private BlockPos buildSite;
	private int constructTicks;
	private int constructTotal;
	/** Drone fleeing from an armed attacker until this game time. */
	private long fleeUntil;
	/** On a raid: where the party goes, and the game time it ends (then everyone goes home). */
	private BlockPos raidTarget;
	private long raidUntil;

	/** A terran unit's order from its player (see {@link UnitGoals.Orders}). */
	public enum Order { NONE, MOVE, ATTACK_MOVE, PATROL, HOLD }

	private Order order = Order.NONE;
	private Vec3 orderPos, patrolFrom;
	/** An SCV's work order: gather at a block, or bring its cargo home (taken by its work goal). */
	public enum Work { NONE, GATHER, RETURN }

	private Work work = Work.NONE;
	private BlockPos workPos;
	/** A player's mining area for this worker (owner 17.10): it takes ore and minerals only in it, nearest first, until none is left. */
	private @org.jetbrains.annotations.Nullable net.minecraft.world.level.levelgen.structure.BoundingBox workArea;

	public @org.jetbrains.annotations.Nullable net.minecraft.world.level.levelgen.structure.BoundingBox workArea() {
		return workArea;
	}

	/** Shows (server side) what the worker works now: its area and the block it mines or the outlet it draws. */
	void showWork(@org.jetbrains.annotations.Nullable BlockPos at) {
		var area = workArea;
		String shown = (area == null ? "" : area.minX() + "," + area.minY() + "," + area.minZ() + "," + area.maxX() + "," + area.maxY() + "," + area.maxZ())
			+ ";" + (at == null ? "" : at.getX() + "," + at.getY() + "," + at.getZ());
		if (!shown.equals(this.entityData.get(WORK_SHOWN))) this.entityData.set(WORK_SHOWN, shown);
	}

	/** Its mining area as the client knows it, or null. */
	public @org.jetbrains.annotations.Nullable net.minecraft.world.phys.AABB shownArea() {
		String[] parts = this.entityData.get(WORK_SHOWN).split(";", -1);
		if (parts.length < 1 || parts[0].isEmpty()) return null;
		String[] n = parts[0].split(",");
		if (n.length != 6) return null;
		return new net.minecraft.world.phys.AABB(Integer.parseInt(n[0]), Integer.parseInt(n[1]), Integer.parseInt(n[2]),
			Integer.parseInt(n[3]) + 1, Integer.parseInt(n[4]) + 1, Integer.parseInt(n[5]) + 1);
	}

	/** The block it works now as the client knows it, or null. */
	public @org.jetbrains.annotations.Nullable BlockPos shownTarget() {
		String[] parts = this.entityData.get(WORK_SHOWN).split(";", -1);
		if (parts.length < 2 || parts[1].isEmpty()) return null;
		String[] n = parts[1].split(",");
		return n.length != 3 ? null : new BlockPos(Integer.parseInt(n[0]), Integer.parseInt(n[1]), Integer.parseInt(n[2]));
	}

	/** Sends a worker to mine an area (null: anywhere again). */
	public void commandArea(@org.jetbrains.annotations.Nullable net.minecraft.world.level.levelgen.structure.BoundingBox area) {
		commandWork(Work.NONE, null);
		this.workArea = area;
	}
	/** An SCV building this building (its UUID), and whether a building had a builder at its last check. */
	private UUID constructing;
	private boolean builderNear;
	/** Where a building stands on the grid (owner 15.10: buildings do not move — not by water, blows or pushes); null while it flies. */
	private @org.jetbrains.annotations.Nullable Vec3 anchor;
	/** What the cargo is (resource label) for the base's tally. */
	public String cargoLabel;
	/** Terran production: kinds queued (paid), ticks into the first, where new units go. */
	private final List<UnitKind> queue = new ArrayList<>();
	private int trainTicks;
	/** A reactor's second slot: the second queued unit trains alongside the first, on its own time (SC2). */
	private int secondTicks;
	/** A warp gate: ticks till it may warp in the next unit (SC2 warp gate cooldown). */
	private int warpCooldown;
	/**
	 * A flyer's height over its usual cruise, in blocks (owner 18.10: the command card raises and lowers
	 * aircraft, down to a block over the ground: into a cave's mouth or a tunnel).
	 */
	private int altitude;
	/** A hatchery: ticks toward its next larva (SC2: each hatchery has its own, {@link dev.starmine.hive.HiveLogic#LARVA_TICKS}). */
	public int larvaTicks;
	public static final int ALTITUDE_STEP = 2, ALTITUDE_MIN = -12, ALTITUDE_MAX = 24;
	private Vec3 rally;
	/** Engineering bay research: "weapons" or "armor" and ticks left. */
	private String research;
	private int researchTicks;
	public static final int QUEUE = 5;
	/** Synced for the command view: the base's player, progress (0-1000) and queue (kind ids). */
	private static final EntityDataAccessor<java.util.Optional<UUID>> OWNER =
		SynchedEntityData.defineId(StarUnit.class, EntityDataSerializers.OPTIONAL_UUID);
	/** Owner 19.10, SC2 neural parasite: the player who commands it for now (the infestor's), synced for the command view. */
	private static final EntityDataAccessor<java.util.Optional<UUID>> CONTROLLER =
		SynchedEntityData.defineId(StarUnit.class, EntityDataSerializers.OPTIONAL_UUID);
	private static final EntityDataAccessor<Integer> PROGRESS = SynchedEntityData.defineId(StarUnit.class, EntityDataSerializers.INT);
	/** Owner 19.10: its side's team colour (ARGB, {@link dev.starmine.faction.TeamColors}), synced for the minimap and its model. */
	private static final EntityDataAccessor<Integer> TEAM = SynchedEntityData.defineId(StarUnit.class, EntityDataSerializers.INT);
	/** Protoss plasma shields left (health units); they absorb damage first and come back {@link #SHIELD_DELAY} after the last hit. */
	private static final EntityDataAccessor<Float> SHIELD = SynchedEntityData.defineId(StarUnit.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<org.joml.Vector3f> STRIKE_POINT = SynchedEntityData.defineId(StarUnit.class, EntityDataSerializers.VECTOR3);
	/** Its base's upgrade levels for it (owner 17.10): weapons, armor and shields, 4 bits each ({@link #applyUpgrades}). */
	private static final EntityDataAccessor<Integer> UPGRADES = SynchedEntityData.defineId(StarUnit.class, EntityDataSerializers.INT);
	/** SC2: shields regenerate 2 a second after 10 game seconds (7 real) without damage. */
	public static final int SHIELD_DELAY = 140;
	private long lastHurt;
	/** Protoss: whether a pylon powers this building (checked each second). */
	private boolean powered = true;
	private static final EntityDataAccessor<String> QUEUE_IDS = SynchedEntityData.defineId(StarUnit.class, EntityDataSerializers.STRING);
	/** Its autocast abilities' ids and "gather" (owner 19.10), for the command card's gold frames. */
	private static final EntityDataAccessor<String> AUTOCAST = SynchedEntityData.defineId(StarUnit.class, EntityDataSerializers.STRING);
	/** Abilities (owner 14.10, dev.starmine.ability): energy (SC2 units), cloaked, seen by a foe's detector, mode (a sieged tank). */
	private static final EntityDataAccessor<Float> ENERGY = SynchedEntityData.defineId(StarUnit.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Boolean> CLOAKED = SynchedEntityData.defineId(StarUnit.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Boolean> REVEALED = SynchedEntityData.defineId(StarUnit.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Integer> MODE = SynchedEntityData.defineId(StarUnit.class, EntityDataSerializers.INT);
	/** A change under way (owner 17.10, drawn as a morph): the mode it goes to + 1, or the kind it becomes + 1000; 0 none. And its length in ticks. */
	private static final EntityDataAccessor<Integer> CHANGE = SynchedEntityData.defineId(StarUnit.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> CHANGE_TICKS = SynchedEntityData.defineId(StarUnit.class, EntityDataSerializers.INT);
	/** A changeling's look (dev.starmine.ability.Changeling): "" its own, "villager", or a player's UUID. */
	private static final EntityDataAccessor<String> DISGUISE = SynchedEntityData.defineId(StarUnit.class, EntityDataSerializers.STRING);
	/** A worker's building on its way to the site ("KIND x y z", or empty): its player sees the site's ghost. */
	private static final EntityDataAccessor<String> BUILD_PLAN = SynchedEntityData.defineId(StarUnit.class, EntityDataSerializers.STRING);
	/** A worker's work shown in the command view (owner 17.10): its mining area and the block it works, "x0,y0,z0,x1,y1,z1;x,y,z" (parts may be empty). */
	private static final EntityDataAccessor<String> WORK_SHOWN = SynchedEntityData.defineId(StarUnit.class, EntityDataSerializers.STRING);
	/** Cooldowns, channels, mode changes, unloading (server). */
	public final dev.starmine.ability.AbilityState abilities = new dev.starmine.ability.AbilityState();
	/**
	 * The player who plays as this unit (owner 14.10, dev.starmine.possess.Possession): the unit is then
	 * out of the world, its data kept here; the player's body moves, fights and is hit for it.
	 */
	private net.minecraft.server.level.ServerPlayer pilot;

	public StarUnit(EntityType<? extends Monster> type, Level level) {
		super(type, level);
		this.kind = StarMine.kindOf(type);
		// owner 15.10: no experience for larvae (a larva every 11 s would be an experience farm)
		this.xpReward = kind == UnitKind.LARVA ? 0 : (int) Math.min(20, 1 + kind.health / 5);
		this.lifeLeft = kind.lifeTicks();
		this.entityData.set(SHIELD, (float) kind.shields);
		this.entityData.set(ENERGY, (float) kind.startEnergy());
		if (kind.flying) {
			this.moveControl = new FlyingMoveControl(this, 10, true);
			this.setNoGravity(true);
		}
	}

	public static AttributeSupplier.Builder attributes(UnitKind kind) {
		if (kind.structure()) return Monster.createMonsterAttributes()
			.add(Attributes.MAX_HEALTH, kind.health)
			.add(Attributes.MOVEMENT_SPEED, 0)
			.add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
			.add(Attributes.FOLLOW_RANGE, 0);
		return Monster.createMonsterAttributes()
			.add(Attributes.MAX_HEALTH, kind.health)
			.add(Attributes.MOVEMENT_SPEED, kind.speed)
			.add(Attributes.FLYING_SPEED, kind.speed * 1.5)
			.add(Attributes.ATTACK_DAMAGE, Math.max(0.5, kind.damage))
			.add(Attributes.FOLLOW_RANGE, kind.worker() ? 64.0 : kind.race != UnitKind.Race.ZERG ? kind.sight() + 4 : 32.0)
			.add(Attributes.KNOCKBACK_RESISTANCE, Math.min(1.0, kind.health / 150))
			// SC2 cliff walkers: the colossus strides and the reaper jumps up to 9 blocks (owner 17.10: one SC2 cliff level of a laid-over map, 8, and its rim); zerglings leap 2 (owner)
			.add(Attributes.STEP_HEIGHT, kind == UnitKind.COLOSSUS || kind == UnitKind.REAPER ? 9.0 : kind == UnitKind.ZERGLING ? 2.0 : 1.0)
			.add(Attributes.JUMP_STRENGTH, kind == UnitKind.ZERGLING ? 0.62 : 0.42);
	}

	@Override
	protected PathNavigation createNavigation(Level level) {
		if (!StarMine.kindOf(this.getType()).flying) return new UnitNavigation(this, level); // buildings in the way, long ways
		FlyingPathNavigation navigation = new FlyingPathNavigation(this, level);
		navigation.setCanOpenDoors(false);
		navigation.setCanFloat(true);
		navigation.setCanPassDoors(true);
		return navigation;
	}

	@Override
	protected void registerGoals() {
		UnitKind kind = StarMine.kindOf(this.getType());
		if (kind.structure()) return;
		this.goalSelector.addGoal(0, new FloatGoal(this));
		if (kind == UnitKind.LARVA) {
			this.goalSelector.addGoal(4, new ZergGoals.ReturnHome(this, 3));
			this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.6));
			return;
		}
		// every race: orders, attacks, work, foes (UnitGoals); zerg also ferry, raid, plant tumors and roam
		UnitGoals.register(this, kind, this.goalSelector, this.targetSelector);
		if (kind.race != UnitKind.Race.ZERG) return;
		if (kind == UnitKind.OVERLORD) this.goalSelector.addGoal(1, new ZergGoals.Transport(this));
		if (kind != UnitKind.DRONE && kind.attack != UnitKind.Attack.NONE) this.goalSelector.addGoal(3, new ZergGoals.Raid(this));
		if (kind == UnitKind.QUEEN) this.goalSelector.addGoal(3, new ZergGoals.PlantTumor(this));
		if (kind == UnitKind.CHANGELING) this.goalSelector.addGoal(3, new dev.starmine.ability.Changeling.Spy(this));
		this.goalSelector.addGoal(5, kind.hoverHeight() > 0 ? new ZergGoals.HoverWander(this)
			: kind.flying ? new WaterAvoidingRandomFlyingGoal(this, 0.8) : new WaterAvoidingRandomStrollGoal(this, 0.8));
	}

	@Override
	public boolean isAlliedTo(Entity other) {
		return dev.starmine.faction.Faction.allied(this, other) || super.isAlliedTo(other);
	}

	/** Its player (a spawn egg's user, or its base's owner); none for a wild unit. */
	public void setOwner(@org.jetbrains.annotations.Nullable UUID owner) {
		this.entityData.set(OWNER, java.util.Optional.ofNullable(owner));
		if (owner != null) this.setPersistenceRequired(); // a player's unit never despawns, however far the player goes
	}

	/** A unit from a player's spawn egg belongs to that player ({@link dev.starmine.StarSpawnEggItem}). */
	@Override
	public net.minecraft.world.entity.SpawnGroupData finalizeSpawn(net.minecraft.world.level.ServerLevelAccessor level,
			net.minecraft.world.DifficultyInstance difficulty, net.minecraft.world.entity.MobSpawnType type,
			@org.jetbrains.annotations.Nullable net.minecraft.world.entity.SpawnGroupData data) {
		Player spawner = dev.starmine.StarSpawnEggItem.spawner();
		if (type == net.minecraft.world.entity.MobSpawnType.SPAWN_EGG && spawner != null) {
			setOwner(spawner.getUUID());
			this.setPersistenceRequired();
		}
		return super.finalizeSpawn(level, difficulty, type, data);
	}

	@Override
	public void swing(InteractionHand hand, boolean updateSelf) {
		super.swing(hand, updateSelf);
		if (this.level().isClientSide) {
			this.attackStartTick = this.tickCount;
			this.attackVariant = this.random.nextInt(3);
		}
	}

	@Override
	public void aiStep() {
		if (kind == UnitKind.COLOSSUS) this.noPhysics = this.isAlive() && !this.isPassenger();
		if (kind.structure()) { // owner 18.10: a building never turns (one turned before stands square again)
			this.setYRot(0);
			this.yRotO = this.yBodyRot = this.yBodyRotO = this.yHeadRot = this.yHeadRotO = 0;
		}
		super.aiStep();
		if (kind == UnitKind.COLOSSUS && this.isAlive() && !this.isPassenger()) stilts();
		if (!(this.level() instanceof ServerLevel server) || !this.isAlive()) return;
		holdPlace();
		if (kind.platform() && this.tickCount % 20 == 0) dev.starmine.colony.Storage.tickPlatform(this, server);
		if (this.tickCount % 4 == 0) leaveBuildings(server);
		if (lifeLeft > 0 && --lifeLeft == 0) this.kill();
		if (blindAttack != null && this.distanceToSqr(blindAttack) <= weapon().range() * weapon().range()) {
			Vec3 point = blindAttack;
			command(Order.HOLD, null);
			blindAttack = point;
			if (tickCount % Math.max(8, weapon().interval()) == 0 && weapon().damage() > 0) {
				dev.starmine.ability.BurrowDamage.strike(this, BlockPos.containing(point).below());
				this.swing(InteractionHand.MAIN_HAND);
			}
		}
		if (weaponCooldown > 0) weaponCooldown--;
		if (kind.boss()) Heroes.tick(this, server);
		if (kind.dungeonBoss()) dev.starmine.world.DungeonBosses.tick(this, server); // owner 19.10: the brood mother, the hybrid
		if (dev.starmine.faction.Faction.amun(this)) dev.starmine.world.AmunInvasion.unitTick(this, server); // owner 17.10: his curse, his workers
		if (this.tickCount % 40 == 11 && hiveId != null) adoptBase(server);
		if (this.tickCount % 20 == 1) { // its side's colour (it changes with an owner, Amun's hold, a freeing)
			int team = dev.starmine.faction.TeamColors.of(this);
			if (team != this.entityData.get(TEAM)) this.entityData.set(TEAM, team);
		}
		if (kind == UnitKind.HATCHERY) HiveLogic.tick(this, server);
		else if (kind.main() && hiveId == null) dev.starmine.colony.Founding.found(this, server);
		else if (kind.main() && constructTicks == 0) { // a wild command center or nexus: its base's AI
			Hive base = HiveSavedData.get(server.getServer()).get(hiveId);
			if (base != null && base.owner == null) dev.starmine.ai.AiCommander.tick(this, server, base);
		}
		if (kind.race == UnitKind.Race.TERRAN && constructTicks > 0 && this.tickCount % 10 == 0) builderNear = builderNear(server);
		if (constructTicks > 0 && (kind.race != UnitKind.Race.TERRAN || builderNear)) {
			float initial = Math.max(1, getMaxHealth() * 0.1f);
			heal((getMaxHealth() - initial) / Math.max(1, constructTotal));
			if (--constructTicks == 0) finishConstruction(server);
		}
		if ((kind.defender() || mode() == dev.starmine.ability.Modes.PLANETARY) && constructTicks == 0) crawlerAttack(server);
		if ((kind == UnitKind.NYDUS_NETWORK || kind == UnitKind.NYDUS_CANAL) && constructTicks == 0 && this.tickCount % 10 == 0)
			dev.starmine.world.Nydus.tick(this, server);
		if (this.tickCount % 20 == 3) powered = kind.race.rules().powered(this);
		if ((kind.building() || kind.main()) && constructTicks == 0 && powered && !lifted() && !dev.starmine.ability.FieldAbilities.contaminated(this)) {
			produce(server);
			researchTick(server);
		}
		if (kind.shields > 0) shieldTick(server);
		if (zone != null && this.tickCount % 10 == 0) // the zone on the ground, seen by all
			dev.starmine.ability.Abilities.ring(server, zone.add(0, 0.15, 0), ZONE_RADIUS, net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK);
		dev.starmine.ability.Abilities.tick(this, server);
		if (kind == UnitKind.MEDIVAC && this.tickCount % 10 == 0) heal(server);
		if (kind.structure() && this.tickCount % 5 == 0) syncProgress();
		if (this.tickCount % 3 == 0 && badlyDamaged()) damagedEffects(server);
		if (this.tickCount % 100 == 7 && hiveId != null && (!kind.structure() || kind.shields > 0 || weapon().damage() > 0)) applyUpgrades(server);
		if (this.tickCount % 20 == 13 && weapon().damage() > 0 && !kind.worker() && dev.starmine.creep.CreepSources.tumors(server).size() > 0)
			dev.starmine.creep.CreepTumors.attackNear(this, server); // owner 17.10: foes' tumors it sees, it shoots
		if (kind == UnitKind.EGG && nest != null && cocoonTicks > DORMANT / 2 && this.tickCount % 20 == 0) wakeNestEgg(server);
		if (kind == UnitKind.EGG && cocoonKind != null && --cocoonTicks <= 0) hatchCocoon(server);
		if (kind.hoverHeight() > 0 && flying() && !isPassenger() && (this.getTarget() == null || kind.attack == UnitKind.Attack.NONE))
			keepAloft();
		if (hiveId == null && this.tickCount == 5 && !kind.structure()) adoptByNearestHive(server);
		if (kind.worker() && order == Order.NONE) defendOnly();
		if (kind.structure() && mode() != dev.starmine.ability.Modes.LOWERED) pushOffTop();
		if (kind.race == UnitKind.Race.ZERG && this.tickCount % 10 == 0) creepEffects(server); // regeneration, creep speed: zerg only (SC2)
		if (this.tickCount % 20 == 0) {
			trackReachability();
			overseerNear = !this.level().getEntitiesOfClass(StarUnit.class, this.getBoundingBox().inflate(UnitKind.OVERSEER.sight()), mob -> mob.kind == UnitKind.OVERSEER).isEmpty();
			if (kind == UnitKind.OVERSEER) revealInvisible();
		}
	}

	/** Flying kinds stay above their hover height over the ground below. */
	private void keepAloft() {
		if (dev.starmine.world.VoidRift.orderedPortal(this) != null) return;
		double hover = this.getTags().contains("starmine_form") ? Math.min(kind.hoverHeight(), 4) : kind.hoverHeight(); // owner 19.10: Karax's form flies low
		double floor = ZergGoals.cruise(this, this.getX(), this.getZ(), above(hover)) - 1;
		if (this.getY() < floor) {
			Vec3 motion = this.getDeltaMovement();
			this.setDeltaMovement(motion.x, Math.max(motion.y, 0.12), motion.z);
		}
	}

	/** Ground zerg note how long their target has been out of reach (for the overlord's ferry). */
	private void trackReachability() {
		if (flying() || this.getTarget() == null || this.isPassenger()) {
			unreachable = 0;
			return;
		}
		Path path = this.getNavigation().getPath();
		// out of reach: farther than its weapon from edge to edge (a big ultralisk striking stands far from its target's middle)
		double gap = this.distanceTo(this.getTarget()) - (this.getBbWidth() + this.getTarget().getBbWidth()) / 2;
		boolean far = gap > Math.max(3, weapon().range());
		unreachable = far && (path == null || !path.canReach() || this.getNavigation().isDone()) ? unreachable + 20 : 0;
	}

	/**
	 * Drones fight only back: the attacker stays their target while it is within 6 blocks and for at
	 * most 10 s, then they go back to work.
	 */
	private void defendOnly() {
		if (fleeing()) {
			this.setTarget(null);
			return;
		}
		LivingEntity target = this.getTarget();
		if (target == null) {
			defendTicks = 0;
			return;
		}
		if (++defendTicks > 200 || !target.isAlive() || this.distanceToSqr(target) > 36) {
			this.setTarget(null);
			defendTicks = 0;
		}
	}

	/**
	 * A unit that appears without a base joins one: a player's unit the base of its player (wherever it
	 * is in this dimension); a wild one the nearest wild base of its race within 64 blocks (a zerg only
	 * on creep). Drones work for it, warriors guard it.
	 */
	private void adoptByNearestHive(ServerLevel server) {
		String dimension = server.dimension().location().toString();
		HiveSavedData data = HiveSavedData.get(server.getServer());
		UUID player = owner().orElse(null);
		if (player != null) {
			Hive base = data.baseOf(player, dimension);
			if (base != null) {
				joinHive(base);
				data.setDirty();
			}
			return;
		}
		if (kind.race == UnitKind.Race.ZERG && !CreepTouch.onCreep(this)) return;
		Hive nearest = null;
		double best = 64 * 64;
		for (Hive hive : data.all()) {
			if (!hive.dimension.equals(dimension) || hive.owner != null || hive.race != kind.race) continue;
			double distance = hive.hatchery.distToCenterSqr(this.position());
			if (distance < best) {
				best = distance;
				nearest = hive;
			}
		}
		if (nearest == null) return;
		joinHive(nearest);
		data.setDirty();
	}

	/** Whoever hurts a hive's zerg (or its hatchery) has its warriors on them. */
	@Override
	public boolean hurt(DamageSource source, float amount) {
		if (this.getTags().contains("starmine_form") && this.level() instanceof ServerLevel here) { // owner 19.10: a guardian's great form
			for (net.minecraft.world.entity.Entity rider : this.getPassengers())
				if (rider instanceof StarUnit hero && hero.kind.guardian()) return hero.hurt(source, amount);
		}
		if (this.getVehicle() instanceof StarUnit transport && dev.starmine.ability.Transport.capacity(transport.kind) > 0
			&& !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) return false; // cargo is out of harm's way (SC2)
		if (!this.level().isClientSide) amount = dev.starmine.ability.Passives.onHurt(this, source, amount);
		amount = dev.starmine.ability.FieldAbilities.shrouded(this, source, amount); // owner 19.10: under a microbial shroud
		if (kind == UnitKind.CHANGELING && amount > 0 && !this.isInvulnerableTo(source)) amount = this.getMaxHealth() * 100; // owner: any harm kills it
		// SC2 has no moment of invulnerability after a hit: every shot of a StarMine weapon or spell counts (a vanilla
		// mob's or player's own blows keep vanilla's half second)
		boolean weapon = source.getEntity() instanceof StarUnit || source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
			&& dev.starmine.possess.Possession.piloting(player);
		if (weapon) this.invulnerableTime = 0;
		amount = absorbByShield(source, amount);
		if (weapon && amount > 0) amount = armored(source, amount);
		boolean hurt;
		if (amount > 0) {
			steady = weapon;
			try {
				hurt = super.hurt(source, amount);
			} finally {
				steady = false;
			}
		}
		else { // all taken by the shields: still provoked
			hurt = !this.isInvulnerableTo(source);
			if (hurt && source.getEntity() instanceof LivingEntity attacker) this.setLastHurtByMob(attacker);
		}
		if (hurt && hiveId != null && this.level() instanceof ServerLevel server && source.getEntity() instanceof LivingEntity attacker
			&& !dev.starmine.faction.Faction.allied(this, attacker)) {
			HiveLogic.alarm(server, hiveId, this.position(), attacker);
			if (kind.guardian()) dev.starmine.ability.GuardianFights.threatened(this, server, attacker); // owner 19.10: its army comes
			if (kind == UnitKind.NYDUS_NETWORK || kind == UnitKind.NYDUS_CANAL) dev.starmine.world.Nydus.underAttack(server, this, attacker);
			// owner: a drone attacked by someone armed runs to the hatchery or to the hive's warriors
			if (kind == UnitKind.DRONE && armed(attacker)) {
				fleeUntil = server.getGameTime() + 200;
				this.setTarget(null);
			}
		}
		return hurt;
	}

	/** A drone's order to mutate into a building at a site (see {@link HiveLogic#mutate}). */
	public void orderBuild(UnitKind building, BlockPos site) {
		this.buildKind = building;
		this.buildSite = site.immutable();
		syncPlan();
	}

	public UnitKind buildKind() {
		return buildKind;
	}

	public BlockPos buildSite() {
		return buildSite;
	}

	public void cancelBuild() {
		buildKind = null;
		buildSite = null;
		syncPlan();
	}

	/** A building this worker is going to put down, and where (seen by clients: the site's ghost). */
	public record Plan(UnitKind kind, BlockPos site) {
	}

	/** The building on its way: ordered, not yet put down (an SCV building it is not on its way any more). */
	private void syncPlan() {
		this.entityData.set(BUILD_PLAN, buildKind != null && buildSite != null && constructing == null
			? buildKind.name() + " " + buildSite.getX() + " " + buildSite.getY() + " " + buildSite.getZ() : "");
	}

	public @org.jetbrains.annotations.Nullable Plan plannedBuild() {
		String plan = this.entityData.get(BUILD_PLAN);
		if (plan.isEmpty()) return null;
		String[] part = plan.split(" ");
		try {
			return new Plan(UnitKind.valueOf(part[0]), new BlockPos(Integer.parseInt(part[1]), Integer.parseInt(part[2]), Integer.parseInt(part[3])));
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** A new building grows for its SC2 build time before it counts for the hive. */
	public void startConstruction(int ticks) {
		this.constructTicks = Math.max(1, ticks);
		this.constructTotal = this.constructTicks;
		this.setHealth(Math.max(1, this.getMaxHealth() * 0.1f));
		syncProgress();
	}

	public boolean underConstruction() {
		return constructTicks > 0;
	}

	/** A terran building grows only while an SCV of its base builds it (within 8 blocks). */
	/**
	 * A building stays where it stands, on the grid of {@link UnitKind#CELL} blocks (owner 15.10): it is put
	 * back every tick, so water, explosions, pushes and blows cannot move it. A terran building in the air
	 * (lift-off) flies freely and takes its place on the grid again where it lands.
	 */
	private void holdPlace() {
		if (kind.footprint() == 0) return;
		if (dev.starmine.ability.LiftOff.airborne(this) || dev.starmine.ability.FieldAbilities.uprooted(this)) { // a crawler on the move too
			anchor = null;
			return;
		}
		if (anchor == null) anchor = kind.gas() ? this.position() : kind.gridCentre(this.position()); // a gas building stands on its outlet
		if (this.position().distanceToSqr(anchor) > 1e-6) this.setPos(anchor.x, anchor.y, anchor.z);
		this.setDeltaMovement(Vec3.ZERO);
	}

	/**
	 * A ground unit caught inside a building (a building put down or snapped onto its grid where it stood)
	 * steps out the shortest way: a building is solid to it, and inside one it could only move one way —
	 * a thor whose path started into the building stood there for the rest of the game.
	 */
	private void leaveBuildings(ServerLevel server) {
		if (kind.structure() || this.isNoGravity() || this.isPassenger()) return;
		AABB box = this.getBoundingBox();
		for (StarUnit building : server.getEntitiesOfClass(StarUnit.class, box.deflate(0.05),
				mob -> mob != this && mob.kind.structure() && mob.isAlive() && !dev.starmine.ability.LiftOff.airborne(mob))) {
			AABB wall = building.getBoundingBox();
			if (wall.minY >= box.maxY - 0.1 || wall.maxY <= box.minY + 0.1) continue; // over or under it
			double east = wall.maxX - box.minX, west = box.maxX - wall.minX, south = wall.maxZ - box.minZ, north = box.maxZ - wall.minZ;
			double least = Math.min(Math.min(east, west), Math.min(south, north));
			Vec3 out = least == east ? new Vec3(east + 0.05, 0, 0) : least == west ? new Vec3(-west - 0.05, 0, 0)
				: least == south ? new Vec3(0, 0, south + 0.05) : new Vec3(0, 0, -north - 0.05);
			// a step it can make (no wall of blocks there), not a jump across the building
			if (least > Math.max(2, this.getBbWidth()) || !server.noBlockCollision(this, box.move(out))) continue;
			this.setPos(this.getX() + out.x, this.getY(), this.getZ() + out.z);
			this.getNavigation().stop();
			return;
		}
	}

	@Override
	public boolean isPushedByFluid() {
		return kind.footprint() == 0 && super.isPushedByFluid();
	}

	private boolean builderNear(ServerLevel server) {
		return !server.getEntitiesOfClass(StarUnit.class, this.getBoundingBox().inflate(8),
			mob -> mob.kind == UnitKind.SCV && mob.isAlive() && this.getUUID().equals(mob.constructing)).isEmpty();
	}

	public void setConstructing(UUID building) {
		this.constructing = building;
		syncPlan();
	}

	public UUID constructing() {
		return constructing;
	}

	private void finishConstruction(ServerLevel server) {
		syncProgress();
		Hive hive = HiveSavedData.get(server.getServer()).get(hiveId);
		if (hive != null) {
			hive.born(kind, 1);
			hive.join(kind);
			HiveSavedData.get(server.getServer()).setDirty();
		}
		if (kind == UnitKind.NYDUS_CANAL) dev.starmine.world.Nydus.connect(server, this); // a drone built it: the corridor from the network
	}

	/**
	 * Crawlers defend the hive: a spine crawler impales the nearest ground foe within range (SC2 25
	 * damage every 1.32 s), a spore crawler shoots the nearest flying one (SC2 15 every 0.61 s).
	 */
	private void crawlerAttack(ServerLevel server) {
		if (kind == UnitKind.BUNKER) return; // a bunker fires only with its garrison (dev.starmine.ability.TerranAbilities#bunkerTick)
		dev.starmine.ability.Modes.Weapon weapon = weapon();
		if (weapon.interval() <= 0 || this.tickCount % weapon.interval() != 0) return;
		LivingEntity foe = null;
		double best = weapon.range() * weapon.range();
		boolean planetary = mode() == dev.starmine.ability.Modes.PLANETARY;
		for (LivingEntity other : server.getEntitiesOfClass(LivingEntity.class, this.getBoundingBox().inflate(weapon.range()),
			entity -> dev.starmine.faction.Faction.hostile(this, entity) && !(entity instanceof StarUnit unit && !dev.starmine.ability.Cloak.seen(unit, this)))) {
			boolean flying = other instanceof net.minecraft.world.entity.FlyingMob || other.isFallFlying()
				|| other instanceof net.minecraft.world.entity.monster.Phantom || other instanceof net.minecraft.world.entity.monster.Blaze
				|| other instanceof net.minecraft.world.entity.monster.Vex || !other.onGround() && other.getY() > this.getY() + 3
				|| other instanceof StarUnit unit && unit.flying();
			boolean tall = other instanceof StarUnit unit && unit.kind.hitByAntiAir() && !unit.flying(); // a colossus: both
			boolean air = kind.antiAir() && !planetary, ground = kind.antiGround() || planetary;
			if (flying ? !air : !(ground || tall && air)) continue;
			double d = other.distanceToSqr(this);
			if (d < best) {
				best = d;
				foe = other;
			}
		}
		if (foe == null) return;
		if (kind == UnitKind.SPINE_CRAWLER) {
			Vec3 point = foe.position().add(0, foe.getBbHeight() / 2, 0).subtract(position());
			entityData.set(STRIKE_POINT, new org.joml.Vector3f((float) point.x, (float) point.y, (float) point.z));
		}
		this.swing(InteractionHand.MAIN_HAND);
		this.getLookControl().setLookAt(foe);
		if (!(planetary && dev.starmine.ability.Modes.siegeShot(this, foe)))
			foe.hurt(attackSource(), (float) (weapon.damage() + attackBonus() + dev.starmine.ability.Passives.bonus(this, foe)));
		dev.starmine.ability.Passives.onHit(this, foe);
		if (kind.race != UnitKind.Race.ZERG) tracer(server, foe);
		shotSounds();
	}

	/**
	 * Its base's upgrades (SC2, owner 17.10): each level of its weapons upgrade adds {@link UnitKind#upgradeDamage}
	 * to an attack, of its armor upgrade 1 armor ({@link #armored}), of the protoss shields 1 shield armor and
	 * a tenth more shields ({@link #maxShield}). Which upgrades are its: {@link dev.starmine.race.Research#weaponsOf}.
	 */
	private void applyUpgrades(ServerLevel server) {
		Hive hive = HiveSavedData.get(server.getServer()).get(hiveId);
		if (hive == null) return;
		int weapons = dev.starmine.race.Research.level(hive, dev.starmine.race.Research.weaponsOf(kind));
		int armor = dev.starmine.race.Research.level(hive, dev.starmine.race.Research.armorOf(kind));
		int shields = kind.shields > 0 ? dev.starmine.race.Research.level(hive, dev.starmine.race.Research.SHIELDS) : 0;
		this.entityData.set(UPGRADES, Math.min(15, weapons) | Math.min(15, armor) << 4 | Math.min(15, shields) << 8);
		modifier(Attributes.ATTACK_DAMAGE, UPGRADE_ATTACK, weapons * kind.upgradeDamage() * UnitKind.SCALE);
		// unit upgrades (dev.starmine.race.Research): speed and health
		double speed = dev.starmine.race.Research.speedFactor(hive, kind) - 1;
		modifier(Attributes.MOVEMENT_SPEED, UPGRADE_SPEED, speed, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
		if (weapon().damage() > 0) refreshWeapon(); // range and pace upgrades
		double health = dev.starmine.race.Research.extraHealth(hive, kind) * UnitKind.SCALE;
		AttributeInstance max = this.getAttribute(Attributes.MAX_HEALTH);
		if (max != null && (max.getModifier(UPGRADE_HEALTH) == null ? 0 : max.getModifier(UPGRADE_HEALTH).amount()) != health) {
			modifier(Attributes.MAX_HEALTH, UPGRADE_HEALTH, health);
			if (health > 0) this.heal((float) health);
		}
	}

	private void modifier(net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, ResourceLocation id, double amount) {
		AttributeInstance instance = this.getAttribute(attribute);
		if (instance == null) return;
		AttributeModifier current = instance.getModifier(id);
		if (current != null && current.amount() == amount) return;
		instance.removeModifier(id);
		if (amount != 0) instance.addTransientModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_VALUE));
	}

	/** Extra damage from the hive's attack upgrades (missiles and crawlers use it too). */
	public double attackBonus() {
		AttributeInstance damage = this.getAttribute(Attributes.ATTACK_DAMAGE);
		AttributeModifier bonus = damage == null ? null : damage.getModifier(UPGRADE_ATTACK);
		return bonus == null ? 0 : bonus.amount();
	}

	private static final ResourceLocation UPGRADE_ATTACK = StarMine.id("upgrade_attack"),
		UPGRADE_SPEED = StarMine.id("upgrade_speed"), UPGRADE_HEALTH = StarMine.id("upgrade_health");

	/** A weapon in hand: swords, axes, bows, crossbows, tridents, maces. */
	private static boolean armed(LivingEntity attacker) {
		var stack = attacker.getMainHandItem();
		var item = stack.getItem();
		return stack.is(net.minecraft.tags.ItemTags.SWORDS) || stack.is(net.minecraft.tags.ItemTags.AXES)
			|| item instanceof net.minecraft.world.item.BowItem || item instanceof net.minecraft.world.item.CrossbowItem
			|| item instanceof net.minecraft.world.item.TridentItem || item instanceof net.minecraft.world.item.MaceItem;
	}

	public boolean fleeing() {
		return this.level().getGameTime() < fleeUntil;
	}

	/** Zerg buildings shove off whatever stands on top of them (owner: nobody camps on a hatchery). */
	private void pushOffTop() {
		AABB box = this.getBoundingBox();
		AABB top = new AABB(box.minX, box.maxY - 0.5, box.minZ, box.maxX, box.maxY + 2, box.maxZ);
		for (Entity other : this.level().getEntities(this, top, entity -> !(entity instanceof StarUnit zerg && zerg.kind.structure()))) {
			if (other.getY() < box.maxY - 0.6 || other.isPassenger()) continue;
			double dx = other.getX() - this.getX(), dz = other.getZ() - this.getZ();
			double length = Math.sqrt(dx * dx + dz * dz);
			if (length < 0.01) {
				double angle = this.random.nextDouble() * Math.PI * 2;
				dx = Math.cos(angle);
				dz = Math.sin(angle);
				length = 1;
			}
			other.setDeltaMovement(dx / length * 0.7, 0.35, dz / length * 0.7);
			other.hurtMarked = true;
		}
	}

	/** Whether the drone may take this block now: not a second kind of valuable resource. */
	public boolean canTake(BlockState state) {
		if (dev.starmine.mineral.Minerals.isCrystal(state)) return cargoBlocks == 0; // a trip of minerals alone
		if (!HiveResources.yieldOf(state).any()) return true;
		return valuable.isEmpty() || valuable.is(state.getBlock().asItem()) && valuable.getCount() < LOAD && !precious;
	}

	/** Time to go home: a full slot (8) or a precious ore. */
	public boolean loadFull() {
		return valuable.getCount() >= LOAD || precious && !valuable.isEmpty();
	}

	/** A drone takes a block it broke: a resource into its valuable slot, stone into its fill slots. */
	public void collect(BlockState state) {
		HiveResources.Yield yield = HiveResources.yieldOf(state);
		Item item = state.getBlock().asItem();
		if (yield.any()) {
			if (!canTake(state)) return; // a second kind would be lost; drones avoid breaking those
			cargoMinerals += yield.minerals();
			cargoVespene += yield.vespene();
			cargoDiamonds += yield.diamonds();
			cargoBlocks++;
			if (yield.label() != null) cargoLabel = yield.label();
			precious |= yield.precious();
			if (item != Items.AIR) {
				if (valuable.isEmpty()) valuable = new ItemStack(item);
				else valuable.grow(1);
			}
		} else if (state.is(FILL_TAG)) {
			Item stone = state.is(Blocks.STONE) ? Items.COBBLESTONE : state.is(Blocks.DEEPSLATE) ? Items.COBBLED_DEEPSLATE : item;
			addFill(stone);
		}
		updateCarried();
	}

	private void addFill(Item item) {
		if (!(item instanceof BlockItem)) return;
		for (ItemStack slot : fillSlots)
			if (slot.is(item) && slot.getCount() < slot.getMaxStackSize()) {
				slot.grow(1);
				return;
			}
		if (fillSlots.size() < FILL_SLOTS) fillSlots.add(new ItemStack(item));
	}

	public int fillCount() {
		int count = 0;
		for (ItemStack slot : fillSlots) count += slot.getCount();
		return count;
	}

	/** Puts a building block from the fill slots (bridge, step); false when there is none or the place is taken. */
	public boolean placeFill(BlockPos pos) {
		if (dev.starmine.world.Skirmish.terrainLocked(this.level())) return false; // owner 22.10: no block is put in a skirmish
		BlockState there = this.level().getBlockState(pos);
		if (!there.canBeReplaced()) return false;
		for (ItemStack slot : fillSlots) {
			if (slot.isEmpty() || !(slot.getItem() instanceof BlockItem blockItem)) continue;
			BlockState placed = blockItem.getBlock().defaultBlockState();
			this.level().setBlockAndUpdate(pos, placed);
			this.playSound(placed.getSoundType().getPlaceSound(), 1.0f, 1.0f);
			this.swing(InteractionHand.MAIN_HAND);
			slot.shrink(1);
			fillSlots.removeIf(ItemStack::isEmpty);
			return true;
		}
		return false;
	}

	/** Minerals taken from a crystal (owner 16.10): a trip's load, carried home as a shard. */
	public void takeMinerals(int amount) {
		cargoMinerals += amount;
		cargoBlocks++;
		cargoLabel = "minerals";
		precious = true;
		valuable = new ItemStack(dev.starmine.mineral.Minerals.SHARD);
		updateCarried();
	}

	/** Vespene drawn from an outlet (owner 14.10): a full load, carried home in a vespene bucket. */
	public void takeVespene(int amount) {
		cargoVespene += amount;
		cargoBlocks++;
		cargoLabel = "vespene";
		precious = true;
		valuable = new ItemStack(dev.starmine.vespene.Vespene.BUCKET);
		updateCarried();
	}

	/** The load is delivered; the fill stays with the drone. */
	public void clearCargo() {
		cargoMinerals = cargoVespene = cargoDiamonds = cargoBlocks = 0;
		cargoLabel = null;
		valuable = ItemStack.EMPTY;
		precious = false;
		updateCarried();
	}

	/** What the client draws in front of the drone: only its valuable slot (earth and stone are not shown). */
	private void updateCarried() {
		this.entityData.set(CARRIED, valuable.copy());
	}

	public ItemStack carried() {
		return this.entityData.get(CARRIED);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(CARRIED, ItemStack.EMPTY);
		builder.define(OWNER, java.util.Optional.empty());
		builder.define(CONTROLLER, java.util.Optional.empty());
		builder.define(TEAM, 0); // its race's until the server tells (kind is not set yet here)
		builder.define(SHIELD, 0f);
		builder.define(STRIKE_POINT, new org.joml.Vector3f());
		builder.define(UPGRADES, 0);
		builder.define(PROGRESS, 0);
		builder.define(QUEUE_IDS, "");
		builder.define(AUTOCAST, "");
		builder.define(ENERGY, 0f);
		builder.define(CLOAKED, false);
		builder.define(REVEALED, false);
		builder.define(MODE, 0);
		builder.define(CHANGE, 0);
		builder.define(CHANGE_TICKS, 0);
		builder.define(DISGUISE, "");
		builder.define(BUILD_PLAN, "");
		builder.define(WORK_SHOWN, "");
	}

	@Override
	protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
		if (dev.starmine.faction.Faction.amun(this) || dev.starmine.world.VoidGate.inVoid(this)) { // owner 17.10: Amun's leave nothing, nor does anyone in the Void
			valuable = ItemStack.EMPTY;
			fillSlots.clear();
			return;
		}
		super.dropCustomDeathLoot(level, source, recentlyHit);
		if (!valuable.isEmpty()) this.spawnAtLocation(valuable.copy());
		for (ItemStack slot : fillSlots) this.spawnAtLocation(slot.copy());
		for (ItemStack loot : dev.starmine.suit.Suits.drops(this, source, recentlyHit)) this.spawnAtLocation(loot);
		if (kind.boss()) for (ItemStack loot : Heroes.reward(this)) this.spawnAtLocation(loot);
		if (kind.dungeonBoss()) for (ItemStack loot : dev.starmine.world.DungeonBosses.loot(this)) this.spawnAtLocation(loot);
		valuable = ItemStack.EMPTY;
		fillSlots.clear();
	}

	/** Owner 17.10: Amun's troops, and anyone dying in the Void, leave no experience. */
	@Override
	protected int getBaseExperienceReward() {
		if (dev.starmine.faction.Faction.amun(this) || dev.starmine.world.VoidGate.inVoid(this)) return 0;
		return super.getBaseExperienceReward();
	}

	/**
	 * Creep (owner): on creep zerg run 30 % faster (SC2) and regenerate 4x; all zerg regenerate slowly
	 * (SC2 0.27 hit points a second, scaled); a player standing on creep draws the zerg on creep nearby.
	 */
	private void creepEffects(ServerLevel server) {
		boolean onCreep = CreepTouch.onCreep(this);
		AttributeInstance speed = this.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) {
			boolean has = speed.hasModifier(CREEP_SPEED);
			if (onCreep && !has) speed.addTransientModifier(new AttributeModifier(CREEP_SPEED, 0.3, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
			else if (!onCreep && has) speed.removeModifier(CREEP_SPEED);
		}
		if (this.tickCount % 20 == 0 && this.getHealth() < this.getMaxHealth())
			this.heal((float) (0.27 * UnitKind.SCALE * (onCreep ? 4 : 1)));
		if (onCreep && this.getTarget() == null && kind.aggressive && kind.attack != UnitKind.Attack.NONE && this.tickCount % 20 == 0) {
			Player intruder = server.getNearestPlayer(this.getX(), this.getY(), this.getZ(), 24,
				entity -> entity instanceof Player player && !player.isCreative() && !player.isSpectator() && CreepTouch.onCreep(player));
			if (intruder != null) this.setTarget(intruder);
		}
	}

	private static final ResourceLocation CREEP_SPEED = StarMine.id("creep_speed");

	/** A building at this share of its health or less shows it (owner 15.10). */
	public static final float BADLY_DAMAGED = 0.25f;

	/** A finished building (not a cocoon) at a quarter of its health or less. */
	public boolean badlyDamaged() {
		return kind.structure() && kind != UnitKind.EGG && constructTicks == 0 && this.isAlive() && this.getHealth() <= this.getMaxHealth() * BADLY_DAMAGED;
	}

	/** Its race's sign of damage ({@link dev.starmine.race.RaceRules#damagedParticles}) at points over its body, more on a bigger one. */
	private void damagedEffects(ServerLevel server) {
		double half = this.getBbWidth() / 2, height = this.getBbHeight();
		int spots = 1 + (int) half;
		for (net.minecraft.core.particles.ParticleOptions particle : kind.race.rules().damagedParticles())
			for (int i = 0; i < spots; i++) {
				double x = this.getX() + (this.random.nextDouble() * 2 - 1) * half * 0.8;
				double z = this.getZ() + (this.random.nextDouble() * 2 - 1) * half * 0.8;
				double y = this.getY() + height * (0.4 + this.random.nextDouble() * 0.6);
				server.sendParticles(particle, x, y, z, 2, 0.15, 0.1, 0.15, 0.01);
			}
	}

	public int unreachableTicks() {
		return unreachable;
	}

	public void clearUnreachable() {
		unreachable = 0;
	}

	/** Overseer: invisible creatures and players around it glow for everyone (SC2 detection). */
	private void revealInvisible() {
		for (LivingEntity other : this.level().getEntitiesOfClass(LivingEntity.class, this.getBoundingBox().inflate(kind.sight()), LivingEntity::isInvisible))
			other.addEffect(new MobEffectInstance(MobEffects.GLOWING, 40, 0, false, false));
	}

	/** Zerg near an overseer see invisible players and creatures. */
	public boolean seesInvisible() {
		return overseerNear || kind == UnitKind.OVERSEER;
	}

	/** Joins a raid: go to the target until the given game time. */
	public void joinRaid(BlockPos target, long until) {
		this.raidTarget = target.immutable();
		this.raidUntil = until;
	}

	/** The raid's destination while the raid lasts, else null. */
	public BlockPos raidTarget() {
		if (raidTarget != null && this.level().getGameTime() > raidUntil) raidTarget = null;
		return raidTarget;
	}

	public void endRaid() {
		raidTarget = null;
	}

	public UUID hiveId() {
		return hiveId;
	}

	/**
	 * Owner 17.10: a wild base that went over to a player (a freed hero's) — its units become the player's: one whose
	 * base was merged into the player's follows it there, one of a base the player now owns takes the player as owner.
	 */
	private void adoptBase(ServerLevel server) {
		HiveSavedData data = HiveSavedData.get(server.getServer());
		Hive hive = data.get(hiveId);
		if (hive == null) {
			UUID to = data.merged.get(hiveId);
			if (to == null || (hive = data.get(to)) == null) return;
			hiveId = to;
		}
		if (hive.owner != null && owner().isEmpty()) {
			setOwner(hive.owner);
			setTarget(null);
			command(Order.NONE, null);
		}
	}

	public BlockPos home() {
		return home;
	}

	/** Where it returns when idle (a larva: the hatchery that made it). */
	public void setHome(BlockPos home) {
		this.home = home == null ? null : home.immutable();
	}

	/** Makes this zerg a nest dweller of the hall at `hall` (call before {@link #joinHive}). */
	public void makeNest(BlockPos hall) {
		this.nest = hall.immutable();
		this.home = this.nest;
		this.setPersistenceRequired();
	}

	public BlockPos nest() {
		return nest;
	}

	/**
	 * A sleeping nest egg wakes when a player (not creative or spectator) or another creature that is
	 * not zerg comes within 10 blocks — a chance each second — and, very rarely, by itself while few
	 * zerg are about (about once an hour). It then hatches in 3–9 seconds.
	 */
	private void wakeNestEgg(ServerLevel server) {
		boolean disturbed = !server.getEntitiesOfClass(LivingEntity.class, this.getBoundingBox().inflate(10),
			other -> other.isAlive() && !(other instanceof StarUnit) && !(other instanceof net.minecraft.world.entity.decoration.ArmorStand)
				&& !(other instanceof Player player && (player.isCreative() || player.isSpectator()))).isEmpty();
		boolean wake = disturbed ? this.random.nextInt(15) == 0
			: this.random.nextInt(3600) == 0 && server.getEntitiesOfClass(StarUnit.class, this.getBoundingBox().inflate(16), mob -> !mob.kind.structure()).size() < 6;
		if (wake) cocoonTicks = 60 + this.random.nextInt(120);
	}

	/** Joins a hive: counted in its members and supply, kept in the world, returns home when idle. */
	public void joinHive(Hive hive) {
		this.hiveId = hive.id;
		this.entityData.set(OWNER, java.util.Optional.ofNullable(hive.owner));
		if (nest != null) return;
		this.home = hive.hatchery;
		if (!kind.structure() || kind == UnitKind.EGG) hive.join(kind);
		this.setPersistenceRequired();
	}

	public void becomeCocoon(UnitKind kind, int count, int ticks) {
		this.cocoonKind = kind;
		this.cocoonCount = count;
		this.cocoonTicks = ticks;
	}

	private void hatchCocoon(ServerLevel server) {
		Hive hive = HiveSavedData.get(server.getServer()).get(hiveId);
		UnitKind born = cocoonKind;
		int count = cocoonCount;
		cocoonKind = null;
		if (nest != null) {
			for (int i = 0; i < count; i++)
				HiveLogic.spawnNest(server, hive, born, Spots.near(server, born, this.position()), nest);
		} else if (hive != null) HiveLogic.hatch(this, server, hive, born, count);
		this.discard();
	}

	@Override
	public void remove(Entity.RemovalReason reason) {
		if (kind.worker() && !this.level().isClientSide) DroneWork.releaseAll(this.getUUID());
		if (kind.boss() && !this.level().isClientSide) Heroes.gone(this);
		if (reason.shouldDestroy() && this.level() instanceof ServerLevel server && hiveId != null && !queue.isEmpty()) {
			Hive hive = HiveSavedData.get(server.getServer()).get(hiveId);
			if (hive != null) for (UnitKind queued : queue) hive.born(queued, 1); // their supply is free again
			queue.clear();
		}
		if (reason.shouldDestroy() && this.level() instanceof ServerLevel server && hiveId != null) {
			HiveSavedData data = HiveSavedData.get(server.getServer());
			Hive hive = data.get(hiveId);
			// owner 17.10: a race base's main building fallen — a portal to its sky ship opens where it stood (after its ruin below)
			Hive fallen = reason == Entity.RemovalReason.KILLED && kind.main() && owner().isEmpty() && hive != null && hive.shipPad != null ? hive : null;
			if (kind == UnitKind.HATCHERY) {
				if (hive != null) {
					if (constructTicks > 0) hive.born(kind, 1); else hive.leave(kind);
					HiveTerrain.ruin(server, this.blockPosition(), HiveTerrain.HATCHERY_FOUNDATION);
					CreepGrowth.forget(server, this.blockPosition());
					if (hive.hatchery.equals(this.blockPosition()))
						server.getEntitiesOfClass(StarUnit.class, new AABB(hive.hatchery).inflate(256),
							other -> other != this && other.kind == UnitKind.HATCHERY && other.isAlive() && !other.underConstruction() && hiveId.equals(other.hiveId()))
							.stream().findFirst().ifPresent(other -> hive.hatchery = other.blockPosition());
					if (hive.owner == null && hive.count(kind) == 0 && hive.pending(kind) == 0) data.remove(hiveId);
					data.setDirty();
				}
			} else if (hive != null && nest == null && !summoned) {
				if (kind.building()) {
					if (constructTicks > 0) hive.born(kind, 1);
					else hive.leave(kind);
					HiveTerrain.ruin(server, this.blockPosition(), HiveTerrain.foundation(kind));
				} else {
					hive.leave(kind);
				}
				if (kind == UnitKind.SUPPLY_DEPOT) dev.starmine.ability.FieldAbilities.depotGone(this, hive);
				if (kind == UnitKind.EGG && cocoonKind != null) hive.born(cocoonKind, cocoonCount);
				data.setDirty();
			}
			if (fallen != null) dev.starmine.world.RacePortal.open(server, fallen, this.blockPosition());
		}
		super.remove(reason);
	}

	@Override
	public void addAdditionalSaveData(CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		if (kind.lifeTicks() > 0) tag.putInt("StarMineLife", lifeLeft);
		if (hiveId != null) tag.putUUID("StarMineHive", hiveId);
		owner().ifPresent(owner -> tag.putUUID("StarMineOwner", owner));
		if (nest != null) tag.put("StarMineNest", NbtUtils.writeBlockPos(nest));
		if (home != null) tag.put("StarMineHome", NbtUtils.writeBlockPos(home));
		if (raidTarget != null) {
			tag.put("StarMineRaid", NbtUtils.writeBlockPos(raidTarget));
			tag.putLong("StarMineRaidUntil", raidUntil);
		}
		if (cargoBlocks > 0) tag.putIntArray("StarMineCargo", new int[] {cargoMinerals, cargoVespene, cargoDiamonds, cargoBlocks});
		if (!valuable.isEmpty()) tag.put("StarMineValuable", valuable.save(this.registryAccess()));
		if (precious) tag.putBoolean("StarMinePrecious", true);
		if (!fillSlots.isEmpty()) {
			ListTag items = new ListTag();
			for (ItemStack slot : fillSlots) items.add(slot.save(this.registryAccess()));
			tag.put("StarMineFill", items);
		}
		if (constructTicks > 0) tag.putInt("StarMineConstruct", constructTicks);
		if (constructTicks > 0) tag.putInt("StarMineConstructTotal", constructTotal);
		if (constructing != null) tag.putUUID("StarMineConstructing", constructing);
		if (order != Order.NONE && orderPos != null) {
			tag.putString("StarMineOrder", order.name());
			tag.putDouble("StarMineOrderX", orderPos.x);
			tag.putDouble("StarMineOrderY", orderPos.y);
			tag.putDouble("StarMineOrderZ", orderPos.z);
			if (patrolFrom != null) tag.put("StarMinePatrol", NbtUtils.writeBlockPos(BlockPos.containing(patrolFrom)));
		}
		if (!queue.isEmpty()) {
			tag.putString("StarMineQueue", String.join(",", queue.stream().map(Enum::name).toList()));
			tag.putInt("StarMineTrain", trainTicks); tag.putInt("StarMineTrain2", secondTicks);
		}
		if (warpCooldown > 0) tag.putInt("StarMineWarpCooldown", warpCooldown);
		if (altitude != 0) tag.putInt("StarMineAltitude", altitude);
		if (larvaTicks != 0) tag.putInt("StarMineLarvaTicks", larvaTicks);
		if (rally != null) tag.put("StarMineRally", NbtUtils.writeBlockPos(BlockPos.containing(rally)));
		if (research != null) {
			tag.putString("StarMineResearch", research);
			tag.putInt("StarMineResearchTicks", researchTicks);
		}
		if (bankMinerals != 0 || bankVespene != 0) {
			tag.putInt("StarMineBankMinerals", bankMinerals);
			tag.putInt("StarMineBankVespene", bankVespene);
		}
		if (cargoLabel != null) tag.putString("StarMineCargoLabel", cargoLabel);
		if (buildKind != null && buildSite != null) {
			tag.putString("StarMineBuild", buildKind.name());
			tag.put("StarMineBuildSite", NbtUtils.writeBlockPos(buildSite));
		}
		if (cocoonKind != null) {
			tag.putString("StarMineCocoon", cocoonKind.name());
			tag.putInt("StarMineCocoonCount", cocoonCount);
			tag.putInt("StarMineCocoonTicks", cocoonTicks);
		}
		if (maxEnergy() > 0) tag.putFloat("StarMineEnergy", energy());
		if (cloaked()) tag.putBoolean("StarMineCloaked", true);
		if (mode() != 0) tag.putInt("StarMineMode", mode());
		if (!disguise().isEmpty()) tag.putString("StarMineDisguise", disguise());
		if (summoned) tag.putBoolean("StarMineSummoned", true);
		if (summoner != null) tag.putUUID("StarMineSummoner", summoner);
		if (orbitalCharged) tag.putBoolean("StarMineOrbitalCharged", true);
		abilities.save(tag);
	}

	@Override
	public void readAdditionalSaveData(CompoundTag tag) {
		super.readAdditionalSaveData(tag);
		if (tag.contains("StarMineLife")) lifeLeft = Math.max(1, tag.getInt("StarMineLife"));
		if (tag.hasUUID("StarMineHive")) hiveId = tag.getUUID("StarMineHive");
		if (tag.hasUUID("StarMineOwner")) setOwner(tag.getUUID("StarMineOwner"));
		nest = NbtUtils.readBlockPos(tag, "StarMineNest").orElse(null);
		home = NbtUtils.readBlockPos(tag, "StarMineHome").orElse(null);
		raidTarget = NbtUtils.readBlockPos(tag, "StarMineRaid").orElse(null);
		raidUntil = tag.getLong("StarMineRaidUntil");
		int[] cargo = tag.getIntArray("StarMineCargo");
		if (cargo.length == 4) {
			cargoMinerals = cargo[0];
			cargoVespene = cargo[1];
			cargoDiamonds = cargo[2];
			cargoBlocks = cargo[3];
		}
		valuable = tag.contains("StarMineValuable") ? ItemStack.parse(this.registryAccess(), tag.get("StarMineValuable")).orElse(ItemStack.EMPTY) : ItemStack.EMPTY;
		precious = tag.getBoolean("StarMinePrecious");
		fillSlots.clear();
		for (Tag item : tag.getList("StarMineFill", Tag.TAG_COMPOUND))
			ItemStack.parse(this.registryAccess(), item).ifPresent(fillSlots::add);
		if (kind.worker()) updateCarried();
		if (tag.contains("StarMineCocoon")) {
			try {
				cocoonKind = UnitKind.valueOf(tag.getString("StarMineCocoon"));
				cocoonCount = tag.getInt("StarMineCocoonCount");
				cocoonTicks = tag.getInt("StarMineCocoonTicks");
			} catch (IllegalArgumentException ignored) {
				cocoonKind = null;
			}
		}
		if (kind == UnitKind.HATCHERY && hiveId == null) hiveId = this.getUUID();
		if (tag.contains("StarMineEnergy")) setEnergy(tag.getFloat("StarMineEnergy"));
		setCloaked(tag.getBoolean("StarMineCloaked"));
		setMode(tag.getInt("StarMineMode"));
		setDisguise(tag.getString("StarMineDisguise"));
		abilities.load(tag);
		summoned = tag.getBoolean("StarMineSummoned");
		summoner = tag.hasUUID("StarMineSummoner") ? tag.getUUID("StarMineSummoner") : null;
		orbitalCharged = tag.getBoolean("StarMineOrbitalCharged");
		if (mode() != 0) applyModeBody();
		constructTicks = tag.getInt("StarMineConstruct");
		constructTotal = tag.contains("StarMineConstructTotal") ? Math.max(constructTicks, tag.getInt("StarMineConstructTotal"))
			: Math.max(constructTicks, kind.economy().trainTicks());
		constructing = tag.hasUUID("StarMineConstructing") ? tag.getUUID("StarMineConstructing") : null;
		try {
			order = tag.contains("StarMineOrder") ? Order.valueOf(tag.getString("StarMineOrder")) : Order.NONE;
		} catch (IllegalArgumentException ignored) {
			order = Order.NONE;
		}
		orderPos = order == Order.NONE ? null : new Vec3(tag.getDouble("StarMineOrderX"), tag.getDouble("StarMineOrderY"), tag.getDouble("StarMineOrderZ"));
		patrolFrom = NbtUtils.readBlockPos(tag, "StarMinePatrol").map(Vec3::atBottomCenterOf).orElse(null);
		queue.clear();
		for (String name : tag.getString("StarMineQueue").split(","))
			if (!name.isEmpty()) try {
				queue.add(UnitKind.valueOf(name));
			} catch (IllegalArgumentException ignored) {
				// a kind removed from the mod
			}
		trainTicks = tag.getInt("StarMineTrain"); secondTicks = tag.getInt("StarMineTrain2");
		warpCooldown = tag.getInt("StarMineWarpCooldown");
		altitude = tag.getInt("StarMineAltitude");
		larvaTicks = tag.getInt("StarMineLarvaTicks");
		rally = NbtUtils.readBlockPos(tag, "StarMineRally").map(Vec3::atBottomCenterOf).orElse(null);
		research = tag.contains("StarMineResearch") ? tag.getString("StarMineResearch") : null;
		researchTicks = tag.getInt("StarMineResearchTicks");
		bankMinerals = tag.getInt("StarMineBankMinerals");
		bankVespene = tag.getInt("StarMineBankVespene");
		cargoLabel = tag.contains("StarMineCargoLabel") ? tag.getString("StarMineCargoLabel") : null;
		if (tag.contains("StarMineBuild")) {
			try {
				buildKind = UnitKind.valueOf(tag.getString("StarMineBuild"));
				buildSite = NbtUtils.readBlockPos(tag, "StarMineBuildSite").orElse(null);
			} catch (IllegalArgumentException ignored) {
				buildKind = null;
			}
		}
		syncPlan();
	}

	@Override
	public boolean removeWhenFarAway(double distance) {
		return hiveId == null && !kind.structure() && super.removeWhenFarAway(distance);
	}

	/** Its model is wider than its hitbox: the crosshair finds it there too ({@link UnitKind#reachPad}). */
	@Override
	public float getPickRadius() {
		return kind.reachPad();
	}

	@Override
	public boolean isPushable() {
		return !kind.structure() && mode() != dev.starmine.ability.Modes.BURROWED && !inLiquidCreep() && super.isPushable();
	}

	/**
	 * A walker by water is not shoved by the units round it (CLOUD_TASK_2 stage 4, ground units never get wet:
	 * a marauder on a bridge being built was pushed off its edge by the thor behind it and into the river).
	 */
	@Override
	public void push(Entity pusher) {
		if (pusher instanceof StarUnit other && workersPass(other)) return;
		if (!flying() && pusher instanceof StarUnit && waterBeside()) return;
		super.push(pusher);
	}

	private boolean waterBeside() {
		net.minecraft.core.BlockPos feet = this.blockPosition();
		int reach = (int) Math.ceil(this.getBbWidth() / 2 + 1);
		for (net.minecraft.core.BlockPos at : net.minecraft.core.BlockPos.betweenClosed(feet.offset(-reach, -1, -reach), feet.offset(reach, 0, reach)))
			if (!this.level().getFluidState(at).isEmpty()) return true;
		return false;
	}

	/** Flyers pass one another (SC2: air units stack and spread, they do not shove each other off their ways). */
	@Override
	protected void doPush(Entity other) {
		if (other instanceof StarUnit unit && workersPass(unit)) return;
		if (flying() && other instanceof StarUnit unit && unit.flying()) return;
		super.doPush(other);
	}

	/** Game time until which this zerg counts as in liquid creep (it passes through creatures there, owner 16.10). */
	private long liquidCreepUntil;

	public void markInLiquidCreep() {
		liquidCreepUntil = this.level().getGameTime() + 2;
	}

	public boolean inLiquidCreep() {
		return this.level().getGameTime() <= liquidCreepUntil;
	}

	@Override
	protected void pushEntities() {
		if (!inLiquidCreep()) super.pushEntities();
	}

	@Override
	public boolean canBeCollidedWith() {
		if (lowered()) return false;
		return kind.structure() || super.canBeCollidedWith();
	}

	/** Allied workers keep their block collision and aiming body, but pass one another. */
	private boolean workersPass(StarUnit other) {
		return kind.worker() && other.kind.worker() && dev.starmine.faction.Faction.allied(this, other);
	}

	@Override
	public boolean canCollideWith(Entity other) {
		return !(other instanceof StarUnit unit && workersPass(unit)) && super.canCollideWith(other);
	}

	@Override
	public boolean shouldDespawnInPeaceful() {
		return !kind.structure() && hiveId == null;
	}

	/** Units ride a transport as cargo (dev.starmine.ability.Transport); nobody steers it but its own AI and orders. */
	@Override
	public LivingEntity getControllingPassenger() {
		return dev.starmine.ability.Transport.capacity(kind) > 0 ? null : super.getControllingPassenger();
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return dev.starmine.ability.Transport.carries(this, passenger) || dev.starmine.world.HeroicTransport.carries(this, passenger);
	}

	/** Cargo hangs under the transport's body, spread a little around its centre. */
	@Override
	protected void positionRider(Entity passenger, Entity.MoveFunction move) {
		if (passenger instanceof Player) { // a player aboard a heroic transport sits on its back
			move.accept(passenger, this.getX(), this.getY() + this.getBbHeight() * 0.7, this.getZ());
			return;
		}
		int index = this.getPassengers().indexOf(passenger);
		double angle = index * 2.4, spread = index == 0 ? 0 : 0.6;
		double y = kind.structure() ? this.getY() + 0.1 : this.getY() - passenger.getBbHeight() + 0.6; // in a bunker, behind its walls
		move.accept(passenger, this.getX() + Math.cos(angle) * spread, y, this.getZ() + Math.sin(angle) * spread);
	}


	@Override
	public boolean doHurtTarget(Entity target) {
		if (kind.attack == UnitKind.Attack.EXPLODE) {
			explode();
			return true;
		}
		if (dev.starmine.ability.Status.has(this, dev.starmine.ability.Status.HALLUCINATION)) { // an illusion strikes for show
			this.swing(InteractionHand.MAIN_HAND);
			return false;
		}
		double bonus = target instanceof LivingEntity living ? dev.starmine.ability.Passives.bonus(this, living) : 0;
		modifier(Attributes.ATTACK_DAMAGE, BONUS_DAMAGE, bonus);
		boolean hurt = super.doHurtTarget(target);
		modifier(Attributes.ATTACK_DAMAGE, BONUS_DAMAGE, 0);
		if (hurt && target instanceof LivingEntity living) dev.starmine.ability.Passives.onHit(this, living);
		if (hurt && kind.attack == UnitKind.Attack.SPLASH) {
			for (LivingEntity other : enemiesAround(target.position(), SPLASH_RADIUS))
				if (other != target) other.hurt(attackSource(), (float) kind.damage * 0.5f);
		}
		if (hurt) shotSounds();
		return hurt;
	}

	/**
	 * A shot at a point with no one there (owner 16.10: a player playing as the unit fires where they aim, not
	 * only at a target): a flying shot goes that way, a hitscan weapon draws its tracer to the point.
	 */
	public void fireAtPoint(Vec3 point) {
		this.swing(InteractionHand.MAIN_HAND);
		shotSounds();
		Entity body = body();
		if (kind.shot() != null && kind.attack != UnitKind.Attack.LINE) {
			ZergSpit spit = new ZergSpit(this.level(), body instanceof LivingEntity living ? living : this, kind);
			spit.setPos(muzzle());
			Vec3 from = spit.position(), to = point.subtract(from);
			spit.shoot(to.x, to.y, to.z, ZergSpit.speed(kind), 0.5f);
			this.level().addFreshEntity(spit);
		} else if (this.level() instanceof ServerLevel server) {
			Fx.shot(server, kind, muzzle(), point);
			server.sendParticles(ParticleTypes.SMOKE, point.x, point.y, point.z, 4, 0.1, 0.1, 0.1, 0.01);
		}
	}

	/** A shot or blow is heard: its weapon every time (owner 16.10), the unit's attack line now and then. */
	public void shotSounds() {
		float pitch = 0.9f + this.random.nextFloat() * 0.2f;
		this.playSound(StarMine.sounds(kind).fire(), 1.0f, pitch);
		if (this.random.nextInt(4) == 0) this.playSound(StarMine.sounds(kind).attack(), 1.0f, pitch);
	}

	/** The ultralisk's cleave around its target (SC2 about 1) and the baneling's burst (SC2 2.2), grown with the units. */
	public static final double SPLASH_RADIUS = 1 * UnitKind.BLOCKS_PER_UNIT, BURST_RADIUS = 2.2 * UnitKind.BLOCKS_PER_UNIT;

	/** Baneling: acid burst on everything around, then it dies (its death is the burst). */
	private void explode() {
		this.swing(InteractionHand.MAIN_HAND);
		for (LivingEntity other : enemiesAround(this.position(), BURST_RADIUS))
			other.hurt(attackSource(), (float) (kind.damage + dev.starmine.ability.Passives.bonus(this, other)));
		if (this.level() instanceof ServerLevel server)
			server.sendParticles(ParticleTypes.ITEM_SLIME, this.getX(), this.getY() + 0.5, this.getZ(), 40, 1.2, 0.5, 1.2, 0.1);
		this.kill();
	}

	private List<LivingEntity> enemiesAround(Vec3 centre, double radius) {
		return this.level().getEntitiesOfClass(LivingEntity.class, new AABB(centre, centre).inflate(radius),
			other -> other != this && dev.starmine.faction.Faction.hostile(this, other));
	}

	@Override
	public void performRangedAttack(LivingEntity target, float power) {
		this.swing(InteractionHand.MAIN_HAND);
		shotSounds();
		if (dev.starmine.ability.Status.has(this, dev.starmine.ability.Status.HALLUCINATION)) return; // an illusion shoots for show
		if (kind == UnitKind.CARRIER || kind == UnitKind.SELENDIS) { // its interceptors are its weapon (Selendis's flagship too)
			dev.starmine.ability.ProtossAbilities.launchInterceptors(this, target);
			return;
		}
		if (dev.starmine.ability.Modes.siegeShot(this, target)) {
			dev.starmine.ability.Passives.onHit(this, target);
			return;
		}
		if (kind.race != UnitKind.Race.ZERG && kind.attack == UnitKind.Attack.RANGED && kind.shot() == null) { // terran shots, protoss beams: hitscan
			target.hurt(attackSource(), (float) (weaponAgainst(target).damage() + attackBonus() + dev.starmine.ability.Passives.bonus(this, target)));
			dev.starmine.ability.Passives.onHit(this, target);
			if (this.level() instanceof ServerLevel server) tracer(server, target);
			return;
		}
		switch (kind.attack) { // a flying shot (zerg missiles, rockets, grenades, plasma) by default
			case LINE -> spineLine(target);
			case PULL -> { // the viper: its abduct is its weapon when it has the energy
				if (this.level() instanceof ServerLevel server)
					dev.starmine.ability.Abilities.cast(this, server, dev.starmine.ability.Ability.ABDUCT, target, null);
			}
			case SPAWN_LOCUSTS -> {
				for (int i = 0; i < 2; i++) summon(StarMine.type(UnitKind.LOCUST), Spots.outside(this.level(), UnitKind.LOCUST, this.position(), this.random.nextDouble() * Math.PI * 2,
					this.getBbWidth() / 2 + 1), target);
			}
			default -> {
				ZergSpit spit = new ZergSpit(this.level(), body(), kind);
				spit.setPos(muzzle());
				double dx = target.getX() - spit.getX();
				double dy = target.getY(0.4) - spit.getY();
				double dz = target.getZ() - spit.getZ();
				double flat = Math.sqrt(dx * dx + dz * dz);
				spit.shoot(dx, dy + flat * (kind == UnitKind.BROOD_LORD ? 0.0 : 0.15), dz, ZergSpit.speed(kind), 4.0f);
				spit.aimAt(target);
				this.level().addFreshEntity(spit);
			}
		}
	}

	/** Shots it has fired: twin weapons take turns ({@link UnitKind#muzzle}). */
	private int shots;

	/** Where its next shot leaves its weapon, in the world (its pilot's body when played: it faces where they look). */
	public Vec3 muzzle() {
		Entity body = body();
		float[] at = kind.muzzle(shots++);
		double yaw = Math.toRadians(body instanceof LivingEntity living ? living.yBodyRot : body.getYRot());
		double half = body.getBbWidth() / 2;
		double forward = at[2] * half, right = at[0] * half;
		// Minecraft: forward is (-sin, cos), right is (-cos, -sin)
		return body.position().add(-Math.sin(yaw) * forward - Math.cos(yaw) * right, at[1] * body.getBbHeight(),
			Math.cos(yaw) * forward - Math.sin(yaw) * right);
	}

	/** A hitscan shot: its beam from the weapon to the target ({@link Fx}) and a flash and a puff where it hits. */
	private void tracer(ServerLevel server, LivingEntity target) {
		Vec3 from = muzzle(), to = target.position().add(0, target.getBbHeight() * 0.5, 0);
		Fx.shot(server, kind, from, to);
		server.sendParticles(kind.race == UnitKind.Race.PROTOSS ? ParticleTypes.END_ROD : ParticleTypes.SMOKE, to.x, to.y, to.z, 3, 0.1, 0.1, 0.1, 0.01);
		server.sendParticles(ParticleTypes.CRIT, from.x, from.y, from.z, 2, 0.02, 0.02, 0.02, 0.05); // the muzzle flash
	}

	/**
	 * A line weapon, everyone in it hit once: the lurker's spines burst out of the ground toward the target, the
	 * hellion's and hellbat's flames pour from its nozzle; the colossus' two thermal lances sweep a line across the
	 * target (SC2: across, not along — a colossus burns a row of zerglings), drawn as lasers from its weapons.
	 */
	private void spineLine(LivingEntity target) {
		Vec3 from = this.position(), direction = target.position().subtract(from).multiply(1, 0, 1).normalize();
		dev.starmine.ability.Modes.Weapon weapon = weapon();
		java.util.Set<LivingEntity> hit = new java.util.HashSet<>(); // each once along the line
		java.util.List<Vec3> line = new java.util.ArrayList<>();
		if (kind == UnitKind.COLOSSUS) { // across the target, SC2's sweep about 2.6 long
			Vec3 across = new Vec3(-direction.z, 0, direction.x);
			double half = 1.3 * UnitKind.BLOCKS_PER_UNIT;
			for (double step = -half; step <= half; step += 0.5) line.add(target.position().add(across.scale(step)));
		} else {
			Vec3 start = kind == UnitKind.LURKER ? from : muzzle().subtract(0, 0.3, 0);
			for (double step = 1; step <= weapon.range(); step += 0.5) line.add(start.add(direction.scale(step)));
		}
		for (Vec3 point : line) {
			if (this.level() instanceof ServerLevel server)
				server.sendParticles(switch (kind) {
					case HELLION, HELLBAT -> ParticleTypes.FLAME;
					case COLOSSUS -> ParticleTypes.SMALL_FLAME;
					default -> ParticleTypes.CRIT;
				}, point.x, point.y + 0.3, point.z, kind == UnitKind.COLOSSUS ? 1 : 3, 0.2, 0.3, 0.2, 0.05);
			for (LivingEntity other : enemiesAround(point, 0.6))
				if (hit.add(other)) {
					other.hurt(attackSource(), (float) (weapon.damage() + attackBonus() + dev.starmine.ability.Passives.bonus(this, other)));
					dev.starmine.ability.Passives.onHit(this, other);
				}
		}
		if (kind == UnitKind.COLOSSUS && this.level() instanceof ServerLevel server && !line.isEmpty()) {
			Vec3 left = muzzle(), right = muzzle(); // its two lances sweep the line from each end to the middle
			int steps = Math.min(6, line.size());
			dev.starmine.ability.Delayed.repeat(server, 1, steps, n -> {
				Vec3 a = line.get(n * (line.size() - 1) / Math.max(1, 2 * steps - 1)), b = line.get(line.size() - 1 - n * (line.size() - 1) / Math.max(1, 2 * steps - 1));
				Fx.shot(server, kind, left, a.add(0, 0.2, 0));
				Fx.shot(server, kind, right, b.add(0, 0.2, 0));
			});
		}
	}

	/** A zerg this one calls (broodlings from eggs, locusts), already hunting its target. */
	public static void summon(EntityType<StarUnit> type, Vec3 at, LivingEntity target) {
		Level level = target.level();
		StarUnit mob = type.create(level);
		if (mob == null) return;
		mob.moveTo(at.x, at.y, at.z, level.random.nextFloat() * 360, 0);
		mob.setTarget(target);
		level.addFreshEntity(mob);
	}

	@Override
	protected void tickDeath() {
		++this.deathTime;
		if (deathTime == 1 && level() instanceof ServerLevel server && kind.structure() && kind.race == UnitKind.Race.ZERG
			&& kind != UnitKind.SPINE_CRAWLER && kind != UnitKind.SPORE_CRAWLER && kind != UnitKind.EGG) {
			Hive base = hiveId == null ? null : HiveSavedData.get(server.getServer()).get(hiveId);
			for (int i = 0; i < 3; i++) {
				StarUnit broodling = StarMine.type(UnitKind.BROODLING).create(server);
				if (broodling == null) continue;
				double angle = i * Math.PI * 2 / 3;
				broodling.moveTo(getX() + Math.cos(angle) * (kind.width / 2 + 1), getY(), getZ() + Math.sin(angle) * (kind.width / 2 + 1), getYRot(), 0);
				if (base != null) broodling.joinHive(base);
				owner().ifPresent(broodling::setOwner);
				broodling.setTarget(getLastHurtByMob());
				server.addFreshEntity(broodling);
			}
		}
		if (this.level() instanceof ServerLevel server) deathBurst(server);
		if (kind.flying) this.setNoGravity(false);
		if (this.isVehicle() && !this.level().isClientSide()) dev.starmine.ability.Transport.atDeath(this);
		if (this.deathTime >= kind.deathTicks() && !this.level().isClientSide() && !this.isRemoved()) {
			this.level().broadcastEntityEvent(this, (byte) 60);
			this.remove(Entity.RemovalReason.KILLED);
		}
	}

	/**
	 * How it dies, seen (owner 17.10: better deaths): zerg burst in gore and acid, terran machines and buildings
	 * explode in fire and smoke (a building in several blasts), terran infantry falls with blood, protoss flash
	 * blue as their shields and bodies come apart; a flyer trails smoke as it falls. The body itself falls over and
	 * sinks or plays its SC2 death (client).
	 */
	private void deathBurst(ServerLevel server) {
		double w = this.getBbWidth() / 2, h = this.getBbHeight(), x = this.getX(), y = this.getY() + h / 2, z = this.getZ();
		int size = (int) Math.max(1, Math.min(12, w * h));
		boolean building = kind.structure() && kind != UnitKind.EGG;
		if (this.deathTime == 1 || building && (this.deathTime == 10 || this.deathTime == 22)) {
			double ox = building ? (this.random.nextDouble() - 0.5) * w : 0, oz = building ? (this.random.nextDouble() - 0.5) * w : 0;
			switch (kind.race) {
				case ZERG -> {
					server.sendParticles(new net.minecraft.core.particles.ItemParticleOption(ParticleTypes.ITEM, new net.minecraft.world.item.ItemStack(
						net.minecraft.world.item.Items.SLIME_BALL)), x + ox, y, z + oz, 10 * size, w / 2, h / 3, w / 2, 0.15);
					server.sendParticles(ParticleTypes.SQUID_INK, x + ox, y, z + oz, 4 * size, w / 2, h / 3, w / 2, 0.05);
					server.sendParticles(new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(0.45f, 0.05f, 0.1f), 2f),
						x + ox, y, z + oz, 6 * size, w / 2, h / 3, w / 2, 0);
				}
				case TERRAN -> {
					if (kind.mechanical()) {
						server.sendParticles(building ? ParticleTypes.EXPLOSION_EMITTER : ParticleTypes.EXPLOSION, x + ox, y, z + oz, building ? 1 : Math.max(1, size / 3),
							w / 3, h / 4, w / 3, 0);
						server.sendParticles(ParticleTypes.LAVA, x + ox, y, z + oz, 3 * size, w / 2, h / 3, w / 2, 0);
						server.sendParticles(ParticleTypes.LARGE_SMOKE, x + ox, y, z + oz, 4 * size, w / 2, h / 3, w / 2, 0.03);
						server.playSound(null, x, y, z, net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(), net.minecraft.sounds.SoundSource.HOSTILE,
							building ? 2f : 0.8f, building ? 0.7f : 1.2f);
					} else server.sendParticles(new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(0.6f, 0.05f, 0.05f), 1.5f),
						x, y, z, 12, w / 2, h / 3, w / 2, 0);
				}
				case PROTOSS -> {
					server.sendParticles(ParticleTypes.FLASH, x + ox, y, z + oz, 1, 0, 0, 0, 0);
					server.sendParticles(ParticleTypes.END_ROD, x + ox, y, z + oz, 8 * size, w / 2, h / 3, w / 2, 0.12);
					server.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x + ox, y, z + oz, 4 * size, w / 2, h / 3, w / 2, 0.06);
					if (kind.mechanical()) server.sendParticles(ParticleTypes.EXPLOSION, x + ox, y, z + oz, Math.max(1, size / 3), w / 3, h / 4, w / 3, 0);
				}
			}
		}
		if (kind.flying && this.deathTime % 2 == 0 && !this.onGround()) // a falling flyer trails smoke
			server.sendParticles(kind.race == UnitKind.Race.ZERG ? ParticleTypes.SQUID_INK : ParticleTypes.LARGE_SMOKE, x, y, z, 2, w / 3, h / 4, w / 3, 0.01);
	}

	/**
	 * The colossus walks on stilts (SC2: it steps over cliffs and small things): nothing round its tall body stops
	 * it, its feet keep to the ground under its middle — up a cliff of up to {@link #STILT_STEP} blocks a step at a
	 * time, down at falling speed. Its way is planned for its legs ({@link UnitNavigation}).
	 */
	private void stilts() {
		// the highest ground under its feet (its middle and the corners of its legs' box): a leg on a ledge lifts it
		double ground = Double.NaN, half = this.getBbWidth() / 2 - 0.1;
		for (double[] foot : new double[][] {{0, 0}, {-half, -half}, {half, -half}, {-half, half}, {half, half}}) {
			double under = groundUnder(this.getX() + foot[0], this.getZ() + foot[1]);
			if (!Double.isNaN(under) && (Double.isNaN(ground) || under > ground)) ground = under;
		}
		net.minecraft.world.phys.Vec3 motion = this.getDeltaMovement();
		if (Double.isNaN(ground)) { // over water or a deep hole: no stilts, it falls like anything else
			this.noPhysics = false;
			return;
		}
		double y = ground > this.getY() ? Math.min(ground, this.getY() + 0.6) : Math.max(ground, this.getY() - 0.6);
		this.setPos(this.getX(), y, this.getZ());
		this.setDeltaMovement(motion.x, 0, motion.z);
		this.setOnGround(true);
		this.fallDistance = 0;
	}

	/** The top of the ground under a column within a stride up or down, NaN over water or a deep hole; tree tops do not count. */
	private double groundUnder(double px, double pz) {
		net.minecraft.core.BlockPos.MutableBlockPos at = new net.minecraft.core.BlockPos.MutableBlockPos();
		int x = net.minecraft.util.Mth.floor(px), z = net.minecraft.util.Mth.floor(pz);
		for (int y = net.minecraft.util.Mth.floor(this.getY() + STILT_STEP); y >= net.minecraft.util.Mth.floor(this.getY()) - 12; y--) {
			at.set(x, y, z);
			BlockState state = this.level().getBlockState(at);
			if (state.getFluidState().isSource() && !state.isSolid()) return Double.NaN; // it does not wade
			var shape = state.getCollisionShape(this.level(), at);
			if (shape.isEmpty() || state.is(net.minecraft.tags.BlockTags.LEAVES)) continue; // it steps through tree tops
			return y + shape.max(net.minecraft.core.Direction.Axis.Y);
		}
		return Double.NaN;
	}

	/** The highest step a colossus takes (SC2: cliffs). */
	static final double STILT_STEP = 4.5;

	@Override
	public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
		return !flying() && super.causeFallDamage(distance, multiplier, source);
	}

	@Override
	protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
		if (!flying()) super.checkFallDamage(y, onGround, state, pos);
	}

	@Override
	protected SoundEvent getAmbientSound() {
		return StarMine.sounds(kind).ambient();
	}

	/** Ticks between a unit's idle lines on average (owner 16.10: they talked too often; vanilla mobs every 4 s). */
	public static final int AMBIENT_INTERVAL = 90 * 20;
	/** Ticks a unit keeps quiet after a hurt line. */
	public static final int HURT_LINE_REST = 8 * 20;
	private long hurtLineUntil;

	@Override
	public int getAmbientSoundInterval() {
		return AMBIENT_INTERVAL;
	}

	@Override
	protected void playHurtSound(DamageSource source) {
		long now = this.level().getGameTime();
		if (now < hurtLineUntil) return;
		hurtLineUntil = now + HURT_LINE_REST;
		super.playHurtSound(source);
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return StarMine.sounds(kind).hurt();
	}

	@Override
	protected SoundEvent getDeathSound() {
		return StarMine.sounds(kind).death();
	}

	@Override
	protected void playStepSound(BlockPos pos, BlockState state) {
	}

	// ---- terran orders, production and research (owner 14.10) ----

	/** A player's order: move, attack-move, patrol (from here to `pos` and back), hold, stop. */
	public void command(Order order, Vec3 pos) {
		blindAttack = null;
		if (owner().isPresent()) abilities.cancelChannel(); // a player's order breaks a channel (a nuke falls dead)
		this.work = Work.NONE;
		this.order = order;
		this.orderPos = pos;
		this.patrolFrom = order == Order.PATROL ? this.position() : null;
		if (order == Order.MOVE || order == Order.HOLD) this.setTarget(null);
		if (order == Order.NONE) { // stop: stay here
			this.getNavigation().stop();
			this.setTarget(null);
			this.home = this.blockPosition();
		}
		if (kind.worker()) {
			cancelBuild();
			constructing = null;
		}
		this.getNavigation().stop();
	}

	public Order order() {
		return order;
	}

	public Vec3 orderPos() {
		return orderPos;
	}

	/** Its side's team colour (ARGB), both sides. */
	public int teamColor() {
		int team = this.entityData.get(TEAM);
		return team != 0 ? team : dev.starmine.faction.TeamColors.race(kind.race);
	}

	/** The player commanding it for now (a neural parasite's), both sides. */
	public java.util.Optional<UUID> controller() {
		return this.entityData.get(CONTROLLER);
	}

	public void setController(@org.jetbrains.annotations.Nullable UUID player) {
		if (!controller().equals(java.util.Optional.ofNullable(player))) this.entityData.set(CONTROLLER, java.util.Optional.ofNullable(player));
	}

	/** Its order given up where it stands (its home stays): a parasite's hold ended. */
	public void dropOrder() {
		this.order = Order.NONE;
		this.orderPos = null;
		this.getNavigation().stop();
	}

	/** The order is done: the unit stays where it got to. */
	void finishOrder() {
		this.order = Order.NONE;
		this.orderPos = null;
		this.home = this.blockPosition();
	}

	/** Patrol: turn back toward where it came from. */
	public void turnPatrol() {
		Vec3 back = patrolFrom;
		patrolFrom = orderPos;
		orderPos = back;
	}

	/** A worker's order: gather at a block, or bring the cargo home. */
	public void commandWork(Work work, BlockPos pos) {
		command(Order.NONE, null);
		this.workArea = null;
		this.work = work;
		this.workPos = pos == null ? null : pos.immutable();
	}

	/** The worker goal takes a new work order (once). */
	Work takeWork() {
		Work taken = work;
		work = Work.NONE;
		return taken;
	}

	BlockPos workPos() {
		return workPos;
	}

	/** Attack this one (a player's order); the unit stays near it afterwards. */
	public void commandAttack(LivingEntity target) {
		command(Order.NONE, null);
		this.setTarget(target);
	}

	public void commandGround(Vec3 at) {
		command(Order.MOVE, at);
		blindAttack = at;
	}

	public java.util.Optional<UUID> owner() {
		return this.entityData.get(OWNER);
	}

	public List<UnitKind> queue() {
		return queue;
	}

	/** A terran building in the air: it neither trains nor researches (SC2). */
	private boolean lifted() {
		return mode() == dev.starmine.ability.Modes.AIRBORNE;
	}

	/** Whether it trains or researches something now (SC2: a terran building lifts off only idle). */
	public boolean working() {
		return !queue.isEmpty() || research != null;
	}

	/** Queues a unit this building trains: paid now, supply reserved; false when it cannot. */
	public boolean train(UnitKind unit) {
		if (!(this.level() instanceof ServerLevel server) || constructTicks > 0 || !kind.trains().contains(unit) || queue.size() >= QUEUE
			|| !kind.race.rules().producerReady(this, unit) || lifted()) return false;
		Hive hive = HiveSavedData.get(server.getServer()).get(hiveId);
		if (hive == null || !hive.unlocked(unit) || !hive.canAfford(unit, 1)) return false;
		hive.pay(unit, 1);
		hive.expect(unit, 1);
		queue.add(unit);
		HiveSavedData.get(server.getServer()).setDirty();
		syncProgress();
		return true;
	}

	/** Cancels the last queued unit (refunded), or a building still under construction (75 % back, SC2). */
	public void cancelLast() {
		if (!(this.level() instanceof ServerLevel server)) return;
		Hive hive = HiveSavedData.get(server.getServer()).get(hiveId);
		if (!queue.isEmpty()) {
			UnitKind last = queue.remove(queue.size() - 1);
			if (queue.isEmpty()) trainTicks = 0;
			if (queue.size() < 2) secondTicks = 0;
			if (hive != null) {
				hive.refund(last, 1);
				hive.born(last, 1);
			}
		} else if (constructTicks > 0) {
			if (hive != null) {
				hive.minerals += kind.economy().minerals() * 3 / 4;
				hive.vespene += kind.economy().vespene() * 3 / 4;
			}
			this.discard();
		} else if (research != null) {
			dev.starmine.race.Research what = dev.starmine.race.Research.byId(research);
			if (hive != null && what != null) {
				hive.refund(what.price(what.nextLevel(hive)));
			}
			research = null;
			researchTicks = 0;
		}
		if (hive != null) HiveSavedData.get(server.getServer()).setDirty();
		syncProgress();
	}

	public void setRally(Vec3 at) {
		this.rally = at;
	}

	/**
	 * The first queued unit trains (with a reactor the first two at once; under chrono boost 50 % faster);
	 * done, it walks out to the rally point (or next to the building). A warp gate trains nothing ({@link #warpIn}).
	 */
	private void produce(ServerLevel server) {
		if (abilities.changingMode()) return;
		boolean warping = mode() == dev.starmine.ability.Modes.WARPGATE;
		if (warping && warpCooldown > 0) warpCooldown -= workTicks();
		if (queue.isEmpty()) return;
		if (warping) {
			warpIn(server);
			return;
		}
		int step = workTicks();
		trainTicks += step;
		boolean second = mode() == dev.starmine.ability.Modes.REACTOR && queue.size() > 1;
		secondTicks = second ? secondTicks + step : 0;
		// owner 18.10 (SC2): each reactor slot finishes on its own unit's time — a unit queued second used to finish
		// together with the first, whatever its own time or how late it was queued
		boolean firstDone = trainTicks >= queue.get(0).economy().trainTicks(), secondDone = second && secondTicks >= queue.get(1).economy().trainTicks();
		if (!firstDone && !secondDone) return;
		List<UnitKind> ready = new ArrayList<>();
		if (secondDone) {
			ready.add(queue.remove(1));
			secondTicks = 0;
		}
		if (firstDone) {
			ready.add(0, queue.remove(0));
			trainTicks = secondDone ? 0 : secondTicks; // the second slot's unit moves up with its progress
			secondTicks = 0;
		}
		Hive hive = HiveSavedData.get(server.getServer()).get(hiveId);
		for (UnitKind unit : ready) {
			if (hive == null) return;
			hive.born(unit, 1);
			StarUnit born = HiveLogic.spawn(server, hive, unit, outside(server, unit), null);
			if (born != null) {
				if (rally != null) born.command(Order.MOVE, rally);
				else born.home = born.blockPosition();
			}
		}
		HiveSavedData.get(server.getServer()).setDirty();
	}

	/** A spot just out of the building for a new unit: by both bodies' half widths (a thor, 10 wide, came out inside its factory). */
	private Vec3 outside(ServerLevel server, UnitKind unit) {
		double angle = this.random.nextDouble() * Math.PI * 2, radius = kind.width / 2 + unit.width / 2 + 1;
		return Spots.outside(server, unit, this.position().add(0, unit.flying ? 2 : 0, 0), angle, radius);
	}

	/**
	 * A warp gate (SC2 LotV) trains nothing: when it is cool, the first ordered unit warps in at once at its rally point
	 * if that is in power (a pylon's or a phasing warp prism's), else by the gate; it stays there unable to move or fight
	 * for its warp-in time ({@link dev.starmine.race.ProtossRace#warpInTicks}: 16 s, 4 s by a nexus or a warp gate), and
	 * the gate cools down for that unit's SC2 time ({@link dev.starmine.race.ProtossRace#warpCooldown}).
	 */
	private void warpIn(ServerLevel server) {
		Hive hive = HiveSavedData.get(server.getServer()).get(hiveId);
		if (warpCooldown > 0 || hive == null) return;
		UnitKind unit = queue.remove(0);
		trainTicks = 0;
		hive.born(unit, 1);
		Vec3 spot = rally != null && dev.starmine.race.ProtossRace.poweredAt(server, rally.x, rally.y, rally.z, hiveId) ? rally : outside(server, unit);
		server.sendParticles(ParticleTypes.END_ROD, spot.x, spot.y + 1, spot.z, 30, 0.5, 1, 0.5, 0.02);
		StarUnit born = HiveLogic.spawn(server, hive, unit, spot, null);
		if (born != null) {
			born.home = born.blockPosition();
			dev.starmine.ability.Status.apply(born, dev.starmine.ability.Status.WARPING, dev.starmine.race.ProtossRace.warpInTicks(server, spot, hiveId));
		}
		warpCooldown = dev.starmine.race.ProtossRace.warpCooldown(unit);
		HiveSavedData.get(server.getServer()).setDirty();
	}

	/** Ticks of work a building does this tick: 1, or 1.5 on average under chrono boost (SC2 +50 %). */
	private int workTicks() {
		return dev.starmine.ability.Status.has(this, dev.starmine.ability.Status.CHRONO) && this.tickCount % 2 == 0 ? 2 : 1;
	}

	/** SC2 engineering bay upgrade cost by level (minerals = vespene): 100, 175, 250. */
	public static int researchCost(int level) {
		return 25 + 75 * level;
	}

	/** Starts a research this building does ({@link dev.starmine.race.Research}: weapons, armor, lair, hive); paid now. */
	public boolean startResearch(String id) {
		dev.starmine.race.Research what = dev.starmine.race.Research.byId(id);
		if (!(this.level() instanceof ServerLevel server) || what == null || !what.at(kind) || constructTicks > 0 || research != null || lifted()) return false;
		Hive hive = HiveSavedData.get(server.getServer()).get(hiveId);
		if (hive == null || what.refusal(hive, kind) != null) return false;
		if ((what.at(UnitKind.BARRACKS) || what.at(UnitKind.FACTORY) || what.at(UnitKind.STARPORT)) && mode() != dev.starmine.ability.Modes.TECHLAB)
			return false; // SC2: at the tech lab
		int level = what.nextLevel(hive);
		hive.pay(what.price(level));
		research = what.id();
		researchTicks = what.ticks(level);
		HiveSavedData.get(server.getServer()).setDirty();
		return true;
	}

	private void researchTick(ServerLevel server) {
		if (research == null || (researchTicks -= workTicks()) > 0) return;
		Hive hive = HiveSavedData.get(server.getServer()).get(hiveId);
		dev.starmine.race.Research what = dev.starmine.race.Research.byId(research);
		if (hive != null && what != null) {
			what.apply(hive);
			HiveSavedData.get(server.getServer()).setDirty();
		}
		research = null;
	}

	// ---- abilities (owner 14.10, dev.starmine.ability) ----

	public float energy() {
		return this.entityData.get(ENERGY);
	}

	public void setEnergy(float energy) {
		this.entityData.set(ENERGY, Math.max(0, energy));
	}

	public boolean cloaked() {
		return this.entityData.get(CLOAKED);
	}

	public void setCloaked(boolean cloaked) {
		this.entityData.set(CLOAKED, cloaked);
	}

	/** Cloaked but seen by a foe's detector (the client draws it plainly). */
	public boolean revealed() {
		return this.entityData.get(REVEALED);
	}

	public void setRevealed(boolean revealed) {
		this.entityData.set(REVEALED, revealed);
	}

	/** Its mode (dev.starmine.ability.Modes: 0 normal, 1 sieged…). */
	public int mode() {
		return this.entityData.get(MODE);
	}

	public Vec3 strikePoint() {
		var point = entityData.get(STRIKE_POINT);
		return new Vec3(point.x, point.y, point.z);
	}

	public void setMode(int mode) {
		this.entityData.set(MODE, mode);
		if (kind == UnitKind.LIBERATOR) { // owner 19.10: SC2's defender zone, set where its foe stands (else ahead of it)
			LivingEntity foe = getTarget();
			zone = mode != dev.starmine.ability.Modes.DEFENDER ? null
				: foe != null && foe.isAlive() ? foe.position() : position().add(getLookAngle().multiply(1, 0, 1).normalize().scale(ZONE_RANGE));
		}
	}

	/** SC2 defender mode: a zone 5 across (units) at most 5 off, where a liberator's lancer missiles reach the ground. */
	public static final double ZONE_RADIUS = 5 * UnitKind.BLOCKS_PER_UNIT / 2 + 1, ZONE_RANGE = 5 * UnitKind.BLOCKS_PER_UNIT;
	private @org.jetbrains.annotations.Nullable Vec3 zone;

	/** A deployed liberator's zone, or null. */
	public @org.jetbrains.annotations.Nullable Vec3 zone() {
		return zone;
	}

	/** Whether a target stands in a deployed liberator's zone. */
	public boolean inZone(LivingEntity other) {
		return zone != null && Math.hypot(other.getX() - zone.x, other.getZ() - zone.z) <= ZONE_RADIUS;
	}

	/** Shows a change starting (to a mode, or `into` another kind) of `ticks`, or none (`ticks` 0). */
	public void showChange(int mode, @org.jetbrains.annotations.Nullable UnitKind into, int ticks) {
		this.entityData.set(CHANGE_TICKS, ticks);
		this.entityData.set(CHANGE, ticks <= 0 ? 0 : into != null ? 1000 + into.ordinal() : mode + 1);
	}

	/** The mode a change under way goes to (-1: none, or a change into another kind). */
	public int changingTo() {
		int change = this.entityData.get(CHANGE);
		return change > 0 && change < 1000 ? change - 1 : -1;
	}

	/** The kind a change under way makes it (null: none, or a change of mode). */
	public @org.jetbrains.annotations.Nullable UnitKind changingInto() {
		int change = this.entityData.get(CHANGE);
		return change >= 1000 && change - 1000 < UnitKind.values().length ? UnitKind.values()[change - 1000] : null;
	}

	/** How far a change under way has got (0..1; 0 none), counted on this side from when it was seen to start. */
	public float changeProgress(float partialTick) {
		int ticks = this.entityData.get(CHANGE_TICKS);
		if (ticks <= 0 || this.entityData.get(CHANGE) == 0) return 0;
		return net.minecraft.util.Mth.clamp((this.tickCount - changeSeen + partialTick) / ticks, 0, 1);
	}

	/** This side's tick when the change under way was seen to start. */
	private int changeSeen;

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
		super.onSyncedDataUpdated(key);
		if (key == CHANGE) changeSeen = this.tickCount;
	}

	/** A changeling's look: "" its own, "villager", or the UUID of the player it copied (both sides: the client draws it). */
	public String disguise() {
		return this.entityData.get(DISGUISE);
	}

	public void setDisguise(String disguise) {
		this.entityData.set(DISGUISE, disguise);
	}

	/** Its weapon and mobility in a mode (the attack goals read the weapon each tick); an immobile mode stops it. */
	public void applyWeapon(dev.starmine.ability.Modes.Weapon weapon) {
		this.weapon = weapon;
		double factor = weapon.mobile() ? dev.starmine.ability.Modes.speedFactor(kind, mode()) * dev.starmine.ability.Status.speedFactor(this) : 0;
		modifier(Attributes.MOVEMENT_SPEED, MODE_SPEED, factor - 1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
		modifier(Attributes.FLYING_SPEED, MODE_SPEED, factor - 1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
		if (factor <= 0) this.getNavigation().stop();
	}

	/** The weapon it fights `target` with: its weapon against flyers when it has one of its own (SC2, {@link dev.starmine.ability.Modes#airWeapon}). */
	public dev.starmine.ability.Modes.Weapon weaponAgainst(@org.jetbrains.annotations.Nullable LivingEntity target) {
		dev.starmine.ability.Modes.Weapon own = weapon();
		if (target == null || own.interval() <= 0 || abilities.changingMode() || !dev.starmine.ability.Modes.airborne(target)) return own;
		dev.starmine.ability.Modes.Weapon air = dev.starmine.ability.Modes.airWeapon(kind, mode());
		return air == null ? own : dev.starmine.race.Research.adjust(this, dev.starmine.ability.Status.adjust(this, air));
	}

	/** The weapon it fights with now (its mode's, as its states leave it). */
	public dev.starmine.ability.Modes.Weapon weapon() {
		if (weapon == null) weapon = dev.starmine.ability.Modes.weapon(kind, mode());
		return weapon;
	}

	private dev.starmine.ability.Modes.Weapon weapon;
	/** Ticks until its weapon may fire again: the unit's, like SC2's weapon cooldown (not reset by a new target or goal). */
	private int weaponCooldown;

	public boolean weaponReady() {
		return weaponCooldown <= 0;
	}

	public void startCooldown(int ticks) {
		weaponCooldown = ticks;
	}

	/** Rebuilds its weapon and speed from its mode and timed states (dev.starmine.ability.Status); not while a mode change is under way. */
	public void refreshWeapon() {
		if (abilities.changingMode()) return;
		applyWeapon(dev.starmine.race.Research.adjust(this, dev.starmine.ability.Status.adjust(this, dev.starmine.ability.Modes.weapon(kind, mode()))));
	}

	/**
	 * The body of its mode: a landed viking walks (ground navigation, gravity), taking off it flies again;
	 * a hellbat and a planetary fortress have more health; an orbital command gets energy.
	 */
	public void applyModeBody() {
		boolean air = flying();
		if (kind.flying && air != (this.moveControl instanceof FlyingMoveControl)) {
			if (air) {
				this.moveControl = new FlyingMoveControl(this, 10, true);
				FlyingPathNavigation navigation = new FlyingPathNavigation(this, this.level());
				navigation.setCanOpenDoors(false);
				navigation.setCanFloat(true);
				navigation.setCanPassDoors(true);
				this.navigation = navigation;
				this.setDeltaMovement(this.getDeltaMovement().add(0, 0.4, 0));
			} else {
				this.moveControl = new net.minecraft.world.entity.ai.control.MoveControl(this);
				this.navigation = new UnitNavigation(this, this.level());
			}
			this.setNoGravity(air);
		}
		double extra = dev.starmine.ability.Modes.extraHealth(kind, mode()) * UnitKind.SCALE;
		AttributeInstance health = this.getAttribute(Attributes.MAX_HEALTH);
		if (health != null && (health.getModifier(MODE_HEALTH) == null ? 0 : health.getModifier(MODE_HEALTH).amount()) != extra) {
			float ratio = this.getHealth() / this.getMaxHealth();
			modifier(Attributes.MAX_HEALTH, MODE_HEALTH, extra);
			this.setHealth(Math.max(1, ratio * this.getMaxHealth()));
		}
		if (mode() == dev.starmine.ability.Modes.ORBITAL && energy() == 0 && maxEnergy() > 0 && kind.maxEnergy() == 0 && !orbitalCharged) {
			setEnergy(50); // SC2: an orbital command starts with 50
			orbitalCharged = true;
		}
	}

	private boolean orbitalCharged;
	private static final ResourceLocation MODE_HEALTH = StarMine.id("mode_health"), BONUS_DAMAGE = StarMine.id("bonus_damage");

	/** Whether it is in the air now: a flying kind, or a terran building lifted off. */
	public boolean flying() {
		return kind.flying || mode() == dev.starmine.ability.Modes.AIRBORNE;
	}

	/**
	 * Whether its weapon can hit this target (SC2, {@link UnitKind#hitsAir}, {@link UnitKind#hitsGround}): a viking hits
	 * only flyers (and the colossus), a tank only the ground; a liberator flyers, in defender mode the ground.
	 */
	public boolean canTarget(LivingEntity other) {
		boolean air = other instanceof StarUnit unit ? unit.flying() : other instanceof net.minecraft.world.entity.FlyingMob || other.isFallFlying()
			|| other instanceof net.minecraft.world.entity.monster.Phantom || other instanceof net.minecraft.world.entity.monster.Vex;
		boolean both = other instanceof StarUnit unit && unit.kind.hitByAntiAir() && !unit.flying(); // a colossus: hit from the air and the ground
		if (kind == UnitKind.LIBERATOR) return mode() == dev.starmine.ability.Modes.DEFENDER ? !air && (zone == null || inZone(other)) : air || both;
		if (pilot != null) return true; // a player plays it: they aim
		return air ? kind.hitsAir() : kind.hitsGround() || both && kind.hitsAir();
	}

	/** SC2 energy it holds at most: its kind's, or an orbital command's 200. */
	public int maxEnergy() {
		return kind == UnitKind.COMMAND_CENTER && mode() == dev.starmine.ability.Modes.ORBITAL ? 200 : kind.maxEnergy();
	}

	/** Biological (a medivac heals it, steady targeting hits it): its kind's (a hellbat too). */
	public boolean biological() {
		return kind.biological();
	}

	/**
	 * A summon that belongs to its base without counting in it (a MULE, a changeling, a hallucination, an
	 * interceptor): it joins its base's side and work but takes no supply and leaves no gap when it dies.
	 */
	public void joinAsSummon(Hive hive, @org.jetbrains.annotations.Nullable UUID summoner) {
		this.hiveId = hive.id;
		this.entityData.set(OWNER, java.util.Optional.ofNullable(hive.owner));
		this.home = hive.hatchery;
		this.summoned = true;
		this.summoner = summoner;
	}

	/** A summon's side without a base (a wild unit's summon): its summoner's owner. */
	public void summonedBy(StarUnit summoner) {
		this.summoned = true;
		this.summoner = summoner.getUUID();
		setOwner(summoner.owner().orElse(null));
		this.home = summoner.blockPosition();
	}

	public boolean summoned() {
		return summoned;
	}

	/** A MULE (an orbital command's calldown): a summoned SCV that mines 4 times as much for 64 s. */
	public boolean mule() {
		return summoned && kind == UnitKind.SCV;
	}

	/** Who summoned it (an interceptor's carrier), or null. */
	public @org.jetbrains.annotations.Nullable UUID summoner() {
		return summoner;
	}

	private boolean summoned;
	private UUID summoner;

	/**
	 * Vanilla's line of sight, except that a force field stops bodies, not shots (SC2): a sentry's field
	 * between the armies blinded both for 11 s, its own stalkers behind it most of all.
	 */
	@Override
	public boolean hasLineOfSight(Entity other) {
		if (other.level() != this.level()) return false;
		Vec3 from = new Vec3(this.getX(), this.getEyeY(), this.getZ()), to = new Vec3(other.getX(), other.getEyeY(), other.getZ());
		if (to.distanceTo(from) > 128) return false;
		for (int field = 0; field < 8; field++) {
			net.minecraft.world.phys.BlockHitResult hit = this.level().clip(new net.minecraft.world.level.ClipContext(from, to,
				net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, this));
			if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) return true;
			if (!this.level().getBlockState(hit.getBlockPos()).is(dev.starmine.block.StarMineBlocks.FORCE_FIELD)) return false;
			// on through the field's block: from where the line leaves it
			Vec3 out = new AABB(hit.getBlockPos()).clip(to, from).orElse(null);
			if (out == null) return true; // the target stands in the field's block
			from = out.add(to.subtract(from).normalize().scale(1e-3));
		}
		return false;
	}

	/**
	 * SC2 armor: what of a StarMine hit reaches its health loses its armor once per strike of the
	 * weapon ({@link UnitKind#armor}, {@link UnitKind#strikes}), at least half a hit point a strike left.
	 * (Without it a marine's 6 hit a zealot's health for 6, not 5, and a zergling's 5 an ultralisk for
	 * 5, not 3.) Spells count as one strike.
	 */
	private float armored(DamageSource source, float amount) {
		UnitKind by = source.getEntity() instanceof StarUnit attacker ? attacker.kind
			: source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player ? dev.starmine.possess.Pilots.kindOf(player) : null;
		if (source.getEntity() instanceof StarUnit attacker) // its base's damage upgrades (infernal pre-igniter, tectonic destabilizers)
			amount += (float) (dev.starmine.race.Research.extraDamage(dev.starmine.ability.Abilities.base(attacker), attacker.kind, Trait.of(this)) * UnitKind.SCALE);
		int armor = kind.armor() + armorUpgrade() + (mode() == dev.starmine.ability.Modes.PLANETARY ? 2 : 0)
			+ (hiveId == null ? 0 : dev.starmine.race.Research.extraArmor(dev.starmine.ability.Abilities.base(this), kind))
			- (dev.starmine.ability.Status.has(this, dev.starmine.ability.Status.ARMOR_BROKEN) ? 3 : 0); // a raven's anti-armor missile
		if (armor == 0) return amount;
		int strikes = by == null ? 1 : by.strikes();
		return (float) Math.max(0.5 * strikes * UnitKind.SCALE, amount - armor * strikes * UnitKind.SCALE);
	}

	/** Set while a StarMine weapon hurts it: {@link #knockback} does nothing then. */
	private boolean steady;

	/**
	 * No knockback from StarMine weapons (SC2 has none: a zergling's bites pushed a thor out of its reach
	 * and every third bite went to catching up); a player's or a vanilla mob's blow still knocks a small
	 * unit back (its knockback resistance grows with its health).
	 */
	@Override
	public void knockback(double strength, double x, double z) {
		if (!steady) super.knockback(strength, x, z);
	}

	/** Potions and effects do not take on buildings (owner 17.10: a thrown potion of attributes leaves them be). */
	@Override
	public boolean canBeAffected(net.minecraft.world.effect.MobEffectInstance effect) {
		return !kind.structure() && super.canBeAffected(effect);
	}

	/** A lowered supply depot (SC2) lies flat in the ground: everyone walks over it, players too. */
	public boolean lowered() {
		return kind == UnitKind.SUPPLY_DEPOT && mode() == dev.starmine.ability.Modes.LOWERED;
	}

	private void modifier(net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, ResourceLocation id, double amount,
			AttributeModifier.Operation operation) {
		AttributeInstance instance = this.getAttribute(attribute);
		if (instance == null) return;
		AttributeModifier current = instance.getModifier(id);
		if (current != null && current.amount() == amount && current.operation() == operation) return;
		instance.removeModifier(id);
		if (amount != 0) instance.addTransientModifier(new AttributeModifier(id, amount, operation));
	}

	private static final ResourceLocation MODE_SPEED = StarMine.id("mode_speed");

	/** The player playing as this unit, or null. */
	public net.minecraft.server.level.ServerPlayer pilot() {
		return pilot;
	}

	public void setPilot(net.minecraft.server.level.ServerPlayer pilot) {
		this.pilot = pilot;
	}

	/** What moves and is hit for this unit: its pilot's body when a player plays as it, else the unit itself. */
	public LivingEntity body() {
		return pilot != null ? pilot : this;
	}

	/** The damage its weapons and spells deal: a player's attack when piloted (kills and anger go to the player). */
	public DamageSource attackSource() {
		return pilot != null ? this.damageSources().playerAttack(pilot) : this.damageSources().mobAttack(this);
	}

	/** The height this flyer keeps over a point: its hover height over the ground, lower under a roof. */
	public double cruiseHeight(double x, double z) {
		return ZergGoals.cruise(this, x, z, above(Math.max(3, kind.hoverHeight())));
	}

	/** A flyer's height over the ground: its usual one plus what its player set ({@link #altitude}), a block at the least. */
	public double above(double usual) {
		return Math.max(1, usual + altitude);
	}

	/** Raises (steps up) or lowers a flyer by {@link #ALTITUDE_STEP} blocks a step; an idle one goes to its new height at once. */
	public void climb(int steps) {
		if (!flying()) return;
		altitude = Math.max(ALTITUDE_MIN, Math.min(ALTITUDE_MAX, altitude + steps * ALTITUDE_STEP));
		if (order() == Order.NONE || order() == Order.HOLD) { // the flying navigation feeds the move control every tick (a bare wanted position steers one tick)
			double y = cruiseHeight(this.getX(), this.getZ());
			if (!this.getNavigation().moveTo(this.getX(), y, this.getZ(), 1.0)) this.getMoveControl().setWantedPosition(this.getX(), y, this.getZ(), 1.0);
		}
	}

	public int altitude() {
		return altitude;
	}

	/** The ground (feet level) under a point, as flying units and transports see it. */
	public double groundBelow(double x, double z) {
		return ZergGoals.ground(this, x, z);
	}

	public void setShield(float shield) {
		this.entityData.set(SHIELD, (float) Math.max(0, Math.min(maxShield(), shield)));
	}

	/** Its weapons upgrade level (0-3), both sides. */
	public int weaponsUpgrade() {
		return this.entityData.get(UPGRADES) & 15;
	}

	/** Its armor upgrade level (0-3): 1 armor each. */
	public int armorUpgrade() {
		return this.entityData.get(UPGRADES) >> 4 & 15;
	}

	/** Its shields upgrade level (0-3, protoss). */
	public int shieldsUpgrade() {
		return this.entityData.get(UPGRADES) >> 8 & 15;
	}

	/** Shields it holds at most: its kind's, a tenth more for each shields upgrade level (owner 17.10). */
	public double maxShield() {
		return kind.shields * (1 + 0.1 * shieldsUpgrade());
	}

	/** Shields left (health units), for the command view. */
	public float shield() {
		return this.entityData.get(SHIELD);
	}

	/** Damage takes the shields first (not falls, the void, or starving); what is left goes to health. */
	private float absorbByShield(DamageSource source, float amount) {
		float shield = shield();
		if (shield <= 0 || amount <= 0 || this.level().isClientSide || source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_ARMOR)) return amount;
		// SC2 shields upgrade: 1 shield armor a level, at least half a hit point gets through
		if (shieldsUpgrade() > 0) amount = (float) Math.max(Math.min(amount, 0.5 * UnitKind.SCALE), amount - shieldsUpgrade() * UnitKind.SCALE);
		float taken = Math.min(shield, amount);
		this.entityData.set(SHIELD, shield - taken);
		lastHurt = this.level().getGameTime();
		if (this.level() instanceof ServerLevel server)
			server.sendParticles(ParticleTypes.ENCHANTED_HIT, this.getX(), this.getY() + this.getBbHeight() / 2, this.getZ(), 6,
				this.getBbWidth() / 2, this.getBbHeight() / 3, this.getBbWidth() / 2, 0.1);
		return amount - taken;
	}

	/** Shields come back (SC2 2 a second, scaled) once {@link #SHIELD_DELAY} has passed since the last hit. */
	private void shieldTick(ServerLevel server) {
		float shield = shield();
		if (shield >= maxShield() || server.getGameTime() - lastHurt < SHIELD_DELAY || this.tickCount % 10 != 0) return;
		if (kind == UnitKind.ARTANIS && getTarget() != null) return; // owner 19.10: his shields come back slowly, only out of the fight
		this.entityData.set(SHIELD, (float) Math.min(maxShield(), shield + 2 * UnitKind.SCALE / 2));
	}

	/** SC2 medivac heal range 4. */
	private static final double HEAL_RANGE = 4 * UnitKind.BLOCKS_PER_UNIT + 1;

	/** Medivac: heals the most wounded bio unit of its race within {@link #HEAL_RANGE} (SC2 12.6 hit points a second, 1 energy per 3 healed). */
	private void heal(ServerLevel server) {
		server.getEntitiesOfClass(StarUnit.class, this.getBoundingBox().inflate(HEAL_RANGE, HEAL_RANGE + kind.hoverHeight(), HEAL_RANGE),
				mob -> mob.isAlive() && mob.biological() && dev.starmine.faction.Faction.allied(this, mob) && mob.getHealth() < mob.getMaxHealth())
			.stream().min((a, b) -> Float.compare(a.getHealth() / a.getMaxHealth(), b.getHealth() / b.getMaxHealth()))
			.ifPresent(mob -> {
				// SC2: 12.6 a second, 1 energy for every 3 hit points healed; no energy, no healing
				double healed = Math.min(12.6 / 2, (mob.getMaxHealth() - mob.getHealth()) / UnitKind.SCALE);
				if (energy() < healed / 3) return;
				setEnergy((float) (energy() - healed / 3));
				mob.heal((float) (healed * UnitKind.SCALE));
				server.sendParticles(ParticleTypes.HAPPY_VILLAGER, mob.getX(), mob.getY() + mob.getBbHeight(), mob.getZ(), 2, 0.3, 0.2, 0.3, 0);
			});
	}

	/** For the command view: construction, training or research progress (0-1000) and the queue. */
	private void syncProgress() {
		int progress = 0;
		if (constructTicks > 0) progress = 1000 - (int) (1000L * constructTicks / Math.max(1, constructTotal));
		else if (abilities.changingMode()) progress = abilities.changeProgress(); // an upgrade (orbital, fortress, add-on), lift-off
		else if (!queue.isEmpty()) progress = (int) (1000L * trainTicks / Math.max(1, queue.get(0).economy().trainTicks()));
		else if (research != null) progress = -(int) (1000L * researchTicks / Math.max(1, dev.starmine.race.Research.byId(research) == null ? 1
			: dev.starmine.race.Research.byId(research).ticks(1)));
		this.entityData.set(PROGRESS, progress);
		this.entityData.set(QUEUE_IDS, constructTicks > 0 ? "construct" : abilities.changingMode() ? "morph"
			: String.join(",", queue.stream().map(k -> k.id).toList()) + (research != null ? (queue.isEmpty() ? "" : ",") + "research:" + research : ""));
	}

	/** Debug: the goals running now. */
	public String goalsRunning() {
		return this.goalSelector.getAvailableGoals().stream().filter(net.minecraft.world.entity.ai.goal.WrappedGoal::isRunning)
			.map(goal -> goal.getGoal() instanceof DroneWork work ? work.toString() : goal.getGoal().getClass().getSimpleName()).toList().toString();
	}

	public int progress() {
		return this.entityData.get(PROGRESS);
	}

	public String queueIds() {
		return this.entityData.get(QUEUE_IDS);
	}

	/** Whether it casts this (an ability's id, or "gather" for a worker) by itself: both sides. */
	public boolean autocasts(String id) {
		return java.util.Arrays.asList(this.entityData.get(AUTOCAST).split(",")).contains(id);
	}

	/** Its autocasts to the clients (when they changed). */
	public void syncAutocast() {
		List<String> ids = new ArrayList<>();
		for (dev.starmine.ability.Ability ability : dev.starmine.ability.Ability.of(kind)) if (abilities.autocasts(ability)) ids.add(ability.id);
		if (kind.worker() && abilities.autoGather) ids.add("gather");
		String now = String.join(",", ids);
		if (!now.equals(this.entityData.get(AUTOCAST))) this.entityData.set(AUTOCAST, now);
	}
}
