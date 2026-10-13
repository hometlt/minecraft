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
 * One zerg mob; its {@link ZergKind} (found from the entity type) decides everything.
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
public class ZergMob extends Monster implements RangedAttackMob {
	public final ZergKind kind;
	/** Client only: tick of the current attack swing and which attack clip it plays. */
	public int attackStartTick = Integer.MIN_VALUE / 2;
	public int attackVariant;
	private int lifeLeft;
	/** The hive this zerg belongs to (its hatchery's UUID) and where that hatchery stands. */
	private UUID hiveId;
	private BlockPos home;
	/** A cocoon's future: what hatches, how many, in how many ticks. */
	private ZergKind cocoonKind;
	private int cocoonCount, cocoonTicks;
	/** Ticks this ground zerg has had a target it has no path to (the overlord ferries such zerg). */
	private int unreachable;
	/** An overseer is near: invisible players and creatures are seen (see the visibility mixin). */
	private boolean overseerNear;
	/**
	 * Drone: the resources it carries to the hatchery and the blocks they came from (shown in front of
	 * it, dropped if it dies), plus fill — dug stone and earth it builds bridges and steps with.
	 */
	public int cargoMinerals, cargoVespene, cargoDiamonds, cargoBlocks;
	private final List<ItemStack> cargoItems = new ArrayList<>();
	private ItemStack fill = ItemStack.EMPTY;
	private static final EntityDataAccessor<ItemStack> CARRIED = SynchedEntityData.defineId(ZergMob.class, EntityDataSerializers.ITEM_STACK);
	/** Drone defending itself: ticks since it took up its attacker. */
	private int defendTicks;

	public ZergMob(EntityType<? extends Monster> type, Level level) {
		super(type, level);
		this.kind = StarMine.kindOf(type);
		this.xpReward = (int) Math.min(20, 1 + kind.health / 5);
		this.lifeLeft = kind.lifeTicks();
		if (kind.flying) {
			this.moveControl = new FlyingMoveControl(this, 10, true);
			this.setNoGravity(true);
		}
	}

