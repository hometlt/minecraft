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
 * Drone pathfinding (owner: drones always have a way to their goal), and the way workers cut for an
 * army ({@link dev.starmine.colony.Engineering}: a body as wide and tall as the army's largest ground
 * unit). An A* search over positions of the body ({@link Body}; a drone's is 2x2x2; a position is the
 * body's lowest corner block) where every move may cost
 * digging: walking a step is cheap, cutting through a wall costs by the blocks' hardness, a stair step
 * up or down costs a little more, a gap is crossed by a bridge of fill blocks or a short drop. Blocks
 * the drone may not break — water or lava at or next to them, metal, very hard blocks, block entities —
 * and the floors of the hive's routes (so a new tunnel never cuts the way to the hatchery or a target)
 * are never planned. When the goal is out of the search budget the plan leads as close as it got.
 */
public final class DronePlanner {
	/** One move: where the body's corner ends up, what is dug first, where fill goes under it. */
	public record Step(BlockPos feet, List<BlockPos> dig, List<BlockPos> fill) {
	}

	/**
	 * The space a move needs: `width` x `width` blocks across, `height` tall, planned at most `reach`
	 * blocks horizontally from the start. A gap under a drone is bridged by two blocks on a diagonal;
	 * a wider body gets a whole floor.
	 */
	public record Body(int width, int height, int reach) {
		public static final Body DRONE = new Body(2, 2, 40);

		/**
		 * The body for a unit of this size: the blocks the game's own pathfinder wants free for it (its
		 * size plus one, rounded down: a thor 5.0 wide walks only where 6 are free; a tunnel 5 wide let
		 * the marine and the tank through and the thor stood at its mouth), at least 1x2.
		 */
		public static Body of(double width, double height, int reach) {
			return new Body(Math.max(1, (int) Math.floor(width + 1)), Math.max(2, (int) Math.floor(height + 1)), reach);
		}
	}

	private record Node(BlockPos feet, double g, double f, Node parent, Step step) {
	}

	private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

	private DronePlanner() {
	}

	/** The body corner for a drone standing at this position (its 1.8-wide body centred on a grid line). */
	static BlockPos feetOf(Vec3 position) {
		return feetOf(position, Body.DRONE);
	}

	/** The body corner for a body standing (centred) at this position. */
	public static BlockPos feetOf(Vec3 position, Body body) {
		double half = body.width / 2.0;
		return BlockPos.containing(Math.floor(position.x - half + 0.5), position.y + 0.01, Math.floor(position.z - half + 0.5));
	}

	/** Where the drone stands when its body corner is at `feet`. */
	static Vec3 centre(BlockPos feet) {
		return centre(feet, Body.DRONE);
	}

	/** Where a body stands when its corner is at `feet`. */
	public static Vec3 centre(BlockPos feet, Body body) {
		return new Vec3(feet.getX() + body.width / 2.0, feet.getY(), feet.getZ() + body.width / 2.0);
	}

	/**
	 * Plans from `start` to within `arrive` blocks of `target` (centre to centre), within 40 blocks
	 * horizontally of the start and at most `budget` expanded positions. `dig` allows breaking blocks,
	 * `fill` is how many bridge blocks the drone has. Null when no step makes progress.
	 */
	static List<Step> plan(ServerLevel level, BlockPos start, Vec3 target, double arrive, Set<Long> routes, boolean dig, int fill, int budget) {
		return plan(level, start, target, arrive, routes, dig, fill, budget, Body.DRONE);
	}

	/**
	 * What the planner has cost so far (for the pathfinding tests' measurements, docs/CLOUD_REPORT.md):
	 * searches, positions expanded (all and the most in one search), time spent (all and the longest).
	 */
	public static final class Stats {
		public long searches, expanded, maxExpanded, nanos, maxNanos;

		public void reset() {
			searches = expanded = maxExpanded = nanos = maxNanos = 0;
		}

		@Override
		public String toString() {
			return String.format(java.util.Locale.ROOT, "searches %d, expanded %d (max %d in one), time %.2f ms (max %.2f ms in one)",
				searches, expanded, maxExpanded, nanos / 1e6, maxNanos / 1e6);
		}
	}

	public static final Stats STATS = new Stats();

	/** {@link #plan} for any body; costs follow how long a worker takes: walking, digging by hardness, bridging. */
	public static List<Step> plan(ServerLevel level, BlockPos start, Vec3 target, double arrive, Set<Long> routes, boolean dig, int fill, int budget, Body body) {
		return plan(level, start, target, arrive, routes, dig, fill, budget, body, null);
	}

	/** {@link #plan}, never breaking blocks `keep` names (a worker's way past ore it cannot carry now). */
	public static List<Step> plan(ServerLevel level, BlockPos start, Vec3 target, double arrive, Set<Long> routes, boolean dig, int fill, int budget, Body body,
			java.util.function.Predicate<BlockState> keep) {
		long began = System.nanoTime();
		int[] expanded = new int[1];
		List<Step> steps = search(level, start, target, arrive, routes, dig, fill, budget, body, expanded, keep);
		long spent = System.nanoTime() - began;
		STATS.searches++;
		STATS.expanded += expanded[0];
		STATS.maxExpanded = Math.max(STATS.maxExpanded, expanded[0]);
		STATS.nanos += spent;
		STATS.maxNanos = Math.max(STATS.maxNanos, spent);
		return steps;
	}

	private static List<Step> search(ServerLevel level, BlockPos start, Vec3 target, double arrive, Set<Long> routes, boolean dig, int fill, int budget, Body body,
			int[] count, java.util.function.Predicate<BlockState> keep) {
		Set<Long> buildings = buildings(level, start, body.reach());
		Cells cells = new Cells(level, routes, keep);
		// a wide body pays its whole face of rock (a thor: 30 blocks, ~110) for every step of a tunnel: a plain
		// A* first walked every free cell in front of a ridge and ran out of nodes there. Weighted toward the
		// goal by about that price, it cuts its tunnel straight (weighted A*: a way not the cheapest, but found)
		double weight = body.width() <= 2 ? 1.1 : 1.1 + body.width() * body.height() * 4;
		PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
		Map<Long, Double> best = new HashMap<>();
		Node first = new Node(start, 0, heuristic(centre(start, body), target), null, null);
		open.add(first);
		best.put(start.asLong(), 0.0);
		Node closest = first;
		int expanded = 0;
		while (!open.isEmpty() && expanded < budget) {
			Node node = open.poll();
			if (node.g > best.getOrDefault(node.feet.asLong(), Double.MAX_VALUE)) continue;
			expanded++;
			count[0] = expanded;
			double h = centre(node.feet, body).distanceTo(target);
			if (h <= arrive) return path(node);
			if (h < centre(closest.feet, body).distanceTo(target)) closest = node;
			for (Direction dir : HORIZONTAL)
				for (int dy = -1; dy <= 1; dy++) {
					Step step = move(cells, node.feet, dir, dy, dig, fill, body, buildings);
					if (step == null) continue;
					BlockPos to = step.feet;
					if (Math.abs(to.getX() - start.getX()) > body.reach || Math.abs(to.getZ() - start.getZ()) > body.reach) continue;
					double cost = node.g + 1 + (to.getY() != node.feet.getY() ? 0.5 : 0) + digCost(level, step) + fillCost(step, body);
					// owner: to get up or down, cut (or build) stairs rather than long level tunnels
					double rise = target.y - centre(node.feet, body).y;
					if (!step.dig.isEmpty() && to.getY() == node.feet.getY() && Math.abs(rise) > 2) cost += 4;
					if (cost >= best.getOrDefault(to.asLong(), Double.MAX_VALUE)) continue;
					best.put(to.asLong(), cost);
					open.add(new Node(to, cost, cost + heuristic(centre(to, body), target) * weight, node, step));
				}
		}
		return closest == first || centre(closest.feet, body).distanceTo(target) > centre(start, body).distanceTo(target) - 2 ? null : path(closest);
	}

	/**
	 * What a step's fill costs: a bridge over a gap is shared by the width of the body that needs it
	 * (a wide body cannot cross otherwise); filling under a wide body that would step up onto a ledge
	 * costs as a block dug, so it digs the ledge instead of climbing onto a floor laid in the air.
	 */
	private static double fillCost(Step step, Body body) {
		if (step.fill.isEmpty()) return 0;
		boolean ledge = step.fill.size() < body.width * body.width;
		return step.fill.size() * (ledge && body.width > 2 ? 2.0 : 2.0 / Math.max(1, body.width - 1));
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
	/**
	 * The cells taken by buildings (StarMine structures are solid entities the game's pathfinders do not
	 * see): a way goes around them, never through.
	 */
	private static Set<Long> buildings(ServerLevel level, BlockPos start, int reach) {
		Set<Long> cells = new java.util.HashSet<>();
		net.minecraft.world.phys.AABB area = new net.minecraft.world.phys.AABB(start).inflate(reach + 8, 16, reach + 8);
		for (StarUnit building : level.getEntitiesOfClass(StarUnit.class, area, mob -> mob.kind.structure() && mob.isAlive())) {
			net.minecraft.world.phys.AABB box = building.getBoundingBox();
			// a worker standing in a building's footprint (just out of it, or put there) walks out: every
			// cell round it counted as the building, so no way out was ever found
			if (box.contains(start.getX() + 0.5, start.getY() + 0.5, start.getZ() + 0.5)) continue;
			for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX - 1e-3, box.maxY - 1e-3, box.maxZ - 1e-3)))
				cells.add(cell.asLong());
		}
		return cells;
	}

	private static Step move(Cells cells, BlockPos from, Direction dir, int dy, boolean dig, int fill, Body body, Set<Long> buildings) {
		BlockPos to = from.relative(dir).above(dy);
		List<BlockPos> breaks = new ArrayList<>();
		int w = body.width, h = body.height;
		// the body's space at its new place; a step up also needs headroom over the old place,
		// a step down needs the old height above the new place to pass through
		if (!clear(cells, to, 0, h - 1, w, breaks, dig, buildings)) return null;
		if (dy > 0 && !clear(cells, from, h, h, w, breaks, dig, buildings)) return null;
		if (dy < 0 && !clear(cells, to, h, h, w, breaks, dig, buildings)) return null;
		List<BlockPos> bridge = new ArrayList<>();
		int floor = 0;
		for (int x = 0; x < w; x++)
			for (int z = 0; z < w; z++) {
				BlockPos under = to.offset(x, -1, z);
				if (!breaks.contains(under) && cells.solid(under)) floor++;
			}
		if (floor == 0) {
			// a gap: a short drop to a floor below, else a bridge of fill
			for (int drop = 1; drop <= 3; drop++) {
				BlockPos lower = to.below(drop);
				if (!clear(cells, lower, 0, 0, w, null, false, buildings)) break;
				int support = 0;
				for (int x = 0; x < w; x++) for (int z = 0; z < w; z++) if (cells.solid(lower.offset(x, -1, z))) support++;
				if (support > 0 && breaks.isEmpty()) return new Step(lower, List.of(), List.of());
			}
			if (w == 2) { // a drone: two blocks on a diagonal hold it
				bridge.add(to.offset(0, -1, 0));
				bridge.add(to.offset(1, -1, 1));
			} else { // a wider body: a whole floor (a vehicle must not hang over holes)
				for (int x = 0; x < w; x++)
					for (int z = 0; z < w; z++) {
						BlockPos under = to.offset(x, -1, z);
						if (!cells.solid(under)) bridge.add(under); // over water too: the bridge's stone takes the water's place
					}
			}
			if (fill < bridge.size()) return null;
		} else if (w > 2 && floor < w * w) {
			// a wide body stands on its whole floor: the holes under it are filled (a thor's way, 11 wide, stepped
			// up onto the first row of a ridge with the rest of it over the air, and its workers' place was there)
			for (int x = 0; x < w; x++)
				for (int z = 0; z < w; z++) {
					BlockPos under = to.offset(x, -1, z);
					if (!breaks.contains(under) && !cells.solid(under)) bridge.add(under);
				}
			if (fill < bridge.size()) return null;
		}
		return new Step(to, breaks, bridge);
	}

	/**
	 * Whether the body's cells at `feet` from height `fromY` to `toY` are free or may be dug (added
	 * to `breaks`); `breaks` null means they must already be free.
	 */
	private static boolean clear(Cells cells, BlockPos feet, int fromY, int toY, int w, List<BlockPos> breaks, boolean dig, Set<Long> buildings) {
		for (int y = fromY; y <= toY; y++)
			for (int x = 0; x < w; x++)
				for (int z = 0; z < w; z++) {
					BlockPos cell = feet.offset(x, y, z);
					if (buildings.contains(cell.asLong())) return false; // a building stands there
					if (!cells.solid(cell)) {
						if (cells.fluid(cell)) return false;
						continue;
					}
					if (breaks == null || !dig || !cells.breakable(cell)) return false;
					if (!breaks.contains(cell)) breaks.add(cell);
				}
		return true;
	}

	/**
	 * The search budget for a body: `budget` positions for a drone's 2x2x2, fewer for a wider body (each
	 * of its positions reads as many more cells), so one search stays within a few milliseconds of a
	 * server tick whatever the body (a thor's 5x5x6 way: 1/19 of the positions).
	 */
	public static int budgetFor(Body body, int budget) {
		return Math.max(600, budget * 8 / Math.max(8, body.width() * body.width() * body.height()));
	}

	/**
	 * What one search knows of the cells it looked at (solid, fluid, breakable), so a wide body's many
	 * overlapping cells are read from the world once: a thor's way looked at each cell dozens of times.
	 */
	private static final class Cells {
		private static final byte KNOWN = 1, SOLID = 2, FLUID = 4, BREAKABLE = 8;
		final ServerLevel level;
		final Set<Long> routes;
		final java.util.function.Predicate<BlockState> keep;
		private final it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap known = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();

		Cells(ServerLevel level, Set<Long> routes, java.util.function.Predicate<BlockState> keep) {
			this.level = level;
			this.routes = routes;
			this.keep = keep;
		}

		private byte of(BlockPos pos) {
			long key = pos.asLong();
			byte info = known.get(key);
			if ((info & KNOWN) != 0) return info;
			info = KNOWN;
			if (DronePlanner.solid(level, pos)) info |= SOLID;
			if (!level.getFluidState(pos).isEmpty()) info |= FLUID;
			if ((info & SOLID) != 0 && DronePlanner.breakable(level, pos, routes) && (keep == null || !keep.test(level.getBlockState(pos)))) info |= BREAKABLE;
			known.put(key, info);
			return info;
		}

		boolean solid(BlockPos pos) {
			return (of(pos) & SOLID) != 0;
		}

		boolean fluid(BlockPos pos) {
			return (of(pos) & FLUID) != 0;
		}

		boolean breakable(BlockPos pos) {
			return (of(pos) & BREAKABLE) != 0;
		}
	}

	public static boolean solid(ServerLevel level, BlockPos pos) {
		return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}

	/**
	 * A block a drone may break: no block entity, no water or lava at or next to it, not metal or very
	 * hard (owner: not iron, obsidian…), and not the floor of one of the hive's routes.
	 */
	/** The valuable a worker at `at` would see: within its 10-block reach, walls or not. */
	public static BlockPos oreFor(ServerLevel level, BlockPos at) {
		return DroneWork.findOreFor(level, at);
	}

	public static boolean breakable(ServerLevel level, BlockPos pos, Set<Long> routes) {
		BlockState state = level.getBlockState(pos);
		if (dev.starmine.world.Skirmish.terrainLocked(level)) return false; // owner 22.10: a skirmish's land is not dug
		if (!state.getFluidState().isEmpty() || level.getBlockEntity(pos) != null) return false;
		if (dev.starmine.world.BaseGround.protectedTop(state)) return false; // owner 17.10: a base's concrete is not dug
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
