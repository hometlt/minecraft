package dev.starmine.ability;

import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The nuclear missile (owner 17.10). Armed at a ghost academy ({@link dev.starmine.race.Research#NUKE}), it leaves
 * the academy's silo when a ghost calls it: rises out of sight, comes down over the ghost's red dot and lands as the
 * ghost's 10-second guiding ends ({@link TerranAbilities#nuke}). It dies with a small blast, as a unit's shot does,
 * when it is shot down, when it meets something on its way down, and when the ghost stops guiding it (an order, the
 * ghost killed). A wild ghost with no academy calls one from the sky.
 */
public class NukeMissile extends Entity {
	/** Ticks it rises out of the silo, and it falls at the end of the guiding. */
	static final int RISE = 70, FALL = 60;
	static final double RISE_SPEED = 0.8, FALL_SPEED = 1.0;

	private @Nullable UUID ghost;
	private Vec3 target = Vec3.ZERO;
	private int age, flight;
	private float health = 20;

	public NukeMissile(EntityType<? extends NukeMissile> type, Level level) {
		super(type, level);
		this.noPhysics = true;
		this.setNoGravity(true);
	}

	/**
	 * Launches one for the ghost onto `point`: from the nearest ghost academy of its base, or (none) high over the
	 * ghost. `flight` is the guiding time, at whose end it lands.
	 */
	static NukeMissile launch(ServerLevel level, StarUnit ghost, Vec3 point, int flight) {
		NukeMissile missile = new NukeMissile(StarMine.NUKE_MISSILE, level);
		missile.ghost = ghost.getUUID();
		missile.target = point;
		missile.flight = flight;
		Hive base = Abilities.base(ghost);
		StarUnit academy = base == null ? null : level.getEntitiesOfClass(StarUnit.class, ghost.getBoundingBox().inflate(256),
			unit -> unit.kind == UnitKind.GHOST_ACADEMY && base.id.equals(unit.hiveId()) && !unit.underConstruction())
			.stream().min(Comparator.comparingDouble(unit -> unit.distanceToSqr(ghost))).orElse(null);
		Vec3 from = academy != null ? academy.position().add(0, academy.getBbHeight() + 0.5, 0) : ghost.position().add(0, 60, 0);
		missile.moveTo(from.x, from.y, from.z, 0, -90);
		level.addFreshEntity(missile);
		if (academy != null) {
			level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, from.x, from.y, from.z, 30, 1.2, 0.3, 1.2, 0.02);
			level.playSound(null, from.x, from.y, from.z, SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.HOSTILE, 4f, 0.5f);
		}
		return missile;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
	}

	@Override
	public void tick() {
		super.tick();
		if (!(level() instanceof ServerLevel level)) {
			Vec3 at = position(); // its flame
			level().addParticle(ParticleTypes.FLAME, at.x, at.y + (getXRot() < 0 ? -0.6 : 0.6), at.z, 0, 0, 0);
			return;
		}
		age++;
		if (!guided(level) && age < flight - 2) {
			blast(level, false); // the ghost let it go: it dies where it is
			return;
		}
		int fallFrom = Math.max(RISE + 1, flight - FALL);
		if (age <= RISE) { // up out of the silo
			move(level, new Vec3(0, RISE_SPEED, 0));
			setXRot(-90);
		} else if (age < fallFrom) { // out of sight: over the target
			Vec3 over = new Vec3(target.x, target.y + FALL * FALL_SPEED, target.z);
			if (position().distanceToSqr(over) > 1) moveTo(over.x, over.y, over.z, 0, 90);
		} else { // down onto the red dot
			setXRot(90);
			if (getY() - target.y <= FALL_SPEED + 0.5) {
				setPos(target.x, target.y, target.z);
				land(level);
				return;
			}
			move(level, new Vec3(0, -FALL_SPEED, 0));
		}
		if (age > flight + 40) discard(); // never lost in the sky
	}

	/** Moves by `step`, blowing up small on whatever it meets (a block, a flyer) on its way. */
	private void move(ServerLevel level, Vec3 step) {
		Vec3 from = position(), to = from.add(step);
		HitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
		boolean low = to.y - target.y <= FALL_SPEED + 0.5;
		if (hit instanceof net.minecraft.world.phys.BlockHitResult block && block.getType() == HitResult.Type.BLOCK && !(step.y < 0 && low)
			&& !level.getBlockState(block.getBlockPos()).is(net.minecraft.world.level.block.Blocks.BARRIER)) { // the world's edge-markers are no obstacle
			setPos(hit.getLocation());
			blast(level, true);
			return;
		}
		Entity ghostBody = ghostUnit(level);
		boolean struck = !level.getEntities(this, new AABB(from, to).inflate(0.4),
			other -> other.isAlive() && other.isPickable() && other != ghostBody && !(other instanceof NukeMissile)).isEmpty();
		if (struck && age > 6 && to.y - target.y > 8) { // a flyer in its way (not what stands on the red dot: that is its target)
			setPos(to);
			blast(level, true);
			return;
		}
		setPos(to);
	}

	/** Whether its ghost still guides it (alive, its nuke channel on). */
	private boolean guided(ServerLevel level) {
		StarUnit unit = ghostUnit(level);
		return unit != null && unit.isAlive() && unit.abilities.channel == Ability.NUKE;
	}

	private @Nullable StarUnit ghostUnit(ServerLevel level) {
		return ghost != null && level.getEntity(ghost) instanceof StarUnit unit ? unit : null;
	}

	/** The nuke lands on its red dot. */
	private void land(ServerLevel level) {
		StarUnit unit = ghostUnit(level);
		if (unit != null) TerranAbilities.nuke(unit, level, target);
		else level.explode(this, target.x, target.y + 0.5, target.z, 4f, Level.ExplosionInteraction.NONE);
		discard();
	}

	/** It dies before landing: a small blast, as a unit's missile (no blocks broken). */
	private void blast(ServerLevel level, boolean loud) {
		Vec3 at = position();
		level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 3, 0.4, 0.4, 0.4, 0);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y, at.z, 12, 0.5, 0.5, 0.5, 0.02);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, loud ? 2f : 1f, 1.4f);
		StarUnit unit = ghostUnit(level);
		DamageSource source = unit != null ? unit.attackSource() : level.damageSources().explosion(this, null);
		for (net.minecraft.world.entity.LivingEntity other : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, getBoundingBox().inflate(2)))
			other.hurt(source, (float) (20 * UnitKind.SCALE));
		discard();
	}

	public boolean falling() {
		return getXRot() > 0;
	}

	@Override
	public boolean isPickable() {
		return true;
	}

	@Override
	public boolean hurt(DamageSource source, float amount) {
		if (level().isClientSide || isRemoved() || isInvulnerableTo(source)) return false;
		health -= amount;
		if (health <= 0 && level() instanceof ServerLevel level) blast(level, true); // shot down
		return true;
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return distance < 256 * 256;
	}

	@Override
	protected void readAdditionalSaveData(CompoundTag tag) {
		if (tag.hasUUID("Ghost")) ghost = tag.getUUID("Ghost");
		target = new Vec3(tag.getDouble("TX"), tag.getDouble("TY"), tag.getDouble("TZ"));
		age = tag.getInt("Age");
		flight = tag.getInt("Flight");
		health = tag.contains("Health") ? tag.getFloat("Health") : 20;
	}

	@Override
	protected void addAdditionalSaveData(CompoundTag tag) {
		if (ghost != null) tag.putUUID("Ghost", ghost);
		tag.putDouble("TX", target.x);
		tag.putDouble("TY", target.y);
		tag.putDouble("TZ", target.z);
		tag.putInt("Age", age);
		tag.putInt("Flight", flight);
		tag.putFloat("Health", health);
	}

	/** Where it will land (tests). */
	public Vec3 target() {
		return target;
	}
}
