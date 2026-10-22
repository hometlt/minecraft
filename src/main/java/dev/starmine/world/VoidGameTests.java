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
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The Void's foes (owner 17.10): Amun cannot be hurt while void shards stand; a rift's troops of every race are
 * Amun's side together and foes of a player's units; Amun's death closes his rifts. The islands' plan spreads
 * them out, with Amun's farthest.
 */
public class VoidGameTests implements FabricGameTest {
	private static Amun amun(GameTestHelper helper, double x, double z) {
		ServerLevel level = helper.getLevel();
		Amun amun = VoidForces.AMUN.create(level);
		Vec3 at = point(helper, x, z);
		amun.moveTo(at.x, at.y + 4, at.z, 0, 0);
		amun.setNoAi(true);
		level.addFreshEntity(amun);
		return amun;
	}

	@GameTest(template = ARENA, batch = "voidShield", timeoutTicks = 60)
	public void amunIsShieldedWhileShardsStand(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		VoidWorld.State state = VoidWorld.State.get(level.getServer());
		int before = state.shards;
		Amun amun = amun(helper, 24, 24);
		StarUnit marine = unit(helper, UnitKind.MARINE, 10, 24, UUID.randomUUID());
		state.shards = 2;
		helper.runAfterDelay(25, () -> { // Amun counts the shards once a second
			try {
				float health = amun.getHealth();
				amun.hurt(level.damageSources().mobAttack(marine), 50);
				helper.assertTrue(amun.getHealth() == health, "Amun was hurt through his shards");
				state.shards = 0;
			} catch (RuntimeException | Error failure) {
				state.shards = before;
				throw failure;
			}
		});
		helper.runAfterDelay(50, () -> {
			float health = amun.getHealth();
			amun.invulnerableTime = 0;
			amun.hurt(level.damageSources().mobAttack(marine), 50);
			state.shards = before;
			helper.assertTrue(amun.getHealth() < health, "Amun was not hurt with no shards left");
			amun.discard();
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "voidRift", timeoutTicks = 40)
	public void riftTroopsAreAmunsSide(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		VoidRift rift = VoidForces.RIFT.create(level);
		Vec3 at = point(helper, 24, 24);
		rift.moveTo(at.x, at.y, at.z, 0, 0);
		level.addFreshEntity(rift);
		StarUnit mine = unit(helper, UnitKind.MARINE, 6, 6, UUID.randomUUID());
		StarUnit wildZergling = unit(helper, UnitKind.ZERGLING, 40, 40, null);
		helper.runAfterDelay(2, () -> {
			rift.wave(level);
			List<StarUnit> troops = level.getEntitiesOfClass(StarUnit.class, new AABB(rift.blockPosition()).inflate(8),
				unit -> unit.getTags().contains(VoidForces.TAG));
			helper.assertTrue(troops.size() >= 3, "the rift brought " + troops.size() + " troops");
			helper.assertTrue(troops.stream().map(unit -> unit.kind.race).distinct().count() == 3, "the rift's troops are not of all three races");
			for (StarUnit a : troops)
				for (StarUnit b : troops) helper.assertTrue(Faction.allied(a, b), a.kind + " and " + b.kind + " of the rift fight each other");
			helper.assertTrue(Faction.allied(troops.get(0), rift), "the troops fight their rift");
			helper.assertTrue(Faction.hostile(mine, troops.get(0)), "a player's marine leaves the rift's troops alone");
			helper.assertTrue(Faction.hostile(troops.get(0), mine), "the rift's troops leave a player's marine alone");
			StarUnit zerg = troops.stream().filter(unit -> unit.kind.race == UnitKind.Race.ZERG).findFirst().orElseThrow();
			helper.assertFalse(Faction.allied(zerg, wildZergling), "a rift's zerg is allied with the wild zerg");
			helper.assertTrue(Faction.hostile(mine, rift), "a player's marine leaves the rift alone");
			troops.forEach(Entity::discard);
			rift.discard();
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "voidDeath", timeoutTicks = 40)
	public void amunsDeathClosesHisRifts(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		VoidWorld.State state = VoidWorld.State.get(level.getServer());
		int shards = state.shards;
		boolean dead = state.amunDead;
		state.shards = 0;
		Amun amun = amun(helper, 24, 24);
		VoidRift rift = VoidForces.RIFT.create(level);
		Vec3 at = point(helper, 10, 10);
		rift.moveTo(at.x, at.y, at.z, 0, 0);
		level.addFreshEntity(rift);
		helper.runAfterDelay(5, () -> {
			VoidRift friendly = VoidForces.RIFT.create(level);
			friendly.configure(UUID.randomUUID(), UUID.randomUUID(), net.minecraft.world.level.Level.NETHER, Vec3.ZERO, 3);
			friendly.moveTo(at.x + 8, at.y, at.z, 0, 0);
			level.addFreshEntity(friendly);
			amun.kill();
			boolean friendlyAlive = friendly.isAlive();
			friendly.discard();
			boolean closed = !rift.isAlive(), marked = state.amunDead;
			state.shards = shards;
			state.amunDead = dead;
			state.setDirty();
			level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, amun.getBoundingBox().inflate(8)).forEach(Entity::discard);
			helper.assertTrue(closed, "a rift outlived Amun");
			helper.assertTrue(friendlyAlive, "Amun's death destroyed a player's gate");
			helper.assertTrue(marked, "the Void does not know Amun is dead");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "voidLayout", timeoutTicks = 20)
	public void theIslandsSpreadOutWithAmunsFarthest(GameTestHelper helper) {
		List<VoidIslands.Island> islands = VoidIslands.layout(helper.getLevel().getSeed());
		helper.assertTrue(islands.size() == VoidIslands.ISLANDS + VoidIslands.RICH_ISLANDS.size() + 2, "islands: " + islands.size());
		helper.assertTrue(islands.get(0).centre().equals(VoidWorld.RETURN.below()), "the first island is not at the portal back");
		VoidIslands.Island amun = islands.get(islands.size() - 1);
		for (int i = 0; i < islands.size(); i++)
			for (int j = i + 1; j < islands.size(); j++) {
				VoidIslands.Island a = islands.get(i), b = islands.get(j);
				double gap = Math.sqrt(a.centre().distSqr(b.centre())) - a.radius() - b.radius();
				helper.assertTrue(gap > -12, "islands " + i + " and " + j + " overlap by " + -gap);
			}
		for (VoidIslands.Island island : islands)
			helper.assertTrue(island == amun || island.centre().distSqr(VoidWorld.RETURN) < amun.centre().distSqr(VoidWorld.RETURN),
				"an island lies beyond Amun's");
		for (VoidIslands.Riches riches : VoidIslands.Riches.values())
			if (riches != VoidIslands.Riches.NONE)
				helper.assertTrue(islands.stream().anyMatch(island -> island.riches() == riches), "no island of " + riches);
		helper.succeed();
	}
}
