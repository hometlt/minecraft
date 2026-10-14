package dev.starmine.ability;

import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * What the game tests of abilities, pathfinding and balance share: a flat stone arena (`starmine:arena`,
 * cleared up to 16 blocks so that flyers keep their height over the ground — the heightmap counts the
 * arena's barrier ceiling), units put on it, a player's base founded by its main building.
 */
public final class TestArena {
	public static final String ARENA = "starmine:arena";

	private TestArena() {
	}

	/** Clears the arena to a stone floor at y 1 and forgets every base (a leftover wild base would adopt the test's wild units). */
	public static void clear(GameTestHelper helper) {
		clear(helper, true);
	}

	/** {@link #clear}; `forget` false keeps the bases (tests of one batch that found bases side by side). */
	public static void clear(GameTestHelper helper, boolean forget) {
		ServerLevel level = helper.getLevel();
		for (BlockPos rel : BlockPos.betweenClosed(0, 0, 0, 47, 16, 47))
			level.setBlock(helper.absolutePos(rel), rel.getY() == 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
		if (forget) HiveSavedData.get(level.getServer()).clear();
	}

	/** A unit standing at (x, z) on the floor (a flyer 3 blocks up), of a player (`owner`) or wild (null). */
	public static StarUnit unit(GameTestHelper helper, UnitKind kind, double x, double z, @Nullable UUID owner) {
		StarUnit unit = StarMine.type(kind).create(helper.getLevel());
		Vec3 at = point(helper, x, z);
		unit.moveTo(at.x, at.y + (kind.flying ? 3 : 0), at.z, 0, 0);
		if (owner != null) unit.setOwner(owner);
		helper.getLevel().addFreshEntity(unit);
		return unit;
	}

	/** A point on the floor of the arena (feet level). */
	public static Vec3 point(GameTestHelper helper, double x, double z) {
		return Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)).add(x + 0.5, 2, z + 0.5);
	}

	/**
	 * Where an entity stands in the arena's own coordinates (x, y, z from its corner). The game's
	 * `relativePos` turns positions half round (its rotation is off by 180°), so tests use this instead.
	 */
	public static Vec3 rel(GameTestHelper helper, net.minecraft.world.entity.Entity entity) {
		return entity.position().subtract(Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)));
	}

	/** {@link #rel} for messages. */
	public static String where(GameTestHelper helper, net.minecraft.world.entity.Entity entity) {
		Vec3 at = rel(helper, entity);
		return String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", at.x, at.y, at.z);
	}

	/** The base a player's main building founded (on its first ticks), or null. */
	public static @Nullable Hive base(GameTestHelper helper, StarUnit main) {
		return main.hiveId() == null ? null : HiveSavedData.get(helper.getLevel().getServer()).get(main.hiveId());
	}

	/**
	 * A test's own schedule: steps due a number of ticks from now, and steps run every tick, which may
	 * schedule further steps. (The game's `runAfterDelay` and `onEachTick` must not be called from inside
	 * a step they run: the test server crashes on the change to their map while it goes through it.)
	 */
	public static final class Timeline {
		private record Step(long due, Runnable run, boolean every) {
		}

		private final List<Step> steps = new ArrayList<>();
		private long now;

		private Timeline() {
		}

		/** A timeline ticked with the test (call once, outside any scheduled step). */
		public static Timeline of(GameTestHelper helper) {
			Timeline timeline = new Timeline();
			helper.onEachTick(timeline::tick);
			return timeline;
		}

		/** A step `ticks` from now. */
		public Timeline at(long ticks, Runnable step) {
			steps.add(new Step(now + ticks, step, false));
			return this;
		}

		/** A step every tick from now on. */
		public Timeline every(Runnable step) {
			steps.add(new Step(now, step, true));
			return this;
		}

		/** Ticks since the timeline began. */
		public long now() {
			return now;
		}

		private void tick() {
			now++;
			for (Step step : List.copyOf(steps))
				if (step.due <= now) {
					if (!step.every) steps.remove(step);
					step.run.run();
				}
		}
	}

	/** A unit of a base (counted in it, like one it trained). */
	public static StarUnit member(GameTestHelper helper, Hive base, UnitKind kind, double x, double z) {
		StarUnit unit = unit(helper, kind, x, z, base.owner);
		unit.joinHive(base);
		return unit;
	}
}
