package dev.starmine.world;

import dev.starmine.block.ArmorBlocks;
import dev.starmine.entity.UnitKind;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;

/**
 * The inside of a sky ship (owner 19.10: the ships twice as large, more rooms, more loot, several hangars, a boss hall
 * with gates). Laid in a hull's local frame (x along it, y up, z across):
 *
 * - decks every {@link #DECK} blocks up through the hull;
 * - on each a spine passage 5 wide along the middle and rooms either side, {@link #ROOM} long, each with a door of the
 *   race (they open by themselves) onto the spine, furnished in the race's way, a chest in many of them, guards;
 * - ramps from deck to deck in rooms of their own (half-steps a unit or a player walks up; the zerg's of flesh slabs);
 * - hangars on the lowest decks, two decks high, open to the outside through the hull's side by a great opening (no
 *   stairs: units walk or fly out); one is twice as long — room for a colossus, a thor or an ultralisk; craft parked in
 *   them ({@link #vehicles}, the terrans' and protoss' can be taken: {@link Vehicles});
 * - the boss hall: the top two decks over a stretch of the hull, open across, its gates (5 x 5 doors of the race) on the
 *   spine at both ends.
 */
final class ShipInterior {
	static final int DECK = 8, ROOM = 18, HALF = 2, HALL_ROOMS = 2, REACH = 80;

	/** A hull's frame and shape, seen from here. */
	interface Hull {
		boolean inside(int x, int y, int z);

		void put(int x, int y, int z, BlockState state);

		BlockPos at(int x, int y, int z);

		Direction facing(Direction local);
	}

	private final Hull hull;
	private final Painter painter;
	private final UnitKind.Race race;
	private final RandomSource random;
	final List<Spawn> spawns = new ArrayList<>();
	final List<Integer> decks = new ArrayList<>();
	private final int xa, xb, rooms, hallRoom;
	BlockPos boss;
	final List<BlockPos> hangars = new ArrayList<>();

	/** The inside of a hull from `xa` to `xb` along it, decks from `bottom` up to `top`, the boss hall from room `hallRoom` on. */
	ShipInterior(Hull hull, Painter painter, UnitKind.Race race, int xa, int xb, int bottom, int top, int hallRoom) {
		this.hull = hull;
		this.painter = painter;
		this.race = race;
		this.random = painter.random;
		this.xa = xa;
		this.rooms = Math.max(1, (xb - xa) / ROOM);
		this.xb = xa + rooms * ROOM;
		this.hallRoom = Math.max(0, Math.min(rooms - HALL_ROOMS, hallRoom));
		for (int fy = bottom; fy + DECK - 1 <= top; fy += DECK) decks.add(fy);
	}

	// ---- the race's blocks ----

	private BlockState floor(int x, int z) {
		return switch (race) {
			case ZERG -> (x * 7 + z * 3) % 11 == 0 ? dev.starmine.block.StarMineBlocks.GLOWING_ZERG_FLESH.defaultBlockState()
				: dev.starmine.block.StarMineBlocks.ZERG_FLESH.defaultBlockState();
			case TERRAN -> z == 0 && Math.floorMod(x, 6) == 0 ? Blocks.SEA_LANTERN.defaultBlockState()
				: Math.abs(z) == HALF ? Blocks.YELLOW_CONCRETE.defaultBlockState() : Blocks.POLISHED_ANDESITE.defaultBlockState();
			case PROTOSS -> z == 0 && Math.floorMod(x, 6) == 0 ? Blocks.SEA_LANTERN.defaultBlockState()
				: Math.abs(z) <= 1 ? Blocks.BLUE_TERRACOTTA.defaultBlockState() : Blocks.SMOOTH_QUARTZ.defaultBlockState();
		};
	}

	private BlockState wall(int x, int y) {
		int style = Math.floorMod(y, DECK) == 3 ? 2 : Math.floorMod(x, 9) == 0 ? 1 : 0;
		return ArmorBlocks.wall(race, style);
	}

	private BlockState door() {
		return ArmorBlocks.DOORS.get(race).defaultBlockState();
	}

	// ---- building it ----

