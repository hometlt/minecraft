package dev.starmine.entity;

import static dev.starmine.ability.TestArena.ARENA;

import dev.starmine.ability.TestArena;
import dev.starmine.hive.Hive;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Long mining (CLOUD_TASK_2 stage 3: «рабочие перестают добывать»): 8 workers of a base mine for 4
 * minutes from a stone hill beside it with ore veins in it, as ore lies in the world, and the minerals
 * must keep coming every minute while ore is left. Minute by minute the test logs what was delivered
 * and where the workers' time went (`[mining]` lines in the CI log).
 */
public class MiningGameTests implements FabricGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("StarMine/mining");
	static final int WORKERS = 8, MINUTE = 1200, MINUTES = 4;
	/** Each minute after the first brings at least this share of a typical (median) minute's minerals. */
	static final double STEADY = 0.4;

	/** The hill: stone from x 22 to the arena's far side, 8 blocks high, with veins of coal, copper and iron. */
	private static List<BlockPos> hill(GameTestHelper helper) {
		for (BlockPos rel : BlockPos.betweenClosed(22, 2, 0, 47, 9, 47))
			helper.getLevel().setBlock(helper.absolutePos(rel), Blocks.STONE.defaultBlockState(), 2);
		Random random = new Random(42);
		Block[] kinds = { Blocks.COAL_ORE, Blocks.COPPER_ORE, Blocks.IRON_ORE };
		List<BlockPos> ores = new ArrayList<>();
		for (int vein = 0; vein < 70; vein++) {
			Block kind = kinds[random.nextInt(kinds.length)];
			BlockPos at = new BlockPos(23 + random.nextInt(24), 2 + random.nextInt(8), random.nextInt(48));
			for (int i = 0, size = 4 + random.nextInt(5); i < size; i++) {
				if (at.getX() >= 22 && at.getX() <= 47 && at.getY() >= 2 && at.getY() <= 9 && at.getZ() >= 0 && at.getZ() <= 47) {
					helper.getLevel().setBlock(helper.absolutePos(at), kind.defaultBlockState(), 2);
					ores.add(at);
				}
				at = at.offset(random.nextInt(3) - 1, random.nextInt(3) - 1, random.nextInt(3) - 1);
			}
		}
		return ores;
	}

	private static void mining(GameTestHelper helper, UnitKind main, boolean player) {
		TestArena.clear(helper);
		List<BlockPos> ores = hill(helper);
		StarUnit building = TestArena.unit(helper, main, 10, 24, player ? UUID.randomUUID() : null);
		UnitKind worker = main.race.rules().worker();
		Map<String, Integer> time = new TreeMap<>();
		List<Integer> perMinute = new ArrayList<>();
		int[] last = { 0 };
		TestArena.Timeline timeline = TestArena.Timeline.of(helper);
		timeline.at(20, () -> { // after the base's first workers came out
			Hive base = TestArena.base(helper, building);
			helper.assertTrue(base != null, "no base");
			// all start from the same line by the hill: a main building is 15 blocks wide (owner 15.10), and a
			// hatchery's first drones came out beyond the arena's edge, where they stood idle all test
			List<StarUnit> own = helper.getLevel().getEntitiesOfClass(StarUnit.class, new AABB(building.blockPosition()).inflate(60),
				unit -> unit.kind == worker && unit.isAlive() && base.id.equals(unit.hiveId()));
			for (int i = 0; i < WORKERS; i++) {
				if (i >= own.size()) {
					TestArena.member(helper, base, worker, 18, 10 + i * 4);
					continue;
				}
				Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(18, 2, 10 + i * 4)));
				own.get(i).getNavigation().stop();
				own.get(i).teleportTo(at.x, at.y, at.z);
			}
		});
		timeline.every(() -> {
			for (StarUnit unit : helper.getLevel().getEntitiesOfClass(StarUnit.class, new AABB(building.blockPosition()).inflate(60),
					unit -> unit.kind == worker && unit.isAlive())) {
				String work = unit.goalsRunning();
				String state = work.contains("MINE") ? "mine" : work.contains("CARRY") ? "carry" : work.contains("SEEK") ? "seek"
					: work.contains("EXPLORE") ? "explore" : work.contains("TUNNEL") ? "tunnel" : work.contains("GAS") ? "gas"
					: work.contains("BUILD") ? "build" : work.contains("ENGINEER") ? "engineer" : work.equals("[]") ? "idle" : work;
				time.merge(state + (work.contains("plan") ? "+plan" : ""), 1, Integer::sum);
			}
		});
		for (int m = 1; m <= MINUTES; m++) {
			int minute = m;
			timeline.at((long) MINUTE * m, () -> {
				Hive base = TestArena.base(helper, building);
				int collected = base == null ? 0 : base.mineralsCollected;
				perMinute.add(collected - last[0]);
				last[0] = collected;
				int left = 0;
				for (BlockPos ore : ores) if (!helper.getLevel().getBlockState(helper.absolutePos(ore)).isAir()
					&& dev.starmine.hive.HiveResources.isOre(helper.getLevel().getBlockState(helper.absolutePos(ore)))) left++;
				LOG.info("[mining] {} minute {}: {} minerals, ore left {} of {}, claims {}, worker ticks {}", main.race, minute,
					perMinute.get(perMinute.size() - 1), left, ores.size(), DroneWork.claims(), time);
				time.clear();
				for (StarUnit unit : helper.getLevel().getEntitiesOfClass(StarUnit.class, new AABB(building.blockPosition()).inflate(60),
						unit -> unit.kind == worker && unit.isAlive()))
					LOG.info("[mining] {} minute {}: worker at {} carries {} — {}", main.race, minute, TestArena.where(helper, unit),
						unit.cargoBlocks, unit.goalsRunning());
				if (minute < MINUTES) return;
				int best = perMinute.stream().mapToInt(Integer::intValue).max().orElse(0);
				helper.assertTrue(best > 0, "nothing was mined in " + MINUTES + " minutes");
				// against a typical minute, not the best: loads of up to 8 blocks come in lumps, and one lucky minute
				// (86, 306, 90, 98) failed workers that never slowed down
				List<Integer> sorted = perMinute.stream().sorted().toList();
				double typical = (sorted.get((sorted.size() - 1) / 2) + sorted.get(sorted.size() / 2)) / 2.0;
				for (int i = 1; i < perMinute.size(); i++)
					helper.assertTrue(perMinute.get(i) >= typical * STEADY, main.race + " workers slowed down: minerals a minute " + perMinute
						+ ", ore left " + left + " of " + ores.size());
				helper.succeed();
			});
		}
	}

	/** A player's command center and 8 SCVs (they wait at the base, never roam). */
	@GameTest(template = ARENA, batch = "miningTerran", timeoutTicks = MINUTE * MINUTES + 200)
	public void scvsKeepMiningForMinutes(GameTestHelper helper) {
		mining(helper, UnitKind.COMMAND_CENTER, true);
	}

	/** A wild hatchery and 8 drones. */
	@GameTest(template = ARENA, batch = "miningZerg", timeoutTicks = MINUTE * MINUTES + 200)
	public void dronesKeepMiningForMinutes(GameTestHelper helper) {
		mining(helper, UnitKind.HATCHERY, false);
	}

	/** Owner 16.10: a wild worker with no ore in reach does not stand turning on the spot — it goes exploring (on a leash). */
	@GameTest(template = ARENA, batch = "idleWorker", timeoutTicks = 200)
	public void anIdleWorkerExplores(GameTestHelper helper) {
		TestArena.clear(helper);
		dev.starmine.hive.Hive base = new dev.starmine.hive.Hive(java.util.UUID.randomUUID(), helper.getLevel().dimension().location().toString(),
			helper.absolutePos(new BlockPos(10, 2, 10)));
		base.race = UnitKind.Race.TERRAN;
		dev.starmine.hive.HiveSavedData.get(helper.getLevel().getServer()).add(base);
		StarUnit scv = TestArena.member(helper, base, UnitKind.SCV, 24, 24);
		helper.succeedWhen(() -> {
			helper.assertTrue(scv.goalsRunning().contains("EXPLORE"), "the SCV does " + scv.goalsRunning());
			scv.discard();
			dev.starmine.hive.HiveSavedData.get(helper.getLevel().getServer()).remove(base.id);
		});
	}
}
