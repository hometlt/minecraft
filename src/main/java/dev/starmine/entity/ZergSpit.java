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
	/** How it looks in flight ({@link UnitKind.Shot}), synced: the client draws its model and trail by it. */
	private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> SHOT =
		net.minecraft.network.syncher.SynchedEntityData.defineId(ZergSpit.class, net.minecraft.network.syncher.EntityDataSerializers.INT);
	private UnitKind kind = UnitKind.ROACH;
	/**
	 * A player's shot (owner 17.10: the infantry's weapons, dev.starmine.gear): its damage instead of a unit's, a bonus
	 * against armored targets, a slow (the marauder's concussive grenades) or a blast (the reaper's KD8 charge). Not
	 * saved: a shot in flight over a reload hits as a unit's would.
	 */
	private float playerDamage = -1, armoredBonus;
	private boolean slows, blast;

	/** A player's shot looking like `look`'s, showing `shown`, with its own damage and effect. */
	public static ZergSpit fromPlayer(Level level, LivingEntity shooter, UnitKind look, ItemStack shown, float damage, float armoredBonus,
			boolean slows, boolean blast) {
		ZergSpit spit = new ZergSpit(level, shooter, look);
		spit.setItem(shown.copyWithCount(1));
		spit.entityData.set(SHOT, UnitKind.Shot.GRENADE.ordinal());
		spit.setPos(shooter.getX(), shooter.getEyeY() - 0.2, shooter.getZ());
		spit.playerDamage = damage;
		spit.armoredBonus = armoredBonus;
		spit.slows = slows;
		spit.blast = blast;
		return spit;
	}

	/** What it flies at (server side, not saved: after a reload it flies on straight). */
	private @org.jetbrains.annotations.Nullable LivingEntity target;

	public ZergSpit(EntityType<? extends ZergSpit> type, Level level) {
		super(type, level);
	}

	public ZergSpit(Level level, LivingEntity shooter, UnitKind kind) {
		super(StarMine.SPIT, shooter, level);
		this.kind = kind;
		this.setItem(new ItemStack(kind.missile()));
		UnitKind.Shot shot = kind.shot();
		this.entityData.set(SHOT, (shot == null ? UnitKind.Shot.ACID : shot).ordinal());
		this.setPos(shooter.getX(), shooter.getY(0.6), shooter.getZ());
	}

	@Override
	protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(SHOT, 0);
	}

	public UnitKind.Shot shot() {
		int i = this.entityData.get(SHOT);
		return UnitKind.Shot.values()[Math.max(0, Math.min(UnitKind.Shot.values().length - 1, i))];
	}

	/** Speed a shot of this style flies at (blocks a tick): rockets and plasma faster than spit. */
	public static float speed(UnitKind kind) {
		UnitKind.Shot shot = kind.shot();
		return shot == UnitKind.Shot.ROCKET ? 2.0f : shot == UnitKind.Shot.PLASMA ? 1.8f : shot == UnitKind.Shot.GRENADE ? 1.5f : 1.4f;
	}

	/** Follow this target (not a brood lord's egg). */
	public void aimAt(LivingEntity target) {
		if (kind != UnitKind.BROOD_LORD) this.target = target;
	}

	@Override
	public void tick() {
		if (this.level().isClientSide) trail();
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
		return blast ? 0.05 : kind == UnitKind.BROOD_LORD ? 0.03 : 0.01; // kind may still be null during construction
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
		if (playerDamage >= 0) { // a player's weapon
			if (blast || owner != null && dev.starmine.faction.Faction.allied(owner, target)) return; // a charge hurts by its blast
			float bonus = target instanceof net.minecraft.world.entity.LivingEntity living && Trait.has(living, Trait.ARMORED) ? armoredBonus : 0;
			target.hurt(owner instanceof net.minecraft.world.entity.player.Player player ? this.damageSources().playerAttack(player)
				: this.damageSources().mobProjectile(this, owner), playerDamage + bonus);
			if (slows) dev.starmine.ability.Status.apply(target, dev.starmine.ability.Status.SLOW, 30); // SC2 concussive shells: 1.07 s
			return;
		}
		StarUnit zerg = owner instanceof StarUnit unit ? unit : null;
		if (owner != null && dev.starmine.faction.Faction.allied(owner, target)) return;
		double bonus = zerg != null ? zerg.attackBonus() + dev.starmine.ability.Passives.bonus(zerg, target) : 0;
		double damage = zerg != null ? zerg.weapon().damage() : kind.damage;
		if (target.hurt(this.damageSources().mobProjectile(this, owner), (float) (damage + bonus)) && zerg != null)
			dev.starmine.ability.Passives.onHit(zerg, target);
		if (kind.attack == UnitKind.Attack.SLOW) dev.starmine.ability.Status.apply(target, dev.starmine.ability.Status.SLOW, 60);
	}

	/** SC2's KD8 charge: a blast that hurts and throws back what is round it (not its thrower's side); blocks stay. */
	private void kd8(ServerLevel level) {
		LivingEntity owner = this.getOwner() instanceof LivingEntity living ? living : null;
		net.minecraft.world.phys.Vec3 at = this.position();
		for (LivingEntity near : level.getEntitiesOfClass(LivingEntity.class, this.getBoundingBox().inflate(KD8_RADIUS),
				other -> other.isAlive() && other != owner && !(owner != null && dev.starmine.faction.Faction.allied(owner, other)))) {
			near.hurt(owner instanceof net.minecraft.world.entity.player.Player player ? this.damageSources().playerAttack(player)
				: this.damageSources().explosion(this, owner), playerDamage);
			net.minecraft.world.phys.Vec3 push = near.position().subtract(at).multiply(1, 0, 1);
			if (push.lengthSqr() > 1e-4) near.push(push.normalize().x * 1.2, 0.35, push.normalize().z * 1.2);
		}
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 1, 0, 0, 0, 0);
		level.playSound(null, at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(), net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.3f);
	}

	/** How far a KD8 charge's blast reaches (SC2: 1.5 units). */
	public static final double KD8_RADIUS = 1.5 * UnitKind.BLOCKS_PER_UNIT;

	@Override
	protected void onHit(HitResult hit) {
		super.onHit(hit);
		if (this.level().isClientSide) return;
		if (blast && this.level() instanceof ServerLevel server) kd8(server);
		if ((kind == UnitKind.BROOD_LORD || kind == UnitKind.STUKOV) && this.getOwner() instanceof StarUnit lord && lord.getTarget() != null)
			for (int i = 0; i < 2; i++) StarUnit.summon(StarMine.type(UnitKind.BROODLING), this.position(), lord.getTarget());
		if (this.level() instanceof ServerLevel server) switch (shot()) {
			case ROCKET, GRENADE -> {
				server.sendParticles(ParticleTypes.EXPLOSION, this.getX(), this.getY(), this.getZ(), 1, 0, 0, 0, 0);
				server.playSound(null, this.getX(), this.getY(), this.getZ(), net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(),
					net.minecraft.sounds.SoundSource.HOSTILE, 0.4f, 1.4f);
			}
			case PLASMA -> server.sendParticles(ParticleTypes.END_ROD, this.getX(), this.getY(), this.getZ(), 8, 0.15, 0.15, 0.15, 0.08);
			case CORROSIVE -> server.sendParticles(ParticleTypes.WITCH, this.getX(), this.getY(), this.getZ(), 8, 0.2, 0.2, 0.2, 0.05);
			default -> server.sendParticles(ParticleTypes.ITEM_SLIME, this.getX(), this.getY(), this.getZ(), 6, 0.2, 0.2, 0.2, 0.05);
		}
		this.discard();
	}

	/** The client's trail behind a shot: smoke behind a rocket, sparks behind plasma, drips behind spit. */
	private void trail() {
		Level level = this.level();
		switch (shot()) {
			case ROCKET -> {
				level.addParticle(ParticleTypes.SMOKE, this.getX(), this.getY(), this.getZ(), 0, 0.01, 0);
				level.addParticle(ParticleTypes.FLAME, this.getX(), this.getY(), this.getZ(), 0, 0, 0);
			}
			case GRENADE -> level.addParticle(ParticleTypes.SMOKE, this.getX(), this.getY(), this.getZ(), 0, 0.01, 0);
			case PLASMA -> level.addParticle(ParticleTypes.END_ROD, this.getX(), this.getY(), this.getZ(), 0, 0, 0);
			case CORROSIVE -> level.addParticle(ParticleTypes.WITCH, this.getX(), this.getY(), this.getZ(), 0, 0, 0);
			case EGG -> {
			}
			default -> {
				if (this.tickCount % 2 == 0) level.addParticle(ParticleTypes.ITEM_SLIME, this.getX(), this.getY(), this.getZ(), 0, 0, 0);
			}
		}
	}
}
