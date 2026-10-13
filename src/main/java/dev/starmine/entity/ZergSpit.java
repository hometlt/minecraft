package dev.starmine.entity;

import dev.starmine.StarMine;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * A zerg missile: acid, needle spines, glaives, brood lord eggs. Damage comes from the shooter's
 * kind; the infestor's slows and roots, the brood lord's hatches two broodlings where it lands.
 * Drawn as an item until SC2 missile models are imported.
 */
public class ZergSpit extends ThrowableItemProjectile {
	private ZergKind kind = ZergKind.ROACH;

	public ZergSpit(EntityType<? extends ZergSpit> type, Level level) {
		super(type, level);
	}

	public ZergSpit(Level level, LivingEntity shooter, ZergKind kind) {
		super(StarMine.SPIT, shooter, level);
		this.kind = kind;
		this.setItem(new ItemStack(kind.missile()));
		this.setPos(shooter.getX(), shooter.getY(0.6), shooter.getZ());
	}

	/** Called by the base constructor before {@link #kind} is set: the synced item is set after. */
	@Override
	protected Item getDefaultItem() {
		return Items.SLIME_BALL;
	}

	@Override
	protected double getDefaultGravity() {
		return kind == ZergKind.BROOD_LORD ? 0.03 : 0.01; // kind may still be null during construction
	}

	@Override
	protected void onHitEntity(EntityHitResult hit) {
		super.onHitEntity(hit);
		if (!(hit.getEntity() instanceof LivingEntity target) || target instanceof ZergMob) return;
		LivingEntity owner = this.getOwner() instanceof LivingEntity living ? living : null;
		double bonus = owner instanceof ZergMob zerg ? zerg.attackBonus() : 0;
		target.hurt(this.damageSources().mobProjectile(this, owner), (float) (kind.damage + bonus));
		if (kind.attack == ZergKind.Attack.SLOW) target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 4));
	}

	@Override
	protected void onHit(HitResult hit) {
		super.onHit(hit);
		if (this.level().isClientSide) return;
		if (kind == ZergKind.BROOD_LORD && this.getOwner() instanceof ZergMob lord && lord.getTarget() != null)
			for (int i = 0; i < 2; i++) ZergMob.summon(StarMine.type(ZergKind.BROODLING), this.position(), lord.getTarget());
		if (this.level() instanceof ServerLevel server)
			server.sendParticles(ParticleTypes.ITEM_SLIME, this.getX(), this.getY(), this.getZ(), 6, 0.2, 0.2, 0.2, 0.05);
		this.discard();
	}
}