	public static AttributeSupplier.Builder attributes(ZergKind kind) {
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
			.add(Attributes.FOLLOW_RANGE, kind == ZergKind.DRONE ? 64.0 : 32.0)
			.add(Attributes.KNOCKBACK_RESISTANCE, Math.min(1.0, kind.health / 150))
			.add(Attributes.STEP_HEIGHT, 1.0);
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
		ZergKind kind = StarMine.kindOf(this.getType());
		if (kind.structure()) return;
		this.goalSelector.addGoal(0, new FloatGoal(this));
		if (kind == ZergKind.LARVA) {
			this.goalSelector.addGoal(4, new ZergGoals.ReturnHome(this, 3));
			this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.6));
			return;
		}
		if (kind == ZergKind.OVERLORD) this.goalSelector.addGoal(1, new ZergGoals.Transport(this));
		if (kind == ZergKind.QUEEN) this.goalSelector.addGoal(3, new ZergGoals.PlantTumor(this));
		if (kind == ZergKind.DRONE) this.goalSelector.addGoal(3, new DroneWork(this));
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
		if (kind.attack == ZergKind.Attack.NONE) return;
		this.targetSelector.addGoal(1, new HurtByTargetGoal(this, ZergMob.class).setAlertOthers(ZergMob.class));
		if (kind.aggressive) {
			this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
			this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, IronGolem.class, true));
		}
	}

	@Override
	public boolean isAlliedTo(Entity other) {
		return other instanceof ZergMob || super.isAlliedTo(other);
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
		if (kind == ZergKind.QUEEN && this.tickCount % 200 == 0) transfuse();
		if (kind == ZergKind.HATCHERY) HiveLogic.tick(this, server);
		if (kind == ZergKind.EGG && cocoonKind != null && --cocoonTicks <= 0) hatchCocoon(server);
		if (kind.hoverHeight() > 0 && !isPassenger() && (this.getTarget() == null || kind.attack == ZergKind.Attack.NONE)) keepAloft();
		if (kind == ZergKind.DRONE) defendOnly();
		if (kind.structure()) pushOffTop();
		if (this.tickCount % 10 == 0) creepEffects(server);
		if (this.tickCount % 20 == 0) {
			trackReachability();
			overseerNear = !this.level().getEntitiesOfClass(ZergMob.class, this.getBoundingBox().inflate(20), mob -> mob.kind == ZergKind.OVERSEER).isEmpty();
			if (kind == ZergKind.OVERSEER) revealInvisible();
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

	/** Zerg buildings shove off whatever stands on top of them (owner: nobody camps on a hatchery). */
	private void pushOffTop() {
		AABB box = this.getBoundingBox();
		AABB top = new AABB(box.minX, box.maxY - 0.5, box.minZ, box.maxX, box.maxY + 2, box.maxZ);
		for (Entity other : this.level().getEntities(this, top, entity -> !(entity instanceof ZergMob zerg && zerg.kind.structure()))) {
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

	/** A drone takes a block it broke: resources go to its load, plain stone and earth to its fill. */
	public void collect(BlockState state) {
		HiveResources.Yield yield = HiveResources.yieldOf(state);
		Item item = state.getBlock().asItem();
		if (yield.any()) {
			cargoMinerals += yield.minerals();
			cargoVespene += yield.vespene();
			cargoDiamonds += yield.diamonds();
			cargoBlocks++;
			if (item != Items.AIR) {
				boolean merged = false;
				for (ItemStack stack : cargoItems)
					if (stack.is(item) && stack.getCount() < stack.getMaxStackSize()) {
						stack.grow(1);
						merged = true;
						break;
					}
				if (!merged) cargoItems.add(new ItemStack(item));
			}
		} else if (item != Items.AIR && item instanceof BlockItem && !state.is(BlockTags.LEAVES) && fill.getCount() < DroneWork.FILL) {
			if (fill.isEmpty()) fill = new ItemStack(item);
			else fill.grow(1);
		}
		updateCarried();
	}

	public int fillCount() {
		return fill.getCount();
	}

	/** Puts a fill block (bridge, step); false when there is none or the place is taken. */
	public boolean placeFill(BlockPos pos) {
		if (fill.isEmpty() || !(fill.getItem() instanceof BlockItem blockItem)) return false;
		BlockState there = this.level().getBlockState(pos);
		if (!there.canBeReplaced()) return false;
		BlockState placed = blockItem.getBlock().defaultBlockState();
		this.level().setBlockAndUpdate(pos, placed);
		this.playSound(placed.getSoundType().getPlaceSound(), 1.0f, 1.0f);
		this.swing(InteractionHand.MAIN_HAND);
		fill.shrink(1);
		updateCarried();
		return true;
	}

	/** The load is delivered; the fill stays with the drone. */
	public void clearCargo() {
		cargoMinerals = cargoVespene = cargoDiamonds = cargoBlocks = 0;
		cargoItems.clear();
		updateCarried();
	}

	/** What the client draws in front of the drone: its load, else its fill. */
	private void updateCarried() {
		ItemStack shown = !cargoItems.isEmpty() ? cargoItems.get(0).copyWithCount(Math.max(1, cargoBlocks)) : fill.copy();
		this.entityData.set(CARRIED, shown);
	}

	public ItemStack carried() {
		return this.entityData.get(CARRIED);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(CARRIED, ItemStack.EMPTY);
	}

	@Override
	protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
		super.dropCustomDeathLoot(level, source, recentlyHit);
		for (ItemStack stack : cargoItems) this.spawnAtLocation(stack.copy());
		if (!fill.isEmpty()) this.spawnAtLocation(fill.copy());
		cargoItems.clear();
		fill = ItemStack.EMPTY;
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
			this.heal((float) (0.27 * ZergKind.SCALE * (onCreep ? 4 : 1)));
		if (onCreep && this.getTarget() == null && kind.aggressive && kind.attack != ZergKind.Attack.NONE && this.tickCount % 20 == 0) {
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
		return overseerNear || kind == ZergKind.OVERSEER;
	}

	public UUID hiveId() {
		return hiveId;
	}

	public BlockPos home() {
		return home;
	}

	/** Joins a hive: counted in its members and supply, kept in the world, returns home when idle. */
	public void joinHive(Hive hive) {
		this.hiveId = hive.id;
		this.home = hive.hatchery;
		if (!kind.structure() || kind == ZergKind.EGG) hive.join(kind);
		this.setPersistenceRequired();
	}

	public void becomeCocoon(ZergKind kind, int count, int ticks) {
		this.cocoonKind = kind;
		this.cocoonCount = count;
		this.cocoonTicks = ticks;
	}

	private void hatchCocoon(ServerLevel server) {
		Hive hive = HiveSavedData.get(server.getServer()).get(hiveId);
		ZergKind born = cocoonKind;
		int count = cocoonCount;
		cocoonKind = null;
		if (hive != null) HiveLogic.hatch(this, server, hive, born, count);
		this.discard();
	}

	@Override
	public void remove(Entity.RemovalReason reason) {
		if (kind == ZergKind.DRONE && !this.level().isClientSide) DroneWork.releaseAll(this.getUUID());
		if (reason.shouldDestroy() && this.level() instanceof ServerLevel server && hiveId != null) {
			HiveSavedData data = HiveSavedData.get(server.getServer());
			Hive hive = data.get(hiveId);
			if (kind == ZergKind.HATCHERY) {
				if (hiveId.equals(this.getUUID()) && hive != null) {
					if (hive.foundation) HiveTerrain.ruin(server, hive.hatchery, HiveTerrain.HATCHERY_FOUNDATION);
					CreepGrowth.forget(server, hive.hatchery);
					data.remove(hiveId);
				}
			} else if (hive != null) {
				hive.leave(kind);
				if (kind == ZergKind.EGG && cocoonKind != null) hive.born(cocoonKind, cocoonCount);
				data.setDirty();
			}
		}
		super.remove(reason);
	}

	@Override
	public void addAdditionalSaveData(CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		if (hiveId != null) tag.putUUID("StarMineHive", hiveId);
		if (home != null) tag.put("StarMineHome", NbtUtils.writeBlockPos(home));
		if (cargoBlocks > 0) tag.putIntArray("StarMineCargo", new int[] {cargoMinerals, cargoVespene, cargoDiamonds, cargoBlocks});
		if (!cargoItems.isEmpty() || !fill.isEmpty()) {
			ListTag items = new ListTag();
			for (ItemStack stack : cargoItems) items.add(stack.save(this.registryAccess()));
			tag.put("StarMineCargoItems", items);
			if (!fill.isEmpty()) tag.put("StarMineFill", fill.save(this.registryAccess()));
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
		home = NbtUtils.readBlockPos(tag, "StarMineHome").orElse(null);
		int[] cargo = tag.getIntArray("StarMineCargo");
		if (cargo.length == 4) {
			cargoMinerals = cargo[0];
			cargoVespene = cargo[1];
			cargoDiamonds = cargo[2];
			cargoBlocks = cargo[3];
		}
		cargoItems.clear();
		for (Tag item : tag.getList("StarMineCargoItems", Tag.TAG_COMPOUND))
			ItemStack.parse(this.registryAccess(), item).ifPresent(cargoItems::add);
		fill = tag.contains("StarMineFill") ? ItemStack.parse(this.registryAccess(), tag.get("StarMineFill")).orElse(ItemStack.EMPTY) : ItemStack.EMPTY;
		if (kind == ZergKind.DRONE) updateCarried();
		if (tag.contains("StarMineCocoon")) {
			try {
				cocoonKind = ZergKind.valueOf(tag.getString("StarMineCocoon"));
				cocoonCount = tag.getInt("StarMineCocoonCount");
				cocoonTicks = tag.getInt("StarMineCocoonTicks");
			} catch (IllegalArgumentException ignored) {
				cocoonKind = null;
			}
		}
		if (kind == ZergKind.HATCHERY) hiveId = this.getUUID();
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
		return kind == ZergKind.OVERLORD ? null : super.getControllingPassenger();
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		if (kind != ZergKind.OVERLORD || !(passenger instanceof ZergMob zerg)) return false;
		int used = 0;
		for (Entity rider : this.getPassengers()) if (rider instanceof ZergMob riding) used += riding.kind.economy().cargo();
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
		List<ZergMob> wounded = this.level().getEntitiesOfClass(ZergMob.class, this.getBoundingBox().inflate(8),
			mob -> mob != this && mob.isAlive() && mob.getHealth() < mob.getMaxHealth());
		wounded.stream().min((a, b) -> Float.compare(a.getHealth() / a.getMaxHealth(), b.getHealth() / b.getMaxHealth())).ifPresent(mob -> {
			this.swing(InteractionHand.MAIN_HAND);
			mob.heal((float) (75 * ZergKind.SCALE));
			if (this.level() instanceof ServerLevel server)
				server.sendParticles(ParticleTypes.HEART, mob.getX(), mob.getY() + mob.getBbHeight(), mob.getZ(), 5, 0.4, 0.3, 0.4, 0.0);
		});
	}

	@Override
	public boolean doHurtTarget(Entity target) {
		if (kind.attack == ZergKind.Attack.EXPLODE) {
			explode();
			return true;
		}
		boolean hurt = super.doHurtTarget(target);
		if (hurt && kind.attack == ZergKind.Attack.SPLASH) {
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
			other -> other != this && other.isAlive() && !(other instanceof ZergMob)
				&& !(other instanceof Player player && (player.isCreative() || player.isSpectator())));
	}

	@Override
	public void performRangedAttack(LivingEntity target, float power) {
		this.swing(InteractionHand.MAIN_HAND);
		this.playSound(StarMine.sounds(kind).attack(), 1.0f, 0.9f + this.random.nextFloat() * 0.2f);
		switch (kind.attack) {
			case LINE -> spineLine(target);
			case PULL -> {
				Vec3 pull = this.position().subtract(target.position()).normalize().scale(1.6).add(0, 0.5, 0);
				target.setDeltaMovement(pull);
				target.hurtMarked = true;
			}
			case SPAWN_LOCUSTS -> {
				for (int i = 0; i < 2; i++) summon(StarMine.type(ZergKind.LOCUST), this.position().add(this.random.nextGaussian(), 0, this.random.nextGaussian()), target);
			}
			default -> {
				ZergSpit spit = new ZergSpit(this.level(), this, kind);
				double dx = target.getX() - this.getX();
				double dy = target.getY(0.4) - spit.getY();
				double dz = target.getZ() - this.getZ();
				double flat = Math.sqrt(dx * dx + dz * dz);
				spit.shoot(dx, dy + flat * (kind == ZergKind.BROOD_LORD ? 0.0 : 0.15), dz, 1.4f, 4.0f);
				this.level().addFreshEntity(spit);
			}
		}
	}

	/** Lurker: spines burst out of the ground along a line toward the target, hitting all in it. */
	private void spineLine(LivingEntity target) {
		Vec3 from = this.position(), direction = target.position().subtract(from).multiply(1, 0, 1).normalize();
		for (double step = 1; step <= kind.range; step += 0.5) {
			Vec3 point = from.add(direction.scale(step));
			if (this.level() instanceof ServerLevel server)
				server.sendParticles(ParticleTypes.CRIT, point.x, point.y + 0.3, point.z, 3, 0.2, 0.3, 0.2, 0.05);
			for (LivingEntity other : enemiesAround(point, 0.6))
				if (other.invulnerableTime == 0) other.hurt(this.damageSources().mobAttack(this), (float) kind.damage);
		}
	}

	/** A zerg this one calls (broodlings from eggs, locusts), already hunting its target. */
	public static void summon(EntityType<ZergMob> type, Vec3 at, LivingEntity target) {
		Level level = target.level();
		ZergMob mob = type.create(level);
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
}
