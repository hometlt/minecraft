package dev.starmine.colony;

import dev.starmine.ability.Abilities;
import dev.starmine.ability.Ability;
import dev.starmine.ability.Transport;
import dev.starmine.entity.StarUnit;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * An army carried over water (CLOUD_TASK_2 stage 4): ground units do not walk into water, and when the
 * way {@link Engineering} plans for an army crosses it while the base has transports of the army's race
 * nearby (medivac, warp prism, overlord), those ferry the army instead of workers bridging it. Each
 * transport flies to the waiting units, loads them ({@link Ability#LOAD}), puts them down past the water at
 * the goal ({@link Ability#UNLOAD}) and comes back for more; a unit put down goes on with its order (a
 * zerg wave with its raid). No progress for {@link Engineering#STALL_TICKS} ends the ferry.
 */
final class Ferry {
	/** How far from the army a base's transport is called. */
	static final double CALL = 96;
	/** Close enough over a unit to load it. */
	static final double OVER = 2.5;
	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("StarMine/engineering");
	private static final List<Ferry> FERRIES = new ArrayList<>();

	final UUID base;
	final ResourceKey<Level> dimension;
	final StarUnit.Order order;
	final Vec3 goal;
	final boolean raid;
	long until;
	final Set<UUID> waiting = new LinkedHashSet<>(), transports = new LinkedHashSet<>();
	long lastProgress;

	private Ferry(UUID base, ResourceKey<Level> dimension, StarUnit.Order order, Vec3 goal, boolean raid, long until, long now) {
		this.base = base;
		this.dimension = dimension;
		this.order = order;
		this.goal = goal;
		this.raid = raid;
		this.until = until;
		this.lastProgress = now;
	}

	/**
	 * Starts a ferry for this army when every unit fits a transport of the base near it; the transports
	 * taken. Returns the units it took, empty when it cannot (no transport, a unit no transport takes).
	 */
	static Set<UUID> start(ServerLevel level, Hive base, List<StarUnit> army, StarUnit.Order order, Vec3 goal, boolean raid, long until) {
		Vec3 centre = army.stream().map(StarUnit::position).reduce(Vec3.ZERO, Vec3::add).scale(1.0 / army.size());
		List<StarUnit> transports = level.getEntitiesOfClass(StarUnit.class, new AABB(BlockPos.containing(centre)).inflate(CALL),
			unit -> unit.isAlive() && base.id.equals(unit.hiveId()) && Transport.capacity(unit.kind) > 0 && !unit.kind.structure()
				&& unit.getPassengers().isEmpty() && !busy(unit.getUUID()));
		if (transports.isEmpty()) return Set.of();
		for (StarUnit unit : army)
			if (transports.stream().noneMatch(transport -> fits(transport, unit))) return Set.of();
		Ferry ferry = new Ferry(base.id, level.dimension(), order, goal, raid, until, level.getGameTime());
		for (StarUnit unit : army) {
			ferry.waiting.add(unit.getUUID());
			if (raid) unit.endRaid();
			else unit.command(StarUnit.Order.NONE, null);
		}
		for (StarUnit transport : transports) {
			ferry.transports.add(transport.getUUID());
			transport.command(StarUnit.Order.NONE, null);
		}
		FERRIES.add(ferry);
		LOG.info("A ferry for an army: {} units, {} transports, to {}", army.size(), transports.size(), BlockPos.containing(goal).toShortString());
		BaseLogic.tell(level, base, "message.starmine.ferry", transports.size(), army.size());
		return Set.copyOf(ferry.waiting);
	}

	/** Whether this transport could ever take the unit (its race, its size; empty now). */
	private static boolean fits(StarUnit transport, StarUnit unit) {
		int size = unit.kind.economy().cargo();
		return unit.kind.race == transport.kind.race && size > 0 && size <= Transport.capacity(transport.kind);
	}

	static boolean busy(UUID unit) {
		for (Ferry ferry : FERRIES) if (ferry.waiting.contains(unit) || ferry.transports.contains(unit)) return true;
		return false;
	}

	/** A new order to a unit takes it out of its ferry. */
	static void release(UUID unit) {
		for (Ferry ferry : FERRIES) {
			ferry.waiting.remove(unit);
			ferry.transports.remove(unit);
		}
	}

	/** Every 10 ticks (with {@link Engineering#serverTick}). */
	static void serverTick(MinecraftServer server) {
		for (Iterator<Ferry> it = FERRIES.iterator(); it.hasNext(); ) {
			Ferry ferry = it.next();
			ServerLevel level = server.getLevel(ferry.dimension);
			if (level == null || !ferry.update(level)) {
				for (UUID id : ferry.transports) if (level != null && level.getEntity(id) instanceof StarUnit transport && transport.isAlive()) transport.command(StarUnit.Order.NONE, null);
				it.remove();
			}
		}
	}

	/** False when every unit is across, or none is left, or nothing moved for too long. */
	private boolean update(ServerLevel level) {
		long now = level.getGameTime();
		List<StarUnit> left = new ArrayList<>();
		for (Iterator<UUID> it = waiting.iterator(); it.hasNext(); ) {
			if (!(level.getEntity(it.next()) instanceof StarUnit unit) || !unit.isAlive()) {
				it.remove();
				continue;
			}
			if (!unit.isPassenger() && crossed(unit)) { // put down at the goal: it goes on
				it.remove();
				go(level, unit);
				lastProgress = now;
				continue;
			}
			left.add(unit);
		}
		if (left.isEmpty()) return false;
		List<StarUnit> fleet = new ArrayList<>();
		for (UUID id : transports) if (level.getEntity(id) instanceof StarUnit transport && transport.isAlive()) fleet.add(transport);
		if (fleet.isEmpty()) return false;
		if (now - lastProgress > Engineering.STALL_TICKS) {
			Hive base = HiveSavedData.get(level.getServer()).get(this.base);
			if (base != null) BaseLogic.tell(level, base, "message.starmine.engineering_stalled");
			LOG.info("A ferry stalled: {} units left", left.size());
			return false;
		}
		Set<StarUnit> called = new java.util.HashSet<>();
		for (StarUnit transport : fleet) {
			if (!transport.getPassengers().isEmpty()) {
				// full, or nobody else waits within reach of a load: over the water with them
				boolean more = left.stream().anyMatch(unit -> !unit.isPassenger() && !called.contains(unit) && Transport.carries(transport, unit));
				if ((!more || Transport.used(transport) >= Transport.capacity(transport.kind)) && transport.abilities.unloadingAt() == null) {
					Abilities.cast(transport, level, Ability.UNLOAD, null, goal);
					lastProgress = now;
				}
				if (transport.abilities.unloadingAt() != null) continue;
			}
			StarUnit next = left.stream().filter(unit -> !unit.isPassenger() && !called.contains(unit) && Transport.carries(transport, unit))
				.min(Comparator.comparingDouble(unit -> unit.distanceToSqr(transport))).orElse(null);
			if (next == null) continue;
			called.add(next);
			double flat = Math.hypot(transport.getX() - next.getX(), transport.getZ() - next.getZ());
			if (flat <= OVER) {
				int before = transport.getPassengers().size();
				Abilities.cast(transport, level, Ability.LOAD, null, null);
				if (transport.getPassengers().size() > before) lastProgress = now;
			} else if (transport.order() != StarUnit.Order.MOVE || transport.orderPos() == null || transport.orderPos().distanceToSqr(next.position()) > 4) {
				transport.command(StarUnit.Order.MOVE, next.position());
			}
		}
		return true;
	}

	/** Put down by a transport: on the ground, no longer where it waited. */
	private boolean crossed(StarUnit unit) {
		return unit.onGround() && unit.order() == StarUnit.Order.NONE && unit.position().distanceTo(goal) < 12;
	}

	private void go(ServerLevel level, StarUnit unit) {
		if (raid) {
			until = Math.max(until, level.getGameTime() + 1200);
			unit.joinRaid(BlockPos.containing(goal), until);
		} else {
			unit.command(order, goal);
		}
	}
}
