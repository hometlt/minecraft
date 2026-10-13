package dev.starmine.entity;

import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Zerg-specific AI goals: hovering flight above the ground, staying near the hive, and the overlord
 * ferrying zerg that cannot reach their target (rivers, cliffs) to it.
 */
final class ZergGoals {
	private ZergGoals() {
	}

	/** Ground height under a point (top of motion-blocking blocks). */
	static double ground(ZergMob mob, double x, double z) {
		return mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
	}

	/** Flying kinds wander through the air at their hover height instead of skimming the ground. */
	static final class HoverWander extends Goal {
		private final ZergMob mob;

		HoverWander(ZergMob mob) {
			this.mob = mob;
			this.setFlags(EnumSet.of(Flag.MOVE));
		}

		@Override
		public boolean canUse() {
			return mob.getTarget() == null && mob.getPassengers().isEmpty() && mob.getRandom().nextInt(60) == 0;
		}

		@Override
		public boolean canContinueToUse() {
			return false;
		}

		@Override
		public void start() {
			BlockPos home = mob.home();
			double cx = home != null ? home.getX() : mob.getX(), cz = home != null ? home.getZ() : mob.getZ();
			double radius = home != null ? 20 : 16;
			double x = cx + (mob.getRandom().nextDouble() * 2 - 1) * radius;
			double z = cz + (mob.getRandom().nextDouble() * 2 - 1) * radius;
			double y = ground(mob, x, z) + mob.kind.hoverHeight() + mob.getRandom().nextDouble() * 3;
			mob.getMoveControl().setWantedPosition(x, y, z, 1.0);
		}
	}

	/** Hive members without a target drift back toward their hatchery. */
	static final class ReturnHome extends Goal {
		private final ZergMob mob;
		private final double radius;

		ReturnHome(ZergMob mob, double radius) {
			this.mob = mob;
			this.radius = radius;
			this.setFlags(EnumSet.of(Flag.MOVE));
		}

		@Override
		public boolean canUse() {
			BlockPos home = mob.home();
			return home != null && mob.getTarget() == null && !mob.isPassenger()
				&& mob.distanceToSqr(Vec3.atBottomCenterOf(home)) > radius * radius;
		}

		@Override
		public void start() {
			BlockPos home = mob.home();
			if (mob.kind.flying)
				mob.getMoveControl().setWantedPosition(home.getX() + 0.5, ground(mob, home.getX(), home.getZ()) + mob.kind.hoverHeight(), home.getZ() + 0.5, 1.0);
			else
				mob.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 1.0);
		}

		@Override
		public boolean canContinueToUse() {
			return !mob.getNavigation().isDone() && mob.getTarget() == null;
		}
	}

	/**
	 * Overlord transport: a nearby ground zerg that has a target it cannot reach is picked up, carried
	 * high over the obstacle and set down next to its target. Capacity is SC2's 8 cargo slots.
	 */
	static final class Transport extends Goal {
		static final int CAPACITY = 8;
		private final ZergMob overlord;
		private ZergMob cargo;
		private int timeout;

		Transport(ZergMob overlord) {
			this.overlord = overlord;
			this.setFlags(EnumSet.of(Flag.MOVE));
		}

		@Override
		public boolean canUse() {
			if (overlord.tickCount % 20 != 0 || !overlord.getPassengers().isEmpty()) return false;
			List<ZergMob> stuck = overlord.level().getEntitiesOfClass(ZergMob.class, overlord.getBoundingBox().inflate(32),
				mob -> !mob.kind.flying && mob.isAlive() && !mob.isPassenger() && mob.getTarget() != null
					&& mob.unreachableTicks() >= 60 && mob.kind.economy().cargo() > 0 && mob.kind.economy().cargo() <= CAPACITY);
			if (stuck.isEmpty()) return false;
			cargo = stuck.get(0);
			timeout = 600;
			return true;
		}

		@Override
		public boolean canContinueToUse() {
			return --timeout > 0 && cargo != null && cargo.isAlive()
				&& (cargo.getVehicle() == overlord || (!cargo.isPassenger() && cargo.getTarget() != null));
		}

		@Override
		public void tick() {
			if (cargo.getVehicle() != overlord) {
				// fetch: fly right above the stuck zerg and take it
				overlord.getMoveControl().setWantedPosition(cargo.getX(), cargo.getY() + cargo.getBbHeight() + 1.5, cargo.getZ(), 2.5);
				if (horizontal(overlord.position(), cargo.position()) < 2 && overlord.getY() - cargo.getY() < 6) {
					cargo.startRiding(overlord, true);
					// pick the cargo mates: others stuck near it with the same target
					int room = CAPACITY - cargo.kind.economy().cargo();
					for (ZergMob mate : overlord.level().getEntitiesOfClass(ZergMob.class, cargo.getBoundingBox().inflate(4),
						m -> m != cargo && !m.kind.flying && !m.isPassenger() && m.getTarget() == cargo.getTarget() && m.unreachableTicks() >= 60)) {
						int size = mate.kind.economy().cargo();
						if (size <= 0 || size > room) continue;
						room -= size;
						mate.startRiding(overlord, true);
					}
				}
				return;
			}
			LivingEntity target = cargo.getTarget();
			if (target == null) {
				overlord.ejectPassengers();
				return;
			}
			double distance = horizontal(overlord.position(), target.position());
			double cruise = Math.max(ground(overlord, target.getX(), target.getZ()), target.getY()) + 6;
			double y = distance < 6 ? target.getY() + 3 : Math.max(cruise, ground(overlord, overlord.getX(), overlord.getZ()) + 6);
			overlord.getMoveControl().setWantedPosition(target.getX(), y, target.getZ(), 2.5);
			if (distance < 3 && overlord.getY() - target.getY() < 6) {
				for (Entity passenger : List.copyOf(overlord.getPassengers())) {
					passenger.stopRiding();
					if (passenger instanceof ZergMob zerg) zerg.clearUnreachable();
				}
			}
		}

		@Override
		public void stop() {
			if (!overlord.getPassengers().isEmpty()) overlord.ejectPassengers();
			cargo = null;
		}

		private static double horizontal(Vec3 a, Vec3 b) {
			double dx = a.x - b.x, dz = a.z - b.z;
			return Math.sqrt(dx * dx + dz * dz);
		}
	}
}
