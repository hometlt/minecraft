package dev.starmine.creep;

import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * What keeps creep alive (owner: creep disappears where no tumor or hatchery/lair/hive is near):
 * hatcheries of this dimension's hives (radius {@link #HATCHERY_RADIUS}: SC2 12, the hatchery is twice SC2 size) and creep tumors
 * (radius {@link #TUMOR_RADIUS}, SC2 10). Tumor positions are kept per dimension as saved data, so
 * receding never mistakes an unloaded tumor for a dead one.
 */
public final class CreepSources extends SavedData {
	public static final int HATCHERY_RADIUS = 16, TUMOR_RADIUS = 10, TUMOR_SPACING = 8, MAX_TUMORS = 60;
	private static final Factory<CreepSources> FACTORY = new Factory<>(CreepSources::new, CreepSources::load, null);
	private final Set<BlockPos> tumors = new HashSet<>();

	/** The tumor set of a level (server only); changes are saved. */
	public static TumorSet tumors(Level level) {
		return new TumorSet(((ServerLevel) level).getDataStorage().computeIfAbsent(FACTORY, "starmine_creep_tumors"));
	}

	/** Whether creep at this position is fed by some source. */
	public static boolean covered(ServerLevel level, BlockPos pos) {
		String dimension = level.dimension().location().toString();
		for (Hive hive : HiveSavedData.get(level.getServer()).all())
			if (hive.dimension.equals(dimension) && hive.hatchery.distSqr(pos) <= sq(HATCHERY_RADIUS + 0.5)) return true;
		for (BlockPos tumor : tumors(level).all())
			if (tumor.distSqr(pos) <= sq(TUMOR_RADIUS + 0.5)) return true;
		return false;
	}

	/** Distance to the nearest source (for placing new tumors away from the others). */
	public static double nearestSource(ServerLevel level, BlockPos pos) {
		double best = Double.MAX_VALUE;
		String dimension = level.dimension().location().toString();
		for (Hive hive : HiveSavedData.get(level.getServer()).all())
			if (hive.dimension.equals(dimension)) best = Math.min(best, Math.sqrt(hive.hatchery.distSqr(pos)));
		for (BlockPos tumor : tumors(level).all()) best = Math.min(best, Math.sqrt(tumor.distSqr(pos)));
		return best;
	}

	/** Distance to the nearest creep tumor (new tumors keep {@link #TUMOR_SPACING} from the others). */
	public static double nearestTumor(ServerLevel level, BlockPos pos) {
		double best = Double.MAX_VALUE;
		for (BlockPos tumor : tumors(level).all()) best = Math.min(best, Math.sqrt(tumor.distSqr(pos)));
		return best;
	}

	private static double sq(double v) {
		return v * v;
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		tag.put("tumors", new LongArrayTag(tumors.stream().mapToLong(BlockPos::asLong).toArray()));
		return tag;
	}

	private static CreepSources load(CompoundTag tag, HolderLookup.Provider registries) {
		CreepSources data = new CreepSources();
		for (long packed : tag.getLongArray("tumors")) data.tumors.add(BlockPos.of(packed));
		return data;
	}

	/** A dimension's tumors. */
	public record TumorSet(CreepSources data) {
		public Set<BlockPos> all() {
			return data.tumors;
		}

		public void add(BlockPos pos) {
			if (data.tumors.add(pos.immutable())) data.setDirty();
		}

		public void remove(BlockPos pos) {
			if (data.tumors.remove(pos)) data.setDirty();
		}

		public int size() {
			return data.tumors.size();
		}
	}
}
