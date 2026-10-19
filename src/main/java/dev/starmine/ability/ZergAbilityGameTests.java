package dev.starmine.ability;

import dev.starmine.block.StarMineBlocks;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.faction.Faction;
import dev.starmine.hive.Hive;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.clear;
import static dev.starmine.ability.TestArena.point;
import static dev.starmine.ability.TestArena.unit;

/**
 * Zerg abilities (owner 14.10, docs/ABILITIES.md): each is cast and its effect checked in the world —
 * damage, roots, positions, blocks, states, larvae, energy. Burrowing and the lurker are in
 * {@link MechanicsGameTests}.
 */
public class ZergAbilityGameTests implements FabricGameTest {
	private static int count(GameTestHelper helper, UnitKind kind) {
		return helper.getLevel().getEntitiesOfClass(StarUnit.class, new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(80),
			unit -> unit.kind == kind && unit.isAlive()).size();
	}

	@GameTest(template = ARENA, batch = "zergBile", timeoutTicks = 200)
	public void bileLandsAfterItsDelay(GameTestHelper helper) {
		clear(helper);
		StarUnit ravager = unit(helper, UnitKind.RAVAGER, 10, 24, UUID.randomUUID());
		StarUnit marine = unit(helper, UnitKind.MARINE, 17, 24, null);
		ravager.setNoAi(true);
		marine.setNoAi(true);
		BlockPos glass = helper.absolutePos(new BlockPos(17, 2, 26));
		helper.getLevel().setBlock(glass, Blocks.GLASS.defaultBlockState(), 3);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> helper.assertTrue(Abilities.cast(ravager, level, Ability.CORROSIVE_BILE, null, point(helper, 17, 25)), "no bile"));
		helper.runAfterDelay(40, () -> helper.assertTrue(marine.getHealth() == marine.getMaxHealth(), "the bile landed before 2.5 s"));
		helper.runAfterDelay(60, () -> {
			helper.assertTrue(marine.getMaxHealth() - marine.getHealth() >= ZergAbilities.BILE_DAMAGE * UnitKind.SCALE - 0.01 || !marine.isAlive(),
				"the bile did " + (marine.getMaxHealth() - marine.getHealth()));
			helper.assertTrue(level.getBlockState(glass).isAir(), "the glass did not melt");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "zergFungal", timeoutTicks = 200)
	public void fungalRootsAndHurts(GameTestHelper helper) {
		clear(helper);
		StarUnit infestor = unit(helper, UnitKind.INFESTOR, 10, 24, UUID.randomUUID());
		infestor.setNoAi(true);
		StarUnit marine = unit(helper, UnitKind.MARINE, 18, 24, null), other = unit(helper, UnitKind.MARINE, 19, 25, null);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> {
			infestor.setEnergy(100);
			helper.assertTrue(Abilities.cast(infestor, level, Ability.FUNGAL_GROWTH, null, marine.position()), "no fungal");
			helper.assertTrue(infestor.energy() == 25, "fungal costs 75");
		});
		helper.runAfterDelay(20, () -> {
			for (StarUnit unit : List.of(marine, other)) {
				helper.assertTrue(Status.has(unit, Status.ROOTED) && unit.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED) == 0,
					"a marine is not rooted");
				helper.assertTrue(unit.getHealth() < unit.getMaxHealth(), "a marine took no damage");
			}
		});
		helper.runAfterDelay(80, () -> {
			helper.assertTrue(!Status.has(marine, Status.ROOTED), "the roots last more than 3 s");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "zergTransfusion", timeoutTicks = 200)
	public void queenTransfuses(GameTestHelper helper) {
		clear(helper);
		UUID player = UUID.randomUUID();
		StarUnit queen = unit(helper, UnitKind.QUEEN, 10, 24, player), roach = unit(helper, UnitKind.ROACH, 13, 24, player);
		queen.setNoAi(true);
		roach.setNoAi(true);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> {
			queen.setEnergy(50);
			roach.setHealth(5);
			helper.assertTrue(Abilities.cast(queen, level, Ability.TRANSFUSION, roach, null), "no transfusion");
			helper.assertTrue(Math.abs(roach.getHealth() - (5 + ZergAbilities.TRANSFUSION_NOW * UnitKind.SCALE)) < 0.6, "healed now to " + roach.getHealth());
		});
		helper.runAfterDelay(160, () -> {
			helper.assertTrue(roach.getHealth() >= 5 + (ZergAbilities.TRANSFUSION_NOW + ZergAbilities.TRANSFUSION_LATER) * UnitKind.SCALE - 0.6, "healed over time to " + roach.getHealth());
			helper.assertTrue(!Abilities.cast(queen, level, Ability.TRANSFUSION, roach, null), "a second transfusion without energy");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "zergInject", timeoutTicks = 900)
	public void injectGivesThreeMoreLarvae(GameTestHelper helper) {
		clear(helper);
		StarUnit hatchery = unit(helper, UnitKind.HATCHERY, 24, 24, UUID.randomUUID());
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(5, () -> {
			Hive base = TestArena.base(helper, hatchery);
			helper.assertTrue(base != null, "the hatchery founded no hive");
			StarUnit queen = TestArena.member(helper, base, UnitKind.QUEEN, 24, 31);
			queen.setEnergy(25);
			int larvae = count(helper, UnitKind.LARVA);
			helper.assertTrue(Abilities.order(level, List.of(queen), Ability.INJECT_LARVA, hatchery, null), "no inject order");
			helper.succeedWhen(() -> {
				helper.assertTrue(Status.has(hatchery, Status.INJECTED) || count(helper, UnitKind.LARVA) >= larvae + ZergAbilities.INJECT_LARVAE,
					"the queen did not inject");
				helper.assertTrue(count(helper, UnitKind.LARVA) >= larvae + ZergAbilities.INJECT_LARVAE, "larvae " + count(helper, UnitKind.LARVA) + " from " + larvae);
			});
		});
	}

	@GameTest(template = ARENA, batch = "zergTumor", timeoutTicks = 100)
	public void queenPlantsATumorOnCreep(GameTestHelper helper) {
		clear(helper);
		StarUnit queen = unit(helper, UnitKind.QUEEN, 10, 24, UUID.randomUUID());
		queen.setNoAi(true);
		BlockPos cell = helper.absolutePos(new BlockPos(13, 2, 24));
		helper.getLevel().setBlock(cell, StarMineBlocks.CREEP.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true), 3);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> {
			queen.setEnergy(25);
			helper.assertTrue(Abilities.cast(queen, level, Ability.CREEP_TUMOR, null, Vec3.atBottomCenterOf(cell)), "no tumor cast");
			helper.assertTrue(level.getBlockState(cell).is(StarMineBlocks.CREEP_TUMOR), "no tumor on the creep");
			helper.assertTrue(queen.energy() < 1, "the tumor cost nothing");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "zergChangeling", timeoutTicks = 100)
	public void changelingIsLeftAlone(GameTestHelper helper) {
		clear(helper);
		StarUnit overseer = unit(helper, UnitKind.OVERSEER, 10, 24, UUID.randomUUID());
		overseer.setNoAi(true);
		StarUnit marine = unit(helper, UnitKind.MARINE, 16, 24, null);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> {
			helper.assertTrue(Abilities.cast(overseer, level, Ability.SPAWN_CHANGELING, null, null), "no changeling");
			helper.assertTrue(count(helper, UnitKind.CHANGELING) == 1, "no changeling out");
		});
		helper.runAfterDelay(60, () -> {
			StarUnit changeling = helper.getLevel().getEntitiesOfClass(StarUnit.class, new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(80),
				unit -> unit.kind == UnitKind.CHANGELING).get(0);
			helper.assertTrue(!Faction.hostile(marine, changeling) && changeling.getHealth() == changeling.getMaxHealth(), "the marine attacks the changeling");
			helper.assertTrue(Status.left(changeling, Status.TEMPORARY) > 0 && changeling.summoned(), "the changeling does not run out");
			helper.succeed();
		});
	}

	private static StarUnit changeling(GameTestHelper helper) {
		List<StarUnit> found = helper.getLevel().getEntitiesOfClass(StarUnit.class, new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(80),
			unit -> unit.kind == UnitKind.CHANGELING && unit.isAlive());
		return found.isEmpty() ? null : found.get(0);
	}

	/**
	 * The owner's changeling (docs/HIVE_DESIGN.md 7b): it takes the look of the villager it sees, stays
	 * among the villagers, tells its hive where they are, passes for a villager with iron golems and dies
	 * from any harm.
	 */
	@GameTest(template = ARENA, batch = "zergChangelingVillager", timeoutTicks = 400)
	public void changelingCopiesAVillagerAndSpies(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		Hive hive = new Hive(UUID.randomUUID(), level.dimension().location().toString(), helper.absolutePos(new BlockPos(4, 2, 4)));
		dev.starmine.hive.HiveSavedData.get(level.getServer()).add(hive);
		StarUnit overseer = unit(helper, UnitKind.OVERSEER, 10, 24, null);
		overseer.setNoAi(true);
		overseer.joinHive(hive);
		net.minecraft.world.entity.npc.Villager villager = net.minecraft.world.entity.EntityType.VILLAGER.create(level);
		Vec3 home = point(helper, 22, 24); // 12 blocks off: within sight, not among them yet
		villager.moveTo(home.x, home.y, home.z, 0, 0);
		villager.setNoAi(true);
		level.addFreshEntity(villager);
		helper.runAfterDelay(2, () -> {
			overseer.setEnergy(50);
			helper.assertTrue(Abilities.cast(overseer, level, Ability.SPAWN_CHANGELING, null, null), "no changeling");
		});
		helper.runAfterDelay(300, () -> {
			StarUnit changeling = changeling(helper);
			helper.assertTrue(changeling != null, "no changeling");
			helper.assertTrue(Changeling.VILLAGER.equals(changeling.disguise()), "no villager look: '" + changeling.disguise() + "'");
			helper.assertTrue(changeling.distanceTo(villager) < Changeling.AMONG + 3, "not among the villagers: "
				+ changeling.distanceTo(villager) + " blocks off, at " + TestArena.where(helper, changeling));
			helper.assertTrue(hive.scouted != null && hive.scouted.closerThan(villager.blockPosition(), 2), "the hive does not know where: " + hive.scouted);
			net.minecraft.world.entity.animal.IronGolem golem = net.minecraft.world.entity.EntityType.IRON_GOLEM.create(level);
			helper.assertTrue(!golem.canAttack(changeling), "a golem sees through the villager look");
			changeling.hurt(level.damageSources().generic(), 0.1f);
			helper.assertTrue(!changeling.isAlive(), "a scratch did not kill it");
			villager.discard();
			helper.succeed();
		});
	}

	/** A changeling that saw a player first looks like that player and keeps 8–16 blocks behind them. */
	@GameTest(template = ARENA, batch = "zergChangelingPlayer", timeoutTicks = 400)
	public void changelingCopiesAPlayerAndFollows(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		net.minecraft.server.level.ServerPlayer player = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayerInLevel();
		Vec3 near = point(helper, 22, 24), far = point(helper, 36, 36);
		player.teleportTo(near.x, near.y, near.z);
		StarUnit overseer = unit(helper, UnitKind.OVERSEER, 10, 24, null);
		overseer.setNoAi(true);
		helper.runAfterDelay(2, () -> {
			overseer.setEnergy(50);
			helper.assertTrue(Abilities.cast(overseer, level, Ability.SPAWN_CHANGELING, null, null), "no changeling");
		});
		helper.runAfterDelay(40, () -> {
			StarUnit changeling = changeling(helper);
			if (changeling == null || !player.getUUID().toString().equals(changeling.disguise())) {
				player.discard();
				helper.fail("no look of the player: '" + (changeling == null ? "no changeling" : changeling.disguise()) + "'");
			}
			player.teleportTo(far.x, far.y, far.z);
		});
		helper.runAfterDelay(340, () -> {
			StarUnit changeling = changeling(helper);
			double distance = changeling == null ? -1 : changeling.distanceTo(player);
			player.discard(); // a mock player left in the world would make every other test's mobs despawn
			helper.assertTrue(distance >= Changeling.BEHIND_MIN - 2 && distance <= Changeling.BEHIND_MAX + 2,
				"not behind the player: " + distance + " blocks");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "zergParasite", timeoutTicks = 300)
	public void neuralParasiteTurnsAUnit(GameTestHelper helper) {
		clear(helper);
		UUID player = UUID.randomUUID();
		StarUnit infestor = unit(helper, UnitKind.INFESTOR, 10, 24, player);
		infestor.setNoAi(true);
		StarUnit thor = unit(helper, UnitKind.MARAUDER, 16, 24, null);
		thor.setNoAi(true);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> {
			infestor.setEnergy(100);
			helper.assertTrue(!Faction.allied(thor, infestor), "allied before");
			helper.assertTrue(Abilities.cast(infestor, level, Ability.NEURAL_PARASITE, thor, null), "no parasite");
		});
		helper.runAfterDelay(30, () -> {
			helper.assertTrue(Faction.allied(thor, infestor) && Status.has(thor, Status.PARASITE), "the marauder is not the infestor's");
			helper.assertTrue(Status.has(infestor, Status.CHANNELLING) && infestor.weapon().range() == 0, "the infestor does not channel");
			helper.assertTrue(thor.controller().equals(java.util.Optional.of(player)), "the infestor's player does not command it (SC2)");
		});
		helper.runAfterDelay(260, () -> {
			helper.assertTrue(!Faction.allied(thor, infestor) && !Status.has(thor, Status.PARASITE), "the parasite lasts more than 11 s");
			helper.assertTrue(thor.controller().isEmpty(), "the infestor's player still commands it");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "zergCloud", timeoutTicks = 200)
	public void blindingCloudShortensRanges(GameTestHelper helper) {
		clear(helper);
		StarUnit viper = unit(helper, UnitKind.VIPER, 10, 24, UUID.randomUUID());
		viper.setNoAi(true);
		StarUnit marine = unit(helper, UnitKind.MARINE, 18, 24, null);
		marine.setNoAi(true);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> {
			viper.setEnergy(100);
			helper.assertTrue(Abilities.cast(viper, level, Ability.BLINDING_CLOUD, null, marine.position()), "no cloud");
		});
		helper.runAfterDelay(20, () -> helper.assertTrue(marine.weapon().range() <= Status.BLINDED_RANGE, "the marine still reaches " + marine.weapon().range()));
		helper.runAfterDelay(150, () -> {
			helper.assertTrue(marine.weapon().range() == UnitKind.MARINE.range, "the cloud lasts too long");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "zergAbduct", timeoutTicks = 100)
	public void abductPullsToTheViper(GameTestHelper helper) {
		clear(helper);
		StarUnit viper = unit(helper, UnitKind.VIPER, 10, 24, UUID.randomUUID());
		viper.setNoAi(true);
		StarUnit tank = unit(helper, UnitKind.SIEGE_TANK, 19, 24, null);
		tank.setNoAi(true);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> {
			viper.setEnergy(100);
			helper.assertTrue(Abilities.cast(viper, level, Ability.ABDUCT, tank, null), "no abduct");
			helper.assertTrue(Abilities.horizontal(tank.position(), viper.position()) < 2, "the tank was not pulled: " + tank.position());
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "zergBomb", timeoutTicks = 300)
	public void parasiticBombHitsFlyersAround(GameTestHelper helper) {
		clear(helper);
		StarUnit viper = unit(helper, UnitKind.VIPER, 10, 24, UUID.randomUUID());
		viper.setNoAi(true);
		StarUnit phoenix = unit(helper, UnitKind.PHOENIX, 17, 24, null), other = unit(helper, UnitKind.PHOENIX, 18, 26, null);
		StarUnit zealot = unit(helper, UnitKind.ZEALOT, 17, 25, null);
		for (StarUnit unit : List.of(phoenix, other, zealot)) unit.setNoAi(true);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> {
			viper.setEnergy(125);
			helper.assertTrue(Abilities.cast(viper, level, Ability.PARASITIC_BOMB, phoenix, null), "no bomb");
		});
		helper.runAfterDelay(160, () -> {
			for (StarUnit air : List.of(phoenix, other))
				helper.assertTrue(air.shield() + air.getHealth() < air.kind.shields + air.getMaxHealth(), "a phoenix took nothing");
			helper.assertTrue(zealot.shield() == zealot.kind.shields, "the bomb hit a ground unit");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "zergConsume", timeoutTicks = 400)
	public void viperConsumesABuilding(GameTestHelper helper) {
		clear(helper);
		UUID player = UUID.randomUUID();
		StarUnit viper = unit(helper, UnitKind.VIPER, 10, 24, player);
		viper.setNoAi(true);
		StarUnit pool = unit(helper, UnitKind.EVOLUTION_CHAMBER, 12, 24, player);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> {
			viper.setEnergy(0);
			helper.assertTrue(Abilities.cast(viper, level, Ability.CONSUME, pool, null), "no consume");
		});
		helper.runAfterDelay(300, () -> {
			helper.assertTrue(viper.energy() >= ZergAbilities.CONSUME_ENERGY - 1, "the viper gained " + viper.energy());
			helper.assertTrue(pool.getMaxHealth() - pool.getHealth() >= ZergAbilities.CONSUME_LIFE * UnitKind.SCALE - 3, "the building lost " + (pool.getMaxHealth() - pool.getHealth()));
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "zergSpray", timeoutTicks = 300)
	public void causticSprayEatsABuilding(GameTestHelper helper) {
		clear(helper);
		StarUnit corruptor = unit(helper, UnitKind.CORRUPTOR, 10, 24, UUID.randomUUID());
		corruptor.setNoAi(true);
		StarUnit depot = unit(helper, UnitKind.SUPPLY_DEPOT, 14, 24, null);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> helper.assertTrue(Abilities.cast(corruptor, level, Ability.CAUSTIC_SPRAY, depot, null), "no spray"));
		helper.runAfterDelay(200, () -> {
			// SC2: 7 a second for 4.3 s, then 30 a second
			double expected = (7 * 4.3 + 30 * (200 / 20.0 - 4.3)) * UnitKind.SCALE;
			helper.assertTrue(depot.getMaxHealth() - depot.getHealth() >= expected * 0.7, "the spray did " + (depot.getMaxHealth() - depot.getHealth()));
			helper.succeed();
		});
	}
}
