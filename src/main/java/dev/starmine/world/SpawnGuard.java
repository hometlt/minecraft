package dev.starmine.world;

import dev.starmine.creep.CreepSources;
import dev.starmine.entity.StarUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

/**
 * Where monsters do not spawn (owner 16.10): near creep tumors and near any building of any race
 * (hatcheries and eggs too). Lit places — the bases' lamps, the glowing flesh of the leviathan, the
 * buildings' own glow — are kept clear by the game's light rule already. Checked by
 * {@link dev.starmine.mixin.SpawnGuardMixin} on every natural spawn.
 */
public final class SpawnGuard {
	/** Blocks round a building and round a creep tumor where no monster spawns. */
	public static final int BUILDING_RADIUS = 24, TUMOR_RADIUS = 16;

	private SpawnGuard() {
	}

	/** Whether a natural spawn of `mob` at `pos` is refused. */
	public static boolean refuses(ServerLevel level, Mob mob, BlockPos pos) {
		if (!(mob instanceof Enemy) || mob instanceof StarUnit) return false;
		for (BlockPos tumor : CreepSources.tumors(level).all())
			if (tumor.distSqr(pos) <= (double) TUMOR_RADIUS * TUMOR_RADIUS) return true;
		return !level.getEntitiesOfClass(StarUnit.class, new AABB(pos).inflate(BUILDING_RADIUS),
			unit -> unit.isAlive() && unit.kind.structure()).isEmpty();
	}
}
