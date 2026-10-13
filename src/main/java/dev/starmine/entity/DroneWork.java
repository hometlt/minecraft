package dev.starmine.entity;

import dev.starmine.StarMine;
import dev.starmine.block.StarMineBlocks;
import dev.starmine.creep.CreepGrowth;
import net.minecraft.world.level.block.MultifaceBlock;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveResources;
import dev.starmine.hive.HiveSavedData;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * A drone's work (stage 2 of docs/HIVE_DESIGN.md, reworked on the owner's play test), run while it
 * has no target:
 *
 * - SEEK: a natural tree nearby (logs with non-persistent leaves, so log houses stay), else an ore
 *   within a scan, else EXPLORE;
 * - TREE: the drone walks to the trunk's foot and, standing there, takes the tree from its top
 *   down, leaves with it, 8 logs a trip — it does not climb;
 * - MINE: walks to the ore if there is a way, otherwise digs a tunnel of 2x2x2 cubes toward it, with
 *   stair steps up and down; takes the whole vein and ores met on the way;
 * - CARRY: brings the load to the edge of its hatchery (minerals, vespene, hoarded diamonds) — when
 *   its valuable slot holds 8, or a precious ore, or before taking a second kind of resource;
 * - EXPLORE: walks ever farther; when walks find nothing, digs stairs deeper.
 *
 * It breaks only blocks within reach (like a player) and always finishes a block it has started
 * unless an enemy takes its attention. Stuck while walking, it bridges a gap or builds a step with
 * the stone it dug (its 8 fill slots), cuts stairs to get up, or digs through; underground it prefers
 * an existing way out; in a passage too narrow for it, it widens the passage first. Creep on a block
 * it digs is not lost: it lines the new cavity. Its valuable slot is shown in front of it; everything
 * drops if it dies. Yields come from `data/starmine/hive/resources.json`; with
 * `starmineHiveGriefing` off drones only explore. Trees and ores are claimed per drone.
 */
final class DroneWork extends Goal {
	private enum State { SEEK, TREE, MINE, CARRY, EXPLORE }

	private static final Map<BlockPos, UUID> CLAIMS = new HashMap<>();
	private static final UUID NOBODY = new UUID(0, 0);
	private static final double REACH = 3.6;
	private static final int TRAIL = 96;

	private final ZergMob drone;
	private State state = State.SEEK, resume = State.SEEK;
	private final List<BlockPos> logs = new ArrayList<>(), leaves = new ArrayList<>();
	private BlockPos treeFoot, mineTarget;
	private boolean deep;
	/** The cube being dug (blocks still to break) and where the drone stands after it. */
	private final Deque<BlockPos> cube = new ArrayDeque<>();
	private BlockPos cubeExit;
	private BlockPos breaking;
	private int breakTicks, breakNeeded, stateTicks, cubeTicks, misses, seekCooldown;
	private Vec3 walkGoal;
	private double bestDistance;
	private int stuckTicks;
	private boolean homeReachable = true;
	private Vec3 exploreTo;
	/** The cubes this drone dug, in order: underground it walks them back to the way out it made. */
	private final Deque<BlockPos> trail = new ArrayDeque<>();

	DroneWork(ZergMob drone) {
		this.drone = drone;
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		return drone.hiveId() != null && drone.getTarget() == null && !drone.isPassenger();
	}

