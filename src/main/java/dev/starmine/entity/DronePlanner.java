package dev.starmine.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Drone pathfinding (owner: drones always have a way to their goal). An A* search over positions of
 * the drone's 2x2x2 body (a position is the body's lowest corner block) where every move may cost
 * digging: walking a step is cheap, cutting through a wall costs by the blocks' hardness, a stair step
 * up or down costs a little more, a gap is crossed by a bridge of fill blocks or a short drop. Blocks
 * the drone may not break — water or lava at or next to them, metal, very hard blocks, block entities —
 * and the floors of the hive's routes (so a new tunnel never cuts the way to the hatchery or a target)
 * are never planned. When the goal is out of the search budget the plan leads as close as it got.
 */
final class DronePlanner {
	/** One move: where the body's corner ends up, what is dug first, where fill goes under it. */
	record Step(BlockPos feet, List<BlockPos> dig, List<BlockPos> fill) {
	}

	private record Node(BlockPos feet, double g, double f, Node parent, Step step) {
	}

	private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

	private DronePlanner() {
	}

	/** The body corner for a drone standing at this position (its 1.8-wide body centred on a grid line). */
	static BlockPos feetOf(Vec3 position) {
		return BlockPos.containing(Math.round(position.x) - 1, position.y + 0.01, Math.round(position.z) - 1);
	}

	/** Where the drone stands when its body corner is at `feet`. */
	static Vec3 centre(BlockPos feet) {
		return new Vec3(feet.getX() + 1, feet.getY(), feet.getZ() + 1);
	}

	/**
	 * Plans from `start` to within `arrive` blocks of `target` (centre to centre), within 40 blocks
	 * horizontally of the start and at most `budget` expanded positions. `dig` allows breaking blocks,
	 * `fill` is how many bridge blocks the drone has. Null when no step makes progress.
	 */
	static List<Step> plan(ServerLevel level, BlockPos start, Vec3 target, double arrive, Set<Long> routes, boolean dig, int fill, int budget) {
		PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
		Map<Long, Double> best = new HashMap<>();
		Node first = new Node(start, 0, heuristic(centre(start), target), null, null);
		open.add(first);
		best.put(start.asLong(), 0.0);
		Node closest = first;
		int expanded = 0;
		while (!open.isEmpty() && expanded < budget) {
			Node node = open.poll();
			if (node.g > best.getOrDefault(node.feet.asLong(), Double.MAX_VALUE)) continue;
			expanded++;
			double h = centre(node.feet).distanceTo(target);
			if (h <= arrive) return path(node);
			if (h < centre(closest.feet).distanceTo(target)) closest = node;
			for (Direction dir : HORIZONTAL)
				for (int dy = -1; dy <= 1; dy++) {
					Step step = move(level, node.feet, dir, dy, routes, dig, fill);
					if (step == null) continue;
					BlockPos to = step.feet;
					if (Math.abs(to.getX() - start.getX()) > 40 || Math.abs(to.getZ() - start.getZ()) > 40) continue;
					double cost = node.g + 1 + (to.getY() != node.feet.getY() ? 0.5 : 0) + digCost(level, step) + step.fill.size() * 2.0;
					// owner: to get up or down, cut (or build) stairs rather than long level tunnels
					double rise = target.y - centre(node.feet).y;
					if (!step.dig.isEmpty() && to.getY() == node.feet.getY() && Math.abs(rise) > 2) cost += 4;
					if (cost >= best.getOrDefault(to.asLong(), Double.MAX_VALUE)) continue;
					best.put(to.asLong(), cost);
					open.add(new Node(to, cost, cost + heuristic(centre(to), target) * 1.1, node, step));
				}
		}
		return closest == first || centre(closest.feet).distanceTo(target) > centre(start).distanceTo(target) - 2 ? null : path(closest);
	}

	/** Distance to the goal with height weighted 1.5x: climbing is what most needs digging. */
	private static double heuristic(Vec3 from, Vec3 target) {
		double dx = target.x - from.x, dz = target.z - from.z;
		return Math.sqrt(dx * dx + dz * dz) + Math.abs(target.y - from.y) * 1.5;
	}

