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
import net.minecraft.world.level.block.state.BlockState;
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

	/** Fixed radial layout stays {@link RaceBases#MIN_DISTANCE} from its captured origin, at the requested race angles. */
	@GameTest(template = ARENA, batch = "layoutChanges", timeoutTicks = 40)
	public void basesUseSixteenChunkRadialLayout(GameTestHelper helper) {
		BlockPos origin = new BlockPos(120, 64, -240);
		BlockPos zerg = RaceBases.baseOffset(origin, 0, UnitKind.Race.ZERG);
		BlockPos protoss = RaceBases.baseOffset(origin, 0, UnitKind.Race.PROTOSS);
		BlockPos terran = RaceBases.baseOffset(origin, 0, UnitKind.Race.TERRAN);
		int r = RaceBases.MIN_DISTANCE;
		helper.assertTrue(zerg.equals(origin.offset((int) Math.round(-Math.sin(Math.toRadians(60)) * r), 0, (int) Math.round(Math.cos(Math.toRadians(60)) * r))),
			"zerg must be at 60 degrees: " + zerg);
		helper.assertTrue(terran.equals(origin.offset(0, 0, -r)), "terran must be at 180 degrees");
		for (BlockPos site : List.of(zerg, protoss, terran))
			helper.assertTrue(Math.abs(Math.sqrt(site.distSqr(origin)) - r) < 1, "base is not " + r + " blocks away");
		helper.assertTrue(zerg.distSqr(protoss) >= (r - 2) * (r - 2) && protoss.distSqr(terran) >= (r - 2) * (r - 2), "bases too close");
		helper.assertTrue(RaceBases.baseOffset(origin, 90, UnitKind.Race.TERRAN).equals(origin.offset(r, 0, 0)), "heading ignored");
		helper.succeed();
	}

	/** Inspect placed blocks: chests flush deferred plans, so a partial in-memory plan is not evidence. */
	@GameTest(template = ARENA, batch = "layoutChanges", timeoutTicks = 80)
	public void hyperionHasCabinsHangarAndRaisedBridge(GameTestHelper helper) {
		Painter painter = new Painter(helper.getLevel(), RandomSource.create(42));
		BlockPos middle = far(helper, 4000).above(40);
		painter.load(middle, 112);
		SkyShips.Ship ship = SkyShips.hyperion(painter, middle, 0);
		helper.assertTrue(painter.stateAt(middle.offset(0, 2, 0)).isAir(), "central passage blocked");
		helper.assertTrue(painter.stateAt(middle.offset(0, 2, 2)).is(Blocks.LIGHT_BLUE_STAINED_GLASS), "no cabin glazing");
		helper.assertTrue(painter.stateAt(middle.offset(2, 2, 7)).is(Blocks.LIGHT_GRAY_CONCRETE), "no cabin partitions");
		helper.assertTrue(painter.stateAt(middle.offset(0, -5, 8)).isAir(), "hangar blocked");
		helper.assertTrue(painter.stateAt(middle.offset(0, -9, 11)).is(Blocks.YELLOW_CONCRETE), "hangar markings absent");
		helper.assertTrue(painter.stateAt(middle.offset(0, -6, 14)).is(Blocks.LIGHT_BLUE_STAINED_GLASS), "no exterior lower cabin window");
		helper.assertTrue(ship.bossRoom().equals(middle.offset(-24, 17, 0)), "Raynor not on the bridge");
		helper.assertTrue(painter.stateAt(ship.bossRoom().below()).is(Blocks.POLISHED_ANDESITE), "Raynor has no floor");
		helper.assertTrue(painter.stateAt(middle.offset(-11, 19, 0)).is(Blocks.LIGHT_BLUE_STAINED_GLASS), "no bridge window");
		for (int y = -60; y < -12; y++)
			helper.assertTrue(!painter.stateAt(middle.offset(0, y, 0)).is(Blocks.SCAFFOLDING)
				&& !painter.stateAt(middle.offset(0, y, 0)).is(Blocks.LADDER), "external access to ship");
		helper.succeed();
	}

	private static BlockPos far(GameTestHelper helper, int dx) {
		return helper.absolutePos(new BlockPos(0, 0, 0)).offset(dx, 70, 400);
	}

	/** Three pylons (owner 16.10: four were too many) must still power every generated production/defence building after grid snapping. */
	@GameTest(template = ARENA, batch = "structuresBase", timeoutTicks = 400)
	public void protossBaseUsesThreePylonsWithoutLosingPower(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		HiveSavedData data = HiveSavedData.get(level.getServer());
		RaceBases.Report report = RaceBases.build(level, UnitKind.Race.PROTOSS, far(helper, 5400), RandomSource.create(12));
		Painter.finishAll();
		try {
			var members = data.pending.stream().filter(p -> p.hive().equals(report.base()) && p.hall() == null).toList();
			var pylons = members.stream().filter(p -> p.kind() == UnitKind.PYLON).toList();
			helper.assertTrue(pylons.size() == 3, "expected three pylons, got " + pylons.size());
			for (var building : members) if (dev.starmine.race.ProtossRace.needsPower(building.kind()))
				helper.assertTrue(pylons.stream().anyMatch(p -> Math.hypot(p.pos().getX() - building.pos().getX(),
					p.pos().getZ() - building.pos().getZ()) <= dev.starmine.race.ProtossRace.POWER_RADIUS), "no power for " + building.kind());
		} finally {
			data.pending.removeIf(p -> p.hive().equals(report.base()));
			data.remove(report.base());
		}
		helper.succeed();
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
		helper.assertTrue(level.getBlockState(core.above(3)).is(Blocks.END_ROD), "no reactor core");
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

	/**
	 * The sky ships (CLOUD_TASK_2 stage 6): two to two and a half times the old ones (the old Hyperion and
	 * leviathan were about 80 blocks long, the mothership 46 across), two decks, a boss room with the
	 * race's hero, a teleporter pad, no scaffolding, built over ticks by a deferred painter.
	 */
	@GameTest(template = ARENA, batch = "structuresSky", timeoutTicks = 400)
	public void skyShipsAreBigWithDecksABossAndNoLadders(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos base = far(helper, 400).above(60);
		int i = 0;
		for (String name : new String[] {"hyperion", "mothership", "leviathan"}) {
			Painter painter = Painter.deferred(level, RandomSource.create(3 + i));
			BlockPos middle = base.offset(i++ * 260, 0, 0);
			painter.load(middle, 112);
			SkyShips.Ship ship = switch (name) {
				case "hyperion" -> SkyShips.hyperion(painter, middle, 0);
				case "mothership" -> SkyShips.mothership(painter, middle);
				default -> SkyShips.leviathan(painter, middle, 0);
			};
			helper.assertTrue(level.getBlockState(ship.pad()).isAir() || !(level.getBlockState(ship.pad()).getBlock() instanceof Teleporter),
				name + ": built before its ticks");
			painter.start();
			Painter.finishAll();
			int length = name.equals("mothership") ? 100 : 160;
			helper.assertTrue(ship.length() >= length, name + " is " + ship.length() + " blocks long, under " + length);
			helper.assertTrue(painter.placed() > 15000, name + " has only " + painter.placed() + " blocks");
			if (!name.equals("leviathan")) helper.assertTrue(ship.decks() >= 2, name + " has " + ship.decks() + " decks");
			helper.assertTrue(level.getBlockState(ship.pad()).getBlock() instanceof Teleporter, name + ": no teleporter pad at " + ship.pad().toShortString());
			UnitKind hero = name.equals("hyperion") ? UnitKind.RAYNOR : name.equals("mothership") ? UnitKind.ARTANIS : UnitKind.KERRIGAN;
			helper.assertTrue(ship.spawns().stream().anyMatch(spawn -> spawn.kind() == hero && spawn.pos().equals(ship.bossRoom())), name + ": no " + hero.id);
			for (int up = 0; up < 4; up++)
				helper.assertTrue(!level.getBlockState(ship.bossRoom().above(up)).isSolid(), name + ": the boss room is not open at " + ship.bossRoom().above(up).toShortString());
			helper.assertTrue(ship.spawns().size() >= 15 && ship.spawns().stream().anyMatch(spawn -> spawn.kind().flying), name + " has " + ship.spawns().size() + " guards");
			int scaffolds = 0;
			for (BlockPos pos : BlockPos.betweenClosed(middle.offset(-110, -60, -70), middle.offset(110, 40, 70)))
				if (level.getBlockState(pos).is(Blocks.SCAFFOLDING)) scaffolds++;
			helper.assertTrue(scaffolds == 0, name + " has " + scaffolds + " scaffolding blocks");
			if (name.equals("leviathan")) { // owner 16.10: flesh through and through, passages without stairs
				int stairs = 0, flesh = 0;
				for (BlockPos pos : BlockPos.betweenClosed(middle.offset(-110, -60, -70), middle.offset(110, 40, 70))) {
					BlockState state = level.getBlockState(pos);
					if (state.getBlock() instanceof net.minecraft.world.level.block.StairBlock) stairs++;
					if (state.is(dev.starmine.block.StarMineBlocks.ZERG_FLESH) || state.is(dev.starmine.block.StarMineBlocks.GLOWING_ZERG_FLESH)) flesh++;
				}
				helper.assertTrue(stairs == 0, "the leviathan has " + stairs + " stairs");
				helper.assertTrue(flesh > 15000, "the leviathan has only " + flesh + " flesh blocks");
			}
		}
		// the Hyperion's two decks: the upper deck's floor over the lower one, both open
		BlockPos hyperion = base;
		helper.assertTrue(level.getBlockState(hyperion.offset(10, 0, 0)).is(Blocks.POLISHED_ANDESITE), "no upper deck floor");
		helper.assertTrue(level.getBlockState(hyperion.offset(10, -4, 0)).isAir() && level.getBlockState(hyperion.offset(10, 3, 0)).isAir(), "the decks are not open");
		helper.succeed();
	}

	/** A race's teleporter carries a player to its pair only once its guards are dead (CLOUD_TASK_2 stage 7), both ways. */
	@GameTest(template = ARENA, batch = "teleporters", timeoutTicks = 300)
	public void teleportersOpenOnceTheirGuardsAreDead(GameTestHelper helper) {
		dev.starmine.ability.TestArena.clear(helper);
		ServerLevel level = helper.getLevel();
		BlockPos a = helper.absolutePos(new BlockPos(6, 2, 6)), b = helper.absolutePos(new BlockPos(40, 2, 40));
		Painter painter = new Painter(level, RandomSource.create(1));
		Teleporter.pad(painter, a, UnitKind.Race.TERRAN, Blocks.POLISHED_DIORITE.defaultBlockState());
		Teleporter.pad(painter, b, UnitKind.Race.TERRAN, Blocks.POLISHED_DIORITE.defaultBlockState());
		Teleporter.link(level, a, b);
		dev.starmine.entity.StarUnit guard = dev.starmine.ability.TestArena.unit(helper, UnitKind.MARINE, 12, 6, null);
		net.minecraft.world.entity.player.Player player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
		player.moveTo(a.getX() + 0.5, a.getY() + 0.3, a.getZ() + 0.5);
		helper.assertTrue(Teleporter.guards(level, a) == 1, "guards at the pad: " + Teleporter.guards(level, a));
		helper.assertFalse(Teleporter.use(level, a, player), "a guarded pad carried the player");
		guard.discard();
		helper.runAfterDelay(50, () -> {
			helper.assertTrue(Teleporter.use(level, a, player), "the free pad does not carry the player");
			helper.assertTrue(player.blockPosition().distManhattan(b) <= 1, "the player is at " + player.blockPosition().toShortString() + ", not at the pair");
			helper.runAfterDelay(Teleporter.REST + 5, () -> {
				helper.assertTrue(Teleporter.use(level, b, player), "the way back does not work");
				helper.assertTrue(player.blockPosition().distManhattan(a) <= 1, "back at " + player.blockPosition().toShortString());
				helper.succeed();
			});
		});
	}

	/** Wild packs (owner 16.10): each kind keeps its race's units (and the zerg their eggs) in its heart till a player comes. */
	@GameTest(template = ARENA, batch = "wildPacks", timeoutTicks = 200)
	public void wildPacksKeepTheirUnits(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		int i = 0;
		for (WildPacks.Kind kind : WildPacks.Kind.values()) {
			BlockPos at = helper.absolutePos(BlockPos.ZERO).offset(2000 + i++ * 60, 0, 600);
			Painter painter = new Painter(level, RandomSource.create(11));
			painter.load(at, 24);
			int top = level.getMinBuildHeight() + 64;
			for (BlockPos pos : BlockPos.betweenClosed(at.offset(-12, 0, -12).atY(top - 3), at.offset(12, 0, 12).atY(top - 1)))
				level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
			BlockPos heartAt = WildPacks.build(level, RandomSource.create(3 + i), kind, at.atY(top));
			helper.assertTrue(heartAt != null && level.getBlockEntity(heartAt) instanceof DungeonHeart.Heart, kind + ": no heart");
			List<UnitKind> kinds = ((DungeonHeart.Heart) level.getBlockEntity(heartAt)).guards().stream().map(DungeonHeart.Guard::kind).toList();
			UnitKind.Race race = kind == WildPacks.Kind.ZERG ? UnitKind.Race.ZERG : kind == WildPacks.Kind.TERRAN ? UnitKind.Race.TERRAN : UnitKind.Race.PROTOSS;
			helper.assertTrue(!kinds.isEmpty() && kinds.stream().allMatch(k -> k.race == race), kind + " keeps " + kinds);
			if (kind == WildPacks.Kind.ZERG) helper.assertTrue(kinds.contains(UnitKind.EGG) && kinds.contains(UnitKind.ZERGLING), "the zerg keep " + kinds);
			if (kind == WildPacks.Kind.DARK_TEMPLAR) helper.assertTrue(kinds.stream().allMatch(k -> k == UnitKind.DARK_TEMPLAR), "dark templars: " + kinds);
			if (kind == WildPacks.Kind.TERRAN) helper.assertTrue(level.getBlockState(heartAt.above()).is(Blocks.CAMPFIRE), "no campfire");
			// owner 16.10: a camp shows it is the terrans', every pack has its line infantry
			if (kind == WildPacks.Kind.TERRAN) helper.assertTrue(kinds.contains(UnitKind.MARINE)
				&& level.getBlockState(heartAt.above().offset(4, 0, -1)).is(Blocks.BLAST_FURNACE), "a terran camp without a marine or its machines: " + kinds);
			if (kind == WildPacks.Kind.PROTOSS) helper.assertTrue(kinds.contains(UnitKind.ZEALOT), "no zealot by the pylon: " + kinds);
		}
		helper.succeed();
	}

	/**
	 * Small dungeons (owner 15.10): each of the three is built in a block of stone, keeps its guards in its heart
	 * until a player comes, then they appear — zerg eggs, zerglings and a roach; marines; zealots or dark templars.
	 */
	@GameTest(template = ARENA, batch = "smallDungeons", timeoutTicks = 200)
	public void smallDungeonsKeepTheirGuards(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		int i = 0;
		for (SmallDungeons.Kind kind : SmallDungeons.Kind.values()) {
			BlockPos at = helper.absolutePos(BlockPos.ZERO).offset(2000 + i++ * 80, 0, 400);
			Painter painter = new Painter(level, RandomSource.create(11));
			painter.load(at, 40);
			int top = level.getMinBuildHeight() + 64;
			for (BlockPos pos : BlockPos.betweenClosed(at.offset(-24, 0, -24).atY(level.getMinBuildHeight() + 5), at.offset(24, 0, 24).atY(top - 1)))
				level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
			BlockPos heartAt = SmallDungeons.build(level, RandomSource.create(5 + i), kind, at.getX(), at.getZ(), top);
			helper.assertTrue(heartAt != null && level.getBlockEntity(heartAt) instanceof DungeonHeart.Heart, kind + ": no heart");
			DungeonHeart.Heart heart = (DungeonHeart.Heart) level.getBlockEntity(heartAt);
			List<UnitKind> kinds = heart.guards().stream().map(DungeonHeart.Guard::kind).toList();
			if (kind == SmallDungeons.Kind.TERRAN_OUTPOST)
				for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(at.getX() - 11, top - 1, at.getZ() - 11),
					new BlockPos(at.getX() + 11, top + 12, at.getZ() + 11)))
					helper.assertTrue(!level.getBlockState(pos).is(Blocks.IRON_BLOCK) && !level.getBlockState(pos).is(Blocks.GOLD_BLOCK),
						"outpost contains resource blocks");
			switch (kind) {
				case ZERG_NEST -> helper.assertTrue(kinds.contains(UnitKind.EGG) && kinds.contains(UnitKind.ZERGLING), "the nest keeps " + kinds);
				case TERRAN_OUTPOST -> helper.assertTrue(kinds.stream().filter(k -> k == UnitKind.MARINE).count() >= 1, "the outpost keeps " + kinds);
				case XELNAGA_TEMPLE -> helper.assertTrue(kinds.stream().allMatch(k -> k == UnitKind.ZEALOT || k == UnitKind.DARK_TEMPLAR) && !kinds.isEmpty(),
					"the temple keeps " + kinds);
			}
			var made = heart.wake(level);
			helper.assertTrue(made.size() == kinds.size(), kind + ": " + made.size() + " of " + kinds.size() + " guards appeared");
			helper.assertTrue(!(level.getBlockState(heartAt).getBlock() instanceof DungeonHeart), kind + ": the heart is still there");
			for (var unit : made) unit.discard(); // other tests share the world
		}
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "structuresBase", timeoutTicks = 400)
	public void terranBaseIsWild(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		HiveSavedData data = HiveSavedData.get(level.getServer());
		BlockPos site = far(helper, 700).below(70);
		RaceBases.Report report = RaceBases.build(level, UnitKind.Race.TERRAN, site, RandomSource.create(5));
		Painter.finishAll(); // the Hyperion is put over ticks: its guards wait for it
		Hive base = data.get(report.base());
		try {
			helper.assertTrue(base != null && base.owner == null && base.race == UnitKind.Race.TERRAN, "no wild terran base");
			helper.assertTrue(data.pending.stream().anyMatch(p -> p.hive().equals(base.id) && p.kind() == UnitKind.COMMAND_CENTER && p.hall() == null),
				"no command center waiting");
			helper.assertTrue(data.pending.stream().anyMatch(p -> p.hive().equals(base.id) && p.kind() == UnitKind.VIKING), "no Hyperion guards");
			helper.assertTrue(report.blocks() > 5000, "only " + report.blocks() + " blocks");
			// the portal hall under the command center (owner 16.10): portal blocks in their frame, off while guarded
			BlockPos hall = new BlockPos(base.hatchery.getX(), Math.max(level.getMinBuildHeight() + 8, base.hatchery.getY() - PortalHall.DEPTH),
				base.hatchery.getZ());
			helper.assertTrue(level.getBlockState(hall).getBlock() instanceof Teleporter, "no portal under the base at " + hall.toShortString());
			helper.assertTrue(level.getBlockState(hall.offset(2, 0, 0)).is(Teleporter.FRAMES.get(UnitKind.Race.TERRAN)), "no portal frame");
			helper.assertTrue(level.getBlockEntity(hall) instanceof Teleporter.Pad pad && pad.pair() != null, "the portal leads nowhere");
			helper.assertTrue(data.pending.stream().filter(p -> p.hive().equals(base.id) && p.hall() != null
				&& p.hall().equals(hall)).count() >= 4, "the portal has no guards");
		} finally { // other tests share the world: no waiting units, no base left behind
			data.pending.removeIf(p -> base != null && p.hive().equals(base.id));
			if (base != null) data.remove(base.id);
		}
		helper.succeed();
	}
}
