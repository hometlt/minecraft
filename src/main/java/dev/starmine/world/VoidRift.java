package dev.starmine.world;

import dev.starmine.entity.Spots;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.HiveLogic;
import java.util.List;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The shared spherical gate: a player-owned linked pair, or a one-way rift Amun opens near his foes. Every {@link #EVERY} ticks troops of all three races
 * come out of it — Amun's, not their race's ({@link VoidForces#TAG}) — up to {@link #CAP} of them round it at once,
 * stronger the longer it stands. It stands until broken, or until Amun dies. A rift of his invasion of the worlds sends
 * its troops only near a player, as strong as the invasion has grown ({@link AmunInvasion#wave}).
 */
public class VoidRift extends Mob {
	private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> SIZE = net.minecraft.network.syncher.SynchedEntityData.defineId(VoidRift.class, net.minecraft.network.syncher.EntityDataSerializers.INT);
	private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> COLOR = net.minecraft.network.syncher.SynchedEntityData.defineId(VoidRift.class, net.minecraft.network.syncher.EntityDataSerializers.INT);
	private static final net.minecraft.network.syncher.EntityDataAccessor<java.util.Optional<java.util.UUID>> OWNER = net.minecraft.network.syncher.SynchedEntityData.defineId(VoidRift.class, net.minecraft.network.syncher.EntityDataSerializers.OPTIONAL_UUID);
	private @Nullable java.util.UUID pair;
	private @Nullable net.minecraft.resources.ResourceKey<Level> destination;
	private Vec3 arrival = Vec3.ZERO;
	private int growth;
	private final java.util.Map<java.util.UUID, Integer> exposure = new java.util.HashMap<>();

	@Override protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(SIZE, 3);
		builder.define(COLOR, 0xB4141E);
		builder.define(OWNER, java.util.Optional.empty());
	}

	public int size() { return entityData.get(SIZE); }
	public int color() { return entityData.get(COLOR); }
	@Override public net.minecraft.network.chat.Component getName() {
		return !invasion() && !hasCustomName() ? net.minecraft.network.chat.Component.translatable("entity.starmine.player_void_gate") : super.getName();
	}
	public static int color(net.minecraft.resources.ResourceKey<Level> destination) {
		if (destination == Level.OVERWORLD) return 0x44DD77;
		if (destination == Level.NETHER) return 0xFF6028;
		if (destination == Level.END) return 0xF3D45A;
		if (destination == Stratosphere.KEY) return 0x70CBFF;
		return 0xA442FF;
	}
	public @Nullable java.util.UUID owner() { return entityData.get(OWNER).orElse(null); }
	public boolean invasion() { return owner() == null; }
	public Vec3 centre() { return position().add(0, size() / 2.0, 0); }
	public void size(int size) { entityData.set(SIZE, net.minecraft.util.Mth.clamp(size | 1, 3, 9)); refreshDimensions(); }
	@Override public net.minecraft.world.entity.EntityDimensions getDefaultDimensions(net.minecraft.world.entity.Pose pose) {
		return net.minecraft.world.entity.EntityDimensions.scalable(size(), size());
	}
	@Override public void onSyncedDataUpdated(net.minecraft.network.syncher.EntityDataAccessor<?> key) {
		super.onSyncedDataUpdated(key);
		if (SIZE.equals(key)) refreshDimensions();
	}

	/** All portals share one body and renderer. Amun's have no owner or return destination. */
	public void configure(java.util.UUID owner, java.util.UUID pair, net.minecraft.resources.ResourceKey<Level> destination, Vec3 arrival, int size) {
		entityData.set(OWNER, java.util.Optional.of(owner));
		this.pair = pair;
		this.destination = destination;
		entityData.set(COLOR, color(destination));
		this.arrival = arrival;
		size(size);
	}

	/** Seven admits every mobile ground unit and capital flyers; nine also admits motherships and Amun. */
	public boolean admits(Entity entity) {
		if (entity instanceof Amun) return size() >= 9;
		if (entity instanceof StarUnit unit) return (!unit.kind.structure() || dev.starmine.ability.FieldAbilities.uprooted(unit)
			|| dev.starmine.ability.LiftOff.airborne(unit)) && admits(unit.kind, unit.flying());
		return entity.getBbHeight() <= size() && entity.getBbWidth() <= size();
	}
	public boolean admits(UnitKind kind, boolean flying) {
		if (kind == UnitKind.MOTHERSHIP) return size() >= 9;
		if (!flying && size() >= 7) return true;
		return switch (kind) {
			case BATTLECRUISER, CARRIER, TEMPEST, BROOD_LORD, HORNER, STUKOV, SELENDIS -> size() >= 7;
			default -> kind.height <= size();
		};
	}
	public static final float HEALTH = 250;
	public static final int EVERY = 300, CAP = 8;
	public static final double AREA = 24;
	/** The waves, weakest first: a rift moves on to the next one every few waves. */
	private static final List<List<UnitKind>> WAVES = List.of(
		List.of(UnitKind.ZERGLING, UnitKind.ZERGLING, UnitKind.MARINE, UnitKind.ZEALOT),
		List.of(UnitKind.ROACH, UnitKind.MARAUDER, UnitKind.STALKER, UnitKind.ZERGLING),
		List.of(UnitKind.HYDRALISK, UnitKind.MARINE, UnitKind.MARINE, UnitKind.ADEPT, UnitKind.ROACH),
		List.of(UnitKind.ULTRALISK, UnitKind.SIEGE_TANK, UnitKind.IMMORTAL, UnitKind.HYDRALISK),
		List.of(UnitKind.ULTRALISK, UnitKind.THOR, UnitKind.ARCHON, UnitKind.MUTALISK, UnitKind.VOID_RAY));
	private int waves, waveIn = 20;

	public VoidRift(EntityType<? extends VoidRift> type, Level level) {
		super(type, level);
		setNoGravity(true);
		setPersistenceRequired();
		noPhysics = true;
	}

	@Override
	public void tick() {
		super.tick();
		setDeltaMovement(Vec3.ZERO);
		if (level().isClientSide) {
			for (int i = 0; i < 2; i++)
				level().addParticle(random.nextBoolean() ? ParticleTypes.REVERSE_PORTAL : ParticleTypes.SMOKE, getRandomX(0.6), getRandomY(), getRandomZ(0.6),
					(random.nextDouble() - 0.5) * 0.2, 0.02, (random.nextDouble() - 0.5) * 0.2);
			return;
		}
		if (!(level() instanceof ServerLevel server)) return;
		if (!invasion()) {
			if (pair == null || destination == null || VoidGate.Broken.get(server.getServer()).contains(pair)) { discard(); return; }
			crossings(server);
			return;
		}
		if (++growth >= 1200 && size() < 9) { size(size() + 2); growth = 0; }
		if (VoidWorld.is(server)) {
			if (tickCount % EVERY == 20) wave(server);
		} else if (tickCount % 20 == 0 && AmunInvasion.nearPlayer(server, position()) && (waveIn -= 20) <= 0) { // the invasion's: near the players, as strong as it has grown
			waveIn = AmunInvasion.every(AmunInvasion.power(server));
			if (AmunInvasion.wave(server, this, waves++ % 2 == 0) > 0) playSound(SoundEvents.ENDERMAN_TELEPORT, 2.0f, 0.5f);
		}
	}

	/** A wave out of a rift of the Void, unless enough of Amun's troops are round it already. */
	void wave(ServerLevel level) {
		if (!invasion()) return;
		AABB area = getBoundingBox().inflate(AREA, 8, AREA);
		int near = level.getEntitiesOfClass(StarUnit.class, area, unit -> unit.isAlive() && unit.getTags().contains(VoidForces.TAG)).size();
		if (near >= CAP) return;
		List<UnitKind> wave = WAVES.get(Math.min(WAVES.size() - 1, waves / 3)).stream().filter(kind -> admits(kind, kind.flying)).toList();
		waves++;
		LivingEntity foe = foe(level);
		for (int i = 0; i < wave.size() && near + i < CAP; i++) {
			double a = random.nextDouble() * Math.PI * 2;
			Vec3 at = Spots.near(level, wave.get(i), position().add(Math.cos(a) * 3, 0, Math.sin(a) * 3));
			StarUnit unit = HiveLogic.spawnNest(level, null, wave.get(i), at, blockPosition());
			if (unit == null) continue;
			unit.addTag(VoidForces.TAG);
			if (foe != null) unit.setTarget(foe);
		}
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, getX(), getY() + 2, getZ(), 60, 1.2, 1.5, 1.2, 0.2);
		playSound(SoundEvents.ENDERMAN_TELEPORT, 2.0f, 0.5f);
	}

	/** The nearest player or player's unit for its troops to go for. */
	private @Nullable LivingEntity foe(ServerLevel level) {
		return level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(64, 24, 64), Amun::foe).stream()
			.min(java.util.Comparator.comparingDouble(this::distanceToSqr)).orElse(null);
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
		closePair();
		if (level() instanceof ServerLevel server) {
			server.sendParticles(ParticleTypes.REVERSE_PORTAL, getX(), getY() + 2, getZ(), 120, 1, 2, 1, 0.4);
			playSound(SoundEvents.END_PORTAL_SPAWN, 1.0f, 1.6f);
		}
	}

	@Override
	public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		tag.putInt("StarMineWaves", waves);
		tag.putInt("GateSize", size());
		tag.putInt("GateGrowth", growth);
		if (owner() != null) tag.putUUID("GateOwner", owner());
		if (pair != null) tag.putUUID("GatePair", pair);
		if (destination != null) {
			tag.putString("GateDimension", destination.location().toString());
			tag.putDouble("GateX", arrival.x); tag.putDouble("GateY", arrival.y); tag.putDouble("GateZ", arrival.z);
		}
	}

	@Override
	public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
		super.readAdditionalSaveData(tag);
		waves = tag.getInt("StarMineWaves");
		size(tag.contains("GateSize") ? tag.getInt("GateSize") : 3);
		growth = Math.max(0, Math.min(1200, tag.getInt("GateGrowth")));
		entityData.set(OWNER, tag.hasUUID("GateOwner") ? java.util.Optional.of(tag.getUUID("GateOwner")) : java.util.Optional.empty());
		pair = tag.hasUUID("GatePair") ? tag.getUUID("GatePair") : null;
		if (tag.contains("GateDimension")) {
			net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(tag.getString("GateDimension"));
			if (id != null) destination = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, id);
			if (destination != null) entityData.set(COLOR, color(destination));
			arrival = new Vec3(tag.getDouble("GateX"), tag.getDouble("GateY"), tag.getDouble("GateZ"));
		}
	}

	/** A broken half invalidates the unloaded half too; ordinary unloading never breaks a pair. */
	private void closePair() {
		if (pair == null || !(level() instanceof ServerLevel server)) return;
		VoidGate.Broken.get(server.getServer()).add(pair);
		for (ServerLevel world : server.getServer().getAllLevels())
			for (VoidRift rift : world.getEntities(VoidForces.RIFT, r -> r != this && pair.equals(r.pair))) rift.discard();
	}
	@Override public void remove(Entity.RemovalReason reason) {
		if (reason == Entity.RemovalReason.KILLED) closePair();
		super.remove(reason);
	}

	/** Friendly orders converge on the gate, without formation offsets that would strand the outside rows. */
	public void order(StarUnit unit) {
		if (!invasion() && admits(unit)) unit.command(StarUnit.Order.MOVE, position());
	}

	/** Resolves an ordinary move order aimed exactly at a player gate; saved orders retain this meaning after reload. */
	public static @Nullable VoidRift orderedPortal(StarUnit unit) {
		if (unit.order() != StarUnit.Order.MOVE || unit.orderPos() == null || !(unit.level() instanceof ServerLevel level)) return null;
		return level.getEntitiesOfClass(VoidRift.class, new AABB(unit.orderPos(), unit.orderPos()).inflate(0.25),
			rift -> rift.isAlive() && !rift.invasion() && rift.position().distanceToSqr(unit.orderPos()) < 0.1 && rift.admits(unit)).stream().findFirst().orElse(null);
	}

	private void crossings(ServerLevel level) {
		java.util.Set<java.util.UUID> inside = new java.util.HashSet<>();
		for (Entity entity : level.getEntities(this, getBoundingBox().inflate(1, 40, 1), Entity::isAlive)) {
			if (entity instanceof VoidRift || entity instanceof VoidShard || entity.isPassenger() || entity.isSpectator() || !admits(entity)) continue;
			double dx = entity.getX() - getX(), dz = entity.getZ() - getZ();
			boolean ordered = entity instanceof StarUnit unit && unit.order() == StarUnit.Order.MOVE && unit.orderPos() != null && unit.orderPos().distanceToSqr(position()) < 1;
			if (ordered && dx * dx + dz * dz <= size() * size()) {
				// Flyers descend out of their ordinary hover lane before entering the sphere.
				if (entity.getY() > getY() + size()) entity.setDeltaMovement(entity.getDeltaMovement().add(0, -0.2, 0));
			}
			Vec3 nearest = new Vec3(net.minecraft.util.Mth.clamp(getX(), entity.getBoundingBox().minX, entity.getBoundingBox().maxX),
				net.minecraft.util.Mth.clamp(centre().y, entity.getBoundingBox().minY, entity.getBoundingBox().maxY),
				net.minecraft.util.Mth.clamp(getZ(), entity.getBoundingBox().minZ, entity.getBoundingBox().maxZ));
			if (nearest.distanceToSqr(centre()) > size() * size() / 4.0) continue;
			inside.add(entity.getUUID());
			if (entity.isOnPortalCooldown()) { entity.setPortalCooldown(); continue; }
			int ticks = exposure.merge(entity.getUUID(), 1, Integer::sum);
			if (!(entity instanceof net.minecraft.world.entity.player.Player p) || p.isCreative() || ticks >= 80) travel(entity);
		}
		exposure.keySet().retainAll(inside);
	}

	/** Arrival is at the paired sphere; vanilla cooldown prevents bouncing, and passengers travel with their vehicle. */
	public @Nullable Entity travel(Entity entity) {
		if (invasion() || pair == null || destination == null || !isAlive() || !admits(entity) || !(level() instanceof ServerLevel level)
			|| VoidGate.Broken.get(level.getServer()).contains(pair) || entity.isOnPortalCooldown()) return null;
		ServerLevel to = level.getServer().getLevel(destination);
		if (to == null || !Double.isFinite(arrival.x + arrival.y + arrival.z)) return null;
		to.getChunkAt(net.minecraft.core.BlockPos.containing(arrival));
		VoidRift other = to.getEntitiesOfClass(VoidRift.class, new AABB(arrival, arrival).inflate(12), r -> r.isAlive() && pair.equals(r.pair)).stream().findFirst().orElse(null);
		if (other == null) return null;
		Vec3 exit = arrival;
		// Use an empty, supported exit; never put a traveller inside blocks or over an unprepared abyss.
		if (!to.noCollision(entity, entity.getBoundingBox().move(exit.subtract(entity.position())))) return null;
		var riders = java.util.List.copyOf(entity.getPassengers());
		if (entity.getSelfAndPassengers().anyMatch(rider -> !admits(rider) || !rider.canChangeDimensions(level, to))) return null;
		for (Entity rider : riders) rider.stopRiding();
		if (entity instanceof StarUnit unit) { unit.command(StarUnit.Order.NONE, null); unit.getNavigation().stop(); unit.setTarget(null); }
		entity.setPortalCooldown();
		Entity moved = entity.changeDimension(new net.minecraft.world.level.portal.DimensionTransition(to, exit, Vec3.ZERO, entity.getYRot(), entity.getXRot(), net.minecraft.world.level.portal.DimensionTransition.DO_NOTHING));
		if (moved == null) { for (Entity rider : riders) rider.startRiding(entity, true); return null; }
		arrived(moved, to, exit);
		for (Entity rider : riders) moveRider(rider, moved, to, exit);
		return moved;
	}

	/** Preserve the Void's existing first-visit army for players, including passengers of a transport. */
	private static void arrived(Entity entity, ServerLevel to, Vec3 exit) {
		entity.setPortalCooldown();
		if (entity instanceof StarUnit unit) unit.setHome(net.minecraft.core.BlockPos.containing(exit));
		if (entity instanceof net.minecraft.server.level.ServerPlayer player && VoidWorld.is(to)) {
			VoidWorld.prepare(to);
			VoidArmy.arrive(to, player);
		}
	}

	private static void moveRider(Entity rider, Entity vehicle, ServerLevel to, Vec3 exit) {
		var passengers = java.util.List.copyOf(rider.getPassengers());
		passengers.forEach(Entity::stopRiding);
		rider.setPortalCooldown();
		if (rider instanceof StarUnit unit) { unit.command(StarUnit.Order.NONE, null); unit.setTarget(null); }
		Entity moved = rider.changeDimension(new net.minecraft.world.level.portal.DimensionTransition(to, exit, Vec3.ZERO, rider.getYRot(), rider.getXRot(), net.minecraft.world.level.portal.DimensionTransition.DO_NOTHING));
		if (moved != null) {
			arrived(moved, to, exit); moved.startRiding(vehicle, true);
			for (Entity passenger : passengers) moveRider(passenger, moved, to, exit);
		}
	}
}
