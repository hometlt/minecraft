package dev.starmine.race;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveLogic;
import dev.starmine.hive.HiveSavedData;
import dev.starmine.hive.HiveTerrain;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Putting a building down where a worker was sent (SCV, probe; drones mutate in {@link HiveLogic#mutate}):
 * tech, money, supply, the race's own site rule ({@link RaceRules#siteRefusal}), a free and levelled
 * site — a vespene building on a vein outlet instead, wherever that is. Paid then; the building starts
 * at 10 % health and grows for its build time.
 */
public final class Placement {
	private Placement() {
	}

	/** Footprint half-size of a building in blocks. */
	public static int half(UnitKind kind) {
		return (int) Math.ceil(kind.width / 2);
	}

	/** No other building (or main building) overlaps the footprint. */
	public static boolean siteFree(Level level, UnitKind kind, BlockPos site) {
		AABB box = new AABB(site).inflate(half(kind), 0, half(kind)).expandTowards(0, kind.height, 0);
		return level.getEntitiesOfClass(StarUnit.class, box, mob -> mob.kind.structure() && mob.isAlive()).isEmpty();
	}

	/** Why the base cannot have this now (a lang key), or null: tech, minerals, vespene, supply. */
	public static @Nullable String refusal(Hive base, UnitKind kind) {
		if (!base.unlocked(kind)) return "message.starmine.need_tech";
		UnitKind.Economy cost = kind.economy();
		if (base.minerals < cost.minerals()) return "message.starmine.need_minerals";
		if (base.vespene < cost.vespene()) return "message.starmine.need_vespene";
		if (cost.food() > 0 && base.supplyUsed() + cost.food() > base.supplyCap()) return "message.starmine.need_supply"; // buildings take none
		return null;
	}

	/** The site a building really takes: a vespene building sits on the outlet under the chosen spot; null when there is none. */
	public static @Nullable BlockPos site(Level level, UnitKind kind, BlockPos chosen) {
		if (!kind.gas()) return chosen;
		BlockPos outlet = dev.starmine.vespene.Vespene.outletUnder(level, chosen);
		return outlet == null ? null : outlet.above();
	}

	/** Places the building for the worker's base, or tells the base's player why not and returns null. */
	public static @Nullable StarUnit put(StarUnit worker, ServerLevel level, Hive base, UnitKind kind, BlockPos chosen) {
		String refusal = refusal(base, kind);
		BlockPos site = site(level, kind, chosen);
		if (refusal == null && site == null) refusal = "message.starmine.need_outlet";
		if (refusal == null) refusal = kind.race.rules().siteRefusal(level, kind, site, base.id);
		if (refusal == null && !siteFree(level, kind, site)) refusal = "message.starmine.site_blocked";
		if (refusal == null && !kind.gas() && !HiveTerrain.prepare(level, site, half(kind), (int) Math.ceil(kind.height), true))
			refusal = "message.starmine.site_blocked";
		if (refusal != null) {
			dev.starmine.colony.BaseLogic.tell(level, base, refusal);
			return null;
		}
		base.pay(kind, 1);
		base.expect(kind, 1);
		StarUnit building = HiveLogic.spawn(level, base, kind, Vec3.atBottomCenterOf(site), null);
		if (building == null) return null;
		building.startConstruction(kind.economy().trainTicks());
		HiveSavedData.get(level.getServer()).setDirty();
		return building;
	}
}
