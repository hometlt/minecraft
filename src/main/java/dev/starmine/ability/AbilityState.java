package dev.starmine.ability;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * A unit's ability state kept on the server and saved with it: when each ability is ready again (game
 * time), the spell being channelled (its target unit or point and ticks left), a mode change under way,
 * where a transport unloads. Energy, cloak and mode are synced on the unit itself (the command view
 * shows them).
 */
public final class AbilityState {
	final Map<Ability, Long> readyAt = new EnumMap<>(Ability.class);
	@Nullable Ability channel;
	@Nullable UUID channelTarget;
	@Nullable Vec3 channelPoint;
	int channelTicks;
	/** Ticks left of a mode change (a tank sieging up); the unit neither moves nor fights meanwhile. */
	int modeTicks;
	/** The mode it changes to. */
	int nextMode;
	/** A transport's unload point. */
	@Nullable Vec3 unloadAt;
	/** A spell ordered on a target out of reach: the caster goes into reach first, then casts. */
	@Nullable Ability pending;
	@Nullable UUID pendingTarget;
	int pendingTicks;
	/** Ticks without a foe (the AI drops cloak, unsieges). */
	int calmTicks;

	/** Ticks until `ability` is ready (0: ready). */
	public long cooldownLeft(Ability ability, long now) {
		return Math.max(0, readyAt.getOrDefault(ability, 0L) - now);
	}

	public boolean channelling() {
		return channel != null;
	}

	public boolean changingMode() {
		return modeTicks > 0;
	}

	public void save(CompoundTag tag) {
		CompoundTag ready = new CompoundTag();
		readyAt.forEach((ability, time) -> ready.putLong(ability.id, time));
		if (!ready.isEmpty()) tag.put("StarMineReady", ready);
		if (modeTicks > 0) {
			tag.putInt("StarMineModeTicks", modeTicks);
			tag.putInt("StarMineNextMode", nextMode);
		}
		if (unloadAt != null) {
			tag.putDouble("StarMineUnloadX", unloadAt.x);
			tag.putDouble("StarMineUnloadY", unloadAt.y);
			tag.putDouble("StarMineUnloadZ", unloadAt.z);
		}
	}

	public void load(CompoundTag tag) {
		readyAt.clear();
		CompoundTag ready = tag.getCompound("StarMineReady");
		for (String key : ready.getAllKeys()) {
			Ability ability = Ability.byId(key);
			if (ability != null) readyAt.put(ability, ready.getLong(key));
		}
		modeTicks = tag.getInt("StarMineModeTicks");
		nextMode = tag.getInt("StarMineNextMode");
		unloadAt = tag.contains("StarMineUnloadX")
			? new Vec3(tag.getDouble("StarMineUnloadX"), tag.getDouble("StarMineUnloadY"), tag.getDouble("StarMineUnloadZ")) : null;
	}
}
