package dev.starmine.ability;

import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The oracle's stasis wards (owner 19.10: they were kept only in the server's memory and gone after a restart; SC2's
 * ward waits its 180 s whatever happens): each ward is saved per level — where, whose, until when — and armed again
 * when the server starts. A ward whose oracle is not around judges friend and foe by a stand-in oracle of its side.
 */
public final class StasisWards extends SavedData {
	private static final Factory<StasisWards> FACTORY = new Factory<>(StasisWards::new, StasisWards::load, null);

	record Ward(Vec3 at, @Nullable UUID owner, long until) {
	}

	private final List<Ward> wards = new ArrayList<>();

	private StasisWards() {
	}

	public static StasisWards get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(FACTORY, "starmine_stasis_wards");
	}

	/** On server start: every ward still waiting is armed again. */
	public static void load() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			for (ServerLevel level : server.getAllLevels()) {
				StasisWards data = get(level);
				long now = level.getGameTime();
				data.wards.removeIf(ward -> ward.until <= now);
				data.setDirty();
				for (Ward ward : List.copyOf(data.wards)) arm(level, ward, null);
			}
		});
	}

	/** A new ward of `oracle`'s at `point`. */
	static void place(StarUnit oracle, ServerLevel level, Vec3 point) {
		Ward ward = new Ward(point, oracle.owner().orElse(null), level.getGameTime() + ProtossAbilities.WARD_TICKS);
		StasisWards data = get(level);
		data.wards.add(ward);
		data.setDirty();
		arm(level, ward, oracle);
	}

	/** Waits for a ground foe within its trigger: then everyone of the foes' near it is held in stasis, and the ward is spent. */
	private static void arm(ServerLevel level, Ward ward, @Nullable StarUnit oracle) {
		StarUnit side = oracle != null ? oracle : standIn(level, ward);
		if (side == null) return;
		long left = ward.until - level.getGameTime();
		Delayed.whileTrue(level, 10, (int) Math.max(1, left / 10), () -> {
			List<LivingEntity> near = Abilities.around(side, ward.at, ProtossAbilities.WARD_TRIGGER, true).stream().filter(ProtossAbilities::onGround).toList();
			if (near.isEmpty() && level.getGameTime() < ward.until) return true;
			if (!near.isEmpty()) {
				for (LivingEntity other : Abilities.around(side, ward.at, ProtossAbilities.WARD_RADIUS, true))
					if (ProtossAbilities.onGround(other)) Status.apply(other, Status.STASIS, ProtossAbilities.STASIS_TICKS);
				level.sendParticles(ParticleTypes.SNOWFLAKE, ward.at.x, ward.at.y + 1, ward.at.z, 60, ProtossAbilities.WARD_RADIUS / 2, 0.8,
					ProtossAbilities.WARD_RADIUS / 2, 0.02);
			}
			StasisWards data = get(level);
			if (data.wards.remove(ward)) data.setDirty();
			return false;
		});
	}

	/** An oracle of the ward's side, never put into the world: it only tells friend from foe. */
	private static @Nullable StarUnit standIn(ServerLevel level, Ward ward) {
		StarUnit oracle = StarMine.type(UnitKind.ORACLE).create(level);
		if (oracle == null) return null;
		oracle.moveTo(ward.at.x, ward.at.y, ward.at.z);
		if (ward.owner != null) oracle.setOwner(ward.owner);
		return oracle;
	}

	/** Wards waiting in a level. */
	public static int count(ServerLevel level) {
		return get(level).wards.size();
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		ListTag list = new ListTag();
		for (Ward ward : wards) {
			CompoundTag entry = new CompoundTag();
			entry.putDouble("x", ward.at.x);
			entry.putDouble("y", ward.at.y);
			entry.putDouble("z", ward.at.z);
			if (ward.owner != null) entry.putUUID("owner", ward.owner);
			entry.putLong("until", ward.until);
			list.add(entry);
		}
		tag.put("wards", list);
		return tag;
	}

	private static StasisWards load(CompoundTag tag, HolderLookup.Provider registries) {
		StasisWards data = new StasisWards();
		for (Tag element : tag.getList("wards", Tag.TAG_COMPOUND)) {
			CompoundTag entry = (CompoundTag) element;
			data.wards.add(new Ward(new Vec3(entry.getDouble("x"), entry.getDouble("y"), entry.getDouble("z")),
				entry.hasUUID("owner") ? entry.getUUID("owner") : null, entry.getLong("until")));
		}
		return data;
	}

	/** Re-arms the saved wards of a level as at a server start (for tests). */
	static void rearm(ServerLevel level) {
		for (Ward ward : List.copyOf(get(level).wards)) arm(level, ward, null);
	}
}
