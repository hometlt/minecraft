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
 * A supply platform (CLOUD_TASK_2 stage 5): how a survival player gets a main building. Crafted from a
 * map amid 8 armor shards of a race (the race of the platform); the player puts resources in it (right
 * click with them: what workers bring and a main building takes — raw ores, ingots, gems, vespene); once
 * it holds a price of `data/starmine/costs/platform.json` (500 minerals, or 500 iron, or 100 diamonds),
 * a right click with the command panel calls a transport of its race (overlord, medivac, warp prism). It
 * flies in with a worker, puts it down on the platform and flies off; the platform is gone. The worker is
 * the player's and puts the race's main building there its race's way (the call paid for it):
 * {@link dev.starmine.race.RaceRules#placeBuilding} for the base {@link Founding} would found — the
 * player's base in this dimension, a fresh one when they have none; what else the platform held goes to it.
 */
public final class PlatformBlock extends Block implements EntityBlock {
	public static final Map<UnitKind.Race, PlatformBlock> BLOCKS = new EnumMap<>(UnitKind.Race.class);
	public static final BlockEntityType<PlatformBlockEntity> ENTITY;
	private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 6, 16);
	/** Where the transport comes from: this far aside and up. */
	static final double FROM = 18, FROM_UP = 14;
	/** Ticks a transport may take to come; then it is put over the platform. Ticks it flies off before it is gone. */
	static final int ARRIVE_TICKS = 1200, LEAVE_TICKS = 60;

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
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(PlatformBlock::serverTick);
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
		boolean panel = stack.is(CommandPanel.ITEM);
		if (!panel && !HiveResources.yieldOfItem(stack).any()) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (level instanceof ServerLevel server) {
			String said = panel ? call(server, pos, player) : put(server, pos, player, stack);
			if (said != null) player.displayClientMessage(Component.translatable(said, contents(level, pos)), true);
		}
		return ItemInteractionResult.sidedSuccess(level.isClientSide);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!level.isClientSide) player.displayClientMessage(Component.translatable("message.starmine.platform_holds", contents(level, pos)), true);
		return InteractionResult.sidedSuccess(level.isClientSide);
	}

	/** "500 minerals, 0 vespene, iron 12, diamond 3". */
	static String contents(Level level, BlockPos pos) {
		if (!(level.getBlockEntity(pos) instanceof PlatformBlockEntity platform)) return "";
		StringBuilder text = new StringBuilder(platform.minerals + " / " + platform.vespene);
		platform.stock.forEach((label, count) -> text.append(", ").append(label).append(' ').append(count));
		return text.toString();
	}

	/** The whole stack in the hand into the platform; the message's lang key (what it holds now). */
	public static @Nullable String put(ServerLevel level, BlockPos pos, Player player, ItemStack stack) {
		if (!(level.getBlockEntity(pos) instanceof PlatformBlockEntity platform)) return null;
		if (platform.called) return "message.starmine.platform_called";
		HiveResources.Yield yield = HiveResources.yieldOfItem(stack);
		if (!yield.any()) return null;
		int count = stack.getCount();
		platform.minerals += yield.minerals() * count;
		platform.vespene += yield.vespene() * count;
		if (yield.label() != null) platform.stock.merge(yield.label(), count, Integer::sum);
		platform.setChanged();
		if (!player.getAbilities().instabuild) stack.shrink(count);
		level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 1.0f, 0.8f);
		return "message.starmine.platform_holds";
	}

	/** The first price of `costs/platform.json` the platform holds, or null. */
	static @Nullable Costs.Cost price(PlatformBlockEntity platform) {
		Map<String, Costs.Cost> prices = Costs.loaded("platform");
		Costs.Cost minerals = prices.get("minerals");
		List<Costs.Cost> order = new ArrayList<>();
		if (minerals != null) order.add(minerals);
		prices.forEach((id, cost) -> {
			if (cost != minerals) order.add(cost);
		});
		for (Costs.Cost cost : order) {
			if (platform.minerals < cost.minerals() || platform.vespene < cost.vespene()) continue;
			boolean all = true;
			for (Map.Entry<String, Integer> material : cost.materials().entrySet())
				if (platform.stock.getOrDefault(material.getKey(), 0) < material.getValue()) all = false;
			if (all) return cost;
		}
		return null;
	}

	/** A right click with the command panel: the transport is called when the platform holds a price; else why not (a lang key). */
	public static @Nullable String call(ServerLevel level, BlockPos pos, Player player) {
		if (!(level.getBlockEntity(pos) instanceof PlatformBlockEntity platform) || !(level.getBlockState(pos).getBlock() instanceof PlatformBlock block))
			return null;
		if (platform.called) return "message.starmine.platform_called";
		Costs.Cost price = price(platform);
		if (price == null) return "message.starmine.platform_short";
		platform.minerals -= price.minerals();
		platform.vespene -= price.vespene();
		price.materials().forEach((label, count) -> platform.stock.merge(label, -count, Integer::sum));
		platform.stock.values().removeIf(count -> count <= 0);
		platform.called = true;
		platform.setChanged();
		UnitKind.Race race = block.race;
		StarUnit transport = StarMine.type(transportOf(race)).create(level), worker = StarMine.type(UnitKind.workerOf(race)).create(level);
		if (transport == null || worker == null) return null;
		double angle = level.random.nextDouble() * Math.PI * 2;
		Vec3 from = Vec3.atBottomCenterOf(pos).add(Math.cos(angle) * FROM, FROM_UP, Math.sin(angle) * FROM);
		transport.moveTo(from.x, from.y, from.z, 0, 0);
		transport.setOwner(player.getUUID());
		worker.moveTo(from.x, from.y, from.z, 0, 0);
		worker.setOwner(player.getUUID());
		level.addFreshEntity(transport);
		level.addFreshEntity(worker);
		worker.startRiding(transport, true);
		transport.command(StarUnit.Order.MOVE, Vec3.atBottomCenterOf(pos));
		CALLS.add(new Call(level.dimension(), pos.immutable(), player.getUUID(), race, transport.getUUID(), worker.getUUID(), level.getGameTime()));
		return "message.starmine.platform_calling";
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

	// ---- the transport's flight ----

	private static final class Call {
		final ResourceKey<Level> dimension;
		final BlockPos pos;
		final UUID player;
		final UnitKind.Race race;
		final UUID transport, worker;
		long since;
		boolean landed;

		Call(ResourceKey<Level> dimension, BlockPos pos, UUID player, UnitKind.Race race, UUID transport, UUID worker, long since) {
			this.dimension = dimension;
			this.pos = pos;
			this.player = player;
			this.race = race;
			this.transport = transport;
			this.worker = worker;
			this.since = since;
		}
	}

	private static final List<Call> CALLS = new ArrayList<>();

	/** The calls' transports: in to the platform, the worker down, off and gone. */
	static void serverTick(MinecraftServer server) {
		for (Iterator<Call> it = CALLS.iterator(); it.hasNext(); ) {
			Call call = it.next();
			ServerLevel level = server.getLevel(call.dimension);
			if (level == null || !(level.getEntity(call.transport) instanceof StarUnit transport) || !transport.isAlive()) {
				it.remove();
				continue;
			}
			long now = level.getGameTime();
			if (call.landed) {
				if (now - call.since > LEAVE_TICKS) {
					transport.discard();
					it.remove();
				}
				continue;
			}
			Vec3 site = Vec3.atBottomCenterOf(call.pos);
			if (now - call.since > ARRIVE_TICKS) transport.teleportTo(site.x, transport.getY(), site.z); // it could not come: it is there
			if (Math.hypot(transport.getX() - site.x, transport.getZ() - site.z) > 2.5) {
				if (transport.order() != StarUnit.Order.MOVE) transport.command(StarUnit.Order.MOVE, site);
				continue;
			}
			Transport.unloadHere(transport);
			call.landed = true;
			call.since = now;
			transport.command(StarUnit.Order.MOVE, site.add(FROM, FROM_UP * 2, 0));
			if (level.getEntity(call.worker) instanceof StarUnit worker && worker.isAlive()) land(level, call, worker);
		}
	}

	/** The worker is down: the platform goes, its base gets what it held, the worker puts the main building there. */
	private static void land(ServerLevel level, Call call, StarUnit worker) {
		PlatformBlockEntity platform = level.getBlockEntity(call.pos) instanceof PlatformBlockEntity held ? held : null;
		HiveSavedData data = HiveSavedData.get(level.getServer());
		String dimension = level.dimension().location().toString();
		Hive base = data.baseOf(call.player, dimension);
		boolean fresh = base == null;
		if (fresh) {
			base = new Hive(UUID.randomUUID(), dimension, call.pos);
			base.race = call.race;
			base.owner = call.player;
			base.minerals = 0;
			data.add(base);
		}
		if (platform != null) {
			base.minerals += platform.minerals;
			base.vespene += platform.vespene;
			for (Map.Entry<String, Integer> held : platform.stock.entrySet()) base.collected.merge(held.getKey(), held.getValue(), Integer::sum);
		}
		level.removeBlock(call.pos, false);
		worker.joinHive(base);
		UnitKind main = mainOf(call.race);
		base.refund(main, 1); // the call paid for it
		StarUnit building = call.race.rules().placeBuilding(worker, level, base, main, call.pos);
		var player = level.getServer().getPlayerList().getPlayer(call.player);
		if (building == null) {
			base.pay(main, 1);
			if (player != null) player.displayClientMessage(Component.translatable("message.starmine.site_blocked"), true);
		} else if (player != null) {
			player.sendSystemMessage(Component.translatable(fresh ? "message.starmine.base_founded" : "message.starmine.expansion"));
		}
		data.setDirty();
	}
}
