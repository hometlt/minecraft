package dev.starmine.colony;

import dev.starmine.entity.StarUnit;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.Vec3;

/**
 * Orders without the command view (owner 22.10): a right click with an empty hand on one of the player's own units
 * makes it follow the player; a second click stops it where it is. A follower keeps within {@link #NEAR} blocks
 * (it walks on when the player goes farther), and drops the escort when the player is gone from its dimension or
 * any other order is given to it.
 */
public final class Escort {
	static final String TAG = "starmine_escort:";
	static final double NEAR = 4, FAR = 6;

	private Escort() {
	}

	public static void load() {
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!(player instanceof ServerPlayer server) || hand != InteractionHand.MAIN_HAND || !(entity instanceof StarUnit unit)
				|| player.isSpectator() || player.isShiftKeyDown() || !player.getMainHandItem().isEmpty()
				|| unit.kind.structure() || !unit.isAlive() || !player.getUUID().equals(unit.owner().orElse(null))
				|| dev.starmine.possess.Possession.piloting(server) || dev.starmine.possess.Vehicles.isParked(unit)) return InteractionResult.PASS;
			toggle(unit, server);
			return InteractionResult.SUCCESS;
		});
		ServerTickEvents.END_SERVER_TICK.register(Escort::tick);
	}

	/** Follows the player, or stops following (and stands). */
	public static void toggle(StarUnit unit, ServerPlayer player) {
		UUID leader = leader(unit);
		unit.getTags().removeIf(tag -> tag.startsWith(TAG));
		if (player.getUUID().equals(leader)) {
			unit.command(StarUnit.Order.NONE, null);
			player.displayClientMessage(Component.translatable("message.starmine.escort_stop", unit.getDisplayName()), true);
			return;
		}
		unit.addTag(TAG + player.getUUID());
		unit.command(StarUnit.Order.MOVE, player.position());
		player.displayClientMessage(Component.translatable("message.starmine.escort_follow", unit.getDisplayName()), true);
	}

	static UUID leader(StarUnit unit) {
		for (String tag : unit.getTags())
			if (tag.startsWith(TAG)) try {
				return UUID.fromString(tag.substring(TAG.length()));
			} catch (IllegalArgumentException ignored) {
			}
		return null;
	}

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % 10 != 0) return;
		for (ServerLevel level : server.getAllLevels())
			for (var entity : level.getAllEntities()) {
				if (!(entity instanceof StarUnit unit) || !unit.isAlive()) continue;
				UUID leader = leader(unit);
				if (leader == null) continue;
				ServerPlayer player = server.getPlayerList().getPlayer(leader);
				// another order given meanwhile (the command view's, a stop): the escort is over
				boolean ordered = unit.order() != StarUnit.Order.MOVE && unit.order() != StarUnit.Order.NONE;
				if (player == null || player.level() != level || player.isSpectator() || ordered) {
					unit.getTags().removeIf(tag -> tag.startsWith(TAG));
					continue;
				}
				double d = unit.distanceTo(player);
				if (d > FAR) {
					Vec3 back = unit.position().subtract(player.position());
					Vec3 to = back.lengthSqr() < 1e-4 ? player.position() : player.position().add(back.normalize().scale(NEAR - 1));
					unit.command(StarUnit.Order.MOVE, to);
				}
			}
	}
}
