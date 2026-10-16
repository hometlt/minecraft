package dev.starmine.ai;

import dev.starmine.colony.Engineering;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveLogic;
import dev.starmine.hive.HiveSavedData;
import dev.starmine.hive.HiveTerrain;
import dev.starmine.race.Morph;
import dev.starmine.race.Placement;
import dev.starmine.race.ProtossRace;
import dev.starmine.race.RaceRules;
import dev.starmine.race.Research;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The AI of a wild base, every race the same way through its rules (owner 14.10, docs/ARCHITECTURE.md).
 * Every {@link #DECISION_TICKS} it: puts its vespene building on the nearest vein outlet; sends a worker
 * to build the next building of the race's order ({@link RaceRules#aiBuildOrder}) it lacks and can
 * afford; researches weapons, armor, lair and hive; keeps supply ahead (an overlord, a depot, a pylon);
 * trains workers (more as minerals come in) and then warriors its tech allows. When it has gathered a
 * wave ({@link #waveSize}) and a foe's base is within {@link #WAVE_RANGE} — a player's base, or a wild
 * base of another race — it sends the wave there (cutting a way if needed, {@link Engineering}).
 * Production goes the race's way: a zerg larva morphs, a building queues, a unit morphs ({@link Morph}).
 */
public final class AiCommander {
	public static final int DECISION_TICKS = 100, MAX_WORKERS = 8;
	public static final int WAVE_RANGE = 256, WAVE_COOLDOWN = 4 * 60 * 20, WAVE_LENGTH = 6 * 60 * 20, MAX_WAVE = 30;

	private AiCommander() {
	}

	/** Ticked by a wild base's main building (several mains share one base: once a tick). */
	public static void tick(StarUnit main, ServerLevel level, Hive base) {
		if (base.owner != null || base.lastTick == level.getGameTime()) return;
		base.lastTick = level.getGameTime();
		if (++base.decisionTimer >= DECISION_TICKS) {
			base.decisionTimer = 0;
			decide(main, level, base);
		}
		if (main.tickCount % 200 == 50) wave(main, level, base);
		HiveSavedData.get(level.getServer()).setDirty();
	}

	public static void decide(StarUnit main, ServerLevel level, Hive base) {
		RaceRules rules = base.race.rules();
		rules.tendConstruction(main, level, base);
		orderGas(main, level, base, rules);
		orderBuilding(main, level, base, rules);
		research(level, base);
		produce(main, level, base, rules);
		Territory.decide(main, level, base);
		if (base.race == UnitKind.Race.ZERG) dev.starmine.world.Nydus.decide(main, level, base);
	}

	// ---- production ----

	/** Supply first, then workers, then a warrior its tech allows (zerglings come in pairs). */
	private static void produce(StarUnit main, ServerLevel level, Hive base, RaceRules rules) {
		UnitKind worker = rules.worker(), supply = rules.supply();
		int workers = base.count(worker) + base.pending(worker);
		if (workers == 0) {
			train(main, level, base, worker, 1, true);
			return;
		}
		double free = base.supplyCap() + supply.economy().supply() * base.pending(supply) - base.supplyUsed();
		if (free < 2 && base.pending(supply) == 0 && base.supplyCap() < Hive.MAX_SUPPLY) {
			if (supply.building()) orderBuild(main, level, base, supply);
			else if (base.canAfford(supply, 1)) train(main, level, base, supply, 1, false);
			return;
		}
		if (base.unlocked(UnitKind.QUEEN) && rules.race() == UnitKind.Race.ZERG && base.count(UnitKind.QUEEN) + base.pending(UnitKind.QUEEN) == 0
			&& base.canAfford(UnitKind.QUEEN, 1) && train(main, level, base, UnitKind.QUEEN, 1, false)) return;
		// at least 4 (a base builds only with 4 workers: a fresh zerg hive with 2 drones and no ore near waited for ever)
		if (workers < Math.min(MAX_WORKERS, Math.max(4, 2 + base.mineralsCollected / 200)) && base.canAfford(worker, 1)) {
			train(main, level, base, worker, 1, false);
			return;
		}
		List<UnitKind> choices = new ArrayList<>();
		for (UnitKind kind : UnitKind.values())
			if (kind.race == rules.race() && kind.producer() != null && kind != worker && kind != supply && kind != UnitKind.QUEEN
				&& kind.attack != UnitKind.Attack.NONE && affordable(base, kind)) choices.add(kind);
		if (choices.isEmpty()) return;
		UnitKind pick = choices.get(level.random.nextInt(choices.size()));
		train(main, level, base, pick, pick == UnitKind.ZERGLING ? 2 : 1, false);
	}

	/** Whether the base can make the kind now: a morph pays its difference ({@link Morph}), zerglings come in pairs. */
	private static boolean affordable(Hive base, UnitKind kind) {
		if (Morph.from(kind) != null) return Morph.refusal(base, kind) == null && base.count(Morph.from(kind)) > 0;
		return base.unlocked(kind) && base.canAfford(kind, kind == UnitKind.ZERGLING ? 2 : 1);
	}

	/** Trains a unit its race's way: a larva of the base morphs, a producer building queues it, a unit of the base morphs into it. */
	private static boolean train(StarUnit main, ServerLevel level, Hive base, UnitKind kind, int count, boolean free) {
		UnitKind producer = kind.producer();
		if (producer == null) return false;
		List<StarUnit> producers = level.getEntitiesOfClass(StarUnit.class, main.getBoundingBox().inflate(48),
			mob -> mob.kind == producer && base.id.equals(mob.hiveId()) && mob.isAlive() && !mob.underConstruction());
		if (producers.isEmpty()) return false;
		if (Morph.from(kind) != null) {
			for (StarUnit unit : producers) if (unit.order() == StarUnit.Order.NONE && Morph.start(unit, level, base, kind)) return true;
			return false;
		}
		if (producer == UnitKind.LARVA) {
			HiveLogic.morph(producers.get(level.random.nextInt(producers.size())), level, base, kind, count, free);
			return true;
		}
		boolean queued = producers.stream().filter(b -> b.queue().size() < 2).min(Comparator.comparingInt(b -> b.queue().size()))
			.map(b -> b.train(kind)).orElse(false);
		if (queued && free) base.refund(kind, 1); // the first worker of an empty base costs nothing
		return queued;
	}

	// ---- buildings ----

	/** The next building of the race's order it lacks and can afford: a free worker goes to build it. */
	private static void orderBuilding(StarUnit main, ServerLevel level, Hive base, RaceRules rules) {
		long now = level.getGameTime();
		if (base.buildOrder != null && now < base.buildOrderUntil && !orderDone(main, level, base)) return;
		base.buildOrder = null;
		if (base.count(rules.worker()) < 4) return;
		Map<UnitKind, Integer> wanted = new EnumMap<>(UnitKind.class);
		for (UnitKind kind : rules.aiBuildOrder()) {
			int want = wanted.merge(kind, 1, Integer::sum);
			if (base.count(kind) + base.pending(kind) >= want) continue;
			// what it needs first (a supply depot before barracks…), when that is missing too
			UnitKind next = kind;
			while (!base.unlocked(next) && next.requires() != null && next.requires().building()
				&& base.count(next.requires()) + base.pending(next.requires()) == 0) next = next.requires();
			// a protoss building needs a powered spot: a pylon first when the base has none (finished or coming)
			if (ProtossRace.needsPower(next) && base.count(UnitKind.PYLON) == 0) next = base.pending(UnitKind.PYLON) == 0 ? UnitKind.PYLON : null;
			if (next != null && base.unlocked(next) && base.canAfford(next, 1)) orderBuild(main, level, base, next);
			return;
		}
	}

	/**
	 * Whether the building last ordered stands finished (no worker still going to build it, none of it
	 * under construction): the next one may be ordered at once. Waiting out the order's whole minute
	 * left a wild base idle for 40 s after each building.
	 */
	private static boolean orderDone(StarUnit main, ServerLevel level, Hive base) {
		UnitKind kind;
		try {
			kind = UnitKind.valueOf(base.buildOrder);
		} catch (IllegalArgumentException e) {
			return true;
		}
		if (base.pending(kind) > 0) return false;
		UnitKind worker = base.race.rules().worker();
		return level.getEntitiesOfClass(StarUnit.class, main.getBoundingBox().inflate(48),
			mob -> mob.kind == worker && base.id.equals(mob.hiveId()) && mob.isAlive() && (mob.buildKind() == kind || mob.constructing() != null)).isEmpty();
	}

	/** A free worker of the base goes to build `kind` on a site near the main building (in pylon power for protoss). */
	private static void orderBuild(StarUnit main, ServerLevel level, Hive base, UnitKind kind) {
		if (base.buildOrder != null && level.getGameTime() < base.buildOrderUntil && !orderDone(main, level, base)) return;
		BlockPos site = findSite(level, main, base, kind);
		if (site == null) return;
		StarUnit worker = freeWorker(level, main, base, Vec3.atCenterOf(site));
		if (worker == null) return;
		worker.orderBuild(kind, site);
		base.buildOrder = kind.name();
		base.buildOrderUntil = level.getGameTime() + 1200;
	}

	private static @Nullable StarUnit freeWorker(ServerLevel level, StarUnit main, Hive base, Vec3 near) {
		UnitKind worker = base.race.rules().worker();
		return level.getEntitiesOfClass(StarUnit.class, main.getBoundingBox().inflate(48),
				mob -> mob.kind == worker && base.id.equals(mob.hiveId()) && mob.isAlive() && mob.raidTarget() == null && mob.cargoBlocks == 0
					&& mob.buildKind() == null && mob.constructing() == null)
			.stream().min(Comparator.comparingDouble(mob -> mob.distanceToSqr(near))).orElse(null);
	}

	/**
	 * A levelled site for a building on the ground around the main building (a protoss building around
	 * one of the base's pylons, in its power), clear of other buildings.
	 */
	private static @Nullable BlockPos findSite(ServerLevel level, StarUnit main, Hive base, UnitKind kind) {
		int half = kind.race == UnitKind.Race.ZERG ? HiveTerrain.foundation(kind) : Placement.half(kind);
		List<StarUnit> centres = new ArrayList<>();
		if (ProtossRace.needsPower(kind))
			centres.addAll(level.getEntitiesOfClass(StarUnit.class, main.getBoundingBox().inflate(48),
				mob -> mob.kind == UnitKind.PYLON && base.id.equals(mob.hiveId()) && mob.isAlive() && !mob.underConstruction()));
		else centres.add(main);
		if (centres.isEmpty()) return null;
		for (int attempt = 0; attempt < 24; attempt++) {
			StarUnit centre = centres.get(level.random.nextInt(centres.size()));
			double angle = level.random.nextDouble() * Math.PI * 2;
			// its width is already its size (scaled again by the model, a site was sought 26 blocks off and more)
			double distance = centre == main ? main.kind.width / 2 + 2 + half + level.random.nextInt(7)
				: centre.kind.width / 2 + half + level.random.nextDouble() * Math.max(1, ProtossRace.POWER_RADIUS - centre.kind.width / 2 - half);
			int x = (int) Math.floor(centre.getX() + Math.cos(angle) * distance), z = (int) Math.floor(centre.getZ() + Math.sin(angle) * distance);
			BlockPos feet = HiveLogic.groundNear(level, x, centre.getBlockY(), z);
			if (feet == null) continue;
			if (!Placement.siteFree(level, kind, feet) || kind.race.rules().siteRefusal(level, kind, feet, base.id) != null) continue;
			if (HiveTerrain.prepare(level, feet, Math.max(0, half - 1), (int) Math.ceil(kind.height), false)) return feet;
		}
		return null;
	}

	/**
	 * Vespene: with 3 workers or more, one more vespene building on the nearest free vein outlet near
	 * the main building (one, then one more per 8 workers, at most 4); they then take 3 workers each.
	 */
	private static void orderGas(StarUnit main, ServerLevel level, Hive base, RaceRules rules) {
		UnitKind gas = rules.gasBuilding(), worker = rules.worker();
		int workers = base.count(worker);
		int buildings = base.count(gas) + base.pending(gas);
		if (workers < 3 || buildings >= Math.min(4, 1 + workers / 8) || !base.canAfford(gas, 1)) return;
		if (!level.getEntitiesOfClass(StarUnit.class, main.getBoundingBox().inflate(48),
			mob -> mob.kind == worker && base.id.equals(mob.hiveId()) && mob.buildKind() == gas).isEmpty()) return; // one is on its way
		for (BlockPos outlet : dev.starmine.vespene.Vespene.outletsNear(level, main.blockPosition(), 24, 16)) {
			BlockPos site = outlet.above();
			if (!level.getEntitiesOfClass(StarUnit.class, new AABB(site).inflate(2, 2, 2), mob -> mob.kind.structure() && mob.isAlive()).isEmpty()) continue;
			StarUnit free = freeWorker(level, main, base, Vec3.atCenterOf(site));
			if (free != null) free.orderBuild(gas, site);
			return;
		}
	}

	/** Weapons and armor at its upgrade buildings (the lower first), and the hatchery's lair and hive. */
	private static void research(ServerLevel level, Hive base) {
		AABB area = new AABB(base.hatchery).inflate(64);
		for (StarUnit building : level.getEntitiesOfClass(StarUnit.class, area, mob -> base.id.equals(mob.hiveId()) && mob.isAlive() && mob.kind.structure())) {
			for (Research what : Research.of(building.kind)) {
				if (what == Research.ARMOR && base.armorLevel >= base.attackLevel) continue;
				if (what == Research.WEAPONS && base.attackLevel > base.armorLevel) continue;
				if (building.startResearch(what.id())) break;
			}
		}
	}

	// ---- waves ----

	/** Warriors gathered for the next wave: 6 at first, 3 more after each. */
	public static int waveSize(Hive base) {
		return base.waveSize;
	}

	/**
	 * A wave: when a foe's base stands within {@link #WAVE_RANGE} and the base has gathered its wave of
	 * warriors near its main building, all of them (at most {@link #MAX_WAVE}) go for the foe's nearest
	 * building. Zerg go as a raid; the others as an attack-move order.
	 */
	public static boolean wave(StarUnit main, ServerLevel level, Hive base) {
		long now = level.getGameTime();
		if (now < base.nextWave) return false;
		List<StarUnit> warriors = level.getEntitiesOfClass(StarUnit.class, main.getBoundingBox().inflate(48),
			mob -> base.id.equals(mob.hiveId()) && mob.isAlive() && !mob.kind.structure() && !mob.kind.worker() && mob.kind != UnitKind.LARVA
				&& mob.kind.attack != UnitKind.Attack.NONE && mob.raidTarget() == null && mob.order() == StarUnit.Order.NONE && mob.nest() == null);
		if (warriors.size() < base.waveSize) return false;
		String dimension = level.dimension().location().toString();
		Hive foe = HiveSavedData.get(level.getServer()).all().stream()
			.filter(other -> other != base && other.dimension.equals(dimension) && (other.owner != null || other.race != base.race)
				&& other.hatchery.distSqr(main.blockPosition()) < (double) WAVE_RANGE * WAVE_RANGE)
			.min(Comparator.comparingDouble(other -> other.hatchery.distSqr(main.blockPosition())))
			.orElse(null);
		if (foe == null) return false;
		if (foe.owner == null) { // owner 16.10: wild against wild only for each other's claims, never their main bases
			BlockPos claim = Territory.foeClaim(level, base, main.blockPosition(), WAVE_RANGE);
			if (claim == null) return false;
			Hive holder = HiveSavedData.get(level.getServer()).all().stream().filter(other -> other.territory.contains(claim)).findFirst().orElse(foe);
			return send(main, level, base, warriors, holder, claim, now);
		}
		BlockPos target = level.getEntitiesOfClass(StarUnit.class, new AABB(foe.hatchery).inflate(64),
				mob -> foe.id.equals(mob.hiveId()) && mob.isAlive() && mob.kind.structure())
			.stream().min(Comparator.comparingDouble(mob -> mob.distanceToSqr(main))).map(StarUnit::blockPosition)
			.orElse(foe.hatchery);
		return send(main, level, base, warriors, foe, target, now);
	}

	/** Sends a wave of the gathered warriors at `target` (a building or a claim of `foe`). */
	private static boolean send(StarUnit main, ServerLevel level, Hive base, List<StarUnit> warriors, Hive foe, BlockPos target, long now) {
		List<StarUnit> wave = warriors.subList(0, Math.min(MAX_WAVE, warriors.size()));
		long until = now + WAVE_LENGTH;
		if (base.race == UnitKind.Race.ZERG) { // a raid; the wave waits while drones cut a way if it cannot walk there
			Set<UUID> waiting = Engineering.raid(level, base, wave, target, until);
			for (StarUnit warrior : wave) if (!waiting.contains(warrior.getUUID())) warrior.joinRaid(target, until);
		} else {
			Vec3 at = Vec3.atBottomCenterOf(target);
			Set<UUID> waiting = Engineering.order(level, base, wave, StarUnit.Order.ATTACK_MOVE, at);
			for (StarUnit warrior : wave) if (!waiting.contains(warrior.getUUID())) warrior.command(StarUnit.Order.ATTACK_MOVE, at);
		}
		base.waveSize = Math.min(MAX_WAVE, base.waveSize + 3);
		base.nextWave = now + WAVE_COOLDOWN;
		base.nextRaid = Math.max(base.nextRaid, now + HiveLogic.RAID_COOLDOWN);
		if (foe.owner != null) dev.starmine.colony.BaseLogic.tell(level, foe, "message.starmine.wave_attack",
			net.minecraft.network.chat.Component.translatable("race.starmine." + base.race.name().toLowerCase()));
		return true;
	}
}
