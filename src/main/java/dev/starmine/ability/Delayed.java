package dev.starmine.ability;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Effects due a few seconds later (owner 14.10): a tactical jump after its warning, later the ravager's
 * bile, a nuke, a psionic storm's pulses. Kept in memory only: a delayed effect pending when the server
 * stops does not happen (they last seconds).
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
