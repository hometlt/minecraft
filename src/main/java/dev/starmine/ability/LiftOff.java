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
 * landed command center moves its base's anchor with it. As in SC2, an add-on (a mode of its building here) stays
 * on the ground when its building lifts off ({@link Hive#addOns}): a barracks, factory or starport that lands on
 * that site takes it (so two buildings swap add-ons by trading places); an orbital command flies and lands an
 * orbital again; a planetary fortress stays down.
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

	/** Whether a building in `mode` may lift off from it (SC2: an add-on stays behind, an orbital command flies). */
	static boolean leaves(int mode) {
		return mode == Modes.TECHLAB || mode == Modes.REACTOR || mode == Modes.ORBITAL;
	}

	/** Its site on the ground: the key of an add-on left there. */
	static net.minecraft.core.BlockPos site(StarUnit unit) {
		return net.minecraft.core.BlockPos.containing(unit.kind.gridCentre(unit.position()));
	}

	/** It starts to lift off: where from, its ground mode, and an add-on of it stays on the site. */
	static void liftingOff(StarUnit unit, @Nullable Hive base) {
		AbilityState state = unit.abilities;
		state.liftedFrom = unit.position();
		state.liftedMode = unit.mode();
		if (base != null && (unit.mode() == Modes.TECHLAB || unit.mode() == Modes.REACTOR)) {
			base.addOns.put(site(unit), unit.mode()); // drawn on its own by the client (AddOnRenderer)
			if (unit.level() instanceof net.minecraft.server.level.ServerLevel server) {
				dev.starmine.hive.HiveSavedData.get(server.getServer()).setDirty();
				dev.starmine.colony.ColonyNetwork.sendAddOns(server);
			}
		}
	}

	/** Why it cannot lift off or land now (a lang key), or null. */
	static @Nullable String refusal(StarUnit unit) {
		if (!airborne(unit)) return unit.working() ? "message.starmine.busy" : null;
		return landingSite(unit) != null ? null : "message.starmine.site_blocked";
	}

	/**
	 * Where it lands from where it hovers (owner 16.10: it could hardly land anywhere — any grass, any bump
	 * under its 15 x 15 body refused it): its place on the grid under it, on the ground under the middle;
	 * earth, stone, sand and plants in its way are cut away as for a new building
	 * ({@link dev.starmine.hive.HiveTerrain#prepare}). No other building may stand there, no trees, no water,
	 * no chasm. Null when it cannot land here.
	 */
	public static @Nullable Vec3 landingSite(StarUnit unit) {
		Vec3 centre = unit.kind.gridCentre(unit.position());
		double ground = unit.groundBelow(centre.x, centre.z);
		double half = unit.kind.width / 2.0;
		AABB box = new AABB(centre.x - half + 0.05, ground + 0.01, centre.z - half + 0.05, centre.x + half - 0.05, ground + unit.kind.height,
			centre.z + half - 0.05);
		if (!unit.level().getEntitiesOfClass(StarUnit.class, box, other -> other != unit && other.kind.structure() && other.isAlive()).isEmpty())
			return null;
		net.minecraft.core.BlockPos feet = net.minecraft.core.BlockPos.containing(centre.x, ground, centre.z);
		if (unit.kind == UnitKind.COMMAND_CENTER && dev.starmine.mineral.Minerals.nearSite(unit.level(), feet, half)) return null;
		int halfSize = (int) Math.ceil(half) - 1;
		if (!dev.starmine.hive.HiveTerrain.prepare(unit.level(), feet, halfSize, (int) Math.ceil(unit.kind.height), false)) return null;
		return new Vec3(centre.x, ground, centre.z);
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
		Vec3 site = landing ? landingSite(unit) : null;
		double ground = site != null ? site.y : unit.groundBelow(unit.getX(), unit.getZ());
		double height = landing ? ground : ground + HOVER;
		double dy = Math.max(-0.15, Math.min(0.15, (height - unit.getY()) * 0.3));
		double dx = 0, dz = 0;
		if (site != null) { // onto its place on the grid as it comes down
			dx = Math.max(-PER_TICK, Math.min(PER_TICK, site.x - unit.getX()));
			dz = Math.max(-PER_TICK, Math.min(PER_TICK, site.z - unit.getZ()));
		}
		Vec3 to = rising || landing ? null : unit.orderPos();
		if (to != null) {
			double x = to.x - unit.getX(), z = to.z - unit.getZ(), length = Math.sqrt(x * x + z * z);
			if (length <= PER_TICK) {
				unit.setPos(to.x, unit.getY(), to.z);
				if (unit.order() == StarUnit.Order.PATROL) unit.turnPatrol(); // SC2: back and forth
				else unit.command(StarUnit.Order.NONE, null); // there: it hovers
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
		Vec3 site = landingSite(unit);
		if (site != null) {
			net.minecraft.core.BlockPos feet = net.minecraft.core.BlockPos.containing(site);
			dev.starmine.hive.HiveTerrain.prepare(unit.level(), feet, (int) Math.ceil(unit.kind.width / 2.0) - 1, (int) Math.ceil(unit.kind.height), true);
			unit.setPos(site.x, site.y, site.z);
		} else unit.setPos(unit.getX(), unit.groundBelow(unit.getX(), unit.getZ()), unit.getZ());
		if (base != null && unit.kind.main() && liftedFrom != null && base.hatchery.closerToCenterThan(liftedFrom, 2))
			base.hatchery = unit.blockPosition();
		int ground = unit.kind.main() && unit.abilities.liftedMode == Modes.ORBITAL ? Modes.ORBITAL : Modes.NORMAL;
		if (base != null && Ability.BUILD_TECHLAB.users().contains(unit.kind)) { // an add-on on the site is its now
			Integer addOn = base.addOns.remove(site(unit));
			if (addOn != null) {
				ground = addOn;
				if (unit.level() instanceof net.minecraft.server.level.ServerLevel server) dev.starmine.colony.ColonyNetwork.sendAddOns(server);
			}
		}
		unit.abilities.liftedMode = Modes.NORMAL;
		unit.setMode(ground);
	}
}
