package dev.starmine.world;

import dev.starmine.creep.CreepGrowth;
import dev.starmine.entity.UnitKind;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;

/**
 * Ships of block hanging in the sky over generated race bases (owner 14.10): the terran battlecruiser
 * Hyperion, the protoss mothership and a zerg leviathan. They do not fly — they are buildings in the
 * air, with an inside reachable from the deck (a scaffolding shaft) or, for the leviathan, through its
 * maw; loot chests inside and guards of the race on the deck, inside and flying around ({@link Spawn}).
 *
 * Each is drawn in its own local frame (x along the length, bow at +x; y up) turned by
 * {@link Frame#turn} quarter turns. A build returns what it wants spawned.
 */
public final class SkyShips {
	/** A scaffolding column standing on the floor (distance 0: it holds when touched). */
	private static final BlockState SCAFFOLD = Blocks.SCAFFOLDING.defaultBlockState().setValue(net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE, 0);

	private SkyShips() {
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
	}

	/** Where a guard can stand on the ship at local (x, z): above the highest block of that column, below `top`. */
	private static BlockPos standOn(Frame frame, int x, int z, int top, int bottom) {
		for (int y = top; y >= bottom; y--)
			if (!frame.painter.level().getBlockState(frame.at(x, y, z)).isAir()) return frame.at(x, y + 1, z);
		return frame.at(x, top, z);
	}

	// ---- Hyperion ----

	/** Inside the Hyperion's hull at local (x, y, z). */
	private static boolean hyperion(int x, int y, int z) {
		int az = Math.abs(z);
		// main hull, bevelled top and bottom
		if (x >= -28 && x <= 24 && y >= -4 && y <= 3 && az <= 6 - Math.max(0, -2 - y) - Math.max(0, y - 2)) return true;
		// the wide flat prow ("hammerhead"), cut in the middle front for the Yamato cannon
		if (x > 24 && x <= 36 && y >= -3 && y <= 2 && az <= 9 - Math.max(0, x - 32) * 2 && !(x > 30 && az <= 2 && y >= -1 && y <= 1)) return true;
		// rear engine block
		if (x >= -36 && x < -28 && y >= -3 && y <= 2 && az <= 8) return true;
		// engine nacelles
		for (int side = -1; side <= 1; side += 2)
			if (x >= -40 && x < -36 && Painter.sq(y + 0.5) + Painter.sq(z - side * 5) <= 6.5) return true;
		// bridge tower
		if (x >= -16 && x <= -4 && y >= 4 && y <= 10 && az <= (y > 8 ? 2 : 3)) return true;
		// dorsal ridge
		return x >= -4 && x <= 20 && y == 4 && az <= 1;
	}

