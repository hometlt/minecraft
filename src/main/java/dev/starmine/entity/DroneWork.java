package dev.starmine.entity;

import dev.starmine.StarMine;
import dev.starmine.block.StarMineBlocks;
import dev.starmine.creep.CreepGrowth;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveResources;
import dev.starmine.hive.HiveSavedData;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * A drone's work (stage 2 of docs/HIVE_DESIGN.md, reworked on the owner's play tests). It always has
 * a goal, and goes there through {@link #moveTo}:
 *
 * - SEEK: a natural tree nearby (logs with non-persistent leaves, so log houses stay), else an ore
 *   within a scan, else EXPLORE;
 * - TREE: to the trunk's foot; standing there it takes the tree from its top down, leaves with it;
 * - MINE: to the ore (the planner digs the way), then the whole vein and ores met on the way;
 * - CARRY: to the edge of its hatchery with its valuable slot — when it holds 8, or a precious ore,
 *   or before a second kind of resource would be taken;
 * - EXPLORE: ever farther, and deeper when walks find nothing;
 * - RAID (a raid party member): to the target, opening the way for the warriors, then stealing food
 *   from containers and breaking mechanisms and crops.
 *
 * Movement: an ordinary path when the game's pathfinder has a reasonable one; otherwise (or when it
 * gets stuck) a plan of {@link DronePlanner} — digging through walls, stair steps up and down, bridges
 * of stone fill, never through the floors of the hive's routes. Blocks are broken only within reach,
 * and a block once started is finished unless an enemy takes the drone's attention. Creep on a dug
 * block lines the new cavity. Yields come from `data/starmine/hive/resources.json`; with
 * `starmineHiveGriefing` off drones neither dig nor chop. Trees and ores are claimed per drone.
 */
final class DroneWork extends Goal {
	private enum State { SEEK, TREE, MINE, CARRY, EXPLORE, RAID }

	private enum Move { MOVING, ARRIVED, FAILED }

	private static final Map<BlockPos, UUID> CLAIMS = new HashMap<>();
	private static final UUID NOBODY = new UUID(0, 0);
	private static final double REACH = 3.6;
	public static final TagKey<Block> MECHANISMS = TagKey.create(Registries.BLOCK, StarMine.id("raid_mechanisms"));

	private final ZergMob drone;
	private State state = State.SEEK, resume = State.SEEK;
	private final List<BlockPos> logs = new ArrayList<>(), leaves = new ArrayList<>();
	private BlockPos treeFoot, mineTarget;
	private boolean deep;
	private BlockPos breaking;
	private int breakTicks, breakNeeded, stateTicks, misses, seekCooldown;
	private Vec3 exploreTo;

