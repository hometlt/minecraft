package dev.starmine.ability;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * A terran building lifted off (SC2: the command center, barracks, factory and starport fly to a new
 * site). Lift-off and landing take SC2 1.43 s ({@link Modes#changeTicks}); in the air the building
 * hovers {@link #HOVER} blocks over the ground, flies straight to its move order point at SC2 speed
 * {@link #SC2_SPEED} and neither trains nor researches. It lifts off only idle (nothing in training, no
 * research) and lands only where its body fits: no other building there, no blocks in the way. A
 * landed command center moves its base's anchor with it. The add-ons and the command center's upgrades
 * are modes of their buildings, so a building with one stays down.
 */
public final class LiftOff {
	/** Blocks a lifted building keeps over the ground (SC2: air units' height). */
	public static final double HOVER = 4;
	/** SC2 speed of a lifted building (LotV, Faster). */
	public static final double SC2_SPEED = 1.4;
	/**
	 * Blocks a tick it flies: the way a walker with that SC2 speed goes (its attribute is SC2 speed x
	 * {@link UnitKind#SPEED_SCALE}, and a Minecraft ground mob covers about 1.6x its speed attribute a tick).
	 */
	static final double PER_TICK = SC2_SPEED * UnitKind.SPEED_SCALE * 1.6;

	private LiftOff() {
	}

	public static boolean airborne(StarUnit unit) {
		return unit.mode() == Modes.AIRBORNE;
	}

	/** Why it cannot lift off or land now (a lang key), or null. */
	static @Nullable String refusal(StarUnit unit) {
		if (!airborne(unit)) return unit.working() ? "message.starmine.busy" : null;
		return siteFree(unit) ? null : "message.starmine.site_blocked";
	}

	/** Whether its body fits on the ground under it: no other building, no blocks. */
	static boolean siteFree(StarUnit unit) {
		double ground = unit.groundBelow(unit.getX(), unit.getZ());
		double half = unit.getBbWidth() / 2;
		AABB box = new AABB(unit.getX() - half, ground + 0.01, unit.getZ() - half, unit.getX() + half, ground + unit.getBbHeight(), unit.getZ() + half);
		if (!unit.level().getEntitiesOfClass(StarUnit.class, box, other -> other != unit && other.kind.structure() && other.isAlive()).isEmpty())
			return false;
		return unit.level().getBlockCollisions(unit, box).iterator().hasNext() == false;
	}

	/** Each tick of a building that can lift off: rises, flies to its order point, lands. */
	static void tick(StarUnit unit) {
		AbilityState state = unit.abilities;
		boolean rising = state.changingMode() && state.nextMode == Modes.AIRBORNE;
		boolean landing = state.changingMode() && state.nextMode == Modes.NORMAL && airborne(unit);
		if (!rising && !landing && !airborne(unit)) {
			if (unit.isNoGravity()) unit.setNoGravity(false); // landed
			return;
		}
		unit.setNoGravity(true);
		double ground = unit.groundBelow(unit.getX(), unit.getZ());
		double height = landing ? ground : ground + HOVER;
		double dy = Math.max(-0.15, Math.min(0.15, (height - unit.getY()) * 0.3));
		double dx = 0, dz = 0;
		Vec3 to = rising || landing ? null : unit.orderPos();
		if (to != null) {
			double x = to.x - unit.getX(), z = to.z - unit.getZ(), length = Math.sqrt(x * x + z * z);
			if (length <= PER_TICK) {
				unit.setPos(to.x, unit.getY(), to.z);
				unit.command(StarUnit.Order.NONE, null); // there: it hovers
			} else {
				dx = x / length * PER_TICK;
				dz = z / length * PER_TICK;
			}
		}
		unit.setDeltaMovement(dx, dy, dz);
		unit.hurtMarked = true;
	}

	/** After landing: on the ground, and a command center takes its base's anchor along. */
	static void landed(StarUnit unit, @Nullable Hive base, @Nullable Vec3 liftedFrom) {
		unit.setNoGravity(false);
		unit.setPos(unit.getX(), unit.groundBelow(unit.getX(), unit.getZ()), unit.getZ());
		if (base != null && unit.kind.main() && liftedFrom != null && base.hatchery.closerToCenterThan(liftedFrom, 2))
			base.hatchery = unit.blockPosition();
	}
}
