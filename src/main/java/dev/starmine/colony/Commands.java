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
	/** How far from its player a unit takes orders (the command view sees as far as the chunks around the player). */
	public static final double COMMAND_RANGE = 192;

	private Commands() {
	}

	public static void command(ServerPlayer player, ColonyNetwork.UnitCommand command) {
		ServerLevel level = player.serverLevel();
		if (!CommandPanel.carried(player)) return; // owner 14.10: commands only with a command panel
		Hive base = HiveSavedData.get(level.getServer()).baseOf(player.getUUID(), level.dimension().location().toString());
		if (base == null) return;
		List<StarUnit> units = new ArrayList<>();
		for (int id : command.ids())
			if (level.getEntity(id) instanceof StarUnit unit && base.id.equals(unit.hiveId()) && unit.isAlive()
				&& unit.distanceTo(player) < COMMAND_RANGE) units.add(unit);
		Vec3 at = new Vec3(command.x(), command.y(), command.z());
		List<StarUnit> mobile = units.stream().filter(unit -> !unit.kind.structure() && unit.kind != UnitKind.LARVA).toList();
		List<StarUnit> buildings = units.stream().filter(unit -> unit.kind.structure()).toList();
		List<StarUnit> workers = mobile.stream().filter(unit -> unit.kind.worker()).toList();
		for (StarUnit unit : units) Engineering.release(unit.getUUID()); // a new order ends the army's way work
		switch (command.action()) {
			case "move" -> moveArmy(level, base, mobile, StarUnit.Order.MOVE, at);
			case "attack_move" -> moveArmy(level, base, mobile, StarUnit.Order.ATTACK_MOVE, at);
			case "patrol" -> moveArmy(level, base, mobile, StarUnit.Order.PATROL, at);
			case "hold" -> mobile.forEach(unit -> unit.command(StarUnit.Order.HOLD, null));
			case "stop" -> mobile.forEach(unit -> unit.command(StarUnit.Order.NONE, null));
			case "attack" -> {
				if (level.getEntity(command.target()) instanceof LivingEntity target && target.isAlive())
					mobile.stream().filter(unit -> unit.kind.attack != UnitKind.Attack.NONE).forEach(unit -> unit.commandAttack(target));
				else formation(mobile, StarUnit.Order.ATTACK_MOVE, at);
			}
			case "gather" -> workers.forEach(unit -> unit.commandWork(StarUnit.Work.GATHER, BlockPos.containing(at)));
			case "return" -> workers.forEach(unit -> unit.commandWork(StarUnit.Work.RETURN, null));
			case "build" -> build(level, base, workers, kind(command.arg()), at);
			case "train" -> train(level, base, units, kind(command.arg()));
			case "cancel" -> buildings.forEach(StarUnit::cancelLast);
			case "rally" -> buildings.forEach(building -> building.setRally(at));
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

	/** Units sent to one point stand in a square around it instead of crowding onto it. */
	private static void formation(List<StarUnit> units, StarUnit.Order order, Vec3 at) {
		int columns = (int) Math.ceil(Math.sqrt(units.size()));
		double spacing = 0.6 + units.stream().mapToDouble(unit -> unit.kind.width).max().orElse(1);
		for (int i = 0; i < units.size(); i++) {
			double dx = (i % columns - (columns - 1) / 2.0) * spacing, dz = (i / columns - (columns - 1) / 2.0) * spacing;
			units.get(i).command(order, units.size() == 1 ? at : at.add(dx, 0, dz));
		}
	}
}
