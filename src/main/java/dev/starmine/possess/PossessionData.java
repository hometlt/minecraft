package dev.starmine.possess;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Players playing as units, kept with the world (`starmine_pilots`) so a player who logs out or a
 * server that stops finds them still in their unit: per player the unit's data and what the player
 * was before (inventory, place, health, game mode, attributes) — see {@link Possession}.
 */
public final class PossessionData extends SavedData {
	private static final Factory<PossessionData> FACTORY = new Factory<>(PossessionData::new, PossessionData::load, null);
	final Map<UUID, CompoundTag> sessions = new LinkedHashMap<>();

	public static PossessionData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "starmine_pilots");
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		CompoundTag all = new CompoundTag();
		sessions.forEach((player, session) -> all.put(player.toString(), session));
		tag.put("sessions", all);
		return tag;
	}

	private static PossessionData load(CompoundTag tag, HolderLookup.Provider registries) {
		PossessionData data = new PossessionData();
		CompoundTag all = tag.getCompound("sessions");
		for (String key : all.getAllKeys()) {
			try {
				data.sessions.put(UUID.fromString(key), all.getCompound(key));
			} catch (IllegalArgumentException ignored) {
			}
		}
		return data;
	}
}
