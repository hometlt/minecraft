package dev.starmine.ability;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.faction.Faction;
import java.util.Comparator;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Transports (owner 14.10, docs/ABILITIES.md): the medivac, the warp prism and the overlord carry up to
 * {@link #CAPACITY} cargo slots (SC2 cargo size of each kind) of ground units of their own race and
 * side; the bunker {@link #BUNKER_CAPACITY} slots of infantry (and SCVs), who shoot from inside it.
 * Load: the transport takes such units within {@link #LOAD_RANGE}. Unload: it flies to the point and
 * puts them down on the ground under it (a bunker lets them out where it stands). Cargo is out of harm's
 * way (SC2): only the transport can be hit; cargo inside a medivac, prism or overlord does not fight.
 * The overlord's own ferry of stuck zerg ({@link dev.starmine.entity.ZergGoals}) rides the same rules.
 */
public final class Transport {
	public static final int CAPACITY = 8, BUNKER_CAPACITY = 4;
	public static final double LOAD_RANGE = 5;

	private Transport() {
	}

	/** Cargo slots of a kind (0: it carries nothing). */
	public static int capacity(UnitKind kind) {
		return kind == UnitKind.MEDIVAC || kind == UnitKind.WARP_PRISM || kind == UnitKind.OVERLORD ? CAPACITY : kind == UnitKind.BUNKER ? BUNKER_CAPACITY : 0;
	}

	/** Slots taken now. */
	public static int used(StarUnit transport) {
		int used = 0;
		for (Entity rider : transport.getPassengers()) if (rider instanceof StarUnit unit) used += unit.kind.economy().cargo();
		return used;
	}

	/** Whether `transport` may take `unit` aboard now. */
	public static boolean carries(StarUnit transport, Entity passenger) {
		if (!(passenger instanceof StarUnit unit) || capacity(transport.kind) == 0) return false;
		int size = unit.kind.economy().cargo();
		if (transport.kind == UnitKind.BUNKER && !(unit.biological() || unit.kind == UnitKind.SCV)) return false; // infantry only
		return !unit.kind.flying && !unit.kind.structure() && size > 0 && unit.kind.race == transport.kind.race && !unit.underConstruction()
			&& Faction.allied(transport, unit) && used(transport) + size <= capacity(transport.kind);
	}

	static @Nullable String refusal(StarUnit transport, Ability ability) {
		if (ability == Ability.LOAD && used(transport) >= capacity(transport.kind)) return "message.starmine.transport_full";
		if (ability == Ability.UNLOAD && transport.getPassengers().isEmpty()) return "message.starmine.transport_empty";
		return null;
	}

	/** Takes aboard the units of its side and race around it, nearest first, while there is room. */
	static void load(StarUnit transport) {
		List<StarUnit> near = transport.level().getEntitiesOfClass(StarUnit.class, transport.getBoundingBox().inflate(LOAD_RANGE, transport.kind.hoverHeight() + 4, LOAD_RANGE),
			unit -> unit.isAlive() && !unit.isPassenger() && unit != transport);
		near.sort(Comparator.comparingDouble(unit -> unit.distanceToSqr(transport)));
		for (StarUnit unit : near) if (carries(transport, unit)) unit.startRiding(transport, true);
	}

	/** Flies to the point; {@link #tick} puts the cargo down there. */
	static void unloadAt(StarUnit transport, @Nullable Vec3 point) {
		if (transport.kind.structure()) { // a bunker: out at its door
			unloadHere(transport);
			return;
		}
		if (point == null) return;
		transport.abilities.unloadAt = point;
		transport.command(StarUnit.Order.MOVE, point);
	}

	static void tick(StarUnit transport, ServerLevel level) {
		Vec3 point = transport.abilities.unloadAt;
		if (transport.getPassengers().isEmpty()) {
			transport.abilities.unloadAt = null;
			return;
		}
		double dx = transport.getX() - point.x, dz = transport.getZ() - point.z;
		if (dx * dx + dz * dz > 9) return;
		unloadHere(transport);
		transport.abilities.unloadAt = null;
	}

	/** Whether a rider fights from where it is: from a bunker yes, from a flying transport no (SC2). */
	public static boolean firesFrom(Entity vehicle) {
		return vehicle instanceof StarUnit transport && transport.kind == UnitKind.BUNKER;
	}

	/** Every passenger to the ground under the transport, apart by the widest one's width (big bodies do not land in each other). */
	public static void unloadHere(StarUnit transport) {
		int i = 0;
		double widest = transport.getPassengers().stream().mapToDouble(Entity::getBbWidth).max().orElse(1);
		for (Entity passenger : List.copyOf(transport.getPassengers())) {
			passenger.stopRiding();
			double angle = i++ * 2.4, spread = widest * (0.6 + 0.4 * i);
			if (transport.kind.structure()) spread += transport.getBbWidth() / 2 + 0.5;
			double x = transport.getX() + Math.cos(angle) * spread, z = transport.getZ() + Math.sin(angle) * spread;
			Vec3 at = new Vec3(x, transport.groundBelow(x, z), z);
			if (passenger instanceof StarUnit unit) at = dev.starmine.entity.Spots.near(transport.level(), unit.kind, at);
			passenger.teleportTo(at.x, at.y, at.z);
			if (passenger instanceof StarUnit unit) unit.command(StarUnit.Order.NONE, null);
		}
	}
}
