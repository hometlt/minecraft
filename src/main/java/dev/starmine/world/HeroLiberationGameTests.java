package dev.starmine.world;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.clear;
import static dev.starmine.ability.TestArena.point;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveLogic;
import dev.starmine.hive.HiveSavedData;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The heroes under Amun's hold (owner 17.10): beaten, a hero does not die but is freed — a tenth of its health
 * left — and goes over to the player who beat it with its base and army; its arena's floor cannot be broken, its
 * key part stands by it, and it does not leave the arena.
 */
public class HeroLiberationGameTests implements FabricGameTest {
	@GameTest(template = ARENA, batch = "heroFreed", timeoutTicks = 120)
	public void aBeatenHeroGoesOverWithItsBase(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		HiveSavedData data = HiveSavedData.get(level.getServer());
		VoidWorld.State state = VoidWorld.State.get(level.getServer());
		boolean wasFreed = state.freed.contains("TERRAN");
		BlockPos centre = BlockPos.containing(point(helper, 24, 24));
		Hive wild = new Hive(UUID.randomUUID(), level.dimension().location().toString(), centre);
		wild.race = UnitKind.Race.TERRAN;
		wild.members.put(UnitKind.COMMAND_CENTER, 1); // a base without one is cleared away every 10 s (BaseLogic.serverTick)
		data.add(wild);
		StarUnit raynor = HiveLogic.spawnNest(level, wild, UnitKind.RAYNOR, point(helper, 24, 24), centre);
		StarUnit marine = HiveLogic.spawnNest(level, wild, UnitKind.MARINE, point(helper, 30, 24), centre);
		raynor.setNoAi(true);
		marine.setNoAi(true);
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayerInLevel();
		Vec3 at = point(helper, 20, 20);
		player.teleportTo(at.x, at.y, at.z);
		helper.runAfterDelay(2, () -> raynor.hurt(level.damageSources().playerAttack(player), 100000));
		helper.runAfterDelay(60, () -> {
			boolean alive = raynor.isAlive(), freed = HeroLiberation.freed(raynor);
			float health = raynor.getHealth() / raynor.getMaxHealth();
			boolean ownerHero = raynor.owner().filter(player.getUUID()::equals).isPresent();
			boolean ownerBase = player.getUUID().equals(wild.owner);
			boolean ownerMarine = marine.owner().filter(player.getUUID()::equals).isPresent();
			if (!wasFreed) state.freed.remove("TERRAN");
			data.remove(wild.id);
			raynor.discard();
			marine.discard();
			level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
			helper.assertTrue(alive, "Raynor died");
			helper.assertTrue(freed, "Raynor is still Amun's");
			helper.assertTrue(health > 0.05f && health <= 0.11f, "Raynor kept " + health + " of his health");
			helper.assertTrue(ownerHero, "Raynor is not the player's");
			helper.assertTrue(ownerBase, "his base did not go over to the player");
			helper.assertTrue(ownerMarine, "his marine did not follow");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "heroMerge", timeoutTicks = 20)
	public void aBaseJoinsThePlayersOwn(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		HiveSavedData data = HiveSavedData.get(level.getServer());
		UUID player = UUID.randomUUID();
		String dim = level.dimension().location().toString();
		Hive mine = new Hive(UUID.randomUUID(), dim, BlockPos.ZERO);
		mine.owner = player;
		mine.minerals = 100;
		Hive wild = new Hive(UUID.randomUUID(), dim, BlockPos.ZERO.east(50));
		wild.minerals = 400;
		wild.members.put(UnitKind.MARINE, 5);
		data.add(mine);
		data.add(wild);
		HeroLiberation.convert(data, wild, player);
		boolean gone = data.get(wild.id) == null, linked = mine.id.equals(data.merged.get(wild.id));
		int minerals = mine.minerals, marines = mine.count(UnitKind.MARINE);
		data.remove(mine.id);
		data.merged.remove(wild.id);
		helper.assertTrue(gone && linked, "the base was not merged into the player's");
		helper.assertTrue(minerals == 500 && marines == 5, "merged: " + minerals + " minerals, " + marines + " marines");
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "heroArena", timeoutTicks = 40)
	public void theArenaHoldsTheHeroAndItsPart(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		VoidWorld.State state = VoidWorld.State.get(level.getServer());
		BlockPos oldPart = state.parts.remove("PROTOSS");
		BlockPos boss = BlockPos.containing(point(helper, 24, 24));
		HeroLiberation.prepare(level, boss, UnitKind.Race.PROTOSS);
		BlockPos part = state.parts.get("PROTOSS");
		boolean floor = level.getBlockState(boss.below()).is(HeroLiberation.FLOORS.get(UnitKind.Race.PROTOSS))
			&& level.getBlockState(boss.below()).getDestroySpeed(level, boss.below()) < 0;
		boolean placed = part != null && level.getBlockState(part).is(Artifact.PARTS.get(UnitKind.Race.PROTOSS)) && part.distSqr(boss) <= 36;
		StarUnit artanis = HiveLogic.spawnNest(level, null, UnitKind.ARTANIS, Vec3.atBottomCenterOf(boss), boss);
		artanis.setNoAi(true);
		helper.runAfterDelay(2, () -> artanis.teleportTo(boss.getX() + 14.5, boss.getY(), boss.getZ() + 0.5));
		helper.runAfterDelay(8, () -> {
			double off = Math.hypot(artanis.getX() - boss.getX() - 0.5, artanis.getZ() - boss.getZ() - 0.5);
			if (part != null) level.removeBlock(part, false);
			state.parts.remove("PROTOSS");
			if (oldPart != null) state.parts.put("PROTOSS", oldPart);
			artanis.discard();
			helper.assertTrue(floor, "the arena's floor can be broken");
			helper.assertTrue(placed, "no key part by the hero: " + part);
			helper.assertTrue(off <= HeroLiberation.STAY_RADIUS, "Artanis left his arena: " + off);
			helper.succeed();
		});
	}
}
