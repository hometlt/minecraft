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
 *
 * Like an SC2 missile it follows its target and flies through the shooter's side: a thrown item with
 * vanilla's scatter missed a walking target at range and hit the first zerg in front of the shooter
 * (no damage, gone) — a hydralisk behind its roaches did a quarter of its damage on the balance stand.
 * A brood lord's egg is lobbed and lands where it falls.
 */
public class ZergSpit extends ThrowableItemProjectile {
	private UnitKind kind = UnitKind.ROACH;
	/** What it flies at (server side, not saved: after a reload it flies on straight). */
	private @org.jetbrains.annotations.Nullable LivingEntity target;

	public ZergSpit(EntityType<? extends ZergSpit> type, Level level) {
		super(type, level);
	}

	public ZergSpit(Level level, LivingEntity shooter, UnitKind kind) {
		super(StarMine.SPIT, shooter, level);
		this.kind = kind;
		this.setItem(new ItemStack(kind.missile()));
		this.setPos(shooter.getX(), shooter.getY(0.6), shooter.getZ());
	}

	/** Follow this target (not a brood lord's egg). */
	public void aimAt(LivingEntity target) {
		if (kind != UnitKind.BROOD_LORD) this.target = target;
	}

	@Override
	public void tick() {
		if (!this.level().isClientSide && target != null) {
			if (!target.isAlive() || target.level() != this.level()) target = null;
			else {
				net.minecraft.world.phys.Vec3 to = target.position().add(0, target.getBbHeight() * 0.5, 0).subtract(this.position());
				double speed = Math.max(1.0, this.getDeltaMovement().length());
				if (to.lengthSqr() > 1e-4) this.setDeltaMovement(to.normalize().scale(speed));
			}
		}
		super.tick();
	}

	/** It flies through the shooter's own side (SC2 missiles do not stop at allies). */
	@Override
	protected boolean canHitEntity(net.minecraft.world.entity.Entity entity) {
		return super.canHitEntity(entity) && !(this.getOwner() != null && dev.starmine.faction.Faction.allied(this.getOwner(), entity));
	}

	/** Called by the base constructor before {@link #kind} is set: the synced item is set after. */
	@Override
	protected Item getDefaultItem() {
		return Items.SLIME_BALL;
	}

	@Override
	protected double getDefaultGravity() {
		return kind == UnitKind.BROOD_LORD ? 0.03 : 0.01; // kind may still be null during construction
	}

	/**
	 * It hurts what it hits unless that is of the shooter's side (StarMine units of other sides too: a
	 * missile that spared every StarMine unit left the zerg's spitters harmless against terrans and
	 * protoss); the shooter's upgrades and SC2 bonus damage count, and its on-hit passives.
	 */
	@Override
	protected void onHitEntity(EntityHitResult hit) {
		super.onHitEntity(hit);
		if (!(hit.getEntity() instanceof LivingEntity target)) return;
		LivingEntity owner = this.getOwner() instanceof LivingEntity living ? living : null;
		StarUnit zerg = owner instanceof StarUnit unit ? unit : null;
		if (owner != null && dev.starmine.faction.Faction.allied(owner, target)) return;
		double bonus = zerg != null ? zerg.attackBonus() + dev.starmine.ability.Passives.bonus(zerg, target) : 0;
		double damage = zerg != null ? zerg.weapon().damage() : kind.damage;
		if (target.hurt(this.damageSources().mobProjectile(this, owner), (float) (damage + bonus)) && zerg != null)
			dev.starmine.ability.Passives.onHit(zerg, target);
		if (kind.attack == UnitKind.Attack.SLOW) dev.starmine.ability.Status.apply(target, dev.starmine.ability.Status.SLOW, 60);
	}

	@Override
	protected void onHit(HitResult hit) {
		super.onHit(hit);
		if (this.level().isClientSide) return;
		if (kind == UnitKind.BROOD_LORD && this.getOwner() instanceof StarUnit lord && lord.getTarget() != null)
			for (int i = 0; i < 2; i++) StarUnit.summon(StarMine.type(UnitKind.BROODLING), this.position(), lord.getTarget());
		if (this.level() instanceof ServerLevel server)
			server.sendParticles(ParticleTypes.ITEM_SLIME, this.getX(), this.getY(), this.getZ(), 6, 0.2, 0.2, 0.2, 0.05);
		this.discard();
	}
}
