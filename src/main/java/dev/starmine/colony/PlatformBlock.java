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
 * A race's block (CLOUD_TASK_2 stage 5; owner 17.10), crafted from a map amid 8 armor shards of a race. Owner 19.10:
 * no longer a platform building of resources — it crowns the founding pedestal, a pyramid of mineral blocks: a right
 * click on it calls the race's transport with a worker who founds the player's base there ({@link Pedestal}). A
 * platform block of an older world that still holds resources becomes the old platform building, with what it held,
 * when it is used ({@link Storage}); platform buildings already standing keep working.
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

	@Override
	protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
			BlockHitResult hit) {
		if (!stack.is(CommandPanel.ITEM)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION; // with anything else it opens
		if (level instanceof ServerLevel server) { // owner 22.10: the command panel lights the summoning beacon
			String why = Pedestal.use(server, pos, race, player);
			player.displayClientMessage(Component.translatable(why != null ? why : "message.starmine.beacon_lit", Pedestal.KEEP_OFF), true);
		}
		return ItemInteractionResult.sidedSuccess(level.isClientSide);
	}

	/** Owner 22.10: a resource crate stands at least {@link Pedestal#KEEP_OFF} blocks from minerals and vespene. */
	@Override
	public @Nullable BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
		if (!context.getLevel().isClientSide && Pedestal.nearResources(context.getLevel(), context.getClickedPos())) {
			if (context.getPlayer() != null)
				context.getPlayer().displayClientMessage(Component.translatable("message.starmine.crate_near_resources", Pedestal.KEEP_OFF), true);
			return null;
		}
		return super.getStateForPlacement(context);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (level instanceof ServerLevel server) use(server, pos, player);
		return InteractionResult.sidedSuccess(level.isClientSide);
	}

	/**
	 * Owner 22.10: a resource crate opens as its player's store, an ender chest's way: the store of their base in this
	 * world (the same in every crate and main building of it), only valuable things, at most 9999 of each; before the
	 * base, the crate's own store (it goes to the base when it is founded at the crate).
	 */
	private void use(ServerLevel level, BlockPos pos, Player player) {
		if (!(level.getBlockEntity(pos) instanceof PlatformBlockEntity crate)) return;
		Hive base = HiveSavedData.get(level.getServer()).baseOf(player.getUUID(), level.dimension().location().toString());
		Storage.Bank bank = base != null ? Storage.bankOf(level, base) : crate.bank();
		level.blockEvent(pos, this, 1, 1); // its top opens
		Storage.openBank(player, bank, Component.translatable(base != null ? "container.starmine.crate_base" : "container.starmine.crate_own", getName(),
			bank.minerals(), bank.vespene()), () -> level.blockEvent(pos, this, 1, 0));
	}

	@Override
	protected boolean triggerEvent(BlockState state, Level level, BlockPos pos, int id, int param) {
		super.triggerEvent(state, level, pos, id, param);
		BlockEntity entity = level.getBlockEntity(pos);
		return entity != null && entity.triggerEvent(id, param);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T extends BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		return level.isClientSide && type == ENTITY
			? (net.minecraft.world.level.block.entity.BlockEntityTicker<T>) (net.minecraft.world.level.block.entity.BlockEntityTicker<PlatformBlockEntity>) PlatformBlockEntity::clientTick
			: null;
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
