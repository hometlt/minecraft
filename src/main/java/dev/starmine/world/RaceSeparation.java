package dev.starmine.world;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * The wild races keep apart (owner 16.10): what the world puts down of one race — a pack or a dungeon's
 * guards waking ({@link DungeonHeart}), a claim ({@link dev.starmine.ai.Territory}), a nydus worm
 * ({@link Nydus}) — never comes within {@link #GAP} blocks of the wild of another race: its bases and their
 * planned sites, their claims, their buildings and their guards. Waves, raids and drops are attacks and go
 * where they are sent; players' bases and units do not count (a player brings their own armies anywhere).
 */
public final class RaceSeparation {
	/** Blocks between the wild of two races. */
	public static final int GAP = 96;

	private RaceSeparation() {
	}

	/** The other race whose wild stands within {@link #GAP} of `pos`, or null when `race` may appear there. */
	public static @Nullable UnitKind.Race foreignNear(ServerLevel level, UnitKind.Race race, BlockPos pos) {
		double gap = (double) GAP * GAP;
		HiveSavedData data = HiveSavedData.get(level.getServer());
		String dimension = level.dimension().location().toString();
		for (Hive hive : data.all()) {
			if (hive.owner != null || hive.race == null || hive.race == race || !hive.dimension.equals(dimension)) continue;
			if (horizontal(hive.hatchery, pos) < gap) return hive.race;
			for (BlockPos claim : hive.territory) if (horizontal(claim, pos) < gap) return hive.race;
		}
		if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD) { // the main bases' sites, before they are built
			if (race != UnitKind.Race.ZERG && data.spawnHiveSite != null && horizontal(data.spawnHiveSite, pos) < gap) return UnitKind.Race.ZERG;
			for (Map.Entry<String, BlockPos> site : data.structureSites.entrySet()) {
				UnitKind.Race other = raceOf(site.getKey());
				if (other != null && other != race && horizontal(site.getValue(), pos) < gap) return other;
			}
		}
		return level.getEntitiesOfClass(StarUnit.class, new AABB(pos).inflate(GAP, 48, GAP),
				unit -> unit.isAlive() && unit.kind.race != race && unit.owner().isEmpty() && (unit.kind.structure() || unit.nest() != null)
					&& horizontal(unit.blockPosition(), pos) < gap)
			.stream().findFirst().map(unit -> unit.kind.race).orElse(null);
	}

	private static double horizontal(BlockPos a, BlockPos b) {
		double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
		return dx * dx + dz * dz;
	}

	private static @Nullable UnitKind.Race raceOf(String key) {
		try {
			return UnitKind.Race.valueOf(key);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
