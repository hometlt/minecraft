package dev.starmine.colony;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveLogic;
import dev.starmine.hive.HiveSavedData;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

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
			UnitKind worker = UnitKind.workerOf(main.kind.race);
			for (int i = 0; worker != null && i < START_WORKERS; i++) {
				double angle = i * Math.PI / 2 + 0.4, radius = main.kind.width * main.kind.modelScale() / 2 + 1.5;
				BlockPos ground = BlockPos.containing(main.getX() + Math.cos(angle) * radius, main.getY() + 2, main.getZ() + Math.sin(angle) * radius);
				for (int step = 0; step < 6 && level.getBlockState(ground.below()).isAir(); step++) ground = ground.below();
				HiveLogic.spawn(level, base, worker, Vec3.atBottomCenterOf(ground), null);
			}
			if (player != null) player.sendSystemMessage(Component.translatable("message.starmine.base_founded"));
		} else if (player != null) {
			player.sendSystemMessage(Component.translatable("message.starmine.expansion"));
		}
		data.setDirty();
		return base;
	}
}
