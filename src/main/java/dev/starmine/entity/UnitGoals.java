package dev.starmine.entity;

import java.util.EnumSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
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
public final class UnitGoals {
	private UnitGoals() {
	}

	/** Called from the mob's constructor, before its `kind` field is set: the kind comes as an argument. */
	static void register(StarUnit unit, UnitKind kind, GoalSelector goals, GoalSelector targets) {
		goals.addGoal(1, new Orders(unit));
		switch (kind.attack) {
			case NONE -> {
			}
			case MELEE, SPLASH, EXPLODE -> goals.addGoal(2, new Strike(unit));
			default -> goals.addGoal(2, new Shoot(unit));
		}
		if (kind.worker()) goals.addGoal(3, new DroneWork(unit));
		else goals.addGoal(4, new ZergGoals.ReturnHome(unit, kind.race == UnitKind.Race.ZERG ? 24 : 4 + kind.width)); // a big body pushed aside stays
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
					&& !(other instanceof StarUnit cloaked && !dev.starmine.ability.Cloak.seen(cloaked, unit))) {
				// vanilla looks only 4 blocks up and down: a carrier hovering 8 blocks up never saw the ground under it
				@Override
				protected net.minecraft.world.phys.AABB getTargetSearchArea(double range) {
					return unit.flying() ? unit.getBoundingBox().inflate(range, range, range) : super.getTargetSearchArea(range);
				}
			});
	}

	/** Whether it may fight now: it has a weapon in its mode and states, and does not ride a transport (a bunker's garrison does fight). */
	static boolean armed(StarUnit unit) {
		return unit.weapon().range() > 0 && (!unit.isPassenger() || dev.starmine.ability.Transport.firesFrom(unit.getVehicle()));
	}

	/**
	 * The melee attack, SC2's way: walk up until the target is within reach, stand, strike at the
	 * weapon's period by the unit's own cooldown. (Vanilla's goal waits a second between blows whatever
	 * the weapon — a zergling bit at half its pace —, strikes a new target at once, and keeps walking
	 * into its target, so a zergling slid off a thor's side and lost every fourth bite catching up.)
	 * Not while it cannot fight ({@link #armed}: burrowed, lifted, changing its mode, riding).
	 */
	static final class Strike extends Goal {
		private final StarUnit unit;
		private LivingEntity target;
		private int repath;

		Strike(StarUnit unit) {
			this.unit = unit;
			this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
		}

		@Override
		public boolean canUse() {
			LivingEntity aim = unit.getTarget();
			if (aim == null || !aim.isAlive() || !armed(unit)) return false;
			target = aim;
			return true;
		}

		@Override
		public boolean canContinueToUse() {
			return canUse();
		}

		@Override
		public void start() {
			repath = 0;
		}

		@Override
		public void stop() {
			target = null;
			unit.getNavigation().stop();
		}

		@Override
		public boolean requiresUpdateEveryTick() {
			return true;
		}

		@Override
		public void tick() {
			if (target == null) return;
			unit.getLookControl().setLookAt(target, 30.0f, 30.0f);
			boolean close = withinReach(unit, target);
			if (close) unit.getNavigation().stop();
			else if (--repath <= 0) {
				repath = 4 + unit.getRandom().nextInt(4);
				unit.getNavigation().moveTo(target, 1.15);
			}
			if (close && unit.weaponReady() && unit.getSensing().hasLineOfSight(target)) {
				unit.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
				unit.doHurtTarget(target);
				unit.startCooldown(Math.max(4, unit.weapon().interval()));
			}
		}
	}

	/**
	 * Melee reach SC2's way: from edge to edge, the weapon's SC2 range (0.1 for most, an ultralisk's 1)
	 * x BLOCKS_PER_UNIT and 0.3 blocks for bodies that push each other. (Vanilla's reach is the attacker's box grown
	 * by 0.83 blocks: zerglings and zealots struck from the second row, and melee armies beat ranged ones of
	 * equal cost far more than in SC2.)
	 */
	public static boolean withinReach(StarUnit unit, LivingEntity target) {
		double reach = (unit.kind == UnitKind.ULTRALISK ? 1.0 : 0.1) * UnitKind.BLOCKS_PER_UNIT + 0.3;
		double dx = unit.getX() - target.getX(), dz = unit.getZ() - target.getZ();
		double edge = Math.sqrt(dx * dx + dz * dz) - (unit.getBbWidth() + target.getBbWidth()) / 2;
		boolean level = unit.getY() < target.getY() + target.getBbHeight() + 0.5 && target.getY() < unit.getY() + unit.getBbHeight() + 0.5;
		return edge <= reach && level;
	}

	/**
	 * The ranged attack, SC2's way (the balance stand found vanilla's skeleton goal wrong for it on three
	 * counts): the weapon ({@link StarUnit#weapon}: reach, period; stim, siege…) is read each tick, not
	 * built into the goal; the cooldown is the unit's own, so a new target or a timed state does not
	 * start it over (each concussive shell rebuilt a stalker's goal and its 27-tick wait began again — it
	 * fired a quarter of its shots); the period does not grow with distance (vanilla's skeleton waits up
	 * to 10 ticks more at its reach: 22 ticks instead of a marine's 12); and it fires only within reach
	 * (to the target's edge, as SC2 measures it), walking up until then.
	 */
	static final class Shoot extends Goal {
		private final StarUnit unit;
		private LivingEntity target;
		private int seeTime;

		Shoot(StarUnit unit) {
			this.unit = unit;
			this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
		}

		@Override
		public boolean canUse() {
			LivingEntity aim = unit.getTarget();
			if (aim == null || !aim.isAlive() || !armed(unit)) return false;
			target = aim;
			return true;
		}

		@Override
		public boolean canContinueToUse() {
			return canUse();
		}

		@Override
		public void stop() {
			target = null;
			seeTime = 0;
		}

		@Override
		public boolean requiresUpdateEveryTick() {
			return true;
		}

		@Override
		public void tick() {
			if (target == null) return;
			dev.starmine.ability.Modes.Weapon weapon = unit.weapon();
			// SC2 ranges are from edge to edge: a thor (10 wide) shoots from its own edge, not its middle
			double reach = unit.distanceTo(target) - (target.getBbWidth() + unit.getBbWidth()) / 2;
			boolean sees = unit.getSensing().hasLineOfSight(target);
			seeTime = sees ? seeTime + 1 : 0;
			if (reach <= weapon.range() && seeTime >= 5 || !weapon.mobile()) unit.getNavigation().stop();
			else unit.getNavigation().moveTo(target, 1.0);
			unit.getLookControl().setLookAt(target, 30.0f, 30.0f);
			if (sees && reach <= weapon.range() && reach >= weapon.minRange() && unit.weaponReady()) {
				unit.performRangedAttack(target, 1.0f);
				unit.startCooldown(Math.max(4, weapon.interval()));
			}
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
		private Vec3 last, goalWas;
		/** Ticks without coming nearer before a unit gives its order up (owner 16.10: it holds to its order). */
		static final int PATIENCE = 240;

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
			if (!goal.equals(goalWas)) { // a new point: off at once (owner 16.10: as responsive as SC2), not at the next repath
				goalWas = goal;
				repath = 0;
				stuck = 0;
				best = Double.MAX_VALUE;
			}
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
			} else if ((stuck += crowded() ? (unit.tickCount % 4 == 0 ? 1 : 0) : 1) > PATIENCE) {
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
			if (flat < 2.5 + unit.getBbWidth() / 2 && Math.abs(unit.getY() - goal.y) < 1.5 && dry(unit, goal)
				&& unit.level().clip(new net.minecraft.world.level.ClipContext(unit.position().add(0, 0.5, 0), new Vec3(goal.x, unit.getY() + 0.5, goal.z),
					net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, unit)).getType()
					== net.minecraft.world.phys.HitResult.Type.MISS) {
				unit.getNavigation().stop();
				unit.getMoveControl().setWantedPosition(goal.x, goal.y, goal.z, 1.0);
				return;
			}
			if (--repath > 0) return;
			repath = 20;
			if (!unit.getNavigation().moveTo(goal.x, goal.y, goal.z, 1.0) && dry(unit, goal)) {
				unit.getMoveControl().setWantedPosition(goal.x, goal.y, goal.z, 1.0);
			}
		}

		/**
		 * Held up by other units round it (a crowd at a narrow pass): its patience runs four times slower.
		 * The last of 24 units queueing through a pass 3 wide waited longer than 12 s and gave its order up.
		 */
		private boolean crowded() {
			return !unit.level().getEntitiesOfClass(StarUnit.class, unit.getBoundingBox().inflate(0.6, 0, 0.6),
				other -> other != unit && other.isAlive() && !other.kind.structure()).isEmpty();
		}

		/**
		 * No water on the straight line to the goal (CLOUD_TASK_2 stage 4): with no way there a unit used to
		 * walk straight at its point, into a river; now it stays on the bank.
		 */
		private static boolean dry(StarUnit unit, Vec3 goal) {
			double dx = goal.x - unit.getX(), dz = goal.z - unit.getZ(), length = Math.sqrt(dx * dx + dz * dz);
			for (double t = 0; t <= length; t += 0.5) {
				net.minecraft.core.BlockPos feet = net.minecraft.core.BlockPos.containing(unit.getX() + dx / Math.max(length, 1e-6) * t, unit.getY() + 0.2, unit.getZ() + dz / Math.max(length, 1e-6) * t);
				if (!unit.level().getFluidState(feet).isEmpty() || !unit.level().getFluidState(feet.below()).isEmpty()) return false;
			}
			return true;
		}
	}
}
