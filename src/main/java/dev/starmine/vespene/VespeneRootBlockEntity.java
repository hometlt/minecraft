package dev.starmine.vespene;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Technical vespene source: each geyser outlet has a persistent finite reserve of 1100 gas.
 * Channels and volcanoes are physical geometry; their size does not determine this reserve.
 * Void lakes retain their existing liquid regeneration.
 */
public final class VespeneRootBlockEntity extends BlockEntity {
	public static final int OUTLET_REFILL = 100, REGEN = 600, VOLCANO_TICKS = 3600, VOLCANO_HEIGHT = 4;
	public static final int RADIUS = 3, BASE_HEIGHT = 2;

	private long[] channel = new long[0], outlets = new long[0];
	private long[] emptySince = new long[0];
	private int nextOutlet;
	public static final int GAS_PER_OUTLET = 1100;
	private int[] remaining = new int[0];
	private long nextRegen;
	/** Blocks each outlet has risen as a volcano. */
	private int[] risen = new int[0];
	private long[] nextRise = new long[0];
	private final java.util.Set<Long> rebuild = new java.util.HashSet<>();
	/** Owner 17.10: a lake inside an island of the Void (no volcano, no cobblestone round it), and whether of terrazine. */
	private boolean lake, terrazine;
	/**
	 * Owner 22.10: which outlets are geysers (a gas building may stand on them); the others are a branch's end underground,
	 * a spot where vespene is taken by bucket only. An older world's veins have only geysers.
	 */
	private boolean[] geysers = new boolean[0];

	public VespeneRootBlockEntity(BlockPos pos, BlockState state) {
		super(Vespene.ROOT_ENTITY, pos, state);
	}

	/** Set by the world generation: the channel bottom-up and the outlets, every one a geyser. */
	public void setVein(List<BlockPos> channelCells, List<BlockPos> outletCells) {
		setVein(channelCells, outletCells, null);
	}

	/** {@link #setVein} with which outlets are geysers (null: all of them); the others are bucket spots. */
	public void setVein(List<BlockPos> channelCells, List<BlockPos> outletCells, @org.jetbrains.annotations.Nullable List<Boolean> geyserOutlets) {
		geysers = new boolean[outletCells.size()];
		for (int i = 0; i < geysers.length; i++) geysers[i] = geyserOutlets == null || i >= geyserOutlets.size() || geyserOutlets.get(i);
		rebuild.clear();
		channel = channelCells.stream().mapToLong(BlockPos::asLong).toArray();
		outlets = outletCells.stream().mapToLong(BlockPos::asLong).toArray();
		remaining = new int[outlets.length];
		java.util.Arrays.fill(remaining, GAS_PER_OUTLET);
		emptySince = new long[outlets.length];
		risen = new int[outlets.length];
		nextRise = new long[outlets.length];
		setChanged();
	}

	/** Makes this vein a lake (of terrazine or vespene). */
	public void setLake(boolean terrazine) {
		this.lake = true;
		this.terrazine = terrazine;
		setChanged();
	}

	public int outletCount() {
		return outlets.length;
	}

	/** Whether a gas building may stand on this outlet: a geyser, not a bucket spot (owner 22.10). */
	public boolean geyser(BlockPos outlet) {
		for (int k = 0; k < outlets.length; k++) if (outlets[k] == outlet.asLong()) return k >= geysers.length || geysers[k];
		return true;
	}

	public boolean lake() { return lake; }

	public int remaining(BlockPos outlet) {
		for (int i = 0; i < outlets.length; i++) if (outlets[i] == outlet.asLong()) return remaining[i];
		return 0;
	}

