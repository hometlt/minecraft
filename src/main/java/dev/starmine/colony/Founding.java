package dev.starmine.colony;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveLogic;
import dev.starmine.hive.HiveSavedData;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * A main building (hatchery, command center, nexus) founds or joins a base (owner 14.10,
 * docs/ARCHITECTURE.md): one of a player — their only base in the dimension, whatever the race, with
 * shared resources and supply — or, without an owner, a wild base of its race run by that race's AI.
 * A fresh base gets 50 minerals and 4 workers of the main building's race (SC2-like).
 */
public final class Founding {
	public static final int START_WORKERS = 4, START_MINERALS = 50;

	private Founding() {
	}

	/** Founds or joins the base of a main building that has none yet; null when it cannot (it then stays baseless). */
	public static Hive found(StarUnit main, ServerLevel level) {
		HiveSavedData data = HiveSavedData.get(level.getServer());
		String dimension = level.dimension().location().toString();
		UUID owner = main.owner().orElse(null);
		Hive base = owner == null ? null : data.baseOf(owner, dimension);
		boolean fresh = base == null;
		if (fresh) {
			base = new Hive(main.getUUID(), dimension, main.blockPosition());
			base.race = main.kind.race;
			base.owner = owner;
			base.minerals = START_MINERALS;
			data.add(base);
		}
		main.joinHive(base);
		base.join(main.kind);
		ServerPlayer player = owner == null ? null : level.getServer().getPlayerList().getPlayer(owner);
		if (fresh) {
			// owner 17.10: under a player's base plain stone 20 deep and a concrete top its workers leave alone
			if (owner != null) dev.starmine.world.BaseGround.foundation(level, main.blockPosition(), (int) Math.ceil(main.kind.width / 2) + 8, main.kind.race);
			startWorkers(level, base, main);
			if (player != null) player.sendSystemMessage(Component.translatable("message.starmine.base_founded"));
		} else if (player != null) {
			player.sendSystemMessage(Component.translatable("message.starmine.expansion"));
		}
		data.setDirty();
		return base;
	}

	/** The start of a fresh base: {@link #START_WORKERS} workers of its race around its main building. */
	public static void startWorkers(ServerLevel level, Hive base, StarUnit main) {
		UnitKind worker = UnitKind.workerOf(main.kind.race);
		for (int i = 0; worker != null && i < START_WORKERS; i++) {
			// just outside the building (its width is already its size: scaled by the model's size again, a command
			// center's SCVs came out 11 blocks off, behind walls and over cliffs, and never reached its minerals)
			double angle = i * Math.PI / 2 + 0.4, radius = main.kind.width / 2 + worker.width / 2 + 1;
			HiveLogic.spawn(level, base, worker, dev.starmine.entity.Spots.outside(level, worker, main.position(), angle, radius), null);
		}
	}
}
