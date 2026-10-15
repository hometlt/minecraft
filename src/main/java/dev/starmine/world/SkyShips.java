package dev.starmine.world;

import dev.starmine.creep.CreepGrowth;
import dev.starmine.entity.UnitKind;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;

/**
 * Ships of block hanging in the sky over generated race bases (owner 14.10; CLOUD_TASK_2 stage 6: two to
 * two and a half times larger, decks, a boss room, no scaffolding): the terran battlecruiser Hyperion,
 * the protoss mothership and a zerg leviathan. They do not fly — they are buildings in the air. One
 * comes up by its race's teleporter ({@link Teleporter}: its pad on the ship is {@link Ship#pad}), goes in
 * by a hatch with stairs, down from deck to deck by stairs, and finds the race's hero in the boss room
 * ({@link Ship#bossRoom}: Raynor on the Hyperion's bridge, Artanis under the mothership's dome, Kerrigan
 * in the leviathan's head). Guards of the race keep the pad, every deck and hall, and fly about the ship.
 *
 * Each is drawn in its own local frame (x along the length, bow at +x; y up) turned by quarter turns,
 * from shapes scaled by {@link #HYPERION_SCALE} and the like. The painter should be
 * {@link Painter#deferred}: a ship is up to ~90 000 blocks, put over a few dozen ticks.
 */
public final class SkyShips {
	public static final double HYPERION_SCALE = 2.4, MOTHERSHIP_SCALE = 2.4, LEVIATHAN_SCALE = 2.2;

	private SkyShips() {
	}

	/** What a ship is: who it wants spawned, its teleporter pad (the cell of the pad block), its boss room's floor middle, its decks. */
	public record Ship(List<Spawn> spawns, BlockPos pad, BlockPos bossRoom, int decks, int length) {
	}

	/** A ship's local frame: `origin` is its middle; x is turned by `turn` quarter turns about y. */
	private record Frame(Painter painter, BlockPos origin, int turn) {
		BlockPos at(int x, int y, int z) {
			return switch (turn & 3) {
				case 0 -> origin.offset(x, y, z);
				case 1 -> origin.offset(-z, y, x);
				case 2 -> origin.offset(-x, y, -z);
				default -> origin.offset(z, y, -x);
			};
		}

		void put(int x, int y, int z, BlockState state) {
			painter.set(at(x, y, z), state);
		}

		void box(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
			for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
				for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
					for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) put(x, y, z, state);
		}

		/** A local direction (+x is the bow) in the world. */
		Direction facing(Direction local) {
			Direction out = local;
			for (int i = 0; i < (turn & 3); i++) out = out.getClockWise();
			return out;
		}

