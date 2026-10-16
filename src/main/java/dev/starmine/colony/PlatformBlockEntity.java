package dev.starmine.colony;

import dev.starmine.hive.HiveResources;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a supply platform holds ({@link PlatformBlock}). Owner 17.10: the platform is a chest of its race — 27 slots
 * opened like a chest, where the player puts ores, ingots, gems and vespene. What it holds counts as minerals,
 * vespene and a stock by resource label ({@link HiveResources#yieldOfItem}); what a call does not use ({@link #absorb}
 * turned it into numbers) stays here as numbers and goes to the base.
 */
public class PlatformBlockEntity extends BaseContainerBlockEntity {
	public static final int SIZE = 27;
	private NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
	/** Resources taken out of the slots (and from older worlds, where the platform held only numbers). */
	int minerals, vespene;
	final Map<String, Integer> stock = new LinkedHashMap<>();
	/** A transport is on its way: the platform takes nothing more. */
	boolean called;

	public PlatformBlockEntity(BlockPos pos, BlockState state) {
		super(PlatformBlock.ENTITY, pos, state);
	}

	/** Minerals it holds: in numbers and in its slots. */
	public int minerals() {
		int total = minerals;
		for (ItemStack stack : items) total += HiveResources.yieldOfItem(stack).minerals() * stack.getCount();
		return total;
	}

	public int vespene() {
		int total = vespene;
		for (ItemStack stack : items) total += HiveResources.yieldOfItem(stack).vespene() * stack.getCount();
		return total;
	}

	/** Its stock by resource label (iron 40, diamond 3…), in numbers and in its slots. */
	public Map<String, Integer> stock() {
		Map<String, Integer> total = new LinkedHashMap<>(stock);
		for (ItemStack stack : items) {
			HiveResources.Yield yield = HiveResources.yieldOfItem(stack);
			if (yield.label() != null) total.merge(yield.label(), stack.getCount(), Integer::sum);
		}
		return total;
	}

	/** The resources in its slots become numbers (a call is paid from them); other things stay in the slots. */
	void absorb() {
		for (int i = 0; i < items.size(); i++) {
			ItemStack stack = items.get(i);
			HiveResources.Yield yield = HiveResources.yieldOfItem(stack);
			if (stack.isEmpty() || !yield.any()) continue;
			minerals += yield.minerals() * stack.getCount();
			vespene += yield.vespene() * stack.getCount();
			if (yield.label() != null) stock.merge(yield.label(), stack.getCount(), Integer::sum);
			items.set(i, ItemStack.EMPTY);
		}
		setChanged();
	}

	/** Puts a stack in its slots as a chest would (merging, then empty slots); what did not fit is left in `stack`. */
	void insert(ItemStack stack) {
		for (int i = 0; i < items.size() && !stack.isEmpty(); i++) {
			ItemStack there = items.get(i);
			if (there.isEmpty()) {
				items.set(i, stack.split(Math.min(stack.getCount(), stack.getMaxStackSize())));
			} else if (ItemStack.isSameItemSameComponents(there, stack) && there.getCount() < there.getMaxStackSize()) {
				int moved = Math.min(stack.getCount(), there.getMaxStackSize() - there.getCount());
				there.grow(moved);
				stack.shrink(moved);
			}
		}
		setChanged();
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return !called && HiveResources.yieldOfItem(stack).any(); // hoppers: resources only
	}

	@Override
	protected Component getDefaultName() {
		return getBlockState().getBlock().getName();
	}

	@Override
	protected NonNullList<ItemStack> getItems() {
		return items;
	}

	@Override
	protected void setItems(NonNullList<ItemStack> items) {
		this.items = items;
	}

	@Override
	protected AbstractContainerMenu createMenu(int id, Inventory inventory) {
		return ChestMenu.threeRows(id, inventory, this);
	}

	@Override
	public int getContainerSize() {
		return SIZE;
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		ContainerHelper.saveAllItems(tag, items, registries);
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
		items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
		ContainerHelper.loadAllItems(tag, items, registries);
		minerals = tag.getInt("minerals");
		vespene = tag.getInt("vespene");
		called = tag.getBoolean("called");
		stock.clear();
		CompoundTag held = tag.getCompound("stock");
		for (String key : held.getAllKeys()) stock.put(key, held.getInt(key));
	}
}
