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
 * Terran unit AI (owner 14.10): orders from the base's player come first ({@link Orders}); then the
 * weapon (instant shots, melee for the SCV, a flame line for the hellion); SCVs work
 * ({@link DroneWork}: gather, carry, build); others hold where they were sent ({@link ZergGoals.ReturnHome}
 * with a short radius). Targets: zerg and hostile monsters within sight, and whoever hurts them (never
 * players or other terrans). SCVs only fight back.
 */
final class TerranGoals {
	private TerranGoals() {
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
					return this.adjustedTickDelay(kind.interval);
				}
			});
			default -> goals.addGoal(2, new RangedAttackGoal(unit, 1.0, kind.interval, kind.interval + 10, (float) kind.range));
		}
		if (kind.worker()) goals.addGoal(3, new DroneWork(unit));
		else goals.addGoal(4, new ZergGoals.ReturnHome(unit, 4));
		goals.addGoal(6, new LookAtPlayerGoal(unit, Player.class, 8.0f));
		goals.addGoal(7, new RandomLookAroundGoal(unit));
		if (kind.attack == UnitKind.Attack.NONE) return;
		targets.addGoal(1, new HurtByTargetGoal(unit).setAlertOthers(StarUnit.class));
		if (!kind.worker())
			targets.addGoal(2, new NearestAttackableTargetGoal<>(unit, LivingEntity.class, 5, true, false,
				other -> dev.starmine.faction.Faction.hostile(unit, other)));
	}

	/**
	 * A player's order. Move: there, ignoring enemies. Attack-move and patrol: there (patrol back and
	 * forth), fighting what they meet on the way. Hold: stay, shooting what comes into range. A unit
	 * that cannot get closer for 5 s gives the order up where it is.
	 */
	static final class Orders extends Goal {
		private final StarUnit unit;
		private int repath, stuck;
		private double best;

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
				case HOLD -> target == null || unit.distanceTo(target) > unit.kind.range + 1;
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
			if (flat < 1.0 + unit.kind.width / 2) {
				if (order == StarUnit.Order.PATROL) {
					unit.turnPatrol();
					best = Double.MAX_VALUE;
				} else {
					unit.finishOrder();
				}
				return;
			}
			if (flat < best - 0.3) {
				best = flat;
				stuck = 0;
			} else if (++stuck > 100) {
				unit.finishOrder();
				return;
			}
			if (--repath > 0) return;
			repath = 20;
			if (unit.kind.flying) {
				double y = ZergGoals.ground(unit, goal.x, goal.z) + Math.max(3, unit.kind.hoverHeight());
				unit.getMoveControl().setWantedPosition(goal.x, y, goal.z, 1.0);
			} else if (!unit.getNavigation().moveTo(goal.x, goal.y, goal.z, 1.0)) {
				unit.getMoveControl().setWantedPosition(goal.x, goal.y, goal.z, 1.0);
			}
		}
	}
}
