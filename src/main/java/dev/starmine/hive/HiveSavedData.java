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
	/** The hive generated near the world spawn: its chosen site and whether it has been built. */
	public net.minecraft.core.BlockPos spawnHiveSite;
	public boolean spawnHiveDone;
	/** Other generated structures (dev.starmine.world.RaceBases): their chosen sites and which are built (or given up), by key. */
	public final Map<String, net.minecraft.core.BlockPos> structureSites = new LinkedHashMap<>();
	public final java.util.Set<String> structuresDone = new java.util.LinkedHashSet<>();
	/**
	 * Zerg of generated hives not yet in the world: each is added when its chunk is entity-ticking
	 * (a player is near), so far tunnels and halls are not filled while nobody can see them.
	 */
	public final java.util.List<HiveStructure.Pending> pending = new java.util.ArrayList<>();

	public static HiveSavedData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "starmine_hives");
	}

	public Hive get(UUID id) {
		return id == null ? null : hives.get(id);
	}

	public Collection<Hive> all() {
		return hives.values();
	}

	/** The world holds at most {@link #MAX_HIVES} zerg hives (terran bases are not counted). */
	public boolean full() {
		return hives.values().stream().filter(hive -> hive.owner == null && hive.race == dev.starmine.entity.UnitKind.Race.ZERG).count() >= MAX_HIVES;
	}

	/** The terran base a player commands in a dimension, or null. */
	public Hive baseOf(UUID player, String dimension) {
		for (Hive hive : hives.values())
			if (player.equals(hive.owner) && hive.dimension.equals(dimension)) return hive;
		return null;
	}

	public void add(Hive hive) {
		hives.put(hive.id, hive);
		setDirty();
	}

	/** Forgets every hive (in-game tests: the test world is reused between runs). */
	public void clear() {
		hives.clear();
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
		if (spawnHiveSite != null) tag.putLong("spawnHiveSite", spawnHiveSite.asLong());
		tag.putBoolean("spawnHiveDone", spawnHiveDone);
		ListTag waiting = new ListTag();
		for (HiveStructure.Pending p : pending) waiting.add(p.save());
		tag.put("pending", waiting);
		CompoundTag sites = new CompoundTag();
		structureSites.forEach((key, site) -> sites.putLong(key, site.asLong()));
		tag.put("structureSites", sites);
		ListTag done = new ListTag();
		for (String key : structuresDone) done.add(net.minecraft.nbt.StringTag.valueOf(key));
		tag.put("structuresDone", done);
		return tag;
	}

	private static HiveSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
		HiveSavedData data = new HiveSavedData();
		for (Tag entry : tag.getList("hives", Tag.TAG_COMPOUND)) {
			Hive hive = Hive.load((CompoundTag) entry);
			data.hives.put(hive.id, hive);
		}
		if (tag.contains("spawnHiveSite")) data.spawnHiveSite = net.minecraft.core.BlockPos.of(tag.getLong("spawnHiveSite"));
		data.spawnHiveDone = tag.getBoolean("spawnHiveDone");
		for (Tag entry : tag.getList("pending", Tag.TAG_COMPOUND)) {
			HiveStructure.Pending p = HiveStructure.Pending.load((CompoundTag) entry);
			if (p != null) data.pending.add(p);
		}
		CompoundTag sites = tag.getCompound("structureSites");
		for (String key : sites.getAllKeys()) data.structureSites.put(key, net.minecraft.core.BlockPos.of(sites.getLong(key)));
		for (Tag entry : tag.getList("structuresDone", Tag.TAG_STRING)) data.structuresDone.add(entry.getAsString());
		return data;
	}
}
