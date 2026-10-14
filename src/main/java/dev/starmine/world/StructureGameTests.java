package dev.starmine.world;

import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;

/**
 * Generated structures (owner 14.10): the terran labyrinth is one connected maze from its door to the
 * reactor core; the protoss temples are joined from the stair door to the altar; the three sky ships
 * are built with their guards; a generated terran base is a wild base waiting for its command center.
 * Built far from the test arenas (the structures are big), in the air of the flat test world.
 */
public class StructureGameTests implements FabricGameTest {
	private static final String ARENA = "starmine:arena";

	private static BlockPos far(GameTestHelper helper, int dx) {
		return helper.absolutePos(new BlockPos(0, 0, 0)).offset(dx, 70, 400);
	}

	/** Whether `to` can be walked to from `from` on one level (feet cells empty, two high) within `limit` cells. */
	private static boolean walkable(ServerLevel level, BlockPos from, BlockPos to, int limit) {
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>(List.of(from));
		seen.add(from);
		while (!queue.isEmpty() && seen.size() < limit) {
			BlockPos pos = queue.poll();
			if (pos.equals(to)) return true;
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos next = pos.relative(direction);
				if (seen.contains(next) || !level.getBlockState(next).isAir() || !level.getBlockState(next.above()).isAir()) continue;
				seen.add(next);
				queue.add(next);
			}
		}
		return false;
	}

	@GameTest(template = ARENA, batch = "structures", timeoutTicks = 200)
	public void labyrinthIsOneMaze(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Painter painter = new Painter(level, RandomSource.create(7));
		BlockPos corner = far(helper, 0);
		painter.load(corner, 80);
		List<Spawn> guards = Dungeons.techLabyrinth(painter, corner);
		int mid = Dungeons.MAZE_CELLS / 2;
		BlockPos door = corner.offset(0, 1, mid * Dungeons.PITCH + 2);
		BlockPos core = corner.offset(mid * Dungeons.PITCH + 2, 1, mid * Dungeons.PITCH + 2);
		helper.assertTrue(level.getBlockState(core.above(3)).is(Blocks.BEACON), "no reactor core");
		helper.assertTrue(walkable(level, door, core.offset(2, 0, 0), 4000), "the core cannot be reached from the door");
		helper.assertTrue(guards.size() >= 11 && guards.stream().allMatch(g -> g.hall() != null), "guards " + guards.size());
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "structures", timeoutTicks = 200)
	public void templesAreJoined(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Painter painter = new Painter(level, RandomSource.create(9));
		BlockPos centre = far(helper, 200);
		painter.load(centre, 80);
		List<Spawn> guards = Dungeons.ancientTemples(painter, centre);
		helper.assertTrue(level.getBlockState(centre.above(2)).is(Blocks.AMETHYST_BLOCK), "no altar crystal");
		helper.assertTrue(walkable(level, centre.offset(0, 0, -41), centre.offset(0, 0, 6), 6000), "the altar cannot be reached from the stair");
		helper.assertTrue(walkable(level, centre.offset(34, 0, 3), centre.offset(0, 0, 6), 6000), "the east shrine is cut off");
		helper.assertTrue(guards.stream().anyMatch(g -> g.kind() == UnitKind.DARK_TEMPLAR), "no dark templar");
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "structures", timeoutTicks = 200)
	public void skyShipsAreBuilt(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos base = far(helper, 400).above(40);
		int i = 0;
		for (String ship : new String[] {"hyperion", "mothership", "leviathan"}) {
			Painter painter = new Painter(level, RandomSource.create(3 + i));
			BlockPos middle = base.offset(i++ * 110, 0, 0);
			painter.load(middle, 64);
			List<Spawn> spawns = switch (ship) {
				case "hyperion" -> SkyShips.hyperion(painter, middle, 0);
				case "mothership" -> SkyShips.mothership(painter, middle);
				default -> SkyShips.leviathan(painter, middle, 0);
			};
			helper.assertTrue(painter.placed() > 1500, ship + " has only " + painter.placed() + " blocks");
			helper.assertTrue(spawns.size() >= 8, ship + " has " + spawns.size() + " guards");
			helper.assertTrue(spawns.stream().anyMatch(s -> s.kind().flying), ship + " has no flyers");
		}
		helper.assertTrue(level.getBlockState(base.offset(30, 0, 0)).is(Blocks.SEA_LANTERN), "no Yamato muzzle");
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "structuresBase", timeoutTicks = 400)
	public void terranBaseIsWild(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		HiveSavedData data = HiveSavedData.get(level.getServer());
		BlockPos site = far(helper, 700).below(70);
		RaceBases.Report report = RaceBases.build(level, UnitKind.Race.TERRAN, site, RandomSource.create(5));
		Hive base = data.get(report.base());
		try {
			helper.assertTrue(base != null && base.owner == null && base.race == UnitKind.Race.TERRAN, "no wild terran base");
			helper.assertTrue(data.pending.stream().anyMatch(p -> p.hive().equals(base.id) && p.kind() == UnitKind.COMMAND_CENTER && p.hall() == null),
				"no command center waiting");
			helper.assertTrue(data.pending.stream().anyMatch(p -> p.hive().equals(base.id) && p.kind() == UnitKind.VIKING), "no Hyperion guards");
			helper.assertTrue(report.blocks() > 5000, "only " + report.blocks() + " blocks");
		} finally { // other tests share the world: no waiting units, no base left behind
			data.pending.removeIf(p -> base != null && p.hive().equals(base.id));
			if (base != null) data.remove(base.id);
		}
		helper.succeed();
	}
}
