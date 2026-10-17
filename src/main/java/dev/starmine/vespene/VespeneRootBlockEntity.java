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
 *   in the channel from the bottom up, until the vein is full again;
 * - cobblestone grows round the vein wherever nothing else is (air, water: a cave it passes through);
 * - an outlet open to the sky, full, with nothing built over it, rises slowly like a volcano (owner 16.10):
 *   every {@link #VOLCANO_TICKS} one block up, at most {@link #VOLCANO_HEIGHT}, a cone of cobblestone
 *   growing round it. A gas building on it or blocks over it stop it.
 * Only full (source) cells count: vespene that has flowed into an emptied cell is not taken or counted.
 */
public final class VespeneRootBlockEntity extends BlockEntity {
	public static final int OUTLET_REFILL = 100, REGEN = 600, VOLCANO_TICKS = 3600, VOLCANO_HEIGHT = 14;

	private long[] channel = new long[0], outlets = new long[0];
	private long[] emptySince = new long[0];
	private int nextOutlet;
	private long nextRegen;
	/** Blocks each outlet has risen as a volcano. */
	private int[] risen = new int[0];
	private long[] nextRise = new long[0];

	public VespeneRootBlockEntity(BlockPos pos, BlockState state) {
		super(Vespene.ROOT_ENTITY, pos, state);
	}

	/** Set by the world generation: the channel bottom-up and the outlets. */
	public void setVein(List<BlockPos> channelCells, List<BlockPos> outletCells) {
		channel = channelCells.stream().mapToLong(BlockPos::asLong).toArray();
		outlets = outletCells.stream().mapToLong(BlockPos::asLong).toArray();
		emptySince = new long[outlets.length];
		risen = new int[outlets.length];
		nextRise = new long[outlets.length];
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
		return level.isLoaded(pos) && VespeneBlock.isSource(level.getBlockState(pos));
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, VespeneRootBlockEntity vein) {
		long now = level.getGameTime();
		if ((now + pos.hashCode()) % 20 != 0) return;
		if (vein.emptySince.length != vein.outlets.length) vein.emptySince = new long[vein.outlets.length];
		if (vein.risen.length != vein.outlets.length) vein.risen = java.util.Arrays.copyOf(vein.risen, vein.outlets.length);
		if (vein.nextRise.length != vein.outlets.length) vein.nextRise = new long[vein.outlets.length];
		vein.volcanoes(level, now);
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
		vein.wall(level);
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
			BlockState there = level.isLoaded(at) ? level.getBlockState(at) : null;
			if (there != null && (there.isAir() || there.is(Vespene.VESPENE) && !VespeneBlock.isSource(there))) {
				vein.fill(level, at, false);
				return;
			}
		}
	}

	/** Cobblestone round the vein where nothing is (air or water beside a cell; not over an outlet, not in the vein). */
	private void wall(Level level) {
		java.util.Set<Long> vein = new java.util.HashSet<>();
		for (long cell : channel) vein.add(cell);
		for (long cell : outlets) vein.add(cell);
		vein.add(getBlockPos().asLong());
		BlockState cobble = net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState();
		for (long cell : vein) {
			BlockPos at = BlockPos.of(cell);
			boolean outlet = java.util.Arrays.stream(outlets).anyMatch(o -> o == cell);
			for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
				if (outlet && side == net.minecraft.core.Direction.UP) continue;
				BlockPos next = at.relative(side);
				if (vein.contains(next.asLong()) || !level.isLoaded(next)) continue;
				if (side == net.minecraft.core.Direction.UP && level.canSeeSky(next)) continue; // the open top of a geyser
				BlockState there = level.getBlockState(next);
				if (there.isAir() || there.is(net.minecraft.world.level.block.Blocks.WATER)) level.setBlock(next, cobble, Block.UPDATE_ALL);
			}
		}
	}

	/** Outlets open to the sky rise as volcanoes, slowly, unless something stands or is built on them. */
	private void volcanoes(Level level, long now) {
		for (int i = 0; i < outlets.length; i++) {
			BlockPos outlet = BlockPos.of(outlets[i]);
			if (risen[i] >= VOLCANO_HEIGHT || !level.isLoaded(outlet) || !isVespene(level, outlet)) {
				nextRise[i] = 0;
				continue;
			}
			BlockPos up = outlet.above();
			boolean open = level.getBlockState(up).isAir() && level.canSeeSky(up)
				&& level.getEntitiesOfClass(dev.starmine.entity.StarUnit.class, new net.minecraft.world.phys.AABB(outlet).inflate(2, 3, 2),
					unit -> unit.kind.gas()).isEmpty();
			if (!open) {
				nextRise[i] = 0;
				continue;
			}
			if (level instanceof net.minecraft.server.level.ServerLevel server && level.random.nextInt(4) == 0)
				server.sendParticles(net.minecraft.core.particles.ParticleTypes.CAMPFIRE_COSY_SMOKE, up.getX() + 0.5, up.getY() + 0.2, up.getZ() + 0.5,
					1, 0.2, 0.1, 0.2, 0.01);
			if (nextRise[i] == 0) nextRise[i] = now + VOLCANO_TICKS;
			if (now < nextRise[i]) continue;
			nextRise[i] = now + VOLCANO_TICKS;
			rise(level, i);
		}
	}

	/** Tests: the outlet `i` rises a block now, as a volcano does in time. */
	void riseNow(Level level, int i) {
		rise(level, i);
	}

	/** An outlet one block up: the old one joins the channel, cobblestone grows round the new one in a cone. */
	private void rise(Level level, int i) {
		BlockPos old = BlockPos.of(outlets[i]), top = old.above();
		channel = java.util.Arrays.copyOf(channel, channel.length + 1);
		channel[channel.length - 1] = old.asLong();
		outlets[i] = top.asLong();
		risen[i]++;
		BlockState cobble = net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState();
		for (int k = 0; k <= risen[i] + 2; k++) {
			int y = top.getY() - k;
			double radius = 1 + k * 0.75;
			int r = (int) Math.ceil(radius);
			for (int dx = -r; dx <= r; dx++)
				for (int dz = -r; dz <= r; dz++) {
					if (dx == 0 && dz == 0 || dx * dx + dz * dz > radius * radius + 1) continue;
					BlockPos at = new BlockPos(top.getX() + dx, y, top.getZ() + dz);
					if (!level.isLoaded(at)) continue;
					BlockState there = level.getBlockState(at);
					if (there.isAir() || there.canBeReplaced() && !there.is(Vespene.VESPENE)) level.setBlock(at, cobble, Block.UPDATE_ALL);
				}
		}
		fill(level, old, false);
		fill(level, top, true);
		level.playSound(null, top, net.minecraft.sounds.SoundEvents.LAVA_POP, net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 0.6f);
	}

	/** The vein an outlet belongs to (its root is at the bedrock within a chunk of it), or null. */
	public static @org.jetbrains.annotations.Nullable VespeneRootBlockEntity veinOf(Level level, BlockPos outlet) {
		long key = outlet.asLong();
		for (int cx = (outlet.getX() >> 4) - 1; cx <= (outlet.getX() >> 4) + 1; cx++)
			for (int cz = (outlet.getZ() >> 4) - 1; cz <= (outlet.getZ() >> 4) + 1; cz++) {
				if (!level.hasChunk(cx, cz)) continue;
				for (BlockEntity entity : level.getChunk(cx, cz).getBlockEntities().values())
					if (entity instanceof VespeneRootBlockEntity vein)
						for (long cell : vein.outlets) if (cell == key) return vein;
			}
		return null;
	}

	/**
	 * Owner 17.10: a gas building put on a volcano destroys it — the cobblestone cone and the risen column go, the
	 * outlet is back at the ground where it rose from, and the building stands there. The outlet's position now.
	 */
	public BlockPos collapse(Level level, BlockPos outlet) {
		int i = -1;
		for (int k = 0; k < outlets.length; k++) if (outlets[k] == outlet.asLong()) i = k;
		if (i < 0 || risen[i] == 0) return outlet;
		int r = risen[i];
		BlockPos ground = outlet.below(r);
		java.util.Set<Long> column = new java.util.HashSet<>();
		for (int k = 1; k <= r; k++) column.add(ground.above(k).asLong());
		channel = java.util.Arrays.stream(channel).filter(cell -> !column.contains(cell)).toArray();
		// the cone: cobblestone round the column above the ground (the column's vespene too)
		int reach = (int) Math.ceil(1 + (r + 2) * 0.75);
		for (int y = ground.getY() + 1; y <= outlet.getY() + 1; y++)
			for (int dx = -reach; dx <= reach; dx++)
				for (int dz = -reach; dz <= reach; dz++) {
					BlockPos at = new BlockPos(ground.getX() + dx, y, ground.getZ() + dz);
					if (!level.isLoaded(at)) continue;
					BlockState there = level.getBlockState(at);
					if (there.is(net.minecraft.world.level.block.Blocks.COBBLESTONE) || there.is(Vespene.VESPENE) || there.is(Vespene.VENT))
						level.setBlock(at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				}
		outlets[i] = ground.asLong();
		risen[i] = 0;
		nextRise[i] = 0;
		fill(level, ground, true);
		level.playSound(null, ground, net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(), net.minecraft.sounds.SoundSource.BLOCKS, 0.6f, 1.2f);
		return ground;
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
		tag.putIntArray("Risen", risen);
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		channel = tag.getLongArray("Channel");
		outlets = tag.getLongArray("Outlets");
		nextOutlet = tag.getInt("NextOutlet");
		emptySince = new long[outlets.length];
		risen = java.util.Arrays.copyOf(tag.getIntArray("Risen"), outlets.length);
		nextRise = new long[outlets.length];
	}
}
