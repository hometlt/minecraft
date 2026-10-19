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
 * A void rift (owner 17.10): a tear Amun opens near his foes. Every {@link #EVERY} ticks troops of all three races
 * come out of it — Amun's, not their race's ({@link VoidForces#TAG}) — up to {@link #CAP} of them round it at once,
 * stronger the longer it stands. It stands until broken, or until Amun dies. A rift of his invasion of the worlds sends
 * its troops only near a player, as strong as the invasion has grown ({@link AmunInvasion#wave}).
 */
public class VoidRift extends Mob implements Enemy, VoidForces.Member {
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
		if (VoidWorld.is(server)) {
			if (tickCount % EVERY == 20) wave(server);
		} else if (tickCount % 20 == 0 && AmunInvasion.nearPlayer(server, position()) && (waveIn -= 20) <= 0) { // the invasion's: near the players, as strong as it has grown
			waveIn = AmunInvasion.every(AmunInvasion.power(server));
			if (AmunInvasion.wave(server, this, waves++ % 2 == 0) > 0) playSound(SoundEvents.ENDERMAN_TELEPORT, 2.0f, 0.5f);
		}
	}

	/** A wave out of a rift of the Void, unless enough of Amun's troops are round it already. */
	void wave(ServerLevel level) {
		AABB area = getBoundingBox().inflate(AREA, 8, AREA);
		int near = level.getEntitiesOfClass(StarUnit.class, area, unit -> unit.isAlive() && unit.getTags().contains(VoidForces.TAG)).size();
		if (near >= CAP) return;
		List<UnitKind> wave = WAVES.get(Math.min(WAVES.size() - 1, waves / 3));
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
		if (level() instanceof ServerLevel server) {
			server.sendParticles(ParticleTypes.REVERSE_PORTAL, getX(), getY() + 2, getZ(), 120, 1, 2, 1, 0.4);
			playSound(SoundEvents.END_PORTAL_SPAWN, 1.0f, 1.6f);
		}
	}

	@Override
	public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		tag.putInt("StarMineWaves", waves);
	}

	@Override
	public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
		super.readAdditionalSaveData(tag);
		waves = tag.getInt("StarMineWaves");
	}
}
