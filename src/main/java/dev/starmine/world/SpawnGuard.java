package dev.starmine.world;

import dev.starmine.creep.CreepSources;
import dev.starmine.entity.StarUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

/**
 * Where monsters do not spawn (owner 16.10): near creep tumors; owner 17.10: within {@link #BUILDING_RADIUS}
 * of any unit or building of any race, wild or a player's (hatcheries and eggs too); within
 * {@link #LAMP_RADIUS} blocks of a map lamp, and on the ground units walk in a laid-over map
 * ({@link MapOverlay#walked} — its caves, cliffs and rocks keep the game's rules). Lit places — the bases' lamps, the glowing flesh of the leviathan, the
 * buildings' own glow — are kept clear by the game's light rule already. Checked by
 * {@link dev.starmine.mixin.SpawnGuardMixin} on every natural spawn.
 */
public final class SpawnGuard {
	/** Blocks round a unit or building, round a creep tumor and round a map lamp where no monster spawns. */
	public static final int BUILDING_RADIUS = 30, TUMOR_RADIUS = 16, LAMP_RADIUS = 10;

	private SpawnGuard() {
	}

	/** Whether a natural spawn of `mob` at `pos` is refused. */
	public static boolean refuses(ServerLevel level, Mob mob, BlockPos pos) {
		if (!(mob instanceof Enemy) || mob instanceof StarUnit) return false;
		if (MapOverlay.walked(pos)) return true;
		if (level.getPoiManager().getInRange(type -> type.is(dev.starmine.block.StarMineBlocks.MAP_LAMP_POI), pos, LAMP_RADIUS,
			net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY).findAny().isPresent()) return true;
		for (BlockPos tumor : CreepSources.tumors(level).all())
			if (tumor.distSqr(pos) <= (double) TUMOR_RADIUS * TUMOR_RADIUS) return true;
		if (dev.starmine.ai.Territory.claimed(level, pos)) return true; // a race's territory
		return !level.getEntitiesOfClass(StarUnit.class, new AABB(pos).inflate(BUILDING_RADIUS),
			StarUnit::isAlive).isEmpty();
	}
}