	@Override
	public boolean canContinueToUse() {
		return canUse();
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void stop() {
		// an enemy took its attention: the cracks fade, the block is left
		cancelBreak();
	}

	@Override
	public void tick() {
		if (!(drone.level() instanceof ServerLevel level)) return;
		stateTicks++;
		// a block once started is finished first
		if (breaking != null) {
			if (breakBlock(level, breaking)) afterBreak(level);
			return;
		}
		if (!cube.isEmpty() || cubeExit != null) {
			digCube(level);
			return;
		}
		boolean griefing = level.getGameRules().getBoolean(StarMine.HIVE_GRIEFING);
		if (drone.loadFull() && state != State.CARRY) to(State.CARRY);
		switch (state) {
			case SEEK -> seek(level, griefing);
			case TREE -> tree(level);
			case MINE -> mine(level, griefing);
			case CARRY -> carry(level);
			case EXPLORE -> explore(level, griefing);
		}
	}

	private void to(State next) {
		if (next == State.CARRY && state != State.CARRY) resume = state == State.TREE || state == State.MINE ? state : State.SEEK;
		state = next;
		stateTicks = 0;
		walkGoal = null;
	}

	// ---- SEEK ----

	private void seek(ServerLevel level, boolean griefing) {
		if (--seekCooldown > 0) return;
		seekCooldown = 40;
		if (griefing && findTree(level)) {
			to(State.TREE);
			return;
		}
		if (griefing) {
			BlockPos ore = findOre(level, drone.blockPosition(), 12);
			if (ore != null) {
				claim(ore);
				mineTarget = ore;
				deep = false;
				to(State.MINE);
				return;
			}
		}
		if (drone.cargoBlocks > 0) {
			to(State.CARRY);
			return;
		}
		startExplore(level);
	}

	// ---- TREE ----

	private boolean findTree(ServerLevel level) {
		BlockPos centre = drone.blockPosition();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (int dx = -16; dx <= 16; dx++)
			for (int dz = -16; dz <= 16; dz++) {
				int x = centre.getX() + dx, z = centre.getZ() + dz;
				BlockPos pos = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1, z);
				for (int i = 0; i < 24 && pos.getY() > level.getMinBuildHeight(); i++, pos = pos.below()) {
					BlockState state = level.getBlockState(pos);
					if (state.is(BlockTags.LOGS)) {
						double d = pos.distSqr(centre);
						if (!CLAIMS.containsKey(pos) && d < bestDist) {
							best = pos;
							bestDist = d;
						}
						break;
					}
					if (!state.is(BlockTags.LEAVES)) break;
				}
			}
		return best != null && collectTree(level, best);
	}

	/** The connected logs of a natural tree (some of its leaves are not player-placed). */
	private boolean collectTree(ServerLevel level, BlockPos seed) {
		logs.clear();
		leaves.clear();
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>(List.of(seed));
		boolean natural = false;
		while (!queue.isEmpty() && logs.size() < 300) {
			BlockPos pos = queue.poll();
			if (!seen.add(pos)) continue;
			BlockState state = level.getBlockState(pos);
			if (state.is(BlockTags.LOGS)) {
				logs.add(pos);
				for (BlockPos near : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) queue.add(near.immutable());
			} else if (naturalLeaves(state)) {
				natural = true;
			}
		}
		if (!natural || logs.isEmpty()) {
			for (BlockPos log : logs) CLAIMS.putIfAbsent(log, NOBODY);
			logs.clear();
			return false;
		}
		Set<BlockPos> leafSeen = new HashSet<>();
		ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
		for (BlockPos log : logs) for (Direction d : Direction.values()) frontier.add(log.relative(d));
		for (int depth = 0; depth < 6 && !frontier.isEmpty() && leaves.size() < 800; depth++) {
			ArrayDeque<BlockPos> next = new ArrayDeque<>();
			for (BlockPos pos : frontier) {
				if (!leafSeen.add(pos)) continue;
				if (naturalLeaves(level.getBlockState(pos))) {
					leaves.add(pos);
					for (Direction d : Direction.values()) next.add(pos.relative(d));
				}
			}
			frontier = next;
		}
		logs.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
		leaves.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
		treeFoot = logs.get(logs.size() - 1);
		for (BlockPos log : logs) claim(log);
		return true;
	}

	private static boolean naturalLeaves(BlockState state) {
		return state.is(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT);
	}

	private void tree(ServerLevel level) {
		while (!logs.isEmpty() && !level.getBlockState(logs.get(0)).is(BlockTags.LOGS)) release(logs.remove(0));
		if (logs.isEmpty()) {
			for (BlockPos leaf : leaves) if (level.getBlockState(leaf).is(BlockTags.LEAVES)) level.destroyBlock(leaf, false, drone);
			leaves.clear();
			to(State.SEEK);
			return;
		}
		// stand at the foot of the trunk
		if (!reach(treeFoot) && !reach(treeFoot.above())) {
			if (!walk(level, Vec3.atBottomCenterOf(treeFoot), true)) {
				for (BlockPos log : logs) release(log);
				logs.clear();
				to(State.SEEK);
			}
			return;
		}
		drone.getNavigation().stop();
		BlockPos top = logs.get(0);
		int removed = 0;
		while (!leaves.isEmpty() && leaves.get(0).getY() >= top.getY() - 1 && removed < 12) {
			BlockPos leaf = leaves.remove(0);
			if (level.getBlockState(leaf).is(BlockTags.LEAVES)) {
				level.destroyBlock(leaf, false, drone);
				removed++;
			}
		}
		if (!drone.canTake(level.getBlockState(top))) { // another resource in its slot: deliver it first
			to(State.CARRY);
			return;
		}
		// taken from the top while the drone works at the foot
		breaking = top;
		breakTicks = 0;
		breakNeeded = Math.max(6, (int) (level.getBlockState(top).getDestroySpeed(level, top) * 12));
	}

