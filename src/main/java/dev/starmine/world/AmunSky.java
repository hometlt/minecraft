package dev.starmine.world;

import dev.starmine.StarMine;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * Owner 19.10: when Amun wakes in a world its sky reddens for a while ({@link AmunInvasion#WARNING}) before his troops
 * come; the client tints its sky and fog ({@code dev.starmine.client.AmunSkyClient}).
 */
public final class AmunSky {
	/** The sky of the player's world red for `ticks` (fading in and out). */
	public record Red(int ticks) implements CustomPacketPayload {
		public static final Type<Red> TYPE = new Type<>(StarMine.id("amun_sky"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Red> CODEC = StreamCodec.of(
			(buf, red) -> buf.writeVarInt(red.ticks()), buf -> new Red(buf.readVarInt()));

		@Override
		public Type<Red> type() {
			return TYPE;
		}
	}

	private AmunSky() {
	}

	public static void register() {
		PayloadTypeRegistry.playS2C().register(Red.TYPE, Red.CODEC);
	}

	public static void redden(ServerPlayer player, int ticks) {
		if (ServerPlayNetworking.canSend(player, Red.TYPE)) ServerPlayNetworking.send(player, new Red(ticks));
	}
}
