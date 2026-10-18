package dev.starmine.entity;

import dev.starmine.colony.ColonyNetwork;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

/**
 * The way a player's unit plans to its order point, sent to that player (owner 18.10: show at once how the
 * units are going to go): the command view draws it instead of a straight line ({@code client.Orders}).
 */
final class Routes {
	/** Nodes sent at most (a block apart; a longer way is shown up to there). */
	static final int MAX_NODES = 128;

	private Routes() {
	}

	static void send(StarUnit unit, Path path) {
		if (path == null || path.getNodeCount() < 2 || unit.getServer() == null) return;
		ServerPlayer player = unit.owner().map(unit.getServer().getPlayerList()::getPlayer).orElse(null);
		if (player == null || player.level() != unit.level() || !ServerPlayNetworking.canSend(player, ColonyNetwork.UnitRoute.TYPE)) return;
		int from = path.getNextNodeIndex(), count = Math.min(MAX_NODES, path.getNodeCount() - from);
		int[] nodes = new int[count * 3];
		for (int i = 0; i < count; i++) {
			Node node = path.getNode(from + i);
			nodes[i * 3] = node.x;
			nodes[i * 3 + 1] = node.y;
			nodes[i * 3 + 2] = node.z;
		}
		ServerPlayNetworking.send(player, new ColonyNetwork.UnitRoute(unit.getId(), nodes));
	}
}
