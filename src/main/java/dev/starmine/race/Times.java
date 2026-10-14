package dev.starmine.race;

/** SC2 times to ticks: build times at Faster (real seconds), and Normal game seconds (/1.4) for the older zerg numbers. */
final class Times {
	private Times() {
	}

	static int seconds(double fasterSeconds) {
		return (int) Math.round(fasterSeconds * 20);
	}

	static int normal(double normalSeconds) {
		return (int) Math.round(normalSeconds / 1.4 * 20);
	}
}
