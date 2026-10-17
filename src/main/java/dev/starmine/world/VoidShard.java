package dev.starmine.world;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A void shard (owner 17.10): a dark crystal floating over a pillar on the Void's islands, its beam on Amun.
 * While any shard stands Amun cannot be hurt and they heal him ({@link Amun#hurt}); break them all first —
 * by hand, with arrows or with your army (they are foes to everyone, and fight back with nothing).
 */
public class VoidShard extends Mob implements Enemy, VoidForces.Member {
	public static final float HEALTH = 60;
	/** How far a shard's beam reaches Amun. */
	public static final double REACH = 192;
	/** Ticks until an invasion's shard sends out its next wave ({@link AmunInvasion#shardTick}). */
	int waveIn = 100;
	private static final EntityDataAccessor<Integer> AMUN = SynchedEntityData.defineId(VoidShard.class, EntityDataSerializers.INT);

	public VoidShard(EntityType<? extends VoidShard> type, Level level) {
		super(type, level);
		setNoGravity(true);
		setPersistenceRequired();
		noCulling = true;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(AMUN, -1);
	}

	/** The entity id of the Amun its beam is on, or -1. */
	public int amun() {
		return entityData.get(AMUN);
	}

	@Override
	public void tick() {
		super.tick();
		setDeltaMovement(Vec3.ZERO);
		if (level() instanceof ServerLevel server && tickCount % 20 == 0) {
			Amun near = Amun.find(server, position(), REACH);
			entityData.set(AMUN, near == null ? -1 : near.getId());
			if (!VoidWorld.is(server)) AmunInvasion.shardTick(this, server); // owner 17.10: a shard of his invasion darkens the land round it
		}
		if (level().isClientSide && random.nextInt(3) == 0)
			level().addParticle(ParticleTypes.REVERSE_PORTAL, getRandomX(0.8), getRandomY(), getRandomZ(0.8), 0, 0.05, 0);
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	protected void doPush(Entity entity) {
	}

	@Override
	public boolean removeWhenFarAway(double distance) {
		return false;
	}

	@Override
	public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
		return false;
	}

	@Override
	public void die(DamageSource source) {
		super.die(source);
		if (!(level() instanceof ServerLevel server)) return;
		server.sendParticles(ParticleTypes.EXPLOSION_EMITTER, getX(), getY() + 1, getZ(), 1, 0, 0, 0, 0);
		server.sendParticles(ParticleTypes.REVERSE_PORTAL, getX(), getY() + 1, getZ(), 80, 1, 1, 1, 0.3);
		playSound(SoundEvents.GENERIC_EXPLODE.value(), 4.0f, 0.7f);
		// owner 17.10: a broken shard leaves void shards (a void gate is made of them)
		spawnAtLocation(new net.minecraft.world.item.ItemStack(VoidGate.SHARD, 2 + random.nextInt(3)));
		if (!VoidWorld.is(server)) return; // one of the invasion's: Amun's own shards are the Void's
		VoidWorld.State state = VoidWorld.State.get(server.getServer());
		state.shards = Math.max(0, state.shards - 1);
		state.setDirty();
		int left = state.shards;
		for (ServerPlayer player : server.players())
			player.displayClientMessage(left == 0 ? Component.translatable("message.starmine.void_shards_gone")
				: Component.translatable("message.starmine.void_shard_broken", left), left == 0);
	}

	@Override
	public void addAdditionalSaveData(CompoundTag tag) {
		super.addAdditionalSaveData(tag);
	}
}
