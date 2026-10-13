package dev.starmine.entity;

import dev.starmine.StarMine;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Zergling: a fast, fragile melee swarmer.
 *
 * SC2 stats (35 HP, 5 damage every 0.497 s, speed 4.13) scaled to Minecraft: 10 health, 1.5 damage
 * every 10 ticks, faster than a zombie. Death lasts {@link #DEATH_TICKS} so the death animation
 * plays out before the body is removed. The client keeps the start tick and variant of the current
 * attack swing for the renderer.
 */
public class Zergling extends Monster {
	/** Ticks the body stays after death: the death clip length, not vanilla's 20. */
	public static final int DEATH_TICKS = 50;
	/** Client only: tick count when the current attack animation started, and its variant. */
	public int attackStartTick = Integer.MIN_VALUE / 2;
	public int attackVariant;

	public Zergling(EntityType<? extends Monster> type, Level level) {
		super(type, level);
		this.xpReward = 3;
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Monster.createMonsterAttributes()
			.add(Attributes.MAX_HEALTH, 35 * StarMine.HEALTH_PER_SC2_HP)
			.add(Attributes.MOVEMENT_SPEED, 0.36)
			.add(Attributes.ATTACK_DAMAGE, 1.5)
			.add(Attributes.FOLLOW_RANGE, 24.0)
			.add(Attributes.STEP_HEIGHT, 1.0);
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(0, new FloatGoal(this));
		this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.15, false) {
			@Override
			protected int getAttackInterval() {
				return this.adjustedTickDelay(10);
			}
		});
		this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.8));
		this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0f));
		this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
		this.targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers(Zergling.class));
		this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
		this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, IronGolem.class, true));
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
	public boolean doHurtTarget(Entity target) {
		boolean hurt = super.doHurtTarget(target);
		if (hurt) this.playSound(StarMine.Sounds.ZERGLING_ATTACK, 0.8f, 0.9f + this.random.nextFloat() * 0.2f);
		return hurt;
	}

	@Override
	protected void tickDeath() {
		++this.deathTime;
		if (this.deathTime >= DEATH_TICKS && !this.level().isClientSide() && !this.isRemoved()) {
			this.level().broadcastEntityEvent(this, (byte) 60);
			this.remove(Entity.RemovalReason.KILLED);
		}
	}

	@Override
	protected SoundEvent getAmbientSound() {
		return StarMine.Sounds.ZERGLING_AMBIENT;
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return StarMine.Sounds.ZERGLING_HURT;
	}

	@Override
	protected SoundEvent getDeathSound() {
		return StarMine.Sounds.ZERGLING_DEATH;
	}

	@Override
	protected void playStepSound(BlockPos pos, BlockState state) {
		// Zerglings scuttle quietly; SC2 has no footstep set for them.
	}
}
