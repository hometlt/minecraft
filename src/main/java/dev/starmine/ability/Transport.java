package dev.starmine.ability;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.faction.Faction;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Transports (owner 14.10, docs/ABILITIES.md; SC2 LotV): the medivac, the warp prism and the overlord carry up to
 * {@link #CAPACITY} cargo slots (SC2 cargo size of each kind) of ground units of their own race and side — a player's
 * overlord only after its ventral sacs ({@link Ability#VENTRAL_SACS}); the bunker {@link #BUNKER_CAPACITY} slots of
 * infantry (and SCVs), 2 more with neosteel armor, who shoot from inside it; the command center (and a planetary
 * fortress, not an orbital) {@link #COMMAND_CENTER_CAPACITY} SCVs; the nydus network and its worms
 * {@link #NYDUS_CAPACITY} slots of zerg: one hold for all of a base's networks and worms, loaded at one and let out at
 * any other. Load: the transport takes such units within {@link #LOAD_RANGE}. Unload: a flyer flies to the point and
 * puts them down on the ground under it; a building lets them out where it stands; one chosen unit may be let out
 * alone ({@link #unloadOne}). Cargo is out of harm's way (SC2): only the transport can be hit; cargo inside a medivac,
 * prism or overlord does not fight. When a transport dies its cargo dies with it, but a bunker's and a command
 * center's walk out, and a nydus' goes on to another end of its base ({@link #atDeath}).
 * The overlord's own ferry of stuck zerg ({@link dev.starmine.entity.ZergGoals}) rides the same rules.
 */
public final class Transport {
	public static final int CAPACITY = 8, BUNKER_CAPACITY = 4, NEOSTEEL_BONUS = 2, COMMAND_CENTER_CAPACITY = 5, NYDUS_CAPACITY = 255;
	public static final double LOAD_RANGE = 5;
	/** How far a nydus end finds the other ends of its base (a worm comes out up to 112 blocks from its network). */
	static final double NYDUS_REACH = 256;

	private Transport() {
	}

	/** Cargo slots of a kind at most (0: it carries nothing). */
	public static int capacity(UnitKind kind) {
		return switch (kind) {
			case MEDIVAC, WARP_PRISM, OVERLORD -> CAPACITY;
			case BUNKER -> BUNKER_CAPACITY;
			case COMMAND_CENTER -> COMMAND_CENTER_CAPACITY;
			case NYDUS_NETWORK, NYDUS_CANAL -> NYDUS_CAPACITY;
			default -> 0;
		};
	}

	/** Cargo slots of this transport now: a player's overlord without its sacs and an orbital carry none, neosteel adds 2 to a bunker. */
	public static int capacity(StarUnit transport) {
		UnitKind kind = transport.kind;
		if (kind == UnitKind.OVERLORD && transport.owner().isPresent() && transport.mode() != Modes.SACS) return 0;
		if (kind == UnitKind.COMMAND_CENTER && transport.mode() == Modes.ORBITAL) return 0;
		int bonus = kind == UnitKind.BUNKER && Abilities.researched(transport, dev.starmine.race.Research.NEOSTEEL_ARMOR) ? NEOSTEEL_BONUS : 0;
		return capacity(kind) + bonus;
	}

	static boolean nydus(UnitKind kind) {
		return kind == UnitKind.NYDUS_NETWORK || kind == UnitKind.NYDUS_CANAL;
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
		if (transport.kind == UnitKind.COMMAND_CENTER && unit.kind != UnitKind.SCV) return false; // SCVs only
		return !unit.kind.flying && !unit.kind.structure() && size > 0 && unit.kind.race == transport.kind.race && !unit.underConstruction()
			&& Faction.allied(transport, unit) && used(transport) + size <= capacity(transport);
	}

	static @Nullable String refusal(StarUnit transport, Ability ability) {
		if (ability == Ability.LOAD && used(transport) >= capacity(transport)) return "message.starmine.transport_full";
		if (ability == Ability.UNLOAD && cargo(transport).isEmpty()) return "message.starmine.transport_empty";
		return null;
	}

	/** What it can let out: its riders, and for a nydus end the riders of all its base's ends. */
	public static List<Entity> cargo(StarUnit transport) {
		List<Entity> cargo = new ArrayList<>(transport.getPassengers());
		if (nydus(transport.kind)) for (StarUnit end : otherEnds(transport)) cargo.addAll(end.getPassengers());
		return cargo;
	}

	/** The other nydus networks and worms of its base (loaded around it). */
	private static List<StarUnit> otherEnds(StarUnit nydus) {
		return nydus.level().getEntitiesOfClass(StarUnit.class, nydus.getBoundingBox().inflate(NYDUS_REACH),
			other -> other != nydus && nydus(other.kind) && other.isAlive() && !other.underConstruction() && Objects.equals(other.hiveId(), nydus.hiveId()));
	}

	/** The riders of its base's other nydus ends come over to this one (SC2: one hold). */
	private static void gatherNydus(StarUnit nydus) {
		for (StarUnit end : otherEnds(nydus))
			for (Entity rider : List.copyOf(end.getPassengers())) {
				rider.stopRiding();
				rider.startRiding(nydus, true);
			}
	}

	/** Takes aboard the units of its side and race around it, nearest first, while there is room. */
	static void load(StarUnit transport) {
		List<StarUnit> near = transport.level().getEntitiesOfClass(StarUnit.class, transport.getBoundingBox().inflate(LOAD_RANGE, transport.kind.hoverHeight() + 4, LOAD_RANGE),
			unit -> unit.isAlive() && !unit.isPassenger() && unit != transport);
		near.sort(Comparator.comparingDouble(unit -> unit.distanceToSqr(transport)));
		for (StarUnit unit : near) if (carries(transport, unit)) unit.startRiding(transport, true);
	}

	/** Flies to the point; {@link #tick} puts the cargo down there. A building lets it out at once. */
	static void unloadAt(StarUnit transport, @Nullable Vec3 point) {
		if (transport.kind.structure()) {
			if (nydus(transport.kind)) gatherNydus(transport);
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

	/**
	 * One chosen rider out (SC2: a click on its portrait in the cargo): a building lets it out at its door, a flyer
	 * puts it down under itself; for a nydus end the rider may wait at another end of the base. False when it is not aboard.
	 */
	public static boolean unloadOne(StarUnit transport, Entity rider) {
		if (!cargo(transport).contains(rider)) return false;
		if (rider.getVehicle() != transport) {
			rider.stopRiding();
			rider.startRiding(transport, true);
		}
		putDown(transport, rider, transport.getPassengers().size() - 1, rider.getBbWidth());
		return true;
	}

	/** Every passenger to the ground under the transport, apart by the widest one's width (big bodies do not land in each other). */
	public static void unloadHere(StarUnit transport) {
		int i = 0;
		double widest = transport.getPassengers().stream().mapToDouble(Entity::getBbWidth).max().orElse(1);
		for (Entity passenger : List.copyOf(transport.getPassengers())) putDown(transport, passenger, i++, widest);
	}

	/** One passenger out: the `i`th around the transport (outside a building's walls), on the ground. */
	private static void putDown(StarUnit transport, Entity passenger, int i, double widest) {
		passenger.stopRiding();
		double angle = i * 2.4, spread = widest * (0.6 + 0.4 * (i + 1));
		if (transport.kind.structure()) spread += transport.getBbWidth() / 2 + 0.5;
		double x = transport.getX() + Math.cos(angle) * spread, z = transport.getZ() + Math.sin(angle) * spread;
		Vec3 at = new Vec3(x, transport.groundBelow(x, z), z);
		if (passenger instanceof StarUnit unit) at = dev.starmine.entity.Spots.near(transport.level(), unit.kind, at);
		passenger.teleportTo(at.x, at.y, at.z);
		if (passenger instanceof StarUnit unit) unit.command(StarUnit.Order.NONE, null);
	}

	/**
	 * A transport is dead: a bunker's and a command center's riders walk out (SC2), a nydus end's go on to another end
	 * of its base (and die with the last one), a flyer's die with it (owner 18.10, SC2).
	 */
	public static void atDeath(StarUnit transport) {
		if (transport.getPassengers().isEmpty()) return;
		if (transport.kind == UnitKind.BUNKER || transport.kind == UnitKind.COMMAND_CENTER) {
			unloadHere(transport);
			return;
		}
		StarUnit other = nydus(transport.kind) ? otherEnds(transport).stream().findFirst().orElse(null) : null;
		List<Entity> cargo = new ArrayList<>(transport.getPassengers());
		transport.ejectPassengers();
		for (Entity carried : cargo) {
			if (other != null) carried.startRiding(other, true);
			else if (carried instanceof StarUnit unit) unit.kill();
		}
	}
}
