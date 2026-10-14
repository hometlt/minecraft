package dev.starmine.ability;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Effects due a few seconds later (owner 14.10): a tactical jump after its warning, the ravager's bile,
 * a KD8 charge, a purification nova, the pulses of a psionic storm, a blinding cloud, a time warp or a
 * parasitic bomb. Kept in memory only: a delayed effect pending when the server stops does not happen
 * (they last seconds). What lasts longer is saved with the unit ({@link Status}) or in blocks (a force
 * field's scheduled tick).
 */
public final class Delayed {
	private record Task(ServerLevel level, long due, Runnable effect) {
	}

	private static final List<Task> TASKS = new ArrayList<>();

	private Delayed() {
	}

	public static void after(ServerLevel level, int ticks, Runnable effect) {
		TASKS.add(new Task(level, level.getGameTime() + ticks, effect));
	}

	/** A pulse every `every` ticks, `times` times, the first at once (a storm, a cloud, a parasitic bomb); it gets the pulse's number. */
	public static void repeat(ServerLevel level, int every, int times, java.util.function.IntConsumer pulse) {
		for (int i = 0; i < times; i++) {
			int n = i;
			TASKS.add(new Task(level, level.getGameTime() + (long) every * i, () -> pulse.accept(n)));
		}
	}

	/** A check every `every` ticks, at most `times` times, the first at once, while it returns true (a stasis ward waiting for a foe). */
	public static void whileTrue(ServerLevel level, int every, int times, java.util.function.BooleanSupplier check) {
		after(level, 0, new Runnable() {
			private int done;

			@Override
			public void run() {
				if (++done <= times && check.getAsBoolean()) after(level, every, this);
			}
		});
	}

	/** Server tick: runs what is due. */
	public static void tick(MinecraftServer server) {
		if (TASKS.isEmpty()) return;
		List<Task> due = new ArrayList<>();
		for (Iterator<Task> it = TASKS.iterator(); it.hasNext(); ) {
			Task task = it.next();
			if (task.level.getServer() != server) { // a stopped server's tasks
				it.remove();
				continue;
			}
			if (task.level.getGameTime() >= task.due) {
				it.remove();
				due.add(task);
			}
		}
		for (Task task : due) task.effect.run();
	}
}
