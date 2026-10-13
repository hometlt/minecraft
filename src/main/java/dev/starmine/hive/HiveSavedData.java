package dev.starmine.hive;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * All hives of a world (every dimension), kept with the overworld's saved data as `starmine_hives`.
 * The world holds at most {@link #MAX_HIVES} (owner's decision).
 */
public final class HiveSavedData extends SavedData {
	public static final int MAX_HIVES = 3;
	private static final Factory<HiveSavedData> FACTORY = new Factory<>(HiveSavedData::new, HiveSavedData::load, null);
	private final Map<UUID, Hive> hives = new LinkedHashMap<>();

	public static HiveSavedData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "starmine_hives");
	}

	public Hive get(UUID id) {
		return id == null ? null : hives.get(id);
	}

	public Collection<Hive> all() {
		return hives.values();
	}

	public boolean full() {
		return hives.size() >= MAX_HIVES;
	}

	public void add(Hive hive) {
		hives.put(hive.id, hive);
		setDirty();
	}

	public void remove(UUID id) {
		if (hives.remove(id) != null) setDirty();
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		ListTag list = new ListTag();
		for (Hive hive : hives.values()) list.add(hive.save());
		tag.put("hives", list);
		return tag;
	}

	private static HiveSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
		HiveSavedData data = new HiveSavedData();
		for (Tag entry : tag.getList("hives", Tag.TAG_COMPOUND)) {
			Hive hive = Hive.load((CompoundTag) entry);
			data.hives.put(hive.id, hive);
		}
		return data;
	}
}
