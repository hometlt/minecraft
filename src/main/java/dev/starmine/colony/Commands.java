package dev.starmine.colony;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveLogic;
import dev.starmine.hive.HiveSavedData;
import dev.starmine.race.Morph;
import dev.starmine.race.Placement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * A player's commands from the command view ({@link ColonyNetwork.UnitCommand}) to units and buildings
 * of their base within {@link #COMMAND_RANGE}, for every race (owner 14.10): move, attack-move,
 * patrol, hold, stop, attack; workers gather, return cargo and build (the building is put down its
 * race's way, {@link dev.starmine.race.RaceRules#placeBuilding}); producers train (a zerg larva
 * morphs, a building queues, a unit morphs into another — {@link Morph}); buildings cancel, set a rally point and research
 * ({@link dev.starmine.race.Research}). Only with a command panel ({@link CommandPanel}).
 */
public final class Commands {
	/** How far from its player, or from where its command view looks ({@link CommanderEye}), a unit takes orders. */
	public static final double COMMAND_RANGE = 192;

	private Commands() {
	}

	public static void command(ServerPlayer player, ColonyNetwork.UnitCommand command) {
		ServerLevel level = player.serverLevel();
		if (!CommandPanel.carried(player)) return; // owner 14.10: commands only with a command panel
		Hive base = HiveSavedData.get(level.getServer()).baseOrHome(player.getUUID(), level.dimension().location().toString());
		if (base == null) return;
		boolean away = !base.dimension.equals(level.dimension().location().toString()); // its army away from home: orders, no building
		List<StarUnit> units = new ArrayList<>();
		for (int id : command.ids())
			if (level.getEntity(id) instanceof StarUnit unit && base.id.equals(unit.hiveId()) && unit.isAlive()
				&& CommanderEye.near(player, unit.position(), COMMAND_RANGE)) units.add(unit);
		Vec3 at = new Vec3(command.x(), command.y(), command.z());
		List<StarUnit> mobile = units.stream().filter(unit -> !unit.kind.structure() && unit.kind != UnitKind.LARVA).toList();
		List<StarUnit> buildings = units.stream().filter(unit -> unit.kind.structure()).toList();
		List<StarUnit> lifted = buildings.stream().filter(dev.starmine.ability.LiftOff::airborne).toList(); // terran buildings fly where they are sent
		List<StarUnit> workers = mobile.stream().filter(unit -> unit.kind.worker()).toList();
		for (StarUnit unit : units) Engineering.release(unit.getUUID()); // a new order ends the army's way work
		switch (command.action()) {
			case "move", "attack_move", "patrol" -> {
				StarUnit.Order order = switch (command.action()) {
					case "move" -> StarUnit.Order.MOVE;
					case "attack_move" -> StarUnit.Order.ATTACK_MOVE;
					default -> StarUnit.Order.PATROL;
				};
				moveArmy(level, base, mobile, order, at);
				formation(lifted, StarUnit.Order.MOVE, at);
			}
			case "hold" -> mobile.forEach(unit -> unit.command(StarUnit.Order.HOLD, null));
			case "stop" -> {
				mobile.forEach(unit -> unit.command(StarUnit.Order.NONE, null));
				lifted.forEach(unit -> unit.command(StarUnit.Order.NONE, null));
			}
			case "attack" -> {
				if (level.getEntity(command.target()) instanceof LivingEntity target && target.isAlive())
					mobile.stream().filter(unit -> unit.kind.attack != UnitKind.Attack.NONE).forEach(unit -> unit.commandAttack(target));
				else formation(mobile, StarUnit.Order.ATTACK_MOVE, at);
			}
			case "gather" -> workers.forEach(unit -> unit.commandWork(StarUnit.Work.GATHER, BlockPos.containing(at)));
			case "gather_area" -> { // owner 17.10: mining an area — `at` one corner, the arg the other (x,y,z); at most 32 across, 6 up and down
				String[] other = command.arg().split(",");
				if (other.length == 3) try {
					BlockPos a = BlockPos.containing(at), b = new BlockPos(Integer.parseInt(other[0]), Integer.parseInt(other[1]), Integer.parseInt(other[2]));
					int x0 = Math.min(a.getX(), b.getX()), z0 = Math.min(a.getZ(), b.getZ()), y0 = Math.min(a.getY(), b.getY()) - 6;
					var area = new net.minecraft.world.level.levelgen.structure.BoundingBox(x0, y0, z0, Math.min(x0 + 31, Math.max(a.getX(), b.getX())),
						Math.max(a.getY(), b.getY()) + 6, Math.min(z0 + 31, Math.max(a.getZ(), b.getZ())));
					workers.forEach(unit -> unit.commandArea(area));
				} catch (NumberFormatException bad) {
					// not a corner: nothing
				}
			}
			case "return" -> workers.forEach(unit -> unit.commandWork(StarUnit.Work.RETURN, null));
			case "build" -> {
				if (!away) build(level, base, workers, kind(command.arg()), at);
			}
			case "train" -> train(level, base, units, kind(command.arg()));
			case "cancel" -> buildings.forEach(StarUnit::cancelLast);
			case "rally" -> { // a right click for buildings: a lifted terran building flies there (owner 16.10: it took the order and stayed)
				buildings.stream().filter(building -> !dev.starmine.ability.LiftOff.airborne(building)).forEach(building -> building.setRally(at));
				formation(lifted, StarUnit.Order.MOVE, at);
			}
			case "ability" -> { // dev.starmine.ability: toggles and plain ones on all, aimed ones by the nearest able unit
				String arg = command.arg() == null ? "" : command.arg();
				int colon = arg.indexOf(':'); // a choice with it: "hallucination:STALKER"
				dev.starmine.ability.Ability ability = dev.starmine.ability.Ability.byId(colon < 0 ? arg : arg.substring(0, colon));
				UnitKind choice = colon < 0 ? null : kind(arg.substring(colon + 1));
				if (ability == dev.starmine.ability.Ability.HALLUCINATION && choice != null && dev.starmine.ability.Ability.HALLUCINATIONS.contains(choice))
					for (StarUnit unit : units) unit.abilities.hallucinate = choice;
				LivingEntity target = level.getEntity(command.target()) instanceof LivingEntity living && living.isAlive() ? living : null;
				if (ability != null && !dev.starmine.ability.Abilities.order(level, units, ability, target, at)) {
					String why = units.stream().filter(unit -> ability.users().contains(unit.kind))
						.map(unit -> dev.starmine.ability.Abilities.refusal(unit, ability)).filter(java.util.Objects::nonNull).findFirst().orElse(null);
					if (why != null) BaseLogic.tell(level, base, why);
				}
			}
			case "unload_one" -> { // SC2: a click on a rider in a transport's cargo lets it out alone
				if (level.getEntity(command.target()) instanceof net.minecraft.world.entity.Entity rider)
					units.stream().filter(unit -> dev.starmine.ability.Transport.unloadOne(unit, rider)).findFirst();
			}
			case "altitude" -> { // owner 18.10: aircraft up or down (target: +1 or -1 steps)
				for (StarUnit unit : mobile) if (!unit.isPassenger()) unit.climb(Integer.signum(command.target()));
			}
			case "sky" -> { // owner 17.10: flyers up into the stratosphere, or down from it to the overworld
				for (StarUnit unit : mobile) if (unit.kind.flying && !unit.isPassenger()) dev.starmine.world.Stratosphere.shift(unit);
			}
			case "possess" -> { // owner 14.10: play as this unit (dev.starmine.possess)
				if (units.size() == 1) dev.starmine.possess.Possession.start(player, units.get(0));
			}
			case "research" -> {
				boolean started = buildings.stream().anyMatch(building -> building.startResearch(command.arg()));
				if (!started) BaseLogic.tell(level, base, "message.starmine.cannot_research");
			}
			default -> {
			}
		}
		HiveSavedData.get(level.getServer()).setDirty();
	}

	/** The nearest selected worker of the building's race goes to put it down there. */
	private static void build(ServerLevel level, Hive base, List<StarUnit> workers, UnitKind kind, Vec3 at) {
		if (kind == null) return;
		List<StarUnit> able = workers.stream().filter(unit -> unit.kind.race == kind.race).toList();
		if (able.isEmpty() || !kind.race.rules().buildings().contains(kind)) return;
		String refusal = Placement.refusal(base, kind);
		if (refusal != null && !refusal.equals("message.starmine.need_supply")) {
			BaseLogic.tell(level, base, refusal);
			return;
		}
		StarUnit worker = able.stream().min(Comparator.comparingDouble(unit -> unit.distanceToSqr(at))).orElseThrow();
		worker.command(StarUnit.Order.NONE, null);
		worker.orderBuild(kind, BlockPos.containing(at));
	}

	/** A selected producer of the kind trains it: a zerg larva morphs (zerglings come in pairs), a building queues it. */
	private static void train(ServerLevel level, Hive base, List<StarUnit> units, UnitKind kind) {
		if (kind == null) return;
		if (Morph.from(kind) != null) { // every selected unit it comes from morphs while the base can pay
			List<StarUnit> sources = units.stream().filter(unit -> Morph.able(unit, base, kind)).toList();
			if (sources.isEmpty()) return;
			String refusal = Morph.refusal(base, kind);
			if (refusal != null) {
				BaseLogic.tell(level, base, refusal);
				return;
			}
			for (StarUnit unit : sources) if (!Morph.start(unit, level, base, kind)) break;
			return;
		}
		String refusal = Placement.refusal(base, kind);
		List<StarUnit> producers = units.stream().filter(unit -> unit.kind.trains().contains(kind) && !unit.underConstruction()).toList();
		if (producers.isEmpty()) return;
		if (producers.stream().noneMatch(producer -> producer.kind.race.rules().producerReady(producer, kind))) {
			BaseLogic.tell(level, base, "message.starmine.need_techlab");
			return;
		}
		if (producers.get(0).kind == UnitKind.LARVA) {
			int count = kind == UnitKind.ZERGLING ? 2 : 1;
			if (!base.unlocked(kind) || !base.canAfford(kind, count)) {
				BaseLogic.tell(level, base, refusal != null ? refusal : "message.starmine.need_supply");
				return;
			}
			HiveLogic.morph(producers.get(0), level, base, kind, count, false);
			return;
		}
		boolean done = producers.stream().sorted(Comparator.comparingInt(b -> b.queue().size())).anyMatch(b -> b.train(kind));
		if (!done) BaseLogic.tell(level, base, refusal != null ? refusal : "message.starmine.queue_full");
	}

	private static UnitKind kind(String name) {
		try {
			return UnitKind.valueOf(name);
		} catch (IllegalArgumentException | NullPointerException e) {
			return null;
		}
	}

	/** A move order: ground army that cannot walk there waits for workers to cut it a way ({@link Engineering}). */
	private static void moveArmy(ServerLevel level, Hive base, List<StarUnit> units, StarUnit.Order order, Vec3 at) {
		java.util.Set<java.util.UUID> taken = Engineering.order(level, base, units, order, at);
		formation(units.stream().filter(unit -> !taken.contains(unit.getUUID())).toList(), order, at);
	}

	/** How spread a group may be and still keep its shape on the way (SC2's "magic box"), blocks from its middle. */
	static final double KEEP_SHAPE = 10;

	/**
	 * Units sent to one point. A tight group sent beyond itself keeps its shape, each unit going to the point
	 * plus its place in the group (SC2: they move side by side, none crosses the others to one spot); a group
	 * sent into its own midst or spread wide stands in a square round the point instead of crowding onto it.
	 */
	private static void formation(List<StarUnit> units, StarUnit.Order order, Vec3 at) {
		if (units.size() > 1) {
			double cx = units.stream().mapToDouble(StarUnit::getX).average().orElse(at.x), cz = units.stream().mapToDouble(StarUnit::getZ).average().orElse(at.z);
			double spread = units.stream().mapToDouble(unit -> Math.hypot(unit.getX() - cx, unit.getZ() - cz)).max().orElse(0);
			if (spread <= KEEP_SHAPE && Math.hypot(at.x - cx, at.z - cz) > spread + 2) {
				for (StarUnit unit : units) unit.command(order, at.add(unit.getX() - cx, 0, unit.getZ() - cz));
				return;
			}
		}
		int columns = (int) Math.ceil(Math.sqrt(units.size()));
		double spacing = 0.6 + units.stream().mapToDouble(unit -> unit.kind.width).max().orElse(1);
		for (int i = 0; i < units.size(); i++) {
			double dx = (i % columns - (columns - 1) / 2.0) * spacing, dz = (i / columns - (columns - 1) / 2.0) * spacing;
			units.get(i).command(order, units.size() == 1 ? at : at.add(dx, 0, dz));
		}
	}
}
