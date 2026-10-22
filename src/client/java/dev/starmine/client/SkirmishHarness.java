package dev.starmine.client;

import com.google.gson.GsonBuilder;
import dev.starmine.StarMine;
import dev.starmine.colony.ColonyNetwork;
import dev.starmine.colony.CommandPanel;
import dev.starmine.colony.Commands;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveLogic;
import dev.starmine.hive.HiveSavedData;
import dev.starmine.race.Placement;
import dev.starmine.race.Research;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import static dev.starmine.entity.UnitKind.*;

/** Dev-only macro player versus the unmodified wild AI. Commands keep normal costs, timers and validation. */
final class SkirmishHarness {
	static volatile boolean done;
	static volatile int round = Integer.getInteger("starmine.skirmish.startRound", 0), seconds;
	static volatile List<Map<String, Object>> results = List.of();
	static final boolean AI_VS_AI = Boolean.getBoolean("starmine.skirmish.aiVsAi");
	static final boolean SINGLE = System.getProperty("starmine.harness", "").contains("singlematch");
	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("StarMine/skirmish");
	private static final int LIMIT = Integer.getInteger("starmine.skirmish.minutes", 20) * 60 * 20;
	private static final UnitKind[] PLAYERS = {COMMAND_CENTER, HATCHERY, NEXUS};
	private static final UnitKind[] ENEMIES = {HATCHERY, NEXUS, COMMAND_CENTER};
	private static final int LAST_ROUND = SINGLE ? round + 1 : PLAYERS.length;
	private static final List<Map<String, Object>> rows = new ArrayList<>();
	private static MinecraftServer server;
	private static Path output;
	private static StarUnit home, enemy;
	private static Hive ownBase, aiBase;
	private static long start;
	private static int y, decision;
	private static boolean active, raided;
	private static final Set<UUID> playerBuilt = new HashSet<>(), enemyBuilt = new HashSet<>(), enemyDefenders = new HashSet<>();
	private static final Set<UUID> playerArmy = new HashSet<>(), enemyArmy = new HashSet<>(), defendersResponded = new HashSet<>();
	private static final Set<UnitKind> playerArmyKinds = EnumSet.noneOf(UnitKind.class), enemyArmyKinds = EnumSet.noneOf(UnitKind.class);
	private static int maxPlayerWorkers, maxEnemyWorkers, maxEnemyMains, maxClaims, maxResearch;
	private static int firstEnemyArmy, firstEnemyAttack;
	private static float playerStructureDamage, enemyStructureDamage;
	private static final Map<UUID, Float> health = new HashMap<>();

	static void start(Minecraft mc) {
		mc.options.framerateLimit().set(30);
		mc.options.renderDistance().set(6);
		server = mc.getSingleplayerServer();
		output = mc.gameDirectory.toPath().resolve(AI_VS_AI ? (SINGLE ? "harness/ai-vs-ai-budget-results.json" : "harness/ai-vs-ai-results.json") : "harness/skirmish-results.json");
		ServerTickEvents.END_SERVER_TICK.register(s -> { if (s == server && active) tick(); });
		server.execute(() -> {
			server.tickRateManager().setTickRate(60);
			ServerLevel level = server.overworld();
			level.getGameRules().getRule(StarMine.AI_PRESSURE).set(0, server); // no free pressure drops in an economic match
			for (int cx = -10; cx <= 10; cx++) for (int cz = -8; cz <= 8; cz++) level.setChunkForced(cx, cz, true);
			setup();
		});
	}

	private static ServerPlayer player() { return server.getPlayerList().getPlayers().get(0); }
	private static Hive base(StarUnit unit) { return unit.hiveId() == null ? null : HiveSavedData.get(server).get(unit.hiveId()); }
	private static List<StarUnit> units(Hive base) {
		return server.overworld().getEntitiesOfClass(StarUnit.class, new AABB(-200, y - 8, -160, 200, y + 40, 160),
			u -> u.isAlive() && base.id.equals(u.hiveId()));
	}
	private static boolean soldier(StarUnit u) { return !u.kind.structure() && !u.kind.worker() && u.kind.attack != UnitKind.Attack.NONE; }

