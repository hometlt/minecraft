package dev.starmine.colony;

import dev.starmine.StarMine;
import dev.starmine.ability.Transport;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveResources;
import dev.starmine.hive.HiveSavedData;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The supply platform as an item and a block (CLOUD_TASK_2 stage 5; owner 17.10). Crafted from a map amid 8 armor
 * shards of a race; placed, it becomes the platform building of its race ({@link UnitKind#platformOf}, a chest of
 * the base's resources that monsters and the wild races attack): all it does is in {@link Storage}. A platform
 * block of an older world (a chest of resources) becomes the building, with what it held, when it is used.
 */
public final class PlatformBlock extends Block implements EntityBlock {
	public static final Map<UnitKind.Race, PlatformBlock> BLOCKS = new EnumMap<>(UnitKind.Race.class);
	public static final BlockEntityType<PlatformBlockEntity> ENTITY;
	private static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 14, 15);

	static {
		for (UnitKind.Race race : UnitKind.Race.values()) {
			String name = race.name().toLowerCase() + "_platform";
			PlatformBlock block = Registry.register(BuiltInRegistries.BLOCK, StarMine.id(name), new PlatformBlock(race,
				BlockBehaviour.Properties.of().strength(3.0f, 6.0f).sound(race == UnitKind.Race.ZERG ? SoundType.SLIME_BLOCK : SoundType.METAL).noOcclusion()));
			Registry.register(BuiltInRegistries.ITEM, StarMine.id(name), new BlockItem(block, new Item.Properties().stacksTo(1)));
			BLOCKS.put(race, block);
		}
		ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, StarMine.id("platform"),
			BlockEntityType.Builder.of(PlatformBlockEntity::new, BLOCKS.values().toArray(new Block[0])).build(null));
	}

	public final UnitKind.Race race;

	private PlatformBlock(UnitKind.Race race, Properties properties) {
		super(properties);
		this.race = race;
	}

	public static void load() {
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(entries -> BLOCKS.values().forEach(entries::accept));
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new PlatformBlockEntity(pos, state);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	/** Placed: the platform building of the player's stands there. */
	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
		if (level instanceof ServerLevel server) Storage.fromBlock(server, pos, race, placer instanceof Player player ? player.getUUID() : null);
	}

	/** An older world's platform block, used: it becomes the building (with what it held), its user's. */
	@Override
	protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
			BlockHitResult hit) {
		if (level instanceof ServerLevel server) Storage.fromBlock(server, pos, race, player.getUUID());
		return ItemInteractionResult.sidedSuccess(level.isClientSide);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (level instanceof ServerLevel server) Storage.fromBlock(server, pos, race, player.getUUID());
		return InteractionResult.sidedSuccess(level.isClientSide);
	}

	/** Broken: what is in its slots falls out, as from a chest. */
	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
		net.minecraft.world.Containers.dropContentsOnDestroy(state, newState, level, pos);
		super.onRemove(state, level, pos, newState, moved);
	}

	public static UnitKind transportOf(UnitKind.Race race) {
		return switch (race) {
			case ZERG -> UnitKind.OVERLORD;
			case TERRAN -> UnitKind.MEDIVAC;
			case PROTOSS -> UnitKind.WARP_PRISM;
		};
	}

	public static UnitKind mainOf(UnitKind.Race race) {
		return switch (race) {
			case ZERG -> UnitKind.HATCHERY;
			case TERRAN -> UnitKind.COMMAND_CENTER;
			case PROTOSS -> UnitKind.NEXUS;
		};
	}
}
