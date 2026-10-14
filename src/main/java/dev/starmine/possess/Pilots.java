package dev.starmine.possess;

import dev.starmine.entity.UnitKind;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Which players play as which unit kind, on each side (owner 14.10): the server fills it from its
 * sessions ({@link Possession}), the client from {@link PossessNetwork.PilotState}. The player's
 * body takes the unit's size ({@link #dimensions}, the dimensions mixin) and is drawn as the unit.
 */
public final class Pilots {
	private static final Map<UUID, UnitKind> SERVER = new ConcurrentHashMap<>(), CLIENT = new ConcurrentHashMap<>();
	/** Client: whether a piloted unit is cloaked (drawn see-through) or revealed. */
	public static final Map<UUID, Integer> CLIENT_FLAGS = new ConcurrentHashMap<>();

	private Pilots() {
	}

	private static Map<UUID, UnitKind> side(boolean client) {
		return client ? CLIENT : SERVER;
	}

	/** The kind a player plays as, or null. */
	public static @Nullable UnitKind kindOf(Player player) {
		return side(player.level().isClientSide).get(player.getUUID());
	}

	public static void set(boolean client, UUID player, @Nullable UnitKind kind) {
		if (kind == null) side(client).remove(player);
		else side(client).put(player, kind);
	}

	public static void clear(boolean client) {
		side(client).clear();
		if (client) CLIENT_FLAGS.clear();
	}

	/** A piloted body's size: the unit's hitbox (eyes at 85 % of its height), whatever the pose. */
	public static @Nullable EntityDimensions dimensions(Player player) {
		UnitKind kind = kindOf(player);
		if (kind == null) return null;
		float width = Math.min(kind.width, 4.5f), height = Math.max(0.6f, Math.min(kind.height, 6f));
		return EntityDimensions.scalable(width, height).withEyeHeight(height * 0.85f);
	}
}
