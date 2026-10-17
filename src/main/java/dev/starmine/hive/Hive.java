package dev.starmine.hive;

import dev.starmine.entity.UnitKind;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;

/**
 * One zerg hive or terran base: its main building (the hatchery, whose entity UUID is the hive id; or
 * the first command center), stores and living members. A terran base belongs to a player
 * ({@link #owner}), who commands it (dev.starmine.colony).
 *
 * Members are counted by kind while they exist anywhere (also in unloaded chunks); a member leaves
 * when it dies or is discarded. Supply follows SC2 rules with the owner's numbers: every hatchery,
 * overlord and overseer provides 8, units use their SC2 food, and cocoons hold their future units'
 * food through {@link #pending} (units in cocoons or in training). Diamonds are only hoarded.
 */
public final class Hive {
	public static final int MAX_SUPPLY = 200;

	public final UUID id;
	public UnitKind.Race race = UnitKind.Race.ZERG;
	/**
	 * The player whose base this is (any races: their resources and supply are shared); null for a wild
	 * base (a hive or outpost that appeared in the world), which its race's AI runs. `race` is the race
	 * of the base's first main building.
	 */
	public UUID owner;
	/**
	 * Its stock of each resource by label (iron, diamond, ...): what its workers and its player brought in,
	 * less what prices in resources took ({@link dev.starmine.colony.Costs}); the resource panel shows it.
	 */
	public final Map<String, Integer> collected = new java.util.LinkedHashMap<>();
	/** Zerg: warriors gathered before the next attack on a terran base, and when it may start. */
	public int waveSize = 6;
	public long nextWave;
	/**
	 * A wild base's territory (owner 16.10, {@link dev.starmine.ai.Territory}): the places it claimed — caves it
	 * lit and built on, outposts with their guns —, when it may claim the next, its expedition on the way
	 * (the place, the units, till when), when its next drop on a player comes and how many it brings.
	 */
	public final java.util.List<BlockPos> territory = new java.util.ArrayList<>();
	public long nextClaim, nextDrop;
	/**
	 * Its nydus corridors (owner 16.10, {@link dev.starmine.world.Nydus}): pairs of where a network stands and
	 * where its worm came out, flattened (network, worm, network, worm…); when its next squad goes through.
	 */
	public final java.util.List<BlockPos> nydus = new java.util.ArrayList<>();
	public long nextNydusTransfer;
	public int dropSize;
	public BlockPos expedition;
	public boolean expeditionCave;
	public long expeditionUntil;
	public final java.util.List<java.util.UUID> expeditionUnits = new java.util.ArrayList<>();
	/** Game time this base's logic last ran (several command centers share one base). */
	public long lastTick = -1;
	public String dimension;
	public BlockPos hatchery;
	/** A race base's sky ship's pad (owner 17.10): when its main building falls a portal there opens to it ({@link dev.starmine.world.RacePortal}). */
	public @org.jetbrains.annotations.Nullable BlockPos shipPad;
	public int minerals = 50, vespene, diamonds;
	/** Game time until which villages count this base as dangerous (its terrans hurt a villager or a golem). */
	public long villageHostileUntil;
	/** Minerals ever brought in: the worker target grows with it. */
	public int mineralsCollected;
	public int larvaTimer, decisionTimer, queenTicks = -1;
	/**
	 * Cells drones of this hive walk (body corners): their floors are never dug, so new tunnels do not
	 * cut the ways to the hatchery and to targets. Oldest are forgotten past {@link #MAX_ROUTES}.
	 */
	public final java.util.LinkedHashSet<Long> routes = new java.util.LinkedHashSet<>();
	public static final int MAX_ROUTES = 6000;

	public void addRoute(BlockPos feet) {
		if (routes.add(feet.asLong()) && routes.size() > MAX_ROUTES) {
			var it = routes.iterator();
			it.next();
			it.remove();
		}
	}

