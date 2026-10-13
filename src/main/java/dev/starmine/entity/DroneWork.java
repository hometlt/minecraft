package dev.starmine.entity;

import dev.starmine.StarMine;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * A drone's work (stage 2 of docs/HIVE_DESIGN.md), a state machine run while it has no target:
 *
 * - SEEK: a natural tree nearby (logs with non-persistent leaves), else an ore within reach of a
 *   scan, else EXPLORE;
 * - TREE: climbs the trunk like a spider and chops it from the top down, the leaves going with it;
 * - ORE: digs a winding 1x1 tunnel (steps of a block, random sideways turns, stairs up) to the ore
 *   and takes the whole vein; ores met along the tunnel are taken too;
 * - CARRY: brings the load to its hatchery (minerals, vespene, hoarded diamonds);
 * - EXPLORE: walks ever farther from the hive, and when walks find nothing, digs deeper tunnels.
 *
 * Blocks give what `data/starmine/hive/resources.json` says; nothing drops for players. Breaking
 * shows crack progress and plays the drone's attack animation. With `starmineHiveGriefing` off,
 * drones only explore. Trees and ores are claimed so two drones do not take the same one.
 */
final class DroneWork extends Goal {
	private enum State { SEEK, TREE, ORE, CARRY, EXPLORE }

	/** Blocks claimed by a drone (server-wide, transient). */
	private static final Map<BlockPos, UUID> CLAIMS = new HashMap<>();
	private static final UUID NOBODY = new UUID(0, 0);

