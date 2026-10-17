package dev.starmine.creep;

import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Where creep tumors spread (owner 17.10): only a base's tumors spread, a wild one (no base of its side near) feeds its
 * creep and no more; and a base's tumors spread with a purpose, never at random far from it:
 * - anywhere round the base, within {@link #HOME} of its main building;
 * - toward what it mines or wants to mine — the mineral fields within {@link #RESOURCES} of it, the place its expedition
 *   goes, the places it claimed — up to them, under the ground too;
 * - toward its foes — the other bases and the players near — but only over the land it needs, {@link #TERRITORY} of it.
 */
public final class TumorSpread {
	public static final int HOME = 24, TERRITORY = 64, RESOURCES = 64, FOES = 400, BASE_REACH = 128, CLAIM_REACH = 32;
	/** Within this of a goal it is reached: creep goes no further that way. */
	public static final int REACHED = 6;

	/** A place creep is wanted at: `territory` — whether it may be followed only over the base's land (foes' ways). */
	public record Goal(BlockPos at, boolean territory) {
	}

	private TumorSpread() {
	}

	/** The base a tumor at `pos` of `owner`'s side belongs to: its side's, its main building or a claim near; null: a wild tumor. */
	public static @Nullable Hive base(ServerLevel level, BlockPos pos, @Nullable UUID owner) {
		String dimension = level.dimension().location().toString();
		Hive best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Hive hive : HiveSavedData.get(level.getServer()).all()) {
			if (hive.hatchery == null || !dimension.equals(hive.dimension) || !java.util.Objects.equals(hive.owner, owner)) continue;
			double distance = hive.hatchery.distSqr(pos);
			for (BlockPos claim : hive.territory)
				if (claim.distSqr(pos) < CLAIM_REACH * CLAIM_REACH) distance = Math.min(distance, 0);
			if (distance < BASE_REACH * BASE_REACH && distance < bestDistance) {
				best = hive;
				bestDistance = distance;
			}
		}
		return best;
	}

	/** Where the base's creep is wanted, not yet reached from `from`. */
	public static List<Goal> goals(ServerLevel level, Hive base, BlockPos from) {
		List<Goal> goals = new ArrayList<>();
		List<BlockPos> cores = dev.starmine.mineral.Minerals.coresNear(level, base.hatchery, RESOURCES);
		for (BlockPos core : cores.subList(0, Math.min(4, cores.size()))) goals.add(new Goal(core, false));
		if (base.expedition != null) goals.add(new Goal(base.expedition, false));
		for (BlockPos claim : base.territory) goals.add(new Goal(claim, false));
		String dimension = level.dimension().location().toString();
		for (Hive other : HiveSavedData.get(level.getServer()).all())
			if (other != base && other.hatchery != null && dimension.equals(other.dimension) && other.hatchery.distSqr(base.hatchery) < FOES * FOES
				&& !java.util.Objects.equals(other.owner, base.owner)) goals.add(new Goal(other.hatchery, true));
		for (ServerPlayer player : level.players())
			if (!player.isSpectator() && !player.getUUID().equals(base.owner) && player.blockPosition().distSqr(base.hatchery) < FOES * FOES)
				goals.add(new Goal(player.blockPosition(), true));
		goals.removeIf(goal -> goal.at().distSqr(from) <= REACHED * REACHED);
		return goals;
	}

	/**
	 * How good `cell` is for a new tumor of `base` spread from `from` toward `goal` (null: none, round the base): lower is
	 * better; {@link Double#NaN} where it may not go.
	 */
	public static double score(Hive base, BlockPos from, BlockPos cell, @Nullable Goal goal) {
		double home = Math.sqrt(cell.distSqr(base.hatchery));
		if (goal != null) {
			double before = Math.sqrt(from.distSqr(goal.at())), after = Math.sqrt(cell.distSqr(goal.at()));
			if (after < before - 2 && (!goal.territory() || home <= TERRITORY)) return after;
		}
		return home <= HOME ? 1000 + home : Double.NaN;
	}
}
