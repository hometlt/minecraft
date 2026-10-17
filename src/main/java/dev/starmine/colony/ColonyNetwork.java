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
	/** One race's supply in a base (owner 16.10: each race has its own limit): the race's name, used and cap. */
	public record Supply(String race, float used, int cap) {
	}

	public record BaseStatus(boolean present, int minerals, int vespene, int diamonds, List<Supply> supplies,
			int attackLevel, int armorLevel, List<Tally> tallies, Map<String, Integer> counts) implements CustomPacketPayload {
		public static final Type<BaseStatus> TYPE = new Type<>(StarMine.id("base_status"));
		public static final BaseStatus NONE = new BaseStatus(false, 0, 0, 0, List.of(), 0, 0, List.of(), Map.of());
		public static final StreamCodec<RegistryFriendlyByteBuf, BaseStatus> CODEC = StreamCodec.of(BaseStatus::write, BaseStatus::read);

		private static void write(RegistryFriendlyByteBuf buf, BaseStatus status) {
			buf.writeBoolean(status.present);
			if (!status.present) return;
			buf.writeVarInt(status.minerals);
			buf.writeVarInt(status.vespene);
			buf.writeVarInt(status.diamonds);
			buf.writeVarInt(status.supplies.size());
			for (Supply supply : status.supplies) {
				buf.writeUtf(supply.race);
				buf.writeFloat(supply.used);
				buf.writeVarInt(supply.cap);
			}
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
			List<Supply> supplies = new ArrayList<>();
			for (int i = buf.readVarInt(); i > 0; i--) supplies.add(new Supply(buf.readUtf(), buf.readFloat(), buf.readVarInt()));
			int attack = buf.readVarInt(), armor = buf.readVarInt();
			List<Tally> tallies = new ArrayList<>();
			for (int i = buf.readVarInt(); i > 0; i--) tallies.add(new Tally(buf.readUtf(), buf.readUtf(), buf.readVarInt()));
			Map<String, Integer> counts = new LinkedHashMap<>();
			for (int i = buf.readVarInt(); i > 0; i--) counts.put(buf.readUtf(), buf.readVarInt());
			return new BaseStatus(true, minerals, vespene, diamonds, supplies, attack, armor, tallies, counts);
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

	/** A base on the map: a race's main base (`race` its name) or the player's own (`race` "PLAYER"). */
	public record Mark(String race, int x, int z) {
	}

	/**
	 * Where the bases are (owner 16.10): the three races' main bases, scouted or not, and the player's own,
	 * for the command view's minimap — marks on it, an arrow toward the nearest one beyond its edge.
	 */
	public record BaseMarks(List<Mark> marks) implements CustomPacketPayload {
		public static final Type<BaseMarks> TYPE = new Type<>(StarMine.id("base_marks"));
		public static final StreamCodec<RegistryFriendlyByteBuf, BaseMarks> CODEC = StreamCodec.of((buf, value) -> {
			buf.writeVarInt(value.marks.size());
			for (Mark mark : value.marks) {
				buf.writeUtf(mark.race(), 16);
				buf.writeVarInt(mark.x());
				buf.writeVarInt(mark.z());
			}
		}, buf -> {
			int n = buf.readVarInt();
			List<Mark> marks = new ArrayList<>();
			for (int i = 0; i < n; i++) marks.add(new Mark(buf.readUtf(16), buf.readVarInt(), buf.readVarInt()));
			return new BaseMarks(marks);
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** The marks a player sees: the generated main bases of this dimension (sites chosen so far) and their own base. */
	static void sendMarks(ServerPlayer player, @org.jetbrains.annotations.Nullable Hive base) {
		if (!ServerPlayNetworking.canSend(player, BaseMarks.TYPE)) return;
		List<Mark> marks = new ArrayList<>();
		if (player.level().dimension() == net.minecraft.world.level.Level.OVERWORLD) {
			dev.starmine.hive.HiveSavedData data = dev.starmine.hive.HiveSavedData.get(player.getServer());
			if (data.spawnHiveSite != null) marks.add(new Mark("ZERG", data.spawnHiveSite.getX(), data.spawnHiveSite.getZ()));
			data.structureSites.forEach((race, site) -> {
				if (!race.startsWith("FAR")) marks.add(new Mark(race, site.getX(), site.getZ()));
			});
			for (net.minecraft.core.BlockPos at : dev.starmine.world.HeroicTransport.waiting(player)) // owner 17.10: the heroic transport waiting
				marks.add(new Mark("TRANSPORT", at.getX(), at.getZ()));
		}
		if (base != null) marks.add(new Mark("PLAYER", base.hatchery.getX(), base.hatchery.getZ()));
		ServerPlayNetworking.send(player, new BaseMarks(marks));
	}

	/** Common registration: payload types and the server's command receiver. */
	public static void register() {
		PayloadTypeRegistry.playS2C().register(BaseStatus.TYPE, BaseStatus.CODEC);
		PayloadTypeRegistry.playS2C().register(BaseMarks.TYPE, BaseMarks.CODEC);
		PayloadTypeRegistry.playC2S().register(UnitCommand.TYPE, UnitCommand.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(UnitCommand.TYPE, (command, context) -> Commands.command(context.player(), command));
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
		counts.put("tier", base.tier); // zerg: lair 2, hive 3
		for (String done : base.researched) counts.put("research:" + done, 1); // unit upgrades done
		for (dev.starmine.race.Research what : dev.starmine.race.Research.values()) // the levels of weapons, armor, shields
			if (what.leveled()) counts.put("level:" + what.id(), dev.starmine.race.Research.level(base, what));
		counts.put("level:nuke", dev.starmine.race.Research.nukes(base)); // armed nukes
		List<Supply> supplies = new ArrayList<>(); // the races the player commands: those with supply given or taken
		for (UnitKind.Race race : UnitKind.Race.values())
			if (base.supplyCap(race) > 0 || base.supplyUsed(race) > 0)
				supplies.add(new Supply(race.name(), (float) base.supplyUsed(race), base.supplyCap(race)));
		ServerPlayNetworking.send(player, new BaseStatus(true, base.minerals, base.vespene, base.diamonds, supplies,
			base.attackLevel, base.armorLevel, tallies, counts));
	}

	/** Lets the client check tech: whether a kind's requirement is in the counts and the tier is enough. */
	public static boolean unlocked(Map<String, Integer> counts, UnitKind kind) {
		UnitKind need = kind.requires();
		if (kind == UnitKind.BROOD_LORD && counts.getOrDefault("research:greater_spire", 0) == 0) return false; // the greater spire first
		return (need == null || counts.getOrDefault(need.name(), 0) > 0) && counts.getOrDefault("tier", 1) >= kind.tier();
	}
}