	private static List<Step> path(Node node) {
		List<Step> steps = new ArrayList<>();
		for (Node n = node; n != null && n.step != null; n = n.parent) steps.add(n.step);
		Collections.reverse(steps);
		return steps;
	}

	/** A move of the body one block toward `dir`, `dy` up or down; null when it cannot be made. */
	private static Step move(ServerLevel level, BlockPos from, Direction dir, int dy, Set<Long> routes, boolean dig, int fill) {
		BlockPos to = from.relative(dir).above(dy);
		List<BlockPos> breaks = new ArrayList<>();
		// the body's space at its new place; a step up also needs headroom over the old place,
		// a step down needs the old height above the new place to pass through
		if (!clear(level, to, 0, 1, breaks, routes, dig)) return null;
		if (dy > 0 && !clear(level, from, 2, 2, breaks, routes, dig)) return null;
		if (dy < 0 && !clear(level, to, 2, 2, breaks, routes, dig)) return null;
		List<BlockPos> bridge = new ArrayList<>();
		int floor = 0;
		for (int x = 0; x <= 1; x++)
			for (int z = 0; z <= 1; z++) {
				BlockPos under = to.offset(x, -1, z);
				if (!breaks.contains(under) && solid(level, under)) floor++;
			}
		if (floor == 0) {
			// a gap: a short drop to a floor below, else a bridge of fill
			for (int drop = 1; drop <= 3; drop++) {
				BlockPos lower = to.below(drop);
				if (!clear(level, lower, 0, 0, null, routes, false)) break;
				int support = 0;
				for (int x = 0; x <= 1; x++) for (int z = 0; z <= 1; z++) if (solid(level, lower.offset(x, -1, z))) support++;
				if (support > 0 && breaks.isEmpty()) return new Step(lower, List.of(), List.of());
			}
			if (fill < 2) return null;
			bridge.add(to.offset(0, -1, 0));
			bridge.add(to.offset(1, -1, 1));
		}
		return new Step(to, breaks, bridge);
	}

	/**
	 * Whether the body's cells at `feet` from height `fromY` to `toY` are free or may be dug (added
	 * to `breaks`); `breaks` null means they must already be free.
	 */
	private static boolean clear(ServerLevel level, BlockPos feet, int fromY, int toY, List<BlockPos> breaks, Set<Long> routes, boolean dig) {
		for (int y = fromY; y <= toY; y++)
			for (int x = 0; x <= 1; x++)
				for (int z = 0; z <= 1; z++) {
					BlockPos cell = feet.offset(x, y, z);
					if (!solid(level, cell)) {
						if (!level.getFluidState(cell).isEmpty()) return false;
						continue;
					}
					if (breaks == null || !dig || !breakable(level, cell, routes)) return false;
					if (!breaks.contains(cell)) breaks.add(cell);
				}
		return true;
	}

	static boolean solid(ServerLevel level, BlockPos pos) {
		return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}

	/**
	 * A block a drone may break: no block entity, no water or lava at or next to it, not metal or very
	 * hard (owner: not iron, obsidian…), and not the floor of one of the hive's routes.
	 */
	static boolean breakable(ServerLevel level, BlockPos pos, Set<Long> routes) {
		BlockState state = level.getBlockState(pos);
		if (!state.getFluidState().isEmpty() || level.getBlockEntity(pos) != null) return false;
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness < 0 || hardness >= 5) return false;
		SoundType sound = state.getSoundType();
		if (sound == SoundType.METAL || sound == SoundType.ANVIL || sound == SoundType.COPPER
			|| sound == SoundType.NETHERITE_BLOCK || sound == SoundType.CHAIN) return false;
		for (Direction d : Direction.values()) if (!level.getFluidState(pos.relative(d)).isEmpty()) return false;
		if (routes != null) {
			BlockPos above = pos.above();
			if (routes.contains(above.asLong()) || routes.contains(above.west().asLong())
				|| routes.contains(above.north().asLong()) || routes.contains(above.west().north().asLong())) return false;
		}
		return true;
	}

	private static double digCost(ServerLevel level, Step step) {
		double cost = 0;
		for (BlockPos pos : step.dig) cost += 1.5 + level.getBlockState(pos).getDestroySpeed(level, pos) * 1.5;
		return cost;
	}
}
