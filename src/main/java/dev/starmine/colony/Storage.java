package dev.starmine.colony;

import dev.starmine.StarMine;
import dev.starmine.ability.Transport;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveResources;
import dev.starmine.hive.HiveSavedData;
import dev.starmine.mineral.Minerals;
import dev.starmine.vespene.Vespene;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The resources of a player's base and where they are kept (owner 17.10). Workers bring what they gather to the
 * nearest storage building of their base — a main building or a supply platform ({@link UnitKind#storage}) —
 * and everything there is one bank: the base's minerals and vespene. Every storage building opens as a chest of
 * that bank (minerals shown as mineral shards, vespene in the title): anything valuable laid in is turned into
 * minerals and vespene by its worth ({@link HiveResources#yieldOfItem}), shards taken out are minerals taken out.
 *
 * The supply platform is a building of its race (crafted, placed like a block; attacked by monsters and the wild
 * races like any building). Before its player has a base it keeps its own bank; with {@link #WORKER_PRICE}
 * minerals in it, a right click with the command panel calls a transport of its race with a worker. The worker
 * founds the player's base at the platform (or joins the base they have) and gathers; the main building is the
 * player's to build.
 */
public final class Storage {
	/** Minerals a worker from a platform costs (owner 17.10: exactly 100 minerals). */
	public static final int WORKER_PRICE = 100;
	/** Chest rows of a storage building's view. */
	static final int ROWS = 6;
	/** Where the transport comes from: this far aside and up; ticks it may take; ticks it flies off before it is gone. */
	static final double FROM = 18, FROM_UP = 14;
	static final int ARRIVE_TICKS = 1200, LEAVE_TICKS = 60;

	private Storage() {
	}

	public static void load() {
		net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> interact(player, level, hand, entity));
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(Storage::serverTick);
	}

	// ---- the bank ----

	/** A storage building's bank: its base's minerals and vespene, or a platform's own while it has no base. */
	public interface Bank {
		int minerals();

		int vespene();

		void add(int minerals, int vespene);
	}

	public static Bank bankOf(ServerLevel level, StarUnit storage) {
		Hive hive = storage.hiveId() == null ? null : HiveSavedData.get(level.getServer()).get(storage.hiveId());
		if (hive != null) return new Bank() {
			public int minerals() {
				return hive.minerals;
			}

			public int vespene() {
				return hive.vespene;
			}

			public void add(int minerals, int vespene) {
				hive.minerals += minerals;
				hive.vespene += vespene;
				hive.capStore(); // owner 22.10: up to 9999 of each
				HiveSavedData.get(level.getServer()).setDirty();
			}
		};
		return new Bank() {
			public int minerals() {
				return storage.bankMinerals;
			}

			public int vespene() {
				return storage.bankVespene;
			}

			public void add(int minerals, int vespene) {
				storage.bankMinerals += minerals;
				storage.bankVespene += vespene;
			}
		};
	}

	/** Whether the player may use this storage building: their own (by its base or by the platform's owner). */
	static boolean ownedBy(ServerLevel level, StarUnit storage, Player player) {
		if (storage.owner().map(player.getUUID()::equals).orElse(false)) return true;
		Hive hive = storage.hiveId() == null ? null : HiveSavedData.get(level.getServer()).get(storage.hiveId());
		return hive != null && player.getUUID().equals(hive.owner);
	}

	// ---- a player at a storage building ----

	/**
	 * A right click on a storage building of the player's: the command panel on a platform calls a worker; a
	 * valuable stack goes into the bank; anything else opens the bank as a chest.
	 */
	public static InteractionResult interact(Player player, Level level, InteractionHand hand, Entity entity) {
		if (!(entity instanceof StarUnit storage) || !storage.kind.storage() || storage.underConstruction() || player.isSpectator())
			return InteractionResult.PASS;
		if (player instanceof ServerPlayer server && dev.starmine.possess.Possession.piloting(server)) return InteractionResult.PASS;
		ItemStack stack = player.getItemInHand(hand);
		if (level.isClientSide) return InteractionResult.SUCCESS;
		ServerLevel server = (ServerLevel) level;
		if (!ownedBy(server, storage, player)) return InteractionResult.PASS;
		if (stack.is(CommandPanel.ITEM)) {
			if (!storage.kind.platform()) return InteractionResult.PASS; // the panel's own use (the command view)
			String said = call(server, storage, player);
			if (said != null) player.displayClientMessage(Component.translatable(said, Math.max(0, bankOf(server, storage).minerals()), workerPrice()), true);
			return InteractionResult.SUCCESS;
		}
		if (stack.is(Vespene.CANISTER_ITEM)) {
			int buckets = dev.starmine.vespene.VespeneCanisterBlock.fill(stack);
			if (buckets > 0) {
				int vespene = buckets * Vespene.PER_TRIP;
				bankOf(server, storage).add(0, vespene);
				dev.starmine.vespene.VespeneCanisterBlock.setFill(stack, 0);
				player.displayClientMessage(Component.translatable("message.starmine.handed_in", buckets, stack.getHoverName(), 0, vespene), true);
				storage.playSound(SoundEvents.BUCKET_EMPTY, 1.0f, 0.8f);
				return InteractionResult.SUCCESS;
			}
		}
		HiveResources.Yield yield = HiveResources.yieldOfItem(stack);
		if (yield.any() && !player.isShiftKeyDown()) {
			int count = stack.getCount();
			deposit(server, storage, yield, count);
			player.displayClientMessage(Component.translatable("message.starmine.handed_in", count, stack.getHoverName(),
				yield.minerals() * count, yield.vespene() * count), true);
			if (!player.getAbilities().instabuild) {
				boolean bucket = stack.is(Vespene.BUCKET);
				stack.shrink(count);
				if (bucket) player.setItemInHand(hand, new ItemStack(Items.BUCKET)); // the bucket is kept
			}
			storage.playSound(SoundEvents.ITEM_PICKUP, 1.0f, 0.8f);
			return InteractionResult.SUCCESS;
		}
		if (player.isShiftKeyDown() && stack.isEmpty() && Exchange.open(server, storage, player)) return InteractionResult.SUCCESS; // owner 22.10
		open(server, storage, player);
		return InteractionResult.SUCCESS;
	}

	/** A stack of something valuable into the bank (counted as gathered by the base too). */
	static void deposit(ServerLevel level, StarUnit storage, HiveResources.Yield yield, int count) {
		Hive hive = storage.hiveId() == null ? null : HiveSavedData.get(level.getServer()).get(storage.hiveId());
		if (hive != null) {
			hive.deliver(yield.minerals() * count, yield.vespene() * count, yield.diamonds() * count, yield.label(), count);
			HiveSavedData.get(level.getServer()).setDirty();
		} else {
			bankOf(level, storage).add(yield.minerals() * count, yield.vespene() * count);
		}
	}

	/** A base's bank (its minerals and vespene, at most 9999 of each for a player's). */
	static Bank bankOf(ServerLevel level, Hive hive) {
		return new Bank() {
			public int minerals() {
				return hive.minerals;
			}

			public int vespene() {
				return hive.vespene;
			}

			public void add(int minerals, int vespene) {
				hive.minerals += minerals;
				hive.vespene += vespene;
				hive.capStore();
				HiveSavedData.get(level.getServer()).setDirty();
			}
		};
	}

	/** Any bank as a chest (a resource crate's, owner 22.10); `closed` runs when it is shut. */
	static void openBank(Player player, Bank bank, Component title, Runnable closed) {
		player.openMenu(new SimpleMenuProvider((id, inventory, who) -> {
			View view = new View(bank, who);
			view.closed = closed;
			return ChestMenu.sixRows(id, inventory, view);
		}, title));
	}

	/** The bank as a chest: mineral shards for its minerals; the title says the minerals and vespene. */
	static void open(ServerLevel level, StarUnit storage, Player player) {
		Bank bank = bankOf(level, storage);
		Component title = Component.translatable("container.starmine.bank", storage.getDisplayName(), bank.minerals(), bank.vespene());
		player.openMenu(new SimpleMenuProvider((id, inventory, who) -> ChestMenu.sixRows(id, inventory, new View(bank, who)), title));
	}

	/**
	 * A chest view of a bank. It shows the minerals as mineral shards; what changes in it changes the bank by the
	 * difference (workers may deliver while it is open): shards taken out are minerals taken, anything valuable laid
	 * in counts by its worth. Closed, what was laid in stays in the bank — vespene buckets come back empty.
	 */
	static final class View extends SimpleContainer {
		private final Bank bank;
		private final Player viewer;
		private int minerals, vespene;
		private boolean filling;
		/** Runs when the view is shut (a resource crate closes its top). */
		Runnable closed = () -> {
		};

		View(Bank bank, Player viewer) {
			super(ROWS * 9);
			this.bank = bank;
			this.viewer = viewer;
			filling = true;
			int shards = Math.max(0, bank.minerals()) / Minerals.PER_TRIP;
			for (int slot = 0; slot < getContainerSize() && shards > 0; slot++) {
				int n = Math.min(64, shards);
				setItem(slot, new ItemStack(Minerals.SHARD, n));
				shards -= n;
			}
			filling = false;
			minerals = worth()[0];
			vespene = worth()[1];
		}

		private int[] worth() {
			int m = 0, v = 0;
			for (int i = 0; i < getContainerSize(); i++) {
				ItemStack stack = getItem(i);
				HiveResources.Yield yield = HiveResources.yieldOfItem(stack);
				m += yield.minerals() * stack.getCount();
				v += yield.vespene() * stack.getCount();
			}
			return new int[] {m, v};
		}

		@Override
		public boolean canPlaceItem(int slot, ItemStack stack) {
			return HiveResources.yieldOfItem(stack).any();
		}

		@Override
		public void setChanged() {
			super.setChanged();
			if (filling) return;
			int[] now = worth();
			bank.add(now[0] - minerals, now[1] - vespene);
			minerals = now[0];
			vespene = now[1];
		}

		@Override
		public void stopOpen(Player player) {
			super.stopOpen(player);
			closed.run();
			// what was laid in is the bank's now; a vespene bucket's bucket goes back to the player
			for (int i = 0; i < getContainerSize(); i++) {
				ItemStack stack = getItem(i);
				if (stack.is(Vespene.BUCKET)) viewer.getInventory().placeItemBackInInventory(new ItemStack(Items.BUCKET, stack.getCount()));
			}
		}
	}

	// ---- a worker from a platform ----

	private record Call(ResourceKey<Level> dimension, UUID platform, UUID player, UUID transport, UUID worker, long[] since, boolean[] landed) {
	}

	private static final List<Call> CALLS = new ArrayList<>();
	private static final Set<UUID> CALLED = new HashSet<>();

	/** The command panel on a platform: with {@link #WORKER_PRICE} minerals in its bank a transport brings a worker; else why not (a lang key). */
	public static @Nullable String call(ServerLevel level, StarUnit platform, Player player) {
		if (CALLED.contains(platform.getUUID())) return "message.starmine.platform_called";
		Bank bank = bankOf(level, platform);
		if (bank.minerals() < workerPrice()) return "message.starmine.platform_short";
		bank.add(-workerPrice(), 0);
		UnitKind.Race race = platform.kind.race;
		StarUnit transport = StarMine.type(PlatformBlock.transportOf(race)).create(level), worker = StarMine.type(UnitKind.workerOf(race)).create(level);
		if (transport == null || worker == null) return null;
		double angle = level.random.nextDouble() * Math.PI * 2;
		Vec3 from = platform.position().add(Math.cos(angle) * FROM, FROM_UP, Math.sin(angle) * FROM);
		transport.moveTo(from.x, from.y, from.z, 0, 0);
		transport.setOwner(player.getUUID());
		worker.moveTo(from.x, from.y, from.z, 0, 0);
		worker.setOwner(player.getUUID());
		level.addFreshEntity(transport);
		level.addFreshEntity(worker);
		worker.startRiding(transport, true);
		transport.command(StarUnit.Order.MOVE, landing(platform));
		CALLED.add(platform.getUUID());
		CALLS.add(new Call(level.dimension(), platform.getUUID(), player.getUUID(), transport.getUUID(), worker.getUUID(), new long[] {level.getGameTime()},
			new boolean[1]));
		return "message.starmine.platform_calling";
	}

	/** A worker's price in minerals: `costs/platform.json` "worker", by default {@link #WORKER_PRICE}. */
	public static int workerPrice() {
		Costs.Cost cost = Costs.loaded("platform").get("worker");
		return cost != null ? cost.minerals() : WORKER_PRICE;
	}

	/** Where the worker is put down: beside the platform. */
	private static Vec3 landing(StarUnit platform) {
		return platform.position().add(platform.kind.width / 2 + 2, 0, 0);
	}

	/** The calls' transports: in to the platform, the worker down, off and gone. */
	static void serverTick(MinecraftServer server) {
		for (Iterator<Call> it = CALLS.iterator(); it.hasNext(); ) {
			Call call = it.next();
			ServerLevel level = server.getLevel(call.dimension);
			if (level == null || !(level.getEntity(call.transport) instanceof StarUnit transport) || !transport.isAlive()) {
				CALLED.remove(call.platform);
				it.remove();
				continue;
			}
			long now = level.getGameTime();
			if (call.landed[0]) {
				if (now - call.since[0] > LEAVE_TICKS) {
					transport.discard();
					CALLED.remove(call.platform);
					it.remove();
				}
				continue;
			}
			StarUnit platform = level.getEntity(call.platform) instanceof StarUnit p && p.isAlive() ? p : null;
			Vec3 site = platform != null ? landing(platform) : transport.position();
			if (now - call.since[0] > ARRIVE_TICKS) transport.teleportTo(site.x, transport.getY(), site.z); // it could not come: it is there
			if (Math.hypot(transport.getX() - site.x, transport.getZ() - site.z) > 2.5) {
				if (transport.order() != StarUnit.Order.MOVE) transport.command(StarUnit.Order.MOVE, site);
				continue;
			}
			Transport.unloadHere(transport);
			call.landed[0] = true;
			call.since[0] = now;
			transport.command(StarUnit.Order.MOVE, site.add(FROM, FROM_UP * 2, 0));
			if (level.getEntity(call.worker) instanceof StarUnit worker && worker.isAlive()) arrive(level, call.player, platform, worker);
		}
	}

	/** The worker is down: it joins the player's base — founded at the platform when they have none. */
	private static void arrive(ServerLevel level, UUID player, @Nullable StarUnit platform, StarUnit worker) {
		HiveSavedData data = HiveSavedData.get(level.getServer());
		String dimension = level.dimension().location().toString();
		Hive base = data.baseOf(player, dimension);
		boolean fresh = base == null;
		if (fresh) base = found(level, player, worker.kind.race, platform != null ? platform.blockPosition() : worker.blockPosition());
		if (platform != null && platform.hiveId() == null) join(level, platform, base);
		worker.joinHive(base);
		data.setDirty();
		ServerPlayer who = level.getServer().getPlayerList().getPlayer(player);
		if (who != null) who.sendSystemMessage(Component.translatable(fresh ? "message.starmine.base_founded_platform" : "message.starmine.worker_arrived"));
	}

	/** A player's new base at a platform: no main building yet, no minerals but the platform's. */
	static Hive found(ServerLevel level, UUID player, UnitKind.Race race, BlockPos at) {
		Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), at);
		base.race = race;
		base.owner = player;
		base.minerals = 0;
		HiveSavedData.get(level.getServer()).add(base);
		return base;
	}

	/** A platform becomes a storage of the base: its own bank goes to the base's. */
	static void join(ServerLevel level, StarUnit platform, Hive base) {
		base.minerals += platform.bankMinerals;
		base.vespene += platform.bankVespene;
		platform.bankMinerals = 0;
		platform.bankVespene = 0;
		platform.joinHive(base);
		base.join(platform.kind);
		HiveSavedData.get(level.getServer()).setDirty();
	}

	/** Each second of a platform: a platform of no base joins its owner's base in this dimension once they have one. */
	public static void tickPlatform(StarUnit platform, ServerLevel level) {
		if (platform.hiveId() != null || platform.owner().isEmpty()) return;
		Hive base = HiveSavedData.get(level.getServer()).baseOf(platform.owner().get(), level.dimension().location().toString());
		if (base != null) join(level, platform, base);
	}

	/** A platform block (placed, or of an older world) becomes the platform building, with what the block held. */
	public static @Nullable StarUnit fromBlock(ServerLevel level, BlockPos pos, UnitKind.Race race, @Nullable UUID owner) {
		int minerals = 0, vespene = 0;
		if (level.getBlockEntity(pos) instanceof PlatformBlockEntity held) {
			minerals = held.minerals();
			vespene = held.vespene();
			held.clearContent(); // its slots are in the bank now
		}
		level.removeBlock(pos, false);
		StarUnit platform = StarMine.type(UnitKind.platformOf(race)).create(level);
		if (platform == null) return null;
		Vec3 at = Vec3.atBottomCenterOf(pos);
		platform.moveTo(at.x, at.y, at.z, 0, 0);
		platform.setOwner(owner);
		platform.bankMinerals = minerals;
		platform.bankVespene = vespene;
		platform.setPersistenceRequired();
		level.addFreshEntity(platform);
		if (owner != null) tickPlatform(platform, level);
		return platform;
	}
}
