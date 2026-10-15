package dev.starmine.colony;

import dev.starmine.entity.DronePlanner;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Ways for an army (owner 14.10): when a ground army cannot walk to where it is sent (a wall, a cliff,
 * a river), workers of its own race and base or hive go ahead and cut it a way — SCVs for a player's
 * terran army ({@link #order}), drones for a zerg wave ({@link #raid}); any race whose workers are
 * {@link UnitKind#worker()} works the same way — a tunnel and stairs as wide and
 * tall as the army's largest ground unit, bridges of stone over gaps (from the workers' fill, else one
 * mineral a block). The way is planned with {@link DronePlanner} for that body, in segments of up to
 * {@link #REACH} blocks. The army waits at the start and moves up a few steps behind the finished part; once the
 * way reaches the goal it carries on with its order. A new order to the army, or no progress for
 * {@link #STALL_TICKS}, ends the work.
 *
 * Baritone's ideas (studied, not its code — it is LGPL and drives a player): moves cost the time they
 * take (walking, breaking by hardness, placing), the search runs over a bounded budget and long ways
 * are cut into segments planned one after another, fluids are never opened.
 */
public final class Engineering {
	public static final int WORKERS = 3, REACH = 40, BUDGET = 8000, MAX_SEGMENTS = 8, STALL_TICKS = 2400, MIND_TICKS = 1200;
	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("StarMine/engineering");

	/**
	 * A worker's next piece of work: a block to break, or one to place (`fill`); `stand` is where it works
	 * from (the open end of the way before that step) and `reach` how far from there it may work (a big
	 * body's blocks are farther apart than a worker's arm).
	 */
	public record Task(BlockPos cell, boolean fill, Vec3 stand, double reach) {
	}

	/** The army waits this many steps behind the work, out of the workers' way. */
	private static final int WAIT_BEHIND = 3;

	private static final class Job {
		final UUID base;
		/** A zerg wave: its warriors are led by their raid target (until `until`), not by orders. */
		boolean raid;
		long until;
		final ResourceKey<Level> dimension;
		final StarUnit.Order order;
		final Vec3 goal;
		final double arrive;
		final DronePlanner.Body body;
		final Set<UUID> army = new LinkedHashSet<>(), workers = new LinkedHashSet<>();
		final Map<BlockPos, UUID> claims = new HashMap<>();
		List<DronePlanner.Step> steps;
		BlockPos start;
		/** Where the way begins (its first segment's start) and the units that have come to it. */
		final BlockPos mouth;
		final Set<UUID> entered = new java.util.HashSet<>();
		int done = -1, segments = 1, lastWork;
		long lastProgress;
		/** Game time the way was opened (the army sent on); the job still minds stragglers for {@link #MIND_TICKS}. */
		long opened = -1;
		Vec3 waitAt;

		Job(UUID base, ResourceKey<Level> dimension, StarUnit.Order order, Vec3 goal, double arrive, DronePlanner.Body body,
				BlockPos start, List<DronePlanner.Step> steps, long now) {
			this.base = base;
			this.dimension = dimension;
			this.order = order;
			this.goal = goal;
			this.arrive = arrive;
			this.body = body;
			this.start = start;
			// the way's mouth: where its first cut begins (the army lines up on the way's axis there)
			int cut = 0;
			while (cut < steps.size() && steps.get(cut).dig().isEmpty() && steps.get(cut).fill().isEmpty()) cut++;
			this.mouth = cut == 0 || cut >= steps.size() ? start : steps.get(cut - 1).feet();
			this.steps = steps;
			this.lastProgress = now;
		}
	}

	private static final List<Job> JOBS = new ArrayList<>();
	private static final Map<UUID, Job> BY_UNIT = new HashMap<>();

	private Engineering() {
	}

	/**
	 * Takes over an army order when its ground units cannot walk there: plans the way, sends workers.
	 * Returns the units it took (the ground army), empty when they can walk (or no way is found, or the
	 * base has no free worker): those orders go as usual.
	 */
	public static Set<UUID> order(ServerLevel level, Hive base, List<StarUnit> units, StarUnit.Order order, Vec3 goal) {
		return begin(level, base, units, order, goal, false, 0);
	}

	private static Set<UUID> begin(ServerLevel level, Hive base, List<StarUnit> units, StarUnit.Order order, Vec3 goal, boolean raid, long until) {
		for (StarUnit unit : units) release(unit.getUUID());
		List<StarUnit> army = units.stream().filter(unit -> !unit.kind.worker() && !unit.kind.flying && !unit.kind.structure()).toList();
		if (army.isEmpty()) return Set.of();
		StarUnit lead = army.stream().max(Comparator.comparingDouble(unit -> unit.kind.width)).orElseThrow();
		Path path = lead.getNavigation().createPath(BlockPos.containing(goal), 1);
		if (path != null && path.canReach()) return Set.of();
		double width = army.stream().mapToDouble(unit -> unit.kind.width).max().orElse(1);
		double height = army.stream().mapToDouble(unit -> unit.kind.height).max().orElse(2);
		DronePlanner.Body body = DronePlanner.Body.of(width, height, REACH);
		double arrive = 2 + body.width() / 2.0;
		BlockPos start = DronePlanner.feetOf(lead.position(), body);
		List<DronePlanner.Step> steps = DronePlanner.plan(level, start, goal, arrive, null, true, 4096, DronePlanner.budgetFor(body, BUDGET), body);
		if (steps == null || steps.isEmpty()) return Set.of();
		// nothing to cut on this stretch (the goal was only too far for the game's pathfinder): walk as usual
		if (steps.stream().allMatch(step -> step.dig().isEmpty() && step.fill().isEmpty())) return Set.of();
		Job job = new Job(base.id, level.dimension(), order, goal, arrive, body, start, steps, level.getGameTime());
		job.raid = raid;
		job.until = until;
		hireWorkers(level, job, Vec3.atCenterOf(start));
		if (job.workers.isEmpty()) {
			BaseLogic.tell(level, base, "message.starmine.engineering_no_workers");
			return Set.of();
		}
		for (StarUnit unit : army) {
			job.army.add(unit.getUUID());
			BY_UNIT.put(unit.getUUID(), job);
		}
		JOBS.add(job);
		LOG.info("A way for an army: body {}x{}, from {} (lead {} at {}), {} steps to {}, {} blocks to cut", body.width(), body.height(), start.toShortString(),
			lead.kind.id, lead.blockPosition().toShortString(), steps.size(), steps.get(steps.size() - 1).feet().toShortString(),
			steps.stream().mapToInt(step -> step.dig().size()).sum());
		BaseLogic.tell(level, base, "message.starmine.engineering", job.workers.size(), steps.size());
		update(level, job);
		return Set.copyOf(job.army);
	}

	/** The unit leaves its job (a new order; a worker that died or was given other work). */
	public static void release(UUID unit) {
		Job job = BY_UNIT.remove(unit);
		if (job == null) return;
		job.army.remove(unit);
		job.workers.remove(unit);
		job.claims.values().removeIf(unit::equals);
	}

	/** Debug: the job of a unit (finished steps, segment, workers), or "none". */
	public static String describe(UUID unit) {
		Job job = BY_UNIT.get(unit);
		return job == null ? "none" : "steps " + job.done + "/" + job.steps.size() + " segment " + job.segments + " workers " + job.workers.size()
			+ " body " + job.body.width() + "x" + job.body.height() + " wait " + job.waitAt;
	}

	/** Whether this worker cuts a way for an army. */
	public static boolean engaged(StarUnit worker) {
		Job job = BY_UNIT.get(worker.getUUID());
		return job != null && job.workers.contains(worker.getUUID());
	}

	/** The next block for this worker: the nearest unclaimed unfinished one of the next few steps; null: nothing now. */
	public static @Nullable Task task(ServerLevel level, StarUnit worker) {
		Job job = BY_UNIT.get(worker.getUUID());
		if (job == null || !job.workers.contains(worker.getUUID()) || job.done < 0) return null;
		job.claims.values().removeIf(worker.getUUID()::equals);
		Task best = null;
		double bestDistance = Double.MAX_VALUE;
		double reach = Math.max(3.6, job.body.width() + job.body.height() * 0.5 + 1);
		for (int i = job.done; i < Math.min(job.steps.size(), job.done + 6); i++) {
			DronePlanner.Step step = job.steps.get(i);
			Vec3 stand = DronePlanner.centre(i == 0 ? job.start : job.steps.get(i - 1).feet(), job.body);
			for (BlockPos cell : step.dig())
				if (DronePlanner.solid(level, cell) && !job.claims.containsKey(cell)) {
					double d = worker.distanceToSqr(Vec3.atCenterOf(cell)) + (i - job.done) * 16;
					if (d < bestDistance) {
						best = new Task(cell, false, stand, reach);
						bestDistance = d;
					}
				}
			for (BlockPos cell : step.fill())
				if (!DronePlanner.solid(level, cell) && !job.claims.containsKey(cell)) {
					double d = worker.distanceToSqr(Vec3.atCenterOf(cell)) + (i - job.done) * 16;
					if (d < bestDistance) {
						best = new Task(cell, true, stand, reach);
						bestDistance = d;
					}
				}
		}
		if (best != null) job.claims.put(best.cell(), worker.getUUID());
		return best;
	}

	/** A stone for a bridge when the worker carries none: one mineral of its base. */
	public static boolean payForBlock(ServerLevel level, StarUnit worker) {
		Hive base = worker.hiveId() == null ? null : HiveSavedData.get(level.getServer()).get(worker.hiveId());
		if (base == null || base.minerals < 1) return false;
		base.minerals--;
		HiveSavedData.get(level.getServer()).setDirty();
		return true;
	}

	/** Every 10 ticks: progress of each job, where its army waits, its workers. */
	public static void serverTick(MinecraftServer server) {
		if (server.getTickCount() % 10 != 0) return;
		for (Iterator<Job> it = JOBS.iterator(); it.hasNext(); ) {
			Job job = it.next();
			ServerLevel level = server.getLevel(job.dimension);
			if (level == null || job.army.isEmpty() || !update(level, job)) {
				finish(job);
				it.remove();
			}
		}
	}

	/** False when the job is over (done, given up, or its army is gone). */
	private static boolean update(ServerLevel level, Job job) {
		List<StarUnit> army = alive(level, job.army);
		if (army.isEmpty()) return false;
		if (job.opened >= 0) return mind(level, job, army);
		int before = job.done;
		if (job.done < 0) job.done = 0;
		while (job.done < job.steps.size() && finished(level, job.steps.get(job.done))) job.done++;
		long now = level.getGameTime();
		if (job.done != before) job.lastProgress = now;
		if (job.done == job.steps.size()) {
			BlockPos end = job.steps.get(job.steps.size() - 1).feet();
			if (DronePlanner.centre(end, job.body).distanceTo(job.goal) <= job.arrive + 1 || job.segments >= MAX_SEGMENTS) {
				open(level, job, army);
				return true;
			}
			List<DronePlanner.Step> next = DronePlanner.plan(level, end, job.goal, job.arrive, null, true, 4096, DronePlanner.budgetFor(job.body, BUDGET), job.body);
			if (next == null || next.isEmpty()) {
				open(level, job, army);
				return true;
			}
			job.start = end;
			job.steps = next;
			job.done = 0;
			job.segments++;
			job.lastProgress = now;
		}
		if (now - job.lastProgress > STALL_TICKS) {
			LOG.info("A way for an army stalled: steps {}/{}, segment {}, workers {}, body {}x{}, at {}", job.done, job.steps.size(), job.segments,
				job.workers.size(), job.body.width(), job.body.height(), job.done < job.steps.size() ? job.steps.get(Math.max(0, job.done)) : "end");
			Hive base = HiveSavedData.get(level.getServer()).get(job.base);
			if (base != null) BaseLogic.tell(level, base, "message.starmine.engineering_stalled");
			for (StarUnit unit : army) if (job.raid) unit.endRaid(); else unit.command(StarUnit.Order.NONE, null);
			return false;
		}
		// the army waits at the end of the finished part
		int behind = job.done - WAIT_BEHIND;
		Vec3 wait = DronePlanner.centre(behind <= 0 ? job.start : job.steps.get(behind - 1).feet(), job.body);
		boolean moved = job.waitAt == null || job.waitAt.distanceToSqr(wait) > 0.25;
		if (moved) job.waitAt = wait;
		for (StarUnit unit : army) {
			boolean was = job.entered.contains(unit.getUUID());
			if (inside(level, job, unit) && (moved || !was)) send(level, job, unit, job.waitAt, false);
		}
		job.workers.removeIf(id -> !(level.getEntity(id) instanceof StarUnit worker) || !worker.isAlive());
		if (job.workers.size() < WORKERS) hireWorkers(level, job, wait);
		return true;
	}

	/**
	 * Whether the unit has come to the way's mouth (or past it); one that has not is sent there first,
	 * onto the way's axis: a tank waiting a few blocks aside of a tunnel cut for a thor's width walked
	 * at the rock beside its mouth and stood there.
	 */
	private static boolean inside(ServerLevel level, Job job, StarUnit unit) {
		// a zerg wave roams about its raid point and cannot line up: it goes as it is
		if (job.raid || job.entered.contains(unit.getUUID())) return true;
		Vec3 mouth = DronePlanner.centre(job.mouth, job.body);
		double flat = Math.hypot(unit.getX() - mouth.x, unit.getZ() - mouth.z);
		// within 2 blocks (an order to a point is done within 1.5 of it), or already past it
		if (flat <= 2.0 || unit.position().distanceTo(job.goal) < mouth.distanceTo(job.goal) - 2) {
			job.entered.add(unit.getUUID());
			return true;
		}
		boolean sent = unit.order() != StarUnit.Order.NONE && unit.orderPos() != null && unit.orderPos().distanceToSqr(mouth) <= 1;
		if (!sent) send(level, job, unit, mouth, false);
		return false;
	}

	/** Sends a unit of the job's army to a point: an order for terrans, the raid target for a zerg wave. */
	private static void send(ServerLevel level, Job job, StarUnit unit, Vec3 to, boolean last) {
		if (job.raid) {
			job.until = Math.max(job.until, level.getGameTime() + 1200); // the wave lasts while its way is cut
			unit.joinRaid(BlockPos.containing(to), job.until);
			return;
		}
		StarUnit.Order moving = last || job.order == StarUnit.Order.MOVE ? job.order : StarUnit.Order.ATTACK_MOVE;
		unit.command(moving, to);
	}

	/**
	 * A zerg wave sent at a target: the warriors that cannot walk there wait while drones of the hive cut
	 * the way (an ultralisk's width). Returns the warriors it took; the others raid as usual.
	 */
	public static Set<UUID> raid(ServerLevel level, Hive hive, List<StarUnit> warriors, BlockPos target, long until) {
		return begin(level, hive, warriors, StarUnit.Order.ATTACK_MOVE, Vec3.atBottomCenterOf(target), true, until);
	}

	/** The way is open: the army goes on with its order, the workers are free. */
	private static void open(ServerLevel level, Job job, List<StarUnit> army) {
		job.opened = level.getGameTime();
		army = army.stream().filter(unit -> inside(level, job, unit)).toList();
		BlockPos end = job.steps.isEmpty() ? job.start : job.steps.get(job.steps.size() - 1).feet();
		LOG.info("A way for an army is open: segment {}, steps {}, its end {} from the goal, body {}x{}", job.segments, job.steps.size(),
			String.format("%.1f", DronePlanner.centre(end, job.body).distanceTo(job.goal)), job.body.width(), job.body.height());
		for (StarUnit unit : army) send(level, job, unit, job.goal, true);
		// the workers stand in the way they cut: they walk on to its end, out of the army's way (a tank
		// stood behind three SCVs in a tunnel's mouth and gave up)
		for (UUID worker : job.workers) {
			BY_UNIT.remove(worker, job);
			if (level.getEntity(worker) instanceof StarUnit unit && unit.isAlive()) unit.command(StarUnit.Order.MOVE, job.goal);
		}
		job.workers.clear();
		job.claims.clear();
	}

	/**
	 * After the way is open: a unit that gave its order up on the way (stuck for a while behind the
	 * others, a narrow turn) is sent again, until all have arrived or {@link #MIND_TICKS} have passed.
	 */
	private static boolean mind(ServerLevel level, Job job, List<StarUnit> army) {
		if (level.getGameTime() - job.opened > MIND_TICKS) return false;
		boolean all = true;
		for (StarUnit unit : army) {
			if (unit.position().distanceTo(job.goal) <= job.arrive + 2) continue;
			all = false;
			if (!job.entered.contains(unit.getUUID())) {
				if (inside(level, job, unit)) send(level, job, unit, job.goal, true); // at the mouth now: on through the way
				continue;
			}
			boolean idle = job.raid ? unit.raidTarget() == null : unit.order() == StarUnit.Order.NONE;
			if (idle) send(level, job, unit, job.goal, true);
		}
		return !all;
	}

	private static boolean finished(ServerLevel level, DronePlanner.Step step) {
		for (BlockPos cell : step.dig()) if (DronePlanner.solid(level, cell)) return false;
		for (BlockPos cell : step.fill()) if (!DronePlanner.solid(level, cell)) return false;
		return true;
	}

	/** Free workers of the base nearest the work (not building, not on another way). */
	private static void hireWorkers(ServerLevel level, Job job, Vec3 near) {
		List<StarUnit> free = level.getEntitiesOfClass(StarUnit.class, new AABB(BlockPos.containing(near)).inflate(96),
			unit -> unit.kind.worker() && job.base.equals(unit.hiveId()) && unit.isAlive() && unit.buildKind() == null && unit.raidTarget() == null
				&& unit.constructing() == null && !BY_UNIT.containsKey(unit.getUUID()));
		free.sort(Comparator.comparingDouble(unit -> unit.distanceToSqr(near)));
		for (StarUnit worker : free) {
			if (job.workers.size() >= WORKERS) break;
			worker.command(StarUnit.Order.NONE, null);
			job.workers.add(worker.getUUID());
			BY_UNIT.put(worker.getUUID(), job);
		}
	}

	private static List<StarUnit> alive(ServerLevel level, Set<UUID> ids) {
		List<StarUnit> units = new ArrayList<>();
		for (Iterator<UUID> it = ids.iterator(); it.hasNext(); ) {
			UUID id = it.next();
			if (level.getEntity(id) instanceof StarUnit unit && unit.isAlive()) units.add(unit);
			else {
				it.remove();
				BY_UNIT.remove(id);
			}
		}
		return units;
	}

	private static void finish(Job job) {
		for (UUID id : job.army) BY_UNIT.remove(id, job);
		for (UUID id : job.workers) BY_UNIT.remove(id, job);
		job.army.clear();
		job.workers.clear();
		job.claims.clear();
	}
}
