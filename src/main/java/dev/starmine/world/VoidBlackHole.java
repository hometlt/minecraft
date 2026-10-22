package dev.starmine.world;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Amun's black holes (owner 19.10): one opens where a foe stands and grows over {@link #GROW} ticks to {@link #RADIUS}
 * blocks round (16 x 16), drawing everything round it in harder and harder — units, players, items — and hurting
 * what it holds at its heart; grown, it bursts, and every block inside it goes with it (only unbreakable ones stay),
 * the living in it badly hurt. So Amun breaks islands with an army or a player on them; one sees it coming and runs.
 * Not saved: one open when the server stops is gone.
 */
public final class VoidBlackHole {
	public static final int EVERY = 640, GROW = 220, RADIUS = 8;
	public static final double PULL = 0.16, REACH = RADIUS * 2.5;
	public static final float HEART_DAMAGE = 2, BURST_DAMAGE = 24;
	private static final DustParticleOptions BLACK = new DustParticleOptions(new Vector3f(0.02f, 0.0f, 0.04f), 3.0f);
	private static final List<VoidBlackHole> OPEN = new ArrayList<>();

	private final ServerLevel level;
	private final Vec3 centre;
	private int age;

	private VoidBlackHole(ServerLevel level, Vec3 centre) {
		this.level = level;
		this.centre = centre;
	}

	/** Opens one at a foe's feet (one at a time). */
	public static void open(ServerLevel level, Amun amun, BlockPos at) {
		if (OPEN.stream().anyMatch(hole -> hole.level == level)) return;
		VoidBlackHole hole = new VoidBlackHole(level, Vec3.atCenterOf(at).add(0, 1, 0));
		OPEN.add(hole);
		level.playSound(null, at, SoundEvents.END_PORTAL_SPAWN, SoundSource.HOSTILE, 3f, 0.4f);
		for (ServerPlayer player : level.players())
			if (player.distanceToSqr(hole.centre) < 80 * 80) player.displayClientMessage(Component.translatable("message.starmine.black_hole"), true);
	}

	/** How far round it is now (blocks). */
	public double radius() {
		return 1 + (RADIUS - 1) * Math.min(1, age / (double) GROW);
	}

	public static List<VoidBlackHole> open() {
		return List.copyOf(OPEN);
	}

	public Vec3 centre() {
		return centre;
	}

	public static void tick(MinecraftServer server) {
		for (Iterator<VoidBlackHole> it = OPEN.iterator(); it.hasNext(); ) {
			VoidBlackHole hole = it.next();
			if (!hole.level.isLoaded(BlockPos.containing(hole.centre))) {
				it.remove();
				continue;
			}
			if (hole.step()) it.remove();
		}
	}

	/** One tick: it grows and pulls; true when it has burst. */
	boolean step() {
		age++;
		double r = radius(), grown = age / (double) GROW;
		if (age % 2 == 0) { // a dark sphere with a swirl round it
			for (int i = 0; i < 6; i++) {
				Vec3 p = new Vec3(level.random.nextGaussian(), level.random.nextGaussian(), level.random.nextGaussian()).normalize().scale(r * 0.9);
				level.sendParticles(BLACK, centre.x + p.x, centre.y + p.y, centre.z + p.z, 1, 0, 0, 0, 0);
			}
			double a = age * 0.4;
			level.sendParticles(ParticleTypes.REVERSE_PORTAL, centre.x + Math.cos(a) * r * 1.4, centre.y, centre.z + Math.sin(a) * r * 1.4, 4, 0.2, 0.4, 0.2, 0.02);
			level.sendParticles(ParticleTypes.SQUID_INK, centre.x, centre.y, centre.z, 3, r * 0.3, r * 0.3, r * 0.3, 0.01);
		}
		if (age % 40 == 0) level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, 3f, 0.5f);
		double reach = REACH * (0.4 + 0.6 * grown);
		for (Entity entity : level.getEntities((Entity) null, new AABB(centre, centre).inflate(reach),
			e -> e.isAlive() && !(e instanceof Amun) && !(e instanceof VoidForces.Member) && !(e instanceof VoidRift rift && rift.invasion()) && !e.isSpectator()
				&& !(e instanceof ServerPlayer p && p.isCreative()))) {
			Vec3 in = centre.subtract(entity.position().add(0, entity.getBbHeight() / 2, 0));
			double d = in.length();
			if (d > reach || d < 0.01) continue;
			double pull = PULL * (0.3 + 0.7 * grown) * (1.2 - d / reach);
			entity.setDeltaMovement(entity.getDeltaMovement().add(in.normalize().scale(pull)));
			entity.hurtMarked = true;
			if (d < 1.5 && entity instanceof LivingEntity living && age % 10 == 0) living.hurt(level.damageSources().magic(), HEART_DAMAGE);
		}
		if (age < GROW) return false;
		burst();
		return true;
	}

	/** Grown: it bursts, every breakable block inside it gone, the living in it badly hurt. */
	private void burst() {
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, centre.x, centre.y, centre.z, 6, RADIUS / 2.0, RADIUS / 2.0, RADIUS / 2.0, 0);
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, centre.x, centre.y, centre.z, 400, RADIUS / 2.0, RADIUS / 2.0, RADIUS / 2.0, 0.6);
		level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 8f, 0.4f);
		BlockPos c = BlockPos.containing(centre);
		for (BlockPos pos : BlockPos.betweenClosed(c.offset(-RADIUS, -RADIUS, -RADIUS), c.offset(RADIUS, RADIUS, RADIUS))) {
			if (pos.distToCenterSqr(centre) > RADIUS * RADIUS) continue;
			var state = level.getBlockState(pos);
			if (state.isAir() || state.getDestroySpeed(level, pos) < 0) continue;
			level.removeBlock(pos, false);
		}
		for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, new AABB(centre, centre).inflate(RADIUS),
			e -> !(e instanceof Amun) && !(e instanceof VoidForces.Member) && !(e instanceof VoidRift rift && rift.invasion()) && e.position().distanceTo(centre) <= RADIUS + 0.5))
			living.hurt(level.damageSources().magic(), BURST_DAMAGE);
	}
}
