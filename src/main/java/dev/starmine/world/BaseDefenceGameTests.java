package dev.starmine.world;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.clear;
import static dev.starmine.ability.TestArena.point;

import dev.starmine.ability.GuardianFights;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveLogic;
import dev.starmine.hive.HiveSavedData;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Owner 19.10: the main bases twice as wide, guns round their edge and every building on a pedestal, clear of
 * blocks; the levels under them reachable from the surface down to the guardian's hall; Karax's and Swann's fights.
 */
public class BaseDefenceGameTests implements FabricGameTest {
	private static BlockPos far(GameTestHelper helper, int dx) {
		return helper.absolutePos(BlockPos.ZERO).offset(dx, 70, 900);
	}

	@GameTest(template = ARENA, batch = "structuresBase", timeoutTicks = 600)
	public void aMainBaseStandsClearOnPedestalsWithGunsAndLevels(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		HiveSavedData data = HiveSavedData.get(level.getServer());
		BlockPos site = far(helper, 9000);
		RaceBases.Report report = RaceBases.build(level, UnitKind.Race.PROTOSS, site, RandomSource.create(21));
		Painter.finishAll();
		try {
			var mine = data.pending.stream().filter(p -> p.hive().equals(report.base())).toList();
			var members = mine.stream().filter(p -> p.hall() == null).toList();
			long cannons = members.stream().filter(p -> p.kind() == UnitKind.PHOTON_CANNON).count();
			helper.assertTrue(cannons >= 12, "only " + cannons + " cannons round the edge");
			BlockPos main = members.stream().filter(p -> p.kind() == UnitKind.NEXUS).findFirst().orElseThrow().pos();
			double widest = members.stream().filter(p -> p.kind().structure())
				.mapToDouble(p -> Math.hypot(p.pos().getX() - main.getX(), p.pos().getZ() - main.getZ())).max().orElse(0);
			helper.assertTrue(widest >= RaceBases.EDGE - 4, "the base is only " + (int) widest + " blocks round");
			for (var p : members) {
				if (!p.kind().structure() || p.kind().gas()) continue;
				Vec3 c = p.kind().gridCentre(Vec3.atBottomCenterOf(p.pos()));
				int size = Math.round(p.kind().width);
				int minX = (int) Math.floor(c.x - size / 2.0 + 0.01), minZ = (int) Math.floor(c.z - size / 2.0 + 0.01);
				BlockPos under = p.pos().below();
				helper.assertTrue(!level.getBlockState(under).isAir(), p.kind() + " has no pedestal under it at " + under);
				for (int x = minX; x < minX + size; x++)
					for (int z = minZ; z < minZ + size; z++)
						for (int up = 0; up < 2; up++) {
							BlockPos at = new BlockPos(x, p.pos().getY() + up, z);
							helper.assertTrue(level.getBlockState(at).getCollisionShape(level, at).isEmpty(),
								p.kind() + " stands in " + level.getBlockState(at) + " at " + at);
						}
			}
			var karax = mine.stream().filter(p -> p.kind() == UnitKind.KARAX).findFirst().orElse(null);
			helper.assertTrue(karax != null && karax.hall() != null && karax.pos().getY() < main.getY() - 40, "Karax is not in the hall under the base: " + karax);
			long guards = mine.stream().filter(p -> p.hall() != null && (p.kind() == UnitKind.ZEALOT || p.kind() == UnitKind.ADEPT)
				&& p.pos().getY() < main.getY() - 10).count();
			helper.assertTrue(guards >= 6, "only " + guards + " guards in the levels");
		} finally {
			data.pending.removeIf(p -> p.hive().equals(report.base()));
			data.remove(report.base());
		}
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "structuresBase", timeoutTicks = 600)
	public void theLevelsLeadFromTheWayInToTheHall(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (UnitKind.Race race : UnitKind.Race.values()) {
			BlockPos centre = far(helper, 12000 + race.ordinal() * 400).above(30);
			Undercity.Plan plan = Undercity.plan(level, race, centre, RandomSource.create(race.ordinal() + 5));
			helper.assertTrue(plan != null && plan.levels() == Undercity.LEVELS, race + ": no levels under " + centre);
			plan.painter().start();
			Painter.finishAll();
			BlockPos start = new BlockPos(centre.getX() + Undercity.SPACING, centre.getY() - Undercity.TOP, centre.getZ());
			helper.assertTrue(reach(level, start, plan.hall(), 400_000), race + ": the hall cannot be walked to from the way in");
			helper.assertTrue(plan.spawns().size() >= 10, race + ": only " + plan.spawns().size() + " dwellers");
			helper.assertTrue(open(level, plan.hall()) && open(level, plan.hall().above(6)), race + ": no hall");
		}
		helper.succeed();
	}

