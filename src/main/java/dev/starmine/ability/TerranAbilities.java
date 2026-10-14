package dev.starmine.ability;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Terran abilities' effects (owner 14.10, docs/ABILITIES.md). Cloak and siege mode are common mechanics
 * ({@link Cloak}, {@link Modes}); here: the battlecruiser's Yamato cannon and tactical jump.
 */
final class TerranAbilities {
	/** SC2 Yamato: 240 damage. */
	static final double YAMATO_DAMAGE = 240;

	private TerranAbilities() {
	}

	/** After its 2 s channel, the Yamato cannon hits the target: a bolt of light and 240 SC2 damage. */
	static void yamato(StarUnit unit, ServerLevel level, @Nullable LivingEntity target) {
		if (target == null || !target.isAlive()) return;
		Vec3 from = unit.position().add(0, unit.getBbHeight() / 2, 0), to = target.position().add(0, target.getBbHeight() / 2, 0);
		int steps = (int) Math.max(4, from.distanceTo(to) * 2);
		for (int i = 0; i <= steps; i++) {
			Vec3 point = from.lerp(to, i / (double) steps);
			level.sendParticles(ParticleTypes.FLAME, point.x, point.y, point.z, 3, 0.1, 0.1, 0.1, 0.01);
		}
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, to.x, to.y, to.z, 1, 0, 0, 0, 0);
		level.playSound(null, to.x, to.y, to.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 2f, 0.7f);
		target.hurt(unit.damageSources().mobAttack(unit), (float) (YAMATO_DAMAGE * UnitKind.SCALE));
	}

	/** After its 4 s warning, the battlecruiser jumps to the point (over the ground there). */
	static void tacticalJump(StarUnit unit, ServerLevel level, @Nullable Vec3 point) {
		if (point == null) return;
		level.sendParticles(ParticleTypes.PORTAL, unit.getX(), unit.getY() + 1, unit.getZ(), 80, 2, 1, 2, 0.5);
		double ground = unit.groundBelow(point.x, point.z);
		unit.teleportTo(point.x, ground + unit.kind.hoverHeight(), point.z);
		unit.getNavigation().stop();
		unit.command(StarUnit.Order.NONE, null);
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, unit.getX(), unit.getY() + 1, unit.getZ(), 80, 2, 1, 2, 0.3);
	}
}
