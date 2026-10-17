package dev.starmine.entity;

import dev.starmine.StarMine;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Weapon beams (owner 17.10: shots from the weapons, the colossus' and void ray's lasers, seen in the command view
 * too): the server tells the clients round a shot where a beam runs, and they draw it glowing for a few ticks
 * (dev.starmine.client.BeamRenderer) — a tracer for guns, a laser for the colossus' thermal lances and the
 * battlecruiser, a thick pulsing beam for the void ray's prismatic beam and the protoss' energy weapons.
 */
public final class Fx {
	/** How the beam looks: a thin quick tracer, a laser bolt, a thick lasting beam. */
	public enum Style { TRACER, LASER, BEAM }

	/** Players this close to a beam are sent it. */
	private static final double REACH = 160;

	private Fx() {
	}

	/** A beam from `from` to `to`: its style, colour (RGB), how many ticks it shows and its width in blocks. */
	public record Beam(int style, Vec3 from, Vec3 to, int color, int ticks, float width) implements CustomPacketPayload {
		public static final Type<Beam> TYPE = new Type<>(StarMine.id("beam"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Beam> CODEC = StreamCodec.of(
			(buf, beam) -> {
				buf.writeByte(beam.style);
				buf.writeDouble(beam.from.x);
				buf.writeDouble(beam.from.y);
				buf.writeDouble(beam.from.z);
				buf.writeDouble(beam.to.x);
				buf.writeDouble(beam.to.y);
				buf.writeDouble(beam.to.z);
				buf.writeInt(beam.color);
				buf.writeVarInt(beam.ticks);
				buf.writeFloat(beam.width);
			},
			buf -> new Beam(buf.readByte(), new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
				new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()), buf.readInt(), buf.readVarInt(), buf.readFloat()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}

		public Style look() {
			return Style.values()[Math.max(0, Math.min(Style.values().length - 1, style))];
		}
	}

	public static void register() {
		PayloadTypeRegistry.playS2C().register(Beam.TYPE, Beam.CODEC);
	}

	/** Sends a beam to the players near it. */
	public static void beam(ServerLevel level, Style style, Vec3 from, Vec3 to, int color, int ticks, float width) {
		Beam beam = new Beam(style.ordinal(), from, to, color, ticks, width);
		Vec3 middle = from.lerp(to, 0.5);
		for (ServerPlayer player : level.players())
			if (player.distanceToSqr(middle) < REACH * REACH && ServerPlayNetworking.canSend(player, Beam.TYPE)) ServerPlayNetworking.send(player, beam);
	}

	/** The beam a hitscan weapon of this kind fires (its style, colour, ticks, width). */
	public static void shot(ServerLevel level, UnitKind kind, Vec3 from, Vec3 to) {
		switch (kind) {
			case VOID_RAY -> beam(level, Style.BEAM, from, to, 0xC060FF, kind.interval + 3, 0.35f); // one lasting beam while it fires
			case COLOSSUS -> beam(level, Style.LASER, from, to, 0xFF5030, 3, 0.22f); // a sweep: a beam a tick (StarUnit.spineLine)
			case BATTLECRUISER -> beam(level, Style.LASER, from, to, 0xFF4040, 3, 0.12f);
			case ARCHON -> beam(level, Style.BEAM, from, to, 0x80C0FF, 6, 0.45f);
			case SENTRY, ORACLE -> beam(level, Style.BEAM, from, to, 0x60D0FF, 4, 0.15f);
			case HIGH_TEMPLAR, ARTANIS -> beam(level, Style.LASER, from, to, 0xA0A0FF, 4, 0.12f);
			case SIEGE_TANK, THOR, BUNKER, COMMAND_CENTER -> beam(level, Style.TRACER, from, to, 0xFFD080, 4, 0.16f);
			default -> beam(level, Style.TRACER, from, to, kind.race == UnitKind.Race.PROTOSS ? 0x80D0FF : 0xFFE8A0, 3, 0.08f);
		}
	}
}
