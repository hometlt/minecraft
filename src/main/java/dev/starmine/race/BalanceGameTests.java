package dev.starmine.race;

import dev.starmine.ability.TestArena;
import dev.starmine.ai.AiCommander;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.point;
import static dev.starmine.entity.UnitKind.*;

/**
 * The balance stand (docs/CLOUD_TASK.md, stage E; results in docs/BALANCE.md): two wild armies of equal
 * cost (SC2 minerals + vespene) of two races meet in a walled arena and fight to the end; every fight
 * runs with the sides swapped and with two seeds (formation jitter), at three tech levels. Each logs a
 * `[balance]` line: who won, what is left of the winner's army (its cost), how long it took. Both armies
 * are wild, so both use their abilities by the AI's rules ({@link dev.starmine.ability.AbilityAi}).
 *
 * The economy stand measures what 4 workers of each race mine from the same ore in 2 minutes, and the AI
 * stand how long a wild base of each race with the same resources takes to gather its first wave.
 * These tests measure; they fail only when a fight or a stand does not run. They take long (tens of
 * game minutes), so they run only with the environment variable `STARMINE_BALANCE=1` (CI runs them in
 * a job of their own); otherwise each passes at once.
 */
public class BalanceGameTests implements FabricGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("StarMine/balance");
	/** Ticks a fight may last before it is called a draw (the cost left decides). */
	private static final int FIGHT_TICKS = 2400;

	/** An army: kinds in the proportion they are bought. */
	private record Army(String name, UnitKind... kinds) {
	}

	private static final Army[][] TIERS = {
		{new Army("zerglings", ZERGLING), new Army("marines", MARINE), new Army("zealots", ZEALOT)},
		{new Army("roaches+hydralisks", ROACH, HYDRALISK), new Army("marines+marauders+medivac", MARINE, MARINE, MARAUDER, MARINE, MEDIVAC),
			new Army("zealots+stalkers+immortal+sentry", ZEALOT, STALKER, IMMORTAL, STALKER, SENTRY)},
		{new Army("ultralisks+hydralisks+corruptor", ULTRALISK, HYDRALISK, HYDRALISK, CORRUPTOR),
			new Army("tanks+marines+marauders+viking", SIEGE_TANK, MARINE, MARINE, MARAUDER, VIKING),
			new Army("colossi+immortals+zealots+stalkers", COLOSSUS, IMMORTAL, ZEALOT, STALKER, ZEALOT)}};
	private static final int[] BUDGET = {1000, 2400, 5000};

	/** SC2 cost of a kind: minerals + vespene (a zergling is half a pair). */
	public static int cost(UnitKind kind) {
		return kind.economy().minerals() + kind.economy().vespene();
	}

	/** The army bought for a budget: its kinds in turn while the money lasts. */
	static List<UnitKind> buy(Army army, int budget) {
		List<UnitKind> units = new ArrayList<>();
		int spent = 0;
		for (int i = 0; ; i++) {
			UnitKind next = army.kinds()[i % army.kinds().length];
			if (spent + cost(next) > budget) break;
			units.add(next);
			spent += cost(next);
		}
		return units;
	}

	/** The arena with walls to its top (a fight stays in it and sees nothing of its neighbours). */
	private static void walls(GameTestHelper helper) {
		walls(helper, true);
	}

	/** {@link #walls}; `forget` drops every base (not in a batch whose tests found bases side by side). */
	private static void walls(GameTestHelper helper, boolean forget) {
		TestArena.clear(helper, forget);
		for (BlockPos rel : BlockPos.betweenClosed(0, 2, 0, 47, 16, 47))
			if (rel.getX() == 0 || rel.getX() == 47 || rel.getZ() == 0 || rel.getZ() == 47)
				helper.getLevel().setBlock(helper.absolutePos(rel), Blocks.STONE.defaultBlockState(), 2);
	}

	private static List<StarUnit> deploy(GameTestHelper helper, List<UnitKind> kinds, double x, Random random) {
		List<StarUnit> units = new ArrayList<>();
		for (int i = 0; i < kinds.size(); i++) {
			// rows of 12 along the side's wall, the next row toward the middle (a row outward stood outside the arena's walls)
			double z = 6 + (i % 12) * 3 + random.nextDouble(), dx = (i / 12) * 3 * Math.signum(24 - x) + random.nextDouble();
			units.add(TestArena.unit(helper, kinds.get(i), x + dx, z, null));
		}
		return units;
	}

	private static int worth(List<StarUnit> units) {
		return units.stream().filter(StarUnit::isAlive).mapToInt(unit -> cost(unit.kind)).sum();
	}

	/** Whether the long stands are off (see the class comment): the test passes at once. */
	private static boolean off(GameTestHelper helper) {
		if ("1".equals(System.getenv("STARMINE_BALANCE"))) return false;
		helper.runAfterDelay(1, helper::succeed); // not at once: a test that ends before it has started never finishes
		return true;
	}

	/** A fight of tier `tier` between army `a` (west) and army `b` (east). */
	private static void fight(GameTestHelper helper, int tier, int a, int b, long seed) {
		if (off(helper)) return;
		walls(helper, false); // not every base: the fights of a batch start together, each with bases of its own
		Army west = TIERS[tier][a], east = TIERS[tier][b];
		Random random = new Random(seed);
		List<StarUnit> left = deploy(helper, buy(west, BUDGET[tier]), 5, random), right = deploy(helper, buy(east, BUDGET[tier]), 42, random);
		List<Hive> bases = List.of(armyBase(helper, left, tier), armyBase(helper, right, tier));
		int leftWorth = worth(left), rightWorth = worth(right);
		long[] start = {-1};
		boolean[] done = {false};
		java.util.Map<StarUnit, Float> life = new java.util.HashMap<>();
		java.util.Map<String, Double> dealt = new java.util.TreeMap<>();
		java.util.Set<StarUnit> sunk = new java.util.HashSet<>();
		helper.onEachTick(() -> {
			if (done[0]) return;
			long now = helper.getTick();
			tally(life, dealt, left);
			tally(life, dealt, right);
			sunk(helper, left, sunk);
			sunk(helper, right, sunk);
			if (start[0] < 0) {
				start[0] = now;
				for (StarUnit unit : left) unit.command(StarUnit.Order.ATTACK_MOVE, point(helper, 40, 24));
				for (StarUnit unit : right) unit.command(StarUnit.Order.ATTACK_MOVE, point(helper, 7, 24));
				return;
			}
			int l = worth(left), r = worth(right);
			long ticks = now - start[0];
			boolean over = l == 0 || r == 0 || ticks >= FIGHT_TICKS;
			if (ticks % 200 == 0 && !over) // the armies drift apart after a fight: send them at each other again
				for (List<StarUnit> side : List.of(left, right)) {
					List<StarUnit> foes = side == left ? right : left;
					StarUnit foe = foes.stream().filter(StarUnit::isAlive).findFirst().orElse(null);
					if (foe != null) for (StarUnit unit : side) if (unit.isAlive() && unit.getTarget() == null) unit.command(StarUnit.Order.ATTACK_MOVE, foe.position());
				}
			if (!over) return;
			String winner = l > r ? west.name() : r > l ? east.name() : "draw";
			double share = l > r ? l / (double) leftWorth : r > l ? r / (double) rightWorth : 0;
			LOG.info("[balance] tier {} | {} ({} {}) vs {} ({} {}) | seed {} | winner {} {} | left {}% of {} | {} ticks{}", tier + 1,
				west.name(), left.get(0).kind.race, leftWorth, east.name(), right.get(0).kind.race, rightWorth, seed,
				l > r ? left.get(0).kind.race : r > l ? right.get(0).kind.race : "-", winner, Math.round(share * 100), Math.max(l, r), ticks,
				ticks >= FIGHT_TICKS ? " (time)" : "");
			LOG.info("[balance] damage tier {} | {} vs {} | seed {} | {}", tier + 1, west.name(), east.name(), seed, dealt);
			if (ticks >= FIGHT_TICKS) // what kept the fight from ending: where the survivors are and what they do
				for (List<StarUnit> side : List.of(left, right))
					side.stream().filter(StarUnit::isAlive).limit(4).forEach(unit -> LOG.info("[balance] at the end {} at {} target {} order {} goals {}",
						unit.kind.id, TestArena.where(helper, unit), unit.getTarget() instanceof StarUnit foe ? foe.kind.id + " " + TestArena.where(helper, foe)
							: unit.getTarget(), unit.order(), unit.goalsRunning()));
			done[0] = true;
			for (Hive base : bases) HiveSavedData.get(helper.getLevel().getServer()).remove(base.id);
			helper.succeed();
		});
	}

	/** What tiers 2 and 3 have researched (SC2: an early army has none, later ones their unit upgrades). */
	private static final Research[] UPGRADES = {Research.STIMPACK, Research.CONCUSSIVE_SHELLS, Research.COMBAT_SHIELD, Research.CHARGE, Research.BLINK,
		Research.METABOLIC_BOOST};

	/**
	 * A wild base of the army's own (no main building), so that its abilities follow its research: none
	 * at tier 1, the unit upgrades at tiers 2 and 3. Removed after the fight.
	 */
	private static Hive armyBase(GameTestHelper helper, List<StarUnit> army, int tier) {
		Hive base = new Hive(UUID.randomUUID(), helper.getLevel().dimension().location().toString(), helper.absolutePos(new BlockPos(24, 2, 24)));
		base.race = army.get(0).kind.race;
		if (tier > 0) for (Research upgrade : UPGRADES) base.researched.add(upgrade.id());
		HiveSavedData.get(helper.getLevel().getServer()).add(base);
		for (StarUnit unit : army) unit.joinHive(base);
		return base;
	}

	/** Debug: a unit that got under the arena's floor (the fight cannot end), logged once with what it was doing. */
	private static void sunk(GameTestHelper helper, List<StarUnit> side, java.util.Set<StarUnit> logged) {
		double floor = helper.absolutePos(new BlockPos(0, 2, 0)).getY();
		for (StarUnit unit : side)
			if (unit.isAlive() && unit.getY() < floor - 0.3 && logged.add(unit)) {
				BlockPos feet = unit.blockPosition();
				LOG.info("[balance] under the floor {} at {} motion {} mode {} vehicle {} in wall {} blocks below/at/above {} {} {} tick {}", unit.kind.id,
					TestArena.where(helper, unit), unit.getDeltaMovement(), unit.mode(), unit.getVehicle(), unit.isInWall(),
					helper.getLevel().getBlockState(feet.below()), helper.getLevel().getBlockState(feet), helper.getLevel().getBlockState(feet.above()),
					unit.tickCount);
			}
	}

	/**
	 * Who did the damage: each tick the health and shields a unit lost go to the kind that hurt it last
	 * (heals are not counted). Shows a weapon that does nothing, or one doing far more than its cost.
	 */
	private static void tally(java.util.Map<StarUnit, Float> life, java.util.Map<String, Double> dealt, List<StarUnit> side) {
		for (StarUnit unit : side) {
			float now = unit.isAlive() ? unit.getHealth() + unit.shield() : 0;
			Float before = life.put(unit, now);
			if (before == null || before <= now) continue;
			String by = unit.getLastHurtByMob() instanceof StarUnit attacker ? attacker.kind.id : "other";
			dealt.merge(by, (double) Math.round((before - now) * 10) / 10.0, Double::sum);
		}
	}

	// tier 1: zerglings, marines, zealots — each pair with the sides swapped, two seeds
	@GameTest(template = ARENA, batch = "balance1zt", timeoutTicks = FIGHT_TICKS + 200) public void t1ZvT1(GameTestHelper h) { fight(h, 0, 0, 1, 1); }
	@GameTest(template = ARENA, batch = "balance1zt", timeoutTicks = FIGHT_TICKS + 200) public void t1TvZ1(GameTestHelper h) { fight(h, 0, 1, 0, 1); }
	@GameTest(template = ARENA, batch = "balance1zt", timeoutTicks = FIGHT_TICKS + 200) public void t1ZvT2(GameTestHelper h) { fight(h, 0, 0, 1, 2); }
	@GameTest(template = ARENA, batch = "balance1zt", timeoutTicks = FIGHT_TICKS + 200) public void t1TvZ2(GameTestHelper h) { fight(h, 0, 1, 0, 2); }
	@GameTest(template = ARENA, batch = "balance1zp", timeoutTicks = FIGHT_TICKS + 200) public void t1ZvP1(GameTestHelper h) { fight(h, 0, 0, 2, 1); }
	@GameTest(template = ARENA, batch = "balance1zp", timeoutTicks = FIGHT_TICKS + 200) public void t1PvZ1(GameTestHelper h) { fight(h, 0, 2, 0, 1); }
	@GameTest(template = ARENA, batch = "balance1zp", timeoutTicks = FIGHT_TICKS + 200) public void t1ZvP2(GameTestHelper h) { fight(h, 0, 0, 2, 2); }
	@GameTest(template = ARENA, batch = "balance1zp", timeoutTicks = FIGHT_TICKS + 200) public void t1PvZ2(GameTestHelper h) { fight(h, 0, 2, 0, 2); }
	@GameTest(template = ARENA, batch = "balance1tp", timeoutTicks = FIGHT_TICKS + 200) public void t1TvP1(GameTestHelper h) { fight(h, 0, 1, 2, 1); }
	@GameTest(template = ARENA, batch = "balance1tp", timeoutTicks = FIGHT_TICKS + 200) public void t1PvT1(GameTestHelper h) { fight(h, 0, 2, 1, 1); }
	@GameTest(template = ARENA, batch = "balance1tp", timeoutTicks = FIGHT_TICKS + 200) public void t1TvP2(GameTestHelper h) { fight(h, 0, 1, 2, 2); }
	@GameTest(template = ARENA, batch = "balance1tp", timeoutTicks = FIGHT_TICKS + 200) public void t1PvT2(GameTestHelper h) { fight(h, 0, 2, 1, 2); }
	// tier 2: roaches and hydralisks, bio with a medivac, stalkers and a sentry
	@GameTest(template = ARENA, batch = "balance2zt", timeoutTicks = FIGHT_TICKS + 200) public void t2ZvT1(GameTestHelper h) { fight(h, 1, 0, 1, 1); }
	@GameTest(template = ARENA, batch = "balance2zt", timeoutTicks = FIGHT_TICKS + 200) public void t2TvZ1(GameTestHelper h) { fight(h, 1, 1, 0, 1); }
	@GameTest(template = ARENA, batch = "balance2zt", timeoutTicks = FIGHT_TICKS + 200) public void t2ZvT2(GameTestHelper h) { fight(h, 1, 0, 1, 2); }
	@GameTest(template = ARENA, batch = "balance2zt", timeoutTicks = FIGHT_TICKS + 200) public void t2TvZ2(GameTestHelper h) { fight(h, 1, 1, 0, 2); }
	@GameTest(template = ARENA, batch = "balance2zp", timeoutTicks = FIGHT_TICKS + 200) public void t2ZvP1(GameTestHelper h) { fight(h, 1, 0, 2, 1); }
	@GameTest(template = ARENA, batch = "balance2zp", timeoutTicks = FIGHT_TICKS + 200) public void t2PvZ1(GameTestHelper h) { fight(h, 1, 2, 0, 1); }
	@GameTest(template = ARENA, batch = "balance2zp", timeoutTicks = FIGHT_TICKS + 200) public void t2ZvP2(GameTestHelper h) { fight(h, 1, 0, 2, 2); }
	@GameTest(template = ARENA, batch = "balance2zp", timeoutTicks = FIGHT_TICKS + 200) public void t2PvZ2(GameTestHelper h) { fight(h, 1, 2, 0, 2); }
	@GameTest(template = ARENA, batch = "balance2tp", timeoutTicks = FIGHT_TICKS + 200) public void t2TvP1(GameTestHelper h) { fight(h, 1, 1, 2, 1); }
	@GameTest(template = ARENA, batch = "balance2tp", timeoutTicks = FIGHT_TICKS + 200) public void t2PvT1(GameTestHelper h) { fight(h, 1, 2, 1, 1); }
	@GameTest(template = ARENA, batch = "balance2tp", timeoutTicks = FIGHT_TICKS + 200) public void t2TvP2(GameTestHelper h) { fight(h, 1, 1, 2, 2); }
	@GameTest(template = ARENA, batch = "balance2tp", timeoutTicks = FIGHT_TICKS + 200) public void t2PvT2(GameTestHelper h) { fight(h, 1, 2, 1, 2); }
	// tier 3: ultralisks, tanks, colossi with air support
	@GameTest(template = ARENA, batch = "balance3zt", timeoutTicks = FIGHT_TICKS + 200) public void t3ZvT1(GameTestHelper h) { fight(h, 2, 0, 1, 1); }
	@GameTest(template = ARENA, batch = "balance3zt", timeoutTicks = FIGHT_TICKS + 200) public void t3TvZ1(GameTestHelper h) { fight(h, 2, 1, 0, 1); }
	@GameTest(template = ARENA, batch = "balance3zt", timeoutTicks = FIGHT_TICKS + 200) public void t3ZvT2(GameTestHelper h) { fight(h, 2, 0, 1, 2); }
	@GameTest(template = ARENA, batch = "balance3zt", timeoutTicks = FIGHT_TICKS + 200) public void t3TvZ2(GameTestHelper h) { fight(h, 2, 1, 0, 2); }
	@GameTest(template = ARENA, batch = "balance3zp", timeoutTicks = FIGHT_TICKS + 200) public void t3ZvP1(GameTestHelper h) { fight(h, 2, 0, 2, 1); }
	@GameTest(template = ARENA, batch = "balance3zp", timeoutTicks = FIGHT_TICKS + 200) public void t3PvZ1(GameTestHelper h) { fight(h, 2, 2, 0, 1); }
	@GameTest(template = ARENA, batch = "balance3zp", timeoutTicks = FIGHT_TICKS + 200) public void t3ZvP2(GameTestHelper h) { fight(h, 2, 0, 2, 2); }
	@GameTest(template = ARENA, batch = "balance3zp", timeoutTicks = FIGHT_TICKS + 200) public void t3PvZ2(GameTestHelper h) { fight(h, 2, 2, 0, 2); }
	@GameTest(template = ARENA, batch = "balance3tp", timeoutTicks = FIGHT_TICKS + 200) public void t3TvP1(GameTestHelper h) { fight(h, 2, 1, 2, 1); }
	@GameTest(template = ARENA, batch = "balance3tp", timeoutTicks = FIGHT_TICKS + 200) public void t3PvT1(GameTestHelper h) { fight(h, 2, 2, 1, 1); }
	@GameTest(template = ARENA, batch = "balance3tp", timeoutTicks = FIGHT_TICKS + 200) public void t3TvP2(GameTestHelper h) { fight(h, 2, 1, 2, 2); }
	@GameTest(template = ARENA, batch = "balance3tp", timeoutTicks = FIGHT_TICKS + 200) public void t3PvT2(GameTestHelper h) { fight(h, 2, 2, 1, 2); }

	// ---- economy: 4 workers of each race on the same ore for 2 minutes ----

	private static void mining(GameTestHelper helper, UnitKind main) {
		if (off(helper)) return;
		walls(helper, false);
		// the same ore at the same distance from the main building's edge for every race (a hatchery is twice
		// as wide as a command center: ore at fixed places would lie nearer to it)
		int from = 20 + (int) Math.ceil(main.width / 2) + 5;
		for (int x = from; x <= from + 10; x++)
			for (int z = from; z <= from + 10; z++)
				for (int y = 2; y <= 4; y++) helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, y, z)), Blocks.IRON_ORE.defaultBlockState(), 2);
		StarUnit building = TestArena.unit(helper, main, 20, 20, UUID.randomUUID());
		int ticks = 2400;
		// where the workers' time goes (their work's state each tick; "breaking" while a block cracks)
		java.util.Map<String, Integer> time = new java.util.TreeMap<>();
		helper.onEachTick(() -> {
			for (StarUnit worker : helper.getLevel().getEntitiesOfClass(StarUnit.class, new AABB(building.blockPosition()).inflate(40),
					unit -> unit.kind == main.race.rules().worker() && unit.isAlive())) {
				String work = worker.goalsRunning();
				String state = work.contains("MINE") ? "mine" : work.contains("CARRY") ? "carry" : work.contains("SEEK") ? "seek"
					: work.contains("EXPLORE") ? "explore" : work.contains("TUNNEL") ? "tunnel" : work.contains("GAS") ? "gas" : work.equals("[]") ? "idle" : work;
				time.merge(state + (work.contains("plan") ? "+plan" : ""), 1, Integer::sum);
			}
		});
		helper.runAfterDelay(ticks, () -> {
			Hive base = TestArena.base(helper, building);
			helper.assertTrue(base != null, "no base");
			int mined = base.mineralsCollected, workers = base.count(main.race.rules().worker());
			int left = 0;
			for (BlockPos rel : BlockPos.betweenClosed(from, 2, from, from + 10, 4, from + 10))
				if (helper.getLevel().getBlockState(helper.absolutePos(rel)).is(Blocks.IRON_ORE)) left++;
			LOG.info("[balance] economy {}: ore left {} of 363; main at {}", main.race, left, TestArena.where(helper, building));
			LOG.info("[balance] economy {}: {} workers mined {} minerals in {} s ({} a minute)", main.race, workers, mined, ticks / 20,
				Math.round(mined * 60.0 / (ticks / 20.0)));
			LOG.info("[balance] economy {} worker ticks: {}; blocks delivered {}", main.race, time, base.collected);
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "balanceEconomy", timeoutTicks = 2500) public void miningZerg(GameTestHelper h) { mining(h, HATCHERY); }
	@GameTest(template = ARENA, batch = "balanceEconomy", timeoutTicks = 2500) public void miningTerran(GameTestHelper h) { mining(h, COMMAND_CENTER); }
	@GameTest(template = ARENA, batch = "balanceEconomy", timeoutTicks = 2500) public void miningProtoss(GameTestHelper h) { mining(h, NEXUS); }

	// ---- the AI: a wild base of each race with 1500 minerals and 600 vespene gathers its first wave ----

	private static void firstWave(GameTestHelper helper, UnitKind main) {
		if (off(helper)) return;
		walls(helper, false);
		StarUnit building = TestArena.unit(helper, main, 24, 24, null);
		long[] start = {-1};
		boolean[] done = {false};
		int limit = 9000;
		helper.onEachTick(() -> {
			if (done[0]) return;
			Hive base = TestArena.base(helper, building);
			if (base == null) return;
			long now = helper.getTick();
			if (start[0] < 0) {
				start[0] = now;
				base.minerals = 1500;
				base.vespene = 600;
				base.nextWave = Long.MAX_VALUE; // no waves at the neighbours: only how long the army takes
				return;
			}
			List<StarUnit> warriors = helper.getLevel().getEntitiesOfClass(StarUnit.class, new AABB(building.blockPosition()).inflate(48),
				mob -> base.id.equals(mob.hiveId()) && mob.isAlive() && !mob.kind.structure() && !mob.kind.worker() && mob.kind != LARVA
					&& mob.kind.attack != Attack.NONE);
			long ticks = now - start[0];
			if (ticks % 400 == 0) // how the base gets there: what it has, what is coming, what it holds
				LOG.info("[balance] ai {} at {} s: minerals {}, vespene {}, supply {}/{}, members {}, pending {}", main.race, ticks / 20, base.minerals,
					base.vespene, base.supplyUsed(base.race), base.supplyCap(base.race), base.members, base.pending);
			if (warriors.size() >= AiCommander.waveSize(base) || ticks >= limit) {
				LOG.info("[balance] ai {}: {} warriors after {} s{}; minerals left {}, vespene {}; buildings {}", main.race, warriors.size(), ticks / 20,
					ticks >= limit ? " (time)" : "", base.minerals, base.vespene, base.members);
				done[0] = true;
				helper.succeed();
			}
		});
	}

	@GameTest(template = ARENA, batch = "balanceAi", timeoutTicks = 9300) public void aiZerg(GameTestHelper h) { firstWave(h, HATCHERY); }
	@GameTest(template = ARENA, batch = "balanceAi", timeoutTicks = 9300) public void aiTerran(GameTestHelper h) { firstWave(h, COMMAND_CENTER); }
	@GameTest(template = ARENA, batch = "balanceAi", timeoutTicks = 9300) public void aiProtoss(GameTestHelper h) { firstWave(h, NEXUS); }
}
