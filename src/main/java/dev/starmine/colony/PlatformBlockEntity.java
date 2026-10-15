package dev.starmine.colony;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** What a supply platform holds ({@link PlatformBlock}): minerals, vespene and the stock of each resource by label. */
public class PlatformBlockEntity extends BlockEntity {
	int minerals, vespene;
	final Map<String, Integer> stock = new LinkedHashMap<>();
	/** A transport is on its way: the platform takes nothing more. */
	boolean called;

	public PlatformBlockEntity(BlockPos pos, BlockState state) {
		super(PlatformBlock.ENTITY, pos, state);
	}

	public int minerals() {
		return minerals;
	}

	public int vespene() {
		return vespene;
	}

	public Map<String, Integer> stock() {
		return stock;
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putInt("minerals", minerals);
		tag.putInt("vespene", vespene);
		tag.putBoolean("called", called);
		CompoundTag held = new CompoundTag();
		stock.forEach(held::putInt);
		tag.put("stock", held);
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		minerals = tag.getInt("minerals");
		vespene = tag.getInt("vespene");
		called = tag.getBoolean("called");
		stock.clear();
		CompoundTag held = tag.getCompound("stock");
		for (String key : held.getAllKeys()) stock.put(key, held.getInt(key));
	}
}
