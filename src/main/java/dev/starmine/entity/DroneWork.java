package dev.starmine.entity;

import dev.starmine.StarMine;
import dev.starmine.block.StarMineBlocks;
import dev.starmine.creep.CreepGrowth;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveLogic;
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
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import dev.starmine.vespene.Vespene;
import dev.starmine.colony.Engineering;
import dev.starmine.vespene.VespeneBlock;

/**
 * A worker's work: the zerg drone and the terran SCV (stage 2 of docs/HIVE_DESIGN.md, reworked on the
 * owner's play tests). It always has a goal, and goes there through {@link #moveTo}:
 *
 * - SEEK: a vespene building of its hive or base short of 3 workers, else an ore within a scan, else
 *   (sometimes) a tunnel for the hive's network, else EXPLORE (owner 14.10: no wood any more);
 * - GAS (owner 14.10): vespene from a vein outlet. With its hive's or base's extractor or refinery on
 *   the outlet the worker walks in, stays hidden {@link #INSIDE} ticks and comes out with
 *   {@link Vespene#PER_TRIP} vespene; at a bare outlet (an order, digging down to it if needed) it pumps
 *   for {@link #PUMP} ticks beside it. An empty outlet is waited for (its vein fills it again);
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
 * `starmineHiveGriefing` off drones do not dig. Ores are claimed per drone.
 *
 * - ENGINEER (an SCV hired by {@link Engineering}): breaks and bridges the blocks of an army's way, the
 *   nearest unfinished ones first; a bridge stone comes from its fill, else costs the base a mineral.
 *
 * SCVs (owner 14.10) work the same way but take their player's orders first ({@link StarUnit#commandWork}:
 * gather at a block, return the cargo; build: to the site, then build until the building is done,
 * {@link dev.starmine.colony.TerranLogic#place}); they bring loads to the nearest command center of their
 * base, dig no hive tunnels, go on no raids and, with nothing in reach, wait instead of exploring far.
 */
final class DroneWork extends Goal {
	private enum State { SEEK, GAS, MINE, CARRY, EXPLORE, RAID, BUILD, TUNNEL, ENGINEER }

	/** Owner: a drone marks a group of up to 8 blocks of the resource it goes for; others leave them. */
	private static final int GROUP = 8;
	/** Owner: drones spread out — an ore within this distance of another drone's mark is not chosen. */
	private static final int SPREAD = 8;
	private BlockPos tunnelTo;

	private enum Move { MOVING, ARRIVED, FAILED }

	private static final Map<BlockPos, UUID> CLAIMS = new HashMap<>();
	private static final UUID NOBODY = new UUID(0, 0);
	private static final double REACH = 3.6;
	public static final TagKey<Block> MECHANISMS = TagKey.create(Registries.BLOCK, StarMine.id("raid_mechanisms"));

	private final StarUnit drone;
	private State state = State.SEEK, resume = State.SEEK;
	private BlockPos mineTarget;
	/** Vespene: the outlet worked, whether the worker is inside its building, ticks left inside or pumping. */
	private BlockPos gasOutlet;
	private boolean inside;
	private int gasTicks;
	static final int INSIDE = 60, PUMP = 160, GAS_WORKERS = 3;
	/** Workers per outlet (a vespene building takes 3, as in SC2). */
	private static final Map<BlockPos, Set<UUID>> GAS = new HashMap<>();
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

	DroneWork(StarUnit drone) {
		this.drone = drone;
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		return drone.hiveId() != null && drone.getTarget() == null && !drone.isPassenger() && drone.order() == StarUnit.Order.NONE;
	}

	private boolean terran() {
		return drone.kind.race == UnitKind.Race.TERRAN;
	}

