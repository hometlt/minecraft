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
import org.jetbrains.annotations.Nullable;

/**
 * The races' bases in the other worlds (owner 17.10), the same as the overworld's three — a base, its army, its AI — each
 * with its hero, Amun's until beaten ({@link HeroLiberation}), who carries that world's part of the artifact's key:
 * - the Nether: three bases near the place under the first player's spawn ({@link #NETHER_DISTANCE} from it, an eighth
 *   of the overworld's distance), carved into the Nether's caves — Zagara's lair, Tosh's base, Zeratul's;
 * - the End: three bases far from the dragon ({@link #END_DISTANCE} from the middle), on islands of their own if the
 *   void is there — Stukov's, Horner's and Selendis's, their heroic ships over them.
 * Each is built when a player comes within {@link RaceBases#TRIGGER} (game rule `starmineSpawnBases`); keys
 * "NETHER:race" and "END:race" in the world's structures.
 */
public final class DimensionBases {
	public static final int NETHER_DISTANCE = 96, END_DISTANCE = 1300;

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
				boolean near = level.players().stream().anyMatch(p -> !p.isSpectator()
					&& Mth.square(p.getX() - column.getX()) + Mth.square(p.getZ() - column.getZ()) < RaceBases.TRIGGER * RaceBases.TRIGGER);
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
