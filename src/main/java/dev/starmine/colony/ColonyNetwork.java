package dev.starmine.colony;

import dev.starmine.StarMine;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveResources;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * StarMine's packets for the terran command view (owner 14.10): the base status a player receives
 * every second ({@link BaseStatus}: stores, supply, upgrades, resource tallies with their icons,
 * buildings and units by kind for the command card's tech checks) and the commands a player sends
 * ({@link UnitCommand}).
 */
public final class ColonyNetwork {
	private ColonyNetwork() {
	}

	/** One line of the resource panel: label, icon item id, blocks brought in. */
	public record Tally(String label, String icon, int count) {
	}

	/** A player's base: `present` false when the player has none in this dimension. */
	public record BaseStatus(boolean present, int minerals, int vespene, int diamonds, float supplyUsed, int supplyCap,
			int attackLevel, int armorLevel, List<Tally> tallies, Map<String, Integer> counts) implements CustomPacketPayload {
		public static final Type<BaseStatus> TYPE = new Type<>(StarMine.id("base_status"));
		public static final BaseStatus NONE = new BaseStatus(false, 0, 0, 0, 0, 0, 0, 0, List.of(), Map.of());
		public static final StreamCodec<RegistryFriendlyByteBuf, BaseStatus> CODEC = StreamCodec.of(BaseStatus::write, BaseStatus::read);

		private static void write(RegistryFriendlyByteBuf buf, BaseStatus status) {
			buf.writeBoolean(status.present);
			if (!status.present) return;
			buf.writeVarInt(status.minerals);
			buf.writeVarInt(status.vespene);
			buf.writeVarInt(status.diamonds);
			buf.writeFloat(status.supplyUsed);
			buf.writeVarInt(status.supplyCap);
			buf.writeVarInt(status.attackLevel);
			buf.writeVarInt(status.armorLevel);
			buf.writeVarInt(status.tallies.size());
			for (Tally tally : status.tallies) {
				buf.writeUtf(tally.label);
				buf.writeUtf(tally.icon);
				buf.writeVarInt(tally.count);
			}
			buf.writeVarInt(status.counts.size());
			status.counts.forEach((kind, count) -> {
				buf.writeUtf(kind);
				buf.writeVarInt(count);
			});
		}

		private static BaseStatus read(RegistryFriendlyByteBuf buf) {
			if (!buf.readBoolean()) return NONE;
			int minerals = buf.readVarInt(), vespene = buf.readVarInt(), diamonds = buf.readVarInt();
			float supplyUsed = buf.readFloat();
			int supplyCap = buf.readVarInt(), attack = buf.readVarInt(), armor = buf.readVarInt();
			List<Tally> tallies = new ArrayList<>();
			for (int i = buf.readVarInt(); i > 0; i--) tallies.add(new Tally(buf.readUtf(), buf.readUtf(), buf.readVarInt()));
			Map<String, Integer> counts = new LinkedHashMap<>();
			for (int i = buf.readVarInt(); i > 0; i--) counts.put(buf.readUtf(), buf.readVarInt());
			return new BaseStatus(true, minerals, vespene, diamonds, supplyUsed, supplyCap, attack, armor, tallies, counts);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * A command to units and buildings (entity ids). Actions: move, attack_move, patrol (to x y z),
	 * hold, stop, attack (target entity, else attack-move to x y z), gather (block at x y z), return,
	 * build (arg = kind name, site x y z), train (arg = kind name), cancel, rally (x y z), research
	 * (arg = weapons or armor).
	 */
	public record UnitCommand(int[] ids, String action, double x, double y, double z, int target, String arg) implements CustomPacketPayload {
		public static final Type<UnitCommand> TYPE = new Type<>(StarMine.id("unit_command"));
		public static final StreamCodec<RegistryFriendlyByteBuf, UnitCommand> CODEC = StreamCodec.of(UnitCommand::write, UnitCommand::read);

		private static void write(RegistryFriendlyByteBuf buf, UnitCommand command) {
			buf.writeVarIntArray(command.ids);
			buf.writeUtf(command.action);
			buf.writeDouble(command.x);
			buf.writeDouble(command.y);
			buf.writeDouble(command.z);
			buf.writeVarInt(command.target);
			buf.writeUtf(command.arg);
		}

		private static UnitCommand read(RegistryFriendlyByteBuf buf) {
			int[] ids = buf.readVarIntArray(256);
			return new UnitCommand(ids, buf.readUtf(32), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readVarInt(), buf.readUtf(64));
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Common registration: payload types and the server's command receiver. */
	public static void register() {
		PayloadTypeRegistry.playS2C().register(BaseStatus.TYPE, BaseStatus.CODEC);
		PayloadTypeRegistry.playC2S().register(UnitCommand.TYPE, UnitCommand.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(UnitCommand.TYPE, (command, context) -> TerranLogic.command(context.player(), command));
	}

	static void sendStatus(ServerPlayer player, Hive base) {
		if (!ServerPlayNetworking.canSend(player, BaseStatus.TYPE)) return;
		if (base == null) {
			ServerPlayNetworking.send(player, BaseStatus.NONE);
			return;
		}
		List<Tally> tallies = new ArrayList<>();
		for (HiveResources.Label label : HiveResources.labels())
			tallies.add(new Tally(label.label(), label.icon(), base.collected.getOrDefault(label.label(), 0)));
		Map<String, Integer> counts = new LinkedHashMap<>();
		base.members.forEach((kind, count) -> counts.put(kind.name(), count));
		base.pending.forEach((kind, count) -> counts.merge("pending:" + kind.name(), count, Integer::sum));
		ServerPlayNetworking.send(player, new BaseStatus(true, base.minerals, base.vespene, base.diamonds, (float) base.supplyUsed(),
			base.supplyCap(), base.attackLevel, base.armorLevel, tallies, counts));
	}

	/** Lets the client check tech: whether a kind's requirement is in the counts. */
	public static boolean unlocked(Map<String, Integer> counts, UnitKind kind) {
		UnitKind need = kind.requires();
		return need == null || counts.getOrDefault(need.name(), 0) > 0;
	}
}
