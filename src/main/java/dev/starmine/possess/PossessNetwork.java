package dev.starmine.possess;

import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * Packets of playing as a unit (owner 14.10): who plays as what ({@link PilotState}, to the player and
 * everyone who sees them, so their body is drawn and sized as the unit), the unit's energy and state
 * for the player's screen ({@link PilotHud}), the end of it ({@link PilotEnded}: the client goes back
 * to the command view) and the player's leave key ({@link LeaveUnit}).
 */
public final class PossessNetwork {
	public static final int CLOAKED = 1, REVEALED = 2;

	private PossessNetwork() {
	}

	/** A player (entity id and UUID) plays as `kind` ("" = no longer), with flags (cloaked, revealed). */
	public record PilotState(int entity, UUID player, String kind, int flags) implements CustomPacketPayload {
		public static final Type<PilotState> TYPE = new Type<>(StarMine.id("pilot_state"));
		public static final StreamCodec<RegistryFriendlyByteBuf, PilotState> CODEC = StreamCodec.of(
			(buf, state) -> {
				buf.writeVarInt(state.entity);
				buf.writeUUID(state.player);
				buf.writeUtf(state.kind);
				buf.writeVarInt(state.flags);
			},
			buf -> new PilotState(buf.readVarInt(), buf.readUUID(), buf.readUtf(), buf.readVarInt()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** The piloted unit's energy (and its maximum), mode and whether it is busy channelling or changing mode. */
	public record PilotHud(float energy, int maxEnergy, int mode, boolean busy) implements CustomPacketPayload {
		public static final Type<PilotHud> TYPE = new Type<>(StarMine.id("pilot_hud"));
		public static final StreamCodec<RegistryFriendlyByteBuf, PilotHud> CODEC = StreamCodec.of(
			(buf, hud) -> {
				buf.writeFloat(hud.energy);
				buf.writeVarInt(hud.maxEnergy);
				buf.writeVarInt(hud.mode);
				buf.writeBoolean(hud.busy);
			},
			buf -> new PilotHud(buf.readFloat(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Playing as the unit is over (left, or the unit died): back to the command view. */
	/** Playing as a unit ended (`vehicle`: a craft driven like a car, owner 19.10 — the player stays out of the command view). */
	public record PilotEnded(boolean died, boolean vehicle) implements CustomPacketPayload {
		public static final Type<PilotEnded> TYPE = new Type<>(StarMine.id("pilot_ended"));
		public static final StreamCodec<RegistryFriendlyByteBuf, PilotEnded> CODEC = StreamCodec.of(
			(buf, ended) -> {
				buf.writeBoolean(ended.died);
				buf.writeBoolean(ended.vehicle);
			}, buf -> new PilotEnded(buf.readBoolean(), buf.readBoolean()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** The player's leave key. */
	public record LeaveUnit() implements CustomPacketPayload {
		public static final Type<LeaveUnit> TYPE = new Type<>(StarMine.id("leave_unit"));
		public static final StreamCodec<RegistryFriendlyByteBuf, LeaveUnit> CODEC = StreamCodec.of((buf, leave) -> { }, buf -> new LeaveUnit());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.playS2C().register(PilotState.TYPE, PilotState.CODEC);
		PayloadTypeRegistry.playS2C().register(PilotHud.TYPE, PilotHud.CODEC);
		PayloadTypeRegistry.playS2C().register(PilotEnded.TYPE, PilotEnded.CODEC);
		PayloadTypeRegistry.playC2S().register(LeaveUnit.TYPE, LeaveUnit.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(LeaveUnit.TYPE, (leave, context) -> Possession.end(context.player(), false));
	}

	static PilotState stateOf(ServerPlayer player) {
		UnitKind kind = Pilots.kindOf(player);
		StarUnit puppet = Possession.puppet(player);
		int flags = puppet == null ? 0 : (puppet.cloaked() ? CLOAKED : 0) | (puppet.revealed() ? REVEALED : 0);
		return new PilotState(player.getId(), player.getUUID(), kind == null ? "" : kind.name(), flags);
	}

	/** Tells the player and everyone who sees them what they play as. */
	static void sendState(ServerPlayer player) {
		PilotState state = stateOf(player);
		ServerPlayNetworking.send(player, state);
		for (ServerPlayer other : PlayerLookup.tracking(player)) if (other != player) ServerPlayNetworking.send(other, state);
	}
}
