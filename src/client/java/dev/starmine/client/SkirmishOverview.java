package dev.starmine.client;

import dev.starmine.world.Skirmish;

/**
 * The skirmish map painted by the server (owner 22.10: the whole map explored and on the minimap with its relief, the
 * fog of war over it): colours (ARGB) per column of the map's bounds, filled as its slices come.
 */
final class SkirmishOverview {
	private static int[] argb;
	private static int minX, minZ, width, height;

	private SkirmishOverview() {
	}

	static void receive(Skirmish.Overview slice) {
		if (slice.width() <= 0 || slice.height() <= 0 || slice.width() > 2048 || slice.height() > 2048) return;
		if (argb == null || minX != slice.minX() || minZ != slice.minZ() || width != slice.width() || height != slice.height()) {
			minX = slice.minX();
			minZ = slice.minZ();
			width = slice.width();
			height = slice.height();
			argb = new int[width * height];
		}
		int from = slice.row() * width;
		if (from < 0 || from >= argb.length) return;
		System.arraycopy(slice.argb(), 0, argb, from, Math.min(slice.argb().length, argb.length - from));
	}

	static void clear() {
		argb = null;
	}

	/** The colour at a column (ARGB), 0 where none is known. */
	static int at(int x, int z) {
		int[] colours = argb;
		if (colours == null) return 0;
		int c = x - minX, r = z - minZ;
		if (c < 0 || r < 0 || c >= width || r >= height) return 0;
		return colours[r * width + c];
	}

	/** Whether a column lies in the skirmish map (all of it is explored there). */
	static boolean inside(int x, int z) {
		int[] b = SkirmishScreen.bounds;
		return b != null && x >= b[0] && z >= b[1] && x < b[2] && z < b[3];
	}
}