	private static void setup() {
		ServerLevel level = server.overworld();
		List<Entity> previous = new ArrayList<>();
		level.getAllEntities().forEach(previous::add);
		for (Entity e : previous) if (e instanceof StarUnit) e.discard();
		HiveSavedData.get(server).clear();
		var saved = HiveSavedData.get(server);
		saved.structuresDone.add("DEPOSIT:SPAWN");
		for (int rx = -2; rx <= 2; rx++) for (int rz = -2; rz <= 2; rz++) saved.structuresDone.add("DEPOSIT:" + rx + ":" + rz);
		y = 0; // enough real ground for full native vespene veins
		for (BlockPos at : BlockPos.betweenClosed(-128, -64, -96, 128, y + 20, 96)) {
			var state = at.getY() == -64 ? Blocks.BEDROCK.defaultBlockState() : at.getY() < y - 3 ? Blocks.STONE.defaultBlockState()
				: at.getY() < y - 1 ? Blocks.DIRT.defaultBlockState() : at.getY() == y - 1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState();
			if (!level.getBlockState(at).equals(state)) level.setBlock(at, state, 2);
		}
		var tumors = dev.starmine.creep.CreepSources.tumors(level);
		for (BlockPos at : List.copyOf(tumors.all())) tumors.remove(at);
		player().setGameMode(AI_VS_AI ? GameType.SPECTATOR : GameType.SURVIVAL);
		player().getAbilities().invulnerable = true;
		player().onUpdateAbilities();
		player().teleportTo(level, 0.5, y, 90.5, 180, 0); // outside the raid radius, on prepared ground
		player().getInventory().setItem(0, new ItemStack(CommandPanel.ITEM));
		// Equal ore and real regenerating gas veins, outside both main buildings' footprints.
		for (int x : new int[] {-64, 64}) {
			for (int z : new int[] {-18, 50}) {
				var gas = dev.starmine.mineral.Deposits.build(level, net.minecraft.util.RandomSource.create(9051L), x, z, x, z + 18);
				int crystals = dev.starmine.mineral.Minerals.coresNear(level, new BlockPos(x, y, z), 14).size();
				// the field's own 1300/800 split stays unless a run overrides it explicitly (-Dstarmine.skirmish.mineralsPerCore)
				Integer perCore = Integer.getInteger("starmine.skirmish.mineralsPerCore");
				if (perCore != null) for (BlockPos core : dev.starmine.mineral.Minerals.coresNear(level, new BlockPos(x, y, z), 14))
					if (level.getBlockEntity(core) instanceof dev.starmine.mineral.Minerals.Core deposit) deposit.setAmount(perCore);
				LOG.info("[skirmish] native field {},{}: {} crystals, {} geysers {}", x, z, crystals, gas.size(), gas);
				if (gas.size() != 2 || crystals != 5)
					throw new IllegalStateException("match resource field incomplete at " + x + "," + z + ": " + crystals + " crystals, " + gas.size() + " geysers");
			}
			if (dev.starmine.vespene.Vespene.outletsNear(level, new BlockPos(x, y, 0), 24, 16).size() < 2)
				throw new IllegalStateException("starting geysers outside AI reach at " + x);
		}
		home = spawn(PLAYERS[round], -64, AI_VS_AI ? null : player().getUUID());
		enemy = spawn(ENEMIES[round], 64, null);
		ownBase = aiBase = null;
		playerBuilt.clear(); enemyBuilt.clear(); enemyDefenders.clear(); playerArmy.clear(); enemyArmy.clear(); defendersResponded.clear(); health.clear();
		playerArmyKinds.clear(); enemyArmyKinds.clear();
		maxPlayerWorkers = maxEnemyWorkers = maxEnemyMains = maxClaims = maxResearch = firstEnemyArmy = firstEnemyAttack = 0;
		playerStructureDamage = enemyStructureDamage = 0;
		raided = false; decision = 0; seconds = 0;
		start = level.getGameTime(); active = true;
		LOG.info("[skirmish] match {}: {} {} vs AI {}; normal 50 minerals/4 workers, pressure drops disabled", round + 1, AI_VS_AI ? "AI" : "player", home.kind.race, enemy.kind.race);
	}

	private static StarUnit spawn(UnitKind kind, int x, UUID owner) {
		StarUnit unit = StarMine.type(kind).create(server.overworld());
		unit.moveTo(x + .5, y, .5, 0, 0);
		if (owner != null) unit.setOwner(owner);
		server.overworld().addFreshEntity(unit);
		return unit;
	}

	private static void tick() {
		ServerLevel level = server.overworld();
		long age = level.getGameTime() - start;
		if (age % 10 != 0) return;
		seconds = (int) age / 20;
		if (base(home) != null) ownBase = base(home);
		if (base(enemy) != null) aiBase = base(enemy);
		Hive mine = ownBase, ai = aiBase;
		if (mine == null || ai == null) { if (age > 200) throw new IllegalStateException("skirmish base failed to found"); return; }
		List<StarUnit> ours = units(mine), theirs = units(ai);
		observe(mine, ai, ours, theirs);
		if (!home.isAlive() || !enemy.isAlive() || age >= LIMIT) { finish(mine, ai, ours, theirs); return; }
		if (AI_VS_AI) {
			if (age % 200 == 0) {
				dev.starmine.ai.AiCommander.wave(home, level, mine, ai);
				dev.starmine.ai.AiCommander.wave(enemy, level, ai, mine);
			}
		} else if (age % 100 == 0) play(mine, ours, theirs);
		if (age % 1200 == 0) {
			Map<String, Object> row = status(mine, ai); rows.add(row); write();
			LOG.info("[skirmish] {}", row);
		}
	}