	/** Hatchery tier (1 hatchery, 2 lair, 3 hive) and the research running at the hatchery (ticks). */
	public int tier = 1, tierTicks = -1;
	/** Evolution chamber upgrades (owner: attack 1, armor 1, attack 2… up to 3 each) and research ticks. */
	public int attackLevel, armorLevel, upgradeTicks = -1;
	/** Unit upgrades done once (dev.starmine.race.Research: metabolic boost, centrifugal hooks, combat shield). */
	public final java.util.Set<String> researched = new java.util.LinkedHashSet<>();
	/** The other leveled upgrades (owner 17.10: melee, vehicle and ship weapons, plating, flyer, shields, air), by research id. */
	public final java.util.Map<String, Integer> levels = new java.util.LinkedHashMap<>();
	/** A drone has been sent to mutate into this building (until it arrives or gives up). */
	public String buildOrder;
	public long buildOrderUntil;
	/** Hive tunnels dug (owner: not too many) and when the next may start. */
	public int tunnels;
	public long nextTunnel;

	/** Game time the next raid may start. */
	public long nextRaid;
	/** Where its changelings last saw villagers or a player, and until when it remembers (dev.starmine.ability.Changeling). */
	public BlockPos scouted;
	public long scoutedUntil;
	/** The thick creep under the hatchery has been laid. */
	public boolean foundation;
	public final Map<UnitKind, Integer> members = new EnumMap<>(UnitKind.class);
	/** Units paid for but not yet born (in cocoons, a queen in training). */
	public final Map<UnitKind, Integer> pending = new EnumMap<>(UnitKind.class);

	public Hive(UUID id, String dimension, BlockPos hatchery) {
		this.id = id;
		this.dimension = dimension;
		this.hatchery = hatchery;
	}

	public int count(UnitKind kind) {
		return members.getOrDefault(kind, 0);
	}

	public void join(UnitKind kind) {
		members.merge(kind, 1, Integer::sum);
	}

	public void leave(UnitKind kind) {
		members.computeIfPresent(kind, (k, n) -> n > 1 ? n - 1 : null);
	}

	public int pending(UnitKind kind) {
		return pending.getOrDefault(kind, 0);
	}

	public void expect(UnitKind kind, int count) {
		pending.merge(kind, count, Integer::sum);
	}

	public void born(UnitKind kind, int count) {
		pending.computeIfPresent(kind, (k, n) -> n > count ? n - count : null);
	}

	/** Whether the hive has what a unit or building needs (its tech building, its hatchery tier). */
	public boolean unlocked(UnitKind kind) {
		UnitKind need = kind.requires();
		return (need == null || count(need) > 0) && tier >= kind.tier();
	}

	/**
	 * Supply taken by its units of one race (owner 16.10: each race has its own limit, as in SC2 — a player commanding
	 * zerg and terrans has an overlord count and a depot count).
	 */
	public double supplyUsed(UnitKind.Race of) {
		double used = 0;
		for (Map.Entry<UnitKind, Integer> entry : members.entrySet())
			if (entry.getKey().race == of) used += entry.getKey().economy().food() * entry.getValue();
		for (Map.Entry<UnitKind, Integer> entry : pending.entrySet())
			if (entry.getKey().race == of) used += entry.getKey().economy().food() * entry.getValue();
		return used;
	}

	/** Supply of its providers of one race (hatcheries and overlords; command centers and depots; nexuses and pylons), at most {@link #MAX_SUPPLY}. */
	public int supplyCap(UnitKind.Race of) {
		int cap = 0;
		for (Map.Entry<UnitKind, Integer> entry : members.entrySet())
			if (entry.getKey().race == of) cap += entry.getKey().economy().supply() * entry.getValue();
		// hives saved before their hatchery was counted as a member
		if (of == UnitKind.Race.ZERG && race == UnitKind.Race.ZERG && count(UnitKind.HATCHERY) == 0) cap += UnitKind.HATCHERY.economy().supply();
		return Math.min(MAX_SUPPLY, cap);
	}

	public boolean canAfford(UnitKind kind, int count) {
		UnitKind.Economy cost = kind.economy();
		// supply only for what takes it: over its cap (an overlord lost) a hive still builds and morphs an
		// overlord (SC2); it used to afford nothing at all and stood so for ever
		return minerals >= cost.minerals() * count && vespene >= cost.vespene() * count && has(dev.starmine.colony.Costs.materials(kind), count)
			&& (cost.food() <= 0 || supplyUsed(kind.race) + cost.food() * count <= supplyCap(kind.race));
	}

	/** Resources brought in: stores, and the panel's tally by resource label. */
	public void deliver(int minerals, int vespene, int diamonds, String label, int blocks) {
		this.minerals += minerals;
		this.mineralsCollected += minerals;
		this.vespene += vespene;
		this.diamonds += diamonds;
		if (label != null && blocks > 0) collected.merge(label, blocks, Integer::sum);
	}

