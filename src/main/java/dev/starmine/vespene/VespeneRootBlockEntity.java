package dev.starmine.vespene;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A vespene vein (owner 14.10): its channel cells from the bedrock up and its outlets. The vein is one
 * store of vespene — the vespene blocks in its channel and outlets:
 * - an outlet emptied (by a worker, a bucket) is filled again after {@link #OUTLET_REFILL} from the
 *   channel, so taking from any outlet drains the whole vein;
 * - every {@link #REGEN} one more vespene grows: in the empty outlets first, one after another, then
 *   in the channel from the bottom up, until the vein is full again.
 */
public final class VespeneRootBlockEntity extends BlockEntity {
	public static final int OUTLET_REFILL = 100, REGEN = 600;

	private long[] channel = new long[0], outlets = new long[0];
	private long[] emptySince = new long[0];
	private int nextOutlet;
	private long nextRegen;

	public VespeneRootBlockEntity(BlockPos pos, BlockState state) {
		super(Vespene.ROOT_ENTITY, pos, state);
	}

	/** Set by the world generation: the channel bottom-up and the outlets. */
	public void setVein(List<BlockPos> channelCells, List<BlockPos> outletCells) {
		channel = channelCells.stream().mapToLong(BlockPos::asLong).toArray();
		outlets = outletCells.stream().mapToLong(BlockPos::asLong).toArray();
		emptySince = new long[outlets.length];
		setChanged();
	}

	public int outletCount() {
		return outlets.length;
	}

	public BlockPos outlet(int i) {
		return BlockPos.of(outlets[i]);
	}

	/** Vespene blocks now in the vein (channel and outlets) and how many it holds when full. */
	public int content(Level level) {
		int count = 0;
		for (long cell : channel) if (isVespene(level, BlockPos.of(cell))) count++;
		for (long cell : outlets) if (isVespene(level, BlockPos.of(cell))) count++;
		return count;
	}

	public int capacity() {
		return channel.length + outlets.length;
	}

	private static boolean isVespene(Level level, BlockPos pos) {
		return level.isLoaded(pos) && level.getBlockState(pos).is(Vespene.VESPENE);
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, VespeneRootBlockEntity vein) {
		long now = level.getGameTime();
		if ((now + pos.hashCode()) % 20 != 0) return;
		if (vein.emptySince.length != vein.outlets.length) vein.emptySince = new long[vein.outlets.length];
		// emptied outlets are fed from the channel
		for (int i = 0; i < vein.outlets.length; i++) {
			BlockPos outlet = BlockPos.of(vein.outlets[i]);
			if (!level.isLoaded(outlet) || !level.getBlockState(outlet).is(Vespene.VENT)) {
				vein.emptySince[i] = 0;
				continue;
			}
			if (vein.emptySince[i] == 0) vein.emptySince[i] = now;
			if (now - vein.emptySince[i] < OUTLET_REFILL) continue;
			BlockPos source = vein.topVespene(level);
			if (source == null) continue;
			level.setBlock(source, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			vein.fill(level, outlet, true);
			vein.emptySince[i] = 0;
		}
		// slow growth: the outlets in turn, then the channel from the bottom
		if (vein.nextRegen == 0) vein.nextRegen = now + REGEN;
		if (now < vein.nextRegen) return;
		vein.nextRegen = now + REGEN;
		for (int k = 0; k < vein.outlets.length; k++) {
			int i = (vein.nextOutlet + k) % vein.outlets.length;
			BlockPos outlet = BlockPos.of(vein.outlets[i]);
			if (level.isLoaded(outlet) && level.getBlockState(outlet).is(Vespene.VENT)) {
				vein.fill(level, outlet, true);
				vein.nextOutlet = i + 1;
				return;
			}
		}
		for (long cell : vein.channel) {
			BlockPos at = BlockPos.of(cell);
			if (level.isLoaded(at) && level.getBlockState(at).isAir()) {
				vein.fill(level, at, false);
				return;
			}
		}
	}

	/** The highest vespene block of the channel (taken first: the vein empties from the top). */
	private BlockPos topVespene(Level level) {
		for (int i = channel.length - 1; i >= 0; i--) {
			BlockPos at = BlockPos.of(channel[i]);
			if (isVespene(level, at)) return at;
		}
		return null;
	}

	private void fill(Level level, BlockPos at, boolean outlet) {
		level.setBlock(at, Vespene.VESPENE.defaultBlockState().setValue(VespeneBlock.OUTLET, outlet), Block.UPDATE_ALL);
		setChanged();
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putLongArray("Channel", channel);
		tag.putLongArray("Outlets", outlets);
		tag.putInt("NextOutlet", nextOutlet);
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		channel = tag.getLongArray("Channel");
		outlets = tag.getLongArray("Outlets");
		nextOutlet = tag.getInt("NextOutlet");
		emptySince = new long[outlets.length];
	}
}
