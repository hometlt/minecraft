package dev.starmine.ai;

import static dev.starmine.ability.TestArena.ARENA;

import dev.starmine.ability.TestArena;
import dev.starmine.block.StarMineBlocks;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** The wild races take the land (owner 16.10): claims in each race's style with its guns, no monsters there; drops on a player's home. */
public class TerritoryGameTests implements FabricGameTest {

	@GameTest(template = ARENA, batch = "additionalGas", timeoutTicks = 30)
	public void aiAddsASecondGasBuildingWhenGasIsShort(GameTestHelper h) {
		TestArena.clear(h);
		var level = h.getLevel();
		Hive base = wild(h, UnitKind.Race.TERRAN, h.absolutePos(new BlockPos(24, 2, 36)));
		base.owner = UUID.randomUUID(); base.minerals = 100; base.vespene = 0;
		StarUnit main = TestArena.member(h, base, UnitKind.COMMAND_CENTER, 24, 36);
		base.join(UnitKind.COMMAND_CENTER); base.join(UnitKind.BARRACKS); base.join(UnitKind.REFINERY); base.join(UnitKind.SUPPLY_DEPOT);
		StarUnit barracks = TestArena.member(h, base, UnitKind.BARRACKS, 24, 12);
		barracks.setMode(dev.starmine.ability.Modes.TECHLAB);
		for (int i = 0; i < 12; i++) TestArena.member(h, base, UnitKind.SCV, 20 + i, 24);
		for (int i = 0; i < 6; i++) TestArena.member(h, base, UnitKind.MARINE, 10 + i, 38).setNoAi(true);
		BlockPos outlet = h.absolutePos(new BlockPos(38, 1, 24));
		level.setBlock(outlet, dev.starmine.vespene.Vespene.VESPENE.defaultBlockState().setValue(dev.starmine.vespene.VespeneBlock.OUTLET, true), 2);
		AiCommander.decide(main, level, base);
		h.assertTrue(level.getEntitiesOfClass(StarUnit.class, new AABB(base.hatchery).inflate(64),
			u -> base.id.equals(u.hiveId()) && u.buildKind() == UnitKind.REFINERY).size() == 1, "AI did not plan additional gas for twelve workers");
		h.succeed();
	}

	@GameTest(template = ARENA, batch = "aiLairResearch", timeoutTicks = 1250)
	public void aiSavesForLairAndCompletesItsResearch(GameTestHelper h) {
		TestArena.clear(h);
		var level = h.getLevel();
		Hive base = wild(h, UnitKind.Race.ZERG, h.absolutePos(new BlockPos(24, 2, 24)));
		base.owner = UUID.randomUUID(); base.minerals = 149; base.vespene = 100;
		StarUnit main = TestArena.member(h, base, UnitKind.HATCHERY, 24, 24);
		main.setNoAi(true); main.tickCount = 4 * 60 * 20;
		base.join(UnitKind.HATCHERY); base.join(UnitKind.SPAWNING_POOL); base.join(UnitKind.OVERLORD); base.join(UnitKind.OVERLORD);
		for (int i = 0; i < 6; i++) {
			TestArena.member(h, base, UnitKind.DRONE, 10 + i, 12).setNoAi(true);
			TestArena.member(h, base, UnitKind.ZERGLING, 10 + i, 38).setNoAi(true);
		}
		AiCommander.decide(main, level, base);
		h.assertTrue(base.minerals == 149, "AI spent its lair reserve on zerglings");
		base.minerals = 150;
		AiCommander.decide(main, level, base);
		h.assertTrue(base.minerals == 0 && base.vespene == 0, "AI did not fund lair research");
		h.runAfterDelay(1160, () -> { h.assertTrue(base.tier == 2, "AI lair research never completed"); h.succeed(); });
	}

