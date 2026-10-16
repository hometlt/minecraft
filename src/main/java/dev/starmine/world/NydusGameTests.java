package dev.starmine.world;

import static dev.starmine.ability.TestArena.ARENA;

import dev.starmine.ability.TestArena;
import dev.starmine.block.StarMineBlocks;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Nydus corridors from zerg bases and the races kept apart (owner 16.10). */
public class NydusGameTests implements FabricGameTest {
	private static Hive wild(GameTestHelper helper, UnitKind.Race race, BlockPos at) {
		ServerLevel level = helper.getLevel();
		Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), at);
		base.race = race;
		HiveSavedData.get(level.getServer()).add(base);
		return base;
	}

	private static void undo(ServerLevel level, Map<BlockPos, BlockState> changed) {
		if (changed != null) changed.forEach((pos, state) -> level.setBlock(pos, state, 2));
	}

	private static void forget(ServerLevel level, Hive base) {
		for (int i = 0; i < base.nydus.size(); i++)
			level.getEntitiesOfClass(StarUnit.class, new AABB(base.nydus.get(i)).inflate(24), unit -> base.id.equals(unit.hiveId())).forEach(StarUnit::discard);
		HiveSavedData.get(level.getServer()).remove(base.id);
	}

	/** A wild zerg base with a network opens a long corridor: a worm far off, a tunnel of liquid creep under the land. */
	@GameTest(template = ARENA, batch = "nydusOpen", timeoutTicks = 300)
	public void aNydusCorridorStartsAtTheBase(GameTestHelper helper) {
		TestArena.clear(helper);
		ServerLevel level = helper.getLevel();
		Hive base = wild(helper, UnitKind.Race.ZERG, helper.absolutePos(new BlockPos(24, 2, 24)));
		base.minerals = 500;
		base.vespene = 500;
		StarUnit network = TestArena.member(helper, base, UnitKind.NYDUS_NETWORK, 12, 24);
		BlockPos site = Nydus.site(level, base, network);
		helper.assertTrue(site != null, "no place for the worm");
		Map<BlockPos, BlockState> changed = Nydus.open(level, base, network, site, RandomSource.create(5));
		try {
			helper.assertTrue(base.nydus.size() == 2, "the corridor was not opened");
			BlockPos canal = base.nydus.get(1);
			double length = Math.hypot(canal.getX() - network.getX(), canal.getZ() - network.getZ());
			helper.assertTrue(length >= Nydus.MIN_LENGTH - 4, "the worm came out only " + (int) length + " blocks from the base");
			long creep = changed.keySet().stream().filter(pos -> level.getBlockState(pos).is(StarMineBlocks.LIQUID_CREEP)).count();
			long flesh = changed.keySet().stream().filter(pos -> level.getBlockState(pos).is(StarMineBlocks.ZERG_FLESH)
				|| level.getBlockState(pos).is(StarMineBlocks.GLOWING_ZERG_FLESH)).count();
			helper.assertTrue(creep >= 4L * Nydus.MIN_LENGTH, "only " + creep + " cells of liquid creep in the tunnel");
			helper.assertTrue(flesh >= creep, "the tunnel is not lined with flesh: " + flesh);
		} catch (RuntimeException e) {
			undo(level, changed);
			network.discard();
			forget(level, base);
			throw e;
		}
		BlockPos canal = base.nydus.get(1);
		helper.succeedWhen(() -> { // the worm comes out once its place is loaded
			helper.assertTrue(!level.getEntitiesOfClass(StarUnit.class, new AABB(canal).inflate(3),
				unit -> unit.kind == UnitKind.NYDUS_CANAL && base.id.equals(unit.hiveId())).isEmpty(), "no nydus worm at the end");
			undo(level, changed);
			network.discard();
			forget(level, base);
		});
	}

	/** The base sends idle warriors through: they come out by the worm and stay there on guard. */
	@GameTest(template = ARENA, batch = "nydusTransfer", timeoutTicks = 600)
	public void warriorsGoThroughTheCorridor(GameTestHelper helper) {
		TestArena.clear(helper);
		ServerLevel level = helper.getLevel();
		Hive base = wild(helper, UnitKind.Race.ZERG, helper.absolutePos(new BlockPos(24, 2, 24)));
		StarUnit network = TestArena.member(helper, base, UnitKind.NYDUS_NETWORK, 10, 10);
		BlockPos site = Nydus.ground(level, network.getBlockX() + 80, network.getBlockZ(), network.getBlockY());
		helper.assertTrue(site != null, "no ground for the worm");
		Map<BlockPos, BlockState> changed = Nydus.open(level, base, network, site, RandomSource.create(3));
		List<StarUnit> lings = List.of(TestArena.member(helper, base, UnitKind.ZERGLING, 22, 22), TestArena.member(helper, base, UnitKind.ZERGLING, 24, 22),
			TestArena.member(helper, base, UnitKind.ROACH, 26, 24));
		int sent = Nydus.transfer(level, base, TestArena.point(helper, 24, 24), Nydus.GARRISON);
		helper.assertTrue(sent == lings.size(), sent + " sent through");
		BlockPos canal = base.nydus.get(1);
		helper.succeedWhen(() -> {
			for (StarUnit unit : lings) {
				helper.assertTrue(unit.isAlive(), unit.kind + " died");
				helper.assertTrue(unit.position().distanceToSqr(Vec3.atCenterOf(canal)) < 16 * 16, unit.kind + " is at " + TestArena.where(helper, unit));
				helper.assertTrue(unit.order() == StarUnit.Order.NONE, unit.kind + " still walks back");
			}
			undo(level, changed);
			network.discard();
			lings.forEach(StarUnit::discard);
			forget(level, base);
		});
	}

	/** A pack does not wake within reach of another race's wild; far from it, it does. */
	@GameTest(template = ARENA, batch = "separation", timeoutTicks = 100)
	public void racesDoNotAppearSideBySide(GameTestHelper helper) {
		TestArena.clear(helper);
		ServerLevel level = helper.getLevel();
		BlockPos heartAt = helper.absolutePos(new BlockPos(24, 1, 24));
		Hive terrans = wild(helper, UnitKind.Race.TERRAN, heartAt.offset(40, 0, 0));
		try {
			helper.assertTrue(RaceSeparation.foreignNear(level, UnitKind.Race.ZERG, heartAt) == UnitKind.Race.TERRAN, "the terran base is not seen");
			helper.assertTrue(RaceSeparation.foreignNear(level, UnitKind.Race.TERRAN, heartAt) == null, "a race keeps apart from itself");
			helper.assertTrue(wake(helper, heartAt).isEmpty(), "a zerg pack woke 40 blocks from a terran base");
			helper.assertTrue(level.getBlockState(heartAt).is(StarMineBlocks.ZERG_FLESH), "the heart did not turn into its block");
			terrans.hatchery = heartAt.offset(RaceSeparation.GAP + 40, 0, 0);
			List<StarUnit> made = wake(helper, heartAt);
			helper.assertTrue(made.size() == 2, "a zerg pack far from the terrans: " + made.size() + " units");
			made.forEach(StarUnit::discard);
			helper.succeed();
		} finally {
			HiveSavedData.get(level.getServer()).remove(terrans.id);
			level.setBlock(heartAt, Blocks.STONE.defaultBlockState(), 2);
		}
	}

	private static List<StarUnit> wake(GameTestHelper helper, BlockPos at) {
		ServerLevel level = helper.getLevel();
		level.setBlock(at, DungeonHeart.BLOCK.defaultBlockState(), 2);
		DungeonHeart.Heart heart = (DungeonHeart.Heart) level.getBlockEntity(at);
		heart.look(StarMineBlocks.ZERG_FLESH);
		heart.add(UnitKind.ZERGLING, at.offset(2, 1, 0), null, 0);
		heart.add(UnitKind.ZERGLING, at.offset(-2, 1, 0), null, 0);
		return heart.wake(level);
	}

	/** Owner 16.10: a camp stood empty — its guards wake for a creative player too (they do not attack them). */
	@GameTest(template = ARENA, batch = "campWakes", timeoutTicks = 200)
	public void aCampWakesForACreativePlayer(GameTestHelper helper) {
		TestArena.clear(helper);
		ServerLevel level = helper.getLevel();
		BlockPos ground = helper.absolutePos(new BlockPos(24, 2, 24));
		BlockPos heart = WildPacks.build(level, RandomSource.create(4), WildPacks.Kind.TERRAN, ground);
		helper.assertTrue(heart != null, "no camp");
		net.minecraft.server.level.ServerPlayer player = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayerInLevel();
		player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
		player.teleportTo(ground.getX() + 10.5, ground.getY(), ground.getZ() + 0.5);
		helper.succeedWhen(() -> {
			List<StarUnit> marines = level.getEntitiesOfClass(StarUnit.class, new AABB(ground).inflate(16), unit -> unit.kind == UnitKind.MARINE);
			helper.assertTrue(!marines.isEmpty(), "no marine came out");
			level.getEntitiesOfClass(StarUnit.class, new AABB(ground).inflate(16), unit -> true).forEach(StarUnit::discard);
			level.removePlayerImmediately(player, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
		});
	}
}
