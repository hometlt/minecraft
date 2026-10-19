package dev.starmine.faction;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import java.util.UUID;

/**
 * Team colours (owner 19.10, SC2's): every side has one — the minimap's dots and marks, and the team colour parts of its
 * units' models (the masks of SC2 models, the accent boxes of StarMine's stand-ins). The wild races keep theirs (zerg
 * purple, terran blue, protoss yellow), Amun's forces are red, the xel'naga keepers teal; each player gets one of SC2's
 * other colours by their UUID. Worked out on the server ({@link #of}) and synced with each unit ({@link StarUnit#teamColor}).
 */
public final class TeamColors {
	public static final int ZERG = 0xFF8A2BD0, TERRAN = 0xFF2060FF, PROTOSS = 0xFFEBD129, AMUN = 0xFFB4141E, XELNAGA = 0xFF1CA7EA;
	/** SC2's other team colours, for players: green, orange, pink, light green, lavender, brown, white, dark teal. */
	static final int[] PLAYERS = {0xFF16A000, 0xFFFE8A0E, 0xFFE55BB0, 0xFF96FF91, 0xFFCCA6FC, 0xFF9E6A2A, 0xFFF0F0F0, 0xFF107A66};

	private TeamColors() {
	}

	public static int race(UnitKind.Race race) {
		return switch (race) {
			case ZERG -> ZERG;
			case TERRAN -> TERRAN;
			case PROTOSS -> PROTOSS;
		};
	}

	/** A player's colour, the same on every server and in every session. */
	public static int player(UUID player) {
		long bits = player.getMostSignificantBits() ^ player.getLeastSignificantBits();
		return PLAYERS[(int) Math.floorMod(bits ^ (bits >>> 31), (long) PLAYERS.length)];
	}

	/** A unit's side's colour (server: Amun's tag is known only there). */
	public static int of(StarUnit unit) {
		if (Faction.amun(unit)) return AMUN;
		if (unit.kind.xelnaga()) return XELNAGA;
		return unit.owner().map(TeamColors::player).orElse(race(unit.kind.race));
	}
}
