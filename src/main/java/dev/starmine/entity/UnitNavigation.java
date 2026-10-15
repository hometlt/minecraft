package dev.starmine.entity;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;

/**
 * Ground navigation of StarMine units (pathfinding tests, docs/CLOUD_TASK.md stage D):
 * - StarMine buildings are solid entities the game's pathfinder does not see: a unit planned a way
 *   straight through a barracks and then stood pushing against it. Here the cells a building takes are
 *   blocked, so the way goes round (a lowered depot of the unit's side is walked over);
 * - a way is planned up to {@link #PLAN_RANGE} blocks however short the unit's sight (a terran's sight
 *   is about 15 blocks: a detour round a cliff or a river to a ramp was out of its planner's reach, it
 *   walked to the cliff and gave up), and farther for a far goal (see {@link #reach}).
 */
final class UnitNavigation extends GroundPathNavigation {
	/** Blocks of planning reach at the least, and at the most (see {@link #reach}). */
	static final float PLAN_RANGE = 48, MAX_RANGE = 128;
	/** Nodes one search may visit (vanilla: 16 per block of follow range, 240 for a marine). */
	static final int NODES = 2048;

	UnitNavigation(Mob mob, Level level) {
		super(mob, level);
		// CLOUD_TASK_2 stage 4: ground units neither swim nor walk the bottom; water is crossed by a transport or a
		// bridge (colony/Engineering, colony/Ferry)
		mob.setPathfindingMalus(PathType.WATER, -1);
	}

	/** Bodies wider than this follow their way node by node (see {@link #followThePath}). */
	static final float BIG = 2f, WAYPOINT = 0.9f;

	/**
	 * A big body goes on to the next node of its way only once it stands on this one (within
	 * {@link #WAYPOINT}). The game lets a body go on within half its width of the node's corner cell and
	 * cut corners: after the units doubled in size a tank (5.8 wide) cut the corner of the passage workers
	 * had cut for it and stood against the wall's edge with its way open.
	 */
	@Override
	protected void followThePath() {
		if (this.mob.getBbWidth() <= BIG) {
			super.followThePath();
			return;
		}
		net.minecraft.world.phys.Vec3 here = this.getTempMobPos(), node = this.path.getEntityPosAtNode(this.mob, this.path.getNextNodeIndex());
		this.maxDistanceToWaypoint = WAYPOINT;
		if (Math.abs(this.mob.getX() - node.x) < WAYPOINT && Math.abs(this.mob.getZ() - node.z) < WAYPOINT && Math.abs(this.mob.getY() - node.y) < 1.0)
			this.path.advance();
		this.doStuckDetection(here);
	}

	@Override
	protected PathFinder createPathFinder(int maxVisitedNodes) {
		this.nodeEvaluator = new BuildingAware();
		this.nodeEvaluator.setCanPassDoors(true);
		return new PathFinder(this.nodeEvaluator, Math.max(maxVisitedNodes, NODES));
	}

	@Override
	protected Path createPath(Set<BlockPos> targets, int regionOffset, boolean offsetUpward, int accuracy) {
		return this.createPath(targets, regionOffset, offsetUpward, accuracy, reach(targets));
	}

	/**
	 * How far a way may run. The game counts it along the walked way, and its search is greedy: it first
	 * walks to the cliff in front of the goal and along it, so the way it finds to a ramp is longer than
	 * the best one (a goal 22 blocks away across a cliff with a ramp 20 blocks aside took a way of ~66
	 * blocks, and with a reach of 48 or 64 the marine stood at the cliff). Three times the straight
	 * distance and a margin, within {@link #PLAN_RANGE}..{@link #MAX_RANGE}; the visited nodes stay
	 * {@link #NODES}, which is what a search costs.
	 */
	private float reach(Set<BlockPos> targets) {
		double far = Double.MAX_VALUE;
		for (BlockPos target : targets) far = Math.min(far, Math.sqrt(target.distToCenterSqr(this.mob.position())));
		float range = (float) Math.min(MAX_RANGE, Math.max(PLAN_RANGE, far * 3 + 16));
		return Math.max(range, (float) this.mob.getAttributeValue(Attributes.FOLLOW_RANGE));
	}

	/** Walking nodes, with the cells of StarMine buildings near the unit blocked (gathered once per path). */
	private static final class BuildingAware extends WalkNodeEvaluator {
		private final LongSet buildings = new LongOpenHashSet();

		@Override
		public void prepare(PathNavigationRegion region, Mob mob) {
			super.prepare(region, mob);
			buildings.clear();
			AABB area = mob.getBoundingBox().inflate(PLAN_RANGE + 8, 16, PLAN_RANGE + 8);
			for (StarUnit building : mob.level().getEntitiesOfClass(StarUnit.class, area, unit -> unit != mob && unit.isAlive() && unit.kind.structure()
				&& !(unit.kind == UnitKind.SUPPLY_DEPOT && unit.mode() == dev.starmine.ability.Modes.LOWERED && dev.starmine.faction.Faction.allied(unit, mob)))) {
				// the cells whose middle the building covers: a cell it only grazes stays open (a worker mining
				// between a nexus and its minerals stood in one, found no way out and gave up every building)
				AABB box = building.getBoundingBox();
				for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(box.minX + 0.5, box.minY, box.minZ + 0.5),
					BlockPos.containing(box.maxX - 0.5, box.maxY - 0.05, box.maxZ - 0.5)))
					buildings.add(cell.asLong());
			}
			// and never where the unit itself stands
			AABB own = mob.getBoundingBox();
			for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(own.minX, own.minY, own.minZ), BlockPos.containing(own.maxX, own.maxY, own.maxZ)))
				buildings.remove(cell.asLong());
		}

		/**
		 * A wide body's way starts at its corner cell. The game starts it at the cell under the body's middle
		 * but reads every other node as a corner (the body's middle at the node plus half its width): the
		 * first point a tank (5.8 wide) was led to lay 3 blocks aside of it, in the rock beside a tunnel's
		 * mouth, and it stood pushing there.
		 */
		@Override
		public net.minecraft.world.level.pathfinder.Node getStart() {
			net.minecraft.world.level.pathfinder.Node start = super.getStart();
			if (start == null || this.mob.getBbWidth() <= BIG) return start;
			double half = (int) (this.mob.getBbWidth() + 1.0F) * 0.5;
			int x0 = net.minecraft.util.Mth.floor(this.mob.getX() - half + 0.5), z0 = net.minecraft.util.Mth.floor(this.mob.getZ() - half + 0.5);
			// the corner whose cells are free, of this one and those next to it: a tank 5.8 wide standing a block off the
			// middle of a passage 6 wide had its corner cell in the wall, and no way began there
			for (int r = 0; r <= 1; r++)
				for (int dx = -r; dx <= r; dx++)
					for (int dz = -r; dz <= r; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
						if (this.mob.getPathfindingMalus(this.getCachedPathType(x0 + dx, start.y, z0 + dz)) >= 0)
							return this.getStartNode(new BlockPos(x0 + dx, start.y, z0 + dz));
					}
			return this.getStartNode(new BlockPos(x0, start.y, z0));
		}

		@Override
		public PathType getPathType(PathfindingContext context, int x, int y, int z) {
			if (!buildings.isEmpty() && buildings.contains(BlockPos.asLong(x, y, z))) return PathType.BLOCKED;
			return super.getPathType(context, x, y, z);
		}
	}
}
