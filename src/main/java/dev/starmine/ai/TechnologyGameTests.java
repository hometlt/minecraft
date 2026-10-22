package dev.starmine.ai;

import static dev.starmine.ability.TestArena.ARENA;

import dev.starmine.ability.TestArena;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.AABB;

/** AI technology savings and paid construction. */
public class TechnologyGameTests implements FabricGameTest {
	@GameTest(template = ARENA, batch = "expiredUnpaidAiOrder", timeoutTicks = 40)
	public void anExpiredUnpaidBuildingCannotHoldTheEconomyForever(GameTestHelper h) {
		TestArena.clear(h); var level = h.getLevel();
		Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), h.absolutePos(new BlockPos(24, 2, 24)));
		base.race = UnitKind.Race.TERRAN; base.owner = UUID.randomUUID(); base.minerals = 500; base.vespene = 500;
		HiveSavedData.get(level.getServer()).add(base);
		StarUnit main = TestArena.member(h, base, UnitKind.COMMAND_CENTER, 24, 24);
		TestArena.member(h, base, UnitKind.BARRACKS, 10, 10);
		for (UnitKind kind : java.util.List.of(UnitKind.COMMAND_CENTER, UnitKind.SUPPLY_DEPOT, UnitKind.BARRACKS, UnitKind.ENGINEERING_BAY, UnitKind.BUNKER)) base.join(kind);
		StarUnit builder = null;
		for (int i = 0; i < 6; i++) builder = TestArena.member(h, base, UnitKind.SCV, 34 + i, 24);
		for (int i = 0; i < 2; i++) TestArena.member(h, base, UnitKind.MARINE, 35 + i, 30).setNoAi(true);
		BlockPos failed = h.absolutePos(new BlockPos(5, 2, 40)); builder.orderBuild(UnitKind.SENSOR_TOWER, failed);
		base.buildOrder = UnitKind.SENSOR_TOWER.name(); base.buildOrderUntil = level.getGameTime() - 1;
		AiCommander.decide(main, level, base);
		h.assertTrue(base.aiRejectedSites.containsKey(failed), "expired unpaid site was not rejected");
		h.assertTrue(builder.buildKind() != UnitKind.SENSOR_TOWER || !failed.equals(builder.buildSite()), "old unpaid order still blocks the economy");
		h.succeed();
	}
	@GameTest(template = ARENA, batch = "restoreMaturePrerequisite", timeoutTicks = 40)
	public void matureTerransRestoreMissingDepotWithPlentyOfSupply(GameTestHelper h) {
		TestArena.clear(h);
		var level = h.getLevel();
		Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), h.absolutePos(new BlockPos(24, 2, 24)));
		base.race = UnitKind.Race.TERRAN; base.owner = UUID.randomUUID(); base.minerals = 100;
		HiveSavedData.get(level.getServer()).add(base);
		StarUnit main = TestArena.member(h, base, UnitKind.COMMAND_CENTER, 24, 24); main.tickCount = 7200;
		for (int i = 0; i < 3; i++) base.join(UnitKind.COMMAND_CENTER);
		for (int i = 0; i < 6; i++) TestArena.member(h, base, UnitKind.SCV, 8 + i * 2, 32);
		h.assertTrue(base.supplyCap(base.race) - base.supplyUsed(base.race) >= 2, "fixture is supply blocked");
		AiCommander.decide(main, level, base);
		h.assertTrue(!level.getEntitiesOfClass(StarUnit.class, new AABB(base.hatchery).inflate(64),
			u -> base.id.equals(u.hiveId()) && u.buildKind() == UnitKind.SUPPLY_DEPOT).isEmpty(), "mature technology plan never restored the barracks prerequisite");
		h.succeed();
	}

	@GameTest(template = ARENA, batch = "rebuildProtossPower", timeoutTicks = 40)
	public void protossRebuildPowerEvenWhenTheirNexusesStillSupplyTheArmy(GameTestHelper h) {
		TestArena.clear(h);
		var level = h.getLevel();
		Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), h.absolutePos(new BlockPos(24, 2, 24)));
		base.race = UnitKind.Race.PROTOSS; base.owner = UUID.randomUUID(); base.minerals = 100;
		HiveSavedData.get(level.getServer()).add(base);
		StarUnit main = TestArena.member(h, base, UnitKind.NEXUS, 24, 24); main.tickCount = 6 * 60 * 20;
		base.join(UnitKind.NEXUS); base.join(UnitKind.NEXUS); base.join(UnitKind.FORGE);
		TestArena.member(h, base, UnitKind.FORGE, 24, 10);
		for (int i = 0; i < 4; i++) TestArena.member(h, base, UnitKind.PROBE, 8 + i * 2, 32);
		h.assertTrue(base.supplyCap(base.race) - base.supplyUsed(base.race) >= 2, "fixture is supply blocked");
		AiCommander.decide(main, level, base);
		h.assertTrue(!level.getEntitiesOfClass(StarUnit.class, new AABB(base.hatchery).inflate(64),
			u -> base.id.equals(u.hiveId()) && u.buildKind() == UnitKind.PYLON).isEmpty(), "lost power was not rebuilt with spare supply");
		h.succeed();
	}

	@GameTest(template = ARENA, batch = "researchDoesNotBlockDefence", timeoutTicks = 40)
	public void unaffordableResearchDoesNotBlockMineralOnlyInfrastructure(GameTestHelper h) {
		TestArena.clear(h);
		var level = h.getLevel();
		Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), h.absolutePos(new BlockPos(24, 2, 24)));
		base.race = UnitKind.Race.ZERG; base.owner = UUID.randomUUID(); base.minerals = 150;
		HiveSavedData.get(level.getServer()).add(base);
		StarUnit main = TestArena.member(h, base, UnitKind.HATCHERY, 24, 24); main.tickCount = 6 * 60 * 20;
		base.join(UnitKind.HATCHERY); base.join(UnitKind.HATCHERY);
		base.join(UnitKind.HATCHERY); // Isolate research priority from savings for another expansion.
		base.join(UnitKind.SPAWNING_POOL); base.join(UnitKind.EVOLUTION_CHAMBER); base.join(UnitKind.OVERLORD);
		for (int i = 0; i < 6; i++) {
			TestArena.member(h, base, UnitKind.DRONE, 8 + i * 2, 32);
			base.join(UnitKind.ZERGLING);
		}
		AiCommander.decide(main, level, base);
		h.assertTrue(!level.getEntitiesOfClass(StarUnit.class, new AABB(base.hatchery).inflate(64),
			u -> base.id.equals(u.hiveId()) && u.buildKind() == UnitKind.SPINE_CRAWLER).isEmpty(),
			"waiting for research gas blocked infrastructure: order=" + base.buildOrder + " expansion=" + base.aiExpansionSite + " bank=" + base.minerals + "/" + base.vespene);
		h.succeed();
	}

	@GameTest(template = ARENA, batch = "resumeDistantExpansion", timeoutTicks = 40)
	public void anUnfinishedExpansionGetsAReplacementScv(GameTestHelper h) {
		TestArena.clear(h);
		var level = h.getLevel();
		for (BlockPos rel : BlockPos.betweenClosed(64, 1, 8, 104, 1, 40))
			level.setBlock(h.absolutePos(rel), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
		Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), h.absolutePos(new BlockPos(24, 2, 24)));
		base.race = UnitKind.Race.TERRAN; base.owner = UUID.randomUUID(); HiveSavedData.get(level.getServer()).add(base);
		StarUnit main = TestArena.member(h, base, UnitKind.COMMAND_CENTER, 24, 24);
		StarUnit expansion = TestArena.member(h, base, UnitKind.COMMAND_CENTER, 88, 24); expansion.startConstruction(1200);
		StarUnit worker = TestArena.member(h, base, UnitKind.SCV, 80, 24);
		base.race.rules().tendConstruction(main, level, base);
		h.assertTrue(expansion.getUUID().equals(worker.constructing()), "distant expansion cannot replace its lost builder");
		h.succeed();
	}

	@GameTest(template = ARENA, batch = "aiTransportProduction", timeoutTicks = 40)
	public void establishedArmiesPayForTheirTransport(GameTestHelper h) {
		TestArena.clear(h);
		var level = h.getLevel();
		for (UnitKind.Race race : new UnitKind.Race[] {UnitKind.Race.TERRAN, UnitKind.Race.PROTOSS}) {
			Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), h.absolutePos(new BlockPos(24, 2, 24)));
			base.race = race; base.owner = UUID.randomUUID(); base.minerals = 1000; base.vespene = 1000;
			base.aiDevelopmentAfter = base.aiResearchAfter = level.getGameTime() + 1200;
			HiveSavedData.get(level.getServer()).add(base);
			UnitKind mainKind = race.rules().main(), transport = race == UnitKind.Race.TERRAN ? UnitKind.MEDIVAC : UnitKind.WARP_PRISM;
			StarUnit main = TestArena.member(h, base, mainKind, 24, 24);
			for (int i = 0; i < 3; i++) base.join(mainKind);
			base.join(UnitKind.BUNKER);
			for (int i = 0; i < 6; i++) base.join(race == UnitKind.Race.TERRAN ? UnitKind.MARINE : UnitKind.ZEALOT);
			base.join(transport.producer());
			StarUnit producer = TestArena.member(h, base, transport.producer(), 24, 10);
			if (race == UnitKind.Race.PROTOSS) {
				base.join(UnitKind.PYLON);
				TestArena.member(h, base, UnitKind.PYLON, 20, 10);
			}
			AiCommander.decide(main, level, base);
			h.assertTrue(producer.queue().contains(transport), "AI never queues " + transport);
			h.assertTrue(base.minerals == 1000 - transport.economy().minerals() && base.vespene == 1000 - transport.economy().vespene(), "transport was not paid normally");
		}
		h.succeed();
	}

	@GameTest(template = ARENA, batch = "technologyAtExpansion", timeoutTicks = 40)
	public void technologyUsesAnExpansionWhenTheOriginalMainIsBlocked(GameTestHelper h) {
		TestArena.clear(h);
		var level = h.getLevel();
		for (BlockPos rel : BlockPos.betweenClosed(56, 1, -8, 120, 1, 56)) level.setBlock(h.absolutePos(rel), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
		for (BlockPos rel : BlockPos.betweenClosed(-6, 2, -6, 54, 16, 54)) level.setBlock(h.absolutePos(rel), net.minecraft.world.level.block.Blocks.BEDROCK.defaultBlockState(), 2);
		Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), h.absolutePos(new BlockPos(24, 2, 24)));
		base.race = UnitKind.Race.TERRAN; base.owner = UUID.randomUUID(); base.minerals = 150; base.vespene = 100;
		HiveSavedData.get(level.getServer()).add(base);
		StarUnit main = TestArena.member(h, base, UnitKind.COMMAND_CENTER, 24, 24);
		StarUnit expansion = TestArena.member(h, base, UnitKind.COMMAND_CENTER, 88, 24);
		for (UnitKind kind : java.util.List.of(UnitKind.COMMAND_CENTER, UnitKind.COMMAND_CENTER, UnitKind.COMMAND_CENTER,
			UnitKind.SUPPLY_DEPOT, UnitKind.BARRACKS, UnitKind.ENGINEERING_BAY, UnitKind.BUNKER)) base.join(kind);
		StarUnit producer = TestArena.member(h, base, UnitKind.BARRACKS, 88, 10); producer.setMode(dev.starmine.ability.Modes.TECHLAB);
		for (int i = 0; i < 6; i++) {
			TestArena.member(h, base, UnitKind.SCV, 88 + i, 40);
			TestArena.member(h, base, UnitKind.MARINE, 88 + i, 44).setNoAi(true);
		}
		AiCommander.decide(main, level, base);
		h.assertTrue(level.getEntitiesOfClass(StarUnit.class, new AABB(base.hatchery).inflate(128),
			u -> base.id.equals(u.hiveId()) && u.buildKind() == UnitKind.FACTORY && u.buildSite().distSqr(expansion.blockPosition()) < 40 * 40).size() == 1,
			"technology could not use the clear expansion");
		h.succeed();
	}

	@GameTest(template = ARENA, batch = "earlyTerranBunker", timeoutTicks = 40)
	public void terransReserveTheirFirstBunkerBeforeAdvancedTechnology(GameTestHelper h) {
		TestArena.clear(h);
		var level = h.getLevel();
		Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), h.absolutePos(new BlockPos(24, 2, 24)));
		base.race = UnitKind.Race.TERRAN; base.owner = UUID.randomUUID(); base.minerals = 100;
		HiveSavedData.get(level.getServer()).add(base);
		StarUnit main = TestArena.member(h, base, UnitKind.COMMAND_CENTER, 24, 24);
		base.join(UnitKind.COMMAND_CENTER); base.join(UnitKind.BARRACKS); base.join(UnitKind.SUPPLY_DEPOT);
		for (int i = 0; i < 4; i++) TestArena.member(h, base, UnitKind.SCV, 36 + i * 2, 24);
		for (int i = 0; i < 2; i++) TestArena.member(h, base, UnitKind.MARINE, 24, 36 + i * 2);
		AiCommander.decide(main, level, base);
		h.assertTrue(level.getEntitiesOfClass(StarUnit.class, new AABB(base.hatchery).inflate(64),
			u -> base.id.equals(u.hiveId()) && u.buildKind() == UnitKind.BUNKER).size() == 1, "first bunker lost priority to infrastructure or troops");
		h.succeed();
	}

	@GameTest(template = ARENA, batch = "researchAndInfrastructure", timeoutTicks = 40)
	public void researchCooldownDoesNotPostponeTheNextTechnologyBuilding(GameTestHelper h) {
		TestArena.clear(h);
		var level = h.getLevel();
		Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), h.absolutePos(new BlockPos(24, 2, 24)));
		base.race = UnitKind.Race.TERRAN; base.owner = UUID.randomUUID(); base.minerals = 1000; base.vespene = 1000;
		HiveSavedData.get(level.getServer()).add(base);
		StarUnit main = TestArena.member(h, base, UnitKind.COMMAND_CENTER, 24, 24); main.tickCount = 4 * 60 * 20;
		for (int i = 0; i < 3; i++) base.join(UnitKind.COMMAND_CENTER);
		for (int i = 0; i < 10; i++) base.join(UnitKind.REFINERY);
		for (UnitKind kind : java.util.List.of(UnitKind.SUPPLY_DEPOT, UnitKind.BARRACKS, UnitKind.ENGINEERING_BAY, UnitKind.BUNKER)) base.join(kind);
		StarUnit producer = TestArena.member(h, base, UnitKind.BARRACKS, 24, 10); producer.setMode(dev.starmine.ability.Modes.TECHLAB);
		int workers = Math.max(6, AiCommander.workerCapacity(level, base));
		while (base.supplyCap(base.race) < workers + 20) base.join(UnitKind.SUPPLY_DEPOT);
		for (int i = 0; i < workers; i++) TestArena.member(h, base, UnitKind.SCV, 8 + i % 8, 24 + i / 8 * 3);
		for (int i = 0; i < 6; i++) TestArena.member(h, base, UnitKind.MARINE, 10 + i, 38).setNoAi(true);
		AiCommander.decide(main, level, base);
		h.assertTrue(base.aiResearchAfter > level.getGameTime(), "fixture did not start research");
		AiCommander.decide(main, level, base);
		h.assertTrue(!level.getEntitiesOfClass(StarUnit.class, new AABB(base.hatchery).inflate(64),
			u -> base.id.equals(u.hiveId()) && u.buildKind() == UnitKind.FACTORY).isEmpty(), "aligned cooldowns kept postponing the factory behind every research");
		h.succeed();
	}

	@GameTest(template = ARENA, batch = "technologyAfterArmyLoss", timeoutTicks = 30)
	public void matureBaseDevelopsEvenAfterItsEntireArmyWasLost(GameTestHelper h) {
		TestArena.clear(h);
		var level = h.getLevel();
		Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), h.absolutePos(new BlockPos(24, 2, 24)));
		base.race = UnitKind.Race.TERRAN; base.owner = UUID.randomUUID(); base.minerals = 150; base.vespene = 100;
		HiveSavedData.get(level.getServer()).add(base);
		StarUnit main = TestArena.member(h, base, UnitKind.COMMAND_CENTER, 24, 24); main.tickCount = 6 * 60 * 20;
		base.join(UnitKind.COMMAND_CENTER); base.join(UnitKind.COMMAND_CENTER); base.join(UnitKind.COMMAND_CENTER);
		base.join(UnitKind.SUPPLY_DEPOT); base.join(UnitKind.BARRACKS);
		StarUnit barracks = TestArena.member(h, base, UnitKind.BARRACKS, 24, 10);
		int workers = Math.max(4, AiCommander.workerCapacity(level, base));
		while (base.supplyCap(base.race) < workers + 12) base.join(UnitKind.SUPPLY_DEPOT);
		for (int i = 0; i < workers; i++) TestArena.member(h, base, UnitKind.SCV, 36 + i % 8, 24 + i / 8 * 2);
		AiCommander.decide(main, level, base);
		h.assertTrue(barracks.abilities.changingMode(), "late base kept replenishing basic marines forever instead of unlocking its tech lab");
		h.succeed();
	}

	@GameTest(template = ARENA, batch = "armyDuringTechnology", timeoutTicks = 160)
	public void paidTechnologyDoesNotBlockTheArmyUntilTheWholeTechTreeFinishes(GameTestHelper h) {
		TestArena.clear(h);
		var level = h.getLevel();
		Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), h.absolutePos(new BlockPos(24, 2, 24)));
		base.race = UnitKind.Race.TERRAN; base.owner = UUID.randomUUID(); base.minerals = 1000; base.vespene = 500;
		HiveSavedData.get(level.getServer()).add(base);
		StarUnit main = TestArena.member(h, base, UnitKind.COMMAND_CENTER, 24, 24);
		for (UnitKind kind : java.util.List.of(UnitKind.COMMAND_CENTER, UnitKind.SUPPLY_DEPOT, UnitKind.SUPPLY_DEPOT,
			UnitKind.BARRACKS, UnitKind.ENGINEERING_BAY, UnitKind.BUNKER)) base.join(kind);
		StarUnit producer = TestArena.member(h, base, UnitKind.BARRACKS, 24, 10); producer.setMode(dev.starmine.ability.Modes.TECHLAB);
		for (int i = 0; i < 6; i++) {
			TestArena.member(h, base, UnitKind.SCV, 8 + i, 24);
			TestArena.member(h, base, UnitKind.MARINE, 10 + i, 38).setNoAi(true);
		}
		AiCommander.decide(main, level, base);
		var builders = level.getEntitiesOfClass(StarUnit.class, new AABB(base.hatchery).inflate(64),
			u -> base.id.equals(u.hiveId()) && u.buildKind() == UnitKind.FACTORY);
		h.assertTrue(builders.size() == 1, "factory was not planned");
		StarUnit builder = builders.getFirst();
		h.assertTrue(base.race.rules().placeBuilding(builder, level, base, UnitKind.FACTORY, builder.buildSite()) != null, "factory could not be paid");
		base.minerals = 150;
		AiCommander.decide(main, level, base);
		// One decision in three deliberately saves for infrastructure. Allow the next normal decision too.
		if (!producer.queue().isEmpty()) h.succeed();
		else h.runAfterDelay(AiCommander.DECISION_TICKS, () -> {
			AiCommander.decide(main, level, base);
			h.assertTrue(!producer.queue().isEmpty(), "uncompleted technology stopped army production despite an available producer and budget");
			h.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "reachableAiGas", timeoutTicks = 30)
	public void additionalGasSkipsAnInaccessibleUndergroundOutlet(GameTestHelper h) {
		TestArena.clear(h);
		var level = h.getLevel();
		// Raise the arena so the underground target remains above the world's minimum build height.
		for (int x = 0; x <= 47; x++) for (int z = 0; z <= 47; z++)
			level.setBlock(h.absolutePos(new BlockPos(x, 21, z)), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
		Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), h.absolutePos(new BlockPos(24, 22, 24)));
		base.race = UnitKind.Race.TERRAN; base.owner = UUID.randomUUID(); base.minerals = 100;
		HiveSavedData.get(level.getServer()).add(base);
		StarUnit main = TestArena.member(h, base, UnitKind.COMMAND_CENTER, 24, 24);
		main.teleportTo(main.getX(), main.getY() + 20, main.getZ());
		base.join(UnitKind.COMMAND_CENTER); base.join(UnitKind.SUPPLY_DEPOT); base.join(UnitKind.SUPPLY_DEPOT);
		base.join(UnitKind.BARRACKS); base.join(UnitKind.REFINERY); base.join(UnitKind.REFINERY);
		StarUnit barracks = TestArena.member(h, base, UnitKind.BARRACKS, 24, 10); barracks.setMode(dev.starmine.ability.Modes.TECHLAB);
		barracks.teleportTo(barracks.getX(), barracks.getY() + 20, barracks.getZ());
		for (int i = 0; i < 12; i++) {
			StarUnit worker = TestArena.member(h, base, UnitKind.SCV, 36 + i % 6 * 2, 34 + i / 6 * 3);
			worker.teleportTo(worker.getX(), worker.getY() + 20, worker.getZ());
		}
		BlockPos buried = h.absolutePos(new BlockPos(24, 4, 24)), accessible = h.absolutePos(new BlockPos(44, 21, 24));
		for (BlockPos p : BlockPos.betweenClosed(buried.offset(-3, -3, -3), buried.offset(3, 3, 3)))
			level.setBlock(p, net.minecraft.world.level.block.Blocks.BEDROCK.defaultBlockState(), 2);
		for (BlockPos p : java.util.List.of(buried, accessible)) level.setBlock(p,
			dev.starmine.vespene.Vespene.VESPENE.defaultBlockState().setValue(dev.starmine.vespene.VespeneBlock.OUTLET, true), 2);
		h.assertTrue(dev.starmine.vespene.Vespene.isOutlet(level.getBlockState(buried)), "underground test outlet was outside world bounds");
		AiCommander.decide(main, level, base);
		var builders = level.getEntitiesOfClass(StarUnit.class, new AABB(base.hatchery).inflate(64), u -> base.id.equals(u.hiveId()) && u.buildKind() != null);
		h.assertTrue(builders.size() == 1 && accessible.above().equals(builders.getFirst().buildSite()), "AI selected an unreachable vent instead of the surface outlet");
		h.succeed();
	}

	@GameTest(template = ARENA, batch = "mineralPressureExpansion", timeoutTicks = 40)
	public void lowMineralsReserveAnExpansionWithOnlyTwoDefenders(GameTestHelper h) {
		TestArena.clear(h);
		var level = h.getLevel();
		BlockPos origin = h.absolutePos(BlockPos.ZERO);
		java.util.List<net.minecraft.world.level.ChunkPos> forced = new java.util.ArrayList<>();
		for (int x = (origin.getX() - 48) >> 4; x <= (origin.getX() + 96) >> 4; x++)
			for (int z = (origin.getZ() - 48) >> 4; z <= (origin.getZ() + 96) >> 4; z++) {
				if (level.getForcedChunks().contains(net.minecraft.world.level.ChunkPos.asLong(x, z))) continue;
				level.setChunkForced(x, z, true); forced.add(new net.minecraft.world.level.ChunkPos(x, z));
			}
		try {
			for (BlockPos p : BlockPos.betweenClosed(-48, 0, -48, 96, 12, 96))
				level.setBlock(h.absolutePos(p), p.getY() <= 1 ? net.minecraft.world.level.block.Blocks.STONE.defaultBlockState() : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
			Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), h.absolutePos(new BlockPos(24, 2, 24)));
			base.race = UnitKind.Race.TERRAN; base.owner = UUID.randomUUID(); base.minerals = 200;
			HiveSavedData.get(level.getServer()).add(base);
			StarUnit main = TestArena.member(h, base, UnitKind.COMMAND_CENTER, 24, 24); main.tickCount = 6 * 60 * 20;
			base.join(UnitKind.COMMAND_CENTER); base.join(UnitKind.SUPPLY_DEPOT);
			for (int i = 0; i < 6; i++) TestArena.member(h, base, UnitKind.SCV, 36 + i, 24);
			for (int i = 0; i < 2; i++) TestArena.member(h, base, UnitKind.MARINE, 35 + i, 30).setNoAi(true);
			for (BlockPos p : java.util.List.of(new BlockPos(24, 2, 43), new BlockPos(24, 2, -18)))
				h.assertTrue(dev.starmine.mineral.Minerals.place(level, h.absolutePos(p), (at, state) -> level.setBlock(at, state, 2)), "test crystal did not fit");
			AiCommander.decide(main, level, base);
			h.assertTrue(base.aiExpansionSite != null && base.minerals == 200 && main.queue().isEmpty(), "depleted field did not receive expansion savings");
			base.minerals = 400;
			h.assertTrue(AiCommander.orderExpansion(main, level, base), "AI refused an affordable expansion until six defenders returned");
			h.succeed();
		} finally {
			forced.forEach(p -> level.setChunkForced(p.x, p.z, false));
		}
	}

	@GameTest(template = ARENA, batch = "aiFactoryTechnology", timeoutTicks = 2200)
	public void aiSavesForFactoryAndAnScvCompletesIt(GameTestHelper h) {
		TestArena.clear(h);
		var level = h.getLevel();
		Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), h.absolutePos(new BlockPos(24, 2, 24)));
		base.race = UnitKind.Race.TERRAN; HiveSavedData.get(level.getServer()).add(base);
		base.owner = UUID.randomUUID(); base.minerals = 149; base.vespene = 100;
		StarUnit main = TestArena.member(h, base, UnitKind.COMMAND_CENTER, 24, 24);
		base.join(UnitKind.COMMAND_CENTER); base.join(UnitKind.SUPPLY_DEPOT); base.join(UnitKind.SUPPLY_DEPOT);
		base.join(UnitKind.BARRACKS); base.join(UnitKind.ENGINEERING_BAY); base.join(UnitKind.BUNKER);
		StarUnit producer = TestArena.member(h, base, UnitKind.BARRACKS, 24, 10); producer.setMode(dev.starmine.ability.Modes.TECHLAB);
		for (int i = 0; i < 6; i++) {
			TestArena.member(h, base, UnitKind.SCV, 8 + i, 24);
			TestArena.member(h, base, UnitKind.MARINE, 10 + i, 38).setNoAi(true);
		}
		AiCommander.decide(main, level, base);
		h.assertTrue(base.minerals == 149 && producer.queue().isEmpty(), "factory savings consumed by marines");
		base.minerals = 150;
		AiCommander.decide(main, level, base);
		h.assertTrue(level.getEntitiesOfClass(StarUnit.class, new AABB(base.hatchery).inflate(64),
			u -> u.buildKind() == UnitKind.FACTORY).size() == 1, "factory not ordered: " + dev.starmine.race.Placement.refusal(base, UnitKind.FACTORY)
			+ " members=" + base.members + " producer=" + producer.mode() + " changing=" + producer.abilities.changingMode());
		// The fixture owns its base to suppress ambient AI; reproduce its normal decision interval.
		h.onEachTick(() -> { if (main.tickCount % AiCommander.DECISION_TICKS == 0) AiCommander.decide(main, level, base); });
		h.succeedWhen(() -> {
			h.assertTrue(base.count(UnitKind.FACTORY) > 0, "SCV did not complete the AI factory: funds=" + base.minerals + "/" + base.vespene
				+ " order=" + base.buildOrder + " workers=" + level.getEntitiesOfClass(StarUnit.class, new AABB(base.hatchery).inflate(64),
					u -> u.kind == UnitKind.SCV && base.id.equals(u.hiveId())).stream().map(u -> TestArena.where(h, u) + ":" + u.buildKind() + ":" + u.constructing()).toList());
			h.assertTrue(base.vespene == 0, "factory's vespene was not paid");
		});
	}

}
