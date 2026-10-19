package dev.starmine.world;

import dev.starmine.block.ArmorBlocks;
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
	public static final double HYPERION_SCALE = 4.8, MOTHERSHIP_SCALE = 4.8, LEVIATHAN_SCALE = 4.4; // owner 19.10: twice as large

	private SkyShips() {
	}

	/** What a ship is: who it wants spawned, its teleporter pad (the cell of the pad block), its boss room's floor middle, its decks. */
	public record Ship(List<Spawn> spawns, BlockPos pad, BlockPos bossRoom, int decks, int length, List<BlockPos> hangars) {
		public Ship(List<Spawn> spawns, BlockPos pad, BlockPos bossRoom, int decks, int length) {
			this(spawns, pad, bossRoom, decks, length, List.of());
		}
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

	/** The {@link ShipInterior.Hull} of a frame and a test of a hull's inside (cells not of its shell). */
	private static ShipInterior.Hull hullOf(Frame frame, Inside inside) {
		return new ShipInterior.Hull() {
			@Override
			public boolean inside(int x, int y, int z) {
				return inside.test(x, y, z);
			}

			@Override
			public void put(int x, int y, int z, BlockState state) {
				frame.put(x, y, z, state);
			}

			@Override
			public BlockPos at(int x, int y, int z) {
				return frame.at(x, y, z);
			}

			@Override
			public Direction facing(Direction local) {
				return frame.facing(local);
			}
		};
	}

	/** The lowest and highest inside cell of a hull's column (x, z), or {0, -1}. */
	private static int[] column(Inside inside, int x, int z, int from, int to) {
		int bottom = Integer.MAX_VALUE, top = Integer.MIN_VALUE;
		for (int y = from; y <= to; y++)
			if (inside.test(x, y, z)) {
				bottom = Math.min(bottom, y);
				top = Math.max(top, y);
			}
		return bottom > top ? new int[] {0, -1} : new int[] {bottom, top};
	}

	/** A hatch from a ship's top down to its highest deck: an opening and a flight of stairs along it (no ladders). */
	private static void hatch(Frame frame, int x, int deckFloor, int surface, Direction down, BlockState block, BlockState stair) {
		int steps = surface - (deckFloor + 1);
		if (steps <= 0) return;
		int sx = down.getStepX();
		frame.box(x - 1, deckFloor + 1, -2, x + sx * (steps + 1), surface + 1, 2, Blocks.AIR.defaultBlockState());
		frame.stairs(x + sx * steps, deckFloor + 1, -2, steps, 5, down.getOpposite(), block, stair);
	}

	// ---- Hyperion ----

	/**
	 * The terran battlecruiser Hyperion (owner 19.10: twice as large again, about 370 blocks long), of terran armour —
	 * plain, plated and lit panels — with window bands along its decks, a dark belly, a hammerhead prow with a glowing
	 * Yamato muzzle, swept wings ending in great blue engines, laser turrets and the bridge tower. Inside
	 * ({@link ShipInterior}): four decks of rooms off a spine, ramps between them, hangars opening on its flanks — the
	 * great one amidships — with craft to take, and the boss hall aft on the top decks, Raynor's, behind its gates.
	 * Its teleporter pad is on the forward deck, a hatch with stairs beside it.
	 */
	public static Ship hyperion(Painter painter, BlockPos middle, int turn) {
		Frame frame = new Frame(painter, middle, turn);
		double s = HYPERION_SCALE, k = s / 2.4;
		Shape hull = new Shape((int) (-41 * s), (int) (37 * s), (int) (-5 * s), (int) (11 * s), (int) (-20 * s), (int) (20 * s),
			(x, y, z) -> hyperionHull(x / s, y / s, z / s));
		Inside inside = (x, y, z) -> hull.in(x, y, z) && !hull.shell(x, y, z);
		BlockState shell = ArmorBlocks.wall(UnitKind.Race.TERRAN, 0), plating = ArmorBlocks.wall(UnitKind.Race.TERRAN, 1),
			panel = ArmorBlocks.wall(UnitKind.Race.TERRAN, 2), trim = Blocks.IRON_BLOCK.defaultBlockState(),
			glass = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState(), light = Blocks.SEA_LANTERN.defaultBlockState(),
			engine = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
		int[] span = column(inside, 0, 0, hull.y0, hull.y0 + hull.sy - 1);
		int bottom = span[0], top = (int) Math.floor(3.4 * s) - 1;
		for (int x = hull.x0; x < hull.x0 + hull.sx; x++)
			for (int y = hull.y0; y < hull.y0 + hull.sy; y++)
				for (int z = hull.z0; z < hull.z0 + hull.sz; z++) {
					if (!hull.in(x, y, z)) continue;
					double X = x / s, Y = y / s, Z = Math.abs(z / s);
					BlockState state;
					if (!hull.shell(x, y, z)) state = air;
					else if (X < -36 || X <= -29 && Z > 14) state = X < -39 || X < -31 && Z > 14 ? light : trim; // engines' glowing rears
					else if (Z > 14) state = Painter.sq(Y + 0.5) + Painter.sq(Z - 17) > 4 ? engine : trim; // wing engines: blue glass cowls
					else if (Y >= 3 && X >= -16 && X <= -4 && y == (int) (8 * s)) state = glass; // the bridge's windows
					else if (Z >= 4.5 && X > -26 && X < 22 && Math.floorMod(y - bottom, ShipInterior.DECK) == 3 && Math.floorMod(x, 4) != 0) state = glass; // deck windows
					else if (Y <= -3) state = plating;
					else if (Z > 6 && Z <= 16 && Y > -1.5 && Y < 0.5) state = Math.floorMod(x + z, 9) == 0 ? trim : panel; // wings
					else state = Math.floorMod(y, 7) == 0 ? panel : shell;
					frame.put(x, y, z, state);
				}
		ShipInterior in = new ShipInterior(hullOf(frame, (x, y, z) -> inside.test(x, y, z) && Math.abs(z) <= 6.4 * s - 1),
			painter, UnitKind.Race.TERRAN, (int) (-27 * s), (int) (23 * s), bottom + 1, top, 1);
		in.build();
		int deck = in.decks.get(in.decks.size() - 1);
		// the Yamato muzzle
		int muzzle = (int) (30.5 * s);
		for (int y = -6; y <= 6; y++)
			for (int z = -8; z <= 8; z++) frame.put(muzzle, y, z, Math.abs(z) <= 4 && Math.abs(y) <= 4 ? Blocks.RED_CONCRETE.defaultBlockState() : trim);
		frame.box(muzzle, -2, -2, muzzle, 2, 2, light);
		// laser turrets on the deck, antennae on the bridge
		for (int x : new int[] {-100, 0, 56}) {
			int z = x == 0 ? 18 : -18, onTop = hull.top(x, z) + 1;
			frame.box(x - 4, onTop, z - 4, x + 4, onTop + 2, z + 4, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
			for (int dx = 5; dx <= 14; dx++) frame.box(x + dx, onTop + 1, z, x + dx, onTop + 2, z, Blocks.IRON_BARS.defaultBlockState());
		}
		int bridgeTop = hull.top((int) (-10 * s), 0) + 1;
		frame.box((int) (-10 * s), bridgeTop, 0, (int) (-10 * s), bridgeTop + 8, 0, Blocks.END_ROD.defaultBlockState());
		// the teleporter pad on the forward deck, the hatch down beside it
		int padX = (int) (20 * s), padTop = hull.top(padX, 0);
		BlockPos pad = frame.at(padX, padTop + 1, 0);
		Teleporter.pad(painter, pad, UnitKind.Race.TERRAN, Blocks.POLISHED_DIORITE.defaultBlockState());
		hatch(frame, padX - 8, deck, hull.top(padX - 8, 0), Direction.WEST, Blocks.POLISHED_ANDESITE.defaultBlockState(),
			Blocks.POLISHED_ANDESITE_STAIRS.defaultBlockState());
		List<Spawn> spawns = new ArrayList<>(in.spawns);
		for (int i = 0; i < 4; i++) // the pad's guards
			spawns.add(Spawn.guard(i % 2 == 0 ? UnitKind.MARINE : UnitKind.MARAUDER, frame.at(padX - 6 + (i % 2) * 12, padTop + 1, i < 2 ? -6 : 6), pad));
		BlockPos boss = in.boss != null ? in.boss : pad;
		spawns.add(Spawn.guard(UnitKind.RAYNOR, boss, boss));
		int above = (int) (14 * s);
		spawns.add(Spawn.guard(UnitKind.VIKING, frame.at(40, above, 60), pad));
		spawns.add(Spawn.guard(UnitKind.VIKING, frame.at(-80, above, -60), pad));
		spawns.add(Spawn.guard(UnitKind.BANSHEE, frame.at(-20, above, 68), pad));
		spawns.add(Spawn.guard(UnitKind.BANSHEE, frame.at(90, above, -64), pad));
		return new Ship(spawns, pad, boss, in.decks.size(), hull.sx, List.copyOf(in.hangars));
	}

	// ---- Mothership ----

	/**
	 * The protoss mothership (owner 19.10: twice as large again — a disc about 145 across in a gold ring 240 across),
	 * of protoss armour with gold bands, blue windows round the rim, a great glass dome, six arms out to the ring, a dark
	 * vortex below running down into a long tail. Inside the disc ({@link ShipInterior}): decks of rooms off a spine,
	 * ramps, hangars opening through the rim — the great one for a colossus — with craft to take; under the dome the boss
	 * hall, Artanis's, behind its gates. Its teleporter pad is on the top of the disc, a hatch with stairs beside it.
	 */
	public static Ship mothership(Painter painter, BlockPos middle) {
		Frame frame = new Frame(painter, middle, 0);
		double s = MOTHERSHIP_SCALE, rx = 15 * s, ry = 3.5 * s;
		BlockState armour = ArmorBlocks.wall(UnitKind.Race.PROTOSS, 0), plating = ArmorBlocks.wall(UnitKind.Race.PROTOSS, 1),
			panel = ArmorBlocks.wall(UnitKind.Race.PROTOSS, 2), gold = Blocks.YELLOW_TERRACOTTA.defaultBlockState(),
			light = Blocks.SEA_LANTERN.defaultBlockState(), window = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState(),
			air = Blocks.AIR.defaultBlockState(), smooth = Blocks.SMOOTH_QUARTZ.defaultBlockState();
		int r0 = (int) Math.ceil(rx), h0 = (int) Math.ceil(ry);
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
					if (!disc.shell(x, y, z)) state = air;
					else if (domeCell) state = window;
					else if (y >= 1 && Math.abs(r - 57) < 1.6) state = gold;
					else if (Math.abs(y) <= 2 && r > 62 && Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(z, x))), 20) < 6) state = window;
					else if (y < 0 && Math.abs(r - 40) < 1.2) state = panel;
					else state = y < 0 ? plating : armour;
					frame.put(x, y, z, state);
				}
		Inside inside = (x, y, z) -> disc.in(x, y, z) && !disc.shell(x, y, z);
		int bottom = column(inside, 0, 0, -h0, h0)[0];
		ShipInterior in = new ShipInterior(hullOf(frame, inside), painter, UnitKind.Race.PROTOSS, (int) (-rx + 10), (int) (rx - 10), bottom + 1, (int) ry - 2,
			(int) ((rx - 10) / ShipInterior.ROOM) - 1);
		in.build();
		int deck = in.decks.get(in.decks.size() - 1);
		for (int y = deck + 1; y <= (int) (8 * s); y++) frame.put(0, y, 0, y == (int) (8 * s) ? Blocks.END_ROD.defaultBlockState() : light); // the nexus core
		// the outer ring and six arms
		double ring = 21 * s, tube = 1.6 * s;
		for (int a = 0; a < 360 * 8; a++) {
			double rad = Math.toRadians(a / 8.0);
			for (double t = -tube; t <= tube; t += 0.5)
				for (double v = -tube; v <= tube; v += 0.5) {
					if (Painter.sq(t / tube) + Painter.sq(v / (tube * 0.85)) > 1) continue;
					int x = (int) Math.round(Math.cos(rad) * (ring + t)), z = (int) Math.round(Math.sin(rad) * (ring + t)), y = (int) Math.round(v);
					frame.put(x, y, z, a % 120 == 0 && y == 0 ? light : gold);
				}
		}
		for (int arm = 0; arm < 6; arm++) {
			double rad = Math.toRadians(arm * 60 + 30);
			for (double r = rx - 1; r <= ring - tube + 1; r += 0.5)
				for (double w = -5; w <= 5; w += 0.5) {
					int x = (int) Math.round(Math.cos(rad) * r - Math.sin(rad) * w), z = (int) Math.round(Math.sin(rad) * r + Math.cos(rad) * w);
					frame.put(x, 0, z, smooth);
					frame.put(x, -1, z, smooth);
					if (Math.abs(w) < 0.6) frame.put(x, 1, z, Math.floorMod((int) r, 6) == 0 ? light : gold);
				}
		}
		// the vortex below, running down into a long tail
		int vortex = (int) (10 * s), tail = vortex + 52;
		for (int depth = 1; depth <= tail; depth++) {
			double radius = depth <= vortex ? 6.5 * s * (1 - depth / (vortex + 4.0)) : 7 * (1 - (depth - vortex) / (double) (tail - vortex + 2)) + 0.6;
			int r = (int) Math.ceil(radius);
			for (int x = -r; x <= r; x++)
				for (int z = -r; z <= r; z++)
					if (x * x + z * z <= radius * radius)
						frame.put(x, (int) -ry - depth + 2, z, x * x + z * z <= Math.max(1, radius * radius / 4) ? Blocks.AMETHYST_BLOCK.defaultBlockState()
							: Blocks.CRYING_OBSIDIAN.defaultBlockState());
		}
		// the teleporter pad on the top of the disc, the hatch down beside it
		int padX = (int) (-rx * 0.6), padTop = disc.top(padX, 0);
		BlockPos pad = frame.at(padX, padTop + 1, 0);
		Teleporter.pad(painter, pad, UnitKind.Race.PROTOSS, gold);
		hatch(frame, padX + 8, deck, disc.top(padX + 8, 0), Direction.EAST, smooth, Blocks.QUARTZ_STAIRS.defaultBlockState());
		List<Spawn> spawns = new ArrayList<>(in.spawns);
		for (int i = 0; i < 4; i++)
			spawns.add(Spawn.guard(i % 2 == 0 ? UnitKind.ZEALOT : UnitKind.STALKER, frame.at(padX + (i % 2 == 0 ? -5 : 5), padTop + 1, i < 2 ? -5 : 5), pad));
		BlockPos boss = in.boss != null ? in.boss : pad;
		spawns.add(Spawn.guard(UnitKind.ARTANIS, boss, boss));
		spawns.add(Spawn.guard(UnitKind.PHOENIX, frame.at(90, 30, 0), pad));
		spawns.add(Spawn.guard(UnitKind.PHOENIX, frame.at(-90, 30, 0), pad));
		spawns.add(Spawn.guard(UnitKind.VOID_RAY, frame.at(0, 34, 90), pad));
		spawns.add(Spawn.guard(UnitKind.VOID_RAY, frame.at(0, 34, -90), pad));
		return new Ship(spawns, pad, boss, in.decks.size(), (int) (2 * (ring + tube)), List.copyOf(in.hangars));
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
	 * A zerg leviathan (owner 19.10: twice as large again, about 370 blocks long): a hide of zerg flesh {@link #HIDE}
	 * thick, some of it glowing, bone spines and teeth, long tentacles hanging and curling below. Inside
	 * ({@link ShipInterior}): decks of flesh, chambers off a spine closed by sphincters, ramps of flesh slabs (no stairs),
	 * nests opening through its flanks, eggs and creep; the boss hall in the head — Kerrigan's — behind its sphincters.
	 * Its teleporter pad is on the second deck amidships.
	 */
	public static Ship leviathan(Painter painter, BlockPos middle, int turn) {
		Frame frame = new Frame(painter, middle, turn);
		RandomSource random = painter.random;
		double s = LEVIATHAN_SCALE;
		BlockState flesh = dev.starmine.block.StarMineBlocks.ZERG_FLESH.defaultBlockState(),
			glow = dev.starmine.block.StarMineBlocks.GLOWING_ZERG_FLESH.defaultBlockState(),
			chitin = ArmorBlocks.wall(UnitKind.Race.ZERG, 1), bone = Blocks.BONE_BLOCK.defaultBlockState();
		int x0 = (int) (-42 * s), x1 = (int) (42 * s);
		// the hide: flesh, an outer skin of chitin plates here and there
		for (int x = x0; x <= x1; x++) {
			double[] section = section(x);
			int ry = (int) Math.ceil(section[2]) + 1, rz = (int) Math.ceil(section[3]) + 1;
			for (int y = (int) Math.floor(section[0]) - ry; y <= section[0] + ry; y++)
				for (int z = (int) Math.floor(section[1]) - rz; z <= section[1] + rz; z++) {
					if (!leviathanInside(x, y, z, 0) || leviathanInside(x, y, z, HIDE)) continue;
					boolean skin = !leviathanInside(x, y, z, 1);
					frame.put(x, y, z, skin && Math.floorMod(x * 3 + y * 5 + z, 17) == 0 ? chitin : random.nextInt(16) == 0 ? glow : flesh);
				}
			if (x >= (int) (33 * s) && x <= (int) (41 * s) && x % 3 == 1) // teeth round the maw
				for (int side = -1; side <= 1; side += 2)
					for (int t = 0; t < 3; t++) frame.put(x, (int) Math.round(section[0]) - t, (int) Math.round(section[1] + side * section[3] * 0.55), bone);
			if (x > (int) (-30 * s) && x < (int) (30 * s) && Math.floorMod(x, 21) == 0) { // spines along the back
				int top = (int) Math.round(section[0] + section[2]), height = 10 + random.nextInt(10);
				for (int h = 1; h <= height; h++) frame.box(x, top + h, (int) Math.round(section[1]), x + (h < height / 2 ? 1 : 0), top + h, (int) Math.round(section[1]), bone);
			}
		}
		// tentacles: long, hanging, curling out and back; thick at the root
		for (int x = (int) (-20 * s); x < (int) (30 * s); x += 26) {
			double[] section = section(x);
			for (int side = -1; side <= 1; side += 2) {
				double cx = x, cy = section[0] - section[2] + 1, cz = section[1] + side * section[3] * 0.45;
				int length = 44 + random.nextInt(28);
				double phase = random.nextDouble() * Math.PI * 2;
				for (int t = 0; t <= length; t++) {
					double f = t / (double) length, radius = 5 * (1 - f) + 1.2;
					double px = cx + Math.sin(phase + t / 9.0) * 6, py = cy - t * (1 - 0.35 * f * f),
						pz = cz + side * (f * f * 20 + Math.sin(phase + t / 12.0) * 4);
					int r = (int) Math.ceil(radius);
					for (int dx = -r; dx <= r; dx++)
						for (int dy = -r; dy <= r; dy++)
							for (int dz = -r; dz <= r; dz++)
								if (dx * dx + dy * dy + dz * dz <= radius * radius && (dx * dx + dy * dy + dz * dz >= (radius - 1.5) * (radius - 1.5) || t % 4 == 0))
									frame.put((int) Math.round(px) + dx, (int) Math.round(py) + dy, (int) Math.round(pz) + dz, (t + dy) % 7 == 0 ? glow : flesh);
				}
			}
		}
		// eyes
		double[] head = section((int) (36 * s));
		for (int side = -1; side <= 1; side += 2)
			frame.box((int) (36 * s), (int) Math.round(head[0] + 6), (int) Math.round(head[1] + side * (head[3] - 1)),
				(int) (36 * s) + 4, (int) Math.round(head[0] + 9), (int) Math.round(head[1] + side * (head[3] - 1)), glow);
		Inside inside = (x, y, z) -> leviathanInside(x, y, z, HIDE) && Math.abs(z - section0(x)) <= 40;
		double[] amid = section(0);
		int bottom = (int) Math.round(amid[0] - amid[2] + HIDE + 2);
		ShipInterior in = new ShipInterior(hullOf(frame, inside), painter, UnitKind.Race.ZERG, (int) (-26 * s), (int) (30 * s), bottom,
			(int) Math.round(amid[0] + amid[2] - HIDE - 2), 99);
		in.build();
		// the pad on the second deck amidships
		int padDeck = in.decks.get(Math.min(1, in.decks.size() - 1));
		BlockPos pad = frame.at((int) (-6 * s), padDeck + 1, 0);
		Teleporter.pad(painter, pad, UnitKind.Race.ZERG, flesh);
		List<Spawn> spawns = new ArrayList<>(in.spawns);
		for (int i = 0; i < 4; i++)
			spawns.add(Spawn.guard(i % 2 == 0 ? UnitKind.ZERGLING : UnitKind.ROACH, frame.at((int) (-6 * s) + (i % 2 == 0 ? -5 : 5), padDeck + 1, i < 2 ? -2 : 2), pad));
		BlockPos boss = in.boss != null ? in.boss : pad;
		spawns.add(Spawn.guard(UnitKind.KERRIGAN, boss, boss));
		// creep on the floors once the blocks are in
		painter.then(() -> {
			var level = painter.level();
			for (int deckY : in.decks)
				for (int x = (int) (-26 * s); x <= (int) (30 * s); x += 1)
					for (int z = -36; z <= 36; z++) {
						if (!inside.test(x, deckY + 1, z) || random.nextInt(3) != 0) continue;
						BlockPos cell = frame.at(x, deckY + 1, z);
						if (level.getBlockState(cell).isAir()) CreepGrowth.lineCavity(level, cell);
					}
		});
		int above = (int) Math.round(amid[0] + amid[2]) + 30;
		for (int i = 0; i < 6; i++) {
			UnitKind kind = i < 4 ? UnitKind.MUTALISK : UnitKind.CORRUPTOR;
			spawns.add(Spawn.guard(kind, frame.at((int) ((-20 + i * 9) * s), above, (i % 2 == 0 ? 1 : -1) * 68), pad));
		}
		spawns.add(Spawn.guard(UnitKind.BROOD_LORD, frame.at((int) (-30 * s), above + 10, 0), pad));
		return new Ship(spawns, pad, boss, in.decks.size(), x1 - x0 + 1, List.copyOf(in.hangars));
	}

	/** How thick the leviathan's hide is. */
	static final int HIDE = 4;

	/** The middle (z) of the leviathan's body at local x, 0 past its ends. */
	private static double section0(int x) {
		double[] section = section(x);
		return section == null ? 0 : section[1];
	}
}
