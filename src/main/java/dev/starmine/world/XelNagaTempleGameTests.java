package dev.starmine.world;

import static dev.starmine.ability.TestArena.ARENA;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.HiveSavedData;
import java.util.ArrayDeque;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Owner 19.10: the xel'naga temples — a labyrinth from the door to the hall of keys wide and high for any army, a door
 * and walls nothing breaks, the door opened by the key, the portal opened by the three parts on the sockets and closed
 * when one is taken, the way through it to the Void and back.
 */
public class XelNagaTempleGameTests implements FabricGameTest {
	/** Every labyrinth leads from the passage outside to the hall, through every room, ten wide. */
	@GameTest(template = ARENA, batch = "templeMaze", timeoutTicks = 20)
	public void theLabyrinthLeadsToTheHall(GameTestHelper helper) {
		int span = XelNagaTemple.HALF;
		for (long seed = 1; seed <= 12; seed++) {
			XelNagaTemple.Maze maze = XelNagaTemple.Maze.make(RandomSource.create(seed));
			boolean[][] seen = new boolean[2 * span + 1][2 * span + 1];
			ArrayDeque<int[]> todo = new ArrayDeque<>();
			todo.add(new int[] {0, span - 4});
			seen[span][2 * span - 4] = true;
			while (!todo.isEmpty()) {
				int[] at = todo.poll();
				for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
					int x = at[0] + d[0], z = at[1] + d[1];
					if (Math.abs(x) > span || Math.abs(z) > span || seen[x + span][z + span]) continue;
					BlockState state = XelNagaTemple.cell(maze, x, 0, z);
					if (state == null || !(state.isAir() || state.is(XelNagaTemple.DOOR))) continue;
					seen[x + span][z + span] = true;
					todo.add(new int[] {x, z});
				}
			}
			helper.assertTrue(seen[span][span], "seed " + seed + ": no way to the hall");
			for (int i = 0; i < XelNagaTemple.CELLS; i++)
				for (int j = 0; j < XelNagaTemple.CELLS; j++) {
					BlockPos room = XelNagaTemple.room(i, j);
					helper.assertTrue(seen[room.getX() + span][room.getZ() + span], "seed " + seed + ": room " + i + "," + j + " cut off");
				}
			for (int across = XelNagaTemple.GATE_LOW; across <= XelNagaTemple.GATE_HIGH; across++) // the passage: ten wide, fourteen high
				for (int y = 0; y < XelNagaTemple.HEIGHT; y++) {
					BlockState state = XelNagaTemple.cell(maze, across, y, XelNagaTemple.DOOR_Z - 3); // under the slope (beyond, the sky)
					helper.assertTrue(state != null && state.isAir(), "seed " + seed + ": the passage is blocked at " + across + "," + y);
				}
			helper.assertTrue(XelNagaTemple.cell(maze, 0, XelNagaTemple.HALL_HEIGHT - 1, 0).isAir(), "the hall is low");
		}
		helper.assertTrue(XelNagaTemple.METAL.defaultBlockState().getDestroySpeed(helper.getLevel(), BlockPos.ZERO) < 0
			&& XelNagaTemple.DOOR.defaultBlockState().getDestroySpeed(helper.getLevel(), BlockPos.ZERO) < 0
			&& XelNagaTemple.METAL.getExplosionResistance() > 1000, "xel'naga metal breaks");
		helper.succeed();
	}

	/** A temple built: its door opens to the key; the three parts open the portal; taking one closes it; a unit goes through and back. */
	@GameTest(template = ARENA, batch = "templeBuilt", timeoutTicks = 300)
	public void theTempleOpensWithItsKey(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		VoidWorld.State state = VoidWorld.State.get(level.getServer());
		String world = Artifact.World.OVERWORLD.name();
		BlockPos wasSite = state.templeSites.get(world);
		boolean wasBuilt = state.temples.contains(world), wasOpen = state.portals.contains(world), wasDoor = state.doors.contains(world);
		BlockPos column = helper.absolutePos(BlockPos.ZERO).offset(3000, 0, 0);
		BlockPos centre = XelNagaTemple.build(level, Artifact.World.OVERWORLD, column);
		Painter.finishAll();
		HiveSavedData.get(level.getServer()).pending.removeIf(p -> XelNagaTemple.KEEPERS.equals(p.hive())); // no keepers left waiting
		Runnable restore = () -> {
			if (wasSite == null) state.templeSites.remove(world);
			else state.templeSites.put(world, wasSite);
			if (!wasBuilt) state.temples.remove(world);
			if (!wasOpen) state.portals.remove(world);
			if (!wasDoor) state.doors.remove(world);
		};
		BlockPos door = centre.offset(0, 3, XelNagaTemple.DOOR_Z);
		boolean closed = level.getBlockState(door).is(XelNagaTemple.DOOR);
		boolean walled = level.getBlockState(centre.offset(XelNagaTemple.GATE_LOW - 1, 3, XelNagaTemple.DOOR_Z)).is(XelNagaTemple.METAL);
		XelNagaTemple.open(level, door);
		boolean opened = level.getBlockState(door).isAir() && level.getBlockState(centre.offset(XelNagaTemple.GATE_HIGH, XelNagaTemple.HEIGHT - 1,
			XelNagaTemple.DOOR_Z)).isAir();
		BlockPos[] sockets = new BlockPos[3];
		for (int i = 0; i < 3; i++) sockets[i] = centre.offset(XelNagaTemple.SOCKET_X[i], 1, XelNagaTemple.SOCKET_Z);
		boolean sockets3 = level.getBlockState(sockets[0].below()).is(XelNagaTemple.SOCKET) && level.getBlockState(sockets[2].below()).is(XelNagaTemple.SOCKET);
		BlockPos inPortal = centre.offset(0, 3, XelNagaTemple.PORTAL_Z);
		boolean framed = level.getBlockState(centre.offset(XelNagaTemple.PORTAL_HALF - 1, 0, XelNagaTemple.PORTAL_Z)).is(XelNagaTemple.GLYPH);
		UnitKind.Race[] races = UnitKind.Race.values();
		level.setBlock(sockets[0], Artifact.KEYS.get(Artifact.World.OVERWORLD).get(races[0]).defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(sockets[1], Artifact.KEYS.get(Artifact.World.OVERWORLD).get(races[1]).defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(sockets[2], Artifact.KEYS.get(Artifact.World.NETHER).get(races[2]).defaultBlockState(), Block.UPDATE_ALL); // another world's
		boolean shutWithForeign = !level.getBlockState(inPortal).is(XelNagaTemple.PORTAL);
		level.setBlock(sockets[2], Artifact.KEYS.get(Artifact.World.OVERWORLD).get(races[2]).defaultBlockState(), Block.UPDATE_ALL);
		boolean portal = level.getBlockState(inPortal).is(XelNagaTemple.PORTAL);
		ServerLevel voidLevel = VoidWorld.level(level.getServer());
		BlockPos voidBase = XelNagaTemple.voidBase(Artifact.World.OVERWORLD);
		if (voidLevel != null) voidLevel.setChunkForced(voidBase.getX() >> 4, voidBase.getZ() >> 4, true); // its entities seen there
		level.setChunkForced(centre.getX() >> 4, (centre.getZ() + XelNagaTemple.PORTAL_Z + 6) >> 4, true);
		StarUnit zealot = dev.starmine.ability.TestArena.unit(helper, UnitKind.ZEALOT, 2, 2, UUID.randomUUID());
		zealot.teleportTo(inPortal.getX() + 0.5, inPortal.getY(), inPortal.getZ() + 0.5);
		boolean went = XelNagaTemple.pass(level, inPortal, zealot);
		helper.runAfterDelay(5, () -> {
			Vec3 there = Vec3.atBottomCenterOf(XelNagaTemple.voidBase(Artifact.World.OVERWORLD).relative(XelNagaTemple.voidFacing(Artifact.World.OVERWORLD), 6).above());
			StarUnit arrived = voidLevel == null ? null : voidLevel.getEntitiesOfClass(StarUnit.class, new AABB(BlockPos.containing(there)).inflate(6),
				unit -> unit.getUUID().equals(zealot.getUUID())).stream().findFirst().orElse(null);
			boolean voidTriangle = voidLevel != null && voidLevel.getBlockState(XelNagaTemple.voidBase(Artifact.World.OVERWORLD).above(3)).is(XelNagaTemple.PORTAL);
			XelNagaTemple.RESTING.clear();
			boolean back = arrived != null && XelNagaTemple.pass(voidLevel, XelNagaTemple.voidBase(Artifact.World.OVERWORLD), arrived);
			helper.runAfterDelay(5, () -> {
				boolean home = !level.getEntitiesOfClass(StarUnit.class, new AABB(centre).inflate(30, 10, 30), unit -> unit.getUUID().equals(zealot.getUUID())).isEmpty();
				level.setBlock(sockets[1], Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				boolean shut = !level.getBlockState(inPortal).is(XelNagaTemple.PORTAL);
				level.getEntitiesOfClass(StarUnit.class, new AABB(centre).inflate(40), unit -> unit.getUUID().equals(zealot.getUUID())).forEach(Entity::discard);
				if (voidLevel != null) voidLevel.setChunkForced(voidBase.getX() >> 4, voidBase.getZ() >> 4, false);
				level.setChunkForced(centre.getX() >> 4, (centre.getZ() + XelNagaTemple.PORTAL_Z + 6) >> 4, false);
				restore.run();
				helper.assertTrue(closed && walled, "no door, or no walls by it");
				helper.assertTrue(opened, "the key did not open the door");
				helper.assertTrue(sockets3 && framed, "no sockets or no triangle in the hall");
				helper.assertTrue(shutWithForeign, "another world's part opened the portal");
				helper.assertTrue(portal, "the three parts did not open the portal");
				if (voidLevel != null) { // the test server may run without the Void's dimension
					helper.assertTrue(went, "the portal let no one through");
					helper.assertTrue(arrived != null, "the unit is not by the Void's triangle");
					helper.assertTrue(voidTriangle, "no triangle in the Void");
					helper.assertTrue(back && home, "the unit did not come back through the Void's triangle");
				} else helper.assertFalse(went, "a unit went to a Void that is not there");
				helper.assertTrue(shut, "taking a part off did not close the portal");
				helper.succeed();
			});
		});
	}
}
