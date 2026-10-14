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
}
