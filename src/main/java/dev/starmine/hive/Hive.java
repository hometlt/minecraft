package dev.starmine.hive;

import dev.starmine.entity.ZergKind;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;

/**
 * One zerg hive: its hatchery (whose entity UUID is the hive id), stores and living members.
 *
 * Members are counted by kind while they exist anywhere (also in unloaded chunks); a member leaves
 * when it dies or is discarded. Supply follows SC2 rules with the owner's numbers: every hatchery,
 * overlord and overseer provides 8, units use their SC2 food, and cocoons hold their future units'
 * food through {@link #pending} (units in cocoons or in training). Diamonds are only hoarded.
 */
public final class Hive {
	public static final int MAX_SUPPLY = 200;

	public final UUID id;
	public String dimension;
	public BlockPos hatchery;
	public int minerals = 50, vespene, diamonds;
	/** Minerals ever brought in: the worker target grows with it. */
	public int mineralsCollected;
	public int larvaTimer, decisionTimer, queenTicks = -1;
	/** Game time the next raid may start. */
	public long nextRaid;
	/** The thick creep under the hatchery has been laid. */
	public boolean foundation;
	public final Map<ZergKind, Integer> members = new EnumMap<>(ZergKind.class);
	/** Units paid for but not yet born (in cocoons, a queen in training). */
	public final Map<ZergKind, Integer> pending = new EnumMap<>(ZergKind.class);

	public Hive(UUID id, String dimension, BlockPos hatchery) {
		this.id = id;
		this.dimension = dimension;
		this.hatchery = hatchery;
	}

	public int count(ZergKind kind) {
		return members.getOrDefault(kind, 0);
	}

	public void join(ZergKind kind) {
		members.merge(kind, 1, Integer::sum);
	}

	public void leave(ZergKind kind) {
		members.computeIfPresent(kind, (k, n) -> n > 1 ? n - 1 : null);
	}

	public int pending(ZergKind kind) {
		return pending.getOrDefault(kind, 0);
	}

	public void expect(ZergKind kind, int count) {
		pending.merge(kind, count, Integer::sum);
	}

	public void born(ZergKind kind, int count) {
		pending.computeIfPresent(kind, (k, n) -> n > count ? n - count : null);
	}

	public double supplyUsed() {
		double used = 0;
		for (Map.Entry<ZergKind, Integer> entry : members.entrySet()) used += entry.getKey().economy().food() * entry.getValue();
		for (Map.Entry<ZergKind, Integer> entry : pending.entrySet()) used += entry.getKey().economy().food() * entry.getValue();
		return used;
	}

	public int supplyCap() {
		int cap = ZergKind.HATCHERY.economy().supply();
		for (ZergKind provider : new ZergKind[] {ZergKind.OVERLORD, ZergKind.OVERSEER}) cap += provider.economy().supply() * count(provider);
		return Math.min(MAX_SUPPLY, cap);
	}

	public boolean canAfford(ZergKind kind, int count) {
		ZergKind.Economy cost = kind.economy();
		return minerals >= cost.minerals() * count && vespene >= cost.vespene() * count
			&& supplyUsed() + cost.food() * count <= supplyCap();
	}

	public void pay(ZergKind kind, int count) {
		minerals -= kind.economy().minerals() * count;
		vespene -= kind.economy().vespene() * count;
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putUUID("id", id);
		tag.putString("dimension", dimension);
		tag.put("hatchery", NbtUtils.writeBlockPos(hatchery));
		tag.putInt("minerals", minerals);
		tag.putInt("vespene", vespene);
		tag.putInt("diamonds", diamonds);
		tag.putInt("mineralsCollected", mineralsCollected);
		tag.putInt("larvaTimer", larvaTimer);
		tag.putInt("queenTicks", queenTicks);
		tag.putBoolean("foundation", foundation);
		tag.putLong("nextRaid", nextRaid);
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
		hive.minerals = tag.getInt("minerals");
		hive.vespene = tag.getInt("vespene");
		hive.diamonds = tag.getInt("diamonds");
		hive.mineralsCollected = tag.getInt("mineralsCollected");
		hive.larvaTimer = tag.getInt("larvaTimer");
		hive.queenTicks = tag.getInt("queenTicks");
		hive.foundation = tag.getBoolean("foundation");
		hive.nextRaid = tag.getLong("nextRaid");
		readCounts(tag.getCompound("members"), hive.members);
		readCounts(tag.getCompound("pending"), hive.pending);
		return hive;
	}

	private static void readCounts(CompoundTag counts, Map<ZergKind, Integer> into) {
		for (String key : counts.getAllKeys()) {
			try {
				into.put(ZergKind.valueOf(key), counts.getInt(key));
			} catch (IllegalArgumentException ignored) {
				// a kind removed from the mod
			}
		}
	}
}