	/** The whole inside: decks, rooms, ramps, hangars, the boss hall; the dwellers in {@link #spawns}. */
	void build() {
		int top = decks.size() - 1;
		int hallFrom = hallRoom;
		boolean[][] special = new boolean[rooms][2];
		for (int deck = 0; deck < decks.size(); deck++) {
			int fy = decks.get(deck);
			for (int x = xa; x <= xb; x++)
				for (int z = -REACH; z <= REACH; z++)
					if (hull.inside(x, fy, z) && hull.inside(x, fy + 1, z)) hull.put(x, fy, z, floor(x, z));
			for (int x = xa; x <= xb; x++) // the spine's walls, and between rooms
				for (int y = fy + 1; y < fy + DECK; y++) {
					for (int side = -1; side <= 1; side += 2) if (hull.inside(x, y, side * (HALF + 1))) hull.put(x, y, side * (HALF + 1), wall(x, y));
					if (Math.floorMod(x - xa, ROOM) == 0)
						for (int z = HALF + 2; z <= REACH; z++)
							for (int side = -1; side <= 1; side += 2) if (hull.inside(x, y, side * z)) hull.put(x, y, side * z, wall(x, y));
				}
			for (int room = 0; room < rooms; room++) // a door of the race from each room onto the spine
				for (int side = -1; side <= 1; side += 2) {
					int dx = xa + room * ROOM + ROOM / 2;
					for (int x = dx - 1; x <= dx + 1; x++)
						for (int y = fy + 1; y <= fy + 3; y++) if (hull.inside(x, y, side * (HALF + 1))) hull.put(x, y, side * (HALF + 1), door());
				}
		}
		// hangars on the lowest decks: two at either end on both sides, and the great one near the middle
		if (decks.size() >= 3) {
			hangar(1, 1, 1, special);
			hangar(1, -1, 1, special);
			hangar(rooms - 3, 1, 1, special);
			hangar(rooms / 2 - 1, -1, 2, special);
		}
		// ramps from deck to deck, in rooms of their own on alternate sides
		for (int deck = 0; deck + 1 < decks.size(); deck++) {
			int side = deck % 2 == 0 ? 1 : -1;
			for (int room = 2 + deck; room < rooms; room++) {
				if (special[room][side > 0 ? 1 : 0] || deck + 1 >= top - 1 && room >= hallFrom) continue;
				ramp(room, side, decks.get(deck));
				special[room][side > 0 ? 1 : 0] = true;
				break;
			}
		}
		hall(hallFrom, special);
		for (int deck = 0; deck < decks.size(); deck++)
			for (int room = 0; room < rooms; room++)
				for (int side = -1; side <= 1; side += 2) {
					if (special[room][side > 0 ? 1 : 0] && deck <= 1) continue;
					if (deck >= top - 1 && room >= hallFrom && room < hallFrom + HALL_ROOMS) continue;
					furnish(room, side, decks.get(deck));
				}
		for (int deck = 0; deck < decks.size(); deck++) // patrols on the spine
			for (int room = 1; room < rooms; room += 3) spawns.add(Spawn.guard(guard(), hull.at(xa + room * ROOM + 4, decks.get(deck) + 1, 0),
				hull.at(xa + room * ROOM + 4, decks.get(deck) + 1, 0)));
	}

	/** The room's x range. */
	private int x0(int room) {
		return xa + room * ROOM + 1;
	}

	/**
	 * A hangar: `length` rooms on one side of the lowest two decks made one bay — no walls, no floor between — open to
	 * the outside through the hull's side by an opening as tall as the bay; craft parked in it.
	 */
	private void hangar(int room, int side, int length, boolean[][] special) {
		if (room < 0 || room + length > rooms || decks.size() < 2) return;
		if (decks.size() < 4 && room < hallRoom + HALL_ROOMS && room + length > hallRoom) return; // the hall's decks: no hangar under it
		int fy = decks.get(0), ceiling = decks.get(1) + DECK - 1, x0 = x0(room), x1 = x0 + length * ROOM - 2;
		for (int r = room; r < room + length; r++) special[r][side > 0 ? 1 : 0] = true;
		for (int x = x0 - 1; x <= x1 + 1; x++)
			for (int y = fy + 1; y <= ceiling; y++)
				for (int z = HALF + 2; z <= REACH; z++) {
					if (!hull.inside(x, y, side * z)) continue;
					if (x < x0 || x > x1) { // the bay's end walls stay whole
						hull.put(x, y, side * z, wall(x, y));
						continue;
					}
					hull.put(x, y, side * z, Blocks.AIR.defaultBlockState());
				}
		// the opening: through the hull's side, along the bay, as tall as it (a rim of the bay left at its ends)
		for (int x = x0 + 1; x <= x1 - 1; x++)
			for (int y = fy + 1; y <= ceiling - 1; y++) {
				int z = HALF + 2;
				while (z < 60 && hull.inside(x, y, side * z)) z++;
				for (int out = z; out < z + 8; out++) hull.put(x, y, side * out, Blocks.AIR.defaultBlockState()); // the shell and a little beyond
			}
		for (int x = x0; x <= x1; x++) // its floor: the race's, with markings
			for (int z = HALF + 2; z <= REACH; z++)
				if (hull.inside(x, fy + 1, side * z) || hull.inside(x, fy, side * z))
					hull.put(x, fy, side * z, (x - x0) % 6 == 0 || z == HALF + 3 ? (race == UnitKind.Race.ZERG ? dev.starmine.block.StarMineBlocks.GLOWING_ZERG_FLESH
						: Blocks.YELLOW_CONCRETE).defaultBlockState() : floor(x, 7));
		BlockPos middle = hull.at((x0 + x1) / 2, fy + 1, side * (HALF + 8));
		hangars.add(middle);
		vehicles(middle, (x0 + x1) / 2, fy + 1, side, length);
	}

