package dev.starmine.colony;

import dev.starmine.StarMine;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.SectionPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Where a player's command view looks (owner 17.10: the camera goes anywhere on the map, wherever the player
 * stands). The client tells the server the point under its camera; while the view is open the world is loaded
 * and sent round that point instead of round the player's body — chunks (dev.starmine.mixin.CommanderEyeMixin),
 * creatures (dev.starmine.mixin.CommanderEyeTrackingMixin), particles and beams — and a ticket keeps the body's
 * own few chunks loaded so the commander is not left standing in an unloaded world.
 */
public final class CommanderEye {
	/** The point a command view looks at, or the view closed (`open` false). */
	public record Look(boolean open, double x, double y, double z) implements CustomPacketPayload {
		public static final Type<Look> TYPE = new Type<>(StarMine.id("commander_eye"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Look> CODEC = StreamCodec.of(
			(buf, look) -> {
				buf.writeBoolean(look.open);
				buf.writeDouble(look.x);
				buf.writeDouble(look.y);
				buf.writeDouble(look.z);
			},
			buf -> new Look(buf.readBoolean(), buf.readDouble(), buf.readDouble(), buf.readDouble()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** The body's chunks a ticket keeps loaded and ticking while its eye is elsewhere. */
	private static final int BODY_RADIUS = 2;
	private static final TicketType<UUID> BODY = TicketType.create("starmine_commander", Comparator.comparing(UUID::toString));
	private static final Map<UUID, Vec3> EYES = new ConcurrentHashMap<>();
	private static final Map<UUID, ChunkPos> BODY_TICKETS = new ConcurrentHashMap<>();

	private CommanderEye() {
	}

	/** Where this player's view looks now, or null when it is closed. */
	public static @Nullable Vec3 eye(ServerPlayer player) {
		return EYES.get(player.getUUID());
	}

	/** The point the world is loaded and sent round for this player: its eye, else its body. */
	public static Vec3 centre(ServerPlayer player) {
		Vec3 eye = eye(player);
		return eye != null ? eye : player.position();
	}

	/** The section the world is loaded round for this player (the chunk map's view of it). */
	public static SectionPos section(ServerPlayer player) {
		Vec3 eye = eye(player);
		return eye != null ? SectionPos.of(net.minecraft.core.BlockPos.containing(eye)) : SectionPos.of(player);
	}

	/** Whether a point is within `reach` of the player's body or of its eye. */
	public static boolean near(ServerPlayer player, Vec3 point, double reach) {
		if (player.distanceToSqr(point) < reach * reach) return true;
		Vec3 eye = eye(player);
		return eye != null && eye.distanceToSqr(point) < reach * reach;
	}

	public static void register() {
		PayloadTypeRegistry.playC2S().register(Look.TYPE, Look.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Look.TYPE, (look, context) -> look(context.player(), look));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> close(handler.player));
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 20 != 0) return;
			for (ServerPlayer player : server.getPlayerList().getPlayers()) if (EYES.containsKey(player.getUUID())) keepBody(player);
		});
	}

	/** Opens (or moves) a player's eye at a point, as their client does. */
	public static void open(ServerPlayer player, Vec3 at) {
		look(player, new Look(true, at.x, at.y, at.z));
	}

	private static void look(ServerPlayer player, Look look) {
		if (!look.open) {
			close(player);
			return;
		}
		ServerLevel level = player.serverLevel();
		var border = level.getWorldBorder();
		Vec3 at = new Vec3(Math.max(border.getMinX(), Math.min(border.getMaxX(), look.x)), look.y, Math.max(border.getMinZ(), Math.min(border.getMaxZ(), look.z)));
		EYES.put(player.getUUID(), at);
		keepBody(player);
		level.getChunkSource().move(player);
	}

	/** Closes a player's eye: the world goes back to being loaded round the body. */
	public static void close(ServerPlayer player) {
		if (EYES.remove(player.getUUID()) == null) return;
		ChunkPos ticket = BODY_TICKETS.remove(player.getUUID());
		if (ticket != null) player.serverLevel().getChunkSource().removeRegionTicket(BODY, ticket, BODY_RADIUS, player.getUUID());
		player.serverLevel().getChunkSource().move(player);
	}

	/** The ticket on the body's chunks follows the body. */
	private static void keepBody(ServerPlayer player) {
		ChunkPos here = player.chunkPosition(), was = BODY_TICKETS.get(player.getUUID());
		if (here.equals(was)) return;
		var chunks = player.serverLevel().getChunkSource();
		if (was != null) chunks.removeRegionTicket(BODY, was, BODY_RADIUS, player.getUUID());
		chunks.addRegionTicket(BODY, here, BODY_RADIUS, player.getUUID());
		BODY_TICKETS.put(player.getUUID(), here);
	}
}
