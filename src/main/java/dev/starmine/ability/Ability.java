package dev.starmine.ability;

import dev.starmine.entity.UnitKind;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

import static dev.starmine.entity.UnitKind.*;

/**
 * A unit ability (owner 14.10, docs/ABILITIES.md): its SC2 numbers and how it is aimed. What it does is
 * in {@link Abilities#effect} (common mechanics: {@link Cloak}, {@link Modes}, {@link Transport}); the
 * engine pays energy, keeps cooldowns, channels and delays ({@link Abilities}). A new ability is a
 * constant here, its users in {@link #users()}, its effect in its race's class.
 *
 * Numbers: energy in SC2 units (a caster has up to 200), times in real seconds (LotV, Faster), range
 * in SC2 units (blocks = x {@link UnitKind#BLOCKS_PER_UNIT} + 1, like weapons).
 */
public enum Ability {
	//             id               key  target          energy cooldown range  channel delay
	CLOAK(         "cloak",         'C', Target.TOGGLE,  25,    0,       0,     0,      0),
	SIEGE_MODE(    "siege_mode",    'E', Target.TOGGLE,  0,     0,       0,     0,      0),
	YAMATO(        "yamato",        'Y', Target.UNIT,    0,     71,      10,    2,      0),
	TACTICAL_JUMP( "tactical_jump", 'T', Target.POINT,   0,     71,      0,     0,      4),
	LOAD(          "load",          'L', Target.NONE,    0,     0,       0,     0,      0),
	UNLOAD(        "unload",        'D', Target.POINT,   0,     0,       0,     0,      0);

	/** How it is aimed: nothing (at once), a toggle (on/off), a unit, a point. */
	public enum Target { NONE, TOGGLE, UNIT, POINT }

	public final String id;
	public final char hotkey;
	public final Target target;
	public final int energy, cooldownTicks, channelTicks, delayTicks;
	/** Reach in blocks (0: anywhere). */
	public final double range;

	Ability(String id, char hotkey, Target target, int energy, double cooldown, double sc2Range, double channel, double delay) {
		this.id = id;
		this.hotkey = hotkey;
		this.target = target;
		this.energy = energy;
		this.cooldownTicks = (int) Math.round(cooldown * 20);
		this.range = sc2Range == 0 ? 0 : sc2Range * BLOCKS_PER_UNIT + 1;
		this.channelTicks = (int) Math.round(channel * 20);
		this.delayTicks = (int) Math.round(delay * 20);
	}

	/** The kinds that have it. */
	public List<UnitKind> users() {
		return switch (this) {
			case CLOAK -> List.of(GHOST, BANSHEE);
			case SIEGE_MODE -> List.of(SIEGE_TANK);
			case YAMATO, TACTICAL_JUMP -> List.of(BATTLECRUISER);
			case LOAD, UNLOAD -> List.of(MEDIVAC, WARP_PRISM, OVERLORD);
		};
	}

	/** A kind's abilities, in command-card order. */
	public static List<Ability> of(UnitKind kind) {
		List<Ability> list = new ArrayList<>();
		for (Ability ability : values()) if (ability.users().contains(kind)) list.add(ability);
		return list;
	}

	public static @Nullable Ability byId(String id) {
		for (Ability ability : values()) if (ability.id.equals(id)) return ability;
		return null;
	}
}