	/**
	 * The terran battlecruiser Hyperion (about 77 blocks long): grey hull with a light stripe, a dark
	 * belly, a hammerhead prow with a glowing Yamato muzzle, a bridge with a window band, laser turrets,
	 * blue engines. Inside: one long hold lit from the ceiling, chests in the aft cargo bay; a scaffolding
	 * shaft leads down from the deck. Marines and marauders guard the deck, vikings fly about it.
	 */
	public static List<Spawn> hyperion(Painter painter, BlockPos middle, int turn) {
		Frame frame = new Frame(painter, middle, turn);
		BlockState shell = Blocks.GRAY_CONCRETE.defaultBlockState(), stripe = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(),
			belly = Blocks.POLISHED_DEEPSLATE.defaultBlockState(), trim = Blocks.IRON_BLOCK.defaultBlockState(),
			glass = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState(), light = Blocks.SEA_LANTERN.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
		for (int x = -40; x <= 36; x++)
			for (int y = -4; y <= 11; y++)
				for (int z = -9; z <= 9; z++) {
					if (!hyperion(x, y, z)) continue;
					boolean outside = !hyperion(x + 1, y, z) || !hyperion(x - 1, y, z) || !hyperion(x, y + 1, z) || !hyperion(x, y - 1, z)
						|| !hyperion(x, y, z + 1) || !hyperion(x, y, z - 1);
					BlockState state;
					if (!outside) state = air;
					else if (x < -36) state = x == -40 ? light : trim; // engines: glowing rear
					else if (y >= 4 && x >= -16 && x <= -4 && y == 8) state = glass; // the bridge's windows
					else if (y == 0 && Math.abs(z) >= 5) state = stripe;
					else if (y <= -3) state = belly;
					else if (Math.abs(z) + Math.max(0, -2 - y) + Math.max(0, y - 2) == 6 && (y == 3 || y == -2)) state = trim;
					else state = shell;
					frame.put(x, y, z, state);
				}
		// the Yamato muzzle
		for (int y = -1; y <= 1; y++)
			for (int z = -2; z <= 2; z++) frame.put(30, y, z, Math.abs(z) <= 1 && Math.abs(y) <= 1 ? Blocks.REDSTONE_BLOCK.defaultBlockState() : trim);
		frame.put(30, 0, 0, light);
		// ceiling lights of the hold, the cargo bay's chests
		for (int x = -26; x <= 22; x += 6) frame.put(x, 2, 0, light);
		painter.chest(frame.at(-24, -3, 4), BuiltInLootTables.STRONGHOLD_CORRIDOR);
		painter.chest(frame.at(-22, -3, -4), BuiltInLootTables.SIMPLE_DUNGEON);
		// laser turrets on the deck
		for (int x : new int[] {-22, 0, 12}) {
			for (int dx = -1; dx <= 1; dx++)
				for (int dz = -1; dz <= 1; dz++) frame.put(x + dx, 4, (x == 0 ? 4 : -4) + dz, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
			for (int dx = 2; dx <= 3; dx++) frame.put(x + dx, 4, x == 0 ? 4 : -4, Blocks.IRON_BARS.defaultBlockState());
		}
		// antennae on the bridge
		frame.put(-10, 11, 0, Blocks.LIGHTNING_ROD.defaultBlockState());
		frame.put(-12, 11, 1, Blocks.LIGHTNING_ROD.defaultBlockState());
		// a shaft from the deck into the hold
		for (int y = -3; y <= 3; y++) frame.put(18, y, 3, SCAFFOLD);

		List<Spawn> spawns = new ArrayList<>();
		BlockPos hall = frame.at(6, 4, 0);
		int[][] deck = {{-2, 3}, {2, -3}, {6, 3}, {10, -3}, {16, 3}, {20, -3}};
		UnitKind[] crew = {UnitKind.MARINE, UnitKind.MARINE, UnitKind.MARAUDER, UnitKind.MARINE, UnitKind.MARINE, UnitKind.MARAUDER};
		for (int i = 0; i < deck.length; i++) spawns.add(Spawn.guard(crew[i], standOn(frame, deck[i][0], deck[i][1], 9, -4), hall));
		spawns.add(Spawn.guard(UnitKind.MARINE, frame.at(-20, -3, 0), frame.at(-20, -3, 0)));
		spawns.add(Spawn.guard(UnitKind.GHOST, frame.at(0, -3, 0), frame.at(0, -3, 0)));
		spawns.add(Spawn.guard(UnitKind.VIKING, frame.at(0, 12, 10), hall));
		spawns.add(Spawn.guard(UnitKind.VIKING, frame.at(-10, 12, -10), hall));
		return spawns;
	}

	// ---- Mothership ----

	/**
	 * The protoss mothership (a disc 31 blocks across in a gold ring 43 across): quartz hull with a gold
	 * ring on top and blue windows round the rim, a glass dome, six arms out to the outer ring of gold
	 * and lights, a dark vortex cone of crying obsidian with an amethyst heart below. Inside the disc: a
	 * round hall with chests, reached by a shaft from the top. Zealots and stalkers guard the top, a high
	 * templar the hall; phoenixes and a void ray fly about it.
	 */
	public static List<Spawn> mothership(Painter painter, BlockPos middle) {
		Frame frame = new Frame(painter, middle, 0);
		BlockState quartz = Blocks.QUARTZ_BRICKS.defaultBlockState(), smooth = Blocks.SMOOTH_QUARTZ.defaultBlockState(),
			gold = Blocks.GOLD_BLOCK.defaultBlockState(), light = Blocks.SEA_LANTERN.defaultBlockState(),
			window = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
		double rx = 15, ry = 3.5;
		for (int x = -16; x <= 16; x++)
			for (int y = -4; y <= 4; y++)
				for (int z = -16; z <= 16; z++) {
					double r = Math.sqrt(x * x + z * z);
					if (Painter.sq(r / rx) + Painter.sq(y / ry) > 1) continue;
					boolean inside = Painter.sq(r / (rx - 1.2)) + Painter.sq(y / (ry - 1.2)) < 1;
					BlockState state = inside ? air
						: y >= 1 && r >= 11.5 && r <= 12.6 ? gold
						: Math.abs(y) <= 0 && r > 13 && Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(z, x))), 30) < 8 ? window
						: y < 0 ? smooth : quartz;
					frame.put(x, y, z, state);
				}
		// the dome
		for (int x = -7; x <= 7; x++)
			for (int y = 0; y <= 6; y++)
				for (int z = -7; z <= 7; z++) {
					double d = Painter.sq(x / 7.0) + Painter.sq(y / 6.0) + Painter.sq(z / 7.0);
					if (d > 1) continue;
					boolean inside = Painter.sq(x / 6.0) + Painter.sq(y / 5.0) + Painter.sq(z / 6.0) < 1;
					frame.put(x, y + 2, z, inside ? air : window);
				}
		for (int y = -2; y <= 6; y++) frame.put(0, y, 0, y == 6 ? Blocks.BEACON.defaultBlockState() : light); // the nexus core
		// the outer ring and six arms
		for (int a = 0; a < 360; a += 2) {
			double rad = Math.toRadians(a);
			for (double tube = -1.5; tube <= 1.5; tube += 0.5)
				for (int y = -1; y <= 1; y++) {
					int x = (int) Math.round(Math.cos(rad) * (21 + tube)), z = (int) Math.round(Math.sin(rad) * (21 + tube));
					if (Painter.sq(tube / 1.6) + Painter.sq(y / 1.4) <= 1) frame.put(x, y, z, a % 15 == 0 && y == 0 ? light : gold);
				}
		}
		for (int arm = 0; arm < 6; arm++) {
			double rad = Math.toRadians(arm * 60 + 30);
			for (double r = 14; r <= 20; r += 0.5)
				for (double w = -1; w <= 1; w += 0.5) {
					int x = (int) Math.round(Math.cos(rad) * r - Math.sin(rad) * w), z = (int) Math.round(Math.sin(rad) * r + Math.cos(rad) * w);
					frame.put(x, 0, z, smooth);
					if (w == 0) frame.put(x, 1, z, gold);
				}
		}
		// the vortex below
		for (int depth = 1; depth <= 10; depth++) {
			double radius = 6.5 * (1 - depth / 11.0);
			for (int x = -7; x <= 7; x++)
				for (int z = -7; z <= 7; z++)
					if (x * x + z * z <= radius * radius)
						frame.put(x, -3 - depth, z, x * x + z * z <= Math.max(1, radius * radius / 4) ? Blocks.AMETHYST_BLOCK.defaultBlockState()
							: Blocks.CRYING_OBSIDIAN.defaultBlockState());
		}
		frame.put(0, -14, 0, Blocks.END_ROD.defaultBlockState());
		// the hall: chests, a shaft from the top
		painter.chest(frame.at(9, -2, 0), BuiltInLootTables.END_CITY_TREASURE);
		painter.chest(frame.at(-9, -2, 0), BuiltInLootTables.DESERT_PYRAMID);
		for (int y = -2; y <= 3; y++) frame.put(0, y, 9, SCAFFOLD);

		List<Spawn> spawns = new ArrayList<>();
		BlockPos top = frame.at(0, 4, 0), hall = frame.at(0, -2, 5);
		UnitKind[] guards = {UnitKind.ZEALOT, UnitKind.ZEALOT, UnitKind.STALKER, UnitKind.ZEALOT, UnitKind.STALKER, UnitKind.ADEPT};
		for (int i = 0; i < guards.length; i++) {
			double rad = Math.toRadians(i * 60);
			int x = (int) Math.round(Math.cos(rad) * 10), z = (int) Math.round(Math.sin(rad) * 10);
			spawns.add(Spawn.guard(guards[i], standOn(frame, x, z, 9, -4), top));
		}
		spawns.add(Spawn.guard(UnitKind.HIGH_TEMPLAR, hall, hall));
		spawns.add(Spawn.guard(UnitKind.DARK_TEMPLAR, frame.at(-5, -2, -5), hall));
		spawns.add(Spawn.guard(UnitKind.PHOENIX, frame.at(18, 8, 0), top));
		spawns.add(Spawn.guard(UnitKind.PHOENIX, frame.at(-18, 8, 0), top));
		spawns.add(Spawn.guard(UnitKind.VOID_RAY, frame.at(0, 10, 18), top));
		return spawns;
	}

	// ---- Leviathan ----

	/** The leviathan's body at local x: centre (y, z) and radii (ry, rz); null outside its length. */
	private static double[] leviathan(int x) {
		if (x < -42 || x > 42) return null;
		double s = (x + 42) / 84.0; // 0 = tail, 1 = head
		double radius = s > 0.82 ? 9 - (s - 0.82) * 30 : 1.5 + 10 * Math.pow(Math.sin(Math.PI * Math.min(1, s / 0.82) * 0.62), 1.2);
		double y = 3 * Math.sin(Math.PI * s * 1.6), z = 4 * Math.sin(Math.PI * 2 * s);
		return new double[] {y, z, Math.max(1.2, radius * 0.8), Math.max(1.2, radius)};
	}

	private static boolean leviathanInside(int x, int y, int z, double shrink) {
		double[] section = leviathan(x);
		if (section == null) return false;
		double ry = section[2] - shrink, rz = section[3] - shrink;
		if (ry <= 0.5 || rz <= 0.5) return false;
		return Painter.sq((y - section[0]) / ry) + Painter.sq((z - section[1]) / rz) <= 1;
	}

	/**
	 * A zerg leviathan (about 85 blocks long): flesh of nether wart with a dark carapace veined with
	 * crying obsidian, a row of bone spines along the back, glowing eyes, a toothed maw that opens into
	 * a gut lined with creep, and tentacles hanging below. In the gut: eggs, a chest, guards; mutalisks
	 * and corruptors circle it.
	 */
	public static List<Spawn> leviathan(Painter painter, BlockPos middle, int turn) {
		Frame frame = new Frame(painter, middle, turn);
		RandomSource random = painter.random;
		BlockState flesh = Blocks.NETHER_WART_BLOCK.defaultBlockState(), carapace = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState(),
			vein = Blocks.CRYING_OBSIDIAN.defaultBlockState(), belly = Blocks.CRIMSON_HYPHAE.defaultBlockState(),
			bone = Blocks.BONE_BLOCK.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
		List<BlockPos> gutFloor = new ArrayList<>();
		for (int x = -42; x <= 42; x++) {
			double[] section = leviathan(x);
			int ry = (int) Math.ceil(section[2]) + 1, rz = (int) Math.ceil(section[3]) + 1;
			for (int y = (int) Math.floor(section[0]) - ry; y <= section[0] + ry; y++)
				for (int z = (int) Math.floor(section[1]) - rz; z <= section[1] + rz; z++) {
					if (!leviathanInside(x, y, z, 0)) continue;
					boolean gut = x > -20 && x < 36 && leviathanInside(x, y, z, 1.6);
					boolean maw = x >= 33 && y < section[0] + 1 && Math.abs(z - section[1]) < section[3] * 0.55;
					if (gut || maw) {
						frame.put(x, y, z, air);
						if (gut && !leviathanInside(x, y - 1, z, 1.6)) gutFloor.add(frame.at(x, y, z));
						continue;
					}
					double up = (y - section[0]) / section[2];
					BlockState state = up > 0.35 ? (random.nextInt(9) == 0 ? vein : carapace) : up < -0.45 ? belly : flesh;
					frame.put(x, y, z, state);
				}
			// teeth round the maw
			if (x >= 33 && x <= 41 && x % 2 == 1)
				for (int side = -1; side <= 1; side += 2)
					frame.put(x, (int) Math.round(section[0]), (int) Math.round(section[1] + side * section[3] * 0.55), bone);
			// spines along the back
			if (x > -30 && x < 30 && Math.floorMod(x, 6) == 0) {
				int top = (int) Math.round(section[0] + section[2]), height = 3 + random.nextInt(4);
				for (int h = 1; h <= height; h++) frame.put(x, top + h, (int) Math.round(section[1]), bone);
			}
			// tentacles below
			if (x > -10 && x < 34 && Math.floorMod(x, 7) == 3) {
				int bottom = (int) Math.round(section[0] - section[2]);
				int length = 6 + random.nextInt(9);
				for (int side = -1; side <= 1; side += 2) {
					int z = (int) Math.round(section[1] + side * section[3] * 0.4);
					for (int h = 1; h <= length; h++)
						frame.put(x, bottom - h, z, h == length ? Blocks.WEEPING_VINES.defaultBlockState() : Blocks.WEEPING_VINES_PLANT.defaultBlockState());
				}
			}
		}
		// eyes
		double[] head = leviathan(36);
		for (int side = -1; side <= 1; side += 2)
			for (int dx = 0; dx <= 1; dx++)
				frame.put(36 + dx, (int) Math.round(head[0] + 2), (int) Math.round(head[1] + side * (head[3] - 0.5)), Blocks.SHROOMLIGHT.defaultBlockState());
		// the gut: creep on its floor, eggs, a chest, guards
		List<Spawn> spawns = new ArrayList<>();
		BlockPos hall = frame.at(8, (int) Math.round(leviathan(8)[0]), (int) Math.round(leviathan(8)[1]));
		for (BlockPos pos : gutFloor) CreepGrowth.lineCavity(painter.level(), pos);
		if (!gutFloor.isEmpty()) {
			painter.chest(gutFloor.get(random.nextInt(gutFloor.size())), BuiltInLootTables.ANCIENT_CITY);
			for (int i = 0; i < 5; i++) {
				BlockPos at = gutFloor.get(random.nextInt(gutFloor.size()));
				UnitKind brood = i % 2 == 0 ? UnitKind.ZERGLING : UnitKind.HYDRALISK;
				spawns.add(new Spawn(UnitKind.EGG, at, hall, brood, brood == UnitKind.ZERGLING ? 2 : 1));
			}
			for (int i = 0; i < 3; i++) spawns.add(Spawn.guard(i == 0 ? UnitKind.QUEEN : UnitKind.ROACH, gutFloor.get(random.nextInt(gutFloor.size())), hall));
		}
		BlockPos back = frame.at(0, (int) Math.round(leviathan(0)[0] + leviathan(0)[2]) + 8, 0);
		for (int i = 0; i < 6; i++) {
			UnitKind kind = i < 4 ? UnitKind.MUTALISK : UnitKind.CORRUPTOR;
			spawns.add(Spawn.guard(kind, frame.at(-20 + i * 9, (int) Math.round(leviathan(0)[2]) + 10, (i % 2 == 0 ? 1 : -1) * 14), back));
		}
		spawns.add(Spawn.guard(UnitKind.BROOD_LORD, frame.at(-30, 12, 0), back));
		return spawns;
	}
}
