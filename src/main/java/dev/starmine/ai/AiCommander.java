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
	public static final int DECISION_TICKS = 100, MAX_WORKERS = 12;
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
		if (awaitingPayment(level, base)) return;
		if (produceEconomy(main, level, base, rules)) return;
		// Secure the first mineral line before saving for the next technology.
		if (base.race == UnitKind.Race.TERRAN && base.count(UnitKind.SCV) >= 4 && combatants(base) >= 2
			&& base.count(UnitKind.BARRACKS) > 0 && base.count(UnitKind.BUNKER) + base.pending(UnitKind.BUNKER) == 0
			&& findSite(level, main, base, UnitKind.BUNKER) != null) {
			if (base.canAfford(UnitKind.BUNKER, 1)) orderBuild(main, level, base, UnitKind.BUNKER);
			return;
		}
		if (prepareExpansion(main, level, base)) return;
		orderGas(main, level, base, rules);
		if (awaitingPayment(level, base)) return;
		boolean developing = combatants(base, true) >= 6 || main.tickCount >= 6 * 60 * 20;
		if (!developing) {
			int before = base.minerals;
			produce(main, level, base, rules);
			if (base.minerals < before || combatProducerReady(level, base)) return;
		}
		if (developing && level.getGameTime() >= base.aiDevelopmentAfter && develop(main, level, base, rules)) return;
		if (!developing) orderBuilding(main, level, base, rules);
		if (awaitingPayment(level, base)) return;
		if (base.minerals >= 300 && combatants(base) >= 6 && level.getGameTime() >= base.aiResearchAfter) research(level, base);
		// Keep one decision in three for infrastructure savings once the first army exists.
		if (combatants(base) < 6 || base.minerals >= 400 || (level.getGameTime() / DECISION_TICKS) % 3 != 0)
			produce(main, level, base, rules);
		Territory.decide(main, level, base);
		Escorts.decide(main, level, base);
		if (base.race == UnitKind.Race.ZERG) dev.starmine.world.Nydus.decide(main, level, base);
	}

	/** Workers pay on arrival: do not spend their building budget or order another unpaid site meanwhile. */
	private static boolean awaitingPayment(ServerLevel level, Hive base) {
		var builders = level.getEntitiesOfClass(StarUnit.class, new AABB(base.hatchery).inflate(128),
			u -> u.isAlive() && base.id.equals(u.hiveId()) && u.kind.worker() && u.buildKind() != null && u.constructing() == null);
		for (StarUnit worker : builders) {
			if (worker.buildKind().name().equals(base.buildOrder) && level.getGameTime() >= base.buildOrderUntil) {
				base.aiRejectedSites.put(worker.buildSite().immutable(), level.getGameTime() + 1200);
				worker.cancelBuild();
				base.aiDevelopmentAfter = 0;
			}
		}
		return builders.stream().anyMatch(u -> u.buildKind() != null);
	}

	// ---- production ----

	/** Supply first, then workers, then a warrior its tech allows (zerglings come in pairs). */
	private static boolean produceEconomy(StarUnit main, ServerLevel level, Hive base, RaceRules rules) {
		UnitKind worker = rules.worker(), supply = rules.supply();
		int workers = base.count(worker) + base.pending(worker);
		if (workers == 0 && workerCapacity(level, base) > 0) {
			return train(main, level, base, worker, 1, true);
		}
		if (base.race == UnitKind.Race.PROTOSS && base.count(UnitKind.PYLON) + base.pending(UnitKind.PYLON) == 0
			&& (main.tickCount >= 6 * 60 * 20 || base.members.keySet().stream().anyMatch(ProtossRace::needsPower))) {
			orderBuild(main, level, base, UnitKind.PYLON);
			return true;
		}
		double free = base.supplyCap(base.race) + supply.economy().supply() * base.pending(supply) - base.supplyUsed(base.race);
		if (free < 2 && base.pending(supply) == 0 && base.supplyCap(base.race) < Hive.MAX_SUPPLY) {
			if (supply.building()) orderBuild(main, level, base, supply);
			else if (base.canAfford(supply, 1)) train(main, level, base, supply, 1, false);
			return true;
		}
		int target = workerCapacity(level, base);
		// Establish the first army before filling the mineral line completely.
		if (combatants(base) < 6 && main.tickCount < 6 * 60 * 20 && base.count(rules.main()) < 2) target = Math.min(target, 8);
		return workers < target && base.canAfford(worker, 1) && train(main, level, base, worker, 1, false);
	}

	/** Only served, distinct resource nodes count; queued workers already occupy these jobs. */
	public static int workerCapacity(ServerLevel level, Hive base) {
		Set<BlockPos> minerals = new java.util.HashSet<>();
		int gas = 0;
		for (StarUnit building : level.getEntitiesOfClass(StarUnit.class, new AABB(base.hatchery).inflate(128),
			u -> u.isAlive() && !u.underConstruction() && base.id.equals(u.hiveId()) && (u.kind.storage() || u.kind.gas()))) {
			if (building.kind.storage()) minerals.addAll(dev.starmine.mineral.Minerals.coresNear(level, building.blockPosition(), 36));
			if (building.kind.gas() && dev.starmine.vespene.Vespene.outletUnder(level, building.blockPosition()) != null) gas++;
		}
		return 2 * (minerals.size() + gas);
	}

	/** Accumulate the next actual technology's price instead of spending each delivery on basic troops. */
	private static boolean develop(StarUnit main, ServerLevel level, Hive base, RaceRules rules) {
		for (StarUnit building : level.getEntitiesOfClass(StarUnit.class, new AABB(base.hatchery).inflate(128),
			u -> u.isAlive() && !u.underConstruction() && base.id.equals(u.hiveId()))) {
			if (building.kind == UnitKind.BARRACKS || building.kind == UnitKind.FACTORY || building.kind == UnitKind.STARPORT) {
				if (building.mode() == dev.starmine.ability.Modes.NORMAL && !building.abilities.changingMode()) {
					if (!building.queue().isEmpty()) return true;
					if (dev.starmine.ability.Abilities.cast(building, level, dev.starmine.ability.Ability.BUILD_TECHLAB, null, null)) {
						base.aiDevelopmentAfter = level.getGameTime() + 1200;
						return true;
					}
					if (base.vespene >= 25) return true;
				}
			}
		}
		if (main.tickCount >= 4 * 60 * 20 && level.getGameTime() >= base.aiResearchAfter && research(level, base)) {
			return true;
		}
		Map<UnitKind, Integer> wanted = new EnumMap<>(UnitKind.class);
		for (UnitKind kind : rules.aiBuildOrder()) {
			if (base.count(kind) + base.pending(kind) >= wanted.merge(kind, 1, Integer::sum)) continue;
			kind = nextPrerequisite(base, kind);
			if (base.race == UnitKind.Race.ZERG && base.tier < kind.tier()) {
				Research tier = base.tier == 1 ? Research.LAIR : Research.HIVE;
				if (tier == Research.HIVE && base.count(UnitKind.INFESTATION_PIT) == 0) {
					if (base.pending(UnitKind.INFESTATION_PIT) > 0) return false;
					if (orderBuild(main, level, base, UnitKind.INFESTATION_PIT)) base.aiDevelopmentAfter = level.getGameTime() + 1200;
					return findSite(level, main, base, UnitKind.INFESTATION_PIT) != null;
				}
				if (main.startResearch(tier.id())) {
					base.aiDevelopmentAfter = level.getGameTime() + 1200;
					return true;
				}
				return main.queueIds().isEmpty() && tier.refusal(base, main.kind) != null;
			}
			if (base.unlocked(kind) && base.pending(kind) == 0 && findSite(level, main, base, kind) != null) {
				if (base.canAfford(kind, 1) && orderBuild(main, level, base, kind)) base.aiDevelopmentAfter = level.getGameTime() + 1200;
				return true;
			}
			break;
		}

		return false;
	}

	private static int combatants(Hive base) {
		return combatants(base, false);
	}

	private static int combatants(Hive base, boolean includePending) {
		int count = 0;
		for (UnitKind kind : UnitKind.values())
			if (!kind.structure() && !kind.worker() && kind.attack != UnitKind.Attack.NONE)
				count += base.count(kind) + (includePending ? base.pending(kind) : 0);
		return count;
	}

	private static boolean combatProducerReady(ServerLevel level, Hive base) {
		var producers = level.getEntitiesOfClass(StarUnit.class, new AABB(base.hatchery).inflate(128),
			u -> base.id.equals(u.hiveId()) && u.isAlive() && !u.underConstruction());
		for (UnitKind kind : UnitKind.values())
			if (kind.race == base.race && !kind.structure() && !kind.worker() && kind != UnitKind.QUEEN
				&& kind.attack != UnitKind.Attack.NONE && Morph.from(kind) == null && base.unlocked(kind)
				&& producers.stream().anyMatch(u -> u.kind == kind.producer())) return true;
		return false;
	}

	private static void produce(StarUnit main, ServerLevel level, Hive base, RaceRules rules) {
		UnitKind worker = rules.worker(), supply = rules.supply();
		UnitKind transport = base.race == UnitKind.Race.TERRAN ? UnitKind.MEDIVAC : base.race == UnitKind.Race.PROTOSS ? UnitKind.WARP_PRISM : null;
		if (transport != null && combatants(base) >= 6 && base.count(transport) + base.pending(transport) < Math.min(2, 1 + combatants(base) / 12)
			&& base.unlocked(transport) && base.canAfford(transport, 1) && train(main, level, base, transport, 1, false)) return;
		if (base.unlocked(UnitKind.QUEEN) && rules.race() == UnitKind.Race.ZERG && base.count(UnitKind.QUEEN) + base.pending(UnitKind.QUEEN) == 0
			&& base.canAfford(UnitKind.QUEEN, 1) && train(main, level, base, UnitKind.QUEEN, 1, false)) return;
		List<UnitKind> choices = new ArrayList<>();
		for (UnitKind kind : UnitKind.values())
			if (kind.race == rules.race() && kind.producer() != null && kind != worker && kind != supply && kind != UnitKind.QUEEN
				&& kind.attack != UnitKind.Attack.NONE && affordable(base, kind)) choices.add(kind);
		if (choices.isEmpty()) return;
		int start = level.random.nextInt(choices.size());
		for (int i = 0; i < choices.size(); i++) {
			UnitKind pick = choices.get((start + i) % choices.size());
			if (train(main, level, base, pick, pick == UnitKind.ZERGLING ? 2 : 1, false)) break;
		}
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
		List<StarUnit> producers = level.getEntitiesOfClass(StarUnit.class, new AABB(base.hatchery).inflate(128),
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
		// The emergency worker is free even when the bank cannot front its normal price.
		if (free) base.refund(kind, 1);
		boolean queued = producers.stream().filter(b -> b.queue().size() < 2).min(Comparator.comparingInt(b -> b.queue().size()))
			.map(b -> b.train(kind)).orElse(false);
		if (!queued && free) base.pay(kind, 1);
		return queued;
	}

	// ---- buildings ----

	private static UnitKind nextPrerequisite(Hive base, UnitKind kind) {
		while (!base.unlocked(kind) && kind.requires() != null && kind.requires().building()
			&& base.count(kind.requires()) + base.pending(kind.requires()) == 0) kind = kind.requires();
		return kind;
	}

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
			UnitKind next = nextPrerequisite(base, kind);
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
	private static boolean orderBuild(StarUnit main, ServerLevel level, Hive base, UnitKind kind) {
		if (Placement.refusal(base, kind) != null) return false;
		if (base.buildOrder != null && level.getGameTime() < base.buildOrderUntil && !orderDone(main, level, base)) return false;
		BlockPos site = findSite(level, main, base, kind);
		if (site == null) return false;
		StarUnit worker = freeWorker(level, main, base, Vec3.atCenterOf(site));
		if (worker == null) return false;
		worker.orderBuild(kind, site);
		base.buildOrder = kind.name();
		base.buildOrderUntil = level.getGameTime() + 1200;
		return true;
	}

	private static @Nullable StarUnit freeWorker(ServerLevel level, StarUnit main, Hive base, Vec3 near) {
		return freeWorkers(level, base, near).stream().findFirst().orElse(null);
	}

	private static List<StarUnit> freeWorkers(ServerLevel level, Hive base, Vec3 near) {
		UnitKind worker = base.race.rules().worker();
		return level.getEntitiesOfClass(StarUnit.class, new AABB(base.hatchery).inflate(128),
				mob -> mob.kind == worker && base.id.equals(mob.hiveId()) && mob.isAlive() && mob.raidTarget() == null && mob.cargoBlocks == 0
					&& mob.buildKind() == null && mob.constructing() == null && mob.order() == StarUnit.Order.NONE
					&& mob.getTarget() == null && !mob.abilities.repairing())
			.stream().sorted(Comparator.comparingDouble(mob -> mob.distanceToSqr(near))).toList();
	}

	/** A paid second/third main beside reachable resources, built by a worker after the first army. */
	static boolean orderExpansion(StarUnit main, ServerLevel level, Hive base) {
		if (!expansionReady(main, base) || Placement.refusal(base, base.race.rules().main()) != null) return false;
		BlockPos site = expansionSite(main, level, base);
		if (site == null) return false;
		StarUnit worker = freeWorker(level, main, base, Vec3.atCenterOf(site));
		if (worker == null) return false;
		worker.orderBuild(base.race.rules().main(), site);
		base.aiExpansionSite = null;
		return true;
	}

	private static boolean expansionReady(StarUnit main, Hive base) {
		UnitKind kind = base.race.rules().main();
		int workers = base.count(base.race.rules().worker());
		int localMinerals = main.level() instanceof ServerLevel level
			? dev.starmine.mineral.Minerals.coresNear(level, main.blockPosition(), 36).size() : 5;
		boolean mineralPressure = localMinerals <= 2 && (combatants(base) >= 2 || localMinerals == 0);
		return main.tickCount >= 4 * 60 * 20 && base.count(kind) < 3 && base.pending(kind) == 0
			&& workers >= (mineralPressure ? 4 : 6) && (combatants(base) >= 6 || mineralPressure)
			&& main.getHealth() >= main.getMaxHealth() / 2;
	}

	/** Save only for a real site; defence and replacing economic units take precedence. */
	private static boolean prepareExpansion(StarUnit main, ServerLevel level, Hive base) {
		if (!expansionReady(main, base)) {
			base.aiExpansionSite = null;
			return false;
		}
		if (base.aiExpansionSite != null && Placement.siteRefusal(level, base.race.rules().main(), base.aiExpansionSite, base.id) != null)
			base.aiExpansionSite = null;
		if (base.aiExpansionSite == null) {
			if (level.getGameTime() < base.aiExpansionRetry) return false;
			base.aiExpansionRetry = level.getGameTime() + 1200;
			base.aiExpansionSite = expansionSite(main, level, base);
		}
		if (base.aiExpansionSite == null) return false;
		if (base.canAfford(base.race.rules().main(), 1)) return orderExpansion(main, level, base);
		return true;
	}

	private static @Nullable BlockPos expansionSite(StarUnit main, ServerLevel level, Hive base) {
		UnitKind kind = base.race.rules().main();
		List<StarUnit> members = level.getEntitiesOfClass(StarUnit.class, new AABB(base.hatchery).inflate(128),
			u -> u.isAlive() && base.id.equals(u.hiveId()));
		if (members.stream().anyMatch(u -> u.kind.worker() && u.buildKind() != null)) return null;
		if (base.aiExpansionSite != null) return base.aiExpansionSite;
		for (BlockPos ore : BlockPos.betweenClosed(base.hatchery.offset(-64, -4, -64), base.hatchery.offset(64, 2, 64))) {
			if (!level.hasChunkAt(ore)) continue;
			var resource = level.getBlockState(ore);
			if (!dev.starmine.hive.HiveResources.isOre(resource)
				&& !(resource.is(dev.starmine.mineral.Minerals.CRYSTAL) && resource.getValue(dev.starmine.mineral.Minerals.CORE))) continue;
			BlockPos resourcePos = ore.immutable();
			if (members.stream().anyMatch(u -> u.kind.main() && u.blockPosition().distSqr(resourcePos) < 36 * 36)) continue;
			for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
				BlockPos near = ore.relative(direction, 28); // 16 blocks of mineral clearance beyond the main's footprint
				BlockPos feet = HiveLogic.groundNear(level, near.getX(), base.hatchery.getY(), near.getZ());
				if (feet == null || !level.hasChunkAt(feet)) continue;
				BlockPos site = Placement.site(level, kind, feet);
				if (site == null || site.distSqr(base.hatchery) > 80 * 80
					|| members.stream().anyMatch(u -> u.kind.main() && u.blockPosition().distSqr(site) < 40 * 40)
					|| Placement.siteRefusal(level, kind, site, base.id) != null) continue;
				return site;
			}
		}
		return null;
	}

	/**
	 * A levelled site for a building on the ground around the main building (a protoss building around
	 * one of the base's pylons, in its power), clear of other buildings.
	 */
	private static @Nullable BlockPos findSite(ServerLevel level, StarUnit main, Hive base, UnitKind kind) {
		base.aiRejectedSites.values().removeIf(until -> until <= level.getGameTime());
		int half = kind.race == UnitKind.Race.ZERG ? HiveTerrain.foundation(kind) : Placement.half(kind);
		List<StarUnit> centres = new ArrayList<>();
		if (ProtossRace.needsPower(kind))
			centres.addAll(level.getEntitiesOfClass(StarUnit.class, new AABB(base.hatchery).inflate(128),
				mob -> mob.kind == UnitKind.PYLON && base.id.equals(mob.hiveId()) && mob.isAlive() && !mob.underConstruction()));
		else centres.addAll(level.getEntitiesOfClass(StarUnit.class, new AABB(base.hatchery).inflate(128),
			mob -> mob.kind == base.race.rules().main() && base.id.equals(mob.hiveId()) && mob.isAlive() && !mob.underConstruction()));
		if (centres.isEmpty()) return null;
		for (int attempt = 0; attempt < 24; attempt++) {
			StarUnit centre = centres.get(level.random.nextInt(centres.size()));
			double angle = level.random.nextDouble() * Math.PI * 2;
			// its width is already its size (scaled again by the model, a site was sought 26 blocks off and more)
			double distance = centre.kind == base.race.rules().main() ? centre.kind.width / 2 + 2 + half + level.random.nextInt(7)
				: centre.kind.width / 2 + half + level.random.nextDouble() * Math.max(1, ProtossRace.POWER_RADIUS - centre.kind.width / 2 - half);
			int x = (int) Math.floor(centre.getX() + Math.cos(angle) * distance), z = (int) Math.floor(centre.getZ() + Math.sin(angle) * distance);
			BlockPos feet = HiveLogic.groundNear(level, x, centre.getBlockY(), z);
			if (feet == null) continue;
			BlockPos snapped = Placement.site(level, kind, feet);
			if (snapped != null && base.aiRejectedSites.containsKey(snapped)) continue;
			if (Placement.siteRefusal(level, kind, feet, base.id) == null) return Placement.site(level, kind, feet);
		}
		return null;
	}

	/**
	 * Add gas capacity after basic production, using outlets the worker can already reach.
	 */
	private static void orderGas(StarUnit main, ServerLevel level, Hive base, RaceRules rules) {
		UnitKind gas = rules.gasBuilding(), worker = rules.worker();
		int workers = base.count(worker);
		int buildings = base.count(gas) + base.pending(gas);
		if (workers < 6 || !combatProducerReady(level, base) || buildings >= Math.max(1, workers / 4)
			|| base.vespene >= 300 || !base.canAfford(gas, 1)) return;
		if (!level.getEntitiesOfClass(StarUnit.class, main.getBoundingBox().inflate(48),
			mob -> mob.kind == worker && base.id.equals(mob.hiveId()) && mob.buildKind() == gas).isEmpty()) return; // one is on its way
		Set<BlockPos> outlets = new java.util.LinkedHashSet<>();
		for (StarUnit storage : level.getEntitiesOfClass(StarUnit.class, new AABB(base.hatchery).inflate(128),
			u -> u.isAlive() && !u.underConstruction() && u.kind.storage() && base.id.equals(u.hiveId())))
			outlets.addAll(dev.starmine.vespene.Vespene.outletsNear(level, storage.blockPosition(), 40, 20).stream()
				.filter(outlet -> dev.starmine.vespene.Vespene.geyser(level, outlet)).toList()); // owner 22.10: not a bucket spot
		for (BlockPos outlet : outlets) {
			if (dev.starmine.vespene.Vespene.depleted(level, outlet)) continue;
			BlockPos site = outlet.above();
			if (Placement.siteRefusal(level, gas, site, base.id) != null) continue;
			if (!level.getEntitiesOfClass(StarUnit.class, new AABB(site).inflate(2, 2, 2), mob -> mob.kind.structure() && mob.isAlive()).isEmpty()) continue;
			StarUnit free = freeWorkers(level, base, Vec3.atCenterOf(site)).stream().limit(3)
				.filter(candidate -> gasReachable(level, base, candidate, gas, site)).findFirst().orElse(null);
			if (free == null) continue;
			free.orderBuild(gas, site);
			return;
		}
	}

	private static boolean gasReachable(ServerLevel level, Hive base, StarUnit worker, UnitKind gas, BlockPos chosen) {
		BlockPos site = Placement.site(level, gas, chosen);
		if (site == null) return false;
		Vec3 goal = Vec3.atBottomCenterOf(site);
		double reach = gas.width / 2 + 2 + dev.starmine.vespene.Vespene.coneReach(level, site);
		if (worker.position().distanceTo(goal) <= reach) return true;
		var body = dev.starmine.entity.DronePlanner.Body.DRONE;
		var path = dev.starmine.entity.DronePlanner.plan(level, dev.starmine.entity.DronePlanner.feetOf(worker.position(), body),
			goal, reach, base.routes, false, 0, 1500, body);
		// A partial route toward an underground vent must not reserve the building budget indefinitely.
		return path != null && !path.isEmpty() && dev.starmine.entity.DronePlanner.centre(path.getLast().feet(), body).distanceTo(goal) <= reach;
	}

	/** Weapons and armor at its upgrade buildings (the lower first), and the hatchery's lair and hive. */
	private static boolean research(ServerLevel level, Hive base) {
		AABB area = new AABB(base.hatchery).inflate(64);
		for (StarUnit building : level.getEntitiesOfClass(StarUnit.class, area, mob -> base.id.equals(mob.hiveId()) && mob.isAlive()
			&& mob.kind.structure() && !mob.underConstruction() && mob.queueIds().isEmpty())) {
			if ((building.kind == UnitKind.BARRACKS || building.kind == UnitKind.FACTORY || building.kind == UnitKind.STARPORT)
				&& building.mode() != dev.starmine.ability.Modes.TECHLAB) continue;
			for (Research what : Research.of(building.kind)) {
				if (what == Research.ARMOR && base.armorLevel >= base.attackLevel) continue;
				if (what == Research.WEAPONS && base.attackLevel > base.armorLevel) continue;
				// owner 17.10: the unit upgrades come with time, when it has resources to spare (an army first); a nuke only for its ghosts
				if (what == Research.NUKE && (base.count(UnitKind.GHOST) == 0 || base.minerals < 400)) continue;
				if (building.startResearch(what.id())) {
					base.aiResearchAfter = level.getGameTime() + 1200;
					return true;
				}
				String refusal = what.refusal(base, building.kind);
				// Optional research must not reserve the entire macro cycle ahead of the next building.
				if ("message.starmine.need_minerals".equals(refusal) || "message.starmine.need_vespene".equals(refusal)) return false;
			}
		}
		return false;
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
		return wave(main, level, base, null);
	}

	/** Explicit match opponent; ordinary wild colonies retain their claim-only war policy. */
	public static boolean wave(StarUnit main, ServerLevel level, Hive base, @org.jetbrains.annotations.Nullable Hive opponent) {
		long now = level.getGameTime();
		if (now < base.nextWave) return false;
		List<StarUnit> warriors = level.getEntitiesOfClass(StarUnit.class, main.getBoundingBox().inflate(48),
			mob -> base.id.equals(mob.hiveId()) && mob.isAlive() && !mob.kind.structure() && !mob.kind.worker() && mob.kind != UnitKind.LARVA
				&& mob.kind.attack != UnitKind.Attack.NONE && mob.raidTarget() == null && mob.order() == StarUnit.Order.NONE && mob.nest() == null);
		if (warriors.size() < base.waveSize) return false;
		String dimension = level.dimension().location().toString();
		Hive foe = opponent != null ? opponent : HiveSavedData.get(level.getServer()).all().stream()
			.filter(other -> other != base && other.dimension.equals(dimension) && (other.owner != null || other.race != base.race)
				&& other.hatchery.distSqr(main.blockPosition()) < (double) WAVE_RANGE * WAVE_RANGE)
			.min(Comparator.comparingDouble(other -> other.hatchery.distSqr(main.blockPosition())))
			.orElse(null);
		if (foe == null || foe == base || !foe.dimension.equals(dimension)) return false;
		if (opponent == null && foe.owner == null) { // owner 16.10: wild against wild only for each other's claims, never their main bases
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
		int reserve = base.race == UnitKind.Race.TERRAN ? Math.min(2, warriors.size() - 1) : 0;
		List<StarUnit> wave = warriors.subList(0, Math.min(MAX_WAVE, warriors.size() - reserve));
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