	private static void observe(Hive mine, Hive ai, List<StarUnit> ours, List<StarUnit> theirs) {
		maxPlayerWorkers = Math.max(maxPlayerWorkers, (int) ours.stream().filter(u -> u.kind.worker()).count());
		maxEnemyWorkers = Math.max(maxEnemyWorkers, (int) theirs.stream().filter(u -> u.kind.worker()).count());
		maxEnemyMains = Math.max(maxEnemyMains, (int) theirs.stream().filter(u -> u.kind.main() && !u.underConstruction()).count());
		maxClaims = Math.max(maxClaims, ai.territory.size());
		maxResearch = Math.max(maxResearch, ai.researched.size() + ai.levels.values().stream().mapToInt(Integer::intValue).sum() + ai.attackLevel + ai.armorLevel + ai.tier - 1);
		for (List<StarUnit> side : List.of(ours, theirs)) for (StarUnit u : side) {
			boolean own = side == ours;
			if (u.underConstruction()) (own ? playerBuilt : enemyBuilt).add(u.getUUID());
			if (soldier(u)) {
				(own ? playerArmy : enemyArmy).add(u.getUUID());
				(own ? playerArmyKinds : enemyArmyKinds).add(u.kind);
			}
			if (!own && !u.underConstruction() && u.kind.structure() && (u.kind.defender() || u.kind == BUNKER)) enemyDefenders.add(u.getUUID());
			Float previous = health.put(u.getUUID(), u.getHealth() + u.shield());
			if (previous != null && previous > u.getHealth() + u.shield() && u.kind.structure()) {
				if (own) playerStructureDamage += previous - u.getHealth() - u.shield(); else enemyStructureDamage += previous - u.getHealth() - u.shield();
			}
			if (!own && u.getTarget() instanceof StarUnit foe && mine.id.equals(foe.hiveId())
				&& u.distanceToSqr(enemy) < 40 * 40) defendersResponded.add(u.getUUID());
			if (!own && soldier(u) && u.distanceToSqr(home) < 32 * 32 && firstEnemyAttack == 0) firstEnemyAttack = seconds;
		}
		if (enemyArmy.size() >= 6 && firstEnemyArmy == 0) firstEnemyArmy = seconds;
	}

	private static void command(List<StarUnit> selected, String action, String arg, Vec3 at) {
		if (selected.isEmpty()) return;
		Commands.command(player(), new ColonyNetwork.UnitCommand(selected.stream().mapToInt(Entity::getId).toArray(), action, at.x, at.y, at.z, -1, arg));
	}

	private static void play(Hive mine, List<StarUnit> ours, List<StarUnit> theirs) {
		List<StarUnit> workers = ours.stream().filter(u -> u.kind.worker()).toList();
		List<StarUnit> army = ours.stream().filter(SkirmishHarness::soldier).toList();
		UnitKind worker = mine.race.rules().worker(), supply = mine.race.rules().supply();
		if (mine.count(worker) + mine.pending(worker) < 8) train(mine, ours, worker);
		boolean building = workers.stream().anyMatch(u -> u.buildKind() != null || u.constructing() != null);
		if (!building) {
			UnitKind next = null;
			if (mine.supplyCap(mine.race) - mine.supplyUsed(mine.race) < 3 && mine.pending(supply) == 0) {
				if (supply.building()) next = supply; else train(mine, ours, supply);
			}
			if (next == null) {
				Map<UnitKind, Integer> needed = new EnumMap<>(UnitKind.class);
				for (UnitKind kind : buildOrder(mine.race)) {
					if (mine.count(kind) + mine.pending(kind) >= needed.merge(kind, 1, Integer::sum)) continue;
					next = kind; break;
				}
			}
			if (next != null && mine.unlocked(next) && mine.canAfford(next, 1)) {
				BlockPos site = site(mine, next);
				if (site != null) command(workers, "build", next.name(), Vec3.atBottomCenterOf(site));
			}
		}
		UnitKind[] choices = switch (mine.race) {
			case TERRAN -> new UnitKind[] {MARINE, MARAUDER, MARINE, MEDIVAC, SIEGE_TANK};
			case ZERG -> new UnitKind[] {ZERGLING, ROACH, HYDRALISK, ROACH};
			case PROTOSS -> new UnitKind[] {ZEALOT, STALKER, IMMORTAL, STALKER};
		};
		for (int i = 0; i < choices.length; i++) train(mine, ours, choices[(decision + i) % choices.length]);
		if (mine.minerals >= 400 && mine.vespene >= 200) for (StarUnit buildingUnit : ours) {
			for (Research research : Research.of(buildingUnit.kind)) {
				if (research.refusal(mine, buildingUnit.kind) != null) continue;
				command(List.of(buildingUnit), "research", research.id(), buildingUnit.position()); break;
			}
		}
		decision++;
		boolean danger = theirs.stream().anyMatch(u -> soldier(u) && u.distanceToSqr(home) < 30 * 30);
		command(army.stream().filter(u -> (u.kind == MARINE || u.kind == MARAUDER) && u.getTarget() != null).toList(), "ability", "stimpack", home.position());
		if (danger) command(army, "attack_move", null, home.position());
		else if (seconds >= 12 * 60) command(army, "attack_move", null, enemy.position());
		else if (!raided && seconds >= 6 * 60 && army.size() >= 6) {
			command(army.subList(0, 2), "attack_move", null, enemy.position()); raided = true;
		}
	}

