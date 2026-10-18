package dev.starmine.world;

import dev.starmine.StarMine;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import dev.starmine.hive.HiveStructure;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * The races' bases in the other worlds (owner 17.10), the same as the overworld's three — a base, its army, its AI — each
 * with its hero, Amun's until beaten ({@link HeroLiberation}), who carries that world's part of the artifact's key:
 * - the Nether: three bases near the place under the first player's spawn ({@link #NETHER_DISTANCE} from it, an eighth
 *   of the overworld's distance), each in a great hall carved into the Nether's rock ({@link #hall}) — Zagara's lair,
 *   Tosh's base, Zeratul's —, its hero in the hall;
 * - the End: three bases far from the dragon ({@link #END_DISTANCE} from the middle), on islands of their own if the
 *   void is there — Stukov's, Horner's and Selendis's, their heroic ships over them (no dungeons: the heroes are ships).
 * Each is built when a player comes within {@link RaceBases#TRIGGER} (game rule `starmineSpawnBases`); keys
 * "NETHER:race" and "END:race" in the world's structures.
 */
public final class DimensionBases {
	public static final int NETHER_DISTANCE = 96, END_DISTANCE = 1300;
	/** A Nether base's hall: its radius and the height of its dome. */
	public static final int HALL_RADIUS = 26, HALL_HEIGHT = 16;

	private DimensionBases() {
	}

	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 15 || !server.overworld().getGameRules().getBoolean(StarMine.SPAWN_BASES)) return;
		HiveSavedData data = HiveSavedData.get(server);
		for (boolean end : new boolean[] {false, true}) {
			ServerLevel level = server.getLevel(end ? Level.END : Level.NETHER);
			if (level == null || level.players().isEmpty()) continue;
			for (UnitKind.Race race : UnitKind.Race.values()) {
				String key = (end ? "END:" : "NETHER:") + race.name();
				if (data.structuresDone.contains(key)) continue;
				BlockPos column = column(data, race, end);
				boolean near = RaceBases.scouted(level, column.getX(), column.getZ(), RaceBases.TRIGGER); // a body, a camera or a unit
				if (!near) continue;
				data.structuresDone.add(key);
				data.setDirty();
				build(level, race, column, end);
				return; // one a tick: building loads chunks
			}
		}
	}

	/** Where a race's base of the Nether or the End stands (its column). */
	public static BlockPos column(HiveSavedData data, UnitKind.Race race, boolean end) {
		double degrees = switch (race) { case ZERG -> 60; case PROTOSS -> 180; case TERRAN -> 300; };
		double angle = Math.toRadians(data.baseHeading + degrees);
		BlockPos origin = end ? BlockPos.ZERO : data.baseOrigin == null ? BlockPos.ZERO
			: new BlockPos(data.baseOrigin.getX() / 8, 0, data.baseOrigin.getZ() / 8);
		int distance = end ? END_DISTANCE : NETHER_DISTANCE;
		return origin.offset((int) Math.round(-Math.sin(angle) * distance), 0, (int) Math.round(Math.cos(angle) * distance));
	}

	/** Builds the base at `column` and sets its hero by it. */
	public static @Nullable UUID build(ServerLevel level, UnitKind.Race race, BlockPos column, boolean end) {
		RandomSource random = RandomSource.create(level.getSeed() ^ column.asLong() ^ (end ? 0xE4DL : 0x4E7L));
		int ground = HiveStructure.ground(level, column.getX(), column.getZ());
		if (end && ground <= level.getMinBuildHeight() + 4) ground = island(level, column); // the void there: an island of its own
		if (!end) hall(level, new BlockPos(column.getX(), ground, column.getZ()), random); // owner 17.10: a great hall, the base and its hero in it
		BlockPos site = new BlockPos(column.getX(), ground, column.getZ());
		UUID base;
		if (race == UnitKind.Race.ZERG) {
			HiveStructure.build(level, site, random);
			Hive lair = HiveSavedData.get(level.getServer()).all().stream()
				.filter(h -> h.owner == null && h.dimension.equals(level.dimension().location().toString()) && h.hatchery.distSqr(site) < 24 * 24)
				.findFirst().orElse(null);
			base = lair == null ? null : lair.id;
		} else base = RaceBases.build(level, race, site, random, false).base();
		if (base == null) return null;
		Hive hive = HiveSavedData.get(level.getServer()).get(base);
		if (hive != null) hive.race = race;
		UnitKind hero = UnitKind.heroOf(race, end);
		BlockPos centre = hive != null ? hive.hatchery : site;
		BlockPos post = hero.flying ? centre.above(14) : centre.offset((int) (race.rules().main().width / 2) + 5, 0, 0);
		HiveSavedData data = HiveSavedData.get(level.getServer());
		data.pending.add(new HiveStructure.Pending(base, hero, post, centre, null, 0, level.dimension().location().toString()));
		data.setDirty();
		return base;
	}

	/**
	 * A great hall in the Nether's rock for a base and its hero (owner 17.10): a dome {@link #HALL_RADIUS} wide and
	 * {@link #HALL_HEIGHT} high over a level floor at `floor` (blackstone where it was lava or air), its lava walled off
	 * with basalt, shroomlights round it. No unbreakable floor: there is nowhere to fall.
	 */
	static void hall(ServerLevel level, BlockPos floor, RandomSource random) {
		int top = Math.min(level.getMinBuildHeight() + level.dimensionType().logicalHeight() - 4, floor.getY() + HALL_HEIGHT);
		for (int dx = -HALL_RADIUS - 1; dx <= HALL_RADIUS + 1; dx++)
			for (int dz = -HALL_RADIUS - 1; dz <= HALL_RADIUS + 1; dz++) {
				double r = Math.sqrt(dx * dx + dz * dz);
				if (r > HALL_RADIUS + 1) continue;
				int x = floor.getX() + dx, z = floor.getZ() + dz;
				boolean rim = r > HALL_RADIUS;
				int height = rim ? 0 : Math.max(5, (int) Math.round(HALL_HEIGHT * Math.sqrt(1 - (r / HALL_RADIUS) * (r / HALL_RADIUS))));
				int roof = Math.min(top, floor.getY() + height);
				for (int y = floor.getY() - 2; y <= Math.min(top + 1, roof + 1); y++) {
					BlockPos at = new BlockPos(x, y, z);
					BlockState there = level.getBlockState(at);
					if (there.is(Blocks.BEDROCK)) continue;
					if (y < floor.getY()) { // the floor: solid, no lava under it
						if (there.isAir() || !there.getFluidState().isEmpty()) level.setBlock(at, Blocks.BLACKSTONE.defaultBlockState(), Block.UPDATE_CLIENTS);
					} else if (!rim && y <= roof) { // the room
						if (!there.isAir()) level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
					} else if (!there.getFluidState().isEmpty()) level.setBlock(at, Blocks.BASALT.defaultBlockState(), Block.UPDATE_CLIENTS); // its shell keeps lava out
				}
				if (rim) for (int y = floor.getY(); y <= roof + HALL_HEIGHT; y++) { // the wall round it: lava walled off
					BlockPos at = new BlockPos(x, y, z);
					if (!level.getBlockState(at).getFluidState().isEmpty()) level.setBlock(at, Blocks.BASALT.defaultBlockState(), Block.UPDATE_CLIENTS);
				}
			}
		for (int i = 0; i < 16; i++) { // lights on the floor round the hall's edge
			double a = Math.PI * 2 * i / 16 + random.nextDouble() * 0.2;
			int r = HALL_RADIUS - 2;
			level.setBlock(floor.offset((int) Math.round(Math.cos(a) * r), -1, (int) Math.round(Math.sin(a) * r)), Blocks.SHROOMLIGHT.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	/** An island of end stone for a base where the End is empty: a disc thinning downwards; its top's height. */
	static int island(ServerLevel level, BlockPos column) {
		int top = 64, radius = 46;
		for (int dx = -radius; dx <= radius; dx++)
			for (int dz = -radius; dz <= radius; dz++) {
				double r = Math.sqrt(dx * dx + dz * dz);
				if (r > radius) continue;
				int depth = 3 + (int) ((radius - r) / 3);
				for (int dy = 1; dy <= depth; dy++)
					level.setBlock(new BlockPos(column.getX() + dx, top - dy, column.getZ() + dz), Blocks.END_STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
		return top;
	}
}
