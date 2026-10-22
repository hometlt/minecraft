package dev.starmine.entity;

import dev.starmine.StarMine;
import dev.starmine.ability.TestArena;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import dev.starmine.mineral.Minerals;
import dev.starmine.vespene.Vespene;
import dev.starmine.vespene.VespeneBlock;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;

public class EconomyGameTests implements FabricGameTest {
	@GameTest(template = TestArena.ARENA, batch = "undergroundDelivery", timeoutTicks = 20)
	public void longUndergroundDeliveriesDoNotAimAtTheSurface(GameTestHelper h) {
		TestArena.clear(h);
		var level = h.getLevel();
		for (BlockPos rel : BlockPos.betweenClosed(0, 12, 0, 47, 12, 47)) level.setBlock(h.absolutePos(rel), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
		Vec3 from = TestArena.point(h, 2, 24), to = TestArena.point(h, 45, 24);
		level.setBlock(BlockPos.containing(from).above(10), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
		level.setBlock(BlockPos.containing(to).above(10), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
		h.runAfterDelay(2, () -> {
			h.assertTrue(level.getBlockState(BlockPos.containing(from).above(10)).is(net.minecraft.world.level.block.Blocks.STONE), "test roof missing");
			Vec3 leg = DroneWork.deliveryLeg(level, from, to);
			h.assertTrue(Math.abs(leg.y - from.y) < 0.01 && leg.x > from.x, "underground cargo was sent to the roof or surface: " + leg);
			h.succeed();
		});
	}

	@GameTest(template = TestArena.ARENA, batch = "workerAlarm", timeoutTicks = 20)
	public void alarmsNeverRecruitWorkersOfAnyRace(GameTestHelper h) {
		TestArena.clear(h);
		var level = h.getLevel();
		Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), h.absolutePos(new BlockPos(24, 2, 24)));
		base.owner = UUID.randomUUID(); HiveSavedData.get(level.getServer()).add(base);
		StarUnit guard = TestArena.member(h, base, UnitKind.MARINE, 20, 24);
		StarUnit foe = TestArena.unit(h, UnitKind.ZERGLING, 30, 24, UUID.randomUUID());
		var workers = new java.util.ArrayList<StarUnit>();
		for (UnitKind kind : new UnitKind[] {UnitKind.SCV, UnitKind.PROBE, UnitKind.DRONE}) workers.add(TestArena.member(h, base, kind, 22, 24));
		dev.starmine.hive.HiveLogic.alarm(level, base.id, guard.position(), foe);
		h.assertTrue(guard.getTarget() == foe && workers.stream().allMatch(w -> w.getTarget() == null), "alarm pulled a worker from the economy");
		h.succeed();
	}

	@GameTest(template = TestArena.ARENA, batch = "conePumpReach", timeoutTicks = 20)
	public void workersPumpFromTheFootOfAFourBlockCone(GameTestHelper h) {
		TestArena.clear(h);
		var level = h.getLevel();
		Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), h.absolutePos(new BlockPos(24, 2, 24)));
		base.race = UnitKind.Race.TERRAN; base.owner = UUID.randomUUID(); HiveSavedData.get(level.getServer()).add(base);
		TestArena.member(h, base, UnitKind.COMMAND_CENTER, 24, 24);
		BlockPos outlet = h.absolutePos(new BlockPos(36, 5, 24));
		level.setBlock(outlet, Vespene.VESPENE.defaultBlockState().setValue(VespeneBlock.OUTLET, true), 2);
		StarUnit worker = TestArena.member(h, base, UnitKind.SCV, 32, 24); worker.setNoAi(true);
		worker.commandWork(StarUnit.Work.GATHER, outlet);
		DroneWork work = new DroneWork(worker);
		for (int tick = 0; tick < 165; tick++) work.tick();
		h.assertTrue(worker.cargoVespene > 0 || base.vespene > 0, "pump requires climbing an unclimbable cone");
		h.succeed();
	}

	@GameTest(template = TestArena.ARENA, batch = "emptyEconomyRecovery", timeoutTicks = 30)
	public void emergencyWorkerNeedsNoMoneyUpFront(GameTestHelper h) {
		TestArena.clear(h);
		var level = h.getLevel();
		BlockPos core = h.absolutePos(new BlockPos(6, 2, 24));
		level.setBlock(core, Minerals.CRYSTAL.defaultBlockState().setValue(Minerals.CORE, true), 2);
		((Minerals.Core) level.getBlockEntity(core)).setAmount(500);
		for (UnitKind kind : new UnitKind[] {UnitKind.COMMAND_CENTER, UnitKind.NEXUS}) {
			Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), h.absolutePos(new BlockPos(24, 2, 24)));
			base.race = kind.race; base.owner = UUID.randomUUID(); base.minerals = 0;
			HiveSavedData.get(level.getServer()).add(base); base.join(kind);
			StarUnit main = TestArena.member(h, base, kind, 24, 24);
			dev.starmine.ai.AiCommander.decide(main, level, base);
			h.assertTrue(main.queue().contains(base.race.rules().worker()), "empty " + kind + " could not queue its free recovery worker");
			h.assertTrue(base.minerals == 0, "emergency worker created money or charged the bankrupt base");
		}
		h.succeed();
	}

	@GameTest(template = TestArena.ARENA, batch = "workerRecoveryAfterLosses", timeoutTicks = 30)
	public void establishedEconomyFillsJobsAfterItsArmyTakesLosses(GameTestHelper h) {
		TestArena.clear(h);
		var level = h.getLevel();
		for (int x = 6; x <= 18; x += 3) {
			BlockPos p = h.absolutePos(new BlockPos(x, 2, 12));
			level.setBlock(p, Minerals.CRYSTAL.defaultBlockState().setValue(Minerals.CORE, true), 2);
			((Minerals.Core) level.getBlockEntity(p)).setAmount(500);
		}
		for (boolean expanded : new boolean[] {false, true}) {
			Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), h.absolutePos(new BlockPos(24, 2, 24)));
			base.race = UnitKind.Race.TERRAN; base.owner = UUID.randomUUID(); base.minerals = 50;
			HiveSavedData.get(level.getServer()).add(base);
			StarUnit main = TestArena.member(h, base, UnitKind.COMMAND_CENTER, 24, 24);
			main.tickCount = expanded ? 0 : 6 * 60 * 20;
			base.join(UnitKind.COMMAND_CENTER); if (expanded) base.join(UnitKind.COMMAND_CENTER);
			base.join(UnitKind.SUPPLY_DEPOT);
			for (int i = 0; i < 8; i++) TestArena.member(h, base, UnitKind.SCV, 36 + i, 24);
			dev.starmine.ai.AiCommander.decide(main, level, base);
			h.assertTrue(main.queue().contains(UnitKind.SCV), "eight-worker opening cap blocked recovery of " + (expanded ? "expanded" : "mature") + " economy");
		}
		h.succeed();
	}

	@GameTest(template = TestArena.ARENA, batch = "mineralJobAfterCombat", timeoutTicks = 30)
	public void returningWorkerCannotResumeAnAlreadySaturatedCrystal(GameTestHelper h) {
		TestArena.clear(h);
		var level = h.getLevel();
		Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), h.absolutePos(new BlockPos(24, 2, 24)));
		base.race = UnitKind.Race.TERRAN; base.owner = UUID.randomUUID(); HiveSavedData.get(level.getServer()).add(base);
		TestArena.member(h, base, UnitKind.COMMAND_CENTER, 24, 24);
		BlockPos core = h.absolutePos(new BlockPos(6, 2, 24));
		level.setBlock(core, Minerals.CRYSTAL.defaultBlockState().setValue(Minerals.CORE, true), 2);
		((Minerals.Core) level.getBlockEntity(core)).setAmount(500);
		DroneWork[] jobs = new DroneWork[3];
		for (int i = 0; i < 3; i++) {
			StarUnit worker = TestArena.member(h, base, UnitKind.SCV, 20 + i * 2, 10); worker.setNoAi(true);
			jobs[i] = new DroneWork(worker); worker.commandWork(StarUnit.Work.GATHER, core);
			if (i < 2) jobs[i].tick();
			else {
				jobs[0].stop(); // A fight frees its slot, which the third worker takes.
				jobs[i].tick();
			}
		}
		jobs[0].tick();
		h.assertFalse(jobs[0].toString().contains(" ore " + core.toShortString()), "returning worker became a third miner on the old crystal");
		h.assertTrue(jobs[1].toString().contains(" ore " + core.toShortString()) && jobs[2].toString().contains(" ore " + core.toShortString()), "existing two jobs were displaced");
		h.succeed();
	}

	@GameTest(template = TestArena.ARENA, batch = "economicSave", timeoutTicks = 20)
	public void secondHatcheryKeepsItsSharedEconomyWhenLoaded(GameTestHelper h) {
		TestArena.clear(h);
		var level = h.getLevel();
		Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), h.absolutePos(new BlockPos(24, 2, 24)));
		base.owner = UUID.randomUUID(); HiveSavedData.get(level.getServer()).add(base);
		StarUnit second = TestArena.member(h, base, UnitKind.HATCHERY, 24, 24);
		var tag = new net.minecraft.nbt.CompoundTag(); second.addAdditionalSaveData(tag);
		StarUnit loaded = StarMine.type(UnitKind.HATCHERY).create(level); loaded.readAdditionalSaveData(tag);
		h.assertTrue(base.id.equals(loaded.hiveId()), "loaded expansion split off from the existing economy");
		h.succeed();
	}

	@GameTest(template = TestArena.ARENA, batch = "resourceSaturation", timeoutTicks = 40)
	public void twoJobsPerMineralAndFinishedGasBuilding(GameTestHelper h) {
		TestArena.clear(h);
		var level = h.getLevel();
		Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), h.absolutePos(new BlockPos(24, 2, 24)));
		base.race = UnitKind.Race.TERRAN; base.owner = UUID.randomUUID(); HiveSavedData.get(level.getServer()).add(base);
		TestArena.member(h, base, UnitKind.COMMAND_CENTER, 24, 24);
		TestArena.member(h, base, UnitKind.TERRAN_PLATFORM, 24, 36);
		for (int x : new int[] {6, 12}) {
			BlockPos at = h.absolutePos(new BlockPos(x, 2, 24));
			level.setBlock(at, Minerals.CRYSTAL.defaultBlockState().setValue(Minerals.CORE, true), 2);
			((Minerals.Core) level.getBlockEntity(at)).setAmount(500);
		}
		BlockPos outlet = h.absolutePos(new BlockPos(38, 1, 24));
		level.setBlock(outlet, Vespene.VESPENE.defaultBlockState().setValue(VespeneBlock.OUTLET, true), 2);
		StarUnit gas = TestArena.member(h, base, UnitKind.REFINERY, 38.5, 24.5);
		gas.setPos(38.5 + h.absolutePos(BlockPos.ZERO).getX(), outlet.getY() + 1, outlet.getZ() + .5);
		h.assertTrue(dev.starmine.ai.AiCommander.workerCapacity(level, base) == 6, "jobs are not 2*(2 distinct minerals+1 gas)");
		gas.startConstruction(100);
		h.assertTrue(dev.starmine.ai.AiCommander.workerCapacity(level, base) == 4, "unfinished refinery counted as gas jobs");
		java.util.Map<BlockPos, Integer> assigned = new java.util.HashMap<>();
		for (int i = 0; i < 5; i++) {
			StarUnit worker = TestArena.member(h, base, UnitKind.SCV, 24 + i, 10);
			BlockPos core = new DroneWork(worker).oreNear(level);
			if (core != null) assigned.merge(core, 1, Integer::sum);
		}
		// CRYSTALS reaches neighbouring test arenas; a fifth worker may legitimately find a farther node.
		for (int x : new int[] {6, 12}) h.assertTrue(assigned.getOrDefault(h.absolutePos(new BlockPos(x, 2, 24)), 0) == 2,
			"local crystal did not keep exactly two jobs: " + assigned);
		h.succeed();
	}

	@GameTest(template = TestArena.ARENA, batch = "workerCollision", timeoutTicks = 20)
	public void alliedWorkersPassWithoutShoving(GameTestHelper h) {
		TestArena.clear(h);
		UUID owner = UUID.randomUUID();
		StarUnit a = TestArena.unit(h, UnitKind.SCV, 20, 20, owner), b = TestArena.unit(h, UnitKind.SCV, 20.2, 20, owner);
		a.setDeltaMovement(Vec3.ZERO); b.setDeltaMovement(Vec3.ZERO);
		a.push(b); b.push(a);
		h.assertTrue(a.getDeltaMovement().lengthSqr() == 0 && b.getDeltaMovement().lengthSqr() == 0, "workers shoved one another");
		h.assertFalse(a.canCollideWith(b), "workers still block each other's body");
		h.assertTrue(a.isPickable(), "worker cannot be selected");
		h.succeed();
	}

	@GameTest(template = TestArena.ARENA, batch = "deathBroodlings", timeoutTicks = 180)
	public void destroyedZergBuildingLeavesThreeShortLivedBroodlings(GameTestHelper h) {
		TestArena.clear(h);
		var level = h.getLevel();
		StarUnit pool = TestArena.unit(h, UnitKind.SPAWNING_POOL, 24, 24, UUID.randomUUID());
		pool.kill();
		h.runAfterDelay(3, () -> h.assertTrue(level.getEntitiesOfClass(StarUnit.class, pool.getBoundingBox().inflate(16),
			u -> u.kind == UnitKind.BROODLING && u.isAlive()).size() == 3, "not exactly three broodlings"));
		h.runAfterDelay(145, () -> {
			h.assertTrue(level.getEntitiesOfClass(StarUnit.class, pool.getBoundingBox().inflate(40), u -> u.kind == UnitKind.BROODLING && u.isAlive()).isEmpty(), "broodlings outlived their lifetime");
			h.succeed();
		});
	}

	@GameTest(template = TestArena.ARENA, batch = "buildingGrid", timeoutTicks = 20)
	public void allBuildingsUseThreeMinecraftBlocksPerSc2Cell(GameTestHelper h) {
		for (UnitKind kind : UnitKind.values()) if (kind.structure() && kind != UnitKind.EGG) {
			h.assertTrue(kind.footprint() > 0 && kind.width == kind.footprint() * 3, kind + " uses another grid scale");
			Vec3 at = kind.gridCentre(new Vec3(17.7, 2, -13.4));
			h.assertTrue(Math.abs((at.x - kind.width / 2) % 3) < .001 && Math.abs((at.z - kind.width / 2) % 3) < .001, kind + " is off grid");
		}
		h.succeed();
	}
}
