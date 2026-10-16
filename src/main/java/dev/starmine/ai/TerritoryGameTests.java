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
