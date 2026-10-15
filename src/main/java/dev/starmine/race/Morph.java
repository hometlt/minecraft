package dev.starmine.race;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveLogic;
import dev.starmine.hive.HiveSavedData;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * Unit morphs (owner 14.10, SC2): a unit of the base becomes a cocoon that hatches the new kind —
 * zergling to baneling, roach to ravager, hydralisk to lurker, corruptor to brood lord, overlord to
 * overseer ({@link RaceRules#morphFrom}). The economy of a kind is its SC2 total, so a morph pays the
 * difference to the unit it comes from (a baneling: 25 minerals 25 vespene) and needs the difference in
 * supply. The unit is lost when the cocoon dies, as in SC2. Players order it from the command card, the
 * AI of a wild base like any other unit it wants ({@link dev.starmine.ai.AiCommander}).
 */
public final class Morph {
	private Morph() {
	}

	/** The unit a kind morphs from, or null when it is not a morph. */
	public static @Nullable UnitKind from(UnitKind kind) {
		return kind.race.rules().morphFrom(kind);
	}

	/** What the morph costs on top of the unit it comes from (minerals, vespene, supply), its time; or the whole economy for other kinds. */
	public static UnitKind.Economy price(UnitKind kind) {
		UnitKind.Economy to = kind.economy();
		UnitKind source = from(kind);
		if (source == null) return to;
		UnitKind.Economy was = source.economy();
		return new UnitKind.Economy(Math.max(0, to.minerals() - was.minerals()), Math.max(0, to.vespene() - was.vespene()),
			Math.max(0, to.food() - was.food()), to.trainTicks(), to.supply(), to.cargo());
	}

	/** Why the base cannot morph into `kind` now (a lang key), or null. */
	public static @Nullable String refusal(Hive base, UnitKind kind) {
		if (!base.unlocked(kind)) return "message.starmine.need_tech";
		UnitKind.Economy cost = price(kind);
		if (base.minerals < cost.minerals()) return "message.starmine.need_minerals";
		if (base.vespene < cost.vespene()) return "message.starmine.need_vespene";
		if (cost.food() > 0 && base.supplyUsed() + cost.food() > base.supplyCap()) return "message.starmine.need_supply"; // a morph that takes no more supply
		return null;
	}

	/** Whether this unit may morph: a working member of the base (not a nest dweller), alive and of the right kind. */
	public static boolean able(StarUnit unit, Hive base, UnitKind kind) {
		return unit.isAlive() && unit.kind == from(kind) && base.id.equals(unit.hiveId()) && unit.nest() == null;
	}

	/** The unit becomes a cocoon of `kind` (paid now); false when it may not or the base cannot pay. */
	public static boolean start(StarUnit unit, ServerLevel level, Hive base, UnitKind kind) {
		if (!able(unit, base, kind) || refusal(base, kind) != null) return false;
		UnitKind.Economy cost = price(kind);
		base.minerals -= cost.minerals();
		base.vespene -= cost.vespene();
		boolean airborne = unit.kind.flying;
		var at = unit.position();
		unit.discard(); // leaves the base: its supply goes to the cocoon
		base.expect(kind, 1);
		StarUnit cocoon = HiveLogic.spawn(level, base, UnitKind.EGG, at, null);
		if (cocoon != null) {
			if (airborne) cocoon.setNoGravity(true);
			cocoon.becomeCocoon(kind, 1, Math.max(20, cost.trainTicks()));
		}
		HiveSavedData.get(level.getServer()).setDirty();
		return true;
	}
}
