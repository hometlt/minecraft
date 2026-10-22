package dev.starmine.ai;

import dev.starmine.ability.TestArena;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** A funded late economy isolates paid technology progression from combat and gas starvation. */
public class LateTechnologyGameTests implements FabricGameTest {
	@GameTest(template = TestArena.ARENA, batch = "paidLateTechnology", timeoutTicks = 120000)
	public void eachRaceBuildsItsTechnologyAndProducesACapitalUnit(GameTestHelper h) {
		var level = h.getLevel();
		level.getServer().tickRateManager().setTickRate(1000);
		for (BlockPos rel : BlockPos.betweenClosed(-64, 1, -32, 136, 1, 88))
			level.setBlock(h.absolutePos(rel), Blocks.STONE.defaultBlockState(), 2);
		for (int x = -64; x <= 136; x += 16) for (int z = -32; z <= 88; z += 16) {
			BlockPos p = h.absolutePos(new BlockPos(x, 2, z)); level.setChunkForced(p.getX() >> 4, p.getZ() >> 4, true);
		}
		UnitKind.Race[] races = {UnitKind.Race.TERRAN, UnitKind.Race.ZERG, UnitKind.Race.PROTOSS};
		UnitKind[] goals = {UnitKind.BATTLECRUISER, UnitKind.ULTRALISK, UnitKind.CARRIER};
		List<StarUnit> mains = new ArrayList<>();
		Hive[] base = {null}; int[] stage = {0}, ticks = {0};
		Runnable start = () -> {
			for (StarUnit u : level.getEntitiesOfClass(StarUnit.class, new AABB(
				net.minecraft.world.phys.Vec3.atLowerCornerOf(h.absolutePos(new BlockPos(-64, 0, -32))),
				net.minecraft.world.phys.Vec3.atLowerCornerOf(h.absolutePos(new BlockPos(136, 48, 88)))))) u.discard();
			for (BlockPos rel : BlockPos.betweenClosed(-64, 1, -32, 136, 23, 88)) {
				BlockPos at = h.absolutePos(rel); var state = rel.getY() == 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
				if (!level.getBlockState(at).equals(state)) level.setBlock(at, state, 2);
			}
			HiveSavedData.get(level.getServer()).clear(); mains.clear();
			Hive b = new Hive(UUID.randomUUID(), level.dimension().location().toString(), h.absolutePos(new BlockPos(24, 2, 24)));
			b.race = races[stage[0]]; b.owner = UUID.randomUUID(); b.minerals = 20000; b.vespene = 20000;
			HiveSavedData.get(level.getServer()).add(b); base[0] = b;
			for (int x : new int[] {24, -36, 84}) {
				b.join(b.race.rules().main());
				StarUnit main = TestArena.member(h, b, b.race.rules().main(), x, 24); main.tickCount = 7200; mains.add(main);
			}
			for (int i = 0; i < 6; i++) {
				StarUnit worker = TestArena.member(h, b, b.race.rules().worker(), 8 + i * 2, 40);
				worker.abilities.autoGather = false; // Funded construction fixture: no scans for absent ore.
			}
			ticks[0] = 0;
		};
		start.run();
		Runnable release = () -> {
			for (int x = -64; x <= 136; x += 16) for (int z = -32; z <= 88; z += 16) {
				BlockPos p = h.absolutePos(new BlockPos(x, 2, z)); level.setChunkForced(p.getX() >> 4, p.getZ() >> 4, false);
			}
			level.getServer().tickRateManager().setTickRate(20);
		};
		h.onEachTick(() -> {
			Hive b = base[0]; int time = ++ticks[0];
			// This funded fixture tests technology, not mining or army navigation.
			if (time % 100 == 0) for (StarUnit unit : level.getEntitiesOfClass(StarUnit.class, new AABB(b.hatchery).inflate(160),
				u -> b.id.equals(u.hiveId()) && !u.kind.structure() && !u.kind.worker())) unit.setNoAi(true);
			if (time % AiCommander.DECISION_TICKS == 0) AiCommander.decide(mains.getFirst(), level, b);
			if (time % 2400 == 0) org.slf4j.LoggerFactory.getLogger("StarMine/late-tech").info(
				"{} seconds={} tier={} bank={}/{} members={} pending={} order={}", b.race, time / 20, b.tier,
				b.minerals, b.vespene, b.members, b.pending, b.buildOrder);
			if (time % 2400 == 0) for (StarUnit worker : level.getEntitiesOfClass(StarUnit.class, new AABB(b.hatchery).inflate(160),
				u -> b.id.equals(u.hiveId()) && u.kind.worker() && u.buildKind() != null))
				org.slf4j.LoggerFactory.getLogger("StarMine/late-tech").info("builder {} at {}: {} -> {} ({}) passenger={} repair={}",
					worker.getUUID(), worker.blockPosition(), worker.buildKind(), worker.buildSite(), worker.goalsRunning(), worker.isPassenger(), worker.abilities.repairing());
			UnitKind goal = goals[stage[0]];
			if (b.count(goal) > 0 && !level.getEntitiesOfClass(StarUnit.class, new AABB(b.hatchery).inflate(128),
				u -> u.kind == goal && u.isAlive() && b.id.equals(u.hiveId())).isEmpty()) {
				h.assertTrue(20000 + b.mineralsCollected - b.minerals > 1000 && b.vespene < 20000, "late technology was not paid");
				org.slf4j.LoggerFactory.getLogger("StarMine/late-tech").info("PASS {} produced {} in {} seconds", b.race, goal, time / 20);
				if (++stage[0] == races.length) {
					release.run(); h.succeed();
				} else start.run();
			} else if (time >= 36000) {
				release.run();
				h.fail("late technology stalled: " + b.race + " tier=" + b.tier + " members=" + b.members
					+ " pending=" + b.pending + " bank=" + b.minerals + "/" + b.vespene + " order=" + b.buildOrder);
			}
		});
	}
}