	private static void train(Hive mine, List<StarUnit> ours, UnitKind kind) {
		if (!mine.unlocked(kind) || !mine.canAfford(kind, kind == ZERGLING ? 2 : 1)) return;
		StarUnit producer = ours.stream().filter(u -> u.kind == kind.producer() && !u.underConstruction() && u.queue().isEmpty()).findFirst().orElse(null);
		if (producer != null) command(List.of(producer), "train", kind.name(), producer.position());
	}

	private static UnitKind[] buildOrder(UnitKind.Race race) {
		return switch (race) {
			case TERRAN -> new UnitKind[] {SUPPLY_DEPOT, BARRACKS, REFINERY, ENGINEERING_BAY, BARRACKS, FACTORY, STARPORT, ARMORY, BUNKER, MISSILE_TURRET};
			case ZERG -> new UnitKind[] {SPAWNING_POOL, EXTRACTOR, ROACH_WARREN, EVOLUTION_CHAMBER, SPINE_CRAWLER, SPORE_CRAWLER, HYDRALISK_DEN};
			case PROTOSS -> new UnitKind[] {PYLON, GATEWAY, ASSIMILATOR, CYBERNETICS_CORE, GATEWAY, FORGE, PHOTON_CANNON, ROBOTICS_FACILITY, STARGATE};
		};
	}

	private static BlockPos site(Hive mine, UnitKind kind) {
		ServerLevel level = server.overworld();
		if (kind.gas()) return dev.starmine.vespene.Vespene.outletsNear(level, mine.hatchery, 24, 16).stream()
			.map(BlockPos::above).filter(at -> Placement.siteFree(level, kind, at)).findFirst().orElse(null);
		for (int radius = 8; radius <= 34; radius += 3) for (int i = 0; i < 24; i++) {
			double angle = i * Math.PI / 12;
			BlockPos at = HiveLogic.groundNear(level, -64 + (int) Math.round(Math.cos(angle) * radius), y, (int) Math.round(Math.sin(angle) * radius));
			if (at != null && Placement.siteRefusal(level, kind, at, mine.id) == null) return Placement.site(level, kind, at);
		}
		return null;
	}