	// movement
	private Vec3 moveGoal;
	private boolean planned;
	private List<DronePlanner.Step> plan;
	private int stepIndex, stepTicks, failures, stuckTicks;
	private double bestDistance;

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
		// an enemy took its attention: the cracks fade, the block is left, the plan is redone later
		cancelBreak();
		resetMove();
	}

	@Override
	public void tick() {
		if (!(drone.level() instanceof ServerLevel level)) return;
		stateTicks++;
		if (breaking != null) { // a block once started is finished first
			if (breakBlock(level, breaking)) afterBreak(level);
			return;
		}
		boolean griefing = level.getGameRules().getBoolean(StarMine.HIVE_GRIEFING);
		if (drone.raidTarget() != null) {
			if (state != State.RAID) to(State.RAID);
		} else if (state == State.RAID) {
			to(State.SEEK);
		} else if (drone.loadFull() && state != State.CARRY) {
			to(State.CARRY);
		}
		switch (state) {
			case SEEK -> seek(level, griefing);
			case TREE -> tree(level);
			case MINE -> mine(level, griefing);
			case CARRY -> carry(level, griefing);
			case EXPLORE -> explore(level, griefing);
			case RAID -> raid(level, griefing);
		}
	}

	private void to(State next) {
		if (next == State.CARRY && state != State.CARRY) resume = state == State.TREE || state == State.MINE ? state : State.SEEK;
		state = next;
		stateTicks = 0;
		resetMove();
	}

	// ---- moving: an ordinary path, else a dig-and-build plan ----

	private void resetMove() {
		moveGoal = null;
		plan = null;
		planned = false;
		failures = 0;
	}

	/** One tick of going to within `arrive` blocks of `goal`. */
	private Move moveTo(ServerLevel level, Vec3 goal, double arrive, boolean dig) {
		if (drone.position().distanceTo(goal) <= arrive) {
			drone.getNavigation().stop();
			return Move.ARRIVED;
		}
		if (moveGoal == null || moveGoal.distanceToSqr(goal) > 1) {
			resetMove();
			moveGoal = goal;
			bestDistance = Double.MAX_VALUE;
			stuckTicks = 0;
			// the game's path if it reaches the goal and is not much longer than the way straight there
			Path path = drone.getNavigation().createPath(BlockPos.containing(goal), (int) Math.max(1, arrive - 1));
			double straight = drone.position().distanceTo(goal);
			if (path != null && path.canReach() && path.getNodeCount() <= straight * 1.6 + 12) {
				drone.getNavigation().moveTo(path, 1.0);
				return Move.MOVING;
			}
			return replan(level, goal, arrive, dig);
		}
		if (plan != null) return followPlan(level, goal, arrive, dig);
		// on an ordinary path: watch for being stuck, then plan
		double distance = drone.position().distanceTo(goal);
		if (distance < bestDistance - 0.5) {
			bestDistance = distance;
			stuckTicks = 0;
		} else if (++stuckTicks > 60 || drone.getNavigation().isDone()) {
			return replan(level, goal, arrive, dig);
		}
		return Move.MOVING;
	}

	private Move replan(ServerLevel level, Vec3 goal, double arrive, boolean dig) {
		drone.getNavigation().stop();
		Hive hive = HiveSavedData.get(level.getServer()).get(drone.hiveId());
		Set<Long> routes = hive != null ? hive.routes : Set.of();
		plan = DronePlanner.plan(level, DronePlanner.feetOf(drone.position()), goal, arrive, routes, dig, drone.fillCount(), 3000);
		stepIndex = 0;
		stepTicks = 0;
		planned = true;
		if (plan == null || plan.isEmpty()) {
			plan = null;
			return ++failures >= 3 ? Move.FAILED : Move.MOVING;
		}
		return Move.MOVING;
	}

	private Move followPlan(ServerLevel level, Vec3 goal, double arrive, boolean dig) {
		if (stepIndex >= plan.size()) return replan(level, goal, arrive, dig); // a partial plan ran out
		DronePlanner.Step step = plan.get(stepIndex);
		for (BlockPos block : step.dig()) {
			if (!DronePlanner.solid(level, block)) continue;
			if (!reach(block) || !DronePlanner.breakable(level, block, null) || !drone.canTake(level.getBlockState(block)))
				return replan(level, goal, arrive, dig);
			start(level, block);
			return Move.MOVING;
		}
		for (BlockPos floor : step.fill()) {
			if (DronePlanner.solid(level, floor)) continue;
			if (!drone.placeFill(floor)) return replan(level, goal, arrive, dig);
			return Move.MOVING;
		}
		Vec3 centre = DronePlanner.centre(step.feet());
		double dx = drone.getX() - centre.x, dz = drone.getZ() - centre.z;
		if (dx * dx + dz * dz < 0.12 && Math.abs(drone.getY() - centre.y) < 0.8) {
			Hive hive = HiveSavedData.get(level.getServer()).get(drone.hiveId());
			if (hive != null) hive.addRoute(step.feet());
			stepIndex++;
			stepTicks = 0;
			return Move.MOVING;
		}
		if (++stepTicks > 80) return replan(level, goal, arrive, dig);
		drone.getMoveControl().setWantedPosition(centre.x, centre.y, centre.z, 1.0);
		return Move.MOVING;
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
		if (!reach(treeFoot) && !reach(treeFoot.above())) {
			if (moveTo(level, Vec3.atBottomCenterOf(treeFoot), 2.5, true) == Move.FAILED) {
				for (BlockPos log : logs) release(log);
				logs.clear();
				to(State.SEEK);
			}
			return;
		}
		drone.getNavigation().stop();
		BlockPos top = logs.get(0);
		if (!drone.canTake(level.getBlockState(top))) { // another resource in its slot: deliver it first
			to(State.CARRY);
			return;
		}
		int removed = 0;
		while (!leaves.isEmpty() && leaves.get(0).getY() >= top.getY() - 1 && removed < 12) {
			BlockPos leaf = leaves.remove(0);
			if (level.getBlockState(leaf).is(BlockTags.LEAVES)) {
				level.destroyBlock(leaf, false, drone);
				removed++;
			}
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
		Move move = moveTo(level, Vec3.atCenterOf(mineTarget), deep ? 2.0 : 2.8, true);
		if (move == Move.ARRIVED && deep) {
			deep = false;
			mineTarget = null;
			to(State.SEEK);
		} else if (move == Move.FAILED) {
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

	// ---- CARRY ----

	/** The load goes to the edge of the hatchery (the hatchery itself is solid). */
	private void carry(ServerLevel level, boolean griefing) {
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
		Vec3 edge = flat < 0.01 ? centre.add(5.5, 0, 0) : centre.add(dx / flat * 5.5, 0, dz / flat * 5.5);
		// leg by leg when far: the plan's reach is 40 blocks
		Vec3 goal = drone.position().distanceTo(edge) > 36 ? surface(level, drone.position().add(edge.subtract(drone.position()).normalize().scale(32))) : edge;
		if (moveTo(level, goal, 2.5, griefing) == Move.FAILED) resetMove();
	}

	private Vec3 surface(ServerLevel level, Vec3 point) {
		return new Vec3(point.x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(point.x), (int) Math.floor(point.z)), point.z);
	}

	// ---- EXPLORE ----

	private void startExplore(ServerLevel level) {
		BlockPos home = drone.home() != null ? drone.home() : drone.blockPosition();
		double radius = Math.min(96, 16 << Math.min(misses, 3));
		double angle = drone.getRandom().nextDouble() * Math.PI * 2;
		exploreTo = surface(level, new Vec3(home.getX() + Math.cos(angle) * radius, 0, home.getZ() + Math.sin(angle) * radius));
		to(State.EXPLORE);
	}

	private void explore(ServerLevel level, boolean griefing) {
		Vec3 goal = exploreTo == null ? null
			: drone.position().distanceTo(exploreTo) > 36 ? surface(level, drone.position().add(exploreTo.subtract(drone.position()).normalize().scale(32))) : exploreTo;
		Move move = goal == null ? Move.FAILED : moveTo(level, goal, 4, false);
		boolean done = exploreTo == null || stateTicks > 1200 || drone.position().distanceTo(exploreTo) < 5 || move == Move.FAILED;
		if (!done) return;
		misses++;
		if (griefing && misses >= 2 && drone.getRandom().nextBoolean()) {
			// walks found nothing: stairs down to look deeper
			double angle = drone.getRandom().nextDouble() * Math.PI * 2;
			mineTarget = drone.blockPosition().offset((int) (Math.cos(angle) * 14), -6 - drone.getRandom().nextInt(10), (int) (Math.sin(angle) * 14));
			deep = true;
			to(State.MINE);
		} else {
			to(State.SEEK);
			seekCooldown = 0;
		}
	}

	// ---- RAID ----

	/**
	 * A drone on a raid opens the way for the party (digs through walls, builds stairs and bridges),
	 * and at the target steals food from containers and breaks mechanisms and crops within 10 blocks.
	 */
	private void raid(ServerLevel level, boolean griefing) {
		BlockPos target = drone.raidTarget();
		if (target == null) return;
		Vec3 goal = Vec3.atBottomCenterOf(target);
		if (drone.position().distanceTo(goal) > 10) {
			Vec3 leg = drone.position().distanceTo(goal) > 36 ? surface(level, drone.position().add(goal.subtract(drone.position()).normalize().scale(32))) : goal;
			moveTo(level, leg, 8, griefing);
			return;
		}
		if (stateTicks % 20 == 0) stealFood(level);
		if (!griefing) return;
		BlockPos victim = null;
		double best = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(target.offset(-10, -4, -10), target.offset(10, 6, 10))) {
			if (!level.getBlockState(pos).is(MECHANISMS)) continue;
			double d = pos.distSqr(drone.blockPosition());
			if (d < best) {
				best = d;
				victim = pos.immutable();
			}
		}
		if (victim == null) return;
		if (reach(victim)) start(level, victim);
		else if (moveTo(level, Vec3.atBottomCenterOf(victim), 2.8, true) == Move.FAILED) resetMove();
	}

	/** Food is taken out of the chests, barrels and other containers within reach (it is eaten). */
	private void stealFood(ServerLevel level) {
		for (BlockPos pos : BlockPos.betweenClosed(drone.blockPosition().offset(-3, -2, -3), drone.blockPosition().offset(3, 3, 3))) {
			if (!(level.getBlockEntity(pos) instanceof Container container)) continue;
			for (int slot = 0; slot < container.getContainerSize(); slot++) {
				ItemStack stack = container.getItem(slot);
				if (!stack.isEmpty() && stack.has(DataComponents.FOOD)) {
					container.setItem(slot, ItemStack.EMPTY);
					drone.swing(InteractionHand.MAIN_HAND);
					drone.playSound(SoundEvents.GENERIC_EAT, 1.0f, 0.8f);
					container.setChanged();
					return;
				}
			}
		}
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