		/** Stairs going up toward local `up` (a flight), `width` wide across from z0. */
		void stairs(int x0, int y0, int z0, int steps, int width, Direction up, BlockState block, BlockState stair) {
			BlockState step = stair.setValue(StairBlock.FACING, facing(up));
			for (int i = 0; i < steps; i++)
				for (int w = 0; w < width; w++) {
					int x = x0 + up.getStepX() * i + (up.getAxis() == Direction.Axis.Z ? w : 0);
					int z = z0 + up.getStepZ() * i + (up.getAxis() == Direction.Axis.X ? w : 0);
					put(x, y0 + i, z, step);
					for (int below = 1; below <= 1 && i > 0; below++) put(x, y0 + i - below, z, block);
					for (int head = 1; head <= 4; head++) put(x, y0 + i + head, z, Blocks.AIR.defaultBlockState());
				}
		}
	}

	/** Cells of a shape in a box of a local frame, kept so its shell (cells with a neighbor outside) is found once. */
	private static final class Shape {
		final int x0, y0, z0, sx, sy, sz;
		final boolean[] in;

		Shape(int x0, int x1, int y0, int y1, int z0, int z1, Inside inside) {
			this.x0 = x0;
			this.y0 = y0;
			this.z0 = z0;
			sx = x1 - x0 + 1;
			sy = y1 - y0 + 1;
			sz = z1 - z0 + 1;
			in = new boolean[sx * sy * sz];
			for (int x = 0; x < sx; x++)
				for (int y = 0; y < sy; y++)
					for (int z = 0; z < sz; z++) in[(x * sy + y) * sz + z] = inside.test(x + x0, y + y0, z + z0);
		}

		boolean in(int x, int y, int z) {
			int i = x - x0, j = y - y0, k = z - z0;
			return i >= 0 && j >= 0 && k >= 0 && i < sx && j < sy && k < sz && in[(i * sy + j) * sz + k];
		}

		boolean shell(int x, int y, int z) {
			return in(x, y, z) && !(in(x + 1, y, z) && in(x - 1, y, z) && in(x, y + 1, z) && in(x, y - 1, z) && in(x, y, z + 1) && in(x, y, z - 1));
		}

		/** The highest cell of the shape in a column, or Integer.MIN_VALUE. */
		int top(int x, int z) {
			for (int y = y0 + sy - 1; y >= y0; y--) if (in(x, y, z)) return y;
			return Integer.MIN_VALUE;
		}

		int bottom(int x, int z) {
			for (int y = y0; y < y0 + sy; y++) if (in(x, y, z)) return y;
			return Integer.MAX_VALUE;
		}
	}

	private interface Inside {
		boolean test(int x, int y, int z);
	}

	// ---- Hyperion ----

	/** The Hyperion's hull in its old (unscaled) units: X along, Y up, Z across. */
	private static boolean hyperionHull(double x, double y, double z) {
		double az = Math.abs(z);
		if (x >= -28 && x <= 24 && y >= -4 && y <= 3.4 && az <= 6.4 - Math.max(0, -2 - y) - Math.max(0, y - 2)) return true; // main hull
		if (x > 24 && x <= 36 && y >= -3 && y <= 2.4 && az <= 9.4 - Math.max(0, x - 32) * 2
			&& !(x > 30.5 && az <= 2 && y >= -1 && y <= 1)) return true; // the hammerhead prow, cut for the Yamato cannon
		if (x >= -36 && x < -28 && y >= -3 && y <= 2.4 && az <= 8.4) return true; // rear engine block
		for (int side = -1; side <= 1; side += 2) // engine nacelles
			if (x >= -40 && x < -36 && Painter.sq(y + 0.5) + Painter.sq(z - side * 5) <= 6.5) return true;
		if (x >= -16 && x <= -4 && y >= 3 && y <= 10 && az <= (y > 8 ? 2.4 : 3.4)) return true; // the bridge tower
		if (x >= -4 && x <= 20 && y >= 3 && y <= 4.4 && az <= 1.4) return true; // dorsal ridge
		// wings swept back from the hull's flanks, each ending in a great engine
		if (x >= -26 && x <= -8 && y >= -1.4 && y <= 0.4 && az >= 6 && az <= 6 + 10 * (1 - Math.max(0, (x + 8) / -18.0) * 0.3)
			&& az <= 6 + (x + 30) * 0.9) return true;
		for (int side = -1; side <= 1; side += 2)
			if (x >= -32 && x <= -14 && Painter.sq(y + 0.5) + Painter.sq(z - side * 17) <= Painter.sq(x < -29 ? 2.6 : 2.2)) return true;
		return false;
	}

	/**
	 * The terran battlecruiser Hyperion (about 185 blocks long, 2.4 times the old one): grey hull with a
	 * light stripe and a band of windows, a dark belly, a hammerhead prow with a glowing Yamato muzzle,
	 * swept wings ending in great blue engines, laser turrets, a bridge tower. Inside: two decks the whole
	 * length (stairs between them fore and aft), bulkheads with doors, a cargo bay with chests, and the
	 * bridge — the boss room, Raynor's — high under the tower. Its teleporter pad is on the forward deck.
	 */
	public static Ship hyperion(Painter painter, BlockPos middle, int turn) {
		Frame frame = new Frame(painter, middle, turn);
		double s = HYPERION_SCALE;
		Shape hull = new Shape((int) (-41 * s), (int) (37 * s), (int) (-5 * s), (int) (11 * s), (int) (-20 * s), (int) (20 * s),
			(x, y, z) -> hyperionHull(x / s, y / s, z / s));
		BlockState shell = Blocks.GRAY_CONCRETE.defaultBlockState(), stripe = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(),
			belly = Blocks.POLISHED_DEEPSLATE.defaultBlockState(), trim = Blocks.POLISHED_DIORITE.defaultBlockState(),
			glass = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState(), light = Blocks.SEA_LANTERN.defaultBlockState(),
			floor = Blocks.POLISHED_ANDESITE.defaultBlockState(), wall = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(),
			engine = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
		int lowerFloor = -9, deck = 0, top = (int) Math.floor(3.4 * s); // the main hull's interior: the lower deck from -9, the upper from 0
		for (int x = hull.x0; x < hull.x0 + hull.sx; x++)
			for (int y = hull.y0; y < hull.y0 + hull.sy; y++)
				for (int z = hull.z0; z < hull.z0 + hull.sz; z++) {
					if (!hull.in(x, y, z)) continue;
					double X = x / s, Y = y / s, Z = Math.abs(z / s);
					boolean outside = hull.shell(x, y, z);
					BlockState state;
					if (!outside) state = y == deck && X > -28 && X < 24 ? floor : air; // the upper deck's floor over the lower deck
					else if (X < -36 || X <= -29 && Z > 14) state = X < -39 || X < -31 && Z > 14 ? light : trim; // engines' glowing rears
					else if (Z > 14) state = Painter.sq(Y + 0.5) + Painter.sq(Z - 17) > 4 ? engine : trim; // wing engines: blue glass cowls
					else if (Y >= 3 && X >= -16 && X <= -4 && y == (int) (8 * s)) state = glass; // the bridge's windows
					else if (y == 3 && Z >= 5.5 && X > -26 && X < 22 && Math.floorMod(x, 4) != 0) state = glass; // the upper deck's window band
					else if (y == -6 && Z >= 4.5 && X > -26 && X < 22 && Math.floorMod(x + 1, 4) != 0) state = glass; // lower cabin windows
					else if (y == -2 && Z >= 5) state = stripe;
					else if (Y <= -3) state = belly;
					else if (Z > 6 && Z <= 16 && Y > -1.5 && Y < 0.5) state = Math.floorMod(x + z, 9) == 0 ? trim : stripe; // wings
					else state = shell;
					frame.put(x, y, z, state);
				}
		// bulkheads with doors across both decks, the bridge's walls
		int[] bulkheads = {70, 30, -9, -39};
		for (int bx : bulkheads)
			for (int y = lowerFloor + 1; y < top; y++)
				for (int z = -16; z <= 16; z++) {
					if (!hull.in(bx, y, z) || hull.shell(bx, y, z) || y == deck) continue;
					int floorY = y < deck ? lowerFloor : deck;
					boolean door = Math.abs(z) <= 3 && y - floorY <= 5;
					frame.put(bx, y, z, door ? air : wall);
				}
		// Most of the two decks are cabins along a three-block passage; only cargo/hangar stay wide.
		for (int floorY : new int[] {lowerFloor, deck}) {
			int ceilingY = floorY == lowerFloor ? deck - 1 : top - 1;
			for (int side : new int[] {-1, 1}) {
				for (int x = -46; x <= 29; x++) {
					int door = Math.floorMod(x + 46, 12);
					for (int y = floorY + 1; y <= ceilingY; y++) {
						if (door >= 4 && door <= 5 && y <= floorY + 3) continue;
						frame.put(x, y, side * 2, y >= floorY + 2 && y <= floorY + 3 ? glass : wall);
					}
				}
				for (int x = -46; x <= 29; x += 12) {
					for (int y = floorY + 1; y <= ceilingY; y++)
						for (int az = 3; az <= 14; az++)
							if (hull.in(x, y, side * az) && !hull.shell(x, y, side * az)) frame.put(x, y, side * az, wall);
					frame.put(x + 3, floorY + 1, side * 11, Blocks.WHITE_WOOL.defaultBlockState());
					frame.put(x + 4, floorY + 1, side * 11, Blocks.WHITE_WOOL.defaultBlockState());
					frame.put(x + 3, floorY + 1, side * 7, Blocks.SPRUCE_SLAB.defaultBlockState());
				}
			}
		}
		// A single broad lower hangar amidships, with a glazed viewing gallery and a port-side aperture.
		frame.box(-8, lowerFloor + 1, -12, 26, deck - 1, 12, air);
		for (int x = -8; x <= 26; x++) {
			frame.put(x, lowerFloor, -11, Blocks.YELLOW_CONCRETE.defaultBlockState());
			frame.put(x, lowerFloor, 11, Blocks.YELLOW_CONCRETE.defaultBlockState());
		}
		frame.box(2, lowerFloor + 1, -16, 18, -3, -13, air);
		// Raynor's real bridge is in the tower, not another room on the main deck.
		int bridgeFloor = 16;
		frame.box(-37, bridgeFloor, -6, -11, bridgeFloor, 6, floor);
		frame.box(-36, bridgeFloor + 1, -5, -12, 22, 5, air);
		frame.box(-37, bridgeFloor + 2, -7, -11, bridgeFloor + 4, -7, glass);
		frame.box(-37, bridgeFloor + 2, 7, -11, bridgeFloor + 4, 7, glass);
		frame.box(-11, bridgeFloor + 2, -6, -11, bridgeFloor + 4, 6, glass);
		frame.box(-19, bridgeFloor + 1, -4, -18, bridgeFloor + 1, 4, Blocks.CYAN_TERRACOTTA.defaultBlockState());
		frame.put(-25, bridgeFloor + 1, 0, Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, frame.facing(Direction.EAST)));
		frame.box(-43, deck + 1, 2, -39, deck + 3, 6, air);
		frame.box(-39, deck + 1, 4, -13, bridgeFloor + 5, 6, air);
		frame.stairs(-38, deck + 1, 4, bridgeFloor, 3, Direction.EAST, floor, Blocks.POLISHED_ANDESITE_STAIRS.defaultBlockState());
		// lights under each deck's ceiling
		for (int x = (int) (-27 * s); x <= (int) (23 * s); x += 8) {
			frame.put(x, deck, 0, light);
			if (hull.in(x, top, 0)) frame.put(x, top, 0, light);
		}
		// stairs: the hatch from the forward deck down to the upper deck, upper to lower deck fore and aft
		int hatch = 50;
		frame.box(hatch - 1, deck + 1, -2, hatch + top + 1, top + 1, 2, air);
		frame.stairs(hatch + top, deck + 1, -2, top, 5, Direction.WEST, floor, Blocks.POLISHED_ANDESITE_STAIRS.defaultBlockState());
		for (int x = hatch - 1; x <= hatch + top + 1; x++) frame.put(x, top, -3, trim);
		frame.box(36, deck, 7, 46, deck, 10, air);
		frame.stairs(46, lowerFloor + 1, 7, deck - lowerFloor, 4, Direction.WEST, floor, Blocks.POLISHED_ANDESITE_STAIRS.defaultBlockState());
		frame.box(-58, deck, -10, -48, deck, -7, air);
		frame.stairs(-48, lowerFloor + 1, -10, deck - lowerFloor, 4, Direction.WEST, floor, Blocks.POLISHED_ANDESITE_STAIRS.defaultBlockState());
		// the Yamato muzzle
		int muzzle = (int) (30.5 * s);
		for (int y = -3; y <= 3; y++)
			for (int z = -4; z <= 4; z++) frame.put(muzzle, y, z, Math.abs(z) <= 2 && Math.abs(y) <= 2 ? Blocks.RED_CONCRETE.defaultBlockState() : trim);
		frame.box(muzzle, -1, -1, muzzle, 1, 1, light);
		// laser turrets on the deck, antennae on the bridge
		for (int x : new int[] {-50, 0, 28}) {
			int z = x == 0 ? 9 : -9, onTop = hull.top(x, z) + 1;
			frame.box(x - 2, onTop, z - 2, x + 2, onTop + 1, z + 2, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
			for (int dx = 3; dx <= 7; dx++) frame.put(x + dx, onTop + 1, z, Blocks.IRON_BARS.defaultBlockState());
		}
		int bridgeTop = hull.top(-24, 0) + 1;
		frame.box(-24, bridgeTop, 0, -24, bridgeTop + 4, 0, Blocks.END_ROD.defaultBlockState());
		frame.box(-28, bridgeTop, 2, -28, bridgeTop + 2, 2, Blocks.END_ROD.defaultBlockState());
		// the cargo bay's chests, the bridge's (the boss's reward is its own drop)
		painter.chest(frame.at(-64, lowerFloor + 1, 8), BuiltInLootTables.STRONGHOLD_CORRIDOR);
		painter.chest(frame.at(-64, lowerFloor + 1, -8), BuiltInLootTables.SIMPLE_DUNGEON);
		painter.chest(frame.at(-36, deck + 1, 6), BuiltInLootTables.STRONGHOLD_LIBRARY);
		// the teleporter pad on the forward deck
		int padX = 64, padTop = hull.top(padX, 0);
		BlockPos pad = frame.at(padX, padTop + 1, 0);
		Teleporter.pad(painter, pad, UnitKind.Race.TERRAN, Blocks.POLISHED_DIORITE.defaultBlockState());

		List<Spawn> spawns = new ArrayList<>();
		for (int i = 0; i < 4; i++) // the pad's guards
			spawns.add(Spawn.guard(i % 2 == 0 ? UnitKind.MARINE : UnitKind.MARAUDER, frame.at(padX - 6 + (i % 2) * 12, padTop + 1, i < 2 ? -6 : 6), pad));
		int[] halls = {56, 10, -60};
		for (int hx : halls) {
			BlockPos lower = frame.at(hx, lowerFloor + 1, 0), upper = frame.at(hx, deck + 1, 0);
			spawns.add(Spawn.guard(UnitKind.MARINE, frame.at(hx + 3, lowerFloor + 1, 4), lower));
			spawns.add(Spawn.guard(hx == 10 ? UnitKind.GHOST : UnitKind.MARINE, frame.at(hx - 3, lowerFloor + 1, -4), lower));
			spawns.add(Spawn.guard(UnitKind.MARAUDER, frame.at(hx, deck + 1, 5), upper));
		}
		BlockPos boss = frame.at(-24, bridgeFloor + 1, 0);
		spawns.add(Spawn.guard(UnitKind.RAYNOR, boss, boss));
		spawns.add(Spawn.guard(UnitKind.MARINE, frame.at(-30, bridgeFloor + 1, 4), boss));
		spawns.add(Spawn.guard(UnitKind.MARINE, frame.at(-30, bridgeFloor + 1, -4), boss));
		int above = (int) (14 * s);
		spawns.add(Spawn.guard(UnitKind.VIKING, frame.at(20, above, 30), pad));
		spawns.add(Spawn.guard(UnitKind.VIKING, frame.at(-40, above, -30), pad));
		spawns.add(Spawn.guard(UnitKind.BANSHEE, frame.at(-10, above, 34), pad));
		return new Ship(spawns, pad, boss, 2, hull.sx);
	}

	// ---- Mothership ----

	/**
	 * The protoss mothership (a disc 72 blocks across in a gold ring 120 across): quartz hull with gold
	 * bands, blue windows round the rim, a great glass dome, six arms out to the ring, a dark vortex below
	 * that runs down into a long tail of crying obsidian and amethyst with spires around it. Inside the
	 * disc: two levels joined by stairs; under the dome the boss room, Artanis's, its nexus core a
	 * column of light. Its teleporter pad is on the top of the disc.
	 */
	public static Ship mothership(Painter painter, BlockPos middle) {
		Frame frame = new Frame(painter, middle, 0);
		double s = MOTHERSHIP_SCALE, rx = 15 * s, ry = 3.5 * s;
		BlockState quartz = Blocks.QUARTZ_BRICKS.defaultBlockState(), smooth = Blocks.SMOOTH_QUARTZ.defaultBlockState(),
			gold = Blocks.YELLOW_TERRACOTTA.defaultBlockState(), light = Blocks.SEA_LANTERN.defaultBlockState(),
			window = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState(), air = Blocks.AIR.defaultBlockState(),
			floor = Blocks.CHISELED_QUARTZ_BLOCK.defaultBlockState(), lapis = Blocks.BLUE_TERRACOTTA.defaultBlockState();
		int r0 = (int) Math.ceil(rx), h0 = (int) Math.ceil(ry), mid = -1;
		double dome = 7 * s, domeH = 6 * s;
		Shape disc = new Shape(-r0, r0, -h0, h0 + (int) domeH + 4, -r0, r0, (x, y, z) -> {
			double r = Math.sqrt(x * x + z * z);
			if (Painter.sq(r / rx) + Painter.sq(y / ry) <= 1) return true;
			return y >= 0 && Painter.sq(x / dome) + Painter.sq((y - 2) / domeH) + Painter.sq(z / dome) <= 1;
		});
		for (int x = -r0; x <= r0; x++)
			for (int y = disc.y0; y < disc.y0 + disc.sy; y++)
				for (int z = -r0; z <= r0; z++) {
					if (!disc.in(x, y, z)) continue;
					double r = Math.sqrt(x * x + z * z);
					boolean domeCell = y > 0 && Painter.sq(r / rx) + Painter.sq(y / ry) > 1;
					BlockState state;
					if (!disc.shell(x, y, z)) state = y == mid && r > 3 ? floor : air; // the upper level's floor over the lower one
					else if (domeCell) state = window;
					else if (y >= 1 && Math.abs(r - 28.5) < 1.2) state = gold;
					else if (Math.abs(y) <= 1 && r > 31 && Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(z, x))), 20) < 6) state = window;
					else if (y < 0 && Math.abs(r - 20) < 0.8) state = lapis;
					else state = y < 0 ? smooth : quartz;
					frame.put(x, y, z, state);
				}
		// the dome's floor is the boss room's: a ring wall on the upper level marks it, with doors east and west
		int room = (int) dome;
		for (int a = 0; a < 360; a += 2) {
			double rad = Math.toRadians(a);
			int x = (int) Math.round(Math.cos(rad) * room), z = (int) Math.round(Math.sin(rad) * room);
			boolean door = a < 12 || a > 348 || Math.abs(a - 180) < 12;
			for (int y = mid + 1; y <= mid + 6; y++) if (!door && disc.in(x, y, z) && !disc.shell(x, y, z)) frame.put(x, y, z, quartz);
		}
		for (int y = mid + 1; y <= (int) (8 * s); y++) frame.put(0, y, 0, y == (int) (8 * s) ? Blocks.END_ROD.defaultBlockState() : light); // the nexus core
		frame.box(-2, mid + 1, -2, 2, mid + 1, 2, gold);
		// stairs: the hatch from the top down to the upper level, the upper level to the lower one
		int hatch = 22, topY = disc.top(hatch, 0);
		frame.box(hatch - 1, mid + 1, -2, hatch + topY - mid + 1, topY, 2, air);
		frame.stairs(hatch, mid + 1, -2, topY - mid, 5, Direction.EAST, smooth, Blocks.QUARTZ_STAIRS.defaultBlockState());
		int lower = disc.bottom(0, -14) + 1;
		frame.box(-14, mid, -20, -6, mid, -16, air);
		frame.stairs(-6, lower, -20, mid - lower + 1, 5, Direction.WEST, smooth, Blocks.QUARTZ_STAIRS.defaultBlockState());
		// the outer ring and six arms
		double ring = 21 * s, tube = 1.6 * s;
		for (int a = 0; a < 360 * 4; a++) {
			double rad = Math.toRadians(a / 4.0);
			for (double t = -tube; t <= tube; t += 0.5)
				for (double v = -tube; v <= tube; v += 0.5) {
					if (Painter.sq(t / tube) + Painter.sq(v / (tube * 0.85)) > 1) continue;
					int x = (int) Math.round(Math.cos(rad) * (ring + t)), z = (int) Math.round(Math.sin(rad) * (ring + t)), y = (int) Math.round(v);
					frame.put(x, y, z, a % 60 == 0 && y == 0 ? light : gold);
				}
		}
		for (int arm = 0; arm < 6; arm++) {
			double rad = Math.toRadians(arm * 60 + 30);
			for (double r = rx - 1; r <= ring - tube + 1; r += 0.5)
				for (double w = -2.5; w <= 2.5; w += 0.5) {
					int x = (int) Math.round(Math.cos(rad) * r - Math.sin(rad) * w), z = (int) Math.round(Math.sin(rad) * r + Math.cos(rad) * w);
					frame.put(x, 0, z, smooth);
					frame.put(x, -1, z, smooth);
					if (Math.abs(w) < 0.6) frame.put(x, 1, z, Math.floorMod((int) r, 6) == 0 ? light : gold);
				}
		}
		// the vortex below, running down into a long tail with spires around it
		int vortex = (int) (10 * s), tail = vortex + 26;
		for (int depth = 1; depth <= tail; depth++) {
			double radius = depth <= vortex ? 6.5 * s * (1 - depth / (vortex + 4.0)) : 3.5 * (1 - (depth - vortex) / (double) (tail - vortex + 2)) + 0.6;
			int r = (int) Math.ceil(radius);
			for (int x = -r; x <= r; x++)
				for (int z = -r; z <= r; z++)
					if (x * x + z * z <= radius * radius)
						frame.put(x, (int) -ry - depth + 2, z, x * x + z * z <= Math.max(1, radius * radius / 4) ? Blocks.AMETHYST_BLOCK.defaultBlockState()
							: Blocks.CRYING_OBSIDIAN.defaultBlockState());
		}
		frame.put(0, (int) -ry - tail + 1, 0, Blocks.END_ROD.defaultBlockState());
		for (int spire = 0; spire < 6; spire++) {
			double rad = Math.toRadians(spire * 60);
			int x = (int) Math.round(Math.cos(rad) * 13), z = (int) Math.round(Math.sin(rad) * 13), from = disc.bottom(x, z) - 1;
			for (int d = 0; d < 10 + spire % 3 * 3; d++) frame.put(x, from - d, z, d % 4 == 3 ? light : Blocks.PURPUR_PILLAR.defaultBlockState());
		}
		painter.chest(frame.at(16, lower, 6), BuiltInLootTables.END_CITY_TREASURE);
		painter.chest(frame.at(-16, lower, 6), BuiltInLootTables.DESERT_PYRAMID);
		painter.chest(frame.at(4, mid + 1, 4), BuiltInLootTables.END_CITY_TREASURE);
		// the teleporter pad on the top of the disc
		int padX = -24, padTop = disc.top(padX, 0);
		BlockPos pad = frame.at(padX, padTop + 1, 0);
		Teleporter.pad(painter, pad, UnitKind.Race.PROTOSS, gold);

		List<Spawn> spawns = new ArrayList<>();
		for (int i = 0; i < 4; i++)
			spawns.add(Spawn.guard(i % 2 == 0 ? UnitKind.ZEALOT : UnitKind.STALKER, frame.at(padX + (i % 2 == 0 ? -5 : 5), padTop + 1, i < 2 ? -5 : 5), pad));
		BlockPos upper = frame.at(18, mid + 1, 0), down = frame.at(0, lower, 18);
		for (int i = 0; i < 4; i++) {
			double rad = Math.toRadians(i * 90 + 45);
			int x = (int) Math.round(Math.cos(rad) * 18), z = (int) Math.round(Math.sin(rad) * 18);
			spawns.add(Spawn.guard(i % 2 == 0 ? UnitKind.STALKER : UnitKind.ADEPT, frame.at(x, mid + 1, z), upper));
			spawns.add(Spawn.guard(i == 0 ? UnitKind.HIGH_TEMPLAR : i == 1 ? UnitKind.DARK_TEMPLAR : UnitKind.ZEALOT, frame.at(x, lower, z), down));
		}
		BlockPos boss = frame.at(4, mid + 1, 0);
		spawns.add(Spawn.guard(UnitKind.ARTANIS, boss, boss));
		spawns.add(Spawn.guard(UnitKind.ZEALOT, frame.at(-4, mid + 1, 4), boss));
		spawns.add(Spawn.guard(UnitKind.ZEALOT, frame.at(-4, mid + 1, -4), boss));
		spawns.add(Spawn.guard(UnitKind.PHOENIX, frame.at(44, 20, 0), pad));
		spawns.add(Spawn.guard(UnitKind.PHOENIX, frame.at(-44, 20, 0), pad));
		spawns.add(Spawn.guard(UnitKind.VOID_RAY, frame.at(0, 24, 44), pad));
		return new Ship(spawns, pad, boss, 2, (int) (2 * (ring + tube)));
	}

	// ---- Leviathan ----

	/** The leviathan's body at old (unscaled) x: centre (y, z) and radii (ry, rz); null outside its length. */
	private static double[] leviathan(double x) {
		if (x < -42 || x > 42) return null;
		double s = (x + 42) / 84.0; // 0 = tail, 1 = head
		double radius = s > 0.82 ? 9 - (s - 0.82) * 30 : 1.5 + 10 * Math.pow(Math.sin(Math.PI * Math.min(1, s / 0.82) * 0.62), 1.2);
		double y = 3 * Math.sin(Math.PI * s * 1.6), z = 4 * Math.sin(Math.PI * 2 * s);
		return new double[] {y, z, Math.max(1.2, radius * 0.8), Math.max(1.2, radius)};
	}

	/** The body (scaled) at local x: centre y, z and radii. */
	private static double[] section(int x) {
		double s = LEVIATHAN_SCALE;
		double[] old = leviathan(x / s);
		return old == null ? null : new double[] {old[0] * s, old[1] * s, old[2] * s, old[3] * s};
	}

	private static boolean leviathanInside(int x, int y, int z, double shrink) {
		double[] section = section(x);
		if (section == null) return false;
		double ry = section[2] - shrink, rz = section[3] - shrink;
		if (ry <= 0.5 || rz <= 0.5) return false;
		return Painter.sq((y - section[0]) / ry) + Painter.sq((z - section[1]) / rz) <= 1;
	}

	/**
	 * A zerg leviathan (about 185 blocks long, 2.2 times the old one; owner 16.10: all of zerg flesh, some of
	 * it glowing, bone spines and teeth, long tentacles hanging and curling below). Inside, no stairs: a
	 * chaotic network of passages wound through the flesh ({@link #burrow}), from the chamber of its
	 * teleporter pad amidships to the boss room in the head — Kerrigan's — with eggs, a chest, roaches,
	 * hydralisks and a queen on creep along the way.
	 */
	public static Ship leviathan(Painter painter, BlockPos middle, int turn) {
		Frame frame = new Frame(painter, middle, turn);
		RandomSource random = painter.random;
		double s = LEVIATHAN_SCALE;
		BlockState flesh = dev.starmine.block.StarMineBlocks.ZERG_FLESH.defaultBlockState(),
			glow = dev.starmine.block.StarMineBlocks.GLOWING_ZERG_FLESH.defaultBlockState(),
			bone = Blocks.BONE_BLOCK.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
		int x0 = (int) (-42 * s), x1 = (int) (42 * s);
		// the body, solid
		for (int x = x0; x <= x1; x++) {
			double[] section = section(x);
			int ry = (int) Math.ceil(section[2]) + 1, rz = (int) Math.ceil(section[3]) + 1;
			for (int y = (int) Math.floor(section[0]) - ry; y <= section[0] + ry; y++)
				for (int z = (int) Math.floor(section[1]) - rz; z <= section[1] + rz; z++)
					if (leviathanInside(x, y, z, 0)) frame.put(x, y, z, random.nextInt(16) == 0 ? glow : flesh);
			// teeth round the maw
			if (x >= (int) (33 * s) && x <= (int) (41 * s) && x % 2 == 1)
				for (int side = -1; side <= 1; side += 2)
					frame.put(x, (int) Math.round(section[0]), (int) Math.round(section[1] + side * section[3] * 0.55), bone);
			// spines along the back
			if (x > (int) (-30 * s) && x < (int) (30 * s) && Math.floorMod(x, 13) == 0) {
				int top = (int) Math.round(section[0] + section[2]), height = 5 + random.nextInt(6);
				for (int h = 1; h <= height; h++) frame.put(x, top + h, (int) Math.round(section[1]), bone);
			}
		}
		// tentacles: long, hanging, curling out and back; thick at the root
		for (int x = (int) (-20 * s); x < (int) (30 * s); x += 15) {
			double[] section = section(x);
			for (int side = -1; side <= 1; side += 2) {
				double cx = x, cy = section[0] - section[2] + 1, cz = section[1] + side * section[3] * 0.45;
				int length = 22 + random.nextInt(14);
				double phase = random.nextDouble() * Math.PI * 2;
				for (int t = 0; t <= length; t++) {
					double f = t / (double) length, radius = 2.6 * (1 - f) + 0.7;
					double px = cx + Math.sin(phase + t / 5.0) * 3, py = cy - t * (1 - 0.35 * f * f),
						pz = cz + side * (f * f * 10 + Math.sin(phase + t / 7.0) * 2);
					int r = (int) Math.ceil(radius);
					for (int dx = -r; dx <= r; dx++)
						for (int dy = -r; dy <= r; dy++)
							for (int dz = -r; dz <= r; dz++)
								if (dx * dx + dy * dy + dz * dz <= radius * radius)
									frame.put((int) Math.round(px) + dx, (int) Math.round(py) + dy, (int) Math.round(pz) + dz,
										(t + dy) % 7 == 0 ? glow : flesh);
					if (t == length) frame.put((int) Math.round(px), (int) Math.round(py) - r - 1, (int) Math.round(pz), Blocks.WEEPING_VINES.defaultBlockState());
				}
			}
		}
		// eyes
		double[] head = section((int) (36 * s));
		for (int side = -1; side <= 1; side += 2)
			frame.box((int) (36 * s), (int) Math.round(head[0] + 4), (int) Math.round(head[1] + side * (head[3] - 1)),
				(int) (36 * s) + 2, (int) Math.round(head[0] + 5), (int) Math.round(head[1] + side * (head[3] - 1)), glow);
		// the inside: the pad's chamber amidships, the boss room in the head, passages winding between and about
		java.util.Set<Long> hollow = new java.util.HashSet<>();
		int padX = (int) (-6 * s), bossX = (int) (31 * s);
		double[] amid = section(padX), skull = section(bossX);
		chamber(hollow, padX, amid[0], amid[1], 6, 4.5);
		chamber(hollow, bossX, skull[0], skull[1], 7, 5);
		java.util.List<double[]> reached = new java.util.ArrayList<>();
		reached.add(new double[] {padX, amid[0], amid[1]});
		reached.add(new double[] {bossX, skull[0], skull[1]});
		burrow(hollow, random, new double[] {padX, amid[0], amid[1]}, new double[] {bossX, skull[0], skull[1]}, reached); // pad to boss, winding
		for (int i = 0; i < 7; i++) { // and more passages from anywhere already open, every way
			double[] from = reached.get(random.nextInt(reached.size()));
			int tx = x0 + 12 + random.nextInt(x1 - x0 - 24);
			double[] to = section(tx);
			burrow(hollow, random, from, new double[] {tx, to[0] + (random.nextDouble() - 0.5) * to[2], to[1] + (random.nextDouble() - 0.5) * to[3]}, reached);
		}
		List<BlockPos> floor = new ArrayList<>();
		for (long cell : hollow) {
			int x = BlockPos.getX(cell), y = BlockPos.getY(cell), z = BlockPos.getZ(cell);
			frame.put(x, y, z, air);
			if (!hollow.contains(BlockPos.asLong(x, y - 1, z))) floor.add(frame.at(x, y, z));
		}
		// the pad on the chamber's floor
		int padY = (int) Math.round(amid[0]), padZ = (int) Math.round(amid[1]);
		while (hollow.contains(BlockPos.asLong(padX, padY - 1, padZ))) padY--;
		BlockPos pad = frame.at(padX, padY, padZ);
		Teleporter.pad(painter, pad, UnitKind.Race.ZERG, flesh);
		// creep on the floors, eggs, a chest, guards
		List<Spawn> spawns = new ArrayList<>();
		List<BlockPos> lining = List.copyOf(floor);
		painter.then(() -> {
			for (BlockPos pos : lining) CreepGrowth.lineCavity(painter.level(), pos);
		});
		List<BlockPos> away = floor.stream().filter(pos -> pos.distSqr(pad) > 12 * 12).toList();
		if (!away.isEmpty()) {
			painter.chest(away.get(random.nextInt(away.size())), BuiltInLootTables.ANCIENT_CITY);
			for (int i = 0; i < 6; i++) {
				BlockPos at = away.get(random.nextInt(away.size()));
				UnitKind brood = i % 2 == 0 ? UnitKind.ZERGLING : UnitKind.HYDRALISK;
				spawns.add(new Spawn(UnitKind.EGG, at, at, brood, brood == UnitKind.ZERGLING ? 2 : 1));
			}
			for (int i = 0; i < 8; i++) {
				BlockPos at = away.get(random.nextInt(away.size()));
				spawns.add(Spawn.guard(i == 0 ? UnitKind.QUEEN : i < 4 ? UnitKind.ROACH : UnitKind.HYDRALISK, at, at));
			}
		}
		for (int i = 0; i < 4; i++)
			spawns.add(Spawn.guard(i % 2 == 0 ? UnitKind.ZERGLING : UnitKind.ROACH, frame.at(padX + (i % 2 == 0 ? -4 : 4), padY, padZ + (i < 2 ? -3 : 3)), pad));
		int bossY = (int) Math.round(skull[0]), bossZ = (int) Math.round(skull[1]);
		while (hollow.contains(BlockPos.asLong(bossX, bossY - 1, bossZ))) bossY--;
		BlockPos boss = frame.at(bossX, bossY, bossZ);
		spawns.add(Spawn.guard(UnitKind.KERRIGAN, boss, boss));
		spawns.add(Spawn.guard(UnitKind.ZERGLING, frame.at(bossX + 2, bossY, bossZ + 2), boss));
		spawns.add(Spawn.guard(UnitKind.ZERGLING, frame.at(bossX - 2, bossY, bossZ - 2), boss));
		int above = (int) Math.round(section(0)[0] + section(0)[2]) + 18;
		for (int i = 0; i < 6; i++) {
			UnitKind kind = i < 4 ? UnitKind.MUTALISK : UnitKind.CORRUPTOR;
			spawns.add(Spawn.guard(kind, frame.at((int) ((-20 + i * 9) * s), above, (i % 2 == 0 ? 1 : -1) * 34), pad));
		}
		spawns.add(Spawn.guard(UnitKind.BROOD_LORD, frame.at((int) (-30 * s), above + 6, 0), pad));
		return new Ship(spawns, pad, boss, 1, x1 - x0 + 1);
	}

	/** A round chamber in the leviathan about (x, y, z): `radius` across, `half` high, flat-bottomed. */
	private static void chamber(java.util.Set<Long> hollow, int x, double y, double z, double radius, double half) {
		int r = (int) Math.ceil(radius), h = (int) Math.ceil(half);
		for (int dx = -r; dx <= r; dx++)
			for (int dy = -h + 1; dy <= h; dy++)
				for (int dz = -r; dz <= r; dz++) {
					if (Painter.sq(dx / radius) + Painter.sq(Math.max(0, dy) / half) + Painter.sq(dz / radius) > 1) continue;
					int cx = x + dx, cy = (int) Math.round(y) + dy, cz = (int) Math.round(z) + dz;
					if (leviathanInside(cx, cy, cz, 2)) hollow.add(BlockPos.asLong(cx, cy, cz));
				}
	}

	/**
	 * A passage wound through the flesh from `from` toward `to`: it wanders (never quite straight, never
	 * steep — walkable, no stairs), turns back from the walls, about 5 across. Points it passes join `reached`.
	 */
	private static void burrow(java.util.Set<Long> hollow, RandomSource random, double[] from, double[] to, java.util.List<double[]> reached) {
		double x = from[0], y = from[1], z = from[2];
		double yaw = Math.atan2(to[2] - z, to[0] - x);
		for (int step = 0; step < 400; step++) {
			double dx = to[0] - x, dz = to[2] - z, dy = to[1] - y;
			double distance = Math.sqrt(dx * dx + dz * dz);
			if (distance < 3 && Math.abs(dy) < 3) break;
			double want = Math.atan2(dz, dx), turn = Math.atan2(Math.sin(want - yaw), Math.cos(want - yaw));
			yaw += turn * 0.25 + (random.nextDouble() - 0.5) * 1.1; // drawn to its end, wandering on the way
			double climb = Mth.clamp(dy / Math.max(4, distance), -0.45, 0.45) + (random.nextDouble() - 0.5) * 0.3;
			double nx = x + Math.cos(yaw), ny = y + climb, nz = z + Math.sin(yaw);
			if (!leviathanInside((int) Math.round(nx), (int) Math.round(ny), (int) Math.round(nz), 4)) {
				yaw += Math.PI * (0.4 + random.nextDouble() * 0.4) * (random.nextBoolean() ? 1 : -1); // a wall: turn away
				continue;
			}
			x = nx;
			y = ny;
			z = nz;
			double radius = 2.3 + random.nextDouble() * 0.8;
			int r = (int) Math.ceil(radius);
			for (int ox = -r; ox <= r; ox++)
				for (int oy = -1; oy <= r + 1; oy++) // flat-ish below, high above: room to walk
					for (int oz = -r; oz <= r; oz++) {
						if (ox * ox + Math.max(0, oy - 1) * Math.max(0, oy - 1) + oz * oz > radius * radius) continue;
						int cx = (int) Math.round(x) + ox, cy = (int) Math.round(y) + oy, cz = (int) Math.round(z) + oz;
						if (leviathanInside(cx, cy, cz, 2)) hollow.add(BlockPos.asLong(cx, cy, cz));
					}
			if (step % 6 == 0) reached.add(new double[] {x, y, z});
		}
	}
}