	@GameTest(template = ARENA, batch = "developmentBudget", timeoutTicks = 400)
	public void establishedArmyLeavesIncomeForDevelopment(GameTestHelper helper) {
		TestArena.clear(helper);
		var level = helper.getLevel();
		Hive base = wild(helper, UnitKind.Race.TERRAN, helper.absolutePos(new BlockPos(24, 2, 24)));
		base.owner = UUID.randomUUID(); base.mineralsCollected = 5000; base.vespene = 1000;
		StarUnit main = TestArena.member(helper, base, UnitKind.COMMAND_CENTER, 24, 24);
		base.join(UnitKind.COMMAND_CENTER); base.join(UnitKind.REFINERY);
		for (UnitKind kind : base.race.rules().aiBuildOrder()) base.join(kind);
		StarUnit barracks = TestArena.member(helper, base, UnitKind.BARRACKS, 12, 24);
		for (int i = 0; i < 24; i++) TestArena.member(helper, base, UnitKind.SCV, 30 + i % 6 * 2, 8 + i / 6 * 3);
		for (int i = 0; i < 6; i++) TestArena.member(helper, base, UnitKind.MARINE, 34 + i, 30).setNoAi(true);
		while (base.supplyCap(base.race) - base.supplyUsed(base.race) < 10) base.join(UnitKind.SUPPLY_DEPOT);
		helper.runAfterDelay((300 - level.getGameTime() % 300) % 300 + 1, () -> {
			base.minerals = 50;
			AiCommander.decide(main, level, base);
			helper.assertTrue(barracks.queue().isEmpty() && (base.minerals == 50 || base.minerals == 0 && barracks.abilities.changingMode()), "army consumed the development reserve");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "armyBeforeTechnology", timeoutTicks = 20)
	public void firstArmyIsFundedBeforeMoreTechnology(GameTestHelper helper) {
		TestArena.clear(helper);
		var level = helper.getLevel();
		Hive base = wild(helper, UnitKind.Race.TERRAN, helper.absolutePos(new BlockPos(24, 2, 24)));
		base.owner = UUID.randomUUID(); base.minerals = 125; base.mineralsCollected = 1000;
		StarUnit main = TestArena.member(helper, base, UnitKind.COMMAND_CENTER, 24, 24);
		base.join(UnitKind.COMMAND_CENTER); base.join(UnitKind.SUPPLY_DEPOT); base.join(UnitKind.BARRACKS); base.join(UnitKind.REFINERY);
		StarUnit barracks = TestArena.member(helper, base, UnitKind.BARRACKS, 12, 24);
		for (int i = 0; i < 8; i++) TestArena.member(helper, base, UnitKind.SCV, 34 + i, 24);
		AiCommander.decide(main, level, base);
		helper.assertTrue(!barracks.queue().isEmpty() && base.minerals < 125, "technology consumed the first army's budget");
		helper.assertTrue(barracks.train(UnitKind.MARINE) && barracks.queue().size() == 2, "could not occupy the army queue");
		base.minerals = 125;
		AiCommander.decide(main, level, base);
		helper.assertTrue(base.minerals == 125, "busy barracks let technology consume the first army's savings");
		helper.assertTrue(level.getEntitiesOfClass(StarUnit.class, new AABB(base.hatchery).inflate(48),
			u -> base.id.equals(u.hiveId()) && u.buildKind() != null).isEmpty(), "AI ordered technology instead of its first army");
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "noExpansionResources", timeoutTicks = 20)
	public void missingExpansionResourcesDoesNotFreezeProduction(GameTestHelper helper) {
		TestArena.clear(helper);
		var level = helper.getLevel();
		Hive base = wild(helper, UnitKind.Race.TERRAN, helper.absolutePos(new BlockPos(24, 2, 24)));
		base.owner = UUID.randomUUID(); base.minerals = 200; base.mineralsCollected = 2000;
		StarUnit main = TestArena.member(helper, base, UnitKind.COMMAND_CENTER, 24, 24);
		base.join(UnitKind.COMMAND_CENTER); base.join(UnitKind.SUPPLY_DEPOT);
		main.tickCount = 6 * 60 * 20;
		for (int i = 0; i < 12; i++) TestArena.member(helper, base, UnitKind.SCV, 34 + i, 24);
		for (int i = 0; i < 6; i++) TestArena.member(helper, base, UnitKind.MARINE, 34 + i, 30).setNoAi(true);
		AiCommander.decide(main, level, base);
		helper.assertTrue(base.aiExpansionSite == null, "AI reserved resources for an expansion without minerals");
		helper.assertTrue(level.getEntitiesOfClass(StarUnit.class, new AABB(base.hatchery).inflate(48),
			u -> base.id.equals(u.hiveId()) && u.buildKind() != null).size() == 1, "missing expansion site froze ordinary development");
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "baseDungeonChunks", timeoutTicks = 40)
	public void generatedBaseDungeonLoadsItsWholeFootprint(GameTestHelper helper) {
		var level = helper.getLevel();
		BlockPos centre = helper.absolutePos(new BlockPos(5000, 8, 5000));
		dev.starmine.world.Dungeons.ancientTemples(new dev.starmine.world.Painter(level, RandomSource.create(4)), centre);
		for (int dx : new int[] {-41, 41}) for (int dz : new int[] {-41, 41})
			helper.assertTrue(level.hasChunk((centre.getX() + dx) >> 4, (centre.getZ() + dz) >> 4), "temple terrain checked an unloaded outer shrine");
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "unfundedSupply", timeoutTicks = 20)
	public void supplyWorkerIsNotSentWithoutMinerals(GameTestHelper helper) {
		TestArena.clear(helper);
		var level = helper.getLevel();
		Hive base = wild(helper, UnitKind.Race.TERRAN, helper.absolutePos(new BlockPos(24, 2, 24)));
		base.owner = UUID.randomUUID(); base.minerals = 0;
		StarUnit main = TestArena.member(helper, base, UnitKind.COMMAND_CENTER, 24, 24);
		base.join(UnitKind.COMMAND_CENTER);
		for (int i = 0; i < 4; i++) TestArena.member(helper, base, UnitKind.SCV, 34 + i, 24);
		while (base.supplyCap(base.race) - base.supplyUsed(base.race) >= 2) base.join(UnitKind.MARINE);
		AiCommander.decide(main, level, base);
		helper.assertTrue(level.getEntitiesOfClass(StarUnit.class, new AABB(base.hatchery).inflate(48),
			u -> base.id.equals(u.hiveId()) && u.kind.worker()).stream().allMatch(u -> u.buildKind() == null), "AI sent a worker to build unaffordable supply");
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "buildBudget", timeoutTicks = 20)
	public void buildingBudgetWaitsForWorkerInsteadOfBeingSpentOnUnits(GameTestHelper helper) {
		TestArena.clear(helper);
		var level = helper.getLevel();
		Hive base = wild(helper, UnitKind.Race.TERRAN, helper.absolutePos(new BlockPos(24, 2, 24)));
		base.owner = UUID.randomUUID(); base.minerals = 150; base.mineralsCollected = 2000;
		StarUnit main = TestArena.member(helper, base, UnitKind.COMMAND_CENTER, 24, 24);
		base.join(UnitKind.COMMAND_CENTER); base.join(UnitKind.SUPPLY_DEPOT);
		StarUnit builder = null;
		for (int i = 0; i < 4; i++) builder = TestArena.member(helper, base, UnitKind.SCV, 34 + i, 24);
		builder.orderBuild(UnitKind.BARRACKS, helper.absolutePos(new BlockPos(42, 2, 24)));
		AiCommander.decide(main, level, base);
		helper.assertTrue(base.minerals == 150 && main.queue().isEmpty(), "AI spent the travelling builder's budget on production");
		helper.assertTrue(level.getEntitiesOfClass(StarUnit.class, new AABB(base.hatchery).inflate(48),
			u -> base.id.equals(u.hiveId()) && u.buildKind() != null).size() == 1, "AI issued overlapping unpaid build orders");
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "explicitMatchOpponent", timeoutTicks = 20)
	public void matchOpponentAllowsMainAttackWithoutChangingWildPolicy(GameTestHelper helper) {
		TestArena.clear(helper);
		ServerLevel level = helper.getLevel();
		Hive terran = wild(helper, UnitKind.Race.TERRAN, helper.absolutePos(new BlockPos(8, 2, 24)));
		Hive zerg = wild(helper, UnitKind.Race.ZERG, helper.absolutePos(new BlockPos(40, 2, 24)));
		StarUnit main = TestArena.member(helper, terran, UnitKind.COMMAND_CENTER, 8, 24);
		TestArena.member(helper, zerg, UnitKind.HATCHERY, 40, 24).setNoAi(true);
		java.util.List<StarUnit> army = new java.util.ArrayList<>();
		for (int i = 0; i < terran.waveSize; i++) army.add(TestArena.member(helper, terran, UnitKind.MARINE, 18, 18 + i));
		helper.assertFalse(AiCommander.wave(main, level, terran), "ordinary wild AI attacked an unclaimed main");
		helper.assertTrue(AiCommander.wave(main, level, terran, zerg), "explicit match opponent did not receive a wave");
		helper.assertTrue(army.stream().allMatch(u -> u.order() == StarUnit.Order.ATTACK_MOVE), "match army did not get normal attack-move orders");
		helper.assertFalse(AiCommander.wave(main, level, terran, zerg), "explicit match bypassed wave cooldown");
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "economicExpansion", timeoutTicks = 2200)
	public void expansionIsBuiltAndPaidByAWorker(GameTestHelper helper) {
		expansion(helper, false);
	}

	@GameTest(template = ARENA, batch = "crystalExpansion", timeoutTicks = 2200)
	public void crystalExpansionIsBuiltAndPaidByAWorker(GameTestHelper helper) {
		expansion(helper, true);
	}

	private static void expansion(GameTestHelper helper, boolean crystal) {
		TestArena.clear(helper);
		ServerLevel level = helper.getLevel();
		java.util.List<net.minecraft.world.level.ChunkPos> forced = new java.util.ArrayList<>();
		BlockPos origin = helper.absolutePos(BlockPos.ZERO);
		for (int x = (origin.getX() - 48) >> 4; x <= (origin.getX() + 96) >> 4; x++)
			for (int z = (origin.getZ() - 48) >> 4; z <= (origin.getZ() + 96) >> 4; z++) {
				if (level.getForcedChunks().contains(net.minecraft.world.level.ChunkPos.asLong(x, z))) continue;
				level.setChunkForced(x, z, true); forced.add(new net.minecraft.world.level.ChunkPos(x, z));
			}
		Runnable release = () -> forced.forEach(p -> level.setChunkForced(p.x, p.z, false));
		helper.runAtTickTime(2199, release); // expansions leave the template's entity-ticking chunks
		for (BlockPos rel : BlockPos.betweenClosed(-48, -2, -48, 96, 18, 96))
			level.setBlock(helper.absolutePos(rel), rel.getY() <= 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
		Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), helper.absolutePos(new BlockPos(24, 2, 24)));
		base.race = UnitKind.Race.TERRAN; base.owner = UUID.randomUUID(); base.minerals = 2000;
		HiveSavedData.get(level.getServer()).add(base);
		StarUnit main = TestArena.member(helper, base, UnitKind.COMMAND_CENTER, 24, 24);
		base.join(UnitKind.COMMAND_CENTER); main.tickCount = 6 * 60 * 20;
		for (int i = 0; i < 12; i++) {
			TestArena.member(helper, base, UnitKind.SCV, 35 + i, 24);
			if (i < 6) TestArena.member(helper, base, UnitKind.MARINE, 35 + i, 30).setNoAi(true);
		}
		base.join(UnitKind.SUPPLY_DEPOT); base.mineralsCollected = 2000;
		BlockPos ore = helper.absolutePos(new BlockPos(24, 2, -18));
		if (crystal) helper.assertTrue(dev.starmine.mineral.Minerals.place(level, ore, (at, state) -> level.setBlock(at, state, 2)), "crystal did not fit");
		else level.setBlock(ore, Blocks.IRON_ORE.defaultBlockState(), 2);
		base.minerals = 200;
		AiCommander.decide(main, level, base);
		helper.assertTrue(base.aiExpansionSite != null && base.minerals == 200 && main.queue().isEmpty(), "AI spent its expansion savings");
		base.minerals = 2000;
		helper.assertTrue(AiCommander.orderExpansion(main, level, base), "AI did not order a resource expansion");
		helper.assertTrue(base.minerals == 2000, "resources charged before worker arrival");
		helper.succeedWhen(() -> {
			helper.assertTrue(base.count(UnitKind.COMMAND_CENTER) == 2, "worker did not finish the second main; "
				+ level.getEntitiesOfClass(StarUnit.class, new AABB(base.hatchery).inflate(96), u -> base.id.equals(u.hiveId()))
					.stream().map(u -> u.kind + " " + u.blockPosition() + " " + u.progress() + " " + u.goalsRunning()).toList());
			helper.assertTrue(base.minerals <= 1600 + base.mineralsCollected - 2000, "second main was free");
			helper.assertTrue(level.getEntitiesOfClass(StarUnit.class, new AABB(base.hatchery).inflate(96),
				u -> u.kind.main() && u.owner().filter(base.owner::equals).isPresent()).stream().allMatch(u -> base.id.equals(u.hiveId())),
				"expansion created a separate economy");
			while (main.queue().size() < 2) helper.assertTrue(main.train(UnitKind.SCV), "could not occupy the original worker queue");
			AiCommander.decide(main, level, base);
			helper.assertTrue(AiCommander.workerCapacity(level, base) <= base.count(UnitKind.SCV) + base.pending(UnitKind.SCV)
				&& level.getEntitiesOfClass(StarUnit.class, new AABB(base.hatchery).inflate(96),
					u -> u != main && u.kind.main() && base.id.equals(u.hiveId())).stream().allMatch(u -> u.queue().isEmpty()),
				"AI queued workers beyond the expansion's available resource jobs");
			release.run();
		});
	}
	private static Hive wild(GameTestHelper helper, UnitKind.Race race, BlockPos at) {
		ServerLevel level = helper.getLevel();
		Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), at);
		base.race = race;
		HiveSavedData.get(level.getServer()).add(base);
		return base;
	}

	@GameTest(template = ARENA, batch = "territory", timeoutTicks = 100)
	public void racesClaimInTheirStyle(GameTestHelper helper) {
		TestArena.clear(helper);
		ServerLevel level = helper.getLevel();
		HiveSavedData data = HiveSavedData.get(level.getServer());
		int i = 0;
		for (UnitKind.Race race : UnitKind.Race.values()) {
			BlockPos site = helper.absolutePos(new BlockPos(8 + i++ * 16, 2, 24));
			// a little cave-like room to style: stone walls round it
			for (BlockPos pos : BlockPos.betweenClosed(site.offset(-5, -1, -5), site.offset(5, 5, 5)))
				if (Math.abs(pos.getX() - site.getX()) == 5 || Math.abs(pos.getZ() - site.getZ()) == 5 || pos.getY() == site.getY() + 5)
					level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
			Hive base = wild(helper, race, site.offset(200, 0, 0));
			try {
				Territory.claim(level, base, site, true, RandomSource.create(7));
				helper.assertTrue(base.territory.contains(site), race + ": not claimed");
				helper.assertTrue(Territory.claimed(level, site.offset(10, 0, 0)), race + ": the claim is not territory");
				int guns = level.getEntitiesOfClass(StarUnit.class, new AABB(site).inflate(8),
					unit -> base.id.equals(unit.hiveId()) && unit.kind.structure()).size();
				helper.assertTrue(guns >= 1, race + ": no defences");
				int styled = 0;
				for (BlockPos pos : BlockPos.betweenClosed(site.offset(-5, -1, -5), site.offset(5, 5, 5))) {
					var state = level.getBlockState(pos);
					if (switch (race) {
						case ZERG -> state.is(StarMineBlocks.ZERG_FLESH) || state.is(StarMineBlocks.GLOWING_ZERG_FLESH) || state.is(StarMineBlocks.CREEP_TUMOR);
						case TERRAN -> state.is(Blocks.LANTERN) || state.is(Blocks.CHAIN) || state.is(Blocks.POLISHED_ANDESITE);
						case PROTOSS -> state.is(Blocks.SEA_LANTERN) || state.is(Blocks.YELLOW_TERRACOTTA) || state.is(Blocks.GOLD_BLOCK);
					}) styled++;
				}
				helper.assertTrue(styled >= 3, race + ": only " + styled + " blocks in its style");
				Zombie zombie = EntityType.ZOMBIE.create(level);
				zombie.moveTo(site.getX() + 0.5, site.getY(), site.getZ() + 0.5);
				helper.assertTrue(dev.starmine.world.SpawnGuard.refuses(level, zombie, site), race + ": a monster may spawn in its territory");
			} finally {
				level.getEntitiesOfClass(StarUnit.class, new AABB(site).inflate(12), unit -> base.id.equals(unit.hiveId())).forEach(StarUnit::discard);
				data.remove(base.id);
			}
		}
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "territoryDrop", timeoutTicks = 100)
	public void wildBaseDropsOnThePlayersHome(GameTestHelper helper) {
		TestArena.clear(helper);
		ServerLevel level = helper.getLevel();
		HiveSavedData data = HiveSavedData.get(level.getServer());
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayerInLevel();
		player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
		BlockPos home = helper.absolutePos(new BlockPos(24, 2, 24));
		player.teleportTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
		Hive mine = new Hive(UUID.randomUUID(), level.dimension().location().toString(), home);
		mine.owner = player.getUUID();
		mine.race = UnitKind.Race.TERRAN;
		data.add(mine);
		Hive wild = wild(helper, UnitKind.Race.PROTOSS, home.offset(2000, 0, 0));
		try {
			BlockPos landing = Territory.drops(level, data, wild, 2, player);
			helper.assertTrue(landing != null && landing.distSqr(home) < 40 * 40, "no drop by the home: " + landing);
			int dropped = level.getEntitiesOfClass(StarUnit.class, new AABB(home).inflate(48), unit -> wild.id.equals(unit.hiveId())).size();
			helper.assertTrue(dropped >= Territory.DROP_MIN, "only " + dropped + " dropped");
		} finally {
			level.getEntitiesOfClass(StarUnit.class, new AABB(home).inflate(60), unit -> wild.id.equals(unit.hiveId())).forEach(StarUnit::discard);
			data.remove(wild.id);
			data.remove(mine.id);
			level.removePlayerImmediately(player, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED); // other tests share the level
		}
		helper.succeed();
	}

	/** Owner 16.10: a worker in danger gets an escort, in the dark it lights the place its race's way. */
	@GameTest(template = ARENA, batch = "escorts", timeoutTicks = 100)
	public void workersInDangerAreEscortedAndLight(GameTestHelper helper) {
		TestArena.clear(helper);
		ServerLevel level = helper.getLevel();
		Hive base = wild(helper, UnitKind.Race.TERRAN, helper.absolutePos(new BlockPos(6, 2, 6)));
		try {
			StarUnit main = TestArena.member(helper, base, UnitKind.SUPPLY_DEPOT, 6, 6);
			StarUnit marine = TestArena.member(helper, base, UnitKind.MARINE, 10, 6);
			StarUnit scv = TestArena.member(helper, base, UnitKind.SCV, 40, 40);
			Zombie zombie = EntityType.ZOMBIE.create(level);
			zombie.moveTo(scv.getX() + 6, scv.getY(), scv.getZ());
			zombie.setNoAi(true);
			level.addFreshEntity(zombie);
			Escorts.decide(main, level, base);
			helper.assertTrue(marine.order() == StarUnit.Order.MOVE && marine.orderPos().distanceTo(scv.position()) < 2,
				"no escort for the threatened SCV: " + marine.order());
			zombie.discard();
			// a dark stone cell by the far wall: a lantern of the terrans, glowing flesh of the zerg, a sea lantern of the protoss
			BlockPos cell = helper.absolutePos(new BlockPos(40, 2, 10));
			for (BlockPos pos : BlockPos.betweenClosed(cell.offset(-3, -1, -3), cell.offset(3, 3, 3)))
				level.setBlock(pos, Math.abs(pos.getX() - cell.getX()) <= 2 && Math.abs(pos.getZ() - cell.getZ()) <= 2 && pos.getY() >= cell.getY()
					&& pos.getY() <= cell.getY() + 2 ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState(), 2);
			BlockPos lamp = Escorts.light(level, UnitKind.Race.TERRAN, cell);
			helper.assertTrue(lamp != null && level.getBlockState(lamp).is(Blocks.LANTERN), "no lantern in the dark");
			BlockPos flesh = Escorts.light(level, UnitKind.Race.ZERG, cell);
			helper.assertTrue(flesh != null && level.getBlockState(flesh).is(StarMineBlocks.GLOWING_ZERG_FLESH), "no glowing flesh in the dark");
			helper.succeed();
		} finally {
			level.getEntitiesOfClass(StarUnit.class, new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(64), unit -> base.id.equals(unit.hiveId())).forEach(StarUnit::discard);
			HiveSavedData.get(level.getServer()).remove(base.id);
		}
	}
}
