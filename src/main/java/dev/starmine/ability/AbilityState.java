package dev.starmine.ability;

import dev.starmine.faction.Faction;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * A unit's ability state kept on the server and saved with it: when each ability is ready again (game
 * time), the spell being channelled (its target unit or point and ticks left), a mode change under way,
 * where a transport unloads, its timed states ({@link Status}) and what the lasting spells hold on to
 * (a beam's or a spray's target, a parasite's side, a repair, a carrier's interceptors, the immortal's
 * barrier). Energy, cloak and mode are synced on the unit itself (the command view shows them).
 */
public final class AbilityState {
	final Map<Ability, Long> readyAt = new EnumMap<>(Ability.class);
	@Nullable Ability channel;
	/** What its next hallucination will be (owner 17.10: the sentry's choice; null: zealots). */
	public @Nullable dev.starmine.entity.UnitKind hallucinate;
	@Nullable UUID channelTarget;
	@Nullable Vec3 channelPoint;
	int channelTicks;
	/** Ticks left of a mode change (a tank sieging up); the unit neither moves nor fights meanwhile. */
	int modeTicks;
	/** The mode it changes to. */
	int nextMode;
	/** The kind it turns into when the change is done ({@link Morphs}: a hellion into a hellbat), or null. */
	@Nullable dev.starmine.entity.UnitKind morphTo;
	/** A widow mine: when its sentinel missile is ready again ({@link Passives#widowMine}). */
	long mineReadyAt;
	/** A changeling: whom it follows ({@link Changeling}); an overseer: when it may send the next one out (wild). */
	@Nullable java.util.UUID spied;
	long nextChangeling;
	/** Where a terran building lifted off (a landed command center moves its base's anchor from there). */
	@Nullable Vec3 liftedFrom;
	/** A transport's unload point. */
	@Nullable Vec3 unloadAt;

	/** Where a transport flies to put its cargo down (null when it is not unloading). */
	public @Nullable Vec3 unloadingAt() {
		return unloadAt;
	}
	/** A spell ordered on a target out of reach: the caster goes into reach first, then casts. */
	@Nullable Ability pending;
	@Nullable UUID pendingTarget;
	@Nullable Vec3 pendingPoint;
	int pendingTicks;
	/** Ticks without a foe (the AI drops cloak, unsieges). */
	int calmTicks;
	/** Timed states and their ticks left ({@link Status}). */
	final Map<Status, Integer> statuses = new EnumMap<>(Status.class);
	/** A lasting channel's target (a phoenix's lifted unit, a corruptor's sprayed building, an infestor's parasite) with {@link Status#CHANNELLING}. */
	@Nullable UUID channelOn;
	@Nullable Ability channelOnAbility;
	/** A neural parasite's hold: the side it fights for and the infestor. */
	@Nullable Faction parasiteSide;
	@Nullable UUID parasiteCaster;
	/** An adept's shade out (owner 17.10), and a shade's adept. */
	@Nullable UUID shade, shadeOf;
	/** A cyclone's locked target (owner 17.10) and the ticks it has held it. */
	@Nullable UUID lockOn;
	int lockTicks;
	/** An SCV's repair target and the minerals owed for it so far (paid by whole minerals). */
	@Nullable UUID repairTarget;
	double repairDebt;
	/** A carrier's interceptors aboard and ticks into building the next one. */
	int interceptors = -1, interceptorTicks;
	/** The immortal's barrier: SC2 damage it still absorbs, and when it is ready again (game time). */
	float barrier;
	long barrierReadyAt;
	/** Whether the unit is cloaked because of something that forces it (burrowed, a cloaking field), not its own cloak. */
	boolean forcedCloak;
	/** The weapon and speed have been set from the mode and states (once after it appears or loads). */
	boolean weaponSet;

	/** Ticks until `ability` is ready (0: ready). */
	public long cooldownLeft(Ability ability, long now) {
		return Math.max(0, readyAt.getOrDefault(ability, 0L) - now);
	}

	public boolean channelling() {
		return channel != null;
	}

	/** A new order stops a channel (SC2): a ghost given an order lets its nuke go. */
	public void cancelChannel() {
		channel = null;
		channelTicks = 0;
	}

	public boolean changingMode() {
		return modeTicks > 0;
	}

	/** Whether an SCV is repairing something or on its way to (its work waits meanwhile). */
	public boolean repairing() {
		return repairTarget != null || pending == Ability.REPAIR;
	}

	/** The side a neural parasite makes it fight for, or null ({@link Faction#of}). */
	public @Nullable Faction parasiteSide() {
		return statuses.containsKey(Status.PARASITE) ? parasiteSide : null;
	}

