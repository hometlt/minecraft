package dev.starmine.world;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.clear;
import static dev.starmine.ability.TestArena.point;
import static dev.starmine.ability.TestArena.unit;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Owner 17.10: the heroes of the Nether and the End — Zagara's banelings and her dig, Tosh's cloak, a beaten hero's part
 * of the key, a freed hero who falls coming back at its base.
 */
public class DimensionHeroGameTests implements FabricGameTest {
	@GameTest(template = ARENA, batch = "heroZagara", timeoutTicks = 400)
	public void zagaraThrowsBanelingsAndDigs(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		StarUnit zagara = unit(helper, UnitKind.ZAGARA, 10, 24, null);
		StarUnit marine = unit(helper, UnitKind.MARINE, 30, 24, UUID.randomUUID());
		marine.setNoAi(true);
		marine.setInvulnerable(true);
		zagara.addTag(HeroLiberation.FREED); // no arena pull for the test
		java.util.concurrent.atomic.AtomicBoolean threw = new java.util.concurrent.atomic.AtomicBoolean();
		helper.runAfterDelay(2, () -> zagara.setTarget(marine));
		helper.onEachTick(() -> {
			if (zagara.getTarget() == null) zagara.setTarget(marine);
			if (!threw.get() && !level.getEntitiesOfClass(StarUnit.class, zagara.getBoundingBox().inflate(40),
				unit -> zagara.getUUID().equals(unit.summoner()) && unit.kind == UnitKind.BANELING).isEmpty()) threw.set(true);
		});
		helper.runAfterDelay(300, () -> {
			helper.assertTrue(threw.get(), "Zagara threw no banelings");
			helper.assertTrue(zagara.distanceTo(marine) < 12, "Zagara did not dig to the marine: " + zagara.distanceTo(marine));
			level.getEntitiesOfClass(StarUnit.class, zagara.getBoundingBox().inflate(60), unit -> zagara.getUUID().equals(unit.summoner())).forEach(Entity::discard);
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "heroTosh", timeoutTicks = 700)
	public void toshHidesAWhileZeratulShowsAWhile(GameTestHelper helper) {
		clear(helper);
		StarUnit tosh = unit(helper, UnitKind.TOSH, 10, 20, null);
		StarUnit zeratul = unit(helper, UnitKind.ZERATUL, 10, 30, null);
		StarUnit marine = unit(helper, UnitKind.MARINE, 20, 24, UUID.randomUUID());
		marine.setNoAi(true);
		marine.setInvulnerable(true);
		tosh.addTag(HeroLiberation.FREED);
		zeratul.addTag(HeroLiberation.FREED);
		int[] toshHidden = {0}, zeratulHidden = {0}, ticks = {0};
		helper.onEachTick(() -> {
			if (tosh.getTarget() == null) tosh.setTarget(marine);
			if (zeratul.getTarget() == null) zeratul.setTarget(marine);
			ticks[0]++;
			if (tosh.cloaked()) toshHidden[0]++;
			if (zeratul.cloaked()) zeratulHidden[0]++;
		});
		helper.runAfterDelay(650, () -> {
			double toshShare = toshHidden[0] / (double) ticks[0], zer = zeratulHidden[0] / (double) ticks[0];
			helper.assertTrue(toshShare > 0.1 && toshShare < 0.5, "Tosh is hidden " + Math.round(toshShare * 100) + "% of the time, not a while");
			helper.assertTrue(zer > 0.6 && zer < 0.98, "Zeratul is hidden " + Math.round(zer * 100) + "% of the time, not nearly always");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "heroPart", timeoutTicks = 100)
	public void aBeatenHeroGivesItsPartAndFallsLater(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		VoidWorld.State state = VoidWorld.State.get(level.getServer());
		StarUnit zeratul = unit(helper, UnitKind.ZERATUL, 24, 24, null);
		zeratul.setNoAi(true);
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayerInLevel();
		Vec3 at = point(helper, 20, 20);
		player.teleportTo(at.x, at.y, at.z);
		helper.runAfterDelay(2, () -> zeratul.hurt(level.damageSources().playerAttack(player), 100000));
		helper.runAfterDelay(20, () -> {
			boolean freed = zeratul.isAlive() && HeroLiberation.freed(zeratul) && state.freed.contains("ZERATUL");
			boolean part = level.getEntitiesOfClass(ItemEntity.class, zeratul.getBoundingBox().inflate(4),
				item -> item.getItem().is(Artifact.part(UnitKind.Race.PROTOSS))).size() == 1;
			boolean theirs = zeratul.owner().filter(player.getUUID()::equals).isPresent();
			// freed, it may fall: then it is remembered to come back
			int before = state.fallen.size();
			zeratul.hurt(level.damageSources().playerAttack(player), 100000);
			boolean dead = !zeratul.isAlive();
			boolean remembered = state.fallen.size() == before + 1 && state.fallen.get(state.fallen.size() - 1).kind().equals("ZERATUL");
			state.freed.remove("ZERATUL");
			state.fallen.removeIf(f -> f.kind().equals("ZERATUL") && f.owner().equals(player.getUUID()));
			level.getEntitiesOfClass(ItemEntity.class, zeratul.getBoundingBox().inflate(8)).forEach(Entity::discard);
			level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
			helper.assertTrue(freed, "Zeratul was not freed");
			helper.assertTrue(part, "Zeratul gave no part of the key");
			helper.assertTrue(theirs, "Zeratul is not the player's");
			helper.assertTrue(dead, "a freed hero cannot fall");
			helper.assertTrue(remembered, "a fallen hero is not remembered to come back");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "heroBack", timeoutTicks = 100)
	public void aFallenHeroComesBack(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		VoidWorld.State state = VoidWorld.State.get(level.getServer());
		UUID owner = UUID.randomUUID();
		net.minecraft.core.BlockPos home = net.minecraft.core.BlockPos.containing(point(helper, 20, 20));
		state.fallen.add(new VoidWorld.State.Fallen("HORNER", owner, level.dimension().location().toString(), home, level.getGameTime() - 1));
		helper.runAfterDelay(30, () -> {
			List<StarUnit> back = level.getEntitiesOfClass(StarUnit.class, new net.minecraft.world.phys.AABB(home).inflate(20),
				unit -> unit.kind == UnitKind.HORNER);
			state.fallen.removeIf(f -> f.owner().equals(owner));
			back.forEach(Entity::discard);
			helper.assertTrue(back.size() == 1 && HeroLiberation.freed(back.get(0)) && back.get(0).owner().filter(owner::equals).isPresent(),
				"Horner did not come back, freed and the player's: " + back.size());
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "heroEndBase", timeoutTicks = 600)
	public void anEndBaseStandsOnItsIslandWithItsHero(GameTestHelper helper) {
		ServerLevel end = helper.getLevel().getServer().getLevel(net.minecraft.world.level.Level.END);
		helper.assertTrue(end != null, "no End");
		dev.starmine.hive.HiveSavedData data = dev.starmine.hive.HiveSavedData.get(end.getServer());
		net.minecraft.core.BlockPos column = new net.minecraft.core.BlockPos(40000 + helper.getLevel().random.nextInt(4000), 0, 40000);
		UUID base = DimensionBases.build(end, UnitKind.Race.TERRAN, column, true);
		try {
			helper.assertTrue(base != null, "no base built");
			dev.starmine.hive.Hive hive = data.get(base);
			helper.assertTrue(hive != null && hive.dimension.equals("minecraft:the_end"), "the base is not the End's: " + (hive == null ? null : hive.dimension));
			helper.assertTrue(end.getBlockState(column.atY(63)).is(net.minecraft.world.level.block.Blocks.END_STONE)
				|| !end.getBlockState(hive.hatchery.below()).isAir(), "no ground under the base");
			helper.assertTrue(data.pending.stream().anyMatch(p -> p.kind() == UnitKind.HORNER && base.equals(p.hive()) && p.in(end)), "Horner does not wait over his base");
			helper.assertTrue(data.pending.stream().filter(p -> base.equals(p.hive())).allMatch(p -> p.in(end)), "the base's units would come in another world");
		} finally {
			data.pending.removeIf(p -> base != null && base.equals(p.hive()));
			if (base != null) data.remove(base);
		}
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "heroNetherBase", timeoutTicks = 600)
	public void aNetherLairStandsUnderTheRoofWithItsHero(GameTestHelper helper) {
		ServerLevel nether = helper.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
		helper.assertTrue(nether != null, "no Nether");
		dev.starmine.hive.HiveSavedData data = dev.starmine.hive.HiveSavedData.get(nether.getServer());
		net.minecraft.core.BlockPos column = new net.minecraft.core.BlockPos(30000 + helper.getLevel().random.nextInt(4000), 0, -30000);
		UUID base = DimensionBases.build(nether, UnitKind.Race.ZERG, column, false);
		try {
			helper.assertTrue(base != null, "no lair built");
			dev.starmine.hive.Hive hive = data.get(base);
			helper.assertTrue(hive != null && hive.dimension.equals("minecraft:the_nether"), "the lair is not the Nether's");
			helper.assertTrue(hive.hatchery.getY() < 120 && hive.hatchery.getY() > nether.getMinBuildHeight() + 4, "the lair is over the Nether's roof: " + hive.hatchery);
			helper.assertTrue(data.pending.stream().anyMatch(p -> p.kind() == UnitKind.ZAGARA && base.equals(p.hive()) && p.in(nether)), "Zagara does not wait by her lair");
		} finally {
			data.pending.removeIf(p -> base != null && base.equals(p.hive()));
			if (base != null) data.remove(base);
		}
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "heroNetherHall", timeoutTicks = 400)
	public void aNetherBaseHasAGreatHall(GameTestHelper helper) {
		ServerLevel nether = helper.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
		helper.assertTrue(nether != null, "no Nether");
		int x = 34000 + helper.getLevel().random.nextInt(4000), z = 30000;
		int ground = dev.starmine.hive.HiveStructure.ground(nether, x, z);
		net.minecraft.core.BlockPos floor = new net.minecraft.core.BlockPos(x, ground, z);
		DimensionBases.hall(nether, floor, net.minecraft.util.RandomSource.create(1));
		int r = DimensionBases.HALL_RADIUS;
		for (int[] d : new int[][] {{0, 0}, {r - 3, 0}, {0, -(r - 3)}, {-(r - 5), 6}, {8, 8}}) {
			net.minecraft.core.BlockPos at = floor.offset(d[0], 0, d[1]);
			helper.assertTrue(nether.getBlockState(at).isAir() && nether.getBlockState(at.above(3)).isAir(), "no room in the hall at " + at);
			helper.assertTrue(nether.getBlockState(at.below()).isCollisionShapeFullBlock(nether, at.below()), "no floor in the hall at " + at);
		}
		helper.assertTrue(nether.getBlockState(floor.above(DimensionBases.HALL_HEIGHT - 2)).isAir() || floor.getY() + DimensionBases.HALL_HEIGHT > 120,
			"the hall is low in the middle");
		helper.succeed();
	}
}
