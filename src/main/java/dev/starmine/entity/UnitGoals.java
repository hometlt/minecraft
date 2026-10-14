package dev.starmine.entity;

import java.util.EnumSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RangedAttackGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Unit AI shared by every race (owner 14.10): orders from the base's player come first ({@link Orders});
 * then the weapon (melee, shots, lines); workers work ({@link DroneWork}: gather, carry, build, vespene,
 * an army's way); others hold near where they were sent ({@link ZergGoals.ReturnHome}: short for a
 * player's units, wide for zerg). Targets: whoever {@link dev.starmine.faction.Faction} calls a foe within
 * sight, and whoever hurts them; workers only fight back. Zerg add their own goals in {@link StarUnit}.
 */
final class UnitGoals {
	private UnitGoals() {
	}

	/** Called from the mob's constructor, before its `kind` field is set: the kind comes as an argument. */
	static void register(StarUnit unit, UnitKind kind, GoalSelector goals, GoalSelector targets) {
		goals.addGoal(1, new Orders(unit));
		switch (kind.attack) {
			case NONE -> {
			}
			case MELEE, SPLASH, EXPLODE -> goals.addGoal(2, new MeleeAttackGoal(unit, 1.15, false) {
				@Override
				protected int getAttackInterval() {
					return this.adjustedTickDelay(Math.max(4, unit.weapon().interval()));
				}

				/** Not while it cannot fight (burrowed, lifted, changing its mode…: its weapon is none) or rides a transport. */
				@Override
				public boolean canUse() {
					return armed(unit) && super.canUse();
				}

				@Override
				public boolean canContinueToUse() {
					return armed(unit) && super.canContinueToUse();
				}
			});
			default -> goals.addGoal(2, new Shoot(unit, kind.interval, (float) kind.range));
		}
		if (kind.worker()) goals.addGoal(3, new DroneWork(unit));
		else goals.addGoal(4, new ZergGoals.ReturnHome(unit, kind.race == UnitKind.Race.ZERG ? 24 : 4));
		goals.addGoal(6, new LookAtPlayerGoal(unit, Player.class, 8.0f));
		goals.addGoal(7, new RandomLookAroundGoal(unit));
		if (kind.attack == UnitKind.Attack.NONE) return;
		// whoever hurts it, unless it cannot see them: a cloaked ghost shooting a marine stays hidden (SC2)
		targets.addGoal(1, new HurtByTargetGoal(unit) {
			@Override
			public boolean canUse() {
				return !(unit.getLastHurtByMob() instanceof StarUnit hidden && !dev.starmine.ability.Cloak.seen(hidden, unit)) && super.canUse();
			}
		}.setAlertOthers(StarUnit.class));
		if (!kind.worker() && kind.aggressive)
			targets.addGoal(2, new NearestAttackableTargetGoal<>(unit, LivingEntity.class, 5, true, false,
				other -> dev.starmine.faction.Faction.hostile(unit, other)
					&& !(other instanceof StarUnit cloaked && !dev.starmine.ability.Cloak.seen(cloaked, unit))));
	}

	/** Whether it may fight now: it has a weapon in its mode and states, and does not ride a transport (a bunker's garrison does fight). */
	static boolean armed(StarUnit unit) {
		return unit.weapon().range() > 0 && (!unit.isPassenger() || dev.starmine.ability.Transport.firesFrom(unit.getVehicle()));
	}

	/** The ranged attack: vanilla's, while the unit is {@link #armed}. */
	static final class Shoot extends RangedAttackGoal {
		private final StarUnit unit;

		Shoot(StarUnit unit, int interval, float range) {
			super(unit, 1.0, interval, interval + 10, range);
			this.unit = unit;
		}

		@Override
		public boolean canUse() {
			return armed(unit) && super.canUse();
		}

		@Override
		public boolean canContinueToUse() {
			return armed(unit) && super.canContinueToUse();
		}
	}

	/**
	 * A player's order. Move: there, ignoring enemies. Attack-move and patrol: there (patrol back and
	 * forth), fighting what they meet on the way. Hold: stay, shooting what comes into range. A unit
	 * that neither gets closer nor moves for 5 s gives the order up where it is (a unit walking a detour
	 * round a cliff or a river, away from the goal for a while, keeps going). A flyer keeps its height
	 * over the ground ahead of it, so it climbs before a hill instead of flying into it.
	 */
	static final class Orders extends Goal {
		private final StarUnit unit;
		private int repath, stuck;
		private double best;
		private Vec3 last;

		Orders(StarUnit unit) {
			this.unit = unit;
			this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
		}

		@Override
		public boolean canUse() {
			StarUnit.Order order = unit.order();
			if (order == StarUnit.Order.NONE || unit.orderPos() == null && order != StarUnit.Order.HOLD) return false;
			LivingEntity target = unit.getTarget();
			return switch (order) {
				case MOVE -> true;
				case HOLD -> target == null || unit.distanceTo(target) > unit.weapon().range() + 1;
				default -> target == null;
			};
		}

		@Override
		public boolean canContinueToUse() {
			return canUse();
		}

		@Override
		public void start() {
			repath = 0;
			stuck = 0;
			best = Double.MAX_VALUE;
		}

		@Override
		public void stop() {
			unit.getNavigation().stop();
		}

		@Override
		public boolean requiresUpdateEveryTick() {
			return true;
		}

		@Override
		public void tick() {
			StarUnit.Order order = unit.order();
			if (order == StarUnit.Order.MOVE) unit.setTarget(null);
			if (order == StarUnit.Order.HOLD) {
				unit.getNavigation().stop();
				return;
			}
			Vec3 goal = unit.orderPos();
			if (goal == null) return; // the order changed this tick (a new command, a way cut for the army)
			double dx = unit.getX() - goal.x, dz = unit.getZ() - goal.z;
			double flat = Math.sqrt(dx * dx + dz * dz);
			if (flat < Math.min(1.0 + unit.kind.width / 2, 1.5)) {
				if (order == StarUnit.Order.PATROL) {
					unit.turnPatrol();
					best = Double.MAX_VALUE;
				} else {
					unit.finishOrder();
				}
				return;
			}
			boolean moving = last != null && unit.position().distanceToSqr(last) > 0.03 * 0.03;
			last = unit.position();
			if (flat < best - 0.3) {
				best = flat;
				stuck = 0;
			} else if (moving) {
				stuck = Math.max(0, stuck - 1);
			} else if (++stuck > 100) {
				unit.finishOrder();
				return;
			}
			if (unit.flying()) { // a flying move control steers one tick per wanted position: give it every tick
				double above = Math.max(3, unit.kind.hoverHeight());
				double y = ZergGoals.cruise(unit, goal.x, goal.z, above);
				// the ground ahead on the way: climb before a hill, not into it
				double toX = goal.x - unit.getX(), toZ = goal.z - unit.getZ();
				for (double ahead = 1; ahead <= Math.min(12, flat); ahead += 1.5)
					y = Math.max(y, ZergGoals.cruise(unit, unit.getX() + toX / flat * ahead, unit.getZ() + toZ / flat * ahead, above));
				unit.getMoveControl().setWantedPosition(goal.x, y, goal.z, 1.0);
				return;
			}
			// the last blocks straight when nothing stands between: the game's path lets a big unit off up to
			// half its width short of its point (a thor sent onto a tunnel's axis stopped 3 blocks aside of it)
			if (flat < 2.5 + unit.getBbWidth() / 2 && Math.abs(unit.getY() - goal.y) < 1.5
				&& unit.level().clip(new net.minecraft.world.level.ClipContext(unit.position().add(0, 0.5, 0), new Vec3(goal.x, unit.getY() + 0.5, goal.z),
					net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, unit)).getType()
					== net.minecraft.world.phys.HitResult.Type.MISS) {
				unit.getNavigation().stop();
				unit.getMoveControl().setWantedPosition(goal.x, goal.y, goal.z, 1.0);
				return;
			}
			if (--repath > 0) return;
			repath = 20;
			if (!unit.getNavigation().moveTo(goal.x, goal.y, goal.z, 1.0)) {
				unit.getMoveControl().setWantedPosition(goal.x, goal.y, goal.z, 1.0);
			}
		}
	}
}
