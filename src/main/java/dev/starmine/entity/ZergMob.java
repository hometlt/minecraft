package dev.starmine.entity;

import dev.starmine.StarMine;
import java.util.List;
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
 */
public class ZergMob extends Monster implements RangedAttackMob {
	public final ZergKind kind;
	/** Client only: tick of the current attack swing and which attack clip it plays. */
	public int attackStartTick = Integer.MIN_VALUE / 2;
	public int attackVariant;
	private int lifeLeft;

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
		return Monster.createMonsterAttributes()
			.add(Attributes.MAX_HEALTH, kind.health)
			.add(Attributes.MOVEMENT_SPEED, kind.speed)
			.add(Attributes.FLYING_SPEED, kind.speed * 1.5)
			.add(Attributes.ATTACK_DAMAGE, Math.max(0.5, kind.damage))
			.add(Attributes.FOLLOW_RANGE, 32.0)
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
		this.goalSelector.addGoal(0, new FloatGoal(this));
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
		this.goalSelector.addGoal(5, kind.flying ? new WaterAvoidingRandomFlyingGoal(this, 0.8) : new WaterAvoidingRandomStrollGoal(this, 0.8));
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
		if (this.level().isClientSide || !this.isAlive()) return;
		if (lifeLeft > 0 && --lifeLeft == 0) this.kill();
		if (kind == ZergKind.QUEEN && this.tickCount % 200 == 0) transfuse();
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
