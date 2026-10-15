package dev.starmine.ability;

import static dev.starmine.entity.UnitKind.BLOCKS_PER_UNIT;
import static dev.starmine.entity.UnitKind.SCALE;

import dev.starmine.entity.StarUnit;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The heroes' own abilities (CLOUD_TASK_2 stage 8, numbers in docs/BALANCE.md): Kerrigan's leaping
 * strike and psionic shift (SC2 co-op: 150 and 50 damage), Raynor's call of the Hyperion (SC2 co-op:
 * its Yamato cannon, 600 damage and splash after 1.5 s) and Artanis's lightning dash (SC2 Legacy of
 * the Void: 100 damage around where he arrives, 2 s stun). Raynor's grenade is the KD8 charge, Artanis's
 * storm the psionic storm.
 */
public final class HeroAbilities {
	/** SC2 damage. */
	public static final double LEAP_DAMAGE = 150, SHIFT_DAMAGE = 50, HYPERION_DAMAGE = 600, HYPERION_SPLASH = 150, DASH_DAMAGE = 100;
	/** Radii in blocks: psionic shift's path, the Yamato's splash, the dash's blast (SC2 1, 2 and 2 units). */
	public static final double SHIFT_RADIUS = 1 * BLOCKS_PER_UNIT + 0.5, HYPERION_RADIUS = 2 * BLOCKS_PER_UNIT, DASH_RADIUS = 2 * BLOCKS_PER_UNIT;
	/** The dash's stun (SC2 2 s). */
	public static final int STUN_TICKS = 40;

	private HeroAbilities() {
	}

	/** Leaping strike: Kerrigan lands beside the target and strikes it. */
	static void leapingStrike(StarUnit unit, ServerLevel level, @Nullable LivingEntity target) {
		if (target == null) return;
		Vec3 at = beside(unit, target);
		Vec3 standing = Abilities.standable(unit, level, at);
		level.sendParticles(ParticleTypes.WITCH, unit.getX(), unit.getY() + 1, unit.getZ(), 30, 0.5, 1, 0.5, 0.05);
		if (standing != null) Abilities.teleport(unit, standing);
		target.hurt(unit.attackSource(), (float) (LEAP_DAMAGE * SCALE));
		unit.setTarget(target);
		unit.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
		level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.HOSTILE, 1.0f, 0.6f);
	}

	/** Psionic shift: Kerrigan dashes to the point, hurting every foe along the way. */
	static void psionicShift(StarUnit unit, ServerLevel level, @Nullable Vec3 point) {
		if (point == null) return;
		Vec3 from = unit.position();
		Vec3 standing = Abilities.standable(unit, level, point);
		if (standing == null) return;
		java.util.Set<LivingEntity> hit = new java.util.HashSet<>();
		double length = from.distanceTo(standing);
		for (double t = 0; t <= length; t += 1) {
			Vec3 on = from.lerp(standing, length == 0 ? 1 : t / length);
			hit.addAll(Abilities.around(unit, on, SHIFT_RADIUS, true));
			level.sendParticles(ParticleTypes.DRAGON_BREATH, on.x, on.y + 1, on.z, 4, 0.3, 0.5, 0.3, 0.01);
		}
		Abilities.teleport(unit, standing);
		for (LivingEntity other : hit) other.hurt(unit.attackSource(), (float) (SHIFT_DAMAGE * SCALE));
	}

	/** The Hyperion's Yamato from the sky (after its windup): the target and those round it. */
	static void hyperionStrike(StarUnit unit, ServerLevel level, @Nullable LivingEntity target) {
		if (target == null || !target.isAlive()) return;
		Vec3 at = target.position();
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 1, at.z, 1, 0, 0, 0, 0);
		level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + 12, at.z, 60, 0.3, 6, 0.3, 0.02);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 2.0f, 0.7f);
		for (LivingEntity other : Abilities.around(unit, at, HYPERION_RADIUS, true))
			other.hurt(unit.attackSource(), (float) ((other == target ? HYPERION_DAMAGE : HYPERION_SPLASH) * SCALE));
	}

	/** Lightning dash: Artanis is beside the target at once; foes round him take a blast and are stunned. */
	static void lightningDash(StarUnit unit, ServerLevel level, @Nullable LivingEntity target) {
		if (target == null) return;
		Vec3 standing = Abilities.standable(unit, level, beside(unit, target));
		level.sendParticles(ParticleTypes.ELECTRIC_SPARK, unit.getX(), unit.getY() + 1, unit.getZ(), 40, 0.5, 1, 0.5, 0.2);
		if (standing != null) Abilities.teleport(unit, standing);
		for (LivingEntity other : Abilities.around(unit, unit.position(), DASH_RADIUS, true)) {
			other.hurt(unit.attackSource(), (float) (DASH_DAMAGE * SCALE));
			Status.apply(other, Status.STUNNED, STUN_TICKS);
		}
		level.sendParticles(ParticleTypes.ELECTRIC_SPARK, unit.getX(), unit.getY() + 1, unit.getZ(), 80, DASH_RADIUS / 2, 1, DASH_RADIUS / 2, 0.3);
		unit.setTarget(target);
	}

	/** Where to land by a target: on the caster's side of it, just outside both bodies. */
	private static Vec3 beside(StarUnit unit, LivingEntity target) {
		Vec3 back = unit.position().subtract(target.position()).multiply(1, 0, 1);
		back = back.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : back.normalize();
		return target.position().add(back.scale((unit.getBbWidth() + target.getBbWidth()) / 2 + 0.5));
	}
}
