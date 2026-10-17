package dev.starmine.world;

import dev.starmine.entity.UnitKind;
import dev.starmine.hive.HiveSavedData;
import dev.starmine.hive.HiveStructure;
import dev.starmine.mineral.Minerals;
import dev.starmine.vespene.Vespene;
import dev.starmine.vespene.VespeneBlock;
import dev.starmine.vespene.VespeneRootBlockEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Space platforms in the stratosphere (owner 17.10): built as players come near — one region of {@link #REGION}
 * blocks in two holds one, clear of the races' ships.
 *
 * - The deck: steel plates with a railing and lamps; on it a field of mineral crystals and a vespene store — a tank
 *   of vespene inside the platform, glazed, with outlets in the deck under ventilation grates (a gas building goes on
 *   an outlet; emptied, it fills again from the tank, as an outlet of a vein does, but never rises like a volcano).
 * - Inside: lit technical passages (a cross and a ring under the deck, a ladder down from a hatch), a hangar at one
 *   edge open to the sky, and dark maintenance tunnels.
 * - Wild troops of a race on the deck, and more in the dark tunnels.
 */
public final class SpacePlatforms {
	public static final int REGION = 320, KEEP_OFF_SHIPS = 150;
	public static final double CHANCE = 0.5;
	/** The deck's walking height range. */
	public static final int LOW = 95, HIGH = 125;

	/** One platform: its deck centre (the floor under the walking height), half its side, its race of wild troops. */
	public record Platform(String key, BlockPos centre, int half, UnitKind.Race race) {
	}

	private SpacePlatforms() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(SpacePlatforms::tick);
	}

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % 100 != 37) return;
		ServerLevel sky = Stratosphere.level(server);
		if (sky == null || sky.players().isEmpty()) return;
		HiveSavedData data = HiveSavedData.get(server);
		for (ServerPlayer player : sky.players()) {
			int prx = Math.floorDiv(player.getBlockX(), REGION), prz = Math.floorDiv(player.getBlockZ(), REGION);
			for (int rx = prx - 1; rx <= prx + 1; rx++)
				for (int rz = prz - 1; rz <= prz + 1; rz++) {
					Platform platform = plan(sky.getSeed(), rx, rz);
					if (platform == null || data.structuresDone.contains(platform.key()) || nearShip(data, platform)) continue;
					data.structuresDone.add(platform.key());
					data.setDirty();
					build(sky, platform);
					return; // one a while: a platform is a lot of blocks
				}
		}
	}

	/** The platform of a region, or null (the same for a world's seed). */
	public static @Nullable Platform plan(long seed, int rx, int rz) {
		RandomSource random = RandomSource.create(seed ^ (rx * 73428767L + rz * 912931L) ^ 0x5BACE5L);
		if (random.nextDouble() >= CHANCE) return null;
		int half = 18 + random.nextInt(8);
		int x = rx * REGION + 60 + random.nextInt(REGION - 120), z = rz * REGION + 60 + random.nextInt(REGION - 120);
		int y = LOW + random.nextInt(HIGH - LOW);
		return new Platform("PLAT:" + rx + ":" + rz, new BlockPos(x, y, z), half, UnitKind.Race.values()[random.nextInt(3)]);
	}

	/** Whether a platform would be in the way of a race's ship (over a main base). */
	static boolean nearShip(HiveSavedData data, Platform platform) {
		List<BlockPos> sites = new ArrayList<>(data.structureSites.values());
		if (data.spawnHiveSite != null) sites.add(data.spawnHiveSite);
		for (BlockPos site : sites)
			if (Math.hypot(site.getX() - platform.centre().getX(), site.getZ() - platform.centre().getZ()) < KEEP_OFF_SHIPS) return true;
		return false;
	}

	/** Builds a platform over the next ticks; its troops wait to be put in the world. */
	public static void build(ServerLevel level, Platform platform) {
		RandomSource random = RandomSource.create(level.getSeed() ^ platform.centre().asLong());
		Painter painter = Painter.deferred(level, random);
		BlockPos c = platform.centre();
		int h = platform.half();
		BlockState hull = Blocks.GRAY_CONCRETE.defaultBlockState(), plate = Blocks.POLISHED_DEEPSLATE.defaultBlockState(),
			stripe = Blocks.YELLOW_CONCRETE.defaultBlockState(), steel = Blocks.IRON_BLOCK.defaultBlockState(),
			light = Blocks.SEA_LANTERN.defaultBlockState(), air = Blocks.AIR.defaultBlockState(), rail = Blocks.IRON_BARS.defaultBlockState();
		// the hull (8 deep) and the deck, its corners cut
		for (int dx = -h; dx <= h; dx++)
			for (int dz = -h; dz <= h; dz++) {
				if (Math.abs(dx) + Math.abs(dz) > h * 1.6) continue;
				boolean edge = Math.abs(dx) == h || Math.abs(dz) == h || Math.abs(dx) + Math.abs(dz) >= h * 1.6 - 1;
				for (int dy = -8; dy <= -1; dy++) painter.set(c.offset(dx, dy, dz), dy == -1 ? (edge ? stripe : (dx + dz) % 7 == 0 ? steel : plate) : hull);
				if (edge) painter.set(c.offset(dx, 0, dz), rail);
				else for (int up = 0; up <= 4; up++) painter.set(c.offset(dx, up, dz), air);
				if (edge && (dx + dz) % 6 == 0) { // lamps on posts along the railing
					painter.set(c.offset(dx, 0, dz), steel);
					painter.set(c.offset(dx, 1, dz), light);
				}
			}
		// the lit passages under the deck: a cross and a ring, three high
		for (int i = -h + 3; i <= h - 3; i++)
			for (int w = -1; w <= 1; w++) {
				corridor(painter, c.offset(i, -6, w), (i + w) % 5 == 0 ? light : null);
				corridor(painter, c.offset(w, -6, i), (i + w) % 5 == 0 ? light : null);
			}
		int ring = h / 2;
		for (int i = -ring; i <= ring; i++) {
			for (BlockPos at : List.of(c.offset(i, -6, -ring), c.offset(i, -6, ring), c.offset(-ring, -6, i), c.offset(ring, -6, i)))
				corridor(painter, at, i % 4 == 0 ? light : null);
		}
		// the hatch and its ladder down from the deck
		BlockPos hatch = c.offset(2, 0, 2);
		for (int y = -6; y <= -1; y++) {
			painter.set(hatch.offset(0, y, 0), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
			painter.set(hatch.offset(0, y, -1), steel);
		}
		// the hangar: at the west edge, open to the sky, lit
		for (int dx = -h; dx <= -h + 10; dx++)
			for (int dz = -6; dz <= 6; dz++)
				for (int dy = -7; dy <= -2; dy++) painter.set(c.offset(dx, dy, dz), dy == -2 && dx % 4 == 0 && dz % 4 == 0 ? light : air);
		for (int dz = -6; dz <= 6; dz++) for (int dy = -7; dy <= -2; dy++) painter.set(c.offset(-h + 11, dy, dz), hull);
		for (int dy = -5; dy <= -3; dy++) for (int dz = -1; dz <= 1; dz++) painter.set(c.offset(-h + 11, dy, dz), air); // into the passages
		// the dark tunnels: from the ring out to the east edge, unlit
		List<BlockPos> dark = new ArrayList<>();
		for (int dx = ring + 1; dx <= h - 3; dx++)
			for (int dz = ring - 3; dz <= ring - 1; dz++) {
				corridor(painter, c.offset(dx, -6, dz), null);
				if (dx == h - 4 && dz == ring - 2) dark.add(c.offset(dx, -6, dz));
			}
		for (int dz = -ring + 1; dz <= ring - 3; dz++)
			for (int dx = h - 5; dx <= h - 3; dx++) {
				corridor(painter, c.offset(dx, -6, dz), null);
				if (dz == -ring + 2 && dx == h - 4) dark.add(c.offset(dx, -6, dz));
			}
		// the vespene store: a glazed tank in the hull, its outlets in the deck under grates
		BlockPos tank = c.offset(-h / 2, 0, h / 2);
		List<BlockPos> channel = new ArrayList<>(), outlets = new ArrayList<>();
		BlockState vespene = Vespene.VESPENE.defaultBlockState();
		for (int dy = -5; dy <= -1; dy++)
			for (int dx = -3; dx <= 3; dx++)
				for (int dz = -2; dz <= 2; dz++) {
					BlockPos at = tank.offset(dx, dy, dz);
					boolean wall = Math.abs(dx) == 3 || Math.abs(dz) == 2;
					boolean outlet = dy == -1 && dz == 0 && (dx == -1 || dx == 1);
					if (dy == -1 && !outlet) {
						painter.set(at, wall ? steel : Blocks.TINTED_GLASS.defaultBlockState());
						continue;
					}
					if (wall) {
						painter.set(at, Blocks.GLASS.defaultBlockState());
						continue;
					}
					painter.set(at, vespene.setValue(VespeneBlock.OUTLET, outlet));
					(outlet ? outlets : channel).add(at.immutable());
				}
		for (BlockPos outlet : List.of(tank.offset(-1, -1, 0), tank.offset(1, -1, 0))) // ventilation: a grate of bars round each outlet, lit
			for (int dx = -1; dx <= 1; dx++)
				for (int dz = -1; dz <= 1; dz++)
					if (dx != 0 || dz != 0) painter.set(outlet.offset(dx, 1, dz), (dx == 0 || dz == 0) ? air : Blocks.CHAIN.defaultBlockState());
		BlockPos root = tank.below(7);
		painter.set(root, Vespene.ROOT.defaultBlockState());
		painter.then(() -> {
			if (level.getBlockEntity(root) instanceof VespeneRootBlockEntity vein) {
				vein.setVein(channel, outlets);
				vein.setLake(false);
			}
		});
		// the minerals: an arc on the deck's far side
		for (int i = 0; i < 5; i++) {
			double a = Math.PI * 1.75 + (i - 2) * 0.3;
			BlockPos core = c.offset(Mth.floor(Math.cos(a) * (h - 6)), 0, Mth.floor(Math.sin(a) * (h - 6)));
			painter.then(() -> Minerals.place(level, core, (pos, state) -> level.setBlock(pos, state, 2)));
		}
		// the wild troops: a squad on the deck, more in the dark
		HiveSavedData data = HiveSavedData.get(level.getServer());
		String dim = level.dimension().location().toString();
		UUID nobody = UUID.randomUUID();
		List<UnitKind> squad = squad(platform.race());
		for (int i = 0; i < squad.size(); i++) {
			BlockPos at = c.offset(4 + i % 3 * 2, 0, -4 - i / 3 * 2);
			data.pending.add(new HiveStructure.Pending(nobody, squad.get(i), at, at, null, 0, dim));
		}
		for (BlockPos at : dark)
			for (int i = 0; i < 2; i++) {
				UnitKind kind = squad.get(random.nextInt(squad.size()));
				if (!kind.flying) data.pending.add(new HiveStructure.Pending(nobody, kind, at.offset(0, 0, i), at, null, 0, dim));
			}
		data.setDirty();
		painter.start();
	}

	/** A cell of a passage, three high, with a light in its ceiling or none. */
	private static void corridor(Painter painter, BlockPos floor, @Nullable BlockState light) {
		for (int up = 0; up <= 2; up++) painter.set(floor.above(up), Blocks.AIR.defaultBlockState());
		if (light != null) painter.set(floor.above(3), light);
	}

	private static List<UnitKind> squad(UnitKind.Race race) {
		return switch (race) {
			case ZERG -> List.of(UnitKind.ZERGLING, UnitKind.ZERGLING, UnitKind.ROACH, UnitKind.HYDRALISK, UnitKind.ZERGLING);
			case TERRAN -> List.of(UnitKind.MARINE, UnitKind.MARINE, UnitKind.MARAUDER, UnitKind.MARINE, UnitKind.GHOST);
			case PROTOSS -> List.of(UnitKind.ZEALOT, UnitKind.ZEALOT, UnitKind.STALKER, UnitKind.ADEPT);
		};
	}
}
