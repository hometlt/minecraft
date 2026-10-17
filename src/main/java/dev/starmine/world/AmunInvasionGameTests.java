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
}