	// ---- MINE ----

	static BlockPos findOre(ServerLevel level, BlockPos centre, int radius) {
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-radius, -radius, -radius), centre.offset(radius, radius, radius))) {
			if (CLAIMS.containsKey(pos) || !HiveResources.isOre(level.getBlockState(pos))) continue;
			double d = pos.distSqr(centre);
			if (d < bestDist) {
				best = pos.immutable();
				bestDist = d;
			}
		}
		return best;
	}

	private void mine(ServerLevel level, boolean griefing) {
		if (mineTarget == null || !griefing) {
			to(State.SEEK);
			return;
		}
		boolean ore = HiveResources.isOre(level.getBlockState(mineTarget));
		if (!deep && !ore) {
			release(mineTarget);
			if (!nextInVein(level)) to(State.SEEK);
			return;
		}
		if (ore && !drone.canTake(level.getBlockState(mineTarget))) { // deliver what it has first
			to(State.CARRY);
			return;
		}
		if (ore && reach(mineTarget)) {
			start(level, mineTarget);
			return;
		}
		if (deep && drone.blockPosition().distManhattan(mineTarget) <= 2) {
			deep = false;
			mineTarget = null;
			to(State.SEEK);
			return;
		}
		// an open way (a cave) is walked; otherwise the drone digs toward the target
		if (stateTicks % 40 == 1) {
			Path path = drone.getNavigation().createPath(mineTarget, 1);
			if (path != null && path.canReach() && path.getNodeCount() < 48) {
				drone.getNavigation().moveTo(path, 1.0);
				return;
			}
		}
		if (!drone.getNavigation().isDone()) return;
		if (!planCube(level, Vec3.atCenterOf(mineTarget), true)) {
			release(mineTarget);
			mineTarget = null;
			to(State.SEEK);
		}
	}

	private boolean nextInVein(ServerLevel level) {
		if (mineTarget == null) return false;
		for (BlockPos near : BlockPos.betweenClosed(mineTarget.offset(-1, -1, -1), mineTarget.offset(1, 1, 1))) {
			if (!CLAIMS.containsKey(near) && HiveResources.isOre(level.getBlockState(near))) {
				mineTarget = near.immutable();
				claim(mineTarget);
				return true;
			}
		}
		mineTarget = null;
		return false;
	}

	// ---- tunnels of 2x2x2 cubes with stair steps ----

	/**
	 * Plans the next cube toward a goal: two blocks forward along the main horizontal direction (an
	 * occasional sideways turn when `winding`), two wide, two high, one step up or down when the goal
	 * is above or below; a step gets a third block of headroom so the drone can climb or drop through.
	 * False when every direction meets water, lava or unbreakable blocks.
	 */
	private boolean planCube(ServerLevel level, Vec3 goal, boolean winding) {
		BlockPos feet = drone.blockPosition();
		double dx = goal.x - drone.getX(), dz = goal.z - drone.getZ(), dy = goal.y - drone.getY();
		Direction main = Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? Direction.EAST : Direction.WEST) : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
		int step = dy > 1.5 ? 1 : dy < -1.5 ? -1 : 0;
		List<Direction> order = new ArrayList<>();
		if (winding && drone.getRandom().nextFloat() < 0.2f) order.add(drone.getRandom().nextBoolean() ? main.getClockWise() : main.getCounterClockWise());
		order.add(main);
		order.add(main.getClockWise());
		order.add(main.getCounterClockWise());
		for (Direction dir : order) {
			List<BlockPos> blocks = cubeBlocks(feet, dir, step);
			// an ore of another kind than the load is not broken through (it would be lost)
			if (blocks.stream().allMatch(pos -> level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
				|| diggable(level, pos) && drone.canTake(level.getBlockState(pos)))) {
				cube.clear();
				for (BlockPos pos : blocks) if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) cube.add(pos);
				cubeExit = feet.relative(dir, 2).above(step);
				cubeTicks = 0;
				return true;
			}
		}
		return false;
	}

	/** The blocks of the next cube: forward 1..2, two wide, floor at the step, headroom at the step. */
	private static List<BlockPos> cubeBlocks(BlockPos feet, Direction dir, int step) {
		Direction side = dir.getClockWise();
		List<BlockPos> blocks = new ArrayList<>();
		for (int forward = 1; forward <= 2; forward++)
			for (int across = 0; across <= 1; across++) {
				int from = forward == 1 ? Math.min(0, step) : step;
				int to = forward == 1 ? Math.max(0, step) + (step != 0 ? 2 : 1) : step + 1;
				for (int up = from; up <= to; up++) blocks.add(feet.relative(dir, forward).relative(side, across).above(up));
			}
		if (step > 0) for (int across = 0; across <= 1; across++) blocks.add(feet.relative(side, across).above(2)); // headroom to step up
		return blocks;
	}

	private void digCube(ServerLevel level) {
		cubeTicks++;
		while (!cube.isEmpty() && level.getBlockState(cube.peek()).getCollisionShape(level, cube.peek()).isEmpty()) cube.poll();
		if (!cube.isEmpty()) {
			BlockPos next = cube.peek();
			if (reach(next) && diggable(level, next)) {
				start(level, next);
			} else {
				// out of reach or no longer safe: this cube is abandoned
				cube.clear();
				cubeExit = null;
			}
			return;
		}
		if (cubeExit == null) return;
		Vec3 exit = Vec3.atBottomCenterOf(cubeExit);
		if (drone.position().distanceToSqr(exit) < 1.2 || cubeTicks > 100) {
			trail.addLast(cubeExit);
			if (trail.size() > TRAIL) trail.pollFirst();
			cubeExit = null;
			BlockPos ore = findOre(level, drone.blockPosition(), 3); // ores met on the way
			if (ore != null && state == State.MINE && !deep) {
				release(mineTarget);
				mineTarget = ore;
				claim(ore);
			}
			return;
		}
		if (!drone.getNavigation().moveTo(exit.x, exit.y, exit.z, 1.0)) drone.getMoveControl().setWantedPosition(exit.x, exit.y, exit.z, 1.0);
	}

	/** A block a drone may break: breakable, no block entity, no water or lava at or next to it. */
	private static boolean diggable(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!state.getFluidState().isEmpty() || level.getBlockEntity(pos) != null) return false;
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness < 0 || hardness > 20) return false;
		for (Direction d : Direction.values()) if (!level.getFluidState(pos.relative(d)).isEmpty()) return false;
		return true;
	}

	// ---- walking, getting unstuck ----

	/**
	 * Walks toward a goal and notices when it stops getting closer: then it bridges a gap or builds a
	 * step with fill, or (when `mayDig`) digs a cube toward the goal. False when it cannot get closer.
	 */
	private boolean walk(ServerLevel level, Vec3 goal, boolean mayDig) {
		if (walkGoal == null || walkGoal.distanceToSqr(goal) > 1) {
			walkGoal = goal;
			bestDistance = Double.MAX_VALUE;
			stuckTicks = 0;
		}
		double distance = drone.position().distanceTo(goal);
		if (distance < bestDistance - 0.5) {
			bestDistance = distance;
			stuckTicks = 0;
		} else {
			stuckTicks++;
		}
		if (drone.getNavigation().isDone() || stateTicks % 40 == 1) travel(goal);
		if (stuckTicks < 60) return true;
		stuckTicks = 0;
		bestDistance = distance;
		return unstick(level, goal, mayDig);
	}

	private boolean unstick(ServerLevel level, Vec3 goal, boolean mayDig) {
		if (widen(level, goal)) return true;
		BlockPos feet = drone.blockPosition();
		double dx = goal.x - drone.getX(), dz = goal.z - drone.getZ();
		Direction dir = Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? Direction.EAST : Direction.WEST) : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
		BlockPos front = feet.relative(dir, 1);
		boolean frontFree = level.getBlockState(front).getCollisionShape(level, front).isEmpty();
		boolean frontFloor = !level.getBlockState(front.below()).getCollisionShape(level, front.below()).isEmpty();
		boolean griefing = level.getGameRules().getBoolean(StarMine.HIVE_GRIEFING);
		if (frontFree && !frontFloor && drone.fillCount() > 0) return drone.placeFill(front.below()); // a bridge block
		if (goal.y > drone.getY() + 1.5) {
			// it cannot get up: a step of stone, or stairs cut into the wall
			if (frontFree && frontFloor && drone.fillCount() > 0) return drone.placeFill(front);
			if (griefing) return planCube(level, goal, false);
		}
		if (mayDig && griefing) return planCube(level, goal, false);
		return false;
	}

	/**
	 * A passage too narrow or low for a 2-block drone: the solid blocks in its 2x2 cross-section a
	 * step ahead (toward the goal) are dug out. False when nothing there can be dug.
	 */
	private boolean widen(ServerLevel level, Vec3 goal) {
		if (!level.getGameRules().getBoolean(StarMine.HIVE_GRIEFING)) return false;
		Vec3 direction = goal.subtract(drone.position()).multiply(1, 0, 1);
		if (direction.lengthSqr() < 1e-4) return false;
		direction = direction.normalize();
		var box = drone.getBoundingBox().move(direction.scale(0.9)).setMaxY(drone.getY() + 1.95).deflate(0.05, 0, 0.05).move(0, 0.05, 0);
		List<BlockPos> blocking = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
			if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) continue;
			if (!diggable(level, pos) || !drone.canTake(level.getBlockState(pos)) || !reach(pos)) return false;
			blocking.add(pos.immutable());
		}
		if (blocking.isEmpty()) return false;
		cube.clear();
		cube.addAll(blocking);
		cubeExit = BlockPos.containing(drone.position().add(direction));
		cubeTicks = 0;
		return true;
	}

	/** Paths over long distances in legs the pathfinder can plan. */
	private void travel(Vec3 goal) {
		Vec3 from = drone.position();
		Vec3 leg = goal;
		if (from.distanceTo(goal) > 40) {
			Vec3 step = from.add(goal.subtract(from).normalize().scale(32));
			leg = new Vec3(step.x, drone.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) step.x, (int) step.z), step.z);
		}
		if (!drone.getNavigation().moveTo(leg.x, leg.y, leg.z, 1.0))
			drone.getMoveControl().setWantedPosition(leg.x, leg.y, leg.z, 1.0);
	}

	// ---- CARRY ----

	/**
	 * The load goes to the edge of the hatchery (the hatchery itself is solid). Underground the drone
	 * takes an existing way out when the pathfinder finds one, else walks back along its own tunnel,
	 * and only when that is gone too digs stairs up.
	 */
	private void carry(ServerLevel level) {
		Hive hive = HiveSavedData.get(level.getServer()).get(drone.hiveId());
		if (hive == null) {
			drone.clearCargo();
			to(State.SEEK);
			return;
		}
		Vec3 centre = Vec3.atBottomCenterOf(hive.hatchery);
		double dx = drone.getX() - centre.x, dz = drone.getZ() - centre.z;
		double flat = Math.sqrt(dx * dx + dz * dz);
		if (flat < 8 && Math.abs(drone.getY() - centre.y) < 4) {
			hive.minerals += drone.cargoMinerals;
			hive.mineralsCollected += drone.cargoMinerals;
			hive.vespene += drone.cargoVespene;
			hive.diamonds += drone.cargoDiamonds;
			HiveSavedData.get(level.getServer()).setDirty();
			drone.clearCargo();
			drone.getNavigation().stop();
			State back = resume;
			to(back == State.TREE && !logs.isEmpty() ? State.TREE : back == State.MINE && mineTarget != null ? State.MINE : State.SEEK);
			return;
		}
		Vec3 edge = flat < 0.01 ? centre.add(5, 0, 0) : centre.add(dx / flat * 5.5, 0, dz / flat * 5.5);
		boolean underground = !level.canSeeSky(drone.blockPosition().above());
		if (underground && stateTicks % 40 == 1) {
			Path path = drone.getNavigation().createPath(BlockPos.containing(edge), 2);
			homeReachable = path != null && path.canReach();
		}
		if (underground && !homeReachable && !trail.isEmpty()) {
			BlockPos back = trail.peekLast();
			if (drone.position().distanceToSqr(Vec3.atBottomCenterOf(back)) < 2.5) trail.pollLast();
			else if (!walk(level, Vec3.atBottomCenterOf(back), true)) trail.pollLast();
			return;
		}
		walk(level, edge, underground);
	}

	// ---- EXPLORE ----

	private void startExplore(ServerLevel level) {
		BlockPos home = drone.home() != null ? drone.home() : drone.blockPosition();
		double radius = Math.min(96, 16 << Math.min(misses, 3));
		double angle = drone.getRandom().nextDouble() * Math.PI * 2;
		double x = home.getX() + Math.cos(angle) * radius, z = home.getZ() + Math.sin(angle) * radius;
		exploreTo = new Vec3(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) x, (int) z), z);
		to(State.EXPLORE);
	}

	private void explore(ServerLevel level, boolean griefing) {
		if (exploreTo == null || stateTicks > 900 || drone.position().distanceToSqr(exploreTo) < 16) {
			misses++;
			if (griefing && misses >= 2 && drone.getRandom().nextBoolean()) {
				// walks found nothing: stairs down to look deeper
				double angle = drone.getRandom().nextDouble() * Math.PI * 2;
				BlockPos head = drone.blockPosition();
				mineTarget = head.offset((int) (Math.cos(angle) * 14), -6 - drone.getRandom().nextInt(10), (int) (Math.sin(angle) * 14));
				deep = true;
				to(State.MINE);
			} else {
				to(State.SEEK);
				seekCooldown = 0;
			}
			return;
		}
		if (!walk(level, exploreTo, false)) exploreTo = null;
	}

	// ---- breaking ----

	private boolean reach(BlockPos pos) {
		Vec3 centre = Vec3.atCenterOf(pos);
		return drone.getEyePosition().distanceToSqr(centre) <= REACH * REACH || drone.position().distanceToSqr(centre) <= REACH * REACH;
	}

	private void start(ServerLevel level, BlockPos pos) {
		if (pos.equals(breaking)) return;
		breaking = pos.immutable();
		breakTicks = 0;
		breakNeeded = Math.max(4, (int) (level.getBlockState(pos).getDestroySpeed(level, pos) * 12));
		drone.getNavigation().stop();
	}

	/** One tick of breaking: cracks, swings; true once the block is gone. */
	private boolean breakBlock(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.isAir()) {
			breaking = null;
			return false;
		}
		drone.getLookControl().setLookAt(Vec3.atCenterOf(pos));
		if (breakTicks % 10 == 0) drone.swing(InteractionHand.MAIN_HAND);
		breakTicks++;
		level.destroyBlockProgress(drone.getId(), pos, Math.min(9, breakTicks * 10 / Math.max(1, breakNeeded)));
		if (breakTicks < breakNeeded) return false;
		level.destroyBlockProgress(drone.getId(), pos, -1);
		drone.collect(state);
		boolean creeped = coveredByCreep(level, pos);
		level.destroyBlock(pos, false, drone);
		if (creeped) CreepGrowth.lineCavity(level, pos);
		return true;
	}

	/** Whether creep lies on any face of this block (it would fall off when the block goes). */
	private static boolean coveredByCreep(ServerLevel level, BlockPos pos) {
		for (Direction d : Direction.values()) {
			BlockState near = level.getBlockState(pos.relative(d));
			if (near.is(StarMineBlocks.CREEP) && MultifaceBlock.hasFace(near, d.getOpposite())) return true;
		}
		return false;
	}

	private void afterBreak(ServerLevel level) {
		BlockPos done = breaking;
		breaking = null;
		if (state == State.TREE && !logs.isEmpty() && logs.get(0).equals(done)) {
			release(logs.remove(0));
		} else if (state == State.MINE && done.equals(mineTarget)) {
			release(mineTarget);
			if (!nextInVein(level) && !deep) to(State.SEEK);
		} else if (!cube.isEmpty() && cube.peek().equals(done)) {
			cube.poll();
		}
	}

	private void cancelBreak() {
		if (breaking != null) drone.level().destroyBlockProgress(drone.getId(), breaking, -1);
		breaking = null;
	}

	private void claim(BlockPos pos) {
		CLAIMS.put(pos.immutable(), drone.getUUID());
	}

	private void release(BlockPos pos) {
		if (pos != null) CLAIMS.remove(pos, drone.getUUID());
	}

	/** A drone that dies or unloads gives up its claims. */
	static void releaseAll(UUID drone) {
		CLAIMS.values().removeIf(drone::equals);
	}
}