	/** A player's work order for an SCV: gather at a block (a tree or an ore), or bring the cargo home. */
	private void takeOrder(ServerLevel level) {
		StarUnit.Work work = drone.takeWork();
		if (work == StarUnit.Work.NONE) return;
		cancelBreak();
		if (work == StarUnit.Work.RETURN) {
			if (drone.cargoBlocks > 0) to(State.CARRY);
			return;
		}
		BlockPos pos = drone.workPos();
		if (pos == null) return;
		BlockState state = level.getBlockState(pos);
		BlockPos outlet = Vespene.isOutlet(state) ? pos.immutable() : Vespene.outletUnder(level, pos);
		if (outlet != null) {
			startGas(outlet);
		} else if (HiveResources.isOre(state)) {
			endGas();
			releaseAll(drone.getUUID());
			claimGroup(level, pos);
			mineTarget = pos.immutable();
			deep = false;
			to(State.MINE);
		}
	}

	@Override
	public boolean canContinueToUse() {
		return canUse();
	}

	/** Debug: state, goal and plan. */
	@Override
	public String toString() {
		return state + (mineTarget != null ? " ore " + mineTarget.toShortString() : "") + (gasOutlet != null ? " gas " + gasOutlet.toShortString() + " t" + gasTicks + (inside ? " inside" : "") : "") + (moveGoal != null ? " to " + BlockPos.containing(moveGoal).toShortString() : "")
			+ (plan != null ? " plan " + stepIndex + "/" + plan.size() : "") + " fail " + failures;
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void stop() {
		// an enemy took its attention: the cracks fade, the block is left, the plan is redone later
		cancelBreak();
		if (inside) { // it comes out of the building with what it drew
			inside = false;
			drone.setInvisible(false);
			drone.takeVespene(Vespene.PER_TRIP);
		}
		resetMove();
	}

	@Override
	public void tick() {
		if (!(drone.level() instanceof ServerLevel level)) return;
		stateTicks++;
		if (!inside && drone.isInvisible()) drone.setInvisible(false); // saved while inside a building
		if (breaking != null) { // a block once started is finished first
			if (breakBlock(level, breaking)) afterBreak(level);
			return;
		}
		boolean griefing = level.getGameRules().getBoolean(StarMine.HIVE_GRIEFING);
		if (terran()) takeOrder(level);
		if (drone.fleeing()) {
			flee(level);
			return;
		}
		if (terran() && Engineering.engaged(drone)) {
			if (state != State.ENGINEER) to(State.ENGINEER);
		} else if (state == State.ENGINEER) {
			to(State.SEEK);
		} else if (drone.buildKind() != null) {
			if (state != State.BUILD) to(State.BUILD);
		} else if (drone.raidTarget() != null) {
			if (state != State.RAID) to(State.RAID);
		} else if (state == State.RAID || state == State.BUILD) {
			to(State.SEEK);
		} else if (drone.loadFull() && state != State.CARRY) {
			to(State.CARRY);
		}
		switch (state) {
			case SEEK -> seek(level, griefing);
			case GAS -> gas(level);
			case MINE -> mine(level, griefing);
			case CARRY -> carry(level, griefing);
			case EXPLORE -> explore(level, griefing);
			case RAID -> raid(level, griefing);
			case BUILD -> build(level);
			case TUNNEL -> tunnel(level, griefing);
			case ENGINEER -> engineer(level);
		}
	}

	private void to(State next) {
		if (next == State.CARRY && state != State.CARRY) resume = state == State.GAS || state == State.MINE ? state : State.SEEK;
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
		BlockPos gas = freeGasBuilding(level);
		if (gas != null) {
			startGas(gas);
			return;
		}
		if (griefing) {
			BlockPos ore = findOre(level, drone.blockPosition(), 16, drone.getUUID());
			if (ore != null) {
				claimGroup(level, ore);
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
		if (terran()) { // SCVs wait near their base for something to gather (or an order)
			seekCooldown = 100;
			return;
		}
		if (griefing && startTunnel(level)) return;
		startExplore(level);
	}

	// ---- GAS ----

	/** Works this outlet from now on (it gives up any other one); a load of another kind goes home first. */
	private void startGas(BlockPos outlet) {
		endGas();
		gasOutlet = outlet.immutable();
		GAS.computeIfAbsent(gasOutlet, k -> new HashSet<>()).add(drone.getUUID());
		to(drone.cargoBlocks > 0 && !"vespene".equals(drone.cargoLabel) ? State.CARRY : State.GAS);
		resume = State.GAS;
	}

	private void endGas() {
		if (gasOutlet != null) {
			Set<UUID> workers = GAS.get(gasOutlet);
			if (workers != null && workers.remove(drone.getUUID()) && workers.isEmpty()) GAS.remove(gasOutlet);
		}
		gasOutlet = null;
	}

	/** Living workers on an outlet (entries of dead or unloaded ones are dropped). */
	private static int workersOn(ServerLevel level, BlockPos outlet) {
		Set<UUID> workers = GAS.get(outlet);
		if (workers == null) return 0;
		workers.removeIf(id -> !(level.getEntity(id) instanceof StarUnit unit) || !unit.isAlive());
		return workers.size();
	}

	/** The outlet of a finished vespene building of its own hive or base nearby that wants another worker. */
	private BlockPos freeGasBuilding(ServerLevel level) {
		if (drone.hiveId() == null) return null;
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (StarUnit building : level.getEntitiesOfClass(StarUnit.class, drone.getBoundingBox().inflate(48),
				mob -> mob.kind.gas() && drone.hiveId().equals(mob.hiveId()) && mob.isAlive() && !mob.underConstruction())) {
			BlockPos outlet = Vespene.outletUnder(level, building.blockPosition());
			if (outlet == null || workersOn(level, outlet) >= GAS_WORKERS) continue;
			double d = building.distanceToSqr(drone);
			if (d < bestDist) {
				best = outlet;
				bestDist = d;
			}
		}
		return best;
	}

	/** The vespene building standing on the outlet, if any (`own`: a finished one of its hive or base). */
	private StarUnit buildingOn(ServerLevel level, BlockPos outlet, boolean own) {
		for (StarUnit building : level.getEntitiesOfClass(StarUnit.class, new AABB(outlet).inflate(2, 3, 2),
				mob -> mob.kind.gas() && mob.isAlive()))
			if (!own || drone.hiveId() != null && drone.hiveId().equals(building.hiveId()) && !building.underConstruction()) return building;
		return null;
	}

	private void gas(ServerLevel level) {
		if (gasOutlet == null) {
			to(State.SEEK);
			return;
		}
		BlockState at = level.getBlockState(gasOutlet);
		if (inside) {
			drone.getNavigation().stop();
			if (--gasTicks > 0) return;
			inside = false;
			drone.setInvisible(false);
			drone.takeVespene(Vespene.PER_TRIP);
			to(State.CARRY);
			return;
		}
		if (!Vespene.isOutlet(at)) { // the vein is gone
			endGas();
			to(State.SEEK);
			return;
		}
		if (drone.cargoBlocks > 0) {
			to(State.CARRY);
			return;
		}
		StarUnit building = buildingOn(level, gasOutlet, true);
		if (building != null) {
			Move move = moveTo(level, building.position(), building.kind.width / 2 + 1.8, false);
			if (move == Move.ARRIVED) {
				drone.getNavigation().stop();
				if (at.is(Vespene.VESPENE)) { // in it goes; the outlet is emptied and its vein refills it
					VespeneBlock.take(level, gasOutlet, at);
					inside = true;
					gasTicks = INSIDE;
					drone.setInvisible(true);
					drone.playSound(net.minecraft.sounds.SoundEvents.BUCKET_FILL, 0.6f, 0.7f);
				}
			} else if (move == Move.FAILED) {
				resetMove();
			}
			return;
		}
		if (buildingOn(level, gasOutlet, false) != null) { // another base's building, or one being built
			endGas();
			to(State.SEEK);
			return;
		}
		// a bare outlet: pumped by hand beside it
		if (!reach(gasOutlet)) {
			if (moveTo(level, Vec3.atBottomCenterOf(gasOutlet.above()), 2.2, true) == Move.FAILED) {
				endGas();
				to(State.SEEK);
			}
			gasTicks = PUMP;
			return;
		}
		drone.getNavigation().stop();
		drone.getLookControl().setLookAt(Vec3.atCenterOf(gasOutlet));
		if (!at.is(Vespene.VESPENE)) return; // empty: it waits for the vein
		if (stateTicks % 20 == 0) {
			drone.swing(InteractionHand.MAIN_HAND);
			level.sendParticles(new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(0.3f, 1f, 0.4f), 1.2f),
				gasOutlet.getX() + 0.5, gasOutlet.getY() + 1.1, gasOutlet.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0);
		}
		if (--gasTicks > 0) return;
		VespeneBlock.take(level, gasOutlet, at);
		drone.playSound(net.minecraft.sounds.SoundEvents.BUCKET_FILL, 0.8f, 0.8f);
		drone.takeVespene(Vespene.PER_TRIP);
		gasTicks = PUMP;
		to(State.CARRY);
	}

	// ---- MINE ----

	/**
	 * The nearest unclaimed ore within `radius`, away from other drones' marks (owner: drones mine in
	 * different places and directions instead of crowding); if all are near marks, the nearest anyway.
	 */
	static BlockPos findOre(ServerLevel level, BlockPos centre, int radius, UUID self) {
		List<BlockPos> others = new ArrayList<>();
		for (Map.Entry<BlockPos, UUID> claim : CLAIMS.entrySet())
			if (!claim.getValue().equals(self) && !claim.getValue().equals(NOBODY) && claim.getKey().distSqr(centre) < (radius + SPREAD) * (radius + SPREAD))
				others.add(claim.getKey());
		BlockPos best = null, crowded = null;
		double bestDist = Double.MAX_VALUE, crowdedDist = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-radius, -radius, -radius), centre.offset(radius, radius, radius))) {
			if (CLAIMS.containsKey(pos) || !HiveResources.isOre(level.getBlockState(pos))) continue;
			double d = pos.distSqr(centre);
			boolean near = false;
			for (BlockPos other : others) if (other.distSqr(pos) < SPREAD * SPREAD) { near = true; break; }
			if (!near && d < bestDist) {
				best = pos.immutable();
				bestDist = d;
			} else if (near && d < crowdedDist) {
				crowded = pos.immutable();
				crowdedDist = d;
			}
		}
		return best != null ? best : crowded;
	}

	/** Marks the ore and the connected blocks of the same kind, up to {@link #GROUP}, as this drone's. */
	private void claimGroup(ServerLevel level, BlockPos ore) {
		Block kind = level.getBlockState(ore).getBlock();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>(List.of(ore));
		Set<BlockPos> seen = new HashSet<>();
		int claimed = 0;
		while (!queue.isEmpty() && claimed < GROUP) {
			BlockPos pos = queue.poll();
			if (!seen.add(pos) || !level.getBlockState(pos).is(kind) || CLAIMS.containsKey(pos) && !drone.getUUID().equals(CLAIMS.get(pos))) continue;
			claim(pos);
			claimed++;
			for (BlockPos near : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) queue.add(near.immutable());
		}
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

	/** The next block of its marked group (or unclaimed ore right next to the last one). */
	private boolean nextInVein(ServerLevel level) {
		if (mineTarget == null) return false;
		for (BlockPos near : BlockPos.betweenClosed(mineTarget.offset(-1, -1, -1), mineTarget.offset(1, 1, 1))) {
			UUID owner = CLAIMS.get(near);
			if ((owner == null || owner.equals(drone.getUUID())) && HiveResources.isOre(level.getBlockState(near))) {
				mineTarget = near.immutable();
				claim(mineTarget);
				return true;
			}
		}
		mineTarget = null;
		releaseAll(drone.getUUID());
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
		double half = UnitKind.HATCHERY.width / 2;
		if (terran()) { // the nearest command center of the base
			StarUnit nearest = null;
			double best = Double.MAX_VALUE;
			for (StarUnit cc : level.getEntitiesOfClass(StarUnit.class, drone.getBoundingBox().inflate(160),
				mob -> mob.kind == UnitKind.COMMAND_CENTER && hive.id.equals(mob.hiveId()) && mob.isAlive() && !mob.underConstruction())) {
				double d = cc.distanceToSqr(drone);
				if (d < best) {
					best = d;
					nearest = cc;
				}
			}
			if (nearest != null) centre = nearest.position();
			half = UnitKind.COMMAND_CENTER.width / 2;
		}
		double dx = drone.getX() - centre.x, dz = drone.getZ() - centre.z;
		double flat = Math.sqrt(dx * dx + dz * dz);
		double rim = half + 3.5;
		// delivered anywhere a walk to the rim may end (it arrives within 2.5 blocks of it)
		if (flat < rim + 2.8 && Math.abs(drone.getY() - centre.y) < 4) {
			hive.deliver(drone.cargoMinerals, drone.cargoVespene, drone.cargoDiamonds, drone.cargoLabel, drone.cargoBlocks);
			HiveSavedData.get(level.getServer()).setDirty();
			drone.clearCargo();
			drone.getNavigation().stop();
			State back = resume;
			to(back == State.GAS && gasOutlet != null ? State.GAS : back == State.MINE && mineTarget != null ? State.MINE : State.SEEK);
			return;
		}
		Vec3 edge = flat < 0.01 ? centre.add(rim, 0, 0) : centre.add(dx / flat * rim, 0, dz / flat * rim);
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

	// ---- BUILD ----

	/** To the site the hive chose, then it mutates into the building (or gives the order up). */
	private void build(ServerLevel level) {
		BlockPos site = drone.buildSite();
		if (site == null) {
			drone.cancelBuild();
			return;
		}
		if (terran()) {
			terranBuild(level, site);
			return;
		}
		Move move = moveTo(level, Vec3.atBottomCenterOf(site), 3, drone.buildKind().gas()); // an outlet may be underground
		if (move == Move.ARRIVED) {
			if (!HiveLogic.mutate(drone, level, drone.buildKind(), site)) drone.cancelBuild();
		} else if (move == Move.FAILED || stateTicks > 1200) {
			drone.cancelBuild();
		}
	}

	/**
	 * SCV: to the site; there the building is placed (paid then; the site is levelled, or refused when
	 * trees, fences or harder blocks are in the way), and the SCV builds it until it is done.
	 */
	private void terranBuild(ServerLevel level, BlockPos site) {
		UnitKind kind = drone.buildKind();
		if (drone.constructing() != null) {
			if (!(level.getEntity(drone.constructing()) instanceof StarUnit building) || !building.isAlive() || !building.underConstruction()) {
				drone.setConstructing(null);
				drone.cancelBuild();
				return;
			}
			Move move = moveTo(level, building.position(), kind.width / 2 + 2.5, false);
			if (move == Move.ARRIVED) {
				drone.getLookControl().setLookAt(building);
				if (stateTicks % 20 == 0) {
					drone.swing(InteractionHand.MAIN_HAND);
					level.sendParticles(net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK, building.getX(), building.getY() + 1, building.getZ(),
						4, kind.width / 3, 0.5, kind.width / 3, 0.05);
				}
			}
			return;
		}
		Move move = moveTo(level, Vec3.atBottomCenterOf(site), kind.width / 2 + 2, kind.gas()); // an outlet may be underground
		if (move == Move.ARRIVED) {
			if (dev.starmine.colony.TerranLogic.place(drone, level, kind, site) == null) drone.cancelBuild();
		} else if (move == Move.FAILED || stateTicks > 1200) {
			drone.cancelBuild();
		}
	}

	// ---- ENGINEER ----

	/** One piece of an army's way: walk (digging if needed) within reach, then break or place the block. */
	private void engineer(ServerLevel level) {
		if (--seekCooldown > 0) return;
		Engineering.Task task = Engineering.task(level, drone);
		if (task == null) {
			drone.getNavigation().stop();
			seekCooldown = 10;
			return;
		}
		BlockPos cell = task.cell();
		boolean near = reach(cell) || drone.position().distanceTo(task.stand()) < 2.0
			&& drone.getEyePosition().distanceTo(Vec3.atCenterOf(cell)) <= task.reach();
		if (!near) { // to the open end of the way (digging there itself if it must)
			if (moveTo(level, task.stand(), 1.2, true) == Move.FAILED) {
				resetMove();
				seekCooldown = 20;
			}
			return;
		}
		drone.getNavigation().stop();
		drone.getLookControl().setLookAt(Vec3.atCenterOf(cell));
		if (!task.fill()) {
			if (DronePlanner.solid(level, cell)) start(level, cell);
			return;
		}
		if (DronePlanner.solid(level, cell)) return;
		if (!drone.placeFill(cell)) {
			if (!Engineering.payForBlock(level, drone)) {
				seekCooldown = 40; // the base cannot pay a stone yet
				return;
			}
			level.setBlockAndUpdate(cell, net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState());
			drone.swing(InteractionHand.MAIN_HAND);
			drone.playSound(net.minecraft.sounds.SoundEvents.STONE_PLACE, 1.0f, 1.0f);
		}
	}

	// ---- TUNNEL ----

	/**
	 * Hive tunnels (owner): now and then (one every 3 min per hive, at most 12) a drone digs a tunnel
	 * of about 20 blocks — sideways and down from the hive's existing ways, so tunnels join up — and a
	 * creep tumor is planted at its end so creep grows into it.
	 */
	private boolean startTunnel(ServerLevel level) {
		Hive hive = HiveSavedData.get(level.getServer()).get(drone.hiveId());
		long now = level.getGameTime();
		if (hive == null || hive.tunnels >= 12 || now < hive.nextTunnel || drone.getRandom().nextInt(4) != 0) return false;
		hive.nextTunnel = now + 3600;
		// start from a cell of the hive's ways (an underground one if there is), else its hatchery
		BlockPos from = hive.hatchery;
		List<Long> cells = new ArrayList<>(hive.routes);
		for (int i = 0; i < 16 && !cells.isEmpty(); i++) {
			BlockPos cell = BlockPos.of(cells.get(drone.getRandom().nextInt(cells.size())));
			from = cell;
			if (!level.canSeeSky(cell.above(2))) break;
		}
		double angle = drone.getRandom().nextDouble() * Math.PI * 2;
		boolean surface = level.canSeeSky(from.above(2));
		int down = surface ? 6 + drone.getRandom().nextInt(6) : drone.getRandom().nextInt(7) - 2;
		tunnelTo = from.offset((int) (Math.cos(angle) * 20), -down, (int) (Math.sin(angle) * 20));
		to(State.TUNNEL);
		return true;
	}

	private void tunnel(ServerLevel level, boolean griefing) {
		if (tunnelTo == null || !griefing) {
			to(State.SEEK);
			return;
		}
		Move move = moveTo(level, Vec3.atBottomCenterOf(tunnelTo), 2.5, true);
		if (move == Move.MOVING && stateTicks < 3600) return;
		if (move == Move.ARRIVED) {
			Hive hive = HiveSavedData.get(level.getServer()).get(drone.hiveId());
			if (hive != null) {
				hive.tunnels++;
				HiveSavedData.get(level.getServer()).setDirty();
			}
			// creep and a tumor at the tunnel's end
			BlockPos end = drone.blockPosition();
			CreepGrowth.lineCavity(level, end);
			CreepGrowth.placeTumor(level, end, dev.starmine.creep.CreepSources.TUMOR_SPACING);
		}
		tunnelTo = null;
		to(State.SEEK);
	}

	// ---- FLEE ----

	/** Away from an armed attacker: to the nearest warrior of the hive within 32 blocks, else home. */
	private void flee(ServerLevel level) {
		StarUnit guard = null;
		double best = 32 * 32;
		for (StarUnit mob : level.getEntitiesOfClass(StarUnit.class, drone.getBoundingBox().inflate(32),
			m -> drone.hiveId().equals(m.hiveId()) && m.isAlive() && !m.kind.worker() && m.kind.attack != UnitKind.Attack.NONE && !m.kind.structure())) {
			double d = mob.distanceToSqr(drone);
			if (d < best) {
				best = d;
				guard = mob;
			}
		}
		Vec3 goal = guard != null ? guard.position() : drone.home() != null ? Vec3.atBottomCenterOf(drone.home()) : drone.position();
		moveTo(level, goal, guard != null ? 3 : 7, false);
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
		if (state == State.MINE && done.equals(mineTarget)) {
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