	private final ZergMob drone;
	private State state = State.SEEK, resume = State.SEEK;
	private final List<BlockPos> logs = new ArrayList<>(), leaves = new ArrayList<>();
	private BlockPos digTarget;
	private boolean deepDig;
	private BlockPos stepCell;
	private BlockPos breaking;
	private int breakTicks, breakNeeded, stateTicks, misses, seekCooldown;
	private Vec3 exploreTo;

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
		cancelBreak();
	}

	@Override
	public void tick() {
		if (!(drone.level() instanceof ServerLevel level)) return;
		stateTicks++;
		boolean griefing = level.getGameRules().getBoolean(StarMine.HIVE_GRIEFING);
		if (drone.cargoFull() && state != State.CARRY) to(State.CARRY);
		switch (state) {
			case SEEK -> seek(level, griefing);
			case TREE -> chop(level);
			case ORE -> dig(level);
			case CARRY -> carry(level);
			case EXPLORE -> explore(level, griefing);
		}
	}

	private void to(State next) {
		if (next == State.CARRY && state != State.CARRY) resume = state == State.TREE || state == State.ORE ? state : State.SEEK;
		cancelBreak();
		state = next;
		stateTicks = 0;
		stepCell = null;
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
			BlockPos ore = findOre(level, drone.blockPosition(), 10);
			if (ore != null) {
				claim(ore);
				digTarget = ore;
				deepDig = false;
				to(State.ORE);
				return;
			}
		}
		if (drone.cargoValue() > 0) {
			to(State.CARRY);
			return;
		}
		startExplore(level);
	}

	// ---- TREE ----

	private boolean findTree(ServerLevel level) {
		BlockPos centre = drone.blockPosition();
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int dx = -16; dx <= 16; dx++)
			for (int dz = -16; dz <= 16; dz++) {
				int x = centre.getX() + dx, z = centre.getZ() + dz;
				int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
				BlockPos pos = new BlockPos(x, top, z);
				for (int i = 0; i < 24 && pos.getY() > level.getMinBuildHeight(); i++, pos = pos.below()) {
					BlockState state = level.getBlockState(pos);
					if (state.is(BlockTags.LOGS)) {
						double distance = pos.distSqr(centre);
						if (!CLAIMS.containsKey(pos) && distance < bestDistance) {
							best = pos;
							bestDistance = distance;
						}
						break;
					}
					if (!state.is(BlockTags.LEAVES)) break;
				}
			}
		if (best == null) return false;
		return collectTree(level, best);
	}

	/** Logs connected to the seed (a natural tree only: some leaves are not player-placed). */
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
			} else if (state.is(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT)) {
				natural = true;
			}
		}
		if (!natural || logs.isEmpty()) {
			for (BlockPos log : logs) CLAIMS.putIfAbsent(log, NOBODY); // built from logs, not a tree: never try again
			logs.clear();
			return false;
		}
		// the leaves of this tree: natural leaves reachable from its logs within 6 steps
		Set<BlockPos> leafSeen = new HashSet<>();
		ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
		for (BlockPos log : logs) for (Direction d : Direction.values()) frontier.add(log.relative(d));
		for (int depth = 0; depth < 6 && !frontier.isEmpty() && leaves.size() < 800; depth++) {
			ArrayDeque<BlockPos> next = new ArrayDeque<>();
			for (BlockPos pos : frontier) {
				if (!leafSeen.add(pos)) continue;
				BlockState state = level.getBlockState(pos);
				if (state.is(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT)) {
					leaves.add(pos);
					for (Direction d : Direction.values()) next.add(pos.relative(d));
				}
			}
			frontier = next;
		}
		logs.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
		leaves.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
		for (BlockPos log : logs) claim(log);
		return true;
	}

	private void chop(ServerLevel level) {
		while (!logs.isEmpty() && !level.getBlockState(logs.get(0)).is(BlockTags.LOGS)) release(logs.remove(0));
		if (logs.isEmpty()) {
			// the last leaves fall with the trunk
			for (BlockPos leaf : leaves) if (level.getBlockState(leaf).is(BlockTags.LEAVES)) level.destroyBlock(leaf, false, drone);
			leaves.clear();
			to(State.SEEK);
			return;
		}
		BlockPos log = logs.get(0);
		// leaves at and above the cut go first, a few per tick
		int removed = 0;
		while (!leaves.isEmpty() && leaves.get(0).getY() >= log.getY() - 1 && removed < 12) {
			BlockPos leaf = leaves.remove(0);
			if (level.getBlockState(leaf).is(BlockTags.LEAVES)) {
				level.destroyBlock(leaf, false, drone);
				removed++;
			}
		}
		if (reach(log)) {
			if (breakBlock(level, log)) {
				release(log);
				logs.remove(0);
				stateTicks = 0;
			}
		} else {
			if (stateTicks % 20 == 1) drone.getNavigation().moveTo(log.getX() + 0.5, log.getY(), log.getZ() + 0.5, 1.0);
			if (stateTicks > 400) { // cannot get there: leave this log
				release(logs.remove(0));
				stateTicks = 0;
			}
		}
	}

	// ---- ORE ----

	static BlockPos findOre(ServerLevel level, BlockPos centre, int radius) {
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-radius, -radius, -radius), centre.offset(radius, radius, radius))) {
			if (CLAIMS.containsKey(pos) || !HiveResources.isOre(level.getBlockState(pos))) continue;
			double distance = pos.distSqr(centre);
			if (distance < bestDistance) {
				best = pos.immutable();
				bestDistance = distance;
			}
		}
		return best;
	}

	private void dig(ServerLevel level) {
		if (digTarget == null) {
			to(State.SEEK);
			return;
		}
		boolean oreThere = HiveResources.isOre(level.getBlockState(digTarget));
		if (!deepDig && !oreThere) {
			release(digTarget);
			if (!nextInVein(level)) to(State.SEEK);
			return;
		}
		if (oreThere && reach(digTarget)) {
			if (breakBlock(level, digTarget)) {
				release(digTarget);
				if (!nextInVein(level)) to(State.SEEK);
			}
			return;
		}
		BlockPos head = drone.blockPosition();
		if (deepDig && head.distManhattan(digTarget) <= 1) {
			deepDig = false;
			digTarget = null;
			to(State.SEEK);
			return;
		}
		if (stepCell != null) {
			// walking into the cell just dug
			if (head.equals(stepCell) || stateTicks > 60) {
				stepCell = null;
				stateTicks = 0;
				BlockPos ore = findOre(level, drone.blockPosition(), 2); // ores met on the way
				if (ore != null && !deepDig) {
					release(digTarget);
					digTarget = ore;
					claim(ore);
				}
			} else {
				drone.getMoveControl().setWantedPosition(stepCell.getX() + 0.5, stepCell.getY(), stepCell.getZ() + 0.5, 1.0);
			}
			return;
		}
		BlockPos next = nextCell(level, head);
		if (next == null) { // water, lava or unbreakable all around: give up this target
			release(digTarget);
			digTarget = null;
			to(State.SEEK);
			return;
		}
		// dig the cell (and headroom when climbing a step); then walk in
		List<BlockPos> cut = new ArrayList<>(List.of(next));
		if (next.getY() > head.getY()) cut.add(head.above());
		for (BlockPos pos : cut) {
			if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) continue;
			if (!breakBlock(level, pos)) return;
		}
		stepCell = next;
		stateTicks = 0;
	}

	/** The next tunnel cell toward the target: mostly straight, sometimes a sideways turn or a step. */
	private BlockPos nextCell(ServerLevel level, BlockPos head) {
		int dx = Integer.signum(digTarget.getX() - head.getX());
		int dy = Integer.signum(digTarget.getY() - head.getY());
		int dz = Integer.signum(digTarget.getZ() - head.getZ());
		List<BlockPos> options = new ArrayList<>();
		boolean xFirst = Math.abs(digTarget.getX() - head.getX()) >= Math.abs(digTarget.getZ() - head.getZ());
		BlockPos straight = xFirst && dx != 0 ? head.offset(dx, 0, 0) : dz != 0 ? head.offset(0, 0, dz) : head.offset(dx, 0, 0);
		if (dy < 0 && drone.getRandom().nextFloat() < 0.5f) options.add(head.below());
		if (dy > 0 && !straight.equals(head)) options.add(straight.above());
		if (drone.getRandom().nextFloat() < 0.25f) {
			// a winding turn sideways
			BlockPos side = xFirst ? head.offset(0, 0, drone.getRandom().nextBoolean() ? 1 : -1) : head.offset(drone.getRandom().nextBoolean() ? 1 : -1, 0, 0);
			options.add(side);
		}
		if (!straight.equals(head)) options.add(straight);
		if (dy < 0) options.add(head.below());
		if (dy > 0) options.add(head.above());
		for (BlockPos option : options) if (diggable(level, option)) return option;
		return null;
	}

	/** A cell a drone may dig into: breakable, and no water or lava next to it. */
	private static boolean diggable(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!state.getFluidState().isEmpty() || level.getBlockEntity(pos) != null) return false;
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness < 0 || hardness > 20) return false;
		for (Direction d : Direction.values()) if (!level.getFluidState(pos.relative(d)).isEmpty()) return false;
		return true;
	}

	private boolean nextInVein(ServerLevel level) {
		if (digTarget == null) return false;
		for (BlockPos near : BlockPos.betweenClosed(digTarget.offset(-1, -1, -1), digTarget.offset(1, 1, 1))) {
			if (!CLAIMS.containsKey(near) && HiveResources.isOre(level.getBlockState(near))) {
				digTarget = near.immutable();
				claim(digTarget);
				return true;
			}
		}
		digTarget = null;
		return false;
	}

	// ---- CARRY ----

	private void carry(ServerLevel level) {
		Hive hive = HiveSavedData.get(level.getServer()).get(drone.hiveId());
		if (hive == null) {
			drone.clearCargo();
			to(State.SEEK);
			return;
		}
		BlockPos home = hive.hatchery;
		double dx = drone.getX() - (home.getX() + 0.5), dz = drone.getZ() - (home.getZ() + 0.5);
		if (dx * dx + dz * dz < 6 * 6 && Math.abs(drone.getY() - home.getY()) < 4) {
			hive.minerals += drone.cargoMinerals;
			hive.mineralsCollected += drone.cargoMinerals;
			hive.vespene += drone.cargoVespene;
			hive.diamonds += drone.cargoDiamonds;
			HiveSavedData.get(level.getServer()).setDirty();
			drone.clearCargo();
			State back = resume;
			to(back == State.TREE && !logs.isEmpty() ? State.TREE : back == State.ORE && digTarget != null ? State.ORE : State.SEEK);
			return;
		}
		if (stateTicks % 40 == 1) travelTo(Vec3.atBottomCenterOf(home));
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
		if (exploreTo == null || stateTicks > 800 || drone.position().distanceToSqr(exploreTo) < 16) {
			misses++;
			// walks that find nothing turn into digging deeper
			if (griefing && misses >= 2 && drone.getRandom().nextBoolean()) {
				double angle = drone.getRandom().nextDouble() * Math.PI * 2;
				BlockPos head = drone.blockPosition();
				digTarget = head.offset((int) (Math.cos(angle) * 12), -6 - drone.getRandom().nextInt(10), (int) (Math.sin(angle) * 12));
				deepDig = true;
				to(State.ORE);
			} else {
				to(State.SEEK);
				seekCooldown = 0;
			}
			return;
		}
		if (stateTicks % 40 == 1) travelTo(exploreTo);
	}

	// ---- helpers ----

	/** Paths over long distances in legs the pathfinder can plan. */
	private void travelTo(Vec3 goal) {
		Vec3 from = drone.position();
		Vec3 leg = goal;
		if (from.distanceTo(goal) > 40) {
			Vec3 step = from.add(goal.subtract(from).normalize().scale(32));
			leg = new Vec3(step.x, drone.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) step.x, (int) step.z), step.z);
		}
		if (!drone.getNavigation().moveTo(leg.x, leg.y, leg.z, 1.0))
			drone.getMoveControl().setWantedPosition(leg.x, leg.y, leg.z, 1.0);
	}

	private boolean reach(BlockPos pos) {
		return drone.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= 3.2 * 3.2;
	}

	/** Breaks a block over time like a player would (cracks, swings); true when it is gone. */
	private boolean breakBlock(ServerLevel level, BlockPos pos) {
		if (!pos.equals(breaking)) {
			cancelBreak();
			breaking = pos;
			breakTicks = 0;
			breakNeeded = Math.max(4, (int) (level.getBlockState(pos).getDestroySpeed(level, pos) * 12));
		}
		drone.getLookControl().setLookAt(Vec3.atCenterOf(pos));
		if (breakTicks % 10 == 0) drone.swing(InteractionHand.MAIN_HAND);
		breakTicks++;
		level.destroyBlockProgress(drone.getId(), pos, Math.min(9, breakTicks * 10 / breakNeeded));
		if (breakTicks < breakNeeded) return false;
		BlockState state = level.getBlockState(pos);
		HiveResources.Yield yield = HiveResources.yieldOf(state);
		level.destroyBlockProgress(drone.getId(), pos, -1);
		level.destroyBlock(pos, false, drone);
		drone.addCargo(yield);
		breaking = null;
		return true;
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