	/** The carrier's interceptors aboard (a new carrier comes with {@link ProtossAbilities#INTERCEPTORS_START}). */
	public int interceptors() {
		return interceptors;
	}

	public void save(CompoundTag tag) {
		CompoundTag ready = new CompoundTag();
		readyAt.forEach((ability, time) -> ready.putLong(ability.id, time));
		if (!ready.isEmpty()) tag.put("StarMineReady", ready);
		if (modeTicks > 0) {
			tag.putInt("StarMineModeTicks", modeTicks);
			tag.putInt("StarMineNextMode", nextMode);
			if (morphTo != null) tag.putString("StarMineMorph", morphTo.name());
		}
		if (unloadAt != null) {
			tag.putDouble("StarMineUnloadX", unloadAt.x);
			tag.putDouble("StarMineUnloadY", unloadAt.y);
			tag.putDouble("StarMineUnloadZ", unloadAt.z);
		}
		if (!statuses.isEmpty()) {
			CompoundTag states = new CompoundTag();
			statuses.forEach((status, ticks) -> states.putInt(status.name(), ticks));
			tag.put("StarMineStatus", states);
		}
		if (channelOn != null && channelOnAbility != null) {
			tag.putUUID("StarMineChannelOn", channelOn);
			tag.putString("StarMineChannelOnAbility", channelOnAbility.id);
		}
		if (parasiteSide != null) {
			if (parasiteSide.owner() != null) tag.putUUID("StarMineParasiteOwner", parasiteSide.owner());
			if (parasiteSide.race() != null) tag.putString("StarMineParasiteRace", parasiteSide.race().name());
			if (parasiteCaster != null) tag.putUUID("StarMineParasiteCaster", parasiteCaster);
		}
		if (repairTarget != null) {
			tag.putUUID("StarMineRepair", repairTarget);
			tag.putDouble("StarMineRepairDebt", repairDebt);
		}
		if (interceptors >= 0) {
			tag.putInt("StarMineInterceptors", interceptors);
			tag.putInt("StarMineInterceptorTicks", interceptorTicks);
		}
		if (barrier > 0) tag.putFloat("StarMineBarrier", barrier);
		if (barrierReadyAt > 0) tag.putLong("StarMineBarrierReady", barrierReadyAt);
		if (forcedCloak) tag.putBoolean("StarMineForcedCloak", true);
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
		try {
			morphTo = modeTicks > 0 && tag.contains("StarMineMorph") ? dev.starmine.entity.UnitKind.valueOf(tag.getString("StarMineMorph")) : null;
		} catch (IllegalArgumentException unknown) {
			morphTo = null;
		}
		unloadAt = tag.contains("StarMineUnloadX")
			? new Vec3(tag.getDouble("StarMineUnloadX"), tag.getDouble("StarMineUnloadY"), tag.getDouble("StarMineUnloadZ")) : null;
		statuses.clear();
		CompoundTag states = tag.getCompound("StarMineStatus");
		for (String key : states.getAllKeys()) {
			try {
				statuses.put(Status.valueOf(key), states.getInt(key));
			} catch (IllegalArgumentException ignored) {
				// a state removed from the mod
			}
		}
		channelOn = tag.hasUUID("StarMineChannelOn") ? tag.getUUID("StarMineChannelOn") : null;
		channelOnAbility = Ability.byId(tag.getString("StarMineChannelOnAbility"));
		UUID parasiteOwner = tag.hasUUID("StarMineParasiteOwner") ? tag.getUUID("StarMineParasiteOwner") : null;
		dev.starmine.entity.UnitKind.Race parasiteRace = null;
		try {
			if (tag.contains("StarMineParasiteRace")) parasiteRace = dev.starmine.entity.UnitKind.Race.valueOf(tag.getString("StarMineParasiteRace"));
		} catch (IllegalArgumentException ignored) {
			parasiteRace = null;
		}
		parasiteSide = parasiteOwner != null || parasiteRace != null ? new Faction(parasiteOwner, parasiteRace) : null;
		parasiteCaster = tag.hasUUID("StarMineParasiteCaster") ? tag.getUUID("StarMineParasiteCaster") : null;
		repairTarget = tag.hasUUID("StarMineRepair") ? tag.getUUID("StarMineRepair") : null;
		repairDebt = tag.getDouble("StarMineRepairDebt");
		interceptors = tag.contains("StarMineInterceptors") ? tag.getInt("StarMineInterceptors") : -1;
		interceptorTicks = tag.getInt("StarMineInterceptorTicks");
		barrier = tag.getFloat("StarMineBarrier");
		barrierReadyAt = tag.getLong("StarMineBarrierReady");
		forcedCloak = tag.getBoolean("StarMineForcedCloak");
		weaponSet = false;
	}
}