	/** Gives back what a cancelled order cost. */
	public void refund(UnitKind kind, int count) {
		minerals += kind.economy().minerals() * count;
		vespene += kind.economy().vespene() * count;
		give(dev.starmine.colony.Costs.materials(kind), count);
	}

	public void pay(UnitKind kind, int count) {
		minerals -= kind.economy().minerals() * count;
		vespene -= kind.economy().vespene() * count;
		take(dev.starmine.colony.Costs.materials(kind), count);
	}

	/** Whether the stock holds these resources (by label) `count` times. */
	public boolean has(Map<String, Integer> materials, int count) {
		for (Map.Entry<String, Integer> material : materials.entrySet())
			if (collected.getOrDefault(material.getKey(), 0) < material.getValue() * count) return false;
		return true;
	}

	public void take(Map<String, Integer> materials, int count) {
		for (Map.Entry<String, Integer> material : materials.entrySet()) collected.merge(material.getKey(), -material.getValue() * count, Integer::sum);
	}

	public void give(Map<String, Integer> materials, int count) {
		for (Map.Entry<String, Integer> material : materials.entrySet()) collected.merge(material.getKey(), material.getValue() * count, Integer::sum);
	}

	/** Whether the base can pay a price now (minerals, vespene, stock). */
	public boolean canPay(dev.starmine.colony.Costs.Cost cost) {
		return minerals >= cost.minerals() && vespene >= cost.vespene() && has(cost.materials(), 1);
	}

	/** The lang key of what is missing for a price, or null. */
	public String lacking(dev.starmine.colony.Costs.Cost cost) {
		if (minerals < cost.minerals()) return "message.starmine.need_minerals";
		if (vespene < cost.vespene()) return "message.starmine.need_vespene";
		if (!has(cost.materials(), 1)) return "message.starmine.need_materials";
		return null;
	}

	public void pay(dev.starmine.colony.Costs.Cost cost) {
		minerals -= cost.minerals();
		vespene -= cost.vespene();
		take(cost.materials(), 1);
	}

	public void refund(dev.starmine.colony.Costs.Cost cost) {
		minerals += cost.minerals();
		vespene += cost.vespene();
		give(cost.materials(), 1);
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putUUID("id", id);
		if (shipPad != null) tag.put("shipPad", NbtUtils.writeBlockPos(shipPad));
		tag.putString("dimension", dimension);
		tag.putString("race", race.name());
		if (owner != null) tag.putUUID("owner", owner);
		CompoundTag tally = new CompoundTag();
		collected.forEach(tally::putInt);
		tag.put("collected", tally);
		tag.putInt("waveSize", waveSize);
		tag.putLong("nextWave", nextWave);
		tag.putLongArray("territory", territory.stream().mapToLong(BlockPos::asLong).toArray());
		tag.putLong("nextClaim", nextClaim);
		tag.putLongArray("nydus", nydus.stream().mapToLong(BlockPos::asLong).toArray());
		tag.putLong("nextNydusTransfer", nextNydusTransfer);
		tag.putLong("nextDrop", nextDrop);
		tag.putInt("dropSize", dropSize);
		tag.put("hatchery", NbtUtils.writeBlockPos(hatchery));
		tag.putInt("minerals", minerals);
		tag.putInt("vespene", vespene);
		tag.putLong("villageHostileUntil", villageHostileUntil);
		tag.putInt("diamonds", diamonds);
		tag.putInt("mineralsCollected", mineralsCollected);
		tag.putInt("larvaTimer", larvaTimer);
		tag.putInt("queenTicks", queenTicks);
		tag.putBoolean("foundation", foundation);
		tag.putLong("nextRaid", nextRaid);
		if (scouted != null) tag.put("scouted", NbtUtils.writeBlockPos(scouted));
		tag.putLong("scoutedUntil", scoutedUntil);
		tag.putInt("tier", tier);
		tag.putInt("tierTicks", tierTicks);
		tag.putInt("attackLevel", attackLevel);
		tag.putInt("armorLevel", armorLevel);
		tag.putInt("upgradeTicks", upgradeTicks);
		net.minecraft.nbt.ListTag done = new net.minecraft.nbt.ListTag();
		for (String id : researched) done.add(net.minecraft.nbt.StringTag.valueOf(id));
		tag.put("researched", done);
		CompoundTag upgrades = new CompoundTag();
		levels.forEach(upgrades::putInt);
		tag.put("levels", upgrades);
		tag.putInt("tunnels", tunnels);
		tag.putLong("nextTunnel", nextTunnel);
		tag.putLongArray("routes", routes.stream().mapToLong(Long::longValue).toArray());
		CompoundTag counts = new CompoundTag();
		members.forEach((kind, n) -> counts.putInt(kind.name(), n));
		tag.put("members", counts);
		CompoundTag expected = new CompoundTag();
		pending.forEach((kind, n) -> expected.putInt(kind.name(), n));
		tag.put("pending", expected);
		return tag;
	}