	/** Whether a unit two high can walk from `from` to `to` (stepping a block up or down), within `limit` cells. */
	private static boolean reach(ServerLevel level, BlockPos from, BlockPos to, int limit) {
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>(List.of(from));
		seen.add(from);
		while (!queue.isEmpty() && seen.size() < limit) {
			BlockPos pos = queue.poll();
			if (pos.distManhattan(to) <= 2) return true;
			for (Direction direction : Direction.Plane.HORIZONTAL)
				for (int dy = -1; dy <= 1; dy++) {
					BlockPos next = pos.relative(direction).above(dy);
					if (seen.contains(next) || !open(level, next) || !open(level, next.above())
						|| level.getBlockState(next.below()).getCollisionShape(level, next.below()).isEmpty()) continue;
					seen.add(next);
					queue.add(next);
				}
		}
		return false;
	}

	/** Whether a body may stand in a cell: nothing in it but a thin layer at most (the zerg's creep). */
	private static boolean open(ServerLevel level, BlockPos pos) {
		var shape = level.getBlockState(pos).getCollisionShape(level, pos);
		return shape.isEmpty() || shape.max(Direction.Axis.Y) <= 0.25;
	}

	private static StarUnit guardian(GameTestHelper helper, UnitKind kind) {
		ServerLevel level = helper.getLevel();
		BlockPos centre = BlockPos.containing(point(helper, 24, 24));
		Hive wild = new Hive(UUID.randomUUID(), level.dimension().location().toString(), centre);
		wild.race = kind.race;
		HiveSavedData.get(level.getServer()).add(wild);
		StarUnit hero = HiveLogic.spawnNest(level, wild, kind, point(helper, 24, 24), centre);
		hero.setNoAi(true);
		return hero;
	}

	/**
	 * The guardian's foe: a player's zealot (a game test's mock player counts as creative — nobody attacks it), standing
	 * still, with health to live through the whole fight.
	 */
	private static StarUnit foe(GameTestHelper helper, StarUnit hero, int x, int z) {
		ServerLevel level = helper.getLevel();
		StarUnit unit = dev.starmine.StarMine.type(UnitKind.ZEALOT).create(level);
		Vec3 at = point(helper, x, z);
		unit.moveTo(at.x, at.y, at.z, 0, 0);
		unit.setOwner(UUID.randomUUID());
		unit.setNoAi(true);
		var health = unit.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
		if (health != null) health.setBaseValue(1000);
		unit.setHealth(unit.getMaxHealth());
		level.addFreshEntity(unit);
		hero.setTarget(unit);
		return unit;
	}

