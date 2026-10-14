package dev.starmine.faction;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Sides (owner 14.10, docs/ARCHITECTURE.md): a StarMine unit belongs to a player (spawned from that
 * player's egg, or produced by that player's base) or is wild (appeared in the world). This class is
 * the only place that says who is an ally and who is a foe:
 *
 * - a player's units are allies of the player and of each other (whatever their race); they fight
 *   wild units and monsters; other players' units and villages are left alone unless they attack;
 * - wild units are allies of wild units of their race only; they fight players, players' units,
 *   other races and monsters; wild zerg attack every creature (owner: zerg are hostile to all life).
 */
public record Faction(@Nullable UUID owner, @Nullable UnitKind.Race race) {
	/** The side of an entity: a unit's owner or its wild race, a player's own side; null for anything else. */
	public static @Nullable Faction of(Entity entity) {
		if (entity instanceof StarUnit unit) {
			Faction parasite = unit.abilities.parasiteSide(); // a neural parasite's hold: the infestor's side for a while
			return parasite != null ? parasite : new Faction(unit.owner().orElse(null), unit.kind.race);
		}
		if (entity instanceof Player player) return new Faction(player.getUUID(), null);
		return null;
	}

	public boolean wild() {
		return owner == null;
	}

	/** Never fight each other: one owner, or wild units of one race. */
	public static boolean allied(Entity a, Entity b) {
		if (a == b) return true;
		Faction fa = of(a), fb = of(b);
		if (fa == null || fb == null) return false;
		if (!fa.wild() || !fb.wild()) return fa.owner != null && fa.owner.equals(fb.owner);
		return fa.race != null && fa.race == fb.race;
	}

	/** Whether `unit` attacks `other` unprovoked (targets, crawlers, splash). */
	public static boolean hostile(StarUnit unit, LivingEntity other) {
		if (!other.isAlive() || other instanceof ArmorStand || allied(unit, other)) return false;
		if (other instanceof StarUnit changeling && changeling.kind == UnitKind.CHANGELING) return false; // SC2: nobody attacks a changeling unprovoked
		if (other instanceof Player player && (player.isCreative() || player.isSpectator())) return false;
		Faction self = of(unit), them = of(other);
		boolean wild = self == null || self.wild();
		if (them != null) {
			// a player's side against another player's side: left alone unless provoked
			if (!wild && !them.wild()) return false;
			return true; // a wild side against anyone else, or a player's against the wild
		}
		if (other instanceof Enemy) return true;
		if (!wild) return false; // a player's units leave villages and animals alone
		if (other instanceof AbstractVillager || other instanceof IronGolem) return unit.kind.race == UnitKind.Race.ZERG;
		return unit.kind.race == UnitKind.Race.ZERG; // wild zerg: every creature
	}
}