	public static Hive load(CompoundTag tag) {
		Hive hive = new Hive(tag.getUUID("id"), tag.getString("dimension"),
			NbtUtils.readBlockPos(tag, "hatchery").orElse(BlockPos.ZERO));
		try {
			if (tag.contains("race")) hive.race = UnitKind.Race.valueOf(tag.getString("race"));
		} catch (IllegalArgumentException ignored) {
			// unknown race: zerg
		}
		if (tag.hasUUID("owner")) hive.owner = tag.getUUID("owner");
		CompoundTag tally = tag.getCompound("collected");
		for (String key : tally.getAllKeys()) hive.collected.put(key, tally.getInt(key));
		if (tag.contains("waveSize")) hive.waveSize = tag.getInt("waveSize");
		hive.nextWave = tag.getLong("nextWave");
		for (long cell : tag.getLongArray("territory")) hive.territory.add(BlockPos.of(cell));
		hive.nextClaim = tag.getLong("nextClaim");
		for (long cell : tag.getLongArray("nydus")) hive.nydus.add(BlockPos.of(cell));
		if (hive.nydus.size() % 2 == 1) hive.nydus.remove(hive.nydus.size() - 1);
		hive.nextNydusTransfer = tag.getLong("nextNydusTransfer");
		hive.nextDrop = tag.getLong("nextDrop");
		hive.dropSize = tag.getInt("dropSize");
		hive.minerals = tag.getInt("minerals");
		hive.vespene = tag.getInt("vespene");
		hive.villageHostileUntil = tag.getLong("villageHostileUntil");
		hive.diamonds = tag.getInt("diamonds");
		hive.mineralsCollected = tag.getInt("mineralsCollected");
		hive.larvaTimer = tag.getInt("larvaTimer");
		hive.queenTicks = tag.getInt("queenTicks");
		hive.foundation = tag.getBoolean("foundation");
		hive.shipPad = NbtUtils.readBlockPos(tag, "shipPad").orElse(null);
		hive.nextRaid = tag.getLong("nextRaid");
		hive.scouted = NbtUtils.readBlockPos(tag, "scouted").orElse(null);
		hive.scoutedUntil = tag.getLong("scoutedUntil");
		hive.tier = Math.max(1, tag.getInt("tier"));
		hive.tierTicks = tag.contains("tierTicks") ? tag.getInt("tierTicks") : -1;
		hive.attackLevel = tag.getInt("attackLevel");
		hive.armorLevel = tag.getInt("armorLevel");
		hive.upgradeTicks = tag.contains("upgradeTicks") ? tag.getInt("upgradeTicks") : -1;
		for (net.minecraft.nbt.Tag done : tag.getList("researched", net.minecraft.nbt.Tag.TAG_STRING)) hive.researched.add(done.getAsString());
		CompoundTag upgrades = tag.getCompound("levels");
		for (String id : upgrades.getAllKeys()) hive.levels.put(id, upgrades.getInt(id));
		hive.tunnels = tag.getInt("tunnels");
		hive.nextTunnel = tag.getLong("nextTunnel");
		for (long cell : tag.getLongArray("routes")) hive.routes.add(cell);
		readCounts(tag.getCompound("members"), hive.members);
		readCounts(tag.getCompound("pending"), hive.pending);
		return hive;
	}

	private static void readCounts(CompoundTag counts, Map<UnitKind, Integer> into) {
		for (String key : counts.getAllKeys()) {
			try {
				into.put(UnitKind.valueOf(key), counts.getInt(key));
			} catch (IllegalArgumentException ignored) {
				// a kind removed from the mod
			}
		}
	}
}
