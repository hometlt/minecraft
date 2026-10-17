package dev.starmine.client;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.gear.TraitPotions;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Seeing through walls (owner 17.10): under a radar potion the player sees every creature and unit within
 * {@link TraitPotions#RADAR_RANGE} as a glowing outline through blocks; the player's own sensor towers do the same
 * within their reach ({@link UnitKind#SENSOR_TOWER}, SC2 30).
 */
public final class Radar {
	private Radar() {
	}

	/** The player's sensor towers, found once a tick (not once an entity a frame). */
	private static java.util.List<StarUnit> towers = java.util.List.of();
	private static long towersAt = -1;

	/** Whether the player sees `entity` through blocks now. */
	public static boolean glows(Entity entity) {
		Player player = Minecraft.getInstance().player;
		if (player == null || entity == player || !(entity instanceof LivingEntity living) || !living.isAlive()) return false;
		if (player.hasEffect(TraitPotions.RADAR) && entity.distanceTo(player) <= TraitPotions.RADAR_RANGE) return true;
		var level = Minecraft.getInstance().level;
		if (level == null) return false;
		if (towersAt != level.getGameTime()) {
			towersAt = level.getGameTime();
			towers = level.getEntitiesOfClass(StarUnit.class, player.getBoundingBox().inflate(160),
				unit -> unit.kind == UnitKind.SENSOR_TOWER && unit.isAlive() && !unit.underConstruction()
					&& unit.owner().filter(player.getUUID()::equals).isPresent());
		}
		for (StarUnit tower : towers)
			if (tower != entity && tower.distanceTo(entity) <= UnitKind.SENSOR_RANGE && !dev.starmine.faction.Faction.allied(tower, entity)) return true;
		return false;
	}
}