	private static Map<String, Object> status(Hive mine, Hive ai) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("match", round + 1); row.put("seconds", seconds); row.put("playerRace", mine.race); row.put("aiRace", ai.race);
		row.put("playerGasMined", mine.vespeneCollected); row.put("aiGasMined", ai.vespeneCollected);
		row.put("mode", AI_VS_AI ? "AI vs AI" : "player macro vs AI");
		row.put("playerMinerals", mine.minerals); row.put("playerGas", mine.vespene); row.put("playerMined", mine.mineralsCollected);
		row.put("aiMinerals", ai.minerals); row.put("aiGas", ai.vespene); row.put("aiMined", ai.mineralsCollected);
		row.put("playerMembers", mine.members.toString()); row.put("aiMembers", ai.members.toString());
		row.put("playerConstructions", playerBuilt.size()); row.put("aiConstructions", enemyBuilt.size());
		row.put("playerSoldiersProduced", playerArmy.size()); row.put("aiSoldiersProduced", enemyArmy.size());
		row.put("aiDefenceStructures", enemyDefenders.size()); row.put("aiDefendersResponded", defendersResponded.size());
		row.put("aiMaxMains", maxEnemyMains); row.put("aiClaims", maxClaims); row.put("aiResearch", maxResearch);
		row.put("aiFirstSixSoldiersSeconds", firstEnemyArmy); row.put("aiFirstAttackSeconds", firstEnemyAttack);
		row.put("playerStructureDamage", playerStructureDamage); row.put("aiStructureDamage", enemyStructureDamage);
		row.put("playerEconomy", economy(mine)); row.put("aiEconomy", economy(ai));
		row.put("playerArmyKinds", playerArmyKinds.toString()); row.put("aiArmyKinds", enemyArmyKinds.toString());
		return row;
	}

	/** Minute-by-minute evidence for both sides, independent of the asymmetric legacy success flags. */
	private static Map<String, Object> economy(Hive base) {
		List<StarUnit> members = units(base);
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("workerCapacity", dev.starmine.ai.AiCommander.workerCapacity(server.overworld(), base));
		row.put("tier", base.tier); row.put("researched", List.copyOf(base.researched));
		row.put("levels", new LinkedHashMap<>(base.levels)); row.put("weapons", base.attackLevel); row.put("armor", base.armorLevel);
		row.put("buildOrder", base.buildOrder); row.put("expansionSite", String.valueOf(base.aiExpansionSite));
		row.put("pending", base.pending.toString());
		row.put("buildings", members.stream().filter(u -> u.kind.structure()).map(u -> {
			Map<String, Object> b = new LinkedHashMap<>();
			b.put("kind", u.kind.name()); b.put("site", u.blockPosition().toShortString()); b.put("hp", u.getHealth());
			b.put("constructing", u.underConstruction()); b.put("mode", u.mode()); b.put("queue", u.queueIds()); return b;
		}).toList());
		row.put("workers", members.stream().filter(u -> u.kind.worker()).map(u -> {
			Map<String, Object> w = new LinkedHashMap<>();
			w.put("id", u.getUUID().toString()); w.put("site", u.blockPosition().toShortString()); w.put("work", u.goalsRunning());
			w.put("build", String.valueOf(u.buildKind())); w.put("constructing", String.valueOf(u.constructing())); w.put("cargo", u.cargoBlocks); return w;
		}).toList());
		return row;
	}

	private static void finish(Hive mine, Hive ai, List<StarUnit> ours, List<StarUnit> theirs) {
		Map<String, Object> row = status(mine, ai);
		row.put("winner", !home.isAlive() ? ai.race.name() : !enemy.isAlive() ? mine.race.name() : "time limit");
		row.put("endCondition", "starting main destroyed or " + LIMIT / 1200 + " game minutes");
		row.put("mineralsPerCore", java.util.Objects.requireNonNullElse((Object) Integer.getInteger("starmine.skirmish.mineralsPerCore"), "field 1300/800"));
		row.put("playerGasMined", mine.vespeneCollected); row.put("aiGasMined", ai.vespeneCollected);
		row.put("playerSurvivorCost", ours.stream().mapToInt(u -> u.kind.economy().minerals() + u.kind.economy().vespene()).sum());
		row.put("aiSurvivorCost", theirs.stream().mapToInt(u -> u.kind.economy().minerals() + u.kind.economy().vespene()).sum());
		row.put("playerPlayed", playerBuilt.size() > 0 && playerArmy.size() > 0 && mine.mineralsCollected > 0);
		row.put("aiDeveloped", enemyBuilt.size() > 1 && maxEnemyWorkers >= 4 && enemyArmy.size() >= 6 && ai.mineralsCollected > 0);
		row.put("aiBuiltDefences", !enemyDefenders.isEmpty()); row.put("aiDefended", !defendersResponded.isEmpty());
		row.put("aiAttacked", firstEnemyAttack > 0); row.put("aiExpandedEconomy", maxEnemyMains > 1);
		List<Map<String, Object>> next = new ArrayList<>(results); next.add(row); results = List.copyOf(next);
		rows.add(row); write(); LOG.info("[skirmish] FINAL {}", row);
		if (++round < LAST_ROUND) setup();
		else {
			active = false; done = true; server.tickRateManager().setTickRate(20); player().setGameMode(GameType.CREATIVE);
			for (int cx = -10; cx <= 10; cx++) for (int cz = -8; cz <= 8; cz++) server.overworld().setChunkForced(cx, cz, false);
		}
	}

	private static void write() {
		try { Files.createDirectories(output.getParent()); Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(rows)); }
		catch (java.io.IOException e) { throw new IllegalStateException("cannot write skirmish evidence", e); }
	}
}
