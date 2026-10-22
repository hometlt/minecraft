package dev.starmine.world;

import dev.starmine.entity.UnitKind;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * A unit a structure wants at a place: a guard keeping to `hall` (a nest dweller, not counted in the
 * base, {@link dev.starmine.entity.StarUnit#makeNest}), a base member when `hall` is null; an egg with
 * `brood` hatches that many of it when disturbed. Put in the world later, when its chunk ticks
 * ({@link dev.starmine.hive.HiveStructure.Pending}).
 */
public record Spawn(UnitKind kind, BlockPos pos, @Nullable BlockPos hall, @Nullable UnitKind brood, int broodCount) {
	public static Spawn guard(UnitKind kind, BlockPos pos, BlockPos hall) {
		return new Spawn(kind, pos.immutable(), hall.immutable(), null, 0);
	}

	public static Spawn member(UnitKind kind, BlockPos pos) {
		return new Spawn(kind, pos.immutable(), null, null, 0);
	}

	/** The brood count that marks a burrowed guard (owner 22.10: zerg lying in wait in their tunnels). */
	public static final int BURROWED = -1;
	/** The tag of a zerg lying in wait burrowed: it comes up at a foe near it (dev.starmine.ability.AbilityAi). */
	public static final String AMBUSH = "starmine_ambush";

	/** A guard that waits burrowed by `pos` and comes up at a foe near it (an ambush). */
	public static Spawn burrowed(UnitKind kind, BlockPos pos, BlockPos hall) {
		return new Spawn(kind, pos.immutable(), hall.immutable(), kind, BURROWED);
	}
}
