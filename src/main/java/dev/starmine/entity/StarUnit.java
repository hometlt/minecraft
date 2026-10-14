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
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RangedAttackGoal;
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
	private BlockPos buildSite;
	private int constructTicks;
	/** Drone fleeing from an armed attacker until this game time. */
	private long fleeUntil;
	/** On a raid: where the party goes, and the game time it ends (then everyone goes home). */
	private BlockPos raidTarget;
	private long raidUntil;

	/** A terran unit's order from its player (see {@link TerranGoals.Orders}). */
	public enum Order { NONE, MOVE, ATTACK_MOVE, PATROL, HOLD }

	private Order order = Order.NONE;
	private Vec3 orderPos, patrolFrom;
	/** An SCV's work order: gather at a block, or bring its cargo home (taken by its work goal). */
	public enum Work { NONE, GATHER, RETURN }

	private Work work = Work.NONE;
	private BlockPos workPos;
	/** An SCV building this building (its UUID), and whether a building had a builder at its last check. */
	private UUID constructing;
	private boolean builderNear;
	/** What the cargo is (resource label) for the base's tally. */
	public String cargoLabel;
	/** Terran production: kinds queued (paid), ticks into the first, where new units go. */
	private final List<UnitKind> queue = new ArrayList<>();
	private int trainTicks;
	private Vec3 rally;
	/** Engineering bay research: "weapons" or "armor" and ticks left. */
	private String research;
	private int researchTicks;
	public static final int QUEUE = 5;
	/** Synced for the command view: the base's player, progress (0-1000) and queue (kind ids). */
	private static final EntityDataAccessor<java.util.Optional<UUID>> OWNER =
		SynchedEntityData.defineId(StarUnit.class, EntityDataSerializers.OPTIONAL_UUID);
	private static final EntityDataAccessor<Integer> PROGRESS = SynchedEntityData.defineId(StarUnit.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<String> QUEUE_IDS = SynchedEntityData.defineId(StarUnit.class, EntityDataSerializers.STRING);

	public StarUnit(EntityType<? extends Monster> type, Level level) {
		super(type, level);
		this.kind = StarMine.kindOf(type);
		this.xpReward = (int) Math.min(20, 1 + kind.health / 5);
		this.lifeLeft = kind.lifeTicks();
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
			.add(Attributes.FOLLOW_RANGE, kind == UnitKind.DRONE || kind == UnitKind.SCV ? 64.0 : kind.race == UnitKind.Race.TERRAN ? kind.sight() + 4 : 32.0)
			.add(Attributes.KNOCKBACK_RESISTANCE, Math.min(1.0, kind.health / 150))
			// zerglings leap up 2 blocks (owner); the pathfinder plans with the step height
			.add(Attributes.STEP_HEIGHT, kind == UnitKind.ZERGLING || kind == UnitKind.REAPER ? 2.0 : 1.0)
			.add(Attributes.JUMP_STRENGTH, kind == UnitKind.ZERGLING ? 0.62 : 0.42);
	}

	@Override
	protected PathNavigation createNavigation(Level level) {
		if (!StarMine.kindOf(this.getType()).flying) return super.createNavigation(level);
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
		if (kind.race == UnitKind.Race.TERRAN) {
			TerranGoals.register(this, kind, this.goalSelector, this.targetSelector);
			return;
		}
		if (kind == UnitKind.LARVA) {
			this.goalSelector.addGoal(4, new ZergGoals.ReturnHome(this, 3));
			this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.6));
			return;
		}
		if (kind == UnitKind.OVERLORD) this.goalSelector.addGoal(1, new ZergGoals.Transport(this));
		if (kind != UnitKind.DRONE && kind.attack != UnitKind.Attack.NONE) this.goalSelector.addGoal(3, new ZergGoals.Raid(this));
		if (kind == UnitKind.QUEEN) this.goalSelector.addGoal(3, new ZergGoals.PlantTumor(this));
		if (kind == UnitKind.DRONE) this.goalSelector.addGoal(3, new DroneWork(this));
		else this.goalSelector.addGoal(4, new ZergGoals.ReturnHome(this, 24));
		switch (kind.attack) {
			case NONE -> {
			}
			case MELEE, SPLASH, EXPLODE -> this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.15, false) {
				@Override
				protected int getAttackInterval() {
					return this.adjustedTickDelay(kind.interval);
				}
			});
			default -> this.goalSelector.addGoal(2, new RangedAttackGoal(this, 1.0, kind.interval, kind.interval + 10, (float) kind.range));
		}
		this.goalSelector.addGoal(5, kind.hoverHeight() > 0 ? new ZergGoals.HoverWander(this)
			: kind.flying ? new WaterAvoidingRandomFlyingGoal(this, 0.8) : new WaterAvoidingRandomStrollGoal(this, 0.8));
		this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0f));
		this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
		if (kind.attack == UnitKind.Attack.NONE) return;
		this.targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers(StarUnit.class));
		if (kind.aggressive) {
			this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
			this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, IronGolem.class, true));
			// owner: zerg are hostile to every creature (animals, villagers, terrans), never to each other
			this.targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, LivingEntity.class, 10, true, false,
				other -> !sameRace(other) && !(other instanceof Player) && !(other instanceof net.minecraft.world.entity.decoration.ArmorStand)));
		}
	}

	@Override
	public boolean isAlliedTo(Entity other) {
		return sameRace(other) || super.isAlliedTo(other);
	}

	/** Units of one race never fight each other. */
	public boolean sameRace(Entity other) {
		return other instanceof StarUnit unit && unit.kind.race == kind.race;
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
		super.aiStep();
		if (!(this.level() instanceof ServerLevel server) || !this.isAlive()) return;
		if (lifeLeft > 0 && --lifeLeft == 0) this.kill();
		if (kind == UnitKind.QUEEN && this.tickCount % 200 == 0) transfuse();
		if (kind == UnitKind.HATCHERY) HiveLogic.tick(this, server);
		if (kind == UnitKind.COMMAND_CENTER) dev.starmine.colony.TerranLogic.tick(this, server);
		if (kind.race == UnitKind.Race.TERRAN && constructTicks > 0 && this.tickCount % 10 == 0) builderNear = builderNear(server);
		if (constructTicks > 0 && (kind.race != UnitKind.Race.TERRAN || builderNear) && --constructTicks == 0) finishConstruction(server);
		if (kind.defender() && constructTicks == 0) crawlerAttack(server);
		if (kind.race == UnitKind.Race.TERRAN && kind.building() && constructTicks == 0) {
			produce(server);
			researchTick(server);
		}
		if (kind == UnitKind.MEDIVAC && this.tickCount % 10 == 0) heal(server);
		if (kind.race == UnitKind.Race.TERRAN && kind.structure() && this.tickCount % 5 == 0) syncProgress();
		if (this.tickCount % 100 == 7 && hiveId != null && !kind.structure()) applyUpgrades(server);
		if (kind == UnitKind.EGG && nest != null && cocoonTicks > DORMANT / 2 && this.tickCount % 20 == 0) wakeNestEgg(server);
		if (kind == UnitKind.EGG && cocoonKind != null && --cocoonTicks <= 0) hatchCocoon(server);
		if (kind.hoverHeight() > 0 && !isPassenger() && (this.getTarget() == null || kind.attack == UnitKind.Attack.NONE)) keepAloft();
		if (hiveId == null && this.tickCount == 5 && !kind.structure()) adoptByNearestHive(server);
		if (kind == UnitKind.DRONE) defendOnly();
		if (kind.structure()) pushOffTop();
		if (this.tickCount % 10 == 0) creepEffects(server);
		if (this.tickCount % 20 == 0) {
			trackReachability();
			overseerNear = !this.level().getEntitiesOfClass(StarUnit.class, this.getBoundingBox().inflate(20), mob -> mob.kind == UnitKind.OVERSEER).isEmpty();
			if (kind == UnitKind.OVERSEER) revealInvisible();
		}
	}

	/** Flying kinds stay above their hover height over the ground below. */
	private void keepAloft() {
		double floor = ZergGoals.ground(this, this.getX(), this.getZ()) + kind.hoverHeight() - 1;
		if (this.getY() < floor) {
			Vec3 motion = this.getDeltaMovement();
			this.setDeltaMovement(motion.x, Math.max(motion.y, 0.12), motion.z);
		}
	}

	/** Ground zerg note how long their target has been out of reach (for the overlord's ferry). */
	private void trackReachability() {
		if (kind.flying || this.getTarget() == null || this.isPassenger()) {
			unreachable = 0;
			return;
		}
		Path path = this.getNavigation().getPath();
		boolean far = this.distanceToSqr(this.getTarget()) > 9;
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
	 * A zerg that appears on creep without a hive (a spawn egg) joins the nearest hive of this
	 * dimension within 64 blocks: drones work for it, warriors guard it.
	 */
	private void adoptByNearestHive(ServerLevel server) {
		if (!CreepTouch.onCreep(this)) return;
		String dimension = server.dimension().location().toString();
		HiveSavedData data = HiveSavedData.get(server.getServer());
		Hive nearest = null;
		double best = 64 * 64;
		for (Hive hive : data.all()) {
			if (!hive.dimension.equals(dimension)) continue;
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
		boolean hurt = super.hurt(source, amount);
		if (hurt && hiveId != null && this.level() instanceof ServerLevel server && source.getEntity() instanceof LivingEntity attacker
			&& !sameRace(attacker)) {
			HiveLogic.alarm(server, hiveId, this.position(), attacker);
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
	}

	/** A new building grows for its SC2 build time before it counts for the hive. */
	public void startConstruction(int ticks) {
		this.constructTicks = Math.max(1, ticks);
		this.setHealth(Math.max(1, this.getMaxHealth() * 0.1f));
	}

	public boolean underConstruction() {
		return constructTicks > 0;
	}

	/** A terran building grows only while an SCV of its base builds it (within 8 blocks). */
	private boolean builderNear(ServerLevel server) {
		return !server.getEntitiesOfClass(StarUnit.class, this.getBoundingBox().inflate(8),
			mob -> mob.kind == UnitKind.SCV && mob.isAlive() && this.getUUID().equals(mob.constructing)).isEmpty();
	}

	public void setConstructing(UUID building) {
		this.constructing = building;
	}

	public UUID constructing() {
		return constructing;
	}

	private void finishConstruction(ServerLevel server) {
		this.setHealth(this.getMaxHealth());
		Hive hive = HiveSavedData.get(server.getServer()).get(hiveId);
		if (hive != null) {
			hive.born(kind, 1);
			hive.join(kind);
			HiveSavedData.get(server.getServer()).setDirty();
		}
	}

	/**
	 * Crawlers defend the hive: a spine crawler impales the nearest ground foe within range (SC2 25
	 * damage every 1.32 s), a spore crawler shoots the nearest flying one (SC2 15 every 0.61 s).
	 */
	private void crawlerAttack(ServerLevel server) {
		if (this.tickCount % kind.interval != 0) return;
		LivingEntity foe = null;
		double best = kind.range * kind.range;
		boolean terran = kind.race == UnitKind.Race.TERRAN;
		for (LivingEntity other : server.getEntitiesOfClass(LivingEntity.class, this.getBoundingBox().inflate(kind.range),
			entity -> entity.isAlive() && !sameRace(entity) && !(entity instanceof net.minecraft.world.entity.decoration.ArmorStand)
				&& !(entity instanceof Player player && (terran || player.isCreative() || player.isSpectator()))
				&& (!terran || entity instanceof StarUnit || entity instanceof net.minecraft.world.entity.monster.Enemy))) {
			boolean flying = other instanceof net.minecraft.world.entity.FlyingMob || other.isFallFlying()
				|| other instanceof net.minecraft.world.entity.monster.Phantom || other instanceof net.minecraft.world.entity.monster.Blaze
				|| other instanceof net.minecraft.world.entity.monster.Vex || !other.onGround() && other.getY() > this.getY() + 3;
			if (flying ? !kind.antiAir() : !kind.antiGround()) continue;
			double d = other.distanceToSqr(this);
			if (d < best) {
				best = d;
				foe = other;
			}
		}
		if (foe == null) return;
		this.swing(InteractionHand.MAIN_HAND);
		this.getLookControl().setLookAt(foe);
		foe.hurt(this.damageSources().mobAttack(this), (float) (kind.damage + attackBonus()));
		if (terran) tracer(server, foe);
		this.playSound(StarMine.sounds(kind).attack(), 1.0f, 1.0f);
	}

	/** The hive's evolution chamber upgrades: +0.5 damage and +2 armor per level. */
	private void applyUpgrades(ServerLevel server) {
		Hive hive = HiveSavedData.get(server.getServer()).get(hiveId);
		if (hive == null) return;
		modifier(Attributes.ATTACK_DAMAGE, UPGRADE_ATTACK, hive.attackLevel * 0.5);
		modifier(Attributes.ARMOR, UPGRADE_ARMOR, hive.armorLevel * 2.0);
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

	private static final ResourceLocation UPGRADE_ATTACK = StarMine.id("upgrade_attack"), UPGRADE_ARMOR = StarMine.id("upgrade_armor");

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
		builder.define(PROGRESS, 0);
		builder.define(QUEUE_IDS, "");
	}

	@Override
	protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
		super.dropCustomDeathLoot(level, source, recentlyHit);
		if (!valuable.isEmpty()) this.spawnAtLocation(valuable.copy());
		for (ItemStack slot : fillSlots) this.spawnAtLocation(slot.copy());
		valuable = ItemStack.EMPTY;
		fillSlots.clear();
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

	public int unreachableTicks() {
		return unreachable;
	}

	public void clearUnreachable() {
		unreachable = 0;
	}

	/** Overseer: invisible creatures and players around it glow for everyone (SC2 detection). */
	private void revealInvisible() {
		for (LivingEntity other : this.level().getEntitiesOfClass(LivingEntity.class, this.getBoundingBox().inflate(16), LivingEntity::isInvisible))
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

	public BlockPos home() {
		return home;
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
				HiveLogic.spawnNest(server, hive, born, this.position().add(this.random.nextGaussian() * 0.3, 0, this.random.nextGaussian() * 0.3), nest);
		} else if (hive != null) HiveLogic.hatch(this, server, hive, born, count);
		this.discard();
	}

	@Override
	public void remove(Entity.RemovalReason reason) {
		if (kind.worker() && !this.level().isClientSide) DroneWork.releaseAll(this.getUUID());
		if (reason.shouldDestroy() && this.level() instanceof ServerLevel server && hiveId != null && !queue.isEmpty()) {
			Hive hive = HiveSavedData.get(server.getServer()).get(hiveId);
			if (hive != null) for (UnitKind queued : queue) hive.born(queued, 1); // their supply is free again
			queue.clear();
		}
		if (reason.shouldDestroy() && this.level() instanceof ServerLevel server && hiveId != null) {
			HiveSavedData data = HiveSavedData.get(server.getServer());
			Hive hive = data.get(hiveId);
			if (kind == UnitKind.HATCHERY) {
				if (hiveId.equals(this.getUUID()) && hive != null) {
					if (hive.foundation) HiveTerrain.ruin(server, hive.hatchery, HiveTerrain.HATCHERY_FOUNDATION);
					CreepGrowth.forget(server, hive.hatchery);
					data.remove(hiveId);
				}
			} else if (hive != null && nest == null) {
				if (kind.building()) {
					if (constructTicks > 0) hive.born(kind, 1);
					else hive.leave(kind);
					HiveTerrain.ruin(server, this.blockPosition(), HiveTerrain.foundation(kind));
				} else {
					hive.leave(kind);
				}
				if (kind == UnitKind.EGG && cocoonKind != null) hive.born(cocoonKind, cocoonCount);
				data.setDirty();
			}
		}
		super.remove(reason);
	}

	@Override
	public void addAdditionalSaveData(CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		if (hiveId != null) tag.putUUID("StarMineHive", hiveId);
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
			tag.putInt("StarMineTrain", trainTicks);
		}
		if (rally != null) tag.put("StarMineRally", NbtUtils.writeBlockPos(BlockPos.containing(rally)));
		if (research != null) {
			tag.putString("StarMineResearch", research);
			tag.putInt("StarMineResearchTicks", researchTicks);
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
	}

	@Override
	public void readAdditionalSaveData(CompoundTag tag) {
		super.readAdditionalSaveData(tag);
		if (tag.hasUUID("StarMineHive")) hiveId = tag.getUUID("StarMineHive");
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
		if (kind == UnitKind.HATCHERY) hiveId = this.getUUID();
		constructTicks = tag.getInt("StarMineConstruct");
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
		trainTicks = tag.getInt("StarMineTrain");
		rally = NbtUtils.readBlockPos(tag, "StarMineRally").map(Vec3::atBottomCenterOf).orElse(null);
		research = tag.contains("StarMineResearch") ? tag.getString("StarMineResearch") : null;
		researchTicks = tag.getInt("StarMineResearchTicks");
		cargoLabel = tag.contains("StarMineCargoLabel") ? tag.getString("StarMineCargoLabel") : null;
		if (tag.contains("StarMineBuild")) {
			try {
				buildKind = UnitKind.valueOf(tag.getString("StarMineBuild"));
				buildSite = NbtUtils.readBlockPos(tag, "StarMineBuildSite").orElse(null);
			} catch (IllegalArgumentException ignored) {
				buildKind = null;
			}
		}
	}

	@Override
	public boolean removeWhenFarAway(double distance) {
		return hiveId == null && !kind.structure() && super.removeWhenFarAway(distance);
	}

	@Override
	public boolean isPushable() {
		return !kind.structure() && super.isPushable();
	}

	@Override
	public boolean canBeCollidedWith() {
		return kind.structure() || super.canBeCollidedWith();
	}

	@Override
	public boolean shouldDespawnInPeaceful() {
		return !kind.structure() && hiveId == null;
	}

	/** Zerg ride an overlord as cargo; nobody steers it but its own AI. */
	@Override
	public LivingEntity getControllingPassenger() {
		return kind == UnitKind.OVERLORD ? null : super.getControllingPassenger();
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		if (kind != UnitKind.OVERLORD || !(passenger instanceof StarUnit zerg)) return false;
		int used = 0;
		for (Entity rider : this.getPassengers()) if (rider instanceof StarUnit riding) used += riding.kind.economy().cargo();
		return used + zerg.kind.economy().cargo() <= ZergGoals.Transport.CAPACITY;
	}

	/** Cargo hangs under the overlord's body, spread a little around its centre. */
	@Override
	protected void positionRider(Entity passenger, Entity.MoveFunction move) {
		int index = this.getPassengers().indexOf(passenger);
		double angle = index * 2.4, spread = index == 0 ? 0 : 0.6;
		move.accept(passenger, this.getX() + Math.cos(angle) * spread, this.getY() - passenger.getBbHeight() + 0.6, this.getZ() + Math.sin(angle) * spread);
	}


	/** Queen: heal the most wounded zerg nearby (Transfusion). */
	private void transfuse() {
		List<StarUnit> wounded = this.level().getEntitiesOfClass(StarUnit.class, this.getBoundingBox().inflate(8),
			mob -> mob != this && mob.isAlive() && mob.getHealth() < mob.getMaxHealth());
		wounded.stream().min((a, b) -> Float.compare(a.getHealth() / a.getMaxHealth(), b.getHealth() / b.getMaxHealth())).ifPresent(mob -> {
			this.swing(InteractionHand.MAIN_HAND);
			mob.heal((float) (75 * UnitKind.SCALE));
			if (this.level() instanceof ServerLevel server)
				server.sendParticles(ParticleTypes.HEART, mob.getX(), mob.getY() + mob.getBbHeight(), mob.getZ(), 5, 0.4, 0.3, 0.4, 0.0);
		});
	}

	@Override
	public boolean doHurtTarget(Entity target) {
		if (kind.attack == UnitKind.Attack.EXPLODE) {
			explode();
			return true;
		}
		boolean hurt = super.doHurtTarget(target);
		if (hurt && kind.attack == UnitKind.Attack.SPLASH) {
			for (LivingEntity other : enemiesAround(target.position(), 2.0))
				if (other != target) other.hurt(this.damageSources().mobAttack(this), (float) kind.damage * 0.5f);
		}
		if (hurt) this.playSound(StarMine.sounds(kind).attack(), 1.0f, 0.9f + this.random.nextFloat() * 0.2f);
		return hurt;
	}

	/** Baneling: acid burst on everything around, then it dies (its death is the burst). */
	private void explode() {
		this.swing(InteractionHand.MAIN_HAND);
		for (LivingEntity other : enemiesAround(this.position(), 2.5))
			other.hurt(this.damageSources().mobAttack(this), (float) kind.damage);
		if (this.level() instanceof ServerLevel server)
			server.sendParticles(ParticleTypes.ITEM_SLIME, this.getX(), this.getY() + 0.5, this.getZ(), 40, 1.2, 0.5, 1.2, 0.1);
		this.kill();
	}

	private List<LivingEntity> enemiesAround(Vec3 centre, double radius) {
		return this.level().getEntitiesOfClass(LivingEntity.class, new AABB(centre, centre).inflate(radius),
			other -> other != this && other.isAlive() && !sameRace(other)
				&& !(other instanceof Player player && (kind.race == UnitKind.Race.TERRAN || player.isCreative() || player.isSpectator())));
	}

	@Override
	public void performRangedAttack(LivingEntity target, float power) {
		this.swing(InteractionHand.MAIN_HAND);
		this.playSound(StarMine.sounds(kind).attack(), 1.0f, 0.9f + this.random.nextFloat() * 0.2f);
		if (kind.race == UnitKind.Race.TERRAN && kind.attack == UnitKind.Attack.RANGED) {
			target.hurt(this.damageSources().mobAttack(this), (float) (kind.damage + attackBonus()));
			if (this.level() instanceof ServerLevel server) tracer(server, target);
			return;
		}
		switch (kind.attack) {
			case LINE -> spineLine(target);
			case PULL -> {
				Vec3 pull = this.position().subtract(target.position()).normalize().scale(1.6).add(0, 0.5, 0);
				target.setDeltaMovement(pull);
				target.hurtMarked = true;
			}
			case SPAWN_LOCUSTS -> {
				for (int i = 0; i < 2; i++) summon(StarMine.type(UnitKind.LOCUST), this.position().add(this.random.nextGaussian(), 0, this.random.nextGaussian()), target);
			}
			default -> {
				ZergSpit spit = new ZergSpit(this.level(), this, kind);
				double dx = target.getX() - this.getX();
				double dy = target.getY(0.4) - spit.getY();
				double dz = target.getZ() - this.getZ();
				double flat = Math.sqrt(dx * dx + dz * dz);
				spit.shoot(dx, dy + flat * (kind == UnitKind.BROOD_LORD ? 0.0 : 0.15), dz, 1.4f, 4.0f);
				this.level().addFreshEntity(spit);
			}
		}
	}

	/** A terran shot: a line of sparks from the muzzle to the target and a puff where it hits. */
	private void tracer(ServerLevel server, LivingEntity target) {
		Vec3 from = this.position().add(0, this.getBbHeight() * 0.6, 0), to = target.position().add(0, target.getBbHeight() * 0.5, 0);
		int steps = (int) Math.min(24, from.distanceTo(to) * 2);
		for (int i = 1; i <= steps; i++) {
			Vec3 point = from.lerp(to, i / (double) steps);
			server.sendParticles(ParticleTypes.ELECTRIC_SPARK, point.x, point.y, point.z, 1, 0, 0, 0, 0);
		}
		server.sendParticles(ParticleTypes.SMOKE, to.x, to.y, to.z, 3, 0.1, 0.1, 0.1, 0.01);
	}

	/** Lurker: spines burst out of the ground along a line toward the target, hitting all in it. */
	private void spineLine(LivingEntity target) {
		Vec3 from = this.position(), direction = target.position().subtract(from).multiply(1, 0, 1).normalize();
		for (double step = 1; step <= kind.range; step += 0.5) {
			Vec3 point = from.add(direction.scale(step));
			if (this.level() instanceof ServerLevel server)
				server.sendParticles(kind == UnitKind.HELLION ? ParticleTypes.FLAME : ParticleTypes.CRIT, point.x, point.y + 0.3, point.z, 3, 0.2, 0.3, 0.2, 0.05);
			for (LivingEntity other : enemiesAround(point, 0.6))
				if (other.invulnerableTime == 0) other.hurt(this.damageSources().mobAttack(this), (float) kind.damage);
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
		if (kind.flying) this.setNoGravity(false);
		if (this.isVehicle()) this.ejectPassengers();
		if (this.deathTime >= kind.deathTicks() && !this.level().isClientSide() && !this.isRemoved()) {
			this.level().broadcastEntityEvent(this, (byte) 60);
			this.remove(Entity.RemovalReason.KILLED);
		}
	}

	@Override
	public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
		return !kind.flying && super.causeFallDamage(distance, multiplier, source);
	}

	@Override
	protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
		if (!kind.flying) super.checkFallDamage(y, onGround, state, pos);
	}

	@Override
	protected SoundEvent getAmbientSound() {
		return StarMine.sounds(kind).ambient();
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

	/** The order is done: the unit stays where it got to. */
	void finishOrder() {
		this.order = Order.NONE;
		this.orderPos = null;
		this.home = this.blockPosition();
	}

	/** Patrol: turn back toward where it came from. */
	void turnPatrol() {
		Vec3 back = patrolFrom;
		patrolFrom = orderPos;
		orderPos = back;
	}

	/** A worker's order: gather at a block, or bring the cargo home. */
	public void commandWork(Work work, BlockPos pos) {
		command(Order.NONE, null);
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

	public java.util.Optional<UUID> owner() {
		return this.entityData.get(OWNER);
	}

	public List<UnitKind> queue() {
		return queue;
	}

	/** Queues a unit this building trains: paid now, supply reserved; false when it cannot. */
	public boolean train(UnitKind unit) {
		if (!(this.level() instanceof ServerLevel server) || constructTicks > 0 || !kind.trains().contains(unit) || queue.size() >= QUEUE) return false;
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
			if (hive != null) {
				int level = (research.equals("weapons") ? hive.attackLevel : hive.armorLevel) + 1;
				hive.minerals += researchCost(level);
				hive.vespene += researchCost(level);
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

	/** The first queued unit trains; done, it walks out to the rally point (or next to the building). */
	private void produce(ServerLevel server) {
		if (queue.isEmpty()) return;
		UnitKind unit = queue.get(0);
		if (++trainTicks < unit.economy().trainTicks()) return;
		Hive hive = HiveSavedData.get(server.getServer()).get(hiveId);
		queue.remove(0);
		trainTicks = 0;
		if (hive == null) return;
		hive.born(unit, 1);
		double angle = this.random.nextDouble() * Math.PI * 2, radius = kind.width / 2 + 1;
		Vec3 at = new Vec3(this.getX() + Math.cos(angle) * radius, this.getY(), this.getZ() + Math.sin(angle) * radius);
		BlockPos ground = BlockPos.containing(at.x, this.getY() + 2, at.z);
		for (int i = 0; i < 6 && server.getBlockState(ground.below()).isAir(); i++) ground = ground.below();
		StarUnit born = HiveLogic.spawn(server, hive, unit, unit.flying ? at.add(0, 2, 0) : Vec3.atBottomCenterOf(ground), null);
		if (born != null) {
			if (rally != null) born.command(Order.MOVE, rally);
			else born.home = born.blockPosition();
		}
		HiveSavedData.get(server.getServer()).setDirty();
	}

	/** SC2 engineering bay upgrade cost by level (minerals = vespene): 100, 175, 250. */
	public static int researchCost(int level) {
		return 25 + 75 * level;
	}

	/** Starts infantry weapons or armor at the engineering bay (levels 2 and 3 need an armory). */
	public boolean startResearch(String what) {
		if (!(this.level() instanceof ServerLevel server) || kind != UnitKind.ENGINEERING_BAY || constructTicks > 0 || research != null) return false;
		Hive hive = HiveSavedData.get(server.getServer()).get(hiveId);
		if (hive == null) return false;
		int level = (what.equals("weapons") ? hive.attackLevel : what.equals("armor") ? hive.armorLevel : 9) + 1;
		int cost = researchCost(level);
		if (level > 3 || (level > 1 && hive.count(UnitKind.ARMORY) == 0) || hive.minerals < cost || hive.vespene < cost) return false;
		hive.minerals -= cost;
		hive.vespene -= cost;
		research = what;
		researchTicks = (int) Math.round((114 + 22 * (level - 1)) * 20);
		HiveSavedData.get(server.getServer()).setDirty();
		return true;
	}

	private void researchTick(ServerLevel server) {
		if (research == null || --researchTicks > 0) return;
		Hive hive = HiveSavedData.get(server.getServer()).get(hiveId);
		if (hive != null) {
			if (research.equals("weapons")) hive.attackLevel++;
			else hive.armorLevel++;
			HiveSavedData.get(server.getServer()).setDirty();
		}
		research = null;
	}

	/** Medivac: heals the most wounded bio unit of its race within 6 blocks (SC2 12.6 hit points a second). */
	private void heal(ServerLevel server) {
		server.getEntitiesOfClass(StarUnit.class, this.getBoundingBox().inflate(6),
				mob -> mob.isAlive() && mob.kind.biological() && sameRace(mob) && mob.getHealth() < mob.getMaxHealth())
			.stream().min((a, b) -> Float.compare(a.getHealth() / a.getMaxHealth(), b.getHealth() / b.getMaxHealth()))
			.ifPresent(mob -> {
				mob.heal((float) (12.6 * UnitKind.SCALE / 2));
				server.sendParticles(ParticleTypes.HAPPY_VILLAGER, mob.getX(), mob.getY() + mob.getBbHeight(), mob.getZ(), 2, 0.3, 0.2, 0.3, 0);
			});
	}

	/** For the command view: construction, training or research progress (0-1000) and the queue. */
	private void syncProgress() {
		int progress = 0;
		if (constructTicks > 0) progress = 1000 - (int) (1000L * constructTicks / Math.max(1, kind.economy().trainTicks()));
		else if (!queue.isEmpty()) progress = (int) (1000L * trainTicks / Math.max(1, queue.get(0).economy().trainTicks()));
		else if (research != null) progress = -(int) (1000L * researchTicks / Math.max(1, (114 + 44) * 20));
		this.entityData.set(PROGRESS, progress);
		this.entityData.set(QUEUE_IDS, constructTicks > 0 ? "construct"
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
}
