package dev.starmine.world;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.clear;
import static dev.starmine.ability.TestArena.point;
import static dev.starmine.ability.TestArena.unit;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.faction.Faction;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Owner 17.10: Amun's invasion after a month — shards fall with their rifts, darken the land, leave void shards;
 * his troops fight only the players' side; his workers make the way; the void gate's scale of 16.
 */
public class AmunInvasionGameTests implements FabricGameTest {
	@GameTest(template = ARENA, batch = "amunClock", timeoutTicks = 60)
	public void amunInvadesAfterAMonth(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		VoidWorld.State state = VoidWorld.State.get(level.getServer());
		boolean was = state.invading, dead = state.amunDead;
		long time = level.getDayTime();
		state.amunDead = false;
		state.invading = false;
		level.setDayTime(31L * 24000);
		AmunInvasion.clockTested = true;
		helper.runAfterDelay(25, () -> {
			AmunInvasion.clockTested = false;
			boolean invading = state.invading;
			state.invading = was;
			state.amunDead = dead;
			level.setDayTime(time);
			helper.assertTrue(invading, "no invasion after 31 days");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "amunShard", timeoutTicks = 100)
	public void aShardFallsWithRiftsAndDarkens(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		BlockPos near = BlockPos.containing(point(helper, 24, 24));
		VoidShard shard = AmunInvasion.fall(level, near, 0); // here, in the arena's loaded chunks
		helper.assertTrue(shard != null, "no shard fell");
		AABB round = shard.getBoundingBox().inflate(16);
		List<VoidRift> rifts = level.getEntitiesOfClass(VoidRift.class, round);
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayerInLevel();
		player.teleportTo(shard.getX() + 3, shard.getY() - 4, shard.getZ());
		helper.runAfterDelay(45, () -> {
			boolean dark = player.hasEffect(MobEffects.DARKNESS);
			shard.hurt(level.damageSources().playerAttack(player), 10000);
			helper.runAfterDelay(5, () -> {
				int shards = level.getEntitiesOfClass(ItemEntity.class, round.inflate(4), item -> item.getItem().is(VoidGate.SHARD)).stream()
					.mapToInt(item -> item.getItem().getCount()).sum();
				rifts.forEach(Entity::discard);
				level.getEntitiesOfClass(ItemEntity.class, round.inflate(4)).forEach(Entity::discard);
				level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
				helper.assertTrue(rifts.size() == 2, "rifts round the shard: " + rifts.size());
				helper.assertTrue(dark, "no darkness round the shard");
				helper.assertTrue(shards >= 2, "the broken shard left " + shards + " void shards");
				helper.succeed();
			});
		});
	}

	@GameTest(template = ARENA, batch = "amunSides", timeoutTicks = 40)
	public void amunFightsOnlyThePlayersSide(GameTestHelper helper) {
		clear(helper);
		StarUnit cursed = unit(helper, UnitKind.ZEALOT, 10, 24, null);
		cursed.addTag(VoidForces.TAG);
		StarUnit wild = unit(helper, UnitKind.ZERGLING, 14, 24, null);
		StarUnit mine = unit(helper, UnitKind.MARINE, 18, 24, UUID.randomUUID());
		for (StarUnit unit : new StarUnit[] {cursed, wild, mine}) unit.setNoAi(true);
		helper.runAfterDelay(2, () -> {
			helper.assertFalse(Faction.hostile(cursed, wild), "Amun's troops fight the wild");
			helper.assertFalse(Faction.hostile(wild, cursed), "the wild fight Amun's troops");
			helper.assertTrue(Faction.hostile(cursed, mine), "Amun's troops leave the player's units be");
			helper.assertTrue(Faction.hostile(mine, cursed), "the player's units leave Amun's be");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "amunSapper", timeoutTicks = 300)
	public void amunsWorkerDigsToThePlayer(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		// a wall 3 thick across the arena between the worker and the player
		for (BlockPos rel : BlockPos.betweenClosed(20, 2, 0, 22, 8, 47)) level.setBlock(helper.absolutePos(rel), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		StarUnit worker = unit(helper, UnitKind.SCV, 12, 24, null);
		worker.addTag(VoidForces.TAG);
		worker.addTag(AmunInvasion.SAPPER);
		StarUnit marine = unit(helper, UnitKind.MARINE, 32, 24, UUID.randomUUID()); // a player's unit beyond the wall
		marine.setNoAi(true);
		marine.setInvulnerable(true);
		helper.runAfterDelay(250, () -> {
			boolean through = worker.getX() > helper.absolutePos(new BlockPos(22, 2, 24)).getX() - 1 || worker.distanceTo(marine) < 8;
			helper.assertTrue(through, "Amun's worker did not dig through the wall: " + dev.starmine.ability.TestArena.where(helper, worker));
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "amunGate", timeoutTicks = 20)
	public void theVoidIsSixteenTimesSmaller(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos out = VoidGate.target(level, new BlockPos(1600, 70, -320));
		helper.assertTrue(out.getX() == 100 && out.getZ() == -20, "the overworld's (1600, -320) leads to " + out);
		ServerLevel nether = level.getServer().getLevel(Level.NETHER);
		if (nether != null) {
			BlockPos fromNether = VoidGate.target(nether, new BlockPos(200, 70, 40));
			helper.assertTrue(fromNether.getX() == 100 && fromNether.getZ() == 20, "the Nether's (200, 40) leads to " + fromNether);
		}
		helper.succeed();
	}

	/** Owner 17.10: shards fall by night; the invasion grows with the nights — small troops seldom first, then more and stronger. */
	@GameTest(template = ARENA, batch = "amunGrows", timeoutTicks = 20)
	public void theInvasionGrowsWithTheNights(GameTestHelper helper) {
		helper.assertTrue(AmunInvasion.night(14000) && AmunInvasion.night(31L * 24000 + 20000), "no night at night");
		helper.assertFalse(AmunInvasion.night(6000) || AmunInvasion.night(31L * 24000 + 1000), "night by day");
		helper.assertTrue(AmunInvasion.every(0) > AmunInvasion.every(10), "waves no more often later");
		net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(7);
		java.util.Set<UnitKind> small = java.util.Set.of(UnitKind.ZERGLING, UnitKind.MARINE, UnitKind.ZEALOT);
		int first = 0, later = 0;
		boolean strong = false;
		for (int i = 0; i < 200; i++) {
			List<UnitKind> early = AmunInvasion.troops(0, random), late = AmunInvasion.troops(12, random);
			helper.assertTrue(small.containsAll(early), "big troops on the first night: " + early);
			helper.assertTrue(early.size() <= 2, "many troops on the first night: " + early);
			first += early.size();
			later += late.size();
			strong |= late.contains(UnitKind.ULTRALISK) || late.contains(UnitKind.THOR) || late.contains(UnitKind.ARCHON) || late.contains(UnitKind.VOID_RAY);
		}
		helper.assertTrue(later > first * 1.5, "no more troops later: " + first + " then " + later);
		helper.assertTrue(strong, "no strong troops later");
		helper.assertFalse(AmunInvasion.nearPlayer(helper.getLevel(), new Vec3(5_000_000, 70, 5_000_000)), "a player near the world's edge");
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "amunShardWaves", timeoutTicks = 120)
	public void aShardSendsTroopsAndShowsOnTheMap(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		VoidShard shard = AmunInvasion.fall(level, BlockPos.containing(point(helper, 24, 24)), 0);
		helper.assertTrue(shard != null, "no shard fell");
		AABB round = shard.getBoundingBox().inflate(24);
		level.getEntitiesOfClass(VoidRift.class, round).forEach(Entity::discard); // the shard's own troops only
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayerInLevel();
		player.teleportTo(shard.getX() + 40, shard.getY(), shard.getZ());
		shard.waveIn = 1;
		helper.runAfterDelay(45, () -> {
			List<StarUnit> troops = level.getEntitiesOfClass(StarUnit.class, round, unit -> unit.getTags().contains(VoidForces.TAG));
			VoidWorld.State state = VoidWorld.State.get(level.getServer());
			boolean was = state.invading;
			state.invading = true;
			boolean marked = AmunInvasion.marks(player).stream().anyMatch(mark -> mark.getKey() && mark.getValue().closerThan(shard.blockPosition(), 2));
			state.invading = was;
			troops.forEach(Entity::discard);
			shard.discard();
			level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
			helper.assertTrue(!troops.isEmpty(), "the shard sent no troops");
			helper.assertTrue(marked, "the shard is not on the map");
			helper.succeed();
		});
	}
}