	@GameTest(template = ARENA, batch = "guardianFights", timeoutTicks = 400)
	public void karaxSendsDronesCannonsAndMonoliths(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		StarUnit karax = guardian(helper, UnitKind.KARAX);
		StarUnit player = foe(helper, karax, 24, 10);
		helper.runAfterDelay(160, () -> {
			helper.assertTrue(!level.getEntitiesOfClass(StarUnit.class, karax.getBoundingBox().inflate(64),
				u -> u.kind == UnitKind.INTERCEPTOR && u.getTags().contains(GuardianFights.DRONE)).isEmpty(), "no air drones");
			helper.assertTrue(!level.getEntitiesOfClass(StarUnit.class, karax.getBoundingBox().inflate(64),
				u -> u.kind == UnitKind.PHOTON_CANNON && u.getTags().contains(GuardianFights.CANNON)).isEmpty(), "no photon cannon warped in");
			karax.addTag("starmine_phase2");
		});
		helper.runAfterDelay(170, () -> {
			long monoliths = karax.getTags().stream().filter(t -> t.startsWith(GuardianFights.MONOLITH)).count();
			helper.assertTrue(monoliths == 2, monoliths + " monoliths");
			for (String tag : karax.getTags()) if (tag.startsWith(GuardianFights.MONOLITH))
				helper.assertTrue(level.getBlockState(BlockPos.of(Long.parseLong(tag.substring(GuardianFights.MONOLITH.length())))).is(Blocks.AMETHYST_BLOCK), "no crystal");
			GuardianFights.end(karax, level); // freed: what he raised goes
			helper.assertTrue(karax.getTags().stream().noneMatch(t -> t.startsWith(GuardianFights.MONOLITH)), "monoliths left after he is freed");
			helper.assertTrue(level.getEntitiesOfClass(StarUnit.class, karax.getBoundingBox().inflate(64), u -> u.getTags().contains(GuardianFights.DRONE)).isEmpty(),
				"drones left after he is freed");
			karax.discard();
			player.discard();
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "guardianFights", timeoutTicks = 100)
	public void aMonolithBurnsThroughThreeBlocks(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		StarUnit karax = guardian(helper, UnitKind.KARAX);
		StarUnit player = foe(helper, karax, 24, 8);
		Vec3 from = point(helper, 24, 30).add(0, 1.5, 0);
		BlockPos wall = BlockPos.containing(point(helper, 24, 20).add(0, 1, 0));
		for (int t = 0; t < 2; t++) for (int up = -1; up <= 3; up++) level.setBlockAndUpdate(wall.north(t).above(up).east(0), Blocks.STONE.defaultBlockState());
		float before = player.getHealth() + player.shield();
		boolean hit = GuardianFights.monolithBeam(karax, level, from, player);
		helper.assertTrue(hit && player.getHealth() + player.shield() < before, "the beam did not burn through two blocks to the player");
		for (int t = 0; t < 5; t++) for (int up = -1; up <= 3; up++) level.setBlockAndUpdate(wall.north(t).above(up), Blocks.STONE.defaultBlockState());
		helper.assertFalse(GuardianFights.monolithBeam(karax, level, from, player), "the beam went through five blocks");
		karax.discard();
		player.discard();
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "guardianFights", timeoutTicks = 300)
	public void swannsMinesBettiesAndOdin(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		StarUnit swann = guardian(helper, UnitKind.SWANN);
		StarUnit player = foe(helper, swann, 24, 8);
		BlockPos mine = GuardianFights.layMine(swann, level, player);
		helper.assertTrue(mine != null && level.getBlockState(mine).is(Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE), "no mine laid");
		float before = player.getHealth() + player.shield();
		player.moveTo(mine.getX() + 0.5, mine.getY(), mine.getZ() + 0.5, 0, 0);
		swann.addTag("starmine_phase2");
		swann.addTag("starmine_phase3");
		helper.runAfterDelay(100, () -> {
			helper.assertTrue(!level.getBlockState(mine).is(Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE), "the mine did not go off");
			helper.assertTrue(player.getHealth() + player.shield() < before, "the mine did not hurt the player");
			var odins = level.getEntitiesOfClass(StarUnit.class, swann.getBoundingBox().inflate(64), u -> u.getTags().contains(GuardianFights.ODIN));
			helper.assertTrue(odins.size() == 1 && odins.get(0).kind == UnitKind.THOR && odins.get(0).getTags().contains(GuardianFights.FORM)
				&& swann.getVehicle() == odins.get(0) && swann.isInvisible(), "Swann did not climb into Odin: " + odins.size());
			float before3 = swann.getHealth();
			odins.get(0).hurt(level.damageSources().generic(), 5);
			helper.assertTrue(swann.getHealth() < before3, "hurting Odin does not hurt Swann");
			var betties = level.getEntitiesOfClass(StarUnit.class, swann.getBoundingBox().inflate(64), u -> u.getTags().contains(GuardianFights.BETTY));
			helper.assertTrue(!betties.isEmpty(), "no flaming Betty");
			GuardianFights.end(swann, level);
			helper.assertTrue(level.getEntitiesOfClass(StarUnit.class, swann.getBoundingBox().inflate(64),
				u -> u.getTags().contains(GuardianFights.ODIN) || u.getTags().contains(GuardianFights.BETTY)).isEmpty(), "Odin or Betties left");
			swann.discard();
			player.discard();
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "guardianFights", timeoutTicks = 100)
	public void aThreatenedGuardianCallsItsArmy(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		StarUnit swann = guardian(helper, UnitKind.SWANN);
		HiveSavedData data = HiveSavedData.get(level.getServer());
		Hive base = data.get(swann.hiveId());
		StarUnit far = HiveLogic.spawn(level, base, UnitKind.MARINE, point(helper, 44, 44), null);
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayerInLevel();
		Vec3 at = point(helper, 10, 10);
		player.teleportTo(at.x, at.y, at.z);
		swann.hurt(level.damageSources().playerAttack(player), 1);
		helper.assertTrue(far.order() == StarUnit.Order.ATTACK_MOVE || far.getTarget() == player, "the marine did not come: " + far.order());
		GuardianFights.end(swann, level);
		far.discard();
		swann.discard();
		level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
		helper.succeed();
	}
}