	/** The source block owns the budget; moving or replacing the refinery cannot refill it. */
	public int extract(Level level, BlockPos outlet, int wanted) {
		for (int i = 0; i < outlets.length; i++) if (outlets[i] == outlet.asLong()) {
			int taken = Math.min(remaining[i], Math.max(0, wanted));
			remaining[i] -= taken;
			sync(level);
			return taken;
		}
		return 0;
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

	/** Blocks this outlet has risen as a volcano (0: none, or not this vein's). */
	public int risen(BlockPos outlet) {
		for (int k = 0; k < outlets.length; k++) if (outlets[k] == outlet.asLong()) return risen[k];
		return 0;
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
		if (!vein.lake) vein.volcanoes(level, now);
		// emptied outlets are fed from the channel
		for (int i = 0; i < vein.outlets.length; i++) {
			BlockPos outlet = BlockPos.of(vein.outlets[i]);
			if (!level.isLoaded(outlet) || !level.getBlockState(outlet).is(Vespene.VENT)) {
				vein.emptySince[i] = 0;
				continue;
			}
			if (vein.emptySince[i] == 0) vein.emptySince[i] = now;
			if (now - vein.emptySince[i] < OUTLET_REFILL) continue;
			if (!vein.lake && vein.remaining[i] == 0) continue;
			BlockPos source = vein.topVespene(level);
			if (vein.lake && source == null) continue;
			if (source != null) level.setBlock(source, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			vein.fill(level, outlet, true);
			vein.emptySince[i] = 0;
		}
		if (!vein.lake) return;
		// slow growth: the outlets in turn, then the channel from the bottom
		if (vein.nextRegen == 0) vein.nextRegen = now + REGEN;
		if (now < vein.nextRegen) return;
		vein.nextRegen = now + REGEN;
		if (!vein.lake) vein.wall(level);
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
		BlockState cobble = Vespene.ROCK.defaultBlockState();
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
			if (level.isLoaded(outlet) && (risen[i] > MOUND || hasOversizedMound(level, outlet))) {
				// The previous version used SC2 cells here and produced 21x21x12 volcanoes.
				int cx = outlet.getX(), cz = outlet.getZ();
				if (!level.hasChunk((cx - 10) >> 4, (cz - 10) >> 4) || !level.hasChunk((cx + 10) >> 4, (cz - 10) >> 4)
					|| !level.hasChunk((cx - 10) >> 4, (cz + 10) >> 4) || !level.hasChunk((cx + 10) >> 4, (cz + 10) >> 4)) continue;
				collapse(level, outlet, true);
				rise(level, i);
				outlet = BlockPos.of(outlets[i]);
			}
			if (!level.isLoaded(outlet) || !isVespene(level, outlet) || i < geysers.length && !geysers[i]) { // a bucket spot does not rise
				nextRise[i] = 0;
				continue;
			}
			BlockPos up = outlet.above();
			boolean open = level.getBlockState(up).isAir()
				&& level.getEntitiesOfClass(dev.starmine.entity.StarUnit.class, new net.minecraft.world.phys.AABB(outlet).inflate(2, 3, 2),
					unit -> unit.kind.gas() && unit.isAlive()).isEmpty()
				&& !gasPending(level, outlet); // a generated base's gas building not yet out: its cone must not rise under it
			if (!open) {
				nextRise[i] = 0;
				continue;
			}
			if (level instanceof net.minecraft.server.level.ServerLevel server && level.random.nextInt(4) == 0)
				server.sendParticles(net.minecraft.core.particles.ParticleTypes.CAMPFIRE_COSY_SMOKE, up.getX() + 0.5, up.getY() + 0.2, up.getZ() + 0.5,
					1, 0.2, 0.1, 0.2, 0.01);
			if (risen[i] == 0 && !hasMound(level, outlet)) {
				if (nextRise[i] == 0) nextRise[i] = now + VOLCANO_TICKS;
				if (rebuild.contains(outlet.asLong()) || now >= nextRise[i]) rise(level, i);
			}
		}
	}

	/** A gas building waits to come out over this outlet (a generated base's, until its chunk ticks). */
	private static boolean gasPending(Level level, BlockPos outlet) {
		if (!(level instanceof net.minecraft.server.level.ServerLevel server)) return false;
		String dimension = level.dimension().location().toString();
		for (dev.starmine.hive.HiveStructure.Pending pending : dev.starmine.hive.HiveSavedData.get(server.getServer()).pending) {
			if (!pending.kind().gas() || !dimension.equals(pending.dimension() == null ? "minecraft:overworld" : pending.dimension())) continue;
			BlockPos at = pending.pos();
			if (Math.abs(at.getX() - outlet.getX()) <= RADIUS && Math.abs(at.getZ() - outlet.getZ()) <= RADIUS && Math.abs(at.getY() - outlet.getY()) <= MOUND + 4)
				return true;
		}
		return false;
	}

	/**
	 * Seals this vein's outlets within `radius` (across) of `centre` for good (owner 22.10: a generated base keeps the
	 * land's own geysers off): each one's cone and mound go, its opening is stone and its vespene is spent.
	 */
	public void retire(Level level, BlockPos centre, int radius) {
		boolean changed = false;
		for (int i = 0; i < outlets.length; i++) {
			BlockPos outlet = BlockPos.of(outlets[i]);
			double dx = outlet.getX() - centre.getX(), dz = outlet.getZ() - centre.getZ();
			if (dx * dx + dz * dz > (double) radius * radius || !level.isLoaded(outlet)) continue;
			BlockPos base = collapse(level, outlet);
			for (int y = 0; y <= 3; y++) {
				BlockPos at = base.below(y);
				if (level.isLoaded(at) && (VespeneBlock.isSource(level.getBlockState(at)) || level.getBlockState(at).is(Vespene.VENT) || level.getBlockState(at).is(Vespene.VESPENE)))
					level.setBlock(at, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
			}
			if (i < remaining.length) remaining[i] = 0;
			risen[i] = 0;
			changed = true;
		}
		if (changed) {
			rebuild.clear();
			setChanged();
			sync(level);
		}
	}

	/** Tests: build the complete volcano now. */
	void riseNow(Level level, int i) {
		rise(level, i);
	}

	/** Adopts only a fresh imported 7x7x4 geyser footprint; its absolute channel and outlet stay put. */
	public void adoptNativeVolcano(Level level, int i) {
		BlockPos top = outlet(i), base = top.below(MOUND);
		// The reserve is stored in this block entity, without carving a reservoir into map terrain.
		for (int y = 1; y <= MOUND; y++) for (int dx = -RADIUS; dx <= RADIUS; dx++) for (int dz = -RADIUS; dz <= RADIUS; dz++) {
			BlockPos at = base.offset(dx, y, dz);
			if (dx == 0 && dz == 0) fill(level, at, y == MOUND);
			else level.setBlock(at, inLayer(dx, y, dz) ? Vespene.ROCK.defaultBlockState() : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		}
		risen[i] = MOUND;
		sync(level);
	}

	/** Move the outlet to the top, extending its channel inside the compact cone. */
	private void rise(Level level, int i) {
		BlockPos base = baseOf(level, BlockPos.of(outlets[i]));
		rebuild.remove(base.asLong());
		BlockPos top = base.above(MOUND);
		java.util.LinkedHashSet<Long> cells = new java.util.LinkedHashSet<>();
		for (long cell : channel) cells.add(cell);
		for (int y = 0; y < MOUND; y++) cells.add(base.above(y).asLong());
		cells.remove(top.asLong());
		channel = cells.stream().mapToLong(Long::longValue).toArray();
		outlets[i] = top.asLong();
		risen[i] = MOUND;
		for (int y = 1; y <= MOUND; y++) {
			int r = radiusAt(y);
			for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
				if (!inLayer(dx, y, dz)) continue;
				BlockPos at = base.offset(dx, y, dz);
				if (!level.isLoaded(at)) continue;
				if (dx == 0 && dz == 0) fill(level, at, y == MOUND);
				else if (level.getBlockState(at).canBeReplaced() || level.getBlockState(at).isAir())
					level.setBlock(at, Vespene.ROCK.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
		fill(level, base, false);
		sync(level);
	}

	/** Minecraft blocks: two identical 7-wide layers, then 5-wide and 3-wide. */
	public static int radiusAt(int y) {
		return y <= BASE_HEIGHT ? RADIUS : Math.max(1, RADIUS - (int) Math.ceil((y - BASE_HEIGHT) * (RADIUS - 1.0) / (MOUND - BASE_HEIGHT)));
	}

	public static boolean inLayer(int dx, int y, int dz) {
		int radius = radiusAt(y);
		return Math.max(Math.abs(dx), Math.abs(dz)) <= radius && Math.abs(dx) + Math.abs(dz) <= radius + 1;
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
	 * A gas building put on a geyser takes all its cobblestone away (owner 17.10: a volcano's cone; owner 18.10: the
	 * geyser's mound too, down to its base): the outlet goes down to the ground the mound stands on, the column over
	 * it and every cobblestone of the mound and cone go, and the building stands on the ground. The outlet's position
	 * now. Left open again (the building destroyed) it rises once more as a volcano ({@link #volcanoes}).
	 */
	public BlockPos collapse(Level level, BlockPos outlet) {
		return collapse(level, outlet, false);
	}

	private BlockPos collapse(Level level, BlockPos outlet, boolean resizing) {
		int i = -1;
		for (int k = 0; k < outlets.length; k++) if (outlets[k] == outlet.asLong()) i = k;
		if (i < 0) return outlet;
		BlockPos base = baseOf(level, outlet);
		if (base.equals(outlet)) return outlet;
		java.util.Set<Long> column = new java.util.HashSet<>();
		for (int y = base.getY(); y <= outlet.getY(); y++) column.add(new BlockPos(base.getX(), y, base.getZ()).asLong());
		channel = java.util.Arrays.stream(channel).filter(cell -> !column.contains(cell)).toArray();
		int reach = coneReach(level, outlet);
		for (int y = base.getY() + 1; y <= outlet.getY() + 1; y++)
			for (int dx = -reach; dx <= reach; dx++)
				for (int dz = -reach; dz <= reach; dz++) {
					// The base is square on the SC2 grid.
					BlockPos at = new BlockPos(base.getX() + dx, y, base.getZ() + dz);
					if (!level.isLoaded(at)) continue;
					BlockState there = level.getBlockState(at);
					boolean wall = there.is(Vespene.ROCK) || !resizing && there.is(net.minecraft.world.level.block.Blocks.COBBLESTONE);
					boolean fluid = there.is(Vespene.VESPENE) || there.is(Vespene.TERRAZINE) || there.is(Vespene.VENT);
					if (wall || fluid && (!resizing || dx == 0 && dz == 0))
						level.setBlock(at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				}
		outlets[i] = base.asLong();
		rebuild.add(base.asLong());
		risen[i] = 0;
		nextRise[i] = 0;
		fill(level, base, true);
		sync(level);
		level.playSound(null, base, net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(), net.minecraft.sounds.SoundSource.BLOCKS, 0.6f, 1.2f);
		return base;
	}

	/**
	 * Where an outlet goes when its cobblestone is taken away ({@link #collapse}): under a volcano's rise and a mound's
	 * height, flush with the ground the trunk comes up through; the outlet itself when it has neither.
	 */
	public BlockPos baseOf(Level level, BlockPos outlet) {
		int rise = risen(outlet);
		if (rise > 0) return outlet.below(rise);
		if (hasOversizedMound(level, outlet)) return outlet.below(12);
		if (hasMound(level, outlet)) return outlet.below(MOUND);
		// Retain the ground of old saved cobblestone mounds.
		BlockPos ring = outlet.below(3);
		int legacy = 0;
		for (net.minecraft.core.Direction side : net.minecraft.core.Direction.Plane.HORIZONTAL)
			if (level.getBlockState(ring.relative(side, 4)).is(net.minecraft.world.level.block.Blocks.COBBLESTONE)) legacy++;
		return legacy >= 3 ? ring.below() : outlet;
	}

	public int coneReach(Level level, BlockPos outlet) {
		int height = outlet.getY() - baseOf(level, outlet).getY();
		return height > MOUND ? 10 : height > 0 ? Math.max(RADIUS, 4) : 0;
	}

	public static final int MOUND = VOLCANO_HEIGHT;

	private static boolean hasOversizedMound(Level level, BlockPos top) {
		BlockPos ring = top.below(11);
		int rock = 0;
		for (net.minecraft.core.Direction side : net.minecraft.core.Direction.Plane.HORIZONTAL)
			if (level.getBlockState(ring.relative(side, 10)).is(Vespene.ROCK)) rock++;
		return rock >= 3;
	}

	private static boolean hasMound(Level level, BlockPos top) {
		BlockPos ring = top.below(MOUND - 1);
		int rock = 0;
		for (net.minecraft.core.Direction side : net.minecraft.core.Direction.Plane.HORIZONTAL)
			if (level.getBlockState(ring.relative(side, RADIUS)).is(Vespene.ROCK)) rock++;
		return rock >= 3;
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
		level.setBlock(at, (terrazine ? Vespene.TERRAZINE : Vespene.VESPENE).defaultBlockState().setValue(VespeneBlock.OUTLET, outlet), Block.UPDATE_ALL);
		setChanged();
	}

	/** Owner 18.10: the client knows the vein (the command view shows a geyser's vespene left, {@link Vespene#gasLeft}). */
	private void sync(Level level) {
		setChanged();
		level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveWithoutMetadata(registries);
	}

	@Override
	public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
		return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putLongArray("Channel", channel);
		tag.putLongArray("Outlets", outlets);
		tag.putIntArray("RemainingVespene", remaining);
		tag.putInt("NextOutlet", nextOutlet);
		tag.putIntArray("Risen", risen);
		tag.putLongArray("Rebuild", rebuild.stream().mapToLong(Long::longValue).toArray());
		tag.putBoolean("Lake", lake);
		tag.putBoolean("Terrazine", terrazine);
		byte[] flags = new byte[geysers.length];
		for (int i = 0; i < flags.length; i++) flags[i] = (byte) (geysers[i] ? 1 : 0);
		tag.putByteArray("Geysers", flags);
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		channel = tag.getLongArray("Channel");
		outlets = tag.getLongArray("Outlets");
		byte[] flags = tag.getByteArray("Geysers"); // none in an older world: all geysers
		geysers = new boolean[outlets.length];
		for (int i = 0; i < geysers.length; i++) geysers[i] = i >= flags.length || flags[i] != 0;
		remaining = new int[outlets.length];
		java.util.Arrays.fill(remaining, GAS_PER_OUTLET);
		int[] saved = tag.getIntArray("RemainingVespene");
		for (int i = 0; i < Math.min(saved.length, remaining.length); i++) remaining[i] = Math.clamp(saved[i], 0, GAS_PER_OUTLET);
		nextOutlet = tag.getInt("NextOutlet");
		emptySince = new long[outlets.length];
		risen = java.util.Arrays.copyOf(tag.getIntArray("Risen"), outlets.length);
		rebuild.clear();
		for (long base : tag.getLongArray("Rebuild")) rebuild.add(base);
		nextRise = new long[outlets.length];
		lake = tag.getBoolean("Lake");
		terrazine = tag.getBoolean("Terrazine");
	}
}
