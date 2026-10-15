package dev.starmine.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Where a unit's body fits (CLOUD_TASK_2 stage 1): units doubled in size, and a thor produced a block from
 * its factory's edge, an ultralisk hatched by its hatchery or a queen beside it came out inside the
 * building or a wall. A spot is a feet position with ground under it (for a walker) and no block and no
 * StarMine building in the body's box.
 */
public final class Spots {
	/** How far around a wanted point a free spot is searched, in blocks. */
	public static final int SEARCH = 8;

	private Spots() {
	}

	/** The nearest spot to `point` where a body of `kind` fits, searched in rings; `point` itself when none is found. */
	public static Vec3 near(Level level, UnitKind kind, Vec3 point) {
		BlockPos start = BlockPos.containing(point);
		for (int r = 0; r <= SEARCH; r++)
			for (int dy = 3; dy >= -4; dy--)
				for (int dx = -r; dx <= r; dx++)
					for (int dz = -r; dz <= r; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
						Vec3 at = r == 0 && dy == 0 ? point : Vec3.atBottomCenterOf(start.offset(dx, dy, dz));
						if (fits(level, kind, at)) return at;
					}
		return point;
	}

	/** A spot `distance` blocks from `centre` at `angle` (radians) where a body of `kind` fits, from the ground there. */
	public static Vec3 outside(Level level, UnitKind kind, Vec3 centre, double angle, double distance) {
		Vec3 at = new Vec3(centre.x + Math.cos(angle) * distance, centre.y, centre.z + Math.sin(angle) * distance);
		return near(level, kind, at);
	}

	/** Whether a body of `kind` stands free at these feet: no blocks or buildings in it, ground under a walker. */
	public static boolean fits(Level level, UnitKind kind, Vec3 feet) {
		double half = kind.width / 2;
		AABB box = new AABB(feet.x - half, feet.y + 0.01, feet.z - half, feet.x + half, feet.y + kind.height, feet.z + half);
		if (!level.noCollision(box)) return false;
		if (kind.flying) return true;
		AABB under = new AABB(feet.x - half, feet.y - 0.5, feet.z - half, feet.x + half, feet.y, feet.z + half);
		return !level.noCollision(under);
	}
}