	/** Craft parked in a hangar (the terrans' and protoss' can be taken), or the zerg's flyers sleeping in their nest. */
	private void vehicles(BlockPos middle, int x, int y, int side, int length) {
		UnitKind[] parked = switch (race) {
			case TERRAN -> length > 1 ? new UnitKind[] {UnitKind.THOR, UnitKind.SIEGE_TANK, UnitKind.HELLION}
				: new UnitKind[] {random.nextBoolean() ? UnitKind.VIKING_ASSAULT : UnitKind.BANSHEE, UnitKind.SIEGE_TANK};
			case PROTOSS -> length > 1 ? new UnitKind[] {UnitKind.COLOSSUS, UnitKind.IMMORTAL, UnitKind.IMMORTAL}
				: new UnitKind[] {random.nextBoolean() ? UnitKind.PHOENIX : UnitKind.VOID_RAY, UnitKind.IMMORTAL};
			case ZERG -> length > 1 ? new UnitKind[] {UnitKind.ULTRALISK, UnitKind.MUTALISK, UnitKind.MUTALISK}
				: new UnitKind[] {UnitKind.MUTALISK, UnitKind.CORRUPTOR};
		};
		for (int i = 0; i < parked.length; i++) {
			BlockPos at = hull.at(x - (parked.length - 1) * 4 + i * 8, y, side * (HALF + 8 + (i % 2) * 4));
			spawns.add(race == UnitKind.Race.ZERG ? Spawn.guard(parked[i], at, middle) : dev.starmine.possess.Vehicles.parked(parked[i], at));
		}
		spawns.add(Spawn.guard(guard(), hull.at(x, y, side * (HALF + 4)), middle));
	}

