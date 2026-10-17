package dev.starmine.ability;

import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * Units that turn into another kind (owner 17.10: the hellbat and the landed viking are units of their own,
 * with their own eggs; the factory trains hellbats). The hellion and the hellbat, the viking and the assault
 * viking change into each other by their toggle ({@link Ability#HELLBAT}, {@link Ability#VIKING_LAND}) in
 * SC2's time: the unit stands still for it ({@link Modes#change}), then a unit of the other kind takes its
 * place — the same entity id and UUID (its selection, orders and control groups stay), its base, owner,
 * energy and cooldowns, its health in proportion. A player who plays as it goes on playing as the new kind.
 */
public final class Morphs {
	private Morphs() {
	}

	/** The kind an ability turns this kind into, or null. */
	public static @Nullable UnitKind target(UnitKind kind, Ability ability) {
		return switch (ability) {
			case HELLBAT -> kind == UnitKind.HELLION ? UnitKind.HELLBAT : kind == UnitKind.HELLBAT ? UnitKind.HELLION : null;
			case VIKING_LAND -> kind == UnitKind.VIKING ? UnitKind.VIKING_ASSAULT : kind == UnitKind.VIKING_ASSAULT ? UnitKind.VIKING : null;
			default -> null;
		};
	}

	/** Whether a morph toggle counts as on for this kind (it is the second form). */
	public static boolean on(UnitKind kind, Ability ability) {
		return ability == Ability.HELLBAT && kind == UnitKind.HELLBAT || ability == Ability.VIKING_LAND && kind == UnitKind.VIKING_ASSAULT;
	}

	/** Starts the change: it takes `ticks`, then {@link #into}. */
	static void begin(StarUnit unit, UnitKind to, int ticks) {
		unit.abilities.morphTo = to;
		Modes.change(unit, unit.mode(), ticks);
	}

	/** Puts a unit of `to` in this one's place; returns it (or the old unit when it could not). */
	public static StarUnit into(StarUnit unit, UnitKind to) {
		if (!(unit.level() instanceof ServerLevel level)) return unit;
		ServerPlayer pilot = unit.pilot();
		if (pilot != null) { // back into the world first, then played again as the new kind
			java.util.UUID id = unit.getUUID();
			dev.starmine.possess.Possession.end(pilot, false);
			if (!(level.getEntity(id) instanceof StarUnit out)) return unit;
			unit = out;
		}
		StarUnit next = StarMine.type(to).create(level);
		if (next == null) return unit;
		CompoundTag tag = new CompoundTag();
		unit.saveWithoutId(tag);
		tag.remove("attributes"); // its new kind's health, speed and armor
		tag.remove("Health");
		tag.remove("StarMineMode");
		tag.remove("StarMineModeTicks");
		tag.remove("NoGravity"); // its new kind decides: a flyer floats, a walker falls
		float health = unit.getHealth() / Math.max(1, unit.getMaxHealth());
		int id = unit.getId();
		unit.remove(Entity.RemovalReason.UNLOADED_WITH_PLAYER); // not killed: its base keeps it until the new one counts
		next.load(tag);
		next.setId(id);
		next.setHealth(Math.max(1, health * next.getMaxHealth()));
		next.setNoGravity(to.flying);
		if (to.flying) next.setPos(next.getX(), next.getY() + 1, next.getZ()); // a viking takes off
		level.addFreshEntity(next);
		next.applyModeBody();
		next.refreshWeapon();
		Hive base = Abilities.base(next);
		if (base != null) {
			base.leave(unit.kind);
			base.join(to);
			HiveSavedData.get(level.getServer()).setDirty();
		}
		level.sendParticles(ParticleTypes.CLOUD, next.getX(), next.getY() + next.getBbHeight() / 2, next.getZ(), 16,
			next.getBbWidth() / 2, next.getBbHeight() / 3, next.getBbWidth() / 2, 0.02);
		if (pilot != null) dev.starmine.possess.Possession.start(pilot, next);
		return next;
	}
}
