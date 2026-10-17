package dev.starmine.client;

import dev.starmine.world.VisionBlocker;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The sight blockers about the client ({@link VisionBlocker}, owner 17.10): what an eye outside a
 * blocker's box cannot see through or into it, for the command view's units ({@link CommandView}),
 * its fog of war on the ground and the minimap. An eye inside a box sees out of it (SC2's rule).
 * The boxes are read from the level's entities every {@link #REFRESH} ticks.
 */
final class VisionBlockers {
	private static final int REFRESH = 10;
	private static List<AABB> boxes = List.of();
	private static long refreshed = Long.MIN_VALUE;

	private VisionBlockers() {
	}

	static void refresh(ClientLevel level) {
		long now = level.getGameTime();
		if (now - refreshed < REFRESH && now >= refreshed) return;
		refreshed = now;
		List<AABB> found = new ArrayList<>();
		for (Entity entity : level.entitiesForRendering()) if (entity instanceof VisionBlocker blocker) found.add(blocker.hiding());
		boxes = found;
	}

	/** Whether a blocker hides `to` from an eye at `from` (3D: the units). */
	static boolean hides(Vec3 from, Vec3 to) {
		for (AABB box : boxes)
			if (!box.contains(from) && (box.contains(to) || box.clip(from, to).isPresent())) return true;
		return false;
	}

	/** Whether a blocker hides the column (x, z) from an eye over (fromX, fromZ) (the ground: fog and minimap). */
	static boolean hides(double fromX, double fromZ, double x, double z) {
		for (AABB box : boxes) {
			boolean inside = fromX >= box.minX && fromX <= box.maxX && fromZ >= box.minZ && fromZ <= box.maxZ;
			if (inside) continue;
			if (x >= box.minX && x <= box.maxX && z >= box.minZ && z <= box.maxZ) return true;
			if (crosses(fromX, fromZ, x, z, box)) return true;
		}
		return false;
	}

	/** Whether the segment crosses the box's rectangle (slab test on x and z). */
	private static boolean crosses(double x0, double z0, double x1, double z1, AABB box) {
		double enter = 0, leave = 1;
		double[][] axes = {{x0, x1 - x0, box.minX, box.maxX}, {z0, z1 - z0, box.minZ, box.maxZ}};
		for (double[] axis : axes) {
			double start = axis[0], delta = axis[1];
			if (Math.abs(delta) < 1e-9) {
				if (start < axis[2] || start > axis[3]) return false;
				continue;
			}
			double a = (axis[2] - start) / delta, b = (axis[3] - start) / delta;
			enter = Math.max(enter, Math.min(a, b));
			leave = Math.min(leave, Math.max(a, b));
			if (enter > leave) return false;
		}
		return true;
	}
}