	/** A ramp up to the next deck along a room (5 wide): half-steps (stairs, or the zerg's slabs), the next deck's floor opened over it. */
	private void ramp(int room, int side, int fy) {
		int x0 = x0(room);
		BlockState block = race == UnitKind.Race.ZERG ? dev.starmine.block.StarMineBlocks.ZERG_FLESH.defaultBlockState() : ArmorBlocks.wall(race, 1);
		for (int i = 0; i < 2 * DECK; i++) {
			int x = x0 + i, topY = fy + 1 + i / 2;
			for (int z = HALF + 2; z <= HALF + 6; z++) {
				int wz = side * z;
				for (int y = fy + 1; y < topY; y++) hull.put(x, y, wz, block);
				BlockState step = i % 2 == 1 ? block : race == UnitKind.Race.ZERG
					? Blocks.MUD_BRICK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM)
					: (race == UnitKind.Race.TERRAN ? Blocks.POLISHED_ANDESITE_STAIRS : Blocks.QUARTZ_STAIRS).defaultBlockState()
						.setValue(StairBlock.FACING, hull.facing(Direction.EAST));
				hull.put(x, topY, wz, step);
				for (int up = 1; up <= 4; up++) hull.put(x, topY + up, wz, Blocks.AIR.defaultBlockState());
				if (i < 2 * DECK - 1) hull.put(x, fy + DECK, wz, Blocks.AIR.defaultBlockState()); // the opening in the deck above
			}
		}
	}

	/**
	 * The boss hall: the top two decks of `HALL_ROOMS` rooms, open across (no walls, no floor between), its gates — 5 x 5
	 * doors of the race — across the spine at both ends; lights, a chest. The boss stands in its middle.
	 */
	private void hall(int room, boolean[][] special) {
		if (decks.size() < 2) return;
		int top = decks.size() - 1, fy = decks.get(top - 1), ceiling = decks.get(top) + DECK - 1;
		int x0 = x0(room) - 1, x1 = x0 + HALL_ROOMS * ROOM;
		for (int x = x0 + 1; x < x1; x++)
			for (int y = fy + 1; y <= ceiling; y++)
				for (int z = -REACH; z <= REACH; z++) if (hull.inside(x, y, z) && hull.inside(x, fy, z)) hull.put(x, y, z, Blocks.AIR.defaultBlockState());
		for (int end : new int[] {x0, x1}) // the gates, and a wall over them up to the hall's ceiling
			for (int z = -HALF; z <= HALF; z++)
				for (int y = fy + 1; y <= ceiling; y++) if (hull.inside(end, y, z)) hull.put(end, y, z, y <= fy + 5 ? door() : wall(end, y));
		for (int x = x0 + 3; x < x1; x += 6) // pillars of light along both sides
			for (int side = -1; side <= 1; side += 2) {
				int z = side * 9;
				for (int y = fy + 1; y <= ceiling; y++) if (hull.inside(x, y, z)) hull.put(x, y, z, y % 4 == 0 ? light() : ArmorBlocks.wall(race, 2));
			}
		boss = hull.at((x0 + x1) / 2, fy + 1, 0);
		painter.chest(hull.at(x1 - 3, fy + 1, 6), loot());
		painter.chest(hull.at(x0 + 3, fy + 1, -6), BuiltInLootTables.END_CITY_TREASURE);
	}

	private BlockState light() {
		return race == UnitKind.Race.ZERG ? dev.starmine.block.StarMineBlocks.GLOWING_ZERG_FLESH.defaultBlockState() : Blocks.SEA_LANTERN.defaultBlockState();
	}

	private net.minecraft.resources.ResourceKey<net.minecraft.world.level.storage.loot.LootTable> loot() {
		return switch (race) {
			case ZERG -> FactionLoot.ZERG;
			case TERRAN -> FactionLoot.TERRAN;
			case PROTOSS -> FactionLoot.PROTOSS;
		};
	}

	private UnitKind guard() {
		UnitKind[] kinds = switch (race) {
			case ZERG -> new UnitKind[] {UnitKind.ZERGLING, UnitKind.ROACH, UnitKind.HYDRALISK};
			case TERRAN -> new UnitKind[] {UnitKind.MARINE, UnitKind.MARINE, UnitKind.MARAUDER};
			case PROTOSS -> new UnitKind[] {UnitKind.ZEALOT, UnitKind.STALKER, UnitKind.ADEPT};
		};
		return kinds[random.nextInt(kinds.length)];
	}

	/** A room's things: lights, the race's furnishings against its walls, a chest more often than not, guards now and then. */
	private void furnish(int room, int side, int fy) {
		int x0 = x0(room), x1 = x0 + ROOM - 2, zIn = HALF + 2;
		int zOut = zIn;
		while (zOut < REACH && hull.inside((x0 + x1) / 2, fy + 1, side * (zOut + 1))) zOut++;
		if (zOut - zIn < 3) return;
		BlockState[] things = switch (race) {
			case TERRAN -> new BlockState[] {Blocks.BLAST_FURNACE.defaultBlockState(), Blocks.LECTERN.defaultBlockState(), Blocks.SMITHING_TABLE.defaultBlockState(),
				Blocks.BARREL.defaultBlockState(), Blocks.OBSERVER.defaultBlockState(), Blocks.IRON_BLOCK.defaultBlockState()};
			case PROTOSS -> new BlockState[] {Blocks.AMETHYST_BLOCK.defaultBlockState(), Blocks.CHISELED_QUARTZ_BLOCK.defaultBlockState(),
				Blocks.LAPIS_BLOCK.defaultBlockState(), Blocks.QUARTZ_PILLAR.defaultBlockState()};
			case ZERG -> new BlockState[] {Blocks.BONE_BLOCK.defaultBlockState(), Blocks.SHROOMLIGHT.defaultBlockState(), Blocks.NETHER_WART_BLOCK.defaultBlockState()};
		};
		for (int x = x0 + 1; x <= x1 - 1; x += 3)
			if (hull.inside(x, fy + 1, side * zOut)) hull.put(x, fy + 1, side * zOut, things[random.nextInt(things.length)]);
		int cy = fy + DECK; // a light in its ceiling
		if (hull.inside((x0 + x1) / 2, cy - 1, side * ((zIn + zOut) / 2))) hull.put((x0 + x1) / 2, cy, side * ((zIn + zOut) / 2), light());
		if (random.nextInt(10) < 6) painter.chest(hull.at(x0 + 1, fy + 1, side * (zOut - 1)), random.nextInt(3) == 0
			? (race == UnitKind.Race.PROTOSS ? BuiltInLootTables.END_CITY_TREASURE : BuiltInLootTables.SIMPLE_DUNGEON) : loot());
		if (race == UnitKind.Race.ZERG && random.nextInt(3) == 0) {
			UnitKind brood = random.nextBoolean() ? UnitKind.ZERGLING : UnitKind.HYDRALISK;
			BlockPos egg = hull.at((x0 + x1) / 2 + 2, fy + 1, side * ((zIn + zOut) / 2));
			spawns.add(new Spawn(UnitKind.EGG, egg, egg, brood, brood == UnitKind.ZERGLING ? 2 : 1));
		}
		if (random.nextInt(3) == 0) {
			BlockPos at = hull.at((x0 + x1) / 2, fy + 1, side * ((zIn + zOut) / 2));
			spawns.add(Spawn.guard(guard(), at, at));
		}
	}
}
