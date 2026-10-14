package dev.starmine.ability;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/**
 * Unit modes (owner 14.10, docs/ABILITIES.md): a unit switches its form and weapon — the siege tank's
 * siege mode now; the viking's landing, the hellbat, burrowing and the warp prism's phasing use the
 * same path. A change takes {@link #CHANGE_TICKS} (SC2 about 3 s for the tank), during which the unit
 * neither moves nor fights; then its weapon and speed follow the new mode ({@link #weapon}, applied by
 * {@link StarUnit#applyWeapon}). The model is the same in both modes for now.
 */
public final class Modes {
	public static final int NORMAL = 0, SIEGED = 1;
	public static final int CHANGE_TICKS = 60;

	/** A weapon in a mode: blocks of reach, damage, ticks between shots, splash radius, the closest it can shoot, whether it can move. */
	public record Weapon(double range, double damage, int interval, double splash, double minRange, boolean mobile) {
	}

	private Modes() {
	}

	/** The weapon of a unit in its mode. */
	public static Weapon weapon(UnitKind kind, int mode) {
		if (kind == UnitKind.SIEGE_TANK && mode == SIEGED) // SC2 crucio shock cannon: 40 (+30 armored), range 13, min 2, 2.14 s, splash
			return new Weapon(13 * UnitKind.BLOCKS_PER_UNIT + 1, Math.round(40 * UnitKind.SCALE * 2) / 2.0, 43, 1.25 * UnitKind.BLOCKS_PER_UNIT,
				2 * UnitKind.BLOCKS_PER_UNIT + 1, false);
		return new Weapon(kind.range, kind.damage, kind.interval, 0, 0, kind.speed > 0);
	}

	/** Starts a change to `mode` (the unit stands still until it is done). */
	static void change(StarUnit unit, int mode) {
		AbilityState state = unit.abilities;
		state.modeTicks = CHANGE_TICKS;
		state.nextMode = mode;
		unit.setTarget(null);
		unit.getNavigation().stop();
		unit.applyWeapon(new Weapon(0, 0, 0, 0, 0, false)); // nothing while it changes
		if (unit.level() instanceof ServerLevel server)
			server.sendParticles(ParticleTypes.SMOKE, unit.getX(), unit.getY() + 1, unit.getZ(), 20, 0.8, 0.4, 0.8, 0.02);
	}

	static void finish(StarUnit unit) {
		unit.setMode(unit.abilities.nextMode);
		unit.applyWeapon(weapon(unit.kind, unit.mode()));
	}

	/** A shot in siege mode: nothing too close; splash around the target hurts all foes there (and the target fully). */
	public static boolean siegeShot(StarUnit unit, LivingEntity target) {
		Weapon weapon = weapon(unit.kind, unit.mode());
		if (unit.distanceTo(target) < weapon.minRange()) return true;
		float damage = (float) (weapon.damage() + unit.attackBonus());
		target.hurt(unit.damageSources().mobAttack(unit), damage);
		for (LivingEntity other : unit.level().getEntitiesOfClass(LivingEntity.class, target.getBoundingBox().inflate(weapon.splash()),
			other -> other != target && dev.starmine.faction.Faction.hostile(unit, other)))
			other.hurt(unit.damageSources().mobAttack(unit), damage * 0.5f);
		if (unit.level() instanceof ServerLevel server)
			server.sendParticles(ParticleTypes.EXPLOSION, target.getX(), target.getY() + 0.5, target.getZ(), 1, 0, 0, 0, 0);
		return true;
	}
}
