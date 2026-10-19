package dev.starmine.gear;

import com.mojang.serialization.MapCodec;
import dev.starmine.StarMine;
import dev.starmine.entity.UnitKind;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The races' chests (owner 19.10), three rows each:
 * - the zerg chest (eight zerg shards): an organic store, it heals as zerg armour does;
 * - the terran safe (a chest among eight terran shards): steel, blasts cannot open it;
 * - the protoss chest (a chest, mineral shards, protoss shards): its shield takes the first hits.
 * Each is its race's armour under wear ({@link dev.starmine.block.BlockWear}).
 */
public final class RaceChests {
	public static final Map<UnitKind.Race, Block> BLOCKS = new EnumMap<>(UnitKind.Race.class);
	public static final BlockEntityType<Store> STORE;

	static {
		for (UnitKind.Race race : UnitKind.Race.values()) {
			String name = race.name().toLowerCase(Locale.ROOT) + (race == UnitKind.Race.TERRAN ? "_safe" : "_chest");
			BlockBehaviour.Properties properties = BlockBehaviour.Properties.of()
				.strength(race == UnitKind.Race.TERRAN ? 25 : 5, race == UnitKind.Race.TERRAN ? 1200 : 6)
				.sound(race == UnitKind.Race.ZERG ? SoundType.SLIME_BLOCK : race == UnitKind.Race.TERRAN ? SoundType.NETHERITE_BLOCK : SoundType.AMETHYST)
				.lightLevel(state -> race == UnitKind.Race.PROTOSS ? 7 : 0);
			if (race == UnitKind.Race.TERRAN) properties = properties.requiresCorrectToolForDrops();
			Block block = Registry.register(BuiltInRegistries.BLOCK, StarMine.id(name), new Chest(properties));
			Registry.register(BuiltInRegistries.ITEM, StarMine.id(name), new BlockItem(block, new Item.Properties()));
			BLOCKS.put(race, block);
		}
		STORE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, StarMine.id("race_chest"),
			BlockEntityType.Builder.of(Store::new, BLOCKS.values().toArray(new Block[0])).build(null));
	}

	private RaceChests() {
	}

	public static void load() {
		BLOCKS.forEach((race, block) -> dev.starmine.block.ArmorBlocks.addRaceBlock(block, race));
		net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents.modifyEntriesEvent(net.minecraft.world.item.CreativeModeTabs.FUNCTIONAL_BLOCKS)
			.register(entries -> BLOCKS.values().forEach(entries::accept));
	}

	/** A race's chest: faces its placer, opens to three rows. */
	static final class Chest extends BaseEntityBlock {
		Chest(Properties properties) {
			super(properties);
			registerDefaultState(stateDefinition.any().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
		}

		@Override
		protected MapCodec<? extends BaseEntityBlock> codec() {
			return simpleCodec(Chest::new);
		}

		@Override
		protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
			builder.add(HorizontalDirectionalBlock.FACING);
		}

		@Override
		public BlockState getStateForPlacement(BlockPlaceContext context) {
			return defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, context.getHorizontalDirection().getOpposite());
		}

		@Override
		protected RenderShape getRenderShape(BlockState state) {
			return RenderShape.MODEL;
		}

		@Override
		public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
			return new Store(pos, state);
		}

		@Override
		protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
			if (level.isClientSide) return InteractionResult.SUCCESS;
			if (level.getBlockEntity(pos) instanceof Store store) {
				player.openMenu(store);
				level.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5f, 0.9f + level.random.nextFloat() * 0.1f);
			}
			return InteractionResult.CONSUME;
		}

		@Override
		protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState now, boolean moved) {
			if (!state.is(now.getBlock()) && level.getBlockEntity(pos) instanceof Store store) Containers.dropContents(level, pos, store);
			super.onRemove(state, level, pos, now, moved);
		}
	}

	/** What a race's chest holds: 27 slots. */
	public static final class Store extends RandomizableContainerBlockEntity {
		private NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);

		public Store(BlockPos pos, BlockState state) {
			super(STORE, pos, state);
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
			return 27;
		}

		@Override
		protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			super.saveAdditional(tag, registries);
			if (!trySaveLootTable(tag)) net.minecraft.world.ContainerHelper.saveAllItems(tag, items, registries);
		}

		@Override
		protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			super.loadAdditional(tag, registries);
			items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
			if (!tryLoadLootTable(tag)) net.minecraft.world.ContainerHelper.loadAllItems(tag, items, registries);
		}
	}
}
